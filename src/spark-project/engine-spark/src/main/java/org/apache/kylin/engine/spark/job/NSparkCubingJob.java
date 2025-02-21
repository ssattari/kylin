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

package org.apache.kylin.engine.spark.job;

import static java.util.stream.Collectors.joining;
import static org.apache.kylin.engine.spark.stats.utils.HiveTableRefChecker.isNeedCleanUpTransactionalTableJob;
import static org.apache.kylin.job.factory.JobFactoryConstant.CUBE_JOB_FACTORY;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.KylinConfigExt;
import org.apache.kylin.common.exception.JobErrorCode;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.guava30.shaded.common.annotations.VisibleForTesting;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.DefaultExecutableOnModel;
import org.apache.kylin.job.execution.ExecutableParams;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.execution.step.JobStepType;
import org.apache.kylin.job.factory.JobFactory;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.job.handler.AddIndexHandler;
import org.apache.kylin.metadata.cube.model.LayoutEntity;
import org.apache.kylin.metadata.cube.model.NBatchConstants;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NDataflowUpdate;
import org.apache.kylin.metadata.job.JobBucket;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.rest.feign.MetadataInvoker;
import org.apache.kylin.rest.request.DataFlowUpdateRequest;
import org.apache.kylin.rest.service.ModelMetadataBaseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.val;

public class NSparkCubingJob extends DefaultExecutableOnModel {

    @SuppressWarnings("unused")
    private static final Logger logger = LoggerFactory.getLogger(NSparkCubingJob.class);

    static {
        JobFactory.register(CUBE_JOB_FACTORY, new NSparkCubingJob.CubingJobFactory());
    }

    static class CubingJobFactory extends JobFactory {

        private CubingJobFactory() {
        }

        @Override
        protected NSparkCubingJob create(JobBuildParams jobBuildParams) {
            return NSparkCubingJob.create(jobBuildParams);
        }
    }

    public NSparkCubingJob() {
        super();
    }

    public NSparkCubingJob(Object notSetId) {
        super(notSetId);
    }

    @VisibleForTesting
    public static NSparkCubingJob create(Set<NDataSegment> segments, Set<LayoutEntity> layouts, String submitter,
            Set<JobBucket> buckets) {
        return create(segments, layouts, submitter, JobTypeEnum.INDEX_BUILD, RandomUtil.randomUUIDStr(), null, null,
                buckets);
    }

    @VisibleForTesting
    public static NSparkCubingJob createIncBuildJob(Set<NDataSegment> segments, Set<LayoutEntity> layouts,
            String submitter, Set<JobBucket> buckets) {
        return create(segments, layouts, submitter, JobTypeEnum.INC_BUILD, RandomUtil.randomUUIDStr(), null, null,
                buckets);
    }

    @VisibleForTesting
    public static NSparkCubingJob create(Set<NDataSegment> segments, Set<LayoutEntity> layouts, String submitter,
            JobTypeEnum jobType, String jobId, Set<String> ignoredSnapshotTables, Set<Long> partitions,
            Set<JobBucket> buckets) {
        val params = new JobFactory.JobBuildParams(segments, layouts, submitter, jobType, jobId, null,
                ignoredSnapshotTables, partitions, buckets, Maps.newHashMap());
        return innerCreate(params);
    }

    //used for JobFactory
    public static NSparkCubingJob create(JobFactory.JobBuildParams jobBuildParams) {

        NSparkCubingJob sparkCubingJob = innerCreate(jobBuildParams);

        if (jobBuildParams instanceof AddIndexHandler.AddIndexJobBuildParams) {
            boolean layoutsDeletableAfterBuild = ((AddIndexHandler.AddIndexJobBuildParams) jobBuildParams)
                    .isLayoutsDeletableAfterBuild();
            sparkCubingJob.setParam(NBatchConstants.P_LAYOUTS_DELETABLE_AFTER_BUILD,
                    String.valueOf(layoutsDeletableAfterBuild));
        }
        if (CollectionUtils.isNotEmpty(jobBuildParams.getToBeDeletedLayouts())) {
            sparkCubingJob.setParam(NBatchConstants.P_TO_BE_DELETED_LAYOUT_IDS,
                    NSparkCubingUtil.ids2Str(NSparkCubingUtil.toLayoutIds(jobBuildParams.getToBeDeletedLayouts())));
        }

        return sparkCubingJob;
    }

    private static NSparkCubingJob innerCreate(JobFactory.JobBuildParams params) {
        Set<NDataSegment> segments = params.getSegments();
        Set<LayoutEntity> layouts = params.getLayouts();
        String submitter = params.getSubmitter();
        JobTypeEnum jobType = params.getJobType();
        String jobId = params.getJobId();
        Set<String> ignoredSnapshotTables = params.getIgnoredSnapshotTables();
        Set<Long> partitions = params.getPartitions();
        Set<JobBucket> buckets = params.getBuckets();
        Map<String, String> extParams = params.getExtParams();
        Preconditions.checkArgument(!segments.isEmpty());
        Preconditions.checkArgument(submitter != null);
        KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        if (!kylinConfig.isUTEnv()) {
            Preconditions.checkArgument(!layouts.isEmpty());
        }
        NDataflow df = segments.iterator().next().getDataflow();
        NSparkCubingJob job = new NSparkCubingJob();

        long startTime = Long.MAX_VALUE - 1;
        long endTime = 0L;
        for (NDataSegment segment : segments) {
            startTime = Math.min(startTime, Long.parseLong(segment.getSegRange().getStart().toString()));
            endTime = endTime > Long.parseLong(segment.getSegRange().getStart().toString()) ? endTime
                    : Long.parseLong(segment.getSegRange().getEnd().toString());
        }
        job.setParams(extParams);
        job.setId(jobId);
        job.setName(jobType.toString());
        job.setJobType(jobType);
        job.setTargetSubject(segments.iterator().next().getModel().getUuid());
        job.setTargetSegments(segments.stream().map(x -> String.valueOf(x.getId())).collect(Collectors.toList()));
        job.setProject(df.getProject());
        job.setSubmitter(submitter);
        if (CollectionUtils.isNotEmpty(partitions)) {
            job.setTargetPartitions(partitions);
            job.setParam(NBatchConstants.P_PARTITION_IDS,
                    job.getTargetPartitions().stream().map(String::valueOf).collect(joining(",")));
            checkIfNeedBuildSnapshots(job);
        }
        if (CollectionUtils.isNotEmpty(buckets)) {
            job.setParam(NBatchConstants.P_BUCKETS, ExecutableParams.toBucketParam(buckets));
        }

        enableCostBasedPlannerIfNeed(df, segments, job);

        job.setParam(NBatchConstants.P_JOB_ID, jobId);
        job.setParam(NBatchConstants.P_PROJECT_NAME, df.getProject());
        job.setParam(NBatchConstants.P_TARGET_MODEL, job.getTargetSubject());
        job.setParam(NBatchConstants.P_DATAFLOW_ID, df.getId());
        job.setParam(NBatchConstants.P_LAYOUT_IDS, NSparkCubingUtil.ids2Str(NSparkCubingUtil.toLayoutIds(layouts)));
        job.setParam(NBatchConstants.P_SEGMENT_IDS, String.join(",", job.getTargetSegments()));
        job.setParam(NBatchConstants.P_DATA_RANGE_START, String.valueOf(startTime));
        job.setParam(NBatchConstants.P_DATA_RANGE_END, String.valueOf(endTime));
        if (CollectionUtils.isNotEmpty(ignoredSnapshotTables)) {
            job.setParam(NBatchConstants.P_IGNORED_SNAPSHOT_TABLES, String.join(",", ignoredSnapshotTables));
        }
        KylinConfigExt config = df.getConfig();

        JobStepType.RESOURCE_DETECT.createStep(job, config);
        JobStepType.CUBING.createStep(job, config);
        JobStepType.UPDATE_METADATA.createStep(job, config);
        initCleanUpTransactionalTable(kylinConfig, df, job, config);

        if (config.isIndexPreloadCacheEnabled()) {
            JobStepType.LOAD_GLUTEN_CACHE.createStep(job, config);
        }
        return job;
    }

    private static AbstractExecutable initCleanUpTransactionalTable(KylinConfig kylinConfig, NDataflow df,
            NSparkCubingJob job, KylinConfigExt config) {
        AbstractExecutable cleanUpTransactionalTable = null;
        Boolean isRangePartitionTable = df.getModel().getAllTableRefs().stream()
                .anyMatch(tableRef -> tableRef.getTableDesc().isRangePartition());
        Boolean isTransactionalTable = df.getModel().getAllTableRefs().stream()
                .anyMatch(tableRef -> tableRef.getTableDesc().isTransactional());

        if (isNeedCleanUpTransactionalTableJob(isTransactionalTable, isRangePartitionTable,
                kylinConfig.isReadTransactionalTableEnabled())) {
            cleanUpTransactionalTable = JobStepType.CLEAN_UP_TRANSACTIONAL_TABLE.createStep(job, config);
        }
        return cleanUpTransactionalTable;
    }

    public static void checkIfNeedBuildSnapshots(NSparkCubingJob job) {
        switch (job.getJobType()) {
        case INC_BUILD:
        case INDEX_REFRESH:
        case INDEX_BUILD:
            job.setParam(NBatchConstants.P_NEED_BUILD_SNAPSHOTS, "true");
            break;
        default:
            job.setParam(NBatchConstants.P_NEED_BUILD_SNAPSHOTS, "false");
            break;
        }
    }

    @Override
    public Set<String> getMetadataDumpList(KylinConfig config) {
        final String dataflowId = getParam(NBatchConstants.P_DATAFLOW_ID);
        return NDataflowManager.getInstance(config, getProject()) //
                .getDataflow(dataflowId) //
                .collectPrecalculationResource();
    }

    public NSparkCubingStep getSparkCubingStep() {
        return getTask(NSparkCubingStep.class);
    }

    public NResourceDetectStep getResourceDetectStep() {
        return getTask(NResourceDetectStep.class);
    }

    public SparkCleanupTransactionalTableStep getCleanIntermediateTableStep() {
        return getTask(SparkCleanupTransactionalTableStep.class);
    }

    @Override
    public void cancelJob() {
        NDataflowManager nDataflowManager = NDataflowManager.getInstance(getConfig(), getProject());
        NDataflow dataflow = nDataflowManager.getDataflow(getSparkCubingStep().getDataflowId());
        if (dataflow == null) {
            logger.debug("Dataflow is null, maybe model is deleted?");
            return;
        }
        List<NDataSegment> toRemovedSegments = new ArrayList<>();
        for (String id : getSparkCubingStep().getSegmentIds()) {
            NDataSegment segment = dataflow.getSegment(id);
            if (segment != null && SegmentStatusEnum.READY != segment.getStatus()
                    && SegmentStatusEnum.WARNING != segment.getStatus()) {
                toRemovedSegments.add(segment);
            }
        }
        if (toRemovedSegments.isEmpty()) {
            logger.warn("Segment related to job {} can not be found, maybe job has been canceled.", getJobId());
            return;
        }
        NDataSegment[] nDataSegments = toRemovedSegments.toArray(new NDataSegment[0]);
        NDataflowUpdate nDataflowUpdate = new NDataflowUpdate(dataflow.getUuid());
        nDataflowUpdate.setToRemoveSegs(nDataSegments);
        // create 'dataFlowUpdateRequest', then do RPC
        DataFlowUpdateRequest dataFlowUpdateRequest = new DataFlowUpdateRequest();
        dataFlowUpdateRequest.setProject(project);
        dataFlowUpdateRequest.setDataflowUpdate(nDataflowUpdate);
        // init update request for sub partition job
        initSubPartitionJobUpdateRequest(dataFlowUpdateRequest);
        updateDataflow(dataFlowUpdateRequest);
    }

    private void updateDataflow(DataFlowUpdateRequest dataFlowUpdateRequest) {
        if (UnitOfWork.isAlreadyInTransaction()) {
            new ModelMetadataBaseService().updateDataflow(dataFlowUpdateRequest);
            return;
        }
        MetadataInvoker.getInstance().updateDataflow(dataFlowUpdateRequest);
    }

    public void initSubPartitionJobUpdateRequest(DataFlowUpdateRequest dataFlowUpdateRequest) {
        if (!isBucketJob()) {
            return;
        }
        NDataflowManager dfManager = NDataflowManager.getInstance(getConfig(), getProject());
        NDataflow df = dfManager.getDataflow(getSparkCubingStep().getDataflowId());
        Set<String> segmentIds = getSparkCubingStep().getSegmentIds();
        Set<Long> partitions = getSparkCubingStep().getTargetPartitions();
        Set<String> existsSegments = Sets.newHashSet();
        for (String id : segmentIds) {
            NDataSegment segment = df.getSegment(id);
            if (segment == null) {
                continue;
            }
            existsSegments.add(segment.getId());
        }
        switch (getJobType()) {
        case SUB_PARTITION_BUILD:
            dataFlowUpdateRequest.setToRemoveSegmentPartitions(new Pair<>(existsSegments, partitions));
            break;
        case SUB_PARTITION_REFRESH:
            dataFlowUpdateRequest.setResetToReadyPartitions(new Pair<>(existsSegments, partitions));
            break;
        default:
            break;
        }
    }

    @Override
    public boolean safetyIfDiscard() {
        if (checkSuicide() || this.getStatusInMem().isFinalState() || this.getJobType() != JobTypeEnum.INC_BUILD) {
            return true;
        }

        val dataflow = NDataflowManager.getInstance(getConfig(), getProject())
                .getDataflow(getSparkCubingStep().getDataflowId());
        val segs = dataflow.getSegments().stream()
                .filter(nDataSegment -> !getTargetSegments().contains(nDataSegment.getId()))
                .collect(Collectors.toList());
        val toDeletedSeg = dataflow.getSegments().stream()
                .filter(nDataSegment -> getTargetSegments().contains(nDataSegment.getId()))
                .collect(Collectors.toList());
        val segHoles = NDataflowManager.getInstance(getConfig(), getProject())
                .calculateHoles(getSparkCubingStep().getDataflowId(), segs);

        for (NDataSegment segHole : segHoles) {
            for (NDataSegment deleteSeg : toDeletedSeg) {
                if (segHole.getSegRange().overlaps(deleteSeg.getSegRange())
                        || segHole.getSegRange().contains(deleteSeg.getSegRange())) {
                    return false;
                }

            }
        }

        return true;
    }

    @AllArgsConstructor
    @Getter
    static class NSparkCubingJobStep {
        private final AbstractExecutable resourceDetect;
        private final AbstractExecutable cubing;
        private final AbstractExecutable updateMetadata;
        private final AbstractExecutable cleanUpTransactionalTable;
    }

    private static void enableCostBasedPlannerIfNeed(NDataflow df, Set<NDataSegment> segments, NSparkCubingJob job) {
        // need run the cost based planner:
        // 1. config enable the cube planner
        // 2. the model dose not have the `layout_cost_based_pruned_list`
        // 3. rule index has agg group
        // 4. just only one segment to be built/refresh(other case will throw exception)
        IndexPlan indexPlan = df.getIndexPlan();
        KylinConfig kylinConfig = indexPlan.getConfig();
        boolean needCostRecommendIndex = indexPlan.getRuleBasedIndex() != null
                && indexPlan.getRuleBasedIndex().getLayoutsOfCostBasedList() == null
                && !indexPlan.getRuleBasedIndex().getAggregationGroups().isEmpty();
        if (kylinConfig.enableCostBasedIndexPlanner() && needCostRecommendIndex
                && canEnablePlannerJob(job.getJobType())) {
            // must run the cost based planner
            if (segments.size() == 1) {
                if (noBuildingSegmentExist(df.getProject(), job.getTargetSubject(), kylinConfig)) {
                    // check the count of rowkey:
                    // if the count of row key exceed the 63, throw exception
                    if (indexPlan.getRuleBasedIndex().countOfIncludeDimension() > (Long.SIZE - 1)) {
                        throw new KylinException(JobErrorCode.COST_BASED_PLANNER_ERROR,
                                String.format(Locale.ROOT,
                                        "The count of row key %d can't be larger than 63, when use the cube planner",
                                        indexPlan.getRuleBasedIndex().countOfIncludeDimension()));
                    }
                    // Add the parameter `P_JOB_ENABLE_PLANNER` which is used to decide whether to use the  cube planner
                    job.setParam(NBatchConstants.P_JOB_ENABLE_PLANNER, Boolean.TRUE.toString());
                } else {
                    throw new KylinException(JobErrorCode.COST_BASED_PLANNER_ERROR,
                            "There are running job for this model when submit the build job with cost based planner, "
                                    + "please wait for other jobs to finish or cancel them");
                }
            } else {
                throw new KylinException(JobErrorCode.COST_BASED_PLANNER_ERROR,
                        "The number of segments to be built or refreshed must be 1, "
                                + "This is the first time to submit build job with enable cost based planner");
            }
        }
    }

    private static boolean noBuildingSegmentExist(String project, String modelId, KylinConfig kylinConfig) {
        NDataflowManager nDataflowManager = NDataflowManager.getInstance(kylinConfig, project);
        NDataflow dataflow = nDataflowManager.getDataflow(modelId);
        // There are no other tasks in building
        return dataflow.getSegments(SegmentStatusEnum.NEW).size() <= 1;
    }

    private static boolean canEnablePlannerJob(JobTypeEnum jobType) {
        // just support: INC_BUILD and INDEX_REFRESH to recommend/prune index
        return JobTypeEnum.INC_BUILD == jobType || JobTypeEnum.INDEX_REFRESH == jobType;
    }
}
