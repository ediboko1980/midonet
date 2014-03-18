/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.api.l4lb.rest_api;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.security.RolesAllowed;
import javax.validation.ConstraintViolation;
import javax.validation.Validator;
import javax.ws.rs.Consumes;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import javax.ws.rs.core.UriInfo;

import com.google.inject.Inject;
import com.google.inject.assistedinject.Assisted;
import com.google.inject.servlet.RequestScoped;
import org.midonet.api.ResourceUriBuilder;
import org.midonet.api.VendorMediaType;
import org.midonet.api.auth.AuthRole;
import org.midonet.api.l4lb.VIP;
import org.midonet.api.rest_api.AbstractResource;
import org.midonet.api.rest_api.BadRequestHttpException;
import org.midonet.api.rest_api.ConflictHttpException;
import org.midonet.api.rest_api.NotFoundHttpException;
import org.midonet.api.rest_api.ResourceFactory;
import org.midonet.api.rest_api.RestApiConfig;
import org.midonet.api.validation.MessageProperty;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.InvalidStateOperationException;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.midonet.api.validation.MessageProperty.getMessage;

@RequestScoped
public class VipResource extends AbstractResource {

    private final static Logger log = LoggerFactory
            .getLogger(VIP.class);

    private final Validator validator;
    private final DataClient dataClient;

    @Inject
    public VipResource(RestApiConfig config, UriInfo uriInfo,
                       SecurityContext context, Validator validator,
                       DataClient dataClient, ResourceFactory factory) {
        super(config, uriInfo, context);
        this.validator = validator;
        this.dataClient = dataClient;
    }

    /**
     * Handler to GETting a list of VIPs
     *
     * @return The list of the VIPs.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Produces({ VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
            MediaType.APPLICATION_JSON })
    public List<VIP> list()
            throws StateAccessException, SerializationException {
        List<org.midonet.cluster.data.l4lb.VIP> vipsData;

        vipsData = dataClient.vipGetAll();
        List<VIP> vips = new ArrayList<VIP>();
        if (vipsData != null) {
            for (org.midonet.cluster.data.l4lb.VIP vipData: vipsData) {
                VIP vip = new VIP(vipData);
                vip.setBaseUri(getBaseUri());
                vips.add(vip);
            }

        }

        return vips;
    }

    /**
     * Handler to GETing the specific VIP
     *
     * @param id VIP ID from the request.
     * @return  The VIP object.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @GET
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Produces({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public VIP get(@PathParam("id") UUID id)
        throws StateAccessException, SerializationException {
        org.midonet.cluster.data.l4lb.VIP vipData = dataClient.vipGet(id);

        if (vipData == null) {
            throw new NotFoundHttpException(getMessage(
                    MessageProperty.RESOURCE_NOT_FOUND, "VIP", id));
        }
        VIP vip = new VIP(vipData);
        vip.setBaseUri(getBaseUri());

        return vip;
    }

    /**
     * Handler to DELETing a VIP
     *
     * @param id VIP ID from the request.
     * @throws StateAccessException
     * @throws SerializationException
     */
    @DELETE
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    public void delete(@PathParam("id") UUID id)
            throws StateAccessException, SerializationException {

        try {
            dataClient.vipDelete(id);
        } catch (NoStatePathException ex) {
            // Delete is idempotent, so just ignore.
        }
    }

    /**
     * Handler to POSTing a VIP
     *
     * @param vip The requested VIP object.
     * @return Response for the POST request.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     * @throws ConflictHttpException
     */
    @POST
    @RolesAllowed({ AuthRole.ADMIN })
    @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public Response create(VIP vip)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException,ConflictHttpException {
        try {
            Set<ConstraintViolation<VIP>> violations = validator.validate(vip);
            if (!violations.isEmpty()) {
                throw new BadRequestHttpException(violations);
            }
            UUID id = dataClient.vipCreate(vip.toData());
            return Response.created(
                    ResourceUriBuilder.getVip(getBaseUri(), id)).build();
        } catch (StatePathExistsException ex) {
            throw new ConflictHttpException(getMessage(
                    MessageProperty.RESOURCE_EXISTS, "VIP", vip.getId()));
        } catch (NoStatePathException ex) {
            throw new BadRequestHttpException(ex);
        }
    }

    /**
     * Handler to PUTing a VIP
     *
     * @param id  The UUID of the VIP to be updated.
     * @param vip The requested VIP object.
     * @throws StateAccessException
     * @throws InvalidStateOperationException
     * @throws SerializationException
     */
    @PUT
    @RolesAllowed({ AuthRole.ADMIN })
    @Path("{id}")
    @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
            MediaType.APPLICATION_JSON })
    public void update(@PathParam("id") UUID id, VIP vip)
            throws StateAccessException, InvalidStateOperationException,
            SerializationException {
        vip.setId(id);
        Set<ConstraintViolation<VIP>> violations = validator.validate(vip);
        if (!violations.isEmpty()) {
            throw new BadRequestHttpException(violations);
        }

        try {
            dataClient.vipUpdate(vip.toData());
        } catch (NoStatePathException ex) {
            throw badReqOrNotFoundException(ex, id);
        }
    }

    /**
     * Sub-resource class for pool's VIPs.
     */
    @RequestScoped
    public static class PoolVipResource extends AbstractResource {
        private final UUID poolId;
        private final DataClient dataClient;

        @Inject
        public PoolVipResource(RestApiConfig config, UriInfo uriInfo,
                               SecurityContext context,
                               DataClient dataClient,
                               @Assisted UUID id) {
            super(config, uriInfo, context);
            this.dataClient = dataClient;
            this.poolId = id;
        }

        @GET
        @RolesAllowed({ AuthRole.ADMIN })
        @Produces({ VendorMediaType.APPLICATION_VIP_COLLECTION_JSON,
                MediaType.APPLICATION_JSON })
        public List<VIP> list()
                throws StateAccessException, SerializationException {

            List<org.midonet.cluster.data.l4lb.VIP> dataVips = null;

            try {
                dataVips = dataClient.poolGetVips(poolId);
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex);
            }
            List<VIP> vips = new ArrayList<VIP>();
            if (dataVips != null) {
                for (org.midonet.cluster.data.l4lb.VIP dataVip : dataVips) {
                    VIP vip = new VIP(dataVip);
                    vip.setBaseUri(getBaseUri());
                    vips.add(vip);
                }
            }
            return vips;
        }

        @POST
        @RolesAllowed({ AuthRole.ADMIN })
        @Consumes({ VendorMediaType.APPLICATION_VIP_JSON,
                MediaType.APPLICATION_JSON})
        public Response create(VIP vip)
                throws StateAccessException, SerializationException {
            vip.setPoolId(poolId);
            try {
                UUID id = dataClient.vipCreate(vip.toData());
                return Response.created(
                        ResourceUriBuilder.getVip(getBaseUri(), id))
                        .build();
            } catch (StatePathExistsException ex) {
                throw new ConflictHttpException(getMessage(
                        MessageProperty.RESOURCE_EXISTS, "VIP"));
            } catch (NoStatePathException ex) {
                throw new NotFoundHttpException(ex);
            }
        }
    }
}
