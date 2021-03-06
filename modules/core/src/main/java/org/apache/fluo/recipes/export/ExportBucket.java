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

package org.apache.fluo.recipes.export;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;

import com.google.common.base.Preconditions;
import org.apache.fluo.api.client.TransactionBase;
import org.apache.fluo.api.config.ScannerConfiguration;
import org.apache.fluo.api.data.Bytes;
import org.apache.fluo.api.data.Column;
import org.apache.fluo.api.data.RowColumn;
import org.apache.fluo.api.data.Span;
import org.apache.fluo.api.iterator.ColumnIterator;
import org.apache.fluo.api.iterator.RowIterator;
import org.apache.fluo.recipes.impl.BucketUtil;
import org.apache.fluo.recipes.types.StringEncoder;
import org.apache.fluo.recipes.types.TypeLayer;
import org.apache.fluo.recipes.types.TypedTransactionBase;

/**
 * This class encapsulates a buckets serialization code.
 */
class ExportBucket {
  private static final String NOTIFICATION_CF = "fluoRecipes";
  private static final String NOTIFICATION_CQ_PREFIX = "eq:";
  private static final Column EXPORT_COL = new Column("e", "v");
  private static final Column NEXT_COL = new Column("e", "next");

  static Column newNotificationColumn(String queueId) {
    return new Column(NOTIFICATION_CF, NOTIFICATION_CQ_PREFIX + queueId);
  }

  private final TypedTransactionBase ttx;
  private final String qid;
  private final Bytes bucketRow;

  static Bytes generateBucketRow(String qid, int bucket, int numBuckets) {
    return Bytes.of(qid + ":" + BucketUtil.genBucketId(bucket, numBuckets));
  }

  ExportBucket(TransactionBase tx, String qid, int bucket, int numBuckets) {
    // TODO encode in a more robust way... but for now fail early
    Preconditions.checkArgument(!qid.contains(":"), "Export QID can not contain :");
    this.ttx = new TypeLayer(new StringEncoder()).wrap(tx);
    this.qid = qid;
    this.bucketRow = generateBucketRow(qid, bucket, numBuckets);
  }

  ExportBucket(TransactionBase tx, Bytes bucketRow) {
    this.ttx = new TypeLayer(new StringEncoder()).wrap(tx);

    int colonLoc = -1;

    for (int i = 0; i < bucketRow.length(); i++) {
      if (bucketRow.byteAt(i) == ':') {
        colonLoc = i;
        break;
      }
    }

    Preconditions.checkArgument(colonLoc != -1 && colonLoc != bucketRow.length(),
        "Invalid bucket row " + bucketRow);
    Preconditions.checkArgument(bucketRow.byteAt(bucketRow.length() - 1) == ':',
        "Invalid bucket row " + bucketRow);

    this.bucketRow = bucketRow.subSequence(0, bucketRow.length() - 1);
    this.qid = bucketRow.subSequence(0, colonLoc).toString();
  }

  private static byte[] encSeq(long l) {
    byte[] ret = new byte[8];
    ret[0] = (byte) (l >>> 56);
    ret[1] = (byte) (l >>> 48);
    ret[2] = (byte) (l >>> 40);
    ret[3] = (byte) (l >>> 32);
    ret[4] = (byte) (l >>> 24);
    ret[5] = (byte) (l >>> 16);
    ret[6] = (byte) (l >>> 8);
    ret[7] = (byte) (l >>> 0);
    return ret;
  }

  private static long decodeSeq(Bytes seq) {
    return (((long) seq.byteAt(0) << 56) + ((long) (seq.byteAt(1) & 255) << 48)
        + ((long) (seq.byteAt(2) & 255) << 40) + ((long) (seq.byteAt(3) & 255) << 32)
        + ((long) (seq.byteAt(4) & 255) << 24) + ((seq.byteAt(5) & 255) << 16)
        + ((seq.byteAt(6) & 255) << 8) + ((seq.byteAt(7) & 255) << 0));
  }


  public void add(long seq, byte[] key, byte[] value) {
    Bytes row =
        Bytes.newBuilder(bucketRow.length() + 1 + key.length + 8).append(bucketRow).append(":")
            .append(key).append(encSeq(seq)).toBytes();
    ttx.set(row, EXPORT_COL, Bytes.of(value));
  }

  /**
   * Computes the minimial row for a bucket
   */
  private Bytes getMinimalRow() {
    return Bytes.newBuilder(bucketRow.length() + 1).append(bucketRow).append(":").toBytes();
  }

  public void notifyExportObserver() {
    ttx.mutate().row(getMinimalRow()).col(newNotificationColumn(qid)).weaklyNotify();
  }

  public Iterator<ExportEntry> getExportIterator(Bytes continueRow) {
    ScannerConfiguration sc = new ScannerConfiguration();

    if (continueRow != null) {
      Span tmpSpan = Span.prefix(bucketRow);
      Span nextSpan =
          new Span(new RowColumn(continueRow, EXPORT_COL), true, tmpSpan.getEnd(),
              tmpSpan.isEndInclusive());
      sc.setSpan(nextSpan);
    } else {
      sc.setSpan(Span.prefix(bucketRow));
    }

    sc.fetchColumn(EXPORT_COL.getFamily(), EXPORT_COL.getQualifier());
    RowIterator iter = ttx.get(sc);

    if (iter.hasNext()) {
      return new ExportIterator(iter);
    } else {
      return Collections.<ExportEntry>emptySet().iterator();
    }
  }

  private class ExportIterator implements Iterator<ExportEntry> {

    private RowIterator rowIter;
    private Bytes lastRow;

    public ExportIterator(RowIterator rowIter) {
      this.rowIter = rowIter;
    }

    @Override
    public boolean hasNext() {
      return rowIter.hasNext();
    }

    @Override
    public ExportEntry next() {
      Entry<Bytes, ColumnIterator> rowCol = rowIter.next();
      Bytes row = rowCol.getKey();

      Bytes keyBytes = row.subSequence(bucketRow.length() + 1, row.length() - 8);
      Bytes seqBytes = row.subSequence(row.length() - 8, row.length());

      ExportEntry ee = new ExportEntry();

      ee.key = keyBytes.toArray();
      ee.seq = decodeSeq(seqBytes);
      // TODO maybe leave as Bytes?
      ee.value = rowCol.getValue().next().getValue().toArray();

      lastRow = row;

      return ee;
    }

    @Override
    public void remove() {
      ttx.mutate().row(lastRow).col(EXPORT_COL).delete();
    }
  }

  public Bytes getContinueRow() {
    return ttx.get(getMinimalRow(), NEXT_COL);
  }

  public void setContinueRow(ExportEntry ee) {
    Bytes nextRow =
        Bytes.newBuilder(bucketRow.length() + 1 + ee.key.length + 8).append(bucketRow).append(":")
            .append(ee.key).append(encSeq(ee.seq)).toBytes();

    ttx.set(getMinimalRow(), NEXT_COL, nextRow);
  }

  public void clearContinueRow() {
    ttx.delete(getMinimalRow(), NEXT_COL);
  }
}
