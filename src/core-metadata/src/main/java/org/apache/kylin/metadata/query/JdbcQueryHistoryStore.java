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

package org.apache.kylin.metadata.query;

import static org.mybatis.dynamic.sql.SqlBuilder.avg;
import static org.mybatis.dynamic.sql.SqlBuilder.count;
import static org.mybatis.dynamic.sql.SqlBuilder.isEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.isGreaterThan;
import static org.mybatis.dynamic.sql.SqlBuilder.isGreaterThanOrEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.isIn;
import static org.mybatis.dynamic.sql.SqlBuilder.isLessThan;
import static org.mybatis.dynamic.sql.SqlBuilder.isLessThanOrEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.isLike;
import static org.mybatis.dynamic.sql.SqlBuilder.isLikeCaseInsensitive;
import static org.mybatis.dynamic.sql.SqlBuilder.isNotEqualTo;
import static org.mybatis.dynamic.sql.SqlBuilder.isNotIn;
import static org.mybatis.dynamic.sql.SqlBuilder.max;
import static org.mybatis.dynamic.sql.SqlBuilder.min;
import static org.mybatis.dynamic.sql.SqlBuilder.or;
import static org.mybatis.dynamic.sql.SqlBuilder.select;
import static org.mybatis.dynamic.sql.SqlBuilder.selectDistinct;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.jdbc.ScriptRunner;
import org.apache.ibatis.session.ExecutorType;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.StorageURL;
import org.apache.kylin.common.logging.LogOutputStream;
import org.apache.kylin.common.persistence.metadata.JdbcDataSource;
import org.apache.kylin.common.persistence.metadata.jdbc.JdbcUtil;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.guava30.shaded.common.annotations.VisibleForTesting;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.metadata.query.util.QueryHisStoreUtil;
import org.mybatis.dynamic.sql.BasicColumn;
import org.mybatis.dynamic.sql.SqlBuilder;
import org.mybatis.dynamic.sql.SqlColumn;
import org.mybatis.dynamic.sql.delete.render.DeleteStatementProvider;
import org.mybatis.dynamic.sql.insert.render.InsertStatementProvider;
import org.mybatis.dynamic.sql.render.RenderingStrategies;
import org.mybatis.dynamic.sql.select.QueryExpressionDSL;
import org.mybatis.dynamic.sql.select.SelectModel;
import org.mybatis.dynamic.sql.select.aggregate.Count;
import org.mybatis.dynamic.sql.select.join.EqualTo;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.mybatis.dynamic.sql.update.render.UpdateStatementProvider;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JdbcQueryHistoryStore {

    private static final Charset DEFAULT_CHARSET = Charset.defaultCharset();
    public static final String MONTH = "month";
    public static final String WEEK = "week";
    public static final String DAY = "day";
    public static final String COUNT = "count";
    public static final String ID_TABLE_ALIAS = "idTable";
    public static final String DELETE_REALIZATION_LOG = "Delete {} row query history realization takes {} ms";

    public static final String UNSUPPORTED_MESSAGE = "Unsupported time window!";

    private final QueryHistoryTable queryHistoryTable;
    private final QueryHistoryRealizationTable queryHistoryRealizationTable;

    @VisibleForTesting
    @Getter
    private final SqlSessionFactory sqlSessionFactory;
    private final DataSource dataSource;
    String qhTableName;
    String qhRealizationTableName;

    public JdbcQueryHistoryStore(KylinConfig config) throws Exception {
        StorageURL url = config.getQueryHistoryUrl();
        Properties props = JdbcUtil.datasourceParameters(url);
        dataSource = JdbcDataSource.getDataSource(props);
        qhTableName = StorageURL.replaceUrl(url) + "_" + QueryHistory.QUERY_MEASUREMENT_SURFIX;
        qhRealizationTableName = StorageURL.replaceUrl(url) + "_" + QueryHistory.REALIZATION_MEASUREMENT_SURFIX;
        queryHistoryTable = new QueryHistoryTable(qhTableName);
        queryHistoryRealizationTable = new QueryHistoryRealizationTable(qhRealizationTableName);
        sqlSessionFactory = QueryHisStoreUtil.getSqlSessionFactory(dataSource, qhTableName, qhRealizationTableName);
    }

    // for subclass NoopJdbcQueryHistoryStore
    JdbcQueryHistoryStore() {
        queryHistoryTable = null;
        queryHistoryRealizationTable = null;
        sqlSessionFactory = null;
        dataSource = null;
    }

    public void dropQueryHistoryTable() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ScriptRunner sr = new ScriptRunner(connection);
            sr.setLogWriter(new PrintWriter(new OutputStreamWriter(new LogOutputStream(log), DEFAULT_CHARSET)));
            sr.runScript(new InputStreamReader(new ByteArrayInputStream(//
                    String.format(Locale.ROOT, "drop table %s;", qhTableName).getBytes(DEFAULT_CHARSET)),
                    DEFAULT_CHARSET));
        }
    }

    public int insert(QueryMetrics queryMetrics) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper qhMapper = session.getMapper(QueryHistoryMapper.class);
            InsertStatementProvider<QueryMetrics> insertStatement = getInsertQhProvider(queryMetrics);
            int rows = qhMapper.insert(insertStatement);

            QueryHistoryRealizationMapper qhRealizationMapper = session.getMapper(QueryHistoryRealizationMapper.class);
            List<InsertStatementProvider<QueryMetrics.RealizationMetrics>> insertQhRealProviderList = Lists
                    .newArrayList();
            queryMetrics.getRealizationMetrics().forEach(realizationMetrics -> insertQhRealProviderList
                    .add(getInsertQhRealizationProvider(realizationMetrics)));
            insertQhRealProviderList.forEach(qhRealizationMapper::insert);

            if (rows > 0) {
                log.debug("Insert one query history(query id:{}) into database.", queryMetrics.getQueryId());
            }
            session.commit();
            return rows;
        }
    }

    public void insert(List<QueryMetrics> queryMetricsList) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            List<InsertStatementProvider<QueryMetrics>> providers = Lists.newArrayList();
            queryMetricsList.forEach(queryMetrics -> providers.add(getInsertQhProvider(queryMetrics)));
            providers.forEach(mapper::insert);

            QueryHistoryRealizationMapper qhRealizationMapper = session.getMapper(QueryHistoryRealizationMapper.class);
            List<InsertStatementProvider<QueryMetrics.RealizationMetrics>> insertQhRealProviderList = Lists
                    .newArrayList();
            queryMetricsList.forEach(queryMetrics -> queryMetrics.getRealizationMetrics()
                    .forEach(realizationMetrics -> insertQhRealProviderList
                            .add(getInsertQhRealizationProvider(realizationMetrics))));
            insertQhRealProviderList.forEach(qhRealizationMapper::insert);

            session.commit();
            if (queryMetricsList.size() > 0) {
                log.info("Insert {} query history into database takes {} ms", queryMetricsList.size(),
                        System.currentTimeMillis() - startTime);
            }
        }
    }

    public List<QueryHistory> queryQueryHistoriesByConditions(QueryHistoryRequest request, int limit, int offset) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = queryQueryHistoriesByConditionsProvider(request, limit, offset);
            return mapper.selectMany(statementProvider);
        }
    }

    public QueryStatistics queryQueryHistoriesSize(QueryHistoryRequest request) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryQueryHistoriesSizeProvider(request);
            return mapper.selectOne(statementProvider);
        }
    }

    public List<QueryDailyStatistic> queryHistoryDailyStatistic(long startTime, long endTime) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            return mapper.selectDaily(qhTableName, startTime, endTime);
        }
    }

    public List<QueryHistory> queryQueryHistoriesSubmitters(QueryHistoryRequest request, int size) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = querySubmittersByConditionsProvider(request, size);
            return mapper.selectMany(statementProvider);
        }
    }

    private List<String> queryQueryHistoriesIds(List<String> modelIds) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = selectDistinct(queryHistoryRealizationTable.queryId)
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.model, isIn(modelIds)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider).stream().map(QueryHistory::getQueryId)
                    .collect(Collectors.toList());
        }
    }

    public List<QueryStatistics> queryQueryHistoriesModelIds(QueryHistoryRequest request) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider1 = selectDistinct(queryHistoryTable.engineType)
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.engineType, isNotEqualTo("NATIVE")) //
                    .and(queryHistoryTable.projectName, isEqualTo(request.getProject())) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider1);
        }
    }

    public QueryHistory getOldestQueryHistory(long index) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable))
                    .from(queryHistoryTable) //
                    .orderBy(queryHistoryTable.id) //
                    .limit(1) //
                    .offset(index - 1L) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectOne(statementProvider);
        }
    }

    public QueryHistory getOldestQueryHistory(String project, long index) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable))
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.projectName, isEqualTo(project)) //
                    .orderBy(queryHistoryTable.id) //
                    .limit(1) //
                    .offset(index - 1L) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectOne(statementProvider);
        }
    }

    public Long getCountOnQueryHistory() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(Count.of(queryHistoryTable.id)) //
                    .from(queryHistoryTable) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectAsLong(statementProvider);
        }
    }

    public Long getCountOnQueryHistory(long retainTime) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(Count.of(queryHistoryTable.id).as(COUNT)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isLessThan(retainTime)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectAsLong(statementProvider);
        }
    }

    public Map<String, Long> getCountGroupByProject() {
        Map<String, Long> projectCounts = new HashMap<>();
        List<QueryHistoryProjectInfo> projectInfos;
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(queryHistoryTable.projectName,
                    count(queryHistoryTable.id).as(COUNT)) //
                    .from(queryHistoryTable) //
                    .groupBy(queryHistoryTable.projectName) //
                    .build().render(RenderingStrategies.MYBATIS3);
            projectInfos = mapper.selectByProject(statementProvider);
        }
        projectInfos.forEach(projectInfo -> projectCounts.put(projectInfo.getProjectName(), projectInfo.getCount()));
        return projectCounts;
    }

    public QueryHistory queryByQueryId(String queryId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryId, isEqualTo(queryId)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectOne(statementProvider);
        }
    }

    public List<QueryHistory> queryByQueryIds(List<String> queryIds) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryId, isIn(queryIds)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryHistory> queryAllQueryHistories() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable)) //
                    .from(queryHistoryTable) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryHistory> queryQueryHistoriesByIdOffset(long id, int batchSize, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(getSelectFields(queryHistoryTable)) //
                    .from(queryHistoryTable) //
                    .join(select(BasicColumn.columnList(queryHistoryTable.id)).from(queryHistoryTable)
                            .where(queryHistoryTable.id, isGreaterThan(id)) //
                            .and(queryHistoryTable.projectName, isEqualTo(project)) //
                            .orderBy(queryHistoryTable.id) //
                            .limit(batchSize), ID_TABLE_ALIAS) //
                    .on(queryHistoryTable.id.qualifiedWith(queryHistoryTable.tableNameAtRuntime()),
                            new EqualTo(queryHistoryTable.id.qualifiedWith(ID_TABLE_ALIAS)))
                    .orderBy(queryHistoryTable.id) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryCountAndAvgDuration(long startTime, long endTime, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = select(count(queryHistoryTable.queryId).as(COUNT),
                    avg(queryHistoryTable.duration).as("mean")) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryCountAndAvgDurationRealization(long startTime, long endTime, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = select(count(queryHistoryRealizationTable.queryId).as(COUNT),
                    avg(queryHistoryRealizationTable.duration).as("mean")).from(queryHistoryRealizationTable)
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime))
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime))
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)).build()
                    .render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryCountByModel(long startTime, long endTime, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = select(queryHistoryRealizationTable.model,
                    count(queryHistoryRealizationTable.queryId).as(COUNT)) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.model) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public long queryQueryHistoryCountBeyondOffset(long offset, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(count(queryHistoryTable.id)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.id, isGreaterThan(offset)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectAsLong(statementProvider);
        }
    }

    public long queryQueryHistoryMaxId(String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(max(queryHistoryTable.id)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            Long maxId = mapper.selectAsLong(statementProvider);
            return maxId == null ? 0L : maxId;
        }
    }

    protected Long queryQueryHistoryMinQueryTime() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            SelectStatementProvider statementProvider = select(queryHistoryTable.queryTime) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.id, isEqualTo(select(min(queryHistoryTable.id)).from(queryHistoryTable)))
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectAsLong(statementProvider);
        }
    }

    public QueryStatistics queryRecentQueryCount(long startTime, long endTime, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryCountByTimeProvider(startTime, endTime, project);
            return mapper.selectOne(statementProvider);
        }
    }

    public List<QueryStatistics> queryCountByTime(long startTime, long endTime, String timeDimension, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryCountByTimeProvider(startTime, endTime, timeDimension,
                    project);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryAvgDurationByModel(long startTime, long endTime, String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = select(queryHistoryRealizationTable.model,
                    avg(queryHistoryRealizationTable.duration).as("mean")) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.model) //
                    .build().render(RenderingStrategies.MYBATIS3);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryAvgDurationByTime(long startTime, long endTime, String timeDimension,
            String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryAvgDurationByTimeProvider(startTime, endTime,
                    timeDimension, project);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryAvgDurationRealizationByTime(long startTime, long endTime, String timeDimension,
            String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryAvgDurationRealizationByTimeProvider(startTime, endTime,
                    timeDimension, project);
            return mapper.selectMany(statementProvider);
        }
    }

    public List<QueryStatistics> queryCountRealizationByTime(long startTime, long endTime, String timeDimension,
            String project) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryStatisticsMapper mapper = session.getMapper(QueryStatisticsMapper.class);
            SelectStatementProvider statementProvider = queryCountRealizationByTimeProvider(startTime, endTime,
                    timeDimension, project);
            return mapper.selectMany(statementProvider);
        }
    }

    public void deleteQueryHistory() {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryTable) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info("Delete {} row query history takes {} ms", deleteRows, System.currentTimeMillis() - startTime);
            }
        }
    }

    public int deleteQueryHistory(long id) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryTable) //
                    .where(queryHistoryTable.id, isLessThanOrEqualTo(id)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info("Delete {} row query history takes {} ms", deleteRows, System.currentTimeMillis() - startTime);
            }
            return deleteRows;
        }
    }

    public int deleteQueryHistory(String project, long id) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryTable) //
                    .where(queryHistoryTable.id, isLessThanOrEqualTo(id)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info("Delete {} row query history for project [{}] takes {} ms", deleteRows, project,
                        System.currentTimeMillis() - startTime);
            }
            return deleteRows;
        }
    }

    public void deleteQueryHistory(String project) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryTable) //
                    .where(queryHistoryTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info("Delete {} row query history for project [{}] takes {} ms", deleteRows, project,
                        System.currentTimeMillis() - startTime);
            }
        } catch (Exception e) {
            log.error("Fail to delete query history for project [{}]", project, e);
        }
    }

    public void deleteQueryHistoryRealization(long queryTime) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isLessThan(queryTime)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info(DELETE_REALIZATION_LOG, deleteRows, System.currentTimeMillis() - startTime);
            }
        }
    }

    public void deleteQueryHistoryRealization(String project, long queryTime) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isLessThan(queryTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info(DELETE_REALIZATION_LOG, deleteRows, System.currentTimeMillis() - startTime);
            }
        }
    }

    public void deleteQueryHistoryRealization(String project) {
        long startTime = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession()) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            DeleteStatementProvider deleteStatement = SqlBuilder.deleteFrom(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .build().render(RenderingStrategies.MYBATIS3);
            int deleteRows = mapper.delete(deleteStatement);
            session.commit();
            if (deleteRows > 0) {
                log.info(DELETE_REALIZATION_LOG, deleteRows, System.currentTimeMillis() - startTime);
            }
        } catch (Exception e) {
            log.error("Fail to delete query history realization for project [{}]", project, e);
        }
    }

    public void updateQueryHistoryInfo(List<Pair<Long, QueryHistoryInfo>> idToQHInfoList) {
        long start = System.currentTimeMillis();
        try (SqlSession session = sqlSessionFactory.openSession(ExecutorType.BATCH)) {
            QueryHistoryMapper mapper = session.getMapper(QueryHistoryMapper.class);
            List<UpdateStatementProvider> providers = Lists.newArrayList();
            idToQHInfoList.forEach(pair -> providers.add(changeQHInfoProvider(pair.getFirst(), pair.getSecond())));
            providers.forEach(mapper::update);
            session.commit();
            if (idToQHInfoList.size() > 0) {
                log.info("Update {} query history info takes {} ms", idToQHInfoList.size(),
                        System.currentTimeMillis() - start);
            }
        }
    }

    InsertStatementProvider<QueryMetrics> getInsertQhProvider(QueryMetrics queryMetrics) {
        SqlColumn<String> monthColumn = KylinConfig.getInstanceFromEnv().isUTEnv() ? queryHistoryTable.month_h2
                : queryHistoryTable.month;
        return SqlBuilder.insert(queryMetrics).into(queryHistoryTable).map(queryHistoryTable.queryId)
                .toPropertyWhenPresent("queryId", queryMetrics::getQueryId) //
                .map(queryHistoryTable.sql).toPropertyWhenPresent("sql", queryMetrics::getSql) //
                .map(queryHistoryTable.sqlPattern).toPropertyWhenPresent("sqlPattern", queryMetrics::getSqlPattern) //
                .map(queryHistoryTable.duration).toPropertyWhenPresent("queryDuration", queryMetrics::getQueryDuration) //
                .map(queryHistoryTable.totalScanBytes)
                .toPropertyWhenPresent("totalScanBytes", queryMetrics::getTotalScanBytes) //
                .map(queryHistoryTable.totalScanCount)
                .toPropertyWhenPresent("totalScanCount", queryMetrics::getTotalScanCount) //
                .map(queryHistoryTable.resultRowCount)
                .toPropertyWhenPresent("resultRowCount", queryMetrics::getResultRowCount) //
                .map(queryHistoryTable.querySubmitter).toPropertyWhenPresent("submitter", queryMetrics::getSubmitter) //
                .map(queryHistoryTable.hostName).toPropertyWhenPresent("server", queryMetrics::getServer) //
                .map(queryHistoryTable.errorType).toPropertyWhenPresent("errorType", queryMetrics::getErrorType) //
                .map(queryHistoryTable.engineType).toPropertyWhenPresent("engineType", queryMetrics::getEngineType) //
                .map(queryHistoryTable.cacheHit).toPropertyWhenPresent("cacheHit", queryMetrics::isCacheHit) //
                .map(queryHistoryTable.queryStatus).toPropertyWhenPresent("queryStatus", queryMetrics::getQueryStatus) //
                .map(queryHistoryTable.indexHit).toPropertyWhenPresent("indexHit", queryMetrics::isIndexHit) //
                .map(queryHistoryTable.queryTime).toPropertyWhenPresent("queryTime", queryMetrics::getQueryTime) //
                .map(monthColumn).toPropertyWhenPresent(MONTH, queryMetrics::getMonth) //
                .map(queryHistoryTable.queryFirstDayOfMonth)
                .toPropertyWhenPresent("queryFirstDayOfMonth", queryMetrics::getQueryFirstDayOfMonth) //
                .map(queryHistoryTable.queryFirstDayOfWeek)
                .toPropertyWhenPresent("queryFirstDayOfWeek", queryMetrics::getQueryFirstDayOfWeek) //
                .map(queryHistoryTable.queryDay).toPropertyWhenPresent("queryDay", queryMetrics::getQueryDay) //
                .map(queryHistoryTable.projectName).toPropertyWhenPresent("projectName", queryMetrics::getProjectName) //
                .map(queryHistoryTable.queryHistoryInfo).toProperty("queryHistoryInfo") //
                .build().render(RenderingStrategies.MYBATIS3);
    }

    InsertStatementProvider<QueryMetrics.RealizationMetrics> getInsertQhRealizationProvider(
            QueryMetrics.RealizationMetrics realizationMetrics) {
        return SqlBuilder.insert(realizationMetrics).into(queryHistoryRealizationTable)
                .map(queryHistoryRealizationTable.model)
                .toPropertyWhenPresent("modelId", realizationMetrics::getModelId)
                .map(queryHistoryRealizationTable.layoutId)
                .toPropertyWhenPresent("layoutId", realizationMetrics::getLayoutId)
                .map(queryHistoryRealizationTable.indexType)
                .toPropertyWhenPresent("indexType", realizationMetrics::getIndexType)
                .map(queryHistoryRealizationTable.queryId)
                .toPropertyWhenPresent("queryId", realizationMetrics::getQueryId)
                .map(queryHistoryRealizationTable.duration)
                .toPropertyWhenPresent("duration", realizationMetrics::getDuration)
                .map(queryHistoryRealizationTable.queryTime)
                .toPropertyWhenPresent("queryTime", realizationMetrics::getQueryTime)
                .map(queryHistoryRealizationTable.projectName)
                .toPropertyWhenPresent("projectName", realizationMetrics::getProjectName)
                .map(queryHistoryRealizationTable.queryDay)
                .toPropertyWhenPresent("queryDay", realizationMetrics::getQueryDay)
                .map(queryHistoryRealizationTable.queryFirstDayOfWeek)
                .toPropertyWhenPresent("queryFirstDayOfWeek", realizationMetrics::getQueryFirstDayOfWeek)
                .map(queryHistoryRealizationTable.queryFirstDayOfMonth)
                .toPropertyWhenPresent("queryFirstDayOfMonth", realizationMetrics::getQueryFirstDayOfMonth).build()
                .render(RenderingStrategies.MYBATIS3);
    }

    private SelectStatementProvider queryQueryHistoriesByConditionsProvider(QueryHistoryRequest request, int limit,
            int offset) {
        return select(getSelectFields(queryHistoryTable)).from(queryHistoryTable)
                .join(filterByConditions(select(BasicColumn.columnList(queryHistoryTable.id)).from(queryHistoryTable),
                        request).orderBy(queryHistoryTable.queryTime.descending()) //
                        .limit(limit) //
                        .offset(offset), ID_TABLE_ALIAS) //
                .on(queryHistoryTable.id.qualifiedWith(queryHistoryTable.tableNameAtRuntime()),
                        new EqualTo(queryHistoryTable.id.qualifiedWith(ID_TABLE_ALIAS)))
                .orderBy(queryHistoryTable.queryTime.descending()) //
                .build().render(RenderingStrategies.MYBATIS3);
    }

    private SelectStatementProvider querySubmittersByConditionsProvider(QueryHistoryRequest request, int size) {
        return filterByConditions(selectDistinct(queryHistoryTable.querySubmitter).from(queryHistoryTable), request)
                .limit(size).build().render(RenderingStrategies.MYBATIS3);
    }

    private SelectStatementProvider queryQueryHistoriesSizeProvider(QueryHistoryRequest request) {
        return filterByConditions(select(count(queryHistoryTable.id).as(COUNT)).from(queryHistoryTable), request)
                .build().render(RenderingStrategies.MYBATIS3);
    }

    private QueryExpressionDSL<SelectModel>.QueryExpressionWhereBuilder filterByConditions(
            QueryExpressionDSL<SelectModel> selectSql, QueryHistoryRequest request) {
        QueryExpressionDSL<SelectModel>.QueryExpressionWhereBuilder filterSql = selectSql.where();

        if (StringUtils.isNotEmpty(request.getStartTimeFrom()) && StringUtils.isNotEmpty(request.getStartTimeTo())) {
            filterSql = filterSql
                    .and(queryHistoryTable.queryTime,
                            isGreaterThanOrEqualTo(Long.parseLong(request.getStartTimeFrom())))
                    .and(queryHistoryTable.queryTime, isLessThan(Long.parseLong(request.getStartTimeTo())));
        }

        if (StringUtils.isNotEmpty(request.getLatencyFrom()) && StringUtils.isNotEmpty(request.getLatencyTo())) {
            filterSql = filterSql
                    .and(queryHistoryTable.duration,
                            isGreaterThanOrEqualTo(Long.parseLong(request.getLatencyFrom()) * 1000L))
                    .and(queryHistoryTable.duration, isLessThan(Long.parseLong(request.getLatencyTo()) * 1000L));
        }

        if (StringUtils.isNotEmpty(request.getServer())) {
            filterSql = filterSql.and(queryHistoryTable.hostName, isEqualTo(request.getServer()));
        }

        if (StringUtils.isNotEmpty(request.getSql())) {
            filterSql = filterSql.and(queryHistoryTable.sql, isLike("%" + request.getSql() + "%"),
                    or(queryHistoryTable.queryId, isLike("%" + request.getSql() + "%")));
        }

        if (request.getRealizations() != null && !request.getRealizations().isEmpty()) {
            filterSql = filterQueryHistoryRealization(filterSql, request);
        }

        if (request.getQueryStatus() != null && request.getQueryStatus().size() == 1) {
            filterSql = filterSql.and(queryHistoryTable.queryStatus, isEqualTo(request.getQueryStatus().get(0)));
        }

        filterSql = filterSql.and(queryHistoryTable.projectName, isEqualTo(request.getProject()));

        if (!request.isAdmin()) {
            filterSql = filterSql.and(queryHistoryTable.querySubmitter, isEqualTo(request.getUsername()));
        }

        if (request.getFilterSubmitter() != null && !request.getFilterSubmitter().isEmpty()) {
            if (request.isSubmitterExactlyMatch()) {
                filterSql = filterSql.and(queryHistoryTable.querySubmitter, isIn(request.getFilterSubmitter()));
            } else if (request.getFilterSubmitter().size() == 1) {
                filterSql = filterSql.and(queryHistoryTable.querySubmitter,
                        isLikeCaseInsensitive("%" + request.getFilterSubmitter().get(0) + "%"));
            }
        }

        return filterSql;
    }

    private QueryExpressionDSL<SelectModel>.QueryExpressionWhereBuilder filterQueryHistoryRealization(
            QueryExpressionDSL<SelectModel>.QueryExpressionWhereBuilder filterSql, QueryHistoryRequest request) {
        List<String> realizations = request.realizations;
        boolean pushdown = realizations.contains("pushdown");
        boolean selectAllModels = realizations.contains("modelName");
        if (pushdown && selectAllModels) {
            return filterSql;
        } else if (pushdown) {
            if (request.getFilterModelIds() != null && !request.getFilterModelIds().isEmpty()) {
                filterSql = filterSql.and(queryHistoryTable.indexHit, isEqualTo(false), or(queryHistoryTable.queryId,
                        isIn(selectDistinct(queryHistoryRealizationTable.queryId).from(queryHistoryRealizationTable)
                                .where(queryHistoryRealizationTable.model, isIn(request.getFilterModelIds())))));
            } else {
                filterSql = filterSql.and(queryHistoryTable.indexHit, isEqualTo(false));
            }
        } else if (selectAllModels) {
            if (!CollectionUtils.isEmpty(request.getExcludeFilterModelIds())) {
                // Process CONSTANTS, HIVE, RDBMS and not in model1, model2, model3...
                filterSql = filterSql.and(queryHistoryTable.engineType, isIn(realizations),
                        or(queryHistoryTable.queryId,
                                isIn(selectDistinct(queryHistoryRealizationTable.queryId)
                                        .from(queryHistoryRealizationTable).where(queryHistoryRealizationTable.model,
                                                isNotIn(request.getExcludeFilterModelIds())))));
            } else {
                // Process CONSTANTS, HIVE, RDBMS and all model
                filterSql = filterSql.and(queryHistoryTable.engineType, isIn(realizations),
                        or(queryHistoryTable.indexHit, isEqualTo(true)));
            }
        } else if (request.getFilterModelIds() != null && !request.getFilterModelIds().isEmpty()) {
            // Process CONSTANTS, HIVE, RDBMS and model1, model2, model3...
            filterSql = filterSql.and(queryHistoryTable.engineType, isIn(realizations),
                    or(queryHistoryTable.queryId,
                            isIn(selectDistinct(queryHistoryRealizationTable.queryId).from(queryHistoryRealizationTable)
                                    .where(queryHistoryRealizationTable.model, isIn(request.getFilterModelIds())))));
        } else {
            // Process CONSTANTS, HIVE, RDBMS
            filterSql = filterSql.and(queryHistoryTable.engineType, isIn(realizations));
        }

        return filterSql;
    }

    QueryExpressionDSL<SelectModel>.QueryExpressionWhereBuilder filterModelsByConditions(
            QueryExpressionDSL<SelectModel> selectSql, List<String> modelIds) {
        return selectSql.where().and(queryHistoryRealizationTable.model, isIn(modelIds));
    }

    private UpdateStatementProvider changeQHInfoProvider(long id, QueryHistoryInfo queryHistoryInfo) {
        return SqlBuilder.update(queryHistoryTable) //
                .set(queryHistoryTable.queryHistoryInfo) //
                .equalTo(queryHistoryInfo) //
                .where(queryHistoryTable.id, isEqualTo(id)) //
                .build().render(RenderingStrategies.MYBATIS3);
    }

    private SelectStatementProvider queryCountByTimeProvider(long startTime, long endTime, String project) {
        return select(count(queryHistoryTable.id).as(COUNT)) //
                .from(queryHistoryTable) //
                .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                .and(queryHistoryTable.projectName, isEqualTo(project)) //
                .build().render(RenderingStrategies.MYBATIS3);
    }

    private SelectStatementProvider queryCountByTimeProvider(long startTime, long endTime, String timeDimension,
            String project) {
        if (timeDimension.equalsIgnoreCase(MONTH)) {
            return select(queryHistoryTable.queryFirstDayOfMonth.as("time"), count(queryHistoryTable.id).as(COUNT)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryFirstDayOfMonth) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(WEEK)) {
            return select(queryHistoryTable.queryFirstDayOfWeek.as("time"), count(queryHistoryTable.id).as(COUNT)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryFirstDayOfWeek) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(DAY)) {
            return select(queryHistoryTable.queryDay.as("time"), count(queryHistoryTable.id).as(COUNT)) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryDay) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else {
            throw new IllegalStateException(UNSUPPORTED_MESSAGE);
        }
    }

    private SelectStatementProvider queryAvgDurationByTimeProvider(long startTime, long endTime, String timeDimension,
            String project) {
        if (timeDimension.equalsIgnoreCase(MONTH)) {
            return select(queryHistoryTable.queryFirstDayOfMonth.as("time"), avg(queryHistoryTable.duration).as("mean")) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryFirstDayOfMonth) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(WEEK)) {
            return select(queryHistoryTable.queryFirstDayOfWeek.as("time"), avg(queryHistoryTable.duration).as("mean")) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryFirstDayOfWeek) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(DAY)) {
            return select(queryHistoryTable.queryDay.as("time"), avg(queryHistoryTable.duration).as("mean")) //
                    .from(queryHistoryTable) //
                    .where(queryHistoryTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryTable.queryDay) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else {
            throw new IllegalStateException(UNSUPPORTED_MESSAGE);
        }
    }

    private SelectStatementProvider queryAvgDurationRealizationByTimeProvider(long startTime, long endTime,
            String timeDimension, String project) {
        if (timeDimension.equalsIgnoreCase(MONTH)) {
            return select(queryHistoryRealizationTable.queryFirstDayOfMonth.as("time"),
                    avg(queryHistoryRealizationTable.duration).as("mean")) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryFirstDayOfMonth) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(WEEK)) {
            return select(queryHistoryRealizationTable.queryFirstDayOfWeek.as("time"),
                    avg(queryHistoryRealizationTable.duration).as("mean")) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryFirstDayOfWeek) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(DAY)) {
            return select(queryHistoryRealizationTable.queryDay.as("time"),
                    avg(queryHistoryRealizationTable.duration).as("mean")) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryDay) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else {
            throw new IllegalStateException(UNSUPPORTED_MESSAGE);
        }
    }

    private SelectStatementProvider queryCountRealizationByTimeProvider(long startTime, long endTime,
            String timeDimension, String project) {
        if (timeDimension.equalsIgnoreCase(MONTH)) {
            return select(queryHistoryRealizationTable.queryFirstDayOfMonth.as("time"),
                    count(queryHistoryRealizationTable.id).as(COUNT)) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryFirstDayOfMonth) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(WEEK)) {
            return select(queryHistoryRealizationTable.queryFirstDayOfWeek.as("time"),
                    count(queryHistoryRealizationTable.id).as(COUNT)) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryFirstDayOfWeek) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else if (timeDimension.equalsIgnoreCase(DAY)) {
            return select(queryHistoryRealizationTable.queryDay.as("time"),
                    count(queryHistoryRealizationTable.id).as(COUNT)) //
                    .from(queryHistoryRealizationTable) //
                    .where(queryHistoryRealizationTable.queryTime, isGreaterThanOrEqualTo(startTime)) //
                    .and(queryHistoryRealizationTable.queryTime, isLessThan(endTime)) //
                    .and(queryHistoryRealizationTable.projectName, isEqualTo(project)) //
                    .groupBy(queryHistoryRealizationTable.queryDay) //
                    .build().render(RenderingStrategies.MYBATIS3);
        } else {
            throw new IllegalStateException(UNSUPPORTED_MESSAGE);
        }
    }

    private BasicColumn[] getSelectFields(QueryHistoryTable queryHistoryTable) {
        return BasicColumn.columnList(queryHistoryTable.id.qualifiedWith(queryHistoryTable.tableNameAtRuntime()),
                queryHistoryTable.cacheHit, queryHistoryTable.duration, queryHistoryTable.engineType,
                queryHistoryTable.errorType, queryHistoryTable.hostName, queryHistoryTable.indexHit,
                queryHistoryTable.projectName, queryHistoryTable.queryHistoryInfo, queryHistoryTable.queryId,
                queryHistoryTable.queryRealizations, queryHistoryTable.queryStatus, queryHistoryTable.querySubmitter,
                queryHistoryTable.queryTime, queryHistoryTable.resultRowCount, queryHistoryTable.sql,
                queryHistoryTable.sqlPattern, queryHistoryTable.totalScanBytes, queryHistoryTable.totalScanCount);
    }
}
