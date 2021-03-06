/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.hbase.master;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.hadoop.hbase.DeserializationException;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.RegionTransition;
import org.apache.hadoop.hbase.Server;
import org.apache.hadoop.hbase.ServerLoad;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.catalog.CatalogTracker;
import org.apache.hadoop.hbase.client.ClientProtocol;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HConnectionTestingUtility;
import org.apache.hadoop.hbase.client.Result;
import org.apache.hadoop.hbase.executor.EventHandler.EventType;
import org.apache.hadoop.hbase.executor.ExecutorService;
import org.apache.hadoop.hbase.executor.ExecutorService.ExecutorType;
import org.apache.hadoop.hbase.master.AssignmentManager.RegionState;
import org.apache.hadoop.hbase.master.AssignmentManager.RegionState.State;
import org.apache.hadoop.hbase.master.balancer.DefaultLoadBalancer;
import org.apache.hadoop.hbase.master.balancer.LoadBalancerFactory;
import org.apache.hadoop.hbase.master.handler.ServerShutdownHandler;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.GetResponse;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanRequest;
import org.apache.hadoop.hbase.protobuf.generated.ClientProtos.ScanResponse;
import org.apache.hadoop.hbase.protobuf.generated.ZooKeeperProtos.Table;
import org.apache.hadoop.hbase.regionserver.RegionOpeningState;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hbase.util.Pair;
import org.apache.hadoop.hbase.util.Threads;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;
import org.apache.hadoop.hbase.zookeeper.ZKAssign;
import org.apache.hadoop.hbase.zookeeper.ZKUtil;
import org.apache.hadoop.hbase.zookeeper.ZooKeeperWatcher;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.apache.zookeeper.Watcher;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.mockito.Mockito;

import com.google.protobuf.RpcController;
import com.google.protobuf.ServiceException;


/**
 * Test {@link AssignmentManager}
 */
@Category(MediumTests.class)
public class TestAssignmentManager {
  private static final HBaseTestingUtility HTU = new HBaseTestingUtility();
  private static final ServerName SERVERNAME_A =
    new ServerName("example.org", 1234, 5678);
  private static final ServerName SERVERNAME_B =
    new ServerName("example.org", 0, 5678);
  private static final HRegionInfo REGIONINFO =
    new HRegionInfo(Bytes.toBytes("t"),
      HConstants.EMPTY_START_ROW, HConstants.EMPTY_START_ROW);

  // Mocked objects or; get redone for each test.
  private Server server;
  private ServerManager serverManager;
  private ZooKeeperWatcher watcher;
  private LoadBalancer balancer;

  @BeforeClass
  public static void beforeClass() throws Exception {
    HTU.startMiniZKCluster();
  }

  @AfterClass
  public static void afterClass() throws IOException {
    HTU.shutdownMiniZKCluster();
  }

  @Before
  public void before() throws ZooKeeperConnectionException, IOException {
    // TODO: Make generic versions of what we do below and put up in a mocking
    // utility class or move up into HBaseTestingUtility.

    // Mock a Server.  Have it return a legit Configuration and ZooKeeperWatcher.
    // If abort is called, be sure to fail the test (don't just swallow it
    // silently as is mockito default).
    this.server = Mockito.mock(Server.class);
    Mockito.when(server.getServerName()).thenReturn(new ServerName("master,1,1"));
    Mockito.when(server.getConfiguration()).thenReturn(HTU.getConfiguration());
    this.watcher =
      new ZooKeeperWatcher(HTU.getConfiguration(), "mockedServer", this.server, true);
    Mockito.when(server.getZooKeeper()).thenReturn(this.watcher);
    Mockito.doThrow(new RuntimeException("Aborted")).
      when(server).abort(Mockito.anyString(), (Throwable)Mockito.anyObject());

    // Mock a ServerManager.  Say server SERVERNAME_{A,B} are online.  Also
    // make it so if close or open, we return 'success'.
    this.serverManager = Mockito.mock(ServerManager.class);
    Mockito.when(this.serverManager.isServerOnline(SERVERNAME_A)).thenReturn(true);
    Mockito.when(this.serverManager.isServerOnline(SERVERNAME_B)).thenReturn(true);
    final Map<ServerName, ServerLoad> onlineServers = new HashMap<ServerName, ServerLoad>();
    onlineServers.put(SERVERNAME_B, ServerLoad.EMPTY_SERVERLOAD);
    onlineServers.put(SERVERNAME_A, ServerLoad.EMPTY_SERVERLOAD);
    Mockito.when(this.serverManager.getOnlineServersList()).thenReturn(
        new ArrayList<ServerName>(onlineServers.keySet()));
    Mockito.when(this.serverManager.getOnlineServers()).thenReturn(onlineServers);

    List<ServerName> avServers = new ArrayList<ServerName>();
    avServers.addAll(onlineServers.keySet());
    Mockito.when(this.serverManager.createDestinationServersList()).thenReturn(avServers);
    Mockito.when(this.serverManager.createDestinationServersList(null)).thenReturn(avServers);

    Mockito.when(this.serverManager.sendRegionClose(SERVERNAME_A, REGIONINFO, -1)).
      thenReturn(true);
    Mockito.when(this.serverManager.sendRegionClose(SERVERNAME_B, REGIONINFO, -1)).
      thenReturn(true);
    // Ditto on open.
    Mockito.when(this.serverManager.sendRegionOpen(SERVERNAME_A, REGIONINFO, -1)).
      thenReturn(RegionOpeningState.OPENED);
    Mockito.when(this.serverManager.sendRegionOpen(SERVERNAME_B, REGIONINFO, -1)).
    thenReturn(RegionOpeningState.OPENED);
  }

  @After
    public void after() throws KeeperException {
    if (this.watcher != null) {
      // Clean up all znodes
      ZKAssign.deleteAllNodes(this.watcher);
      this.watcher.close();
    }
  }

  /**
   * Test a balance going on at same time as a master failover
   *
   * @throws IOException
   * @throws KeeperException
   * @throws InterruptedException
   * @throws DeserializationException 
   */
  @Test(timeout = 5000)
  public void testBalanceOnMasterFailoverScenarioWithOpenedNode()
  throws IOException, KeeperException, InterruptedException, ServiceException, DeserializationException {
    AssignmentManagerWithExtrasForTesting am =
      setUpMockedAssignmentManager(this.server, this.serverManager);
    try {
      createRegionPlanAndBalance(am, SERVERNAME_A, SERVERNAME_B, REGIONINFO);
      startFakeFailedOverMasterAssignmentManager(am, this.watcher);
      while (!am.processRITInvoked) Thread.sleep(1);
      // Now fake the region closing successfully over on the regionserver; the
      // regionserver will have set the region in CLOSED state. This will
      // trigger callback into AM. The below zk close call is from the RS close
      // region handler duplicated here because its down deep in a private
      // method hard to expose.
      int versionid =
        ZKAssign.transitionNodeClosed(this.watcher, REGIONINFO, SERVERNAME_A, -1);
      assertNotSame(versionid, -1);
      Mocking.waitForRegionPendingOpenInRIT(am, REGIONINFO.getEncodedName());

      // Get current versionid else will fail on transition from OFFLINE to
      // OPENING below
      versionid = ZKAssign.getVersion(this.watcher, REGIONINFO);
      assertNotSame(-1, versionid);
      // This uglyness below is what the openregionhandler on RS side does.
      versionid = ZKAssign.transitionNode(server.getZooKeeper(), REGIONINFO,
        SERVERNAME_A, EventType.M_ZK_REGION_OFFLINE,
        EventType.RS_ZK_REGION_OPENING, versionid);
      assertNotSame(-1, versionid);
      // Move znode from OPENING to OPENED as RS does on successful open.
      versionid = ZKAssign.transitionNodeOpened(this.watcher, REGIONINFO,
        SERVERNAME_B, versionid);
      assertNotSame(-1, versionid);
      am.gate.set(false);
      // Block here until our znode is cleared or until this test times out.
      ZKAssign.blockUntilNoRIT(watcher);
    } finally {
      am.getExecutorService().shutdown();
      am.shutdown();
    }
  }

  @Test(timeout = 5000)
  public void testBalanceOnMasterFailoverScenarioWithClosedNode()
  throws IOException, KeeperException, InterruptedException, ServiceException, DeserializationException {
    AssignmentManagerWithExtrasForTesting am =
      setUpMockedAssignmentManager(this.server, this.serverManager);
    try {
      createRegionPlanAndBalance(am, SERVERNAME_A, SERVERNAME_B, REGIONINFO);
      startFakeFailedOverMasterAssignmentManager(am, this.watcher);
      while (!am.processRITInvoked) Thread.sleep(1);
      // Now fake the region closing successfully over on the regionserver; the
      // regionserver will have set the region in CLOSED state. This will
      // trigger callback into AM. The below zk close call is from the RS close
      // region handler duplicated here because its down deep in a private
      // method hard to expose.
      int versionid =
        ZKAssign.transitionNodeClosed(this.watcher, REGIONINFO, SERVERNAME_A, -1);
      assertNotSame(versionid, -1);
      am.gate.set(false);
      Mocking.waitForRegionPendingOpenInRIT(am, REGIONINFO.getEncodedName());

      // Get current versionid else will fail on transition from OFFLINE to
      // OPENING below
      versionid = ZKAssign.getVersion(this.watcher, REGIONINFO);
      assertNotSame(-1, versionid);
      // This uglyness below is what the openregionhandler on RS side does.
      versionid = ZKAssign.transitionNode(server.getZooKeeper(), REGIONINFO,
          SERVERNAME_A, EventType.M_ZK_REGION_OFFLINE,
          EventType.RS_ZK_REGION_OPENING, versionid);
      assertNotSame(-1, versionid);
      // Move znode from OPENING to OPENED as RS does on successful open.
      versionid = ZKAssign.transitionNodeOpened(this.watcher, REGIONINFO,
          SERVERNAME_B, versionid);
      assertNotSame(-1, versionid);

      // Block here until our znode is cleared or until this test timesout.
      ZKAssign.blockUntilNoRIT(watcher);
    } finally {
      am.getExecutorService().shutdown();
      am.shutdown();
    }
  }

  @Test(timeout = 5000)
  public void testBalanceOnMasterFailoverScenarioWithOfflineNode()
  throws IOException, KeeperException, InterruptedException, ServiceException, DeserializationException {
    AssignmentManagerWithExtrasForTesting am =
      setUpMockedAssignmentManager(this.server, this.serverManager);
    try {
      createRegionPlanAndBalance(am, SERVERNAME_A, SERVERNAME_B, REGIONINFO);
      startFakeFailedOverMasterAssignmentManager(am, this.watcher);
      while (!am.processRITInvoked) Thread.sleep(1);
      // Now fake the region closing successfully over on the regionserver; the
      // regionserver will have set the region in CLOSED state. This will
      // trigger callback into AM. The below zk close call is from the RS close
      // region handler duplicated here because its down deep in a private
      // method hard to expose.
      int versionid =
        ZKAssign.transitionNodeClosed(this.watcher, REGIONINFO, SERVERNAME_A, -1);
      assertNotSame(versionid, -1);
      Mocking.waitForRegionPendingOpenInRIT(am, REGIONINFO.getEncodedName());

      am.gate.set(false);
      // Get current versionid else will fail on transition from OFFLINE to
      // OPENING below
      versionid = ZKAssign.getVersion(this.watcher, REGIONINFO);
      assertNotSame(-1, versionid);
      // This uglyness below is what the openregionhandler on RS side does.
      versionid = ZKAssign.transitionNode(server.getZooKeeper(), REGIONINFO,
          SERVERNAME_A, EventType.M_ZK_REGION_OFFLINE,
          EventType.RS_ZK_REGION_OPENING, versionid);
      assertNotSame(-1, versionid);
      // Move znode from OPENING to OPENED as RS does on successful open.
      versionid = ZKAssign.transitionNodeOpened(this.watcher, REGIONINFO,
          SERVERNAME_B, versionid);
      assertNotSame(-1, versionid);
      // Block here until our znode is cleared or until this test timesout.
      ZKAssign.blockUntilNoRIT(watcher);
    } finally {
      am.getExecutorService().shutdown();
      am.shutdown();
    }
  }

  private void createRegionPlanAndBalance(final AssignmentManager am,
      final ServerName from, final ServerName to, final HRegionInfo hri) {
    // Call the balance function but fake the region being online first at
    // servername from.
    am.regionOnline(hri, from);
    // Balance region from 'from' to 'to'. It calls unassign setting CLOSING state
    // up in zk.  Create a plan and balance
    am.balance(new RegionPlan(hri, from, to));
  }


  /**
   * Tests AssignmentManager balance function.  Runs a balance moving a region
   * from one server to another mocking regionserver responding over zk.
   * @throws IOException
   * @throws KeeperException
   * @throws DeserializationException 
   */
  @Test
  public void testBalance()
    throws IOException, KeeperException, DeserializationException, InterruptedException {
    // Create and startup an executor.  This is used by AssignmentManager
    // handling zk callbacks.
    ExecutorService executor = startupMasterExecutor("testBalanceExecutor");

    // We need a mocked catalog tracker.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    LoadBalancer balancer = LoadBalancerFactory.getLoadBalancer(server
        .getConfiguration());
    // Create an AM.
    AssignmentManager am = new AssignmentManager(this.server,
        this.serverManager, ct, balancer, executor, null);
    try {
      // Make sure our new AM gets callbacks; once registered, can't unregister.
      // Thats ok because we make a new zk watcher for each test.
      this.watcher.registerListenerFirst(am);
      // Call the balance function but fake the region being online first at
      // SERVERNAME_A.  Create a balance plan.
      am.regionOnline(REGIONINFO, SERVERNAME_A);
      // Balance region from A to B.
      RegionPlan plan = new RegionPlan(REGIONINFO, SERVERNAME_A, SERVERNAME_B);
      am.balance(plan);

      // Now fake the region closing successfully over on the regionserver; the
      // regionserver will have set the region in CLOSED state.  This will
      // trigger callback into AM. The below zk close call is from the RS close
      // region handler duplicated here because its down deep in a private
      // method hard to expose.
      int versionid =
        ZKAssign.transitionNodeClosed(this.watcher, REGIONINFO, SERVERNAME_A, -1);
      assertNotSame(versionid, -1);
      // AM is going to notice above CLOSED and queue up a new assign.  The
      // assign will go to open the region in the new location set by the
      // balancer.  The zk node will be OFFLINE waiting for regionserver to
      // transition it through OPENING, OPENED.  Wait till we see the OFFLINE
      // zk node before we proceed.
      Mocking.waitForRegionPendingOpenInRIT(am, REGIONINFO.getEncodedName());

      // Get current versionid else will fail on transition from OFFLINE to OPENING below
      versionid = ZKAssign.getVersion(this.watcher, REGIONINFO);
      assertNotSame(-1, versionid);
      // This uglyness below is what the openregionhandler on RS side does.
      versionid = ZKAssign.transitionNode(server.getZooKeeper(), REGIONINFO,
        SERVERNAME_A, EventType.M_ZK_REGION_OFFLINE,
        EventType.RS_ZK_REGION_OPENING, versionid);
      assertNotSame(-1, versionid);
      // Move znode from OPENING to OPENED as RS does on successful open.
      versionid =
        ZKAssign.transitionNodeOpened(this.watcher, REGIONINFO, SERVERNAME_B, versionid);
      assertNotSame(-1, versionid);
      // Wait on the handler removing the OPENED znode.
      while(am.isRegionInTransition(REGIONINFO) != null) Threads.sleep(1);
    } finally {
      executor.shutdown();
      am.shutdown();
      // Clean up all znodes
      ZKAssign.deleteAllNodes(this.watcher);
    }
  }

  /**
   * Run a simple server shutdown handler.
   * @throws KeeperException
   * @throws IOException
   */
  @Test
  public void testShutdownHandler()
      throws KeeperException, IOException, ServiceException {
    // Create and startup an executor.  This is used by AssignmentManager
    // handling zk callbacks.
    ExecutorService executor = startupMasterExecutor("testShutdownHandler");

    // We need a mocked catalog tracker.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    LoadBalancer balancer = LoadBalancerFactory.getLoadBalancer(server
        .getConfiguration());
    // Create an AM.
    AssignmentManager am = new AssignmentManager(this.server,
        this.serverManager, ct, balancer, executor, null);
    try {
      processServerShutdownHandler(ct, am, false);
    } finally {
      executor.shutdown();
      am.shutdown();
      // Clean up all znodes
      ZKAssign.deleteAllNodes(this.watcher);
    }
  }

  /**
   * To test closed region handler to remove rit and delete corresponding znode
   * if region in pending close or closing while processing shutdown of a region
   * server.(HBASE-5927).
   * 
   * @throws KeeperException
   * @throws IOException
   * @throws ServiceException
   */
  @Test
  public void testSSHWhenDisableTableInProgress() throws KeeperException, IOException,
      ServiceException {
    testCaseWithPartiallyDisabledState(Table.State.DISABLING);
    testCaseWithPartiallyDisabledState(Table.State.DISABLED);
  }
  
    
  /**
   * To test if the split region is removed from RIT if the region was in SPLITTING state but the RS
   * has actually completed the splitting in META but went down. See HBASE-6070 and also HBASE-5806
   * 
   * @throws KeeperException
   * @throws IOException
   */
  @Test
  public void testSSHWhenSplitRegionInProgress() throws KeeperException, IOException, Exception {
    // true indicates the region is split but still in RIT
    testCaseWithSplitRegionPartial(true);
    // false indicate the region is not split
    testCaseWithSplitRegionPartial(false);
  }
  
  private void testCaseWithSplitRegionPartial(boolean regionSplitDone) throws KeeperException,
      IOException, NodeExistsException, InterruptedException, ServiceException {
    // Create and startup an executor. This is used by AssignmentManager
    // handling zk callbacks.
    ExecutorService executor = startupMasterExecutor("testSSHWhenSplitRegionInProgress");

    // We need a mocked catalog tracker.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    LoadBalancer balancer = LoadBalancerFactory.getLoadBalancer(server.getConfiguration());
    // Create an AM.
    AssignmentManagerWithExtrasForTesting am = setUpMockedAssignmentManager(this.server,
        this.serverManager);
    // adding region to regions and servers maps.
    am.regionOnline(REGIONINFO, SERVERNAME_A);
    // adding region in pending close.
    am.regionsInTransition.put(REGIONINFO.getEncodedName(), new RegionState(REGIONINFO,
        State.SPLITTING, System.currentTimeMillis(), SERVERNAME_A));
    am.getZKTable().setEnabledTable(REGIONINFO.getTableNameAsString());
    RegionTransition data = RegionTransition.createRegionTransition(EventType.RS_ZK_REGION_SPLITTING,
        REGIONINFO.getRegionName(), SERVERNAME_A);
    String node = ZKAssign.getNodeName(this.watcher, REGIONINFO.getEncodedName());
    // create znode in M_ZK_REGION_CLOSING state.
    ZKUtil.createAndWatch(this.watcher, node, data.toByteArray());

    try {
      processServerShutdownHandler(ct, am, regionSplitDone);
      // check znode deleted or not.
      // In both cases the znode should be deleted.

      if (regionSplitDone) {
        assertTrue("Region state of region in SPLITTING should be removed from rit.",
            am.regionsInTransition.isEmpty());
      } else {
        while (!am.assignInvoked) {
          Thread.sleep(1);
        }
        assertTrue("Assign should be invoked.", am.assignInvoked);
      }
    } finally {
      REGIONINFO.setOffline(false);
      REGIONINFO.setSplit(false);
      executor.shutdown();
      am.shutdown();
      // Clean up all znodes
      ZKAssign.deleteAllNodes(this.watcher);
    }
  }

  private void testCaseWithPartiallyDisabledState(Table.State state) throws KeeperException,
      IOException, NodeExistsException, ServiceException {
    // Create and startup an executor. This is used by AssignmentManager
    // handling zk callbacks.
    ExecutorService executor = startupMasterExecutor("testSSHWhenDisableTableInProgress");
    // We need a mocked catalog tracker.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    LoadBalancer balancer = LoadBalancerFactory.getLoadBalancer(server.getConfiguration());
    // Create an AM.
    AssignmentManager am = new AssignmentManager(this.server, this.serverManager, ct, balancer,
        executor, null);
    // adding region to regions and servers maps.
    am.regionOnline(REGIONINFO, SERVERNAME_A);
    // adding region in pending close.
    am.regionsInTransition.put(REGIONINFO.getEncodedName(), new RegionState(REGIONINFO,
        State.PENDING_CLOSE));
    if (state == Table.State.DISABLING) {
      am.getZKTable().setDisablingTable(REGIONINFO.getTableNameAsString());
    } else {
      am.getZKTable().setDisabledTable(REGIONINFO.getTableNameAsString());
    }
    RegionTransition data = RegionTransition.createRegionTransition(EventType.M_ZK_REGION_CLOSING,
        REGIONINFO.getRegionName(), SERVERNAME_A);
    // RegionTransitionData data = new
    // RegionTransitionData(EventType.M_ZK_REGION_CLOSING,
    // REGIONINFO.getRegionName(), SERVERNAME_A);
    String node = ZKAssign.getNodeName(this.watcher, REGIONINFO.getEncodedName());
    // create znode in M_ZK_REGION_CLOSING state.
    ZKUtil.createAndWatch(this.watcher, node, data.toByteArray());
    try {
      processServerShutdownHandler(ct, am, false);
      // check znode deleted or not.
      // In both cases the znode should be deleted.
      assertTrue("The znode should be deleted.", ZKUtil.checkExists(this.watcher, node) == -1);
      // check whether in rit or not. In the DISABLING case also the below
      // assert will be true but the piece of code added for HBASE-5927 will not
      // do that.
      if (state == Table.State.DISABLED) {
        assertTrue("Region state of region in pending close should be removed from rit.",
            am.regionsInTransition.isEmpty());
      }
    } finally {
      am.setEnabledTable(REGIONINFO.getTableNameAsString());
      executor.shutdown();
      am.shutdown();
      // Clean up all znodes
      ZKAssign.deleteAllNodes(this.watcher);
    }
  }
     
  private void processServerShutdownHandler(CatalogTracker ct, AssignmentManager am, boolean splitRegion)
      throws IOException, ServiceException {
    // Make sure our new AM gets callbacks; once registered, can't unregister.
    // Thats ok because we make a new zk watcher for each test.
    this.watcher.registerListenerFirst(am);

    // Need to set up a fake scan of meta for the servershutdown handler
    // Make an RS Interface implementation.  Make it so a scanner can go against it.
    ClientProtocol implementation = Mockito.mock(ClientProtocol.class);
    // Get a meta row result that has region up on SERVERNAME_A
    
    Result r = null;
    if (splitRegion) {
      r = Mocking.getMetaTableRowResultAsSplitRegion(REGIONINFO, SERVERNAME_A);
    } else {
      r = Mocking.getMetaTableRowResult(REGIONINFO, SERVERNAME_A);
    }
    
    ScanResponse.Builder builder = ScanResponse.newBuilder();
    builder.setMoreResults(true);
    builder.addResult(ProtobufUtil.toResult(r));
    Mockito.when(implementation.scan(
      (RpcController)Mockito.any(), (ScanRequest)Mockito.any())).
        thenReturn(builder.build());

    // Get a connection w/ mocked up common methods.
    HConnection connection =
      HConnectionTestingUtility.getMockedConnectionAndDecorate(HTU.getConfiguration(),
        null, implementation, SERVERNAME_B, REGIONINFO);

    // Make it so we can get a catalogtracker from servermanager.. .needed
    // down in guts of server shutdown handler.
    Mockito.when(ct.getConnection()).thenReturn(connection);
    Mockito.when(this.server.getCatalogTracker()).thenReturn(ct);

    // Now make a server shutdown handler instance and invoke process.
    // Have it that SERVERNAME_A died.
    DeadServer deadServers = new DeadServer();
    deadServers.add(SERVERNAME_A);
    // I need a services instance that will return the AM
    MasterServices services = Mockito.mock(MasterServices.class);
    Mockito.when(services.getAssignmentManager()).thenReturn(am);
    Mockito.when(services.getServerManager()).thenReturn(this.serverManager);
    Mockito.when(services.getZooKeeper()).thenReturn(this.watcher);
    ServerShutdownHandler handler = new ServerShutdownHandler(this.server,
      services, deadServers, SERVERNAME_A, false);
    handler.process();
    // The region in r will have been assigned.  It'll be up in zk as unassigned.
  }

  /**
   * Create and startup executor pools. Start same set as master does (just
   * run a few less).
   * @param name Name to give our executor
   * @return Created executor (be sure to call shutdown when done).
   */
  private ExecutorService startupMasterExecutor(final String name) {
    // TODO: Move up into HBaseTestingUtility?  Generally useful.
    ExecutorService executor = new ExecutorService(name);
    executor.startExecutorService(ExecutorType.MASTER_OPEN_REGION, 3);
    executor.startExecutorService(ExecutorType.MASTER_CLOSE_REGION, 3);
    executor.startExecutorService(ExecutorType.MASTER_SERVER_OPERATIONS, 3);
    executor.startExecutorService(ExecutorType.MASTER_META_SERVER_OPERATIONS, 3);
    return executor;
  }

  @Test
  public void testUnassignWithSplitAtSameTime() throws KeeperException, IOException {
    // Region to use in test.
    final HRegionInfo hri = HRegionInfo.FIRST_META_REGIONINFO;
    // First amend the servermanager mock so that when we do send close of the
    // first meta region on SERVERNAME_A, it will return true rather than
    // default null.
    Mockito.when(this.serverManager.sendRegionClose(SERVERNAME_A, hri, -1)).thenReturn(true);
    // Need a mocked catalog tracker.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    LoadBalancer balancer = LoadBalancerFactory.getLoadBalancer(server
        .getConfiguration());
    // Create an AM.
    AssignmentManager am = new AssignmentManager(this.server,
        this.serverManager, ct, balancer, null, null);
    try {
      // First make sure my mock up basically works.  Unassign a region.
      unassign(am, SERVERNAME_A, hri);
      // This delete will fail if the previous unassign did wrong thing.
      ZKAssign.deleteClosingNode(this.watcher, hri);
      // Now put a SPLITTING region in the way.  I don't have to assert it
      // go put in place.  This method puts it in place then asserts it still
      // owns it by moving state from SPLITTING to SPLITTING.
      int version = createNodeSplitting(this.watcher, hri, SERVERNAME_A);
      // Now, retry the unassign with the SPLTTING in place.  It should just
      // complete without fail; a sort of 'silent' recognition that the
      // region to unassign has been split and no longer exists: TOOD: what if
      // the split fails and the parent region comes back to life?
      unassign(am, SERVERNAME_A, hri);
      // This transition should fail if the znode has been messed with.
      ZKAssign.transitionNode(this.watcher, hri, SERVERNAME_A,
        EventType.RS_ZK_REGION_SPLITTING, EventType.RS_ZK_REGION_SPLITTING, version);
      assertTrue(am.isRegionInTransition(hri) == null);
    } finally {
      am.shutdown();
    }
  }

  /**
   * Tests the processDeadServersAndRegionsInTransition should not fail with NPE
   * when it failed to get the children. Let's abort the system in this
   * situation
   * @throws ServiceException 
   */
  @Test(timeout = 5000)
  public void testProcessDeadServersAndRegionsInTransitionShouldNotFailWithNPE()
      throws IOException, KeeperException, InterruptedException, ServiceException {
    final RecoverableZooKeeper recoverableZk = Mockito
        .mock(RecoverableZooKeeper.class);
    AssignmentManagerWithExtrasForTesting am = setUpMockedAssignmentManager(
        this.server, this.serverManager);
    Watcher zkw = new ZooKeeperWatcher(HBaseConfiguration.create(), "unittest",
        null) {
      public RecoverableZooKeeper getRecoverableZooKeeper() {
        return recoverableZk;
      }
    };
    ((ZooKeeperWatcher) zkw).registerListener(am);
    Mockito.doThrow(new InterruptedException()).when(recoverableZk)
        .getChildren("/hbase/unassigned", zkw);
    am.setWatcher((ZooKeeperWatcher) zkw);
    try {
      am.processDeadServersAndRegionsInTransition();
      fail("Expected to abort");
    } catch (NullPointerException e) {
      fail("Should not throw NPE");
    } catch (RuntimeException e) {
      assertEquals("Aborted", e.getLocalizedMessage());
    }
  }
  /**
   * TestCase verifies that the regionPlan is updated whenever a region fails to open 
   * and the master tries to process RS_ZK_FAILED_OPEN state.(HBASE-5546).
   */
  @Test
  public void testRegionPlanIsUpdatedWhenRegionFailsToOpen() throws IOException, KeeperException,
      ServiceException, InterruptedException {
    this.server.getConfiguration().setClass(
      HConstants.HBASE_MASTER_LOADBALANCER_CLASS, MockedLoadBalancer.class,
      LoadBalancer.class);
    AssignmentManagerWithExtrasForTesting am = setUpMockedAssignmentManager(
      this.server, this.serverManager);
    try {
      // Boolean variable used for waiting until randomAssignment is called and
      // new
      // plan is generated.
      AtomicBoolean gate = new AtomicBoolean(false);
      if (balancer instanceof MockedLoadBalancer) {
        ((MockedLoadBalancer) balancer).setGateVariable(gate);
      }
      ZKAssign.createNodeOffline(this.watcher, REGIONINFO, SERVERNAME_A);
      int v = ZKAssign.getVersion(this.watcher, REGIONINFO);
      ZKAssign.transitionNode(this.watcher, REGIONINFO, SERVERNAME_A,
          EventType.M_ZK_REGION_OFFLINE, EventType.RS_ZK_REGION_FAILED_OPEN, v);
      String path = ZKAssign.getNodeName(this.watcher, REGIONINFO
          .getEncodedName());
      RegionState state = new RegionState(REGIONINFO, State.OPENING, System
          .currentTimeMillis(), SERVERNAME_A);
      am.regionsInTransition.put(REGIONINFO.getEncodedName(), state);
      // a dummy plan inserted into the regionPlans. This plan is cleared and
      // new one is formed
      am.regionPlans.put(REGIONINFO.getEncodedName(), new RegionPlan(
          REGIONINFO, null, SERVERNAME_A));
      RegionPlan regionPlan = am.regionPlans.get(REGIONINFO.getEncodedName());
      List<ServerName> serverList = new ArrayList<ServerName>(2);
      serverList.add(SERVERNAME_B);
      Mockito.when(
          this.serverManager.createDestinationServersList(SERVERNAME_A))
          .thenReturn(serverList);
      am.nodeDataChanged(path);
      // here we are waiting until the random assignment in the load balancer is
      // called.
      while (!gate.get()) {
        Thread.sleep(10);
      }
      // new region plan may take some time to get updated after random
      // assignment is called and
      // gate is set to true.
      RegionPlan newRegionPlan = am.regionPlans
          .get(REGIONINFO.getEncodedName());
      while (newRegionPlan == null) {
        Thread.sleep(10);
        newRegionPlan = am.regionPlans.get(REGIONINFO.getEncodedName());
      }
      // the new region plan created may contain the same RS as destination but
      // it should
      // be new plan.
      assertNotSame("Same region plan should not come", regionPlan,
          newRegionPlan);
      assertTrue("Destination servers should be different.", !(regionPlan
          .getDestination().equals(newRegionPlan.getDestination())));

      Mocking.waitForRegionPendingOpenInRIT(am, REGIONINFO.getEncodedName());
    } finally {
      this.server.getConfiguration().setClass(
          HConstants.HBASE_MASTER_LOADBALANCER_CLASS, DefaultLoadBalancer.class,
          LoadBalancer.class);
      am.getExecutorService().shutdown();
      am.shutdown();
    }
  }
  
  /**
   * Mocked load balancer class used in the testcase to make sure that the testcase waits until
   * random assignment is called and the gate variable is set to true.
   */
  public static class MockedLoadBalancer extends DefaultLoadBalancer {
    private AtomicBoolean gate;

    public void setGateVariable(AtomicBoolean gate) {
      this.gate = gate;
    }

    @Override
    public ServerName randomAssignment(HRegionInfo regionInfo, List<ServerName> servers) {
      ServerName randomServerName = super.randomAssignment(regionInfo, servers);
      this.gate.set(true);
      return randomServerName;
    }
  }
  
  /**
   * Test the scenario when the master is in failover and trying to process a
   * region which is in Opening state on a dead RS. Master should immediately
   * assign the region and not wait for Timeout Monitor.(Hbase-5882).
   */
  @Test
  public void testRegionInOpeningStateOnDeadRSWhileMasterFailover() throws IOException,
      KeeperException, ServiceException, InterruptedException {
    AssignmentManagerWithExtrasForTesting am = setUpMockedAssignmentManager(this.server,
        this.serverManager);
    ZKAssign.createNodeOffline(this.watcher, REGIONINFO, SERVERNAME_A);
    int version = ZKAssign.getVersion(this.watcher, REGIONINFO);
    ZKAssign.transitionNode(this.watcher, REGIONINFO, SERVERNAME_A, EventType.M_ZK_REGION_OFFLINE,
        EventType.RS_ZK_REGION_OPENING, version);
    RegionTransition rt = RegionTransition.createRegionTransition(EventType.RS_ZK_REGION_OPENING,
        REGIONINFO.getRegionName(), SERVERNAME_A, HConstants.EMPTY_BYTE_ARRAY);
    Map<ServerName, List<Pair<HRegionInfo, Result>>> deadServers = 
      new HashMap<ServerName, List<Pair<HRegionInfo, Result>>>();
    deadServers.put(SERVERNAME_A, null);
    version = ZKAssign.getVersion(this.watcher, REGIONINFO);
    am.gate.set(false);
    am.processRegionsInTransition(rt, REGIONINFO, deadServers, version);
    // Waiting for the assignment to get completed.
    while (!am.gate.get()) {
      Thread.sleep(10);
    }
    assertTrue("The region should be assigned immediately.", null != am.regionPlans.get(REGIONINFO
        .getEncodedName()));
  }
  /**
   * Creates a new ephemeral node in the SPLITTING state for the specified region.
   * Create it ephemeral in case regionserver dies mid-split.
   *
   * <p>Does not transition nodes from other states.  If a node already exists
   * for this region, a {@link NodeExistsException} will be thrown.
   *
   * @param zkw zk reference
   * @param region region to be created as offline
   * @param serverName server event originates from
   * @return Version of znode created.
   * @throws KeeperException
   * @throws IOException
   */
  // Copied from SplitTransaction rather than open the method over there in
  // the regionserver package.
  private static int createNodeSplitting(final ZooKeeperWatcher zkw,
      final HRegionInfo region, final ServerName serverName)
  throws KeeperException, IOException {
    RegionTransition rt =
      RegionTransition.createRegionTransition(EventType.RS_ZK_REGION_SPLITTING,
        region.getRegionName(), serverName);

    String node = ZKAssign.getNodeName(zkw, region.getEncodedName());
    if (!ZKUtil.createEphemeralNodeAndWatch(zkw, node, rt.toByteArray())) {
      throw new IOException("Failed create of ephemeral " + node);
    }
    // Transition node from SPLITTING to SPLITTING and pick up version so we
    // can be sure this znode is ours; version is needed deleting.
    return transitionNodeSplitting(zkw, region, serverName, -1);
  }

  // Copied from SplitTransaction rather than open the method over there in
  // the regionserver package.
  private static int transitionNodeSplitting(final ZooKeeperWatcher zkw,
      final HRegionInfo parent,
      final ServerName serverName, final int version)
  throws KeeperException, IOException {
    return ZKAssign.transitionNode(zkw, parent, serverName,
      EventType.RS_ZK_REGION_SPLITTING, EventType.RS_ZK_REGION_SPLITTING, version);
  }

  private void unassign(final AssignmentManager am, final ServerName sn,
      final HRegionInfo hri) {
    // Before I can unassign a region, I need to set it online.
    am.regionOnline(hri, sn);
    // Unassign region.
    am.unassign(hri);
  }

  /**
   * Create an {@link AssignmentManagerWithExtrasForTesting} that has mocked
   * {@link CatalogTracker} etc.
   * @param server
   * @param manager
   * @return An AssignmentManagerWithExtras with mock connections, etc.
   * @throws IOException
   * @throws KeeperException
   */
  private AssignmentManagerWithExtrasForTesting setUpMockedAssignmentManager(final Server server,
      final ServerManager manager)
  throws IOException, KeeperException, ServiceException {
    // We need a mocked catalog tracker. Its used by our AM instance.
    CatalogTracker ct = Mockito.mock(CatalogTracker.class);
    // Make an RS Interface implementation. Make it so a scanner can go against
    // it and a get to return the single region, REGIONINFO, this test is
    // messing with. Needed when "new master" joins cluster. AM will try and
    // rebuild its list of user regions and it will also get the HRI that goes
    // with an encoded name by doing a Get on .META.
    ClientProtocol ri = Mockito.mock(ClientProtocol.class);
    // Get a meta row result that has region up on SERVERNAME_A for REGIONINFO
    Result r = Mocking.getMetaTableRowResult(REGIONINFO, SERVERNAME_A);
    ScanResponse.Builder builder = ScanResponse.newBuilder();
    builder.setMoreResults(false);
    builder.addResult(ProtobufUtil.toResult(r));
    Mockito.when(ri.scan(
      (RpcController)Mockito.any(), (ScanRequest)Mockito.any())).
        thenReturn(builder.build());
    // If a get, return the above result too for REGIONINFO
    GetResponse.Builder getBuilder = GetResponse.newBuilder();
    getBuilder.setResult(ProtobufUtil.toResult(r));
    Mockito.when(ri.get((RpcController)Mockito.any(), (GetRequest) Mockito.any())).
      thenReturn(getBuilder.build());
    // Get a connection w/ mocked up common methods.
    HConnection connection = HConnectionTestingUtility.
      getMockedConnectionAndDecorate(HTU.getConfiguration(), null,
        ri, SERVERNAME_B, REGIONINFO);
    // Make it so we can get the connection from our mocked catalogtracker
    Mockito.when(ct.getConnection()).thenReturn(connection);
    // Create and startup an executor. Used by AM handling zk callbacks.
    ExecutorService executor = startupMasterExecutor("mockedAMExecutor");
    this.balancer = LoadBalancerFactory.getLoadBalancer(server.getConfiguration());
    AssignmentManagerWithExtrasForTesting am = new AssignmentManagerWithExtrasForTesting(
        server, manager, ct, this.balancer, executor);
    return am;
  }

  /**
   * An {@link AssignmentManager} with some extra facility used testing
   */
  class AssignmentManagerWithExtrasForTesting extends AssignmentManager {
    // Keep a reference so can give it out below in {@link #getExecutorService}
    private final ExecutorService es;
    // Ditto for ct
    private final CatalogTracker ct;
    boolean processRITInvoked = false;
    boolean assignInvoked = false;
    AtomicBoolean gate = new AtomicBoolean(true);

    public AssignmentManagerWithExtrasForTesting(final Server master,
        final ServerManager serverManager, final CatalogTracker catalogTracker,
        final LoadBalancer balancer, final ExecutorService service)
    throws KeeperException, IOException {
      super(master, serverManager, catalogTracker, balancer, service, null);
      this.es = service;
      this.ct = catalogTracker;
    }

    @Override
    boolean processRegionInTransition(String encodedRegionName,
        HRegionInfo regionInfo,
        Map<ServerName, List<Pair<HRegionInfo, Result>>> deadServers)
        throws KeeperException, IOException {
      this.processRITInvoked = true;
      return super.processRegionInTransition(encodedRegionName, regionInfo,
          deadServers);
    }
    @Override
    void processRegionsInTransition(final RegionTransition rt,
        final HRegionInfo regionInfo,
        final Map<ServerName, List<Pair<HRegionInfo, Result>>> deadServers,
        final int expectedVersion) throws KeeperException {
      while (this.gate.get()) Threads.sleep(1);
      super.processRegionsInTransition(rt, regionInfo, deadServers, expectedVersion);
    }

    @Override
    public void assign(HRegionInfo region, boolean setOfflineInZK, boolean forceNewPlan,
        boolean hijack) {
      super.assign(region, setOfflineInZK, forceNewPlan, hijack);
      this.gate.set(true);
    }
    
    @Override
    public ServerName getRegionServerOfRegion(HRegionInfo hri) {
      return SERVERNAME_A;
    }
    
    @Override
    public void assign(java.util.List<HRegionInfo> regions, java.util.List<ServerName> servers) 
    {
      assignInvoked = true;
    };
    /** reset the watcher */
    void setWatcher(ZooKeeperWatcher watcher) {
      this.watcher = watcher;
    }
    
    /**
     * @return ExecutorService used by this instance.
     */
    ExecutorService getExecutorService() {
      return this.es;
    }

    /**
     * @return CatalogTracker used by this AM (Its a mock).
     */
    CatalogTracker getCatalogTracker() {
      return this.ct;
    }
  }

  /**
   * Call joinCluster on the passed AssignmentManager.  Do it in a thread
   * so it runs independent of what all else is going on.  Try to simulate
   * an AM running insided a failed over master by clearing all in-memory
   * AM state first.
  */
  private void startFakeFailedOverMasterAssignmentManager(final AssignmentManager am,
      final ZooKeeperWatcher watcher) {
    // Make sure our new AM gets callbacks; once registered, we can't unregister.
    // Thats ok because we make a new zk watcher for each test.
    watcher.registerListenerFirst(am);
    Thread t = new Thread("RunAmJoinCluster") {
      public void run() {
        // Call the joinCluster function as though we were doing a master
        // failover at this point. It will stall just before we go to add
        // the RIT region to our RIT Map in AM at processRegionsInTransition.
        // First clear any inmemory state from AM so it acts like a new master
        // coming on line.
        am.regionsInTransition.clear();
        am.regionPlans.clear();
        try {
          am.joinCluster();
        } catch (IOException e) {
          throw new RuntimeException(e);
        } catch (KeeperException e) {
          throw new RuntimeException(e);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      };
    };
    t.start();
    while (!t.isAlive()) Threads.sleep(1);
  }

  @org.junit.Rule
  public org.apache.hadoop.hbase.ResourceCheckerJUnitRule cu =
    new org.apache.hadoop.hbase.ResourceCheckerJUnitRule();
}
