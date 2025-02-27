/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.optimizer

import org.apache.spark.sql.catalyst.dsl.expressions._
import org.apache.spark.sql.catalyst.dsl.plans._
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.plans.{Inner, LeftSemi, PlanTest}
import org.apache.spark.sql.catalyst.rules.RuleExecutor

class ConvertInnerJoinToSemiJoinSuite extends PlanTest {

  object Optimize extends RuleExecutor[LogicalPlan] {
    val batches =
      Batch("Convert inner join to semi", FixedPoint(1),
        new ConvertInnerJoinToSemiJoin()
      ) :: Nil
  }

  test("replace Inner Join with Left-semi Join") {
    val table1 = LocalRelation('a.int, 'b.int)
    val table2 = LocalRelation('c.int, 'd.int, 'e.int)
    val agg = Aggregate(Seq('c, 'e), Seq('c, 'e), table2)
    val join = Join(table1, agg, Inner, Option('a <=> 'c && 'b <=> 'e), JoinHint.NONE)
    val project = Project(Seq('a, 'b), join)

    val optimized = Optimize.execute(project.analyze)

    val correctAnswer =
      Project(Seq('a, 'b),
        Join(table1,
          Project(Seq('c, 'e), table2),
          LeftSemi, Option('a <=> 'c && 'b <=> 'e), JoinHint.NONE)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("replace Inner Join with Left-semi Join - agg on the left side") {
    val table1 = LocalRelation('a.int, 'b.int)
    val table2 = LocalRelation('c.int, 'd.int, 'e.int)
    val agg = Aggregate(Seq('c, 'e), Seq('c, 'e), table2)
    val join = Join(agg, table1, Inner, Option('a <=> 'c && 'b <=> 'e), JoinHint.NONE)
    val project = Project(Seq('a, 'b), join)

    val optimized = Optimize.execute(project.analyze)

    val correctAnswer =
      Project(Seq('a, 'b),
        Join(table1,
          Project(Seq('c, 'e), table2),
          LeftSemi, Option('a <=> 'c && 'b <=> 'e), JoinHint.NONE)).analyze

    comparePlans(optimized, correctAnswer)
  }

  test("should not replace") {
    {
      val table1 = LocalRelation('a.int, 'b.int)
      val table2 = LocalRelation('c.int, 'd.int)
      val agg = Aggregate(Seq('c, 'd), Seq('c, 'd), table2)
      val join = Join(table1, agg, Inner, Option('a <=> 'c), JoinHint.NONE)
      val project = Project(Seq('a, 'b), join)

      val optimized = Optimize.execute(project.analyze)
      comparePlans(optimized, project.analyze)
    }

    {
      val table1 = LocalRelation('a.int, 'b.int)
      val table2 = LocalRelation('c.int, 'd.int)
      val agg = Aggregate(Seq('c, 'd), Seq('c), table2)
      val join = Join(table1, agg, Inner, Option('a <=> 'c), JoinHint.NONE)
      val project = Project(Seq('a, 'b), join)

      val optimized = Optimize.execute(project.analyze)
      comparePlans(optimized, project.analyze)
    }
  }


}
