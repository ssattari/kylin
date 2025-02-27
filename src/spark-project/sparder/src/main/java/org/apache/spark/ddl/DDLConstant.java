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

package org.apache.spark.ddl;

import java.util.regex.Pattern;

public class DDLConstant {
    public static final String LOGICAL_VIEW = "logic";
    public static final String REPLACE_LOGICAL_VIEW = "replaceLogicalView";
    public static final String CREATE_LOGICAL_VIEW = "createLogicalView";
    public static final String DROP_LOGICAL_VIEW = "dropLogicalView";
    public static final String HIVE_VIEW = "hive";
    public static final String NO_RESTRICT = "noRestrict";
    public static final Integer VIEW_RULE_PRIORITY = 1;
    public static final Integer SOURCE_TABLE_RULE_PRIORITY = 2;

    public static final Pattern LOGICAL_VIEW_DDL_CREATE_OR_REPLACE_SYNTAX = Pattern
            .compile("(create|replace)\\s+logical\\s+view\\s+", Pattern.CASE_INSENSITIVE);
    public static final String DDL_CREATE_LOGICAL_VIEW = "CREATE LOGICAL VIEW "; // keep the tail space

    private DDLConstant() {
    }
}
