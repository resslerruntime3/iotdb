/*
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
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.iotdb.db.mpp.sql.planner.plan.node.write;

import org.apache.iotdb.common.rpc.thrift.TRegionReplicaSet;
import org.apache.iotdb.db.metadata.idtable.entry.IDeviceID;
import org.apache.iotdb.db.metadata.path.PartialPath;
import org.apache.iotdb.db.mpp.sql.planner.plan.node.PlanNodeId;
import org.apache.iotdb.db.mpp.sql.planner.plan.node.WritePlanNode;
import org.apache.iotdb.db.wal.buffer.IWALByteBufferView;
import org.apache.iotdb.db.wal.utils.WALWriteUtils;
import org.apache.iotdb.tsfile.exception.NotImplementedException;
import org.apache.iotdb.tsfile.file.metadata.enums.CompressionType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.file.metadata.enums.TSEncoding;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;
import org.apache.iotdb.tsfile.write.schema.MeasurementSchema;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public abstract class InsertNode extends WritePlanNode {

  /**
   * if use id table, this filed is id form of device path <br>
   * if not, this filed is device path<br>
   */
  protected PartialPath devicePath;

  protected boolean isAligned;
  protected MeasurementSchema[] measurementSchemas;
  protected String[] measurements;
  protected TSDataType[] dataTypes;
  // TODO(INSERT) need to change it to a function handle to update last time value
  //  protected IMeasurementMNode[] measurementMNodes;

  /**
   * device id reference, for reuse device id in both id table and memtable <br>
   * used in memtable
   */
  protected IDeviceID deviceID;

  /** Physical address of data region after splitting */
  TRegionReplicaSet dataRegionReplicaSet;

  protected InsertNode(PlanNodeId id) {
    super(id);
  }

  protected InsertNode(
      PlanNodeId id,
      PartialPath devicePath,
      boolean isAligned,
      MeasurementSchema[] measurementSchemas,
      TSDataType[] dataTypes) {
    super(id);
    this.devicePath = devicePath;
    this.isAligned = isAligned;
    this.measurementSchemas = measurementSchemas;
    this.dataTypes = dataTypes;

    this.measurements = new String[measurementSchemas.length];
    for (int i = 0; i < measurementSchemas.length; i++) {
      if (measurementSchemas[i] != null) {
        measurements[i] = measurementSchemas[i].getMeasurementId();
      }
    }
  }

  public TRegionReplicaSet getDataRegionReplicaSet() {
    return dataRegionReplicaSet;
  }

  public void setDataRegionReplicaSet(TRegionReplicaSet dataRegionReplicaSet) {
    this.dataRegionReplicaSet = dataRegionReplicaSet;
  }

  public PartialPath getDevicePath() {
    return devicePath;
  }

  public void setDevicePath(PartialPath devicePath) {
    this.devicePath = devicePath;
  }

  public boolean isAligned() {
    return isAligned;
  }

  public void setAligned(boolean aligned) {
    isAligned = aligned;
  }

  public MeasurementSchema[] getMeasurementSchemas() {
    return measurementSchemas;
  }

  public void setMeasurementSchemas(MeasurementSchema[] measurementSchemas) {
    this.measurementSchemas = measurementSchemas;
  }

  public String[] getMeasurements() {
    return measurements;
  }

  public TSDataType[] getDataTypes() {
    return dataTypes;
  }

  public void setDataTypes(TSDataType[] dataTypes) {
    this.dataTypes = dataTypes;
  }

  public IDeviceID getDeviceID() {
    return deviceID;
  }

  public void setDeviceID(IDeviceID deviceID) {
    this.deviceID = deviceID;
  }

  public void serializeMeasurementSchemaToWAL(IWALByteBufferView buffer) {
    for (MeasurementSchema measurementSchema : measurementSchemas) {
      if (measurementSchema != null) {
        WALWriteUtils.write(measurementSchema.getMeasurementId(), buffer);

        WALWriteUtils.write(measurementSchema.getType(), buffer);

        WALWriteUtils.write(measurementSchema.getEncodingType(), buffer);

        WALWriteUtils.write(measurementSchema.getCompressor(), buffer);

        Map<String, String> props = measurementSchema.getProps();
        if (props == null) {
          WALWriteUtils.write(0, buffer);
        } else {
          WALWriteUtils.write(props.size(), buffer);
          for (Map.Entry<String, String> entry : props.entrySet()) {
            WALWriteUtils.write(entry.getKey(), buffer);
            WALWriteUtils.write(entry.getValue(), buffer);
          }
        }
      }
    }
  }

  public int serializeMeasurementSchemaSize() {
    int byteLen = 0;
    for (MeasurementSchema measurementSchema : measurementSchemas) {
      if (measurementSchema != null) {
        byteLen += ReadWriteIOUtils.sizeToWrite(measurementSchema.getMeasurementId());
        byteLen += 3 * Byte.BYTES;
        Map<String, String> props = measurementSchema.getProps();
        if (props == null) {
          byteLen += Integer.BYTES;
        } else {
          byteLen += Integer.BYTES;
          for (Map.Entry<String, String> entry : props.entrySet()) {
            byteLen += ReadWriteIOUtils.sizeToWrite(entry.getKey());
            byteLen += ReadWriteIOUtils.sizeToWrite(entry.getValue());
          }
        }
      }
    }
    return byteLen;
  }

  /** Make sure the measurement schema is already inited before calling this */
  public void deserializeMeasurementSchema(DataInputStream stream) throws IOException {
    for (int i = 0; i < measurementSchemas.length; i++) {

      measurementSchemas[i] =
          new MeasurementSchema(
              ReadWriteIOUtils.readString(stream),
              TSDataType.deserialize(ReadWriteIOUtils.readByte(stream)),
              TSEncoding.deserialize(ReadWriteIOUtils.readByte(stream)),
              CompressionType.deserialize(ReadWriteIOUtils.readByte(stream)));

      int size = ReadWriteIOUtils.readInt(stream);
      if (size > 0) {
        Map<String, String> props = new HashMap<>();
        String key;
        String value;
        for (int j = 0; j < size; j++) {
          key = ReadWriteIOUtils.readString(stream);
          value = ReadWriteIOUtils.readString(stream);
          props.put(key, value);
        }
        measurementSchemas[i].setProps(props);
      }
    }
  }

  public TRegionReplicaSet getRegionReplicaSet() {
    return dataRegionReplicaSet;
  }

  @Override
  protected void serializeAttributes(ByteBuffer byteBuffer) {
    throw new NotImplementedException("serializeAttributes of InsertNode is not implemented");
  }

  protected int countFailedMeasurements() {
    int result = 0;
    for (MeasurementSchema measurement : measurementSchemas) {
      if (measurement == null) {
        result++;
      }
    }
    return result;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    if (!super.equals(o)) return false;
    InsertNode that = (InsertNode) o;
    return isAligned == that.isAligned
        && Objects.equals(devicePath, that.devicePath)
        && Arrays.equals(measurementSchemas, that.measurementSchemas)
        && Arrays.equals(measurements, that.measurements)
        && Arrays.equals(dataTypes, that.dataTypes)
        && Objects.equals(deviceID, that.deviceID)
        && Objects.equals(dataRegionReplicaSet, that.dataRegionReplicaSet);
  }

  @Override
  public int hashCode() {
    int result =
        Objects.hash(super.hashCode(), devicePath, isAligned, deviceID, dataRegionReplicaSet);
    result = 31 * result + Arrays.hashCode(measurementSchemas);
    result = 31 * result + Arrays.hashCode(measurements);
    result = 31 * result + Arrays.hashCode(dataTypes);
    return result;
  }
}
