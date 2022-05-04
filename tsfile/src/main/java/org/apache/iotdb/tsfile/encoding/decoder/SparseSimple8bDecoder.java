/*
 * Copyright 2022 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.iotdb.tsfile.encoding.decoder;

import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.utils.BitReader;

import java.io.IOException;
import java.nio.ByteBuffer;

/** @author Wang Haoyu */
public class SparseSimple8bDecoder extends Decoder {

  private long data[];

  private int readTotalCount = 0;

  private int nextReadIndex = 0;

  private Simple8bDecoder decoder = new Simple8bDecoder();

  public SparseSimple8bDecoder() {
    super(TSEncoding.SIMPLE8B_SPARSE);
  }

  @Override
  public boolean hasNext(ByteBuffer buffer) throws IOException {
    return (nextReadIndex < readTotalCount) || buffer.hasRemaining();
  }

  @Override
  public void reset() {
    nextReadIndex = 0;
    readTotalCount = 0;
  }

  @Override
  public int readInt(ByteBuffer buffer) {
    return (int) readLong(buffer);
  }

  @Override
  public long readLong(ByteBuffer buffer) {
    if (nextReadIndex == readTotalCount) {
      loadBlock(buffer);
      nextReadIndex = 0;
    }
    return data[nextReadIndex++];
  }

  private void loadBlock(ByteBuffer buffer) {
    BitReader reader = new BitReader(buffer);
    // Block size with 32 bits
    this.readTotalCount = (int) reader.next(32);
    // Number of nonzero values with 32 bits
    int m = (int) reader.next(32);
    // Decode index sequence
    int[] index = decodeIndex(m, reader);
    reader.skip();
    // Decode value sequence
    long[] value = decodeValue(m, buffer);
    // Sparse to dense
    this.data = new long[readTotalCount];
    for (int i = 0; i < m; i++) {
      data[index[i]] = value[i];
    }
  }

  private long[] decodeValue(int m, ByteBuffer buffer) {
    long value[] = new long[m];
    for (int i = 0; i < m; i++) {
      value[i] = decoder.readLong(buffer);
    }
    return value;
  }

  private int[] decodeIndex(int m, BitReader reader) {
    int[] value = new int[m];
    int bitsWidth = getValueWidth(getValueWidth(readTotalCount - 1));
    for (int i = 0; i < m; i += 8) {
      int bits = (int) reader.next(bitsWidth);
      for (int j = i; j < Math.min(i + 8, m); j++) {
        value[j] = (int) reader.next(bits);
      }
    }
    return value;
  }

  /**
   * Get the valid bit width of x
   *
   * @param x
   * @return valid bit width
   */
  private int getValueWidth(long x) {
    return 64 - Long.numberOfLeadingZeros(x);
  }
}
