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

package org.apache.kylin.rest.response;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.apache.kylin.common.NativeQueryRealization;
import org.apache.kylin.common.QueryContext;
import org.apache.kylin.common.debug.BackdoorToggles;
import org.apache.kylin.guava30.shaded.common.base.Preconditions;
import org.apache.kylin.guava30.shaded.common.collect.ImmutableList;
import org.apache.kylin.metadata.querymeta.SelectedColumnMeta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class SQLResponse implements Serializable {
    protected static final long serialVersionUID = 1L;

    private static final Logger logger = LoggerFactory.getLogger(SQLResponse.class);

    // the data type for each column
    private List<SelectedColumnMeta> columnMetas;

    // the result rows, each row contains several columns
    private Iterable<List<String>> results;

    @JsonProperty("query_plan")
    private QueryContext.QueryPlan queryPlan;
    @JsonProperty("is_explain")
    private boolean isExplain;

    // if not select query, only return affected row count
    protected int affectedRowCount;

    // queryTagInfo indicating whether an exception occurred
    @JsonProperty("isException")
    protected boolean isException;

    // if isException, the detailed exception message
    protected String exceptionMessage;

    // if isException, the related Exception
    protected Throwable throwable;

    protected long duration;

    @JsonProperty("cpu_time")
    protected long cpuTime;

    protected boolean isPartial = false;

    @JsonProperty("vacant")
    private boolean isVacant;

    private List<Long> scanRows;

    private List<Long> scanBytes;

    private String appMasterURL = "";

    protected int failTimes = -1;

    @JsonProperty("appMasterURL")
    public String getAppMasterURL() {
        if (storageCacheUsed) {
            return "";
        } else {
            return appMasterURL;
        }
    }

    protected long resultRowCount;

    protected int shufflePartitions;

    protected boolean hitExceptionCache = false;

    protected boolean storageCacheUsed = false;

    protected String storageCacheType;

    /**
     * The data fetching time of the datasets that contribute to the query result. The time stands for the data
     * freshness of the query result.
     * <ul>
     *     <li>If 'dataFetchTime' = now(), meaning the query gets most fresh data from source, typically executed
     *         by a pushdown engine, and bypasses all caches along the way, including query cache, cube segment cache,
     *         soft affinity local cache etc..</li>
     *     <li>Or otherwise, 'dataFetchTime' should be set to the cache build time, the time when the data is last
     *         fetched from source and the cache is built.</li>
     *     <li>If multiple datasets and multiple data caches are involved in a query, the LATEST data fetch time
     *         among them is returned.</li>
     * </ul>
     */
    @JsonProperty("dataFetchTime")
    protected long dataFetchTime;

    @JsonProperty("pushDown")
    protected boolean queryPushDown = false;

    @JsonProperty("is_prepare")
    private boolean isPrepare = false;

    @JsonProperty("is_timeout")
    private boolean isTimeout;

    @JsonProperty("is_refused")
    private boolean isRefused;

    protected byte[] queryStatistics;

    protected String queryId;

    private String server;

    @JsonProperty("is_stop_by_user")
    private boolean isStopByUser;

    @Setter
    @Getter
    private String signature;

    @JsonProperty("realizations")
    private List<NativeQueryRealization> nativeRealizations;

    private String engineType;

    private List<SQLResponseTrace> traces;

    @JsonProperty("executed_plan")
    private String executedPlan;

    private boolean isBigQuery = false;

    public SQLResponse() {
        this(new LinkedList<>(), new LinkedList<>(), 0, false, null);
    }

    public SQLResponse(List<SelectedColumnMeta> columnMetas, List<List<String>> results, int affectedRowCount,
            boolean isException, String exceptionMessage) {
        this.columnMetas = columnMetas;
        this.results = results;
        this.affectedRowCount = affectedRowCount;
        this.isException = isException;
        this.exceptionMessage = exceptionMessage;
        if (results != null) {
            this.resultRowCount = results.size();
        }
    }

    public SQLResponse(List<SelectedColumnMeta> columnMetas, List<List<String>> results, int affectedRowCount,
            boolean isException, String exceptionMessage, boolean isPartial, boolean isPushDown) {
        this.columnMetas = columnMetas;
        this.results = results;
        this.affectedRowCount = affectedRowCount;
        this.isException = isException;
        this.exceptionMessage = exceptionMessage;
        this.isPartial = isPartial;
        this.queryPushDown = isPushDown;
        this.isPrepare = BackdoorToggles.getPrepareOnly();
        if (results != null) {
            this.resultRowCount = results.size();
        }
    }

    public SQLResponse(List<SelectedColumnMeta> columnMetas, Iterable<List<String>> results, int resultSize,
            int affectedRowCount, boolean isException, String exceptionMessage, boolean isPartial, boolean isPushDown) {
        this.columnMetas = columnMetas;
        this.results = results;
        this.affectedRowCount = affectedRowCount;
        this.isException = isException;
        this.exceptionMessage = exceptionMessage;
        this.isPartial = isPartial;
        this.queryPushDown = isPushDown;
        this.isPrepare = BackdoorToggles.getPrepareOnly();
        if (results != null) {
            this.resultRowCount = resultSize;
        }
    }

    @JsonIgnore
    public Throwable getThrowable() {
        return throwable;
    }

    public SQLResponse wrapResultOfQueryContext(QueryContext queryContext) {
        Preconditions.checkNotNull(queryContext, "queryContext is null");
        this.setQueryId(queryContext.getQueryId());
        this.setScanRows(queryContext.getMetrics().getScanRows());
        this.setScanBytes(queryContext.getMetrics().getScanBytes());
        this.setShufflePartitions(queryContext.getShufflePartitions());
        return this;
    }

    public long getTotalScanRows() {
        return QueryContext.calValueWithDefault(scanRows);
    }

    public long getTotalScanBytes() {
        return QueryContext.calValueWithDefault(scanBytes);
    }

    /**
     * read all rows from results iterator and replace results iterable with all rows read
     * this is needed when the result needs to be both saved in cache and returned to user
     */
    public void readAllRows() {
        if (!(results instanceof Collection)) {
            results = ImmutableList.copyOf(results);
        }
    }

    public void updateDataFetchTime(QueryContext queryContext) {
        // The dataFetchTime could come from:
        // 1) from queryContext.getMetrics().getDataFetchTime(), in case of push-down,
        //    this is the time from local file cache (KylinCacheFileSystem)
        // 2) from this.nativeRealizations, in case of index query, this is the time when segment is built
        //    We take the max of the above all.

        dataFetchTime = queryContext.getMetrics().getDataFetchTime();

        if (nativeRealizations != null) {
            for (NativeQueryRealization real : nativeRealizations) {
                dataFetchTime = Math.max(dataFetchTime, real.getLastDataRefreshTime());
            }
        }
    }

    public void addNativeRealizationIfNotExist(String modelId) {
        if (nativeRealizations == null)
            nativeRealizations = new ArrayList<>();

        if (nativeRealizations.stream().anyMatch(real -> real.getModelId().equals(modelId)))
            return;

        nativeRealizations.add(new NativeQueryRealization(modelId, null, null));
    }
}
