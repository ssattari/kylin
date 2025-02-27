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

package org.apache.kylin.job.manager;

import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_CREATE_ABANDON;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_STORAGE_QUOTA_LIMIT;
import static org.apache.kylin.guava30.shaded.common.base.Preconditions.checkNotNull;

import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.constant.LogConstant;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.logging.SetLogCategory;
import org.apache.kylin.job.JobContext;
import org.apache.kylin.job.common.ExecutableUtil;
import org.apache.kylin.job.common.SegmentUtil;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.handler.AbstractJobHandler;
import org.apache.kylin.job.handler.AddIndexHandler;
import org.apache.kylin.job.handler.AddSegmentHandler;
import org.apache.kylin.job.handler.IndexPlanOptimizeJobHandler;
import org.apache.kylin.job.handler.InternalTableJobHandler;
import org.apache.kylin.job.handler.LayoutDataOptimizeJobHandler;
import org.apache.kylin.job.handler.MergeSegmentHandler;
import org.apache.kylin.job.handler.RefreshSegmentHandler;
import org.apache.kylin.job.handler.SnapshotJobHandler;
import org.apache.kylin.job.handler.TableSamplingJobHandler;
import org.apache.kylin.job.model.JobParam;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModelManager;

import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobManager {

    private KylinConfig config;

    private String project;

    public JobManager(KylinConfig config, String project) {
        this.config = config;
        this.project = project;
    }

    // called by reflection
    @SuppressWarnings("unused")
    static JobManager newInstance(KylinConfig conf, String project) {
        checkNotNull(project);
        return new JobManager(conf, project);
    }

    public static JobManager getInstance(KylinConfig config, String project) {
        return config.getManager(project, JobManager.class);
    }

    public String addJob(JobParam jobParam, AbstractJobHandler handler) {
        try (SetLogCategory ignored = new SetLogCategory(LogConstant.BUILD_CATEGORY)) {
            if (!config.isJobNode() && !config.isUTEnv() && !config.isMetadataNode()) {
                throw new KylinException(JOB_CREATE_ABANDON);
            }
            checkNotNull(project);
            checkStorageQuota(project);
            jobParam.setProject(project);
            ExecutableUtil.computeParams(jobParam);

            AbstractJobHandler localHandler = handler != null ? handler : createJobHandler(jobParam);
            if (localHandler == null)
                return null;

            localHandler.handle(jobParam);
            return jobParam.getJobId();
        }
    }

    public String addJob(JobParam jobParam) {
        return addJob(jobParam, null);
    }

    public String refreshSegmentJob(JobParam jobParam) {
        return refreshSegmentJob(jobParam, false);
    }

    public String refreshSegmentJob(JobParam jobParam, boolean refreshAllLayouts) {
        jobParam.getCondition().put(JobParam.ConditionConstant.REFRESH_ALL_LAYOUTS, refreshAllLayouts);
        NDataModel model = NDataModelManager.getInstance(config, project).getDataModelDesc(jobParam.getModel());
        if (model.isMultiPartitionModel() && CollectionUtils.isNotEmpty(jobParam.getTargetPartitions())) {
            jobParam.setJobTypeEnum(JobTypeEnum.SUB_PARTITION_REFRESH);
        } else {
            jobParam.setJobTypeEnum(JobTypeEnum.INDEX_REFRESH);
        }
        return addJob(jobParam);
    }

    public String mergeSegmentJob(JobParam jobParam) {
        jobParam.setJobTypeEnum(JobTypeEnum.INDEX_MERGE);
        return addJob(jobParam);
    }

    public String addRelatedIndexJob(JobParam jobParam) {
        boolean noNeed = (jobParam.getTargetSegments() == null
                && SegmentUtil.getValidSegments(jobParam.getModel(), project).isEmpty())
                || (jobParam.getTargetSegments() != null && jobParam.getTargetSegments().isEmpty());
        if (noNeed) {
            log.debug("No need to add index build job due to there is no valid segment in {}.", jobParam.getModel());
            return null;
        }
        jobParam.setJobTypeEnum(JobTypeEnum.INDEX_BUILD);
        return addJob(jobParam);
    }

    public String addIndexJob(JobParam jobParam) {
        val relatedSegments = SegmentUtil.getValidSegments(jobParam.getModel(), project).stream()
                .map(NDataSegment::getId).collect(Collectors.toSet());
        jobParam.withTargetSegments(relatedSegments);
        return addRelatedIndexJob(jobParam);
    }

    public String addSegmentJob(JobParam jobParam) {
        jobParam.setJobTypeEnum(JobTypeEnum.INC_BUILD);
        return addJob(jobParam);
    }

    public String buildPartitionJob(JobParam jobParam) {
        jobParam.setJobTypeEnum(JobTypeEnum.SUB_PARTITION_BUILD);
        return addJob(jobParam);
    }

    private AbstractJobHandler createJobHandler(JobParam jobParam) {
        AbstractJobHandler handler;
        switch (jobParam.getJobTypeEnum()) {
        case INC_BUILD:
            handler = new AddSegmentHandler();
            break;
        case INDEX_MERGE:
            handler = new MergeSegmentHandler();
            break;
        case INDEX_BUILD:
        case SUB_PARTITION_BUILD:
            handler = new AddIndexHandler();
            break;
        case INDEX_REFRESH:
        case SUB_PARTITION_REFRESH:
            handler = new RefreshSegmentHandler();
            break;
        case TABLE_SAMPLING:
            handler = new TableSamplingJobHandler();
            break;
        case SNAPSHOT_BUILD:
        case SNAPSHOT_REFRESH:
            handler = new SnapshotJobHandler();
            break;
        case INDEX_PLAN_OPT:
            handler = new IndexPlanOptimizeJobHandler();
            break;
        case LAYOUT_DATA_OPTIMIZE:
            handler = new LayoutDataOptimizeJobHandler();
            break;
        case INTERNAL_TABLE_REFRESH:
        case INTERNAL_TABLE_BUILD:
        case INTERNAL_TABLE_DELETE_PARTITION:
            handler = new InternalTableJobHandler();
            break;
        default:
            log.error("jobParam doesn't have matched job: {}", jobParam.getJobTypeEnum());
            return null;
        }
        return handler;
    }

    public static void checkStorageQuota(String project) {
        JobContext jobContext = JobContextUtil.getJobContext(KylinConfig.getInstanceFromEnv());
        if (null != jobContext && jobContext.isProjectReachQuotaLimit(project)) {
            log.error("Add job failed due to no available storage quota in project {}", project);
            throw new KylinException(JOB_STORAGE_QUOTA_LIMIT);
        }
    }
}
