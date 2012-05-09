/*
 * @(#)testVif        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api;

import java.net.URI;
import java.util.UUID;

import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.test.framework.JerseyTest;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.client.DtoApplication;
import com.midokura.midolman.mgmt.data.dto.client.DtoRule;
import com.midokura.midolman.mgmt.data.dto.client.DtoRuleChain;
import com.midokura.midolman.mgmt.data.dto.client.DtoTenant;


import static com.midokura.midolman.mgmt.rest_api.core.VendorMediaType.*;
import static org.hamcrest.Matchers.arrayContainingInAnyOrder;
import static org.hamcrest.Matchers.arrayWithSize;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class TestChain extends JerseyTest {

    private final static Logger log = LoggerFactory.getLogger(TestChain.class);
    private final String testTenantName = "TEST-TENANT";
    private final String testRouterName = "TEST-ROUTER";

    private WebResource resource;
    private ClientResponse response;
    private URI testRouterUri;
    private UUID testRouterPortId;

    DtoTenant tenant1;
    DtoTenant tenant2;

    public TestChain() {
        super(FuncTest.appDesc);
    }

    @Before
    public void before() {
        ClientResponse response;

        DtoApplication app = new DtoApplication();
        app = resource().path("")
                .type(APPLICATION_JSON)
                .get(DtoApplication.class);

        tenant1 = new DtoTenant();
        tenant1.setId("ChainTenant1");
        response = resource().uri(app.getTenant())
                .type(APPLICATION_TENANT_JSON)
                .post(ClientResponse.class, tenant1);
        assertEquals("The tenant was created.", 201, response.getStatus());
        tenant1 = resource().uri(response.getLocation())
                .accept(APPLICATION_TENANT_JSON)
                .get(DtoTenant.class);

        tenant2 = new DtoTenant();
        tenant2.setId("ChainTenant2");
        response = resource().uri(app.getTenant())
                .type(APPLICATION_TENANT_JSON)
                .post(ClientResponse.class, tenant2);
        assertEquals("The tenant was created.", 201, response.getStatus());
        tenant2 = resource().uri(response.getLocation())
                .accept(APPLICATION_TENANT_JSON)
                .get(DtoTenant.class);
    }

    @Test
    public void testCreateGetListDelete() {
        ClientResponse response;

        // Create a rule chain for Tenant1
        DtoRuleChain ruleChain1 = new DtoRuleChain();
        ruleChain1.setName("Chain1");
        response = resource().uri(tenant1.getChains())
                .type(APPLICATION_CHAIN_JSON)
                .post(ClientResponse.class, ruleChain1);
        assertEquals("The bridge was created.", 201, response.getStatus());
        ruleChain1 = resource().uri(response.getLocation())
                .accept(APPLICATION_CHAIN_JSON)
                .get(DtoRuleChain.class);
        assertEquals("Chain1", ruleChain1.getName());
        assertEquals(tenant1.getId(), ruleChain1.getTenantId());

        // Create another rule chain for Tenant1
        DtoRuleChain ruleChain2 = new DtoRuleChain();
        ruleChain2.setName("Chain2");
        response = resource().uri(tenant1.getChains())
                .type(APPLICATION_CHAIN_JSON)
                .post(ClientResponse.class, ruleChain2);
        assertEquals("The bridge was created.", 201, response.getStatus());
        ruleChain2 = resource().uri(response.getLocation())
                .accept(APPLICATION_CHAIN_JSON)
                .get(DtoRuleChain.class);
        assertEquals("Chain2", ruleChain2.getName());
        assertEquals(tenant1.getId(), ruleChain2.getTenantId());

        // Create a rule chain for Tenant2
        DtoRuleChain ruleChain3 = new DtoRuleChain();
        ruleChain3.setName("Chain3");
        response = resource().uri(tenant2.getChains())
                .type(APPLICATION_CHAIN_JSON)
                .post(ClientResponse.class, ruleChain3);
        assertEquals("The bridge was created.", 201, response.getStatus());
        ruleChain3 = resource().uri(response.getLocation())
                .accept(APPLICATION_CHAIN_JSON)
                .get(DtoRuleChain.class);
        assertEquals("Chain3", ruleChain3.getName());
        assertEquals(tenant2.getId(), ruleChain3.getTenantId());

        // List tenant1's chains
        response = resource().uri(tenant1.getChains())
                .accept(APPLICATION_CHAIN_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        DtoRuleChain[] chains = response.getEntity(DtoRuleChain[].class);
        assertThat("Tenant1 has 2 chains.", chains, arrayWithSize(2));
        assertThat("We expect the listed chains to match those we created.",
                chains, arrayContainingInAnyOrder(ruleChain1, ruleChain2));

        // Create a JUMP rule in chain1 with target=chain2
        DtoRule jumpRule = new DtoRule();
        jumpRule.setPosition(1);
        jumpRule.setJumpChainName("Chain2");
        jumpRule.setType(DtoRule.Jump);
        response = resource().uri(ruleChain1.getRules())
                .type(APPLICATION_RULE_JSON)
                .post(ClientResponse.class, jumpRule);
        assertEquals("The jump rule was created.", 201, response.getStatus());
        jumpRule = resource().uri(response.getLocation())
                .accept(APPLICATION_RULE_JSON)
                .get(DtoRule.class);
        assertEquals("Chain2", jumpRule.getJumpChainName());
        assertEquals(ruleChain1.getId(), jumpRule.getChainId());

        // Delete the first rule-chain
        response = resource().uri(ruleChain1.getUri())
                .delete(ClientResponse.class);
        assertEquals(204, response.getStatus());
        // There should now be only the second chain.
        response = resource().uri(tenant1.getChains())
                .accept(APPLICATION_CHAIN_COLLECTION_JSON)
                .get(ClientResponse.class);
        assertEquals(200, response.getStatus());
        chains = response.getEntity(DtoRuleChain[].class);
        assertThat("We expect 1 listed chain after the delete",
                chains, arrayWithSize(1));
        assertThat("The listed chain should be the one that wasn't deleted.",
                chains, arrayContainingInAnyOrder(ruleChain2));

        // Test GET of a non-existing chain (the deleted first chain).
        response = resource().uri(ruleChain1.getUri())
                .accept(APPLICATION_CHAIN_JSON).get(ClientResponse.class);
        assertEquals(404, response.getStatus());

        // TODO(pino): creata JUMP rule in chain1 with target=chain2.

        // TODO(pino): all these cases should fail:
        // TODO:  1) Set a JUMP target to the other tenant's chain.
        // TODO:  2) Set a JUMP target to a non-existent chain.
        // TODO:  3) Set a chain as a filter on the other tenant's bridge.
        // TODO:  4) Set a chain as a filter on the other tenant's router.
        // TODO:  5) Set a chain as a filter on the other tenant's port.
    }

}
