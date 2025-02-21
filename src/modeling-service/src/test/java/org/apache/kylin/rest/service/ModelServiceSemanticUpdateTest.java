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

import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.commons.collections.CollectionUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.code.ErrorCodeServer;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.NLocalFileMetadataTestCase;
import org.apache.kylin.common.util.Unsafe;
import org.apache.kylin.cube.model.SelectRule;
import org.apache.kylin.engine.spark.job.ExecutableAddCuboidHandler;
import org.apache.kylin.engine.spark.job.NSparkCubingJob;
import org.apache.kylin.engine.spark.utils.SparkJobFactoryUtils;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableList;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.job.JobContext;
import org.apache.kylin.job.execution.AbstractExecutable;
import org.apache.kylin.job.execution.ExecutableManager;
import org.apache.kylin.job.execution.ExecutableState;
import org.apache.kylin.job.util.JobContextUtil;
import org.apache.kylin.metadata.cube.cuboid.NAggregationGroup;
import org.apache.kylin.metadata.cube.model.IndexEntity;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.metadata.cube.model.LayoutEntity;
import org.apache.kylin.metadata.cube.model.NDataLayout;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NDataflowUpdate;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.cube.model.RuleBasedIndex;
import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.apache.kylin.metadata.model.ComputedColumnManager;
import org.apache.kylin.metadata.model.FunctionDesc;
import org.apache.kylin.metadata.model.ManagementType;
import org.apache.kylin.metadata.model.MeasureDesc;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModel.ColumnStatus;
import org.apache.kylin.metadata.model.NDataModel.Measure;
import org.apache.kylin.metadata.model.NDataModel.NamedColumn;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.model.ParameterDesc;
import org.apache.kylin.metadata.model.PartitionDesc;
import org.apache.kylin.metadata.model.util.ComputedColumnUtil;
import org.apache.kylin.metadata.project.EnhancedUnitOfWork;
import org.apache.kylin.metadata.realization.RealizationStatusEnum;
import org.apache.kylin.metadata.recommendation.candidate.JdbcRawRecStore;
import org.apache.kylin.query.util.ComputedColumnRewriter;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.request.AggShardByColumnsRequest;
import org.apache.kylin.rest.request.ModelParatitionDescRequest;
import org.apache.kylin.rest.request.ModelRequest;
import org.apache.kylin.rest.request.UpdateRuleBasedCuboidRequest;
import org.apache.kylin.rest.response.ParameterResponse;
import org.apache.kylin.rest.response.SimplifiedMeasure;
import org.apache.kylin.rest.util.AclEvaluate;
import org.apache.kylin.rest.util.AclUtil;
import org.apache.kylin.rest.util.SCD2SimplificationConvertUtil;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import lombok.val;
import lombok.var;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ModelServiceSemanticUpdateTest extends NLocalFileMetadataTestCase {

    private static final String BASIC_MODEL = "89af4ee2-2cdb-4b07-b39e-4c29856309aa";
    private static final String INNER_MODEL = "741ca86a-1f13-46da-a59f-95fb68615e3a";

    @InjectMocks
    private ModelService modelService = Mockito.spy(new ModelService());

    @InjectMocks
    private ModelSemanticHelper semanticService = Mockito.spy(new ModelSemanticHelper());

    @InjectMocks
    private IndexPlanService indexPlanService = Mockito.spy(new IndexPlanService());

    @Mock
    private AclEvaluate aclEvaluate = Mockito.spy(AclEvaluate.class);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    @Mock
    protected IUserGroupService userGroupService = Mockito.spy(NUserGroupService.class);

    private void setupResource() {
        overwriteSystemProp("HADOOP_USER_NAME", "root");
        createTestMetadata();
        modelService.setSemanticUpdater(semanticService);
        indexPlanService.setSemanticUpater(semanticService);
        modelService.setIndexPlanService(indexPlanService);

        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        modelMgr.updateDataModel(BASIC_MODEL, model -> model.setManagementType(ManagementType.MODEL_BASED));
        modelMgr.updateDataModel(INNER_MODEL, model -> model.setManagementType(ManagementType.MODEL_BASED));
    }

    private String getProject() {
        return "default";
    }

    @Before
    public void setUp() {
        JobContextUtil.cleanUp();
        setupResource();
        SparkJobFactoryUtils.initJobFactory();
        SecurityContextHolder.getContext()
                .setAuthentication(new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN));
        ReflectionTestUtils.setField(indexPlanService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(aclEvaluate, "aclUtil", Mockito.spy(AclUtil.class));
        ReflectionTestUtils.setField(modelService, "aclEvaluate", aclEvaluate);
        ReflectionTestUtils.setField(modelService, "userGroupService", userGroupService);
        ReflectionTestUtils.setField(semanticService, "userGroupService", userGroupService);
        try {
            new JdbcRawRecStore(getTestConfig());
        } catch (Exception e) {
            //
        }
        JobContextUtil.getJobInfoDao(getTestConfig());
        ComputedColumnUtil.setEXTRACTOR(ComputedColumnRewriter::extractCcRexNode);
    }

    @After
    public void tearDown() {
        JobContext jobContext = JobContextUtil.getJobContext(getTestConfig());

        JobContextUtil.cleanUp();
        cleanupTestMetadata();

        await().atMost(30, TimeUnit.SECONDS).until(() -> !jobContext.getJobScheduler().hasRunningJob());
    }

    @Test
    public void testUpdateCC_DontNeedReload() throws Exception {
        ModelRequest request = newSemanticRequest();
        for (ComputedColumnDesc cc : request.getComputedColumnDescs()) {
            if (cc.getColumnName().equalsIgnoreCase("DEAL_AMOUNT")) {
                cc.setComment("comment1");
            }
        }
        modelService.updateDataModelSemantic(request.getProject(), request);

        NDataModel model = getTestModel();
        for (ComputedColumnDesc cc : model.getComputedColumnDescs()) {
            if (cc.getColumnName().equalsIgnoreCase("DEAL_AMOUNT")) {
                Assert.assertEquals("comment1", cc.getComment());
            }
        }

        val colIdOfCC = model.getColumnIdByColumnName("TEST_KYLIN_FACT.DEAL_AMOUNT");
        Assert.assertEquals(27, colIdOfCC);
    }

    @Test
    public void testModelUpdateComputedColumn() throws Exception {

        // Add new computed column
        final int colIdOfCC;
        final String ccColName = "TEST_KYLIN_FACT.TEST_CC_1";
        {
            ModelRequest request = newSemanticRequest();
            Assert.assertFalse(request.getComputedColumnNames().contains("TEST_CC_1"));
            ComputedColumnDesc newCC = new ComputedColumnDesc();
            newCC.setColumnName("TEST_CC_1");
            newCC.setExpression("1 + 1");
            newCC.setDatatype("integer");
            newCC.setTableIdentity("DEFAULT.TEST_KYLIN_FACT");
            newCC.setTableAlias("TEST_KYLIN_FACT");
            request.getComputedColumnDescs().add(newCC);
            modelService.updateDataModelSemantic(request.getProject(), request);

            NDataModel model = getTestModel();
            Assert.assertTrue(model.getComputedColumnNames().contains("TEST_CC_1"));
            colIdOfCC = model.getColumnIdByColumnName(ccColName);
            Assert.assertNotEquals(-1, colIdOfCC);
        }

        // Add dimension which uses TEST_CC_1, column will be renamed
        {
            ModelRequest request = newSemanticRequest();
            request.getAllNamedColumns().stream()
                    .filter(column -> column.getAliasDotColumn().equalsIgnoreCase(ccColName)) //
                    .forEach(column -> {
                        column.setName("TEST_DIM_WITH_CC");
                        column.setStatus(ColumnStatus.DIMENSION);
                    });
            request.setSimplifiedDimensions(request.getAllNamedColumns().stream().filter(NamedColumn::isDimension)
                    .collect(Collectors.toList()));
            request.getOtherColumns().removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase(ccColName));
            modelService.updateDataModelSemantic(request.getProject(), request);

            ModelRequest requestToVerify = newSemanticRequest();
            Assert.assertEquals(colIdOfCC, requestToVerify.getColumnIdByColumnName(ccColName));
            Optional<NamedColumn> dimensionToVerify = requestToVerify.getSimplifiedDimensions().stream()
                    .filter(col -> col.getId() == colIdOfCC).findFirst();
            Assert.assertTrue(dimensionToVerify.isPresent());
            Assert.assertEquals("TEST_DIM_WITH_CC", dimensionToVerify.get().getName());
            Assert.assertEquals(ColumnStatus.DIMENSION, dimensionToVerify.get().getStatus());
        }

        // Add measure which uses TEST_CC_1
        final int measureIdOfCC;
        {
            ModelRequest request = newSemanticRequest();
            SimplifiedMeasure newMeasure = new SimplifiedMeasure();
            newMeasure.setName("TEST_MEASURE_WITH_CC");
            newMeasure.setExpression("SUM");
            newMeasure.setReturnType("bigint");
            ParameterResponse param = new ParameterResponse("column", ccColName);
            newMeasure.setParameterValue(Lists.newArrayList(param));
            request.getSimplifiedMeasures().add(newMeasure);

            SimplifiedMeasure newMeasure2 = new SimplifiedMeasure();
            newMeasure2.setName("TEST_MEASURE_CONSTANT");
            newMeasure2.setExpression("SUM");
            newMeasure2.setReturnType("bigint");
            newMeasure2.setParameterValue(Lists.newArrayList(new ParameterResponse("constant", "1")));
            request.getSimplifiedMeasures().add(newMeasure2);

            modelService.updateDataModelSemantic(request.getProject(), request);

            NDataModel model = getTestModel();
            Optional<Measure> measure = model.getAllMeasures().stream()
                    .filter(m -> m.getName().equals("TEST_MEASURE_WITH_CC")).findFirst();
            Assert.assertTrue(measure.isPresent());
            measureIdOfCC = measure.get().getId();
            Assert.assertTrue(measure.get().getFunction().isSum());
            Assert.assertEquals(ccColName, measure.get().getFunction().getParameters().get(0).getValue());
        }

        // Update TEST_CC_1's definition, named column and measure will be updated
        {
            ModelRequest request = newSemanticRequest();
            Optional<ComputedColumnDesc> ccDesc = request.getComputedColumnDescs().stream()
                    .filter(cc -> cc.getColumnName().equals("TEST_CC_1")).findFirst();
            Assert.assertTrue(ccDesc.isPresent());
            ccDesc.get().setExpression("1 + 2");
            modelService.updateDataModelSemantic(request.getProject(), request);

            NDataModel model = getTestModel();
            Optional<NamedColumn> originalColumn = model.getAllNamedColumns().stream()
                    .filter(col -> col.getId() == colIdOfCC).findFirst();
            Assert.assertTrue(originalColumn.isPresent());
            Assert.assertEquals("TEST_DIM_WITH_CC", originalColumn.get().getName());
            Assert.assertEquals(ColumnStatus.DIMENSION, originalColumn.get().getStatus());

            Optional<Measure> originalMeasure = model.getAllMeasures().stream().filter(m -> m.getId() == measureIdOfCC)
                    .findFirst();
            Assert.assertTrue(originalMeasure.isPresent());
            Assert.assertEquals("TEST_MEASURE_WITH_CC", originalMeasure.get().getName());
            Assert.assertFalse(originalMeasure.get().isTomb());
        }

        // Remove TEST_CC_1, all related should be moved to tomb
        {
            ModelRequest request = newSemanticRequest();
            request.getComputedColumnDescs().removeIf(cc -> cc.getColumnName().equals("TEST_CC_1"));
            request.getAllNamedColumns().stream()
                    .filter(column -> column.getAliasDotColumn().equalsIgnoreCase(ccColName))
                    .forEach(column -> column.setStatus(ColumnStatus.TOMB));
            request.getSimplifiedDimensions()
                    .removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase(ccColName));

            modelService.updateDataModelSemantic(request.getProject(), request);
            NDataModel model = getTestModel();
            Optional<NamedColumn> first = model.getAllNamedColumns().stream().filter(c -> c.getId() == colIdOfCC)
                    .findFirst();
            Assert.assertTrue(first.isPresent());
            Assert.assertFalse(first.get().isExist());
            Optional<Measure> second = model.getAllMeasures().stream().filter(m -> m.getId() == measureIdOfCC)
                    .findFirst();
            Assert.assertTrue(second.isPresent());
            Assert.assertTrue(second.get().isTomb());
        }
    }

    @Test
    public void testModelUpdateMeasures() throws Exception {
        val request = newSemanticRequest();
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("GMV_AVG");
        newMeasure1.setExpression("AVG");
        newMeasure1.setReturnType("bitmap");
        val param = new ParameterResponse("column", "TEST_KYLIN_FACT.PRICE");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        request.setSimplifiedMeasures(request.getSimplifiedMeasures().stream()
                .filter(m -> m.getId() != 100002 && m.getId() != 100003).collect(Collectors.toList()));
        // add new measure and remove 1002 and 1003
        IndexPlan indexPlan = NIndexPlanManager.getInstance(getTestConfig(), getProject())
                .getIndexPlan(getTestModel().getUuid());
        UnitOfWork.doInTransactionWithRetry(() -> {
            NIndexPlanManager.getInstance(getTestConfig(), getProject()).updateIndexPlan(indexPlan.getUuid(),
                    copyForWrite -> copyForWrite.setIndexes(new ArrayList<>()));
            return 0;
        }, getProject());
        modelService.updateDataModelSemantic(getProject(), request);

        val model = getTestModel();
        Assert.assertEquals("GMV_AVG", model.getEffectiveMeasures().get(100018).getName());
        Assert.assertNull(model.getEffectiveMeasures().get(100002));
        Assert.assertNull(model.getEffectiveMeasures().get(100003));
    }

    @Test
    public void testUpdateMeasure_DuplicateParams() throws Exception {
        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "The definition of this measure  is the same as measure \"TRANS_SUM2\". Please modify it.");
        val request = newSemanticRequest();
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("TRANS_SUM2");
        newMeasure1.setExpression("SUM");
        val param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_KYLIN_FACT.PRICE");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        newMeasure1.setReturnType("decimal");
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testUpdateMeasure_ChangeReturnType() throws Exception {
        val request = newSemanticRequest();
        for (SimplifiedMeasure simplifiedMeasure : request.getSimplifiedMeasures()) {
            if (simplifiedMeasure.getReturnType().equals("bitmap")) {
                simplifiedMeasure.setReturnType("hllc(12)");
            }
        }
        IndexPlan indexPlan = NIndexPlanManager.getInstance(getTestConfig(), getProject())
                .getIndexPlan(getTestModel().getUuid());
        UnitOfWork.doInTransactionWithRetry(() -> {
            NIndexPlanManager.getInstance(getTestConfig(), getProject()).updateIndexPlan(indexPlan.getUuid(),
                    copyForWrite -> {
                        copyForWrite.setIndexes(new ArrayList<>());
                    });
            return 0;
        }, getProject());
        modelService.updateDataModelSemantic(getProject(), request);
        val modelMgr = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        val model = modelMgr.getDataModelDesc(BASIC_MODEL);
        Assert.assertNull(model.getEffectiveMeasures().get(100010));
        Assert.assertEquals(1, model.getAllMeasures().stream()
                .filter(m -> m.getFunction().getReturnType().equals("hllc(12)")).count());
    }

    @Test
    public void testModelUpdateMeasureName() throws Exception {
        val request = newSemanticRequest();
        request.getSimplifiedMeasures().get(0).setName("NEW_MEASURE");
        val originId = request.getSimplifiedMeasures().get(0).getId();
        modelService.updateDataModelSemantic(getProject(), request);

        val model = getTestModel();
        Assert.assertEquals("NEW_MEASURE", model.getEffectiveMeasures().get(originId).getName());
    }

    @Test
    public void testRenameTableAlias() throws Exception {
        var request = newSemanticRequest();
        request = changeAlias(request, "TEST_ORDER", "NEW_ALIAS");
        modelService.updateDataModelSemantic(getProject(), request);

        val model = getTestModel();
        val tombCount = model.getAllNamedColumns().stream().filter(n -> n.getAliasDotColumn().startsWith("TEST_ORDER"))
                .peek(col -> Assert.assertEquals(ColumnStatus.TOMB, col.getStatus())).count();
        Assert.assertEquals(0, tombCount);
        val otherTombCount = model.getAllNamedColumns().stream()
                .filter(n -> !n.getAliasDotColumn().startsWith("TEST_ORDER")).filter(nc -> !nc.isExist()).count();
        Assert.assertEquals(1, otherTombCount);
        Assert.assertEquals(202, model.getAllNamedColumns().size());
        val executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(0, executables.size());
    }

    @Test
    public void testRenameTableAliasUsedWithSimplifiedMeasure() throws IOException {
        String project = getProject();
        val modelManager = NDataModelManager.getInstance(getTestConfig(), project);
        modelManager.listAllModels().forEach(modelManager::dropModel);
        val request = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure.json"), ModelRequest.class);
        request.setAlias("model_with_measure");
        val newModel = modelService.createModel(project, request);
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            // prepare dirty model
            NDataModelManager modelMgr = NDataModelManager.getInstance(getTestConfig(), project);
            modelMgr.updateDataModel(newModel.getId(), copyForWrite -> {
                List<Measure> allMeasures = copyForWrite.getAllMeasures();
                Measure measure = new Measure();
                measure.setId(100002);
                measure.setType(NDataModel.MeasureType.NORMAL);
                measure.setName("MAX2");
                FunctionDesc function = new FunctionDesc();
                function.setExpression("MAX");
                function.setReturnType("integer");
                function.setConfiguration(Maps.newLinkedHashMap());
                ParameterDesc parameter = new ParameterDesc();
                parameter.setType("column");
                parameter.setValue("TEST_ACCOUNT.ACCOUNT_SELLER_LEVEL");
                parameter.setColRef(allMeasures.get(0).getFunction().getParameters().get(0).getColRef());
                function.setParameters(ImmutableList.of(parameter));
                measure.setFunction(function);
                allMeasures.add(measure);
            });
            return null;
        }, project);
        val updateRequest = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure_change_alias.json"),
                ModelRequest.class);
        updateRequest.setAlias("model_with_measure_change_alias");
        updateRequest.setUuid(newModel.getUuid());
        List<SimplifiedMeasure> simplifiedMeasures = updateRequest.getSimplifiedMeasures();
        simplifiedMeasures.get(0).setId(100000);
        simplifiedMeasures.get(1).setId(100001);
        SimplifiedMeasure simplifiedMeasure = new SimplifiedMeasure();
        ParameterResponse param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_ACCOUNT.ACCOUNT_SELLER_LEVEL");
        simplifiedMeasure.setParameterValue(ImmutableList.of(param));
        simplifiedMeasure.setExpression("MAX");
        simplifiedMeasure.setName("MAX2");
        simplifiedMeasure.setReturnType("integer");
        simplifiedMeasures.add(simplifiedMeasure);
        try {
            modelService.updateDataModelSemantic(project, updateRequest);
            Assert.fail();
        } catch (KylinException e) {
            Assert.assertEquals(ErrorCodeServer.SIMPLIFIED_MEASURES_MISSING_ID.getErrorCode().getCode(),
                    e.getErrorCodeString());
        }
    }

    @Test
    public void testModifyNestedComputedColumn() throws IOException {
        NDataModelManager modelManager = NDataModelManager.getInstance(getTestConfig(), getProject());
        modelManager.listAllModels().forEach(modelManager::dropModel);
        ModelRequest request = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure.json"), ModelRequest.class);
        request.setAlias("model_with_measure");
        NDataModel newModel = modelService.createModel(request.getProject(), request);

        NDataModel model = modelManager.getDataModelDesc(newModel.getId());
        List<ComputedColumnDesc> ccList = model.getComputedColumnDescs();
        ComputedColumnDesc cc1 = new ComputedColumnDesc();
        cc1.setExpression("TEST_ORDER.BUYER_ID + 1");
        cc1.setInnerExpression("`TEST_ORDER`.`BUYER_ID` + 1");
        cc1.setColumnName("CC1");
        cc1.setDatatype("bigint");
        cc1.setTableAlias("TEST_ORDER");
        cc1.setTableIdentity("DEFAULT.TEST_ORDER");
        ComputedColumnDesc cc2 = new ComputedColumnDesc();
        cc2.setExpression("TEST_ORDER.BUYER_ID + TEST_ORDER.CC3");
        cc2.setInnerExpression("`TEST_ORDER`.`BUYER_ID` + (`TEST_ORDER`.`BUYER_ID` + 3)");
        cc2.setColumnName("CC2");
        cc2.setDatatype("bigint");
        cc2.setTableAlias("TEST_ORDER");
        cc2.setTableIdentity("DEFAULT.TEST_ORDER");
        ComputedColumnDesc cc3 = new ComputedColumnDesc();
        cc3.setExpression("TEST_ORDER.BUYER_ID + 3");
        cc3.setInnerExpression("`TEST_ORDER`.`BUYER_ID` + 3");
        cc3.setColumnName("CC3");
        cc3.setDatatype("bigint");
        cc3.setTableAlias("TEST_ORDER");
        cc3.setTableIdentity("DEFAULT.TEST_ORDER");
        ccList.add(cc1);
        ccList.add(cc2);
        ccList.add(cc3);
        ComputedColumnManager ccManager = ComputedColumnManager.getInstance(getTestConfig(), getProject());
        ccList.forEach(cc -> {
            ComputedColumnDesc created = ccManager.saveCCWithCheck(model, cc);
            model.getComputedColumnUuids().add(created.getUuid());
        });

        List<NamedColumn> allNamedColumns = model.getAllNamedColumns();
        NamedColumn col1 = new NamedColumn();
        col1.setStatus(ColumnStatus.DIMENSION);
        col1.setName("CC1");
        col1.setAliasDotColumn("TEST_ORDER.CC1");
        col1.setId(10);
        NamedColumn col2 = new NamedColumn();
        col2.setStatus(ColumnStatus.DIMENSION);
        col2.setName("CC2");
        col2.setAliasDotColumn("TEST_ORDER.CC2");
        col2.setId(11);
        NamedColumn col3 = new NamedColumn();
        col3.setStatus(ColumnStatus.DIMENSION);
        col3.setName("CC3");
        col3.setAliasDotColumn("TEST_ORDER.CC3");
        col3.setId(12);
        allNamedColumns.add(col1);
        allNamedColumns.add(col2);
        allNamedColumns.add(col3);

        ModelSemanticHelper modelSemanticHelper = new ModelSemanticHelper();
        modelSemanticHelper.discardInvalidColsAndMeasForBrokenModel(getProject(), model);
        Assert.assertEquals(3, model.getComputedColumnDescs().size());
    }

    @Test
    public void testMockFixDirtyModelWhenSaving() throws IOException {
        val modelManager = NDataModelManager.getInstance(getTestConfig(), getProject());
        modelManager.listAllModels().forEach(modelManager::dropModel);
        val request = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure.json"), ModelRequest.class);
        request.setAlias("model_with_measure");
        val newModel = modelService.createModel(request.getProject(), request);
        EnhancedUnitOfWork.doInTransactionWithCheckAndRetry(() -> {
            // prepare dirty model
            NDataModelManager modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
            modelMgr.updateDataModel(newModel.getId(), copyForWrite -> {
                List<Measure> allMeasures = copyForWrite.getAllMeasures();
                Measure measure = new Measure();
                measure.setId(100002);
                measure.setType(NDataModel.MeasureType.NORMAL);
                measure.setName("MAX2");
                FunctionDesc function = new FunctionDesc();
                function.setExpression("MAX");
                function.setReturnType("integer");
                function.setConfiguration(Maps.newLinkedHashMap());
                ParameterDesc parameter = new ParameterDesc();
                parameter.setType("column");
                parameter.setValue("TEST_ACCOUNT.ACCOUNT_SELLER_LEVEL");
                parameter.setColRef(allMeasures.get(0).getFunction().getParameters().get(0).getColRef());
                function.setParameters(ImmutableList.of(parameter));
                measure.setFunction(function);
                allMeasures.add(measure);
            });
            return null;
        }, getProject());

        // set max2 to tomb
        val updateRequest = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure_change_alias.json"),
                ModelRequest.class);
        updateRequest.setAlias("model_with_measure_change_alias");
        updateRequest.setUuid(newModel.getUuid());
        List<SimplifiedMeasure> simplifiedMeasures = updateRequest.getSimplifiedMeasures();
        simplifiedMeasures.get(0).setId(100000);
        simplifiedMeasures.get(1).setId(100001);
        modelService.updateDataModelSemantic(getProject(), updateRequest);

        NDataModel modifiedModel = modelManager.getDataModelDesc(newModel.getUuid());
        List<Measure> allMeasures = modifiedModel.getAllMeasures();
        Optional<Measure> max2 = allMeasures.stream().filter(measure -> measure.getName().equals("MAX2")).findFirst();
        Assert.assertTrue(max2.isPresent());
        Assert.assertTrue(max2.get().isTomb());
    }

    @Test
    public void testRenameTableAliasUsedAsMeasure() throws Exception {
        val modelManager = NDataModelManager.getInstance(getTestConfig(), getProject());
        modelManager.listAllModels().forEach(modelManager::dropModel);
        val request = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure.json"), ModelRequest.class);
        request.setAlias("model_with_measure");
        val newModel = modelService.createModel(request.getProject(), request);
        Map<String, Integer> measureMap = newModel.getAllMeasures().stream()
                .collect(Collectors.toMap(Measure::getName, Measure::getId));
        val updateRequest = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure_change_alias.json"),
                ModelRequest.class);
        updateRequest.setAlias("model_with_measure_change_alias");
        updateRequest.setUuid(newModel.getUuid());
        updateRequest.getSimplifiedMeasures().forEach(measure -> measure.setId(measureMap.get(measure.getName())));
        modelService.updateDataModelSemantic(getProject(), updateRequest);

        var model = modelService.getManager(NDataModelManager.class, getProject())
                .getDataModelDesc(updateRequest.getUuid());
        Assert.assertEquals(Lists.newArrayList("MAX1", "COUNT_ALL"),
                model.getAllMeasures().stream().filter(m -> !m.isTomb()).sorted(Comparator.comparing(Measure::getId))
                        .map(MeasureDesc::getName).collect(Collectors.toList()));

        // make sure update again is ok
        val updateRequest2 = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_measure_change_alias_twice.json"),
                ModelRequest.class);
        updateRequest2.setUuid(newModel.getUuid());
        updateRequest2.setAlias("model_with_measure_change_alias_twice");
        updateRequest2.getSimplifiedMeasures().forEach(measure -> measure.setId(measureMap.get(measure.getName())));
        modelService.updateDataModelSemantic(getProject(), updateRequest2);
        model = modelService.getManager(NDataModelManager.class, getProject())
                .getDataModelDesc(updateRequest.getUuid());
        List<Measure> allMeasures = model.getAllMeasures();
        Assert.assertEquals(Lists.newArrayList("MAX1", "COUNT_ALL"), allMeasures.stream().filter(m -> !m.isTomb())
                .sorted(Comparator.comparing(Measure::getId)).map(MeasureDesc::getName).collect(Collectors.toList()));
        Assert.assertEquals(2, allMeasures.size());
        Assert.assertEquals(2, allMeasures.stream().filter(measure -> !measure.isTomb()).count());
    }

    @Test
    public void testModelUpdateDimensions() throws Exception {
        val request = newSemanticRequest();

        // reserve cc & corresponding column
        String ccDealYear = "DEAL_YEAR";
        Optional<ComputedColumnDesc> ccDescOptional = request.getComputedColumnDescs().stream()
                .filter(cc -> ccDealYear.equals(cc.getColumnName())).findFirst();
        Assert.assertTrue(ccDescOptional.isPresent());
        ComputedColumnDesc ccDesc = ccDescOptional.get();
        Optional<NamedColumn> ccCol = request.getAllNamedColumns().stream()
                .filter(c -> c.getAliasDotColumn().equals(ccDesc.getFullName())).findFirst();
        Assert.assertTrue(ccCol.isPresent());

        // set "TEST_KYLIN_FACT.PRICE" as dimension and rename
        String colPrice = "TEST_KYLIN_FACT.PRICE";
        request.getAllNamedColumns().stream() //
                .filter(column -> colPrice.equalsIgnoreCase(column.getAliasDotColumn())) //
                .forEach(column -> {
                    column.setName("PRICE2");
                    column.setStatus(NDataModel.ColumnStatus.DIMENSION);
                });
        List<NamedColumn> dimensions = request.getAllNamedColumns().stream().filter(NamedColumn::isDimension)
                .collect(Collectors.toList());
        request.getComputedColumnDescs().removeIf(cc -> cc.getColumnName().equalsIgnoreCase(ccDealYear));
        dimensions.removeIf(column -> ccDesc.getFullName().equalsIgnoreCase(column.getAliasDotColumn()));
        dimensions.removeIf(column -> column.getId() == 25);
        request.setSimplifiedDimensions(dimensions);
        request.getOtherColumns().stream() //
                .filter(column -> ccDesc.getFullName().equalsIgnoreCase(column.getAliasDotColumn()))
                .forEach(column -> column.setStatus(ColumnStatus.TOMB));
        request.getOtherColumns().removeIf(column -> colPrice.equalsIgnoreCase(column.getAliasDotColumn()));

        int preservedId = getTestModel().getAllNamedColumns().stream()
                .filter(n -> n.getAliasDotColumn().equals(colPrice)) //
                .findFirst().map(NamedColumn::getId).orElse(0);
        IndexPlan indexPlan = NIndexPlanManager.getInstance(getTestConfig(), getProject())
                .getIndexPlan(getTestModel().getUuid());
        UnitOfWork.doInTransactionWithRetry(() -> {
            NIndexPlanManager.getInstance(getTestConfig(), getProject()).updateIndexPlan(indexPlan.getUuid(),
                    copyForWrite -> copyForWrite.setIndexes(new ArrayList<>()));
            return 0;
        }, getProject());
        modelService.updateDataModelSemantic(getProject(), request);

        val model = getTestModel();
        Assert.assertEquals("PRICE2", model.getNameByColumnId(preservedId));
        Assert.assertNull(model.getEffectiveDimensions().get(25));
        Assert.assertFalse(model.getComputedColumnNames().contains(ccDealYear));
        Assert.assertNull(model.getEffectiveDimensions().get(ccCol.get().getId()));
        Assert.assertNull(model.getEffectiveCols().get(ccCol.get().getId()));

        // rename & update again
        request.getAllNamedColumns().stream() //
                .filter(column -> colPrice.equalsIgnoreCase(column.getAliasDotColumn())) //
                .forEach(column -> {
                    column.setName("PRICE3");
                    column.setStatus(NDataModel.ColumnStatus.DIMENSION);
                });
        request.getComputedColumnDescs().add(ccDesc);
        modelService.updateDataModelSemantic(getProject(), request);
        val model2 = getTestModel();
        Assert.assertEquals("PRICE3", model2.getNameByColumnId(preservedId));
        Assert.assertTrue(model2.getComputedColumnNames().contains(ccDealYear));
        NamedColumn newCcCol = model2.getAllNamedColumns().stream()
                .filter(c -> c.getAliasDotColumn().equals(ccDesc.getFullName())).filter(NamedColumn::isExist)
                .findFirst().orElse(null);
        Assert.assertNotNull(newCcCol);
        Assert.assertNotEquals(ccCol.get().getId(), newCcCol.getId());
    }

    @Test
    public void testModelAddDimensions() throws Exception {
        String project = getProject();
        val modelMgr = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
        val model = modelMgr.getDataModelDesc(BASIC_MODEL);

        // delete all indexes and agg groups
        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val LayoutList = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream().map(LayoutEntity::getId)
                .collect(Collectors.toSet());
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.removeIndexes(getProject(), BASIC_MODEL, LayoutList);
            indexPlanService.updateRuleBasedCuboid(getProject(),
                    UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                            .aggregationGroups(java.util.Collections.emptyList()).build());
            return true;
        }, getProject());

        // modify dimensions and measures, 2 dimensions and 1 measures
        val request = JsonUtil.readValue(JsonUtil.writeValueAsString(model), ModelRequest.class);
        request.setProject(project);
        request.setUuid(BASIC_MODEL);
        request.setSimplifiedDimensions(model.getAllNamedColumns().stream().filter(NDataModel.NamedColumn::isDimension)
                .filter(f -> f.getAliasDotColumn().contains("TEST_KYLIN_FACT")).collect(Collectors.toList())
                .subList(0, 2));
        request.setSimplifiedMeasures(
                model.getAllMeasures().stream().filter(m -> !m.isTomb()).filter(m -> m.getId() == 100000)
                        .map(SimplifiedMeasure::fromMeasure).collect(Collectors.toList()).subList(0, 1));

        request.setWithBaseIndex(true);
        val requestj = JsonUtil.readValue(JsonUtil.writeValueAsString(request), ModelRequest.class);
        modelService.updateDataModelSemantic(getProject(), requestj);

        // add 1 agg groups
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { 1, 2 });
        newAggregationGroup.setMeasures(new Integer[] { 100000 });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);

        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.updateRuleBasedCuboid(getProject(),
                    UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                            .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build());
            return true;
        }, getProject());

        Long bAL = indexPlanManager.getIndexPlan(BASIC_MODEL).getBaseAggLayout().getId();
        java.util.Set<Long> allLIs = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayoutIds(false);

        // add new dimension
        val request3 = JsonUtil.readValue(JsonUtil.writeValueAsString(model), ModelRequest.class);
        request3.setProject(project);
        request3.setUuid(BASIC_MODEL);
        request3.setSimplifiedDimensions(model.getAllNamedColumns().stream().filter(NDataModel.NamedColumn::isDimension)
                .filter(f -> f.getAliasDotColumn().contains("TEST_KYLIN_FACT")).collect(Collectors.toList())
                .subList(0, 3));
        request3.setSimplifiedMeasures(
                model.getAllMeasures().stream().filter(m -> !m.isTomb()).filter(m -> m.getId() == 100000)
                        .map(SimplifiedMeasure::fromMeasure).collect(Collectors.toList()).subList(0, 1));

        request3.setWithBaseIndex(true);
        val requestj3 = JsonUtil.readValue(JsonUtil.writeValueAsString(request3), ModelRequest.class);
        modelService.updateDataModelSemantic(getProject(), requestj3);

        // get all layout, baseLayout
        Long bAL2 = indexPlanManager.getIndexPlan(BASIC_MODEL).getBaseAggLayout().getId();
        java.util.Set<Long> allLIs2 = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayoutIds(false);

        Assert.assertNotEquals(bAL, bAL2);
        Assert.assertNotEquals(allLIs.size(), allLIs2.size());
        Assert.assertTrue(allLIs2.contains(bAL));

        // for test coverage
        // delete base agg layout
        val LayoutListBase = indexPlanManager.getIndexPlan(BASIC_MODEL).getBaseAggLayout().getId();
        HashSet<Long> toDeleteIds = new HashSet<>();
        toDeleteIds.add(LayoutListBase);
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.removeIndexes(getProject(), BASIC_MODEL, toDeleteIds);
            indexPlanService.updateRuleBasedCuboid(getProject(),
                    UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                            .aggregationGroups(java.util.Collections.emptyList()).build());
            return true;
        }, getProject());

        // add new dimenssion
        val request4 = JsonUtil.readValue(JsonUtil.writeValueAsString(model), ModelRequest.class);
        request4.setProject(project);
        request4.setUuid(BASIC_MODEL);
        request4.setSimplifiedDimensions(model.getAllNamedColumns().stream().filter(NDataModel.NamedColumn::isDimension)
                .filter(f -> f.getAliasDotColumn().contains("TEST_KYLIN_FACT")).collect(Collectors.toList())
                .subList(0, 4));
        request4.setSimplifiedMeasures(
                model.getAllMeasures().stream().filter(m -> !m.isTomb()).filter(m -> m.getId() == 100000)
                        .map(SimplifiedMeasure::fromMeasure).collect(Collectors.toList()).subList(0, 1));

        request4.setWithBaseIndex(true);
        val requestj4 = JsonUtil.readValue(JsonUtil.writeValueAsString(request4), ModelRequest.class);
        modelService.updateDataModelSemantic(getProject(), requestj4);

    }

    @Test
    public void testRemoveColumnExistInTableIndex() throws Exception {
        val request = newSemanticRequest();
        request.getComputedColumnDescs().removeIf(cc -> cc.getColumnName().equalsIgnoreCase("DEAL_YEAR"));
        request.getAllNamedColumns().stream()
                .filter(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.PRICE"))
                .forEach(column -> {
                    column.setName("PRICE2");
                    column.setStatus(NDataModel.ColumnStatus.DIMENSION);
                });
        List<NamedColumn> dimensions = request.getAllNamedColumns().stream().filter(NamedColumn::isDimension)
                .collect(Collectors.toList());
        dimensions.removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.DEAL_YEAR"));
        dimensions.removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase("BUYER_COUNTRY.NAME"));
        request.setSimplifiedDimensions(dimensions);
        request.getOtherColumns().stream()
                .filter(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.DEAL_YEAR"))
                .forEach(column -> column.setStatus(ColumnStatus.TOMB));

        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "The dimension BUYER_COUNTRY.NAME,TEST_KYLIN_FACT.DEAL_YEAR is referenced by indexes or aggregate groups. "
                        + "Please go to the Data Asset - Model - Index page to view, delete referenced aggregate groups and indexes.");
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testRemoveDimensionExistInAggIndex() throws Exception {
        String modelId = "82fa7671-a935-45f5-8779-85703601f49a";
        val request = newSemanticRequest(modelId);
        request.setSimplifiedDimensions(request.getAllNamedColumns().stream()
                .filter(c -> c.isDimension() && c.getId() != 25).collect(Collectors.toList()));
        NamedColumn dimDesc = request.getSimplifiedDimensions().stream()
                .filter(cc -> "LSTG_FORMAT_NAME".equals(cc.getName())).findFirst().orElse(null);
        Assert.assertNotNull(dimDesc);
        request.getSimplifiedDimensions().remove(dimDesc);
        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "The dimension TEST_KYLIN_FACT.LSTG_FORMAT_NAME,BUYER_COUNTRY.NAME is referenced by indexes or aggregate groups. "
                        + "Please go to the Data Asset - Model - Index page to view, delete referenced aggregate groups and indexes.");
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testRemoveDimensionOfDirtyModel() throws Exception {
        UpdateRuleBasedCuboidRequest.convertToRequest(getProject(), BASIC_MODEL, false, new RuleBasedIndex());
        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        indexPlanManager.updateIndexPlan(BASIC_MODEL, copyForWrite -> {
            IndexPlan indexPlan = indexPlanManager.getIndexPlan(BASIC_MODEL);
            RuleBasedIndex ruleBasedIndex = new RuleBasedIndex();
            ruleBasedIndex.getMeasures().addAll(Lists.newArrayList(100000, 101000));
            ruleBasedIndex.setSchedulerVersion(2);
            ruleBasedIndex.setGlobalDimCap(0);
            ruleBasedIndex.setLayoutIdMapping(Lists.newArrayList());
            ruleBasedIndex.setIndexStartId(indexPlan.getNextAggregationIndexId());
            copyForWrite.setRuleBasedIndex(ruleBasedIndex);
        });
        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "The dimension TEST_KYLIN_FACT.LSTG_FORMAT_NAME is referenced by indexes or aggregate groups. "
                        + "Please go to the Data Asset - Model - Index page to view, delete referenced aggregate groups and indexes.");
        val request = newSemanticRequest(BASIC_MODEL);
        request.getSimplifiedDimensions().removeIf(col -> col.getName().equalsIgnoreCase("LSTG_FORMAT_NAME"));
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testRemoveMeasureExistInAggIndex() throws Exception {
        String modelId = "82fa7671-a935-45f5-8779-85703601f49a";
        val request = newSemanticRequest(modelId);
        request.getSimplifiedMeasures().remove(1);
        thrown.expect(KylinException.class);
        thrown.expectMessage("The measure GMV_SUM is referenced by indexes or aggregate groups. Please go to "
                + "the Data Asset - Model - Index page to view, delete referenced aggregate groups and indexes.");
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testRemoveCCInShardCol() throws Exception {
        // ensure model has an agg group
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { 0 });
        newAggregationGroup.setMeasures(new Integer[] { 100000 });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);
        UnitOfWork.doInTransactionWithRetry(//
                () -> indexPlanService
                        .updateRuleBasedCuboid(getProject(),
                                UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                                        .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build()),
                getProject());

        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val indexList = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream().map(LayoutEntity::getId)
                .collect(Collectors.toSet());
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.removeIndexes(getProject(), BASIC_MODEL, indexList);
            return true;
        }, getProject());

        val shardReq = new AggShardByColumnsRequest();
        shardReq.setModelId(BASIC_MODEL);
        shardReq.setProject(getProject());
        shardReq.setShardByColumns(Lists.newArrayList("TEST_KYLIN_FACT.NEST5"));
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.updateShardByColumns(getProject(), shardReq);
            return true;
        }, getProject());

        var request = newSemanticRequest(BASIC_MODEL);
        request.getComputedColumnDescs().removeIf(c -> ("NEST5").equals(c.getColumnName()));
        request.getSimplifiedDimensions()
                .removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.NEST5"));
        request.getOtherColumns().stream()
                .filter(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.NEST5"))
                .forEach(column -> column.setStatus(ColumnStatus.TOMB));
        modelService.updateDataModelSemantic(getProject(), request);
        Assert.assertTrue(indexPlanService.getShardByColumns(getProject(), BASIC_MODEL).getShardByColumns().isEmpty());
    }

    @Test
    public void testRemoveCCExistInTableIndexWithAggGroup() throws Exception {
        // ensure model has an agg group
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { 0 });
        newAggregationGroup.setMeasures(new Integer[] { 100000 });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);
        UnitOfWork.doInTransactionWithRetry(//
                () -> indexPlanService
                        .updateRuleBasedCuboid(getProject(),
                                UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                                        .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build()),
                getProject());

        // remove cc TEST_KYLIN_FACT.NEST5
        var request = newSemanticRequest(BASIC_MODEL);
        request.getComputedColumnDescs().removeIf(c -> ("NEST5").equals(c.getColumnName()));
        request.getSimplifiedDimensions()
                .removeIf(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.NEST5"));
        request.getOtherColumns().stream()
                .filter(column -> column.getAliasDotColumn().equalsIgnoreCase("TEST_KYLIN_FACT.NEST5"))
                .forEach(column -> column.setStatus(ColumnStatus.TOMB));
        thrown.expect(IllegalStateException.class);
        thrown.expectMessage("The dimension TEST_KYLIN_FACT.NEST5 is referenced by indexes or aggregate groups. "
                + "Please go to the Data Asset - Model - Index page to view, delete referenced aggregate groups and indexes.");
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testModifyCCExistInTableIndex() throws Exception {
        val indexPlanManager = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        var request = newSemanticRequest(BASIC_MODEL);
        val originPlan = indexPlanManager.getIndexPlan(BASIC_MODEL);
        val nest5 = request.getColumnIdByColumnName("TEST_KYLIN_FACT.NEST5");
        val transId = request.getColumnIdByColumnName("TEST_KYLIN_FACT.TRANS_ID");
        val siteName = request.getColumnIdByColumnName("TEST_SITES.SITE_NAME");
        val indexCol = Arrays.asList(nest5, transId, siteName);
        // old indexes
        val oldIndexId = originPlan.getAllLayouts().stream().filter(l -> l.getColOrder().containsAll(indexCol))
                .findFirst().map(LayoutEntity::getId).orElse(-1L);
        // modify expression of cc TEST_KYLIN_FACT.NEST5
        val originCC = request.getComputedColumnDescs().stream().filter(c -> ("NEST5").equals(c.getColumnName()))
                .findFirst();
        Assert.assertTrue(originCC.isPresent());
        originCC.get().setExpression(originCC.get().getExpression() + "+1");
        modelService.updateDataModelSemantic(getProject(), request);
        // new indexes
        val newPlan = indexPlanManager.getIndexPlan(BASIC_MODEL);
        val newIndexId = newPlan.getAllLayouts().stream().filter(l -> l.getColOrder().containsAll(indexCol)).findFirst()
                .map(LayoutEntity::getId).orElse(-2L);
        Assert.assertTrue(newIndexId > oldIndexId);
    }

    @Test
    public void testModifyCCExistInAggIndex() throws Exception {
        val indexPlanManager = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        // create measure
        var request = newSemanticRequest(BASIC_MODEL);
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("NEST5_SUM");
        newMeasure1.setExpression("SUM");
        val param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_KYLIN_FACT.NEST5");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        newMeasure1.setReturnType("decimal(38, 0)");
        modelService.updateDataModelSemantic(getProject(), request);
        // get dim and measure id
        request = newSemanticRequest(BASIC_MODEL);
        val transId = request.getColumnIdByColumnName("TEST_KYLIN_FACT.TRANS_ID");
        val nest5SumId = request.getSimplifiedMeasures().stream().filter(m -> "NEST5_SUM".equals(m.getName()))
                .mapToInt(SimplifiedMeasure::getId).findFirst().orElse(-1);
        // create agg group
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { transId });
        newAggregationGroup.setMeasures(new Integer[] { nest5SumId });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.updateRuleBasedCuboid(getProject(),
                    UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                            .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build());
            return true;
        }, getProject());
        // old indexes
        val indexCol = Arrays.asList(transId, nest5SumId);
        val oldIndexId = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream()
                .filter(l -> l.getColOrder().containsAll(indexCol)).findFirst().map(LayoutEntity::getId).orElse(-1L);
        // modify expression of cc TEST_KYLIN_FACT.NEST5
        val originCC = request.getComputedColumnDescs().stream().filter(c -> ("NEST5").equals(c.getColumnName()))
                .findFirst();
        Assert.assertTrue(originCC.isPresent());
        originCC.get().setExpression(originCC.get().getExpression() + "+1");
        modelService.updateDataModelSemantic(getProject(), request);
        // new indexes
        val newIndexId = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream()
                .filter(l -> l.getColOrder().containsAll(indexCol)).findFirst().map(LayoutEntity::getId).orElse(-2L);
        Assert.assertTrue(newIndexId > oldIndexId);
    }

    @Test
    public void testModifyCCChangeType() throws Exception {
        val indexPlanManager = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        // create measure
        var request = newSemanticRequest(BASIC_MODEL);
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("NEST5_SUM");
        newMeasure1.setExpression("SUM");
        val param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_KYLIN_FACT.NEST5");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        // return type for SUM(decimal) is "decimal(38, 0)"
        // this will trigger measure return type change
        newMeasure1.setReturnType("any");
        modelService.updateDataModelSemantic(getProject(), request);
        // get dim and measure id
        request = newSemanticRequest(BASIC_MODEL);
        val transId = request.getColumnIdByColumnName("TEST_KYLIN_FACT.TRANS_ID");
        val nest5SumId = request.getSimplifiedMeasures().stream().filter(m -> "NEST5_SUM".equals(m.getName()))
                .mapToInt(SimplifiedMeasure::getId).findFirst().orElse(-1);
        // create agg group
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { transId });
        newAggregationGroup.setMeasures(new Integer[] { nest5SumId });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);
        UnitOfWork
                .doInTransactionWithRetry(
                        () -> indexPlanService.updateRuleBasedCuboid(getProject(),
                                UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                                        .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build()),
                        getProject());
        // old indexes
        val indexCol = Arrays.asList(transId, nest5SumId);
        val oldLayoutId = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream()
                .filter(l -> l.getColOrder().containsAll(indexCol)).findFirst().map(LayoutEntity::getId).orElse(-1L);
        // modify expression of cc TEST_KYLIN_FACT.NEST5
        val originCC = request.getComputedColumnDescs().stream().filter(c -> ("NEST5").equals(c.getColumnName()))
                .findFirst();
        Assert.assertTrue(originCC.isPresent());
        ComputedColumnDesc cc = originCC.get();
        cc.setExpression(cc.getExpression() + "+1");
        modelService.updateDataModelSemantic(getProject(), request);
        // measure NEST5_SUM is reloaded due to return type change
        val newNest5SumId = nest5SumId + 1;
        val newIndexCol = Arrays.asList(transId, newNest5SumId);
        // new layout id
        val newLayoutId = indexPlanManager.getIndexPlan(BASIC_MODEL).getAllLayouts().stream()
                .filter(l -> l.getColOrder().containsAll(newIndexCol)).findFirst().map(LayoutEntity::getId).orElse(-2L);
        Assert.assertTrue(newLayoutId > oldLayoutId);
    }

    @Test
    public void testModifyCCMeasureInvalid() throws Exception {
        // create measure
        var request = newSemanticRequest(BASIC_MODEL);
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("NEST5_SUM");
        newMeasure1.setExpression("SUM");
        val param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_KYLIN_FACT.NEST5");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        // return type for SUM(decimal) is "decimal(38, 0)"
        newMeasure1.setReturnType("decimal(38, 0)");
        modelService.updateDataModelSemantic(getProject(), request);
        // get dim and measure id
        request = newSemanticRequest(BASIC_MODEL);
        val transId = request.getColumnIdByColumnName("TEST_KYLIN_FACT.TRANS_ID");
        val nest5SumId = request.getSimplifiedMeasures().stream().filter(m -> "NEST5_SUM".equals(m.getName()))
                .mapToInt(SimplifiedMeasure::getId).findFirst().orElse(-1);
        // create agg group
        NAggregationGroup newAggregationGroup = new NAggregationGroup();
        newAggregationGroup.setIncludes(new Integer[] { transId });
        newAggregationGroup.setMeasures(new Integer[] { nest5SumId });
        val selectRule = new SelectRule();
        selectRule.mandatoryDims = new Integer[0];
        selectRule.hierarchyDims = new Integer[0][0];
        selectRule.jointDims = new Integer[0][0];
        newAggregationGroup.setSelectRule(selectRule);
        indexPlanService.updateRuleBasedCuboid(getProject(),
                UpdateRuleBasedCuboidRequest.builder().project(getProject()).modelId(BASIC_MODEL)
                        .aggregationGroups(Lists.newArrayList(newAggregationGroup)).build());
        // modify expression of cc TEST_KYLIN_FACT.NEST5
        val originCC = request.getComputedColumnDescs().stream().filter(c -> ("NEST5").equals(c.getColumnName()))
                .findFirst();
        Assert.assertTrue(originCC.isPresent());
        originCC.get().setExpression("'now im a varchar'");
        originCC.get().setInnerExpression("'now im a varchar'");
        originCC.get().setDatatype("VARCHAR");
        try {
            modelService.updateDataModelSemantic(getProject(), request);
        } catch (KylinException e) {
            Assert.assertEquals(
                    "Can’t initialize metadata at the moment. Please try restarting first. If the problem still exist, please contact technical support.",
                    e.getMessage());
        }
    }

    @Test
    public void testModifyCCExistInNestedCC() throws Exception {
        // add nested cc
        var request = newSemanticRequest(BASIC_MODEL);
        ComputedColumnDesc newCC = new ComputedColumnDesc();
        newCC.setColumnName("NEST6");
        newCC.setExpression("TEST_KYLIN_FACT.NEST5+1");
        newCC.setDatatype("decimal(34,0)");
        newCC.setTableIdentity("DEFAULT.TEST_KYLIN_FACT");
        newCC.setTableAlias("TEST_KYLIN_FACT");
        request.getComputedColumnDescs().add(newCC);
        modelService.updateDataModelSemantic(getProject(), request);
        // expect a KylinException when modify cc used by a nested cc
        NDataModel modelDesc = getTestModel();
        thrown.expect(KylinException.class);
        thrown.expectMessage(
                "Can’t modify computed column \"TEST_KYLIN_FACT.NEST5\". It’s been referenced by a nested computed column \"TEST_KYLIN_FACT.NEST6\" in the current model. Please remove it from the nested column first.");
        modelService.checkComputedColumn(modelDesc, getProject(), "TEST_KYLIN_FACT.NEST5");
    }

    @Test
    public void testCreateModelWithMultipleMeasures() throws Exception {
        val request = JsonUtil.readValue(
                getClass().getResourceAsStream("/ut_request/model_update/model_with_multi_measures.json"),
                ModelRequest.class);
        request.setAlias("model_with_multi_measures");
        request.setUuid(null);
        List<NamedColumn> dimensions = request.getAllNamedColumns().stream().filter(NamedColumn::isDimension)
                .collect(Collectors.toList());
        dimensions.removeIf(column -> column.getName().startsWith("LEFTJOIN"));
        dimensions.removeIf(column -> column.getName().startsWith("DEAL"));
        List<NamedColumn> otherColumns = request.getAllNamedColumns().stream()
                .filter(column -> column.isExist() && !column.isDimension()).collect(Collectors.toList());
        request.setSimplifiedDimensions(dimensions);
        request.setOtherColumns(otherColumns);
        request.setAllNamedColumns(Lists.newArrayList());

        val model = modelService.createModel(request.getProject(), request);
        Assert.assertEquals(3, model.getEffectiveMeasures().size());
        Assert.assertEquals(
                model.getEffectiveMeasures().values().stream().map(MeasureDesc::getName).collect(Collectors.toList()),
                Lists.newArrayList("SUM_PRICE", "MAX_COUNT", "COUNT_ALL"));
    }

    @Test
    public void testRemoveDimensionsWithCubePlanRule() throws Exception {
        thrown.expect(KylinException.class);
        thrown.expectMessage("The dimension TEST_KYLIN_FACT.TEST_COUNT_DISTINCT_BITMAP is referenced "
                + "by indexes or aggregate groups. Please go to the Data Asset - Model - Index page to view, delete "
                + "referenced aggregate groups and indexes.");
        UnitOfWork.doInTransactionWithRetry(() -> NIndexPlanManager.getInstance(getTestConfig(), getProject())
                .updateIndexPlan(BASIC_MODEL, cubeBasic -> {
                    val rule = new RuleBasedIndex();
                    rule.setDimensions(Lists.newArrayList(1, 2, 3, 4, 5, 26));
                    rule.setMeasures(Lists.newArrayList(100001, 100002, 100003));
                    cubeBasic.setRuleBasedIndex(rule);
                }), getProject());
        val request = newSemanticRequest();
        request.setSimplifiedDimensions(request.getAllNamedColumns().stream()
                .filter(c -> c.getId() != 26 && c.isExist()).collect(Collectors.toList()));
        modelService.updateDataModelSemantic(getProject(), request);
    }

    @Test
    public void testChangeJoinType() throws Exception {
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();
        UnitOfWork.doInTransactionWithRetry(() -> NDataModelManager.getInstance(getTestConfig(), getProject())
                .updateDataModel(BASIC_MODEL, model -> {
                    val joins = model.getJoinTables();
                    joins.get(0).getJoin().setType("inner");
                }), getProject());
        val cube = dfMgr.getDataflow(originModel.getUuid()).getIndexPlan();
        val tableIndexCount = cube.getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count();
        UnitOfWork.doInTransactionWithRetry(() -> {
            semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, null, null);
            return true;
        }, getProject());
        val executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(1, executables.size());
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);

        Assert.assertEquals(tableIndexCount,
                cube.getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count());
    }

    @Test
    public void testChangePartitionDesc() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();
        val cube = dfMgr.getDataflow(originModel.getUuid()).getIndexPlan();
        val tableIndexCount = cube.getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count();

        modelMgr.updateDataModel(BASIC_MODEL, model -> {
            val partitionDesc = model.getPartitionDesc();
            partitionDesc.setCubePartitionType(PartitionDesc.PartitionType.UPDATE_INSERT);
        });
        semanticService.handleSemanticUpdate(getProject(), originModel.getUuid(), originModel, null, null);

        val df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(0, df.getSegments().size());
        Assert.assertEquals(tableIndexCount,
                df.getIndexPlan().getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count());
        Assert.assertEquals(RealizationStatusEnum.OFFLINE, df.getStatus());
    }

    @Test
    public void testChangePartitionDesc_EmptyToNull() throws Exception {
        final String modelId = "cb596712-3a09-46f8-aea1-988b43fe9b6c";
        NDataModelManager.getInstance(getTestConfig(), getProject()).updateDataModel(modelId,
                copyForWrite -> copyForWrite.setManagementType(ManagementType.MODEL_BASED));
        var request = newSemanticRequest(modelId);
        modelService.updateDataModelStatus(modelId, getProject(), "ONLINE");
        request.setPartitionDesc(null);
        modelService.updateDataModelSemantic(getProject(), request);
        Assert.assertEquals(RealizationStatusEnum.ONLINE, modelService.getModelStatus(modelId, getProject()));
    }

    @Ignore("KE-42912")
    @Test
    public void testChangeParititionDesc_OneToNull() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();
        val cube = dfMgr.getDataflow(originModel.getUuid()).getIndexPlan();
        val tableIndexCount = cube.getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count();

        modelMgr.updateDataModel(BASIC_MODEL, model -> model.setPartitionDesc(null));
        semanticService.handleSemanticUpdate(getProject(), originModel.getUuid(), originModel, null, null);

        val executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(1, executables.size());
        val df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(1, df.getSegments().size());
        Assert.assertEquals(tableIndexCount,
                df.getIndexPlan().getAllLayouts().stream().filter(l -> l.getIndex().isTableIndex()).count());
    }

    @Test
    public void testChangePartitionDesc_NullToOne() {
        UnitOfWork.doInTransactionWithRetry(() -> {
            val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
            modelMgr.updateDataModel(BASIC_MODEL, model -> model.setPartitionDesc(null));

            val originModel = modelMgr.getDataModelDesc(BASIC_MODEL);

            modelMgr.updateDataModel(BASIC_MODEL, model -> {
                val partition = new PartitionDesc();
                partition.setPartitionDateColumn("DEFAULT.TEST_KYLIN_FACT.CAL_DT");
                partition.setPartitionDateFormat("yyyy-MM-dd");
                model.setPartitionDesc(partition);
            });

            semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, "1325347200000",
                    "1388505600000");
            return true;
        }, getProject());

        val executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(1, executables.size());

        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(1, df.getSegments().size());

        val segment = df.getSegments().get(0);
        Assert.assertEquals(1325347200000L, segment.getTSRange().getStart());
        Assert.assertEquals(1388505600000L, segment.getTSRange().getEnd());
    }

    @Test
    public void testChangePartitionDesc_NullToOneWithNoDateRange() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        modelMgr.updateDataModel(BASIC_MODEL, model -> model.setPartitionDesc(null));

        val originModel = modelMgr.getDataModelDesc(BASIC_MODEL);

        modelMgr.updateDataModel(BASIC_MODEL, model -> {
            val partition = new PartitionDesc();
            partition.setPartitionDateColumn("DEFAULT.TEST_KYLIN_FACT.CAL_DT");
            partition.setPartitionDateFormat("yyyy-MM-dd");
            model.setPartitionDesc(partition);
        });

        semanticService.handleSemanticUpdate(getProject(), originModel.getUuid(), originModel, null, null);

        val executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(0, executables.size());
        val df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(0, df.getSegments().size());
    }

    @Test
    public void testChangePartitionDesc_ChangePartitionColumn() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();

        modelMgr.updateDataModel(BASIC_MODEL, model -> {
            val partition = new PartitionDesc();
            partition.setPartitionDateColumn("DEFAULT.TEST_KYLIN_FACT.TRANS_ID");
            partition.setPartitionDateFormat("yyyy-MM-dd");
            model.setPartitionDesc(partition);
        });

        var df = dfMgr.getDataflow(BASIC_MODEL);
        Assert.assertEquals(1, df.getSegments().size());

        semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, null, null);

        val executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(0, executables.size());
        df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(0, df.getSegments().size());
    }

    @Test
    public void testChangePartitionDesc_ChangePartitionColumn_WithDateRange() {
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();

        UnitOfWork.doInTransactionWithRetry(() -> NDataModelManager.getInstance(getTestConfig(), getProject())
                .updateDataModel(BASIC_MODEL, model -> {
                    val partition = new PartitionDesc();
                    partition.setPartitionDateColumn("DEFAULT.TEST_KYLIN_FACT.TRANS_ID");
                    partition.setPartitionDateFormat("yyyy-MM-dd");
                    model.setPartitionDesc(partition);
                }), getProject());

        var df = dfMgr.getDataflow(BASIC_MODEL);
        Assert.assertEquals(1, df.getSegments().size());

        UnitOfWork.doInTransactionWithRetry(() -> {
            semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, "1325347200000",
                    "1388505600000");
            return true;
        }, getProject());

        val executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(1, executables.size());
        df = dfMgr.getDataflow(BASIC_MODEL);

        Assert.assertEquals(1, df.getSegments().size());
        val segment = df.getSegments().get(0);

        Assert.assertEquals(1325347200000L, segment.getSegRange().getStart());
        Assert.assertEquals(1388505600000L, segment.getSegRange().getEnd());
    }

    @Test
    public void testOnlyAddDimensions() throws Exception {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();
        modelMgr.updateDataModel(BASIC_MODEL,
                model -> model.setAllNamedColumns(model.getAllNamedColumns().stream().peek(c -> {
                    if (!c.isExist()) {
                        return;
                    }
                    c.setStatus(NDataModel.ColumnStatus.DIMENSION);
                }).collect(Collectors.toList())));
        semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, null, null);
        val executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(0, executables.size());
    }

    @Test
    public void testOnlyChangeMeasures() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val indePlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestBasicModel();
        modelMgr.updateDataModel(BASIC_MODEL, model -> model.setAllMeasures(model.getAllMeasures().stream().peek(m -> {
            if (m.getId() == 100011) {
                m.setId(100018);
            }
        }).collect(Collectors.toList())));
        semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, null, null);

        var executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(0, executables.size());

        indePlanManager.updateIndexPlan(BASIC_MODEL, copyForWrite -> {
            val rule = new RuleBasedIndex();
            rule.setDimensions(Lists.newArrayList(1, 2, 3, 4, 5, 6));
            rule.setMeasures(Lists.newArrayList(100000, 100001));
            val aggGroup = new NAggregationGroup();
            aggGroup.setIncludes(new Integer[] { 1, 2, 3, 4, 5, 6 });
            aggGroup.setMeasures(new Integer[] { 100000, 100001 });
            val selectRule = new SelectRule();
            selectRule.mandatoryDims = new Integer[0];
            selectRule.hierarchyDims = new Integer[0][0];
            selectRule.jointDims = new Integer[0][0];
            aggGroup.setSelectRule(selectRule);
            rule.setAggregationGroups(Lists.newArrayList(aggGroup));
            copyForWrite.setRuleBasedIndex(rule);
        });
        semanticService.handleSemanticUpdate(getProject(), BASIC_MODEL, originModel, null, null);

        executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(0, executables.size());

        val cube = indePlanManager.getIndexPlan(BASIC_MODEL);
        for (LayoutEntity layout : cube.getWhitelistLayouts()) {
            Assert.assertFalse(layout.getColOrder().contains(100011));
            Assert.assertFalse(layout.getIndex().getMeasures().contains(100011));
        }
    }

    @Test
    public void testOnlyChangeMeasuresWithRule() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        val indePlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val originModel = getTestInnerModel();
        modelMgr.updateDataModel(originModel.getUuid(),
                model -> model.setAllMeasures(model.getAllMeasures().stream().peek(m -> {
                    if (m.getId() == 100017) {
                        m.setId(100018);
                    }
                }).collect(Collectors.toList())));

        semanticService.handleSemanticUpdate(getProject(), originModel.getUuid(), originModel, null, null);

        val cube = indePlanManager.getIndexPlan(INNER_MODEL);
        for (LayoutEntity layout : cube.getWhitelistLayouts()) {
            Assert.assertFalse(layout.getColOrder().contains(100017));
            Assert.assertFalse(layout.getIndex().getMeasures().contains(100017));
        }
        val newRule = cube.getRuleBasedIndex();
        Assert.assertFalse(newRule.getMeasures().contains(100017));
    }

    @Test
    public void testAllChanged() throws Exception {
        UnitOfWork.doInTransactionWithRetry(() -> {
            val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
            val originModel = getTestInnerModel();
            modelMgr.updateDataModel(originModel.getUuid(),
                    model -> model.setAllMeasures(model.getAllMeasures().stream().peek(m -> {
                        if (m.getId() == 100011) {
                            m.setId(100017);
                        }
                    }).collect(Collectors.toList())));
            modelMgr.updateDataModel(originModel.getUuid(), model -> {
                val joins = model.getJoinTables();
                joins.get(0).getJoin().setType("left");
            });
            modelMgr.updateDataModel(originModel.getUuid(),
                    model -> model.setAllNamedColumns(model.getAllNamedColumns().stream().peek(c -> {
                        if (!c.isExist()) {
                            return;
                        }
                        c.setStatus(NDataModel.ColumnStatus.DIMENSION);
                        if (c.getId() == 26) {
                            c.setStatus(NDataModel.ColumnStatus.EXIST);
                        }
                    }).collect(Collectors.toList())));
            semanticService.handleSemanticUpdate(getProject(), originModel.getUuid(), originModel, null, null);
            return true;
        }, getProject());

        val executables = getRunningExecutables(getProject(), null);
        Assert.assertEquals(1, executables.size());
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);

        val indePlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val cube = indePlanManager.getIndexPlan(INNER_MODEL);
        for (LayoutEntity layout : cube.getWhitelistLayouts()) {
            Assert.assertFalse(layout.getColOrder().contains(100011));
            Assert.assertFalse(layout.getIndex().getMeasures().contains(100011));
        }
    }

    @Test
    public void testOnlyRuleChanged() {
        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val dfMgr = NDataflowManager.getInstance(getTestConfig(), getProject());
        val df = dfMgr.getDataflow(INNER_MODEL);
        val originSegLayoutSize = df.getSegments().get(0).getLayoutsMap().size();
        NDataflowUpdate update = new NDataflowUpdate(df.getUuid());
        val cube = df.getIndexPlan();
        val nc1 = NDataLayout.newDataLayout(df, df.getSegments().get(0).getId(),
                cube.getRuleBaseLayouts().get(0).getId());
        val nc2 = NDataLayout.newDataLayout(df, df.getSegments().get(0).getId(),
                cube.getRuleBaseLayouts().get(1).getId());
        val nc3 = NDataLayout.newDataLayout(df, df.getSegments().get(0).getId(),
                cube.getRuleBaseLayouts().get(2).getId());
        update.setToAddOrUpdateLayouts(nc1, nc2, nc3);
        dfMgr.updateDataflow(update);

        val newCube = indexPlanManager.updateIndexPlan(cube.getUuid(), copyForWrite -> {
            val newRule = new RuleBasedIndex();
            newRule.setDimensions(Lists.newArrayList(1, 2, 3, 4, 5, 6));
            newRule.setMeasures(Lists.newArrayList(100001, 100002));
            copyForWrite.setRuleBasedIndex(newRule);
        });
        semanticService.handleIndexPlanUpdateRule(getProject(), df.getModel().getUuid(), cube.getRuleBasedIndex(),
                newCube.getRuleBasedIndex(), false);

        val executables = getRunningExecutables(getProject(), INNER_MODEL);
        Assert.assertEquals(1, executables.size());
        Assert.assertTrue(((NSparkCubingJob) executables.get(0)).getHandler() instanceof ExecutableAddCuboidHandler);

        val df2 = NDataflowManager.getInstance(getTestConfig(), getProject()).getDataflow(df.getUuid());
        Assert.assertEquals(originSegLayoutSize, df2.getFirstSegment().getLayoutsMap().size());
    }

    @Test
    public void testOnlyRemoveColumns_removeToBeDeletedIndex() {
        val modelManager = NDataModelManager.getInstance(getTestConfig(), getProject());
        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());

        val indexPlan = indexPlanManager.getIndexPlan(INNER_MODEL);
        val originModel = getTestInnerModel();

        NIndexPlanManager.getInstance(getTestConfig(), getProject()).updateIndexPlan(indexPlan.getUuid(),
                copyForWrite -> {
                    val toBeDeletedSet = copyForWrite.getIndexes().stream().map(IndexEntity::getLayouts)
                            .flatMap(List::stream).filter(layoutEntity -> 20000020001L == layoutEntity.getId())
                            .collect(Collectors.toSet());
                    copyForWrite.markIndexesToBeDeleted(copyForWrite.getUuid(), toBeDeletedSet);
                    copyForWrite.removeLayouts(Sets.newHashSet(20000020001L), true, true);
                });

        modelManager.updateDataModel(originModel.getUuid(), model -> model.setAllNamedColumns(
                model.getAllNamedColumns().stream().filter(m -> m.getId() != 25).collect(Collectors.toList())));

        NDataflowManager dataflowManager = NDataflowManager.getInstance(getTestConfig(), getProject());
        NDataflow dataflow = dataflowManager.getDataflow(INNER_MODEL);
        NIndexPlanManager.getInstance(getTestConfig(), getProject()).updateIndexPlan(dataflow.getUuid(),
                copyForWrite -> {
                    val toBeDeletedSet = copyForWrite.getIndexes().stream().map(IndexEntity::getLayouts)
                            .flatMap(List::stream).filter(layoutEntity -> 1000001L == layoutEntity.getId())
                            .collect(Collectors.toSet());
                    copyForWrite.markIndexesToBeDeleted(dataflow.getUuid(), toBeDeletedSet);
                });
        Assert.assertTrue(
                CollectionUtils.isNotEmpty(indexPlanManager.getIndexPlan(INNER_MODEL).getToBeDeletedIndexes()));
        indexPlanManager.updateIndexPlan(indexPlan.getUuid(), k -> {
            val newDim = k.getRuleBasedIndex().getDimensions().stream().filter(x -> x != 25)
                    .collect(Collectors.toList());
            k.getRuleBasedIndex().setDimensions(newDim);
            List<NAggregationGroup> aggs = new ArrayList<>();
            for (val agg : k.getRuleBasedIndex().getAggregationGroups()) {
                val newMeasure = Arrays.stream(agg.getIncludes()).filter(x -> x != 25).toArray(Integer[]::new);
                agg.setMeasures(newMeasure);
            }
            k.getRuleBasedIndex().setAggregationGroups(aggs);
        });
        semanticService.handleSemanticUpdate(getProject(), indexPlan.getUuid(), originModel, null, null);
        Assert.assertTrue(CollectionUtils.isEmpty(indexPlanManager.getIndexPlan(INNER_MODEL).getToBeDeletedIndexes()));
    }

    @Test
    public void testOnlyRemoveMeasures() {
        val modelManager = NDataModelManager.getInstance(getTestConfig(), getProject());
        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());

        val indexPlan = indexPlanManager.getIndexPlan(INNER_MODEL);
        val originModel = getTestInnerModel();

        indexPlanManager.updateIndexPlan(indexPlan.getId(), k -> {
            List<NAggregationGroup> aggs = new ArrayList<>();
            for (val agg : indexPlan.getRuleBasedIndex().getAggregationGroups()) {
                val newMeasure = Arrays.stream(agg.getMeasures()).filter(x -> x != 100001 && x != 100002 && x != 100011)
                        .toArray(Integer[]::new);
                agg.setMeasures(newMeasure);
                aggs.add(agg);
            }
            k.getRuleBasedIndex().setAggregationGroups(aggs);
        });

        modelManager.updateDataModel(originModel.getUuid(),
                model -> model.setAllMeasures(model.getAllMeasures().stream()
                        .filter(m -> m.getId() != 100002 && m.getId() != 100001 && m.getId() != 100011)
                        .collect(Collectors.toList())));
        semanticService.handleSemanticUpdate(getProject(), indexPlan.getUuid(), originModel, null, null);

        val executables = getRunningExecutables(getProject(), INNER_MODEL);
        Assert.assertEquals(1, executables.size());

        val newCube = indexPlanManager.getIndexPlan(indexPlan.getUuid());
        Assert.assertNotEquals(indexPlan.getRuleBasedIndex().getLayoutIdMapping().toString(),
                newCube.getRuleBasedIndex().getLayoutIdMapping().toString());
    }

    @Test
    public void testSetBlackListLayout() {
        val indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
        val indexPlan = indexPlanManager.getIndexPlan(INNER_MODEL);
        val dataflowManager = NDataflowManager.getInstance(getTestConfig(), getProject());
        val dataflow = dataflowManager.getDataflow(INNER_MODEL);

        val dfUpdate = new NDataflowUpdate(dataflow.getUuid());
        List<NDataLayout> layouts = Lists.newArrayList();
        for (int i = 0; i < 3; i++) {
            val layout1 = new NDataLayout();
            layout1.setLayoutId(indexPlan.getRuleBaseLayouts().get(i).getId());
            layout1.setRows(100);
            layout1.setByteSize(100);
            layout1.setSegDetails(dataflow.getSegments().getLatestReadySegment().getSegDetails());
            layouts.add(layout1);
        }
        dfUpdate.setToAddOrUpdateLayouts(layouts.toArray(new NDataLayout[0]));
        dataflowManager.updateDataflow(dfUpdate);

        val blacklist2 = Lists.newArrayList(indexPlan.getRuleBaseLayouts().get(1).getId());
        var updatedPlan = semanticService.addRuleBasedIndexBlackListLayouts(indexPlan, blacklist2);
        Assert.assertEquals(updatedPlan.getAllLayouts().size() + 1, indexPlan.getAllLayouts().size());
        val df2 = dataflowManager.getDataflow(dataflow.getId());
        for (Long bId : blacklist2) {
            Assert.assertFalse(df2.getLastSegment().getLayoutsMap().containsKey(bId));
        }

        val blacklist3 = Lists.newArrayList(indexPlan.getRuleBaseLayouts().get(2).getId());
        updatedPlan = semanticService.addRuleBasedIndexBlackListLayouts(indexPlan, blacklist3);
        Assert.assertEquals(updatedPlan.getAllLayouts().size() + 2, indexPlan.getAllLayouts().size());
        val df3 = dataflowManager.getDataflow(dataflow.getId());
        for (Long bId : blacklist3) {
            Assert.assertFalse(df3.getLastSegment().getLayoutsMap().containsKey(bId));
        }

        // add layout to blacklist which is auto and manual, will not remove datalayout from segment
        val blacklist4 = Lists.newArrayList(indexPlan.getRuleBaseLayouts().get(0).getId());
        updatedPlan = semanticService.addRuleBasedIndexBlackListLayouts(indexPlan, blacklist4);
        Assert.assertEquals(updatedPlan.getAllLayouts().size() + 2, indexPlan.getAllLayouts().size());
        val df4 = dataflowManager.getDataflow(dataflow.getId());
        for (Long bId : blacklist4) {
            Assert.assertTrue(df4.getLastSegment().getLayoutsMap().containsKey(bId));
        }
    }

    private NDataModel getTestInnerModel() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        return modelMgr.getDataModelDesc(INNER_MODEL);
    }

    private NDataModel getTestBasicModel() {
        val modelMgr = NDataModelManager.getInstance(getTestConfig(), getProject());
        return modelMgr.getDataModelDesc(BASIC_MODEL);
    }

    private ModelRequest changeAlias(ModelRequest request, String old, String newAlias) throws IOException {
        val newRequest = JsonUtil.deepCopy(request, ModelRequest.class);
        Function<String, String> replaceTableName = col -> {
            if (col.startsWith(old)) {
                return col.replace(old, newAlias);
            } else {
                return col;
            }
        };
        newRequest.getJoinTables().forEach(join -> {
            if (join.getAlias().equals(old)) {
                join.setAlias(newAlias);
            }
            join.getJoin().setForeignKey(
                    Stream.of(join.getJoin().getForeignKey()).map(replaceTableName).toArray(String[]::new));
            join.getJoin().setPrimaryKey(
                    Stream.of(join.getJoin().getPrimaryKey()).map(replaceTableName).toArray(String[]::new));
        });
        newRequest.setSimplifiedDimensions(request.getAllNamedColumns().stream().filter(NamedColumn::isDimension)
                .peek(nc -> nc.setAliasDotColumn(replaceTableName.apply(nc.getAliasDotColumn())))
                .collect(Collectors.toList()));
        newRequest.setSimplifiedJoinTableDescs(
                SCD2SimplificationConvertUtil.simplifiedJoinTablesConvert(newRequest.getJoinTables()));
        return newRequest;
    }

    private ModelRequest newSemanticRequest() throws Exception {
        return newSemanticRequest(BASIC_MODEL);
    }

    private ModelRequest newSemanticRequest(String modelId) throws Exception {
        return newSemanticRequest(modelId, getProject());
    }

    private ModelRequest newSemanticRequest(String modelId, String project) throws Exception {
        val modelMgr = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), project);
        val model = modelMgr.getDataModelDesc(modelId);
        val request = JsonUtil.readValue(JsonUtil.writeValueAsString(model), ModelRequest.class);
        request.setComputedColumnDescs(model.getComputedColumnDescs());
        request.setProject(project);
        request.setUuid(modelId);
        request.setSimplifiedDimensions(model.getAllNamedColumns().stream().filter(NDataModel.NamedColumn::isDimension)
                .collect(Collectors.toList()));
        request.setSimplifiedMeasures(model.getAllMeasures().stream().filter(m -> !m.isTomb())
                .map(SimplifiedMeasure::fromMeasure).collect(Collectors.toList()));
        request.setSimplifiedJoinTableDescs(
                SCD2SimplificationConvertUtil.simplifiedJoinTablesConvert(model.getJoinTables()));
        List<NamedColumn> otherColumns = model.getAllNamedColumns().stream().filter(column -> !column.isDimension())
                .collect(Collectors.toList());
        request.setOtherColumns(otherColumns);

        return JsonUtil.readValue(JsonUtil.writeValueAsString(request), ModelRequest.class);
    }

    private NDataModel getTestModel() {
        val modelMgr = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        return modelMgr.getDataModelDesc(BASIC_MODEL);
    }

    @Test
    public void testUpdateModelColumnForTableAliasModify()
            throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        NDataModel testModel = getTestModel();
        Map<String, String> map = Maps.newHashMap();
        map.put("TEST_ORDER", "TEST_ORDER1");
        testModel.setFilterCondition("`TEST_ORDER`.`ORDER_ID` > 1");
        ModelSemanticHelper semanticHelper = new ModelSemanticHelper();
        Class<? extends ModelSemanticHelper> clazz = semanticHelper.getClass();
        Method method = clazz.getDeclaredMethod("updateModelColumnForTableAliasModify", NDataModel.class, Map.class);
        Unsafe.changeAccessibleObject(method, true);
        method.invoke(semanticHelper, testModel, map);
        Assert.assertEquals("`TEST_ORDER1`.`ORDER_ID` > 1", testModel.getFilterCondition());
        Unsafe.changeAccessibleObject(method, false);
    }

    @Test
    public void testChangeTableAlias() throws NoSuchMethodException, InvocationTargetException, IllegalAccessException {
        ComputedColumnDesc cc = new ComputedColumnDesc();
        cc.setExpression("\"TEST_ORDER\".\"ORDER_ID\" + 1");
        ModelSemanticHelper semanticHelper = new ModelSemanticHelper();
        Class<? extends ModelSemanticHelper> clazz = semanticHelper.getClass();
        Method method = clazz.getDeclaredMethod("changeTableAlias", ComputedColumnDesc.class, String.class,
                String.class);
        Unsafe.changeAccessibleObject(method, true);
        method.invoke(semanticHelper, cc, "TEST_ORDER", "TEST_ORDER1");
        Assert.assertEquals("\"TEST_ORDER1\".\"ORDER_ID\" + 1", cc.getExpression());
        Unsafe.changeAccessibleObject(method, false);
    }

    @Test
    public void testIsFilterConditionNotChange() {
        Assert.assertTrue(semanticService.isFilterConditionNotChange(null, null));
        Assert.assertTrue(semanticService.isFilterConditionNotChange("", null));
        Assert.assertTrue(semanticService.isFilterConditionNotChange(null, "    "));
        Assert.assertTrue(semanticService.isFilterConditionNotChange("  ", ""));
        Assert.assertTrue(semanticService.isFilterConditionNotChange("", "         "));
        Assert.assertTrue(semanticService.isFilterConditionNotChange("A=8", " A=8   "));

        Assert.assertFalse(semanticService.isFilterConditionNotChange(null, "null"));
        Assert.assertFalse(semanticService.isFilterConditionNotChange("", "null"));
        Assert.assertFalse(semanticService.isFilterConditionNotChange("A=8", "A=9"));
    }

    @Test
    public void testUpdateDataModelParatitionDesc() {
        val modelMgr = NDataModelManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        var model = modelMgr.getDataModelDesc(BASIC_MODEL);
        Assert.assertNotNull(model.getPartitionDesc());
        ModelParatitionDescRequest modelParatitionDescRequest = new ModelParatitionDescRequest();
        modelParatitionDescRequest.setStart("0");
        modelParatitionDescRequest.setEnd("1111");
        modelParatitionDescRequest.setPartitionDesc(null);
        PartitionDesc partitionDesc = model.getPartitionDesc();

        var executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(0, executables.size());

        modelService.updateModelPartitionColumn(getProject(), model.getAlias(), modelParatitionDescRequest);
        model = modelMgr.getDataModelDesc(BASIC_MODEL);
        Assert.assertNull(model.getPartitionDesc());
        executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(1, executables.size());
        modelParatitionDescRequest.setPartitionDesc(partitionDesc);

        deleteJobByForce(executables.get(0));
        modelService.updateModelPartitionColumn(getProject(), model.getAlias(), modelParatitionDescRequest);
        model = modelMgr.getDataModelDesc(BASIC_MODEL);
        Assert.assertEquals(partitionDesc, model.getPartitionDesc());
        executables = getRunningExecutables(getProject(), BASIC_MODEL);
        Assert.assertEquals(1, executables.size());
    }

    @Test
    public void testModelSemanticUpdateNoBlackListLayoutRestore() throws Exception {
        String modelId = BASIC_MODEL;

        val newRule = new RuleBasedIndex();
        newRule.setDimensions(Arrays.asList(14, 15, 16));
        val group1 = JsonUtil.readValue("{\n" //
                + "        \"includes\": [14,15,16],\n" //
                + "        \"select_rule\": {\n" //
                + "          \"hierarchy_dims\": [],\n" //
                + "          \"mandatory_dims\": [],\n" //
                + "          \"joint_dims\": []\n" //
                + "        }\n" //
                + "}", NAggregationGroup.class);
        newRule.setAggregationGroups(Lists.newArrayList(group1));
        group1.setMeasures(new Integer[] { 100000, 100008 });
        UnitOfWork.doInTransactionWithRetry(() -> {
            val indexManager = NIndexPlanManager.getInstance(getTestConfig(), getProject());
            var originIndexPlan = indexManager.getIndexPlanByModelAlias("nmodel_basic");

            return indexManager.updateIndexPlan(originIndexPlan.getId(), copyForWrite -> {
                copyForWrite.setRuleBasedIndex(newRule);
            });
        }, getProject());

        val indexPlanManager = NIndexPlanManager.getInstance(KylinConfig.getInstanceFromEnv(), getProject());
        // create measure
        val ruleBasedIndex = indexPlanManager.getIndexPlan(modelId).getRuleBasedIndex();
        val layouts = ruleBasedIndex.genCuboidLayouts();
        UnitOfWork.doInTransactionWithRetry(() -> {
            indexPlanService.removeIndexes(getProject(), modelId,
                    layouts.stream().map(LayoutEntity::getId).collect(Collectors.toSet()));
            return true;
        }, getProject());

        var request = newSemanticRequest(modelId);
        val newMeasure1 = new SimplifiedMeasure();
        newMeasure1.setName("NEST5_SUM");
        newMeasure1.setExpression("SUM");
        val param = new ParameterResponse();
        param.setType("column");
        param.setValue("TEST_KYLIN_FACT.NEST5");
        newMeasure1.setParameterValue(Lists.newArrayList(param));
        request.getSimplifiedMeasures().add(newMeasure1);
        newMeasure1.setReturnType("decimal(38, 0)");
        modelService.updateDataModelSemantic(getProject(), request);
        Assert.assertEquals(7, indexPlanManager.getIndexPlan(modelId).getRuleBasedIndex().getLayoutBlackList().size());
        Assert.assertTrue(indexPlanManager.getIndexPlan(modelId).getRuleBasedIndex().genCuboidLayouts().isEmpty());
    }

    protected List<AbstractExecutable> getRunningExecutables(String project, String model) {
        return ExecutableManager.getInstance(getTestConfig(), project).getRunningExecutables(project, model);
    }

    protected void deleteJobByForce(AbstractExecutable executable) {
        val exManager = ExecutableManager.getInstance(KylinConfig.getInstanceFromEnv(), "default");
        exManager.updateJobOutput(executable.getId(), ExecutableState.DISCARDED);
        exManager.deleteJob(executable.getId());
    }
}
