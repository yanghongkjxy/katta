/**
 * Copyright 2008 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package net.sf.katta.loadtest;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.sf.katta.util.KattaException;
import net.sf.katta.zk.IZkChildListener;
import net.sf.katta.zk.IZkReconnectListener;
import net.sf.katta.zk.ZKClient;
import net.sf.katta.zk.ZkPathes;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.ipc.RPC;
import org.apache.log4j.Logger;

public class LoadTestStarter {

  static final Logger LOG = Logger.getLogger(LoadTestStarter.class);

  ZKClient _zkClient;
  private Map<String, LoadTestNodeMetaData> _testNodes = new HashMap<String, LoadTestNodeMetaData>();
  private int _numberOfTesterNodes;
  private int _threads;

  ChildListener _childListener;

  private String[] _indexNames;
  private String _queryString;
  private int _count;
  private int _runTime;

  class ChildListener implements IZkChildListener {
    @Override
    public void handleChildChange(String parentPath, List<String> currentChilds) throws KattaException {
      checkNodes(currentChilds);
    }
  }

  class ReconnectListener implements IZkReconnectListener {

    @Override
    public void handleReconnect() throws KattaException {
      LOG.info("Reconnecting test starter.");
      _zkClient.subscribeChildChanges(ZkPathes.LOADTEST_NODES, _childListener);
      checkNodes(_zkClient.getChildren(ZkPathes.LOADTEST_NODES));
    }
  }

  public LoadTestStarter(final ZKClient zkClient, int nodes, int threads, int runTime, String[] indexNames,
          String queryString, int count) {
    _zkClient = zkClient;
    _numberOfTesterNodes = nodes;
    _threads = threads;
    _indexNames = indexNames;
    _queryString = queryString;
    _count = count;
    _runTime = runTime;
  }

  public void start() throws KattaException {
    LOG.debug("Starting zk client...");
    _zkClient.getEventLock().lock();
    try {
      if (!_zkClient.isStarted()) {
        _zkClient.start(30000);
      }
      _zkClient.subscribeReconnects(new ReconnectListener());
      _childListener = new ChildListener();
      _zkClient.subscribeChildChanges(ZkPathes.LOADTEST_NODES, _childListener);
      checkNodes(_zkClient.getChildren(ZkPathes.LOADTEST_NODES));
    } finally {
      _zkClient.getEventLock().unlock();
    }
  }

  void checkNodes(List<String> children) throws KattaException {
    synchronized (_testNodes) {
      LOG.info("Nodes found: " + children);

      Set<String> obsoleteNodes = new HashSet<String>(_testNodes.keySet());
      obsoleteNodes.removeAll(children);
      for (String obsoleteNode : obsoleteNodes) {
        LOG.info("Lost connection to " + obsoleteNode);
        _testNodes.remove(obsoleteNode);
      }

      for (String child : children) {
        if (!_testNodes.containsKey(child)) {
          try {
            LoadTestNodeMetaData metaData = new LoadTestNodeMetaData();
            _zkClient.readData(ZkPathes.LOADTEST_NODES + "/" + child, metaData);
            LOG.info("New test node on " + metaData.getHost() + ":" + metaData.getPort());
            _testNodes.put(child, metaData);
          } catch (KattaException e) {
            LOG.info("Could not read meta data of load test node: " + child + ". It probably disappeared.");
          }
        }
      }
      if (_testNodes.size() >= _numberOfTesterNodes) {
        startTest();
      }
    }
  }

  private void startTest() throws KattaException {
    List<LoadTestNodeMetaData> testers = new ArrayList<LoadTestNodeMetaData>(_testNodes.values());
    List<ILoadTestNode> listeners = new ArrayList<ILoadTestNode>();
    for (int i = 0; i < _numberOfTesterNodes; i++) {
      try {
        listeners.add((ILoadTestNode) RPC.getProxy(ILoadTestNode.class, 0, new InetSocketAddress(testers
                .get(i).getHost(), testers.get(i).getPort()), new Configuration()));
      } catch (IOException e) {
        throw new KattaException("Failed to start tests.", e);
      }
    }
    _zkClient.unsubscribeAll();
    for (ILoadTestNode testCommandListener : listeners) {
      LOG.info("Starting test on node.");
      testCommandListener.startTest(_threads, _indexNames, _queryString, _count);
    }
    try {
      Thread.sleep(_runTime);
    } catch (InterruptedException e) {
      // ignore
    }
    for (ILoadTestNode testCommandListener : listeners) {
      LOG.info("Stopping test on node.");
      testCommandListener.stopTest();
    }
    shutdown();
  }

  public void shutdown() {
    _zkClient.getEventLock().lock();
    try {
      _zkClient.unsubscribeAll();
      _zkClient.close();
    } finally {
      _zkClient.getEventLock().unlock();
    }
  }
}
