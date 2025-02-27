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
package org.apache.kylin.metadata.model.util;

import static org.apache.kylin.common.exception.ServerErrorCode.DUPLICATE_COMPUTED_COLUMN_EXPRESSION;
import static org.apache.kylin.common.exception.ServerErrorCode.DUPLICATE_COMPUTED_COLUMN_NAME;
import static org.apache.kylin.common.exception.ServerErrorCode.INVALID_COMPUTED_COLUMN_EXPRESSION;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.COMPUTED_COLUMN_CONFLICT_ADJUST_INFO;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.COMPUTED_COLUMN_EXPR_CONFLICT;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.COMPUTED_COLUMN_NAME_CONFLICT;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.util.SqlBasicVisitor;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.mutable.MutableInt;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.KylinRuntimeException;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.BiMap;
import org.apache.kylin.guava30.shaded.common.collect.HashBiMap;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.model.BadModelException;
import org.apache.kylin.metadata.model.BadModelException.CauseType;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.alias.AliasDeduce;
import org.apache.kylin.metadata.model.alias.AliasMapping;
import org.apache.kylin.metadata.model.alias.ExpressionComparator;
import org.apache.kylin.metadata.model.graph.JoinsGraph;
import org.apache.kylin.metadata.model.tool.CalciteParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.DigestUtils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.val;

public class ComputedColumnUtil {
    private static final Logger logger = LoggerFactory.getLogger(ComputedColumnUtil.class);
    public static final String CC_NAME_PREFIX = "CC_AUTO_";
    public static final String DEFAULT_CC_NAME = "CC_AUTO_1";

    @Setter
    private static RexStrExtractor EXTRACTOR = null;

    public static String newAutoCCName(long ts, int index) {
        return String.format(Locale.ROOT, "%s_%s_%s", ComputedColumnUtil.CC_NAME_PREFIX, ts, index);
    }

    public static String uniqueCCName(String unique) {
        return String.format(Locale.ROOT, "%s_%s", ComputedColumnUtil.CC_NAME_PREFIX, unique);
    }

    public static String shareCCNameAcrossModel(ComputedColumnDesc newCC, NDataModel newModel,
            List<NDataModel> otherModels) {
        try {
            JoinsGraph newCCGraph = getCCExprRelatedSubgraph(newCC, newModel);
            for (NDataModel existingModel : otherModels) {
                for (ComputedColumnDesc existingCC : existingModel.getComputedColumnDescs()) {
                    if (!StringUtils.equals(newCC.getTableIdentity(), existingCC.getTableIdentity())) {
                        continue;
                    }
                    JoinsGraph existCCGraph = getCCExprRelatedSubgraph(existingCC, existingModel);
                    AliasMapping aliasMapping = getAliasMappingFromJoinsGraph(newCCGraph, existCCGraph);
                    boolean sameCCExpr = isSameCCExpr(existingCC, newCC, aliasMapping);
                    if (sameCCExpr) {
                        return existingCC.getColumnName();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("share cc: '{}' name cross model fail", newCC.getExpression(), e);
            return null;
        }
        return null;
    }

    public static class ExprIdentifierFinder extends SqlBasicVisitor<SqlNode> {
        List<Pair<String, String>> columnWithTableAlias;

        ExprIdentifierFinder() {
            this.columnWithTableAlias = new ArrayList<>();
        }

        List<Pair<String, String>> getIdentifiers() {
            return columnWithTableAlias;
        }

        public static List<Pair<String, String>> getExprIdentifiers(String expr) {
            SqlNode exprNode = CalciteParser.getReadonlyExpNode(expr);
            ExprIdentifierFinder id = new ExprIdentifierFinder();
            exprNode.accept(id);
            return id.getIdentifiers();
        }

        @Override
        public SqlNode visit(SqlCall call) {
            for (SqlNode operand : call.getOperandList()) {
                if (operand != null) {
                    operand.accept(this);
                }
            }
            return null;
        }

        @Override
        public SqlNode visit(SqlIdentifier id) {
            if (id.names.size() == 2) {
                columnWithTableAlias.add(Pair.newPair(id.names.get(0), id.names.get(1)));
            }
            return null;
        }
    }

    public static Set<String> getCCUsedColsWithProject(String project, ColumnDesc columnDesc) {
        NDataModel model = getModel(project, columnDesc.getName());
        return getCCUsedColsWithModel(model, columnDesc);
    }

    static Map<String, Set<String>> getCCUsedColsMapWithModel(NDataModel model, ColumnDesc columnDesc) {
        return getCCUsedColsMap(model, columnDesc.getName());
    }

    public static Set<String> getCCUsedColsWithModel(NDataModel model, ColumnDesc columnDesc) {
        return getCCUsedCols(model, columnDesc.getName(), columnDesc.getComputedColumnExpr());
    }

    public static Set<String> getCCUsedColsWithModel(NDataModel model, ComputedColumnDesc ccDesc) {
        return getCCUsedCols(model, ccDesc.getColumnName(), ccDesc.getExpression());
    }

    public static Set<String> getAllCCUsedColsInModel(NDataModel dataModel) {
        Set<String> ccUsedColsInModel = new HashSet<>();
        List<ComputedColumnDesc> ccList = dataModel.getComputedColumnDescs();
        for (ComputedColumnDesc ccDesc : ccList) {
            ccUsedColsInModel.addAll(ComputedColumnUtil.getCCUsedColsWithModel(dataModel, ccDesc));
        }
        return ccUsedColsInModel;
    }

    public static ColumnDesc[] createComputedColumns(List<ComputedColumnDesc> computedColumnDescs,
            final TableDesc tableDesc) {
        final MutableInt id = new MutableInt(tableDesc.getColumnCount());
        return computedColumnDescs.stream()
                .filter(input -> tableDesc.getIdentity().equalsIgnoreCase(input.getTableIdentity())) //
                .map(input -> {
                    id.increment();
                    ColumnDesc columnDesc = new ColumnDesc(id.toString(), input.getColumnName(), input.getDatatype(),
                            input.getComment(), null, null, input.getInnerExpression());
                    columnDesc.init(tableDesc);
                    return columnDesc;
                }).toArray(ColumnDesc[]::new);
    }

    public static Map<String, Set<String>> getCCUsedColsMap(NDataModel model, String colName) {
        Map<String, Set<String>> usedCols = Maps.newHashMap();
        Map<String, String> aliasTableMap = getAliasTableMap(model);
        Preconditions.checkState(aliasTableMap.size() > 0, "can not find cc:%s's table alias", colName);

        ComputedColumnDesc targetCC = model.getComputedColumnDescs().stream()
                .filter(cc -> cc.getColumnName().equalsIgnoreCase(colName)) //
                .findFirst().orElse(null);
        if (targetCC == null) {
            throw new IllegalStateException(
                    "ComputedColumn(name: " + colName + ") is not on model: " + model.getUuid());
        }

        List<Pair<String, String>> colsWithAlias = ExprIdentifierFinder.getExprIdentifiers(targetCC.getExpression());
        for (Pair<String, String> cols : colsWithAlias) {
            String tableIdentifier = aliasTableMap.get(cols.getFirst());
            usedCols.putIfAbsent(tableIdentifier, Sets.newHashSet());
            usedCols.get(tableIdentifier).add(cols.getSecond());
        }
        return usedCols;
    }

    private static Set<String> getCCUsedCols(NDataModel model, String colName, String ccExpr) {
        Set<String> usedCols = new HashSet<>();
        Map<String, String> aliasTableMap = getAliasTableMap(model);
        Preconditions.checkState(aliasTableMap.size() > 0, "can not find cc:%s's table alias", colName);
        List<Pair<String, String>> colsWithAlias = ExprIdentifierFinder.getExprIdentifiers(ccExpr);
        for (Pair<String, String> cols : colsWithAlias) {
            String tableIdentifier = aliasTableMap.get(cols.getFirst());
            usedCols.add(tableIdentifier + "." + cols.getSecond());
        }
        return usedCols;
    }

    private static Map<String, String> getAliasTableMap(NDataModel model) {
        Map<String, String> tableWithAlias = new HashMap<>();
        for (String alias : model.getAliasMap().keySet()) {
            String tableName = model.getAliasMap().get(alias).getTableDesc().getIdentity();
            tableWithAlias.put(alias, tableName);
        }
        return tableWithAlias;
    }

    private static NDataModel getModel(String project, String ccName) {
        List<NDataModel> models = NDataflowManager.getInstance(KylinConfig.getInstanceFromEnv(), project)
                .listUnderliningDataModels();
        for (NDataModel model : models) {
            Set<String> computedColumnNames = model.getComputedColumnNames();
            if (computedColumnNames.contains(ccName)) {
                return model;
            }
        }
        return null;
    }

    public static void singleCCConflictCheck(NDataModel existingModel, NDataModel newModel,
            ComputedColumnDesc existingCC, ComputedColumnDesc newCC, CCConflictHandler handler) {
        AliasMapping aliasMapping = getCCAliasMapping(existingModel, newModel, existingCC, newCC);
        boolean sameModel = isSameModel(existingModel, newModel);
        boolean sameName = isSameName(existingCC, newCC);
        boolean sameCCExpr = isSameCCExpr(existingCC, newCC, aliasMapping);

        if (sameName && sameCCExpr) {
            handler.handleOnSameExprSameName(existingModel, existingCC, newCC);
        }

        if (sameName) {
            if (sameModel) {
                handler.handleOnSingleModelSameName(existingModel, existingCC, newCC);
            }

            if (!isSameAliasTable(existingCC, newCC, aliasMapping)) {
                handler.handleOnWrongPositionName(existingModel, existingCC, newCC, aliasMapping);
            }

            if (!sameCCExpr) {
                handler.handleOnSameNameDiffExpr(existingModel, newModel, existingCC, newCC);
            }
        }

        if (sameCCExpr) {
            if (sameModel) {
                handler.handleOnSingleModelSameExpr(existingModel, existingCC, newCC);
            }

            if (!isSameAliasTable(existingCC, newCC, aliasMapping)) {
                handler.handleOnWrongPositionExpr(existingModel, existingCC, newCC, aliasMapping);
            }

            if (!sameName) {
                handler.handleOnSameExprDiffName(existingModel, existingCC, newCC);
            }
        }
    }

    private static boolean isSameModel(NDataModel existingModel, NDataModel newModel) {
        if (existingModel == null)
            return false;

        return existingModel.equals(newModel);
    }

    private static AliasMapping getAliasMappingFromJoinsGraph(JoinsGraph fromGraph, JoinsGraph toMatchGraph) {
        AliasMapping adviceAliasMapping = null;

        Map<String, String> matches = fromGraph.matchAlias(toMatchGraph, true);
        if (matches != null && !matches.isEmpty()) {
            BiMap<String, String> biMap = HashBiMap.create();
            biMap.putAll(matches);
            adviceAliasMapping = new AliasMapping(biMap);
        }
        return adviceAliasMapping;
    }

    private static AliasMapping getCCAliasMapping(NDataModel existingModel, NDataModel newModel,
            ComputedColumnDesc existingCC, ComputedColumnDesc newCC) {
        JoinsGraph newCCGraph = getCCExprRelatedSubgraph(newCC, newModel);
        JoinsGraph existCCGraph = getCCExprRelatedSubgraph(existingCC, existingModel);
        return getAliasMappingFromJoinsGraph(newCCGraph, existCCGraph);
    }

    // model X contains table f,a,b,c, and model Y contains table f,a,b,d
    // if two cc involve table a,b, they might still be treated equal regardless of the model difference on c,d
    public static JoinsGraph getCCExprRelatedSubgraph(ComputedColumnDesc cc, NDataModel model) {
        Set<String> aliasSets = CalciteParser.getUsedAliasSet(cc.getExpression());
        if (cc.getTableAlias() != null) {
            aliasSets.add(cc.getTableAlias());
        }
        return model.getJoinsGraph().getSubGraphByAlias(aliasSets);
    }

    public static boolean isSameName(ComputedColumnDesc col1, ComputedColumnDesc col2) {
        return StringUtils.equalsIgnoreCase(col1.getTableIdentity() + "." + col1.getColumnName(),
                col2.getTableIdentity() + "." + col2.getColumnName());
    }

    private static boolean isSameCCExpr(ComputedColumnDesc existingCC, ComputedColumnDesc newCC,
            AliasMapping aliasMapping) {
        if (existingCC.getExpression() == null) {
            return newCC.getExpression() == null;
        } else if (newCC.getExpression() == null) {
            return false;
        }

        return ExpressionComparator.isNodeEqual(CalciteParser.getReadonlyExpNode(newCC.getExpression()),
                CalciteParser.getReadonlyExpNode(existingCC.getExpression()), aliasMapping, AliasDeduce.NO_OP);
    }

    /**
     * search cc in model by expr
     * @param models
     * @param ccToFind cc desc containing to searching cc expr
     * @return
     */
    public static ComputedColumnDesc findCCByExpr(List<NDataModel> models, ComputedColumnDesc ccToFind) {
        for (NDataModel model : models) {
            for (ComputedColumnDesc existingCC : model.getComputedColumnDescs()) {
                AliasMapping aliasMapping = getCCAliasMapping(model, model, existingCC, ccToFind);
                if (isSameCCExpr(existingCC, ccToFind, aliasMapping)) {
                    return existingCC;
                }
            }
        }
        return null;
    }

    private static boolean isSameAliasTable(ComputedColumnDesc existingCC, ComputedColumnDesc newCC,
            AliasMapping adviceAliasMapping) {
        if (adviceAliasMapping == null) {
            return false;
        }
        String existingAlias = existingCC.getTableAlias();
        String newAlias = newCC.getTableAlias();
        return StringUtils.equals(newAlias, adviceAliasMapping.getAliasMap().get(existingAlias));
    }

    public interface CCConflictHandler {
        void handleOnWrongPositionName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping);

        void handleOnSameNameDiffExpr(NDataModel existingModel, NDataModel newModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC);

        void handleOnWrongPositionExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping);

        void handleOnSameExprDiffName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC);

        void handleOnSameExprSameName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC);

        void handleOnSingleModelSameName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC);

        void handleOnSingleModelSameExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC);
    }

    public static class BasicCCConflictHandler implements CCConflictHandler {
        @Override
        public void handleOnWrongPositionName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping) {
            // do nothing
        }

        @Override
        public void handleOnSameNameDiffExpr(NDataModel existingModel, NDataModel newModel,
                ComputedColumnDesc existingCC, ComputedColumnDesc newCC) {
            // do nothing
        }

        @Override
        public void handleOnWrongPositionExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping) {
            // do nothing
        }

        @Override
        public void handleOnSameExprDiffName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            // do nothing
        }

        @Override
        public void handleOnSameExprSameName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            // do nothing
        }

        @Override
        public void handleOnSingleModelSameName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            // do nothing
        }

        @Override
        public void handleOnSingleModelSameExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            // do nothing
        }
    }

    public static class DefaultCCConflictHandler extends BasicCCConflictHandler {

        @Override
        public void handleOnSameNameDiffExpr(NDataModel existingModel, NDataModel newModel,
                ComputedColumnDesc existingCC, ComputedColumnDesc newCC) {
            JoinsGraph ccJoinsGraph = getCCExprRelatedSubgraph(existingCC, existingModel);
            AliasMapping aliasMapping = getAliasMappingFromJoinsGraph(ccJoinsGraph, newModel.getJoinsGraph());
            String advisedExpr = aliasMapping == null ? null
                    : CalciteParser.replaceAliasInExpr(existingCC.getExpression(), aliasMapping.getAliasMap());

            String finalExpr = advisedExpr != null ? advisedExpr : existingCC.getExpression();
            String msg = String.format(Locale.ROOT, MsgPicker.getMsg().getComputedColumnNameDuplicated(),
                    newCC.getFullName(), existingModel.getAlias(), finalExpr);
            throw new BadModelException(DUPLICATE_COMPUTED_COLUMN_NAME, msg,
                    BadModelException.CauseType.SAME_NAME_DIFF_EXPR, advisedExpr, existingModel.getAlias(),
                    newCC.getFullName());
        }

        @Override
        public void handleOnWrongPositionName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping) {
            String advice = positionAliasMapping == null ? null
                    : positionAliasMapping.getAliasMap().get(existingCC.getTableAlias());

            String msg = null;

            if (advice != null) {
                msg = String.format(Locale.ROOT,
                        "Computed column %s is already defined in model %s, to reuse it you have to define it on alias table: %s",
                        newCC.getColumnName(), existingModel.getAlias(), advice);
            } else {
                msg = String.format(Locale.ROOT,
                        "Computed column %s is already defined in model %s, no suggestion could be provided to reuse it",
                        newCC.getColumnName(), existingModel.getAlias());
            }

            throw new BadModelException(msg, BadModelException.CauseType.WRONG_POSITION_DUE_TO_NAME, advice,
                    existingModel.getAlias(), newCC.getFullName());
        }

        @Override
        public void handleOnWrongPositionExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC, AliasMapping positionAliasMapping) {
            String advice = positionAliasMapping == null ? null
                    : positionAliasMapping.getAliasMap().get(existingCC.getTableAlias());

            String msg = null;

            if (advice != null) {
                msg = String.format(Locale.ROOT,
                        "Computed column %s's expression is already defined in model %s, to reuse it you have to define it on alias table: %s",
                        newCC.getColumnName(), existingModel.getAlias(), advice);
            } else {
                msg = String.format(Locale.ROOT,
                        "Computed column %s's expression is already defined in model %s, no suggestion could be provided to reuse it",
                        newCC.getColumnName(), existingModel.getAlias());
            }

            throw new BadModelException(msg, BadModelException.CauseType.WRONG_POSITION_DUE_TO_EXPR, advice,
                    existingModel.getAlias(), newCC.getFullName());
        }

        @Override
        public void handleOnSameExprDiffName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            String adviseName = existingCC.getColumnName();
            String msg = String.format(Locale.ROOT, MsgPicker.getMsg().getComputedColumnExpressionDuplicated(),
                    existingModel.getAlias(), existingCC.getColumnName());
            throw new BadModelException(DUPLICATE_COMPUTED_COLUMN_EXPRESSION, msg,
                    BadModelException.CauseType.SAME_EXPR_DIFF_NAME, adviseName, existingModel.getAlias(),
                    newCC.getFullName());
        }

        @Override
        public void handleOnSingleModelSameName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            String msg = MsgPicker.getMsg().getComputedColumnNameDuplicatedSingleModel();
            throw new BadModelException(DUPLICATE_COMPUTED_COLUMN_NAME, msg, CauseType.SELF_CONFLICT_WITH_SAME_NAME,
                    null, null, newCC.getFullName());
        }

        @Override
        public void handleOnSingleModelSameExpr(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            String ccFullName = newCC.getFullName();
            String errorMsg = "In model " + existingModel.getAlias() + ", computed columns " + existingCC.getFullName()
                    + " and " + ccFullName + " have equivalent expressions.";
            logger.error(errorMsg);
            String msg = MsgPicker.getMsg().getComputedColumnExpressionDuplicatedSingleModel();
            throw new BadModelException(DUPLICATE_COMPUTED_COLUMN_EXPRESSION, msg,
                    BadModelException.CauseType.SELF_CONFLICT_WITH_SAME_EXPRESSION, null, null, ccFullName);
        }
    }

    @AllArgsConstructor
    public static class AdjustCCConflictHandler extends DefaultCCConflictHandler {

        @Getter
        private CCConflictInfo ccConflictInfo;

        @Override
        public void handleOnSameNameDiffExpr(NDataModel existingModel, NDataModel newModel,
                ComputedColumnDesc existingCC, ComputedColumnDesc newCC) {
            val detail = new CCConflictDetail(existingModel.getAlias(), existingCC, newCC);
            ccConflictInfo.addSameNameDiffExprDetail(detail);
        }

        @Override
        public void handleOnSameExprDiffName(NDataModel existingModel, ComputedColumnDesc existingCC,
                ComputedColumnDesc newCC) {
            val detail = new CCConflictDetail(existingModel.getAlias(), existingCC, newCC);
            ccConflictInfo.addSameExprDiffNameDetail(detail);
        }

    }

    @Data
    @NoArgsConstructor
    public static class CCConflictInfo {

        private List<CCConflictDetail> sameExprDiffNameDetails = Lists.newArrayList();
        private List<CCConflictDetail> sameNameDiffExprDetails = Lists.newArrayList();

        public void addSameExprDiffNameDetail(CCConflictDetail ccConflictDetail) {
            this.sameExprDiffNameDetails.add(ccConflictDetail);
        }

        public void addSameNameDiffExprDetail(CCConflictDetail ccConflictDetail) {
            this.sameNameDiffExprDetails.add(ccConflictDetail);
        }

        public boolean noneConflict() {
            return !hasSameNameConflict() && !hasSameExprConflict();
        }

        public boolean hasSameNameConflict() {
            return CollectionUtils.isNotEmpty(this.sameNameDiffExprDetails);
        }

        public boolean hasSameExprConflict() {
            return CollectionUtils.isNotEmpty(this.sameExprDiffNameDetails);
        }

        public List<KylinException> getSameNameConflictException() {
            return this.sameNameDiffExprDetails.stream() //
                    .filter(Objects::nonNull) //
                    .map(CCConflictDetail::getNameConflictKylinException) //
                    .collect(Collectors.toList());
        }

        public List<KylinException> getSameExprConflictException() {
            return this.sameExprDiffNameDetails.stream() //
                    .filter(Objects::nonNull) //
                    .map(CCConflictDetail::getExprConflictKylinException) //
                    .collect(Collectors.toList());
        }

        public List<KylinException> getAllConflictException() {
            List<KylinException> exceptionList = Lists.newArrayList();
            exceptionList.addAll(getSameExprConflictException());
            exceptionList.addAll(getSameNameConflictException());
            return exceptionList;
        }

        public Pair<List<ComputedColumnDesc>, List<CCConflictDetail>> getAdjustedCCList(
                List<ComputedColumnDesc> inputCCDescList) {
            List<ComputedColumnDesc> resultCCDescList = Lists.newArrayList();
            List<CCConflictDetail> adjustDetails = Lists.newArrayList();

            for (ComputedColumnDesc ccDesc : inputCCDescList) {
                for (CCConflictDetail detail : this.sameExprDiffNameDetails) {
                    val existingCC = detail.getExistingCC();
                    val newCC = detail.getNewCC();
                    if (newCC.equals(ccDesc)) {
                        logger.info("adjust cc name {} to {}", newCC.getColumnName(), existingCC.getColumnName());
                        ccDesc.setColumnName(existingCC.getColumnName());
                        adjustDetails.add(detail);
                        break;
                    }
                }
                resultCCDescList.add(ccDesc);
            }
            return Pair.newPair(resultCCDescList, adjustDetails);
        }
    }

    @Data
    public static class CCConflictDetail {

        private String existingModelName;
        private ComputedColumnDesc existingCC;
        private ComputedColumnDesc newCC;

        public CCConflictDetail(String existingModelName, ComputedColumnDesc existingCC, ComputedColumnDesc newCC) {
            this.existingModelName = existingModelName;
            this.existingCC = existingCC;
            this.newCC = newCC;
        }

        public KylinException getAdjustKylinException() {
            return new KylinException(COMPUTED_COLUMN_CONFLICT_ADJUST_INFO, newCC.getColumnName(),
                    newCC.getExpression(), existingCC.getColumnName(), existingCC.getExpression(),
                    existingCC.getColumnName());
        }

        public KylinException getNameConflictKylinException() {
            return new KylinException(COMPUTED_COLUMN_NAME_CONFLICT, newCC.getColumnName(), newCC.getExpression(),
                    existingModelName);
        }

        public KylinException getExprConflictKylinException() {
            return new KylinException(COMPUTED_COLUMN_EXPR_CONFLICT, newCC.getColumnName(), newCC.getExpression(),
                    existingModelName);
        }
    }

    public static List<ComputedColumnDesc> getAuthorizedCC(List<NDataModel> modelList,
            Predicate<Set<String>> isColumnAuthorizedFunc) {
        val authorizedCC = Lists.<ComputedColumnDesc> newArrayList();
        val checkedCC = Sets.<ComputedColumnDesc> newHashSet();
        val checkedCCUsedSourceCols = Sets.<String> newHashSet();
        for (NDataModel model : modelList) {
            val ccUsedColsMap = Maps.<String, Set<String>> newHashMap();
            for (ComputedColumnDesc cc : model.getComputedColumnDescs()) {
                if (checkedCC.contains(cc)) {
                    continue;
                }
                ccUsedColsMap.put(cc.getIdentName(), ComputedColumnUtil.getCCUsedColsWithModel(model, cc));
            }

            // parse inner expression might cause error, for example timestampdiff
            // so have to do parsing cc expression recursively
            for (ComputedColumnDesc cc : model.getComputedColumnDescs()) {
                if (checkedCC.contains(cc)) {
                    continue;
                }
                val ccUsedSourceCols = Sets.<String> newHashSet();
                collectCCUsedSourceCols(cc.getIdentName(), ccUsedColsMap, ccUsedSourceCols);
                ccUsedSourceCols.removeIf(checkedCCUsedSourceCols::contains);
                if (ccUsedSourceCols.isEmpty() || isColumnAuthorizedFunc.test(ccUsedSourceCols)) {
                    authorizedCC.add(cc);
                    checkedCCUsedSourceCols.addAll(ccUsedSourceCols);
                }
                checkedCC.add(cc);
            }
        }
        return authorizedCC;
    }

    public static void collectCCUsedSourceCols(String ccColName, Map<String, Set<String>> ccUsedColsMap,
            Set<String> ccUsedSourceCols) {
        if (!ccUsedColsMap.containsKey(ccColName)) {
            ccUsedSourceCols.add(ccColName);
            return;
        }
        for (String usedColumn : ccUsedColsMap.get(ccColName)) {
            collectCCUsedSourceCols(usedColumn, ccUsedColsMap, ccUsedSourceCols);
        }
    }

    public static List<ComputedColumnDesc> deepCopy(List<ComputedColumnDesc> ccList) {
        List<ComputedColumnDesc> result = Lists.newArrayList();
        try {
            for (ComputedColumnDesc cc : ccList) {
                result.add(JsonUtil.deepCopy(cc, ComputedColumnDesc.class));
            }
        } catch (IOException e) {
            logger.error("failed to deep copy cc list", e);
        }
        return result;
    }

    public static void computeMd5(KylinConfig config, NDataModel model, ComputedColumnDesc cc) {
        if (EXTRACTOR == null) {
            throw new KylinRuntimeException("When update or insert CC, a RexStrExtractor must be Specified.");
        }
        try {
            String rexStr = EXTRACTOR.extract(model, config, cc.getInnerExpression());
            String subJoinGraphStr = ComputedColumnUtil.getCCExprRelatedSubgraph(cc, model).toString(true, true);
            cc.setExpressionMD5(
                    DigestUtils.md5DigestAsHex((subJoinGraphStr + rexStr).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new KylinException(INVALID_COMPUTED_COLUMN_EXPRESSION, "Compute md5 for cc failed!", e);
        }
    }

    public interface RexStrExtractor {
        String extract(NDataModel model, KylinConfig config, String cc) throws SqlParseException;
    }
}
