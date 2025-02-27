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

package org.apache.kylin.metadata.recommendation.entity;

import java.util.Map;

import org.apache.kylin.common.annotation.Clarification;
import org.apache.kylin.metadata.model.NDataModel;
import org.apache.kylin.metadata.recommendation.candidate.RawRecItem;

import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Clarification(priority = Clarification.Priority.MAJOR, msg = "Enterprise")
public class RecItemV2 {
    @JsonProperty("create_time")
    private long createTime;
    @JsonProperty("unique_content")
    private String uniqueContent;
    @JsonProperty("uuid")
    private String uuid;

    public int[] genDependIds(Map<String, RawRecItem> nonLayoutUniqueFlagRecMap, String content, NDataModel dataModel) {
        throw new UnsupportedOperationException("This method must be overridden.");
    }
}
