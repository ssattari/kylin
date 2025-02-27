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

import static org.apache.kylin.common.exception.ServerErrorCode.CONFIG_NONEXIST_MODEL;
import static org.apache.kylin.common.exception.ServerErrorCode.DIAG_FAILED;
import static org.apache.kylin.common.exception.ServerErrorCode.DIAG_UUID_NOT_EXIST;
import static org.apache.kylin.common.exception.ServerErrorCode.FILE_NOT_EXIST;
import static org.apache.kylin.tool.constant.DiagTypeEnum.FULL;
import static org.apache.kylin.tool.constant.DiagTypeEnum.JOB;
import static org.apache.kylin.tool.constant.DiagTypeEnum.QUERY;
import static org.apache.kylin.tool.constant.StageEnum.DONE;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import javax.validation.constraints.NotNull;

import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.common.KylinConfig;
import org.apache.kylin.common.KylinConfigBase;
import org.apache.kylin.common.exception.KylinException;
import org.apache.kylin.common.exception.KylinTimeoutException;
import org.apache.kylin.common.msg.MsgPicker;
import org.apache.kylin.common.persistence.transaction.MessageSynchronization;
import org.apache.kylin.common.scheduler.EventBusFactory;
import org.apache.kylin.common.util.BufferedLogger;
import org.apache.kylin.common.util.CliCommandExecutor;
import org.apache.kylin.common.util.StringHelper;
import org.apache.kylin.guava30.shaded.common.cache.Cache;
import org.apache.kylin.guava30.shaded.common.cache.CacheBuilder;
import org.apache.kylin.helper.MetadataToolHelper;
import org.apache.kylin.helper.RoutineToolHelper;
import org.apache.kylin.job.dao.ExecutablePO;
import org.apache.kylin.metadata.cube.model.NIndexPlanManager;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.NDataModelManager;
import org.apache.kylin.metadata.project.NProjectManager;
import org.apache.kylin.metadata.project.ProjectInstance;
import org.apache.kylin.query.plugin.diagnose.DiagnoseHelper;
import org.apache.kylin.rest.constant.Constant;
import org.apache.kylin.rest.request.BackupRequest;
import org.apache.kylin.rest.request.DiagProgressRequest;
import org.apache.kylin.rest.response.DiagStatusResponse;
import org.apache.kylin.rest.response.EnvelopeResponse;
import org.apache.kylin.rest.util.AclEvaluate;
import org.apache.kylin.tool.DiagK8sTool;
import org.apache.kylin.tool.JobDiagInfoTool;
import org.apache.kylin.tool.constant.DiagTypeEnum;
import org.apache.kylin.tool.constant.StageEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.val;

@Service("systemService")
public class SystemService extends BasicService {

    private static final Logger logger = LoggerFactory.getLogger(SystemService.class);

    private final MetadataToolHelper helper = new MetadataToolHelper();
    @Autowired
    private AclEvaluate aclEvaluate;

    private static final String MODEL_CONFIG_BLOCK_LIST = "kylin.index.rule-scheduler-data";

    @Data
    @NoArgsConstructor
    public static class DiagInfo {
        private final long startTime = System.currentTimeMillis();
        private String stage = StageEnum.PREPARE.toString();
        private float progress = 0.0f;
        private File exportFile;
        private Future task;
        private DiagTypeEnum diagType;
        private long updateTime;

        public DiagInfo(File exportFile, Future task, DiagTypeEnum diagType) {
            this.exportFile = exportFile;
            this.task = task;
            this.diagType = diagType;
        }
    }

    private final Cache<String, DiagInfo> diagMap = CacheBuilder.newBuilder().expireAfterAccess(1, TimeUnit.DAYS)
            .build();
    private final Cache<String, DiagStatusResponse> exceptionMap = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS).build();
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN + " or hasPermission(#backupRequest.getProject(), 'ADMINISTRATION')")
    public void backup(BackupRequest backupRequest) throws Exception {
        String project = StringUtils.isNotBlank(backupRequest.getProject()) ? backupRequest.getProject() : null;
        String path = StringUtils.isNotBlank(backupRequest.getBackupPath()) ? backupRequest.getBackupPath() : null;
        boolean compress = backupRequest.isCompress();
        helper.backup(getConfig(), project, path, null, compress, false);
    }

    public String dumpLocalDiagPackage(String startTime, String endTime, String jobId, String queryId, String project,
            HttpHeaders headers) {
        File exportFile = KylinConfigBase.getDiagFileName();
        String uuid = exportFile.getName();
        FileUtils.deleteQuietly(exportFile);
        exportFile.mkdirs();

        DiagTypeEnum diagPackageType;
        String[] arguments;
        // full
        if (StringUtils.isEmpty(jobId) && StringUtils.isEmpty(queryId)) {
            // Sparder executor gc log should be collected before FULL and QUERY diag package
            DiagnoseHelper.collectSparderExecutorGc();
            if (startTime == null && endTime == null) {
                startTime = Long.toString(System.currentTimeMillis() - 259200000L);
                endTime = Long.toString(System.currentTimeMillis());
            }
            arguments = new String[] { "-destDir", exportFile.getAbsolutePath(), "-startTime", startTime, "-endTime",
                    endTime, "-diagId", uuid };
            diagPackageType = FULL;
        } else if (StringUtils.isEmpty(queryId)) {//job
            String jobOpt = "-job";
            if (StringUtils.endsWithAny(jobId, new String[] { "_build", "_merge" })) {
                jobOpt = "-streamingJob";
            }
            arguments = new String[] { jobOpt, jobId, "-destDir", exportFile.getAbsolutePath(), "-diagId", uuid };
            diagPackageType = JOB;
        } else { //query
            // Sparder executor gc log should be collected before FULL and QUERY diag package
            DiagnoseHelper.collectSparderExecutorGc();
            arguments = new String[] { "-project", project, "-query", queryId, "-destDir", exportFile.getAbsolutePath(),
                    "-diagId", uuid };
            diagPackageType = QUERY;
        }
        Future<?> task = executorService.submit(() -> {
            try {
                exceptionMap.invalidate(uuid);
                if (!Arrays.stream(arguments).allMatch(StringHelper::validateShellArgument)) {
                    throw new IllegalArgumentException("Shell args have illegal char: " + Arrays.toString(arguments));
                }
                if (KylinConfig.getInstanceFromEnv().getMicroServiceMode() == null) {
                    CliCommandExecutor commandExecutor = new CliCommandExecutor();
                    val patternedLogger = new BufferedLogger(logger);
                    String finalCommand = String.format(Locale.ROOT, "%s/bin/diag.sh %s", KylinConfig.getKylinHome(),
                            StringUtils.join(arguments, " "));
                    commandExecutor.execute(finalCommand, patternedLogger, uuid);
                } else {
                    val packageType = diagPackageType.toString().toLowerCase(Locale.ROOT);
                    new DiagK8sTool(headers, packageType).execute(arguments);
                }
                DiagInfo diagInfo = diagMap.getIfPresent(uuid);
                if (Objects.isNull(diagInfo) || !"DONE".equals(diagInfo.getStage())) {
                    throw new KylinException(DIAG_FAILED, MsgPicker.getMsg().getDiagFailed());
                }
            } catch (Exception ex) {
                handleDiagException(uuid, ex);
            }
        });
        diagMap.put(uuid, new DiagInfo(exportFile, task, diagPackageType));
        return uuid;
    }

    public String dumpLocalQueryDiagPackage(String queryId, String project, HttpHeaders headers) {
        aclEvaluate.checkProjectQueryPermission(project);
        return dumpLocalDiagPackage(null, null, null, queryId, project, headers);
    }

    public String dumpLocalDiagPackage(String startTime, String endTime, String jobId, String project,
            HttpHeaders headers) {
        if (StringUtils.isEmpty(jobId)) {
            aclEvaluate.checkIsGlobalAdmin();
        } else {
            if (StringUtils.isEmpty(project)) {
                project = getProjectByJobId(jobId);
            }
            checkDiagPermission(project);
        }
        return dumpLocalDiagPackage(startTime, endTime, jobId, null, null, headers);
    }

    private String getProjectByJobId(String jobId) {
        val projects = NProjectManager.getInstance(KylinConfig.getInstanceFromEnv()).listAllProjects().stream()
                .map(ProjectInstance::getName).collect(Collectors.toList());
        JobDiagInfoTool jobDiagInfoTool = new JobDiagInfoTool();
        for (String project : projects) {
            ExecutablePO job = jobDiagInfoTool.getJobByJobId(jobId);
            if (job != null) {
                return project;
            }
        }
        return null;
    }

    private void handleDiagException(String uuid, @NotNull Exception ex) {
        logger.warn("Diagnostic kit error", ex);
        Throwable cause = ex;
        while (cause != null && cause.getCause() != null) {
            cause = cause.getCause();
        }
        DiagStatusResponse response = new DiagStatusResponse();
        if (cause instanceof KylinTimeoutException) {
            response.setStatus("001");
        } else if (cause instanceof IOException || cause instanceof AccessDeniedException) {
            response.setStatus("002");
        } else {
            response.setStatus("999");
        }
        response.setError(cause == null ? ex.getMessage() : cause.getMessage());
        DiagInfo diagInfo = diagMap.getIfPresent(uuid);
        if (diagInfo != null) {
            response.setDuration(System.currentTimeMillis() - diagInfo.getStartTime());
        }
        exceptionMap.put(uuid, response);
        FileUtils.deleteQuietly(diagInfo == null ? null : diagInfo.getExportFile());
        diagMap.invalidate(uuid);
    }

    public String getDiagPackagePath(String uuid, String project) {
        DiagStatusResponse exception = exceptionMap.getIfPresent(uuid);
        if (exception != null) {
            throw new RuntimeException(exception.getError());
        }
        DiagInfo diagInfo = diagMap.getIfPresent(uuid);
        if (diagInfo != null && !"DONE".equals(diagInfo.getStage())) {
            throw new RuntimeException("Diagnostic task is running now , can not download yet");
        }
        File exportFile = diagInfo == null ? null : diagInfo.getExportFile();
        if (exportFile == null) {
            throw new KylinException(DIAG_UUID_NOT_EXIST,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getInvalidId(), uuid));
        }
        if (QUERY != diagInfo.getDiagType()
                || !KylinConfig.getInstanceFromEnv().isAllowedNonAdminGenerateQueryDiagPackage()) {
            checkDiagPermission(project);
        }
        String zipFilePath = findZipFile(exportFile);
        if (zipFilePath == null) {
            throw new KylinException(FILE_NOT_EXIST, String.format(Locale.ROOT,
                    MsgPicker.getMsg().getDiagPackageNotAvailable(), exportFile.getAbsoluteFile()));
        }
        return zipFilePath;
    }

    private String findZipFile(File rootDir) {
        if (rootDir == null)
            return null;
        File[] files = rootDir.listFiles();
        if (files == null)
            return null;
        for (File subFile : files) {
            if (subFile.isDirectory()) {
                String zipFilePath = findZipFile(subFile);
                if (zipFilePath != null)
                    return zipFilePath;
            } else {
                if (subFile.getName().endsWith(".zip")) {
                    return subFile.getAbsolutePath();
                }
            }
        }
        return null;
    }

    public EnvelopeResponse<DiagStatusResponse> getExtractorStatus(String uuid, String project) {
        DiagStatusResponse exception = exceptionMap.getIfPresent(uuid);
        if (exception != null) {
            exception.setUuid(uuid);
            return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, exception, "");
        }
        DiagInfo diagInfo = diagMap.getIfPresent(uuid);
        if (Objects.isNull(diagInfo)) {
            throw new KylinException(DIAG_UUID_NOT_EXIST,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getInvalidId(), uuid));
        }
        if (QUERY != diagInfo.getDiagType()
                || !KylinConfig.getInstanceFromEnv().isAllowedNonAdminGenerateQueryDiagPackage()) {
            checkDiagPermission(project);
        }
        DiagStatusResponse response = new DiagStatusResponse();
        response.setUuid(uuid);
        response.setStatus("000");
        response.setStage(diagInfo.getStage());
        response.setProgress(diagInfo.getProgress());
        long endTime = System.currentTimeMillis();
        if (DONE.toString().equals(diagInfo.getStage())) {
            endTime = diagInfo.getUpdateTime();
        }
        response.setDuration(endTime - diagInfo.startTime);
        return new EnvelopeResponse<>(KylinException.CODE_SUCCESS, response, "");
    }

    private void checkDiagPermission(String project) {
        if (StringUtils.isEmpty(project)) {
            aclEvaluate.checkIsGlobalAdmin();
        } else {
            aclEvaluate.checkProjectAdminPermission(project);
        }
    }

    public void updateDiagProgress(DiagProgressRequest diagProgressRequest) {
        DiagInfo diagInfo = diagMap.getIfPresent(diagProgressRequest.getDiagId());
        if (Objects.isNull(diagInfo)) {
            throw new KylinException(DIAG_UUID_NOT_EXIST,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getInvalidId(), diagProgressRequest.getDiagId()));
        }
        diagInfo.setStage(diagProgressRequest.getStage());
        diagInfo.setProgress(diagProgressRequest.getProgress());
        diagInfo.setUpdateTime(diagProgressRequest.getUpdateTime());
    }

    public void stopDiagTask(String uuid) {
        logger.debug("Stop diagnostic package task {}", uuid);
        DiagInfo diagInfo = diagMap.getIfPresent(uuid);
        if (diagInfo == null) {
            throw new KylinException(DIAG_UUID_NOT_EXIST,
                    String.format(Locale.ROOT, MsgPicker.getMsg().getInvalidId(), uuid));
        }
        if (QUERY != diagInfo.getDiagType()
                || !KylinConfig.getInstanceFromEnv().isAllowedNonAdminGenerateQueryDiagPackage()) {
            aclEvaluate.checkIsGlobalAdmin();
        }
        EventBusFactory.getInstance().postSync(new CliCommandExecutor.JobKilled(uuid));
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN)
    public void reloadMetadata() throws IOException {
        MessageSynchronization messageSynchronization = MessageSynchronization
                .getInstance(KylinConfig.getInstanceFromEnv());
        messageSynchronization.replayAllMetadata(true);
    }

    @PreAuthorize(Constant.ACCESS_HAS_ROLE_ADMIN)
    public Map<String, String> getReadOnlyConfig(String projectName, String modelAlias) {
        TreeMap<String, String> result = new TreeMap<>();
        if (StringUtils.isBlank(projectName)) {
            if (StringUtils.isNotBlank(modelAlias)) {
                throw new KylinException(CONFIG_NONEXIST_MODEL,
                        String.format(Locale.ROOT, MsgPicker.getMsg().getLackProject(), projectName), false);
            }
            result.putAll(getConfig().getReadonlyProperties());
        } else if (StringUtils.isBlank(modelAlias)) {
            // Project Level Config
            val project = getManager(NProjectManager.class).getProject(projectName);
            if (project == null) {
                throw new KylinException(CONFIG_NONEXIST_MODEL,
                        String.format(Locale.ROOT, MsgPicker.getMsg().getNonExistProject(), projectName), false);
            }
            result.putAll(project.getConfig().getReadonlyProperties());
        } else {
            // Model Level Config
            NDataModel model = getManager(NDataModelManager.class, projectName).getDataModelDescByAlias(modelAlias);
            if (model == null) {
                throw new KylinException(CONFIG_NONEXIST_MODEL,
                        String.format(Locale.ROOT, MsgPicker.getMsg().getNonExistedModel(), modelAlias), false);
            }
            val indexPlan = getManager(NIndexPlanManager.class, projectName).getIndexPlan(model.getId());
            if (indexPlan != null) {
                result.putAll(indexPlan.getOverrideProps());
                result.remove(MODEL_CONFIG_BLOCK_LIST);
            }
        }
        return result;
    }

    public void cleanSparderEventLog() {
        val config = KylinConfig.getInstanceFromEnv();
        if (config.isQueryNodeOnly()) {
            logger.info("Clean current sparder event log for RPC");
            RoutineToolHelper.cleanEventLog(RoutineToolHelper.CleanType.SPARDER, null);
        }
    }
}
