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

package org.apache.kylin.rest.config.initialize;

import static org.apache.kylin.common.exception.code.ErrorCodeCommon.NON_KE_EXCEPTION;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.net.ssl.SSLContext;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpHeaders;
import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.client.HttpRequestRetryHandler;
import org.apache.http.client.ServiceUnavailableRetryStrategy;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContextBuilder;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.ErrorCode;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.code.ErrorMsg;
import org.apache.kylin.common.exception.code.ErrorSuggestion;
import org.apache.kylin.common.metrics.MetricsCategory;
import org.apache.kylin.common.metrics.MetricsGroup;
import org.apache.kylin.common.metrics.MetricsName;
import org.apache.kylin.common.metrics.MetricsTag;
import org.apache.kylin.common.metrics.prometheus.PrometheusMetrics;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.scheduler.JobFinishedNotifier;
import org.apache.kylin.common.scheduler.JobReadyNotifier;
import org.apache.kylin.common.util.AddressUtil;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.engine.spark.job.NSparkCubingJob;
import org.apache.kylin.guava30.shaded.common.base.Throwables;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.eventbus.Subscribe;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.manager.SegmentAutoMergeUtil;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TimeRange;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.rest.constant.SnapshotStatus;
import org.apache.kylin.rest.response.SegmentPartitionResponse;
import org.apache.kylin.rest.util.SpringContext;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import lombok.SneakyThrows;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class JobSyncListener {
    private static final long RETRY_INTERVAL = 5000L;
    private static final int MAX_RETRY_COUNT = 5;
    private static String URL;

    private static class SimpleHttpRequestRetryHandler implements HttpRequestRetryHandler {

        @Override
        public boolean retryRequest(IOException exception, int retryTimes, HttpContext httpContext) {
            if (exception == null) {
                return false;
            }
            log.info("Trigger SimpleHttpRequestRetryHandler, url: {}, exception: {}, retryTimes: {}", URL,
                    exception.getClass().getName(), retryTimes);
            return retryTimes < MAX_RETRY_COUNT;
        }
    }

    private static class SimpleServiceUnavailableRetryStrategy implements ServiceUnavailableRetryStrategy {

        @Override
        public boolean retryRequest(HttpResponse httpResponse, int executionCount, HttpContext httpContext) {
            int statusCode = httpResponse.getStatusLine().getStatusCode();
            log.info("url: {}, status code: {}, execution count: {}", URL, statusCode, executionCount);
            return executionCount < MAX_RETRY_COUNT && statusCode != HttpStatus.SC_OK;
        }

        @Override
        public long getRetryInterval() {
            return RETRY_INTERVAL;
        }
    }

    // only for test usage
    @Getter
    @Setter
    private boolean jobReadyNotified = false;
    @Getter
    @Setter
    private boolean jobFinishedNotified = false;

    @Subscribe
    public void onJobIsReady(JobReadyNotifier notifier) {
        jobReadyNotified = true;
        //TODO schedule job immediately
        // NDefaultScheduler.getInstance(notifier.getProject()).fetchJobsImmediately();
    }

    @Subscribe
    public void onJobFinished(JobFinishedNotifier notifier) {
        try {
            //TODO schedule job immediately
            // NDefaultScheduler.getInstance(notifier.getProject()).fetchJobsImmediately();
            postJobInfo(extractJobInfo(notifier));
        } finally {
            updateMetrics(notifier);
        }
    }

    @Subscribe
    public void onBuildJobFinished(JobFinishedNotifier notifier) {
        try {
            if (notifier.getJobClass().equals(NSparkCubingJob.class.getName()) && notifier.isSucceed()) {
                SegmentAutoMergeUtil.autoMergeSegments(notifier.getProject(), notifier.getSubject(),
                        notifier.getOwner());
            }
        } catch (Exception e) {
            log.error("Auto merge failed on project {} model {}", notifier.getProject(), notifier.getSubject(), e);
        }
    }

    private static void setLanguage(String lang) {
        MsgPicker.setMsg(lang);
        ErrorCode.setMsg(lang);
        ErrorMsg.setMsg(lang);
        ErrorSuggestion.setMsg(lang);
    }

    private static KylinConfig getOverrideConfig(String project) {
        val originalConfig = KylinConfig.getInstanceFromEnv();
        val projectInstance = NProjectManager.getInstance(originalConfig).getProject(project);
        return projectInstance.getConfig();
    }

    static JobInfo extractJobInfo(JobFinishedNotifier notifier) {
        List<SegRange> segRangeList = Lists.newArrayList();
        List<SegmentPartitionsInfo> segmentPartitionsInfoList = Lists.newArrayList();
        String project = notifier.getProject();
        String subject = notifier.getSubject();
        NTableMetadataManager nTableMetadataManager = NTableMetadataManager
                .getInstance(KylinConfig.getInstanceFromEnv(), project);
        TableDesc tableDesc = nTableMetadataManager.getTableDesc(subject);
        if (tableDesc == null) {
            Set<String> segmentIds = notifier.getSegmentIds();
            NDataflowManager nDataflowManager = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            NDataflow dataflow = nDataflowManager.getDataflow(subject);
            if (dataflow != null && CollectionUtils.isNotEmpty(segmentIds)) {
                val model = dataflow.getModel();
                val partitionDesc = model.getMultiPartitionDesc();
                segmentIds.forEach(id -> {
                    NDataSegment segment = dataflow.getSegment(id);
                    if (segment == null) {
                        return;
                    }
                    TimeRange segRange = segment.getTSRange();
                    segRangeList.add(new SegRange(id, segRange.getStart(), segRange.getEnd()));
                    if (partitionDesc != null && notifier.getSegmentPartitionsMap().get(id) != null
                            && !notifier.getSegmentPartitionsMap().get(id).isEmpty()) {
                        List<SegmentPartitionResponse> segmentPartitionResponseList = segment
                                .getMultiPartitions().stream().filter(segmentPartition -> notifier
                                        .getSegmentPartitionsMap().get(id).contains(segmentPartition.getPartitionId()))
                                .map(partition -> {
                                    val partitionInfo = partitionDesc.getPartitionInfo(partition.getPartitionId());
                                    return new SegmentPartitionResponse(partitionInfo.getId(),
                                            partitionInfo.getValues(), partition.getStatus(),
                                            partition.getLastBuildTime(), partition.getSourceCount(),
                                            partition.getStorageSize());
                                }).collect(Collectors.toList());
                        segmentPartitionsInfoList.add(new SegmentPartitionsInfo(id, segmentPartitionResponseList));
                    }
                });
            }
        }

        String errorCode = null;
        String suggestion = null;
        String msg = null;
        String code = null;
        String stacktrace = null;
        Throwable throwable = notifier.getThrowable();
        if (throwable != null) {
            Throwable rootCause = Throwables.getRootCause(throwable);
            KylinConfig kylinConfig = getOverrideConfig(project);
            setLanguage(kylinConfig.getJobCallbackLanguage());
            if (rootCause instanceof KylinException) {
                msg = rootCause.getLocalizedMessage();
                KylinException kylinException = (KylinException) rootCause;
                code = kylinException.getCode();
                suggestion = kylinException.getSuggestionString();
                errorCode = kylinException.getErrorCodeString();
                stacktrace = Throwables.getStackTraceAsString(rootCause);
            } else {
                errorCode = NON_KE_EXCEPTION.getErrorCode().getCode();
                msg = NON_KE_EXCEPTION.getCodeMsg();
                suggestion = NON_KE_EXCEPTION.getErrorSuggest().getLocalizedString();
                code = KylinException.CODE_UNDEFINED;
                stacktrace = Throwables.getStackTraceAsString(throwable);
            }
        }
        return JobInfo.builder().jobId(notifier.getJobId()).project(notifier.getProject())
                .modelId(tableDesc == null ? notifier.getSubject() : null).segmentIds(notifier.getSegmentIds())
                .indexIds(notifier.getLayoutIds()).duration(notifier.getDuration())
                .state("SUICIDAL".equalsIgnoreCase(notifier.getJobState()) ? "DISCARDED" : notifier.getJobState())
                .jobType(notifier.getJobType()).segRanges(segRangeList)
                .segmentPartitionInfoList(segmentPartitionsInfoList)
                .snapshotJobInfo(getSnapshotJobInfo(tableDesc, notifier)).startTime(notifier.getStartTime())
                .endTime(notifier.getEndTime()).tag(notifier.getTag()).errorCode(errorCode).suggestion(suggestion)
                .msg(msg).code(code).stacktrace(stacktrace).build();

    }

    private static SnapshotJobInfo getSnapshotJobInfo(TableDesc tableDesc, JobFinishedNotifier notifier) {
        SnapshotJobInfo snapshotJobInfo = null;
        if (tableDesc != null && (JobTypeEnum.SNAPSHOT_BUILD.toString().equals(notifier.getJobType())
                || JobTypeEnum.SNAPSHOT_REFRESH.toString().equals(notifier.getJobType()))) {
            snapshotJobInfo = SnapshotJobInfo.builder().table(tableDesc.getName()).database(tableDesc.getDatabase())
                    .totalRows(tableDesc.getSnapshotTotalRows()).storage(tableDesc.getLastSnapshotSize())
                    .status(getSnapshotJobStatus(tableDesc)).lastModifiedTime(tableDesc.getSnapshotLastModified())
                    .selectPartitionCol(tableDesc.getSelectedSnapshotPartitionCol()).build();
        }
        return snapshotJobInfo;
    }

    @Getter
    @Setter
    @Builder
    public static class JobInfo {

        @JsonProperty("job_id")
        private String jobId;

        @JsonProperty("project")
        private String project;

        @JsonProperty("model_id")
        private String modelId;

        @JsonProperty("segment_ids")
        private Set<String> segmentIds;

        @JsonProperty("index_ids")
        private Set<Long> indexIds;

        @JsonProperty("duration")
        private long duration;

        @JsonProperty("job_state")
        private String state;

        @JsonProperty("job_type")
        private String jobType;

        @JsonProperty("segment_time_range")
        private List<SegRange> segRanges;

        @JsonProperty("segment_partition_info")
        private List<SegmentPartitionsInfo> segmentPartitionInfoList;

        @JsonProperty("snapshot_job_info")
        private SnapshotJobInfo snapshotJobInfo;

        @JsonProperty("start_time")
        private long startTime;

        @JsonProperty("end_time")
        private long endTime;

        @JsonProperty("tag")
        private Object tag;

        @JsonProperty("error_code")
        private String errorCode;

        @JsonProperty("suggestion")
        private String suggestion;

        @JsonProperty("msg")
        private String msg;

        @JsonProperty("code")
        private String code;

        @JsonProperty("stacktrace")
        private String stacktrace;
    }

    @Setter
    @Getter
    static class SegRange {
        @JsonProperty("segment_id")
        private String segmentId;

        @JsonProperty("data_range_start")
        private long start;

        @JsonProperty("data_range_end")
        private long end;

        public SegRange(String id, long start, long end) {
            this.segmentId = id;
            this.start = start;
            this.end = end;
        }
    }

    @Setter
    @Getter
    static class SegmentPartitionsInfo {
        @JsonProperty("segment_id")
        private String segmentId;
        @JsonProperty("partition_info")
        private List<SegmentPartitionResponse> partitionInfo;

        public SegmentPartitionsInfo(String segmentId, List<SegmentPartitionResponse> segmentPartitionResponseList) {
            this.segmentId = segmentId;
            this.partitionInfo = segmentPartitionResponseList;
        }
    }

    @Setter
    @Getter
    @Builder
    static class SnapshotJobInfo {
        @JsonProperty("table")
        private String table;
        @JsonProperty("database")
        private String database;
        @JsonProperty("total_rows")
        private long totalRows;
        @JsonProperty("storage")
        private long storage;
        @JsonProperty("last_modified_time")
        private long lastModifiedTime;
        @JsonProperty("status")
        private SnapshotStatus status;
        @JsonProperty("select_partition_col")
        private String selectPartitionCol;
    }

    static void postJobInfo(JobInfo info) {
        URL = KylinConfig.getInstanceFromEnv().getJobFinishedNotifierUrl();
        log.info("post job info parameter, url : {}, state : {}, segmentId : {}, table : {}", URL, info.getState(),
                info.getSegmentIds(),
                info.getSnapshotJobInfo() != null
                        ? info.getSnapshotJobInfo().getDatabase() + "." + info.getSnapshotJobInfo().getTable()
                        : null);
        if (URL == null || "READY".equalsIgnoreCase(info.getState())) {
            return;
        }

        RequestConfig config = RequestConfig.custom().setSocketTimeout(3000).build();
        try (CloseableHttpClient httpClient = HttpClients.custom().setSSLContext(getTrustAllSSLContext())
                .setSSLHostnameVerifier(NoopHostnameVerifier.INSTANCE).setDefaultRequestConfig(config)
                .setRetryHandler(new SimpleHttpRequestRetryHandler())
                .setServiceUnavailableRetryStrategy(new SimpleServiceUnavailableRetryStrategy()).build()) {
            HttpPost httpPost = new HttpPost(URL);
            httpPost.addHeader(HttpHeaders.CONTENT_TYPE, "application/json");
            String username = KylinConfig.getInstanceFromEnv().getJobFinishedNotifierUsername();
            String password = KylinConfig.getInstanceFromEnv().getJobFinishedNotifierPassword();
            if (username != null && password != null) {
                log.info("use basic auth.");
                String basicToken = makeToken(username, password);
                httpPost.addHeader(HttpHeaders.AUTHORIZATION, basicToken);
            }
            httpPost.setEntity(new StringEntity(JsonUtil.writeValueAsString(info), StandardCharsets.UTF_8));
            HttpResponse response = httpClient.execute(httpPost);
            int code = response.getStatusLine().getStatusCode();
            if (code == HttpStatus.SC_OK) {
                log.info("Post job info to " + URL + " successful.");
            } else {
                log.info("Post job info to " + URL + " failed. Status code: " + code);
            }
        } catch (IOException e) {
            log.warn("Error occurred when post job status.", e);
        }
    }

    private static SnapshotStatus getSnapshotJobStatus(TableDesc tableDesc) {
        if (tableDesc.isSnapshotHasBroken()) {
            return SnapshotStatus.BROKEN;
        }
        boolean hasSnapshot = StringUtils.isNotEmpty(tableDesc.getLastSnapshotPath());
        if (hasSnapshot) {
            return SnapshotStatus.ONLINE;
        } else {
            return SnapshotStatus.OFFLINE;
        }
    }

    @SneakyThrows
    public static SSLContext getTrustAllSSLContext() {
        return new SSLContextBuilder().loadTrustMaterial(null, (chain, authType) -> true).build();
    }

    private static String makeToken(String username, String password) {
        String rawTokenString = String.format(Locale.ROOT, "%s:%s", username, password);
        return "Basic " + Base64.getEncoder().encodeToString(rawTokenString.getBytes(Charset.defaultCharset()));
    }

    private void updateMetrics(JobFinishedNotifier notifier) {
        try {
            log.info("Update metrics for {}, duration is {}, waitTime is {}, jobType is {}, state is {}, subject is {}",
                    notifier.getJobId(), notifier.getDuration(), notifier.getWaitTime(), notifier.getJobType(),
                    notifier.getJobState(), notifier.getSubject());
            ExecutableState state = ExecutableState.valueOf(notifier.getJobState());
            String project = notifier.getProject();
            NDataflowManager manager = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            NDataflow dataflow = manager.getDataflow(notifier.getSubject());
            recordPrometheusMetric(notifier, SpringContext.getBean(MeterRegistry.class),
                    dataflow == null ? "" : dataflow.getModelAlias(), state);
            if (state.isFinalState()) {
                long duration = notifier.getDuration();
                MetricsGroup.hostTagCounterInc(MetricsName.JOB_FINISHED, MetricsCategory.PROJECT, project);
                MetricsGroup.hostTagCounterInc(MetricsName.JOB_DURATION, MetricsCategory.PROJECT, project, duration);
                MetricsGroup.hostTagHistogramUpdate(MetricsName.JOB_DURATION_HISTOGRAM, MetricsCategory.PROJECT,
                        project, duration);
                MetricsGroup.hostTagCounterInc(MetricsName.JOB_WAIT_DURATION, MetricsCategory.PROJECT, project,
                        duration);

                if (dataflow != null) {
                    String modelAlias = dataflow.getModelAlias();
                    Map<String, String> tags = Maps.newHashMap();
                    tags.put(MetricsTag.MODEL.getVal(), project.concat("-").concat(modelAlias));
                    MetricsGroup.counterInc(MetricsName.MODEL_BUILD_DURATION, MetricsCategory.PROJECT, project, tags,
                            duration);
                    MetricsGroup.counterInc(MetricsName.MODEL_WAIT_DURATION, MetricsCategory.PROJECT, project, tags,
                            notifier.getWaitTime());
                    MetricsGroup.histogramUpdate(MetricsName.MODEL_BUILD_DURATION_HISTOGRAM, MetricsCategory.PROJECT,
                            project, tags, duration);
                }

                Map<String, String> tags = getJobStatisticsTags(notifier.getJobType());
                if (state == ExecutableState.SUCCEED) {
                    MetricsGroup.counterInc(MetricsName.SUCCESSFUL_JOB_COUNT, MetricsCategory.PROJECT, project, tags);
                } else if (ExecutableState.ERROR == state) {
                    MetricsGroup.hostTagCounterInc(MetricsName.JOB_ERROR, MetricsCategory.PROJECT, project);
                    MetricsGroup.counterInc(MetricsName.ERROR_JOB_COUNT, MetricsCategory.PROJECT, project, tags);
                }

                if (duration <= 5 * 60 * 1000) {
                    MetricsGroup.counterInc(MetricsName.JOB_COUNT_LT_5, MetricsCategory.PROJECT, project, tags);
                } else if (duration <= 10 * 60 * 1000) {
                    MetricsGroup.counterInc(MetricsName.JOB_COUNT_5_10, MetricsCategory.PROJECT, project, tags);
                } else if (duration <= 30 * 60 * 1000) {
                    MetricsGroup.counterInc(MetricsName.JOB_COUNT_10_30, MetricsCategory.PROJECT, project, tags);
                } else if (duration <= 60 * 60 * 1000) {
                    MetricsGroup.counterInc(MetricsName.JOB_COUNT_30_60, MetricsCategory.PROJECT, project, tags);
                } else {
                    MetricsGroup.counterInc(MetricsName.JOB_COUNT_GT_60, MetricsCategory.PROJECT, project, tags);
                }
                MetricsGroup.counterInc(MetricsName.JOB_TOTAL_DURATION, MetricsCategory.PROJECT, project, tags,
                        duration);
            }
        } catch (Exception e) {
            log.error("Fail to update metrics.", e);
        }

    }

    public void recordPrometheusMetric(JobFinishedNotifier notifier, MeterRegistry meterRegistry, String modelAlias,
            ExecutableState state) {
        if (!KylinConfig.getInstanceFromEnv().isPrometheusMetricsEnabled()) {
            return;
        }
        if (state.isFinalState() || ExecutableState.ERROR == state) {
            JobTypeEnum jobTypeEnum = JobTypeEnum.getEnumByName(notifier.getJobType());
            DistributionSummary.builder(PrometheusMetrics.JOB_MINUTES.getValue())
                    .tags(MetricsTag.PROJECT.getVal(), notifier.getProject(), MetricsTag.SUCCEED.getVal(),
                            (ExecutableState.SUCCEED == state) + "", MetricsTag.JOB_CATEGORY.getVal(),
                            Objects.isNull(jobTypeEnum) ? "" : jobTypeEnum.getCategory())
                    .distributionStatisticExpiry(Duration.ofDays(1)).register(meterRegistry)
                    .record((notifier.getDuration() + notifier.getWaitTime()) / (60.0 * 1000.0));
            if (StringUtils.isEmpty(modelAlias)) {
                return;
            }

            boolean containPrometheusJobTypeFlag = JobTypeEnum.getJobTypeByCategory(JobTypeEnum.Category.BUILD).stream()
                    .anyMatch(e -> e.toString().equals(notifier.getJobType()));

            if (containPrometheusJobTypeFlag) {
                DistributionSummary.builder(PrometheusMetrics.MODEL_BUILD_DURATION.getValue())
                        .tags(MetricsTag.MODEL.getVal(), modelAlias, MetricsTag.PROJECT.getVal(), notifier.getProject(),
                                MetricsTag.JOB_TYPE.getVal(), notifier.getJobType(), MetricsTag.SUCCEED.getVal(),
                                String.valueOf(ExecutableState.SUCCEED == state))
                        .distributionStatisticExpiry(Duration.ofDays(1))
                        .sla(KylinConfig.getInstanceFromEnv().getMetricsJobSlaMinutes()).register(meterRegistry)
                        .record((notifier.getDuration() + notifier.getWaitTime()) / (60.0 * 1000.0));
            }
        }
    }

    /**
     * Tags of job statistics used in prometheus
     */
    private Map<String, String> getJobStatisticsTags(String jobType) {
        Map<String, String> tags = Maps.newHashMap();
        tags.put(MetricsTag.HOST.getVal(), AddressUtil.getZkLocalInstance());
        tags.put(MetricsTag.JOB_TYPE.getVal(), jobType);
        return tags;
    }
}
