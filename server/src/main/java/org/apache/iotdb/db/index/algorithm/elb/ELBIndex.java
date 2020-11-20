/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.iotdb.db.index.algorithm.elb;

import static org.apache.iotdb.db.index.common.IndexConstant.BLOCK_SIZE;
import static org.apache.iotdb.db.index.common.IndexConstant.BORDER;
import static org.apache.iotdb.db.index.common.IndexConstant.DEFAULT_BLOCK_SIZE;
import static org.apache.iotdb.db.index.common.IndexConstant.DEFAULT_DISTANCE;
import static org.apache.iotdb.db.index.common.IndexConstant.DEFAULT_ELB_TYPE;
import static org.apache.iotdb.db.index.common.IndexConstant.DISTANCE;
import static org.apache.iotdb.db.index.common.IndexConstant.ELB_TYPE;
import static org.apache.iotdb.db.index.common.IndexConstant.MISSING_PARAM_ERROR_MESSAGE;
import static org.apache.iotdb.db.index.common.IndexConstant.PATTERN;
import static org.apache.iotdb.db.index.common.IndexConstant.THRESHOLD;
import static org.apache.iotdb.db.index.common.IndexType.ELB_INDEX;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import org.apache.iotdb.db.exception.index.IndexManagerException;
import org.apache.iotdb.db.exception.index.QueryIndexException;
import org.apache.iotdb.db.exception.index.UnsupportedIndexFuncException;
import org.apache.iotdb.db.exception.metadata.MetadataException;
import org.apache.iotdb.db.index.IndexProcessor;
import org.apache.iotdb.db.index.algorithm.IoTDBIndex;
import org.apache.iotdb.db.index.algorithm.elb.ELBFeatureExtractor.ELBType;
import org.apache.iotdb.db.index.algorithm.elb.ELBFeatureExtractor.ELBWindowBlockFeature;
import org.apache.iotdb.db.index.common.IndexInfo;
import org.apache.iotdb.db.index.common.IndexType;
import org.apache.iotdb.db.index.common.IndexUtils;
import org.apache.iotdb.db.index.distance.Distance;
import org.apache.iotdb.db.index.preprocess.Identifier;
import org.apache.iotdb.db.index.read.func.IndexFuncFactory;
import org.apache.iotdb.db.index.read.func.IndexFuncResult;
import org.apache.iotdb.db.metadata.PartialPath;
import org.apache.iotdb.db.rescon.TVListAllocator;
import org.apache.iotdb.db.utils.datastructure.TVList;
import org.apache.iotdb.tsfile.file.metadata.enums.TSDataType;
import org.apache.iotdb.tsfile.utils.Pair;
import org.apache.iotdb.tsfile.utils.ReadWriteIOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Use ELB to represent a pattern, and slide the pattern over a long time series to find
 * sliding windows whose distance is less than the given threshold. Considering the original setting
 * in the paper, the sliding step is limited to 1. We will extend the work to the case of arbitrary
 * sliding step in the future.
 * </p>
 *
 * <p>Parameters for Creating ELB-Match:
 * Window Range,
 * </p>
 *
 * <p>Parameters for Querying ELB-Match</p>
 *
 *
 * <p>Query Parameters:</p>
 * <ul>
 *   <li>PATTERN: pattern series,</li>
 *   <li>THRESHOLD: [eps_1, eps_2, ..., eps_b];</li>
 *   <li>BORDER: [left_1, left_2, ..., left_b]; where left_1 is always 0</li>
 * </ul>
 *
 * <p>
 * The above borders indicate the subpattern borders.
 * For example, the range of the i-th subpattern is [left_i, left_{i+1}) with threshold eps_i.
 * </p>
 */

public class ELBIndex extends IoTDBIndex {

  private final Logger logger = LoggerFactory.getLogger(ELBIndex.class);
  private Distance distance;
  private ELBType elbType;

  private ELBMatchFeatureExtractor elbMatchPreprocessor;
  private List<ELBWindowBlockFeature> windowBlockFeatures;
  // Only for query
  private int queryTimeRange;
  private double[] pattern;
  // leaf: upper bounds, right: lower bounds
  private Pair<double[], double[]> patternFeatures;
  private ELBFeatureExtractor elbFeatureExtractor;
  private int blockWidth;
  private File featureFile;

  public ELBIndex(String path, String indexDir, IndexInfo indexInfo) {
    super(path, indexInfo);
    windowBlockFeatures = new ArrayList<>();
    featureFile = IndexUtils.getIndexFile(indexDir + File.separator + "feature");
    File indexDirFile = IndexUtils.getIndexFile(indexDir);
    if (indexDirFile.exists()) {
      System.out.println(String.format("reload index %s from %s", ELB_INDEX, indexDir));
      deserializeFeatures();
    } else {
      indexDirFile.mkdirs();
    }
//    logger.debug("");
    // ELB always variable query length, so it's needed windowRange
    windowRange = -1;
    initELBParam();
//    throw new IndexRuntimeException("indexDir没用起来，记得初始化");
  }

  private void initELBParam() {
    this.distance = Distance.getDistance(props.getOrDefault(DISTANCE, DEFAULT_DISTANCE));
    elbType = ELBType.valueOf(props.getOrDefault(ELB_TYPE, DEFAULT_ELB_TYPE));
    this.blockWidth = props.containsKey(BLOCK_SIZE) ? Integer.parseInt(props.get(BLOCK_SIZE))
        : DEFAULT_BLOCK_SIZE;
  }

  @Override
  public void initPreprocessor(ByteBuffer previous, boolean inQueryMode) {
    if (this.indexFeatureExtractor != null) {
      this.indexFeatureExtractor.clear();
    }
    this.elbMatchPreprocessor = new ELBMatchFeatureExtractor(tsDataType, windowRange, blockWidth,
        elbType, inQueryMode);
    this.indexFeatureExtractor = elbMatchPreprocessor;
    indexFeatureExtractor.deserializePrevious(previous);
  }

  @Override
  public boolean buildNext() {
    ELBWindowBlockFeature block = (ELBWindowBlockFeature) elbMatchPreprocessor
        .getCurrent_L3_Feature();
    windowBlockFeatures.add(block);
    return true;
  }

  @Override
  public void flush() {
    // we need to do nothing when a batch of memtable flush out.
//    if (indexFeatureExtractor.getCurrentChunkSize() == 0) {
//      logger.warn("Nothing to be flushed, directly return null");
//      System.out.println("Nothing to be flushed, directly return null");
//      return;
//    }
//    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
//    // serialize window block features
//    try {
//      elbMatchPreprocessor.serializeFeatures(outputStream);
//    } catch (IOException e) {
//      logger.error("flush failed", e);
//      return;
//    }
//    long st = indexFeatureExtractor.getChunkStartTime();
//    long end = indexFeatureExtractor.getChunkEndTime();
//    return new IndexFlushChunk(path, indexType, outputStream, st, end);
//    elbMatchPreprocessor.serializeFeatures(outputStream);
  }


  private void deserializeFeatures() {
    if (!featureFile.exists()) {
      return;
    }
    try (InputStream inputStream = new FileInputStream(featureFile)) {
      int size = ReadWriteIOUtils.readInt(inputStream);

      for (int i = 0; i < size; i++) {
        long startTime = ReadWriteIOUtils.readLong(inputStream);
        long endTime = ReadWriteIOUtils.readLong(inputStream);
        double feature = ReadWriteIOUtils.readDouble(inputStream);
        windowBlockFeatures.add(new ELBWindowBlockFeature(startTime, endTime, feature));
      }
    } catch (IOException e) {
      logger.error("Error when deserialize ELB features. Given up.", e);
    }

  }

  @Override
  protected void serializeIndexAndFlush() {
    try (OutputStream outputStream = new FileOutputStream(featureFile)) {
      ReadWriteIOUtils.write(windowBlockFeatures.size(), outputStream);
      for (ELBWindowBlockFeature features : windowBlockFeatures) {
        ReadWriteIOUtils.write(features.startTime, outputStream);
        ReadWriteIOUtils.write(features.endTime, outputStream);
        ReadWriteIOUtils.write(features.feature, outputStream);
      }
    } catch (IOException e) {
      logger.error("Error when serialize router. Given up.", e);
    }
  }

  @Override
  public void delete() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void initQuery(Map<String, Object> queryConditions, List<IndexFuncResult> indexFuncResults)
      throws UnsupportedIndexFuncException {
    for (IndexFuncResult result : indexFuncResults) {
      switch (result.getIndexFunc()) {
        case TIME_RANGE:
        case SIM_ST:
        case SIM_ET:
        case SERIES_LEN:
        case ED:
        case DTW:
          result.setIsTensor(true);
          break;
        default:
          throw new UnsupportedIndexFuncException(indexFuncResults.toString());
      }
      result.setIndexFuncDataType(result.getIndexFunc().getType());
    }
    if (queryConditions.containsKey(PATTERN)) {
      this.pattern = (double[]) queryConditions.get(PATTERN);
    } else {
      throw new UnsupportedIndexFuncException(String.format(MISSING_PARAM_ERROR_MESSAGE, PATTERN));
    }
    // calculate ELB upper/lower bounds of the given pattern according to given segmentation and threshold.
    double[] thresholds;
    if (queryConditions.containsKey(THRESHOLD)) {
      thresholds = (double[]) queryConditions.get(THRESHOLD);
    } else {
      throw new UnsupportedIndexFuncException(
          String.format(MISSING_PARAM_ERROR_MESSAGE, THRESHOLD));
    }

    int[] borders;
    if (queryConditions.containsKey(BORDER)) {
      borders = (int[]) queryConditions.get(BORDER);
    } else {
      throw new UnsupportedIndexFuncException(String.format(MISSING_PARAM_ERROR_MESSAGE, BORDER));
    }
    calculatePatternFeatures(thresholds, borders);
  }

  private void calculatePatternFeatures(double[] thresholds, int[] borders) {
    // calculate pattern features
    //TODO note that, the primary ELB version adopts an inelegant implementation for overcritical
    // memory control. A decoupling reconstruction will be done afterwards.
    // Convert the pattern array into TVList format is mere temporary patch. It will be modified soon.
    TVList patternList = TVListAllocator.getInstance().allocate(TSDataType.DOUBLE);
    for (double v : pattern) {
      patternList.putDouble(0, v);
    }
    this.elbFeatureExtractor = null;
//  this.elbFeatureExtractor = new ELBFeatureExtractor(distance, windowRange, featureDim, elbType);
    IndexUtils.breakDown("featureDim need to be replaced");

    this.patternFeatures = elbFeatureExtractor
        .calcELBFeature(patternList, 0, thresholds, borders);
  }

  @Override
  public List<Identifier> queryByIndex(ByteBuffer indexChunkData) throws IndexManagerException {
    // deserialize
    int featureDim = 0;
    IndexUtils.breakDown("featureDim ");

//    List<ELBWindowBlockFeature> windowBlockFeatures;
//    windowBlockFeatures = ELBMatchFeatureExtractor.deserializeFeatures(indexChunkData);

    List<Identifier> res = new ArrayList<>();
    // pruning
    for (int i = 0; i <= windowBlockFeatures.size() - featureDim; i++) {
      boolean canBePruned = false;
      for (int j = 0; j < featureDim; j++) {
        if (patternFeatures.left[j] < windowBlockFeatures.get(i + j).feature ||
            patternFeatures.right[j] > windowBlockFeatures.get(i + j).feature) {
          canBePruned = true;
          break;
        }
      }
      if (!canBePruned) {
        res.add(new Identifier(windowBlockFeatures.get(i).startTime,
            windowBlockFeatures.get(i).endTime,
            -1));
      }
    }
    return res;
  }

  @Override
  public int postProcessNext(List<IndexFuncResult> funcResult) throws QueryIndexException {
    TVList aligned = (TVList) indexFeatureExtractor.getCurrent_L2_AlignedSequence();
    int reminding = funcResult.size();
    if (elbFeatureExtractor.exactDistanceCalc(aligned)) {
      for (IndexFuncResult result : funcResult) {
        IndexFuncFactory.basicSimilarityCalc(result, indexFeatureExtractor, pattern);
      }
    }
    TVListAllocator.getInstance().release(aligned);
    return reminding;
  }

  @Override
  public String toString() {
    return windowBlockFeatures.toString();
  }

}
