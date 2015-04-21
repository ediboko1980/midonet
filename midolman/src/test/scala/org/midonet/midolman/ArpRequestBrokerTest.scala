/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.midolman

import java.util.{UUID, ArrayDeque}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Success

import akka.actor.ActorSystem
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.CreateMode
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.concurrent.Eventually._

import org.midonet.cluster.ClusterRouterManager.ArpCacheImpl
import org.midonet.cluster.client.{RouterPort, ArpCache}
import org.midonet.midolman.DeduplicationActor.EmitGeneratedPacket
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.state.ArpRequestBroker._
import org.midonet.midolman.simulation._
import org.midonet.midolman.state._
import org.midonet.midolman.state.ArpRequestBroker._
import org.midonet.odp.{FlowMatches, Packet, FlowMatch}
import org.midonet.packets._
import org.midonet.packets.util.PacketBuilder
import org.midonet.midolman.util.ArpCacheHelper
import org.midonet.odp.flows.FlowKeys
import org.midonet.sdn.flows.{WildcardMatch, FlowTagger}
import org.midonet.sdn.flows.FlowTagger.FlowTag
import org.midonet.util.eventloop.{TryCatchReactor, Reactor}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class ArpRequestBrokerTest extends Suite
                             with BeforeAndAfter
                             with BeforeAndAfterAll
                             with ShouldMatchers {
    private final val ARP_RETRY = 1 * 1000
    private final val ARP_TIMEOUT = 6 * 1000
    private final val ARP_STALE = 100 * 1000
    private final val ARP_EXPIRATION = 200 * 1000

    implicit def strToIpv4(str: String): IPv4Addr = IPv4Addr.fromString(str)

    def uniqueRoot = "/test-" + UUID.randomUUID().toString
    var routerId: UUID = _

    var ts: TestingServer = _
    var directory: Directory = _
    var zkConn: ZkConnection = _
    var arpTable: ArpTable = _
    var arpCache: ArpCache = _
    var reactor: Reactor = _

    var remoteDir: Directory = _
    var remoteZkConn: ZkConnection = _
    var remoteArpTable: ArpTable = _
    var remoteArpCache: ArpCache = _
    var remoteReactor: Reactor = _

    var packetsEmitted = 0

    private val THEIR_IP = "180.0.1.1"
    private val NW_ADDR = "180.0.1.0"
    private val NW_CIDR = "180.0.1.2/24"
    private val MY_IP = "180.0.1.2"
    private val MY_MAC = MAC.fromString("02:0a:08:06:04:02")
    private val THEIR_MAC = MAC.random()

    val port = new RouterPort
    port.id = UUID.randomUUID()
    port.deviceID = routerId
    port.portMac = MY_MAC
    port.portAddr = IPv4Subnet.fromCidr(NW_CIDR)

    var router: Router = _

    implicit var actorSystem: ActorSystem = _
    implicit def ec: ExecutionContext = actorSystem.dispatcher

    var emitter: PacketEmitter = _
    val arps = new ArrayDeque[EmitGeneratedPacket]()
    val invalidations = new ArrayDeque[FlowTag]()
    var arpBroker: ArpRequestBroker = _

    var emittedPackets = new ArrayDeque[EmitGeneratedPacket]()

    val clock = UnixClock.MOCK

    implicit def packetContext: PacketContext = {
        val frame = Ethernet.random()
        val fmatch = FlowMatches.fromEthernetPacket(frame)
        val wmatch = WildcardMatch.fromEthernetPacket(frame)
        val context = new PacketContext(Left(-1), new Packet(frame, fmatch),
                                        None, wmatch, arpBroker)
        context
    }

    override def beforeAll() {
        ts = new TestingServer
        ts.start()
        reactor = new TryCatchReactor("test-arp-req-broker", 1)
        remoteReactor = new TryCatchReactor("test-arp-req-broker-remote", 1)
        actorSystem = ActorSystem()
        zkConn = new ZkConnection(ts.getConnectString, 10000, null)
        remoteZkConn = new ZkConnection(ts.getConnectString, 10000, null)
        zkConn.open()
        remoteZkConn.open()
        directory = new ZkDirectory(zkConn, "", null, reactor)
        remoteDir = new ZkDirectory(zkConn, "", null, reactor)
    }

    override def afterAll() {
        zkConn.close()
        ts.stop()
        reactor.shutDownNow()
        actorSystem.shutdown()
        actorSystem = null
    }

    before {
        clock.time = 0L
        routerId = UUID.randomUUID()
        val root = "/test-" + routerId.toString
        directory.add(root, "".getBytes, CreateMode.PERSISTENT)

        arpTable = new ArpTable(directory.getSubDirectory(root))
        arpTable.start()

        remoteArpTable = new ArpTable(directory.getSubDirectory(root))
        remoteArpTable.start()

        arpCache = new ArpCacheImpl(arpTable, routerId, reactor)
        remoteArpCache = new ArpCacheImpl(arpTable, routerId, reactor)
        arps.clear()
        invalidations.clear()

        emitter = (pkt) => arps.add(pkt)

        val invalidator: InvalidationSource = (t) => invalidations.add(t)

        arpBroker = new ArpRequestBroker(emitter, invalidator, (delay) => {},
            ARP_RETRY/1000, ARP_TIMEOUT/1000, ARP_STALE/1000, ARP_EXPIRATION/1000,
            clock)
        router = new Router(routerId, null, null, null, arpCache)(actorSystem)
    }

    after {
        arpTable.stop()
        remoteArpTable.stop()
        arpTable = null
        remoteArpTable = null
        arpCache = null
        remoteArpCache = null
    }

    private def expectEmitArp() {
        import PacketBuilder._
        arps should not be 'empty
        val pkt = arps.poll()
        pkt.egressPort should === (port.id)
        val arpReq: Ethernet = { eth addr MY_MAC -> eth_bcast } <<
            { arp.req mac MY_MAC -> eth_zero ip MY_IP --> THEIR_IP}
        pkt.eth should === (arpReq)
        arps should be ('empty)
    }

    private def addMoreWaiters(waiters: List[Future[MAC]], howMany: Int = 10): List[Future[MAC]] = {
        if (howMany > 0) {
            val NotYetException(macFuture, _) = intercept[NotYetException] {
                arpBroker.get(THEIR_IP, port, router)
            }
            addMoreWaiters(macFuture.asInstanceOf[Future[MAC]] :: waiters, howMany-1)
        } else {
            waiters
        }
    }

    def testUnrepliedArpLoop(): Unit = {
        val timeout = clock.time + ARP_TIMEOUT

        var futures = addMoreWaiters(List.empty)

        expectEmitArp()
        for (f <- futures)
            f should not be 'completed

        arpBroker.process()
        arps should be ('empty)

        var retryNo: Int = -1
        def loopDelta() = {
            retryNo += 1
            ((RETRY_MAX_BASE_JITTER + RETRY_JITTER_INCREMENT * retryNo) * ARP_RETRY).toLong
        }

        // go past the timeout by more than 1 retry interval
        while (clock.time < timeout + ARP_RETRY*2) {
            if (clock.time < timeout) {
                for (f <- futures)
                    f should not be 'completed
                futures = addMoreWaiters(futures)
            }

            clock.time += loopDelta()
            arpBroker.process()

            if (clock.time < timeout) {
                arps.size should be > 0
                arps.size should be <= 2
                arps.clear()
            } else {
                arps should be ('empty)
            }
        }

        for (f <- futures) {
            f should be ('completed)
            f.value.get should be ('failure)
        }
    }

    def testLocallyRepliedArpLoop(): Unit = {
        val timeout = clock.time + ARP_TIMEOUT

        var futures = addMoreWaiters(List.empty)
        arpBroker.process()

        while (clock.time < timeout/2) {
            for (i <- 1 to 4) {
                clock.time += ARP_RETRY / 2
                futures = addMoreWaiters(futures)
                arpBroker.process()
            }
        }

        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually {
            arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC)
        }

        arpBroker.process()
        for (f <- futures) {
            f.value should be (Some(Success(THEIR_MAC)))
        }
    }

    def testRemotelyRepliedArpLoop(): Unit = {
        val futures = addMoreWaiters(List.empty)
        arpBroker.process()

        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, THEIR_MAC)
        eventually {
            arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC)
            arpBroker.process()
            for (f <- futures) {
                eventually { f.value should be (Some(Success(THEIR_MAC))) }
            }
        }
    }

    def testInvalidatesFlowsRemotely(): Unit ={
        intercept[NotYetException] { arpBroker.get(THEIR_IP, port, router) }
        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, THEIR_MAC)
        eventually { arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC) }

        ArpCacheHelper.feedArpCache(remoteArpCache, THEIR_IP, MAC.random())
        eventually {
            arpBroker.process()
            invalidations should have size 1
            invalidations.poll() should be (FlowTagger.tagForDestinationIp(routerId, THEIR_IP))
        }
    }

    def testInvalidatesFlowsLocally(): Unit = {
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually { arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC) }

        arpBroker.set(THEIR_IP, MAC.random(), router)
        eventually {
            arpBroker.process()
            invalidations should have size 1
            invalidations.poll() should be (FlowTagger.tagForDestinationIp(routerId, THEIR_IP))
        }
    }

    def testArpForStaleEntries(): Unit = {
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually { arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC) }
        arpBroker.process()
        arpBroker.shouldProcess() should be (false)

        clock.time += (ARP_STALE + ARP_STALE * STALE_JITTER).toLong
        arpBroker.shouldProcess() should be (false)
        arpBroker.process()
        arps should be ('empty)

        arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC)
        expectEmitArp()

        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong
        arpBroker.shouldProcess() should be (true)
        arpBroker.process()
        expectEmitArp()

        arpBroker.set(THEIR_IP, MAC.random(), router)
        eventually { arpBroker.shouldProcess() should be (true) }
        arpBroker.process()
        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong * 2
        arpBroker.process()
        arps should be ('empty)

        clock.time += (ARP_RETRY * RETRY_MAX_BASE_JITTER).toLong * 2
        arpBroker.shouldProcess() should be (false)
        arpBroker.process()
        arps should be ('empty)
    }

    def testExpiresEntries(): Unit = {
        arpBroker.set(THEIR_IP, THEIR_MAC, router)
        eventually { arpBroker.get(THEIR_IP, port, router) should be (THEIR_MAC) }
        arpBroker.process()
        arps.clear()

        clock.time += ARP_EXPIRATION
        arpBroker.process()
        eventually {
            arpCache.get(THEIR_IP) should be (null)
        }
        arps should be ('empty)
    }
}
