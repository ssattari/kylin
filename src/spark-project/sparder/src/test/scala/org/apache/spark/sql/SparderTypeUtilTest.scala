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
package org.apache.spark.sql

import org.apache.calcite.rel.`type`.RelDataTypeSystem
import org.apache.calcite.sql.`type`.SqlTypeFactoryImpl
import org.apache.commons.io.FileUtils
import org.apache.kylin.common.KylinConfig
import org.apache.kylin.guava30.shaded.common.io.Files
import org.apache.kylin.metadata.datatype.DataType
import org.apache.kylin.query.schema.OlapTable
import org.apache.spark.sql.common.SparderBaseFunSuite
import org.apache.spark.sql.types.{DataTypes, StructField}
import org.apache.spark.sql.util.SparderTypeUtil

import java.io.File
import java.sql.Types
import scala.collection.immutable

class SparderTypeUtilTest extends SparderBaseFunSuite {

  val dataTypes: immutable.Seq[DataType] = List(
    DataType.getType("decimal(19,4)"),
    DataType.getType("char(50)"),
    DataType.getType("varchar(1000)"),
    DataType.getType("date"),
    DataType.getType("timestamp"),
    DataType.getType("tinyint"),
    DataType.getType("smallint"),
    DataType.getType("integer"),
    DataType.getType("bigint"),
    DataType.getType("float"),
    DataType.getType("double"),
    DataType.getType("decimal(38,19)"),
    DataType.getType("numeric(5,4)"),
    DataType.getType("ARRAY<STRING>")
  )

  test("Test decimal") {
    val dt = DataType.getType("decimal(19,4)")
    val dataTp = DataTypes.createDecimalType(19, 4)
    val dataType = SparderTypeUtil.kylinTypeToSparkResultType(dt)
    assert(dataTp.sameType(dataType))
    val sparkTp = SparderTypeUtil.toSparkType(dt)
    assert(dataTp.sameType(sparkTp))
    val sparkTpSum = SparderTypeUtil.toSparkType(dt, isSum = true)
    assert(DataTypes.createDecimalType(29, 4).sameType(sparkTpSum))
  }

  test("Test kylinRawTableSQLTypeToSparkType") {
    dataTypes.map(SparderTypeUtil.kylinRawTableSQLTypeToSparkType)

  }

  test("Test kylinTypeToSparkResultType") {
    dataTypes.map(SparderTypeUtil.kylinTypeToSparkResultType)
  }

  test("Test toSparkType") {
    dataTypes.map(dt => {
      val sparkType = SparderTypeUtil.toSparkType(dt)
      SparderTypeUtil.convertSparkTypeToSqlType(sparkType)
    })
  }

  test("Test convertSqlTypeToSparkType") {
    kylinConfig
    val typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)
    dataTypes.map(dt => {
      val relDataType = OlapTable.createSqlType(typeFactory, dt, true)
      SparderTypeUtil.convertSqlTypeToSparkType(relDataType)
    })
  }

  private def kylinConfig = {
    val tmpHome = Files.createTempDir
    System.setProperty("KYLIN_HOME", tmpHome.getAbsolutePath)
    FileUtils.touch(new File(tmpHome.getAbsolutePath + "/kylin.properties"))
    KylinConfig.setKylinConfigForLocalTest(tmpHome.getCanonicalPath)
    KylinConfig.getInstanceFromEnv
  }

  test("test convertSparkFieldToJavaField") {
    kylinConfig
    val typeFactory = new SqlTypeFactoryImpl(RelDataTypeSystem.DEFAULT)
    dataTypes.map(dt => {
      val relDataType = OlapTable.createSqlType(typeFactory, dt, true)
      val structField = SparderTypeUtil.convertSparkFieldToJavaField(
        StructField("foo", SparderTypeUtil.convertSqlTypeToSparkType(relDataType))
      )

      if (relDataType.getSqlTypeName.getJdbcOrdinal == Types.CHAR) {
        assert(Types.VARCHAR == structField.getDataType)
      } else if (relDataType.getSqlTypeName.getJdbcOrdinal == Types.DECIMAL) {
        assert(Types.DECIMAL == structField.getDataType)
        assert(relDataType.getPrecision == structField.getPrecision)
        assert(relDataType.getScale == structField.getScale)
      } else if (dt.getName.startsWith("array")) {
        assert(structField.getDataType == Types.OTHER)
        assert(relDataType.getSqlTypeName.getJdbcOrdinal == Types.ARRAY)
      } else {
        assert(relDataType.getSqlTypeName.getJdbcOrdinal == structField.getDataType)
      }
    })
  }

}
