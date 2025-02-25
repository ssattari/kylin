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

package org.apache.kylin.rest.service;

import static org.apache.kylin.common.exception.QueryErrorCode.EMPTY_TABLE;
import static org.apache.kylin.common.exception.ServerErrorCode.COLUMN_NOT_EXIST;
import static org.apache.kylin.common.exception.ServerErrorCode.DUPLICATED_COLUMN_NAME;
import static org.apache.kylin.common.exception.ServerErrorCode.FAILED_IMPORT_SSB_DATA;
import static org.apache.kylin.common.exception.ServerErrorCode.FAILED_REFRESH_CATALOG_CACHE;
import static org.apache.kylin.common.exception.ServerErrorCode.FILE_NOT_EXIST;
import static org.apache.kylin.common.exception.ServerErrorCode.INVALID_COMPUTED_COLUMN_EXPRESSION;
import static org.apache.kylin.common.exception.ServerErrorCode.INVALID_PARTITION_COLUMN;
import static org.apache.kylin.common.exception.ServerErrorCode.TABLE_NOT_EXIST;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_ID_NOT_EXIST;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_NAME_NOT_EXIST;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.TABLE_RELOAD_HAVING_NOT_FINAL_JOB;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.TABLE_RELOAD_MODEL_RETRY;
import static org.apache.kylin.job.execution.JobTypeEnum.SNAPSHOT_BUILD;
import static org.apache.kylin.job.execution.JobTypeEnum.SNAPSHOT_REFRESH;
import static org.apache.kylin.rest.util.TableUtils.calculateTableSize;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.PrivilegedExceptionAction;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kylin.common.KapConfig;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.KylinConfigBase;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.code.ErrorCodeServer;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.persistence.transaction.AddCredentialToSparkBroadcastEventNotifier;
import org.apache.kylin.common.persistence.transaction.TransactionException;
import org.apache.kylin.common.persistence.transaction.UnitOfWorkParams;
import org.apache.kylin.common.scheduler.EventBusFactory;
import org.apache.kylin.common.util.BufferedLogger;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.DateFormat;
import org.apache.kylin.common.util.HadoopUtil;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.common.util.ShellException;
import org.apache.kylin.constants.AclConstants;
import org.apache.kylin.guava30.shaded.common.annotations.VisibleForTesting;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.base.Strings;
import org.apache.kylin.guava30.shaded.common.collect.HashMultimap;
import org.apache.kylin.guava30.shaded.common.collect.LinkedHashMultimap;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.MapDifference;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Multimap;
import org.apache.kylin.guava30.shaded.common.collect.SetMultimap;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.guava30.shaded.common.graph.Graph;
import org.apache.kylin.guava30.shaded.common.graph.Graphs;
import org.apache.kylin.job.dao.ExecutablePO;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.execution.JobTypeEnum;
import org.apache.kylin.job.manager.JobManager;
import org.apache.kylin.job.model.JobParam;
import org.apache.kylin.job.service.TableSampleService;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.job.util.JobInfoUtil;
import org.apache.kylin.metadata.acl.AclTCR;
import org.apache.kylin.metadata.acl.AclTCRManager;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.cube.model.NSegmentConfigHelper;
import org.apache.kylin.metadata.datatype.DataType;
import org.apache.kylin.metadata.filter.function.LikeMatchers;
import org.apache.kylin.metadata.model.AutoMergeTimeEnum;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.ISourceAware;
import org.apache.kylin.metadata.model.JoinTableDesc;
import org.apache.kylin.metadata.model.ManagementType;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.SegmentRange;
import org.apache.kylin.metadata.model.SegmentStatusEnum;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.VolatileRange;
import org.apache.kylin.metadata.model.exception.IllegalCCExpressionException;
import org.apache.kylin.metadata.model.schema.AffectedModelContext;
import org.apache.kylin.metadata.model.schema.ReloadTableContext;
import org.apache.kylin.metadata.model.schema.SchemaNode;
import org.apache.kylin.metadata.model.schema.SchemaNodeType;
import org.apache.kylin.metadata.model.schema.SchemaUtil;
import org.apache.kylin.metadata.project.EnhancedUnitOfWork;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.recommendation.ref.OptRecManagerV2;
import org.apache.kylin.metadata.sourceusage.SourceUsageManager;
import org.apache.kylin.metadata.streaming.DataParserManager;
import org.apache.kylin.metadata.streaming.KafkaConfig;
import org.apache.kylin.metadata.streaming.KafkaConfigManager;
import org.apache.kylin.query.util.PushDownUtil;
import org.apache.kylin.rest.aspect.Transaction;
import org.apache.kylin.rest.cluster.ClusterManager;
import org.apache.kylin.rest.constant.JobInfoEnum;
import org.apache.kylin.rest.request.AutoMergeRequest;
import org.apache.kylin.rest.request.DateRangeRequest;
import org.apache.kylin.rest.request.S3TableExtInfo;
import org.apache.kylin.rest.request.TableDescRequest;
import org.apache.kylin.rest.response.AutoMergeConfigResponse;
import org.apache.kylin.rest.response.EnvelopeResponse;
import org.apache.kylin.rest.response.LoadTableResponse;
import org.apache.kylin.rest.response.NHiveTableNameResponse;
import org.apache.kylin.rest.response.NInitTablesResponse;
import org.apache.kylin.rest.response.OpenPreReloadTableResponse;
import org.apache.kylin.rest.response.PreReloadTableResponse;
import org.apache.kylin.rest.response.PreUnloadTableResponse;
import org.apache.kylin.rest.response.ServerInfoResponse;
import org.apache.kylin.rest.response.TableDescResponse;
import org.apache.kylin.rest.response.TableNameResponse;
import org.apache.kylin.rest.response.TableRefresh;
import org.apache.kylin.rest.response.TableRefreshAll;
import org.apache.kylin.rest.response.TablesAndColumnsResponse;
import org.apache.kylin.rest.security.KerberosLoginManager;
import org.apache.kylin.rest.source.DataSourceState;
import org.apache.kylin.rest.util.AclEvaluate;
import org.apache.kylin.rest.util.AclPermissionUtil;
import org.apache.kylin.rest.util.PagingUtil;
import org.apache.kylin.source.ISourceMetadataExplorer;
import org.apache.kylin.source.SourceFactory;
import org.apache.spark.sql.SparderEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import lombok.val;
import lombok.var;

@Service("tableService")
public class TableService extends BasicService {

    private static final Logger logger = LoggerFactory.getLogger(TableService.class);

    private static final String REFRESH_SINGLE_CATALOG_PATH = "/kylin/api/query/single_catalog_cache";

    private static final String SSB_ERROR_MSG = "import ssb data error.";

    @Autowired
    private TableModelSupporter modelService;

    @Autowired
    private TableFusionModelSupporter fusionModelService;

    @Autowired
    private TableIndexPlanSupporter indexPlanService;

    @Autowired
    private TableSampleService tableSampleService;

    @Autowired
    private AclEvaluate aclEvaluate;

    @Autowired
    private AccessService accessService;

    @Autowired(required = false)
    @Qualifier("kafkaService")
    private KafkaService kafkaService;

    @Autowired(required = false)
    @Qualifier("aclTCRService")
    private AclTCRServiceSupporter aclTCRService;

    @Autowired(required = false)
    @Qualifier("jobInfoService")
    private JobSupporter jobInfoService;

    @Autowired
    private ClusterManager clusterManager;
    @Autowired
    private InternalTableService internalTableService;

    public Pair<List<TableDesc>, Integer> getTableDesc(String project, boolean withExt, final String table,
            final String database, boolean isFuzzy, List<Integer> sourceType, int returnTableSize) throws IOException {
        TableDescRequest internalTableDescRequest = new TableDescRequest(project, withExt, table, database, isFuzzy,
                sourceType);
        return getTableDesc(internalTableDescRequest, returnTableSize);
    }

    public Pair<List<TableDesc>, Integer> getTableDesc(TableDescRequest tableDescRequest, int returnTableSize)
            throws IOException {
        aclEvaluate.checkProjectReadPermission(tableDescRequest.getProject());
        boolean streamingEnabled = getConfig().isStreamingEnabled();
        NTableMetadataManager nTableMetadataManager = getManager(NTableMetadataManager.class,
                tableDescRequest.getProject());
        List<TableDesc> tables = Lists.newArrayList();
        //get table not fuzzy,can use getTableDesc(tableName)
        if (StringUtils.isNotEmpty(tableDescRequest.getTable()) && !tableDescRequest.isFuzzy()) {
            val tableDesc = nTableMetadataManager
                    .getTableDesc(tableDescRequest.getDatabase() + "." + tableDescRequest.getTable());
            if (tableDesc != null && tableDesc.isAccessible(streamingEnabled))
                tables.add(tableDesc);
        } else {
            tables.addAll(nTableMetadataManager.listAllTables().stream().filter(tableDesc -> {
                if (StringUtils.isEmpty(tableDescRequest.getDatabase())) {
                    return true;
                }
                return tableDesc.getDatabase().equalsIgnoreCase(tableDescRequest.getDatabase());
            }).filter(tableDesc -> {
                if (StringUtils.isEmpty(tableDescRequest.getTable())) {
                    return true;
                }
                return tableDesc.getName().toLowerCase(Locale.ROOT)
                        .contains(tableDescRequest.getTable().toLowerCase(Locale.ROOT));
            }).filter(tableDesc -> {
                // Advance the logic of filtering the table by sourceType to here
                List<Integer> sourceTypes = tableDescRequest.getSourceType();
                if (!sourceTypes.isEmpty()) {
                    return sourceTypes.contains(tableDesc.getSourceType())
                            || sourceTypes.stream().flatMap(st -> getConfig().getSourceProviderFamily(st).stream())
                                    .collect(Collectors.toList()).contains(tableDesc.getSourceType());
                }
                return true;
            }).filter(table -> table.isAccessible(streamingEnabled)).sorted(this::compareTableDesc)
                    .collect(Collectors.toList()));
        }
        return getTablesResponse(tables, tableDescRequest, returnTableSize);
    }

    public int compareTableDesc(TableDesc table1, TableDesc table2) {
        if (table1.isTop() == table2.isTop()) {
            if (table1.isIncrementLoading() == table2.isIncrementLoading()) {
                return table1.getName().compareToIgnoreCase(table2.getName());
            } else {
                return table1.isIncrementLoading() && !table2.isIncrementLoading() ? -1 : 1;
            }
        } else {
            return table1.isTop() && !table2.isTop() ? -1 : 1;
        }
    }

    @Transaction(project = 2)
    public String loadTableToProject(TableDesc tableDesc, TableExtDesc extDesc, String project) {
        final NTableMetadataManager tableMetaMgr = getManager(NTableMetadataManager.class, project);
        Set<TableExtDesc.RoleCredentialInfo> broadcastConf = new HashSet<>();

        TableDesc origTable = tableMetaMgr.getTableDesc(tableDesc.getIdentity());
        val nTableDesc = new TableDesc(tableDesc);
        if (origTable == null || origTable.getProject() == null) {
            nTableDesc.setUuid(RandomUtil.randomUUIDStr());
            nTableDesc.setLastModified(0);
            tableMetaMgr.saveSourceTable(nTableDesc);
        } else {
            tableMetaMgr.updateTableDesc(nTableDesc.getIdentity(), copyForWrite -> {
                nTableDesc.copyPropertiesTo(copyForWrite);
                copyForWrite.setUuid(origTable.getUuid());
                copyForWrite.setLastModified(origTable.getLastModified());
                copyForWrite.setIncrementLoading(origTable.isIncrementLoading());
                copyForWrite.setTableComment(tableDesc.getTableComment());
            });
        }
        if (extDesc != null) {
            val colNameMap = Stream.of(nTableDesc.getColumns()).collect(Collectors.toMap(ColumnDesc::getName, col -> {
                try {
                    return Integer.parseInt(col.getId());
                } catch (NumberFormatException e) {
                    return Integer.MAX_VALUE;
                }
            }));
            TableExtDesc origExt = tableMetaMgr.getTableExtIfExists(tableDesc);
            TableExtDesc nTableExtDesc = new TableExtDesc(extDesc);
            if (origExt == null || origExt.getProject() == null) {
                nTableExtDesc.setUuid(RandomUtil.randomUUIDStr());
                nTableExtDesc.setLastModified(0);
                nTableExtDesc.getAllColumnStats()
                        .sort(Comparator.comparing(stat -> colNameMap.getOrDefault(stat.getColumnName(), -1)));
                nTableExtDesc.init(project);
                tableMetaMgr.saveTableExt(nTableExtDesc);
            } else {
                tableMetaMgr.updateTableExt(nTableExtDesc.getIdentity(), copyForWrite -> {
                    nTableExtDesc.copyPropertiesTo(copyForWrite);
                    copyForWrite.setUuid(origExt.getUuid());
                    copyForWrite.setLastModified(origExt.getLastModified());
                    copyForWrite.setMvcc(origExt.getMvcc());
                    copyForWrite.setOriginalSize(origExt.getOriginalSize());
                    copyForWrite.getAllColumnStats()
                            .sort(Comparator.comparing(stat -> colNameMap.getOrDefault(stat.getColumnName(), -1)));
                    copyForWrite.init(project);
                });
            }
            if (!broadcastConf.contains(extDesc.getRoleCredentialInfo())) {
                addAndBroadcastSparkSession(extDesc.getRoleCredentialInfo());
                broadcastConf.add(extDesc.getRoleCredentialInfo());
            }
        }
        return tableDesc.getIdentity();
    }

    public List<Pair<TableDesc, TableExtDesc>> extractTableMeta(String[] tables, String project) {
        return extractTableMeta(tables, project, null);
    }

    public List<Pair<TableDesc, TableExtDesc>> extractTableMeta(String[] tables, String project,
            LoadTableResponse tableResponse) {
        // de-dup
        SetMultimap<String, String> databaseTables = LinkedHashMultimap.create();
        for (String fullTableName : tables) {
            String[] parts = HadoopUtil.parseHiveTableName(fullTableName.toUpperCase(Locale.ROOT));
            Preconditions.checkArgument(!parts[1].isEmpty() && !parts[0].isEmpty(),
                    MsgPicker.getMsg().getTableParamEmpty());
            databaseTables.put(parts[0], parts[1]);
        }
        // load all tables first Pair<TableDesc, TableExtDesc>
        ProjectInstance projectInstance = getManager(NProjectManager.class).getProject(project);
        ISourceMetadataExplorer explr = SourceFactory.getSource(projectInstance).getSourceMetadataExplorer();

        List<Pair<TableDesc, TableExtDesc>> successResults = Collections.synchronizedList(new ArrayList<>());
        List<String> failedTableNames = Collections.synchronizedList(new ArrayList<>());
        databaseTables.entries().parallelStream().forEach(entry -> {
            try {
                Pair<TableDesc, TableExtDesc> pair = explr.loadTableMetadata(entry.getKey(), entry.getValue(), project);
                TableDesc tableDesc = pair.getFirst();
                Preconditions.checkState(tableDesc.getDatabase().equalsIgnoreCase(entry.getKey()));
                Preconditions.checkState(tableDesc.getName().equalsIgnoreCase(entry.getValue()));
                Preconditions.checkState(tableDesc.getIdentity().equals(
                        entry.getKey().toUpperCase(Locale.ROOT) + "." + entry.getValue().toUpperCase(Locale.ROOT)));
                TableExtDesc extDesc = pair.getSecond();
                Preconditions.checkState(tableDesc.getIdentity().equals(extDesc.getIdentity()));
                successResults.add(pair);
            } catch (Exception e) {
                logger.error("Failed to extract meta data of table:{}.{}", entry.getKey(), entry.getValue(), e);
                failedTableNames.add(entry.getKey() + "." + entry.getValue());
            }
        });
        if (!failedTableNames.isEmpty()) {
            String errorTables = StringUtils.join(failedTableNames, "\n");
            String errorMessage = String.format(Locale.ROOT, MsgPicker.getMsg().getHiveTableNotFound(), errorTables);
            if (Objects.isNull(tableResponse)) {
                throw new KylinException(TABLE_NOT_EXIST, errorMessage);
            } else {
                tableResponse.getFailed().addAll(failedTableNames);
                logger.error("Skip load these failed tables:\n{}", errorTables);
            }
        }
        return successResults;
    }

    public List<String> getSourceDbNames(String project) throws Exception {
        aclEvaluate.checkProjectWritePermission(project);
        ISourceMetadataExplorer explr = SourceFactory.getSource(getManager(NProjectManager.class).getProject(project))
                .getSourceMetadataExplorer();
        return explr.listDatabases().stream().map(str -> str.toUpperCase(Locale.ROOT)).collect(Collectors.toList());
    }

    public List<String> getSourceTableNames(String project, String database, final String table) throws Exception {
        ISourceMetadataExplorer explr = SourceFactory.getSource(getManager(NProjectManager.class).getProject(project))
                .getSourceMetadataExplorer();
        return explr.listTables(database).stream().filter(s -> {
            if (StringUtils.isEmpty(table)) {
                return true;
            } else {
                return s.toLowerCase(Locale.ROOT).contains(table.toLowerCase(Locale.ROOT));
            }
        }).map(str -> str.toUpperCase(Locale.ROOT)).collect(Collectors.toList());

    }

    public List<TableNameResponse> getTableNameResponses(String project, String database, final String table)
            throws Exception {
        aclEvaluate.checkProjectReadPermission(project);
        List<TableNameResponse> tableNameResponses = new ArrayList<>();
        NTableMetadataManager tableManager = getManager(NTableMetadataManager.class, project);
        List<String> tables = getSourceTableNames(project, database, table);
        for (String tableName : tables) {
            TableNameResponse tableNameResponse = new TableNameResponse();
            tableNameResponse.setTableName(tableName);
            String tableNameWithDB = database + "." + tableName;
            checkTableExistOrLoad(tableNameResponse, tableManager.getTableDesc(tableNameWithDB));
            tableNameResponses.add(tableNameResponse);
        }
        return tableNameResponses;
    }

    private TableDescResponse getTableResponse(TableDesc table, String project, boolean withExt) {
        if (!withExt) {
            return new TableDescResponse(table);
        }
        TableDescResponse tableDescResponse = new TableDescResponse(table);
        TableExtDesc tableExtDesc = getManager(NTableMetadataManager.class, project).getTableExtIfExists(table);
        if (table.isKafkaTable()) {
            tableDescResponse.setKafkaBootstrapServers(table.getKafkaConfig().getKafkaBootstrapServers());
            tableDescResponse.setSubscribe(table.getKafkaConfig().getSubscribe());
            tableDescResponse.setBatchTable(table.getKafkaConfig().getBatchTable());
            tableDescResponse.setParserName(table.getKafkaConfig().getParserName());
        }

        if (tableExtDesc == null) {
            return tableDescResponse;
        }

        for (TableDescResponse.ColumnDescResponse colDescResponse : tableDescResponse.getExtColumns()) {
            TableExtDesc.ColumnStats columnStats = tableExtDesc.getColumnStatsByName(colDescResponse.getName());
            colDescResponse.setExcluded(tableExtDesc.isExcludedCol(colDescResponse.getName()));
            if (columnStats != null) {
                colDescResponse.setCardinality(columnStats.getCardinality());
                colDescResponse.setMaxValue(columnStats.getMaxValue());
                colDescResponse.setMinValue(columnStats.getMinValue());
                colDescResponse.setNullCount(columnStats.getNullCount());
            }
        }
        tableDescResponse.setDescExd(tableExtDesc.getDataSourceProps());
        tableDescResponse.setCreateTime(tableExtDesc.getCreateTime());
        tableDescResponse.setExcluded(tableExtDesc.isExcluded());
        return tableDescResponse;
    }

    private Pair<List<TableDesc>, Integer> getTablesResponse(List<TableDesc> tables, TableDescRequest tableDescRequest,
            int returnTableSize) {
        String project = tableDescRequest.getProject();
        boolean withExt = tableDescRequest.isWithExt();
        List<Integer> sourceTypesRequest = tableDescRequest.getSourceType();
        List<TableDesc> descs = new ArrayList<>();
        val projectManager = getManager(NProjectManager.class);
        val groups = getCurrentUserGroups();
        final List<AclTCR> aclTCRS = getManager(AclTCRManager.class, project)
                .getAclTCRs(AclPermissionUtil.getCurrentUsername(), groups);
        final boolean isAclGreen = AclPermissionUtil.canUseACLGreenChannel(project, groups);
        FileSystem fs = HadoopUtil.getWorkingFileSystem();
        List<NDataModel> healthyModels = projectManager.listHealthyModels(project);
        Set<String> extPermissionSet = accessService.getUserNormalExtPermissions(project);
        int satisfiedTableSize = 0;
        boolean hasDataQueryPermission = extPermissionSet.contains(AclConstants.DATA_QUERY);
        for (val originTable : tables) {
            // New judgment logic, when the total size of tables meet the current size of paging directly after the exit
            // Also, if the processing is not finished, the total size of tables is returned
            if (satisfiedTableSize == returnTableSize) {
                return Pair.newPair(descs, tables.size());
            }
            TableDesc table = getAuthorizedTableDesc(project, isAclGreen, originTable, aclTCRS);
            if (Objects.isNull(table)) {
                continue;
            }
            TableDescResponse tableDescResponse = getTableResponse(table, project, withExt);
            List<NDataModel> modelsUsingTable = healthyModels.stream() //
                    .filter(model -> model.containsTable(table)).collect(Collectors.toList());
            List<NDataModel> modelsUsingRootTable = healthyModels.stream() //
                    .filter(model -> model.isRootFactTable(table)).collect(Collectors.toList());

            TableExtDesc tableExtDesc = getManager(NTableMetadataManager.class, project).getTableExtIfExists(table);
            if (tableExtDesc != null) {
                tableDescResponse.setTotalRecords(tableExtDesc.getTotalRows());
                tableDescResponse.setJodID(tableExtDesc.getJodID());
                if (hasDataQueryPermission) {
                    tableDescResponse.setSamplingRows(tableExtDesc.getSampleRows());
                    filterSamplingRows(project, tableDescResponse, isAclGreen, aclTCRS);
                }
            }

            if (CollectionUtils.isNotEmpty(modelsUsingRootTable)) {
                tableDescResponse.setRootFact(true);
                tableDescResponse.setStorageSize(getStorageSize(project, modelsUsingRootTable, fs));
            } else if (CollectionUtils.isNotEmpty(modelsUsingTable)) {
                tableDescResponse.setLookup(true);
                tableDescResponse.setStorageSize(getSnapshotSize(project, table.getIdentity(), fs));
            }
            Pair<Set<String>, Set<String>> tableColumnType = getTableColumnType(project, table, modelsUsingTable);
            tableDescResponse.setForeignKey(tableColumnType.getSecond());
            tableDescResponse.setPrimaryKey(tableColumnType.getFirst());

            // Table source type conversion
            sourceTypesRequest.stream()
                    .filter(stq -> getConfig().getSourceProviderFamily(stq).contains(originTable.getSourceType()))
                    .findFirst().ifPresent(tableDescResponse::setSourceType);

            descs.add(tableDescResponse);
            satisfiedTableSize++;
        }
        return Pair.newPair(descs, descs.size());
    }

    @VisibleForTesting
    void filterSamplingRows(String project, TableDescResponse rtableDesc, boolean isAclGreen, List<AclTCR> aclTCRS) {
        if (isAclGreen) {
            return;
        }
        List<String[]> result = Lists.newArrayList();
        final String dbTblName = rtableDesc.getIdentity();
        AclTCRManager manager = getManager(AclTCRManager.class, project);
        Map<Integer, AclTCR.ColumnRealRows> columnRows = Arrays.stream(rtableDesc.getExtColumns()).map(cdr -> {
            int id = Integer.parseInt(cdr.getId());
            val columnRealRows = manager.getAuthorizedRows(dbTblName, cdr.getName(), aclTCRS);
            return new AbstractMap.SimpleEntry<>(id, columnRealRows);
        }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        for (String[] row : rtableDesc.getSamplingRows()) {
            if (Objects.isNull(row)) {
                continue;
            }
            int i = 0;
            boolean jumpThisSample = false;
            List<String> nlist = Lists.newArrayList();
            while (i++ < row.length) {
                if (!columnRows.containsKey(i)) {
                    continue;
                }
                val columnRealRows = columnRows.get(i);
                if (Objects.isNull(columnRealRows)) {
                    jumpThisSample = true;
                    break;
                }
                Set<String> equalRows = columnRealRows.getRealRow();
                Set<String> likeRows = columnRealRows.getRealLikeRow();
                if ((CollectionUtils.isNotEmpty(equalRows) && !equalRows.contains(row[i - 1]))
                        || (CollectionUtils.isNotEmpty(likeRows) && noMatchedLikeCondition(row[i - 1], likeRows))) {
                    jumpThisSample = true;
                    break;
                }
                nlist.add(row[i - 1]);
            }
            if (jumpThisSample) {
                continue;
            }
            if (CollectionUtils.isNotEmpty(nlist)) {
                result.add(nlist.toArray(new String[0]));
            }
        }
        rtableDesc.setSamplingRows(result);
    }

    private static boolean noMatchedLikeCondition(String target, Set<String> likePatterns) {
        for (String likePattern : likePatterns) {
            val matcher = new LikeMatchers.DefaultLikeMatcher(likePattern, "\\");
            if (matcher.matches(target)) {
                return false;
            }
        }
        return true;
    }

    @VisibleForTesting
    TableDesc getAuthorizedTableDesc(String project, boolean isAclGreen, TableDesc originTable, List<AclTCR> aclTCRS) {
        if (isAclGreen) {
            return originTable;
        }
        return getManager(AclTCRManager.class, project).getAuthorizedTableDesc(originTable, aclTCRS);
    }

    public long getSnapshotSize(String project, String table, FileSystem fs) {
        val tableDesc = getManager(NTableMetadataManager.class, project).getTableDesc(table);
        if (tableDesc != null && tableDesc.getLastSnapshotPath() != null) {
            try {
                val path = new Path(KapConfig.wrap(KylinConfig.getInstanceFromEnv()).getMetadataWorkingDirectory(),
                        tableDesc.getLastSnapshotPath());
                return HadoopUtil.getContentSummary(fs, path).getLength();
            } catch (Exception e) {
                logger.warn("cannot get snapshot path {}", tableDesc.getLastSnapshotPath(), e);
            }
        }
        return 0;
    }

    private long getStorageSize(String project, List<NDataModel> models, FileSystem fs) {
        val dfManger = getManager(NDataflowManager.class, project);
        long size = 0;
        for (val model : models) {
            val df = dfManger.getDataflow(model.getUuid());
            if (df == null) {
                logger.warn("cannot get model size  {} of {}（might be deleted） ", model.getAlias(), project);
                continue;
            }
            val readySegs = df.getSegments(SegmentStatusEnum.READY, SegmentStatusEnum.WARNING);
            if (CollectionUtils.isNotEmpty(readySegs)) {
                for (NDataSegment segment : readySegs) {
                    size += segment.getStorageBytesSize();
                }
            }
        }
        return size;
    }

    //get table's primaryKeys(pair first) and foreignKeys(pair second)
    private Pair<Set<String>, Set<String>> getTableColumnType(String project, TableDesc table,
            List<NDataModel> modelsUsingTable) {
        val dataModelManager = getManager(NDataModelManager.class, project);
        Set<String> primaryKey = new HashSet<>();
        Set<String> foreignKey = new HashSet<>();
        for (val model : modelsUsingTable) {
            var dataMode = dataModelManager.getDataModelDesc(model.getUuid());
            if (dataMode == null) {
                continue;
            }
            val joinTables = dataMode.getJoinTables();
            for (JoinTableDesc joinTable : joinTables) {
                if (joinTable.getTable().equals(table.getIdentity())) {
                    foreignKey.addAll(Arrays.asList(joinTable.getJoin().getForeignKey()));
                    primaryKey.addAll(Arrays.asList(joinTable.getJoin().getPrimaryKey()));
                    break;
                }
            }
        }
        Pair<Set<String>, Set<String>> result = new Pair<>();
        result.setFirst(primaryKey);
        result.setSecond(foreignKey);
        return result;
    }

    public String normalizeHiveTableName(String tableName) {
        String[] dbTableName = HadoopUtil.parseHiveTableName(tableName);
        return (dbTableName[0] + "." + dbTableName[1]).toUpperCase(Locale.ROOT);
    }

    public String getPartitionColumnFormat(String project, String table, String partitionColumn,
            String partitionExpression) throws Exception {
        aclEvaluate.checkProjectOperationPermission(project);

        NTableMetadataManager tableManager = getManager(NTableMetadataManager.class, project);
        TableDesc tableDesc = tableManager.getTableDesc(table);
        Preconditions.checkNotNull(tableDesc, String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), table));
        Set<String> columnSet = Stream.of(tableDesc.getColumns()).map(ColumnDesc::getName)
                .map(str -> str.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        if (!columnSet.contains(StringUtils.upperCase(partitionColumn))) {
            throw new KylinException(COLUMN_NOT_EXIST, String.format(Locale.ROOT,
                    "Can not find the column:%s in table:%s, project:%s", partitionColumn, table, project));
        }

        try {
            if (tableDesc.isKafkaTable()) {
                List<ByteBuffer> messages = kafkaService.getMessages(tableDesc.getKafkaConfig(), project);
                checkMessage(table, messages);
                Map<String, Object> resp = kafkaService.decodeMessage(messages);
                String message = ((List<String>) resp.get("message")).get(0);
                Map<String, Object> mapping = kafkaService.parserMessage(project, tableDesc.getKafkaConfig(), message);
                Map<String, Object> mappingAllCaps = new HashMap<>();
                mapping.forEach((key, value) -> mappingAllCaps.put(key.toUpperCase(Locale.ROOT), value));
                String cell = (String) mappingAllCaps.get(partitionColumn);
                return DateFormat.proposeDateFormat(cell);
            } else if (partitionExpression == null) {
                List<String> list = PushDownUtil.backtickQuote(partitionColumn.split("\\."));
                String cell = PushDownUtil.probeColFormat(table, String.join(".", list), project);
                return DateFormat.proposeDateFormat(cell);
            } else {
                String cell = PushDownUtil.probeExpFormat(table, partitionExpression, project);
                return DateFormat.proposeDateFormat(cell);
            }
        } catch (KylinException e) {
            throw e;
        } catch (Exception e) {
            logger.error("Failed to get date format.", e);
            throw new KylinException(INVALID_PARTITION_COLUMN, MsgPicker.getMsg().getPushdownPartitionFormatError());
        }
    }

    private void checkMessage(String table, List<ByteBuffer> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new KylinException(EMPTY_TABLE,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getNoDataInTable(), table));
        }
    }

    @VisibleForTesting
    public SegmentRange getSegmentRangeByTable(DateRangeRequest dateRangeRequest) {
        String project = dateRangeRequest.getProject();
        String table = dateRangeRequest.getTable();
        NTableMetadataManager nProjectManager = getManager(NTableMetadataManager.class, project);
        TableDesc tableDesc = nProjectManager.getTableDesc(table);
        return SourceFactory.getSource(tableDesc).getSegmentRange(dateRangeRequest.getStart(),
                dateRangeRequest.getEnd());

    }

    private List<AbstractExecutable> stopAndGetSnapshotJobs(String project, String table) {
        val execManager = getManager(ExecutableManager.class, project);

        val jobInfoList = execManager.fetchJobsByTypesAndStates(project,
                Lists.newArrayList(SNAPSHOT_BUILD.name(), SNAPSHOT_REFRESH.name()), Lists.newArrayList(table),
                ExecutableState.getNotFinalStates());

        List<String> conflictJobIds = jobInfoList.stream().map(jobInfo -> jobInfo.getJobId())
                .collect(Collectors.toList());
        JobContextUtil.remoteDiscardJob(project, conflictJobIds);

        return jobInfoList.stream().map(jobInfo -> execManager.fromPO(JobInfoUtil.deserializeExecutablePO(jobInfo)))
                .collect(Collectors.toList());
    }

    @Transaction(project = 0)
    public String unloadTable(String project, String table, Boolean cascade) {
        aclEvaluate.checkProjectWritePermission(project);
        NTableMetadataManager tableMetadataManager = getManager(NTableMetadataManager.class, project);
        val tableDesc = tableMetadataManager.getTableDesc(table);
        if (Objects.isNull(tableDesc)) {
            String errorMsg = String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), table);
            throw new KylinException(TABLE_NOT_EXIST, errorMsg);
        }

        stopAndGetSnapshotJobs(project, table);

        val dataflowManager = getManager(NDataflowManager.class, project);
        if (cascade) {
            for (NDataModel tableRelatedModel : dataflowManager.getModelsUsingTable(tableDesc)) {
                fusionModelService.onDropModel(tableRelatedModel.getId(), project, true);
            }
            unloadKafkaTableUsingTable(project, tableDesc);
        } else {
            stopStreamingJobByTable(project, tableDesc);
            jobInfoService.stopBatchJob(project, tableDesc);
        }

        unloadTable(project, table);
        aclTCRService.unloadTable(project, table);

        NProjectManager npr = getManager(NProjectManager.class);
        final ProjectInstance projectInstance = npr.getProject(project);
        Set<String> databases = getLoadedDatabases(project).stream().map(str -> str.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
        if (tableDesc.getDatabase().equals(projectInstance.getDefaultDatabase())
                && !databases.contains(projectInstance.getDefaultDatabase())) {
            npr.updateProject(project,
                    copyForWrite -> copyForWrite.setDefaultDatabase(ProjectInstance.DEFAULT_DATABASE));
        }

        return tableDesc.getIdentity();
    }

    private void unloadKafkaTableUsingTable(String project, TableDesc tableDesc) {
        if (tableDesc.getSourceType() != ISourceAware.ID_SPARK)
            return;

        val kafkaConfigManger = getManager(KafkaConfigManager.class, project);
        for (KafkaConfig kafkaConfig : kafkaConfigManger.getKafkaTablesUsingTable(tableDesc.getIdentity())) {
            unloadTable(project, kafkaConfig.getIdentity());
        }
    }

    private void stopStreamingJobByTable(String project, TableDesc tableDesc) {
        for (NDataModel tableRelatedModel : getManager(NDataflowManager.class, project)
                .getModelsUsingTable(tableDesc)) {
            fusionModelService.onStopStreamingJob(tableRelatedModel.getId(), project);
        }
    }

    public void unloadTable(String project, String tableIdentity) {
        NTableMetadataManager tableMetadataManager = getManager(NTableMetadataManager.class, project);
        if (tableMetadataManager.getTableDesc(tableIdentity).isHasInternal()) {
            internalTableService.dropInternalTable(project, tableIdentity);
        }
        getManager(NTableMetadataManager.class, project).removeTableExt(tableIdentity);
        getManager(NTableMetadataManager.class, project).removeSourceTable(tableIdentity);
        KafkaConfigManager kafkaConfigManager = getManager(KafkaConfigManager.class, project);
        KafkaConfig kafkaConfig = kafkaConfigManager.getKafkaConfig(tableIdentity);
        if (Objects.isNull(kafkaConfig)) {
            return;
        }
        kafkaConfigManager.removeKafkaConfig(tableIdentity);
        getManager(DataParserManager.class, project).removeUsingTable(tableIdentity, kafkaConfig.getParserName());
    }

    public PreUnloadTableResponse preUnloadTable(String project, String tableIdentity) throws IOException {
        aclEvaluate.checkProjectWritePermission(project);
        val response = new PreUnloadTableResponse();
        val dataflowManager = getManager(NDataflowManager.class, project);
        val tableMetadataManager = getManager(NTableMetadataManager.class, project);
        val executableManager = getManager(ExecutableManager.class, project);

        val tableDesc = tableMetadataManager.getTableDesc(tableIdentity);
        if (Objects.isNull(tableDesc)) {
            String errorMsg = String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), tableIdentity);
            throw new KylinException(TABLE_NOT_EXIST, errorMsg);
        }

        val models = dataflowManager.getModelsUsingTable(tableDesc).stream().filter(Objects::nonNull)
                .map(model -> model.fusionModelBatchPart() ? model.getFusionModelAlias() : model.getAlias())
                .collect(Collectors.toList());
        response.setHasModel(CollectionUtils.isNotEmpty(models));
        response.setModels(models);

        val rootTableModels = dataflowManager.getModelsUsingRootTable(tableDesc);
        val fs = HadoopUtil.getWorkingFileSystem();
        long storageSize = 0;
        if (CollectionUtils.isNotEmpty(rootTableModels)) {
            storageSize += getStorageSize(project, rootTableModels, fs);
        }
        storageSize += getSnapshotSize(project, tableIdentity, fs);
        response.setStorageSize(storageSize);

        response.setHasJob(
                executableManager.countByModelAndStatus(tableIdentity, state -> state == ExecutableState.RUNNING) > 0);

        response.setHasSnapshot(tableDesc.getLastSnapshotPath() != null);

        return response;
    }

    @Transaction(project = 1)
    public void setTop(String table, String project, boolean top) {
        aclEvaluate.checkProjectWritePermission(project);
        NTableMetadataManager nTableMetadataManager = getManager(NTableMetadataManager.class, project);
        TableDesc tableDesc = nTableMetadataManager.getTableDesc(table);
        tableDesc = nTableMetadataManager.copyForWrite(tableDesc);
        tableDesc.setTop(top);
        nTableMetadataManager.updateTableDesc(tableDesc);
    }

    public List<TablesAndColumnsResponse> getTableAndColumns(String project) {
        aclEvaluate.checkProjectReadPermission(project);
        List<TableDesc> tables = getManager(NTableMetadataManager.class, project).listAllTables();
        List<TablesAndColumnsResponse> result = new ArrayList<>();
        for (TableDesc table : tables) {
            TablesAndColumnsResponse response = new TablesAndColumnsResponse();
            response.setTable(table.getName());
            response.setDatabase(table.getDatabase());
            ColumnDesc[] columns = table.getColumns();
            List<String> columnNames = new ArrayList<>();
            for (ColumnDesc column : columns) {
                columnNames.add(column.getName());
            }
            response.setColumns(columnNames);
            result.add(response);
        }
        return result;
    }

    public AutoMergeConfigResponse getAutoMergeConfigByModel(String project, String modelId) {
        aclEvaluate.checkProjectOperationPermission(project);
        NDataModelManager dataModelManager = getManager(NDataModelManager.class, project);
        AutoMergeConfigResponse mergeConfig = new AutoMergeConfigResponse();

        NDataModel model = dataModelManager.getDataModelDesc(modelId);
        if (model == null) {
            throw new KylinException(MODEL_ID_NOT_EXIST, modelId);
        }
        val segmentConfig = NSegmentConfigHelper.getModelSegmentConfig(project, modelId);
        Preconditions.checkState(segmentConfig != null);
        mergeConfig.setAutoMergeEnabled(segmentConfig.getAutoMergeEnabled());
        mergeConfig.setAutoMergeTimeRanges(segmentConfig.getAutoMergeTimeRanges());
        mergeConfig.setVolatileRange(segmentConfig.getVolatileRange());
        return mergeConfig;
    }

    @Transaction(project = 0)
    public void setAutoMergeConfigByModel(String project, AutoMergeRequest autoMergeRequest) {
        aclEvaluate.checkProjectWritePermission(project);
        String modelId = autoMergeRequest.getModel();
        NDataModelManager dataModelManager = getManager(NDataModelManager.class, project);
        List<AutoMergeTimeEnum> autoMergeRanges = new ArrayList<>();
        for (String range : autoMergeRequest.getAutoMergeTimeRanges()) {
            autoMergeRanges.add(AutoMergeTimeEnum.valueOf(range));
        }
        VolatileRange volatileRange = new VolatileRange();
        volatileRange.setVolatileRangeType(AutoMergeTimeEnum.valueOf(autoMergeRequest.getVolatileRangeType()));
        volatileRange.setVolatileRangeEnabled(autoMergeRequest.isVolatileRangeEnabled());
        volatileRange.setVolatileRangeNumber(autoMergeRequest.getVolatileRangeNumber());

        NDataModel model = dataModelManager.getDataModelDesc(modelId);
        if (model == null) {
            throw new KylinException(MODEL_ID_NOT_EXIST, modelId);
        }
        if (ManagementType.MODEL_BASED == model.getManagementType()) {
            NDataModel modelUpdate = dataModelManager.copyForWrite(model);
            var segmentConfig = modelUpdate.getSegmentConfig();
            segmentConfig.setVolatileRange(volatileRange);
            segmentConfig.setAutoMergeTimeRanges(autoMergeRanges);
            segmentConfig.setAutoMergeEnabled(autoMergeRequest.isAutoMergeEnabled());
            dataModelManager.updateDataModelDesc(modelUpdate);
        }
    }

    public OpenPreReloadTableResponse preProcessBeforeReloadWithoutFailFast(String project, String tableIdentity,
            boolean needDetails) throws Exception {
        Preconditions.checkNotNull(tableIdentity, "table identity can not be null");
        aclEvaluate.checkProjectWritePermission(project);
        val context = calcReloadContext(project, tableIdentity.toUpperCase(Locale.ROOT), false);
        removeFusionModelBatchPart(project, context);
        PreReloadTableResponse preReloadTableResponse = preProcessBeforeReloadWithContext(project, context,
                needDetails);

        OpenPreReloadTableResponse openPreReloadTableResponse = new OpenPreReloadTableResponse(preReloadTableResponse);
        openPreReloadTableResponse.setDuplicatedColumns(Lists.newArrayList(context.getDuplicatedColumns()));
        openPreReloadTableResponse.setEffectedJobs(Lists.newArrayList(context.getEffectedJobs()));
        boolean hasDatasourceChanged = (preReloadTableResponse.getAddColumnCount()
                + preReloadTableResponse.getRemoveColumnCount()
                + preReloadTableResponse.getDataTypeChangeColumnCount()) > 0;
        openPreReloadTableResponse.setHasDatasourceChanged(hasDatasourceChanged);
        openPreReloadTableResponse.setHasEffectedJobs(!context.getEffectedJobs().isEmpty());
        openPreReloadTableResponse.setHasDuplicatedColumns(!context.getDuplicatedColumns().isEmpty());

        return openPreReloadTableResponse;
    }

    public PreReloadTableResponse preProcessBeforeReloadWithFailFast(String project, String tableIdentity)
            throws Exception {
        aclEvaluate.checkProjectWritePermission(project);
        val context = calcReloadContext(project, tableIdentity, true);
        removeFusionModelBatchPart(project, context);
        return preProcessBeforeReloadWithContext(project, context, false);
    }

    private void removeFusionModelBatchPart(String project, ReloadTableContext context) {
        NDataModelManager manager = getManager(NDataModelManager.class, project);
        context.getRemoveAffectedModels().keySet()
                .removeIf(modelId -> manager.getDataModelDesc(modelId).fusionModelBatchPart());
    }

    private PreReloadTableResponse preProcessBeforeReloadWithContext(String project, ReloadTableContext context,
            boolean needDetails) {
        val result = new PreReloadTableResponse();
        result.setAddColumnCount(context.getAddColumns().size());
        result.setRemoveColumnCount(context.getRemoveColumns().size());
        result.setRemoveDimCount(context.getRemoveAffectedModels().values().stream()
                .map(AffectedModelContext::getDimensions).mapToLong(Set::size).sum());
        result.setDataTypeChangeColumnCount(context.getChangeTypeColumns().size());
        result.setTableCommentChanged(context.isTableCommentChanged());

        val schemaChanged = result.getAddColumnCount() > 0 || result.getRemoveColumnCount() > 0
                || result.getDataTypeChangeColumnCount() > 0;
        val originTable = getManager(NTableMetadataManager.class, project)
                .getTableDesc(context.getTableDesc().getIdentity());
        result.setSnapshotDeleted(schemaChanged && originTable.getLastSnapshotPath() != null);

        val affectedModels = Maps.newHashMap(context.getChangeTypeAffectedModels());
        affectedModels.putAll(context.getRemoveAffectedModels());
        result.setBrokenModelCount(affectedModels.values().stream().filter(AffectedModelContext::isBroken).count());

        // change type column also will remove measure when column type change
        long removeColumnAffectMeasureSum = context.getRemoveAffectedModels().values().stream()
                .map(AffectedModelContext::getMeasures).mapToLong(Set::size).sum();

        long changeColumnTypeAffectMeasureSum = context.getChangeTypeAffectedModels().values().stream()
                .map(AffectedModelContext::getMeasures).mapToLong(Set::size).sum();

        result.setRemoveMeasureCount(removeColumnAffectMeasureSum + changeColumnTypeAffectMeasureSum);
        result.setRemoveLayoutsCount(
                context.getRemoveAffectedModels().values().stream().mapToLong(m -> m.getUpdatedLayouts().size()).sum());
        result.setAddLayoutsCount(
                context.getRemoveAffectedModels().values().stream().mapToLong(m -> m.getAddLayouts().size()).sum());
        result.setRefreshLayoutsCount(context.getChangeTypeAffectedModels().values().stream()
                .mapToLong(m -> Sets
                        .difference(m.getUpdatedLayouts(),
                                context.getRemoveAffectedModel(m.getProject(), m.getModelId()).getUpdatedLayouts())
                        .size())
                .sum());

        result.setUpdateBaseIndexCount(context.getChangeTypeAffectedModels().values().stream().mapToInt(m -> {
            IndexPlan indexPlan = NIndexPlanManager.getInstance(getConfig(), m.getProject())
                    .getIndexPlan(m.getModelId());
            if (!indexPlan.getConfig().isBaseIndexAutoUpdate()) {
                return 0;
            }
            Set<Long> updateLayouts = Sets.newHashSet();
            updateLayouts.addAll(m.getUpdatedLayouts());
            updateLayouts.addAll(context.getRemoveAffectedModel(m.getProject(), m.getModelId()).getUpdatedLayouts());
            int updateBaseIndexCount = 0;
            if (updateLayouts.contains(indexPlan.getBaseAggLayoutId())) {
                updateBaseIndexCount++;
            }
            if (updateLayouts.contains(indexPlan.getBaseTableLayoutId())) {
                updateBaseIndexCount++;
            }
            return updateBaseIndexCount;
        }).sum());
        preProcessBeforeReloadDetailWithContext(result, context, needDetails);
        return result;
    }

    //Add the changed list
    private void preProcessBeforeReloadDetailWithContext(PreReloadTableResponse result, ReloadTableContext context,
            boolean needDetails) {
        if (!needDetails) {
            return;
        }
        result.getDetails().setAddedColumns(context.getAddColumns());
        result.getDetails().setRemovedColumns(context.getRemoveColumns());
        result.getDetails().setDataTypeChangedColumns(context.getChangeTypeColumns());

        val affectedModels = Maps.newHashMap(context.getChangeTypeAffectedModels());
        affectedModels.putAll(context.getRemoveAffectedModels());
        result.getDetails().setBrokenModels(affectedModels.values().stream().filter(AffectedModelContext::isBroken)
                .map(AffectedModelContext::getModelAlias).collect(Collectors.toSet()));

        // change type column also will remove measure when column type change
        result.getDetails().setRemovedMeasures(affectedModels.values().stream()
                .flatMap(model -> model.getMeasuresKey().stream()).collect(Collectors.toSet()));

        Collection<AffectedModelContext> removeAffectedModelsList = context.getRemoveAffectedModels().values();

        result.getDetails().setRemovedDimensions(removeAffectedModelsList.stream()
                .flatMap(model -> model.getDimensionsKey().stream()).collect(Collectors.toSet()));

        result.getDetails().setRemovedLayouts(
                removeAffectedModelsList.stream().filter(m -> !m.getUpdatedLayouts().isEmpty()).collect(Collectors
                        .toMap(AffectedModelContext::getModelAlias, AffectedModelContext::getUpdatedLayouts)));
        result.getDetails().setAddedLayouts(removeAffectedModelsList.stream().filter(m -> !m.getAddLayouts().isEmpty())
                .collect(Collectors.toMap(AffectedModelContext::getModelAlias, AffectedModelContext::getAddLayouts)));

        Map<String, Set<Long>> refreshedLayouts = Maps.newHashMap();
        context.getChangeTypeAffectedModels().values().forEach(m -> {
            Sets.SetView<Long> difference = Sets.difference(m.getUpdatedLayouts(),
                    context.getRemoveAffectedModel(m.getProject(), m.getModelId()).getUpdatedLayouts());
            if (!difference.isEmpty()) {
                refreshedLayouts.put(m.getModelAlias(), difference);
            }
        });
        result.getDetails().setRefreshedLayouts(refreshedLayouts);
    }

    public Pair<String, List<String>> reloadTable(String projectName, String tableIdentity, boolean needSample,
            int maxRows, boolean needBuild) {
        return reloadTable(projectName, tableIdentity, needSample, maxRows, needBuild, ExecutablePO.DEFAULT_PRIORITY,
                null);
    }

    public Pair<String, List<String>> reloadTable(String projectName, String tableIdentity, boolean needSample,
            int maxRows, boolean needBuild, int priority, String yarnQueue) {
        aclEvaluate.checkProjectWritePermission(projectName);
        UnitOfWorkParams<Pair<String, List<String>>> params = UnitOfWorkParams.<Pair<String, List<String>>> builder()
                .unitName(projectName).processor(() -> {
                    Pair<String, List<String>> pair = new Pair<>();
                    List<String> buildingJobs = innerReloadTable(projectName, tableIdentity, needBuild, null);
                    pair.setSecond(buildingJobs);
                    if (needSample && maxRows > 0) {
                        List<String> jobIds = tableSampleService.sampling(Sets.newHashSet(tableIdentity), projectName,
                                maxRows, priority, yarnQueue, null);
                        if (CollectionUtils.isNotEmpty(jobIds)) {
                            pair.setFirst(jobIds.get(0));
                        }
                    }
                    return pair;
                }).build();
        return EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(params);
    }

    public Pair<String, List<String>> reloadAWSTableCompatibleCrossAccount(String projectName,
            S3TableExtInfo tableExtInfo, boolean needSample, int maxRows, boolean needBuild, int priority,
            String yarnQueue) {
        aclEvaluate.checkProjectWritePermission(projectName);
        return EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            Pair<String, List<String>> pair = new Pair<>();
            List<String> buildingJobs = innerReloadTable(projectName, tableExtInfo.getName(), needBuild, tableExtInfo);
            pair.setSecond(buildingJobs);
            if (needSample && maxRows > 0) {
                List<String> jobIds = tableSampleService.sampling(Sets.newHashSet(tableExtInfo.getName()), projectName,
                        maxRows, priority, yarnQueue, null);
                if (CollectionUtils.isNotEmpty(jobIds)) {
                    pair.setFirst(jobIds.get(0));
                }
            }
            return pair;
        }, projectName);
    }

    @Transaction(project = 0)
    List<String> innerReloadTable(String projectName, String tableIdentity, boolean needBuild,
            S3TableExtInfo tableExtInfo) throws Exception {
        val tableManager = getManager(NTableMetadataManager.class, projectName);
        val originTable = tableManager.getTableDesc(tableIdentity);
        Preconditions.checkNotNull(originTable,
                String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), tableIdentity));

        List<String> jobs = Lists.newArrayList();
        val context = calcReloadContext(projectName, tableIdentity, true);
        if (null != tableExtInfo) {
            context.getTableExtDesc().addDataSourceProp(TableExtDesc.S3_ROLE_PROPERTY_KEY, tableExtInfo.getRoleArn());
            context.getTableExtDesc().addDataSourceProp(TableExtDesc.S3_ENDPOINT_KEY, tableExtInfo.getEndpoint());
        }
        if (!context.isNeedProcess()) {
            TableExtDesc tableExtDesc = tableManager.getTableExtIfExists(originTable);
            TableExtDesc targetExtDesc = context.getTableExtDesc();
            String roleArn = targetExtDesc.getDataSourceProps().get(TableExtDesc.S3_ROLE_PROPERTY_KEY);
            String endpoint = targetExtDesc.getDataSourceProps().get(TableExtDesc.S3_ENDPOINT_KEY);
            if (null != tableExtDesc) {
                tableManager.updateTableExt(tableIdentity, copyForWrite -> {
                    copyForWrite.addDataSourceProp(TableExtDesc.S3_ROLE_PROPERTY_KEY, roleArn);
                    copyForWrite.addDataSourceProp(TableExtDesc.S3_ENDPOINT_KEY, endpoint);
                });
                // refresh spark session and broadcast
                addAndBroadcastSparkSession(targetExtDesc.getRoleCredentialInfo());
            }
            return jobs;
        }

        val project = getManager(NProjectManager.class).getProject(projectName);
        Set<NDataModel> affectedModels = getAffectedModels(projectName, context);
        for (val model : affectedModels) {
            val jobId = updateBrokenModel(project, model, context, needBuild);
            if (StringUtils.isNotEmpty(jobId)) {
                jobs.add(jobId);
            }
        }
        mergeTable(projectName, context, true);
        for (val model : affectedModels) {
            val jobId = updateModelByReloadTable(project, model, context, needBuild);
            if (StringUtils.isNotEmpty(jobId)) {
                jobs.add(jobId);
            }
        }
        internalTableService.reloadInternalTableSchema(projectName, tableIdentity);

        mergeTable(projectName, context, false);
        return jobs;
    }

    public void addAndBroadcastSparkSession(TableExtDesc.RoleCredentialInfo roleCredentialInfo) {
        if (roleCredentialInfo == null) {
            return;
        }
        if (Strings.isNullOrEmpty(roleCredentialInfo.getEndpoint())
                && Strings.isNullOrEmpty(roleCredentialInfo.getRole())) {
            return;
        }
        if (KylinConfig.getInstanceFromEnv().useDynamicRoleCredentialInTable()) {
            SparderEnv.addCredential(roleCredentialInfo, SparderEnv.getSparkSession());
            EventBusFactory.getInstance()
                    .postAsync(new AddCredentialToSparkBroadcastEventNotifier(roleCredentialInfo.getType(),
                            roleCredentialInfo.getBucket(), roleCredentialInfo.getRole(),
                            roleCredentialInfo.getEndpoint(), roleCredentialInfo.getRegion()));
        }
    }

    private Set<NDataModel> getAffectedModels(String project, ReloadTableContext context) {
        String tableIdentity = context.getTableDesc().getIdentity();
        return NDataModelManager.getInstance(KylinConfig.readSystemKylinConfig(), project).listAllModels().stream() //
                .filter(model -> !model.isBroken() && model.getAllTables().stream().map(TableRef::getTableIdentity)
                        .anyMatch(tableIdentity::equalsIgnoreCase))
                .collect(Collectors.toSet());
    }

    private String updateBrokenModel(ProjectInstance project, NDataModel model, ReloadTableContext context,
            boolean needBuild) throws Exception {
        val removeAffectedModel = context.getRemoveAffectedModel(project.getName(), model.getId());
        val changeTypeAffectedModel = context.getChangeTypeAffectedModel(project.getName(), model.getId());

        if (!removeAffectedModel.isBroken()) {
            return null;
        }
        val projectName = project.getName();
        cleanIndexPlan(projectName, model, Lists.newArrayList(removeAffectedModel, changeTypeAffectedModel));

        OptRecManagerV2.getInstance(projectName).discardAll(model.getId());
        modelService.onUpdateBrokenModel(model, removeAffectedModel, changeTypeAffectedModel, projectName);

        val updatedModel = getManager(NDataModelManager.class, projectName).getDataModelDesc(model.getId());
        if (needBuild && !updatedModel.isBroken()) {
            final JobParam jobParam = new JobParam(model.getId(), getUsername());
            jobParam.setProject(projectName);
            return getManager(SourceUsageManager.class).licenseCheckWrap(projectName,
                    () -> getManager(JobManager.class, projectName).addIndexJob(jobParam));
        }
        return null;
    }

    private String updateModelByReloadTable(ProjectInstance project, NDataModel model, ReloadTableContext context,
            boolean needBuild) throws Exception {
        val projectName = project.getName();
        val baseIndexUpdater = indexPlanService.getIndexUpdateHelper(model, false);
        val removeAffected = context.getRemoveAffectedModel(project.getName(), model.getId());
        val changeTypeAffected = context.getChangeTypeAffectedModel(project.getName(), model.getId());
        if (removeAffected.isBroken()) {
            return null;
        }

        if (!(context.getRemoveColumns().isEmpty() && context.getChangeTypeColumns().isEmpty())) {
            cleanIndexPlan(projectName, model, Lists.newArrayList(removeAffected, changeTypeAffected));
        }

        OptRecManagerV2.getInstance(projectName).discardAll(model.getId());

        try {
            modelService.onUpdateDataModel(model, removeAffected, changeTypeAffected, projectName,
                    context.getTableDesc());
        } catch (TransactionException e) {
            Throwable root = ExceptionUtils.getRootCause(e) == null ? e : ExceptionUtils.getRootCause(e);
            String tableName = context.getTableDesc().getName();
            String columnNames = String.join(MsgPicker.getMsg().getCOMMA(), context.getChangeTypeColumns());
            if (root instanceof IllegalCCExpressionException) {
                throw new KylinException(INVALID_COMPUTED_COLUMN_EXPRESSION, String.format(Locale.ROOT,
                        MsgPicker.getMsg().getReloadTableCcRetry(), root.getMessage(), tableName, columnNames));
            } else if (root instanceof KylinException && ((KylinException) root).getErrorCodeProducer()
                    .getErrorCode() == ErrorCodeServer.SCD2_MODEL_UNKNOWN_EXCEPTION.getErrorCode()) {
                throw new KylinException(TABLE_RELOAD_MODEL_RETRY, tableName, columnNames, model.getAlias());
            }
            throw e;
        }

        if (CollectionUtils.isNotEmpty(changeTypeAffected.getUpdatedLayouts())) {
            indexPlanService.onReloadLayouts(projectName, changeTypeAffected.getModelId(),
                    changeTypeAffected.getUpdatedLayouts());
        }
        indexPlanService.onUpdateBaseIndex(baseIndexUpdater);
        if (CollectionUtils.isNotEmpty(removeAffected.getUpdatedLayouts())
                || CollectionUtils.isNotEmpty(changeTypeAffected.getUpdatedLayouts())) {
            if (needBuild) {
                final JobParam jobParam = new JobParam(model.getId(), getUsername());
                jobParam.setProject(projectName);
                return getManager(SourceUsageManager.class).licenseCheckWrap(projectName,
                        () -> getManager(JobManager.class, projectName).addIndexJob(jobParam));
            }
        }
        return null;
    }

    void cleanIndexPlan(String projectName, NDataModel model, List<AffectedModelContext> affectedModels) {
        val indexManager = getManager(NIndexPlanManager.class, projectName);
        for (AffectedModelContext affectedContext : affectedModels) {
            if (!affectedContext.getUpdateMeasureMap().isEmpty()) {
                getManager(NDataModelManager.class, projectName).updateDataModel(model.getId(),
                        modelDesc -> affectedContext.getUpdateMeasureMap().forEach((originalMeasureId, newMeasure) -> {
                            int maxMeasureId = modelDesc.getAllMeasures().stream().map(NDataModel.Measure::getId)
                                    .mapToInt(i -> i).max().orElse(NDataModel.MEASURE_ID_BASE - 1);
                            Optional<NDataModel.Measure> originalMeasureOptional = modelDesc.getAllMeasures().stream()
                                    .filter(measure -> measure.getId() == originalMeasureId).findAny();
                            if (originalMeasureOptional.isPresent()) {
                                NDataModel.Measure originalMeasure = originalMeasureOptional.get();
                                originalMeasure.setTomb(true);
                                maxMeasureId++;
                                newMeasure.setId(maxMeasureId);
                                modelDesc.getAllMeasures().add(newMeasure);
                            }
                        }));
            }
            indexManager.updateIndexPlan(model.getId(), affectedContext::shrinkIndexPlan);
        }
    }

    void mergeTable(String projectName, ReloadTableContext context, boolean keepTomb) {
        val tableManager = getManager(NTableMetadataManager.class, projectName);
        val originTable = tableManager.getTableDesc(context.getTableDesc().getIdentity());
        val originTableExt = tableManager.getTableExtIfExists(originTable);
        context.getTableDesc().setMvcc(originTable.getMvcc());
        if (originTableExt != null && keepTomb) {
            val validStats = originTableExt.getAllColumnStats().stream()
                    .filter(stats -> !context.getRemoveColumns().contains(stats.getColumnName()))
                    .collect(Collectors.toList());
            context.getTableExtDesc().setColumnStats(validStats);
            context.getTableExtDesc().setOriginalSize(originTableExt.getOriginalSize());

            val originCols = originTableExt.getAllColumnStats().stream().map(TableExtDesc.ColumnStats::getColumnName)
                    .collect(Collectors.toList());
            val indexMapping = Maps.<Integer, Integer> newHashMap();
            int index = 0;
            for (ColumnDesc column : context.getTableDesc().getColumns()) {
                int oldIndex = originCols.indexOf(column.getName());
                indexMapping.put(index, oldIndex);
                index++;
            }
            context.getTableExtDesc().setSampleRows(originTableExt.getSampleRows().stream().map(row -> {
                val result = new String[indexMapping.size()];
                indexMapping.forEach((key, value) -> {
                    if (value != -1) {
                        result[key] = row[value];
                    } else {
                        result[key] = "";
                    }
                });
                return result;
            }).collect(Collectors.toList()));
            context.getTableExtDesc().setMvcc(originTable.getMvcc());
        }

        TableDesc loadDesc = context.getTableDesc();
        if (keepTomb) {
            val copy = tableManager.copy(originTable);
            val originColMap = Stream.of(copy.getColumns())
                    .collect(Collectors.toMap(ColumnDesc::getName, Function.identity()));
            val newColMap = Stream.of(context.getTableDesc().getColumns())
                    .collect(Collectors.toMap(ColumnDesc::getName, Function.identity()));
            for (String changedColumn : context.getChangedColumns()) {
                originColMap.put(changedColumn, newColMap.get(changedColumn));
            }
            for (String addColumn : context.getAddColumns()) {
                originColMap.put(addColumn, newColMap.get(addColumn));
            }
            copy.setColumns(originColMap.values().stream()
                    .sorted(Comparator.comparing(col -> Integer.parseInt(col.getId()))).toArray(ColumnDesc[]::new));
            loadDesc = copy;
        }
        int idx = 1;
        for (ColumnDesc column : loadDesc.getColumns()) {
            column.setId(idx + "");
            idx++;
        }
        cleanSnapshot(context, loadDesc, originTable, projectName);
        loadTableToProject(loadDesc, context.getTableExtDesc(), projectName);
    }

    void cleanSnapshot(ReloadTableContext context, TableDesc targetTable, TableDesc originTable, String projectName) {
        if (context.isChanged(originTable)) {
            val tableIdentity = targetTable.getIdentity();
            List<AbstractExecutable> stopJobs = stopAndGetSnapshotJobs(projectName, tableIdentity);

            var snapshotBuilt = false;
            if (stopJobs.isEmpty()) {
                val execManager = getManager(ExecutableManager.class, projectName);
                val jobInfoList = execManager.fetchJobsByTypesAndStates(projectName,
                        Lists.newArrayList(SNAPSHOT_BUILD.name(), SNAPSHOT_REFRESH.name()),
                        Lists.newArrayList(tableIdentity), Lists.newArrayList(ExecutableState.getFinalStates()));
                snapshotBuilt = !jobInfoList.isEmpty();
            }

            if (!stopJobs.isEmpty() || snapshotBuilt || targetTable.getLastSnapshotPath() != null) {
                targetTable.deleteSnapshot(true);
            } else {
                targetTable.copySnapshotFrom(originTable);
            }
        } else {
            targetTable.copySnapshotFrom(originTable);
        }
    }

    private void checkNewColumn(String project, String tableName, Set<String> addColumns) {
        Multimap<String, String> duplicatedColumns = getDuplicatedColumns(project, tableName, addColumns);
        if (!duplicatedColumns.isEmpty()) {
            Map.Entry<String, String> entry = duplicatedColumns.entries().iterator().next();
            throw new KylinException(DUPLICATED_COLUMN_NAME,
                    MsgPicker.getMsg().getTableReloadAddColumnExist(entry.getKey(), entry.getValue()));
        }
    }

    private Multimap<String, String> getDuplicatedColumns(String project, String tableName, Set<String> addColumns) {
        Multimap<String, String> duplicatedColumns = HashMultimap.create();
        List<NDataModel> models = NDataModelManager.getInstance(KylinConfig.readSystemKylinConfig(), project)
                .listAllModels();
        for (NDataModel model : models) {
            if (model.isBroken()) {
                continue;
            }
            for (ComputedColumnDesc cc : model.getComputedColumnDescs()) {
                if (addColumns.contains(cc.getColumnName())) {
                    duplicatedColumns.put(tableName, cc.getColumnName());
                }
            }
        }
        return duplicatedColumns;
    }

    private void checkEffectedJobs(TableDesc newTableDesc, boolean isOnlyAddCol) {
        List<String> targetSubjectList = getEffectedJobs(newTableDesc, JobInfoEnum.JOB_TARGET_SUBJECT);
        if (CollectionUtils.isNotEmpty(targetSubjectList) && !isOnlyAddCol) {
            throw new KylinException(TABLE_RELOAD_HAVING_NOT_FINAL_JOB,
                    StringUtils.join(targetSubjectList.iterator(), ","));
        }
    }

    private Set<String> getEffectedJobIds(TableDesc newTableDesc) {
        return Sets.newHashSet(getEffectedJobs(newTableDesc, JobInfoEnum.JOB_ID));
    }

    private List<String> getEffectedJobs(TableDesc newTableDesc, JobInfoEnum jobInfoType) {
        val executableManager = ExecutableManager.getInstance(KylinConfig.readSystemKylinConfig(),
                newTableDesc.getProject());
        val notFinalStateJobs = executableManager.getExecutablePOsByStatus(ExecutableState.getNotFinalStates()).stream()
                .map(job -> executableManager.fromPO(job)).collect(Collectors.toList());

        List<String> effectedJobs = Lists.newArrayList();
        notFinalStateJobs.forEach(job -> {
            if (JobTypeEnum.TABLE_SAMPLING == job.getJobType()) {
                if (newTableDesc.getIdentity().equalsIgnoreCase(job.getTargetSubject())) {
                    String jobId = JobInfoEnum.JOB_ID == jobInfoType ? job.getId() : job.getTargetSubject();
                    effectedJobs.add(jobId);
                }
            } else {
                try {
                    NDataModel model = modelService.onGetModelById(job.getTargetSubject(), newTableDesc.getProject());
                    if (!model.isBroken() && model.getAllTables().stream().map(TableRef::getTableIdentity)
                            .anyMatch(s -> s.equalsIgnoreCase(newTableDesc.getIdentity()))) {
                        effectedJobs.add(JobInfoEnum.JOB_ID == jobInfoType ? job.getId() : model.getAlias());
                    }
                } catch (KylinException e) {
                    logger.warn("Get model by Job target subject failed!", e);
                }
            }
        });
        return effectedJobs;
    }

    private ReloadTableContext calcReloadContext(String project, String tableIdentity, boolean failFast)
            throws Exception {
        val context = new ReloadTableContext();
        UserGroupInformation ugi = KerberosLoginManager.getInstance().getProjectUGI(project);
        val tableMeta = ugi.doAs(new PrivilegedExceptionAction<Pair<TableDesc, TableExtDesc>>() {
            @Override
            public Pair<TableDesc, TableExtDesc> run() throws Exception {
                return extractTableMeta(new String[] { tableIdentity }, project).get(0);
            }
        });
        TableDesc newTableDesc = new TableDesc(tableMeta.getFirst());
        context.setTableDesc(newTableDesc);
        context.setTableExtDesc(tableMeta.getSecond());

        handleExcludedColumns(project, context, newTableDesc, tableIdentity);

        TableDesc originTableDesc = getManager(NTableMetadataManager.class, project).getTableDesc(tableIdentity);
        val collector = Collectors.toMap(ColumnDesc::getName, col -> Pair.newPair(col.getDatatype(), col.getComment()));
        val diff = Maps.difference(Stream.of(originTableDesc.getColumns()).collect(collector),
                Stream.of(newTableDesc.getColumns()).collect(collector));
        val dataTypeCollector = Collectors.toMap(ColumnDesc::getName, ColumnDesc::getDatatype);
        val originCols = Stream.of(originTableDesc.getColumns()).collect(dataTypeCollector);
        val newCols = Stream.of(newTableDesc.getColumns()).collect(dataTypeCollector);
        val dataTypeDiff = Maps.difference(newCols, originCols);

        assert diff.entriesDiffering().keySet().containsAll(dataTypeDiff.entriesDiffering().keySet());

        context.setAddColumns(dataTypeDiff.entriesOnlyOnLeft().keySet());
        context.setRemoveColumns(dataTypeDiff.entriesOnlyOnRight().keySet());
        context.setChangedColumns(diff.entriesDiffering().keySet());
        context.setChangeTypeColumns(dataTypeDiff.entriesDiffering().keySet());
        context.setTableCommentChanged(
                !Objects.equals(originTableDesc.getTableComment(), newTableDesc.getTableComment()));

        if (!context.isNeedProcess()) {
            return context;
        }

        if (failFast) {
            checkNewColumn(project, newTableDesc.getIdentity(), Sets.newHashSet(context.getAddColumns()));
            checkEffectedJobs(newTableDesc, context.isOnlyAddCols());
        } else {
            Set<String> duplicatedColumnsSet = Sets.newHashSet();
            Multimap<String, String> duplicatedColumns = getDuplicatedColumns(project, newTableDesc.getIdentity(),
                    Sets.newHashSet(context.getAddColumns()));
            for (Map.Entry<String, String> entry : duplicatedColumns.entries()) {
                duplicatedColumnsSet.add(entry.getKey() + "." + entry.getValue());
            }
            context.setDuplicatedColumns(duplicatedColumnsSet);
            context.setEffectedJobs(getEffectedJobIds(newTableDesc));
        }

        if (context.isOnlyAddCols()) {
            return context;
        }

        val dependencyGraph = SchemaUtil.dependencyGraph(project, tableIdentity);
        Map<String, Set<Pair<NDataModel.Measure, NDataModel.Measure>>> suitableColumnTypeChangedMeasuresMap //
                = getSuitableColumnTypeChangedMeasures(dependencyGraph, project, originTableDesc,
                        dataTypeDiff.entriesDiffering());

        BiFunction<Set<String>, Boolean, Map<String, AffectedModelContext>> toAffectedModels = (cols, isDelete) -> {
            Set<SchemaNode> affectedNodes = Sets.newHashSet();
            val columnMap = Arrays.stream(originTableDesc.getColumns())
                    .collect(Collectors.toMap(ColumnDesc::getName, Function.identity()));
            cols.forEach(colName -> {
                if (columnMap.get(colName) != null) {
                    affectedNodes.addAll(
                            Graphs.reachableNodes(dependencyGraph, SchemaNode.ofTableColumn(columnMap.get(colName))));
                }
            });
            val nodesMap = affectedNodes.stream().filter(SchemaNode::isModelNode)
                    .collect(Collectors.groupingBy(SchemaNode::getSubject, Collectors.toSet()));
            Map<String, AffectedModelContext> modelContexts = Maps.newHashMap();
            nodesMap.forEach((key, nodes) -> {
                val indexPlan = NIndexPlanManager.getInstance(KylinConfig.readSystemKylinConfig(), project)
                        .getIndexPlanByModelAlias(key);
                Set<Pair<NDataModel.Measure, NDataModel.Measure>> updateMeasures = Sets.newHashSet();
                if (!isDelete) {
                    updateMeasures = suitableColumnTypeChangedMeasuresMap.getOrDefault(key, updateMeasures);
                }

                val modelContext = new AffectedModelContext(project, indexPlan, nodes, updateMeasures, isDelete);
                modelContexts.put(indexPlan.getUuid(), modelContext);
            });
            return modelContexts;
        };
        context.setRemoveAffectedModels(toAffectedModels.apply(context.getRemoveColumns(), true));
        context.setChangeTypeAffectedModels(toAffectedModels.apply(context.getChangeTypeColumns(), false));

        return context;
    }

    /**
     * Handle excluded column when reloading table.
     * 1. Add column to the excluded table, the added column will be treated as an excluded column automatically.
     * 2. If the last un-excluded column is removed, this table will be treated as an excluded table.
     */
    private void handleExcludedColumns(String project, ReloadTableContext context, TableDesc newTable,
            String tableIdentity) {
        NTableMetadataManager tableManager = getManager(NTableMetadataManager.class, project);
        TableDesc originTable = tableManager.getTableDesc(tableIdentity);
        TableExtDesc originExt = tableManager.getTableExtIfExists(originTable);
        if (originExt == null) {
            return;
        }

        boolean excluded = originExt.isExcluded();
        context.getTableExtDesc().setExcluded(excluded);
        if (excluded) {
            context.getTableExtDesc().getExcludedColumns().clear();
        } else {
            Set<String> excludedColumns = Sets.newHashSet(originExt.getExcludedColumns());
            Set<String> newColNameSet = Arrays.stream(newTable.getColumns()).map(ColumnDesc::getName)
                    .collect(Collectors.toSet());
            excludedColumns.removeIf(col -> !newColNameSet.contains(col));
            logger.debug("reserved excluded columns are: {}", excludedColumns);
            if (newColNameSet.equals(excludedColumns)) {
                context.getTableExtDesc().setExcluded(true);
                context.getTableExtDesc().getExcludedColumns().clear();
                logger.debug("Set the table to excluded table for all columns is excluded.");
            } else {
                context.getTableExtDesc().getExcludedColumns().addAll(excludedColumns);
            }
        }
    }

    /**
     * get suitable measures when column type change
     * and remove old measure add new measure with suitable function return type
     * @param dependencyGraph
     * @param project
     * @param tableDesc
     * @param changeTypeDifference
     * @return
     */
    private Map<String, Set<Pair<NDataModel.Measure, NDataModel.Measure>>> getSuitableColumnTypeChangedMeasures(
            Graph<SchemaNode> dependencyGraph, String project, TableDesc tableDesc,
            Map<String, MapDifference.ValueDifference<String>> changeTypeDifference) {
        Map<String, Set<Pair<NDataModel.Measure, NDataModel.Measure>>> result = Maps.newHashMap();

        NDataModelManager dataModelManager = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), project);

        val columnMap = Arrays.stream(tableDesc.getColumns())
                .collect(Collectors.toMap(ColumnDesc::getName, Function.identity()));
        for (val value : changeTypeDifference.entrySet()) {
            val colName = value.getKey();
            val colDiff = value.getValue();

            Graphs.reachableNodes(dependencyGraph, SchemaNode.ofTableColumn(columnMap.get(colName))).stream()
                    .filter(node -> node.getType() == SchemaNodeType.MODEL_MEASURE).forEach(node -> {
                        String modelAlias = node.getSubject();
                        String measureId = node.getDetail();

                        NDataModel modelDesc = dataModelManager.getDataModelDescByAlias(modelAlias);
                        if (modelDesc != null) {
                            NDataModel.Measure measure = modelDesc.getEffectiveMeasures()
                                    .get(Integer.parseInt(measureId));
                            if (measure != null) {
                                int originalMeasureId = measure.getId();

                                FunctionDesc originalFunction = measure.getFunction();

                                String newColumnType = colDiff.leftValue();

                                boolean datatypeSuitable = originalFunction
                                        .isDatatypeSuitable(DataType.getType(newColumnType));

                                if (datatypeSuitable) {
                                    // update measure function's return type
                                    FunctionDesc newFunction = FunctionDesc.newInstance(
                                            originalFunction.getExpression(),
                                            Lists.newArrayList(originalFunction.getParameters()),
                                            FunctionDesc.proposeReturnType(originalFunction.getExpression(),
                                                    newColumnType));

                                    NDataModel.Measure newMeasure = new NDataModel.Measure();

                                    newMeasure.setFunction(newFunction);
                                    newMeasure.setName(measure.getName());
                                    newMeasure.setColumn(measure.getColumn());
                                    newMeasure.setComment(measure.getComment());

                                    Set<Pair<NDataModel.Measure, NDataModel.Measure>> measureList = result
                                            .getOrDefault(modelAlias, new HashSet<>());

                                    measureList.add(Pair.newPair(measure, newMeasure));

                                    result.put(modelAlias, measureList);
                                }
                            }
                        }
                    });
        }
        return result;
    }

    boolean isSqlContainsColumns(String sql, String reloadTable, Set<String> cols) {
        if (sql == null) {
            sql = "";
        }
        sql = sql.toUpperCase(Locale.ROOT);
        if (reloadTable.contains(".")) {
            reloadTable = reloadTable.split("\\.")[1];
        }
        for (String col : cols) {
            col = col.toUpperCase(Locale.ROOT);
            String colWithTableName = reloadTable + "." + col;
            if (sql.contains(colWithTableName) || !sql.contains("." + col) && sql.contains(col)) {
                return true;
            }
        }
        return false;
    }

    public Set<String> getLoadedDatabases(String project) {
        aclEvaluate.checkProjectReadPermission(project);
        NTableMetadataManager tableManager = getManager(NTableMetadataManager.class, project);
        List<TableDesc> tables = tableManager.listAllTables();
        Set<String> loadedDatabases = new HashSet<>();
        boolean streamingEnabled = getConfig().isStreamingEnabled();
        tables.stream().filter(table -> table.isAccessible(streamingEnabled))
                .forEach(table -> loadedDatabases.add(table.getDatabase()));
        return loadedDatabases;
    }

    public NInitTablesResponse getProjectTables(String project, String table, int offset, int limit,
            boolean withExcluded, boolean useHiveDatabase, List<Integer> sourceType) throws Exception {
        TableDescRequest internalTableDescRequest = new TableDescRequest(project, table, offset, limit, withExcluded,
                sourceType);
        return getProjectTables(internalTableDescRequest, useHiveDatabase);
    }

    public NInitTablesResponse getProjectTables(TableDescRequest tableDescRequest, boolean useHiveDatabase)
            throws Exception {
        String project = tableDescRequest.getProject();
        aclEvaluate.checkProjectReadPermission(project);
        NInitTablesResponse response = new NInitTablesResponse();
        logger.debug("only get project tables of excluded: {}", tableDescRequest.isWithExcluded());

        Pair<String, String> databaseAndTable = checkDatabaseAndTable(tableDescRequest.getTable());
        String exceptDatabase = databaseAndTable.getFirst();
        String table = databaseAndTable.getSecond();
        String notAllowedModifyTableName = table;

        Collection<String> databases = useHiveDatabase ? getSourceDbNames(project) : getLoadedDatabases(project);
        val projectInstance = getManager(NProjectManager.class).getProject(project);
        List<String> tableFilterList = DataSourceState.getInstance().getHiveFilterList(projectInstance);
        for (String database : databases) {
            if ((exceptDatabase != null && !exceptDatabase.equalsIgnoreCase(database))
                    || (!tableFilterList.isEmpty() && !tableFilterList.contains(database))) {
                continue;
            }
            // we may temporarily change the table name, but later to change back
            // Avoid affecting the next loop and causing logic errors
            if (exceptDatabase == null && database.toLowerCase(Locale.ROOT).contains(table.toLowerCase(Locale.ROOT))) {
                table = "";
            }
            tableDescRequest.setDatabase(database);
            tableDescRequest.setTable(table);
            Pair<List<?>, Integer> objWithActualSize = new Pair<>();
            if (tableDescRequest.getSourceType().isEmpty()) {
                // This means request api for showProjectTableNames
                List<TableNameResponse> hiveTableNameResponses = getHiveTableNameResponses(project, database, table);
                objWithActualSize.setFirst(hiveTableNameResponses);
                objWithActualSize.setSecond(hiveTableNameResponses.size());
            } else {
                int returnTableSize = calculateTableSize(tableDescRequest.getOffset(), tableDescRequest.getLimit());
                Pair<List<TableDesc>, Integer> tableDescWithActualSize = getTableDesc(tableDescRequest,
                        returnTableSize);
                objWithActualSize.setFirst(tableDescWithActualSize.getFirst());
                objWithActualSize.setSecond(tableDescWithActualSize.getSecond());
            }
            table = notAllowedModifyTableName;
            List<?> tablePage = PagingUtil.cutPage(objWithActualSize.getFirst(), tableDescRequest.getOffset(),
                    tableDescRequest.getLimit());
            if (!tablePage.isEmpty()) {
                response.putDatabase(database, objWithActualSize.getSecond(), tablePage);
            }
        }
        return response;
    }

    public Pair<String[], Set<String>> classifyDbTables(String project, String[] tables) throws Exception {
        HashMap<String, Set<String>> map = new HashMap<>();
        Set<String> dbs = new HashSet<>(getSourceDbNames(project));
        List<String> existed = new ArrayList<>();
        Set<String> failed = new HashSet<>();
        for (String str : tables) {
            String db = null;
            String table = null;
            if (str.contains(".")) {
                db = str.split("\\.", 2)[0].trim().toUpperCase(Locale.ROOT);
                table = str.split("\\.", 2)[1].trim().toUpperCase(Locale.ROOT);
            } else {
                db = str.toUpperCase(Locale.ROOT);
            }
            if (!dbs.contains(db)) {
                failed.add(str);
                continue;
            }
            if (table != null) {
                Set<String> tbs = map.get(db);
                if (tbs == null) {
                    tbs = new HashSet<>(getSourceTableNames(project, db, null));
                    map.put(db, tbs);
                }
                if (!tbs.contains(table)) {
                    failed.add(str);
                    continue;
                }
            }
            existed.add(str);
        }
        return new Pair<>(existed.toArray(new String[0]), failed);
    }

    public List<TableNameResponse> getHiveTableNameResponses(String project, String database, final String table)
            throws Exception {
        if (Boolean.TRUE.equals(KylinConfig.getInstanceFromEnv().getLoadHiveTablenameEnabled())) {
            return getTableNameResponsesInCache(project, database, table);
        } else {
            return getTableNameResponses(project, database, table);
        }
    }

    public List<TableNameResponse> getTableNameResponsesInCache(String project, String database, final String table) {
        aclEvaluate.checkProjectReadPermission(project);
        List<TableNameResponse> responses = new ArrayList<>();
        NTableMetadataManager tableManager = getManager(NTableMetadataManager.class, project);
        List<String> tables = DataSourceState.getInstance().getTables(project, database);
        for (String tableName : tables) {
            if (StringUtils.isEmpty(table)
                    || tableName.toUpperCase(Locale.ROOT).contains(table.toUpperCase(Locale.ROOT))) {
                TableNameResponse response = new TableNameResponse();
                String tableNameWithDB = database + "." + tableName;
                checkTableExistOrLoad(response, tableManager.getTableDesc(tableNameWithDB));
                response.setTableName(tableName);
                responses.add(response);
            }
        }
        return responses;
    }

    public void checkTableExistOrLoad(TableNameResponse response, TableDesc tableDesc) {
        if (Objects.isNull(tableDesc)) {
            return;
        }
        // Table name already exists
        if (Objects.nonNull(tableDesc.getKafkaConfig())) {
            // The Kafka table has the same table name, A table with the same name already exists
            response.setExisted(true);
        } else {
            // The Hive table has the same table name, A table with the same name has been loaded
            response.setLoaded(true);
        }
    }

    public void loadHiveTableNameToCache() throws Exception {
        DataSourceState.getInstance().loadAllSourceInfoToCache();
    }

    public NHiveTableNameResponse loadProjectHiveTableNameToCacheImmediately(String project, boolean force) {
        aclEvaluate.checkProjectWritePermission(project);
        return DataSourceState.getInstance().loadAllSourceInfoToCacheForced(project, force);
    }

    public void importSSBDataBase() {
        aclEvaluate.checkIsGlobalAdmin();
        if (checkSSBDataBase()) {
            return;
        }
        synchronized (TableService.class) {
            if (checkSSBDataBase()) {
                return;
            }
            CliCommandExecutor exec = new CliCommandExecutor();
            val patternedLogger = new BufferedLogger(logger);
            val sampleSh = checkSSBEnv();
            try {
                exec.execute(sampleSh, patternedLogger);
            } catch (ShellException e) {
                throw new KylinException(FAILED_IMPORT_SSB_DATA, SSB_ERROR_MSG, e);
            }
            if (!checkSSBDataBase()) {
                throw new KylinException(FAILED_IMPORT_SSB_DATA, SSB_ERROR_MSG);
            }
        }
    }

    private String checkSSBEnv() {
        var home = KylinConfigBase.getKylinHome();
        if (!StringUtils.isEmpty(home) && !home.endsWith("/")) {
            home = home + "/";
        }
        val sampleSh = String.format(Locale.ROOT, "%sbin/sample.sh", home);
        checkFile(sampleSh);
        val ssbSh = String.format(Locale.ROOT, "%stool/ssb/create_sample_ssb_tables.sql", home);
        checkFile(ssbSh);
        val customer = String.format(Locale.ROOT, "%stool/ssb/data/SSB.CUSTOMER.csv", home);
        checkFile(customer);
        val dates = String.format(Locale.ROOT, "%stool/ssb/data/SSB.DATES.csv", home);
        checkFile(dates);
        val lineorder = String.format(Locale.ROOT, "%stool/ssb/data/SSB.LINEORDER.csv", home);
        checkFile(lineorder);
        val part = String.format(Locale.ROOT, "%stool/ssb/data/SSB.PART.csv", home);
        checkFile(part);
        val supplier = String.format(Locale.ROOT, "%stool/ssb/data/SSB.SUPPLIER.csv", home);
        checkFile(supplier);
        return sampleSh;
    }

    private void checkFile(String fileName) {
        File file = new File(fileName);
        if (!file.exists() || !file.isFile()) {
            throw new KylinException(FILE_NOT_EXIST,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getFileNotExist(), fileName));
        }
    }

    public boolean checkSSBDataBase() {
        aclEvaluate.checkIsGlobalAdmin();
        if (KylinConfig.getInstanceFromEnv().isUTEnv()) {
            return true;
        }
        ISourceMetadataExplorer explr = SourceFactory.getSparkSource().getSourceMetadataExplorer();
        try {
            val result = explr.listTables("SSB").stream().map(str -> str.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            return result
                    .containsAll(Sets.newHashSet("CUSTOMER", "DATES", "LINEORDER", "P_LINEORDER", "PART", "SUPPLIER"));
        } catch (Exception e) {
            logger.warn("check ssb error", e);
            return false;
        }
    }

    public TableRefresh refreshSingleCatalogCache(Map refreshRequest) {
        TableRefresh result = new TableRefresh();
        val message = MsgPicker.getMsg();
        List<String> tables = (List<String>) refreshRequest.get("tables");
        List<String> refreshed = Lists.newArrayList();
        List<String> failed = Lists.newArrayList();
        tables.forEach(table -> refreshTable(table, refreshed, failed));

        if (failed.isEmpty()) {
            result.setCode(KylinException.CODE_SUCCESS);
        } else {
            result.setCode(KylinException.CODE_UNDEFINED);
            result.setMsg(message.getTableRefreshNotfound());
        }
        result.setRefreshed(refreshed);
        result.setFailed(failed);
        return result;
    }

    public void refreshTable(String table, List<String> refreshed, List<String> failed) {
        try {
            PushDownUtil.trySimplyExecute("REFRESH TABLE " + table, null);
            refreshed.add(table);
        } catch (Exception e) {
            failed.add(table);
            logger.error("fail to refresh spark cached table", e);
        }
    }

    public TableRefreshAll refreshAllCatalogCache(final HttpServletRequest request) {

        val message = MsgPicker.getMsg();
        List<ServerInfoResponse> servers = clusterManager.getQueryServers();
        TableRefreshAll result = new TableRefreshAll();
        result.setCode(KylinException.CODE_SUCCESS);
        StringBuilder msg = new StringBuilder();
        List<TableRefresh> nodes = new ArrayList<>();

        servers.forEach(server -> {
            String host = server.getHost();
            String url = "http://" + host + REFRESH_SINGLE_CATALOG_PATH;
            try {
                EnvelopeResponse response = generateTaskForRemoteHost(request, url);
                if (StringUtils.isNotBlank(response.getMsg())) {
                    msg.append(host + ":" + response.getMsg() + ";");
                }
                if (response.getCode().equals(KylinException.CODE_UNDEFINED)) {
                    result.setCode(KylinException.CODE_UNDEFINED);
                }
                if (response.getData() != null) {
                    TableRefresh data = JsonUtil.convert(response.getData(), TableRefresh.class);
                    data.setServer(host);
                    nodes.add(data);
                }
            } catch (Exception e) {
                throw new KylinException(FAILED_REFRESH_CATALOG_CACHE, message.getTableRefreshError(), e);
            }
        });
        if (!nodes.isEmpty()) {
            result.setNodes(nodes);
        }
        result.setMsg(msg.toString());
        return result;
    }

    public List<TableDesc> getTablesOfModel(String project, String modelAlias) {
        aclEvaluate.checkProjectReadPermission(project);
        NDataModel model = getManager(NDataModelManager.class, project).getDataModelDescByAlias(modelAlias);
        if (Objects.isNull(model)) {
            throw new KylinException(MODEL_NAME_NOT_EXIST, modelAlias);
        }
        List<String> usedTableNames = Lists.newArrayList();
        usedTableNames.add(model.getRootFactTableName());
        usedTableNames.addAll(model.getJoinTables().stream().map(JoinTableDesc::getTable).collect(Collectors.toList()));
        return usedTableNames.stream().map(getManager(NTableMetadataManager.class, project)::getTableDesc)
                .filter(Objects::nonNull).collect(Collectors.toList());
    }

    public TableExtDesc getOrCreateTableExt(String project, TableDesc t) {
        return getManager(NTableMetadataManager.class, project).getOrCreateTableExt(t);
    }
}
