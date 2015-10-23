/*
 * Copyright 2014 Fluo authors (see AUTHORS)
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

package io.fluo.recipes.export;

import java.util.Iterator;

import com.google.common.base.Function;
import com.google.common.collect.Iterators;
import io.fluo.api.client.TransactionBase;
import io.fluo.api.data.Bytes;
import io.fluo.api.data.Column;
import io.fluo.api.observer.AbstractObserver;
import io.fluo.recipes.serialization.SimpleSerializer;

public class ExportObserver<K, V> extends AbstractObserver {

  private String queueId;
  private Class<K> keyType;
  private Class<V> valType;
  SimpleSerializer serializer;
  private Exporter<K, V> exporter;

  protected String getQueueId() {
    return queueId;
  }

  SimpleSerializer getSerializer() {
    return serializer;
  }

  @SuppressWarnings("unchecked")
  @Override
  public void init(Context context) throws Exception {
    queueId = context.getParameters().get("queueId");
    ExportQueue.Options opts = new ExportQueue.Options(queueId, context.getAppConfiguration());

    // TODO defer loading classes... so that not done during fluo init
    // TODO move class loading to centralized place... also attempt to check type params
    keyType = (Class<K>) getClass().getClassLoader().loadClass(opts.keyType);
    valType = (Class<V>) getClass().getClassLoader().loadClass(opts.valueType);
    exporter =
        getClass().getClassLoader().loadClass(opts.exporterType).asSubclass(Exporter.class)
            .newInstance();

    serializer = SimpleSerializer.getInstance(context.getAppConfiguration());

    exporter.init(queueId, context);
  }

  @Override
  public ObservedColumn getObservedColumn() {
    return new ObservedColumn(ExportBucket.newNotificationColumn(queueId), NotificationType.WEAK);
  }

  @Override
  public void process(TransactionBase tx, Bytes row, Column column) throws Exception {
    ExportBucket bucket = new ExportBucket(tx, row);

    Iterator<SequencedExport<K, V>> exportIterator =
        Iterators.transform(bucket.getExportIterator(),
            new Function<ExportEntry, SequencedExport<K, V>>() {
              @Override
              public SequencedExport<K, V> apply(ExportEntry ee) {
                return new SequencedExport<>(serializer.deserialize(ee.key, keyType), serializer
                    .deserialize(ee.value, valType), ee.seq);
              }
            });

    exportIterator = Iterators.consumingIterator(exportIterator);

    exporter.processExports(exportIterator);
  }
}
