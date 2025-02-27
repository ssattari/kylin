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

package org.apache.kylin.rest.request;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.metadata.cube.model.IndexEntity;
import org.apache.kylin.metadata.cube.model.IndexPlan;
import org.apache.kylin.metadata.insensitive.ModelInsensitiveRequest;
import org.apache.kylin.metadata.model.ColumnDesc;
import org.apache.kylin.metadata.model.ComputedColumnDesc;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.model.TableDesc;
import org.apache.kylin.metadata.model.TableRef;
import org.apache.kylin.metadata.model.util.scd2.SimplifiedJoinTableDesc;
import org.apache.kylin.rest.response.LayoutRecDetailResponse;
import org.apache.kylin.rest.response.SimplifiedMeasure;
import org.apache.kylin.rest.util.SCD2SimplificationConvertUtil;

import com.fasterxml.jackson.annotation.JsonGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@EqualsAndHashCode(callSuper = false)
public class ModelRequest extends NDataModel implements ModelInsensitiveRequest {

    @JsonProperty("project")
    private String project;

    @JsonProperty("start")
    private String start;

    @JsonProperty("end")
    private String end;

    @JsonProperty("simplified_measures")
    private List<SimplifiedMeasure> simplifiedMeasures = Lists.newArrayList();

    @JsonProperty("simplified_dimensions")
    private List<NamedColumn> simplifiedDimensions = Lists.newArrayList();

    // non-dimension columns, used for sync column alias
    // if not present, use original column name
    @JsonProperty("other_columns")
    private List<NamedColumn> otherColumns = Lists.newArrayList();

    @JsonProperty("rec_items")
    private List<LayoutRecDetailResponse> recItems = Lists.newArrayList();

    @JsonProperty("index_plan")
    private IndexPlan indexPlan;

    @JsonProperty("save_only")
    private boolean saveOnly = false;

    @JsonProperty("with_rec_job")
    private boolean withRecJob = false;

    @JsonProperty("with_segment")
    private boolean withEmptySegment = true;

    @JsonProperty("with_model_online")
    private boolean withModelOnline = false;

    @JsonProperty("with_base_index")
    private boolean withBaseIndex = false;

    @JsonProperty("base_index_type")
    private Set<IndexEntity.Source> baseIndexType;

    @JsonProperty("computed_column_name_auto_adjust")
    private boolean computedColumnNameAutoAdjust = false;

    private List<SimplifiedJoinTableDesc> simplifiedJoinTableDescs;

    @EqualsAndHashCode.Include
    @JsonGetter("computed_columns")
    @JsonInclude(JsonInclude.Include.NON_NULL) // output to frontend
    public List<ComputedColumnDesc> getComputedColumnDescs() {
        return this.computedColumnDescs;
    }

    @JsonSetter("computed_columns")
    public void setComputedColumnDescs(List<ComputedColumnDesc> computedColumnDescs) {
        this.computedColumnDescs = computedColumnDescs;
    }

    @JsonProperty("join_tables")
    public void setSimplifiedJoinTableDescs(List<SimplifiedJoinTableDesc> simplifiedJoinTableDescs) {
        this.simplifiedJoinTableDescs = simplifiedJoinTableDescs;
        this.setJoinTables(SCD2SimplificationConvertUtil.convertSimplified2JoinTables(simplifiedJoinTableDescs));
    }

    @JsonProperty("join_tables")
    public List<SimplifiedJoinTableDesc> getSimplifiedJoinTableDescs() {
        return simplifiedJoinTableDescs;
    }

    @JsonSetter("dimensions")
    public void setDimensions(List<NamedColumn> dimensions) {
        setSimplifiedDimensions(dimensions);
    }

    @JsonSetter("all_measures")
    public void setMeasures(List<Measure> inputMeasures) {
        List<Measure> measures = inputMeasures != null ? inputMeasures : Lists.newArrayList();
        List<SimplifiedMeasure> simpleMeasureList = Lists.newArrayList();
        for (NDataModel.Measure measure : measures) {
            SimplifiedMeasure simplifiedMeasure = SimplifiedMeasure.fromMeasure(measure);
            simpleMeasureList.add(simplifiedMeasure);
        }
        setAllMeasures(measures);
        setSimplifiedMeasures(simpleMeasureList);
    }

    private transient BiFunction<TableDesc, Boolean, Collection<ColumnDesc>> columnsFetcher = TableRef::filterColumns;

    public BiFunction<TableDesc, Boolean, Collection<ColumnDesc>> getColumnsFetcher() {
        return columnsFetcher != null ? columnsFetcher : TableRef::filterColumns;
    }

    public ModelRequest() {
        super();
    }

    public ModelRequest(NDataModel dataModel) {
        super(dataModel);
        this.setSimplifiedJoinTableDescs(
                SCD2SimplificationConvertUtil.simplifiedJoinTablesConvert(dataModel.getJoinTables()));
    }

    private String[] toUpperCase(String[] arr) {
        if (ArrayUtils.isEmpty(arr)) {
            return arr;
        }
        return Arrays.stream(arr).map(StringUtils::toRootUpperCase).toArray(String[]::new);
    }

    public void toUpperCaseModelRequest() {
        this.setRootFactTableName(StringUtils.toRootUpperCase(this.getRootFactTableName()));
        this.getSimplifiedDimensions()
                .forEach(dim -> dim.setAliasDotColumn(StringUtils.toRootUpperCase(dim.getAliasDotColumn())));
        if (CollectionUtils.isEmpty(this.getJoinTables())) {
            return;
        }
        this.getJoinTables().forEach(join -> {
            join.setTable(StringUtils.toRootUpperCase(join.getTable()));
            join.getJoin().setForeignKey(toUpperCase(join.getJoin().getForeignKey()));
            join.getJoin().setPrimaryKey(toUpperCase(join.getJoin().getPrimaryKey()));
        });
        this.getSimplifiedJoinTableDescs().forEach(join -> {
            join.setTable(StringUtils.toRootUpperCase(join.getTable()));
            join.getSimplifiedJoinDesc().setForeignKey(toUpperCase(join.getSimplifiedJoinDesc().getForeignKey()));
            join.getSimplifiedJoinDesc().setPrimaryKey(toUpperCase(join.getSimplifiedJoinDesc().getPrimaryKey()));
            if (CollectionUtils.isNotEmpty(join.getSimplifiedJoinDesc().getSimplifiedNonEquiJoinConditions())) {
                join.getSimplifiedJoinDesc().getSimplifiedNonEquiJoinConditions().forEach(nonEqual -> {
                    nonEqual.setForeignKey(StringUtils.toRootUpperCase(nonEqual.getForeignKey()));
                    nonEqual.setPrimaryKey(StringUtils.toRootUpperCase(nonEqual.getPrimaryKey()));
                });
            }
        });
    }

}
