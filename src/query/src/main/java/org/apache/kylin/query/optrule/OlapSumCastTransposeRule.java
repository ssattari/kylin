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

package org.apache.kylin.query.optrule;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.plan.hep.HepRelVertex;
import org.apache.calcite.rel.RelCollations;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexBuilder;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.SqlTypeFactoryImpl;
import org.apache.calcite.tools.RelBuilder;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.metadata.datatype.DataType;
import org.apache.kylin.query.calcite.KylinRelDataTypeSystem;
import org.apache.kylin.query.relnode.ContextUtil;
import org.apache.kylin.query.relnode.OlapAggregateRel;
import org.apache.kylin.query.relnode.OlapProjectRel;
import org.apache.kylin.query.util.AggExpressionUtil;
import org.apache.kylin.query.util.RuleUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OlapSumCastTransposeRule extends RelOptRule {
    private static final Logger logger = LoggerFactory.getLogger(OlapSumCastTransposeRule.class);

    public static final OlapSumCastTransposeRule INSTANCE = new OlapSumCastTransposeRule(
            operand(OlapAggregateRel.class,
                    operand(OlapProjectRel.class, null, OlapSumCastTransposeRule::needSumCastTranspose, any())),
            RelFactories.LOGICAL_BUILDER, "OlapSumTransCastToThenRule");

    public OlapSumCastTransposeRule(RelOptRuleOperand operand, RelBuilderFactory relBuilderFactory,
            String description) {
        super(operand, relBuilderFactory, description);
    }

    public static boolean needSumCastTranspose(Project project) {
        if (project.getInput() instanceof HepRelVertex
                && ((HepRelVertex) project.getInput()).getCurrentRel() instanceof OlapAggregateRel) {
            return false;
        }
        List<RexNode> childExps = project.getProjects();
        for (RexNode rexNode : childExps) {
            if (RuleUtils.containCast(rexNode)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        Aggregate originalAgg = call.rel(0);
        Project originalProject = call.rel(1);

        for (AggregateCall aggCall : originalAgg.getAggCallList()) {
            if (AggExpressionUtil.isSum(aggCall.getAggregation().kind)) {
                int index = aggCall.getArgList().get(0);
                RexNode value = originalProject.getProjects().get(index);
                if (RuleUtils.containCast(value)) {
                    RexNode rexNode = ((RexCall) value).getOperands().get(0);
                    DataType dataType = DataType.getType(rexNode.getType().getSqlTypeName().getName());
                    return dataType.isNumberFamily() || dataType.isIntegerFamily();
                }
            }
        }
        return false;
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        try {
            RelBuilder relBuilder = call.builder();
            Aggregate originalAgg = call.rel(0);
            Project originalProject = call.rel(1);

            RelNode relNode = transposeSumCast(relBuilder, originalAgg, originalProject);
            ContextUtil.dumpCalcitePlan("new plan", relNode, logger);
            call.transformTo(relNode);
        } catch (Exception e) {
            logger.error("sql cannot apply sum cast transpose rule ", e);
        }
    }

    private RelNode transposeSumCast(RelBuilder relBuilder, Aggregate oldAgg, Project oldProject) {
        // #0 Set base input
        relBuilder.push(oldProject.getInput());

        List<AggExpressionUtil.AggExpression> aggExpressions = oldAgg.getAggCallList().stream()
                .map(AggExpressionUtil.AggExpression::new).collect(Collectors.toList());

        // #1 Build bottom project
        List<RexNode> bottomProjectList = buildBottomProject(oldProject, aggExpressions);
        relBuilder.project(bottomProjectList);

        // #2 Build bottom aggregate
        ImmutableBitSet bottomAggGroupSet = oldAgg.getGroupSet();
        RelBuilder.GroupKey groupKey = relBuilder.groupKey(bottomAggGroupSet);
        List<AggregateCall> aggCalls = buildBottomAggregate(relBuilder, aggExpressions,
                bottomAggGroupSet.cardinality());
        relBuilder.aggregate(groupKey, aggCalls);

        // #3 Build top project
        List<RexNode> caseProjList = buildTopProject(relBuilder, oldProject, oldAgg, aggExpressions);
        relBuilder.project(caseProjList);

        return relBuilder.build();
    }

    private List<RexNode> buildBottomProject(Project oldProject, List<AggExpressionUtil.AggExpression> aggExpressions) {
        List<RexNode> bottomProjectList = Lists.newArrayList();
        bottomProjectList.addAll(oldProject.getProjects());

        RelDataTypeSystem typeSystem = new KylinRelDataTypeSystem();
        RelDataTypeFactory typeFactory = new SqlTypeFactoryImpl(typeSystem);

        for (AggExpressionUtil.AggExpression aggExpression : aggExpressions) {
            AggregateCall aggCall = aggExpression.getAggCall();
            if (AggExpressionUtil.isSum(aggCall.getAggregation().kind)) {
                int index = aggCall.getArgList().get(0);
                RexNode value = oldProject.getProjects().get(index);
                if (RuleUtils.containCast(value)) {
                    bottomProjectList.set(index, ((RexCall) (value)).operands.get(0));
                    RelDataType type = ((RexCall) (value)).operands.get(0).getType();
                    type = typeSystem.deriveSumType(typeFactory, type);
                    aggExpression.setType(type);
                }
            }
        }
        return bottomProjectList;
    }

    private List<AggregateCall> buildBottomAggregate(RelBuilder relBuilder,
            List<AggExpressionUtil.AggExpression> aggExpressions, int bottomAggOffset) {
        List<AggregateCall> bottomAggCalls = Lists.newArrayList();

        for (AggExpressionUtil.AggExpression aggExpression : aggExpressions) {
            AggregateCall aggCall = aggExpression.getAggCall();
            if (AggExpressionUtil.isSum(aggCall.getAggregation().kind)) {
                AggregateCall oldAggCall = aggExpression.getAggCall();
                bottomAggCalls.add(AggregateCall.create(SqlStdOperatorTable.SUM, false, false, false,
                        aggExpression.getAggCall().getArgList(), -1, null, RelCollations.EMPTY, bottomAggOffset,
                        relBuilder.peek(), aggExpression.getType(), oldAggCall.name));
            } else {
                bottomAggCalls.add(aggExpression.getAggCall());
            }
        }

        return bottomAggCalls;
    }

    private List<RexNode> buildTopProject(RelBuilder relBuilder, Project oldProject, Aggregate oldAgg,
            List<AggExpressionUtil.AggExpression> aggExpressions) {
        List<RexNode> topProjectList = Lists.newArrayList();
        RexBuilder rexBuilder = relBuilder.getRexBuilder();

        int i = 0;
        int groupSize = oldAgg.getGroupSet().asSet().size();
        for (; i < groupSize; i++) {
            topProjectList.add(relBuilder.getRexBuilder().makeInputRef(relBuilder.peek(), i));
        }

        for (AggExpressionUtil.AggExpression aggExpression : aggExpressions) {
            AggregateCall aggCall = aggExpression.getAggCall();
            if (AggExpressionUtil.isSum(aggCall.getAggregation().kind)) {
                int index = aggCall.getArgList().get(0);
                RexNode value = oldProject.getProjects().get(index);
                if (RuleUtils.containCast(value)) {
                    RelDataType type = ((RexCall) value).type;
                    if (type instanceof BasicSqlType && type.getPrecision() < aggCall.getType().getPrecision()) {
                        type = aggCall.getType();
                    }
                    value = relBuilder.getRexBuilder().makeCast(type,
                            relBuilder.getRexBuilder().makeInputRef(relBuilder.peek(), i));
                    topProjectList.add(value);
                } else if (RuleUtils.isNotNullLiteral(value)) {
                    value = relBuilder.getRexBuilder().makeInputRef(relBuilder.peek(), i);
                    topProjectList.add(value);
                } else {
                    topProjectList.add(rexBuilder.makeBigintLiteral(BigDecimal.ZERO));
                }
            } else {
                RexNode rexNode = relBuilder.getRexBuilder().makeInputRef(relBuilder.peek(), i);
                topProjectList.add(rexNode);
            }
            i++;
        }

        return topProjectList;
    }
}
