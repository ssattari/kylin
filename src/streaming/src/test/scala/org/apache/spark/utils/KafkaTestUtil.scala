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
package org.apache.spark.utils

import kafka.api.Request
import kafka.server.{KafkaConfig, KafkaServer}
import kafka.zk.{AdminZkClient, KafkaZkClient}
import org.apache.kafka.clients.producer.{KafkaProducer, ProducerRecord}
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.spark.internal.Logging
import org.apache.spark.streaming.Time
import org.apache.spark.util.{ShutdownHookManager, Utils}
import org.apache.spark.{SparkConf, SparkException}
import org.apache.zookeeper.server.{NIOServerCnxnFactory, ZooKeeperServer}

import java.io.{File, IOException}
import java.lang.{Integer => JInt}
import java.net.InetSocketAddress
import java.util.concurrent.TimeoutException
import java.util.{Properties, Map => JMap}
import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.control.NonFatal

trait KafkaTestUtils extends Logging {

  // Zookeeper related configurations
  private val zkHost = "127.0.0.1"
  private var zkPort: Int = 0
  private val zkConnectionTimeout = 60000
  private val zkSessionTimeout = 10000

  private var zookeeper: EmbeddedZookeeper = _

  private var zkUtils: KafkaZkClient = _
  private var adminClient: AdminZkClient = _

  // Kafka broker related configurations
  private val brokerHost = "127.0.0.1"
  private var brokerPort = 19092
  private var brokerConf: KafkaConfig = _

  // Kafka broker server
  private var server: KafkaServer = _

  // Kafka producer
  private var producer: KafkaProducer[String, String] = _

  // Flag to test whether the system is correctly started
  private var zkReady = false
  private var brokerReady = false
  private var leakDetector: AnyRef = null

  def zkAddress: String = {
    assert(zkReady, "Zookeeper not setup yet or already torn down, cannot get zookeeper address")
    s"$zkHost:$zkPort"
  }

  def brokerAddress: String = {
    assert(brokerReady, "Kafka not setup yet or already torn down, cannot get broker address")
    s"$brokerHost:$brokerPort"
  }

  def zookeeperClient: KafkaZkClient = {
    assert(zkReady, "Zookeeper not setup yet or already torn down, cannot get zookeeper client")
    Option(zkUtils).getOrElse(
      throw new IllegalStateException("Zookeeper client is not yet initialized"))
  }

  // Set up the Embedded Zookeeper server and get the proper Zookeeper port
  private def setupEmbeddedZookeeper(): Unit = {
    // Zookeeper server startup
    zookeeper = new EmbeddedZookeeper(s"$zkHost:$zkPort")
    // Get the actual zookeeper binding port
    zkPort = zookeeper.actualPort
    zkUtils = KafkaZkClient(s"$zkHost:$zkPort", false, zkSessionTimeout, zkConnectionTimeout, 10, org.apache.kafka.common.utils.Time.SYSTEM)
    zkReady = true
  }

  // Set up the Embedded Kafka server
  private def setupEmbeddedKafkaServer(): Unit = {
    assert(zkReady, "Zookeeper should be set up beforehand")

    // Kafka broker startup
    Utils.startServiceOnPort(brokerPort, port => {
      brokerPort = port
      brokerConf = new KafkaConfig(brokerConfiguration, doLog = false)
      server = new KafkaServer(brokerConf)
      server.startup()
      brokerPort = server.boundPort(new ListenerName("PLAINTEXT"))
      (server, brokerPort)
    }, new SparkConf(), "KafkaBroker")

    brokerReady = true
  }

  /** setup the whole embedded servers, including Zookeeper and Kafka brokers */
  def setup(): Unit = {
    // Set up a KafkaTestUtils leak detector so that we can see where the leak KafkaTestUtils is
    // created.
    val exception = new SparkException("It was created at: ")
    leakDetector = ShutdownHookManager.addShutdownHook { () =>
      logError("Found a leak KafkaTestUtils.", exception)
    }

    setupEmbeddedZookeeper()
    setupEmbeddedKafkaServer()
  }

  /** Teardown the whole servers, including Kafka broker and Zookeeper */
  def teardown(): Unit = {
    if (leakDetector != null) {
      ShutdownHookManager.removeShutdownHook(leakDetector)
    }
    brokerReady = false
    zkReady = false

    if (producer != null) {
      producer.close()
      producer = null
    }

    if (server != null) {
      server.shutdown()
      server.awaitShutdown()
      server = null
    }

    // On Windows, `logDirs` is left open even after Kafka server above is completely shut down
    // in some cases. It leads to test failures on Windows if the directory deletion failure
    // throws an exception.
    brokerConf.logDirs.foreach { f =>
      try {
        Utils.deleteRecursively(new File(f))
      } catch {
        case e: IOException if Utils.isWindows =>
          logWarning(e.getMessage)
      }
    }

    if (zkUtils != null) {
      zkUtils.close()
      zkUtils = null
    }

    if (zookeeper != null) {
      zookeeper.shutdown()
      zookeeper = null
    }
  }

  /** Create a Kafka topic and wait until it is propagated to the whole cluster */
  def createTopic(topic: String, partitions: Int, config: Properties): Unit = {
    adminClient = new AdminZkClient(zkUtils)
    adminClient.createTopic(topic, partitions, 1, config)
    // wait until metadata is propagated
    (0 until partitions).foreach { p =>
      waitUntilMetadataIsPropagated(topic, p)
    }
  }

  /** Create a Kafka topic and wait until it is propagated to the whole cluster */
  def createTopic(topic: String, partitions: Int): Unit = {
    createTopic(topic, partitions, new Properties())
  }

  /** Create a Kafka topic and wait until it is propagated to the whole cluster */
  def createTopic(topic: String): Unit = {
    createTopic(topic, 1, new Properties())
  }

  /** Java-friendly function for sending messages to the Kafka broker */
  def sendMessages(topic: String, messageToFreq: JMap[String, JInt]): Unit = {
    sendMessages(topic, Map(messageToFreq.asScala.mapValues(_.intValue()).toSeq: _*))
  }

  /** Send the messages to the Kafka broker */
  def sendMessages(topic: String, messageToFreq: Map[String, Int]): Unit = {
    val messages = messageToFreq.flatMap { case (s, freq) => Seq.fill(freq)(s) }.toArray
    sendMessages(topic, messages)
  }

  /** Send the array of messages to the Kafka broker */
  def sendMessages(topic: String, messages: Array[String]): Unit = {
    if (producer == null) {
      producer = new KafkaProducer[String, String](producerConfiguration)
    }
    messages.foreach { message =>
      producer.send(new ProducerRecord[String, String](topic, message))
    }
  //  producer.close()
   // producer = null
  }

  def flush(): Unit = {
    producer.close()
    producer = null
  }

  /** Send the array of (key, value) messages to the Kafka broker */
  def sendMessages(topic: String, messages: Array[(String, String)]): Unit = {
    producer = new KafkaProducer[String, String](producerConfiguration)
    messages.foreach { message =>
      producer.send(new ProducerRecord[String, String](topic, message._1, message._2))
    }
    producer.close()
    producer = null
  }

  val brokerLogDir = Utils.createTempDir().getAbsolutePath

  private def brokerConfiguration: Properties = {
    val props = new Properties()
    props.put("broker.id", "0")
    props.put("host.name", "127.0.0.1")
    props.put("advertised.host.name", "127.0.0.1")
    props.put("port", brokerPort.toString)
    props.put("log.dir", brokerLogDir)
    props.put("zookeeper.connect", zkAddress)
    props.put("zookeeper.connection.timeout.ms", "60000")
    props.put("log.flush.interval.messages", "1")
    props.put("replica.socket.timeout.ms", "1500")
    props.put("delete.topic.enable", "true")
    props.put("offsets.topic.num.partitions", "1")
    props.put("offsets.topic.replication.factor", "1")
    props.put("group.initial.rebalance.delay.ms", "10")
    props
  }

  private def producerConfiguration: Properties = {
    val props = new Properties()
    props.put("bootstrap.servers", brokerAddress)

    props.put("request.required.acks", "1")
    props.put("producer.type", "async")
    props.put("retries", "0")
    props.put("batch.size", "10000")
    props.put("linger.ms", "1")

    props.put("value.serializer", classOf[StringSerializer].getName)
    // Key serializer is required.
    props.put("key.serializer", classOf[StringSerializer].getName)
    // wait for all in-sync replicas to ack sends
    props.put("acks", "all")
    props
  }

  // A simplified version of scalatest eventually, rewritten here to avoid adding extra test
  // dependency
  def eventually[T](timeout: Time, interval: Time)(func: => T): T = {
    def makeAttempt(): Either[Throwable, T] = {
      try {
        Right(func)
      } catch {
        case e if NonFatal(e) => Left(e)
      }
    }

    val startTime = System.currentTimeMillis()
    @tailrec
    def tryAgain(attempt: Int): T = {
      makeAttempt() match {
        case Right(result) => result
        case Left(e) =>
          val duration = System.currentTimeMillis() - startTime
          if (duration < timeout.milliseconds) {
            Thread.sleep(interval.milliseconds)
          } else {
            throw new TimeoutException(e.getMessage)
          }

          tryAgain(attempt + 1)
      }
    }

    tryAgain(1)
  }

  private def waitUntilMetadataIsPropagated(topic: String, partition: Int): Unit = {
    def isPropagated = server.metadataCache.getPartitionInfo(topic, partition) match {
      case Some(partitionState) =>
        val leader = partitionState.leader
        val isr = partitionState.isr
        zkUtils.getLeaderForPartition(new TopicPartition(topic, partition)).isDefined &&
          Request.isValidBrokerId(leader) && !isr.isEmpty
      case _ =>
        false
    }
    eventually(Time(10000), Time(100)) {
      assert(isPropagated, s"Partition [$topic, $partition] metadata not propagated after timeout")
    }
  }

  private class EmbeddedZookeeper(val zkConnect: String) {
    val snapshotDir = Utils.createTempDir()
    val logDir = Utils.createTempDir()

    val zookeeper = new ZooKeeperServer(snapshotDir, logDir, 500)
    val (ip, port) = {
      val splits = zkConnect.split(":")
      (splits(0), splits(1).toInt)
    }
    val factory = new NIOServerCnxnFactory()
    factory.configure(new InetSocketAddress(ip, port), 16)
    factory.startup(zookeeper)

    val actualPort = factory.getLocalPort

    def shutdown() {
      factory.shutdown()
      // The directories are not closed even if the ZooKeeper server is shut down.
      // Please see ZOOKEEPER-1844, which is fixed in 3.4.6+. It leads to test failures
      // on Windows if the directory deletion failure throws an exception.
      try {
        Utils.deleteRecursively(snapshotDir)
      } catch {
        case e: IOException if Utils.isWindows =>
          logWarning(e.getMessage)
      }
      try {
        Utils.deleteRecursively(logDir)
      } catch {
        case e: IOException if Utils.isWindows =>
          logWarning(e.getMessage)
      }
    }
  }
}
