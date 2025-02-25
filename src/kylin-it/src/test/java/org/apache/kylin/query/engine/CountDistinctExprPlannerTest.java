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

package org.apache.kylin.query.engine;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.calcite.plan.RelOptRule;
import org.apache.calcite.test.DiffRepository;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.query.rules.CalciteRuleTestBase;
import org.apache.kylin.query.util.HepUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class CountDistinctExprPlannerTest extends CalciteRuleTestBase {

    static final String defaultProject = "default";
    static final DiffRepository diff = DiffRepository.lookup(CountDistinctExprPlannerTest.class);

    @Before
    public void setUp() {
        createTestMetadata();
        overwriteSystemProp("kylin.query.optimized-sum-cast-double-rule-enabled", "FALSE");
        overwriteSystemProp("kylin.query.improved-sum-decimal-precision.enabled", "true");
    }

    @After
    public void tearDown() {
        cleanupTestMetadata();
    }

    @Override
    protected DiffRepository getDiffRepo() {
        return diff;
    }

    @Test
    @Ignore("For development")
    public void dumpPlans() throws IOException {
        List<Pair<String, String>> queries = readALLSQLs(KylinConfig.getInstanceFromEnv(), defaultProject,
                "query/sql_count_distinct_expr");
        CalciteRuleTestBase.StringOutput output = new CalciteRuleTestBase.StringOutput(false);
        List<RelOptRule> rules = Stream.concat(HepUtils.SumExprRules.stream(), HepUtils.CountDistinctExprRules.stream())
                .collect(Collectors.toList());
        queries.forEach(e -> checkSQLPostOptimize(defaultProject, e.getSecond(), e.getFirst(), output, rules));
        output.dump(log);
    }

    @Test
    public void testAllCases() throws IOException {
        List<Pair<String, String>> queries = readALLSQLs(KylinConfig.getInstanceFromEnv(), defaultProject,
                "query/sql_count_distinct_expr");
        List<RelOptRule> rules = Stream.concat(HepUtils.SumExprRules.stream(), HepUtils.CountDistinctExprRules.stream())
                .collect(Collectors.toList());
        queries.forEach(e -> checkSQLPostOptimize(defaultProject, e.getSecond(), e.getFirst(), null, rules));
    }

    @Test
    public void testSimpleCountDistinctExpr() {
        String SQL = "SELECT COUNT(DISTINCT CASE WHEN LSTG_FORMAT_NAME='FP-NON GTC' THEN PRICE ELSE null END) "
                + "FROM TEST_KYLIN_FACT";
        checkSQLPostOptimize(defaultProject, SQL, null, null, HepUtils.CountDistinctExprRules);
    }

}
