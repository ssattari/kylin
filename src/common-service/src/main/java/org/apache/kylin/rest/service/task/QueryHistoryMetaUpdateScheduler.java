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

package org.apache.kylin.rest.service.task;

import static org.apache.kylin.job.factory.JobFactoryConstant.META_JOB_FACTORY;
import static org.apache.kylin.metadata.favorite.QueryHistoryIdOffset.OffsetType.META;
import static org.apache.kylin.metadata.query.QueryMetrics.INTERNAL_TABLE;
import static org.apache.kylin.metadata.query.QueryMetrics.TABLE_SNAPSHOT;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.NativeQueryRealization;
import org.apache.kylin.common.Singletons;
import org.apache.kylin.common.constant.LogConstant;
import org.apache.kylin.common.logging.SetLogCategory;
import org.apache.kylin.common.persistence.metadata.jdbc.JdbcUtil;
import org.apache.kylin.common.util.NamedThreadFactory;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.guava30.shaded.common.annotations.VisibleForTesting;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.factory.JobFactory;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.optimization.FrequencyMap;
import org.apache.kylin.metadata.favorite.AccelerateRuleUtil;
import org.apache.kylin.metadata.favorite.QueryHistoryIdOffsetManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.project.EnhancedUnitOfWork;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.query.QueryHistory;
import org.apache.kylin.metadata.query.QueryMetrics;
import org.apache.kylin.metadata.query.RDBMSQueryHistoryDAO;
import org.apache.kylin.metadata.table.InternalTableDesc;
import org.apache.kylin.metadata.table.InternalTableManager;
import org.apache.kylin.rest.service.IUserGroupService;
import org.apache.kylin.rest.util.SpringContext;

import lombok.Data;
import lombok.Getter;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class QueryHistoryMetaUpdateScheduler {
    static {
        JobFactory.register(META_JOB_FACTORY, new MetaUpdateJob.MetaUpdateJobFactory());
    }
    private ScheduledExecutorService taskScheduler;
    private boolean hasStarted;
    @VisibleForTesting
    RDBMSQueryHistoryDAO queryHistoryDAO;
    AccelerateRuleUtil accelerateRuleUtil;
    @Getter
    private IUserGroupService userGroupService;

    public QueryHistoryMetaUpdateScheduler() {
        queryHistoryDAO = RDBMSQueryHistoryDAO.getInstance();
        accelerateRuleUtil = new AccelerateRuleUtil();
        if (userGroupService == null && SpringContext.getApplicationContext() != null) {
            userGroupService = (IUserGroupService) SpringContext.getApplicationContext().getBean("userGroupService");
        }
        try (SetLogCategory ignored = new SetLogCategory(LogConstant.SCHEDULE_CATEGORY)) {
            log.debug("New QueryHistoryMetaUpdateScheduler created.");
        }
    }

    public static QueryHistoryMetaUpdateScheduler getInstance() {
        return Singletons.getInstance(QueryHistoryMetaUpdateScheduler.class);
    }

    public void init() {
        taskScheduler = Executors.newScheduledThreadPool(1, new NamedThreadFactory("QueryHistoryMetaUpdateWorker"));
        taskScheduler.scheduleWithFixedDelay(this::checkAndSubmitJob, 0,
                KylinConfig.getInstanceFromEnv().getQueryHistoryStatMetaUpdateInterval(), TimeUnit.MINUTES);

        hasStarted = true;
        log.info("Query history task scheduler is started.");
    }

    private void checkAndSubmitJob() {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        if (!JobContextUtil.getJobContext(config).getJobScheduler().isMaster()) {
            log.info("Not master node, skip submitting meta job");
            return;
        }

        List<ProjectInstance> prjList = NProjectManager.getInstance(config).listAllProjects();
        prjList.forEach(projectInstance -> {
            String project = projectInstance.getName();
            ExecutableManager manager = ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            manager.checkAndSubmitCronJob(META_JOB_FACTORY, JobTypeEnum.META);
        });
    }

    public Future scheduleImmediately(QueryHistoryTask runner) {
        return taskScheduler.schedule(runner, 10L, TimeUnit.SECONDS);
    }

    public boolean hasStarted() {
        return this.hasStarted;
    }

    public class QueryHistoryMetaUpdateRunner extends QueryHistoryTask {
        private long lastOffset = 0;

        public QueryHistoryMetaUpdateRunner(String project) {
            super(project);
        }

        @Override
        protected String name() {
            return "metaUpdate";
        }

        @Override
        protected List<QueryHistory> getQueryHistories(int batchSize) {
            QueryHistoryIdOffsetManager qhIdOffsetManager = QueryHistoryIdOffsetManager.getInstance(project);
            this.lastOffset = qhIdOffsetManager.get(META).getOffset();
            return queryHistoryDAO.queryQueryHistoriesByIdOffset(lastOffset, batchSize, project);
        }

        @Override
        public void work() {
            int maxSize = KylinConfig.getInstanceFromEnv().getQueryHistoryStatMetaUpdateMaxSize();
            int batchSize = KylinConfig.getInstanceFromEnv().getQueryHistoryStatMetaUpdateBatchSize();
            batchHandle(batchSize, maxSize, this::updateStatMeta);
        }

        private void updateStatMeta(List<QueryHistory> queryHistories) {
            long maxId = 0;
            Map<String, Long> modelsLastQueryTime = Maps.newHashMap();
            val dfHitCountMap = collectDataflowHitCount(queryHistories);
            for (QueryHistory queryHistory : queryHistories) {
                collectModelLastQueryTime(queryHistory, modelsLastQueryTime);

                if (queryHistory.getId() > maxId) {
                    maxId = queryHistory.getId();
                }
            }
            // count snapshot && internalTable hit
            val hitCountMap = collectHitCount(queryHistories);

            // update metadata
            updateMetadata(dfHitCountMap, modelsLastQueryTime, maxId, hitCountMap);
        }

        private void updateMetadata(Map<String, DataflowHitCount> dfHitCountMap, Map<String, Long> modelsLastQueryTime,
                Long maxId, Pair<Map<TableExtDesc, Integer>, Map<InternalTableDesc, Integer>> hitCountMap) {
            EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
                // update model usage
                incQueryHitCount(dfHitCountMap, project);

                // update model last query time
                updateLastQueryTime(modelsLastQueryTime, project);

                // update snapshot hit count
                incQueryHitSnapshotCount(hitCountMap.getFirst(), project);

                // update internalTable hit count
                incQueryHitInternalTableCount(hitCountMap.getSecond(), project);

                // update offset in transaction, the retry times must be set as 1.
                QueryHistoryIdOffsetManager offsetManager = QueryHistoryIdOffsetManager.getInstance(project);
                if (offsetManager.get(META).getOffset() != lastOffset) {
                    log.warn("Multiple QueryHistoryMetaUpdateRunners are executing concurrently, just exit this one.");
                    throw new IllegalStateException(
                            "Multiple QueryHistoryMetaUpdateRunners are executing concurrently, just exit this one.");
                }
                JdbcUtil.withTxAndRetry(offsetManager.getTransactionManager(), () -> {
                    offsetManager.updateOffset(META, copyForWrite -> copyForWrite.setOffset(maxId));
                    return null;
                });
                return 0;
            }, project, 1);
        }

        private Map<String, DataflowHitCount> collectDataflowHitCount(List<QueryHistory> queryHistories) {
            val result = Maps.<String, DataflowHitCount> newHashMap();
            for (QueryHistory queryHistory : queryHistories) {
                val realizations = queryHistory.transformRealizations(project);
                if (CollectionUtils.isEmpty(realizations)) {
                    continue;
                }
                val realizationList = realizations.stream().filter(this::isIndexRealization)
                        .collect(Collectors.toList());
                for (val realization : realizationList) {
                    String modelId = realization.getModelId();
                    result.computeIfAbsent(modelId, k -> new DataflowHitCount());
                    result.get(modelId).dataflowHit += 1;
                    val layoutHits = result.get(modelId).getLayoutHits();
                    layoutHits.computeIfAbsent(realization.getLayoutId(), k -> new FrequencyMap());
                    layoutHits.get(realization.getLayoutId()).incFrequency(queryHistory.getQueryTime());
                }
            }
            return result;
        }

        private boolean isIndexRealization(NativeQueryRealization realization) {
            val config = KylinConfig.getInstanceFromEnv();
            val dfManager = NDataflowManager.getInstance(config, project);
            return dfManager.getDataflow(realization.getModelId()) != null && realization.getLayoutId() != null;
        }

        private Pair<Map<TableExtDesc, Integer>, Map<InternalTableDesc, Integer>> collectHitCount(
                List<QueryHistory> queryHistories) {
            val tableManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            val internalTableManager = InternalTableManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            Map<TableExtDesc, Integer> tableExtDescMap = new HashMap<>();
            Map<InternalTableDesc, Integer> internalTableDescMap = new HashMap<>();

            Pair<Map<TableExtDesc, Integer>, Map<InternalTableDesc, Integer>> results = new Pair<>(tableExtDescMap,
                    internalTableDescMap);

            for (QueryHistory queryHistory : queryHistories) {
                if (queryHistory.getQueryHistoryInfo() == null) {
                    continue;
                }
                List<QueryMetrics.RealizationMetrics> realizationMetrics = queryHistory.getQueryHistoryInfo()
                        .getRealizationMetrics();
                if (CollectionUtils.isEmpty(realizationMetrics)) {
                    continue;
                }
                for (QueryMetrics.RealizationMetrics realizationMetric : realizationMetrics) {
                    if (CollectionUtils.isEmpty(realizationMetric.getSnapshots())) {
                        continue;
                    }
                    realizationMetric.getSnapshots().forEach(tableIdentify -> {
                        if (TABLE_SNAPSHOT.equals(realizationMetric.getIndexType())) {
                            results.getFirst().merge(tableManager.getOrCreateTableExt(tableIdentify), 1, Integer::sum);
                        } else if (INTERNAL_TABLE.equals(realizationMetric.getIndexType())) {
                            results.getSecond().merge(internalTableManager.getInternalTableDesc(tableIdentify), 1,
                                    Integer::sum);
                        }
                    });
                }
            }
            return results;
        }

        private void collectModelLastQueryTime(QueryHistory queryHistory, Map<String, Long> modelsLastQueryTime) {
            List<NativeQueryRealization> realizations = queryHistory.transformRealizations(project);
            long queryTime = queryHistory.getQueryTime();
            for (NativeQueryRealization realization : realizations) {
                String modelId = realization.getModelId();
                if (StringUtils.isEmpty(modelId)) {
                    continue;
                }
                modelsLastQueryTime.put(modelId, queryTime);
            }
        }

        private void incQueryHitCount(Map<String, DataflowHitCount> dfHitCountMap, String project) {
            val dfManager = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            for (val entry : dfHitCountMap.entrySet()) {
                if (dfManager.getDataflow(entry.getKey()) == null) {
                    continue;
                }
                val layoutHitCount = entry.getValue().getLayoutHits();
                dfManager.updateDataflow(entry.getKey(), copyForWrite -> {
                    copyForWrite.setQueryHitCount(copyForWrite.getQueryHitCount() + entry.getValue().getDataflowHit());
                    for (Map.Entry<Long, FrequencyMap> layoutHitEntry : layoutHitCount.entrySet()) {
                        copyForWrite.getLayoutHitCount().merge(layoutHitEntry.getKey(), layoutHitEntry.getValue(),
                                FrequencyMap::merge);
                    }
                });
            }
        }

        private void incQueryHitSnapshotCount(Map<TableExtDesc, Integer> hitSnapshotCountMap, String project) {
            val tableManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            for (val entry : hitSnapshotCountMap.entrySet()) {
                if (tableManager.getOrCreateTableExt(entry.getKey().getIdentity()) == null) {
                    continue;
                }
                val tableCopy = tableManager.copyForWrite(entry.getKey());
                tableCopy.setSnapshotHitCount(tableCopy.getSnapshotHitCount() + entry.getValue());
                tableManager.saveTableExt(tableCopy);
            }
        }

        private void incQueryHitInternalTableCount(Map<InternalTableDesc, Integer> hitInternalTableCountMap,
                String project) {
            InternalTableManager internalTableManager = InternalTableManager
                    .getInstance(KylinConfig.getInstanceFromEnv(), project);
            for (val entry : hitInternalTableCountMap.entrySet()) {
                if (internalTableManager.getInternalTableDesc(entry.getKey().getIdentity()) == null) {
                    continue;
                }
                val internalTableCopy = internalTableManager.copyForWrite(entry.getKey());
                internalTableCopy.setHitCount(internalTableCopy.getHitCount() + entry.getValue());
                internalTableManager.saveOrUpdateInternalTable(internalTableCopy);
            }
        }

        private void updateLastQueryTime(Map<String, Long> modelsLastQueryTime, String project) {
            NDataflowManager dfManager = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
            for (Map.Entry<String, Long> entry : modelsLastQueryTime.entrySet()) {
                String dataflowId = entry.getKey();
                Long lastQueryTime = entry.getValue();
                if (dfManager.getDataflow(dataflowId) == null) {
                    continue;
                }
                dfManager.updateDataflow(dataflowId, copyForWrite -> copyForWrite.setLastQueryTime(lastQueryTime));
            }
        }

    }

    private abstract static class QueryHistoryTask implements Runnable {
        protected final String project;

        public QueryHistoryTask(String project) {
            this.project = project;
        }

        protected abstract String name();

        public void batchHandle(int batchSize, int maxSize, Consumer<List<QueryHistory>> consumer) {
            if (!(batchSize > 0 && maxSize >= batchSize)) {
                throw new IllegalArgumentException(String.format(Locale.ROOT,
                        "%s task, batch size: %d , maxsize: %d is illegal", name(), batchSize, maxSize));
            }

            int finishNum = 0;
            while (true) {
                List<QueryHistory> queryHistories = getQueryHistories(batchSize);
                finishNum = finishNum + queryHistories.size();
                if (isInterrupted()) {
                    break;
                }
                if (!queryHistories.isEmpty()) {
                    consumer.accept(queryHistories);
                }
                log.debug("{} handled {} query history", name(), queryHistories.size());
                if (queryHistories.size() < batchSize || finishNum >= maxSize) {
                    break;
                }
            }
        }

        protected boolean isInterrupted() {
            return false;
        }

        protected abstract List<QueryHistory> getQueryHistories(int batchSize);

        @Override
        public void run() {
            try (SetLogCategory ignored = new SetLogCategory(LogConstant.SCHEDULE_CATEGORY)) {
                work();
            } catch (Exception e) {
                log.warn("QueryHistory {}  process failed of project({})", name(), project, e);
            }
        }

        protected abstract void work();

    }

    @Data
    private static class DataflowHitCount {

        Map<Long, FrequencyMap> layoutHits = Maps.newHashMap();

        int dataflowHit;
    }
}
