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
package org.apache.kylin.common.persistence.metadata.mapper;

import static org.apache.kylin.common.persistence.metadata.mapper.TableModelRelationDynamicSqlSupport.sqlTable;

import java.util.List;
import java.util.Optional;

import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.ResultMap;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.type.JdbcType;
import org.apache.kylin.common.persistence.metadata.jdbc.ContentTypeHandler;
import org.apache.kylin.common.persistence.metadata.jdbc.SqlWithRecordLockProviderAdapter;
import org.apache.kylin.common.persistence.resources.TableModelRelationRawResource;
import org.mybatis.dynamic.sql.BasicColumn;
import org.mybatis.dynamic.sql.select.render.SelectStatementProvider;
import org.mybatis.dynamic.sql.update.UpdateDSL;
import org.mybatis.dynamic.sql.update.UpdateModel;
import org.mybatis.dynamic.sql.util.SqlProviderAdapter;

public interface TableModelRelationMapper extends BasicMapper<TableModelRelationRawResource> {
    @Override
    default UpdateDSL<UpdateModel> updateAllColumns(TableModelRelationRawResource record, UpdateDSL<UpdateModel> dsl) {
        dsl = BasicMapper.super.updateAllColumns(record, dsl);
        return dsl.set(sqlTable.tableIdentity).equalTo(record::getTableIdentity).set(sqlTable.modelUuid)
                .equalTo(record::getModelUuid);
    }

    @Override
    default BasicColumn[] getSelectList() {
        return getSelectListWithAdditions(sqlTable.tableIdentity, sqlTable.modelUuid);
    }

    @Override
    default BasicSqlTable getSqlTable() {
        return sqlTable;
    }

    @Override
    @SelectProvider(type = SqlProviderAdapter.class, method = "select")
    @ResultMap("TableModelRelationResult")
    Optional<TableModelRelationRawResource> selectOne(SelectStatementProvider selectStatement);

    @Override
    @SelectProvider(type = SqlProviderAdapter.class, method = "select")
    @Results(id = "TableModelRelationResult", value = {
            @Result(column = "id", property = "id", jdbcType = JdbcType.BIGINT, id = true),
            @Result(column = "meta_key", property = "metaKey", jdbcType = JdbcType.VARCHAR),
            @Result(column = "project", property = "project", jdbcType = JdbcType.VARCHAR),
            @Result(column = "uuid", property = "uuid", jdbcType = JdbcType.CHAR),
            @Result(column = "table_identity", property = "tableIdentity", jdbcType = JdbcType.VARCHAR),
            @Result(column = "model_uuid", property = "modelUuid", jdbcType = JdbcType.CHAR),
            @Result(column = "mvcc", property = "mvcc", jdbcType = JdbcType.BIGINT),
            @Result(column = "ts", property = "ts", jdbcType = JdbcType.BIGINT),
            @Result(column = "content", property = "content", jdbcType = JdbcType.VARCHAR, typeHandler = ContentTypeHandler.class),
            @Result(column = "reserved_filed_1", property = "reservedFiled1", jdbcType = JdbcType.VARCHAR),
            @Result(column = "reserved_filed_2", property = "reservedFiled2", jdbcType = JdbcType.LONGVARBINARY),
            @Result(column = "reserved_filed_3", property = "reservedFiled3", jdbcType = JdbcType.LONGVARBINARY) })
    List<TableModelRelationRawResource> selectMany(SelectStatementProvider selectStatement);

    @Override
    @SelectProvider(type = SqlWithRecordLockProviderAdapter.class, method = "select")
    @ResultMap("TableModelRelationResult")
    List<TableModelRelationRawResource> selectManyWithRecordLock(SelectStatementProvider selectStatement);
}
