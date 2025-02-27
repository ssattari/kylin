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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.code.ErrorCodeServer;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.NLocalFileMetadataTestCase;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableExtDesc;
import org.apache.kylin.metadata.project.EnhancedUnitOfWork;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.request.S3TableExtInfo;
import org.apache.kylin.rest.request.TableExclusionRequest;
import org.apache.kylin.rest.request.TableLoadRequest;
import org.apache.kylin.rest.request.UpdateAWSTableExtDescRequest;
import org.apache.kylin.rest.response.ExcludedColumnResponse;
import org.apache.kylin.rest.response.ExcludedTableDetailResponse;
import org.apache.kylin.rest.response.ExcludedTableResponse;
import org.apache.kylin.rest.response.LoadTableResponse;
import org.apache.kylin.rest.response.UpdateAWSTableExtDescResponse;
import org.apache.kylin.rest.util.AclEvaluate;
import org.apache.kylin.rest.util.AclUtil;
import org.apache.kylin.util.MetadataTestUtils;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

public class TableExtServiceTest extends NLocalFileMetadataTestCase {

    @Mock
    private final TableService tableService = Mockito.spy(TableService.class);

    @Mock
    private final AclEvaluate aclEvaluate = Mockito.spy(AclEvaluate.class);

    @InjectMocks
    private final TableExtService tableExtService = Mockito.spy(new TableExtService());

    @Before
    public void setup() throws IOException {
        overwriteSystemProp("HADOOP_USER_NAME", "root");
        createTestMetadata();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN));
        ReflectionTestUtils.setField(aclEvaluate, "aclUtil", Mockito.spy(AclUtil.class));
        ReflectionTestUtils.setField(tableService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(tableExtService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(tableExtService, "tableService", tableService);
    }

    @After
    public void tearDown() {
        cleanupTestMetadata();
    }

    @Test
    public void testLoadTables() throws Exception {
        String[] tables = { "DEFAULT.TEST_KYLIN_FACT", "DEFAULT.TEST_ACCOUNT" };
        String[] tableNames = { "TEST_KYLIN_FACT", "TEST_ACCOUNT" };
        List<Pair<TableDesc, TableExtDesc>> result = mockTablePair(2, "DEFAULT");
        Mockito.doReturn(result).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(tableExtService).loadTable(result.get(0).getFirst(), result.get(0).getSecond(),
                "default");
        Mockito.doNothing().when(tableExtService).loadTable(result.get(1).getFirst(), result.get(1).getSecond(),
                "default");
        Mockito.doReturn(Lists.newArrayList(tableNames)).when(tableService).getSourceTableNames("default", "DEFAULT",
                "");
        Mockito.doReturn(Lists.newArrayList("DEFAULT")).when(tableService).getSourceDbNames("default");

        LoadTableResponse response = tableExtService.loadDbTables(tables, "default", false);
        Assert.assertEquals(2, response.getLoaded().size());
    }

    @Test
    public void testLoadAWSTablesCompatibleCrossAccount() throws Exception {
        String[] tableNames = { "TABLE0", "TABLE1" };
        List<S3TableExtInfo> crossAccountTableReq = new ArrayList<>();
        S3TableExtInfo s3TableExtInfo1 = new S3TableExtInfo();
        s3TableExtInfo1.setName("DEFAULT.TABLE0");
        s3TableExtInfo1.setLocation("s3://bucket1/test1/");
        S3TableExtInfo s3TableExtInfo2 = new S3TableExtInfo();
        s3TableExtInfo2.setName("DEFAULT.TABLE1");
        s3TableExtInfo2.setLocation("s3://bucket2/test2/");
        s3TableExtInfo2.setEndpoint("us-west-2.amazonaws.com");
        s3TableExtInfo2.setRoleArn("test:role");
        crossAccountTableReq.add(s3TableExtInfo1);
        crossAccountTableReq.add(s3TableExtInfo2);
        List<Pair<TableDesc, TableExtDesc>> result = mockTablePair(2, "DEFAULT", "TABLE");
        Mockito.doReturn(result).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        Mockito.doNothing().when(tableExtService).loadTable(result.get(0).getFirst(), result.get(0).getSecond(),
                "default");
        Mockito.doNothing().when(tableExtService).loadTable(result.get(1).getFirst(), result.get(1).getSecond(),
                "default");
        Mockito.doReturn(Lists.newArrayList(tableNames)).when(tableService).getSourceTableNames("default", "DEFAULT",
                "");
        Mockito.doReturn(Lists.newArrayList("DEFAULT")).when(tableService).getSourceDbNames("default");

        LoadTableResponse response = tableExtService.loadAWSTablesCompatibleCrossAccount(crossAccountTableReq,
                "default");
        Assert.assertEquals(2, response.getLoaded().size());

        KylinConfig.getInstanceFromEnv().setProperty("kylin.env.use-dynamic-role-credential-in-table", "true");
        LoadTableResponse response2 = tableExtService.loadAWSTablesCompatibleCrossAccount(crossAccountTableReq,
                "default");
        Assert.assertEquals(2, response2.getLoaded().size());
    }

    @Test
    public void testUpdateAWSLoadedTableExtProp() {
        UpdateAWSTableExtDescRequest request = new UpdateAWSTableExtDescRequest();
        List<S3TableExtInfo> tableExtInfoList = new ArrayList<>();
        S3TableExtInfo s3TableExtInfo1 = new S3TableExtInfo();
        s3TableExtInfo1.setName("DEFAULT.TABLE0");
        s3TableExtInfo1.setLocation("s3://bucket1/test1/");
        S3TableExtInfo s3TableExtInfo2 = new S3TableExtInfo();
        s3TableExtInfo2.setName("DEFAULT.TABLE1");
        s3TableExtInfo2.setLocation("s3://bucket2/test2/");
        s3TableExtInfo2.setEndpoint("us-west-2.amazonaws.com");
        s3TableExtInfo2.setRoleArn("test:role");
        tableExtInfoList.add(s3TableExtInfo1);
        tableExtInfoList.add(s3TableExtInfo2);
        request.setProject("default");
        request.setTables(tableExtInfoList);

        TableExtDesc tableExtDesc = new TableExtDesc();
        tableExtDesc.setUuid(RandomUtil.randomUUIDStr());
        tableExtDesc.setIdentity("DEFAULT.TABLE1");
        TableDesc tableDesc = new TableDesc();
        tableDesc.setName("TABLE1");
        tableDesc.setDatabase("DEFAULT");
        tableDesc.setUuid(RandomUtil.randomUUIDStr());

        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            UnitOfWork.get().getCopyForWriteItems().add("TABLE_EXD/default.DEFAULT.TABLE1");
            UnitOfWork.get().getCopyForWriteItems().add("TABLE_INFO/default.DEFAULT.TABLE1");
            NTableMetadataManager tableMetadataManager = NTableMetadataManager
                    .getInstance(KylinConfig.getInstanceFromEnv(), "default");
            tableMetadataManager.saveTableExt(tableExtDesc);
            tableMetadataManager.saveSourceTable(tableDesc);
            return null;
        }, "default");

        UpdateAWSTableExtDescResponse response = tableExtService.updateAWSLoadedTableExtProp(request);
        Assert.assertEquals(1, response.getSucceed().size());

        KylinConfig.getInstanceFromEnv().setProperty("kylin.env.use-dynamic-role-credential-in-table", "true");
        UpdateAWSTableExtDescResponse response2 = tableExtService.updateAWSLoadedTableExtProp(request);
        Assert.assertEquals(1, response2.getSucceed().size());
    }

    @Test
    public void testLoadTablesByDatabase() throws Exception {
        String[] tableIdentities = { "EDW.TEST_CAL_DT", "EDW.TEST_SELLER_TYPE_DIM", "EDW.TEST_SITES" };
        String[] tableNames = { "TEST_CAL_DT", "TEST_SELLER_TYPE_DIM", "TEST_SITES" };
        LoadTableResponse loadTableResponse = new LoadTableResponse();
        List<Pair<TableDesc, TableExtDesc>> result = mockTablePair(3, "EDW");
        Mockito.doNothing().when(tableExtService).loadTable(result.get(1).getFirst(), result.get(1).getSecond(),
                "default");
        Mockito.doReturn(result).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        loadTableResponse.setLoaded(Sets.newHashSet(tableIdentities));

        Mockito.doReturn(Lists.newArrayList(tableNames)).when(tableService).getSourceTableNames(Mockito.any(),
                Mockito.any(), Mockito.any());
        Mockito.doReturn(Lists.newArrayList("EDW")).when(tableService).getSourceDbNames("default");

        Mockito.doReturn(loadTableResponse).when(tableExtService).loadDbTables(tableIdentities, "default", false);
        LoadTableResponse response = tableExtService.loadDbTables(new String[] { "EDW" }, "default", true);

        Assert.assertEquals(1, response.getLoaded().size());
    }

    @Test
    public void testLoadTablesByDatabaseNotInCache() throws Exception {
        String[] tableIdentities = { "EDW.TEST_CAL_DT" };
        String[] tableNames = { "TEST_CAL_DT" };
        LoadTableResponse loadTableResponse = new LoadTableResponse();
        loadTableResponse.setLoaded(Sets.newHashSet(tableIdentities));
        List<Pair<TableDesc, TableExtDesc>> result = mockTablePair(1, "EDW");

        Mockito.doReturn(Lists.newArrayList(tableNames)).when(tableService).getSourceTableNames("default", "EDW", "");
        Mockito.doReturn(loadTableResponse).when(tableExtService).loadDbTables(tableIdentities, "default", false);

        NTableMetadataManager tableManager = NTableMetadataManager.getInstance(getTestConfig(), "default");
        tableManager.removeSourceTable("EDW.TEST_CAL_DT");
        Mockito.doReturn(Lists.newArrayList("EDW")).when(tableService).getSourceDbNames("default");
        Mockito.doReturn(result).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        LoadTableResponse response = tableExtService.loadDbTables(new String[] { "EDW" }, "default", true);
        Assert.assertEquals(0, response.getLoaded().size());
    }

    @Test
    public void testRemoveJobIdFromTableExt() {
        TableExtDesc tableExtDesc = new TableExtDesc();
        tableExtDesc.setUuid(RandomUtil.randomUUIDStr());
        tableExtDesc.setIdentity("DEFAULT.TEST_REMOVE");
        tableExtDesc.setJodID("test");
        TableDesc tableDesc = new TableDesc();
        tableDesc.setName("TEST_REMOVE");
        tableDesc.setDatabase("DEFAULT");
        tableDesc.setUuid(RandomUtil.randomUUIDStr());
        NTableMetadataManager tableMetadataManager = NTableMetadataManager.getInstance(getTestConfig(), "default");
        tableMetadataManager.saveTableExt(tableExtDesc);
        tableMetadataManager.saveSourceTable(tableDesc);
        tableExtService.removeJobIdFromTableExt("test", "default");
        TableExtDesc tableExtDesc1 = tableMetadataManager.getOrCreateTableExt("DEFAULT.TEST_REMOVE");
        Assert.assertNull(tableExtDesc1.getJodID());
    }

    @Test
    public void testGetExcludedTableFailed() {
        try {
            tableExtService.getExcludedTable("default", "default.test_account", 0, 10, "", false);
            Assert.fail();
        } catch (KylinException e) {
            Assert.assertEquals(ErrorCodeServer.EXCLUDED_TABLE_REQUEST_NOT_ALLOWED.getErrorCode().getCode(),
                    e.getErrorCode().getCodeString());
        }
    }

    @Test
    public void testGetExcludedTableWithExcludedColumns() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.mockExcludedTable(project, table);

        // get all excluded columns
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", true);
            Assert.assertEquals(5, response.getTotalSize());
            Assert.assertEquals(5, response.getExcludedColumns().size());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());
        }

        // get excluded columns with pattern
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "level",
                    true);
            Assert.assertEquals(2, response.getTotalSize());
            Assert.assertEquals(2, response.getExcludedColumns().size());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());
        }

        // get admitted columns, the result is empty list [this is abnormal usage]
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", false);
            Assert.assertEquals(0, response.getTotalSize());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
        }

        // get excluded columns with pageSize is 2
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 2, "", true);
            Assert.assertEquals(5, response.getTotalSize());
            Assert.assertEquals(2, response.getExcludedColumns().size());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());
        }
    }

    @Test
    public void testGetExcludedTableWithToBeExcludedColumns() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.turnOnExcludedTable(getTestConfig());

        // get all columns to be excluded
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", false);
            Assert.assertEquals(5, response.getTotalSize());
            Assert.assertEquals(5, response.getAdmittedColumns().size());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
        }

        // get columns to be excluded with pattern
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "level",
                    false);
            Assert.assertEquals(2, response.getTotalSize());
            Assert.assertEquals(2, response.getAdmittedColumns().size());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
        }

        // get columns of excluded, the admitted columns is empty list [this is abnormal usage]
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", true);
            Assert.assertEquals(0, response.getTotalSize());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
        }

        // get columns of admitted columns with pageSize is 2
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 2, "", false);
            Assert.assertEquals(5, response.getTotalSize());
            Assert.assertEquals(2, response.getAdmittedColumns().size());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
        }
    }

    @Test
    public void testGetExcludedColumnsWhenSettingSomeExcludedColumns() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        Set<String> excludedColumns = Sets.newHashSet("ACCOUNT_BUYER_LEVEL", "ACCOUNT_SELLER_LEVEL");
        MetadataTestUtils.mockExcludedCols(project, table, excludedColumns);

        // get all columns to be excluded
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", false);
            Assert.assertEquals(3, response.getTotalSize());
            List<ExcludedColumnResponse> admittedColumnList = response.getAdmittedColumns();
            Assert.assertEquals(3, admittedColumnList.size());
            Assert.assertTrue(response.getExcludedColumns().isEmpty());
            admittedColumnList.forEach(admittedColumn -> Assert.assertFalse(admittedColumn.isExcluded()));
        }

        // get all excluded columns
        {
            ExcludedTableDetailResponse response = tableExtService.getExcludedTable(project, table, 0, 10, "", true);
            Assert.assertEquals(2, response.getTotalSize());
            List<ExcludedColumnResponse> excludedColumnList = response.getExcludedColumns();
            Assert.assertEquals(2, excludedColumnList.size());
            Assert.assertTrue(response.getAdmittedColumns().isEmpty());

            excludedColumnList.forEach(excludedColResp -> Assert.assertTrue(excludedColResp.isExcluded()));
            Set<String> excludedColNameSet = excludedColumnList.stream().map(ExcludedColumnResponse::getName)
                    .collect(Collectors.toSet());
            Assert.assertEquals(excludedColumns, excludedColNameSet);
        }
    }

    @Test
    public void testGetExcludedTables() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        Set<String> excludedColumns = Sets.newHashSet("ACCOUNT_BUYER_LEVEL", "ACCOUNT_SELLER_LEVEL");
        MetadataTestUtils.mockExcludedCols(project, table, excludedColumns);
        MetadataTestUtils.mockExcludedTable(project, "DEFAULT.TEST_COUNTRY");

        // get without table pattern
        {
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
            Assert.assertEquals(2, excludedTables.size());
            excludedTables.forEach(excludedTableResponse -> {
                if (excludedTableResponse.getTable().equals(table)) {
                    Assert.assertFalse(excludedTableResponse.isExcluded());
                    Assert.assertEquals(2, excludedTableResponse.getExcludedColSize());
                    Assert.assertEquals(excludedColumns, Sets.newHashSet(excludedTableResponse.getExcludedColumns()));
                } else {
                    Assert.assertEquals("DEFAULT.TEST_COUNTRY", excludedTableResponse.getTable());
                    Assert.assertEquals(4, excludedTableResponse.getExcludedColSize());
                    Assert.assertTrue(excludedTableResponse.isExcluded());
                }
            });
        }

        // get with table pattern 
        {
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false,
                    "TEST_COUNTRY");
            Assert.assertEquals(1, excludedTables.size());
            ExcludedTableResponse excludedTableResponse = excludedTables.get(0);

            Assert.assertEquals("DEFAULT.TEST_COUNTRY", excludedTableResponse.getTable());
            Assert.assertEquals(4, excludedTableResponse.getExcludedColSize());
            Assert.assertTrue(excludedTableResponse.isExcluded());
        }

        // get with viewPartialCols
        {
            MetadataTestUtils.mockExcludedTable(project, "DEFAULT.TEST_MEASURE");
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, true,
                    "TEST_MEASURE");
            Assert.assertEquals(1, excludedTables.size());
            ExcludedTableResponse excludedTableResponse = excludedTables.get(0);
            Assert.assertEquals("DEFAULT.TEST_MEASURE", excludedTableResponse.getTable());
            Assert.assertEquals(17, excludedTableResponse.getExcludedColSize());
            Assert.assertTrue(excludedTableResponse.isExcluded());
            Assert.assertEquals(TableExtService.DEFAULT_EXCLUDED_COLUMN_SIZE,
                    excludedTableResponse.getExcludedColumns().size());
        }
    }

    @Test
    public void updateExcludedTablesOfExcludeOneTable() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.turnOnExcludedTable(getTestConfig());

        TableExclusionRequest request = new TableExclusionRequest();
        TableExclusionRequest.ExcludedTable excludedTable = new TableExclusionRequest.ExcludedTable();
        excludedTable.setExcluded(true);
        excludedTable.setTable(table);
        request.setProject(project);
        request.setExcludedTables(Lists.newArrayList(excludedTable));

        tableExtService.updateExcludedTables(project, request);
        List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
        Assert.assertEquals(1, excludedTables.size());
        ExcludedTableResponse excludedTableResponse = excludedTables.get(0);
        Assert.assertTrue(excludedTableResponse.isExcluded());
        Assert.assertEquals(table, excludedTableResponse.getTable());
        Assert.assertEquals(5, excludedTableResponse.getExcludedColSize());
        String expectedExcludedCols = "[ACCOUNT_ID, ACCOUNT_BUYER_LEVEL, ACCOUNT_SELLER_LEVEL, ACCOUNT_COUNTRY, ACCOUNT_CONTACT]";
        Assert.assertEquals(expectedExcludedCols, excludedTableResponse.getExcludedColumns().toString());
    }

    @Test
    public void updateExcludedTablesOfCancelOneTable() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.mockExcludedTable(project, table);
        Set<String> tables = MetadataTestUtils.getExcludedTables(project);
        Assert.assertEquals(1, tables.size());
        Assert.assertEquals(table, tables.iterator().next());

        TableExclusionRequest request = new TableExclusionRequest();
        request.setProject(project);
        request.setCanceledTables(Lists.newArrayList(table));

        tableExtService.updateExcludedTables(project, request);
        List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
        Assert.assertTrue(excludedTables.isEmpty());
    }

    @Test
    public void updateExcludedTablesOfCancelAllExcludedColumns() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.mockExcludedTable(project, table);
        Set<String> tables = MetadataTestUtils.getExcludedTables(project);
        Assert.assertEquals(1, tables.size());
        Assert.assertEquals(table, tables.iterator().next());

        // cancel all table columns, but not set excluded, assert the table is excluded.
        TableExclusionRequest request = new TableExclusionRequest();
        TableExclusionRequest.ExcludedTable excludedTable = new TableExclusionRequest.ExcludedTable();
        excludedTable.setExcluded(false);
        excludedTable.setTable(table);
        excludedTable.setRemovedColumns(Lists.newArrayList("ACCOUNT_ID", "ACCOUNT_BUYER_LEVEL", "ACCOUNT_SELLER_LEVEL",
                "ACCOUNT_COUNTRY", "ACCOUNT_CONTACT"));
        request.setProject(project);
        request.setExcludedTables(Lists.newArrayList(excludedTable));

        tableExtService.updateExcludedTables(project, request);
        List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
        Assert.assertTrue(excludedTables.isEmpty());
    }

    @Test
    public void updateExcludedTablesOfCancelSomeColumns() {
        String project = "default";
        String table = "DEFAULT.TEST_ACCOUNT";
        MetadataTestUtils.turnOnExcludedTable(getTestConfig());

        // add some columns
        {
            TableExclusionRequest request = new TableExclusionRequest();
            TableExclusionRequest.ExcludedTable excludedTable = new TableExclusionRequest.ExcludedTable();
            excludedTable.setExcluded(false);
            excludedTable.setTable(table);
            excludedTable.setAddedColumns(Lists.newArrayList("ACCOUNT_ID", "ACCOUNT_BUYER_LEVEL"));
            request.setProject(project);
            request.setExcludedTables(Lists.newArrayList(excludedTable));

            tableExtService.updateExcludedTables(project, request);
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
            Assert.assertEquals(1, excludedTables.size());
            ExcludedTableResponse excludedTableResponse = excludedTables.get(0);
            Assert.assertFalse(excludedTableResponse.isExcluded());
            Assert.assertEquals(table, excludedTableResponse.getTable());
            Assert.assertEquals(2, excludedTableResponse.getExcludedColSize());
            String expectedExcludedCols = "[ACCOUNT_ID, ACCOUNT_BUYER_LEVEL]";
            Assert.assertEquals(expectedExcludedCols, excludedTableResponse.getExcludedColumns().toString());
        }

        // cancel some columns
        {
            TableExclusionRequest request = new TableExclusionRequest();
            TableExclusionRequest.ExcludedTable excludedTable = new TableExclusionRequest.ExcludedTable();
            excludedTable.setExcluded(false);
            excludedTable.setTable(table);
            excludedTable.setRemovedColumns(Lists.newArrayList("ACCOUNT_BUYER_LEVEL"));
            request.setProject(project);
            request.setExcludedTables(Lists.newArrayList(excludedTable));

            tableExtService.updateExcludedTables(project, request);
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
            Assert.assertEquals(1, excludedTables.size());
            ExcludedTableResponse excludedTableResponse = excludedTables.get(0);
            Assert.assertFalse(excludedTableResponse.isExcluded());
            Assert.assertEquals(table, excludedTableResponse.getTable());
            Assert.assertEquals(1, excludedTableResponse.getExcludedColSize());
            String expectedExcludedCols = "[ACCOUNT_ID]";
            Assert.assertEquals(expectedExcludedCols, excludedTableResponse.getExcludedColumns().toString());
        }

        // cancel other columns
        {
            TableExclusionRequest request = new TableExclusionRequest();
            TableExclusionRequest.ExcludedTable excludedTable = new TableExclusionRequest.ExcludedTable();
            excludedTable.setExcluded(false);
            excludedTable.setTable(table);
            excludedTable.setRemovedColumns(Lists.newArrayList("ACCOUNT_ID"));
            request.setProject(project);
            request.setExcludedTables(Lists.newArrayList(excludedTable));

            tableExtService.updateExcludedTables(project, request);
            List<ExcludedTableResponse> excludedTables = tableExtService.getExcludedTables(project, false, "");
            Assert.assertTrue(excludedTables.isEmpty());
        }
    }

    @Test
    public void testLoadTablesWithShortCircuit() throws Exception {
        List<Pair<TableDesc, TableExtDesc>> lt1000 = mockTablePair(8, "TB");
        Mockito.doReturn(lt1000).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        TableLoadRequest request = new TableLoadRequest();
        request.setDatabases(new String[] { "DEFAULT" });
        request.setProject("default");
        LoadTableResponse lt1000response = tableExtService.loadTablesWithShortCircuit(request);
        Assert.assertEquals(8, lt1000response.getFailed().size());

        List<Pair<TableDesc, TableExtDesc>> gt1000 = mockTablePair(1001, "TB");
        Mockito.doReturn(gt1000).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        Assert.assertThrows(KylinException.class, () -> tableExtService.loadTablesWithShortCircuit(request));

        request.setTables(mockInputDBOrTable());
        Assert.assertThrows(KylinException.class, () -> tableExtService.loadTablesWithShortCircuit(request));

        request.setTables(new String[] { "TEST_KYLIN_FACT" });
        Assert.assertThrows(KylinException.class, () -> tableExtService.loadTablesWithShortCircuit(request));

        request.setDatabases(null);
        gt1000.forEach(
                t -> Mockito.doNothing().when(tableExtService).loadTable(t.getFirst(), t.getSecond(), "default"));
        Mockito.doReturn(gt1000).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        Assert.assertThrows(KylinException.class, () -> tableExtService.loadTablesWithShortCircuit(request));

        request.setDatabases(null);
        request.setTables(new String[] { "TEST_KYLIN_FACT" });
        List<Pair<TableDesc, TableExtDesc>> table8 = mockTablePair(8, "TB");
        Mockito.doReturn(table8).when(tableService).extractTableMeta(Mockito.any(), Mockito.any(), Mockito.any());
        LoadTableResponse response1 = tableExtService.loadTablesWithShortCircuit(request);
        Assert.assertEquals(8, response1.getFailed().size());

        request.setDatabases(new String[] { "DEFAULT" });
        request.setTables(new String[] { "TEST_KYLIN_FACT" });
        LoadTableResponse response2 = tableExtService.loadTablesWithShortCircuit(request);
        Assert.assertEquals(8, response2.getFailed().size());

        // load as internal table when gluten not enabled
        request.setLoadAsInternal(true);
        Assert.assertThrows(KylinException.class, () -> tableExtService.loadTablesWithShortCircuit(request));
    }

    private String[] mockInputDBOrTable() {
        return IntStream.range(0, 1000).mapToObj(t -> "TB" + t).toArray(String[]::new);
    }

    private List<Pair<TableDesc, TableExtDesc>> mockTablePair(int size, String tableName) {
        List<Pair<TableDesc, TableExtDesc>> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TableDesc table1 = new TableDesc();
            table1.setName(tableName + i);
            TableExtDesc tableExt1 = new TableExtDesc();
            result.add(Pair.newPair(table1, tableExt1));
        }
        return result;
    }

    private List<Pair<TableDesc, TableExtDesc>> mockTablePair(int size, String dbName, String tableName) {
        List<Pair<TableDesc, TableExtDesc>> result = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            TableDesc tableDesc = new TableDesc();
            tableDesc.setDatabase(dbName);
            tableDesc.setName(tableName + i);
            TableExtDesc tableExt1 = new TableExtDesc();
            result.add(Pair.newPair(tableDesc, tableExt1));
        }
        return result;
    }
}
