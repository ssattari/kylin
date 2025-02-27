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

package org.apache.spark.sql

import java.lang.{Boolean => JBoolean, String => JString}
import java.security.PrivilegedAction
import java.util.Map
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.{Callable, ExecutorService}

import org.apache.commons.lang3.StringUtils
import org.apache.hadoop.conf.Configuration
import org.apache.hadoop.security.UserGroupInformation
import org.apache.kylin.common.exception.{KylinException, KylinTimeoutException, ServerErrorCode}
import org.apache.kylin.common.msg.MsgPicker
import org.apache.kylin.common.util.{DefaultHostInfoFetcher, FileSystemUtil, HadoopUtil}
import org.apache.kylin.common.{KapConfig, KylinConfig, QueryContext}
import org.apache.kylin.engine.spark.QueryCostCollector
import org.apache.kylin.engine.spark.filter.{BloomFilterSkipCollector, ParquetPageFilterCollector}
import org.apache.kylin.metadata.model.{NTableMetadataManager, TableExtDesc}
import org.apache.kylin.metadata.project.NProjectManager
import org.apache.kylin.query.runtime.plan.QueryToExecutionIDCache
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler._
import org.apache.spark.sql.KylinSession._
import org.apache.spark.sql.catalyst.optimizer.ConvertInnerJoinToSemiJoin
import org.apache.spark.sql.catalyst.parser.ParseException
import org.apache.spark.sql.catalyst.plans.logical.LogicalPlan
import org.apache.spark.sql.execution.datasource.{KylinDeltaSourceStrategy, KylinSourceStrategy, LayoutFileSourceStrategy, RewriteInferFiltersFromConstraints}
import org.apache.spark.sql.execution.ui.PostQueryExecutionForKylin
import org.apache.spark.sql.hive.HiveStorageRule
import org.apache.spark.sql.udf.UdfManager
import org.apache.spark.util.{ThreadUtils, Utils}
import org.apache.spark.{ExecutorAllocationClient, SparkConf, SparkContext, SparkEnv}

// scalastyle:off
object SparderEnv extends Logging {
  @volatile
  private var spark: SparkSession = _

  private val initializingLock = new ReentrantLock()
  private val initializingCondition = initializingLock.newCondition()
  private var initializing: Boolean = false
  private val initializingExecutor: ExecutorService =
    ThreadUtils.newDaemonFixedThreadPool(1, "SparderEnv-Init")

  @volatile
  var APP_MASTER_TRACK_URL: String = null

  @volatile
  var startSparkFailureTimes: Int = 0

  @volatile
  var lastStartSparkFailureTime: Long = 0

  private var _containerSchedulerManager: Option[ContainerSchedulerManager] = None

  def getSparkSessionWithConfig(config: KylinConfig): SparkSession = {
    if (spark == null || spark.sparkContext.isStopped) {
      logInfo("Init spark.")
      initSpark(() => doInitSpark(), config)
    }
    if (spark == null)
      throw new KylinException(ServerErrorCode.SPARK_FAILURE, MsgPicker.getMsg.getSparkFailure)
    spark
  }

  def getSparkSession: SparkSession = {
    getSparkSessionWithConfig(null)
  }

  def rollUpEventLog(): String = {
    if (spark != null && !spark.sparkContext.isStopped) {
      val check = "CHECK_ROLLUP_" + System.currentTimeMillis()
      spark.sparkContext.listenerBus.post(SparkListenerLogRollUp(check))
      return check
    }
    ""
  }

  def setSparkSession(sparkSession: SparkSession): Unit = {
    spark = sparkSession
    UdfManager.create(sparkSession)
  }

  def setAPPMasterTrackURL(url: String): Unit = {
    APP_MASTER_TRACK_URL = url
  }

  def isSparkAvailable: Boolean = {
    spark != null && !spark.sparkContext.isStopped
  }

  def restartSpark(): Unit = {
    this.synchronized {
      if (spark != null && !spark.sparkContext.isStopped) {
        Utils.tryWithSafeFinally {
          spark.stop()
        } {
          SparkContext.clearActiveContext
        }
      }

      logInfo("Restart Spark")
      init()
    }
  }

  def init(): Unit = {
    getSparkSession
  }

  def getSparkConf(key: String): String = {
    getSparkSession.sparkContext.conf.get(key)
  }

  def isSparkExecutorResourceLimited(sparkConf: SparkConf): Boolean = {
    !sparkConf.get("spark.dynamicAllocation.enabled", "false").toBoolean ||
      sparkConf.get("spark.dynamicAllocation.maxExecutors", Int.MinValue.toString).toInt > 0
  }

  def getTotalCore: Int = {
    val sparkConf = getSparkSession.sparkContext.getConf
    if (sparkConf.get("spark.master").startsWith("local")) {
      return 1
    }
    val instances = getExecutorNum(sparkConf)
    val cores = sparkConf.get("spark.executor.cores").toInt
    Math.max(instances * cores, 1)
  }

  def getExecutorNum(sparkConf: SparkConf): Int = {
    if (sparkConf.get("spark.dynamicAllocation.enabled", "false").toBoolean) {
      val maxExecutors = sparkConf.get("spark.dynamicAllocation.maxExecutors", Int.MaxValue.toString).toInt
      logInfo(s"Use spark.dynamicAllocation.maxExecutors:$maxExecutors as num instances of executors.")
      maxExecutors
    } else {
      sparkConf.get("spark.executor.instances").toInt
    }
  }

  def initSpark(doInit: () => Unit, config: KylinConfig = null): Unit = {
    // do init
    try {
      initializingLock.lock()
      // exit if spark is running or it's during initializing
      if ((spark == null || spark.sparkContext.isStopped) && !initializing) {

        initializing = true

        initializingExecutor.submit(new Callable[Unit]() {
          override def call(): Unit = {
            if (config != null) {
              KylinConfig.setAndUnsetThreadLocalConfig(config)
            }
            try {
              logInfo("Initializing Spark thread starting.")
              doInit()
            } finally {
              logInfo("Initialized Spark")
              // wake up all waiting query threads after init done
              initializingLock.lock()
              initializing = false
              initializingCondition.signalAll()
              initializingLock.unlock()
            }
          }
        })
      }
    } finally {
      initializingLock.unlock()
    }

    // wait until initializing done
    try {
      initializingLock.lock()
      if (Thread.interrupted()) { // exit in case thread is interrupted already
        throw new InterruptedException
      }
      while (initializing) {
        initializingCondition.await()
      }
    } catch {
      case _: InterruptedException =>
        Thread.currentThread.interrupt()
        QueryContext.current().getQueryTagInfo.setTimeout(true)
        logWarning(s"Query timeouts after: ${KylinConfig.getInstanceFromEnv.getQueryTimeoutSeconds}s")
        throw new KylinTimeoutException("The query exceeds the set time limit of "
          + KylinConfig.getInstanceFromEnv.getQueryTimeoutSeconds + "s. Current step: Init sparder. ")
    } finally {
      initializingLock.unlock()
    }

    initConnWithHive()
  }

  private def initConnWithHive(): Unit = {
    try {
      UserGroupInformation.getLoginUser.doAs(new PrivilegedAction[Unit] {
        override def run(): Unit = spark.sql("show databases").show()
      })
    } catch {
      case throwable: Throwable =>
        logError("Error for initializing connection with hive.", throwable)
    }
  }

  def doInitSpark(): Unit = {
    try {
      SparkSession.clearActiveSession
      val hostInfoFetcher = new DefaultHostInfoFetcher
      val appName = "sparder-" + UserGroupInformation.getCurrentUser.getShortUserName + "-" + hostInfoFetcher.getHostname
      logInfo(s"sparder init user:${UserGroupInformation.getCurrentUser.getUserName}")

      val isLocalMode = KylinConfig.getInstanceFromEnv.isJobNodeOnly ||
        "true".equals(System.getenv("SPARK_LOCAL")) ||
        "true".equals(System.getProperty("spark.local"))
      val sparkSession = isLocalMode match {
        case true =>
          SparkSession.builder
            .master("local[3]")
            .appName("sparder-local-sql-context")
            .enableHiveSupport()
            .getOrCreateKylinSession()
        case _ =>
          SparkSession.builder
            .appName(appName)
            .master("yarn")
            .enableHiveSupport()
            .getOrCreateKylinSession()
      }
      injectExtensions(sparkSession.extensions)
      if (KylinConfig.getInstanceFromEnv.getPercentileApproxAlgorithm.equalsIgnoreCase("t-digest")) {
        UdfManager.register(sparkSession, KapFunctions.percentileFunction)
      }
      spark = sparkSession
      logInfo("Spark context started successfully with stack trace:")
      logInfo(Thread.currentThread().getStackTrace.mkString("\n"))
      logInfo(
        "Class loader: " + Thread
          .currentThread()
          .getContextClassLoader
          .toString)
      registerListener(sparkSession.sparkContext)
      registerContainerSchedulerManager(sparkSession.sparkContext)
      registerQueryMetrics(sparkSession.sparkContext)
      APP_MASTER_TRACK_URL = null
      startSparkFailureTimes = 0
      lastStartSparkFailureTime = 0

      if (KylinConfig.getInstanceFromEnv.useDynamicRoleCredentialInTable) {
        NProjectManager.getInstance(KylinConfig.getInstanceFromEnv).listAllProjects().forEach(project => {
          val tableMetadataManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv, project.getName)
          tableMetadataManager.listAllTables().forEach(tableDesc =>
            SparderEnv.addCredential(tableMetadataManager.getOrCreateTableExt(tableDesc).getRoleCredentialInfo, spark)
          )
        })
      }
      if (KylinConfig.getInstanceFromEnv.isDDLLogicalViewEnabled) {
        LogicalViewLoader.initScheduler()
      }
    } catch {
      case throwable: Throwable =>
        logError("Error for initializing spark ", throwable)
        startSparkFailureTimes += 1
        lastStartSparkFailureTime = System.currentTimeMillis()
    }
  }

  def containerSchedulerManager: Option[ContainerSchedulerManager] = _containerSchedulerManager

  //for test
  def setContainerSchedulerManager(containerSchedulerManager: ContainerSchedulerManager): Unit = {
    _containerSchedulerManager = Some(containerSchedulerManager)
    _containerSchedulerManager.foreach(_.start())
  }

  def registerContainerSchedulerManager(sc: SparkContext): Unit = {
    if (KylinConfig.getInstanceFromEnv.isContainerSchedulerEnabled) {
      _containerSchedulerManager = sc.schedulerBackend match {
        case client: ExecutorAllocationClient =>
          Some(new ContainerSchedulerManager(
            client, sc.listenerBus, sc.conf,
            cleaner = sc.cleaner, resourceProfileManager = sc.resourceProfileManager))
        case _ =>
          None
      }
      _containerSchedulerManager.foreach(_.start())
    }
  }

  def injectExtensions(sse: SparkSessionExtensions): Unit = {
    sse.injectPlannerStrategy(_ => KylinSourceStrategy)
    sse.injectPlannerStrategy(_ => LayoutFileSourceStrategy)
    sse.injectPlannerStrategy(_ => KylinDeltaSourceStrategy)
    sse.injectPostHocResolutionRule(HiveStorageRule)
    sse.injectOptimizerRule(_ => new ConvertInnerJoinToSemiJoin())
    if (KapConfig.getInstanceFromEnv.isConstraintPropagationEnabled) {
      sse.injectOptimizerRule(_ => RewriteInferFiltersFromConstraints)
    }
  }

  def registerListener(sc: SparkContext): Unit = {
    val sparkListener = new SparkListener {

      override def onOtherEvent(event: SparkListenerEvent): Unit = event match {
        case e: PostQueryExecutionForKylin =>
          val queryID = e.localProperties.getProperty(QueryToExecutionIDCache.KYLIN_QUERY_ID_KEY, "")
          QueryToExecutionIDCache.setQueryExecutionID(queryID, e.executionId.toString)
          val executionID = e.localProperties.getProperty(QueryToExecutionIDCache.KYLIN_QUERY_EXECUTION_ID, "")
          QueryToExecutionIDCache.setQueryExecution(executionID, e.queryExecution)
        case _ => // Ignore
      }
    }
    sc.addSparkListener(sparkListener)
  }

  def registerQueryMetrics(sc: SparkContext): Unit = {
    if (!KylinConfig.getInstanceFromEnv.isCollectQueryMetricsEnabled) {
      return
    }
    val taskListener = new SparkListener {
      override def onTaskEnd(taskEnd: SparkListenerTaskEnd): Unit = {
        try {
          if (StringUtils.isNotBlank(taskEnd.queryId)) {
            val inputMetrics = taskEnd.taskMetrics.inputMetrics
            BloomFilterSkipCollector.addQueryMetrics(taskEnd.queryId,
              inputMetrics.totalBloomBlocks, inputMetrics.totalSkipBloomBlocks,
              inputMetrics.totalSkipBloomRows, inputMetrics.footerReadTime,
              inputMetrics.footerReadNumber)
            ParquetPageFilterCollector.addQueryMetrics(taskEnd.queryId, inputMetrics.totalPagesCount,
              inputMetrics.filteredPagesCount, inputMetrics.afterFilterPagesCount)
            QueryCostCollector.addQueryMetrics(taskEnd.queryId,taskEnd.taskMetrics.executorCpuTime);
            QueryCostCollector.addQueryMetrics(taskEnd.queryId,taskEnd.taskMetrics.executorDeserializeCpuTime);
          }
        } catch {
          case e: Throwable => logWarning("error when add metrics for query", e)
        }
      }
    }
    sc.addSparkListener(taskListener)
  }

  /**
   * @param sqlText SQL to be validated
   * @return The logical plan
   * @throws ParseException if validate failed
   */
  @throws[ParseException]
  def validateSql(sqlText: String): LogicalPlan = {
    val logicalPlan: LogicalPlan = getSparkSession.sessionState.sqlParser.parsePlan(sqlText)
    logicalPlan
  }

  val _separator = new ThreadLocal[JString]
  val _df = new ThreadLocal[Dataset[Row]]
  val _needCompute = new ThreadLocal[JBoolean] {
    override protected def initialValue = false
  }

  def setSeparator(separator: java.lang.String): Unit = _separator.set(separator)

  def getSeparator: java.lang.String = if (_separator.get == null) "," else _separator.get

  def getDF: Dataset[Row] = _df.get

  def setDF(df: Dataset[Row]): Unit = _df.set(df)

  // clean it after query end
  def clean(): Unit = {
    _df.remove()
    _needCompute.remove()
  }

  def needCompute(): JBoolean = {
    !_needCompute.get()
  }

  def skipCompute(): Unit = {
    _needCompute.set(true)
  }

  def cleanCompute(): Unit = {
    _needCompute.set(false)
  }

  def addCredential(credentialInfo: TableExtDesc.RoleCredentialInfo, sparkSession: SparkSession): Unit = {
    if (credentialInfo != null) {
      val conf: Map[String, String] = FileSystemUtil.generateRoleCredentialConf(
        credentialInfo.getType, credentialInfo.getBucket, credentialInfo.getRole, credentialInfo.getEndpoint, credentialInfo.getRegion)
      conf.forEach((key: String, value: String) => sparkSession.conf.set(key, value))
    }

  }

  def getHadoopConfiguration(): /**/ Configuration = {
    var configuration = HadoopUtil.getCurrentConfiguration
    spark.conf.getAll.filter(item => item._1.startsWith("fs.")).foreach(item => configuration.set(item._1, item._2))
    configuration
  }

  // Return the list of currently active executors
  def getActiveExecutorIds(): Seq[String] = {
    getSparkSession.sparkContext.getExecutorIds()
  }

  def deleteQueryTaskResultBlock(queryExecutionID: String): Unit = {
    SparkEnv.get.deleteAllBlockForQueryResult(queryExecutionID)
  }
}
