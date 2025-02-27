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

package org.apache.kylin.query.plugin.profiler

import org.apache.kylin.common.KylinConfig
import org.apache.kylin.common.exception.{KylinException, QueryErrorCode}
import org.apache.kylin.profiler.Message._
import org.apache.kylin.profiler.{AsyncProfilerTool, AsyncProfilerUtils}
import org.apache.spark.internal.Logging
import org.apache.spark.sql.SparderEnv

import java.io.{File, OutputStream}
import java.nio.file.Files
import java.util.concurrent.CountDownLatch


object AsyncProfiling extends Logging {

  var localCacheDir: File = Files.createTempDirectory("ke-async-profiler-result-").toFile
  localCacheDir.deleteOnExit()
  private val resultCollectionTimeout = KylinConfig.getInstanceFromEnv.asyncProfilingResultTimeout
  private val profilingTimeout = KylinConfig.getInstanceFromEnv.asyncProfilingProfileTimeout
  private var timeoutExecutionThread: Thread = _

  private var command: String = createDriverMessage(NOP)
  private var running = false
  private var dumped = false
  private var cachedResult: CountDownLatch = _

  val asyncProfilerUtils: AsyncProfilerUtils = AsyncProfilerUtils.getInstance()
  asyncProfilerUtils.build(resultCollectionTimeout, localCacheDir)

  def nextCommand(): String = {
    command
  }

  def start(params: String): Unit = {
    val startParam = Option(params).getOrElse("start,event=cpu")
    AsyncProfiling.synchronized {
      if (running) {
        throw new KylinException(QueryErrorCode.PROFILING_ALREADY_STARTED, "profiling is already started, stop it first")
      }
      logDebug("profiler start")
      // Linux may periodically clean up the files in the /tmp directory
      if (!localCacheDir.exists()) {
        localCacheDir = Files.createTempDirectory("ke-async-profiler-result-").toFile
        asyncProfilerUtils.build(localCacheDir)
      }
      asyncProfilerUtils.cleanLocalCache()
      // expecting driver + count(executor) amount of results
      cachedResult = new CountDownLatch(
        SparderEnv.getSparkSession.sparkContext.getExecutorMemoryStatus.size
      )
      asyncProfilerUtils.build(cachedResult)
      logDebug(s"expecting ${cachedResult.getCount} to be collected")

      running = true
      dumped = false
      command = createDriverMessage(START, startParam) // inform executors
      AsyncProfilerTool.start(startParam) // start driver prof
      logDebug("profiler started")

      timeoutExecutionThread = new Thread(() => {
        try {
          Thread.sleep(profilingTimeout)
        } catch {
          case _: InterruptedException =>
            logTrace("profiler stopped normally, timeout thread interrupted and exit")
            Thread.currentThread().interrupt()
        }

        if (!Thread.interrupted()) {
          logInfo(s"profiling timeout after ${profilingTimeout}ms, stopping profiling")
          AsyncProfiling.synchronized {
            if (running) {
              running = false
              command = createDriverMessage(STOP)
              AsyncProfilerTool.stop()
            }
          }
        }
      })
      timeoutExecutionThread.start()
    }
  }

  def dump(params: String): Unit = {
    val dumpParam = Option(params).getOrElse("flamegraph")
    AsyncProfiling.synchronized {
      if (dumped) {
        throw new KylinException(QueryErrorCode.PROFILER_ALREADY_DUMPED, "result is already dumped.")
      }

      if (running) {
        logDebug("profiler stop")
        running = false
        timeoutExecutionThread.interrupt()
      } else {
        logWarning("profiling is not started")
      }

      dumped = true
      command = createDriverMessage(DUMP, dumpParam) // inform executors
      asyncProfilerUtils.cacheDriverResult(AsyncProfilerTool.dump(dumpParam)) // dump driver prof
    }
  }

  def waitForResult(outStream: OutputStream): Unit = {
    asyncProfilerUtils.waitForResult(outStream)
  }

  private[profiler] def cacheExecutorResult(content: String, executorId: String): Unit = {
    asyncProfilerUtils.cacheExecutorResult(content, executorId)
  }
}