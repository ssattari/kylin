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

import static org.apache.kylin.engine.spark.utils.ExecutableHandleUtils.mergeMetadataForTable;

import java.util.Set;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.engine.spark.utils.HiveTableRefChecker;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.JobContext;
import org.apache.kylin.job.constant.ExecutableConstants;
import org.apache.kylin.job.exception.ExecuteException;
import org.apache.kylin.job.execution.DefaultExecutableOnTable;
import org.apache.kylin.job.execution.ExecutableHandler;
import org.apache.kylin.job.execution.ExecuteResult;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.execution.MergerInfo;
import org.apache.kylin.job.factory.JobFactory;
import org.apache.kylin.job.factory.JobFactoryConstant;
import org.apache.kylin.job.handler.TableSamplingJobHandler;
import org.apache.kylin.metadata.cube.model.NBatchConstants;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NTableSamplingJob extends DefaultExecutableOnTable {

    static {
        JobFactory.register(JobFactoryConstant.TABLE_SAMPLING_JOB_FACTORY,
                new NTableSamplingJob.TableSamplingJobFactory());
    }

    public NTableSamplingJob() {
        super();
    }

    public NTableSamplingJob(Object notSetId) {
        super(notSetId);
    }

    public static NTableSamplingJob create(TableSamplingJobHandler.TableSamplingJobBuildParam param) {
        NTableMetadataManager tblMgr = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(),
                param.getProject());
        TableDesc tableDesc = tblMgr.getTableDesc(param.getTable());
        return internalCreate(tableDesc, param.getProject(), param.getSubmitter(), param.getRow(), param.getJobId());
    }

    public static NTableSamplingJob internalCreate(TableDesc tableDesc, String project, String submitter, int rows) {
        return internalCreate(tableDesc, project, submitter, rows, RandomUtil.randomUUIDStr());
    }

    public static NTableSamplingJob internalCreate(TableDesc tableDesc, String project, String submitter, int rows,
            String jobId) {
        Preconditions.checkArgument(tableDesc != null, //
                "Create table sampling job failed for table not exist!");

        log.info("start creating a table sampling job on table {}", tableDesc.getIdentity());
        NTableSamplingJob job = new NTableSamplingJob();
        job.setId(jobId);
        job.setName(JobTypeEnum.TABLE_SAMPLING.toString());
        job.setProject(project);
        job.setJobType(JobTypeEnum.TABLE_SAMPLING);
        job.setTargetSubject(tableDesc.getIdentity());

        job.setSubmitter(submitter);
        job.setParam(NBatchConstants.P_PROJECT_NAME, project);
        job.setParam(NBatchConstants.P_JOB_ID, job.getId());
        job.setParam(NBatchConstants.P_TABLE_NAME, tableDesc.getIdentity());
        job.setParam(NBatchConstants.P_SAMPLING_ROWS, String.valueOf(rows));

        KylinConfig globalConfig = KylinConfig.getInstanceFromEnv();
        KylinConfig config = NProjectManager.getInstance(globalConfig).getProject(project).getConfig();
        StepEnum.RESOURCE_DETECT.create(job, config);
        StepEnum.SAMPLING.create(job, config);
        if (HiveTableRefChecker.isNeedCleanUpTransactionalTableJob(tableDesc.isTransactional(),
                tableDesc.isRangePartition(), config.isReadTransactionalTableEnabled())) {
            StepEnum.CLEANUP_TRANSACTIONAL_TABLE.create(job, config);
        }
        log.info("sampling job create success on table {}", tableDesc.getIdentity());
        return job;
    }

    @Override
    public Set<String> getMetadataDumpList(KylinConfig config) {
        final String table = getParam(NBatchConstants.P_TABLE_NAME);
        final TableDesc tableDesc = NTableMetadataManager.getInstance(config, getProject()).getTableDesc(table);
        final ProjectInstance projectInstance = NProjectManager.getInstance(config).getProject(this.getProject());
        Set<String> dumpList = Sets.newHashSet(tableDesc.getResourcePath(), projectInstance.getResourcePath());
        final TableExtDesc tableExtDesc = NTableMetadataManager.getInstance(config, getProject())
                .getTableExtIfExists(tableDesc);
        if (tableExtDesc != null) {
            dumpList.add(tableExtDesc.getResourcePath());
        }
        return dumpList;
    }

    @Override
    public boolean checkSuicide() {
        return null == NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject())
                .getTableDesc(getTableIdentity());
    }

    public NResourceDetectStep getResourceDetectStep() {
        return getTask(NResourceDetectStep.class);
    }

    public SamplingStep getSamplingStep() {
        return getTask(SamplingStep.class);
    }

    public static class SamplingStep extends NSparkExecutable {

        // called by reflection
        public SamplingStep() {
        }

        public SamplingStep(Object notSetId) {
            super(notSetId);
        }

        // Ensure metadata compatibility
        public SamplingStep(String sparkSubmitClassName) {
            this.setSparkSubmitClassName(sparkSubmitClassName);
            this.setName(ExecutableConstants.STEP_NAME_TABLE_SAMPLING);
        }

        private String getTableIdentity() {
            return getParam(NBatchConstants.P_TABLE_NAME);
        }

        @Override
        protected ExecuteResult doWork(JobContext context) throws ExecuteException {
            ExecuteResult result = super.doWork(context);
            if (!result.succeed()) {
                return result;
            }
            if (checkSuicide()) {
                log.info("This Table Sampling job seems meaningless now, quit before mergeRemoteMetaAfterSampling()");
                return null;
            }
            MergerInfo mergerInfo = new MergerInfo(project, ExecutableHandler.HandlerType.SAMPLING);
            mergerInfo.addTaskMergeInfo(this);
            mergeMetadataForTable(project, mergerInfo);
            return result;
        }

        @Override
        protected Set<String> getMetadataDumpList(KylinConfig config) {

            final Set<String> dumpList = Sets.newHashSet();
            // dump project
            ProjectInstance instance = NProjectManager.getInstance(config).getProject(getProject());
            dumpList.add(instance.getResourcePath());

            // dump table & table ext
            final NTableMetadataManager tableMetadataManager = NTableMetadataManager.getInstance(config, getProject());
            final TableExtDesc tableExtDesc = tableMetadataManager
                    .getTableExtIfExists(tableMetadataManager.getTableDesc(getTableIdentity()));
            if (tableExtDesc != null) {
                dumpList.add(tableExtDesc.getResourcePath());
            }
            final TableDesc table = tableMetadataManager.getTableDesc(getTableIdentity());
            if (table != null) {
                dumpList.add(table.getResourcePath());
            }
            dumpList.addAll(getLogicalViewMetaDumpList(config));
            return dumpList;
        }
    }

    public static class TableSamplingJobFactory extends JobFactory {

        protected TableSamplingJobFactory() {
        }

        @Override
        protected NTableSamplingJob create(JobBuildParams jobBuildParams) {
            return NTableSamplingJob.create((TableSamplingJobHandler.TableSamplingJobBuildParam) jobBuildParams);
        }
    }
}
