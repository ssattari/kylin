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
package org.apache.kylin.engine.spark.smarter

import org.apache.kylin.common.KylinConfig
import org.apache.spark.SparkContext
import org.apache.spark.internal.Logging

import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

class BuildAppStatusStore(val kylinConfig: KylinConfig, val sc: SparkContext) extends Logging {

  val resourceStateQueue: BlockingQueue[(Int, Int)] =
    new LinkedBlockingQueue[(Int, Int)](kylinConfig.buildResourceConsecutiveIdleStateNum);

  def write(runningTaskNum: Int, appTaskThreshold: Int): Unit = {
    if (resourceStateQueue.remainingCapacity() == 0) {
      resourceStateQueue.take()
    }
    resourceStateQueue.put((runningTaskNum, appTaskThreshold))
  }
}
