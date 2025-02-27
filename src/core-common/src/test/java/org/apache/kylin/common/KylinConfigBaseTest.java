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

package org.apache.kylin.common;

import static org.apache.kylin.common.KylinConfigBase.FALSE;
import static org.apache.kylin.common.KylinConfigBase.PATH_DELIMITER;
import static org.apache.kylin.common.KylinConfigBase.TRUE;
import static org.apache.kylin.common.KylinConfigBase.WRITING_CLUSTER_WORKING_DIR;
import static org.apache.kylin.common.constant.Constants.KYLIN_SOURCE_JDBC_SOURCE_ENABLE_KEY;
import static org.apache.kylin.common.constant.Constants.KYLIN_SOURCE_JDBC_SOURCE_NAME_KEY;
import static org.apache.kylin.common.constant.Constants.SNAPSHOT_AUTO_REFRESH;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.util.Shell;
import org.apache.kylin.common.constant.NonCustomProjectLevelConfig;
import org.apache.kylin.common.util.EncryptUtil;
import org.apache.kylin.common.util.ProcessUtils;
import org.apache.kylin.common.util.TimeZoneUtils;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableSet;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.util.concurrent.ListenableFuture;
import org.apache.kylin.guava30.shaded.common.util.concurrent.ListeningExecutorService;
import org.apache.kylin.guava30.shaded.common.util.concurrent.MoreExecutors;
import org.apache.kylin.guava30.shaded.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.kylin.junit.annotation.MetadataInfo;
import org.junit.Assert;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import lombok.val;

@MetadataInfo(onlyProps = true)
class KylinConfigBaseTest {

    private static final Map<String, PropertiesEntity> map = new HashMap<>();

    static {
        map.put("getDeployEnv", new PropertiesEntity("kylin.env", "DEV", "DEV"));

        map.put("getKylinMetricsPrefix", new PropertiesEntity("kylin.metrics.prefix", "KYLIN", "KYLIN"));

        map.put("getFirstDayOfWeek", new PropertiesEntity("kylin.metadata.first-day-of-week", "monday", "monday"));

        map.put("getKylinMetricsActiveReservoirDefaultClass",
                new PropertiesEntity("kylin.metrics.active-reservoir-default-class",
                        "org.apache.kylin.metrics.lib.impl.StubReservoir",
                        "org.apache.kylin.metrics.lib.impl.StubReservoir"));

        map.put("getKylinSystemCubeSinkDefaultClass",
                new PropertiesEntity("kylin.metrics.system-cube-sink-default-class",
                        "org.apache.kylin.metrics.lib.impl.hive.HiveSink",
                        "org.apache.kylin.metrics.lib.impl.hive.HiveSink"));
        map.put("isConvertExpressionToCcEnabled",
                new PropertiesEntity("kylin.query.implicit-computed-column-convert", "true", true));
        map.put("getConvertCcMaxIterations",
                new PropertiesEntity("kylin.query.computed-column-max-recursion-times", "10", 10));

        map.put("isKylinMetricsMonitorEnabled", new PropertiesEntity("kylin.metrics.monitor-enabled", "false", false));

        map.put("getZookeeperBasePath", new PropertiesEntity("kylin.env.zookeeper-base-path", "/kylin", "/kylin"));

        map.put("getZookeeperConnectString",
                new PropertiesEntity("kylin.env.zookeeper-connect-string", "localhost:2181", "localhost:2181"));

        map.put("isZookeeperAclEnabled", new PropertiesEntity("kylin.env.zookeeper-acl-enabled", "false", false));

        map.put("getZKAuths",
                new PropertiesEntity("kylin.env.zookeeper.zk-auth", "digest:ADMIN:KYLIN", "digest:ADMIN:KYLIN"));

        map.put("getZKAcls",
                new PropertiesEntity("kylin.env.zookeeper.zk-acl", "world:anyone:rwcda", "world:anyone:rwcda"));

        map.put("getYarnStatusCheckUrl", new PropertiesEntity("kylin.job.yarn-app-rest-check-status-url", "", ""));

        map.put("getQueryConcurrentRunningThresholdForProject",
                new PropertiesEntity("kylin.query.project-concurrent-running-threshold", "0", 0));

        map.put("isAdminUserExportAllowed", new PropertiesEntity("kylin.web.export-allow-admin", "true", true));

        map.put("isNoneAdminUserExportAllowed", new PropertiesEntity("kylin.web.export-allow-other", "true", true));

        map.put("getMetadataUrl", new PropertiesEntity("kylin.metadata.url", "kylin_metadata@jdbc",
                StorageURL.valueOf("kylin_metadata@jdbc")));

        map.put("getMetadataAuditLogMaxSize",
                new PropertiesEntity("kylin.metadata.audit-log.max-size", "3000000", 3000000L));

        map.put("getHdfsMetaStoreFileSystemSchemas",
                new PropertiesEntity("kylin.metadata.hdfs-compatible-schemas",
                        "hdfs,maprfs, s3, s3a, wasb,wasbs,adl,adls,abfs,abfss, gs,oss", new String[] { "hdfs", "maprfs",
                                "s3", "s3a", "wasb", "wasbs", "adl", "adls", "abfs", "abfss", "gs", "oss" }));

        map.put("getSecurityProfile", new PropertiesEntity("kylin.security.profile", "testing", "testing"));

        map.put("getCubeDimensionCustomEncodingFactories",
                new PropertiesEntity("kylin.metadata.custom-dimension-encodings", "", new String[0]));

        map.put("isCheckCopyOnWrite", new PropertiesEntity("kylin.metadata.check-copy-on-write", "false", false));

        map.put("isCheckHostname", new PropertiesEntity("kylin.env.hostname-check-enabled", "false", false));

        map.put("getServerAddress", new PropertiesEntity("kylin.server.address", "127.0.0.1:7070", "127.0.0.1:7070"));

        map.put("getServerPort", new PropertiesEntity("server.port", "7070", "7070"));

        map.put("isServerHttpsEnabled", new PropertiesEntity("kylin.server.https.enable", "false", false));

        map.put("getServerHttpsPort", new PropertiesEntity("kylin.server.https.port", "7443", 7443));

        map.put("getServerHttpsKeyType", new PropertiesEntity("kylin.server.https.keystore-type", "JKS", "JKS"));

        map.put("getServerHttpsKeystore",
                new PropertiesEntity("kylin.server.https.keystore-file", "/server/.keystore", "/server/.keystore"));

        map.put("getServerHttpsKeyPassword",
                new PropertiesEntity("kylin.server.https.keystore-password", "changeit", "changeit"));

        map.put("getServerHttpsKeyAlias", new PropertiesEntity("kylin.server.https.key-alias", "", ""));

        map.put("isSemiAutoMode", new PropertiesEntity("kylin.metadata.semi-automatic-mode", "false", false));

        map.put("exposeComputedColumn",
                new PropertiesEntity("kylin.query.metadata.expose-computed-column", "false", false));

        map.put("getCalciteQuoting",
                new PropertiesEntity("kylin.query.calcite.extras-props.quoting", "DOUBLE_QUOTE", "DOUBLE_QUOTE"));

        map.put("isSnapshotParallelBuildEnabled",
                new PropertiesEntity("kylin.snapshot.parallel-build-enabled", "true", true));

        map.put("snapshotParallelBuildTimeoutSeconds",
                new PropertiesEntity("kylin.snapshot.parallel-build-timeout-seconds", "3600", 3600));

        map.put("getSnapshotMaxVersions", new PropertiesEntity("kylin.snapshot.max-versions", "3", 3));

        map.put("getSnapshotVersionTTL", new PropertiesEntity("kylin.snapshot.version-ttl", "259200000", 259200000L));

        map.put("getSnapshotShardSizeMB", new PropertiesEntity("kylin.snapshot.shard-size-mb", "128", 128));

        map.put("getGlobalDictV2MinHashPartitions",
                new PropertiesEntity("kylin.dictionary.globalV2-min-hash-partitions", "1", 1));

        map.put("getGlobalDictV2ThresholdBucketSize",
                new PropertiesEntity("kylin.dictionary.globalV2-threshold-bucket-size", "500000", 500000));

        map.put("getGlobalDictV2InitLoadFactor",
                new PropertiesEntity("kylin.dictionary.globalV2-init-load-factor", "0.5", 0.5));

        map.put("getGlobalDictV2BucketOverheadFactor",
                new PropertiesEntity("kylin.dictionary.globalV2-bucket-overhead-factor", "1.5", 1.5));

        map.put("getGlobalDictV2MaxVersions", new PropertiesEntity("kylin.dictionary.globalV2-max-versions", "3", 3));

        map.put("getGlobalDictV2VersionTTL",
                new PropertiesEntity("kylin.dictionary.globalV2-version-ttl", "259200000", 259200000L));

        map.put("getNullEncodingOptimizeThreshold",
                new PropertiesEntity("kylin.dictionary.null-encoding-opt-threshold", "40000000", 40000000L));

        map.put("getSegmentAdvisor", new PropertiesEntity("kylin.cube.segment-advisor",
                "org.apache.kylin.cube.CubeSegmentAdvisor", "org.apache.kylin.cube.CubeSegmentAdvisor"));

        map.put("getCubeAggrGroupMaxCombination",
                new PropertiesEntity("kylin.cube.aggrgroup.max-combination", "4096", 4096L));

        map.put("getCubeAggrGroupIsMandatoryOnlyValid",
                new PropertiesEntity("kylin.cube.aggrgroup.is-mandatory-only-valid", "true", true));

        map.put("isBaseCuboidAlwaysValid",
                new PropertiesEntity("kylin.cube.aggrgroup.is-base-cuboid-always-valid", "true", true));

        map.put("getRunAsRemoteCommand", new PropertiesEntity("kylin.job.use-remote-cli", "true", true));

        map.put("getRemoteHadoopCliPort", new PropertiesEntity("kylin.job.remote-cli-port", "22", 22));

        map.put("getRemoteHadoopCliHostname",
                new PropertiesEntity("kylin.job.remote-cli-hostname", "localhost", "localhost"));

        map.put("getRemoteHadoopCliUsername", new PropertiesEntity("kylin.job.remote-cli-username", "root", "root"));

        map.put("getRemoteHadoopCliPassword",
                new PropertiesEntity("kylin.job.remote-cli-password", "hadoop", "hadoop"));

        map.put("getRemoteSSHPort", new PropertiesEntity("kylin.job.remote-ssh-port", "22", 22));

        map.put("getRemoteSSHUsername", new PropertiesEntity("kylin.job.ssh-username", "root", "root"));

        map.put("getRemoteSSHPassword", new PropertiesEntity("kylin.job.ssh-password", "hadoop", "hadoop"));

        map.put("getCliWorkingDir", new PropertiesEntity("kylin.job.remote-cli-working-dir", "/kylin", "/kylin"));

        map.put("getMaxConcurrentJobLimit", new PropertiesEntity("kylin.job.max-concurrent-jobs", "10", 10));

        map.put("getMaxStreamingConcurrentJobLimit",
                new PropertiesEntity("kylin.streaming.job.max-concurrent-jobs", "10", 10));

        map.put("getAutoSetConcurrentJob", new PropertiesEntity("kylin.job.auto-set-concurrent-jobs", "true", true));

        map.put("isCtlJobPriorCrossProj",
                new PropertiesEntity("kylin.job.control-job-priority-cross-project", "true", true));

        map.put("getMaxLocalConsumptionRatio",
                new PropertiesEntity("kylin.job.max-local-consumption-ratio", "0.5", 0.5));

        map.put("isMailEnabled", new PropertiesEntity("kylin.job.notification-enabled", "false", false));

        map.put("isStarttlsEnabled",
                new PropertiesEntity("kylin.job.notification-mail-enable-starttls", "false", false));

        map.put("getSmtpPort", new PropertiesEntity("kylin.job.notification-mail-port", "25", "25"));

        map.put("getMailHost", new PropertiesEntity("kylin.job.notification-mail-host", "", ""));

        map.put("getMailUsername", new PropertiesEntity("kylin.job.notification-mail-username", "", ""));

        map.put("getMailPassword", new PropertiesEntity("kylin.job.notification-mail-password", "", ""));

        map.put("getMailSender", new PropertiesEntity("kylin.job.notification-mail-sender", "", ""));

        map.put("getAdminDls", new PropertiesEntity("kylin.job.notification-admin-emails", "", new String[0]));

        map.put("getJobRetry", new PropertiesEntity("kylin.job.retry", "0", 0));

        map.put("getJobRetryInterval", new PropertiesEntity("kylin.job.retry-interval", "30000", 30000));

        map.put("getJobRetryExceptions", new PropertiesEntity("kylin.job.retry-exception-classes", "", new String[0]));

        map.put("getJobControllerLock",
                new PropertiesEntity("kylin.job.lock", "org.apache.kylin.storage.hbase.util.ZookeeperJobLock",
                        "org.apache.kylin.storage.hbase.util.ZookeeperJobLock"));

        map.put("getSchedulerPollIntervalSecond",
                new PropertiesEntity("kylin.job.scheduler.poll-interval-second", "30", 30));

        map.put("isFlatTableJoinWithoutLookup",
                new PropertiesEntity("kylin.job.flat-table-join-without-lookup", "false", false));

        map.put("getJobTrackingURLPattern", new PropertiesEntity("kylin.job.tracking-url-pattern", "", ""));

        map.put("isJobLogPrintEnabled", new PropertiesEntity("kylin.job.log-print-enabled", "true", true));

        map.put("getDefaultSource", new PropertiesEntity("kylin.source.default", "9", 9));

        map.put("getHiveUrl", new PropertiesEntity("kylin.source.hive.connection-url", "", ""));

        map.put("getHiveUser", new PropertiesEntity("kylin.source.hive.connection-user", "", ""));

        map.put("getHivePassword", new PropertiesEntity("kylin.source.hive.connection-password", "", ""));

        map.put("getHiveDatabaseForIntermediateTable",
                new PropertiesEntity("kylin.source.hive.database-for-flat-table", "default", "default"));

        map.put("getHiveClientMode", new PropertiesEntity("kylin.source.hive.client", "cli", "cli"));

        map.put("getHiveBeelineParams", new PropertiesEntity("kylin.source.hive.beeline-params", "", ""));

        map.put("getDefaultVarcharPrecision",
                new PropertiesEntity("kylin.source.hive.default-varchar-precision", "4096", 4096));

        map.put("getDefaultCharPrecision",
                new PropertiesEntity("kylin.source.hive.default-char-precision", "255", 255));

        map.put("getDefaultDecimalPrecision",
                new PropertiesEntity("kylin.source.hive.default-decimal-precision", "19", 19));

        map.put("getDefaultDecimalScale", new PropertiesEntity("kylin.source.hive.default-decimal-scale", "4", 4));

        map.put("getJdbcConnectionUrl", new PropertiesEntity("kylin.source.jdbc.connection-url",
                "jdbc:postgresql://sandbox:5432/kylin", "jdbc:postgresql://sandbox:5432/kylin"));

        map.put("getJdbcDriver",
                new PropertiesEntity("kylin.source.jdbc.driver", "org.postgresql.Driver", "org.postgresql.Driver"));

        map.put("getJdbcDialect", new PropertiesEntity("kylin.source.jdbc.dialect", "mysql", "mysql"));

        map.put("getJdbcUser", new PropertiesEntity("kylin.source.jdbc.user", "postgres", "postgres"));

        map.put("getJdbcEnable", new PropertiesEntity(KYLIN_SOURCE_JDBC_SOURCE_ENABLE_KEY, "true", true));
        map.put("getJdbcSourceName", new PropertiesEntity(KYLIN_SOURCE_JDBC_SOURCE_NAME_KEY, "gbase", "gbase"));

        map.put("getDefaultStorageEngine", new PropertiesEntity("kylin.storage.default", "20", 20));

        map.put("getDefaultStorageType", new PropertiesEntity("kylin.storage.default-storage-type", "0", 0));

        map.put("getKylinJobJarPath", new PropertiesEntity("kylin.engine.spark.job-jar", "/usr/lib/", "/usr/lib/"));

        map.put("getStreamingJobJarPath",
                new PropertiesEntity("kylin.streaming.spark.job-jar", "/usr/lib/", "/usr/lib/"));

        map.put("getSparkBuildClassName",
                new PropertiesEntity("kylin.engine.spark.build-class-name",
                        "org.apache.kylin.engine.spark.job.SegmentBuildJob",
                        "org.apache.kylin.engine.spark.job.SegmentBuildJob"));

        map.put("getSparkTableSamplingClassName",
                new PropertiesEntity("kylin.engine.spark.sampling-class-name",
                        "org.apache.kylin.engine.spark.job.TableAnalyzeJob",
                        "org.apache.kylin.engine.spark.job.TableAnalyzeJob"));

        map.put("getSparkMergeClassName",
                new PropertiesEntity("kylin.engine.spark.merge-class-name",
                        "org.apache.kylin.engine.spark.job.SegmentMergeJob",
                        "org.apache.kylin.engine.spark.job.SegmentMergeJob"));

        map.put("getClusterManagerClassName", new PropertiesEntity("kylin.engine.spark.cluster-manager-class-name",
                "org.apache.kylin.cluster.YarnClusterManager", "org.apache.kylin.cluster.YarnClusterManager"));

        map.put("getBuildingCacheThreshold", new PropertiesEntity("kylin.engine.spark.cache-threshold", "100", 100));

        map.put("getSparkEngineMaxRetryTime", new PropertiesEntity("kylin.engine.max-retry-time", "3", 3));

        map.put("getSparkEngineRetryMemoryGradient",
                new PropertiesEntity("kylin.engine.retry-memory-gradient", "1.5", 1.5));

        map.put("getSparkEngineRetryOverheadMemoryGradient",
                new PropertiesEntity("kylin.engine.retry-overheadMemory-gradient", "0.2", 0.2));

        map.put("isAutoSetSparkConf", new PropertiesEntity("kylin.spark-conf.auto-prior", "true", true));

        map.put("getMaxAllocationResourceProportion",
                new PropertiesEntity("kylin.engine.max-allocation-proportion", "0.9", 0.9));

        map.put("getSparkEngineDriverMemoryTableSampling",
                new PropertiesEntity("kylin.engine.driver-memory-table-sampling", "1024", 1024));

        map.put("getSparkEngineDriverMemoryBase",
                new PropertiesEntity("kylin.engine.driver-memory-base", "1024", 1024));

        map.put("getSparkEngineDriverMemoryStrategy",
                new PropertiesEntity("kylin.engine.driver-memory-strategy", "2,20,100", new int[] { 2, 20, 100 }));

        map.put("getSparkEngineDriverMemoryMaximum",
                new PropertiesEntity("kylin.engine.driver-memory-maximum", "4096", 4096));

        map.put("getSparkEngineTaskCoreFactor", new PropertiesEntity("kylin.engine.spark.task-core-factor", "3", 3));

        map.put("getSparkEngineSampleSplitThreshold",
                new PropertiesEntity("kylin.engine.spark.sample-split-threshold", "256m", "256m"));

        map.put("isSparkEngineTaskImpactInstanceEnabled",
                new PropertiesEntity("kylin.engine.spark.task-impact-instance-enabled", "true", true));

        map.put("isSparderAsync", new PropertiesEntity("kylin.query.init-sparder-async", "true", true));

        map.put("getSparkEngineBaseExuctorInstances",
                new PropertiesEntity("kylin.engine.base-executor-instance", "5", 5));

        map.put("getSparkEngineExuctorInstanceStrategy", new PropertiesEntity("kylin.engine.executor-instance-strategy",
                "100,2,500,3,1000,4", "100,2,500,3,1000,4"));

        map.put("getSparkEngineResourceRequestOverLimitProportion",
                new PropertiesEntity("kylin.engine.resource-request-over-limit-proportion", "1.0", 1.0));

        map.put("getHadoopConfDir", new PropertiesEntity("kylin.env.hadoop-conf-dir", "", ""));

        map.put("isUseTableIndexAnswerNonRawQuery",
                new PropertiesEntity("kylin.query.use-tableindex-answer-non-raw-query", "false", false));

        map.put("isTransactionEnabledInQuery", new PropertiesEntity("kylin.query.transaction-enable", "false", false));

        map.put("isConvertCreateTableToWith",
                new PropertiesEntity("kylin.query.convert-create-table-to-with", "false", false));

        map.put("getCalciteAddRule", new PropertiesEntity("kylin.query.calcite.add-rule", "rule1,rule2",
                Lists.newArrayList("rule1", "rule2")));

        map.put("getCalciteRemoveRule", new PropertiesEntity("kylin.query.calcite.remove-rule", "rule1,rule2",
                Lists.newArrayList("rule1", "rule2")));

        map.put("isReplaceColCountWithCountStar",
                new PropertiesEntity("kylin.query.replace-count-column-with-count-star", "false", false));

        map.put("getForceLimit", new PropertiesEntity("kylin.query.force-limit", "-1", -1));

        map.put("getEmptyResultForSelectStar",
                new PropertiesEntity("kylin.query.return-empty-result-on-select-star", "false", false));

        map.put("getLargeQueryThreshold",
                new PropertiesEntity("kylin.query.large-query-threshold", String.valueOf(1000000), 1000000L));

        map.put("getQueryTransformers", new PropertiesEntity("kylin.query.transformers", "", new String[0]));

        map.put("getQueryInterceptors", new PropertiesEntity("kylin.query.interceptors", "", new String[0]));

        map.put("getQueryDurationCacheThreshold",
                new PropertiesEntity("kylin.query.cache-threshold-duration", String.valueOf(2000), 2000L));

        map.put("getQueryScanCountCacheThreshold",
                new PropertiesEntity("kylin.query.cache-threshold-scan-count", String.valueOf(10 * 1024), 10 * 1024L));

        map.put("getQueryScanBytesCacheThreshold", new PropertiesEntity("kylin.query.cache-threshold-scan-bytes",
                String.valueOf(1024 * 1024), 1024 * 1024L));

        map.put("isQueryCacheEnabled", new PropertiesEntity("kylin.query.cache-enabled", "true", true));

        map.put("isQueryIgnoreUnknownFunction",
                new PropertiesEntity("kylin.query.ignore-unknown-function", "false", false));

        map.put("isQueryMatchPartialInnerJoinModel",
                new PropertiesEntity("kylin.query.match-partial-inner-join-model", "false", false));

        map.put("getQueryAccessController", new PropertiesEntity("kylin.query.access-controller", "", ""));

        map.put("getDimCountDistinctMaxCardinality",
                new PropertiesEntity("kylin.query.max-dimension-count-distinct", "5000000", 5000000));

        map.put("getSlowQueryDefaultDetectIntervalSeconds",
                new PropertiesEntity("kylin.query.slowquery-detect-interval", "3", 3));

        map.put("getQueryTimeoutSeconds", new PropertiesEntity("kylin.query.timeout-seconds", "300", 300));

        map.put("getQueryVIPRole", new PropertiesEntity("kylin.query.vip-role", "", ""));

        map.put("getPushDownRunnerClassName", new PropertiesEntity("kylin.query.pushdown.runner-class-name", "", ""));

        map.put("getPushDownRunnerClassNameWithDefaultValue",
                new PropertiesEntity("kylin.query.pushdown.runner-class-name", "",
                        "org.apache.kylin.query.pushdown.PushDownRunnerSparkImpl"));

        map.put("getPushDownConverterClassNames",
                new PropertiesEntity("kylin.query.pushdown.converter-class-names", "",
                        new String[] { "org.apache.kylin.source.adhocquery.DoubleQuotePushDownConverter",
                                "org.apache.kylin.query.util.PowerBIConverter",
                                "org.apache.kylin.query.util.KeywordDefaultDirtyHack",
                                "org.apache.kylin.query.util.RestoreFromComputedColumn",
                                "org.apache.kylin.query.security.RowFilter",
                                "org.apache.kylin.query.security.HackSelectStarWithColumnACL",
                                "org.apache.kylin.query.util.SchemaConverter",
                                "org.apache.kylin.query.util.SparkSQLFunctionConverter" }));

        map.put("isPushdownQueryCacheEnabled",
                new PropertiesEntity("kylin.query.pushdown.cache-enabled", "false", false));

        map.put("isAutoSetPushDownPartitions",
                new PropertiesEntity("kylin.query.pushdown.auto-set-shuffle-partitions-enabled", "true", true));

        map.put("getBaseShufflePartitionSize",
                new PropertiesEntity("kylin.query.pushdown.base-shuffle-partition-size", "48", 48));

        map.put("getHiveMetastoreExtraClassPath",
                new PropertiesEntity("kylin.query.pushdown.hive-extra-class-path", "", ""));

        map.put("getJdbcUrl", new PropertiesEntity("kylin.query.pushdown.jdbc.url", "", ""));

        map.put("getJdbcDriverClass", new PropertiesEntity("kylin.query.pushdown.jdbc.driver", "", ""));

        map.put("getJdbcUsername", new PropertiesEntity("kylin.query.pushdown.jdbc.username", "", ""));

        map.put("getJdbcPassword", new PropertiesEntity("kylin.query.pushdown.jdbc.password", "", ""));

        map.put("getPoolMaxTotal", new PropertiesEntity("kylin.query.pushdown.jdbc.pool-max-total", "8", 8));

        map.put("getPoolMaxIdle", new PropertiesEntity("kylin.query.pushdown.jdbc.pool-max-idle", "8", 8));

        map.put("getPoolMinIdle", new PropertiesEntity("kylin.query.pushdown.jdbc.pool-min-idle", "0", 0));

        map.put("isAclTCREnabled", new PropertiesEntity("kylin.query.security.acl-tcr-enabled", "true", true));

        map.put("isEscapeDefaultKeywordEnabled",
                new PropertiesEntity("kylin.query.escape-default-keyword", "false", false));

        map.put("getQueryRealizationFilter", new PropertiesEntity("kylin.query.realization-filter", "", ""));

        map.put("getServerMode", new PropertiesEntity("kylin.server.mode", "all", "all"));

        map.put("getStreamingChangeMeta", new PropertiesEntity("kylin.server.streaming-change-meta", "false", false));

        map.put("getServerUserCacheExpireSeconds",
                new PropertiesEntity("kylin.server.auth-user-cache.expire-seconds", "300", 300));

        map.put("getServerUserCacheMaxEntries",
                new PropertiesEntity("kylin.server.auth-user-cache.max-entries", "100", 100));

        map.put("getExternalAclProvider", new PropertiesEntity("kylin.server.external-acl-provider", "", ""));

        map.put("getLDAPUserSearchBase", new PropertiesEntity("kylin.security.ldap.user-search-base", "", ""));

        map.put("getLDAPGroupSearchBase", new PropertiesEntity("kylin.security.ldap.user-group-search-base", "", ""));

        map.put("getLDAPAdminRole", new PropertiesEntity("kylin.security.acl.admin-role", "", ""));

        map.put("getTimeZone", new PropertiesEntity("kylin.web.timezone", TimeZone.getDefault().getID(),
                TimeZone.getDefault().getID()));

        map.put("getRestClientDefaultMaxPerRoute",
                new PropertiesEntity("kylin.restclient.connection.default-max-per-route", "20", 20));

        map.put("getRestClientMaxTotal", new PropertiesEntity("kylin.restclient.connection.max-total", "200", 200));

        map.put("getFavoriteQueryAccelerateThreshold",
                new PropertiesEntity("kylin.favorite.query-accelerate-threshold", "20", 20));

        map.put("getFavoriteQueryAccelerateTipsEnabled",
                new PropertiesEntity("kylin.favorite.query-accelerate-tips-enable", "true", true));

        map.put("getAutoMarkFavoriteInterval",
                new PropertiesEntity("kylin.favorite.auto-mark-detection-interval-minutes", "60", 60 * 60));

        map.put("getFavoriteStatisticsCollectionInterval",
                new PropertiesEntity("kylin.favorite.statistics-collection-interval-minutes", "10", 10 * 60));

        map.put("getFavoriteAccelerateBatchSize",
                new PropertiesEntity("kylin.favorite.batch-accelerate-size", "500", 500));

        map.put("getQueryHistoryScanPeriod",
                new PropertiesEntity("kylin.favorite.query-history-scan-period-minutes", "200", 200 * 60 * 1000L));

        map.put("getQueryHistoryMaxScanInterval", new PropertiesEntity("kylin.favorite.query-history-max-scan-interval",
                "10", 10 * 30 * 24 * 60 * 60 * 1000L));

        map.put("getAutoCheckAccStatusBatchSize",
                new PropertiesEntity("kylin.favorite.auto-check-accelerate-batch-size", "100", 100));

        map.put("getCodahaleMetricsReportClassesNames", new PropertiesEntity("kylin.metrics.reporter-classes",
                "JsonFileMetricsReporter,JmxMetricsReporter", "JsonFileMetricsReporter,JmxMetricsReporter"));

        map.put("getMetricsFileLocation",
                new PropertiesEntity("kylin.metrics.file-location", "/tmp/report.json", "/tmp/report.json"));

        map.put("getMetricsReporterFrequency", new PropertiesEntity("kylin.metrics.file-frequency", "5000", 5000L));

        map.put("getBuildConf", new PropertiesEntity("kylin.engine.submit-hadoop-conf-dir", "", ""));

        map.put("getParquetReadFileSystem", new PropertiesEntity("kylin.storage.columnar.file-system", "", ""));

        map.put("getJdbcFileSystem", new PropertiesEntity("kylin.storage.columnar.jdbc-file-system", "", ""));

        map.put("getPropertiesWhiteList", new PropertiesEntity("kylin.web.properties.whitelist",
                "kylin.web.timezone,kylin.env,kylin.security.profile,kylin.source.default,metadata.semi-automatic-mode,kylin.cube.aggrgroup.is-base-cuboid-always-valid,kylin.htrace.show-gui-trace-toggle,kylin.web.export-allow-admin,kylin.web.export-allow-other",
                "kylin.web.timezone,kylin.env,kylin.security.profile,kylin.source.default,metadata.semi-automatic-mode,kylin.cube.aggrgroup.is-base-cuboid-always-valid,kylin.htrace.show-gui-trace-toggle,kylin.web.export-allow-admin,kylin.web.export-allow-other"));

        map.put("convertInSubQueryThreshold", new PropertiesEntity("kylin.query.convert-in-to-or-threshold", "20", 20));

        map.put("isEnumerableRulesEnabled",
                new PropertiesEntity("kylin.query.calcite.enumerable-rules-enabled", "false", false));

        map.put("isReduceExpressionsRulesEnabled",
                new PropertiesEntity("kylin.query.calcite.reduce-rules-enabled", "true", true));

        map.put("getEventPollIntervalSecond", new PropertiesEntity("kylin.job.event.poll-interval-second", "60", 60));

        map.put("getIndexOptimizationLevel", new PropertiesEntity("kylin.index.optimization-level", "2", 2));

        map.put("getLayoutSimilarityThreshold",
                new PropertiesEntity("kylin.index.similarity-ratio-threshold", "0.9", 0.9));

        map.put("getSimilarityStrategyRejectThreshold",
                new PropertiesEntity("kylin.index.beyond-similarity-bias-threshold", "100000000", 100_000_000L));

        map.put("isIncludedStrategyConsiderTableIndex",
                new PropertiesEntity("kylin.index.include-strategy.consider-table-index", "true", true));

        map.put("isLowFreqStrategyConsiderTableIndex",
                new PropertiesEntity("kylin.index.frequency-strategy.consider-table-index", "true", true));

        map.put("getExecutableSurvivalTimeThreshold", new PropertiesEntity(
                "kylin.garbage.storage.executable-survival-time-threshold", "30d", 30 * 24 * 60 * 60 * 1000L));

        map.put("getStorageQuotaSize",
                new PropertiesEntity("kylin.storage.quota-in-giga-bytes", "10240", 10240L * 1024 * 1024 * 1024));

        map.put("getCuboidLayoutSurvivalTimeThreshold", new PropertiesEntity(
                "kylin.garbage.storage.cuboid-layout-survival-time-threshold", "7d", 7L * 24 * 60 * 60 * 1000));

        map.put("getJobDataLoadEmptyNotificationEnabled",
                new PropertiesEntity("kylin.job.notification-on-empty-data-load", "false", false));

        map.put("getJobErrorNotificationEnabled",
                new PropertiesEntity("kylin.job.notification-on-job-error", "false", false));

        map.put("getJobNotificationStates", new PropertiesEntity("kylin.job.notification-enable-states",
                "error,finished,discarded", new String[] { "error", "finished", "discarded" }));

        map.put("getStorageResourceSurvivalTimeThreshold",
                new PropertiesEntity("kylin.storage.resource-survival-time-threshold", "7d", 7L * 24 * 60 * 60 * 1000));

        map.put("getMetadataBackupCountThreshold",
                new PropertiesEntity("kylin.metadata.backup-count-threshold", "7", 7));

        map.put("getSchedulerLimitPerMinute",
                new PropertiesEntity("kylin.scheduler.schedule-limit-per-minute", "10", 10));

        map.put("getSchedulerJobTimeOutMinute",
                new PropertiesEntity("kylin.scheduler.schedule-job-timeout-minute", "0", 0));

        map.put("getRateLimitPermitsPerMinute", new PropertiesEntity("kylin.ratelimit.permits-per-minutes", "10", 10L));

        map.put("getSmartModeBrokenModelDeleteEnabled",
                new PropertiesEntity("kylin.metadata.broken-model-deleted-on-smart-mode", "false", false));

        map.put("getPersistFlatTableThreshold",
                new PropertiesEntity("kylin.engine.persist-flattable-threshold", "1", 1));

        map.put("isPersistFlatViewEnabled", new PropertiesEntity("kylin.engine.persist-flatview", "false", false));

        map.put("isShardingJoinOptEnabled",
                new PropertiesEntity("kylin.storage.columnar.expose-sharding-trait", "true", true));

        map.put("getQueryPartitionSplitSizeMB",
                new PropertiesEntity("kylin.storage.columnar.partition-split-size-mb", "64", 64));

        map.put("getStorageProvider",
                new PropertiesEntity("kylin.storage.provider", "org.apache.kylin.common.storage.DefaultStorageProvider",
                        "org.apache.kylin.common.storage.DefaultStorageProvider"));

        map.put("getLoadHiveTablenameIntervals",
                new PropertiesEntity("kylin.source.load-hive-tablename-interval-seconds", "3600", 3600L));

        map.put("getLoadHiveTablenameEnabled",
                new PropertiesEntity("kylin.source.load-hive-tablename-enabled", "true", true));

        map.put("getKerberosProjectLevelEnable",
                new PropertiesEntity("kylin.kerberos.project-level-enabled", "false", false));

        map.put("isSmartModelEnabled", new PropertiesEntity("kylin.env.smart-mode-enabled", "false", false));

        map.put("getEngineWriteFs", new PropertiesEntity("kylin.env.engine-write-fs", "", ""));

        map.put("isAllowedProjectAdminGrantAcl",
                new PropertiesEntity("kylin.security.allow-project-admin-grant-acl", "true", true));

        map.put("isTrackingUrlIpAddressEnabled",
                new PropertiesEntity("kylin.job.tracking-url-ip-address-enabled", "true", true));

        map.put("getJStackDumpTaskEnabled", new PropertiesEntity("kylin.task.jstack-dump-enabled", "true", true));

        map.put("getJStackDumpTaskPeriod", new PropertiesEntity("kylin.task.jstack-dump-interval-minutes", "10", 10L));

        map.put("getJStackDumpTaskLogsMaxNum",
                new PropertiesEntity("kylin.task.jstack-dump-log-files-max-count", "20", 20L));

        map.put("getQueryHistoryMaxSize",
                new PropertiesEntity("kylin.query.queryhistory.max-size", "10000000", 10000000));

        map.put("getQueryHistoryProjectMaxSize",
                new PropertiesEntity("kylin.query.queryhistory.project-max-size", "1000000", 1000000));

        map.put("getQueryHistoryBufferSize",
                new PropertiesEntity("kylin.query.queryhistory.buffer-size", "1000", 1000));

        map.put("getClusterName",
                new PropertiesEntity("kylin.server.cluster-name", "kylin_metadata@jdbc", "kylin_metadata@jdbc"));

        map.put("getZKBaseSleepTimeMs", new PropertiesEntity("kylin.env.zookeeper-base-sleep-time", "3s", 3 * 1000));

        map.put("getZKMaxRetries", new PropertiesEntity("kylin.env.zookeeper-max-retries", "3", 3));

        map.put("getRandomAdminPasswordEnabled",
                new PropertiesEntity("kylin.metadata.random-admin-password.enabled", "true", true));
        map.put("getCatchUpInterval", new PropertiesEntity("kylin.metadata.audit-log.catchup-interval", "5s", 5L));
        map.put("isSessionSecureRandomCreateEnabled",
                new PropertiesEntity("kylin.web.session.secure-random-create-enabled", "false", false));
        map.put("isSessionJdbcEncodeEnabled",
                new PropertiesEntity("kylin.web.session.jdbc-encode-enabled", "false", false));
        map.put("getSpringStoreType", new PropertiesEntity("spring.session.store-type", "jdbc", "jdbc"));
        map.put("getUserPasswordEncoder",
                new PropertiesEntity("kylin.security.user-password-encoder",
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder",
                        "org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder"));

        map.put("isOverCapacityNotificationEnabled",
                new PropertiesEntity("kylin.capacity.notification-enabled", "false", false));

        map.put("getOverCapacityMailingList",
                new PropertiesEntity("kylin.capacity.notification-emails", "", new String[0]));

        map.put("isGuardianEnabled", new PropertiesEntity("kylin.guardian.enabled", "false", false));
        map.put("getGuardianCheckInterval", new PropertiesEntity("kylin.guardian.check-interval", "1min", 60L));
        map.put("getGuardianCheckInitDelay", new PropertiesEntity("kylin.guardian.check-init-delay", "5min", 5 * 60L));
        map.put("isGuardianHAEnabled", new PropertiesEntity("kylin.guardian.ha-enabled", "true", true));
        map.put("getGuardianHACheckInterval", new PropertiesEntity("kylin.guardian.ha-check-interval", "1min", 60L));
        map.put("getGuardianHACheckInitDelay",
                new PropertiesEntity("kylin.guardian.ha-check-init-delay", "5min", 5 * 60L));
        map.put("getGuardianHealthCheckers",
                new PropertiesEntity("kylin.guardian.checkers", "org.apache.kylin.tool.daemon.checker.KEProcessChecker",
                        "org.apache.kylin.tool.daemon.checker.KEProcessChecker"));
        map.put("getGuardianFullGCCheckFactor", new PropertiesEntity("kylin.guardian.full-gc-check-factor", "5", 5));
        map.put("isFullGCRatioBeyondRestartEnabled",
                new PropertiesEntity("kylin.guardian.full-gc-duration-ratio-restart-enabled", "true", true));
        map.put("getGuardianFullGCRatioThreshold",
                new PropertiesEntity("kylin.guardian.full-gc-duration-ratio-threshold", "60", 60.0));
        map.put("isDowngradeOnFullGCBusyEnable",
                new PropertiesEntity("kylin.guardian.downgrade-on-full-gc-busy-enabled", "true", true));
        map.put("getGuardianFullGCHighWatermark",
                new PropertiesEntity("kylin.guardian.full-gc-busy-high-watermark", "40", 40.0));
        map.put("getGuardianFullGCLowWatermark",
                new PropertiesEntity("kylin.guardian.full-gc-busy-low-watermark", "20", 20.0));
        map.put("getGuardianApiFailThreshold", new PropertiesEntity("kylin.guardian.api-fail-threshold", "3", 3));
        map.put("isSparkFailRestartKeEnabled",
                new PropertiesEntity("kylin.guardian.restart-spark-fail-restart-enabled", "true", true));
        map.put("getGuardianSparkFailThreshold",
                new PropertiesEntity("kylin.guardian.restart-spark-fail-threshold", "3", 3));
        map.put("getDowngradeParallelQueryThreshold",
                new PropertiesEntity("kylin.downgrade-mode.parallel-query-threshold", "10", 10));
        map.put("isSlowQueryKillFailedRestartKeEnabled",
                new PropertiesEntity("kylin.guardian.kill-slow-query-fail-restart-enabled", "true", true));
        map.put("getGuardianSlowQueryKillFailedThreshold",
                new PropertiesEntity("kylin.guardian.kill-slow-query-fail-threshold", "3", 3));
        map.put("getSuggestModelSqlLimit", new PropertiesEntity("kylin.model.suggest-model-sql-limit", "200", 200));
        map.put("getIntersectFilterOrSeparator", new PropertiesEntity("kylin.query.intersect.separator", "|", "|"));
        map.put("getBitmapValuesUpperBound",
                new PropertiesEntity("kylin.query.bitmap-values-upper-bound", "10000000", 10000000));
        map.put("isExecuteAsEnabled", new PropertiesEntity("kylin.query.query-with-execute-as", "false", false));
        map.put("getSourceUsageSurvivalTimeThreshold",
                new PropertiesEntity("kylin.garbage.storage.sourceusage-survival-time-threshold", "90d", 7776000000L));
        map.put("isSanityCheckEnabled", new PropertiesEntity("kylin.engine.sanity-check-enabled", "false", false));
        map.put("getLoadCounterCapacity", new PropertiesEntity("kylin.query.load-counter-capacity", "50", 50));
        map.put("getLoadCounterPeriodSeconds",
                new PropertiesEntity("kylin.query.load-counter-period-seconds", "3s", 3L));
        map.put("getJobFinishedNotifierUrl", new PropertiesEntity("kylin.job.finished-notifier-url",
                "http://localhost:8088/test", "http://localhost:8088/test"));
        map.put("getJobFinishedNotifierUsername",
                new PropertiesEntity("kylin.job.finished-notifier-username", "admin", "admin"));
        map.put("getJobFinishedNotifierPassword", new PropertiesEntity("kylin.job.finished-notifier-password",
                "ENC('YeqVr9MakSFbgxEec9sBwg==')", "kylin"));
        map.put("getTurnMaintainModeRetryTimes",
                new PropertiesEntity("kylin.tool.turn-on-maintainmodel-retry-times", "3", 3));
        map.put("getCatchUpTimeout", new PropertiesEntity("kylin.metadata.audit-log.catchup-timeout", "2s", 2L));
        map.put("getMaxModelDimensionMeasureNameLength",
                new PropertiesEntity("kylin.model.dimension-measure-name.max-length", "300", 300));
        map.put("getAuditLogBatchSize", new PropertiesEntity("kylin.metadata.audit-log.batch-size", "5000", 5000));
        map.put("getDiagTaskTimeout", new PropertiesEntity("kylin.diag.task-timeout", "180s", 180L));
        map.put("getDiagTaskTimeoutBlackList", new PropertiesEntity("kylin.diag.task-timeout-black-list",
                "METADATA,LOG", ImmutableSet.copyOf("METADATA,LOG".split(","))));
        map.put("isMetadataOnlyForRead", new PropertiesEntity("kylin.env.metadata.only-for-read", "true", true));
        map.put("getGlobalDictV2StoreImpl", new PropertiesEntity("kylin.engine.global-dict.store.impl",
                "org.apache.spark.dict.NGlobalDictHDFSStore", "org.apache.spark.dict.NGlobalDictHDFSStore"));
        map.put("getJobResourceLackIgnoreExceptionClasses",
                new PropertiesEntity("kylin.job.resource-lack-ignore-exception-classes", "",
                        new String[] { "com.amazonaws.services.s3.model.AmazonS3Exception" }));
        map.put("getAADUsernameClaim", new PropertiesEntity("kylin.server.aad-username-claim", "upn", "upn"));
        map.put("getAADClientId", new PropertiesEntity("kylin.server.aad-client-id", "", ""));
        map.put("getAADTenantId", new PropertiesEntity("kylin.server.aad-tenant-id", "", ""));
        map.put("getAADTokenClockSkewSeconds",
                new PropertiesEntity("kylin.server.aad-token-clock-skew-seconds", "0", 0));
        map.put("getOktaOauth2Issuer", new PropertiesEntity("kylin.server.okta-oauth2-issuer", "", ""));
        map.put("getOktaClientId", new PropertiesEntity("kylin.server.okta-client-id", "", ""));
        map.put("getLicenseExtractor",
                new PropertiesEntity("kylin.tool.license-extractor",
                        "org.apache.kylin.rest.service.DefaultLicenseExtractor",
                        "org.apache.kylin.rest.service.DefaultLicenseExtractor"));
        map.put("getAsyncQueryResultRetainDays",
                new PropertiesEntity("kylin.query.async.result-retain-days", "7d", 7L));
        map.put("getAuditLogBatchTimeout", new PropertiesEntity("kylin.metadata.audit-log.batch-timeout", "30s", 30));
        map.put("isSnapshotManualManagementEnabled",
                new PropertiesEntity("kylin.snapshot.manual-management-enabled", "false", false));

        map.put("getMultiPartitionKeyMappingProvider",
                new PropertiesEntity("kylin.model.multi-partition-key-mapping-provider-class",
                        "org.apache.kylin.metadata.model.DefaultMultiPartitionKeyMappingProvider",
                        "org.apache.kylin.metadata.model.DefaultMultiPartitionKeyMappingProvider"));
        map.put("isGlobalDictCheckEnabled",
                new PropertiesEntity("kylin.engine.global-dict-check-enabled", "true", true));

        map.put("getNonCustomProjectConfigs",
                new PropertiesEntity("kylin.model.multi-partition-key-mapping-provider-class", "",
                        NonCustomProjectLevelConfig.listAllConfigNames()));
        map.put("getDiagObfLevel", new PropertiesEntity("kylin.diag.obf.level", "OBF", "OBF"));
        map.put("isMetadataCompressEnabled", new PropertiesEntity("kylin.metadata.compress.enabled", "true", true));

        map.put("getStreamingBaseCheckpointLocation", new PropertiesEntity("kylin.engine.streaming-checkpoint-location",
                "/kylin/checkpoint", "/kylin/checkpoint"));
        map.put("getStreamingBaseJobsLocation",
                new PropertiesEntity("kylin.engine.streaming-jobs-location", "/kylin/jobs", "/kylin/jobs"));
        map.put("getStreamingMetricsEnabled",
                new PropertiesEntity("kylin.engine.streaming-metrics-enabled", "false", false));
        map.put("getTriggerOnce", new PropertiesEntity("kylin.engine.streaming-trigger-once", "false", false));
        map.put("getStreamingSegmentMergeInterval",
                new PropertiesEntity("kylin.engine.streaming-segment-merge-interval", "60s", 60L));
        map.put("getStreamingSegmentCleanInterval",
                new PropertiesEntity("kylin.engine.streaming-segment-clean-interval", "2h", 2L));
        map.put("getStreamingSegmentMergeRatio",
                new PropertiesEntity("kylin.engine.streaming-segment-merge-ratio", "1.5", 1.5));
        map.put("getStreamingJobExecutionIdCheckInterval",
                new PropertiesEntity("kylin.streaming.job-execution-id-check-interval", "1m", 1L));
        map.put("getStreamingJobStatsSurvivalThreshold",
                new PropertiesEntity("kylin.streaming.jobstats.survival-time-threshold", "7d", 7L));
        map.put("getStreamingJobRetryEnabled",
                new PropertiesEntity("kylin.streaming.job-retry-enabled", "false", "false"));
        map.put("getStreamingJobStatusWatchEnabled",
                new PropertiesEntity("kylin.streaming.job-status-watch-enabled", "true", "true"));
        map.put("getStreamingJobRetryInterval", new PropertiesEntity("kylin.streaming.job-retry-interval", "5m", 5));
        map.put("getStreamingJobMaxRetryInterval",
                new PropertiesEntity("kylin.streaming.job-retry-max-interval", "30m", 30));
        map.put("getStreamingJobWatermark",
                new PropertiesEntity("kylin.streaming.watermark", "1 minutes", "1 minutes"));
        map.put("getKafkaMaxOffsetsPerTrigger",
                new PropertiesEntity("kylin.streaming.kafka-conf.maxOffsetsPerTrigger", "-1", "-1"));
        map.put("getServerIpAddress", new PropertiesEntity("kylin.env.ip-address", "127.0.0.1", "127.0.0.1"));
        map.put("getSystemProfileExtractor",
                new PropertiesEntity("kylin.tool.system-profile-extractor",
                        "org.apache.kylin.tool.LightningSystemProfileExtractor",
                        "org.apache.kylin.tool.LightningSystemProfileExtractor"));
        map.put("isCharDisplaySizeEnabled",
                new PropertiesEntity("kylin.query.char-display-size-enabled", "true", true));
        map.put("isAllowedNonAdminGenerateQueryDiagPackage",
                new PropertiesEntity("kylin.security.allow-non-admin-generate-query-diag-package", "true", true));
        map.put("isPrometheusMetricsEnabled", new PropertiesEntity("kylin.metrics.prometheus-enabled", "true", true));
        map.put("isSetYarnQueueInTaskEnabled",
                new PropertiesEntity("kylin.engine-yarn.queue.in.task.enabled", "false", false));
        map.put("getYarnQueueInTaskAvailable", new PropertiesEntity("kylin.engine-yarn.queue.in.task.available",
                "default", Lists.newArrayList("default")));
        map.put("getSparkEngineBuildStepsToSkip", new PropertiesEntity("kylin.engine.steps.skip", "", ""));
        map.put("getAutoModelViewEnabled", new PropertiesEntity("kylin.query.auto-model-view-enabled", "false", false));
        map.put("isBatchGetRowAclEnabled",
                new PropertiesEntity("kylin.query.batch-get-row-acl-enabled", "false", false));
        map.put("getCheckResourceEnabled", new PropertiesEntity("kylin.build.resource.check-enabled", "false", false));
        map.put("getCheckResourceTimeLimit",
                new PropertiesEntity("kylin.build.resource.check-retry-limit-minutes", "10", 10L));
        map.put("getSourceNameCaseSensitiveEnabled",
                new PropertiesEntity("kylin.source.name-case-sensitive-enabled", "", false));
        map.put("getPushdownSelectStarCaseSensitiveEnable",
                new PropertiesEntity("kylin.pushdown.select-star-case-sensitive-enabled", "true", true));
        map.put("getPushdownSelectStarLowercaseEnable",
                new PropertiesEntity("kylin.pushdown.select-star-lowercase-enabled", "", false));
        map.put("asyncProfilingEnabled", new PropertiesEntity("kylin.query.async-profiler-enabled", "true", true));
        map.put("asyncProfilingResultTimeout",
                new PropertiesEntity("kylin.query.async-profiler-result-timeout", "60s", 60000L));
        map.put("asyncProfilingProfileTimeout",
                new PropertiesEntity("kylin.query.async-profiler-profile-timeout", "5m", 300000L));
        map.put("isReadTransactionalTableEnabled",
                new PropertiesEntity("kylin.build.resource.read-transactional-table-enabled", "true", true));
        map.put("getFlatTableStorageFormat",
                new PropertiesEntity("kylin.source.hive.flat-table-storage-format", "SEQUENCEFILE", "SEQUENCEFILE"));
        map.put("getFlatTableFieldDelimiter",
                new PropertiesEntity("kylin.source.hive.flat-table-field-delimiter", "\u001F", "\u001F"));
        map.put("isSkipBasicAuthorization",
                new PropertiesEntity("kap.authorization.skip-basic-authorization", "false", false));
        map.put("getMetricsQuerySlaSeconds",
                new PropertiesEntity("kylin.metrics.query.sla.seconds", "1,3,15,60", new long[] { 1, 3, 15, 60 }));
        map.put("getMetricsJobSlaMinutes",
                new PropertiesEntity("kylin.metrics.job.sla.minutes", "1,30,60,300", new long[] { 1, 30, 60, 300 }));
        map.put("isMeasureNameCheckEnabled",
                new PropertiesEntity("kylin.model.measure-name-check-enabled", "true", true));
        map.put("isConcurrencyFetchDataSourceSize",
                new PropertiesEntity("kylin.job.concurrency-fetch-datasource-size-enabled", "true", true));
        map.put("getConcurrencyFetchDataSourceSizeThreadNumber",
                new PropertiesEntity("kylin.job.concurrency-fetch-datasource-size-thread_number", "10", 10));
        map.put("isSpark3ExecutorPrometheusEnabled",
                new PropertiesEntity("kylin.storage.columnar.spark-conf.spark.ui.prometheus.enabled", "false", false));
        map.put("getSpark3DriverPrometheusServletClass", new PropertiesEntity(
                "kylin.storage.columnar.spark-conf.spark.metrics.conf.*.sink.prometheusServlet.class",
                "org.apache.spark.metrics.sink.PrometheusServlet", "org.apache.spark.metrics.sink.PrometheusServlet"));
        map.put("getSpark3DriverPrometheusServletPath",
                new PropertiesEntity(
                        "kylin.storage.columnar.spark-conf.spark.metrics.conf.*.sink.prometheusServlet.path",
                        "/metrics/prometheus", "/metrics/prometheus"));
        map.put("isRemoveLdapCustomSecurityLimitEnabled",
                new PropertiesEntity("kylin.security.remove-ldap-custom-security-limit-enabled", "false", false));
        map.put("getLightningClusterId", new PropertiesEntity("kylin.lightning.cluster-id", "0", 0L));
        map.put("getLightningWorkspaceId", new PropertiesEntity("kylin.lightning.workspace-id", "0", 0L));
        map.put("getJobCallbackLanguage", new PropertiesEntity("kylin.job.callback-language", "en", "en"));
        map.put("getMaxResultRows", new PropertiesEntity("kylin.query.max-result-rows", "0", 0));
        map.put("getLoadHiveTableWaitSparderSeconds",
                new PropertiesEntity("kylin.source.load-hive-table-wait-sparder-seconds", "900", 900));
        map.put("getLoadHiveTableWaitSparderIntervals",
                new PropertiesEntity("kylin.source.load-hive-table-wait-sparder-interval-seconds", "10", 10));
        map.put("buildJobProfilingEnabled",
                new PropertiesEntity("kylin.engine.async-profiler-enabled", "false", false));
        map.put("buildJobProfilingResultTimeout",
                new PropertiesEntity("kylin.engine.async-profiler-result-timeout", "60s", 60000L));
        map.put("buildJobProfilingProfileTimeout",
                new PropertiesEntity("kylin.engine.async-profiler-profile-timeout", "5m", 300000L));
        map.put("isHdfsMetricsPeriodicCalculationEnabled",
                new PropertiesEntity("kylin.metrics.hdfs-periodic-calculation-enabled", "false", false));
        map.put("getHdfsMetricsPeriodicCalculationInterval",
                new PropertiesEntity("kylin.metrics.hdfs-periodic-calculation-interval", "5m", 300000L));
        map.put("isSkipResourceCheck",
                new PropertiesEntity("kylin.build.resource.skip-resource-check", "false", false));
        map.put("getCalciteBindableCacheSize",
                new PropertiesEntity("kylin.query.calcite.bindable.cache.maxSize", "10", 10));
        map.put("getCalciteBindableCacheConcurrencyLevel",
                new PropertiesEntity("kylin.query.calcite.bindable.cache.concurrencyLevel", "5", 5));
        map.put("allowNegativeScaleOfDecimalEnabled", new PropertiesEntity(
                "kylin.storage.columnar.spark-conf.spark.sql.legacy.allowNegativeScaleOfDecimal", "false", false));
        map.put("decimalOperationsAllowPrecisionLoss", new PropertiesEntity(
                "kylin.storage.columnar.spark-conf.spark.sql.decimalOperations.allowPrecisionLoss", "true", true));
        map.put("isSparkUIAclEnabled", new PropertiesEntity("kylin.query.engine.spark-ui-acl.enabled", "", false));
        map.put("isInternalTablePreloadCacheEnabled",
                new PropertiesEntity("kylin.internal-table.preloaded-cache.enabled", "true", false));
        map.put("isIndexPreloadCacheEnabled",
                new PropertiesEntity("kylin.index.preloaded-cache.enabled", "true", false));
        map.put("getConcurrentRunningThresholdForGlutenCache",
                new PropertiesEntity("kylin.cache.gluten-cache-concurrent-running-threshold", "20", 20));
    }

    @Test
    void testGetStreamingJobTmpOutputStorePath() {
        String project = "default";
        String jobId = "e1ad7bb0-522e-456a-859d-2eab1df448de_build";
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        String jobOutPutDir = config.getStreamingJobTmpOutputStorePath(project, jobId);
        String expectOutputDir = config.getStreamingJobTmpDir(project) + jobId + "/";
        Assertions.assertEquals(expectOutputDir, jobOutPutDir);
    }

    @Test
    void testGetStreamingJobTmpDir() {
        String project = "default";
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        String jobTmpDir = config.getStreamingJobTmpDir(project);
        String expectDir = config.getHdfsWorkingDirectoryWithoutScheme() + "streaming/jobs/" + project + "/";
        Assertions.assertEquals(expectDir, jobTmpDir);
    }

    @Test
    @MetadataInfo(onlyProps = false)
    void testGetHdfsWorkingDirDefaultCase() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setMetadataUrl("test");
        String dir = config.getHdfsWorkingDirectory();
        Assertions.assertTrue(
                dir.endsWith("examples/test_data/" + ProcessUtils.getCurrentId("0") + "/working-dir/test/"));
    }

    @Test
    void testGetHdfsWorkingDirWhenDataDirSet() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.env.hdfs-data-working-dir", "/test/data");
        String dir = config.getHdfsWorkingDirectory();
        Assertions.assertEquals("file:///test/data/", dir);
    }

    @Test
    void testGetNonCustomProjectConfigs() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(20, config.getNonCustomProjectConfigs().size());
        config.setProperty("kylin.server.non-custom-project-configs", "kylin.job.retry");
        Assertions.assertEquals(21, config.getNonCustomProjectConfigs().size());
    }

    @Test
    void test() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Class<? extends KylinConfig> configClass = config.getClass();

        for (Map.Entry<String, PropertiesEntity> entry : map.entrySet()) {
            String func = entry.getKey();
            PropertiesEntity propertiesEntity = entry.getValue();
            Method method = configClass.getSuperclass().getDeclaredMethod(func);
            Assertions.assertNotNull(method);
            config.setProperty(propertiesEntity.getKey(), propertiesEntity.getValue());
            Object invoke = method.invoke(config);
            if (invoke != null && invoke.getClass().isArray()) {
                Class<?> componentType = invoke.getClass().getComponentType();
                if (componentType.isPrimitive()) {
                    switch (componentType.getName()) {
                    case "int":
                        Assertions.assertArrayEquals((int[]) propertiesEntity.getExpectValue(), (int[]) invoke);
                        break;
                    case "long":
                        Assertions.assertArrayEquals((long[]) propertiesEntity.getExpectValue(), (long[]) invoke);
                        break;
                    default:
                        /// just implement it
                        Assertions.fail();
                    }
                } else {
                    Assertions.assertArrayEquals((Object[]) propertiesEntity.getExpectValue(), (Object[]) invoke);
                }
            } else {
                Assertions.assertEquals(propertiesEntity.getExpectValue(), invoke);
            }
        }
    }

    @Test
    void testTimeZone() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        ZoneId zoneId = TimeZone.getTimeZone(config.getTimeZone()).toZoneId();
        // Mock the setting timezone action when launch KE
        TimeZoneUtils.setDefaultTimeZone(config);
        ZoneId zoneId1 = TimeZone.getDefault().toZoneId();
        Assertions.assertEquals(zoneId, zoneId1);
    }

    @Test
    @Timeout(value = 5)
    void testMultipleUpdateEnvironment() {
        EnvironmentUpdateUtils.put("test.environment1", "test.value1");
        EnvironmentUpdateUtils.put("test.environment2", "test.value2");
        Assertions.assertEquals("test.value1", System.getenv("test.environment1"), "Environment was not set propertly");
        Assertions.assertEquals("test.value2", System.getenv("test.environment2"), "Environment was not set propertly");
    }

    @Test
    void testConcurrentRequests() throws InterruptedException {
        int timeoutSecond = 5;
        int concurThread = 10;
        int exceptionCount = 0;
        List<ListenableFuture<Object>> pendingTasks = new ArrayList<>();
        final ExecutorService callbackExecutor = Executors.newFixedThreadPool(concurThread,
                new ThreadFactoryBuilder().setDaemon(false).setNameFormat("CallbackExecutor").build());
        ListeningExecutorService taskExecutorService = MoreExecutors.listeningDecorator(callbackExecutor);
        while (concurThread > 0) {
            ListenableFuture<Object> runningTaskFuture = taskExecutorService.submit(new EnvironmentRequest());
            pendingTasks.add(runningTaskFuture);
            concurThread--;
        }

        // no concurrent exception
        KylinConfig.getInstanceFromEnv().getOptional("test.environment.concurrent");

        //waiting for all threads submitted to thread pool
        for (ListenableFuture<Object> future : pendingTasks) {
            try {
                future.get();
            } catch (ExecutionException e) {
                exceptionCount++;
            }
        }

        //stop accepting new threads and shutdown threadpool
        taskExecutorService.shutdown();
        try {
            if (!taskExecutorService.awaitTermination(timeoutSecond, TimeUnit.SECONDS)) {
                taskExecutorService.shutdownNow();
            }
        } catch (InterruptedException ie) {
            taskExecutorService.shutdownNow();
        }

        Assertions.assertEquals(0, exceptionCount);
    }

    private static class EnvironmentRequest implements Callable<Object> {

        @Override
        public Object call() {
            EnvironmentUpdateUtils.put("test.environment.concurrent" + Thread.currentThread().getId(),
                    "test.evironment.concurrent");
            return null;
        }
    }

    @Test
    void testRedisSettings() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.cache.redis.expire-time-unit", "INVALID");
        Assertions.assertEquals("EX", config.getRedisExpireTimeUnit());
        Assertions.assertEquals(2000, config.getRedisConnectionTimeout());
        Assertions.assertEquals(2000, config.getRedisSoTimeout());
        Assertions.assertEquals(20, config.getRedisMaxAttempts());
    }

    @Test
    void testMetadataUrlSetting() {
        val config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(config.getStreamingStatsUrl().toString(), config.getMetadataUrl().toString());
        Assertions.assertEquals(config.getQueryHistoryUrl().toString(), config.getMetadataUrl().toString());
        Assertions.assertEquals(config.getJdbcShareStateUrl().toString(), config.getMetadataUrl().toString());
        val pgUrl = "kylin_metadata@jdbc,driverClassName=org.postgresql.Driver,"
                + "url=jdbc:postgresql://sandbox:5432/kylin,username=postgres,password";
        config.setStreamingStatsUrl(pgUrl);
        Assertions.assertEquals(pgUrl, config.getStreamingStatsUrl().toString());
        config.setQueryHistoryUrl(pgUrl);
        Assertions.assertEquals(pgUrl, config.getQueryHistoryUrl().toString());
        config.setJdbcShareStateUrl(pgUrl);
        Assertions.assertEquals(pgUrl, config.getJdbcShareStateUrl().toString());
    }

    @Test
    void testMetadataUrlContainsComma() {
        String url = "kylin_metadata@jdbc,driverClassName=com.mysql.jdbc.Driver,"
                + "url=\"jdbc:mysql:replication://10.1.3.12:3306,10.1.3.11:3306/kylin_test?useUnicode=true&characterEncoding=utf8\","
                + "username=kylin,password=test,maxTotal=20,maxIdle=20";
        StorageURL storageURL = StorageURL.valueOf(url);
        Assertions.assertEquals(url, storageURL.toString());
    }

    @Test
    void testConnectClusterMangerParam() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();

        Assertions.assertEquals(10, config.getClusterManagerHealthCheckMaxTimes());
        config.setProperty("kylin.engine.cluster-manager-health-check-max-times", "0");
        Assertions.assertEquals(0, config.getClusterManagerHealthCheckMaxTimes());
        config.setProperty("kylin.engine.cluster-manager-health-check-max-times", "-1");
        Assertions.assertEquals(-1, config.getClusterManagerHealthCheckMaxTimes());

        Assertions.assertEquals(120, config.getClusterManagerHealCheckIntervalSecond());
        config.setProperty("kylin.engine.cluster-manager-heal-check-interval-second", "0");
        Assertions.assertEquals(0, config.getClusterManagerHealCheckIntervalSecond());
    }

    @Test
    void testGetRoutineOpsTaskTimeOut() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(4 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "30m");
        Assertions.assertEquals(30 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "1d");
        Assertions.assertEquals(24 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "4h");
        Assertions.assertEquals(4 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
    }

    @Test
    void testBuildJobProfilingEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.engine.async-profiler-enabled", "false");
        Assertions.assertFalse(config.buildJobProfilingEnabled());
        config.setProperty("kylin.engine.async-profiler-enabled", "true");
        Assertions.assertTrue(config.buildJobProfilingEnabled());
    }

    @Test
    void testBuildJobProfilingResultTimeout() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(60000, config.buildJobProfilingResultTimeout());
        config.setProperty("kylin.engine.async-profiler-result-timeout", "2m");
        Assertions.assertEquals(120000, config.buildJobProfilingResultTimeout());
        // TODO We do not have a check for a negative time parameter, maybe we need this
        //  in the whole range of KE parameters
        config.setProperty("kylin.engine.async-profiler-result-timeout", "-1");
        Assertions.assertEquals(-1, config.buildJobProfilingResultTimeout());
    }

    @Test
    void testBuildJobProfilingProfileTimeout() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(300000, config.buildJobProfilingProfileTimeout());
        config.setProperty("kylin.engine.async-profiler-profile-timeout", "10s");
        Assertions.assertEquals(10000, config.buildJobProfilingProfileTimeout());
        // TODO We do not have a check for a negative time parameter, maybe we need this
        //  in the whole range of KE parameters
        config.setProperty("kylin.engine.async-profiler-profile-timeout", "-1");
        Assertions.assertEquals(-1, config.buildJobProfilingProfileTimeout());
    }

    @Test
    void testGetJobTmpProfilerFlagsDir() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        String project = "ke-project";
        String jobId = "job-00001";
        String jobTmpProfilerFlagsDir = config.getJobTmpProfilerFlagsDir(project, jobId);
        String expectedDir = config.getJobTmpDir(project) + jobId + "/profiler_flags";
        Assertions.assertEquals(expectedDir, jobTmpProfilerFlagsDir);
    }

    @Test
    void testGetQueryTimeoutSeconds() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(300, config.getQueryTimeoutSeconds());
        config.setProperty("kylin.query.timeout-seconds", "3");
        Assertions.assertEquals(5, config.getQueryTimeoutSeconds());
        config.setProperty("kylin.query.timeout-seconds", "5");
        Assertions.assertEquals(5, config.getQueryTimeoutSeconds());
    }

    @Test
    void testSnapshotAutoRefresh() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertFalse(config.isSnapshotAutoRefreshEnabled());
        Assertions.assertEquals("0 0 0 */1 * ?", config.getSnapshotAutoRefreshCron());
        Assertions.assertEquals(1, config.getSnapshotAutoRefreshFetchFilesCount());
        Assertions.assertEquals(1, config.getSnapshotAutoRefreshFetchPartitionsCount());
        Assertions.assertEquals(20, config.getSnapshotAutoRefreshMaxConcurrentJobLimit());
        Assertions.assertEquals(config.getHdfsWorkingDirectory("test") + SNAPSHOT_AUTO_REFRESH + "/",
                config.getSnapshotAutoRefreshDir("test"));
        Assertions.assertEquals(30 * 60 * 1000, config.getSnapshotAutoRefreshTaskTimeout());
        Assertions.assertFalse(config.isSnapshotFirstAutoRefreshEnabled());
        Assertions.assertFalse(config.isSnapshotNullLocationAutoRefreshEnabled());
    }

    @Test
    void testIsHdfsMetricsPeriodicCalculationEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.metrics.hdfs-periodic-calculation-enabled", "false");
        Assertions.assertFalse(config.isHdfsMetricsPeriodicCalculationEnabled());
        config.setProperty("kylin.metrics.hdfs-periodic-calculation-enabled", "true");
        Assertions.assertTrue(config.isHdfsMetricsPeriodicCalculationEnabled());
    }

    @Test
    void testGetHdfsMetricsPeriodicCalculationInterval() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(300000L, config.getHdfsMetricsPeriodicCalculationInterval());
        config.setProperty("kylin.metrics.hdfs-periodic-calculation-interval", "1m");
        Assertions.assertEquals(60000L, config.getHdfsMetricsPeriodicCalculationInterval());
    }

    @Test
    void testGetHdfsMetricsDir() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        String hdfsMetricsDir = config.getHdfsMetricsDir("hdfsCapacity.json");
        String expectedDir = config.getHdfsWorkingDirectory() + "_metrics/" + "hdfsCapacity.json";
        Assertions.assertEquals(hdfsMetricsDir, expectedDir);
    }

    @Test
    void testReloadKylinConfig2Properties() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        final Properties properties = config.exportToProperties();
        properties.setProperty(WRITING_CLUSTER_WORKING_DIR, "file://");
        int beforeSize = config.properties.size();
        config.reloadKylinConfig(properties);
        int afterSize = config.properties.size();
        Assertions.assertNotEquals(beforeSize, afterSize);
        // reset
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "");
    }

    @Test
    void testIsBuildFilesSeparationEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        // getBuildConf empty getWritingClusterWorkingDir empty
        config.setProperty("kylin.engine.submit-hadoop-conf-dir", "");
        config.setProperty("kylin.env.hdfs-write-working-dir", "");
        Assertions.assertFalse(config.isBuildFilesSeparationEnabled());
        // getBuildConf empty getWritingClusterWorkingDir not empty
        config.setProperty("kylin.engine.submit-hadoop-conf-dir", "");
        config.setProperty("kylin.env.hdfs-write-working-dir", "file://abc");
        Assertions.assertFalse(config.isBuildFilesSeparationEnabled());
        // getBuildConf not empty getWritingClusterWorkingDir empty
        config.setProperty("kylin.engine.submit-hadoop-conf-dir", "/kylin");
        config.setProperty("kylin.env.hdfs-write-working-dir", "");
        Assertions.assertFalse(config.isBuildFilesSeparationEnabled());
        // getBuildConf not empty getWritingClusterWorkingDir not empty
        config.setProperty("kylin.engine.submit-hadoop-conf-dir", "/kylin");
        config.setProperty("kylin.env.hdfs-write-working-dir", "file://abc");
        Assertions.assertTrue(config.isBuildFilesSeparationEnabled());
        // reset
        config.setProperty("kylin.engine.submit-hadoop-conf-dir", "");
        config.setProperty("kylin.env.hdfs-write-working-dir", "");
    }

    @Test
    void testGetWritingClusterWorkingDir() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertTrue(config.getWritingClusterWorkingDir().isEmpty());
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "hdfs://writecluster/kylin");
        Assertions.assertFalse(config.getWritingClusterWorkingDir().isEmpty());
        // Reset to prevent impacting other tests
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "");
    }

    @Test
    void testGetWriteClusterWorkingDir() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertTrue(config.getWriteClusterWorkingDir().isEmpty());
        config.setProperty("kylin.env.write-hdfs-working-dir", "hdfs://writecluster/kylin");
        Assertions.assertEquals("hdfs://writecluster/kylin", config.getWriteClusterWorkingDir());
        // Reset to prevent impacting other tests
        config.setProperty("kylin.env.write-hdfs-working-dir", "");
    }

    @Test
    void testGetWritingClusterWorkingDirWithSuffix() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        // path empty
        Assert.assertThrows("Can not create a Path from an empty string", IllegalArgumentException.class,
                () -> config.getWritingClusterWorkingDir(""));
        // path not absolute
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "../kylin");
        Assert.assertThrows("kylin.env.hdfs-write-working-dir must be absolute, but got ../kylin",
                IllegalArgumentException.class, () -> config.getWritingClusterWorkingDir(""));
        // with suffix
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "/kylin/");
        Assertions.assertTrue(config.getWritingClusterWorkingDir("project/flat_table").contains("/kylin"));
        // Reset to prevent impacting other tests
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "");
    }

    @Test
    void testGetFlatTableDir() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        String project = "readWriteSeparation";
        String dataFlowId = "3791a20e";
        String segmentId = "60c51f8e";
        String flatTableDirSuffix = project + "/flat_table/" + dataFlowId + PATH_DELIMITER + segmentId;
        Assertions.assertEquals(config.getFlatTableDir(project, dataFlowId, segmentId),
                new Path(config.getHdfsWorkingDirectory() + flatTableDirSuffix));
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "/kylin/");
        Assertions.assertEquals(config.getFlatTableDir(project, dataFlowId, segmentId),
                new Path(config.getWritingClusterWorkingDir(flatTableDirSuffix)));
        // Reset to prevent impacting other tests
        config.setProperty(WRITING_CLUSTER_WORKING_DIR, "");
    }

    @Test
    void testGetSubstitutor() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        val sub1 = config.getSubstitutor();
        val sub2 = config.getSubstitutor();
        Assertions.assertSame(sub1, sub2);
    }

    @Test
    void testIsBuildSegmentOverlapEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.build.segment-overlap-enabled", "false");
        Assertions.assertFalse(config.isBuildSegmentOverlapEnabled());
        config.setProperty("kylin.build.segment-overlap-enabled", "true");
        Assertions.assertTrue(config.isBuildSegmentOverlapEnabled());
    }

    @Test
    void testIsJobTmpDirReadWritePermissionEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.engine.job-tmp-dir-all-permission-enabled", "false");
        Assertions.assertFalse(config.isJobTmpDirALLPermissionEnabled());
        config.setProperty("kylin.engine.job-tmp-dir-all-permission-enabled", "true");
        Assertions.assertTrue(config.isJobTmpDirALLPermissionEnabled());
    }

    @Test
    void testIsQuotaStorageEnabled() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertFalse(config.isStorageQuotaEnabled());
        config.setProperty("kylin.storage.check-quota-enabled", "true");
        Assertions.assertTrue(config.isStorageQuotaEnabled());
    }

    @Test
    void testCalciteBindableCacheSize() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(10, config.getCalciteBindableCacheSize());
        config.setProperty("kylin.query.calcite.bindable.cache.maxSize", "7");
        Assertions.assertEquals(7, config.getCalciteBindableCacheSize());
    }

    @Test
    void testCalciteBindableCacheConcurrencyLevel() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(5, config.getCalciteBindableCacheConcurrencyLevel());
        config.setProperty("kylin.query.calcite.bindable.cache.concurrencyLevel", "3");
        Assertions.assertEquals(3, config.getCalciteBindableCacheConcurrencyLevel());
    }

    @Test
    void testGetMaxMeasureSegmentPrunerBeforeDays() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        long defaultValue = config.getMaxMeasureSegmentPrunerBeforeDays();
        Assertions.assertEquals(-1, defaultValue);
        config.setProperty("kylin.query.max-measure-segment-pruner-before-days", "1");
        Assertions.assertEquals(1, config.getMaxMeasureSegmentPrunerBeforeDays());
    }

    @Test
    void testRemoteSSHPassword() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();

        String password = "hadoop";
        String encPassword = "ENC('KotkDR//FvNP+8x+G5G93g==')";
        Assertions.assertEquals(encPassword, EncryptUtil.encryptWithPrefix(password));

        config.setProperty("kylin.job.ssh-password", password);
        Assertions.assertEquals(password, config.getRemoteSSHPassword());

        config.setProperty("kylin.job.ssh-password", encPassword);
        Assertions.assertEquals(password, config.getRemoteSSHPassword());
    }

    @Test
    void testMultiTenantMode() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertFalse(config.isKylinMultiTenantEnabled());
        config.setProperty("kylin.multi-tenant.enabled", "true");
        Assertions.assertTrue(config.isKylinMultiTenantEnabled());

        Assertions.assertEquals(30 * 60 * 1000, config.getKylinMultiTenantRouteTaskTimeOut());
        config.setProperty("kylin.multi-tenant.route-task-timeout", "10min");
        Assertions.assertEquals(10 * 60 * 1000, config.getKylinMultiTenantRouteTaskTimeOut());
    }

    @Test
    void testGetZKAuths() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertTrue(StringUtils.isBlank(config.getZKAuths()));

        config.setProperty("kylin.env.zookeeper.zk-auth", EncryptUtil.encryptWithPrefix("digest:ADMIN:KYLIN"));
        Assertions.assertEquals("digest:ADMIN:KYLIN", config.getZKAuths());
    }

    @Test
    void testGetMetadataAuditLogMaxSize() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(500000, config.getMetadataAuditLogMaxSize());

        config.setProperty("kylin.metadata.audit-log.max-size", "3000000");
        Assertions.assertEquals(3000000, config.getMetadataAuditLogMaxSize());
    }

    @Test
    void testGetTableAccessCache() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();

        Assertions.assertTrue(config.getTableAccessCacheEnable());
        config.setProperty("kylin.source.hive.table-access-cache-enabled", FALSE);
        Assertions.assertFalse(config.getTableAccessCacheEnable());

        Assertions.assertEquals(100000, config.getTableAccessCacheSize());
        config.setProperty("kylin.source.hive.table-access-cache-size", "200000");
        Assertions.assertEquals(200000, config.getTableAccessCacheSize());

        Assertions.assertEquals(10080, config.getTableAccessCacheTTL());
        config.setProperty("kylin.source.hive.table-access-cache-ttl", "1m");
        Assertions.assertEquals(1, config.getTableAccessCacheTTL());
        config.setProperty("kylin.source.hive.table-access-cache-ttl", "1h");
        Assertions.assertEquals(60, config.getTableAccessCacheTTL());
        config.setProperty("kylin.source.hive.table-access-cache-ttl", "1d");
        Assertions.assertEquals(1440, config.getTableAccessCacheTTL());
    }

    @Test
    void testSparkPeriodicGCEnable() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assertions.assertEquals(TRUE, config.sparkPeriodicGCEnabled());

        config.setProperty("kylin.query.engine.periodicGC.crontab", " ");
        Assertions.assertEquals(TRUE, config.sparkPeriodicGCEnabled());

        config.setProperty("kylin.query.engine.periodicGC.crontab", "-");
        Assertions.assertEquals(TRUE, config.sparkPeriodicGCEnabled());

        config.setProperty("kylin.query.engine.periodicGC.crontab", "0 0 12 * * ?");
        Assertions.assertEquals(FALSE, config.sparkPeriodicGCEnabled());
    }

    @Test
    void testGetSourceProviderFamilyMapping() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Map<Integer, List<Integer>> map;

        // No provider-family set
        map = config.getSourceProviderFamilyMapping();
        Assertions.assertEquals(0, map.size());

        // 9 refers to ISourceAware.ID_SPARK
        config.setProperty("kylin.source.provider-family.9", "3001");
        map = config.getSourceProviderFamilyMapping();
        Assertions.assertEquals(1, map.size());
        Assertions.assertEquals(1, map.get(9).size());
        Assertions.assertEquals(3001, map.get(9).get(0));

        // 13 refers to ISourceAware.ID_FILE
        config.setProperty("kylin.source.provider-family.9", "3001,3002,3003");
        config.setProperty("kylin.source.provider-family.13", "5001,5002");
        map = config.getSourceProviderFamilyMapping();
        Assertions.assertEquals(2, map.size());
        Assertions.assertEquals(3, map.get(9).size());
        Assertions.assertEquals(2, map.get(13).size());
        Assertions.assertEquals(3003, map.get(9).get(2));
        Assertions.assertEquals(5002, map.get(13).get(1));
    }

    private void assertUnorderedEqualsIgnoringEmpty(String expected, String actual) {
        Set<String> actualSet = new HashSet<>(Arrays.asList(actual.split(",")));
        Set<String> expectedSet = new HashSet<>(Arrays.asList(expected.split(",")));

        actualSet.removeIf(StringUtils::isBlank);
        expectedSet.removeIf(StringUtils::isBlank);

        Assertions.assertEquals(expectedSet, actualSet, "expectedSet: " + expectedSet + " === actualSet: " + actualSet);
    }

    @Test
    public void getKylinExtJarsPath() throws Exception {
        val config = KylinConfig.getInstanceFromEnv();
        val kylinHome = KylinConfig.getKylinHome();
        val libExtDir = new File(kylinHome + File.separator + "lib/ext");
        FileUtils.forceMkdir(libExtDir);
        val glutenJar = new File(libExtDir, "gluten.jar");
        FileUtils.write(glutenJar, "gluten jar");
        val celebornJar = new File(libExtDir, "celeborn-client-spark-3.jar");
        FileUtils.write(celebornJar, "celeborn client spark3 jar");
        val mysqlJar = new File(libExtDir, "mysqlJar.jar");
        FileUtils.write(mysqlJar, "mysqlJar jar");
        val glutenCelebornJar = new File(libExtDir, "gluten-celeborn-clickhouse-jar-with-dependencies.jar");
        FileUtils.write(glutenCelebornJar, "gluten celeborn jar");

        val withGluten = config.getKylinExtJarsPath(true);
        val withGlutenExpected = "," + celebornJar.getAbsolutePath() + "," + glutenJar.getAbsolutePath() + ","
                + mysqlJar.getAbsolutePath() + "," + glutenCelebornJar.getAbsolutePath();
        assertUnorderedEqualsIgnoringEmpty(withGlutenExpected, withGluten);

        val withoutGluten = config.getKylinExtJarsPath(false);
        val withoutExpected = "," + celebornJar.getAbsolutePath() + "," + mysqlJar.getAbsolutePath();
        Assertions.assertEquals(withoutExpected, withoutGluten);
    }

    @Test
    public void testGetServerAddress() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        config.setProperty("kylin.server.address", "127.0.0.1");
        Assertions.assertEquals("127.0.0.1", config.getServerAddress());
        config.setProperty("kylin.server.address", "8080");
        Assertions.assertEquals("8080", config.getServerAddress());

        try {
            config.setProperty("kylin.server.address", "8080>");
            config.getServerAddress();
            Assertions.fail();
        } catch (Exception e) {
            Assertions.assertInstanceOf(IllegalArgumentException.class, e);
            Assertions.assertTrue(e.getMessage().contains("Url contains disallowed chars, host: "));
        }
    }
}

class EnvironmentUpdateUtils {

    /**
     * Allows dynamic update to the environment variables. After calling put,
     * System.getenv(key) will then return value.
     *
     * @param key   System environment variable
     * @param value Value to assign to system environment variable
     */
    public static synchronized void put(String key, String value) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.put(key, value);
        if (!Shell.WINDOWS) {
            updateEnvironment(environment);
        } else {
            updateEnvironmentOnWindows(environment);
        }
    }

    /**
     * Allows dynamic update to a collection of environment variables. After
     * calling putAll, System.getenv(key) will then return value for each entry
     * in the map
     *
     * @param additionalEnvironment Collection where the key is the System
     *                              environment variable and the value is the value to assign the system
     *                              environment variable
     */
    public static synchronized void putAll(Map<String, String> additionalEnvironment) {
        Map<String, String> environment = new HashMap<>(System.getenv());
        environment.putAll(additionalEnvironment);
        if (!Shell.WINDOWS) {
            updateEnvironment(environment);
        } else {
            updateEnvironmentOnWindows(environment);
        }
    }

    /**
     * Finds and modifies internal storage for system environment variables using
     * reflection
     *
     * @param environment Collection where the key is the System
     *                    environment variable and the value is the value to assign the system
     *                    environment variable
     */
    @SuppressWarnings("unchecked")
    private static void updateEnvironment(Map<String, String> environment) {
        final Map<String, String> currentEnv = System.getenv();
        copyMapValuesToPrivateField(currentEnv.getClass(), currentEnv, "m", environment);
    }

    /**
     * Finds and modifies internal storage for system environment variables using reflection. This
     * method works only on windows. Note that the actual env is not modified, rather the copy of env
     * which the JVM creates at the beginning of execution is.
     *
     * @param environment Collection where the key is the System
     *                    environment variable and the value is the value to assign the system
     *                    environment variable
     */
    @SuppressWarnings("unchecked")
    private static void updateEnvironmentOnWindows(Map<String, String> environment) {
        try {
            Class<?> processEnvironmentClass = Class.forName("java.lang.ProcessEnvironment");
            copyMapValuesToPrivateField(processEnvironmentClass, null, "theEnvironment", environment);
            copyMapValuesToPrivateField(processEnvironmentClass, null, "theCaseInsensitiveEnvironment", environment);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Failed to update Environment variables", e);
        }
    }

    /**
     * Copies the given map values to the field specified by {@code fieldName}
     *
     * @param klass        The {@code Class} of the object
     * @param object       The object to modify or null if the field is static
     * @param fieldName    The name of the field to set
     * @param newMapValues The values to replace the current map.
     */
    @SuppressWarnings("unchecked")
    private static void copyMapValuesToPrivateField(Class<?> klass, Object object, String fieldName,
            Map<String, String> newMapValues) {
        try {
            Field field = klass.getDeclaredField(fieldName);
            field.setAccessible(true);
            Map<String, String> currentMap = (Map<String, String>) field.get(object);
            currentMap.clear();
            currentMap.putAll(newMapValues);
        } catch (NoSuchFieldException | IllegalStateException | IllegalAccessException e) {
            throw new IllegalStateException("Failed to update Environment variables", e);
        }
    }

    @Test
    @MetadataInfo(onlyProps = true)
    void testGetRoutineOpsTaskTimeOut() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        Assert.assertEquals(4 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "30m");
        Assert.assertEquals(30 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "1d");
        Assert.assertEquals(24 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
        config.setProperty("kylin.metadata.ops-cron-timeout", "4h");
        Assert.assertEquals(4 * 60 * 60 * 1000, config.getRoutineOpsTaskTimeOut());
    }
}
