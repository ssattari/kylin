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
package org.apache.kylin.metadata.cube.optimization;

import java.io.Serializable;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.apache.kylin.common.util.TimeUtil;
import org.apache.kylin.metadata.favorite.FavoriteRule;
import org.apache.kylin.metadata.favorite.FavoriteRuleManager;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FrequencyMap implements Serializable {

    @JsonIgnore
    private NavigableMap<Long, Integer> dateFrequency = new TreeMap<>();

    @JsonAnySetter
    public void add(String key, Integer value) {
        dateFrequency.put(Long.parseLong(key), value);
    }

    @JsonAnyGetter
    public Map<Long, Integer> getMap() {
        return dateFrequency;
    }

    public void incFrequency(long time) {
        long date = getDateInMillis(time);
        Integer freq = dateFrequency.get(date);
        if (freq != null) {
            freq++;
            dateFrequency.put(date, freq);
        } else {
            dateFrequency.put(date, 1);
        }
    }

    public FrequencyMap merge(FrequencyMap other) {
        other.getDateFrequency().forEach((k, v) -> this.dateFrequency.merge(k, v, Integer::sum));
        return this;
    }

    @JsonIgnore
    public long getFrequency(String project) {
        FavoriteRuleManager ruleManager = FavoriteRuleManager.getInstance(project);
        Integer timeWindow = FavoriteRule.getTimeWindowLength(ruleManager.getValue(FavoriteRule.FREQUENCY_TIME_WINDOW));
        return getFrequency(timeWindow);
    }

    @JsonIgnore
    public int getFrequency(Integer timeWindowDays) {
        long frequencyInitialCollectDate = getDateBeforeFrequencyTimeWindow(timeWindowDays);
        return dateFrequency.subMap(frequencyInitialCollectDate, Long.MAX_VALUE).values().stream().reduce(Integer::sum)
                .orElse(0);
    }

    @JsonIgnore
    public boolean isLowFrequency(Integer timeWindow, Integer lowFrequencyThreshold) {
        int frequency = getFrequency(timeWindow);
        return frequency < lowFrequencyThreshold;
    }

    private long getDateBeforeFrequencyTimeWindow(Integer timeWindowDays) {
        return TimeUtil.minusDays(getDateInMillis(System.currentTimeMillis()), timeWindowDays);
    }

    private long getDateInMillis(final long queryTime) {
        return TimeUtil.getDayStart(queryTime);
    }
}
