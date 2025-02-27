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

import org.apache.kylin.query.plugin.SparkPluginWithMeta
import org.apache.spark.launcher.SparkLauncher
import org.apache.spark.{SparkConf, SparkContext}
import org.junit.Assert

class QueryAsyncProfilerDriverPluginTest extends SparkPluginWithMeta {

  val sparkPluginName: String = classOf[QueryAsyncProfilerSparkPlugin].getName

  override def beforeAll(): Unit = {
    super.beforeAll()
    val conf = new SparkConf()
      .setAppName(getClass.getName)
      .set(SparkLauncher.SPARK_MASTER, "local[1]")
      .set("spark.plugins", sparkPluginName)

    sc = new SparkContext(conf)
  }

  test("plugin initialization") {
    Assert.assertEquals(sparkPluginName, sc.getConf.get("spark.plugins").toString)
    new QueryAsyncProfilerDriverPlugin().receive("NEX-1:start,event=cpu")
  }

  test("plugin initialization receive result") {
    Assert.assertEquals(sparkPluginName, sc.getConf.get("spark.plugins").toString)
    try {
      new QueryAsyncProfilerDriverPlugin().receive("RES-1:flamegraph")
    } catch {
      case _: Throwable =>
    }
  }

  test("plugin initialization receive others") {
    Assert.assertEquals(sparkPluginName, sc.getConf.get("spark.plugins").toString)
    try {
      new QueryAsyncProfilerDriverPlugin().receive("OTH-1:start,event=cpu")
    } catch {
      case _: Throwable =>
    }
  }
}