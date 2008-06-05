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
package net.sf.katta.node;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import net.sf.katta.util.Logger;

import org.apache.hadoop.io.Writable;

public class HitsMapWritable implements Writable {

  private String _serverName;

  private final Map<String, List<Hit>> _hitToShard = new ConcurrentHashMap<String, List<Hit>>();

  private int _totalHits;

  public HitsMapWritable() {
  }

  public HitsMapWritable(final String serverName) {
    _serverName = serverName;
  }

  public void readFields(final DataInput in) throws IOException {
    long start = 0;
    if (Logger.isDebug()) {
      start = System.currentTimeMillis();
    }
    _serverName = in.readUTF();
    _totalHits = in.readInt();
    if (Logger.isDebug()) {
      Logger.debug("HitsMap reading start at: " + start + " for server " + _serverName);
    }
    final int shardSize = in.readInt();
    for (int i = 0; i < shardSize; i++) {
      final String shardName = in.readUTF();
      final int hitSize = in.readInt();
      for (int j = 0; j < hitSize; j++) {
        final float score = in.readFloat();
        final int docId = in.readInt();
        final Hit hit = new Hit(shardName, _serverName, score, docId);
        addHitToShard(shardName, hit);
      }
    }
    if (Logger.isDebug()) {
      final long end = System.currentTimeMillis();
      Logger.debug("HitsMap reading took " + (end - start) / 1000.0 + "sec.");
    }
  }

  public void write(final DataOutput out) throws IOException {
    long start = 0;
    if (Logger.isDebug()) {
      start = System.currentTimeMillis();
    }
    out.writeUTF(_serverName);
    out.writeInt(_totalHits);
    final Set<String> keySet = _hitToShard.keySet();
    out.writeInt(keySet.size());
    for (final String key : keySet) {
      out.writeUTF(key);
      final List<Hit> list = _hitToShard.get(key);
      out.writeInt(list.size());
      for (final Hit hit : list) {
        out.writeFloat(hit.getScore());
        out.writeInt(hit.getDocId());
      }
    }
    if (Logger.isDebug()) {
      final long end = System.currentTimeMillis();
      Logger.debug("HitsMap writing took " + (end - start) / 1000.0 + "sec.");
      Logger.debug("HitsMap writing ended at: " + end + " for server " + _serverName);
    }
  }

  public void addHitToShard(final String shard, final Hit hit) {
    List<Hit> hitList = _hitToShard.get(shard);
    if (hitList == null) {
      hitList = new ArrayList<Hit>();
      _hitToShard.put(shard, hitList);
    }
    hitList.add(hit);
  }

  public String getServerName() {
    return _serverName.toString();
  }

  public Hits getHits() {
    final Hits result = new Hits();
    result.setTotalHits(_totalHits);
    for (final List<Hit> hitList : _hitToShard.values()) {
      result.addHits(hitList);
    }
    return result;
  }

  public void addTotalHits(final int length) {
    _totalHits += length;
  }

  public int getTotalHits() {
    return _totalHits;
  }
}