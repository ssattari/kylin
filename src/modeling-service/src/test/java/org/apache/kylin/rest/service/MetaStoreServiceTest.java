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

import static org.apache.kylin.common.constant.Constants.KE_VERSION;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_ID_NOT_EXIST;
import static org.apache.kylin.common.persistence.metadata.FileSystemMetadataStore.JSON_SUFFIX;
import static org.apache.kylin.metadata.model.schema.ImportModelContext.MODEL_REC_PATH;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.persistence.MetadataType;
import org.apache.kylin.common.persistence.RawResource;
import org.apache.kylin.common.persistence.ResourceStore;
import org.apache.kylin.common.persistence.StringEntity;
import org.apache.kylin.common.persistence.metadata.MetadataStore;
import org.apache.kylin.common.persistence.metadata.jdbc.JdbcUtil;
import org.apache.kylin.common.persistence.transaction.UnitOfWork;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.common.util.MetadataChecker;
import org.apache.kylin.common.util.Pair;
import org.apache.kylin.common.util.RandomUtil;
import org.apache.kylin.guava30.shaded.common.base.Objects;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.guava30.shaded.common.io.ByteSource;
import org.apache.kylin.metadata.cube.model.IndexEntity;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.metadata.cube.model.LayoutEntity;
import org.apache.kylin.metadata.cube.model.NDataSegDetails;
import org.apache.kylin.metadata.cube.model.NDataSegment;
import org.apache.kylin.metadata.cube.model.NDataflow;
import org.apache.kylin.metadata.cube.model.NDataflowManager;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.cube.model.RuleBasedIndex;
import org.apache.kylin.metadata.model.MultiPartitionDesc;
import org.apache.kylin.metadata.model.MultiPartitionKeyMappingImpl;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.model.NTableMetadataManager;
import org.apache.kylin.metadata.model.PartitionDesc;
import org.apache.kylin.metadata.model.Segments;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TblColRef;
import org.apache.kylin.metadata.model.schema.SchemaChangeCheckResult;
import org.apache.kylin.metadata.model.schema.SchemaNodeType;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.metadata.recommendation.candidate.JdbcRawRecStore;
import org.apache.kylin.metadata.recommendation.candidate.RawRecItem;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.request.ModelConfigRequest;
import org.apache.kylin.rest.request.ModelImportRequest;
import org.apache.kylin.rest.response.LoadTableResponse;
import org.apache.kylin.rest.response.ModelPreviewResponse;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;

import lombok.val;
import lombok.var;

public class MetaStoreServiceTest extends ServiceTestBase {
    @Autowired
    private MetaStoreService metaStoreService;

    @Autowired
    private ModelService modelService;

    @Mock
    private ModelChangeSupporter modelChangeSupporter = Mockito.spy(ModelChangeSupporter.class);

    @Rule
    public ExpectedException thrown = ExpectedException.none();

    JdbcTemplate jdbcTemplate = null;
    JdbcRawRecStore jdbcRawRecStore = null;
    private final Authentication authentication = new TestingAuthenticationToken("ADMIN", "ADMIN", Constant.ROLE_ADMIN);

    private static final String PROJECT_DEFAULT = "default";

    @Before
    public void setUp() {
        createTestMetadata("src/test/resources/ut_meta/metastore_model");
        overwriteSystemProp("HADOOP_USER_NAME", "root");
        ReflectionTestUtils.setField(metaStoreService, "modelChangeSupporters",
                Collections.singletonList(modelChangeSupporter));
        try {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            jdbcTemplate = JdbcUtil.getJdbcTemplate(getTestConfig());
        } catch (Exception e) {
            e.printStackTrace();
        }
        jdbcTemplate.batchUpdate("SHUTDOWN;");
        try {
            jdbcRawRecStore = new JdbcRawRecStore(getTestConfig());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @After
    public void tearDown() {
        getTestConfig().setProperty("kylin.metadata.semi-automatic-mode", "false");
        super.tearDown();
    }

    @Test
    public void testGetSimplifiedModel() {
        List<ModelPreviewResponse> modelPreviewResponseList = metaStoreService.getPreviewModels("default",
                Collections.emptyList());
        Assert.assertEquals(11, modelPreviewResponseList.size());

        modelPreviewResponseList = metaStoreService.getPreviewModels("default",
                Lists.newArrayList("8b5a2d39-304f-4a20-a9da-942f461534d8", "7212bf0c-0716-4cef-b623-69c161981262"));
        Assert.assertEquals(2, modelPreviewResponseList.size());

        modelPreviewResponseList = metaStoreService.getPreviewModels("original_project", Collections.emptyList());
        Assert.assertEquals(13, modelPreviewResponseList.size());

        Assert.assertTrue(
                modelPreviewResponseList.stream().anyMatch(ModelPreviewResponse::isHasMultiplePartitionValues));

        val id = "7212bf0c-0716-4cef-b623-69c161981262";
        val idxPlanMgr = modelService.getManager(NIndexPlanManager.class, "default");

        idxPlanMgr.updateIndexPlan(id, updater -> {
            val overrideProps = new LinkedHashMap<String, String>();
            overrideProps.put("kylin.index.rule-scheduler-data", "");
            updater.setOverrideProps(overrideProps);
        });
        modelPreviewResponseList = metaStoreService.getPreviewModels("default", Lists.newArrayList(id));
        Assert.assertTrue(
                idxPlanMgr.getIndexPlan(id).getOverrideProps().containsKey("kylin.index.rule-scheduler-data"));
        Assert.assertFalse(modelPreviewResponseList.get(0).isHasOverrideProps());
    }

    @Test
    public void testGetCompressedModelMetadata() throws Exception {
        List<NDataflow> dataflowList = modelService.getManager(NDataflowManager.class, PROJECT_DEFAULT)
                .listAllDataflows();
        List<NDataModel> dataModelList = dataflowList.stream().filter(df -> !df.checkBrokenWithRelatedInfo())
                .map(NDataflow::getModel).collect(Collectors.toList());
        List<String> modelIdList = dataModelList.stream().map(NDataModel::getId).collect(Collectors.toList());
        val modelConfigRequest = new ModelConfigRequest();
        String modelId = modelIdList.get(0);
        String affectProp = "kylin.cube.aggrgroup.is-base-cuboid-always-valid";
        String ruleSchedDataProp = "kylin.index.rule-scheduler-data";
        modelConfigRequest.setOverrideProps(new LinkedHashMap<String, String>() {
            {
                put(affectProp, "false");
                put(ruleSchedDataProp, "");
            }
        });
        modelService.updateModelConfig(PROJECT_DEFAULT, modelId, modelConfigRequest);

        ByteArrayOutputStream byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT,
                modelIdList, false, false, false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        Map<String, RawResource> rawResourceMap = getRawResourceFromZipFile(
                new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
        Assert.assertEquals(62, rawResourceMap.size());

        // export over props
        byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT, modelIdList, false, true,
                false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        rawResourceMap = getRawResourceFromZipFile(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());

        ResourceStore resourceStore = ResourceStore.getKylinMetaStore(kylinConfig);
        rawResourceMap.values().forEach(rs -> {
            long mvcc = -1;
            RawResource originalResource = resourceStore.getResource(rs.getMetaKey());
            if (originalResource != null) {
                mvcc = originalResource.getMvcc();
            }
            resourceStore.checkAndPutResource(rs.getMetaKey(), rs.getByteSource(), mvcc);
        });

        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(kylinConfig, PROJECT_DEFAULT);
        Assert.assertTrue(indexPlanManager.listAllIndexPlans().stream()
                .anyMatch(indexPlan -> !indexPlan.getOverrideProps().isEmpty()));
        Assert.assertEquals("false", indexPlanManager.getIndexPlan(modelId).getOverrideProps().get(affectProp));
        Assert.assertEquals("", indexPlanManager.getIndexPlan(modelId).getOverrideProps().get(ruleSchedDataProp));

    }

    @Test
    public void testGetCompressedModelMetadataWithIndentJson() throws Exception {
        ByteArrayOutputStream byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT,
                Collections.singletonList("1af229fb-bb2c-42c5-9663-2bd92b50a861"), true, false, false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        Map<String, RawResource> rawResourceMap = getRawResourceFromZipFile(
                new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));

        Assert.assertFalse(rawResourceMap.isEmpty());

        Assert.assertTrue(rawResourceMap.keySet().stream().anyMatch(path -> path.contains(MetadataType.MODEL.name())));
        Assert.assertTrue(
                rawResourceMap.keySet().stream().anyMatch(path -> path.contains(MetadataType.INDEX_PLAN.name())));
        Assert.assertTrue(
                rawResourceMap.keySet().stream().anyMatch(path -> path.contains(MetadataType.TABLE_INFO.name())));

        for (Map.Entry<String, RawResource> entry : rawResourceMap.entrySet()) {
            RawResource rawResource = entry.getValue();
            String jsonString = IOUtils.toString(rawResource.getByteSource().openStream(), StandardCharsets.UTF_8)
                    .trim();
            Assert.assertEquals(JsonUtil.readValueAsTree(jsonString).toPrettyString(), jsonString);
        }
    }

    @Test
    public void testGetCompressedModelMetadataWithRec() throws Exception {
        getTestConfig().setProperty("kylin.metadata.semi-automatic-mode", "true");
        List<RawRecItem> rawRecItems = new ArrayList<>();
        val rawRecItemsNode = JsonUtil.readValue(
                new File("src/test/resources/ut_meta/metastore_model/rec/1af229fb-bb2c-42c5-9663-2bd92b50a861.json"),
                JsonNode.class);
        Assert.assertNotNull(rawRecItemsNode);
        for (JsonNode jsonNode : rawRecItemsNode) {
            RawRecItem rawRecItem = JsonUtil.readValue(jsonNode.toString(), RawRecItem.class);
            rawRecItem.setRecEntity(
                    RawRecItem.toRecItem(jsonNode.get("recEntity").toString(), (byte) rawRecItem.getType().id()));
            rawRecItems.add(rawRecItem);
        }

        Assert.assertEquals(4, rawRecItems.size());
        jdbcRawRecStore.saveWithoutCheck(rawRecItems, true);

        List<RawRecItem> rawRecItems1 = jdbcRawRecStore.queryAll();
        Assert.assertEquals(4, rawRecItems1.size());
        // export recommendations
        val byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT,
                Lists.newArrayList("1af229fb-bb2c-42c5-9663-2bd92b50a861", "7212bf0c-0716-4cef-b623-69c161981262"),
                true, false, false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        val rawResourceMap = getRawResourceFromZipFile(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));

        String recPath = String.format(Locale.ROOT, MODEL_REC_PATH, PROJECT_DEFAULT,
                "1af229fb-bb2c-42c5-9663-2bd92b50a861");
        RawResource rawResource = rawResourceMap.get(recPath);
        Assert.assertNotNull(rawResource);

        val JsonNode = JsonUtil.readValue(rawResource.getByteSource().openStream(), JsonNode.class);
        val arrayNode = (ArrayNode) JsonNode.get("rec_items");
        Assert.assertEquals(4, arrayNode.size());

        recPath = String.format(Locale.ROOT, MODEL_REC_PATH, PROJECT_DEFAULT, "7212bf0c-0716-4cef-b623-69c161981262");
        rawResource = rawResourceMap.get(recPath);
        Assert.assertNull(rawResource);
    }

    @Test
    public void testGetCompressedModelMetadataWithMultiplePartition() throws Exception {
        // export multiple partition
        var byteArrayOutputStream = metaStoreService.getCompressedModelMetadata("original_project",
                Collections.singletonList("ff810fb9-55c4-4c45-9f8e-4235122a63d3"), false, false, true);
        var rawResourceMap = getRawResourceFromZipFile(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());

        ResourceStore resourceStore = ResourceStore.getKylinMetaStore(kylinConfig);
        rawResourceMap.values().forEach(rs -> {
            long mvcc = -1;
            RawResource originalResource = resourceStore.getResource(rs.getMetaKey());
            if (originalResource != null) {
                mvcc = originalResource.getMvcc();
            }
            resourceStore.checkAndPutResource(rs.getMetaKey(), rs.getByteSource(), mvcc);
        });

        var dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        var modelDesc = dataModelManager.getDataModelDesc("ff810fb9-55c4-4c45-9f8e-4235122a63d3");
        Assert.assertNotNull(modelDesc);
        Assert.assertTrue(modelDesc.isMultiPartitionModel());
        Assert.assertEquals(3, modelDesc.getMultiPartitionDesc().getPartitions().size());

        // don't export multiple partition
        byteArrayOutputStream = metaStoreService.getCompressedModelMetadata("original_project",
                Collections.singletonList("ff810fb9-55c4-4c45-9f8e-4235122a63d3"), false, false, false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        rawResourceMap = getRawResourceFromZipFile(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));

        kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());

        val resourceStore2 = ResourceStore.getKylinMetaStore(kylinConfig);
        rawResourceMap.values().forEach(rs -> {
            long mvcc = -1;
            RawResource originalResource = resourceStore2.getResource(rs.getMetaKey());
            if (originalResource != null) {
                mvcc = originalResource.getMvcc();
            }
            resourceStore2.checkAndPutResource(rs.getMetaKey(), rs.getByteSource(), mvcc);
        });

        dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        modelDesc = dataModelManager.getDataModelDesc("ff810fb9-55c4-4c45-9f8e-4235122a63d3");
        Assert.assertNotNull(modelDesc);
        Assert.assertTrue(modelDesc.isMultiPartitionModel());
        Assert.assertEquals(0, modelDesc.getMultiPartitionDesc().getPartitions().size());
    }

    @Test
    public void testGetCompressedModelMetadataWithVersionFile() throws Exception {
        List<NDataflow> dataflowList = modelService.getManager(NDataflowManager.class, PROJECT_DEFAULT)
                .listAllDataflows();
        List<NDataModel> dataModelList = dataflowList.stream().filter(df -> !df.checkBrokenWithRelatedInfo())
                .map(NDataflow::getModel).collect(Collectors.toList());
        List<String> modelIdList = dataModelList.stream().map(NDataModel::getId).collect(Collectors.toList());
        ByteArrayOutputStream byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT,
                modelIdList, false, false, false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        Map<String, RawResource> rawResourceMap = getRawResourceFromZipFile(
                new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
        Assert.assertEquals(62, rawResourceMap.size());

        RawResource rw = rawResourceMap.get(ResourceStore.VERSION_FILE);
        try (InputStream inputStream = rw.getByteSource().openStream()) {
            Assert.assertEquals("unknown", JsonUtil.readValue(inputStream, StringEntity.class).getStr());
        }

        overwriteSystemProp(KE_VERSION, "4.3.x");

        byteArrayOutputStream = metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT, modelIdList, false, false,
                false);
        Assert.assertTrue(ArrayUtils.isNotEmpty(byteArrayOutputStream.toByteArray()));
        rawResourceMap = getRawResourceFromZipFile(new ByteArrayInputStream(byteArrayOutputStream.toByteArray()));
        Assert.assertEquals(62, rawResourceMap.size());

        rw = rawResourceMap.get(ResourceStore.VERSION_FILE);
        try (InputStream inputStream = rw.getByteSource().openStream()) {
            Assert.assertEquals("4.3.x", JsonUtil.readValue(inputStream, StringEntity.class).getStr());
        }
    }

    @Test
    public void testExportNotExistsModel() {
        String notExistsUuid = RandomUtil.randomUUIDStr();
        List<String> modelList = Lists.newArrayList(notExistsUuid);
        Assert.assertThrows(MODEL_ID_NOT_EXIST.getMsg(notExistsUuid), KylinException.class,
                () -> metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT, modelList, false, false, false));
    }

    @Test
    public void testExportBrokenModel() {
        // broken model id
        String brokenModelId = "8b5a2d39-304f-4a20-a9da-942f461534d8";
        String msg = String.format(Locale.ROOT,
                "Can’t export model \"%s\"  as it’s in \"BROKEN\" status. Please re-select and try again.",
                brokenModelId);
        List<String> modelList = Lists.newArrayList(brokenModelId);
        Assert.assertThrows(msg, KylinException.class,
                () -> metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT, modelList, false, false, false));

    }

    @Test
    public void testExportEmptyModel() {
        // empty model list
        List<String> emptyList = Lists.newArrayList();
        Assert.assertThrows("Please select at least one model to export.", KylinException.class,
                () -> metaStoreService.getCompressedModelMetadata(PROJECT_DEFAULT, emptyList, false, false, false));
    }

    private Map<String, RawResource> getRawResourceFromZipFile(InputStream inputStream) throws IOException {
        Map<String, RawResource> rawResourceMap = Maps.newHashMap();
        try (ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {
            ZipEntry zipEntry;
            while ((zipEntry = zipInputStream.getNextEntry()) != null) {
                val bs = ByteSource.wrap(IOUtils.toByteArray(zipInputStream));
                long t = zipEntry.getTime();
                String fileName = zipEntry.getName();
                if (!fileName.startsWith(ResourceStore.METASTORE_UUID_TAG)
                        && !fileName.equals(ResourceStore.VERSION_FILE) && !fileName.endsWith(JSON_SUFFIX)) {
                    continue;
                }
                String resPath = fileName.substring(0, fileName.length() - JSON_SUFFIX.length());
                rawResourceMap.put(resPath, new RawResource(resPath, bs, t, 0));
            }
            return rawResourceMap;
        }
    }

    @Test
    public void testCheckModelMetadataModelCCUpdate() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("model_cc_update");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(1, modelSchemaChange.getDifferences());
        Assert.assertEquals(1, modelSchemaChange.getNewItems().size());
        var schemaChange = modelSchemaChange.getNewItems().get(0);
        Assert.assertEquals(SchemaNodeType.MODEL_CC, schemaChange.getType());
        Assert.assertEquals("CC2", schemaChange.getDetail());
        Assert.assertEquals("model_cc_update", schemaChange.getModelAlias());
        Assert.assertEquals("P_LINEORDER.LO_SUPPKEY + 2", schemaChange.getAttributes().get("expression"));
        Assert.assertTrue(schemaChange.isOverwritable());
    }

    @Test
    public void testCheckModelMetadataNoChanges() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("ssb_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(0, modelSchemaChange.getDifferences());
    }

    @Test
    public void testCheckModelMetadataModelAggUpdate() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("model_agg_update");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(4, modelSchemaChange.getDifferences());
        Assert.assertEquals(4, modelSchemaChange.getReduceItems().size());
        Assert.assertEquals("10001,40001,60001,70001", modelSchemaChange.getReduceItems().stream()
                .map(SchemaChangeCheckResult.ChangedItem::getDetail).sorted().collect(Collectors.joining(",")));
        Assert.assertTrue(
                modelSchemaChange.getReduceItems().stream().anyMatch(SchemaChangeCheckResult.BaseItem::isOverwritable));
    }

    @Test
    public void testCheckModelMetadataModelDimConflict() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_dim_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getNewItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_DIM && !sc.isOverwritable()));

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_DIM && !sc.isOverwritable()));

        Assert.assertTrue(modelSchemaChange.getNewItems().stream().anyMatch(
                sc -> sc.getType() == SchemaNodeType.MODEL_JOIN && sc.getDetail().equals("P_LINEORDER-SUPPLIER")));

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream().anyMatch(
                sc -> sc.getType() == SchemaNodeType.MODEL_JOIN && sc.getDetail().equals("P_LINEORDER-CUSTOMER")));
    }

    @Test
    public void testCheckModelMetadataModelJoinConflict() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_join_condition_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(1, modelSchemaChange.getDifferences());
        Assert.assertEquals(1, modelSchemaChange.getUpdateItems().size());
        val schemaUpdate = modelSchemaChange.getUpdateItems().get(0);
        Assert.assertTrue(schemaUpdate.getType() == SchemaNodeType.MODEL_JOIN
                && schemaUpdate.getFirstSchemaNode().getDetail().equals("P_LINEORDER-CUSTOMER")
                && schemaUpdate.getFirstSchemaNode().getAttributes().get("join_type").equals("INNER")
                && schemaUpdate.getFirstSchemaNode().getAttributes().get("primary_keys")
                        .equals(Collections.singletonList("CUSTOMER.C_CUSTKEY"))
                && schemaUpdate.getFirstSchemaNode().getAttributes().get("foreign_keys")
                        .equals(Collections.singletonList("P_LINEORDER.LO_CUSTKEY"))
                && schemaUpdate.getFirstSchemaNode().getAttributes().get("non_equal_join_condition").equals("")
                && schemaUpdate.getSecondSchemaNode().getDetail().equals("P_LINEORDER-CUSTOMER")
                && schemaUpdate.getSecondSchemaNode().getAttributes().get("join_type").equals("LEFT")
                && schemaUpdate.getSecondSchemaNode().getAttributes().get("primary_keys")
                        .equals(Collections.singletonList("CUSTOMER.C_NAME"))
                && schemaUpdate.getSecondSchemaNode().getAttributes().get("foreign_keys")
                        .equals(Collections.singletonList("P_LINEORDER.LO_CUSTKEY"))
                && schemaUpdate.getSecondSchemaNode().getAttributes().get("non_equal_join_condition").equals(
                        "\"P_LINEORDER\".\"LO_CUSTKEY\" = \"CUSTOMER\".\"C_NAME\" AND CAST(\"P_LINEORDER\".\"LO_CUSTKEY\" AS BIGINT) < CAST(\"CUSTOMER\".\"C_CITY\" AS BIGINT) AND \"P_LINEORDER\".\"LO_CUSTKEY\" >= \"CUSTOMER\".\"C_CUSTKEY\"")
                && !schemaUpdate.isOverwritable() && schemaUpdate.isCreatable());
    }

    @Test
    public void testCheckModelMetadataModelFactConflict() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_fact_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(
                modelSchemaChange.getNewItems().stream().anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_FACT
                        && sc.isCreatable() && sc.getDetail().equals("SSB.LINEORDER")));
        Assert.assertTrue(modelSchemaChange.getNewItems().stream().anyMatch(
                sc -> sc.getType() == SchemaNodeType.MODEL_JOIN && sc.getDetail().equals("LINEORDER-CUSTOMER")));

        Assert.assertTrue(
                modelSchemaChange.getReduceItems().stream().anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_FACT
                        && sc.isCreatable() && sc.getDetail().equals("SSB.P_LINEORDER")));

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream().anyMatch(
                sc -> sc.getType() == SchemaNodeType.MODEL_JOIN && sc.getDetail().equals("P_LINEORDER-CUSTOMER")));
    }

    @Test
    public void testCheckModelMetadataModelColumnUpdate() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("model_column_update");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_DIMENSION && sc.isOverwritable()));

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.RULE_BASED_INDEX && sc.isOverwritable()));

        Assert.assertTrue(modelSchemaChange.getNewItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.MODEL_DIMENSION && sc.isOverwritable()));

        Assert.assertTrue(modelSchemaChange.getNewItems().stream()
                .anyMatch(sc -> sc.getType() == SchemaNodeType.RULE_BASED_INDEX && sc.isOverwritable()));
    }

    @Test
    public void testCheckModelMetadataModelFilterConflict() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_filter_condition_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream().anyMatch(
                pair -> pair.getType() == SchemaNodeType.MODEL_FILTER && !pair.isOverwritable() && pair.isCreatable()));
    }

    @Test
    public void testCheckModelMetadataModelPartitionConflict() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream()
                .anyMatch(pair -> pair.getType() == SchemaNodeType.MODEL_PARTITION && !pair.isOverwritable()
                        && pair.isCreatable()));
    }

    @Test
    public void testCheckModelMetadataModelMultiplePartitionColumnsChanged() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/conflict_multiple_partition_project/target_project_model_metadata_2020_12_02_17_27_25_DF9679B582150A5597CB7D4683FE0A0B.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream()
                .anyMatch(pair -> pair.getType() == SchemaNodeType.MODEL_MULTIPLE_PARTITION && !pair.isOverwritable()
                        && pair.isCreatable() && !Objects.equal(pair.getFirstAttributes().get("columns"),
                                pair.getSecondAttributes().get("columns"))));
    }

    @Test
    public void testCheckModelMetadataModelMultiplePartition() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_different_multiple_partition_project/target_project_model_metadata_2020_12_02_20_50_10_5F5B27368F5F4EA718B4297DE5F261E9.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream()
                .anyMatch(pair -> pair.getType() == SchemaNodeType.MODEL_MULTIPLE_PARTITION && pair.isOverwritable()
                        && Objects.equal(pair.getFirstAttributes().get("columns"),
                                pair.getSecondAttributes().get("columns"))
                        && pair.getFirstAttributes().get("partitions")
                                .equals(Arrays.asList(Collections.singletonList("p1"), Collections.singletonList("p2"),
                                        Collections.singletonList("p3")))
                        && pair.getSecondAttributes().get("partitions")
                                .equals(Arrays.asList(Collections.singletonList("p2"), Collections.singletonList("p1"),
                                        Collections.singletonList("p5")))));
    }

    @Test
    public void testCheckModelMetadataModelEmptyMultiplePartitionValues() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_empty_multiple_partition_value/model_empty_multiple_partition_value_2021_01_18_11_10_11_91E72B2EF5A6D8F818FA5F55009A9E16.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().isEmpty());
    }

    @Test
    public void testCheckModelMetadataModelDifferentMultiplePartitionColumnWithEmptyValue() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_different_multiple_column_with_empty_partition_value/model_different_multiple_column_with_empty_partition_value_2021_01_18_11_30_10_C21557AD200F8B6EF17DEE5F5AD35DF9.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream()
                .anyMatch(pair -> pair.getType() == SchemaNodeType.MODEL_MULTIPLE_PARTITION && pair.isCreatable()
                        && !Objects.equal(pair.getFirstAttributes().get("columns"),
                                pair.getSecondAttributes().get("columns"))
                        && pair.getFirstAttributes().get("partitions")
                                .equals(Arrays.asList(Collections.singletonList("p1"), Collections.singletonList("p2"),
                                        Collections.singletonList("p3")))
                        && ((List<?>) pair.getSecondAttributes().get("partitions")).isEmpty()));
    }

    @Test
    public void testCheckModelMetadataModelMultiplePartitionWithDifferentPartitionValueOrder() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_different_multiple_partition_with_different_partition_value_order_project/target_project_model_metadata_2020_12_02_20_50_10_04A9FB74DE74189500A908F56DBCF933.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(0, modelSchemaChange.getDifferences());
    }

    @Test
    public void testCheckModelMetadataModelMultiplePartitionWithPartitionValueReduce() throws IOException {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_different_multiple_partition_with_partition_value_reduce_project/target_project_model_metadata_2020_12_02_20_50_10_3A88090D810EFB0FDAC1FFAEB80D9933.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("conflict_multiple_partition_col_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(1, modelSchemaChange.getDifferences());

        Assert.assertTrue(modelSchemaChange.getUpdateItems().stream()
                .anyMatch(updatedItem -> updatedItem.isOverwritable()
                        && updatedItem.getFirstDetail().equals("P_LINEORDER.LO_CUSTKEY")
                        && updatedItem.getSecondDetail().equals("P_LINEORDER.LO_CUSTKEY")
                        && String.join(",", (List<String>) updatedItem.getFirstAttributes().get("columns"))
                                .equals("P_LINEORDER.LO_CUSTKEY")
                        && String.join(",", (List<String>) updatedItem.getSecondAttributes().get("columns"))
                                .equals("P_LINEORDER.LO_CUSTKEY")
                        && ((List<String>) updatedItem.getFirstAttributes().get("partitions")).size() == 3
                        && ((List<String>) updatedItem.getSecondAttributes().get("partitions")).size() == 2));
    }

    @Test
    public void testCheckModelMetadataModelMissingTable() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val checkResult = metaStoreService.checkModelMetadata("original_project", multipartFile, null);
        Assert.assertEquals(1, checkResult.getExistTableList().size());
        Assert.assertEquals("SSB.CUSTOMER_NEW", checkResult.getExistTableList().get(0).getIdentity());

        SchemaChangeCheckResult.ModelSchemaChange change = checkResult.getModels().get("missing_table_model");
        Assert.assertNotNull(change);
        Assert.assertTrue(change.getMissingItems().isEmpty());
        Assert.assertTrue(change.importable());
        Assert.assertTrue(change.creatable());
    }

    @Test
    public void testCheckModelMetadataModelIndex() throws IOException {
        val file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("model_index");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertEquals(3, modelSchemaChange.getDifferences());

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream()
                .filter(sc -> sc.getType() == SchemaNodeType.WHITE_LIST_INDEX)
                .filter(sc -> sc.getDetail().equals("20000000001"))
                .filter(SchemaChangeCheckResult.BaseItem::isOverwritable).anyMatch(sc -> {
                    String col_orders = String.join(",", ((List<String>) sc.getAttributes().get("col_orders")));
                    return col_orders.equals(
                            "P_LINEORDER.LO_CUSTKEY,P_LINEORDER.LO_SUPPKEY,P_LINEORDER.LO_ORDERDATE,P_LINEORDER.LO_QUANTITY,P_LINEORDER.LO_DISCOUNT,P_LINEORDER.LO_LINENUMBER,P_LINEORDER.LO_PARTKEY,P_LINEORDER.LO_ORDERKEY");
                }));

        Assert.assertTrue(
                modelSchemaChange.getNewItems().stream().filter(sc -> sc.getType() == SchemaNodeType.WHITE_LIST_INDEX)
                        .filter(sc -> sc.getDetail().equals("20000000001"))
                        .filter(SchemaChangeCheckResult.BaseItem::isOverwritable).anyMatch(sc -> {
                            String col_orders = String.join(",", ((List<String>) sc.getAttributes().get("col_orders")));
                            return col_orders.equals(
                                    "P_LINEORDER.LO_LINENUMBER,P_LINEORDER.LO_SUPPKEY,P_LINEORDER.LO_QUANTITY,P_LINEORDER.LO_PARTKEY,P_LINEORDER.LO_ORDERKEY,P_LINEORDER.LO_CUSTKEY,P_LINEORDER.LO_DISCOUNT,P_LINEORDER.LO_ORDERDATE");
                        }));

        Assert.assertTrue(
                modelSchemaChange.getNewItems().stream().filter(sc -> sc.getType() == SchemaNodeType.WHITE_LIST_INDEX)
                        .filter(sc -> sc.getDetail().equals("20000010001"))
                        .filter(SchemaChangeCheckResult.BaseItem::isOverwritable).anyMatch(sc -> {
                            String col_orders = String.join(",", ((List<String>) sc.getAttributes().get("col_orders")));
                            return col_orders.equals("P_LINEORDER.LO_SUPPKEY,P_LINEORDER.LO_QUANTITY");
                        }));
    }

    @Test
    public void testCheckModelMetadataWithoutMD5Checksum() throws Exception {
        File file = new File("src/test/resources/ut_model_metadata/metastore_model_metadata.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        Assert.assertThrows(
                "Can’t parse the metadata file. Please don’t modify the content or zip the file manually after unzip.",
                KylinException.class, () -> metaStoreService.checkModelMetadata("default", multipartFile, null));
    }

    @Test
    public void testCheckModelMetadataWithWrongMD5Checksum() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FB1.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        Assert.assertThrows(
                "Can’t parse the metadata file. Please don’t modify the content or zip the file manually after unzip.",
                KylinException.class, () -> metaStoreService.checkModelMetadata("default", multipartFile, null));
    }

    @Test
    public void testImportModelMetadata() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("model_index", "model_index",
                ModelImportRequest.ImportType.OVERWRITE));
        models.add(new ModelImportRequest.ModelImport("model_column_update", "model_column_update",
                ModelImportRequest.ImportType.OVERWRITE));
        models.add(new ModelImportRequest.ModelImport("model_agg_update", "model_agg_update",
                ModelImportRequest.ImportType.OVERWRITE));
        models.add(new ModelImportRequest.ModelImport("conflict_partition_col_model", "conflict_partition_col_model_2",
                ModelImportRequest.ImportType.NEW));
        models.add(new ModelImportRequest.ModelImport("conflict_filter_condition_model", null,
                ModelImportRequest.ImportType.UN_IMPORT));

        request.setModels(models);
        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        NDataModelManager dataModelManager = NDataModelManager.getInstance(getTestConfig(), "original_project");
        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), "original_project");

        IndexPlan indexPlan = indexPlanManager.getIndexPlanByModelAlias("model_index");
        Assert.assertEquals(2, indexPlan.getWhitelistLayouts().size());
        LayoutEntity layout = indexPlan.getLayoutEntity(20000030001L);
        Assert.assertEquals("1,4,5,6,7,8,10,12",
                layout.getColOrder().stream().map(String::valueOf).collect(Collectors.joining(",")));

        layout = indexPlan.getLayoutEntity(20000040001L);
        Assert.assertEquals("4,5", layout.getColOrder().stream().map(String::valueOf).collect(Collectors.joining(",")));

        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("model_column_update");

        Assert.assertTrue(dataModel.getEffectiveDimensions().values().stream()
                .anyMatch(tblColRef -> tblColRef.getName().equals("LO_REVENUE")));
        Assert.assertTrue(dataModel.getEffectiveDimensions().values().stream()
                .anyMatch(tblColRef -> tblColRef.getName().equals("LO_TAX")));
        Assert.assertTrue(dataModel.getEffectiveDimensions().values().stream()
                .noneMatch(tblColRef -> tblColRef.getName().equals("LO_LINENUMBER")));
        Assert.assertTrue(dataModel.getEffectiveMeasures().values().stream()
                .anyMatch(measure -> measure.getName().equals("LO_REVENUE_SUM")));
        Assert.assertTrue(dataModel.getEffectiveMeasures().values().stream()
                .anyMatch(measure -> measure.getName().equals("LO_TAX_SUM")));
        Assert.assertTrue(dataModel.getEffectiveMeasures().values().stream()
                .noneMatch(measure -> measure.getName().equals("LO_ORDERDATE_COUNT")));

        indexPlan = indexPlanManager.getIndexPlanByModelAlias("model_agg_update");
        Assert.assertTrue(indexPlan.getAllLayouts().stream().noneMatch(layoutEntity -> layoutEntity.getId() == 70001L));
        Assert.assertTrue(indexPlan.getAllLayouts().stream().noneMatch(layoutEntity -> layoutEntity.getId() == 60001L));
        Assert.assertTrue(indexPlan.getAllLayouts().stream().noneMatch(layoutEntity -> layoutEntity.getId() == 40001L));
        Assert.assertTrue(indexPlan.getAllLayouts().stream().noneMatch(layoutEntity -> layoutEntity.getId() == 10001L));

        dataModel = dataModelManager.getDataModelDescByAlias("conflict_partition_col_model_2");
        Assert.assertNotNull(dataModel);
        PartitionDesc partitionDesc = dataModel.getPartitionDesc();
        // changed to yyyyMMdd
        Assert.assertEquals("yyyyMMdd", partitionDesc.getPartitionDateFormat());

        dataModel = dataModelManager.getDataModelDescByAlias("conflict_filter_condition_model");
        // still (P_LINEORDER.LO_CUSTKEY <> 1)
        Assert.assertEquals("(P_LINEORDER.LO_CUSTKEY <> 1)", dataModel.getFilterCondition());
    }

    @Test
    public void testImportModelMetadataWithRecInExpertModeProject() throws Exception {
        String id = "761215ee-3f21-4d1a-aae5-3d0d9d6ede85";
        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), "original_project");
        indexPlanManager.updateIndexPlan(id, copyForWrite -> copyForWrite.setRuleBasedIndex(new RuleBasedIndex()));
        String fileName = "issue_model_metadata_2022_06_17_14_54_54_099BFE894A696F15823C24135D40B183.zip";
        File file = new File("src/test/resources/ut_model_metadata/" + fileName);
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("model_index", "model_index",
                ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);
        NDataModelManager dataModelManager = NDataModelManager.getInstance(getTestConfig(), "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("model_index");
        List<RawRecItem> rawRecItems = jdbcRawRecStore.listAll("original_project", dataModel.getUuid(), 1, 10);
        Assert.assertEquals(0, rawRecItems.size());
        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        NProjectManager projectManager = NProjectManager.getInstance(getTestConfig());
        ProjectInstance projectInstance = projectManager.getProject("original_project");

        Assert.assertTrue(projectInstance.isExpertMode());

        rawRecItems = jdbcRawRecStore.listAll("original_project", dataModel.getUuid(), dataModel.getSemanticVersion(),
                10);
        Assert.assertEquals(0, rawRecItems.size());
        IndexEntity index = indexPlanManager.getIndexPlan(dataModel.getUuid()).getIndexEntity(160000);
        Assert.assertEquals(1, index.getLayouts().size());
    }

    @Test
    public void testImportModelMetadataWithMeasureDependsOnCCRec() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/project_1_model_metadata_2021_01_20_14_56_44_4D0A8DAFD6BDDB01FE2F7C8D55B075AF.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("ssb_model_with_rec", "ssb_model_with_rec",
                ModelImportRequest.ImportType.NEW));

        request.setModels(models);

        UnitOfWork.doInTransactionWithRetry(
                () -> NProjectManager.getInstance(getTestConfig()).updateProject("original_project", copyForWrite -> {
                    copyForWrite.putOverrideKylinProps("kylin.metadata.semi-automatic-mode", String.valueOf(true));
                }), "original_project");

        getTestConfig().clearManagers();
        NProjectManager projectManager = NProjectManager.getInstance(getTestConfig());
        ProjectInstance projectInstance = projectManager.getProject("original_project");

        Assert.assertFalse(projectInstance.isExpertMode());
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
        NDataModelManager dataModelManager = NDataModelManager.getInstance(getTestConfig(), "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("ssb_model_with_rec");

        List<RawRecItem> rawRecItems = jdbcRawRecStore.listAll("original_project", dataModel.getUuid(),
                dataModel.getSemanticVersion(), 10);
        Assert.assertEquals(4, rawRecItems.size());
    }

    @Test
    public void testImportModelMetadataWithMixInIndexWithRec() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/project_2_model_metadata_2021_01_21_15_45_16_F3B35485BC888981895C00635A8DDAF3.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("ssb_model_index_mixin", "ssb_model_index_mixin",
                ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        var dataModelManager = NDataModelManager.getInstance(getTestConfig(), "model_index_mix");
        var dataModel = dataModelManager.getDataModelDescByAlias("ssb_model_index_mixin");
        // 0 cc
        Assert.assertEquals(0, dataModel.getComputedColumnDescs().size());

        // 1 measure
        Assert.assertEquals(1, dataModel.getEffectiveMeasures().size());
        NDataModel.Measure measure = dataModel.getEffectiveMeasures().values().iterator().next();
        Assert.assertEquals("COUNT", measure.getFunction().getExpression());

        // 3 dimension
        Assert.assertEquals(3, dataModel.getEffectiveDimensions().size());
        Assert.assertEquals("P_LINEORDER.LO_CUSTKEY, P_LINEORDER.LO_ORDERDATE, P_LINEORDER.LO_SUPPKEY",
                dataModel.getEffectiveDimensions().values().stream().map(TblColRef::getAliasDotName).sorted()
                        .collect(Collectors.joining(", ")));

        var indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), "model_index_mix");
        var indexPlan = indexPlanManager.getIndexPlanByModelAlias("ssb_model_index_mixin");

        // 9 layout
        Assert.assertEquals(9, indexPlan.getAllLayouts().size());
        Assert.assertEquals(7, indexPlan.getRuleBaseLayouts().size());
        Assert.assertEquals(2, indexPlan.getWhitelistLayouts().size());

        // import
        metaStoreService.importModelMetadata("model_index_mix", multipartFile, request);

        List<RawRecItem> rawRecItems = jdbcRawRecStore.queryAll();
        Assert.assertEquals(13, rawRecItems.size());

        dataModelManager = NDataModelManager.getInstance(getTestConfig(), "model_index_mix");
        dataModel = dataModelManager.getDataModelDescByAlias("ssb_model_index_mixin");

        // 1 cc
        Assert.assertEquals(1, dataModel.getComputedColumnDescs().size());

        // 3 measure
        Assert.assertEquals(3, dataModel.getEffectiveMeasures().size());

        // 7 dimension
        Assert.assertEquals(7, dataModel.getEffectiveDimensions().size());
        Assert.assertEquals(
                "P_LINEORDER.LO_CUSTKEY, P_LINEORDER.LO_ORDERDATE, P_LINEORDER.LO_ORDERKEY, P_LINEORDER.LO_ORDERPRIOTITY, P_LINEORDER.LO_PARTKEY, P_LINEORDER.LO_SUPPKEY, P_LINEORDER.LO_SUPPLYCOST",
                dataModel.getEffectiveDimensions().values().stream().map(TblColRef::getAliasDotName).sorted()
                        .collect(Collectors.joining(", ")));

        indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), "model_index_mix");
        indexPlan = indexPlanManager.getIndexPlanByModelAlias("ssb_model_index_mixin");

        // 11 layout
        Assert.assertEquals(11, indexPlan.getAllLayouts().size());
        // should 6, rule based black layout should be deleted
        Assert.assertEquals(6, indexPlan.getRuleBaseLayouts().size());

        Assert.assertEquals(5, indexPlan.getWhitelistLayouts().size());

        // 2 recommendation agg index
        Assert.assertEquals(2, indexPlan.getWhitelistLayouts().stream().filter(layoutEntity -> !layoutEntity.isManual())
                .filter(layoutEntity -> IndexEntity.isAggIndex(layoutEntity.getId())).count());

        // 2 recommendation table index
        Assert.assertEquals(2, indexPlan.getWhitelistLayouts().stream().filter(layoutEntity -> !layoutEntity.isManual())
                .filter(layoutEntity -> IndexEntity.isTableIndex(layoutEntity.getId())).count());

        // 1
        Assert.assertEquals(1, indexPlan.getWhitelistLayouts().stream().filter(LayoutEntity::isManual)
                .filter(layoutEntity -> IndexEntity.isTableIndex(layoutEntity.getId())).count());
    }

    @Test
    public void testImportModelMetadataWithOverProps() throws Exception {
        KylinConfig testConfig = getTestConfig();
        File file = new File(
                "src/test/resources/ut_model_metadata/override_props_project_model_metadata_2020_11_23_17_48_49_98C8C10F8495CB5CB3CE2CFF2E732EF4.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        NDataModelManager dataModelManager = NDataModelManager.getInstance(testConfig, "original_project");
        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(testConfig, "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("ssb_model");
        Assert.assertNull(dataModel.getSegmentConfig().getAutoMergeEnabled());
        IndexPlan indexPlan = indexPlanManager.getIndexPlanByModelAlias("ssb_model");
        Assert.assertEquals(1, indexPlan.getOverrideProps().size());
        Assert.assertEquals("2", indexPlan.getOverrideProps().get("kylin.engine.spark-conf.spark.executor.cores"));

        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(
                new ModelImportRequest.ModelImport("ssb_model", "ssb_model", ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());
        dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        dataModel = dataModelManager.getDataModelDescByAlias("ssb_model");

        Assert.assertEquals(Boolean.TRUE, dataModel.getSegmentConfig().getAutoMergeEnabled());

        indexPlanManager = NIndexPlanManager.getInstance(kylinConfig, "original_project");
        Assert.assertEquals(4, indexPlanManager.getIndexPlanByModelAlias("ssb_model").getOverrideProps().size());
    }

    @Test
    public void testImportModelMetadataWithoutOverProps() throws Exception {
        KylinConfig testConfig = getTestConfig();
        File file = new File(
                "src/test/resources/ut_model_metadata/override_props_project_model_metadata_2020_11_23_18_43_01_EEF33ABA4B86F7C0A829856BC76E94A4.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        NDataModelManager dataModelManager = NDataModelManager.getInstance(testConfig, "original_project");
        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(testConfig, "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("ssb_model");
        Assert.assertNull(dataModel.getSegmentConfig().getAutoMergeEnabled());
        IndexPlan indexPlan = indexPlanManager.getIndexPlanByModelAlias("ssb_model");
        Assert.assertEquals(1, indexPlan.getOverrideProps().size());
        Assert.assertEquals("2", indexPlan.getOverrideProps().get("kylin.engine.spark-conf.spark.executor.cores"));

        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(
                new ModelImportRequest.ModelImport("ssb_model", "ssb_model", ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());
        dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        dataModel = dataModelManager.getDataModelDescByAlias("ssb_model");

        Assert.assertNull(dataModel.getSegmentConfig().getAutoMergeEnabled());
        indexPlan = indexPlanManager.getIndexPlanByModelAlias("ssb_model");
        Assert.assertEquals(1, indexPlan.getOverrideProps().size());
        Assert.assertEquals("2", indexPlan.getOverrideProps().get("kylin.engine.spark-conf.spark.executor.cores"));
    }

    @Test
    public void testImportModelMetadataWithMultiplePartitionValue() throws Exception {
        KylinConfig testConfig = getTestConfig();
        File file = new File(
                "src/test/resources/ut_meta/schema_utils/model_different_multiple_partition_with_partition_value_reduce_project/target_project_model_metadata_2020_12_02_20_50_10_3A88090D810EFB0FDAC1FFAEB80D9933.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        NDataModelManager dataModelManager = NDataModelManager.getInstance(testConfig, "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("conflict_multiple_partition_col_model");

        MultiPartitionKeyMappingImpl originalMultiPartitionKeyMapping = dataModel.getMultiPartitionKeyMapping();
        MultiPartitionDesc originalMultiPartitionDesc = dataModel.getMultiPartitionDesc();

        Assert.assertEquals(Collections.singletonList("SSB.CUSTOMER.C_CUSTKEY"),
                originalMultiPartitionKeyMapping.getAliasCols());
        Assert.assertEquals(Collections.singletonList("SSB.P_LINEORDER.LO_CUSTKEY"),
                originalMultiPartitionKeyMapping.getMultiPartitionCols());
        Assert.assertEquals(
                Arrays.asList(Pair.newPair(Collections.singletonList("p1"), Collections.singletonList("p11")),
                        Pair.newPair(Collections.singletonList("p2"), Collections.singletonList("p12")),
                        Pair.newPair(Collections.singletonList("p3"), Collections.singletonList("p13"))),
                originalMultiPartitionKeyMapping.getValueMapping());

        Assert.assertEquals(Collections.singletonList("P_LINEORDER.LO_CUSTKEY"),
                originalMultiPartitionDesc.getColumns());
        Assert.assertEquals(
                Arrays.asList(new MultiPartitionDesc.PartitionInfo(0, new String[] { "p1" }),
                        new MultiPartitionDesc.PartitionInfo(1, new String[] { "p2" }),
                        new MultiPartitionDesc.PartitionInfo(3, new String[] { "p3" })),
                originalMultiPartitionDesc.getPartitions());

        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("conflict_multiple_partition_col_model",
                "conflict_multiple_partition_col_model", ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());
        dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        dataModel = dataModelManager.getDataModelDescByAlias("conflict_multiple_partition_col_model");

        val targetMultiPartitionKeyMapping = dataModel.getMultiPartitionKeyMapping();
        val targetMultiPartitionDesc = dataModel.getMultiPartitionDesc();

        Assert.assertNull(targetMultiPartitionKeyMapping);

        Assert.assertEquals(Collections.singletonList("P_LINEORDER.LO_CUSTKEY"), targetMultiPartitionDesc.getColumns());
        Assert.assertEquals(
                Arrays.asList(new MultiPartitionDesc.PartitionInfo(1, new String[] { "p2" }),
                        new MultiPartitionDesc.PartitionInfo(3, new String[] { "p3" })),
                targetMultiPartitionDesc.getPartitions());
    }

    @Test
    public void testImportModelMetadataWithoutMultiplePartitionValue() throws Exception {
        KylinConfig testConfig = getTestConfig();
        File file = new File(
                "src/test/resources/ut_meta/schema_utils/model_empty_multiple_partition_value/model_empty_multiple_partition_value_2021_01_18_11_10_11_91E72B2EF5A6D8F818FA5F55009A9E16.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        NDataModelManager dataModelManager = NDataModelManager.getInstance(testConfig, "original_project");
        NDataModel dataModel = dataModelManager.getDataModelDescByAlias("conflict_multiple_partition_col_model");

        MultiPartitionKeyMappingImpl originalMultiPartitionKeyMapping = dataModel.getMultiPartitionKeyMapping();
        MultiPartitionDesc originalMultiPartitionDesc = dataModel.getMultiPartitionDesc();

        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("conflict_multiple_partition_col_model",
                "conflict_multiple_partition_col_model", ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        KylinConfig kylinConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());
        dataModelManager = NDataModelManager.getInstance(kylinConfig, "original_project");
        dataModel = dataModelManager.getDataModelDescByAlias("conflict_multiple_partition_col_model");

        val targetMultiPartitionKeyMapping = dataModel.getMultiPartitionKeyMapping();
        val targetMultiPartitionDesc = dataModel.getMultiPartitionDesc();

        // keep multiple values and mapping
        Assert.assertEquals(originalMultiPartitionKeyMapping.getAliasCols(),
                targetMultiPartitionKeyMapping.getAliasCols());
        Assert.assertEquals(originalMultiPartitionKeyMapping.getMultiPartitionCols(),
                targetMultiPartitionKeyMapping.getMultiPartitionCols());
        Assert.assertEquals(originalMultiPartitionKeyMapping.getValueMapping(),
                targetMultiPartitionKeyMapping.getValueMapping());
        Assert.assertEquals(originalMultiPartitionDesc.getColumns(), targetMultiPartitionDesc.getColumns());
        Assert.assertEquals(originalMultiPartitionDesc.getPartitions(), targetMultiPartitionDesc.getPartitions());
    }

    @Test
    public void testImportModelMetadataWithUnOverWritable() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("conflict_filter_condition_model", null,
                ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        thrown.expectCause(new BaseMatcher<Throwable>() {
            @Override
            public boolean matches(Object item) {
                return ((Exception) item).getMessage().contains(
                        "Can’t select ImportType \"OVERWRITE\" for the model \"conflict_filter_condition_model\". Please select \"UN_IMPORT\" (or \"NEW\").");
            }

            @Override
            public void describeTo(Description description) {

            }
        });
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
    }

    @Test
    public void testImportModelMetadataAndOverwriteWithLockedIndexes() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/test_lock_2_model_metadata_2024_03_12_16_14_19_A6843668BBCDE879C6BB8B4242A9E26C.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("test_lock_m1", "test_lock_m1",
                ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);
        metaStoreService.importModelMetadata("original_project", multipartFile, request);

        NDataflowManager nDataflowManager = NDataflowManager.getInstance(getTestConfig(), "original_project");
        NDataflow dataflow = nDataflowManager.getDataflow("f26dec69-99f5-6bb8-5af5-05809812efce");
        Segments<NDataSegment> segments = dataflow.getSegments();
        Assert.assertNotNull(segments);
        Assert.assertEquals(1, segments.size());

        NIndexPlanManager indexPlanManager = NIndexPlanManager.getInstance(getTestConfig(), "original_project");
        IndexPlan indexPlan = indexPlanManager.getIndexPlan("f26dec69-99f5-6bb8-5af5-05809812efce");
        List<IndexEntity> toBeDeletedIndexes = indexPlan.getToBeDeletedIndexes();
        List<Long> toBeDeletedIndexIdList = Lists.newArrayList();
        for (IndexEntity toBeDeletedIndex : toBeDeletedIndexes) {
            toBeDeletedIndexIdList.add(toBeDeletedIndex.getId());
        }

        Assert.assertEquals(indexPlan.getIndexes().get(0).getId(), 120000L);
        Assert.assertEquals(indexPlan.getIndexes().get(1).getId(), 20000010000L);

        Assert.assertEquals(toBeDeletedIndexIdList,
                Lists.newArrayList(70000L, 10000L, 50000L, 20000L, 0L, 20000000000L));

        NDataSegment segment = segments.get(0);
        NDataSegDetails segDetails = segment.getSegDetails();
        Assert.assertNotNull(segDetails.getLayoutById(70001L));
        Assert.assertNotNull(segDetails.getLayoutById(10001L));
        Assert.assertNotNull(segDetails.getLayoutById(50001L));
        Assert.assertNotNull(segDetails.getLayoutById(20001L));
        Assert.assertNotNull(segDetails.getLayoutById(1L));
        Assert.assertNotNull(segDetails.getLayoutById(20000000001L));
    }

    @Test
    public void testImportModelMetadataWithUnCreatable() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        request.setModels(Lists.newArrayList(new ModelImportRequest.ModelImport("missing_table_model",
                "missing_table_model_1", ModelImportRequest.ImportType.NEW)));
        val manager = NTableMetadataManager.getInstance(getTestConfig(), "original_project");
        Assert.assertNull(manager.getTableDesc("SSB.CUSTOMER_NEW"));
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
        Assert.assertNotNull(manager.getTableDesc("SSB.CUSTOMER_NEW"));
    }

    @Test
    public void testImportModelWithLoadTableFailed() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        request.setModels(Lists.newArrayList(new ModelImportRequest.ModelImport("missing_table_model",
                "missing_table_model_1", ModelImportRequest.ImportType.NEW)));
        val manager = NTableMetadataManager.getInstance(getTestConfig(), "original_project");
        Assert.assertNull(manager.getTableDesc("SSB.CUSTOMER_NEW"));
        val spyService = Mockito.spy(metaStoreService);
        val tableExtService = (TableExtService) ReflectionTestUtils.getField(spyService, "tableExtService");
        val spyTableService = Mockito.spy(tableExtService);
        LoadTableResponse loadTableResponse = new LoadTableResponse();
        loadTableResponse.getFailed().add("SSB.CUSTOMER_NEW");
        Mockito.doReturn(loadTableResponse).when(spyTableService).loadDbTables(new String[] { "SSB.CUSTOMER_NEW" },
                "original_project", false);
        ReflectionTestUtils.setField(spyService, "tableExtService", spyTableService);
        Mockito.doReturn(loadTableResponse).when(spyService).innerLoadTables(Mockito.anyString(), Mockito.anySet());
        spyService.importModelMetadata("original_project", multipartFile, request);
        Assert.assertNull(manager.getTableDesc("SSB.CUSTOMER_NEW"));
    }

    @Test
    public void testImportModelMetadataOverwriteWithUnExistsOriginalModel() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("ssb_model_1", null, ModelImportRequest.ImportType.OVERWRITE));
        models.add(new ModelImportRequest.ModelImport("ssb_model_2", null, ModelImportRequest.ImportType.OVERWRITE));

        request.setModels(models);

        thrown.expectCause(new BaseMatcher<Throwable>() {
            @Override
            public boolean matches(Object item) {
                return ((Exception) item).getMessage()
                        .contains("KE-010002016(Failed to import model):Can’t import the model.\n"
                                + "Can’t overwrite the model \"ssb_model_1\", as it doesn’t exist. Please re-select and try again.\n"
                                + "Can’t overwrite the model \"ssb_model_2\", as it doesn’t exist. Please re-select and try again.");
            }

            @Override
            public void describeTo(Description description) {

            }
        });
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
    }

    @Test
    public void testImportModelMetadataWithCreateDuplicateNameModel() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(new ModelImportRequest.ModelImport("ssb_model", "conflict_filter_condition_model",
                ModelImportRequest.ImportType.NEW));
        models.add(new ModelImportRequest.ModelImport("model_agg_update", "model_column_update",
                ModelImportRequest.ImportType.NEW));

        request.setModels(models);

        thrown.expectCause(new BaseMatcher<Throwable>() {
            @Override
            public boolean matches(Object item) {
                return ((Exception) item).getMessage()
                        .contains("KE-010002009(Model Metadata File Error):Can’t import the model.\n"
                                + "Model name 'conflict_filter_condition_model' is duplicated, could not be created.\n"
                                + "Model name 'model_column_update' is duplicated, could not be created.");
            }

            @Override
            public void describeTo(Description description) {

            }
        });
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
    }

    @Test
    public void testImportModelMetadataWithCreateIllegalNameModel() throws Exception {
        File file = new File(
                "src/test/resources/ut_model_metadata/metastore_model_metadata_1904B44DD1531491299AA9A2D8A00FBD.zip");
        var multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        ModelImportRequest request = new ModelImportRequest();
        List<ModelImportRequest.ModelImport> models = new ArrayList<>();
        models.add(
                new ModelImportRequest.ModelImport("ssb_model", "ssb_model@_test", ModelImportRequest.ImportType.NEW));
        models.add(new ModelImportRequest.ModelImport("model_agg_update", "#ssb_model_test",
                ModelImportRequest.ImportType.NEW));
        models.add(new ModelImportRequest.ModelImport("model_index", "ssb_model_test ",
                ModelImportRequest.ImportType.NEW));
        models.add(new ModelImportRequest.ModelImport("model_cc_update", "ssb_test_123",
                ModelImportRequest.ImportType.NEW));

        request.setModels(models);

        thrown.expectCause(new BaseMatcher<Throwable>() {
            @Override
            public boolean matches(Object item) {
                return ((Exception) item).getMessage()
                        .contains("KE-010002016(Failed to import model):Can’t import the model.\n"
                                + "The model name \"ssb_model@_test\" is invalid. Please use letters, numbers and underlines only.\n"
                                + "The model name \"#ssb_model_test\" is invalid. Please use letters, numbers and underlines only.\n"
                                + "The model name \"ssb_model_test \" is invalid. Please use letters, numbers and underlines only.");
            }

            @Override
            public void describeTo(Description description) {

            }
        });
        metaStoreService.importModelMetadata("original_project", multipartFile, request);
    }

    @Test
    public void testReduceColumn() throws Exception {
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_reduce_column_table/model_reduce_column_model_metadata_2020_11_14_17_11_19_7E0BD7CB6DB0FF36846254464AA167C2.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val metadataCheckResponse = metaStoreService.checkModelMetadata("original_project", multipartFile, null);

        SchemaChangeCheckResult.ModelSchemaChange modelSchemaChange = metadataCheckResponse.getModels()
                .get("ssb_model");
        Assert.assertNotNull(modelSchemaChange);

        Assert.assertTrue(modelSchemaChange.getReduceItems().stream()
                .anyMatch(changedItem -> changedItem.isOverwritable()
                        && changedItem.getType() == SchemaNodeType.TABLE_COLUMN
                        && changedItem.getDetail().equals("SSB.P_LINEORDER.LO_SUPPKEY")));
    }

    @Test
    public void testMissTable() throws IOException {
        String table = "SSB.CUSTOMER_NEW";
        val file = new File(
                "src/test/resources/ut_meta/schema_utils/model_missing_table_update/model_table_missing_update_model_metadata_2020_11_16_02_37_33_35ACA82A376E4D5418B96444DA5CC657.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        val checkResult = metaStoreService.checkModelMetadata("original_project", multipartFile, null);
        val modelSchemaChange = checkResult.getModels().get("ssb_model");
        Assert.assertEquals(1, checkResult.getExistTableList().size());
        Assert.assertEquals(table, checkResult.getExistTableList().get(0).getIdentity());
        Assert.assertNotNull(modelSchemaChange);
        Assert.assertTrue(modelSchemaChange.creatable());
        Assert.assertTrue(modelSchemaChange.importable());
        Assert.assertFalse(modelSchemaChange.overwritable());
    }

    @Test
    public void testSearchTablesInDataSource() {
        {
            val existTables = metaStoreService.searchTablesInDataSource("original_project", Lists.newArrayList());
            Assert.assertTrue(existTables.isEmpty());
        }

        {
            TableDesc tableDesc = new TableDesc();
            tableDesc.setDatabase("SSB");
            tableDesc.setName("CUSTOMER_NEW");
            val existTables = metaStoreService.searchTablesInDataSource("original_project",
                    Lists.newArrayList(tableDesc));
            Assert.assertFalse(existTables.isEmpty());
            Assert.assertEquals(1, existTables.size());
            Assert.assertEquals("SSB.CUSTOMER_NEW", existTables.get(0).getIdentity());
        }

        {
            TableDesc tableDesc = new TableDesc();
            tableDesc.setDatabase("SSB");
            tableDesc.setName("CUSTOMER_NEW_NEW");
            val existTables = metaStoreService.searchTablesInDataSource("original_project",
                    Lists.newArrayList(tableDesc));
            Assert.assertTrue(existTables.isEmpty());
        }
    }

    @Test
    public void testGetModelMetadataProjectName() throws IOException {
        File file = new File(
                "src/test/resources/ut_meta/schema_utils/conflict_dim_table_project/conflict_dim_table_project_model_metadata_2020_11_14_16_20_06_2241FB33CC2A33D4033214E73BEFBCE6.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));

        Map<String, RawResource> rawResourceMap = getRawResourceFromUploadFile(multipartFile);
        for (int i = 0; i < new Random().nextInt(10); i++) {
            String projectName = ReflectionTestUtils.invokeMethod(metaStoreService, "getModelMetadataProjectName",
                    rawResourceMap);

            Assert.assertEquals("conflict_dim_table_project", projectName);
        }
    }

    @Test
    public void testMetadataChecker() throws IOException {
        File file = new File("src/test/resources/ut_model_metadata/ut_model_matadata.zip");
        val multipartFile = new MockMultipartFile(file.getName(), file.getName(), null,
                Files.newInputStream(file.toPath()));
        KylinConfig modelConfig = KylinConfig.createKylinConfig(KylinConfig.getInstanceFromEnv());
        MetadataChecker metadataChecker = new MetadataChecker(MetadataStore.createMetadataStore(modelConfig));
        Map<String, RawResource> rawResourceMap = getRawResourceFromUploadFile(multipartFile);
        MetadataChecker.VerifyResult verifyResult = metadataChecker
                .verifyModelMetadata(Lists.newArrayList(rawResourceMap.keySet()));
        Assert.assertTrue(verifyResult.isModelMetadataQualified());
        String messageResult = "the uuid file exists : true\n" + "the image file exists : false\n"
                + "the user_group file exists : false\n" + "the user dir exist : false\n"
                + "the acl dir exist : false\n";
        Assert.assertEquals(messageResult, verifyResult.getResultMessage());
    }

    private Map<String, RawResource> getRawResourceFromUploadFile(MultipartFile uploadFile) throws IOException {
        return MetaStoreService.getRawResourceFromUploadFile(uploadFile);
    }

    @Test
    public void testGetSimplifiedModelWithStreaming() {
        List<ModelPreviewResponse> modelPreviewResponseList = metaStoreService.getPreviewModels("streaming_test",
                Collections.emptyList());
        Assert.assertEquals(1, modelPreviewResponseList.size());
    }

    @Test
    public void testCleanupMeta() {
        metaStoreService.cleanupMeta(UnitOfWork.GLOBAL_UNIT);
        metaStoreService.cleanupMeta(PROJECT_DEFAULT);
    }
}
