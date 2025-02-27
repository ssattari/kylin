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

import java.util.Arrays;

import org.apache.kylin.common.util.NLocalFileMetadataTestCase;
import org.apache.kylin.engine.spark.utils.SparkJobFactoryUtils;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.model.util.ComputedColumnUtil;
import org.apache.kylin.metadata.user.ManagedUser;
import org.apache.kylin.query.util.ComputedColumnRewriter;
import org.apache.kylin.rest.constant.Constant;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.ImportResource;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import org.springframework.test.context.web.WebAppConfiguration;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = ServiceTestBase.SpringConfig.class)
@WebAppConfiguration(value = "../common-service/src/test/resources")
@TestPropertySource(properties = { "spring.cloud.nacos.discovery.enabled = false" })
@TestPropertySource(properties = { "spring.session.store-type = NONE" })
@ActiveProfiles({ "testing", "test" })
@PowerMockIgnore({ "com.sun.security.*", "org.w3c.*", "javax.xml.*", "org.xml.*", "org.apache.*", "org.w3c.dom.*",
        "org.apache.cxf.*", "javax.management.*", "javax.script.*", "org.apache.hadoop.*", "javax.security.*",
        "java.security.*", "javax.crypto.*", "javax.net.ssl.*", "org.apache.kylin.profiler.AsyncProfiler" })
public class ServiceTestBase extends NLocalFileMetadataTestCase {

    @Autowired
    @Qualifier("userService")
    UserService userService;

    @BeforeClass
    public static void setupResource() {
        staticCreateTestMetadata();
        Authentication authentication = new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        ComputedColumnUtil.setEXTRACTOR(ComputedColumnRewriter::extractCcRexNode);
    }

    @AfterClass
    public static void tearDownResource() {
        staticCleanupTestMetadata();
    }

    @Before
    public void setUp() {
        // init job factory
        SparkJobFactoryUtils.initJobFactory();
        JobContextUtil.cleanUp();
        createTestMetadata();
        Authentication authentication = new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN);
        SecurityContextHolder.getContext().setAuthentication(authentication);
        if (!userService.userExists("ADMIN")) {
            userService.createUser(new ManagedUser("ADMIN", "KYLIN", false, Arrays.asList(//
                    new SimpleGrantedAuthority(Constant.ROLE_ADMIN), new SimpleGrantedAuthority(Constant.ROLE_ANALYST),
                    new SimpleGrantedAuthority(Constant.ROLE_MODELER))));
        }

        if (!userService.userExists("MODELER")) {
            userService.createUser(new ManagedUser("MODELER", "MODELER", false, Arrays.asList(//
                    new SimpleGrantedAuthority(Constant.ROLE_ANALYST),
                    new SimpleGrantedAuthority(Constant.ROLE_MODELER))));
        }

        if (!userService.userExists("ANALYST")) {
            userService.createUser(new ManagedUser("ANALYST", "ANALYST", false, Arrays.asList(//
                    new SimpleGrantedAuthority(Constant.ROLE_ANALYST))));
        }
    }

    @After
    public void tearDown() {
        cleanupTestMetadata();
    }

    /**
     * better keep this method, otherwise cause error
     * org.apache.kylin.rest.service.TestBase.initializationError
     */
    @Test
    public void test() {
    }

    @Configuration
    @ComponentScan("org.apache.kylin.rest")
    @ImportResource(locations = { "classpath:applicationContext.xml", "classpath:kylinSecurity.xml" })
    @EnableAsync
    public static class SpringConfig {
    }
}
