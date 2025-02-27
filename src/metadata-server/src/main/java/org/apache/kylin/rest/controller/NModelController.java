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

package org.apache.kylin.rest.controller;

import static org.apache.kylin.common.constant.HttpConstant.HTTP_VND_APACHE_KYLIN_JSON;
import static org.apache.kylin.common.constant.HttpConstant.HTTP_VND_APACHE_KYLIN_V4_PUBLIC_JSON;
import static org.apache.kylin.common.exception.ServerErrorCode.FAILED_CREATE_MODEL;
import static org.apache.kylin.common.exception.ServerErrorCode.FAILED_UPDATE_MODEL;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_NAME_INVALID;
import static org.apache.kylin.common.exception.code.ErrorCodeServer.MODEL_NAME_TOO_LONG;

import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.apache.kylin.common.constant.Constant;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.KylinRuntimeException;
import org.apache.kylin.common.util.JsonUtil;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Sets;
import org.apache.kylin.metadata.cube.model.NDataLayoutDetails;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.PartitionDesc;
import org.apache.kylin.metadata.model.exception.LookupTableException;
import org.apache.kylin.rest.constant.ModelAttributeEnum;
import org.apache.kylin.rest.constant.ModelStatusToDisplayEnum;
import org.apache.kylin.rest.request.AggShardByColumnsRequest;
import org.apache.kylin.rest.request.ComputedColumnCheckRequest;
import org.apache.kylin.rest.request.ModelCheckRequest;
import org.apache.kylin.rest.request.ModelCloneRequest;
import org.apache.kylin.rest.request.ModelConfigRequest;
import org.apache.kylin.rest.request.ModelRequest;
import org.apache.kylin.rest.request.ModelUpdateRequest;
import org.apache.kylin.rest.request.ModelValidationRequest;
import org.apache.kylin.rest.request.MultiPartitionMappingRequest;
import org.apache.kylin.rest.request.OptimizeLayoutDataRequest;
import org.apache.kylin.rest.request.OwnerChangeRequest;
import org.apache.kylin.rest.request.PartitionColumnRequest;
import org.apache.kylin.rest.request.UpdateModelStorageTypeRequest;
import org.apache.kylin.rest.request.UpdateMultiPartitionValueRequest;
import org.apache.kylin.rest.response.AffectedModelsResponse;
import org.apache.kylin.rest.response.AggShardByColumnsResponse;
import org.apache.kylin.rest.response.BuildBaseIndexResponse;
import org.apache.kylin.rest.response.ComputedColumnCheckResponse;
import org.apache.kylin.rest.response.ComputedColumnUsageResponse;
import org.apache.kylin.rest.response.DataResult;
import org.apache.kylin.rest.response.EnvelopeResponse;
import org.apache.kylin.rest.response.ExistedDataRangeResponse;
import org.apache.kylin.rest.response.IndicesResponse;
import org.apache.kylin.rest.response.InvalidIndexesResponse;
import org.apache.kylin.rest.response.JobInfoResponse;
import org.apache.kylin.rest.response.ModelConfigResponse;
import org.apache.kylin.rest.response.ModelSaveCheckResponse;
import org.apache.kylin.rest.response.MultiPartitionValueResponse;
import org.apache.kylin.rest.response.PurgeModelAffectedResponse;
import org.apache.kylin.rest.service.AbstractModelService;
import org.apache.kylin.rest.service.FusionIndexService;
import org.apache.kylin.rest.service.FusionModelService;
import org.apache.kylin.rest.service.IndexPlanService;
import org.apache.kylin.rest.service.ModelService;
import org.apache.kylin.rest.service.ModelTdsService;
import org.apache.kylin.rest.service.params.ModelQueryParams;
import org.apache.kylin.tool.bisync.SyncContext;
import org.apache.kylin.tool.bisync.model.SyncModel;
import org.apache.kylin.util.DataRangeUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import com.fasterxml.jackson.core.JsonProcessingException;

import io.swagger.annotations.ApiOperation;
import lombok.val;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
@EnableDiscoveryClient
@RequestMapping(value = "/api/models", produces = { HTTP_VND_APACHE_KYLIN_JSON })
public class NModelController extends NBasicController {
    public static final String MODEL_ID = "modelId";
    private static final String NEW_MODEL_NAME = "newModelNAME";

    @Autowired
    @Qualifier("modelService")
    private ModelService modelService;

    @Autowired
    @Qualifier("modelTdsService")
    private ModelTdsService tdsService;

    @Autowired
    private FusionModelService fusionModelService;

    @Autowired
    private IndexPlanService indexPlanService;

    @Autowired
    private FusionIndexService fusionIndexService;

    @ApiOperation(value = "getModels{Red}", tags = {
            "AI" }, notes = "Update Param: page_offset, page_size, sort_by; Update Response: total_size")
    @GetMapping(value = "")
    @ResponseBody
    public EnvelopeResponse<DataResult<List<NDataModel>>> getModels(
            @RequestParam(value = "model_id", required = false) String modelId, //
            @RequestParam(value = "model_name", required = false) String modelAlias,
            @RequestParam(value = "exact", required = false, defaultValue = "true") boolean exactMatch,
            @RequestParam(value = "project") String project, //
            @RequestParam(value = "owner", required = false) String owner,
            @RequestParam(value = "status", required = false) List<String> status,
            @RequestParam(value = "table", required = false) String table,
            @RequestParam(value = "page_offset", required = false, defaultValue = "0") Integer offset,
            @RequestParam(value = "page_size", required = false, defaultValue = "10") Integer limit,
            @RequestParam(value = "sort_by", required = false, defaultValue = "last_modify") String sortBy,
            @RequestParam(value = "reverse", required = false, defaultValue = "true") boolean reverse,
            @RequestParam(value = "model_alias_or_owner", required = false) String modelAliasOrOwner,
            @RequestParam(value = "model_attributes", required = false) List<ModelAttributeEnum> modelAttributes,
            @RequestParam(value = "last_modify_from", required = false) Long lastModifyFrom,
            @RequestParam(value = "last_modify_to", required = false) Long lastModifyTo,
            @RequestParam(value = "only_normal_dim", required = false, defaultValue = "true") boolean onlyNormalDim,
            @RequestParam(value = "lite", required = false, defaultValue = "false") boolean lite) {
        checkProjectName(project);
        status = formatStatus(status, ModelStatusToDisplayEnum.class);

        ModelQueryParams request = new ModelQueryParams(modelId, modelAlias, exactMatch, project, owner, status, table,
                offset, limit, sortBy, reverse, modelAliasOrOwner, modelAttributes, lastModifyFrom, lastModifyTo,
                onlyNormalDim, lite);
        DataResult<List<NDataModel>> filterModels = modelService.getModels(request);
        fusionModelService.setModelUpdateEnabled(filterModels);
        fusionModelService.setAutoIndexPlanEnabled(filterModels);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, filterModels, "");
    }

    @ApiOperation(value = "validateNewModelAlias", tags = { "AI" })
    @PostMapping(value = "/name/validation")
    @ResponseBody
    public EnvelopeResponse<Boolean> validateNewModelAlias(@RequestBody ModelValidationRequest modelRequest) {
        checkRequiredArg("model_name", modelRequest.getModelName());
        checkProjectName(modelRequest.getProject());
        val exists = fusionModelService.modelExists(modelRequest.getModelName(), modelRequest.getProject());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, exists, "");
    }

    @ApiOperation(value = "offlineAllModelsInProject", tags = {
            "AI" }, notes = "Update URL: {project}; Update Param: project")
    @PutMapping(value = "/disable_all_models")
    @ResponseBody
    public EnvelopeResponse<String> offlineAllModelsInProject(@RequestParam("project") String project) {
        checkProjectName(project);
        modelService.offlineAllModelsInProject(project);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "offlineAllModelsInProject", tags = {
            "AI" }, notes = "Update URL: {project}; Update Param: project")
    @PutMapping(value = "/enable_all_models")
    @ResponseBody
    public EnvelopeResponse<String> onlineAllModelsInProject(@RequestParam("project") String project) {
        checkProjectName(project);
        modelService.onlineAllModelsInProject(project);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "createModel", tags = { "AI" })
    @PostMapping(value = "", produces = { HTTP_VND_APACHE_KYLIN_JSON })
    @ResponseBody
    public EnvelopeResponse<BuildBaseIndexResponse> createModel(@RequestBody ModelRequest modelRequest) {
        String project = checkProjectName(modelRequest.getProject());
        modelService.validatePartitionDesc(modelRequest.getPartitionDesc());
        String partitionDateFormat = modelRequest.getPartitionDesc() == null ? null
                : modelRequest.getPartitionDesc().getPartitionDateFormat();
        DataRangeUtils.validateDataRange(modelRequest.getStart(), modelRequest.getEnd(), partitionDateFormat);
        try {
            NDataModel model = modelService.createModel(modelRequest.getProject(), modelRequest);
            try {
                if (modelRequest.isWithRecJob()) {
                    indexPlanService.optIndexPlan(model.getId(), null, modelRequest.getProject(), 3, null, null);
                }
            } catch (Exception e) {
                log.error("Create rec job failed, due to:", e);
            }
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS,
                    BuildBaseIndexResponse.from(modelService.getIndexPlan(model.getId(), model.getProject())), "");
        } catch (LookupTableException e) {
            throw new KylinException(FAILED_CREATE_MODEL, e);
        }
    }

    private void executeOptIndexPlan(String project, String modelId, boolean recOptIndexJob) {
        try {
            if (recOptIndexJob) {
                indexPlanService.optIndexPlan(modelId, null, project, 3, null, null);
            }
        } catch (Exception e) {
            log.error("Create rec job failed, due to:", e);
        }
    }

    @Deprecated
    @ApiOperation(value = "batchSaveModels", tags = { "AI" }, notes = "Update URL: {project}; Update Param: project")
    @PostMapping(value = "/batch_save_models")
    @ResponseBody
    public EnvelopeResponse<String> batchSaveModels(@RequestParam("project") String project,
            @RequestBody List<ModelRequest> modelRequests) {
        checkProjectName(project);
        checkProjectNotSemiAuto(project);
        try {
            modelService.batchCreateModel(project, modelRequests, Lists.newArrayList());
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
        } catch (LookupTableException e) {
            throw new KylinException(FAILED_CREATE_MODEL, e.getMessage(), e);
        }
    }

    /**
     * if exist same name model, then return true.
     */
    @ApiOperation(value = "validateModelAlias", tags = { "AI" }, notes = "")
    @PostMapping(value = "/validate_model")
    @ResponseBody
    public EnvelopeResponse<Boolean> validateModelAlias(@RequestBody ModelRequest modelRequest) {
        checkProjectName(modelRequest.getProject());
        checkRequiredArg(MODEL_ID, modelRequest.getUuid());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, !modelService
                .checkModelAliasUniqueness(modelRequest.getUuid(), modelRequest.getAlias(), modelRequest.getProject()),
                "");
    }

    /**
     * list model that is scd2 join condition
     *
     * @param project
     * @return
     * @throws Exception
     */
    @ApiOperation(value = "listScd2Model", tags = { "AI" }, notes = "")
    @GetMapping(value = "name/scd2")
    @ResponseBody
    public EnvelopeResponse<List<String>> listScd2Model(@RequestParam("project") String project,
            @RequestParam(value = "non_offline", required = false, defaultValue = "true") boolean nonOffline) {
        checkProjectName(project);

        List<String> status = nonOffline ? modelService.getModelNonOffOnlineStatus()
                : Collections.singletonList(ModelStatusToDisplayEnum.OFFLINE.name());
        List<String> scd2ModelsOnline = modelService.getSCD2ModelsAliasByStatus(project, status);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, scd2ModelsOnline, "");
    }

    /**
     * list model that is multi partition
     */
    @ApiOperation(value = "listMultiPartitionModel", tags = { "AI" }, notes = "")
    @GetMapping(value = "/name/multi_partition")
    @ResponseBody
    public EnvelopeResponse<List<String>> listMultiPartitionModel(@RequestParam("project") String project,
            @RequestParam(value = "non_offline", required = false, defaultValue = "true") boolean nonOffline) {
        checkProjectName(project);
        List<String> onlineStatus = null;
        if (nonOffline) {
            onlineStatus = modelService.getModelNonOffOnlineStatus();
        }
        List<String> multiPartitionModels = modelService.getMultiPartitionModelsAlias(project, onlineStatus);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, multiPartitionModels, "");
    }

    @ApiOperation(value = "getLatestData", tags = { "AI" }, notes = "Update URL: {model}")
    @GetMapping(value = "/{model:.+}/data_range/latest_data")
    @ResponseBody
    public EnvelopeResponse<ExistedDataRangeResponse> getLatestData(@PathVariable(value = "model") String modelId,
            @RequestParam(value = "project") String project) {
        String projectName = checkProjectName(project);
        ExistedDataRangeResponse response = modelService.getLatestDataRange(projectName, modelId, null);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "getLatestData", tags = { "AI" }, notes = "Update URL: {model}")
    @PostMapping(value = "/{model:.+}/data_range/latest_data")
    @ResponseBody
    public EnvelopeResponse<ExistedDataRangeResponse> getPartitionLatestData(
            @PathVariable(value = "model") String modelId, @RequestBody PartitionColumnRequest request) {
        String project = checkProjectName(request.getProject());
        ExistedDataRangeResponse response = modelService.getLatestDataRange(project, modelId,
                request.getPartitionDesc());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "getAggIndices", tags = {
            "AI" }, notes = "Update URL: model; Update Param: is_case_sensitive, page_offset, page_size, sort_by; Update Response: total_size")
    @GetMapping(value = "/{model:.+}/agg_indices")
    @ResponseBody
    public EnvelopeResponse<IndicesResponse> getAggIndices(@PathVariable(value = "model") String modelId,
            @RequestParam(value = "project") String project, //
            @RequestParam(value = "index", required = false) Long indexId, //
            @RequestParam(value = "content", required = false) String contentSeg, //
            @RequestParam(value = "is_case_sensitive", required = false, defaultValue = "false") boolean isCaseSensitive, //
            @RequestParam(value = "page_offset", required = false, defaultValue = "0") Integer pageOffset, //
            @RequestParam(value = "page_size", required = false, defaultValue = "10") Integer pageSize, //
            @RequestParam(value = "sort_by", required = false, defaultValue = "last_modify_time") String sortBy,
            @RequestParam(value = "reverse", required = false, defaultValue = "true") Boolean reverse) {
        checkProjectName(project);
        checkRequiredArg(MODEL_ID, modelId);
        val result = modelService.getAggIndices(project, modelId, indexId, contentSeg, isCaseSensitive, pageOffset,
                pageSize, sortBy, reverse);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, result, "");
    }

    @ApiOperation(value = "updateAggIndicesShardColumns", tags = {
            "AI" }, notes = "Update URL: model;Update Param: model_id")
    @PostMapping(value = "/{model:.+}/agg_indices/shard_columns")
    @ResponseBody
    public EnvelopeResponse<String> updateAggIndicesShardColumns(@PathVariable("model") String modelId,
            @RequestBody AggShardByColumnsRequest aggShardByColumnsRequest) {
        checkProjectName(aggShardByColumnsRequest.getProject());
        aggShardByColumnsRequest.setModelId(modelId);
        checkRequiredArg(MODEL_ID, aggShardByColumnsRequest.getModelId());
        fusionIndexService.updateShardByColumns(aggShardByColumnsRequest.getProject(), aggShardByColumnsRequest);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "getAggIndicesShardColumns", tags = {
            "AI" }, notes = "Update URL: model; Update Response: model_id")
    @GetMapping(value = "/{model:.+}/agg_indices/shard_columns")
    @ResponseBody
    public EnvelopeResponse<AggShardByColumnsResponse> getAggIndicesShardColumns(
            @PathVariable(value = "model") String modelId, @RequestParam(value = "project") String project) {
        checkProjectName(project);
        checkRequiredArg(MODEL_ID, modelId);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, indexPlanService.getShardByColumns(project, modelId),
                "");
    }

    @Deprecated
    @ApiOperation(value = "getTableIndices", tags = { "AI" }, notes = "Update URL: {model}")
    @GetMapping(value = "/{model:.+}/table_indices")
    @ResponseBody
    public EnvelopeResponse<IndicesResponse> getTableIndices(@PathVariable(value = "model") String modelId,
            @RequestParam(value = "project") String project) {
        checkProjectName(project);
        checkRequiredArg(MODEL_ID, modelId);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, modelService.getTableIndices(modelId, project), "");
    }

    @ApiOperation(value = "getModelJson", tags = { "AI" }, notes = "Update URL: {model}")
    @GetMapping(value = "/{model:.+}/json")
    @ResponseBody
    public EnvelopeResponse<String> getModelJson(@PathVariable(value = "model") String modelId,
            @RequestParam(value = "project") String project) {

        checkProjectName(project);
        checkRequiredArg(MODEL_ID, modelId);
        try {
            String json = modelService.getModelJson(modelId, project);
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, json, "");
        } catch (JsonProcessingException e) {
            throw new KylinRuntimeException("can not get model json " + e);
        }
    }

    @ApiOperation(value = "getModelSql", tags = { "AI" }, notes = "Update URL: {model}")
    @GetMapping(value = "{model:.+}/sql")
    @ResponseBody
    public EnvelopeResponse<String> getModelSql(@PathVariable(value = "model") String modelId,
            @RequestParam(value = "project") String project) {
        checkProjectName(project);
        checkRequiredArg(MODEL_ID, modelId);

        try {
            String sql = modelService.getModelSql(modelId, project);
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, sql, "");
        } catch (Exception e) {
            throw new KylinRuntimeException("can not get model sql, " + e);
        }
    }

    @ApiOperation(value = "getAffectedModels", tags = { "AI" })
    @GetMapping(value = "/affected_models", produces = { HTTP_VND_APACHE_KYLIN_JSON,
            HTTP_VND_APACHE_KYLIN_V4_PUBLIC_JSON })
    @ResponseBody
    public EnvelopeResponse<AffectedModelsResponse> getAffectedModelsBySourceTableAction(
            @RequestParam(value = "table") String tableName, //
            @RequestParam(value = "project") String project, //
            @RequestParam(value = "action") String action) {
        checkProjectName(project);
        checkRequiredArg("table", tableName);
        checkRequiredArg("action", action);

        if ("TOGGLE_PARTITION".equals(action)) {
            // TABLE_ORIENTED model is deprecated, just return empty response.
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, new AffectedModelsResponse(), "");
        } else if ("DROP_TABLE".equals(action)) {
            val affectedModelResponse = modelService.getAffectedModelsByDeletingTable(tableName, project);
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, affectedModelResponse, "");
        } else if ("RELOAD_ROOT_FACT".equals(action)) {
            // TABLE_ORIENTED model is deprecated, just return empty response.
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, new AffectedModelsResponse(), "");
        } else {
            throw new IllegalArgumentException();
        }
    }

    @ApiOperation(value = "checkPartitionDesc", tags = { "AI" })
    @PostMapping(value = "/check_partition_desc")
    @ResponseBody
    public EnvelopeResponse<String> checkPartitionDesc(@RequestBody PartitionDesc partitionDesc) {
        modelService.validatePartitionDesc(partitionDesc);
        String partitionDateFormat = partitionDesc.getPartitionDateFormat();
        PartitionDesc.TimestampType timestampType = partitionDesc.getTimestampType();
        if (timestampType == null) {
            SimpleDateFormat simpleDateFormat = new SimpleDateFormat(partitionDateFormat,
                    Locale.getDefault(Locale.Category.FORMAT));
            String dateFormat = simpleDateFormat.format(new Date());
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, dateFormat, "");
        } else {
            long timestamp = System.currentTimeMillis() / timestampType.millisecondRatio;
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, timestamp + "", "");
        }
    }

    @ApiOperation(value = "detectInvalidIndexes", tags = { "AI" })
    @PostMapping(value = "/invalid_indexes")
    @ResponseBody
    public EnvelopeResponse<InvalidIndexesResponse> detectInvalidIndexes(@RequestBody ModelRequest request) {
        checkProjectName(request.getProject());
        InvalidIndexesResponse response = modelService.detectInvalidIndexes(request);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "updateModelSemantic", tags = { "AI" })
    @PutMapping(value = "/semantic")
    @ResponseBody
    public EnvelopeResponse<BuildBaseIndexResponse> updateSemantic(@RequestBody ModelRequest request) {
        String project = checkProjectName(request.getProject());
        String partitionColumnFormat = modelService.getPartitionColumnFormatById(request.getProject(), request.getId());
        DataRangeUtils.validateDataRange(request.getStart(), request.getEnd(), partitionColumnFormat);
        modelService.validatePartitionDesc(request.getPartitionDesc());
        checkRequiredArg(MODEL_ID, request.getUuid());
        try {
            BuildBaseIndexResponse response = BuildBaseIndexResponse.EMPTY;
            if (request.getBrokenReason() == NDataModel.BrokenReason.SCHEMA) {
                modelService.repairBrokenModel(request.getProject(), request);
            } else {
                response = fusionModelService.updateDataModelSemantic(request.getProject(), request);
            }
            executeOptIndexPlan(project, request.getId(), request.isWithRecJob());
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
        } catch (LookupTableException e) {
            log.error("Update model failed", e);
            throw new KylinException(FAILED_UPDATE_MODEL, e);
        } catch (Exception e) {
            log.error("Update model failed", e);
            Throwable root = ExceptionUtils.getRootCause(e) == null ? e : ExceptionUtils.getRootCause(e);
            throw new KylinException(FAILED_UPDATE_MODEL, root.getMessage());
        }
    }

    @ApiOperation(value = "changePartition", tags = { "AI" })
    @PutMapping(value = "/{model:.+}/partition")
    @ResponseBody
    public EnvelopeResponse<String> updatePartitionSemantic(@PathVariable("model") String modelId,
            @RequestBody PartitionColumnRequest request) throws Exception {
        checkProjectName(request.getProject());
        modelService.validatePartitionDesc(request.getPartitionDesc());
        checkRequiredArg(MODEL_ID, modelId);
        try {
            modelService.updatePartitionColumn(request.getProject(), modelId, request.getPartitionDesc(),
                    request.getMultiPartitionDesc());
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
        } catch (LookupTableException e) {
            log.error("Change partition failed", e);
            throw new KylinException(FAILED_UPDATE_MODEL, e);
        }
    }

    @ApiOperation(value = "updateModelName", tags = { "AI" }, notes = "Update Body: model_id, new_model_name")
    @PutMapping(value = "/{model:.+}/name")
    @ResponseBody
    public EnvelopeResponse<String> updateModelName(@PathVariable("model") String modelId,
            @RequestBody ModelUpdateRequest modelRenameRequest) {
        checkProjectName(modelRenameRequest.getProject());
        checkRequiredArg(MODEL_ID, modelId);
        String newAlias = modelRenameRequest.getNewModelName();
        String description = modelRenameRequest.getDescription();
        if (!StringUtils.containsOnly(newAlias, AbstractModelService.VALID_NAME_FOR_MODEL)) {
            throw new KylinException(MODEL_NAME_INVALID, newAlias);
        }
        if (newAlias.length() > Constant.MODEL_ALIAS_LEN_LIMIT) {
            throw new KylinException(MODEL_NAME_TOO_LONG);
        }

        fusionModelService.renameDataModel(modelRenameRequest.getProject(), modelId, newAlias, description);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "updateModelStatus", tags = { "AI" }, notes = "Update Body: model_id, new_model_name")
    @PutMapping(value = "/{model:.+}/status", produces = { HTTP_VND_APACHE_KYLIN_JSON })
    @ResponseBody
    public EnvelopeResponse<String> updateModelStatus(@PathVariable("model") String modelId,
            @RequestBody ModelUpdateRequest modelRenameRequest) {
        String actualProject = checkProjectName(modelRenameRequest.getProject());
        modelRenameRequest.setProject(actualProject);
        checkRequiredArg(MODEL_ID, modelId);
        modelService.updateDataModelStatus(modelId, modelRenameRequest.getProject(), modelRenameRequest.getStatus());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "unlinkModel", tags = { "AI" }, notes = "Update Body: model_id")
    @PutMapping(value = "/{model:.+}/management_type")
    @ResponseBody
    public EnvelopeResponse<String> unlinkModel() {
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "deleteModel", tags = { "AI" }, notes = "Update URL: {project}; Update Param: project")
    @DeleteMapping(value = "/{model:.+}")
    @ResponseBody
    public EnvelopeResponse<String> deleteModel(@PathVariable("model") String model,
            @RequestParam("project") String project) {
        checkProjectName(project);
        fusionModelService.dropModel(model, project);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "getPurgeModelAffectedResponse", tags = { "AI" }, notes = "Add URL: {model}")
    @GetMapping(value = "/{model:.+}/purge_effect")
    @ResponseBody
    public EnvelopeResponse<PurgeModelAffectedResponse> getPurgeModelAffectedResponse(
            @PathVariable(value = "model") String model, @RequestParam(value = "project") String project) {
        checkProjectName(project);
        checkRequiredArg(MODEL_ID, model);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS,
                modelService.getPurgeModelAffectedResponse(project, model), "");
    }

    @ApiOperation(value = "cloneModel", tags = { "AI" }, notes = "Add URL: {model}; Update Param: new_model_name")
    @PostMapping(value = "/{model:.+}/clone")
    @ResponseBody
    public EnvelopeResponse<String> cloneModel(@PathVariable("model") String modelId,
            @RequestBody ModelCloneRequest request) {
        checkProjectName(request.getProject());
        String newModelName = request.getNewModelName();
        checkRequiredArg(MODEL_ID, modelId);
        checkRequiredArg(NEW_MODEL_NAME, newModelName);
        if (!StringUtils.containsOnly(newModelName, AbstractModelService.VALID_NAME_FOR_MODEL)) {
            throw new KylinException(MODEL_NAME_INVALID, newModelName);
        }
        modelService.cloneModel(modelId, request.getNewModelName(), request.getProject());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "checkComputedColumns", tags = {
            "AI" }, notes = "Update Response: table_identity, table_alias, column_name, inner_expression, data_type")
    @PostMapping(value = "/computed_columns/check")
    @ResponseBody
    public EnvelopeResponse<ComputedColumnCheckResponse> checkComputedColumns(
            @RequestBody ComputedColumnCheckRequest modelRequest) {
        checkProjectName(modelRequest.getProject());
        modelRequest.getModelDesc().setProject(modelRequest.getProject());
        NDataModel modelDesc = modelService.convertToDataModel(modelRequest.getModelDesc());
        modelDesc.setSeekingCCAdvice(modelRequest.isSeekingExprAdvice());
        modelService.primaryCheck(modelDesc);
        ComputedColumnCheckResponse response = modelService.checkComputedColumn(modelDesc, modelRequest.getProject(),
                modelRequest.getCcInCheck());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "getComputedColumnUsage", tags = { "AI" })
    @GetMapping(value = "/computed_columns/usage")
    @ResponseBody
    public EnvelopeResponse<ComputedColumnUsageResponse> getComputedColumnUsage(
            @RequestParam(value = "project") String project) {
        checkProjectName(project);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, modelService.getComputedColumnUsages(project), "");
    }

    @Deprecated
    @ApiOperation(value = "updateModelDataCheckDesc", tags = { "AI" }, notes = "URL, front end Deprecated")
    @PutMapping(value = "/{model:.+}/data_check")
    @ResponseBody
    public EnvelopeResponse<String> updateModelDataCheckDesc(@PathVariable("model") String modelId,
            @RequestBody ModelCheckRequest request) {
        checkProjectName(request.getProject());
        modelService.updateModelDataCheckDesc(request.getProject(), modelId, request.getCheckOptions(),
                request.getFaultThreshold(), request.getFaultActions());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "getModelConfig", tags = { "AI" }, notes = "Update Param: model_name, page_offset, page_size")
    @GetMapping(value = "/config")
    @ResponseBody
    public EnvelopeResponse<DataResult<List<ModelConfigResponse>>> getModelConfig(
            @RequestParam(value = "model_name", required = false) String modelAlias,
            @RequestParam(value = "project") String project,
            @RequestParam(value = "page_offset", required = false, defaultValue = "0") Integer offset,
            @RequestParam(value = "page_size", required = false, defaultValue = "10") Integer limit) {
        checkProjectName(project);
        val modelConfigs = modelService.getModelConfig(project, modelAlias);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, DataResult.get(modelConfigs, offset, limit), "");
    }

    @ApiOperation(value = "updateModelConfig", tags = { "AI" })
    @PutMapping(value = "/{model:.+}/config")
    @ResponseBody
    public EnvelopeResponse<String> updateModelConfig(@PathVariable("model") String modelId,
            @RequestBody ModelConfigRequest request) {
        checkProjectName(request.getProject());
        modelService.updateModelConfig(request.getProject(), modelId, request);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "checkBeforeModelSave", tags = { "AI" })
    @PostMapping(value = "/model_save/check")
    @ResponseBody
    public EnvelopeResponse<ModelSaveCheckResponse> checkBeforeModelSave(@RequestBody ModelRequest modelRequest) {
        checkProjectName(modelRequest.getProject());
        ModelSaveCheckResponse response = modelService.checkBeforeModelSave(modelRequest);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "updateModelOwner", tags = { "AI" })
    @PutMapping(value = "/{model:.+}/owner")
    @ResponseBody
    public EnvelopeResponse<String> updateModelOwner(@PathVariable("model") String modelId,
            @RequestBody OwnerChangeRequest request) {
        checkProjectName(request.getProject());
        checkRequiredArg("owner", request.getOwner());
        fusionModelService.updateModelOwner(request.getProject(), modelId, request);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "validate tds export", tags = { "QE" })
    @GetMapping(value = "/validate_export")
    @ResponseBody
    public EnvelopeResponse<Boolean> validateExport(@RequestParam(value = "model") String modelId,
            @RequestParam(value = "project") String project,
            @RequestParam(value = "element", required = false, defaultValue = "AGG_INDEX_COL") SyncContext.ModelElement element) {
        String projectName = checkProjectName(project);
        SyncContext virtualContext = tdsService.prepareSyncContext(projectName, modelId, null, element, "", -1);
        SyncModel syncModel = tdsService.exportModel(virtualContext);
        Boolean result = tdsService.preCheckNameConflict(syncModel);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, result, "");
    }

    @ApiOperation(value = "export model", tags = { "QE" }, notes = "Add URL: {model}")
    @GetMapping(value = "/{model:.+}/export")
    @ResponseBody
    public void exportModel(@PathVariable("model") String modelId, @RequestParam(value = "project") String project,
            @RequestParam(value = "export_as") SyncContext.BI exportAs,
            @RequestParam(value = "element", required = false, defaultValue = "AGG_INDEX_COL") SyncContext.ModelElement element,
            @RequestParam(value = "server_host", required = false) String serverHost,
            @RequestParam(value = "server_port", required = false) Integer serverPort, HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        String projectName = checkProjectName(project);
        String host = getHost(serverHost, request.getServerName());
        int port = getPort(serverPort, request.getServerPort());

        SyncContext syncContext = tdsService.prepareSyncContext(projectName, modelId, exportAs, element, host, port);
        SyncModel syncModel = tdsService.exportModel(syncContext);
        tdsService.dumpSyncModel(syncContext, syncModel, response);
    }

    @ApiOperation(value = "updateMultiPartitionMapping", tags = { "QE" }, notes = "Add URL: {model}")
    @PutMapping(value = "/{model:.+}/multi_partition/mapping")
    @ResponseBody
    public EnvelopeResponse<String> updateMultiPartitionMapping(@PathVariable("model") String modelId,
            @RequestBody MultiPartitionMappingRequest mappingRequest) {
        checkProjectName(mappingRequest.getProject());
        modelService.updateMultiPartitionMapping(mappingRequest.getProject(), modelId, mappingRequest);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "getMultiPartitionValues", tags = { "DW" }, notes = "Add URL: {model}")
    @GetMapping(value = "/{model:.+}/multi_partition/sub_partition_values")
    @ResponseBody
    public EnvelopeResponse<List<MultiPartitionValueResponse>> getMultiPartitionValues(
            @PathVariable("model") String modelId, @RequestParam("project") String project) {
        checkProjectName(project);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS,
                modelService.getMultiPartitionValues(project, modelId), "");
    }

    @ApiOperation(value = "addMultiPartitionValues", tags = { "DW" }, notes = "Add URL: {model}")
    @PostMapping(value = "/{model:.+}/multi_partition/sub_partition_values")
    @ResponseBody
    public EnvelopeResponse<String> addMultiPartitionValues(@PathVariable("model") String modelId,
            @RequestBody UpdateMultiPartitionValueRequest request) {
        checkProjectName(request.getProject());
        checkRequiredArg("sub_partition_values", request.getSubPartitionValues());
        modelService.addMultiPartitionValues(request.getProject(), modelId, request.getSubPartitionValues());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "deleteMultiPartitionValues", tags = { "DW" }, notes = "Add URL: {model}")
    @DeleteMapping(value = "/{model:.+}/multi_partition/sub_partition_values")
    @ResponseBody
    public EnvelopeResponse<String> deleteMultiPartitionValues(@PathVariable("model") String modelId,
            @RequestParam("project") String project, @RequestParam(value = "ids") Long[] ids) {
        checkProjectName(project);
        modelService.deletePartitions(project, null, modelId, Sets.newHashSet(ids));
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "optimizeLayoutData", tags = { "DW" }, notes = "Add URL: {model}")
    @PostMapping(value = "{model:.+}/optimize_layout_data")
    @ResponseBody
    public EnvelopeResponse<JobInfoResponse> optimizeModelIndexData(@PathVariable("model") String modelId,
            @RequestBody OptimizeLayoutDataRequest optimizeRequests) throws Exception {
        checkProjectName(optimizeRequests.getProject());
        JobInfoResponse response = modelService.optimizeLayoutData(optimizeRequests.getProject(), modelId,
                optimizeRequests);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    @ApiOperation(value = "getOptimizeLayoutDataConfigTemplate")
    @GetMapping(value = "/optimize_layout_data_template")
    @ResponseBody
    public EnvelopeResponse<String> getOptimizeLayoutDataConfigTemplate(HttpServletResponse response)
            throws IOException {
        String templateString = JsonUtil.writeValueAsStringWithPretty(OptimizeLayoutDataRequest.template);
        InputStream in = IOUtils.toInputStream(templateString, "UTF-8");
        setDownloadResponse(in, "optimize_layout_data_template.json", MediaType.APPLICATION_OCTET_STREAM_VALUE,
                response);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "setModelStorageType", tags = { "DW" }, notes = "Add URL: {model}")
    @PutMapping(value = "{model:.+}/storage_type")
    @ResponseBody
    public EnvelopeResponse<String> setModelStorageType(@PathVariable("model") String modelId,
            @RequestBody UpdateModelStorageTypeRequest request) {
        checkProjectName(request.getProject());
        modelService.setStorageType(request.getProject(), modelId, request.getStorageType());
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, "", "");
    }

    @ApiOperation(value = "getLayoutDetail")
    @GetMapping(value = "/{project}/{model}/{layoutId}/layout_detail")
    @ResponseBody
    public EnvelopeResponse<NDataLayoutDetails> getLayoutDetail(@PathVariable("project") String project,
            @PathVariable("model") String modelId, @PathVariable("layoutId") Long layoutId) {
        checkProjectName(project);
        NDataLayoutDetails details = modelService.getLayoutDetail(project, modelId, layoutId);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, details, "");
    }
}
