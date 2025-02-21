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

import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_CREATE_CHECK_FAIL;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_CREATE_CHECK_INDEX_FAIL;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_CREATE_CHECK_SEGMENT_FAIL;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_CREATE_EXCEPTION;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.JOB_REFRESH_CHECK_INDEX_FAIL;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.NLocalFileMetadataTestCase;
import org.apache.kylin.engine.spark.job.ExecutableAddCuboidHandler;
import org.apache.kylin.engine.spark.job.ExecutableAddSegmentHandler;
import org.apache.kylin.engine.spark.job.ExecutableMergeOrRefreshHandler;
import org.apache.kylin.engine.spark.job.NSparkCubingJob;
import org.apache.kylin.engine.spark.job.NSparkMergingJob;
import org.apache.kylin.engine.spark.utils.SparkJobFactoryUtils;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.manager.JobManager;
import org.apache.kylin.job.model.JobParam;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.cube.model.NBatchConstants;
import org.apache.kylin.metadata.cube.model.NDataLayout;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NDataflowUpdate;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.model.SegmentRange;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.project.EnhancedUnitOfWork;
import org.apache.kylin.rest.response.NDataSegmentResponse;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;

import lombok.val;
import lombok.var;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobSchedulerTest extends NLocalFileMetadataTestCase {

    public static final String DEFAULT_PROJECT = "default";
    public static final String MODEL_ID = "741ca86a-1f13-46da-a59f-95fb68615e3a";

    // NDefaultScheduler scheduler;
    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Before
    public void setup() {
        SparkJobFactoryUtils.initJobFactory();
        createTestMetadata();
        prepareSegment();
    }

    @After
    public void after() {
        JobContextUtil.cleanUp();
        cleanupTestMetadata();
    }

    @Test
    public void testAddIndex_chooseIndexAndSegment() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);

        val oldSegs = new NDataSegment(df.getSegments().get(0));
        val update = new NDataflowUpdate(df.getUuid());
        val oldLayouts = new ArrayList<>(df.getSegments().get(0).getLayoutsMap().values());
        update.setToUpdateSegs(oldSegs);
        update.setToRemoveLayouts(oldLayouts.get(0), oldLayouts.get(1));
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(
                () -> NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT).updateDataflow(update),
                DEFAULT_PROJECT);

        // select some segments and indexes
        HashSet<String> relatedSegments = Sets.newHashSet();
        relatedSegments.add(oldSegs.getId());
        val targetLayouts = new HashSet<Long>();
        targetLayouts.add(1L);
        targetLayouts.add(10001L);
        val jobId = jobManager.addRelatedIndexJob(new JobParam(relatedSegments, targetLayouts, MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertNotNull(jobId);
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);
        Assert.assertEquals(1, getProcessLayout(executables.get(0)));

        // auto select valid segment
        val jobId2 = jobManager.addIndexJob(new JobParam(MODEL_ID, "ADMIN"));
        Assert.assertNull(jobId2);
    }

    @Test
    public void testAddIndex_selectNoIndex() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);

            val update = new NDataflowUpdate(df.getUuid());
            val seg = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
            seg.setStatus(SegmentStatusEnum.READY);
            update.setToUpdateSegs(seg);
            update.setToAddOrUpdateLayouts(NDataLayout.newDataLayout(df, seg.getId(), 1L),
                    NDataLayout.newDataLayout(df, seg.getId(), 10001L),
                    NDataLayout.newDataLayout(df, seg.getId(), 10002L));
            return dfm.updateDataflow(update);
        }, DEFAULT_PROJECT);

        jobManager.addIndexJob(new JobParam(MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);
        Assert.assertEquals(16, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testAddIndex_ExcludeLockedIndex() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);

            val update = new NDataflowUpdate(df.getUuid());
            val seg = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
            seg.setStatus(SegmentStatusEnum.READY);
            update.setToUpdateSegs(seg);
            update.setToAddOrUpdateLayouts(NDataLayout.newDataLayout(df, seg.getId(), 1L),
                    NDataLayout.newDataLayout(df, seg.getId(), 10001L),
                    NDataLayout.newDataLayout(df, seg.getId(), 10002L));
            dfm.updateDataflow(update);
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            return indexManager.updateIndexPlan(MODEL_ID, copyForWrite -> {
                copyForWrite.markWhiteIndexToBeDelete(MODEL_ID, Sets.newHashSet(20000000001L));
            });
        }, DEFAULT_PROJECT);

        jobManager.addIndexJob(new JobParam(MODEL_ID, "ADMIN"));
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);
        Assert.assertEquals(15, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testAddIndex_timeException() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);

        val oldSegs = new NDataSegment(df.getSegments().get(0));
        val update = new NDataflowUpdate(df.getUuid());
        val oldLayouts = new ArrayList<>(df.getSegments().get(0).getLayoutsMap().values());
        update.setToUpdateSegs(oldSegs);
        update.setToRemoveLayouts(oldLayouts.get(0), oldLayouts.get(1));
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(
                () -> NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT).updateDataflow(update),
                DEFAULT_PROJECT);

        HashSet<String> relatedSegments = Sets.newHashSet();
        df.getSegments().forEach(seg -> relatedSegments.add(seg.getId()));
        val targetLayouts = new HashSet<Long>();
        targetLayouts.add(1L);
        targetLayouts.add(10001L);
        jobManager.addRelatedIndexJob(new JobParam(relatedSegments, targetLayouts, MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);
        Assert.assertEquals(1, getProcessLayout(executables.get(0)));

        relatedSegments.remove(0);
        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_FAIL.getMsg());
        jobManager.addRelatedIndexJob(new JobParam(relatedSegments, targetLayouts, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testRefreshSegmentExcludeLockedIndex() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);
        UnitOfWork.doInTransactionWithRetry(() -> {
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            return indexManager.updateIndexPlan(MODEL_ID, copyForWrite -> {
                copyForWrite.markWhiteIndexToBeDelete(MODEL_ID, Sets.newHashSet(20000000001L));
            });
        }, DEFAULT_PROJECT);
        jobManager.refreshSegmentJob(new JobParam(df.getSegments().get(0), MODEL_ID, "ADMIN"));
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        val segmentResponse = new NDataSegmentResponse(df, df.getSegments(), df.getSegments().get(0), executables);
        Assert.assertEquals(1, segmentResponse.getLockedIndexCount());
        Assert.assertEquals(18, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testRefreshSegmentOnlyLockedIndex() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);
        UnitOfWork.doInTransactionWithRetry(() -> {
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            return indexManager.updateIndexPlan(MODEL_ID, copyForWrite -> {
                Set<Long> layouts = copyForWrite.getAllLayoutIds(false);
                layouts.remove(20000000001L);
                copyForWrite.removeLayouts(layouts, true, true);
                copyForWrite.markWhiteIndexToBeDelete(MODEL_ID, Sets.newHashSet(20000000001L));
            });
        }, MODEL_ID);
        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_REFRESH_CHECK_INDEX_FAIL.getMsg());
        jobManager.refreshSegmentJob(new JobParam(df.getSegments().get(0), MODEL_ID, "ADMIN"));
    }

    @Test
    public void testRefreshJob() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);


        NDataSegment seg = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-02-01"), SegmentRange.dateToLong("" + "2012-03-01")));
        }, DEFAULT_PROJECT);
        jobManager.refreshSegmentJob(new JobParam(seg, MODEL_ID, "ADMIN"));

        NDataSegment seg2 = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-01-01"), SegmentRange.dateToLong("" + "2012-02-01")));
        }, DEFAULT_PROJECT);
        jobManager.refreshSegmentJob(new JobParam(seg2, MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(2, executables.size());
        Assert.assertTrue(
                ((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableMergeOrRefreshHandler);
        Assert.assertTrue(
                ((NSparkCubingJob) executables.get(1)).getHandler() instanceof ExecutableMergeOrRefreshHandler);
        Assert.assertEquals(19, getProcessLayout(executables.get(0)));
        Assert.assertEquals(19, getProcessLayout(executables.get(1)));

        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_FAIL.getMsg());
        jobManager.refreshSegmentJob(new JobParam(seg, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testRefreshJob_timeException() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        NDataSegment seg = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-02-01"), SegmentRange.dateToLong("" + "2012-03-01")));
        }, DEFAULT_PROJECT);
        jobManager.refreshSegmentJob(new JobParam(seg, MODEL_ID, "ADMIN"));
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(1, executables.size());

        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_FAIL.getMsg());
        jobManager.refreshSegmentJob(new JobParam(seg, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testRefreshJob_emptyIndex() {
        NDataSegment seg = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfManager = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            val df = dfManager.getDataflow(MODEL_ID);
            val update = new NDataflowUpdate(df.getUuid());
            val newSeg = dfManager.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
            newSeg.setStatus(SegmentStatusEnum.READY);
            update.setToUpdateSegs(newSeg);
            dfManager.updateDataflow(update);
            return newSeg;
        }, DEFAULT_PROJECT);
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.refreshSegmentJob(new JobParam(seg, MODEL_ID, "ADMIN"), true);
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(1, executables.size());
        Assert.assertEquals(19, executables.get(0).getParam(NBatchConstants.P_LAYOUT_IDS).split(",").length);
    }

    @Test
    public void testMergeJob() {
        List<NDataSegment> newSegments = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            val seg1 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-01-01"), SegmentRange.dateToLong("" + "2012-03-01")));
            val seg2 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-03-01"), SegmentRange.dateToLong("" + "2012-05-01")));
            return Arrays.asList(seg1, seg2);
        }, DEFAULT_PROJECT);

        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.mergeSegmentJob(new JobParam(newSegments.get(0), MODEL_ID, "ADMIN"));
        jobManager.mergeSegmentJob(new JobParam(newSegments.get(1), MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(2, executables.size());
        Assert.assertTrue(
                ((NSparkMergingJob) executables.get(0)).getHandler() instanceof ExecutableMergeOrRefreshHandler);
        Assert.assertEquals(19, getProcessLayout(executables.get(0)));
        Assert.assertTrue(
                ((NSparkMergingJob) executables.get(0)).getHandler() instanceof ExecutableMergeOrRefreshHandler);
        Assert.assertEquals(19, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testMergeJob_indexNotAlightedEception() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);

        val oldSegs = new NDataSegment(df.getSegments().get(0));
        val update = new NDataflowUpdate(df.getUuid());
        val oldLayouts = new ArrayList<>(df.getSegments().get(0).getLayoutsMap().values());
        update.setToUpdateSegs(oldSegs);
        update.setToRemoveLayouts(oldLayouts.get(0), oldLayouts.get(1));
        NDataSegment seg1 = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            NDataflowManager manager = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            NDataflow updated = manager.updateDataflow(update);
            return manager.appendSegment(updated, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-01-01"), SegmentRange.dateToLong("" + "2012-03-01")));
        }, DEFAULT_PROJECT);
        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_SEGMENT_FAIL.getMsg());
        jobManager.mergeSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testMergeJob_notReadySegmentException() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        var df = dfm.getDataflow(MODEL_ID);

        val seg1 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                SegmentRange.dateToLong("2012-09-01"), SegmentRange.dateToLong("" + "2012-10-01")));
        try {
            jobManager.mergeSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
            Assert.fail();
        } catch (KylinException e) {
            Assert.assertEquals(JOB_CREATE_EXCEPTION.getMsg(), e.getMessage());
        }
    }

    @Test
    public void testMergeJob_timeEception() {
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        NDataSegment seg1 = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);

            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-01-01"), SegmentRange.dateToLong("" + "2012-03-01")));
        }, DEFAULT_PROJECT);
        
        jobManager.mergeSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(1, executables.size());
        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_FAIL.getMsg());
        jobManager.mergeSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testAddSegmentJob_selectNoSegments() {
        List<NDataSegment> newSegments = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            val seg1 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
            val seg2 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-06-01"), SegmentRange.dateToLong("" + "2012-07-01")));
            return Arrays.asList(seg1, seg2);
        }, DEFAULT_PROJECT);

        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.addSegmentJob(new JobParam(newSegments.get(0), MODEL_ID, "ADMIN"));
        jobManager.addSegmentJob(new JobParam(newSegments.get(1), MODEL_ID, "ADMIN"));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(2, executables.size());
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddSegmentHandler);
        Assert.assertEquals(19, getProcessLayout(executables.get(0)));
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddSegmentHandler);
        Assert.assertEquals(19, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testAddSegmentJob_selectSegments() {
        List<NDataSegment> newSegs = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            val seg1 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
            val seg2 = dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-06-01"), SegmentRange.dateToLong("" + "2012-07-01")));
            return Arrays.asList(seg1, seg2);
        }, DEFAULT_PROJECT);

        HashSet<Long> targetLayouts = new HashSet<>();
        targetLayouts.add(1L);
        targetLayouts.add(10001L);
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.addSegmentJob(new JobParam(newSegs.get(0), MODEL_ID, "ADMIN", targetLayouts));
        jobManager.addSegmentJob(new JobParam(newSegs.get(1), MODEL_ID, "ADMIN", targetLayouts));

        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(2, executables.size());
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddSegmentHandler);
        Assert.assertEquals(2, getProcessLayout(executables.get(0)));
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddSegmentHandler);
        Assert.assertEquals(2, getProcessLayout(executables.get(0)));
    }

    @Test
    public void testAddSegmentJob_onlyIncludeLockedIndex() {
        NDataSegment seg1 = UnitOfWork.doInTransactionWithRetry(() -> {
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            indexManager.updateIndexPlan(MODEL_ID, copyForWrite -> {
                Set<Long> layouts = copyForWrite.getAllLayoutIds(false);
                layouts.remove(20000000001L);
                copyForWrite.removeLayouts(layouts, true, true);
                copyForWrite.markWhiteIndexToBeDelete(MODEL_ID, Sets.newHashSet(20000000001L));
            });
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
        }, DEFAULT_PROJECT);

        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_INDEX_FAIL.getMsg());
        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.addSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
    }

    @Test
    public void testAddSegmentJob_timeException() {
        NDataSegment seg1 = EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfm = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            var df = dfm.getDataflow(MODEL_ID);
            return dfm.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-05-01"), SegmentRange.dateToLong("" + "2012-06-01")));
        }, DEFAULT_PROJECT);

        val jobManager = JobManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
        jobManager.addSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
        List<AbstractExecutable> executables = getRunningExecutables(DEFAULT_PROJECT, MODEL_ID);
        Assert.assertEquals(1, executables.size());

        thrown.expect(KylinException.class);
        thrown.expectMessage(JOB_CREATE_CHECK_FAIL.getMsg());
        jobManager.addSegmentJob(new JobParam(seg1, MODEL_ID, "ADMIN"));
    }

    public void prepareSegment() {
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            val dfManager = NDataflowManager.getInstance(getTestConfig(), DEFAULT_PROJECT);
            val df = dfManager.getDataflow(MODEL_ID);
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), DEFAULT_PROJECT);

            val update = new NDataflowUpdate(df.getUuid());
            update.setToRemoveSegs(df.getSegments().toArray(new NDataSegment[0]));
            dfManager.updateDataflow(update);

            val seg1 = dfManager.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-01-01"), SegmentRange.dateToLong("" + "2012-02-01")));
            val seg2 = dfManager.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-02-01"), SegmentRange.dateToLong("" + "2012-03-01")));
            val seg3 = dfManager.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-03-01"), SegmentRange.dateToLong("" + "2012-04-01")));
            val seg4 = dfManager.appendSegment(df, new SegmentRange.TimePartitionedSegmentRange(
                    SegmentRange.dateToLong("2012-04-01"), SegmentRange.dateToLong("" + "2012-05-01")));
            seg1.setStatus(SegmentStatusEnum.READY);
            seg2.setStatus(SegmentStatusEnum.READY);
            seg3.setStatus(SegmentStatusEnum.READY);
            seg4.setStatus(SegmentStatusEnum.READY);
            val update2 = new NDataflowUpdate(df.getUuid());
            update2.setToUpdateSegs(seg1, seg2, seg3, seg4);
            List<NDataLayout> layouts = Lists.newArrayList();
            indexManager.getIndexPlan(MODEL_ID).getAllLayouts().forEach(layout -> {
                layouts.add(NDataLayout.newDataLayout(df, seg1.getId(), layout.getId()));
                layouts.add(NDataLayout.newDataLayout(df, seg2.getId(), layout.getId()));
                layouts.add(NDataLayout.newDataLayout(df, seg3.getId(), layout.getId()));
                layouts.add(NDataLayout.newDataLayout(df, seg4.getId(), layout.getId()));
            });
            update2.setToAddOrUpdateLayouts(layouts.toArray(new NDataLayout[0]));
            dfManager.updateDataflow(update2);
            return true;
        }, DEFAULT_PROJECT);
    }

    private List<AbstractExecutable> getRunningExecutables(String project, String model) {
        return ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv(), project).getRunningExecutables(project,
                model);
    }

    private int getProcessLayout(AbstractExecutable executable) {
        String layouts = executable.getParam(NBatchConstants.P_LAYOUT_IDS);
        if (StringUtils.isBlank(layouts)) {
            return 0;
        }
        return layouts.split(",").length;
    }

}
