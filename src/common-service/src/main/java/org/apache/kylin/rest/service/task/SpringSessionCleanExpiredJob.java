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

package org.apache.kylin.rest.service.task;

import org.apache.kylin.job.JobContext;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.DefaultExecutable;
import org.apache.kylin.job.execution.ExecuteResult;
import org.apache.kylin.job.factory.JobFactory;
import org.apache.kylin.rest.util.SpringContext;
import org.springframework.context.ApplicationContext;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class SpringSessionCleanExpiredJob extends AbstractExecutable {

    public static class Factory extends JobFactory {

        @Override
        protected DefaultExecutable create(JobBuildParams jobBuildParams) {
            return SpringSessionCleanExpiredJob.create(jobBuildParams);
        }
    }

    public SpringSessionCleanExpiredJob() {
        super();
    }

    public SpringSessionCleanExpiredJob(Object notSetId) {
        super(notSetId);
    }

    public static DefaultExecutable create(JobFactory.JobBuildParams jobBuildParams) {
        DefaultExecutable job = new DefaultExecutable();
        SpringSessionCleanExpiredJob innerJob = new SpringSessionCleanExpiredJob();
        innerJob.setParam(PARENT_ID, job.getJobId());
        job.addTask(innerJob);
        job.setJobType(jobBuildParams.getJobType());
        return job;
    }

    @Override
    protected ExecuteResult doWork(JobContext context) {
        ApplicationContext applicationContext = SpringContext.getApplicationContext();
        applicationContext.getBean(JdbcIndexedSessionRepository.class).cleanUpExpiredSessions();
        log.debug("Clean up expired sessions successfully.");
        return ExecuteResult.createSucceed();
    }
}
