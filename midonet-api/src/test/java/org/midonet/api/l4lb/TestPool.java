/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.api.l4lb;

import java.net.URI;
import java.util.UUID;

import junit.framework.Assert;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.experimental.runners.Enclosed;
import org.junit.runner.RunWith;
import org.midonet.api.VendorMediaType;
import org.midonet.api.zookeeper.StaticMockDirectory;
import org.midonet.client.dto.*;

import static javax.ws.rs.core.Response.Status.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.midonet.api.validation.MessageProperty.RESOURCE_EXISTS;
import static org.midonet.api.validation.MessageProperty.RESOURCE_NOT_FOUND;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_POOL_MEMBER_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_COLLECTION_JSON;
import static org.midonet.api.VendorMediaType.APPLICATION_VIP_JSON;

@RunWith(Enclosed.class)
public class TestPool {


    public static class TestPoolCrud extends L4LBTestBase {

        @Before
        public void setUp() {
            super.setUp();
        }

        @After
        public void resetDirectory() throws Exception {
            StaticMockDirectory.clearDirectoryInstance();
        }

        private void verifyNumberOfPools(int num) {
            DtoPool[] pools = dtoWebResource.getAndVerifyOk(
                    topLevelPoolsUri,
                    VendorMediaType.APPLICATION_POOL_COLLECTION_JSON,
                    DtoPool[].class);
            assertEquals(num, pools.length);
        }

        private void checkHealthMonitorBackref(
                URI healthMonitorUri, DtoPool expectedPool) {
            // Check health monitor backreference.
            DtoHealthMonitor hm = getHealthMonitor(healthMonitorUri);
            DtoPool[] hmPools = dtoWebResource.getAndVerifyOk(hm.getPools(),
                    APPLICATION_POOL_COLLECTION_JSON,
                    DtoPool[].class);
            if (expectedPool == null) {
                assertEquals(0, hmPools.length);
            } else {
                assertEquals(1, hmPools.length);
                assertEquals(expectedPool, hmPools[0]);
            }
        }

        private void checkVipBackrefs(DtoPool expectedPool, URI... vipUris) {
            for (URI vipUri : vipUris) {
                DtoVip vip = getVip(vipUri);
                if (expectedPool == null) {
                    assertNull(vip.getPoolId());
                    assertNull(vip.getPool());
                } else {
                    assertEquals(expectedPool.getId(), vip.getPoolId());
                    assertEquals(expectedPool.getUri(), vip.getPool());
                }
            }
        }

        @Test
        public void testCrud() throws Exception {

            // Pools should be empty
            verifyNumberOfPools(0);

            // Post
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            verifyNumberOfPools(1);

            // Post another
            DtoPool pool2 = createStockPool(loadBalancer.getId());
            verifyNumberOfPools(2);

            // Get and check
            DtoPool newPool = getPool(pool.getUri());
            Assert.assertEquals(pool, newPool);
            newPool = getPool(pool2.getUri());
            Assert.assertEquals(newPool, pool2);

            // Delete
            deletePool(pool.getUri());
            verifyNumberOfPools(1);
            deletePool(pool2.getUri());
            verifyNumberOfPools(0);
        }

        @Test
        public void testCreateIntializesReferences() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = getStockPool(loadBalancer.getId());
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor.getId());
            pool = postPool(pool);
            assertEquals(healthMonitor.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor.getUri(), pool);
        }

        @Test
        public void testUpdateUpdatesReferences() {
            // Start with no health monitor.
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            assertNull(pool.getHealthMonitor());

            // Add a health monitor.
            DtoHealthMonitor healthMonitor1 = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor1.getId());
            pool = updatePool(pool);

            // Pool and healthMonitor1 should now reference each other.
            assertEquals(healthMonitor1.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor1.getUri(), pool);

            // Switch reference to healthMonitor2.
            DtoHealthMonitor healthMonitor2 = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor2.getId());
            pool = updatePool(pool);

            // References between pool and healthMonitor1 should be
            // cleared and replaced with references between pool and
            // healthMonitor2.
            assertEquals(healthMonitor2.getUri(), pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor1.getUri(), null);
            checkHealthMonitorBackref(healthMonitor2.getUri(), pool);

            // Clear references.
            pool.setHealthMonitorId(null);
            pool = updatePool(pool);

            // All references gone.
            assertNull(pool.getHealthMonitor());
            checkHealthMonitorBackref(healthMonitor2.getUri(), null);
        }

        @Test
        public void testDeleteClearsBackrefs() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = getStockPool(loadBalancer.getId());
            DtoHealthMonitor healthMonitor = createStockHealthMonitor();
            pool.setHealthMonitorId(healthMonitor.getId());
            pool = postPool(pool);
            checkHealthMonitorBackref(healthMonitor.getUri(), pool);

            // Add some VIPs.
            DtoVip vip = createStockVip(pool.getId());
            DtoVip vip2 = createStockVip(pool.getId());
            checkVipBackrefs(pool, vip.getUri(), vip2.getUri());

            deletePool(pool.getUri());
            checkHealthMonitorBackref(healthMonitor.getUri(), null);
            // Strongly associated resources are deleted by cascading.
            dtoWebResource.getAndVerifyNotFound(vip.getUri(),
                    VendorMediaType.APPLICATION_VIP_JSON);
            dtoWebResource.getAndVerifyNotFound(vip2.getUri(),
                    VendorMediaType.APPLICATION_VIP_JSON);
        }

        @Test
        public void testCreateWithBadHealthMonitorId() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = getStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolsUri, APPLICATION_POOL_JSON, pool, BAD_REQUEST);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                    "health monitor", pool.getHealthMonitorId());
        }

        @Test
        public void testCreateWithDuplicatePoolId() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool1 = createStockPool(loadBalancer.getId());
            DtoPool pool2 = getStockPool(loadBalancer.getId());
            pool2.setId(pool1.getId());
            DtoError error = dtoWebResource.postAndVerifyError(
                    topLevelPoolsUri, APPLICATION_POOL_JSON, pool2, CONFLICT);
            assertErrorMatches(error, RESOURCE_EXISTS, "pool", pool2.getId());
        }

        @Test
        public void testGetWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = addIdToUri(topLevelPoolsUri, id);
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
        }

        @Test
        public void testDeleteWithRandomPoolId() throws Exception {
            // Succeeds because delete is idempotent.
            deletePool(addIdToUri(topLevelPoolsUri, UUID.randomUUID()));
        }

        @Test
        public void testUpdateWithBadPoolId() throws Exception {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            pool.setId(UUID.randomUUID());
            pool.setUri(addIdToUri(topLevelPoolsUri, pool.getId()));
            DtoError error = dtoWebResource.putAndVerifyNotFound(
                    pool.getUri(), APPLICATION_POOL_JSON, pool);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", pool.getId());
        }

        @Test
        public void testUpdateWithBadHealthMonitorId() {
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            pool.setHealthMonitorId(UUID.randomUUID());
            DtoError error = dtoWebResource.putAndVerifyBadRequest(
                    pool.getUri(), APPLICATION_POOL_JSON, pool);
            assertErrorMatches(error, RESOURCE_NOT_FOUND,
                    "health monitor", pool.getHealthMonitorId());
        }

        @Test
        public void testListVips() {
            // Should start out empty.
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool = createStockPool(loadBalancer.getId());
            DtoVip[] vips = getVips(pool.getVips());
            assertEquals(0, vips.length);

            // Add one VIP.
            DtoVip vip1 = createStockVip(pool.getId());
            vips = getVips(pool.getVips());
            assertEquals(1, vips.length);
            assertEquals(vip1, vips[0]);

            // Try to add a second VIP without a reference to the pool and
            // fail with 400 Bad Request. `poolId` can't be null.
            DtoVip vip2 = getStockVip(null);
            dtoWebResource.postAndVerifyBadRequest(topLevelVipsUri,
                    APPLICATION_VIP_JSON,
                    vip2);
            vips = getVips(pool.getVips());
            assertEquals(1, vips.length);
            assertEquals(vip1, vips[0]);

            // Create a new VIP linking it to the pool.
            vip2 = createStockVip(pool.getId());
            vips = getVips(pool.getVips());
            assertEquals(2, vips.length);

            if (vip1.equals(vips[0])) {
                assertEquals(vip2, vips[1]);
            } else if (vip2.equals(vips[0])) {
                assertEquals(vip1, vips[1]);
            } else {
                Assert.fail("Neither VIP equal to vips[0]");
            }
        }

        @Test
        public void testListVipsWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = new URI(topLevelPoolsUri + "/" + id + "/vips");
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_VIP_COLLECTION_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
        }

        @Test
        public void testListMembersWithRandomPoolId() throws Exception {
            UUID id = UUID.randomUUID();
            URI uri = new URI(topLevelPoolsUri + "/" + id + "/pool_members");
            DtoError error = dtoWebResource.getAndVerifyNotFound(
                    uri, APPLICATION_POOL_MEMBER_COLLECTION_JSON);
            assertErrorMatches(error, RESOURCE_NOT_FOUND, "pool", id);
        }

        @Test
        synchronized public void testPoolsOfLoadBalancer() {
            int poolsCounter = 0;
            // Create two Pools in advance.
            DtoLoadBalancer loadBalancer = createStockLoadBalancer();
            DtoPool pool1 = createStockPool(loadBalancer.getId());
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool1.getLoadBalancerId());

            DtoPool pool2 = createStockPool(loadBalancer.getId());
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool2.getLoadBalancerId());

            DtoPool[] pools = getPools(loadBalancer.getPools());
            assertEquals(poolsCounter, pools.length);

            // POST another one to the URI of the load balancer without the
            // explicit load balancer ID.
            DtoPool pool3 = getStockPool(loadBalancer.getId());
            pool3  = dtoWebResource.postAndVerifyCreated(loadBalancer.getPools(),
                    APPLICATION_POOL_JSON, pool3, DtoPool.class);
            poolsCounter++;
            assertEquals(loadBalancer.getId(), pool3.getLoadBalancerId());

            // Traverse the pools associated withe the load balancer
            pools = getPools(loadBalancer.getPools());
            assertEquals(poolsCounter, pools.length);
            for (DtoPool originalPool : pools) {
                DtoPool retrievedPool = dtoWebResource.getAndVerifyOk(
                        originalPool.getUri(),
                        APPLICATION_POOL_JSON, DtoPool.class);
                assertEquals(originalPool, retrievedPool);

            }
        }
    }
}
