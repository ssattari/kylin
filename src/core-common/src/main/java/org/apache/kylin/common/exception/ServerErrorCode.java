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
package org.apache.kylin.common.exception;

public enum ServerErrorCode implements ErrorCodeSupplier {
    // 10000XXX general
    EMPTY_ID("KE-010000001"), //
    INVALID_RANGE("KE-010000002"), //
    INVALID_PARAMETER("KE-010000003"), //
    INVALID_NAME("KE-010000004"), //
    EMPTY_PARAMETER("KE-010000005"), //
    UNAUTHORIZED_ENTITY("KE-010000006"), //
    FAILED_DETECT_DATA_RANGE("KE-010000007"), //
    ONGOING_OPTIMIZATION("KE-010000008"), //
    EVENT_HANDLE("KE-010000009"), //
    EXCEED_MAX_ALLOWED_PACKET("KE-010000010"),
    INVALID_DATE_FORMAT("KE-010000011"), //

    // 10001XXX project
    EMPTY_PROJECT_NAME("KE-010001002"), //
    INVALID_PROJECT_NAME("KE-010001003"), //
    DUPLICATE_PROJECT_NAME("KE-010001004"), //
    PROJECT_NAME_ILLEGAL("KE-010001005"), //
    FAILED_CREATE_PROJECT("KE-010001006"), //
    INCORRECT_PROJECT_MODE("KE-010001007"), //
    PROJECT_DROP_FAILED("KE-010001008"), //

    // 10002XXX model
    FAILED_UPDATE_MODEL("KE-010002005"), //
    FAILED_IMPORT_MODEL("KE-010002006"), //
    EMPTY_MODEL_ID("KE-010002008"), //
    MODEL_METADATA_FILE_ERROR("KE-010002009"), //
    FAILED_CREATE_MODEL("KE-010002010"), //
    MODEL_BROKEN("KE-010002011"), //
    MODEL_ONLINE_ABANDON("KE-010002012"), //
    INVALID_MEASURE_DATA_TYPE("KE-010002013"), //
    MODEL_EXPORT_ERROR("KE-010002015"), //
    MODEL_IMPORT_ERROR("KE-010002016"), //
    INVALID_MODEL_TYPE("KE-010002017"), //
    PARTITION_VALUE_NOT_SUPPORT("KE-010002018"), //
    // 10035XXX multi partition column
    INVALID_MULTI_PARTITION_MAPPING_REQUEST("KE-010002019"), //
    INVALID_PARTITION_VALUES("KE-010002020"), //
    TABLE_JOIN_RELATIONSHIP_ERROR("KE-010002021"), //
    INVALID_TABLE_AUTH("KE-010002022"), //
    MODEL_CONFIG_NOT_EXIST("KE-010002023"), //
    MODEL_CONFIG_KEY_EXIST("KE-010002024"), //
    MODEL_CONFIG_KEY_NOT_EXIST("KE-010002025"), //
    MODEL_STORAGE_UPDATE_FAILED("KE-010002026"), //
    // 10003XXX user
    USER_NOT_EXIST("KE-010003001"), //
    EMPTY_USER_NAME("KE-010003002"), //
    INVALID_USER_NAME("KE-010003003"), //
    DUPLICATE_USER_NAME("KE-010003004"), //
    USER_LOCKED("KE-010003005"), //
    FAILED_UPDATE_USER("KE-010003006"), //
    USER_UNAUTHORIZED("KE-010003007"), //
    LOGIN_FAILED("KE-010003008"), //

    // 10004XXX user group
    USERGROUP_NOT_EXIST("KE-010004001"), //
    EMPTY_USERGROUP_NAME("KE-010004002"), //
    INVALID_USERGROUP_NAME("KE-010004003"), //
    DUPLICATE_USERGROUP_NAME("KE-010004004"), //

    // 10005XXX password
    INVALID_PASSWORD("KE-010005001"), //
    SHORT_PASSWORD("KE-010005002"), //
    FAILED_UPDATE_PASSWORD("KE-010005003"), //

    // 10006XXX column
    PARTITION_COLUMN_NOT_EXIST("KE-010006001"), //
    INVALID_PARTITION_COLUMN("KE-010006002"), //
    COLUMN_NOT_EXIST("KE-010006003"), //
    EMPTY_PARTITION_COLUMN("KE-010006004"), //
    TIMESTAMP_COLUMN_NOT_EXIST("KE-010006005"), //

    // 10007XXX table
    TABLE_NOT_EXIST("KE-010007001"), //
    EMPTY_TABLE_NAME("KE-010007002"), //
    INVALID_TABLE_NAME("KE-010007003"), //
    RELOAD_TABLE_FAILED("KE-010007004"), //
    INVALID_TABLE_REFRESH_PARAMETER("KE-010007005"), //
    INVALID_TABLE_SAMPLE_RANGE("KE-010007006"), //
    DUPLICATED_COLUMN_NAME("KE-010007007"), //
    ON_GOING_JOB_EXIST("KE-010007008"), //
    VIEW_PARTITION_DATE_FORMAT_DETECTION_FORBIDDEN("KE-010007009"), //
    INVALID_LOGICAL_VIEW("KE-010007010"), //

    INTERNAL_TABLE_ERROR("KE-010007011"), //
    NOT_INTERNAL_TABLE_ERROR("KE-010007012"), //
    INVALID_INTERNAL_TABLE_PARAMETER("KE-010007013"), //
    INTERNAL_TABLE_NOT_EXIST("KE-010007014"), //
    INTERNAL_TABLE_PARTITION_NOT_EXIST("KE-010007015"), //
    INTERNAL_TABLE_RELOAD_ERROR("KE-010007016"), //
    GLUTEN_NOT_ENABLED_ERROR("KE-010007017"), //

    // 10008XXX database
    DATABASE_NOT_EXIST("KE-010008001"), //
    FAILED_IMPORT_SSB_DATA("KE-010008002"), //
    UNSUPPORTED_DATA_SOURCE_TYPE("KE-010008003"), //

    // 10009XXX measure
    DUPLICATE_MEASURE_NAME("KE-010009001"), //
    DUPLICATE_MEASURE_EXPRESSION("KE-010009002"), //

    // 10010XXX dimension
    DUPLICATE_DIMENSION_NAME("KE-010010001"), //
    EFFECTIVE_DIMENSION_NOT_FIND("KE-010010002"),

    // 10011XXX cc
    DUPLICATE_COMPUTED_COLUMN_NAME("KE-010011001"), //
    DUPLICATE_COMPUTED_COLUMN_EXPRESSION("KE-010011002"), //
    INVALID_COMPUTED_COLUMN_EXPRESSION("KE-010011003"), //
    COMPUTED_COLUMN_CASCADE_ERROR("KE-010011004"), //
    COMPUTED_COLUMN_DEPENDS_ANTI_FLATTEN_LOOKUP("KE-010011005"), //
    FILTER_CONDITION_DEPENDS_ANTI_FLATTEN_LOOKUP("KE-010011006"), //

    // 10012XXX index
    FAILED_UPDATE_TABLE_INDEX("KE-010012001"), //
    FAILED_UPDATE_AGG_INDEX("KE-010012002"), //
    NO_INDEX_DEFINE("KE-010012003"), //
    RULE_BASED_INDEX_METADATA_INCONSISTENT("KE-010012004"), //
    DUPLICATE_INDEX("KE-010012005"), //
    INVALID_INDEX_SOURCE_TYPE("KE-010012006"), //
    INVALID_INDEX_SORT_BY_FIELD("KE-010012007"), //
    INVALID_INDEX_STATUS_TYPE("KE-010012008"), //

    // 10013XXX job
    FAILED_UPDATE_JOB_STATUS("KE-010013001"), //
    READ_TRANSACTIONAL_TBALE_FAILED("KE-010013010"), //

    // 10014XXX sql expression
    INVALID_FILTER_CONDITION("KE-010014001"), //
    FAILED_EXECUTE_MODEL_SQL("KE-010014002"), //
    DUPLICATE_JOIN_CONDITION("KE-010014003"), //
    EMPTY_SQL_EXPRESSION("KE-010014004"), //

    // 10015XXX license
    LICENSE_FILE_NOT_EXIST("KE-010015001"), //
    EMPTY_LICENSE_CONTENT("KE-010015002"), //
    INVALID_LICENSE("KE-010015003"), //
    LOW_LEVEL_LICENSE("KE-010015004"), //
    LICENSE_PARSING_ERROR("KE-010015005"), //
    LICENSE_MISMATCH_LICENSE("KE-010015006"),

    // 10016XXX email
    EMPTY_EMAIL("KE-010016001"), //
    INVALID_EMAIL("KE-010016002"), //

    // 10017XXX file
    FILE_NOT_EXIST("KE-010017001"), //
    FAILED_DOWNLOAD_FILE("KE-010017002"), //
    FILE_FORMAT_ERROR("KE-010017003"), //
    EMPTY_FILE_CONTENT("KE-010017004"), //
    SQL_NUMBER_EXCEEDS_LIMIT("KE-010017005"), //
    FILE_TYPE_MISMATCH("KE-010017006"),

    // 10018XXX kerberos
    INVALID_KERBEROS_FILE("KE-010018001"), //
    FAILED_CHECK_KERBEROS("KE-010018002"), //

    // 10019XXX catalog
    FAILED_REFRESH_CATALOG_CACHE("KE-010019001"), //
    FAILED_CONNECT_CATALOG("KE-010019002"),

    // 10020XXX recommendation
    FAILED_APPROVE_RECOMMENDATION("KE-010020001"), //
    REC_LIST_OUT_OF_DATE("KE-010020002"), //
    UNSUPPORTED_REC_OPERATION_TYPE("KE-010020003"), //
    EXEC_JOB_FAILED("KE-010020004"), //

    // 10021XXX server
    REMOTE_SERVER_ERROR("KE-010021001"), //
    SYSTEM_IS_RECOVER("KE-010021002"), //
    TRANSFER_FAILED("KE-010021003"), //
    NO_ACTIVE_ALL_NODE("KE-010021004"), //
    PROJECT_WITHOUT_RESOURCE_GROUP("KE-010021005"), //
    USER_DATA_SOURCE_CONNECTION_FAILED("KE-010021006"), //

    // 10022XXX segment
    INVALID_SEGMENT_RANGE("KE-010022001"), //
    SEGMENT_RANGE_OVERLAP("KE-010022002"), //
    FAILED_REFRESH_SEGMENT("KE-010022003"), //
    SEGMENT_UNSUPPORTED_OPERATOR("KE-010022004"), //
    EMPTY_SEGMENT_ID("KE-010022005"), //
    FAILED_MERGE_SEGMENT("KE-010022006"), //
    EMPTY_SEGMENT_RANGE("KE-010022007"), //
    SEGMENT_NOT_EXIST("KE-010022008"), //
    INVALID_SEGMENT_PARAMETER("KE-010022009"), //

    // 10023XXX diag
    DIAG_UUID_NOT_EXIST("KE-010023001"), //
    DIAG_PACKAGE_NOT_READY("KE-010023002"), //
    DIAG_FAILED("KE-010023003"), //

    // 10024XXX auth
    ACCESS_DENIED("KE-010024001"), //
    PERMISSION_DENIED("KE-010024002"), //

    // 10027XXX frequency query
    EMPTY_FREQUENCY_RULE_VALUE("KE-010027001"), //
    EMPTY_DURATION_RULE_VALUE("KE-010027002"), //
    EMPTY_COUNT_RULE_VALUE("KE-010027003"), //
    FAILED_ACCELERATE_QUERY("KE-010027004"), //
    FAILED_INSERT_ACCELERATE_QUERY_BLACKLIST("KE-010027005"), //
    EMPTY_REC_RULE_VALUE("KE-010027003"), //
    SAVE_QUERY_FAILED("KE-010027006"), //
    EMPTY_MIN_HIT_COUNT("KE-010027007"), EMPTY_EFFECTIVE_DAYS("KE-010027008"), EMPTY_UPDATE_FREQUENCY("KE-010027009"),
    SEMI_AUTO_NOT_ENABLED("KE-010027010"), //
    AUTO_INDEX_PLAN_NOT_ENABLED("KE-010027011"), //
    INVALID_AUTO_COMPLETE_MODE("KE-010027012"), //
    INVALID_DATE_UNIT("KE-010027013"), //
    WHITELIST_NOT_IN_INDEXES("KE-010027014"), //
    INDEXES_NOT_IN_WHITELIST("KE-010027015"), //
    MULTIPLE_INDEX_PLAN_JOB_NOT_ALLOWED("KE-010027016"), //
    INSTANT_INIT_NOT_ALLOWED("KE-010027017"), //
    INSTANT_INIT_TOO_MUCH("KE-010027018"), //
    INSTANT_INIT_TOO_LITTLE("KE-010027019"), //

    // 10028XXX query blacklist
    BLACKLIST_QUERY_REJECTED("KE-010028001"), //
    BLACKLIST_EXCEEDED_CONCURRENT_LIMIT("KE-010028002"), //
    BLACKLIST_REGEX_AND_SQL_EMPTY("KE-010028003"), //
    BLACKLIST_PROJECT_EMPTY("KE-010028004"), //
    BLACKLIST_REGEX_EMPTY("KE-010028005"), //
    BLACKLIST_SQL_EXISTS("KE-010028006"), //
    BLACKLIST_ITEM_ID_EMPTY("KE-010028007"), //
    BLACKLIST_REGEX_EXISTS("KE-010028008"), //

    // 10031XXX query result
    FAILED_OBTAIN_QUERY_RESULT("KE-010031001"),

    // 10032XXX add job result
    FAILED_CREATE_JOB("KE-010032001"), //
    FAILED_CREATE_JOB_SAVE_INDEX_SUCCESS("KE-010032002"), FAILED_CREATE_JOB_EXPORT_TO_TIERED_STORAGE_WITHOUT_BASE_INDEX(
            "KE-010032003"),

    // 10033XXX snapshot
    SNAPSHOT_NOT_EXIST("KE-010033001"), //
    SNAPSHOT_MANAGEMENT_NOT_ENABLED("KE-010033002"), //
    SNAPSHOT_RELOAD_PARTITION_FAILED("KE-010033003"), //

    // 10034XXX acl
    ACL_DEPENDENT_COLUMN_PARSE_ERROR("KE-010034001"), //
    ACL_INVALID_COLUMN_DATA_TYPE("KE-010034002"), //
    ACL_INVALID_ROW_FIELD("KE-010034003"), //

    // 10035XXX streaming
    INVALID_KAFKA_CONFIG_ERROR("KE-010035001"), //
    STREAMING_PARSER_ERROR("KE-010035002"), //
    INVALID_BROKER_DEFINITION("KE-010035003"), //
    INVALID_STREAMING_MESSAGE("KE-010035004"), //
    JOB_START_FAILURE("KE-010035005"), //
    JOB_STOP_FAILURE("KE-010035006"), //
    REPEATED_START_ERROR("KE-010035007"), //
    SEGMENT_MERGE_FAILURE("KE-010035008"), //
    BROKER_TIMEOUT_MESSAGE("KE-010035009"), //
    STREAMING_TIMEOUT_MESSAGE("KE-010035010"), //
    STREAMING_INDEX_UPDATE_DISABLE("KE-010035011"), //
    STREAMING_MODEL_NOT_FOUND("KE-010035012"), //
    STREAMING_TABLE_NOT_SUPPORT_AUTO_MODELING("KE-010035013"), //
    UNSUPPORTED_STREAMING_OPERATION("KE-010035014"), //
    READ_KAFKA_JAAS_FILE_ERROR("KE-010035015"), //

    //10038XXX system profile
    SYSTEM_PROFILE_ABNORMAL_DATA("KE-010038001"), //
    SPARK_FAILURE("KE-010038002"), //
    CONFIG_NONEXIST_PROJECT("KE-010038003"), //
    CONFIG_NONEXIST_MODEL("KE-010038004"), //
    INVALID_DATA_BINDER_AUTO_GROW_COLLECTION_LIMIT("KE-010038005"), //

    // 10039XXX jdbc source

    INVALID_JDBC_SOURCE_CONFIG("KE-010039001"), //

    // 10040XXX cache
    REDIS_CLEAR_ERROR("KE-010040001"), //
    REDIS_INIT_FAILED("KE-010040002"), //

    // 10050XXX SQL DDL
    DDL_CHECK_ERROR("KE-010050001");

    private final ErrorCode errorCode;

    ServerErrorCode(String code) {
        errorCode = new ErrorCode(code);
    }

    @Override
    public ErrorCode toErrorCode() {
        return errorCode;
    }
}
