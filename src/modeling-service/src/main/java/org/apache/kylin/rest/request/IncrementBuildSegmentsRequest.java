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

import java.util.List;
import java.util.Set;

import org.apache.kylin.job.dao.ExecutablePO;
import org.apache.kylin.metadata.insensitive.ProjectInsensitiveRequest;
import org.apache.kylin.metadata.model.MultiPartitionDesc;
import org.apache.kylin.metadata.model.PartitionDesc;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class IncrementBuildSegmentsRequest implements ProjectInsensitiveRequest {

    private String project;

    private String start;

    private String end;

    @JsonProperty("partition_desc")
    private PartitionDesc partitionDesc;

    @JsonProperty("segment_holes")
    private List<SegmentTimeRequest> segmentHoles;

    @JsonProperty("build_all_indexes")
    private boolean buildAllIndexes = true;

    @JsonProperty("ignored_snapshot_tables")
    private Set<String> ignoredSnapshotTables;

    @JsonProperty("sub_partition_values")
    private List<String[]> subPartitionValues;

    @JsonProperty("multi_partition_desc")
    private MultiPartitionDesc multiPartitionDesc;

    private int priority = ExecutablePO.DEFAULT_PRIORITY;

    @JsonProperty("build_all_sub_partitions")
    private boolean buildAllSubPartitions = false;

    @JsonProperty("yarn_queue")
    private String yarnQueue;

    @JsonProperty("tag")
    private Object tag;

    @JsonProperty("auto_index_plan_enable")
    private boolean autoIndexPlanEnable = false;

    @JsonProperty("instant_init_index_num")
    private Integer instantInitIndexNum;

}
