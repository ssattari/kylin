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

import static org.apache.kylin.common.exception.code.ErrorCodeServer.SIMPLIFIED_MEASURES_MISSING_ID;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.dialect.CalciteSqlDialect;
import org.apache.calcite.sql.util.SqlVisitor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.QueryContext;
import org.apache.kylin.common.exception.CommonErrorCode;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.ServerErrorCode;
import org.apache.kylin.common.exception.code.ErrorCodeServer;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.ModifyTableNameSqlVisitor;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.common.util.ThreadUtil;
import org.apache.kylin.engine.spark.utils.ComputedColumnEvalUtil;
import org.apache.kylin.guava30.shaded.common.base.Throwables;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableBiMap;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.manager.JobManager;
import org.apache.kylin.job.model.JobParam;
import org.apache.kylin.metadata.cube.cuboid.NAggregationGroup;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.metadata.cube.model.LayoutEntity;
import org.apache.kylin.metadata.cube.model.NDataSegmentManager;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.cube.model.RuleBasedIndex;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.JoinDesc;
import org.apache.kylin.metadata.model.JoinTableDesc;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.MultiPartitionDesc;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModel.Measure;
import org.apache.kylin.metadata.model.NDataModel.NamedColumn;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.NonEquiJoinCondition;
import org.apache.kylin.metadata.model.ParameterDesc;
import org.apache.kylin.metadata.model.PartitionDesc;
import org.apache.kylin.metadata.model.SegmentRange;
import org.apache.kylin.metadata.model.Segments;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.model.UpdateImpact;
import org.apache.kylin.metadata.model.tool.CalciteParser;
import org.apache.kylin.metadata.model.util.ComputedColumnUtil;
import org.apache.kylin.metadata.model.util.ExpandableMeasureUtil;
import org.apache.kylin.metadata.model.util.scd2.SCD2CondChecker;
import org.apache.kylin.metadata.model.util.scd2.SCD2SqlConverter;
import org.apache.kylin.metadata.model.util.scd2.Scd2Simplifier;
import org.apache.kylin.metadata.model.util.scd2.SimplifiedJoinDesc;
import org.apache.kylin.metadata.model.util.scd2.SimplifiedJoinTableDesc;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.recommendation.ref.OptRecManagerV2;
import org.apache.kylin.query.engine.QueryExec;
import org.apache.kylin.query.relnode.OlapContext;
import org.apache.kylin.query.util.PushDownUtil;
import org.apache.kylin.query.util.QueryUtil;
import org.apache.kylin.rest.request.ModelRequest;
import org.apache.kylin.rest.response.BuildIndexResponse;
import org.apache.kylin.rest.response.SimplifiedMeasure;
import org.apache.kylin.rest.util.AclPermissionUtil;
import org.apache.kylin.rest.util.SCD2SimplificationConvertUtil;
import org.apache.kylin.source.SourceFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.val;
import lombok.var;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ModelSemanticHelper extends BasicService {

    private static final Logger logger = LoggerFactory.getLogger(ModelSemanticHelper.class);
    private final ExpandableMeasureUtil expandableMeasureUtil = new ExpandableMeasureUtil((model, ccDesc) -> {
        String ccExpression = PushDownUtil.massageComputedColumn(model, model.getProject(), ccDesc,
                AclPermissionUtil.createAclInfo(model.getProject(), getCurrentUserGroups()));
        ccDesc.setInnerExpression(ccExpression);
        ComputedColumnEvalUtil.evaluateExprAndType(model, ccDesc);
    });

    public NDataModel deepCopyModel(NDataModel originModel) {
        NDataModel nDataModel;
        try {
            nDataModel = JsonUtil.readValue(JsonUtil.writeValueAsIndentString(originModel), NDataModel.class);
            nDataModel.setJoinTables(SCD2SimplificationConvertUtil.deepCopyJoinTables(originModel.getJoinTables()));
        } catch (IOException e) {
            ThreadUtil.warnKylinStackTrace("Parse json failed...\n");
            throw new KylinException(CommonErrorCode.FAILED_PARSE_JSON, e);
        }
        return nDataModel;
    }

    public NDataModel convertToDataModel(ModelRequest modelRequest) {
        List<SimplifiedMeasure> simplifiedMeasures = modelRequest.getSimplifiedMeasures();
        NDataModel dataModel;
        try {
            dataModel = JsonUtil.deepCopy(modelRequest, NDataModel.class);
            dataModel.setComputedColumnDescs(ComputedColumnUtil.deepCopy(modelRequest.getComputedColumnDescs()));
        } catch (IOException e) {
            ThreadUtil.warnKylinStackTrace("Parse json failed...\n");
            throw new KylinException(CommonErrorCode.FAILED_PARSE_JSON, e);
        }

        Map<String, TableDesc> allTablesMap = getManager(NTableMetadataManager.class, modelRequest.getProject())
                .getAllTablesMap();
        List<ComputedColumnDesc> ccList = dataModel.getComputedColumnDescs();
        if (!ccList.isEmpty()) {
            String factTableIdentity = dataModel.getRootFactTableName();
            TableDesc tableDesc = allTablesMap.get(factTableIdentity);
            TableDesc extendTable = tableDesc.appendColumns(ComputedColumnUtil.createComputedColumns(ccList, tableDesc),
                    true);
            allTablesMap.put(factTableIdentity, extendTable);
        }
        dataModel.setUuid(modelRequest.getUuid() != null ? modelRequest.getUuid() : RandomUtil.randomUUIDStr());
        dataModel.setDescription(
                modelRequest.getDescription() != null ? modelRequest.getDescription() : StringUtils.EMPTY);
        dataModel.setProject(modelRequest.getProject());
        dataModel.setAllMeasures(convertMeasure(simplifiedMeasures));
        dataModel.setAllNamedColumns(convertNamedColumns(modelRequest.getProject(), dataModel, modelRequest));
        dataModel.initJoinDesc(KylinConfig.getInstanceFromEnv(), allTablesMap);
        convertNonEquiJoinCond(dataModel, modelRequest);
        dataModel.setModelType(dataModel.getModelTypeFromTable());
        return dataModel;
    }

    /**
     * expand model request, add hidden internal measures from current model
     *
     * @param modelRequest
     * @return
     */
    public void expandModelRequest(ModelRequest modelRequest) {
        if (modelRequest.getUuid() != null) {
            NDataModel existingModel = NDataModelManager
                    .getInstance(KylinConfig.getInstanceFromEnv(), modelRequest.getProject())
                    .getDataModelDesc(modelRequest.getUuid());

            Map<Integer, Collection<Integer>> effectiveExpandedMeasures = null;
            ImmutableBiMap<Integer, Measure> effectiveMeasures = null;
            if (existingModel.isBroken()) {
                effectiveExpandedMeasures = new HashMap<>();
                effectiveMeasures = loadModelMeasureWithoutInit(modelRequest, effectiveExpandedMeasures);
            } else {
                effectiveExpandedMeasures = existingModel.getEffectiveExpandedMeasures();
                effectiveMeasures = existingModel.getEffectiveMeasures();
            }

            Set<Integer> internalIds = new HashSet<>();
            for (SimplifiedMeasure measure : modelRequest.getSimplifiedMeasures()) {
                if (effectiveExpandedMeasures.containsKey(measure.getId())) {
                    internalIds.addAll(effectiveExpandedMeasures.get(measure.getId()));
                }
            }
            Set<Integer> requestMeasureIds = modelRequest.getSimplifiedMeasures().stream().map(SimplifiedMeasure::getId)
                    .collect(Collectors.toSet());
            for (Integer internalId : internalIds) {
                if (!requestMeasureIds.contains(internalId)) {
                    modelRequest.getSimplifiedMeasures()
                            .add(SimplifiedMeasure.fromMeasure(effectiveMeasures.get(internalId)));
                }
            }
        }
    }

    private ImmutableBiMap<Integer, Measure> loadModelMeasureWithoutInit(ModelRequest modelRequest,
            Map<Integer, Collection<Integer>> effectiveExpandedMeasures) {
        NDataModel srcModel = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), modelRequest.getProject())
                .getDataModelDescWithoutInit(modelRequest.getUuid());
        ImmutableBiMap.Builder<Integer, Measure> mapBuilder = ImmutableBiMap.builder();
        for (Measure measure : srcModel.getAllMeasures()) {
            measure.setName(measure.getName());
            if (!measure.isTomb()) {
                mapBuilder.put(measure.getId(), measure);
                if (measure.getType() == NDataModel.MeasureType.EXPANDABLE) {
                    effectiveExpandedMeasures.put(measure.getId(), measure.getInternalIds());
                }
            }
        }
        return mapBuilder.build();
    }

    public void deleteExpandableMeasureInternalMeasures(NDataModel model) {
        expandableMeasureUtil.deleteExpandableMeasureInternalMeasures(model);
    }

    /**
     * expand measures (e.g. CORR measure) in current model, may create new CC or new measures
     *
     * @param model
     * @return
     */
    public void expandExpandableMeasure(NDataModel model) {
        expandableMeasureUtil.expandExpandableMeasure(model);
    }

    private void convertNonEquiJoinCond(final NDataModel dataModel, final ModelRequest request) {

        final List<SimplifiedJoinTableDesc> requestJoinTableDescs = request.getSimplifiedJoinTableDescs();
        if (CollectionUtils.isEmpty(requestJoinTableDescs)) {
            return;
        }

        Set<JoinDesc> nonEquivJoins = new HashSet<>();

        String project = dataModel.getProject();
        val projectKylinConfig = NProjectManager.getProjectConfig(project);
        boolean isScd2Enabled = projectKylinConfig.isQueryNonEquiJoinModelEnabled();
        if (!projectKylinConfig.isUTEnv()) {
            QueryContext.current().setAclInfo(AclPermissionUtil.createAclInfo(project, getCurrentUserGroups()));
        }
        QueryExec queryExec = new QueryExec(project, projectKylinConfig, false);
        for (int i = 0; i < requestJoinTableDescs.size(); i++) {
            final JoinDesc joinWithoutNonEquivInfo = dataModel.getJoinTables().get(i).getJoin();
            final SimplifiedJoinDesc requestJoinDesc = requestJoinTableDescs.get(i).getSimplifiedJoinDesc();

            if (CollectionUtils.isEmpty(requestJoinDesc.getSimplifiedNonEquiJoinConditions())) {
                continue;
            }

            //1. check scd2 turn on when non-equi join exists
            if (!isScd2Enabled) {
                throw new KylinException(ErrorCodeServer.PROJECT_SCD2_IS_NOT_ALLOWED);
            }

            //2. check request equi join condition
            checkRequestNonEquiJoinConds(requestJoinDesc);

            //3. suggest nonEquiModel
            String scd2Sql = SCD2SqlConverter.INSTANCE.genSCD2SqlStr(joinWithoutNonEquivInfo,
                    requestJoinDesc.getSimplifiedNonEquiJoinConditions());
            final JoinDesc analyzedJoin = deriveJoins(queryExec, scd2Sql);
            // restore table alias in non-equi conditions
            final NonEquiJoinCondition nonEquiCondWithAliasRestored = new NonEquiJoinCondition.NeqConditionVisitor() {
                @Override
                public NonEquiJoinCondition visitColumn(NonEquiJoinCondition cond) {
                    TableRef originalTableRef;
                    if (cond.getColRef().getTableRef().getTableIdentity()
                            .equals(joinWithoutNonEquivInfo.getPKSide().getTableIdentity())) {
                        originalTableRef = joinWithoutNonEquivInfo.getPKSide();
                    } else {
                        originalTableRef = joinWithoutNonEquivInfo.getFKSide();
                    }

                    return new NonEquiJoinCondition(originalTableRef.getColumn(cond.getColRef().getName()),
                            cond.getDataType());
                }
            }.visit(analyzedJoin.getNonEquiJoinCondition());
            analyzedJoin.setNonEquiJoinCondition(nonEquiCondWithAliasRestored);
            String expr = analyzedJoin.getNonEquiJoinCondition().getExpr();
            expr = expr.replaceAll(analyzedJoin.getPKSide().getAlias(), joinWithoutNonEquivInfo.getPKSide().getAlias());
            expr = expr.replaceAll(analyzedJoin.getFKSide().getAlias(), joinWithoutNonEquivInfo.getFKSide().getAlias());
            analyzedJoin.getNonEquiJoinCondition().setExpr(expr);
            analyzedJoin.setPrimaryTableRef(joinWithoutNonEquivInfo.getPKSide());
            analyzedJoin.setPrimaryTable(joinWithoutNonEquivInfo.getPrimaryTable());
            analyzedJoin.setForeignTableRef(joinWithoutNonEquivInfo.getFKSide());
            analyzedJoin.setForeignTable(joinWithoutNonEquivInfo.getForeignTable());

            //4. update dataModel
            try {
                Scd2Simplifier.INSTANCE.simplifyScd2Conditions(analyzedJoin);
                joinWithoutNonEquivInfo.setNonEquiJoinCondition(analyzedJoin.getNonEquiJoinCondition());
                joinWithoutNonEquivInfo.setForeignTable(analyzedJoin.getForeignTable());
                joinWithoutNonEquivInfo.setPrimaryTable(analyzedJoin.getPrimaryTable());
            } catch (Exception e) {
                ThreadUtil.warnKylinStackTrace("Update model failed...\n");
                if (e instanceof KylinException) {
                    throw e;
                }
                throw new KylinException(ErrorCodeServer.SCD2_MODEL_UNKNOWN_EXCEPTION,
                        Throwables.getRootCause(e).getMessage());
            }

            //5. check same join conditions
            if (nonEquivJoins.contains(requestJoinDesc)) {
                throw new KylinException(ErrorCodeServer.DUPLICATE_MODEL_JOIN_CONDITIONS);
            } else {
                nonEquivJoins.add(requestJoinDesc);
            }
        }
    }

    private JoinDesc deriveJoins(QueryExec queryExec, String sql) {
        List<OlapContext> contexts = queryExec.deriveOlapContexts(sql);
        Optional<KylinException> th;
        if (contexts.isEmpty()) {
            th = Optional.of(new KylinException(ErrorCodeServer.SCD2_MODEL_UNKNOWN_EXCEPTION,
                    "Failed to extract joins from the input sql: " + sql));
        } else if (contexts.size() > 1) {
            th = Optional.of(new KylinException(ErrorCodeServer.SCD2_MODEL_UNKNOWN_EXCEPTION,
                    "Small sub-queries were split from the input sql: " + sql));
        } else {
            OlapContext ctx = contexts.get(0);
            if (ctx.getJoins().size() == 1) {
                return ctx.getJoins().get(0);
            }
            th = Optional.of(new KylinException(ErrorCodeServer.SCD2_MODEL_UNKNOWN_EXCEPTION,
                    "Non-equiv-join conditions were split. the input sql is: " + sql));
        }
        throw th.get();
    }

    private void checkRequestNonEquiJoinConds(final SimplifiedJoinDesc requestJoinDesc) {

        if (!SCD2CondChecker.INSTANCE.checkSCD2EquiJoinCond(requestJoinDesc.getForeignKey(),
                requestJoinDesc.getPrimaryKey())) {
            throw new KylinException(ErrorCodeServer.SCD2_MODEL_REQUIRES_AT_LEAST_ONE_EQUAL_CONDITION);
        }
        if (!SCD2CondChecker.INSTANCE
                .checkSCD2NonEquiJoinCondPair(requestJoinDesc.getSimplifiedNonEquiJoinConditions())) {
            throw new KylinException(ErrorCodeServer.SCD2_MODEL_REQUIRES_AT_LEAST_ONE_NON_EQUAL_CONDITION);
        }
        if (!SCD2CondChecker.INSTANCE.checkFkPkPairUnique(requestJoinDesc)) {
            throw new KylinException(ErrorCodeServer.SCD2_MODEL_PK_FK_UNIQUE_CHECK_FAILED);
        }
    }

    private List<NDataModel.NamedColumn> convertNamedColumns(String project, NDataModel dataModel,
            ModelRequest modelRequest) {
        NTableMetadataManager tableManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(),
                project);
        List<JoinTableDesc> allTables = Lists.newArrayList();
        val rootFactTable = new JoinTableDesc();
        rootFactTable.setTable(dataModel.getRootFactTableName());
        rootFactTable.setAlias(dataModel.getRootFactTableAlias());
        rootFactTable.setKind(NDataModel.TableKind.FACT);
        allTables.add(rootFactTable);
        allTables.addAll(dataModel.getJoinTables());

        List<NDataModel.NamedColumn> simplifiedColumns = modelRequest.getSimplifiedDimensions();
        Map<String, NDataModel.NamedColumn> dimensionNameMap = Maps.newHashMap();
        for (NDataModel.NamedColumn namedColumn : simplifiedColumns) {
            dimensionNameMap.put(namedColumn.getAliasDotColumn(), namedColumn);
        }
        Map<String, NDataModel.NamedColumn> otherColumnNameMap = Maps.newHashMap();
        for (NDataModel.NamedColumn namedColumn : modelRequest.getOtherColumns()) {
            otherColumnNameMap.put(namedColumn.getAliasDotColumn(), namedColumn);
        }

        int id = 0;
        List<NDataModel.NamedColumn> columns = Lists.newArrayList();
        for (JoinTableDesc joinTable : allTables) {
            val tableDesc = tableManager.getTableDesc(joinTable.getTable());
            boolean isFact = joinTable.getKind() == NDataModel.TableKind.FACT;
            val alias = StringUtils.isEmpty(joinTable.getAlias()) ? tableDesc.getName() : joinTable.getAlias();
            for (ColumnDesc column : modelRequest.getColumnsFetcher().apply(tableDesc, !isFact)) {
                val namedColumn = new NDataModel.NamedColumn();
                namedColumn.setId(id++);
                namedColumn.setName(column.getName());
                namedColumn.setAliasDotColumn(alias + "." + column.getName());
                namedColumn.setStatus(NDataModel.ColumnStatus.EXIST);
                val dimension = dimensionNameMap.get(namedColumn.getAliasDotColumn());
                if (dimension != null) {
                    namedColumn.setStatus(NDataModel.ColumnStatus.DIMENSION);
                    namedColumn.setName(dimension.getName());
                }
                if (otherColumnNameMap.get(namedColumn.getAliasDotColumn()) != null) {
                    namedColumn.setName(otherColumnNameMap.get(namedColumn.getAliasDotColumn()).getName());
                }
                columns.add(namedColumn);
            }
        }
        Map<String, ComputedColumnDesc> ccMap = dataModel.getComputedColumnDescs().stream()
                .collect(Collectors.toMap(ComputedColumnDesc::getFullName, Function.identity()));
        List<ComputedColumnDesc> orderedCCList = Lists.newArrayList();
        NDataModel originModel = getManager(NDataModelManager.class, project).getDataModelDesc(dataModel.getUuid());
        if (originModel != null && !originModel.isBroken()) {
            originModel.getAllNamedColumns().stream().filter(NamedColumn::isExist)
                    .filter(column -> ccMap.containsKey(column.getAliasDotColumn())) //
                    .forEach(column -> {
                        ComputedColumnDesc cc = ccMap.get(column.getAliasDotColumn());
                        orderedCCList.add(cc);
                        ccMap.remove(column.getAliasDotColumn());
                    });
            orderedCCList.addAll(ccMap.values());
        } else {
            orderedCCList.addAll(dataModel.getComputedColumnDescs());
        }

        for (ComputedColumnDesc computedColumnDesc : orderedCCList) {
            NDataModel.NamedColumn namedColumn = new NDataModel.NamedColumn();
            namedColumn.setId(id++);
            namedColumn.setName(computedColumnDesc.getColumnName());
            namedColumn.setAliasDotColumn(computedColumnDesc.getFullName());
            namedColumn.setStatus(NDataModel.ColumnStatus.EXIST);
            val dimension = dimensionNameMap.get(namedColumn.getAliasDotColumn());
            if (dimension != null) {
                namedColumn.setStatus(NDataModel.ColumnStatus.DIMENSION);
                namedColumn.setName(dimension.getName());
            }
            columns.add(namedColumn);
        }
        return columns;
    }

    private void updateModelColumnForTableAliasModify(NDataModel model, Map<String, String> matchAlias) {

        for (val kv : matchAlias.entrySet()) {
            String oldAliasName = kv.getKey();
            String newAliasName = kv.getValue();
            if (oldAliasName.equalsIgnoreCase(newAliasName)) {
                continue;
            }

            model.getAllNamedColumns().stream().filter(NamedColumn::isExist)
                    .forEach(x -> x.changeTableAlias(oldAliasName, newAliasName));
            model.getAllMeasures().stream().filter(x -> !x.isTomb())
                    .forEach(x -> x.changeTableAlias(oldAliasName, newAliasName));
            model.getComputedColumnDescs().forEach(x -> changeTableAlias(x, oldAliasName, newAliasName));

            if (StringUtils.isNotBlank(model.getFilterCondition())) {
                String expr = QueryUtil.adaptCalciteSyntax(model.getFilterCondition());
                SqlNode sqlNode = CalciteParser.getExpNode(expr);
                sqlNode.accept(new ModifyTableNameSqlVisitor(oldAliasName, newAliasName));

                String newFilterCondition = sqlNode.toSqlString(CalciteParser.HIVE_SQL_DIALECT).toString();
                model.setFilterCondition(newFilterCondition);
            }
        }
    }

    private void changeTableAlias(ComputedColumnDesc computedColumnDesc, String oldAlias, String newAlias) {
        SqlVisitor<Object> modifyAlias = new ModifyTableNameSqlVisitor(oldAlias, newAlias);
        SqlNode sqlNode = CalciteParser.getExpNode(computedColumnDesc.getExpression());
        sqlNode.accept(modifyAlias);
        computedColumnDesc.setExpression(sqlNode.toSqlString(CalciteSqlDialect.DEFAULT).toString());
    }

    private Map<String, String> getAliasTransformMap(NDataModel originModel, NDataModel expectModel) {
        Map<String, String> matchAlias = Maps.newHashMap();
        boolean match = originModel.getJoinsGraph().match(expectModel.getJoinsGraph(), matchAlias);
        if (!match) {
            matchAlias.clear();
        }
        return matchAlias;
    }

    private final Function<List<NDataModel.NamedColumn>, Map<String, NDataModel.NamedColumn>> toExistMap //
            = allCols -> allCols.stream().filter(NDataModel.NamedColumn::isExist)
                    .collect(Collectors.toMap(NDataModel.NamedColumn::getAliasDotColumn, Function.identity()));

    private final Function<List<NDataModel.Measure>, Map<SimplifiedMeasure, NDataModel.Measure>> toMeasureMap //
            = allCols -> allCols.stream().filter(m -> !m.isTomb())
                    .collect(Collectors.toMap(SimplifiedMeasure::fromMeasure, Function.identity(), (u, v) -> {
                        throw new KylinException(ServerErrorCode.DUPLICATE_MEASURE_EXPRESSION, String
                                .format(Locale.ROOT, MsgPicker.getMsg().getDuplicateMeasureDefinition(), v.getName()));
                    }));

    private final Function<List<NDataModel.NamedColumn>, Map<String, NDataModel.NamedColumn>> toDimensionMap //
            = allCols -> allCols.stream().filter(NDataModel.NamedColumn::isDimension)
                    .collect(Collectors.toMap(NDataModel.NamedColumn::getAliasDotColumn, Function.identity()));

    private boolean isValidMeasure(MeasureDesc measure) {
        val funcDesc = measure.getFunction();
        val param = funcDesc.getParameters().get(0);
        if (param.isConstant()) {
            return true;
        }
        val ccDataType = param.getColRef().getType();
        return funcDesc.isDatatypeSuitable(ccDataType);
    }

    private NDataModel updateColumnsInit(NDataModel originModel, ModelRequest request, boolean saveCheck) {
        val expectedModel = convertToDataModel(request);
        discardInvalidColsAndMeasForBrokenModel(request.getProject(), expectedModel);

        String project = request.getProject();
        KylinConfig kylinConfig = KylinConfig.getInstanceFromEnv();
        expectedModel.init(kylinConfig);
        Map<String, String> matchAlias = getAliasTransformMap(originModel, expectedModel);
        updateModelColumnForTableAliasModify(expectedModel, matchAlias);

        NDataModelManager modelManager = NDataModelManager.getInstance(kylinConfig, project);
        expectedModel.init(kylinConfig, project, modelManager.getCCRelatedModels(expectedModel), saveCheck);

        originModel.setJoinTables(expectedModel.getJoinTables());
        originModel.setCanvas(expectedModel.getCanvas());
        originModel.setRootFactTableName(expectedModel.getRootFactTableName());
        originModel.setRootFactTableAlias(expectedModel.getRootFactTableAlias());
        originModel.setPartitionDesc(expectedModel.getPartitionDesc());
        originModel.setFilterCondition(expectedModel.getFilterCondition());
        originModel.setMultiPartitionDesc(expectedModel.getMultiPartitionDesc());
        updateModelColumnForTableAliasModify(originModel, matchAlias);

        return expectedModel;
    }

    private boolean equalsIgnoreReturnType(Measure removedOrUpdatedMeasure, Measure newMeasure) {
        val simpleOld = SimplifiedMeasure.fromMeasure(removedOrUpdatedMeasure);
        simpleOld.setReturnType("any");
        val simpleNew = SimplifiedMeasure.fromMeasure(newMeasure);
        simpleNew.setReturnType("any");
        return simpleOld.equals(simpleNew);
    }

    public UpdateImpact updateModelColumns(NDataModel originModel, ModelRequest request) {
        return updateModelColumns(originModel, request, false);
    }

    public UpdateImpact updateModelColumns(NDataModel originModel, ModelRequest request, boolean saveCheck) {
        val expectedModel = updateColumnsInit(originModel, request, saveCheck);
        val updateImpact = new UpdateImpact();
        // handle computed column updates
        List<ComputedColumnDesc> currentComputedColumns = originModel.getComputedColumnDescs();
        List<ComputedColumnDesc> newComputedColumns = expectedModel.getComputedColumnDescs();
        Set<String> removedOrUpdatedComputedColumns = currentComputedColumns.stream()
                .filter(cc -> !newComputedColumns.contains(cc)).map(ComputedColumnDesc::getFullName)
                .collect(Collectors.toSet());
        // move deleted CC's named column to TOMB
        originModel.getAllNamedColumns().stream() //
                .filter(column -> removedOrUpdatedComputedColumns.contains(column.getAliasDotColumn())
                        && column.isExist())
                .forEach(unusedColumn -> {
                    unusedColumn.setStatus(NDataModel.ColumnStatus.TOMB);
                    updateImpact.getRemovedOrUpdatedCCs().add(unusedColumn.getId());
                });
        Set<String> allFunctions = originModel.getEffectiveMeasures().values().stream()
                .map(measure -> measure.getFunction().toString()).collect(Collectors.toSet());
        if (allFunctions.size() != originModel.getEffectiveMeasures().size()) {
            fixDupMeasureNames(originModel, request);
        }

        // move deleted CC's measure to TOMB
        List<Measure> currentMeasures = originModel.getEffectiveMeasures().values().asList();
        currentMeasures.stream().filter(measure -> {
            List<TblColRef> params = measure.getFunction().getColRefs();
            if (CollectionUtils.isEmpty(params)) {
                return false;
            }
            return params.stream().map(TblColRef::getIdentity).anyMatch(removedOrUpdatedComputedColumns::contains);
        }).forEach(unusedMeasure -> {
            unusedMeasure.setTomb(true);
            updateImpact.getInvalidMeasures().add(unusedMeasure.getId());
        });
        originModel.setComputedColumnDescs(expectedModel.getComputedColumnDescs());
        originModel.setComputedColumnUuids(originModel.getComputedColumnDescs().stream()
                .map(ComputedColumnDesc::getUuid).collect(Collectors.toList()));

        // compare measures
        List<NDataModel.Measure> newMeasures = Lists.newArrayList();
        compareAndUpdateColumns(toMeasureMap.apply(originModel.getAllMeasures()),
                toMeasureMap.apply(expectedModel.getAllMeasures()), newMeasures::add,
                oldMeasure -> oldMeasure.setTomb(true), (oldMeasure, newMeasure) -> {
                    oldMeasure.setName(newMeasure.getName());
                    oldMeasure.setComment(newMeasure.getComment());
                });
        updateMeasureStatus(newMeasures, originModel, updateImpact);

        // compare originModel and expectedModel's existing allNamedColumn
        val originExistMap = toExistMap.apply(originModel.getAllNamedColumns());
        val newCols = Lists.<NDataModel.NamedColumn> newArrayList();
        compareAndUpdateColumns(originExistMap, toExistMap.apply(expectedModel.getAllNamedColumns()), newCols::add,
                oldCol -> oldCol.setStatus(NDataModel.ColumnStatus.TOMB),
                (olCol, newCol) -> olCol.setName(newCol.getName()));
        updateColumnStatus(newCols, originModel, updateImpact);

        // measures invalid due to cc removal
        // should not clear related layouts
        val removedCCs = new HashSet<Integer>();
        removedCCs.addAll(updateImpact.getRemovedOrUpdatedCCs());
        removedCCs.removeAll(updateImpact.getUpdatedCCs());
        updateImpact.getInvalidMeasures().removeIf(measureId -> causedByCCDelete(removedCCs, originModel, measureId));

        // compare originModel and expectedModel's dimensions
        val originDimensionMap = toDimensionMap.apply(originModel.getAllNamedColumns());
        compareAndUpdateColumns(originDimensionMap, toDimensionMap.apply(expectedModel.getAllNamedColumns()),
                newCol -> originExistMap.get(newCol.getAliasDotColumn()).setStatus(NDataModel.ColumnStatus.DIMENSION),
                oldCol -> oldCol.setStatus(NDataModel.ColumnStatus.EXIST),
                (olCol, newCol) -> olCol.setName(newCol.getName()));

        //Move unused named column to EXIST status
        originModel.getAllNamedColumns().stream().filter(NDataModel.NamedColumn::isDimension)
                .filter(column -> request.getSimplifiedDimensions().stream()
                        .noneMatch(dimension -> dimension.getAliasDotColumn().equals(column.getAliasDotColumn())))
                .forEach(c -> c.setStatus(NDataModel.ColumnStatus.EXIST));

        return updateImpact;
    }

    private void fixDupMeasureNames(NDataModel originModel, ModelRequest request) {
        Set<Integer> healthyExistedMeasures = Sets.newHashSet();
        List<String> illegalSimplifiedMeasures = Lists.newArrayList();

        Map<String, Integer> nameToIdOfSimplified = Maps.newHashMap();
        Set<Integer> idOfSimplified = Sets.newHashSet();
        for (SimplifiedMeasure measure : request.getSimplifiedMeasures()) {
            nameToIdOfSimplified.put(measure.getName(), measure.getId());
            if (measure.getId() != 0) {
                idOfSimplified.add(measure.getId());
            }
        }

        List<Measure> nonCountStarExistedMeasures = originModel.getAllMeasures().stream()
                .filter(measure -> !measure.getName().equals("COUNT_ALL")).filter(measure -> !measure.isTomb())
                .collect(Collectors.toList());
        Map<String, Integer> nameToIdOfExistedModel = nonCountStarExistedMeasures.stream()
                .collect(Collectors.toMap(MeasureDesc::getName, Measure::getId));
        nameToIdOfExistedModel.forEach((name, id) -> {
            if (!nameToIdOfSimplified.containsKey(name)) {
                if (idOfSimplified.contains(id)) {
                    healthyExistedMeasures.add(id);
                }
            } else if (nameToIdOfSimplified.get(name) == 0) {
                illegalSimplifiedMeasures.add(name);
            } else {
                healthyExistedMeasures.add(id);
            }
        });

        if (!illegalSimplifiedMeasures.isEmpty()) {
            throw new KylinException(SIMPLIFIED_MEASURES_MISSING_ID, String.join(",", illegalSimplifiedMeasures));
        }

        nonCountStarExistedMeasures.stream() //
                .filter(measure -> !healthyExistedMeasures.contains(measure.getId())) //
                .forEach(measure -> {
                    log.warn("the measure({}) has been handled to tomb", measure.getName());
                    measure.setTomb(true);
                });
    }

    /**
     * one measure in expectedModel but not in originModel then add one
     * one in expectedModel, is also a TOMB one in originModel, set status to not TOMB
     *
     * @param newMeasures
     * @param originModel
     * @param updateImpact
     */
    private void updateMeasureStatus(List<Measure> newMeasures, NDataModel originModel, UpdateImpact updateImpact) {
        int maxMeasureId = originModel.getAllMeasures().stream().map(NDataModel.Measure::getId).mapToInt(i -> i).max()
                .orElse(NDataModel.MEASURE_ID_BASE - 1);
        newMeasures.sort(Comparator.comparing(Measure::getId));
        for (NDataModel.Measure measure : newMeasures) {
            Integer modifiedMeasureId = updateImpact.getInvalidMeasures().stream() //
                    .filter(measureId -> equalsIgnoreReturnType(originModel.getTombMeasureById(measureId), measure))
                    .findFirst().orElse(null);
            if (modifiedMeasureId != null) {
                // measure affected by cc modification
                // check if measure is still valid due to cc modification
                if (!isValidMeasure(measure)) {
                    // measure removed due to cc modification
                    continue;
                }
                // measure added/updated is valid, removed from InvalidMeasure
                updateImpact.getInvalidMeasures().remove(modifiedMeasureId);
                val funcDesc = measure.getFunction();
                val ccDataType = funcDesc.getParameters().get(0).getColRef().getType();
                val proposeReturnType = FunctionDesc.proposeReturnType(funcDesc.getExpression(), ccDataType.toString());
                val originReturnType = originModel.getTombMeasureById(modifiedMeasureId).getFunction().getReturnType();
                if (!originReturnType.equals(proposeReturnType)) {
                    // measure return type change, assign new id
                    val newFuncDesc = FunctionDesc.newInstance(funcDesc.getExpression(), funcDesc.getParameters(),
                            proposeReturnType);
                    measure.setFunction(newFuncDesc);
                    maxMeasureId++;
                    measure.setId(maxMeasureId);
                    originModel.getAllMeasures().add(measure);
                    updateImpact.getReplacedMeasures().put(modifiedMeasureId, maxMeasureId);
                } else {
                    // measure return type not change, id not change
                    originModel.getTombMeasureById(modifiedMeasureId).setTomb(false);
                    updateImpact.getUpdatedMeasures().add(modifiedMeasureId);
                }
            } else {
                // new added measure
                if (isValidMeasure(measure)) {
                    maxMeasureId++;
                    measure.setId(maxMeasureId);
                    originModel.getAllMeasures().add(measure);
                } else {
                    updateImpact.getInvalidRequestMeasures().add(measure.getId());
                }
            }
        }
    }

    /**
     * one in expectedModel, is also a TOMB one in originModel, set status as the expected's
     *
     * @param newCols
     * @param originModel
     * @param updateImpact
     */
    private void updateColumnStatus(List<NDataModel.NamedColumn> newCols, NDataModel originModel,
            UpdateImpact updateImpact) {
        int maxId = originModel.getAllNamedColumns().stream().map(NamedColumn::getId).mapToInt(i -> i).max().orElse(-1);
        for (NDataModel.NamedColumn newCol : newCols) {
            val modifiedColId = updateImpact.getRemovedOrUpdatedCCs().stream() //
                    .filter(modifiedId -> newCol.getAliasDotColumn()
                            .equals(originModel.getTombColumnNameById(modifiedId)))
                    .findFirst().orElse(null);
            if (modifiedColId != null) {
                // column affected by cc modification
                val modifiedColumn = originModel.getAllNamedColumns().stream().filter(c -> c.getId() == modifiedColId)
                        .findFirst().orElse(null);
                if (modifiedColumn != null) {
                    modifiedColumn.setStatus(newCol.getStatus());
                    updateImpact.getUpdatedCCs().add(modifiedColId);
                }
            } else {
                maxId++;
                newCol.setId(maxId);
                originModel.getAllNamedColumns().add(newCol);
            }
        }
    }

    /**
     * if a measure becomes invalid because of cc delete,
     * the measure and related aggGroups/layouts should remains
     *
     * @param removedCCs
     * @param originModel
     * @param measureId
     * @return
     */
    private boolean causedByCCDelete(Set<Integer> removedCCs, NDataModel originModel, int measureId) {
        for (int ccId : removedCCs) {
            val colName = originModel.getTombColumnNameById(ccId);
            val funcParams = originModel.getTombMeasureById(measureId).getFunction().getParameters();
            for (val funcParam : funcParams) {
                val funcColName = funcParam.getColRef().getIdentity();
                if (StringUtils.equalsIgnoreCase(funcColName, colName)) {
                    return true;
                }
            }
        }
        return false;
    }

    private <K, T> void compareAndUpdateColumns(Map<K, T> origin, Map<K, T> target, Consumer<T> onlyInTarget,
            Consumer<T> onlyInOrigin, BiConsumer<T, T> inBoth) {
        for (Map.Entry<K, T> entry : target.entrySet()) {
            // change name does not matter
            val matched = origin.get(entry.getKey());
            if (matched == null) {
                onlyInTarget.accept(entry.getValue());
            } else {
                inBoth.accept(matched, entry.getValue());
            }
        }
        for (Map.Entry<K, T> entry : origin.entrySet()) {
            val matched = target.get(entry.getKey());
            if (matched == null) {
                onlyInOrigin.accept(entry.getValue());
            }
        }

    }

    private List<NDataModel.Measure> convertMeasure(List<SimplifiedMeasure> simplifiedMeasures) {
        List<NDataModel.Measure> measures = new ArrayList<>();
        boolean hasCountAll = false;
        int id = NDataModel.MEASURE_ID_BASE;
        if (simplifiedMeasures == null) {
            simplifiedMeasures = Lists.newArrayList();
        }
        for (SimplifiedMeasure simplifiedMeasure : simplifiedMeasures) {
            val measure = simplifiedMeasure.toMeasure();
            measure.setId(id);
            measures.add(measure);
            val functionDesc = measure.getFunction();
            if (functionDesc.isCount() && !functionDesc.isCountOnColumn()) {
                hasCountAll = true;
            }
            id++;
        }
        if (!hasCountAll) {
            FunctionDesc functionDesc = new FunctionDesc();
            ParameterDesc parameterDesc = new ParameterDesc();
            parameterDesc.setType("constant");
            parameterDesc.setValue("1");
            functionDesc.setParameters(Lists.newArrayList(parameterDesc));
            functionDesc.setExpression("COUNT");
            functionDesc.setReturnType("bigint");
            NDataModel.Measure measure = newMeasure(functionDesc, "COUNT_ALL", id);
            measures.add(measure);
        }
        return measures;
    }

    private NDataModel.Measure newMeasure(FunctionDesc func, String name, int id) {
        NDataModel.Measure measure = new NDataModel.Measure();
        measure.setName(name);
        measure.setFunction(func);
        measure.setId(id);
        return measure;
    }

    public void handleSemanticUpdate(String project, String model, NDataModel originModel, String start, String end) {
        handleSemanticUpdate(project, model, originModel, start, end, false);
    }

    public void handleSemanticUpdate(String project, String model, NDataModel originModel, String start, String end,
            boolean saveOnly) {
        val needBuild = doHandleSemanticUpdate(project, model, originModel, start, end);
        if (!saveOnly && needBuild) {
            buildForModel(project, model);
        }
    }

    public boolean doHandleSemanticUpdate(String project, String model, NDataModel originModel, String start,
            String end) {
        val config = KylinConfig.getInstanceFromEnv();
        val indePlanManager = NIndexPlanManager.getInstance(config, project);
        val modelMgr = NDataModelManager.getInstance(config, project);
        val optRecManagerV2 = OptRecManagerV2.getInstance(project);

        val indexPlan = indePlanManager.getIndexPlan(model);
        val newModel = modelMgr.getDataModelDesc(model);

        if (isSignificantChange(originModel, newModel)) {
            log.info("model {} reload data from datasource", originModel.getAlias());
            val savedIndexPlan = handleMeasuresChanged(indexPlan, newModel.getEffectiveMeasures().keySet(),
                    indePlanManager);
            removeUselessDimensions(savedIndexPlan, newModel.getEffectiveDimensions().keySet(), false, config);
            modelMgr.updateDataModel(newModel.getUuid(),
                    copyForWrite -> copyForWrite.setSemanticVersion(copyForWrite.getSemanticVersion() + 1));
            handleReloadData(newModel, originModel, project, start, end);
            optRecManagerV2.discardAll(model);
            return true;
        }

        // measure changed: does not matter to auto created cuboids' data, need refresh rule based cuboids
        if (isMeasureChange(originModel, newModel)) {
            handleMeasuresChanged(indexPlan, newModel.getEffectiveMeasures().keySet(), indePlanManager);
        }
        // dimension deleted: previous step is remove dimensions in rule,
        //   so we only remove the auto created cuboids
        if (isDimNotOnlyAdd(originModel, newModel)) {
            removeUselessDimensions(indexPlan, newModel.getEffectiveDimensions().keySet(), true, config);
        }

        return hasRulebaseLayoutChange(indexPlan.getRuleBasedIndex(),
                indePlanManager.getIndexPlan(indexPlan.getId()).getRuleBasedIndex());
    }

    public boolean isDimNotOnlyAdd(NDataModel originModel, NDataModel newModel) {
        return !newModel.getEffectiveDimensions().keySet().containsAll(originModel.getEffectiveDimensions().keySet());
    }

    public boolean isMeasureChange(NDataModel originModel, NDataModel newModel) {
        return !CollectionUtils.isEqualCollection(newModel.getEffectiveMeasures().keySet(),
                originModel.getEffectiveMeasures().keySet());
    }

    public boolean isFilterConditionNotChange(String oldFilterCondition, String newFilterCondition) {
        oldFilterCondition = oldFilterCondition == null ? "" : oldFilterCondition;
        newFilterCondition = newFilterCondition == null ? "" : newFilterCondition;
        return StringUtils.trim(oldFilterCondition).equals(StringUtils.trim(newFilterCondition));
    }

    public static boolean isMultiPartitionDescSame(MultiPartitionDesc oldPartitionDesc,
            MultiPartitionDesc newPartitionDesc) {
        String oldColumns = oldPartitionDesc == null ? "" : StringUtils.join(oldPartitionDesc.getColumns(), ",");
        String newColumns = newPartitionDesc == null ? "" : StringUtils.join(newPartitionDesc.getColumns(), ",");
        return oldColumns.equals(newColumns);
    }

    public static boolean isAntiFlattenableSame(List<JoinTableDesc> oldJoinTables, List<JoinTableDesc> newJoinTables) {
        Map<JoinDesc, JoinTableDesc> newJoinMap = newJoinTables.stream()
                .collect(Collectors.toMap(JoinTableDesc::getJoin, Function.identity()));
        boolean sameAntiFlattenable = true;
        for (JoinTableDesc oldJoinTable : oldJoinTables) {
            JoinDesc join = oldJoinTable.getJoin();
            if (newJoinMap.containsKey(join)) {
                JoinTableDesc newJoinTable = newJoinMap.get(join);
                if (oldJoinTable.hasDifferentAntiFlattenable(newJoinTable)) {
                    sameAntiFlattenable = false;
                    break;
                }
            }
        }
        return sameAntiFlattenable;
    }

    // if partitionDesc, mpCol, joinTable, FilterCondition changed, we need reload data from datasource
    public boolean isSignificantChange(NDataModel originModel, NDataModel newModel) {
        return isDifferent(originModel.getPartitionDesc(), newModel.getPartitionDesc())
                || !Objects.equals(originModel.getRootFactTable(), newModel.getRootFactTable())
                || !originModel.getJoinsGraph().match(newModel.getJoinsGraph(), Maps.newHashMap())
                || !isFilterConditionNotChange(originModel.getFilterCondition(), newModel.getFilterCondition())
                || !isMultiPartitionDescSame(originModel.getMultiPartitionDesc(), newModel.getMultiPartitionDesc())
                || !isAntiFlattenableSame(originModel.getJoinTables(), newModel.getJoinTables());
    }

    private boolean isDifferent(PartitionDesc p1, PartitionDesc p2) {
        boolean isP1Null = p1 == null || p1.isEmpty();
        boolean isP2Null = p2 == null || p2.isEmpty();
        if (isP1Null && isP2Null) {
            return false;
        }
        return !Objects.equals(p1, p2);
    }

    private IndexPlan handleMeasuresChanged(IndexPlan indexPlan, Set<Integer> measures,
            NIndexPlanManager indexPlanManager) {
        return indexPlanManager.updateIndexPlan(indexPlan.getUuid(), copyForWrite -> {
            copyForWrite.setIndexes(copyForWrite.getIndexes().stream()
                    .filter(index -> measures.containsAll(index.getMeasures())).collect(Collectors.toList()));
            if (copyForWrite.getRuleBasedIndex() == null) {
                return;
            }
            val newRule = JsonUtil.deepCopyQuietly(copyForWrite.getRuleBasedIndex(), RuleBasedIndex.class);
            newRule.setLayoutIdMapping(Lists.newArrayList());

            if (newRule.getAggregationGroups() != null) {
                for (NAggregationGroup aggGroup : newRule.getAggregationGroups()) {
                    val aggMeasures = Sets.newHashSet(aggGroup.getMeasures());
                    aggGroup.setMeasures(Sets.intersection(aggMeasures, measures).toArray(new Integer[0]));
                }
            }

            copyForWrite.setRuleBasedIndex(newRule);
        });
    }

    private void removeUselessDimensions(IndexPlan indexPlan, Set<Integer> availableDimensions, boolean onlyDataflow,
            KylinConfig config) {
        val dataflowManager = NDataflowManager.getInstance(config, indexPlan.getProject());
        val deprecatedLayoutIds = indexPlan.getIndexes().stream().filter(index -> !index.isTableIndex())
                .filter(index -> !availableDimensions.containsAll(index.getDimensions()))
                .flatMap(index -> index.getLayouts().stream().map(LayoutEntity::getId)).collect(Collectors.toSet());
        val toBeDeletedLayoutIds = indexPlan.getToBeDeletedIndexes().stream().filter(index -> !index.isTableIndex())
                .filter(index -> !availableDimensions.containsAll(index.getDimensions()))
                .flatMap(index -> index.getLayouts().stream().map(LayoutEntity::getId)).collect(Collectors.toSet());
        deprecatedLayoutIds.addAll(toBeDeletedLayoutIds);
        if (deprecatedLayoutIds.isEmpty()) {
            return;
        }
        if (onlyDataflow) {
            val df = dataflowManager.getDataflow(indexPlan.getUuid());
            dataflowManager.removeLayouts(df, deprecatedLayoutIds);
            if (CollectionUtils.isNotEmpty(toBeDeletedLayoutIds)) {
                val indexPlanManager = NIndexPlanManager.getInstance(config, indexPlan.getProject());
                indexPlanManager.updateIndexPlan(indexPlan.getUuid(), copyForWrite -> {
                    copyForWrite.removeLayouts(deprecatedLayoutIds, true, true);
                });
            }
        } else {
            val indexPlanManager = NIndexPlanManager.getInstance(config, indexPlan.getProject());
            indexPlanManager.updateIndexPlan(indexPlan.getUuid(), copyForWrite -> {
                copyForWrite.removeLayouts(deprecatedLayoutIds, true, true);
                copyForWrite.removeLayouts(deprecatedLayoutIds, true, true);
            });
        }
    }

    public SegmentRange getSegmentRangeByModel(String project, String modelId, String start, String end) {
        TableRef tableRef = getManager(NDataModelManager.class, project).getDataModelDesc(modelId).getRootFactTable();
        TableDesc tableDesc = getManager(NTableMetadataManager.class, project)
                .getTableDesc(tableRef.getTableIdentity());
        return SourceFactory.getSource(tableDesc).getSegmentRange(start, end);
    }

    private void handleDatePartitionColumn(NDataModel newModel, NDataflowManager dataflowManager, NDataflow df,
            String modelId, String project, String start, String end) {
        // from having partition to no partition
        if (newModel.getPartitionDesc() == null) {
            dataflowManager.fillDfManually(df,
                    Lists.newArrayList(SegmentRange.TimePartitionedSegmentRange.createInfinite()));
            return;
        }
        // change partition column and from no partition to having partition
        if (StringUtils.isNotEmpty(start) && StringUtils.isNotEmpty(end)) {
            dataflowManager.fillDfManually(df,
                    Lists.newArrayList(getSegmentRangeByModel(project, modelId, start, end)));
        }
    }

    private void handleReloadData(NDataModel newModel, NDataModel oriModel, String project, String start, String end) {
        val config = KylinConfig.getInstanceFromEnv();
        val dataflowManager = NDataflowManager.getInstance(config, project);
        val segmentManager = NDataSegmentManager.getInstance(config, project);
        var df = dataflowManager.getDataflow(newModel.getUuid());
        val segments = df.getFlatSegments();

        segments.forEach(segmentManager::delete);
        dataflowManager.updateDataflow(df.getUuid(), copyForWrite -> {
            copyForWrite.setSegmentUuids(new Segments<>());
        });

        String modelId = newModel.getUuid();
        NDataModelManager modelManager = NDataModelManager.getInstance(config, project);
        if (newModel.isMultiPartitionModel() || oriModel.isMultiPartitionModel()) {
            boolean isMultiPartitionChange = !isMultiPartitionDescSame(oriModel.getMultiPartitionDesc(),
                    newModel.getMultiPartitionDesc())
                    || !Objects.equals(oriModel.getPartitionDesc(), newModel.getPartitionDesc());
            if (isMultiPartitionChange && newModel.isMultiPartitionModel()) {
                modelManager.updateDataModel(modelId, copyForWrite -> {
                    copyForWrite.setMultiPartitionDesc(
                            new MultiPartitionDesc(newModel.getMultiPartitionDesc().getColumns()));
                });
            }
            // Case where the date partition column of the multi partition model has also been changed
            if (!Objects.equals(oriModel.getPartitionDesc(), newModel.getPartitionDesc())) {
                handleDatePartitionColumn(newModel, dataflowManager, df, modelId, project, start, end);
            }
        } else {
            if (!Objects.equals(oriModel.getPartitionDesc(), newModel.getPartitionDesc())) {
                handleDatePartitionColumn(newModel, dataflowManager, df, modelId, project, start, end);
            } else {
                List<SegmentRange> segmentRanges = Lists.newArrayList();
                segments.forEach(segment -> segmentRanges.add(segment.getSegRange()));
                dataflowManager.fillDfManually(df, segmentRanges);
            }
        }
    }

    public BuildIndexResponse handleIndexPlanUpdateRule(String project, String model, RuleBasedIndex oldRule,
            RuleBasedIndex newRule, boolean forceFireEvent) {
        log.debug("handle indexPlan udpate rule {} {}", project, model);
        val kylinConfig = KylinConfig.getInstanceFromEnv();
        val df = NDataflowManager.getInstance(kylinConfig, project).getDataflow(model);
        val readySegs = df.getSegments();
        if (readySegs.isEmpty()) {
            return new BuildIndexResponse(BuildIndexResponse.BuildIndexType.NO_SEGMENT);
        }

        // new cuboid
        if (hasRulebaseLayoutChange(oldRule, newRule) || forceFireEvent) {
            val jobManager = JobManager.getInstance(kylinConfig, project);
            String jobId = jobManager.addIndexJob(new JobParam(model, BasicService.getUsername()));

            val buildIndexResponse = new BuildIndexResponse(BuildIndexResponse.BuildIndexType.NORM_BUILD, jobId);

            if (Objects.isNull(jobId)) {
                buildIndexResponse.setWarnCodeWithSupplier(ServerErrorCode.FAILED_CREATE_JOB_SAVE_INDEX_SUCCESS);
            }

            return buildIndexResponse;
        }

        return new BuildIndexResponse(BuildIndexResponse.BuildIndexType.NO_LAYOUT);

    }

    private boolean hasRulebaseLayoutChange(RuleBasedIndex oldRule, RuleBasedIndex newRule) {
        val originLayouts = oldRule == null ? Sets.<LayoutEntity> newHashSet() : oldRule.genCuboidLayouts();
        val targetLayouts = newRule == null ? Sets.<LayoutEntity> newHashSet() : newRule.genCuboidLayouts();
        val difference = Sets.difference(targetLayouts, originLayouts);
        return difference.size() > 0;
    }

    public IndexPlan addRuleBasedIndexBlackListLayouts(IndexPlan indexPlan, Collection<Long> blackListLayoutIds) {
        val indexPlanManager = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), indexPlan.getProject());
        return indexPlanManager.updateIndexPlan(indexPlan.getId(), indexPlanCopy -> {
            indexPlanCopy.addRuleBasedBlackList(blackListLayoutIds);
        });
    }

    public void buildForModel(String project, String modelId) {
        IndexPlan indexPlan = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), project)
                .getIndexPlan(modelId);
        if (CollectionUtils.isNotEmpty(indexPlan.getAllLayoutIds(false))) {
            final JobParam jobParam = new JobParam(modelId, BasicService.getUsername());
            jobParam.setProject(project);
            getManager(JobManager.class, project).addIndexJob(jobParam);
        }
    }

    public void buildForModelSegments(String project, String modelId, Set<String> targetSegments) {
        IndexPlan indexPlan = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), project)
                .getIndexPlan(modelId);
        if (CollectionUtils.isNotEmpty(indexPlan.getAllLayoutIds(false))) {
            final JobParam jobParam = new JobParam(modelId, BasicService.getUsername());
            jobParam.setProject(project);
            jobParam.withTargetSegments(targetSegments);
            getManager(JobManager.class, project).addRelatedIndexJob(jobParam);
        }
    }

    public void discardInvalidColsAndMeasForBrokenModel(String project, NDataModel model) {
        NTableMetadataManager tableMetadataManager = getManager(NTableMetadataManager.class, project);
        Set<String> aliasDotColSet = new HashSet<>();
        TableDesc rootFactTableDesc = tableMetadataManager.getTableDesc(model.getRootFactTableName());
        Arrays.stream(rootFactTableDesc.getColumns()).forEach(columnDesc -> {
            String aliasDotCol = rootFactTableDesc.getName() + "." + columnDesc.getName();
            aliasDotColSet.add(aliasDotCol);
        });
        List<JoinTableDesc> joinTables = model.getJoinTables();
        joinTables.forEach(joinTableDesc -> {
            TableDesc tableDesc = tableMetadataManager.getTableDesc(joinTableDesc.getTable());
            String joinTableAlias = joinTableDesc.getAlias();
            Arrays.stream(tableDesc.getColumns()).forEach(colDesc -> {
                String aliasDotCol = joinTableAlias + "." + colDesc.getName();
                aliasDotColSet.add(aliasDotCol);
            });
        });

        // check if CC-used column exists in tableDesc. If not, remove computed column desc
        model.bindComputedColumns();
        List<ComputedColumnDesc> computedColumnDescs = model.getComputedColumnDescs();
        List<ComputedColumnDesc> validCCDescs = discardInvalidComputedColumnsForBrokenModel(aliasDotColSet,
                computedColumnDescs);
        model.setComputedColumnDescs(validCCDescs);
        model.setComputedColumnUuids(
                validCCDescs.stream().map(ComputedColumnDesc::getUuid).collect(Collectors.toList()));

        //check all named columns, rule out invalid model columns and CCs
        List<NDataModel.NamedColumn> allNamedColumns = model.getAllNamedColumns();
        allNamedColumns.stream().filter(NamedColumn::isExist).forEach(col -> {
            String aliasDotColumn = col.getAliasDotColumn();
            if (!aliasDotColSet.contains(aliasDotColumn)) {
                col.setStatus(NDataModel.ColumnStatus.TOMB);
            }
        });

        //check all measures, rule out invalid measures
        List<NDataModel.Measure> allMeasures = model.getAllMeasures();
        allMeasures.stream().filter(measure -> !measure.isTomb()).forEach(measure -> {
            FunctionDesc functionDesc = measure.getFunction();
            functionDesc.getParameters().forEach(p -> {
                if (p.isColumnType()) {
                    String aliasDotColumn = p.getValue();
                    if (!aliasDotColSet.contains(aliasDotColumn)) {
                        measure.setTomb(true);
                    }
                }
            });
        });
    }

    private List<ComputedColumnDesc> discardInvalidComputedColumnsForBrokenModel(Set<String> aliasDotColSet,
            List<ComputedColumnDesc> computedColumnDescs) {
        return computedColumnDescs.stream().map(ccDesc -> {
            AtomicBoolean isValidCC = new AtomicBoolean(true);
            String calciteSyntaxExp = QueryUtil.adaptCalciteSyntax(ccDesc.getInnerExpression());
            List<Pair<String, String>> colsWithAlias = ComputedColumnUtil.ExprIdentifierFinder
                    .getExprIdentifiers(calciteSyntaxExp);
            colsWithAlias.forEach(c -> {
                String column = c.getFirst() + "." + c.getSecond();
                if (!aliasDotColSet.contains(column)) {
                    isValidCC.set(false);
                }
            });
            if (!isValidCC.get()) {
                return null;
            }
            String ccAlias = ccDesc.getTableAlias() + "." + ccDesc.getColumnName();
            aliasDotColSet.add(ccAlias);
            return ccDesc;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }
}
