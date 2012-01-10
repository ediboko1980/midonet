/*
 * @(#)TestTenantOpBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;

public class TestTenantOpBuilder {

    private ZkManager zkDaoMock = null;
    private PathBuilder pathBuilderMock = null;
    private TenantOpBuilder builder = null;
    private final static String dummyId = "foo";
    private final static String dummyPath = "/foo";

    @Before
    public void setUp() throws Exception {
        zkDaoMock = Mockito.mock(ZkManager.class);
        pathBuilderMock = Mockito.mock(PathBuilder.class);
        builder = new TenantOpBuilder(zkDaoMock, pathBuilderMock);
    }

    @Test
    public void TestBridgesCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantBridgesPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantBridgesCreateOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, null);
    }

    @Test
    public void TestBridgesDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantBridgesPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantBridgesDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestBridgeNamesCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantBridgeNamesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getTenantBridgeNamesCreateOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, null);
    }

    @Test
    public void TestBridgeNamesDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantBridgeNamesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getTenantBridgeNamesDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantCreateOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, null);
    }

    @Test
    public void TestDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestRoutersCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantRoutersPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantRoutersCreateOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, null);
    }

    @Test
    public void TestRoutersDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantRoutersPath(dummyId)).thenReturn(
                dummyPath);

        builder.getTenantRoutersDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

    @Test
    public void TestRouterNamesCreateOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantRouterNamesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getTenantRouterNamesCreateOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getPersistentCreateOp(
                dummyPath, null);
    }

    @Test
    public void TestRouterNamesDeleteOpSuccess() throws Exception {
        Mockito.when(pathBuilderMock.getTenantRouterNamesPath(dummyId))
                .thenReturn(dummyPath);

        builder.getTenantRouterNamesDeleteOp(dummyId);

        Mockito.verify(zkDaoMock, Mockito.times(1)).getDeleteOp(dummyPath);
    }

}
