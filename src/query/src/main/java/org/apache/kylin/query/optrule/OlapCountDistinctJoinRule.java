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

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.plan.RelOptRuleOperand;
import org.apache.calcite.rel.core.Aggregate;
import org.apache.calcite.rel.core.AggregateCall;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.tools.RelBuilderFactory;
import org.apache.calcite.util.ImmutableBitSet;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableList;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.query.calcite.KylinSumSplitter;
import org.apache.kylin.query.relnode.OlapAggregateRel;
import org.apache.kylin.query.relnode.OlapJoinRel;
import org.apache.kylin.query.relnode.OlapProjectRel;
import org.apache.kylin.query.relnode.OlapRel;
import org.apache.kylin.query.util.RuleUtils;

/**
 * agg-join  ->  agg(CD)-agg(other-agg)-join
 * agg-project-join  ->  agg(CD)-agg(other-agg)-project-join
 */
public class OlapCountDistinctJoinRule extends RelOptRule {

    public static final OlapCountDistinctJoinRule COUNT_DISTINCT_JOIN_ONE_SIDE_AGG = new OlapCountDistinctJoinRule(
            operand(OlapAggregateRel.class, operand(OlapJoinRel.class, any())), RelFactories.LOGICAL_BUILDER,
            "OlapCountDistinctJoinRule:agg(contain-count-distinct)-join-oneSideAgg");

    public static final OlapCountDistinctJoinRule COUNT_DISTINCT_AGG_PROJECT_JOIN = new OlapCountDistinctJoinRule(
            operand(OlapAggregateRel.class, operand(OlapProjectRel.class, operand(OlapJoinRel.class, any()))),
            RelFactories.LOGICAL_BUILDER, "OlapCountDistinctJoinRule:agg(contain-count-distinct)-agg-project-join");

    public OlapCountDistinctJoinRule(RelOptRuleOperand operand, RelBuilderFactory relBuilderFactory,
            String description) {
        super(operand, relBuilderFactory, description);
    }

    @Override
    public boolean matches(RelOptRuleCall call) {
        final OlapAggregateRel aggregate = call.rel(0);
        final OlapJoinRel join;
        if (call.rel(1) instanceof OlapJoinRel) {
            join = call.rel(1);
        } else {
            join = call.rel(2);
        }
        return aggregate.isContainCountDistinct() && RuleUtils.isJoinOnlyOneAggChild(join);
    }

    @Override
    public void onMatch(RelOptRuleCall call) {
        final OlapAggregateRel aggregate = call.rel(0);
        final OlapRel inputRel = call.rel(1);

        // build bottom aggRelNode
        final ImmutableList.Builder<AggregateCall> bottomAggCallsBuilder = ImmutableList.builder();
        ImmutableBitSet.Builder bottomGroupSetBuilder = ImmutableBitSet.builder();
        bottomGroupSetBuilder.addAll(aggregate.getGroupSet());

        for (AggregateCall agg : aggregate.getAggCallList()) {
            if (agg.getAggregation().getKind() == SqlKind.COUNT && agg.isDistinct()) {
                bottomGroupSetBuilder.addAll(Lists.newArrayList(agg.getArgList()));
            } else {
                bottomAggCallsBuilder.add(agg.copy(Lists.newArrayList(agg.getArgList()), agg.filterArg));
            }
        }

        ImmutableBitSet bottomGroupSetBuild = bottomGroupSetBuilder.build();
        ImmutableList<AggregateCall> bottomAggCallsBuild = bottomAggCallsBuilder.build();
        List<Integer> bottomGroupSets = bottomGroupSetBuild.asList();
        final Aggregate bottomAggregate = aggregate.copy(aggregate.getTraitSet(), inputRel, aggregate.indicator,
                bottomGroupSetBuild, null, bottomAggCallsBuild);

        // build top aggRelNode
        ImmutableBitSet.Builder topGroupSet = ImmutableBitSet.builder();
        List<Integer> topGroupSetList = new ArrayList<>();
        setTopAggregateGroupSet(bottomAggregate, aggregate, topGroupSetList, topGroupSet);

        int topAggArgsIndex = bottomGroupSets.size();
        final ImmutableList.Builder<AggregateCall> topAggCalls = ImmutableList.builder();
        for (AggregateCall agg : aggregate.getAggCallList()) {
            if (agg.getAggregation().getKind() == SqlKind.COUNT && agg.isDistinct()) {
                ArrayList<Integer> aggArgsList = new ArrayList<>();
                for (Integer arg : agg.getArgList()) {
                    aggArgsList.add(bottomGroupSets.indexOf(arg));
                }
                topAggCalls.add(agg.copy(aggArgsList, agg.filterArg));
            } else {
                if (agg.getAggregation().getKind() == SqlKind.COUNT) {
                    topAggCalls.add(AggregateCall.create(SqlStdOperatorTable.SUM0, false, false,
                            Lists.newArrayList(topAggArgsIndex++), -1, agg.type, agg.name));
                } else if (agg.getAggregation().getKind() == SqlKind.SUM) {
                    topAggCalls.add(AggregateCall.create(KylinSumSplitter.KYLIN_SUM, false, false, false,
                            Lists.newArrayList(topAggArgsIndex++), -1, agg.distinctKeys, agg.collation, agg.type,
                            agg.name));
                } else {
                    topAggCalls.add(agg.copy(Lists.newArrayList(topAggArgsIndex++), agg.filterArg));
                }
            }
        }

        final Aggregate topAggregate = aggregate.copy(aggregate.getTraitSet(), bottomAggregate, aggregate.indicator,
                topGroupSet.build(), null, topAggCalls.build());

        call.transformTo(topAggregate);
    }

    private void setTopAggregateGroupSet(Aggregate bottomAggregate, Aggregate aggregate, List<Integer> topGroupSetList,
            ImmutableBitSet.Builder topGroupSet) {
        List<Integer> bottomAggregateGroupIndexList = bottomAggregate.getGroupSet().asList();
        List<Integer> aggregateGroupIndexList = aggregate.getGroupSet().asList();
        for (int i = 0; i < bottomAggregateGroupIndexList.size(); i++) {
            if (aggregateGroupIndexList.contains(bottomAggregateGroupIndexList.get(i))) {
                topGroupSetList.add(i);
            }
        }
        topGroupSet.addAll(topGroupSetList);
    }
}
