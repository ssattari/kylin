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

package org.apache.kylin.tool.security;

import static org.apache.kylin.common.persistence.metadata.jdbc.JdbcUtil.datasourceParameters;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.Charset;

import org.apache.commons.dbcp2.BasicDataSourceFactory;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.persistence.metadata.JdbcAuditLogStore;
import org.apache.kylin.common.persistence.metadata.jdbc.AuditLogRowMapper;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.LogOutputTestCase;
import org.apache.kylin.metadata.user.ManagedUser;
import org.apache.kylin.metadata.user.NKylinUserManager;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.tool.constant.StringConstant;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import lombok.val;

public class KylinPasswordResetCLITest extends LogOutputTestCase {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Before
    public void setup() {
        createTestMetadata();
        getTestConfig().setMetadataUrl(
                "testKylinPasswordResetCLITest@jdbc,driverClassName=org.h2.Driver,url=jdbc:h2:mem:db_default;DB_CLOSE_DELAY=-1;MODE=MySQL,username=sa,password=");
    }

    @After
    public void teardown() throws Exception {
        val jdbcTemplate = getJdbcTemplate();
        jdbcTemplate.batchUpdate("SHUTDOWN;");
        cleanupTestMetadata();
    }

    @Test
    public void testResetAdminPassword() throws Exception {
        val pwdEncoder = new BCryptPasswordEncoder();
        overwriteSystemProp("kylin.metadata.random-admin-password.enabled", "true");
        overwriteSystemProp("kylin.security.user-password-encoder", pwdEncoder.getClass().getName());
        overwriteSystemProp("kylin.metadata.random-admin-password.enabled", "true");
        val user = new ManagedUser("ADMIN", "KYLIN", true, Constant.ROLE_ADMIN, Constant.GROUP_ALL_USERS);
        user.setPassword(pwdEncoder.encode(user.getPassword()));
        val config = KylinConfig.getInstanceFromEnv();
        val manger = NKylinUserManager.getInstance(config);
        UnitOfWork.doInTransactionWithRetry(() -> {
            NKylinUserManager.getInstance(KylinConfig.getInstanceFromEnv()).update(user);
            return null;
        }, "_global");

        Assert.assertEquals(user.getPassword(), manger.get(user.getUsername()).getPassword());

        val modifyUser = manger.get(user.getUsername());
        modifyUser.setPassword(pwdEncoder.encode("KYLIN2"));
        UnitOfWork.doInTransactionWithRetry(() -> {
            NKylinUserManager.getInstance(KylinConfig.getInstanceFromEnv()).update(modifyUser);
            return null;
        }, "_global");
        Assert.assertEquals(modifyUser.getPassword(), manger.get(user.getUsername()).getPassword());
        Assert.assertTrue(pwdEncoder.matches("KYLIN2", manger.get(user.getUsername()).getPassword()));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output, false, Charset.defaultCharset().name()));

        KylinPasswordResetCLI.reset();

        ResourceStore.clearCache(config);
        config.clearManagers();
        val afterManager = NKylinUserManager.getInstance(config);

        Assert.assertFalse(pwdEncoder.matches("KYLIN", afterManager.get(user.getUsername()).getPassword()));
        Assert.assertTrue(output.toString(Charset.defaultCharset().name()).startsWith("The metadata backup path is"));
        Assert.assertTrue(output.toString(Charset.defaultCharset().name())
                .contains(StringConstant.ANSI_RED + "Reset password of [" + StringConstant.ANSI_RESET + "ADMIN"
                        + StringConstant.ANSI_RED + "] succeed. The password is "));
        Assert.assertTrue(output.toString(Charset.defaultCharset().name())
                .endsWith("Please keep the password properly." + StringConstant.ANSI_RESET + "\n"));

        val url = getTestConfig().getMetadataUrl();
        val jdbcTemplate = getJdbcTemplate();
        val all = jdbcTemplate.query("select * from " + url.getIdentifier() + JdbcAuditLogStore.AUDIT_LOG_SUFFIX, new AuditLogRowMapper());
        Assert.assertTrue(all.stream().anyMatch(auditLog -> auditLog.getResPath().equals("USER_INFO/ADMIN")));

        System.setOut(System.out);
    }

    JdbcTemplate getJdbcTemplate() throws Exception {
        val url = getTestConfig().getMetadataUrl();
        val props = datasourceParameters(url);
        val dataSource = BasicDataSourceFactory.createDataSource(props);
        return new JdbcTemplate(dataSource);
    }

    @Test
    public void testUpdatePasswordInQueryNode() throws Exception {
        KylinConfig config = KylinConfig.getInstanceFromEnv();
        val mode = config.getServerMode();
        try {
            config.setProperty("kylin.server.mode", "query");
            Assert.assertFalse(KylinPasswordResetCLI.reset());
            Assert.assertTrue(containsLog("Only job/all node can update metadata."));
        } finally {
            config.setProperty("kylin.server.mode", mode);
        }
    }
}
