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
import java.util.Map;

import org.apache.kylin.guava30.shaded.common.collect.Lists;
import org.apache.kylin.guava30.shaded.common.collect.Maps;
import org.apache.kylin.metadata.insensitive.ProjectInsensitiveRequest;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Data;

@Data
public class OptRecRequest implements ProjectInsensitiveRequest {

    @JsonProperty("project")
    private String project;

    @JsonProperty("model_id")
    @JsonAlias("modelId")
    private String modelId;

    @JsonProperty("recs_to_remove_layout")
    private List<Integer> recItemsToRemoveLayout = Lists.newArrayList();

    @JsonProperty("recs_to_add_layout")
    private List<Integer> recItemsToAddLayout = Lists.newArrayList();

    @JsonProperty("names")
    private Map<Integer, String> names = Maps.newHashMap();

    @JsonProperty("job_id")
    private String jobId;

    @JsonProperty("token")
    private String token;

}
