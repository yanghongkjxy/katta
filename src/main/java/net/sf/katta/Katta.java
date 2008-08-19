/**
 * Copyright 2008 The Apache Software Foundation
 *
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
package net.sf.katta;

import java.util.ArrayList;
import java.util.List;

import net.sf.katta.client.Client;
import net.sf.katta.client.IClient;
import net.sf.katta.index.DeployedShard;
import net.sf.katta.index.IndexMetaData;
import net.sf.katta.master.IPaths;
import net.sf.katta.master.Master;
import net.sf.katta.node.Hit;
import net.sf.katta.node.Hits;
import net.sf.katta.node.IQuery;
import net.sf.katta.node.Node;
import net.sf.katta.node.NodeMetaData;
import net.sf.katta.node.Query;
import net.sf.katta.util.KattaException;
import net.sf.katta.util.ZkConfiguration;
import net.sf.katta.zk.ZKClient;
import net.sf.katta.zk.ZkPathes;
import net.sf.katta.zk.ZkServer;

import org.apache.log4j.Logger;

public class Katta {

  private final static Logger LOG = Logger.getLogger(Katta.class);

  private final ZKClient _zkClient;

  public Katta() throws KattaException {
    final ZkConfiguration configuration = new ZkConfiguration();
    _zkClient = new ZKClient(configuration);
    _zkClient.start(10000);
  }

  public static void main(final String[] args) throws KattaException {
    if (args.length < 1) {
      printUsageAndExit();
    }
    final String command = args[0];
    // static methods first
    if (command.endsWith("startNode")) {
      startNode();
    } else if (command.endsWith("startMaster")) {
      startMaster();
    } else {
      // non static methods
      Katta katta = null;
      if (command.equals("search")) {
        final String[] indexNames = args[1].split(",");
        final String query = args[2];
        if (args.length > 3) {
          final int count = Integer.parseInt(args[3]);
          Katta.search(indexNames, query, count);
        } else {
          Katta.search(indexNames, query);
        }
      } else if (command.endsWith("addIndex")) {
        int replication = 3;
        if (args.length == 5) {
          replication = Integer.parseInt(args[4]);
        }
        katta = new Katta();
        katta.addIndex(args[1], args[2], args[3], replication);
      } else if (command.endsWith("removeIndex")) {
        katta = new Katta();
        katta.removeIndex(args[1]);
      } else if (command.endsWith("listIndexes")) {
        katta = new Katta();
        katta.listIndex();
      } else if (command.endsWith("listNodes")) {
        katta = new Katta();
        katta.listNodes();
      } else if (command.endsWith("showStructure")) {
        katta = new Katta();
        katta.showStructure();
      } else if (command.endsWith("listErrors")) {
        if (args.length > 1) {
          katta = new Katta();
          katta.showErrors(args[1]);
        } else {
          System.err.println("Missing parameter index name.");
          printUsageAndExit();
        }
      } else if (command.endsWith("redeployIndex")) {
        if (args.length > 1) {
          katta = new Katta();
          katta.redeployIndex(args[1]);
        } else {
          System.err.println("Missing parameter index name.");
          printUsageAndExit();
        }
      }
      if (katta != null) {
        katta.close();
      }
    }
  }

  private void redeployIndex(final String indexName) throws KattaException {
    String indexPath = IPaths.INDEXES + "/" + indexName;
    IndexMetaData indexMetaData = new IndexMetaData();
    if (_zkClient.exists(indexPath)) {
      _zkClient.readData(indexPath, indexMetaData);
      try {
        removeIndex(indexName);
        Thread.sleep(5000);
        addIndex(indexName, indexMetaData.getPath(), indexMetaData.getAnalyzerClassName(), indexMetaData
            .getReplicationLevel());
      } catch (InterruptedException e) {
        LOG.error("Redeployment of index '" + indexName + "' interrupted.");
      }
    } else {
      System.err.println("Index '" + indexName + "' not found.");
    }

  }

  private void showErrors(final String indexName) throws KattaException {
    System.out.println("List of errors:");
    String indexPath = IPaths.INDEXES + "/" + indexName;
    if (_zkClient.exists(indexPath)) {
      List<String> shards = _zkClient.getChildren(indexPath);
      for (String shardName : shards) {
        System.out.println("Shard: " + shardName);
        String shardPath = IPaths.SHARD_TO_NODE + "/" + shardName;
        if (_zkClient.exists(shardPath)) {
          List<String> nodes = _zkClient.getChildren(shardPath);
          for (String node : nodes) {
            System.out.print("\tNode: " + node);
            String shardToNodePath = shardPath + "/" + node;
            DeployedShard deployedShard = new DeployedShard();
            _zkClient.readData(shardToNodePath, deployedShard);
            if (deployedShard.hasError()) {
              System.out.println("\tError: " + deployedShard.getErrorMsg());
            } else {
              System.out.println("\tNo Error");
            }
          }
        }
      }
    }
  }

  public static void startMaster() throws KattaException {
    final ZkConfiguration conf = new ZkConfiguration();
    final ZkServer zkServer = new ZkServer(conf);
    final ZKClient client = new ZKClient(conf);
    final Master master = new Master(client);
    master.start();
    zkServer.join();
  }

  public static void startNode() throws KattaException {
    final ZkConfiguration configuration = new ZkConfiguration();
    final ZKClient client = new ZKClient(configuration);
    final Node node = new Node(client);
    node.start();
    node.join();
  }

  public void removeIndex(final String indexName) throws KattaException {
    final String indexPath = IPaths.INDEXES + "/" + indexName;
    if (_zkClient.exists(indexPath)) {
      _zkClient.deleteRecursive(indexPath);
    } else {
      System.err.println("Unknown index:" + indexName);
    }
  }

  public void showStructure() throws KattaException {
    _zkClient.showFolders();
  }

  public void listNodes() throws KattaException {
    final List<String> nodes = _zkClient.getChildren(ZkPathes.NODES);
    int inServiceNodeCount = 0;
    final Table table = new Table();
    for (final String node : nodes) {
      final String path = ZkPathes.getNodePath(node);
      final NodeMetaData nodeMetaData = new NodeMetaData();
      _zkClient.readData(path, nodeMetaData);
      boolean inService = nodeMetaData.isHealth();
      if (inService) {
        inServiceNodeCount++;
      }
      table.addRow(new String[] { nodeMetaData.getName(), nodeMetaData.getStartTimeAsDate(), "" + inService,
          nodeMetaData.getStatus(), nodeMetaData.isStarting() + "" });
    }
    table.setHeader(new String[] { "Name", "Start time", "Healthy",
        "Status (" + inServiceNodeCount + "/" + nodes.size() + " nodes in Service)", "Starting" });
    System.out.println(table.toString());
  }

  public void listIndex() throws KattaException {
    final Table t = new Table(new String[] { "Name", "Deployed", "Analyzer", "Path" });

    final List<String> indexes = _zkClient.getChildren(IPaths.INDEXES);
    for (final String index : indexes) {
      final IndexMetaData metaData = new IndexMetaData();
      _zkClient.readData(IPaths.INDEXES + "/" + index, metaData);
      t.addRow(new String[] { index, metaData.getState().toString(), metaData.getAnalyzerClassName(),
          metaData.getPath() });
      // maybe show shards
      // maybe show serving nodes..
      // maybe show replication level...
    }
    System.out.println(t.toString());
  }

  public void addIndex(final String name, final String path, final String analyzerClass, final int replicationLevel)
      throws KattaException {
    final String indexPath = IPaths.INDEXES + "/" + name;
    if (name.trim().equals("*")) {
      System.err.println("Index with name " + name + " isn't allowed.");
      return;
    }
    if (_zkClient.exists(indexPath)) {
      System.out.println("Index with name " + name + " already exists.");
    }

    _zkClient.create(indexPath, new IndexMetaData(path, analyzerClass, replicationLevel,
        IndexMetaData.IndexState.ANNOUNCED));
    final IndexMetaData data = new IndexMetaData();
    while (true) {
      _zkClient.readData(indexPath, data);
      if (data.getState() == IndexMetaData.IndexState.DEPLOYED) {
        break;
      } else if (data.getState() == IndexMetaData.IndexState.DEPLOY_ERROR) {
        System.err.println("not deployed.");
        return;
      }
      System.out.print(".");
      try {
        Thread.sleep(1000);
      } catch (final InterruptedException e) {
        e.printStackTrace();
      }
    }
    System.out.println("deployed index " + name + ".");

  }

  public static void search(final String[] indexNames, final String queryString, final int count) throws KattaException {
    final IClient client = new Client();
    final IQuery query = new Query(queryString);
    final long start = System.currentTimeMillis();
    final Hits hits = client.search(query, indexNames, count);
    final long end = System.currentTimeMillis();
    System.out.println(hits.size() + " hits found in " + ((end - start) / 1000.0) + "sec.");
    int index = 0;
    final Table table = new Table(new String[] { "Hit", "Node", "Shard", "DocId", "Score" });
    for (final Hit hit : hits.getHits()) {
      table
          .addRow(new String[] { "" + index, hit.getNode(), hit.getShard(), "" + hit.getDocId(), "" + hit.getScore() });
      index++;
    }
    System.out.println(table.toString());
  }

  public static void search(final String[] indexNames, final String queryString) throws KattaException {
    final IClient client = new Client();
    final IQuery query = new Query(queryString);
    final long start = System.currentTimeMillis();
    final int hitsSize = client.count(query, indexNames);
    final long end = System.currentTimeMillis();
    System.out.println(hitsSize + " Hits found in " + ((end - start) / 1000.0) + "sec.");
  }

  private static void printUsageAndExit() {
    System.err.println("Usage: ");
    System.err
        .println("\tsearch <index name>[,<index name>,...] \"<query>\" [count]\tSearch in supplied indexes. The query should be in \". If you supply a result count hit details will be printed. To search in all indices write \"*\"");
    System.err.println("\tlistIndexes\tLists all indexes.");
    System.err.println("\tlistNodes\tLists all nodes.");
    System.err.println("\tstartMaster\tStarts a local master.");
    System.err.println("\tstartNode\tStarts a local node.");
    System.err.println("\tshowStructure\tShows the structure of a Katta installation.");
    System.err.println("\tremoveIndex <index name>\tRemove a index from a Katta installation.");
    System.err
        .println("\taddIndex <index name> <path to index> <lucene analyzer class> [<replication level>]\tAdd a index to a Katta installation.");
    System.err.println("\tlistErrors <index name>\tLists all deploy errors for a specified index.");
    System.err.println("\tredeployIndex <index name>\tTries to deploy an index.");
    System.exit(1);
  }

  private static class Table {
    private String[] _header;
    private final List<String[]> _rows = new ArrayList<String[]>();

    public Table(final String[] header) {
      _header = header;
    }

    public Table() {
      // setting header later
    }

    public void setHeader(String[] header) {
      _header = header;
    }

    public void addRow(final String[] row) {
      _rows.add(row);
    }

    @Override
    public String toString() {
      final StringBuilder builder = new StringBuilder();
      builder.append("\n");
      final int[] columnSizes = getColumnSizes(_header, _rows);
      int rowWidth = 0;
      for (final int columnSize : columnSizes) {
        rowWidth += columnSize + 2;
      }
      // header
      builder.append("| ");
      for (int i = 0; i < _header.length; i++) {
        final String column = _header[i];
        builder.append(column + getChar(columnSizes[i] - column.length(), " ") + " | ");
      }
      builder.append("\n=");
      builder.append(getChar(rowWidth + columnSizes.length, "=") + "\n");

      for (final String[] row : _rows) {
        builder.append("| ");
        for (int i = 0; i < row.length; i++) {
          builder.append(row[i] + getChar(columnSizes[i] - row[i].length(), " ") + " | ");
        }
        builder.append("\n-");
        builder.append(getChar(rowWidth + columnSizes.length, "-") + "\n");
      }

      return builder.toString();
    }

    private String getChar(final int count, final String character) {
      String spaces = "";
      for (int j = 0; j < count; j++) {
        spaces += character;
      }
      return spaces;
    }

    private int[] getColumnSizes(final String[] header, final List<String[]> rows) {
      final int[] sizes = new int[header.length];
      for (int i = 0; i < sizes.length; i++) {
        int min = header[i].length();
        for (final String[] row : rows) {
          if (row[i].length() > min) {
            min = row[i].length();
          }
        }
        sizes[i] = min;
      }

      return sizes;
    }
  }

  public void close() {
    if (_zkClient != null) {
      _zkClient.close();
    }
  }

}
