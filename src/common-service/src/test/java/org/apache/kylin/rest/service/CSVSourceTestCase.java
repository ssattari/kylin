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

import java.sql.Connection;
import java.sql.DriverManager;
import java.util.Comparator;
import java.util.List;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.source.jdbc.H2Database;
import org.junit.Before;

import lombok.val;

public class CSVSourceTestCase extends ServiceTestBase {

    protected String getProject() {
        return "default";
    }

    protected Connection h2Connection;
    protected H2Database h2DB;

    @Before
    public void setUp() {
        super.setUp();
        NProjectManager projectManager = NProjectManager.getInstance(KylinConfig.getInstanceFromEnv());
        ProjectInstance projectInstance = projectManager.getProject(getProject());
        val overrideKylinProps = projectInstance.getOverrideKylinProps();
        overrideKylinProps.put("kylin.query.force-limit", "-1");
        overrideKylinProps.put("kylin.source.default", "9");
        ProjectInstance projectInstanceUpdate = ProjectInstance.create(projectInstance.getName(),
                projectInstance.getOwner(), projectInstance.getDescription(), overrideKylinProps);
        projectManager.updateProject(projectInstance, projectInstanceUpdate.getName(),
                projectInstanceUpdate.getDescription(), projectInstanceUpdate.getOverrideKylinProps());
        projectManager.forceDropProject("broken_test");
        projectManager.forceDropProject("bad_query_test");
    }

    protected void setupPushdownEnv() throws Exception {
        Class.forName("org.h2.Driver");
        getTestConfig().setProperty("kylin.query.pushdown.runner-class-name",
                "org.apache.kylin.query.pushdown.PushDownRunnerJdbcImpl");
        getTestConfig().setProperty("kylin.query.pushdown.partition-check.runner-class-name",
                "org.apache.kylin.query.pushdown.PushDownRunnerJdbcImpl");
        getTestConfig().setProperty("kylin.query.pushdown-enabled", "true");
        // Load H2 Tables (inner join)
        h2Connection = DriverManager.getConnection("jdbc:h2:mem:db_default;DB_CLOSE_DELAY=-1", "sa", "");
        h2DB = new H2Database(h2Connection, getTestConfig(), "default");
        h2DB.loadAllTables();

        overwriteSystemProp("kylin.query.pushdown.jdbc.url", "jdbc:h2:mem:db_default;SCHEMA=`DEFAULT`");
        overwriteSystemProp("kylin.query.pushdown.jdbc.driver", "org.h2.Driver");
        overwriteSystemProp("kylin.query.pushdown.jdbc.username", "sa");
        overwriteSystemProp("kylin.query.pushdown.jdbc.password", "");
    }

    protected void cleanPushdownEnv() throws Exception {
        getTestConfig().setProperty("kylin.query.pushdown.runner-class-name", "");
        getTestConfig().setProperty("kylin.query.pushdown-enabled", "false");
        // Load H2 Tables (inner join)
        h2DB.dropAll();
        h2Connection.close();
    }

    public NDataModelManager spyNDataModelManager() throws Exception {
        return spyManagerByProject(NDataModelManager.getInstance(getTestConfig(), getProject()),
                NDataModelManager.class, getProject());
    }

    public NIndexPlanManager spyNIndexPlanManager() throws Exception {
        return spyManagerByProject(NIndexPlanManager.getInstance(getTestConfig(), getProject()),
                NIndexPlanManager.class, getProject());
    }

    public NDataflowManager spyNDataflowManager() throws Exception {
        return spyManagerByProject(NDataflowManager.getInstance(getTestConfig(), getProject()), NDataflowManager.class,
                getProject());
    }

    protected List<AbstractExecutable> getRunningExecutables(String project, String model) {
        List<AbstractExecutable> runningExecutables = ExecutableManager
                .getInstance(KylinConfig.getInstanceFromEnv(), project).getRunningExecutables(project, model);
        runningExecutables.sort(Comparator.comparing(AbstractExecutable::getCreateTime));
        return runningExecutables;
    }

    protected void deleteJobByForce(AbstractExecutable executable) {
        JobContextUtil.withTxAndRetry(() -> {
            val exManager = ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv(), "default");
            exManager.updateJobOutput(executable.getId(), ExecutableState.DISCARDED);
            exManager.deleteJob(executable.getId());
            return null;
        });
    }

}
