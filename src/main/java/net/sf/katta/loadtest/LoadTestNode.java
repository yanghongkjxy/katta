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
import java.util.List;
import java.util.Random;
import java.util.Vector;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import net.sf.katta.client.Client;
import net.sf.katta.client.IClient;
import net.sf.katta.node.BaseRpcServer;
import net.sf.katta.node.Query;
import net.sf.katta.util.KattaException;
import net.sf.katta.util.LoadTestNodeConfiguration;
import net.sf.katta.zk.IZkReconnectListener;
import net.sf.katta.zk.ZKClient;
import net.sf.katta.zk.ZkPathes;

import org.apache.log4j.Logger;
import org.apache.zookeeper.CreateMode;

public class LoadTestNode extends BaseRpcServer implements ILoadTestNode {

  final static Logger LOG = Logger.getLogger(LoadTestNode.class);

  private ZKClient _zkClient;
  ScheduledExecutorService _executorService;
  private List<Integer> _statistics;

  private Lock _shutdownLock = new ReentrantLock(true);
  private volatile boolean _shutdown = false;
  LoadTestNodeConfiguration _configuration;
  LoadTestNodeMetaData _metaData;
  IClient _client = new Client();

  private String _currentNodeName;

  private final class TestSearcherRunnable implements Runnable {
    private int _count;
    private String[] _indexNames;
    private String _queryString;
    private Random _random = new Random(System.currentTimeMillis());

    TestSearcherRunnable(int count, String[] indexNames, String queryString) {
      _count = count;
      _indexNames = indexNames;
      _queryString = queryString;
    }

    @SuppressWarnings("deprecation")
    @Override
    public void run() {
      // TODO PVo search for different terms
      try {
        long startTime = System.currentTimeMillis();
        _client.search(new Query(_queryString), _indexNames, _count);
        long endTime = System.currentTimeMillis();
        writeStatistics((int) (endTime - startTime));
      } catch (KattaException e) {
        writeStatistics(-1);
        LOG.error("Search failed.", e);
      }
      _executorService.schedule(this, _random.nextInt(_configuration.getTestDelay()), TimeUnit.MILLISECONDS);
    }
  }

  class ReconnectListener implements IZkReconnectListener {

    @Override
    public void handleReconnect() throws KattaException {
      LOG.info("Reconnecting load test node.");
      announceTestSearcher(_metaData);
    }
  }

  public LoadTestNode(final ZKClient zkClient, LoadTestNodeConfiguration configuration) throws KattaException {
    _zkClient = zkClient;
    _configuration = configuration;
  }

  public void start() throws KattaException {
    LOG.debug("Starting zk client...");
    _statistics = new Vector<Integer>();
    _zkClient.getEventLock().lock();
    try {
      if (!_zkClient.isStarted()) {
        _zkClient.start(30000);
      }
      _zkClient.subscribeReconnects(new ReconnectListener());
    } finally {
      _zkClient.getEventLock().unlock();
    }
    startRpcServer(_configuration.getStartPort());
    _metaData = new LoadTestNodeMetaData();
    _metaData.setHost(getRpcHostName());
    _metaData.setPort(getRpcServerPort());
    announceTestSearcher(_metaData);
  }

  void announceTestSearcher(LoadTestNodeMetaData metaData) throws KattaException {
    LOG.info("Announcing new node.");
    if (_currentNodeName != null) {
      _zkClient.deleteIfExists(_currentNodeName);
    }
    _currentNodeName = _zkClient.create(ZkPathes.LOADTEST_NODES + "/node-", metaData, CreateMode.EPHEMERAL_SEQUENTIAL);
  }

  public void shutdown() {
    _shutdownLock.lock();
    try {
      if (_shutdown) {
        return;
      }
      _shutdown = true;
      stopRpcServer();
      if (_executorService != null) {
        _executorService.shutdown();
        try {
          _executorService.awaitTermination(10, TimeUnit.SECONDS);
        } catch (InterruptedException e1) {
          // ignore
        }
      }

      _zkClient.close();
    } finally {
      _shutdownLock.unlock();
    }
  }

  @Override
  public void startTest(int threads, final String[] indexNames, final String queryString, final int count) {
    LOG.info("Requested to run test with " + threads + " threads.");
    _executorService = Executors.newScheduledThreadPool(threads);
    _zkClient.unsubscribeAll();
    for (int i = 0; i < threads; i++) {
      _executorService.submit(new TestSearcherRunnable(count, indexNames, queryString));
    }
  }

  void writeStatistics(int elapsedTime) {
    _statistics.add(elapsedTime);
  }

  @Override
  public void stopTest() {
    LOG.info("Requested to stop test.");
    _executorService.shutdown();

    // shutdown from different thread
    new Thread() {
      @Override
      public void run() {
        try {
          Thread.sleep(500);
        } catch (InterruptedException e) {
          // ignore
        }
        shutdown();
      }
    }.start();
  }

  @Override
  public long getProtocolVersion(String arg0, long arg1) throws IOException {
    return 0;
  }

  @Override
  protected void setup() {
    // do nothing
  }

  @Override
  public List<Integer> getResults() throws IOException {
    return _statistics;
  }
}
