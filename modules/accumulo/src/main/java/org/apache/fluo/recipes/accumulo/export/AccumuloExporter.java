/*
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for additional information regarding
 * copyright ownership. The ASF licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License. You may obtain a
 * copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package org.apache.fluo.recipes.accumulo.export;

import java.util.ArrayList;
import java.util.Iterator;

import org.apache.accumulo.core.data.Mutation;
import org.apache.fluo.api.config.FluoConfiguration;
import org.apache.fluo.api.config.SimpleConfiguration;
import org.apache.fluo.api.observer.Observer.Context;
import org.apache.fluo.recipes.export.Exporter;
import org.apache.fluo.recipes.export.SequencedExport;

/**
 * An {@link Exporter} that takes {@link AccumuloExport} objects and writes mutations to Accumulo
 *
 * @param <K> Export queue key type
 */
public class AccumuloExporter<K> extends Exporter<K, AccumuloExport<K>> {

  private SharedBatchWriter sbw;

  @Override
  public void init(String queueId, Context context) throws Exception {

    SimpleConfiguration appConf = context.getAppConfiguration();

    String instanceName = appConf.getString("recipes.accumuloExporter." + queueId + ".instance");
    String zookeepers = appConf.getString("recipes.accumuloExporter." + queueId + ".zookeepers");
    String user = appConf.getString("recipes.accumuloExporter." + queueId + ".user");
    // TODO look into using delegation token
    String password = appConf.getString("recipes.accumuloExporter." + queueId + ".password");
    String table = appConf.getString("recipes.accumuloExporter." + queueId + ".table");

    sbw = SharedBatchWriter.getInstance(instanceName, zookeepers, user, password, table);
  }

  public static void setExportTableInfo(FluoConfiguration fconf, String queueId, TableInfo ti) {
    SimpleConfiguration appConf = fconf.getAppConfiguration();
    appConf.setProperty("recipes.accumuloExporter." + queueId + ".instance", ti.instanceName);
    appConf.setProperty("recipes.accumuloExporter." + queueId + ".zookeepers", ti.zookeepers);
    appConf.setProperty("recipes.accumuloExporter." + queueId + ".user", ti.user);
    appConf.setProperty("recipes.accumuloExporter." + queueId + ".password", ti.password);
    appConf.setProperty("recipes.accumuloExporter." + queueId + ".table", ti.table);
  }

  @Override
  protected void processExports(Iterator<SequencedExport<K, AccumuloExport<K>>> exports) {
    ArrayList<Mutation> buffer = new ArrayList<>();

    while (exports.hasNext()) {
      SequencedExport<K, AccumuloExport<K>> export = exports.next();
      buffer.addAll(export.getValue().toMutations(export.getKey(), export.getSequence()));
    }

    if (buffer.size() > 0) {
      sbw.write(buffer);
    }
  }
}
