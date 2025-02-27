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
package org.apache.kylin.engine.spark.job.exec


import java.io.IOException
import java.util.Locale

import org.apache.kylin.engine.spark.job.StepExec
import org.apache.kylin.engine.spark.job.step.StageExec

import scala.collection.JavaConverters._

class InternalTableLoadExec(id: String) extends StepExec(id) {

  @throws(classOf[IOException])
  def executeStep(): Unit = {
    for (stage <- subStageList.asScala) {
      logInfo(s"Start sub stage ${stage.getStageName}")
      stage.doExecuteWithoutFinally()
      logInfo(s"End sub stage ${stage.getStageName}")
    }
  }

}
