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

import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_ID_NOT_EXIST;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_NAME_NOT_EXIST;
import static org.apache.kylin.metadata.model.NTableMetadataManager.getInstance;
import static org.mockito.ArgumentMatchers.eq;

import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.security.UserGroupInformation;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.constant.ObsConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.persistence.Serializer;
import org.apache.kylin.common.scheduler.EventBusFactory;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.JobContext;
import org.apache.kylin.job.constant.JobStatusEnum;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.acl.AclTCR;
import org.apache.kylin.metadata.acl.AclTCRManager;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ManagementType;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.SegmentRange;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.recommendation.candidate.JdbcRawRecStore;
import org.apache.kylin.metadata.streaming.KafkaConfig;
import org.apache.kylin.metadata.user.ManagedUser;
import org.apache.kylin.query.util.PushDownUtil;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.request.AutoMergeRequest;
import org.apache.kylin.rest.request.DateRangeRequest;
import org.apache.kylin.rest.request.TopTableRequest;
import org.apache.kylin.rest.response.AutoMergeConfigResponse;
import org.apache.kylin.rest.response.NInitTablesResponse;
import org.apache.kylin.rest.response.TableDescResponse;
import org.apache.kylin.rest.response.TableNameResponse;
import org.apache.kylin.rest.response.TableRefresh;
import org.apache.kylin.rest.response.TablesAndColumnsResponse;
import org.apache.kylin.rest.source.DataSourceState;
import org.apache.kylin.rest.source.NHiveSourceInfo;
import org.apache.kylin.rest.util.AclEvaluate;
import org.apache.kylin.rest.util.AclUtil;
import org.apache.kylin.streaming.jobs.StreamingJobListener;
import org.apache.kylin.streaming.manager.StreamingJobManager;
import org.apache.spark.sql.SparderEnv;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.val;
import lombok.var;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TableServiceTest extends CSVSourceTestCase {
    public static final String S3_ROLE_ARN_KEY_FORMAT = "fs.s3a.bucket.%s.assumed.role.arn";
    public static final String S3_ENDPOINT_KEY_FORMAT = "fs.s3a.bucket.%s.endpoint";
    @InjectMocks
    private final TableService tableService = Mockito.spy(new TableService());

    @Mock
    private final ModelService modelService = Mockito.spy(ModelService.class);

    @Mock
    private final AclTCRServiceSupporter aclTCRService = Mockito.spy(AclTCRServiceSupporter.class);

    @Mock
    private final AclEvaluate aclEvaluate = Mockito.spy(AclEvaluate.class);

    @InjectMocks
    private final UserAclService userAclService = Mockito.spy(new UserAclService());

    @Mock
    private final UserService userService = Mockito.spy(UserService.class);

    @InjectMocks
    private AccessService accessService = Mockito.spy(new AccessService());

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Mock
    protected IUserGroupService userGroupService = Mockito.spy(NUserGroupService.class);

    @Mock
    private KafkaService kafkaServiceMock = Mockito.mock(KafkaService.class);

    @Mock
    private AclService aclService = Mockito.mock(AclService.class);

    @InjectMocks
    private FusionModelService fusionModelService = Mockito.spy(new FusionModelService());

    @InjectMocks
    private JobSupporter jobInfoService = Mockito.spy(JobSupporter.class);

    private final StreamingJobListener eventListener = new StreamingJobListener();

    @Before
    public void setUp() {
        JobContextUtil.cleanUp();
        super.setUp();
        overwriteSystemProp("HADOOP_USER_NAME", "root");
        ReflectionTestUtils.setField(aclEvaluate, "aclUtil", Mockito.spy(AclUtil.class));
        ReflectionTestUtils.setField(modelService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(tableService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(tableService, "modelService", modelService);
        ReflectionTestUtils.setField(tableService, "aclTCRService", aclTCRService);
        ReflectionTestUtils.setField(tableService, "userGroupService", userGroupService);
        ReflectionTestUtils.setField(tableService, "kafkaService", kafkaServiceMock);
        ReflectionTestUtils.setField(fusionModelService, "modelService", modelService);
        ReflectionTestUtils.setField(tableService, "fusionModelService", fusionModelService);
        ReflectionTestUtils.setField(userAclService, "userService", userService);
        ReflectionTestUtils.setField(accessService, "userAclService", userAclService);
        ReflectionTestUtils.setField(accessService, "userService", userService);
        ReflectionTestUtils.setField(accessService, "aclService", aclService);
        ReflectionTestUtils.setField(tableService, "accessService", accessService);
        ReflectionTestUtils.setField(tableService, "jobInfoService", jobInfoService);
        NProjectManager projectManager = NProjectManager.getInstance(KylinConfig.getInstanceFromEnv());
        ProjectInstance projectInstance = projectManager.getProject("default");
        LinkedHashMap<String, String> overrideKylinProps = projectInstance.getOverrideKylinProps();
        overrideKylinProps.put("kylin.query.force-limit", "-1");
        overrideKylinProps.put("kylin.source.default", "9");
        ProjectInstance projectInstanceUpdate = ProjectInstance.create(projectInstance.getName(),
                projectInstance.getOwner(), projectInstance.getDescription(), overrideKylinProps);
        projectManager.updateProject(projectInstance, projectInstanceUpdate.getName(),
                projectInstanceUpdate.getDescription(), projectInstanceUpdate.getOverrideKylinProps());
        Mockito.doReturn(Collections.singletonList("admin")).when(userService).listSuperAdminUsers();
        try {
            new JdbcRawRecStore(getTestConfig());
        } catch (Exception e) {
            //
        }
        EventBusFactory.getInstance().register(eventListener, true);

        JobContext jobContext = JobContextUtil.getJobContext(getTestConfig());
        try {
            // need not start job scheduler
            jobContext.destroy();
        } catch (Exception e) {
            log.error("Destroy jobContext failed.");
            throw new RuntimeException("Destroy jobContext failed.", e);
        }
        // Streaming jon need this lock.
        jobContext.getLockClient().start();
    }

    @After
    public void tearDown() {
        EventBusFactory.getInstance().unregister(eventListener);
        JobContextUtil.cleanUp();
        super.tearDown();
        FileUtils.deleteQuietly(new File("metastore_db"));
        FileUtils.deleteQuietly(new File("../modeling-service/metastore_db"));
    }

    @Test
    public void testGetTableDesc() throws IOException {
        List<Integer> sourceType = new ArrayList<>();
        sourceType.add(1); // Kafka table
        sourceType.add(9); // Hive table
        List<TableDesc> tableDesc = tableService.getTableDesc("default", true, "", "DEFAULT", true, sourceType, 12)
                .getFirst();
        Assert.assertEquals(12, tableDesc.size());
        List<TableDesc> tableDesc2 = tableService
                .getTableDesc("default", true, "TEST_COUNTRY", "DEFAULT", false, sourceType, 10).getFirst();
        Assert.assertEquals(1, tableDesc2.size());
        List<TableDesc> tables3 = tableService.getTableDesc("default", true, "", "", true, sourceType, 100).getFirst();
        Assert.assertEquals(21, tables3.size());
        List<TableDesc> tables = tableService
                .getTableDesc("default", true, "TEST_KYLIN_FACT", "DEFAULT", true, sourceType, 10).getFirst();
        Assert.assertEquals("TEST_KYLIN_FACT", tables.get(0).getName());
        Assert.assertEquals(5633024, ((TableDescResponse) tables.get(0)).getStorageSize());
        Assert.assertEquals(0, ((TableDescResponse) tables.get(0)).getTotalRecords());

        List<TableDesc> table2 = tableService.getTableDesc("default", true, "country", "DEFAULT", true, sourceType, 10)
                .getFirst();
        Assert.assertEquals("TEST_COUNTRY", table2.get(0).getName());
        Assert.assertEquals(0L, ((TableDescResponse) table2.get(0)).getStorageSize());

        val manager = NTableMetadataManager.getInstance(getTestConfig(), "default");
        val countryTable = manager.copyForWrite(manager.getTableDesc("DEFAULT.TEST_COUNTRY"));
        countryTable.setLastSnapshotPath("cannot/find/it");
        manager.updateTableDesc(countryTable);

        table2 = tableService.getTableDesc("default", true, "country", "DEFAULT", true, sourceType, 10).getFirst();
        Assert.assertEquals("TEST_COUNTRY", table2.get(0).getName());
        Assert.assertEquals(0L, ((TableDescResponse) table2.get(0)).getStorageSize());

        // get a not existing table desc
        tableDesc = tableService.getTableDesc("default", true, "not_exist_table", "DEFAULT", false, sourceType, 10)
                .getFirst();
        Assert.assertEquals(0, tableDesc.size());

        tableDesc = tableService.getTableDesc("streaming_test", true, "", "DEFAULT", true, sourceType, 10).getFirst();
        Assert.assertEquals(2, tableDesc.size());
        val tableMetadataManager = getInstance(getTestConfig(), "streaming_test");
        var tableDesc1 = tableMetadataManager.getTableDesc("DEFAULT.SSB_TOPIC");
        Assert.assertTrue(tableDesc1.isAccessible(getTestConfig().isStreamingEnabled()));
        getTestConfig().setProperty("kylin.streaming.enabled", "false");
        tableDesc = tableService.getTableDesc("streaming_test", true, "", "DEFAULT", true, sourceType, 10).getFirst();
        Assert.assertEquals(0, tableDesc.size());
        // check kafka table
        Assert.assertFalse(tableDesc1.isAccessible(getTestConfig().isStreamingEnabled()));

        // check batch table
        tableDesc1 = tableMetadataManager.getTableDesc("SSB.CUSTOMER");
        Assert.assertTrue(tableDesc1.isAccessible(getTestConfig().isStreamingEnabled()));
    }

    @Test
    public void testGetTableDescAndVerifyColumnsInfo() throws IOException {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc tableDesc = tableMgr.getTableDesc(tableIdentity);
        final TableExtDesc oldExtDesc = tableMgr.getOrCreateTableExt(tableDesc);

        // mock table ext desc
        TableExtDesc tableExt = new TableExtDesc(oldExtDesc);
        tableExt.setIdentity(tableIdentity);
        TableExtDesc.ColumnStats col1 = new TableExtDesc.ColumnStats();
        col1.setCardinality(100);
        col1.setTableExtDesc(tableExt);
        col1.setColumnName(tableDesc.getColumns()[0].getName());
        col1.setMinValue("America");
        col1.setMaxValue("Zimbabwe");
        col1.setNullCount(0);
        tableExt.setColumnStats(Lists.newArrayList(col1));
        List<String[]> sampleRows = new ArrayList<>();
        sampleRows.add(new String[] { "America" });
        tableExt.setSampleRows(sampleRows);
        tableMgr.mergeAndUpdateTableExt(oldExtDesc, tableExt);

        // verify the column stats update successfully
        final TableExtDesc newTableExt = tableMgr.getTableExtIfExists(tableDesc);
        Assert.assertEquals(1, newTableExt.getAllColumnStats().size());

        // call api to check tableDescResponse has the correct value
        final List<TableDesc> tables = tableService
                .getTableDesc("newten", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertEquals(1, tables.size());
        Assert.assertTrue(tables.get(0) instanceof TableDescResponse);
        TableDescResponse t = (TableDescResponse) tables.get(0);
        final TableDescResponse.ColumnDescResponse[] extColumns = t.getExtColumns();
        Assert.assertEquals(100L, extColumns[0].getCardinality().longValue());
        Assert.assertEquals("America", extColumns[0].getMinValue());
        Assert.assertEquals("Zimbabwe", extColumns[0].getMaxValue());
        Assert.assertEquals(0L, extColumns[0].getNullCount().longValue());

        // check sample rows
        Assert.assertEquals(1, t.getSamplingRows().size());
    }

    @Test
    public void testGetSamplingRows() throws IOException {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc tableDesc = tableMgr.getTableDesc(tableIdentity);
        final TableExtDesc oldExtDesc = tableMgr.getOrCreateTableExt(tableDesc);

        // mock table ext desc
        TableExtDesc tableExt = new TableExtDesc(oldExtDesc);
        tableExt.setIdentity(tableIdentity);
        TableExtDesc.ColumnStats col1 = new TableExtDesc.ColumnStats();
        col1.setCardinality(100);
        col1.setTableExtDesc(tableExt);
        col1.setColumnName(tableDesc.getColumns()[0].getName());
        col1.setMinValue("America");
        col1.setMaxValue("Zimbabwe");
        col1.setNullCount(0);
        tableExt.setColumnStats(Lists.newArrayList(col1));
        List<String[]> sampleRows = new ArrayList<>();
        sampleRows.add(new String[] { "America" });
        tableExt.setSampleRows(sampleRows);
        tableMgr.mergeAndUpdateTableExt(oldExtDesc, tableExt);

        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("test", "test", Constant.ROLE_MODELER));
        Mockito.when(userService.isGlobalAdmin(Mockito.anyString())).thenReturn(true);
        Mockito.when(userAclService.hasUserAclPermissionInProject(Mockito.anyString(), Mockito.anyString()))
                .thenReturn(false);
        Mockito.when(userService.loadUserByUsername(eq("test"))).thenReturn(new ManagedUser());

        List<TableDesc> tableExtList = tableService
                .getTableDesc("newten", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertEquals(0, ((TableDescResponse) tableExtList.get(0)).getSamplingRows().size());
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN));
    }

    @Test
    public void testGetTableDescWithSchemaChange() throws IOException {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc tableDesc = tableMgr.getTableDesc(tableIdentity);
        final TableExtDesc oldExtDesc = tableMgr.getOrCreateTableExt(tableDesc);

        // mock table ext desc
        TableExtDesc tableExt = new TableExtDesc(oldExtDesc);
        tableExt.setIdentity(tableIdentity);
        TableExtDesc.ColumnStats col1 = new TableExtDesc.ColumnStats();
        col1.setCardinality(100);
        col1.setTableExtDesc(tableExt);
        col1.setColumnName(tableDesc.getColumns()[0].getName());
        col1.setMinValue("America");
        col1.setMaxValue("Zimbabwe");
        col1.setNullCount(0);
        TableExtDesc.ColumnStats col2 = new TableExtDesc.ColumnStats();
        col2.setCardinality(1000);
        col2.setTableExtDesc(tableExt);
        col2.setColumnName(tableDesc.getColumns()[1].getName());
        col2.setMinValue("2300.0");
        col2.setMaxValue("2600.0");
        col2.setNullCount(0);
        TableExtDesc.ColumnStats col3 = new TableExtDesc.ColumnStats();
        col3.setCardinality(10000);
        col3.setTableExtDesc(tableExt);
        col3.setColumnName(tableDesc.getColumns()[2].getName());
        col3.setMinValue("3300.0");
        col3.setMaxValue("3600.0");
        col3.setNullCount(0);
        TableExtDesc.ColumnStats col4 = new TableExtDesc.ColumnStats();
        col4.setCardinality(40000);
        col4.setTableExtDesc(tableExt);
        col4.setColumnName(tableDesc.getColumns()[3].getName());
        col4.setMinValue("AAAA");
        col4.setMaxValue("ZZZZ");
        col4.setNullCount(10);
        tableExt.setColumnStats(Lists.newArrayList(col1, col2, col3, col4));
        tableExt.setJodID("949afe5d-0221-420f-92db-cdd91cb31ac8");
        tableMgr.mergeAndUpdateTableExt(oldExtDesc, tableExt);

        // verify the column stats update successfully
        final TableExtDesc newTableExt = tableMgr.getTableExtIfExists(tableDesc);
        Assert.assertEquals(4, newTableExt.getAllColumnStats().size());

        // table desc schema change
        TableDesc changedTable = new TableDesc(tableDesc);
        final ColumnDesc[] columns = changedTable.getColumns();
        Assert.assertEquals(4, columns.length);
        columns[0].setName("COUNTRY_NEW");
        columns[1].setName(columns[3].getName());
        columns[2].setDatatype("float");
        ColumnDesc[] newColumns = new ColumnDesc[3];
        System.arraycopy(columns, 0, newColumns, 0, 3);
        changedTable.setColumns(newColumns);
        tableMgr.updateTableDesc(changedTable);

        // verify update table desc changed successfully
        final TableDesc confirmedTableDesc = tableMgr.getTableDesc(tableIdentity);
        Assert.assertEquals(3, confirmedTableDesc.getColumnCount());
        Assert.assertEquals("COUNTRY_NEW", confirmedTableDesc.getColumns()[0].getName());
        Assert.assertEquals("NAME", confirmedTableDesc.getColumns()[1].getName());
        Assert.assertEquals("float", confirmedTableDesc.getColumns()[2].getDatatype());

        // call api to check tableDescResponse has the correct value
        final List<TableDesc> tables = tableService
                .getTableDesc("newten", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertEquals(1, tables.size());
        Assert.assertTrue(tables.get(0) instanceof TableDescResponse);
        TableDescResponse t = (TableDescResponse) tables.get(0);
        final TableDescResponse.ColumnDescResponse[] extColumns = t.getExtColumns();
        Assert.assertNull(extColumns[0].getCardinality());
        Assert.assertNull(extColumns[0].getMinValue());
        Assert.assertNull(extColumns[0].getMaxValue());
        Assert.assertNull(extColumns[0].getNullCount());
        Assert.assertEquals(40000L, extColumns[1].getCardinality().longValue());
        Assert.assertEquals("AAAA", extColumns[1].getMinValue());
        Assert.assertEquals("ZZZZ", extColumns[1].getMaxValue());
        Assert.assertEquals(10L, extColumns[1].getNullCount().longValue());
        Assert.assertEquals(10000L, extColumns[2].getCardinality().longValue());
        Assert.assertEquals("3300.0", extColumns[2].getMinValue());
        Assert.assertEquals("3600.0", extColumns[2].getMaxValue());
        Assert.assertEquals("float", extColumns[2].getDatatype());
    }

    @Test
    public void testFilterSamplingRows() {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc originTableDesc = tableMgr.getTableDesc(tableIdentity);
        AclTCR aclTCR = new AclTCR();
        AclTCR.Table table = new AclTCR.Table();
        AclTCR.ColumnRow columnRow = new AclTCR.ColumnRow();
        AclTCR.Column column = new AclTCR.Column();
        column.add("COUNTRY");
        column.add("LONGITUDE");
        column.add("NAME");
        columnRow.setColumn(column);
        // Equal Condition Row
        AclTCR.Row row = new AclTCR.Row();
        AclTCR.RealRow realRow = new AclTCR.RealRow();
        realRow.add("country_a");
        row.put("COUNTRY", realRow);
        columnRow.setRow(row);
        // Like Condition Row
        AclTCR.Row likeRow = new AclTCR.Row();
        AclTCR.RealRow likeRealRow = new AclTCR.RealRow();
        likeRealRow.add("name\\_\\%%");
        likeRow.put("NAME", likeRealRow);
        columnRow.setLikeRow(likeRow);

        table.put("DEFAULT.TEST_COUNTRY", columnRow);
        aclTCR.setTable(table);
        List<AclTCR> aclTCRs = Lists.newArrayList(aclTCR);
        TableDesc tableDesc = tableService.getAuthorizedTableDesc(getProject(), false, originTableDesc, aclTCRs);
        TableDescResponse tableDescResponse = new TableDescResponse(tableDesc);

        List<String[]> sampleRows = Lists.newArrayList();
        sampleRows.add(new String[] { "country_a", "10.10", "11.11", "name_%a" });
        sampleRows.add(new String[] { "country_b", "20.20", "22.22", "name_%b" });
        sampleRows.add(new String[] { "country_c", "30.30", "33.33", "name_%c" });
        sampleRows.add(new String[] { "country_d", "40.40", "44.44", "name_%d" });
        tableDescResponse.setSamplingRows(sampleRows);

        tableService.filterSamplingRows("newten", tableDescResponse, false, aclTCRs);

        Assert.assertEquals(1, tableDescResponse.getSamplingRows().size());
        Assert.assertEquals("country_a,11.11,name_%a",
                String.join(",", tableDescResponse.getSamplingRows().get(0)));
    }

    @Test
    public void testExtractTableMeta() {
        String[] tables = { "DEFAULT.TEST_ACCOUNT", "DEFAULT.TEST_KYLIN_FACT" };
        List<Pair<TableDesc, TableExtDesc>> result = tableService.extractTableMeta(tables, "default");
        Assert.assertEquals(2, result.size());

        thrown.expect(IllegalArgumentException.class);
        thrown.expectMessage("Can’t find the table. Please check and try again");
        String[] emptyTables = new String[] { "" };
        tableService.extractTableMeta(emptyTables, "default");
    }

    @Test
    public void testExtraTableMetaException() {
        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "Can’t load table \"DEFAULT.NOT_EXISTS\". Please ensure that the table(s) could be found in the data source.");
        String[] notExistsTables = new String[] { "DEFAULT.NOT_EXISTS" };
        tableService.extractTableMeta(notExistsTables, "default");
    }

    @Test
    public void testLoadTableToProject() throws IOException {
        List<TableDesc> tables = tableService
                .getTableDesc("default", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        TableDesc nTableDesc = new TableDesc(tables.get(0));
        TableExtDesc tableExt = new TableExtDesc();
        tableExt.setIdentity("DEFAULT.TEST_COUNTRY");
        TableExtDesc tableExtDesc = new TableExtDesc(tableExt);
        String result = tableService.loadTableToProject(nTableDesc, tableExtDesc, "default");
        Assert.assertEquals("DEFAULT.TEST_COUNTRY", result);
    }

    @Test
    public void testLoadTableToProjectWithS3Role() throws IOException {
        getTestConfig().setProperty("kylin.env.use-dynamic-role-credential-in-table", "true");
        assert !SparderEnv.getSparkSession().conf().contains(String.format(S3_ROLE_ARN_KEY_FORMAT, "testbucket"));
        List<TableDesc> tables = tableService
                .getTableDesc("default", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        TableDesc nTableDesc = new TableDesc(tables.get(0));
        TableExtDesc tableExt = new TableExtDesc();
        tableExt.setIdentity("DEFAULT.TEST_COUNTRY");
        TableExtDesc tableExtDesc = new TableExtDesc(tableExt);
        tableExtDesc.addDataSourceProp(TableExtDesc.S3_ROLE_PROPERTY_KEY, "testRole");
        tableExtDesc.addDataSourceProp(TableExtDesc.LOCATION_PROPERTY_KEY, "s3://testbucket/path");
        tableExtDesc.addDataSourceProp(TableExtDesc.S3_ENDPOINT_KEY, "us-west-2.amazonaws.com");
        String result = tableService.loadTableToProject(nTableDesc, tableExtDesc, "default");
        assert SparderEnv.getSparkSession().conf().get(String.format(S3_ROLE_ARN_KEY_FORMAT, "testbucket"))
                .equals("testRole");
        assert SparderEnv.getSparkSession().conf().get(String.format(S3_ENDPOINT_KEY_FORMAT, "testbucket"))
                .equals("us-west-2.amazonaws.com");
        Assert.assertEquals(nTableDesc.getIdentity(), result);
    }

    @Test
    public void testAddAndBroadcastSparkSession() {
        getTestConfig().setProperty("kylin.env.use-dynamic-role-credential-in-table", "true");
        tableService.addAndBroadcastSparkSession(null);
        TableExtDesc.RoleCredentialInfo roleCredentialInfo;
        String type = ObsConfig.S3.getType();
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2", "", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert !SparderEnv.getSparkSession().conf().contains("fs.s3a.bucket2.testbucket.aws.credentials.provider");
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2", "testRole", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert SparderEnv.getSparkSession().conf().get(String.format(S3_ROLE_ARN_KEY_FORMAT, "testbucket2"))
                .equals("testRole");

        getTestConfig().setProperty("kylin.env.use-dynamic-role-credential-in-table", "false");
        getTestConfig().setProperty("kylin.env.use-dynamic-S3-role-credential-in-table", "true");
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2", "testRole", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert SparderEnv.getSparkSession().conf().get(String.format(S3_ROLE_ARN_KEY_FORMAT, "testbucket2"))
                .equals("testRole");

        getTestConfig().setProperty("kylin.env.use-dynamic-S3-role-credential-in-table", "false");
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket1", "testRole", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert !SparderEnv.getSparkSession().conf().contains("fs.s3a.bucket.testbucket1.aws.credentials.provider");

        type = ObsConfig.OSS.getType();
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2", "", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert !SparderEnv.getSparkSession().conf().contains("fs.oss.bucket2.testbucket.credentials.provider");

        getTestConfig().setProperty("kylin.env.use-dynamic-role-credential-in-table", "true");
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2",
                "acs:ram::1111111:role/readonly,acs:ram::1111111:role/readwrite",
                "oss-cn-shanghai-internal.aliyuncs.com", type, "cn-shanghai");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        // fs.oss.bucket.testbucket2.credentials.provider
        assert SparderEnv.getSparkSession().conf()
                .contains(String.format(ObsConfig.OSS.getAssumedRoleCredentialProviderKey(), "testbucket2"));
        assert SparderEnv.getSparkSession().conf()
                .contains(String.format(ObsConfig.OSS.getCredentialProviderKey(), "testbucket2"));
        assert SparderEnv.getSparkSession().conf()
                .contains(String.format(ObsConfig.OSS.getEndpointKey(), "testbucket2"));
        assert SparderEnv.getSparkSession().conf().contains(String.format(ObsConfig.OSS.getRegionKey(), "testbucket2"));
        assert SparderEnv.getSparkSession().conf()
                .contains(String.format(ObsConfig.OSS.getRoleArnKey(), "testbucket2"));
        assert SparderEnv.getSparkSession().conf().get("fs.oss.bucket.testbucket2.credentials.provider")
                .equals(ObsConfig.OSS.getCredentialProviderValue());
        assert SparderEnv.getSparkSession().conf().get("fs.oss.bucket.testbucket2.assumed.role.credentials.provider")
                .equals(ObsConfig.OSS.getAssumedRoleCredentialProviderValue());
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket2", "testRole", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert SparderEnv.getSparkSession().conf().get(String.format(ObsConfig.OSS.getRoleArnKey(), "testbucket2"))
                .equals("testRole");

        getTestConfig().setProperty("kylin.env.use-dynamic-role-credential-in-table", "false");
        roleCredentialInfo = new TableExtDesc.RoleCredentialInfo("testbucket3", "testRole", "", type, "");
        tableService.addAndBroadcastSparkSession(roleCredentialInfo);
        assert !SparderEnv.getSparkSession().conf().contains("fs.oss.bucket.testbucket3.credentials.provider");

    }

    @Test
    public void testLoadCaseSensitiveTableToProject() throws IOException {
        NTableMetadataManager tableManager = tableService.getManager(NTableMetadataManager.class, "case_sensitive");
        Serializer<TableDesc> serializer = tableManager.getTableMetadataSerializer();
        String contents = StringUtils.join(Files.readAllLines(
                new File("src/test/resources/ut_meta/case_sensitive/table_desc/CASE_SENSITIVE.TEST_KYLIN_FACT.json")
                        .toPath(),
                Charset.defaultCharset()), "\n");
        InputStream originStream = IOUtils.toInputStream(contents, Charset.defaultCharset());
        TableDesc origin = serializer.deserialize(new DataInputStream(originStream));
        TableExtDesc tableExt = new TableExtDesc();
        tableExt.setIdentity("CASE_SENSITIVE.TEST_KYLIN_FACT");
        TableExtDesc tableExtDesc = new TableExtDesc(tableExt);
        String result = tableService.loadTableToProject(origin, tableExtDesc, "case_sensitive");
        Assert.assertEquals("CASE_SENSITIVE.TEST_KYLIN_FACT", result);
        ObjectMapper mapper = new ObjectMapper();
        String jsonContent = mapper.writeValueAsString(origin);
        InputStream savedStream = IOUtils.toInputStream(jsonContent, Charset.defaultCharset());
        TableDesc saved = serializer.deserialize(new DataInputStream(savedStream));

        Assert.assertEquals("test_kylin_fact", saved.getCaseSensitiveName());
        Assert.assertEquals("TEST_KYLIN_FACT", saved.getName());
        Assert.assertEquals("case_sensitive", saved.getCaseSensitiveDatabase());
        Assert.assertEquals("CASE_SENSITIVE", saved.getDatabase());
        Assert.assertEquals("trans_id", saved.getColumns()[0].getCaseSensitiveName());
        Assert.assertEquals("TRANS_ID", saved.getColumns()[0].getName());

    }

    @Test
    public void testReloadExistTable() throws IOException {
        testLoadTableToProject();
        testLoadTableToProject();
    }

    @Test
    public void testUnloadTable() {
        TableDesc tableDesc = new TableDesc();
        List<ColumnDesc> columns = new ArrayList<>();
        columns.add(new ColumnDesc());
        ColumnDesc[] colomnArr = new ColumnDesc[1];
        tableDesc.setColumns(columns.toArray(colomnArr));
        tableDesc.setName("TEST_UNLOAD");
        tableDesc.setDatabase("DEFAULT");
        TableExtDesc tableExt = new TableExtDesc();
        tableExt.setIdentity("DEFAULT.TEST_UNLOAD");
        TableExtDesc tableExtDesc = new TableExtDesc(tableExt);
        String result = tableService.loadTableToProject(tableDesc, tableExtDesc, "default");
        NTableMetadataManager nTableMetadataManager = NTableMetadataManager
                .getInstance(KylinConfig.getInstanceFromEnv(), "default");
        Assert.assertEquals("DEFAULT.TEST_UNLOAD", result);
        val size = nTableMetadataManager.listAllTables().size();
        String unloadedTable = tableService.unloadTable("default", "DEFAULT.TEST_UNLOAD", false);
        Assert.assertEquals(tableDesc.getIdentity(), unloadedTable);

        Assert.assertNull(nTableMetadataManager.getTableDesc("DEFAULT.TEST_UNLOAD"));
        Assert.assertEquals(size - 1, nTableMetadataManager.listAllTables().size());
    }

    @Test
    public void testUnloadTable_RemoveDB() {
        String removeDB = "EDW";
        NProjectManager npr = NProjectManager.getInstance(KylinConfig.getInstanceFromEnv());
        NTableMetadataManager tableManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(),
                "default");
        npr.updateProject("default", copyForWrite -> copyForWrite.setDefaultDatabase(removeDB));
        Assert.assertEquals(removeDB, npr.getDefaultDatabase("default"));

        for (TableDesc table : tableManager.listAllTables()) {
            if (removeDB.equalsIgnoreCase(table.getDatabase())) {
                tableService.unloadTable("default", table.getIdentity(), false);
            }
        }

        Assert.assertEquals("DEFAULT", npr.getDefaultDatabase("default"));
    }

    @Test
    public void testUnloadTable_RemoveModels() throws IOException {
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), "default");
        val originSize = dfMgr.listUnderliningDataModels().size();
        val response = tableService.preUnloadTable("default", "EDW.TEST_SITES");
        Assert.assertTrue(response.isHasModel());
        tableService.unloadTable("default", "EDW.TEST_SITES", true);
        Assert.assertEquals(originSize - 4, dfMgr.listUnderliningDataModels().size());
    }

    @Test
    public void testUnloadNotExistTable() {
        String tableNotExist = "DEFAULT.not_exist_table";
        thrown.expect(KylinException.class);
        thrown.expectMessage(String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), tableNotExist));
        tableService.unloadTable("default", tableNotExist, false);
    }

    @Test
    public void testPrepareUnloadNotExistTable() throws IOException {
        String tableNotExist = "DEFAULT.not_exist_table";
        thrown.expect(KylinException.class);
        thrown.expectMessage(String.format(Locale.ROOT, MsgPicker.getMsg().getTableNotFound(), tableNotExist));
        tableService.preUnloadTable("default", tableNotExist);
    }

    @Test
    public void testUnloadKafkaTable() {
        String project = "streaming_test";
        NTableMetadataManager tableManager = NTableMetadataManager.getInstance(KylinConfig.getInstanceFromEnv(),
                project);
        StreamingJobManager mgr = StreamingJobManager.getInstance(getTestConfig(), project);
        var buildJobId = "e78a89dd-847f-4574-8afa-8768b4228b72_build";
        var mergeJobId = "e78a89dd-847f-4574-8afa-8768b4228b72_merge";
        var buildJobMeta = mgr.getStreamingJobByUuid(buildJobId);
        var mergeJobMeta = mgr.getStreamingJobByUuid(mergeJobId);
        Assert.assertNotNull(buildJobMeta);
        Assert.assertNotNull(mergeJobMeta);
        for (TableDesc table : tableManager.listAllTables()) {
            if (table.isKafkaTable() && "P_LINEORDER_STR".equalsIgnoreCase(table.getKafkaConfig().getName())) {
                tableService.unloadTable(project, table.getIdentity(), true);
            }
        }
        buildJobId = "e78a89dd-847f-4574-8afa-8768b4228b72_build";
        mergeJobId = "e78a89dd-847f-4574-8afa-8768b4228b72_merge";
        buildJobMeta = mgr.getStreamingJobByUuid(buildJobId);
        mergeJobMeta = mgr.getStreamingJobByUuid(mergeJobId);
        Assert.assertNull(buildJobMeta);
        Assert.assertNull(mergeJobMeta);
    }

    @Test
    public void testGetSourceDbNames() throws Exception {
        List<String> dbNames = tableService.getSourceDbNames("default");
        ArrayList<String> dbs = Lists.newArrayList(dbNames);
        Assert.assertTrue(dbs.contains("DEFAULT"));
    }

    @Test
    public void testGetSourceTableNames() throws Exception {
        List<String> tableNames = tableService.getSourceTableNames("default", "DEFAULT", "");
        Assert.assertTrue(tableNames.contains("TEST_ACCOUNT"));
    }

    @Test
    public void testNormalizeHiveTableName() {
        String tableName = tableService.normalizeHiveTableName("DEFaULT.TeST_ACCOUNT");
        Assert.assertEquals("DEFAULT.TEST_ACCOUNT", tableName);
    }

    @Test
    public void testGetPartitionFormatForbidden() throws Exception {
        setupPushdownEnv();
        final String table = "DEFAULT.TEST_KYLIN_FACT";
        final NTableMetadataManager tableMgr = getInstance(getTestConfig(), "default");
        final TableDesc tableDesc = tableMgr.getTableDesc(table);
        tableDesc.setTableType(TableDesc.TABLE_TYPE_VIEW);
        tableMgr.updateTableDesc(tableDesc);
        try {
            tableService.getPartitionColumnFormat("default", table, "CAL_DT", null);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertEquals(MsgPicker.getMsg().getViewDateFormatDetectionError(), e.getMessage());
        }
    }

    @Test
    public void testGetPartitionFormatException() throws Exception {
        setupPushdownEnv();
        getTestConfig().setProperty("kylin.query.pushdown.partition-check.runner-class-name", "org.apache.kylin.AAA");
        final String table = "DEFAULT.TEST_KYLIN_FACT";
        try {
            tableService.getPartitionColumnFormat("default", table, "CAL_DT", null);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertEquals(MsgPicker.getMsg().getPushdownPartitionFormatError(), e.getMessage());
        }
    }

    @Test
    public void testGetTableAndColumns() {
        List<TablesAndColumnsResponse> result = tableService.getTableAndColumns("default");
        Assert.assertEquals(21, result.size());
    }

    @Test
    public void testGetSegmentRange() {
        DateRangeRequest dateRangeRequest = mockDateRangeRequest();
        SegmentRange segmentRange = tableService.getSegmentRangeByTable(dateRangeRequest);
        Assert.assertTrue(segmentRange instanceof SegmentRange.TimePartitionedSegmentRange);
    }

    @Test
    public void testSetTop() throws IOException {
        TopTableRequest topTableRequest = mockTopTableRequest();
        tableService.setTop(topTableRequest.getTable(), topTableRequest.getProject(), topTableRequest.isTop());
        List<TableDesc> tables = tableService
                .getTableDesc("default", false, "", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertTrue(tables.get(0).isTop());
    }

    @Test
    public void testGetAutoMergeConfigException() {
        thrown.expect(KylinException.class);
        thrown.expectMessage(MODEL_ID_NOT_EXIST.getMsg("default"));
        tableService.getAutoMergeConfigByModel("default", "default");
    }

    @Test
    public void testGetAutoMergeConfig() {
        NDataModelManager modelManager = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), "default");
        NDataModel dataModel = modelManager.getDataModelDesc("89af4ee2-2cdb-4b07-b39e-4c29856309aa");
        dataModel.setManagementType(ManagementType.MODEL_BASED);
        NDataModel dataModelUpdate = modelManager.copyForWrite(dataModel);
        modelManager.updateDataModelDesc(dataModelUpdate);
        //model Based model
        AutoMergeConfigResponse response = tableService.getAutoMergeConfigByModel("default",
                "89af4ee2-2cdb-4b07-b39e-4c29856309aa");
        Assert.assertEquals(0, response.getVolatileRange().getVolatileRangeNumber());
        Assert.assertFalse(response.isAutoMergeEnabled());
        Assert.assertEquals(4, response.getAutoMergeTimeRanges().size());

    }

    @Test
    public void testSetAutoMergeConfigByModel() {
        AutoMergeRequest autoMergeRequest = mockAutoMergeRequest();
        NDataModelManager modelManager = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), "default");
        NDataModel dataModel = modelManager.getDataModelDesc("89af4ee2-2cdb-4b07-b39e-4c29856309aa");
        dataModel.setManagementType(ManagementType.MODEL_BASED);
        NDataModel dataModelUpdate = modelManager.copyForWrite(dataModel);
        modelManager.updateDataModelDesc(dataModelUpdate);
        autoMergeRequest.setTable("");
        autoMergeRequest.setModel("89af4ee2-2cdb-4b07-b39e-4c29856309aa");
        tableService.setAutoMergeConfigByModel("default", autoMergeRequest);
        AutoMergeConfigResponse respone = tableService.getAutoMergeConfigByModel("default",
                "89af4ee2-2cdb-4b07-b39e-4c29856309aa");
        Assert.assertEquals(respone.isAutoMergeEnabled(), autoMergeRequest.isAutoMergeEnabled());
        Assert.assertEquals(respone.getAutoMergeTimeRanges().size(), autoMergeRequest.getAutoMergeTimeRanges().length);
        Assert.assertEquals(respone.getVolatileRange().getVolatileRangeNumber(),
                autoMergeRequest.getVolatileRangeNumber());
        Assert.assertEquals(respone.getVolatileRange().getVolatileRangeType().toString(),
                autoMergeRequest.getVolatileRangeType());

    }

    @Test
    public void testGetTableNameResponse_PASS() throws Exception {
        List<TableNameResponse> result = tableService.getTableNameResponses("default", "DEFAULT", "");
        Assert.assertEquals(11, result.size());
        Assert.assertTrue(result.get(0).isLoaded());

    }

    @Test
    public void testGetLoadedDatabases() {
        Set<String> loadedDatabases = tableService.getLoadedDatabases("default");
        Assert.assertEquals(3, loadedDatabases.size());
    }

    private TopTableRequest mockTopTableRequest() {
        TopTableRequest topTableRequest = new TopTableRequest();
        topTableRequest.setProject("default");
        topTableRequest.setTable("DEFAULT.TEST_COUNTRY");
        topTableRequest.setTop(true);
        return topTableRequest;
    }

    private AutoMergeRequest mockAutoMergeRequest() {
        AutoMergeRequest autoMergeRequest = new AutoMergeRequest();
        autoMergeRequest.setProject("default");
        autoMergeRequest.setTable("DEFAULT.TEST_KYLIN_FACT");
        autoMergeRequest.setAutoMergeEnabled(true);
        autoMergeRequest.setAutoMergeTimeRanges(new String[] { "HOUR" });
        autoMergeRequest.setVolatileRangeEnabled(true);
        autoMergeRequest.setVolatileRangeNumber(7);
        autoMergeRequest.setVolatileRangeType("HOUR");
        return autoMergeRequest;
    }

    private DateRangeRequest mockDateRangeRequest() {
        DateRangeRequest request = new DateRangeRequest();
        request.setStart("1294364500000");
        request.setEnd("1294450900000");
        request.setProject("default");
        request.setTable("DEFAULT.TEST_KYLIN_FACT");
        return request;
    }

    @Test
    public void testGetProjectTables() throws Exception {
        NInitTablesResponse response;
        overwriteSystemProp("kylin.source.load-hive-tablename-enabled", "false");

        response = tableService.getProjectTables("default", "SSB.SS", 0, 14, true, true, Collections.emptyList());
        Assert.assertEquals(0, response.getDatabases().size());

        response = tableService.getProjectTables("default", "SSB.CU", 0, 14, true, true, Collections.emptyList());
        Assert.assertEquals(1, response.getDatabases().size());
        Assert.assertEquals(2, response.getDatabases().get(0).getTables().size());

        response = tableService.getProjectTables("default", "", 0, 14, true, true, Collections.emptyList());
        Assert.assertEquals(3, response.getDatabases().size());
        Assert.assertEquals(21,
                response.getDatabases().get(0).getTables().size() + response.getDatabases().get(1).getTables().size()
                        + response.getDatabases().get(2).getTables().size());

        response = tableService.getProjectTables("default", "TEST", 0, 14, true, true, Collections.emptyList());
        Assert.assertEquals(2, response.getDatabases().size());
        Assert.assertEquals(13,
                response.getDatabases().get(0).getTables().size() + response.getDatabases().get(1).getTables().size());

        response = tableService.getProjectTables("default", "EDW.", 0, 14, true, true, Collections.emptyList());
        Assert.assertEquals(1, response.getDatabases().size());
        Assert.assertEquals(3, response.getDatabases().get(0).getTables().size());

        response = tableService.getProjectTables("default", "EDW.", 0, 14, true, false, Collections.emptyList());
        Assert.assertEquals(1, response.getDatabases().size());
        Assert.assertEquals(3, response.getDatabases().get(0).getTables().size());

        response = tableService.getProjectTables("default", "DEFAULT.TEST_ORDER", 0, 14, true, false,
                Collections.emptyList());
        Assert.assertEquals(1, response.getDatabases().size());
        Assert.assertEquals(1, response.getDatabases().get(0).getTables().size());

        response = tableService.getProjectTables("default", ".TEST_ORDER", 0, 14, true, false, Collections.emptyList());
        Assert.assertEquals(0, response.getDatabases().size());

        response = tableService.getProjectTables("default", "", 0, 14, true, true, Collections.singletonList(9));
        Assert.assertEquals(3, response.getDatabases().size());
    }

    @Test
    public void testClassifyDbTables() throws Exception {
        String project = "default";

        String[] tables1 = { "ssb", "ssb.KK", "DEFAULT", "DEFAULT.TEST", "DEFAULT.TEST_ACCOUNT" };
        Pair<String[], Set<String>> res = tableService.classifyDbTables(project, tables1);
        Assert.assertEquals("ssb", ((String[]) res.getFirst())[0]);
        Assert.assertEquals("DEFAULT", ((String[]) res.getFirst())[1]);
        Assert.assertEquals("DEFAULT.TEST_ACCOUNT", ((String[]) res.getFirst())[2]);
        Assert.assertEquals(2, (res.getSecond()).size());

        String[] tables2 = { "KKK", "KKK.KK", ".DEFAULT", "DEFAULT.TEST", "DEFAULT.TEST_ACCOUNT" };
        res = tableService.classifyDbTables(project, tables2);
        Assert.assertEquals("DEFAULT.TEST_ACCOUNT", ((String[]) res.getFirst())[0]);
        Assert.assertEquals(4, (res.getSecond()).size());

        String[] tables3 = { "DEFAULT.TEST_ACCOUNT", "SsB" };
        res = tableService.classifyDbTables(project, tables3);
        Assert.assertEquals("DEFAULT.TEST_ACCOUNT", ((String[]) res.getFirst())[0]);
        Assert.assertEquals("SsB", ((String[]) res.getFirst())[1]);
        Assert.assertEquals(0, (res.getSecond()).size());
    }

    @Test
    public void testGetTableNameResponsesInCache() throws Exception {
        Map<String, List<String>> testData = new HashMap<>();
        testData.put("t", Arrays.asList("aa", "ab", "bc"));
        NHiveSourceInfo sourceInfo = new NHiveSourceInfo();
        sourceInfo.setTables(testData);
        UserGroupInformation ugi = UserGroupInformation.getLoginUser();
        DataSourceState.getInstance().putCache("ugi#" + ugi.getUserName(), sourceInfo, Arrays.asList("aa", "ab", "bc"));
        List<?> tables = tableService.getTableNameResponsesInCache("default", "t", "a");
        Assert.assertEquals(2, tables.size());
    }

    @Test
    public void testloadProjectHiveTableNameToCacheImmediately() throws Exception {
        List<?> tables = tableService.getTableNameResponsesInCache("default", "SSB", "");
        Assert.assertEquals(0, tables.size());

        KylinConfig.getInstanceFromEnv().setProperty("kylin.source.hive.databases", "default");
        Assert.assertEquals(1, KylinConfig.getInstanceFromEnv().getHiveDatabases().length);
        tableService.loadProjectHiveTableNameToCacheImmediately("default", true);
        tables = tableService.getTableNameResponsesInCache("default", "SSB", "");
        Assert.assertEquals(0, tables.size());

        NProjectManager.getInstance(KylinConfig.getInstanceFromEnv()).getProject("default").getConfig()
                .setProperty("kylin.source.hive.databases", "ssb");
        tableService.loadProjectHiveTableNameToCacheImmediately("default", true);
        tables = tableService.getTableNameResponsesInCache("default", "SSB", "");
        Assert.assertEquals(7, tables.size());

        NProjectManager.getInstance(KylinConfig.getInstanceFromEnv()).getProject("default").setPrincipal("default");
        tableService.loadHiveTableNameToCache();
        tables = tableService.getTableNameResponsesInCache("default", "EDW", "");
        Assert.assertEquals(0, tables.size());
    }

    @Test
    public void testloadProjectHiveTableNameToCacheImmediatelyCase2() throws Exception {
        List<?> tables = tableService.getTableNameResponsesInCache("default", "SSB", "");
        Assert.assertEquals(0, tables.size());
        tableService.loadProjectHiveTableNameToCacheImmediately("default", false);
        tables = tableService.getTableNameResponsesInCache("default", "SSB", "");
        Assert.assertEquals(0, tables.size());
    }

    @Test
    public void testGetTableNameResponsesInCacheJdbc() throws Exception {
        NProjectManager projectManager = NProjectManager.getInstance(KylinConfig.getInstanceFromEnv());
        ProjectInstance projectInstance = projectManager.getProject("default");
        LinkedHashMap<String, String> overrideKylinProps = projectInstance.getOverrideKylinProps();
        overrideKylinProps.put("kylin.query.force-limit", "-1");
        overrideKylinProps.put("kylin.source.default", "8");
        ProjectInstance projectInstanceUpdate = ProjectInstance.create(projectInstance.getName(),
                projectInstance.getOwner(), projectInstance.getDescription(), overrideKylinProps);
        projectManager.updateProject(projectInstance, projectInstanceUpdate.getName(),
                projectInstanceUpdate.getDescription(), projectInstanceUpdate.getOverrideKylinProps());
        Map<String, List<String>> testData = new HashMap<>();
        testData.put("t", Arrays.asList("aa", "ab", "bc"));
        NHiveSourceInfo sourceInfo = new NHiveSourceInfo();
        sourceInfo.setTables(testData);
        DataSourceState.getInstance().putCache("project#default", sourceInfo, Arrays.asList("aa", "ab", "bc"));
        List<?> tables = tableService.getTableNameResponsesInCache("default", "t", "a");
        Assert.assertEquals(2, tables.size());
    }

    @Test
    public void testCheckTableExistOrLoad() {
        TableDesc tableDesc = new TableDesc();
        tableDesc.setKafkaConfig(new KafkaConfig());
        TableNameResponse response = new TableNameResponse();
        tableService.checkTableExistOrLoad(response, tableDesc);
        Assert.assertTrue(response.isExisted());

        TableNameResponse response2 = new TableNameResponse();
        tableService.checkTableExistOrLoad(response2, null);
        Assert.assertFalse(response2.isExisted());

        TableNameResponse response3 = new TableNameResponse();
        tableService.checkTableExistOrLoad(response3, new TableDesc());
        Assert.assertTrue(response3.isLoaded());
    }

    @Test
    public void testIsSqlContainsColumns() {
        Assert.assertFalse(tableService.isSqlContainsColumns("a > 10", "DB.A", Sets.newHashSet("b")));
        Assert.assertTrue(tableService.isSqlContainsColumns("a > 10 AND b < 1", "DB.A", Sets.newHashSet("a", "b")));
        Assert.assertTrue(tableService.isSqlContainsColumns("a > 10 OR b < 1", "DB.A", Sets.newHashSet("b")));
        Assert.assertFalse(
                tableService.isSqlContainsColumns("A.a > 10 AND B.b < 1", "DB.C", Sets.newHashSet("a", "b")));
        Assert.assertTrue(
                tableService.isSqlContainsColumns("A.a  > 10 AND B.b < 1", "DB.A", Sets.newHashSet("a", "b", "c")));
        Assert.assertFalse(tableService.isSqlContainsColumns("(A.a > 10 AND B.b < 1) OR C.c != 'string'", "DB.B",
                Sets.newHashSet("a", "c")));
        Assert.assertFalse(tableService.isSqlContainsColumns("(A.a > 10 AND B.b < 1) OR C.c != 'string'", "DB.D",
                Sets.newHashSet("a", "b", "c")));
        Assert.assertTrue(tableService.isSqlContainsColumns("(A.a > 10) AND B.b < 1", "A", Sets.newHashSet("a")));
        Assert.assertTrue(
                tableService.isSqlContainsColumns("A.a  > 10 AND B.b < 1", "DB.A", Sets.newHashSet("a", "b", "c")));

    }

    @Test
    public void testRefreshSingleCatalogCache() {
        Map<String, List<String>> request = mockRefreshTable("DEFAULT.TEST_KYLIN_FACT", "DEFAULT.TEST_KYLIN_FAKE");
        TableRefresh tableRefresh = tableService.refreshSingleCatalogCache(request);
        Assert.assertEquals(KylinException.CODE_UNDEFINED, tableRefresh.getCode());
        Assert.assertEquals(1, tableRefresh.getRefreshed().size());
        Assert.assertEquals(1, tableRefresh.getFailed().size());
    }

    @Test
    public void testRefreshSparkTable() throws Exception {
        CliCommandExecutor command = new CliCommandExecutor();
        String warehousePath = getTestConfig().exportToProperties()
                .getProperty("kylin.storage.columnar.spark-conf.spark.sql.warehouse.dir").substring(5)
                + "/test_kylin_refresh/";
        PushDownUtil.trySimplyExecute("drop table if exists test_kylin_refresh", null);
        PushDownUtil.trySimplyExecute("create table test_kylin_refresh (word string) STORED AS PARQUET", null);
        PushDownUtil.trySimplyExecute("insert into test_kylin_refresh values ('a')", null);
        PushDownUtil.trySimplyExecute("insert into test_kylin_refresh values ('c')", null);
        PushDownUtil.trySimplyExecute("select * from test_kylin_refresh", null);
        CliCommandExecutor.CliCmdExecResult res = command.execute("ls " + warehousePath, null, null);
        val files = Arrays.stream(res.getCmd().split("\n")).filter(file -> file.endsWith("parquet"))
                .collect(Collectors.toList());
        command.execute("rm " + warehousePath + files.get(0), null, null);

        try {
            PushDownUtil.trySimplyExecute("select * from test_kylin_refresh", null);
            Assert.fail();
        } catch (Exception e) {
            Assert.assertTrue(e.getMessage().contains("REFRESH TABLE tableName"));
        }

        HashMap<String, Object> request = Maps.newHashMap();
        request.put("tables", Collections.singletonList("test_kylin_refresh"));
        TableRefresh refreshRes = tableService.refreshSingleCatalogCache(request);
        PushDownUtil.trySimplyExecute("select * from test_kylin_refresh", null);
        Assert.assertEquals(1, refreshRes.getRefreshed().size());
        Assert.assertEquals("test_kylin_refresh", refreshRes.getRefreshed().get(0));
        SparderEnv.getSparkSession().stop();
    }

    private HashMap<String, List<String>> mockRefreshTable(String... tables) {
        Mockito.doAnswer(invocation -> {
            String table = invocation.getArgument(0);
            List<String> refreshed = invocation.getArgument(1);
            List<String> failed = invocation.getArgument(2);
            if (table.equals("DEFAULT.TEST_KYLIN_FACT")) {
                refreshed.add("DEFAULT.TEST_KYLIN_FACT");
            } else {
                failed.add(table);
            }
            return null;
        }).when(tableService).refreshTable(Mockito.any(), Mockito.any(), Mockito.any());
        HashMap<String, List<String>> request = Maps.newHashMap();
        request.put("tables", Arrays.asList(tables));
        return request;
    }

    @Test
    public void testGetHiveTableNameResponses() throws Exception {
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("test", "test", Constant.ROLE_ANALYST));
        KylinConfig config = getTestConfig();
        config.setProperty("kylin.source.load-hive-tablename-enabled", "false");
        config.setProperty("kylin.query.security.acl-tcr-enabled", "true");
        Assert.assertEquals(7, tableService.getHiveTableNameResponses("default", "SSB", "").size());
        Assert.assertEquals(11, tableService.getHiveTableNameResponses("default", "DEFAULT", "").size());

        val table = NTableMetadataManager.getInstance(getTestConfig(), "default").getTableDesc("DEFAULT.TEST_ENCODING");
        AclTCRManager manager = AclTCRManager.getInstance(getTestConfig(), "default");
        AclTCR acl = new AclTCR();
        AclTCR.Table aclTable = new AclTCR.Table();
        AclTCR.ColumnRow aclColumnRow = new AclTCR.ColumnRow();
        AclTCR.Column aclColumns = new AclTCR.Column();
        Arrays.stream(table.getColumns()).forEach(x -> aclColumns.add(x.getName()));
        aclColumnRow.setColumn(aclColumns);
        aclTable.put("DEFAULT.TEST_ENCODING", aclColumnRow);
        acl.setTable(aclTable);
        manager.updateAclTCR(acl, "test", true);

        Assert.assertEquals(7, tableService.getHiveTableNameResponses("default", "SSB", "").size());
        Assert.assertEquals(11, tableService.getHiveTableNameResponses("default", "DEFAULT", "").size());
        config.setProperty("kylin.source.load-hive-tablename-enabled", "true");
        config.setProperty("kylin.query.security.acl-tcr-enabled", "false");
    }

    @Test
    public void testGetTableExtDescJobID() throws IOException {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc tableDesc = tableMgr.getTableDesc(tableIdentity);
        TableExtDesc oldExtDesc = tableMgr.getOrCreateTableExt(tableDesc);

        // mock table ext desc
        TableExtDesc tableExt = new TableExtDesc(oldExtDesc);
        tableExt.setIdentity(tableIdentity);
        tableExt.setJodID("949afe5d-0221-420f-92db-cdd91cb31ac8");
        tableMgr.mergeAndUpdateTableExt(oldExtDesc, tableExt);

        List<TableDesc> tables = tableService
                .getTableDesc("newten", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertEquals(1, tables.size());

        Assert.assertEquals("949afe5d-0221-420f-92db-cdd91cb31ac8", ((TableDescResponse) tables.get(0)).getJodID());
    }

    @Test
    public void testGetModelTables() {
        String project = "default";
        // normal model
        String modelName = "nmodel_basic";
        List<TableDesc> tableDescs = tableService.getTablesOfModel(project, modelName);
        Assert.assertEquals(10, tableDescs.size());

        // table deleted
        tableService.unloadTable(project, "DEFAULT.TEST_KYLIN_FACT", Boolean.FALSE);
        tableDescs = tableService.getTablesOfModel(project, modelName);
        Assert.assertEquals(9, tableDescs.size());

        // model not exist
        thrown.expect(KylinException.class);
        thrown.expectMessage(MODEL_NAME_NOT_EXIST.getMsg("nomodel"));
        tableService.getTablesOfModel(project, "nomodel");
    }

    @Test
    public void testGetTableDescByType() {
        String project = "streaming_test";
        try {
            val tableDescs = tableService
                    .getTableDesc(project, true, "", "default", true, Collections.singletonList(1), 10).getFirst();
            Assert.assertNotNull(tableDescs);

            val tableDescs1 = tableService
                    .getTableDesc(project, true, "P_LINEORDER_STREAMING", "ssb", true, Collections.singletonList(1), 10)
                    .getFirst();
            Assert.assertEquals(1, tableDescs1.size());
            val tableDesc1 = tableDescs1.get(0);
            Assert.assertEquals(tableDesc1.getTableAlias(), tableDesc1.getKafkaConfig().getBatchTable());

            val tableDescs2 = tableService
                    .getTableDesc(project, true, "LINEORDER_HIVE", "SSB", false, Collections.singletonList(9), 10)
                    .getFirst();
            Assert.assertEquals(1, tableDescs2.size());
            val tableDesc2 = tableDescs2.get(0);
            Assert.assertEquals(tableDesc2.getTableAlias(), tableDesc2.getIdentity());
        } catch (Exception e) {
            Assert.fail();
        }

    }

    @Test
    public void testGetTableDescByTypes() {
        String project = "streaming_test";
        try {
            List<Integer> sourceTypes = Arrays.asList(1, 9);
            val tableDescs2 = tableService.getTableDesc(project, true, "", "SSB", false, sourceTypes, 10).getFirst();
            assert tableDescs2.stream().anyMatch(tableDesc -> tableDesc.getSourceType() == 1);
            assert tableDescs2.stream().anyMatch(tableDesc -> tableDesc.getSourceType() == 9);
        } catch (Exception e) {
            Assert.fail();
        }

    }

    @Test
    public void testUnloadKafkaConfig() {
        String project = "streaming_test";
        NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), project);
        tableService.unloadTable(project, "DEFAULT.SSB_TOPIC", true);
        val table = tableMgr.getTableDesc("DEFAULT.SSB_TOPIC");
        Assert.assertNull(table);

        tableService.unloadTable(project, "SSB.LINEORDER_HIVE", true);
        val table1 = tableMgr.getTableDesc("SSB.P_LINEORDER_STREAMING");
        Assert.assertNull(table1);
    }

    @Test
    public void testStopStreamingJobByTables1() {
        String project = "streaming_test";

        val streamingJobMgr = StreamingJobManager.getInstance(getTestConfig(), project);
        val jobId = "4965c827-fbb4-4ea1-a744-3f341a3b030d_merge";
        Assert.assertEquals(JobStatusEnum.RUNNING, streamingJobMgr.getStreamingJobByUuid(jobId).getCurrentStatus());

        NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), project);
        tableService.unloadTable(project, "DEFAULT.SSB_STREAMING", false);
        val table = tableMgr.getTableDesc("DEFAULT.SSB_STREAMING");
        Assert.assertNull(table);
        Assert.assertEquals(JobStatusEnum.STOPPED, streamingJobMgr.getStreamingJobByUuid(jobId).getCurrentStatus());
    }

    @Test
    public void testStopStreamingJobByTables2() {
        String project = "streaming_test";

        val streamingJobMgr = StreamingJobManager.getInstance(getTestConfig(), project);
        val jobId = "4965c827-fbb4-4ea1-a744-3f341a3b030d_merge";
        Assert.assertEquals(JobStatusEnum.RUNNING, streamingJobMgr.getStreamingJobByUuid(jobId).getCurrentStatus());

        NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), project);
        tableService.unloadTable(project, "SSB.LINEORDER_HIVE", false);
        val table = tableMgr.getTableDesc("SSB.P_LINEORDER_STREAMING");
        Assert.assertNotNull(table);
        Assert.assertEquals(JobStatusEnum.STOPPED, streamingJobMgr.getStreamingJobByUuid(jobId).getCurrentStatus());

    }

    @Test
    public void testCheckMessage() {
        Assert.assertThrows(KylinException.class,
                () -> ReflectionTestUtils.invokeMethod(tableService, "checkMessage", "table", null));
    }

    @Test
    public void testCheckMessageWithArgs() {
        ThrowingRunnable func = () -> ReflectionTestUtils.invokeMethod(tableService, "checkMessage", "table",
                new ArrayList<>());
        Assert.assertThrows(KylinException.class, func);
    }

    @Test
    public void testTableDescResponseV2() throws IOException {
        final String tableIdentity = "DEFAULT.TEST_COUNTRY";
        final NTableMetadataManager tableMgr = NTableMetadataManager.getInstance(getTestConfig(), "newten");
        final TableDesc tableDesc = tableMgr.getTableDesc(tableIdentity);
        final TableExtDesc oldExtDesc = tableMgr.getOrCreateTableExt(tableDesc);
        // mock table ext desc
        TableExtDesc tableExt = new TableExtDesc(oldExtDesc);
        tableExt.setIdentity(tableIdentity);
        TableExtDesc.ColumnStats col1 = new TableExtDesc.ColumnStats();
        col1.setCardinality(100);
        col1.setTableExtDesc(tableExt);
        col1.setColumnName(tableDesc.getColumns()[0].getName());
        col1.setMinValue("America");
        col1.setMaxValue("Zimbabwe");
        col1.setNullCount(0);
        tableExt.setColumnStats(Lists.newArrayList(col1));
        tableMgr.mergeAndUpdateTableExt(oldExtDesc, tableExt);

        final List<TableDesc> tables = tableService
                .getTableDesc("newten", true, "TEST_COUNTRY", "DEFAULT", true, Collections.emptyList(), 10).getFirst();
        Assert.assertEquals(1, tables.size());
        Assert.assertTrue(tables.get(0) instanceof TableDescResponse);
        TableDescResponse t = (TableDescResponse) tables.get(0);
        Map<String, Long> cardinality = t.getCardinality();
        for (int i = 0; i < t.getExtColumns().length; i++) {
            if (t.getExtColumns()[i].getCardinality() != null) {
                Assert.assertEquals(cardinality.get(t.getExtColumns()[i].getName()),
                        t.getExtColumns()[i].getCardinality());
            }
        }
        Assert.assertEquals(t.getTransactionalV2(), t.isTransactional());
        t.setTransactional(true);
        Assert.assertEquals(t.getTransactionalV2(), t.isTransactional());
    }
}
