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

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.type.RelDataType;
import org.apache.calcite.rel.type.RelDataTypeFactory;
import org.apache.calcite.rel.type.RelDataTypeSystem;
import org.apache.calcite.rex.RexCall;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.schema.AggregateFunction;
import org.apache.calcite.schema.FunctionParameter;
import org.apache.calcite.schema.impl.AggregateFunctionImpl;
import org.apache.calcite.sql.SqlAggFunction;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.type.BasicSqlType;
import org.apache.calcite.sql.type.ExplicitReturnTypeInference;
import org.apache.calcite.sql.type.InferTypes;
import org.apache.calcite.sql.type.OperandTypes;
import org.apache.calcite.sql.type.ReturnTypes;
import org.apache.calcite.sql.type.SqlTypeFamily;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.sql.type.SqlTypeUtil;
import org.apache.calcite.sql.validate.SqlUserDefinedAggFunction;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.Optionality;
import org.apache.calcite.util.Util;
import org.apache.kylin.measure.bitmap.BitmapCountAggFunc;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.query.relnode.OlapAggregateRel;
import org.apache.kylin.query.relnode.OlapProjectRel;
import org.apache.kylin.query.util.AggExpressionUtil;
import org.apache.kylin.query.util.AggExpressionUtil.AggExpression;
import org.apache.kylin.query.util.RuleUtils;

/**
 * COUNT(DISTINCT (CASE WHEN ... THEN COLUMN ELSE NULL))
 * only support the form COUNT(DISTINCT (CASE WHEN ... THEN COLUMN ELSE NULL))
 */
public class CountDistinctCaseWhenFunctionRule extends AbstractAggCaseWhenFunctionRule {

    public static final CountDistinctCaseWhenFunctionRule INSTANCE = new CountDistinctCaseWhenFunctionRule(
            operand(OlapAggregateRel.class, operand(OlapProjectRel.class, null,
                    input -> !AggExpressionUtil.hasAggInput(input), RelOptRule.any())),
            RelFactories.LOGICAL_BUILDER, "CountDistinctCaseWhenFunctionRule");

    public CountDistinctCaseWhenFunctionRule(RelOptRuleOperand operand, RelBuilderFactory relBuilderFactory,
            String description) {
        super(operand, relBuilderFactory, description);
    }

    private boolean isCountDistinctCaseExpr(AggregateCall aggregateCall, Project inputProject) {
        if (aggregateCall.getArgList().size() != 1) {
            return false;
        }

        // check for count distinct agg
        if (!(aggregateCall.getAggregation().getKind() == SqlKind.COUNT && aggregateCall.isDistinct())) {
            return false;
        }

        int input = aggregateCall.getArgList().get(0);
        RexNode expression = inputProject.getProjects().get(input);
        // check if it's in the form of case when ... then col else null
        if (expression.getKind() != SqlKind.CASE) {
            return false;
        }
        RexCall caseCall = (RexCall) expression;
        if (caseCall.getOperands().size() != 3) {
            return false;
        }
        return isSimpleCaseWhen(inputProject, caseCall.getOperands().get(1), caseCall.getOperands().get(2))
                || isSimpleCaseWhen(inputProject, caseCall.getOperands().get(2), caseCall.getOperands().get(1));
    }

    private boolean isSimpleCaseWhen(Project inputProject, RexNode n1, RexNode n2) {
        if (RuleUtils.isNullLiteral(n1)) {
            if (n2 instanceof RexInputRef) {
                return RuleUtils.isPlainTableColumn(((RexInputRef) n2).getIndex(), inputProject.getInput(0));
            } else if (RuleUtils.isCast(n2) && ((RexCall) n2).getOperands().get(0) instanceof RexInputRef) {
                return RuleUtils.isPlainTableColumn(((RexInputRef) ((RexCall) n2).getOperands().get(0)).getIndex(),
                        inputProject.getInput(0)) && !isNeedTackCast(n2);
            }
        }
        return false;
    }

    @Override
    protected boolean checkAggCaseExpression(Aggregate oldAgg, Project oldProject) {
        for (AggregateCall call : oldAgg.getAggCallList()) {
            if (isCountDistinctCaseExpr(call, oldProject)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected boolean isApplicableWithSumCaseRule(AggregateCall aggregateCall, Project project) {
        SqlKind aggFunction = aggregateCall.getAggregation().getKind();

        return aggFunction == SqlKind.SUM || aggFunction == SqlKind.SUM0 || aggFunction == SqlKind.MAX
                || aggFunction == SqlKind.MIN || aggFunction == SqlKind.COUNT && !aggregateCall.isDistinct()
                || isCountDistinctCaseExpr(aggregateCall, project)
                || FunctionDesc.FUNC_BITMAP_UUID.equalsIgnoreCase(aggregateCall.getName());
    }

    @Override
    protected boolean isApplicableAggExpression(AggExpression aggExpr) {
        return aggExpr.isCountDistinctCase();
    }

    @Override
    protected SqlAggFunction getBottomAggFunc(AggregateCall aggCall) {
        return createBitmapAggFunc();
    }

    @Override
    protected SqlAggFunction getTopAggFunc(AggregateCall aggCall) {
        SqlKind kind = aggCall.getAggregation().getKind();
        if (Objects.requireNonNull(kind) == SqlKind.COUNT) {
            return aggCall.isDistinct() ? createBitmapCountAggFunc() : SqlStdOperatorTable.SUM0;
        }
        return aggCall.getAggregation();
    }

    private static SqlAggFunction createBitmapAggFunc() {
        return createCustomAggFunction(FunctionDesc.FUNC_BITMAP_UUID,
                new BasicSqlType(RelDataTypeSystem.DEFAULT, SqlTypeName.ANY), BitmapCountAggFunc.class, null);
    }

    private static SqlAggFunction createBitmapCountAggFunc() {
        return createCustomAggFunction(FunctionDesc.FUNC_BITMAP_COUNT,
                new BasicSqlType(RelDataTypeSystem.DEFAULT, SqlTypeName.BIGINT), BitmapCountAggFunc.class, null);
    }

    private static SqlAggFunction createCustomAggFunction(String funcName, RelDataType returnType,
            Class<?> customAggFuncClz, RelDataTypeFactory typeFactory) {

        SqlIdentifier sqlIdentifier = new SqlIdentifier(funcName, new SqlParserPos(1, 1));
        AggregateFunction aggFunction = AggregateFunctionImpl.create(customAggFuncClz);
        List<RelDataType> argTypes = new ArrayList<RelDataType>();
        List<SqlTypeFamily> typeFamilies = new ArrayList<SqlTypeFamily>();
        for (FunctionParameter o : aggFunction.getParameters()) {
            if (typeFactory != null) {
                final RelDataType type = o.getType(typeFactory);
                argTypes.add(type);
                typeFamilies.add(Util.first(type.getSqlTypeName().getFamily(), SqlTypeFamily.ANY));
            }
        }
        ExplicitReturnTypeInference explicitReturnTypeInference = null;
        if (returnType != null) {
            explicitReturnTypeInference = ReturnTypes.explicit(returnType);
        }
        return new SqlUserDefinedAggFunction(sqlIdentifier, explicitReturnTypeInference, InferTypes.explicit(argTypes),
                OperandTypes.family(typeFamilies), aggFunction, false, false, Optionality.FORBIDDEN, typeFactory);

    }

    @Override
    protected String getBottomAggPrefix() {
        return "COUNT_DISTINCT_CASE$";
    }

    @Override
    protected boolean isValidAggColumnExpr(RexNode rexNode) {
        return !RuleUtils.isNullLiteral(rexNode);
    }

    /**
     * only successful non coerce cast can remove.
     */
    @Override
    protected boolean isNeedTackCast(RexNode rexNode) {
        if (!RuleUtils.isCast(rexNode)) {
            return false;
        }
        return !SqlTypeUtil.canCastFrom(rexNode.getType(), ((RexCall) rexNode).getOperands().get(0).getType(), false);
    }
}
