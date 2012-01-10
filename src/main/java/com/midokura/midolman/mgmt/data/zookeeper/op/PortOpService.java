/*
 * @(#)PortOpService        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.zookeeper.PortZkDao;
import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * Port Op builder.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class PortOpService {

    private final static Logger log = LoggerFactory
            .getLogger(PortOpService.class);
    private final PortOpBuilder opBuilder;
    private final PortZkDao zkDao;

    /**
     * Constructor
     *
     * @param opBuilder
     *            PortOpBuilder object
     * @param zkDao
     *            PortZkDao object
     */
    public PortOpService(PortOpBuilder opBuilder, PortZkDao zkDao) {
        this.opBuilder = opBuilder;
        this.zkDao = zkDao;
    }

    /**
     * Build list of Op objects to create a port link
     *
     * @param id
     *            ID of the port
     * @param config
     *            PortConfig object
     * @param peerId
     *            ID of the peer port
     * @param peerConfig
     *            PortConfig of the peer port
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreateLink(UUID id, PortConfig config, UUID peerId,
            PortConfig peerConfig) throws StateAccessException {
        log.debug("PortOpService.buildCreate entered: id=" + id + ", peerId="
                + peerId);

        List<Op> ops = new ArrayList<Op>();

        ops.add(opBuilder.getPortCreateOp(id, null));
        ops.add(opBuilder.getPortCreateOp(peerId, null));

        ops.addAll(opBuilder.getPortLinkCreateOps(id, config, peerId,
                peerConfig));

        log.debug("PortOpService.buildCreate exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to create a port
     *
     * @param id
     *            ID of the port
     * @param config
     *            PortConfig object
     * @param mgmtConfig
     *            PortMgmtConfig object
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> buildCreate(UUID id, PortConfig config,
            PortMgmtConfig mgmtConfig) throws StateAccessException {
        log.debug("PortOpService.buildCreate entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();

        // Create PortMgmtConfig
        ops.add(opBuilder.getPortCreateOp(id, mgmtConfig));

        // Create PortConfig
        ops.addAll(opBuilder.getPortCreateOps(id, config));

        log.debug("PortOpService.buildCreate exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete a port link
     *
     * @param id
     *            ID of the port
     * @param peerId
     *            ID of the peer port
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> buildDeleteLink(UUID id, UUID peerId)
            throws StateAccessException {
        log.debug("PortOpService.buildDelete exiting: id=" + id + ", peerId="
                + peerId);

        List<Op> ops = new ArrayList<Op>();
        ops.addAll(opBuilder.getPortDeleteOps(id));
        ops.add(opBuilder.getPortDeleteOp(peerId));
        ops.add(opBuilder.getPortDeleteOp(id));

        log.debug("PortOpService.buildDelete exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete a port
     *
     * @param id
     *            ID of the port
     * @param cascade
     *            True to delete the midolman side
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> buildDelete(UUID id, boolean cascade)
            throws StateAccessException {
        log.debug("PortOpService.buildDelete exiting: id=" + id + ", cascade="
                + cascade);

        List<Op> ops = new ArrayList<Op>();

        if (cascade) {
            // Delete PortConfig
            ops.addAll(opBuilder.getPortDeleteOps(id));
        }

        // Delete PortMgmtConfig
        ops.add(opBuilder.getPortDeleteOp(id));

        log.debug("PortOpService.buildDelete exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to update a port
     *
     * @param id
     *            ID of the port
     * @param mgmtConfig
     *            PortMgmtConfig pbject
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildUpdate(UUID id, PortMgmtConfig mgmtConfig)
            throws StateAccessException {
        log.debug("PortOpService.buildUpdate entered: id={}", id);
        List<Op> ops = new ArrayList<Op>();

        ops.add(opBuilder.getPortSetDataOp(id, mgmtConfig));

        log.debug("PortOpService.buildUpdate exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Builds operations to handle the VIF plug event for the port side. If VIF
     * ID is set to null, it means unplugging.
     *
     * @param id
     *            port ID
     * @param vifId
     *            VIF ID
     * @return Op list
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> buildPlug(UUID id, UUID vifId) throws StateAccessException {
        log.debug("PortOpService.buildPlug entered: id=" + id + ", vifId="
                + vifId);

        List<Op> ops = new ArrayList<Op>();
        PortMgmtConfig mgmtConfig = zkDao.getMgmtData(id);
        mgmtConfig.vifId = vifId;
        ops.addAll(buildUpdate(id, mgmtConfig));

        log.debug("PortOpService.buildPlug exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build Op list for bridge delete event.
     *
     * @param bridgeId
     *            Bridge ID
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildBridgePortsDelete(UUID bridgeId)
            throws StateAccessException {
        log.debug("PortOpService.buildBridgePortsDelete entered: bridgeId={}",
                bridgeId);

        List<Op> ops = new ArrayList<Op>();

        Set<UUID> ids = zkDao.getBridgePortIds(bridgeId);
        for (UUID id : ids) {
            ops.addAll(buildDelete(id, false));
        }

        log.debug("PortOpService.buildBridgePortsDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build Op list for router delete event.
     *
     * @param routerId
     *            Router ID
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildRouterPortsDelete(UUID routerId)
            throws StateAccessException {
        log.debug("PortOpService.buildRouterPortsDelete entered: routerId={}",
                routerId);

        List<Op> ops = new ArrayList<Op>();

        Set<UUID> ids = zkDao.getRouterPortIds(routerId);
        for (UUID id : ids) {
            ops.addAll(buildDelete(id, false));
        }

        log.debug("PortOpService.buildRouterPortsDelete exiting: ops count={}",
                ops.size());
        return ops;
    }
}
