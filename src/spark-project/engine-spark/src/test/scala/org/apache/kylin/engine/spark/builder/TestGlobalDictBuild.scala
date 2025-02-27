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
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kylin.engine.spark.builder

import org.apache.commons.lang3.{RandomStringUtils, StringUtils}
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.fs.{Path, PathFilter}
import org.apache.kylin.common.KylinConfig
import org.apache.kylin.engine.spark.builder.v3dict.GlobalDictionaryBuilderHelper.genRandomData
import org.apache.kylin.engine.spark.job.NSparkCubingUtil
import org.apache.kylin.engine.spark.job.step.ParamPropagation
import org.apache.kylin.metadata.cube.cuboid.{AdaptiveSpanningTree, NSpanningTreeFactory}
import org.apache.kylin.metadata.cube.model.{NDataSegment, NDataflow, NDataflowManager}
import org.apache.kylin.metadata.model.TblColRef
import org.apache.spark.application.NoRetryException
import org.apache.spark.dict.{NGlobalDictMetaInfo, NGlobalDictStoreFactory, NGlobalDictionaryV2}
import org.apache.spark.sql.catalyst.encoders.RowEncoder
import org.apache.spark.sql.common.{LocalMetadata, SharedSparkSession, SparderBaseFunSuite}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.types.{StringType, StructType}
import org.apache.spark.sql.{Dataset, Row}
import org.apache.spark.{SparkException, TaskContext}
import org.junit.Assert
import org.scalatest.matchers.must.Matchers.the

import java.util.Set
import scala.collection.mutable

class TestGlobalDictBuild extends SparderBaseFunSuite with SharedSparkSession with LocalMetadata {

  private val DEFAULT_PROJECT = "default"
  private val CUBE_NAME = "89af4ee2-2cdb-4b07-b39e-4c29856309aa"

  def getTestConfig: KylinConfig = {
    val config = KylinConfig.getInstanceFromEnv
    config
  }

  test("build v2 dict and encode flattable") {
    val dsMgr: NDataflowManager = NDataflowManager.getInstance(getTestConfig, DEFAULT_PROJECT)
    val df: NDataflow = dsMgr.getDataflow(CUBE_NAME)
    val seg = df.getLastSegment
    val nSpanningTree = NSpanningTreeFactory.fromLayouts(seg.getIndexPlan.getAllLayouts, df.getUuid)
    val dictColSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDicts(seg, nSpanningTree.getAllIndexEntities)

    val dictCol = dictColSet.iterator().next()
    val encodeColName: String = StringUtils.split(dictCol.getTable, ".").apply(1) + NSparkCubingUtil.SEPARATOR + dictCol.getName
    val randomDF = genRandomData(spark, encodeColName, 1000, 10)

    val dictionaryBuilder = new DFDictionaryBuilder(randomDF, seg, randomDF.sparkSession, dictColSet)
    val colName = dictColSet.iterator().next()
    val bucketPartitionSize = DictionaryBuilderHelper.calculateBucketSize(seg, colName, randomDF, System.currentTimeMillis())
    val buildVersion = System.currentTimeMillis()
    var dict = new NGlobalDictionaryV2(seg.getProject,
      colName.getTable,
      colName.getName,
      seg.getConfig.getHdfsWorkingDirectory,
      buildVersion)
    var meta1 = dict.getMetaInfo
    Assert.assertNull(meta1)
    val buildParam = new ParamPropagation
    buildParam.getGlobalDictBuildVersionMap.put(colName.getIdentity, buildVersion)
    val encode = encodeColumn(randomDF, seg, dictColSet, buildParam)
    // Use colloct to simulate a save operation
    Assert.assertThrows(classOf[SparkException], () => encode.collect())
    // if dict encode value is error, then rebuild dict
    val rebuildVersion = System.currentTimeMillis()
    dictionaryBuilder.build(colName, bucketPartitionSize, randomDF, rebuildVersion)
    encode.collect()
    dict = new NGlobalDictionaryV2(seg.getProject,
      colName.getTable,
      colName.getName,
      seg.getConfig.getHdfsWorkingDirectory,
      rebuildVersion)
    meta1 = dict.getMetaInfo
    Assert.assertEquals(1000, meta1.getDictCount)
    val rowsWithZero = encode.filter(encode(encode.columns(1)) === 0)
    encode.select(encode(encode.columns(1))).show()
    Assert.assertEquals(true, rowsWithZero.isEmpty)
    // clean all
    val cleanCol = dictColSet.iterator().next()
    val cleanDict = new NGlobalDictionaryV2(seg.getProject, cleanCol.getTable, cleanCol.getName, seg.getConfig.getHdfsWorkingDirectory)
    val cleanDictPath = new Path(seg.getConfig.getHdfsWorkingDirectory + cleanDict.getResourceDir)
    val fileSystem = cleanDictPath.getFileSystem(new Configuration())
    fileSystem.delete(cleanDictPath, true)
  }

  test("global dict build and checkout bucket resize strategy") {
    val dsMgr: NDataflowManager = NDataflowManager.getInstance(getTestConfig, DEFAULT_PROJECT)
    Assert.assertTrue(getTestConfig.getHdfsWorkingDirectory.startsWith("file:"))
    val df: NDataflow = dsMgr.getDataflow(CUBE_NAME)
    val seg = df.getLastSegment
    val nSpanningTree = NSpanningTreeFactory.fromLayouts(seg.getIndexPlan.getAllLayouts, df.getUuid)
    val dictColSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDicts(seg, nSpanningTree.getAllIndexEntities)
    seg.getConfig.setProperty("kylin.dictionary.globalV2-threshold-bucket-size", "100")
    seg.getConfig.setProperty("kylin.engine.global-dict-check-enabled", "TRUE")

    // When to resize the dictionary, please refer to the description of DictionaryBuilderHelper.calculateBucketSize

    // First build dictionary, no dictionary file exists
    var randomDataSet = generateOriginData(1000, 21)
    val meta1 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(20, meta1.getBucketSize)
    Assert.assertEquals(1000, meta1.getDictCount)

    // apply rule #1
    randomDataSet = generateOriginData(3000, 22)
    val meta2 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(60, meta2.getBucketSize)
    Assert.assertEquals(4000, meta2.getDictCount)

    randomDataSet = generateOriginData(3000, 23)
    val meta3 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(60, meta3.getBucketSize)
    Assert.assertEquals(7000, meta3.getDictCount)

    // apply rule #2
    randomDataSet = generateOriginData(200, 24)
    val meta4 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(140, meta4.getBucketSize)
    Assert.assertEquals(7200, meta4.getDictCount)

    // apply rule #3
    randomDataSet = generateHotOriginData(200, 140)
    val meta5 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(140, meta5.getBucketSize)
    Assert.assertEquals(7400, meta5.getDictCount)

    // apply rule #3
    randomDataSet = generateOriginData(200, 25)
    val meta6 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(280, meta6.getBucketSize)
    Assert.assertEquals(7600, meta6.getDictCount)

    randomDataSet = generateOriginData(2000, 26)
    val meta7 = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(280, meta7.getBucketSize)
    Assert.assertEquals(9600, meta7.getDictCount)

    // case: global dict build with error check
    // clean all
    val col = dictColSet.iterator().next()
    val dict = new NGlobalDictionaryV2(seg.getProject, col.getTable, col.getName, seg.getConfig.getHdfsWorkingDirectory)
    val dictPath = new Path(seg.getConfig.getHdfsWorkingDirectory + dict.getResourceDir)
    val fileSystem = dictPath.getFileSystem(new Configuration())
    fileSystem.delete(dictPath, true)

    // rebuild
    randomDataSet = generateOriginData(100, 21)
    val meta = buildDict(seg, randomDataSet, dictColSet)
    Assert.assertEquals(2, meta.getBucketSize)
    Assert.assertEquals(100, meta.getDictCount)

    val dictStore = NGlobalDictStoreFactory.getResourceStore(seg.getConfig.getHdfsWorkingDirectory + dict.getResourceDir)
    val versionPath = dictStore.getVersionDir(dictStore.listAllVersions()(0))
    // delete dict of bucket 0
    fileSystem.delete(new Path(versionPath, "CURR_0"), true)

    // reduce source data and build dict
    randomDataSet = generateOriginData(51, 21)
    val thrown = the[NoRetryException] thrownBy buildDict(seg, randomDataSet, dictColSet)
    thrown.printStackTrace()
  }

  test("shuffle partition equals min hash partitions") {
    val dataflowMgr = NDataflowManager.getInstance(getTestConfig, DEFAULT_PROJECT)
    val dataflow = dataflowMgr.getDataflow(CUBE_NAME)

    val shufflePartitionSize = spark.conf.get("spark.sql.shuffle.partitions")
    val shufflePartitionSizeInt = shufflePartitionSize.toInt
    val segment = dataflow.getLastSegment
    segment.getConfig.setProperty("kylin.dictionary.globalV2-min-hash-partitions", shufflePartitionSize)
    segment.getConfig.setProperty("kylin.engine.global-dict-check-enabled", "TRUE")
    val spanningTree = new AdaptiveSpanningTree(getTestConfig, //
      new AdaptiveSpanningTree.AdaptiveTreeBuilder(segment, //
        dataflow.getIndexPlan.getAllLayouts))
    val dictColSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDicts(segment, spanningTree.getIndices)
    val dictCol = dictColSet.iterator().next()
    val dictV2 = new NGlobalDictionaryV2(segment.getProject, dictCol.getTable, dictCol.getName,
      segment.getConfig.getHdfsWorkingDirectory)
    val dictPath = new Path(segment.getConfig.getHdfsWorkingDirectory + dictV2.getResourceDir)
    val fileSystem = dictPath.getFileSystem(new Configuration())
    fileSystem.delete(dictPath, true)

    val sampleDS = generateOriginData(200, 10)

    val dictColumn = col(dataflow.getModel.getColumnIdByColumnName(dictCol.getIdentity).toString)
    val distinctDS = sampleDS.select(dictColumn).distinct()
    val dictBuilder = new DFDictionaryBuilder(sampleDS, segment, spark, dictColSet)
    val buildVersion = System.currentTimeMillis()
    dictBuilder.build(dictCol, shufflePartitionSizeInt, distinctDS, buildVersion)

    val dictStore = NGlobalDictStoreFactory.getResourceStore(segment.getConfig.getHdfsWorkingDirectory + dictV2.getResourceDir)
    val versionPath = dictStore.getVersionDir(buildVersion)

    val dictDirSize = fileSystem.listStatus(versionPath, new PathFilter {
      override def accept(path: Path): Boolean = {
        path.getName.startsWith("CURR_")
      }
    }).length

    Assert.assertEquals(shufflePartitionSizeInt, dictDirSize)
  }


  test("global dict build and close aqe") {
    val dsMgr: NDataflowManager = NDataflowManager.getInstance(getTestConfig, DEFAULT_PROJECT)
    val df: NDataflow = dsMgr.getDataflow(CUBE_NAME)
    val seg = df.getLastSegment
    val nSpanningTree = NSpanningTreeFactory.fromLayouts(seg.getIndexPlan.getAllLayouts, df.getUuid)
    val dictColSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDicts(seg, nSpanningTree.getAllIndexEntities)
    val randomDataSet = generateOriginData(200, 10)

    val dictionaryBuilder = new DFDictionaryBuilder(randomDataSet, seg, spark, dictColSet)
    val col = dictColSet.iterator().next()
    val ds = randomDataSet.select("26").distinct()
    val bucketPartitionSize = DictionaryBuilderHelper.calculateBucketSize(seg, col, ds, System.currentTimeMillis())

    val originalAQE = spark.conf.get("spark.sql.adaptive.enabled")

    // false false
    seg.getConfig.setProperty("kylin.engine.global-dict-aqe-enabled", "FALSE")
    dictionaryBuilder.changeAQEConfig(false)
    Assert.assertFalse(spark.conf.get("spark.sql.adaptive.enabled").toBoolean)
    dictionaryBuilder.build(col, bucketPartitionSize, ds, System.currentTimeMillis())
    Assert.assertTrue(spark.conf.get("spark.sql.adaptive.enabled").equals(originalAQE))

    // false true
    dictionaryBuilder.changeAQEConfig(true)
    Assert.assertTrue(spark.conf.get("spark.sql.adaptive.enabled").equals(originalAQE))

    // true false
    seg.getConfig.setProperty("kylin.engine.global-dict-aqe-enabled", "TRUE")
    dictionaryBuilder.changeAQEConfig(false)
    Assert.assertTrue(spark.conf.get("spark.sql.adaptive.enabled").equals(originalAQE))

    dictionaryBuilder.build(col, bucketPartitionSize, ds, System.currentTimeMillis())
    Assert.assertTrue(spark.conf.get("spark.sql.adaptive.enabled").equals(originalAQE))

    // true true
    dictionaryBuilder.changeAQEConfig(true)
    Assert.assertTrue(spark.conf.get("spark.sql.adaptive.enabled").equals(originalAQE))

  }

  def buildDict(seg: NDataSegment, randomDataSet: Dataset[Row], dictColSet: Set[TblColRef]): NGlobalDictMetaInfo = {
    val dictionaryBuilder = new DFDictionaryBuilder(randomDataSet, seg, randomDataSet.sparkSession, dictColSet)
    val col = dictColSet.iterator().next()
    val ds = randomDataSet.select("26").distinct()
    val bucketPartitionSize = DictionaryBuilderHelper.calculateBucketSize(seg, col, ds, System.currentTimeMillis())
    val buildVersion = System.currentTimeMillis()
    dictionaryBuilder.build(col, bucketPartitionSize, ds, buildVersion)
    val dict = new NGlobalDictionaryV2(seg.getProject, col.getTable, col.getName,
      seg.getConfig.getHdfsWorkingDirectory, buildVersion)
    dict.getMetaInfo
  }

  def encodeColumn(ds: Dataset[Row], dataSegment: NDataSegment,
                   encodeCols: Set[TblColRef], buildParam: ParamPropagation): Dataset[Row] = {
    val encodeDs = DFTableEncoder.encodeTable(ds, dataSegment, encodeCols, buildParam.getGlobalDictBuildVersionMap)
    encodeDs
  }

  def generateOriginData(count: Int, length: Int): Dataset[Row] = {
    var schema = new StructType

    schema = schema.add("26", StringType)
    var set = new mutable.LinkedHashSet[Row]
    while (set.size != count) {
      val objects = new Array[String](1)
      objects(0) = RandomStringUtils.randomAlphabetic(length)
      set.+=(Row.fromSeq(objects.toSeq))
    }

    spark.createDataFrame(spark.sparkContext.parallelize(set.toSeq), schema)
  }

  def generateHotOriginData(threshold: Int, bucketSize: Int): Dataset[Row] = {
    var schema = new StructType
    schema = schema.add("26", StringType)
    var ds = generateOriginData(threshold * bucketSize * 2, 30)
    ds = ds.repartition(bucketSize, col("26"))
      .mapPartitions {
        iter =>
          val partitionID = TaskContext.get().partitionId()
          if (partitionID != 1) {
            Iterator.empty
          } else {
            iter
          }
      }(RowEncoder.apply(ds.schema))
    ds.limit(threshold)
  }
}
