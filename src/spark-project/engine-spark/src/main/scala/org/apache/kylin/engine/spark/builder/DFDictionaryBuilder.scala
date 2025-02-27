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

import org.apache.kylin.common.KylinConfig
import org.apache.kylin.common.util.HadoopUtil
import org.apache.kylin.engine.spark.builder.DFDictionaryBuilder.getLatestDictBuildVersion
import org.apache.kylin.engine.spark.job.NSparkCubingUtil
import org.apache.kylin.engine.spark.utils.LogEx
import org.apache.kylin.metadata.cube.model.NDataSegment
import org.apache.kylin.metadata.model.TblColRef
import org.apache.spark.TaskContext
import org.apache.spark.application.NoRetryException
import org.apache.spark.dict.NGlobalDictionaryV2.NO_VERSION_SPECIFIED
import org.apache.spark.dict.{NGlobalDictStoreFactory, NGlobalDictionaryV2}
import org.apache.spark.sql.execution.{ExplainMode, ExtendedMode}
import org.apache.spark.sql.functions.{col, expr}
import org.apache.spark.sql.types.StringType
import org.apache.spark.sql.{Column, Dataset, Row, SparkSession}

import java.io.IOException
import java.util
import java.util.concurrent.locks.Lock
import scala.collection.JavaConverters._
import scala.collection.mutable

class DFDictionaryBuilder(
                           val dataset: Dataset[Row],
                           @transient val seg: NDataSegment,
                           val ss: SparkSession,
                           val colRefSet: util.Set[TblColRef]) extends LogEx with Serializable {

  @throws[IOException]
  def buildDictSet(globalDictBuildVersionMap: mutable.HashMap[String, Long]): Unit = {
    colRefSet.asScala.foreach(col => safeBuild(col, globalDictBuildVersionMap))
    changeAQEConfig(true)
  }

  private val AQE = "spark.sql.adaptive.enabled"
  private val originalAQE = ss.conf.get(AQE)

  @throws[IOException]
  private[builder] def safeBuild(ref: TblColRef, globalDictBuildVersionMap: mutable.HashMap[String, Long]): Unit = {
    val sourceColumn = ref.getIdentity
    ZKHelper.tryZKJaasConfiguration(ss)
    val lock: Lock = KylinConfig.getInstanceFromEnv.getDistributedLockFactory
      .getLockForCurrentThread(getLockPath(sourceColumn))
    lock.lock()
    try {
      logInfo(s"Calculate bucket size for dict $sourceColumn")
      val dictColDistinct = dataset.select(wrapCol(ref)).distinct
      val resizeBuildVersion = generateBuildVersion(ref) // global dict new build version if resize happens
      ss.sparkContext.setJobDescription(s"Calculate bucket size for dict $sourceColumn")
      val bucketPartitionSize = logTime(s"calculating bucket size for $sourceColumn") {
        DictionaryBuilderHelper.calculateBucketSize(seg, ref, dictColDistinct, resizeBuildVersion)
      }

      val buildVersion: Long = generateBuildVersion(ref) // global dict final build version for current build
      globalDictBuildVersionMap.put(sourceColumn, buildVersion)
      logInfo(s"Start to build global dict $sourceColumn with version: $buildVersion")
      build(ref, bucketPartitionSize, dictColDistinct, buildVersion)
    } finally lock.unlock()
  }

  // Workaround: https://olapio.atlassian.net/browse/KE-41645
  private[builder] def changeAQEConfig(isDictBuildFinished: Boolean = false): Boolean = {
    if (!seg.getConfig.isGlobalDictAQEEnabled && !isDictBuildFinished) {
      logInfo("Temporarily Close AQE for dict build job")
      ss.conf.set(AQE, false)
      return false
    }
    logInfo(s"Restore AQE to its initial config: $originalAQE")
    ss.conf.set(AQE, originalAQE)
    originalAQE.toBoolean
  }

  def dictBuilderInfo(bucketPartitionSize: Int, df: Dataset[Row]): String = {
    s"""
       |==========================[DICT REPARTITION INFO]===============================
       |Partition Size :${df.rdd.getNumPartitions}
       |Bucket Partition Size: $bucketPartitionSize
       |AQE Enabled: ${ss.conf.get(AQE)}
       |Physical Plan:\n ${df.queryExecution.explainString(ExplainMode.fromString(ExtendedMode.name))}
       |==========================[DICT REPARTITION INFO]===============================
      """.stripMargin
  }

  @throws[IOException]
  private[builder] def build(ref: TblColRef, bucketPartitionSize: Int,
                             afterDistinct: Dataset[Row],
                             buildVersion: Long): Unit = logTime(s"building global dictionaries V2 for ${ref.getIdentity}") {
    val globalDict = new NGlobalDictionaryV2(seg.getProject, ref.getTable, ref.getName, seg.getConfig.getHdfsWorkingDirectory, buildVersion)
    globalDict.prepareWrite()
    val broadcastDict = ss.sparkContext.broadcast(globalDict)

    changeAQEConfig(false)
    ss.sparkContext.setJobDescription(s"Build dict ${ref.getIdentity} with version $buildVersion")

    val dictCol = col(afterDistinct.schema.fields.head.name)
    // https://issues.apache.org/jira/browse/SPARK-32051
    val afterDistinctRepartition = afterDistinct.filter(dictCol.isNotNull)
      .repartition(bucketPartitionSize, dictCol)

    logInfo(dictBuilderInfo(bucketPartitionSize, afterDistinctRepartition))

    afterDistinctRepartition.foreachPartition((iter: Iterator[Row]) => {
      val partitionID = TaskContext.get().partitionId()
      logInfo(s"Build partition dict col: ${ref.getIdentity}, partitionId: $partitionID")
      val broadcastGlobalDict = broadcastDict.value
      val bucketDict = broadcastGlobalDict.loadBucketDictionary(partitionID)
      iter.foreach(r => bucketDict.addRelativeValue(r.getString(0)))

      bucketDict.saveBucketDict(partitionID)
    })

    globalDict.writeMetaDict(bucketPartitionSize, seg.getConfig.getGlobalDictV2MaxVersions, seg.getConfig.getGlobalDictV2VersionTTL)

    if (seg.getConfig.isGlobalDictCheckEnabled) {
      logInfo(s"Start to check the correctness of the global dict, table: ${ref.getTableAlias}, col: ${ref.getName}")
      val latestGD = new NGlobalDictionaryV2(seg.getProject, ref.getTable, ref.getName, seg.getConfig.getHdfsWorkingDirectory, buildVersion)
      for (bid <- 0 until globalDict.getMetaInfo.getBucketSize) {
        val dMap = latestGD.loadBucketDictionary(bid).getAbsoluteDictMap
        val vdCount = dMap.values().stream().distinct().count()
        val kdCount = dMap.keySet().stream().distinct().count()
        if (kdCount != vdCount) {
          logError(s"Global dict correctness check failed, table: ${ref.getTableAlias}, col: ${ref.getName}")
          throw new NoRetryException("Global dict build error, bucket: " + bid + ", key distinct count:" + kdCount
            + ", value distinct count: " + vdCount)
        }
      }
      logInfo(s"Global dict correctness check completed, table: ${ref.getTableAlias}, col: ${ref.getName}")
    }
    changeAQEConfig(true)
  }

  private def getLockPath(pathName: String) = s"/${seg.getProject}${HadoopUtil.GLOBAL_DICT_STORAGE_ROOT}/$pathName/lock"

  /**
   * Method to generate global dict build version.
   * Should be used after distributed lock acquired.
   *
   * @param ref the TblColRef contains global dict to be built
   * @return buildVersion
   */
  private def generateBuildVersion(ref: TblColRef): Long = {
    val buildVersion = System.currentTimeMillis()
    val latestDictVersion = getLatestDictBuildVersion(seg, ref)
    if (latestDictVersion != NO_VERSION_SPECIFIED && buildVersion <= latestDictVersion) {
      latestDictVersion + 1
    } else {
      buildVersion
    }
  }

  def wrapCol(ref: TblColRef): Column = {
    val colName = NSparkCubingUtil.convertFromDotWithBackTick(ref.getBackTickIdentity)
    expr(colName).cast(StringType)
  }

}

object DFDictionaryBuilder {

  def getLatestDictBuildVersion(seg: NDataSegment, ref: TblColRef): Long = {
    val dictBaseDir = NGlobalDictionaryV2.toDictBasePath(seg.getConfig.getHdfsWorkingDirectory, seg.getProject,
      ref.getTable, ref.getName)
    val dictStore = NGlobalDictStoreFactory.getResourceStore(dictBaseDir)
    val dictVersions = dictStore.listAllVersions()
    if (dictVersions.nonEmpty) {
      Long2long(dictVersions(dictVersions.length - 1))
    } else {
      NO_VERSION_SPECIFIED
    }
  }
}
