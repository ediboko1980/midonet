// Copyright 2013 Midokura Inc.

package org.midonet.midolman

import akka.util.Duration
import akka.dispatch.{Promise, Future}
import akka.actor._
import akka.event.LoggingReceive
import collection.{immutable, mutable}
import scala.collection.JavaConverters._
import scala.compat.Platform
import mutable.PriorityQueue
import collection.mutable.{HashMap, MultiMap}
import java.util.UUID
import java.util.concurrent.{TimeoutException, TimeUnit}
import javax.annotation.Nullable
import javax.inject.Inject

import com.yammer.metrics.core.{Gauge, MetricsRegistry, Clock}
import org.slf4j.LoggerFactory

import org.midonet.cache.Cache
import org.midonet.cluster.DataClient
import org.midonet.midolman.datapath.ErrorHandlingCallback
import org.midonet.midolman.guice.datapath.DatapathModule.SIMULATION_THROTTLING_GUARD
import org.midonet.midolman.guice.CacheModule.{NAT_CACHE, TRACE_MESSAGES,
                                               TRACE_INDEX}
import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.monitoring.metrics.PacketPipelineMeter
import org.midonet.midolman.monitoring.metrics.PacketPipelineHistogram
import org.midonet.midolman.monitoring.metrics.PacketPipelineGauge
import org.midonet.midolman.monitoring.metrics.PacketPipelineCounter
import org.midonet.midolman.monitoring.metrics.PacketPipelineAccumulatedTime
import org.midonet.midolman.rules.Condition
import org.midonet.midolman.topology.TraceConditionsManager
import org.midonet.midolman.topology.VirtualTopologyActor
import org.midonet.netlink.exceptions.NetlinkException
import org.midonet.odp.protos.OvsDatapathConnection
import org.midonet.odp.{FlowMatches, Datapath, FlowMatch, Packet}
import org.midonet.odp.flows.FlowAction
import org.midonet.packets.Ethernet
import org.midonet.util.collection.RingBuffer
import org.midonet.util.throttling.ThrottlingGuard
import org.midonet.util.BatchCollector
import org.midonet.midolman.PacketWorkflow.{PipelinePath, WildcardTableHit, PacketToPortSet, Simulation}
import org.midonet.midolman.topology.rcu.TraceConditions


object DeduplicationActor extends Referenceable {
    override val Name = "DeduplicationActor"

    // Messages
    case class HandlePackets(packet: Array[Packet])

    case class ApplyFlow(actions: Seq[FlowAction[_]], cookie: Option[Int])

    case class DiscardPacket(cookie: Int)

    /* This message is sent by simulations that result in packets being
     * generated that in turn need to be simulated before they can correctly
     * be forwarded. */
    case class EmitGeneratedPacket(egressPort: UUID, eth: Ethernet,
                                   parentCookie: Option[Int] = None)

    case object _ExpireCookies


    class PacketPipelineMetrics(val registry: MetricsRegistry,
                                val throttler: ThrottlingGuard) {
        val pendedPackets = registry.newCounter(
            classOf[PacketPipelineGauge], "currentPendedPackets")

        val wildcardTableHits = registry.newMeter(
            classOf[PacketPipelineMeter],
            "wildcardTableHits", "packets",
            TimeUnit.SECONDS)

        val packetsToPortSet = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsToPortSet", "packets",
            TimeUnit.SECONDS)

        val packetsSimulated = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsSimulated", "packets",
            TimeUnit.SECONDS)

        val packetsProcessed = registry.newMeter(
            classOf[PacketPipelineMeter],
            "packetsProcessed", "packets",
            TimeUnit.SECONDS)

        // FIXME(guillermo) - make this a meter - the throttler needs to expose a callback
        val packetsDropped = registry.newGauge(
            classOf[PacketPipelineCounter],
            "packetsDropped",
            new Gauge[Long]{
                override def value = throttler.numDroppedTokens()
            })

        val liveSimulations = registry.newGauge(
            classOf[PacketPipelineGauge],
            "liveSimulations",
            new Gauge[Long]{
                override def value = throttler.numTokens()
            })

        val wildcardTableHitLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "wildcardTableHitLatency")

        val packetToPortSetLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "packetToPortSetLatency")

        val simulationLatency = registry.newHistogram(
            classOf[PacketPipelineHistogram], "simulationLatency")

        val wildcardTableHitAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "wildcardTableHitAccumulatedTime")

        val packetToPortSetAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "packetToPortSetAccumulatedTime")

        val simulationAccumulatedTime = registry.newCounter(
            classOf[PacketPipelineAccumulatedTime], "simulationAccumulatedTime")


        def wildcardTableHit(latency: Int) {
            wildcardTableHits.mark()
            wildcardTableHitLatency.update(latency)
            wildcardTableHitAccumulatedTime.inc(latency)
        }

        def packetToPortSet(latency: Int) {
            packetsToPortSet.mark()
            packetToPortSetLatency.update(latency)
            packetToPortSetAccumulatedTime.inc(latency)
        }

        def packetSimulated(latency: Int) {
            packetsSimulated.mark()
            simulationLatency.update(latency)
            simulationAccumulatedTime.inc(latency)
        }
    }
}

class DeduplicationActor extends Actor with ActorLogWithoutPath with
        UserspaceFlowActionTranslator with SuspendedPacketQueue {

    import DeduplicationActor._

    @Inject var datapathConnection: OvsDatapathConnection = null
    @Inject var clusterDataClient: DataClient = null
    @Inject @Nullable @NAT_CACHE var connectionCache: Cache = null
    @Inject @TRACE_MESSAGES var traceMessageCache: Cache = null
    @Inject @TRACE_INDEX var traceIndexCache: Cache = null
    var traceConditions = immutable.Seq[Condition]()

    @Inject
    @SIMULATION_THROTTLING_GUARD
    var throttler: ThrottlingGuard = null

    @Inject
    var metricsRegistry: MetricsRegistry = null

    var datapath: Datapath = null
    var dpState: DatapathState = null

    implicit val dispatcher = this.context.dispatcher
    implicit val system = this.context.system

    private var cookieId = 0

    // data structures to handle the duplicate packets.
    protected val cookieToDpMatch = HashMap[Int, FlowMatch]()
    protected val dpMatchToCookie = HashMap[FlowMatch, Int]()
    protected val cookieToPendedPackets: MultiMap[Int, Packet] =
        new HashMap[Int, mutable.Set[Packet]] with MultiMap[Int, Packet]

    protected val cookieTimeToLiveMillis  = 10000L
    protected val cookieExpirationCheckIntervalMillis  = 5000L

    private val cookieExpirations: PriorityQueue[(FlowMatch, Long)] =
        new PriorityQueue[(FlowMatch, Long)]()(
            Ordering.by[(FlowMatch,Long), Long](_._2).reverse)

    var metrics: PacketPipelineMetrics = null

    private sealed class GetConditionListFromVta { }

    override def preStart() {
        super.preStart()
        metrics = new PacketPipelineMetrics(metricsRegistry, throttler)
        // Defer this until actor start-up finishes, so that the VTA
        // will have an actor (ie, self) in 'sender' to send replies to.
        self ! new GetConditionListFromVta
    }

    override def receive = super.receive orElse LoggingReceive {
        case _: GetConditionListFromVta =>
            val vta = VirtualTopologyActor.getRef()
            log.debug("Subscribing to VTA {} for ConditionListRequests.", vta)
            vta.tell(VirtualTopologyActor.ConditionListRequest(
                         TraceConditionsManager.uuid, true))

        case DatapathController.DatapathReady(dp, state) =>
            if (null == datapath) {
                datapath = dp
                dpState = state
                installPacketInHook()
                log.info("Datapath hook installed")
                val expInterval = Duration(cookieExpirationCheckIntervalMillis,
                                           TimeUnit.MILLISECONDS)
                context.system.scheduler.schedule(expInterval, expInterval,
                                                  self, _ExpireCookies)
             }

        case HandlePackets(packets) =>
            /* Use an array and a while loop (as opposed to a list and a for loop
             * to minimize garbage generation (for loops are closures, while loops
             * are not).
             */
            var i = 0
            while (i < packets.length && packets(i) != null) {
                handlePacket(packets(i))
                i += 1
            }

        case ApplyFlow(actions, cookieOpt) => cookieOpt foreach { cookie =>
            cookieToPendedPackets.remove(cookie) foreach { pendedPackets =>
                // NOTE: tokens are claimed at the netlink layer when
                //       a packet is sent up to the DDA.
                //
                // they are released using cookieToPendedPackets as a marker,
                // thus in these situations:
                //
                //  - ApplyFlow is received for a cookie present in
                //    cookieToPendedPackets
                //  - A cookie expires and is removed from cookieToPendedPackets
                //  - A packet is pended, meaning that cookieToPendedPackets
                //    is already populated for this flowMatch
                throttler.tokenOut()

                cookieToDpMatch.remove(cookie) foreach {
                    dpMatch => dpMatchToCookie.remove(dpMatch)
                }

                // Send all pended packets with the same action list (unless
                // the action list is empty, which is equivalent to dropping)
                if (actions.length > 0) {
                    for (unpendedPacket <- pendedPackets) {
                        executePacket(cookie, unpendedPacket, actions)
                        metrics.pendedPackets.dec()
                    }
                } else {
                    metrics.packetsProcessed.mark(pendedPackets.size)
                }
            }

            if (actions.length == 0) {
                context.system.eventStream.publish(DiscardPacket(cookie))
            }
        }

        // This creates a new PacketWorkflow and
        // executes the simulation method directly.
        case EmitGeneratedPacket(egressPort, ethernet, parentCookie) =>
            val packet = new Packet().setPacket(ethernet)
            val packetId = scala.util.Random.nextLong()
            startWorkflow(workflow(packet, Right(egressPort)))

        case TraceConditions(newTraceConditions) =>
            log.debug("traceConditions updated to {}", newTraceConditions)
            traceConditions = newTraceConditions

        case DeduplicationActor._ExpireCookies => {
            val now = Platform.currentTime
            while (cookieExpirations.size > 0 && cookieExpirations.head._2 < now) {
                val (flowMatch, _) = cookieExpirations.dequeue()
                dpMatchToCookie.remove(flowMatch) match {
                    case Some(cookie) =>
                        log.warning("Expiring cookie:{}", cookie)
                        cookieToPendedPackets.remove(cookie) foreach {
                            // See comment in ApplyFlow to understand the rules
                            // we follow to release tokens from the throttling
                            // guard
                            _ => throttler.tokenOut()
                        }
                        cookieToDpMatch.remove(cookie)
                    case None =>
                }
            }
        }
    }

    protected def workflow(packet: Packet, cookieOrEgressPort: Either[Int, UUID],
                           parentCookie: Option[Int] = None): PacketHandler = {
        log.debug("Creating new PacketWorkflow for {}", cookieOrEgressPort)
        new PacketWorkflow(datapathConnection, dpState,
            datapath, clusterDataClient, connectionCache,
            traceMessageCache, traceIndexCache, packet,
            cookieOrEgressPort, parentCookie, traceConditions)
    }

    protected def startWorkflow(pw: PacketHandler): Future[PipelinePath] = {
        val resultFuture = pw.start()

        resultFuture onComplete {
            case Right(path) =>
                log.debug("Packet with {} processed.", pw.cookieStr)
                pw.cookie match {
                    case None => // do nothing
                    case Some(c) =>
                        val latency = (Clock.defaultClock().tick() -
                                      pw.packet.getStartTimeNanos).toInt
                        metrics.packetsProcessed.mark()
                        path match {
                            case WildcardTableHit => metrics.wildcardTableHit(latency)
                            case PacketToPortSet => metrics.packetToPortSet(latency)
                            case Simulation => metrics.packetSimulated(latency)
                            case _ =>
                        }
                }
            case Left(ex) =>
                log.warning("Exception while processing packet {} - {}, {}",
                    pw.cookieStr, ex.getMessage, ex.getStackTraceString)
                pw.cookie foreach { _ => metrics.packetsProcessed.mark() }
        }
    }

    private def handlePacket(packet: Packet) {
        log.debug("Handling packet with match {}", packet.getMatch)
        dpMatchToCookie.get(packet.getMatch) match {
            case None =>
                // If there is no match on the match, create a new cookie
                // and a new PacketWorkflow to handle the packet
                val cookie = nextCookieId
                log.debug("make new cookie #{} for new match: {}",
                    cookie, packet.getMatch)

                dpMatchToCookie.put(packet.getMatch, cookie)
                cookieToDpMatch.put(cookie, packet.getMatch)
                scheduleCookieExpiration(packet.getMatch)
                cookieToPendedPackets.put(cookie, mutable.Set.empty)
                startWorkflow(workflow(packet, Left(cookie)))

            case Some(cookie) =>
                // Simulation in progress. Just pend the packet.
                log.debug("A matching packet with cookie {} is already " +
                    "being handled", cookie)
                throttler.tokenOut()
                cookieToPendedPackets.addBinding(cookie, packet)
                metrics.pendedPackets.inc()
        }
    }

    private def scheduleCookieExpiration(flowMatch: FlowMatch) {
        cookieExpirations += ((flowMatch, Platform.currentTime + cookieTimeToLiveMillis))
    }

    private def executePacket(cookie: Int,
                              packet: Packet,
                              actions: Seq[FlowAction[_]]) {
        packet.setActions(actions.asJava)
        if (packet.getMatch.isUserSpaceOnly)
            applyActionsAfterUserspaceMatch(packet)

        if (packet.getActions.size > 0) {
            log.debug("Sending pended packet {} for cookie {}",
                packet, cookie)

            datapathConnection.packetsExecute(datapath, packet,
                new ErrorHandlingCallback[java.lang.Boolean] {
                    def onSuccess(data: java.lang.Boolean) {
                        metrics.packetsProcessed.mark()
                    }

                    def handleError(ex: NetlinkException, timeout: Boolean) {
                        log.error(ex,
                            "Failed to send a packet {} due to {}",
                            packet,
                            if (timeout) "timeout" else "error")
                        metrics.packetsProcessed.mark()
                    }
                })
        }
    }

    private def installPacketInHook() = {
         log.info("Installing packet in handler in the DDA")
         datapathConnection.datapathsSetNotificationHandler(datapath,
                 new BatchCollector[Packet] {
                     val BATCH_SIZE = 16
                     var packets = new Array[Packet](BATCH_SIZE)
                     var cursor = 0
                     val log = LoggerFactory.getLogger("PacketInHook")

                     override def endBatch() {
                         if (cursor > 0) {
                             log.trace("batch of {} packets", cursor)
                             self ! HandlePackets(packets)
                             packets = new Array[Packet](BATCH_SIZE)
                             cursor = 0
                         }
                     }

                     override def submit(data: Packet) {
                         log.trace("accumulating packet: {}", data.getMatch)
                         val eth = data.getPacket
                         FlowMatches.addUserspaceKeys(eth, data.getMatch)
                         data.setStartTimeNanos(Clock.defaultClock().tick())
                         packets(cursor) = data
                         cursor += 1
                         if (cursor == BATCH_SIZE)
                             endBatch()
                     }
         }).get()
    }

    /* Increment the cookie id number and return the value. Since this method
     * is called in the actor receive block, it is not thread safe. */
    private def nextCookieId: Int = {
        cookieId += 1
        cookieId
    }

}
