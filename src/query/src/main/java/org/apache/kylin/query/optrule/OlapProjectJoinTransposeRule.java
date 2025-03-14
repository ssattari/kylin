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
import java.util.Iterator;
import java.util.List;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.plan.RelOptRuleCall;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.core.Join;
import org.apache.calcite.rel.core.JoinRelType;
import org.apache.calcite.rel.core.Project;
import org.apache.calcite.rel.core.RelFactories;
import org.apache.calcite.rel.rules.PushProjector;
import org.apache.calcite.rel.type.RelDataTypeField;
import org.apache.calcite.rex.RexInputRef;
import org.apache.calcite.rex.RexNode;
import org.apache.calcite.rex.RexOver;
import org.apache.calcite.runtime.PredicateImpl;
import org.apache.calcite.tools.RelBuilderFactory;

public class OlapProjectJoinTransposeRule extends RelOptRule {
    private static class SkipOverCondition extends PredicateImpl<RexNode> implements PushProjector.ExprCondition {
        @Override
        public boolean test(RexNode expr) {
            return !(expr instanceof RexOver);
        }
    }

    public static final OlapProjectJoinTransposeRule INSTANCE = new OlapProjectJoinTransposeRule(
            new SkipOverCondition(), RelFactories.LOGICAL_BUILDER);

    public static final String KY = "_KY_";

    //~ Instance fields --------------------------------------------------------

    /**
     * Condition for expressions that should be preserved in the projection.
     */
    private final PushProjector.ExprCondition preserveExprCondition;

    //~ Constructors -----------------------------------------------------------

    /**
     * Creates a ProjectJoinTransposeRule with an explicit condition.
     *
     * @param preserveExprCondition Condition for expressions that should be
     *                              preserved in the projection
     */
    public OlapProjectJoinTransposeRule(PushProjector.ExprCondition preserveExprCondition,
            RelBuilderFactory relFactory) {
        super(operand(Project.class, operand(Join.class, any())), relFactory, null);
        this.preserveExprCondition = preserveExprCondition;
    }

    //~ Methods ----------------------------------------------------------------
    private boolean projectSameInputFields(Project originProject, Join originJoin) {
        List<RelDataTypeField> inputFields = originJoin.getRowType().getFieldList();
        Iterator<RelDataTypeField> inputIterator = inputFields.iterator();
        Iterator<RexNode> projectsIterator = originProject.getProjects().iterator();
        while (inputIterator.hasNext()) {
            RelDataTypeField inputField = inputIterator.next();
            if (inputField.getName().contains(KY)) {
                continue;
            }

            if (!projectsIterator.hasNext()) {
                return false;
            }

            RexNode project = projectsIterator.next();
            if (!(project instanceof RexInputRef)) {
                return false;
            }

            RelDataTypeField projectField = inputFields.get(((RexInputRef) project).getIndex());
            if (!projectField.equals(inputField)) {
                return false;
            }
        }
        return !projectsIterator.hasNext();
    }

    // implement RelOptRule
    public void onMatch(RelOptRuleCall call) {
        Project origProj = call.rel(0);
        final Join join = call.rel(1);

        if (join.getJoinType() == JoinRelType.SEMI) {
            return; // TODO: support SemiJoin
        }
        if (projectSameInputFields(origProj, join)) {
            return;
        }

        // locate all fields referenced in the projection and join condition;
        // determine which inputs are referenced in the projection and
        // join condition; if all fields are being referenced and there are no
        // special expressions, no point in proceeding any further
        PushProjector pushProject = new PushProjector(origProj, join.getCondition(), join, preserveExprCondition,
                call.builder());
        if (pushProject.locateAllRefs()) {
            return;
        }

        // create left and right projections, projecting only those
        // fields referenced on each side
        RelNode leftProjRel = pushProject.createProjectRefsAndExprs(join.getLeft(), true, false);
        RelNode rightProjRel = pushProject.createProjectRefsAndExprs(join.getRight(), true, true);

        // convert the join condition to reference the projected columns
        RexNode newJoinFilter = null;
        int[] adjustments = pushProject.getAdjustments();
        if (join.getCondition() != null) {
            List<RelDataTypeField> projJoinFieldList = new ArrayList<>();
            projJoinFieldList.addAll(join.getSystemFieldList());
            projJoinFieldList.addAll(leftProjRel.getRowType().getFieldList());
            projJoinFieldList.addAll(rightProjRel.getRowType().getFieldList());
            newJoinFilter = pushProject.convertRefsAndExprs(join.getCondition(), projJoinFieldList, adjustments);
        }

        // create a new join with the projected children
        Join newJoinRel = join.copy(join.getTraitSet(), newJoinFilter, leftProjRel, rightProjRel, join.getJoinType(),
                join.isSemiJoinDone());

        // put the original project on top of the join, converting it to
        // reference the modified projection list
        RelNode topProject = pushProject.createNewProject(newJoinRel, adjustments);

        call.transformTo(topProject);
    }
}
