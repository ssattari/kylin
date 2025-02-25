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

import org.apache.spark.sql.catalyst.FunctionIdentifier
import org.apache.spark.sql.catalyst.analysis.FunctionRegistry.FunctionBuilder
import org.apache.spark.sql.catalyst.expressions.ExpressionUtils.expression
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.aggregate.AggregateFunction
import org.apache.spark.sql.types._
import org.apache.spark.sql.udaf.BitmapFuncType.BitmapFuncType
import org.apache.spark.sql.udaf._

object KapFunctions {


  private def withAggregateFunction(func: AggregateFunction,
                                    isDistinct: Boolean = false): Column = {
    Column(func.toAggregateExpression(isDistinct))
  }

  def k_add_months(startDate: Column, numMonths: Column): Column = {
    Column(KapAddMonths(startDate.expr, numMonths.expr))
  }

  def k_subtract_months(date0: Column, date1: Column): Column = {
    Column(KapSubtractMonths(date0.expr, date1.expr))
  }

  def k_like(left: Column, right: Column, escapeChar: Char = '\\'): Column = Column(Like(left.expr, right.expr, escapeChar))

  def k_similar(left: Column, right: Column): Column = Column(RLike(left.expr, right.expr))

  def sum0(e: Column): Column = withAggregateFunction {
    Sum0(e.expr)
  }

  // special lit for KE.
  def k_lit(literal: Any): Column = literal match {
    case c: Column => c
    case s: Symbol => new ColumnName(s.name)
    case _ => Column(Literal(literal))
  }

  def in(value: Expression, list: Seq[Expression]): Column = Column(In(value, list))

  def k_sum_lc(measureCol: Column, wrapDataType: DataType): Column =
    Column(ReuseSumLC(measureCol.expr, wrapDataType, wrapDataType).toAggregateExpression())

  def k_sum_lc_decode(measureCol: Column, wrapDataType: String): Column =
    Column(SumLCDecode(measureCol.expr, Literal(wrapDataType)))

  def k_percentile(head: Column, column: Column, precision: Int): Column =
    Column(Percentile(head.expr, precision, Some(column.expr), DoubleType).toAggregateExpression())

  def k_percentile_decode(column: Column, p: Column, precision: Int): Column =
    Column(PercentileDecode(column.expr, p.expr, Literal(precision)))

  def precise_count_distinct(column: Column): Column =
    Column(PreciseCountDistinct(column.expr, LongType).toAggregateExpression())

  def precise_count_distinct_decode(column: Column): Column =
    Column(PreciseCountDistinctDecode(column.expr))

  def precise_bitmap_uuid(column: Column): Column =
    Column(PreciseCountDistinct(column.expr, BinaryType).toAggregateExpression())

  def precise_bitmap_build(column: Column): Column =
    Column(PreciseBitmapBuildBase64WithIndex(column.expr, StringType).toAggregateExpression())

  def precise_bitmap_build_decode(column: Column): Column =
    Column(PreciseBitmapBuildBase64Decode(column.expr))

  def precise_bitmap_build_pushdown(column: Column): Column =
    Column(PreciseBitmapBuildPushDown(column.expr).toAggregateExpression())

  def bitmap_uuid_func(column: Column, returnDataType: DataType, funcType: BitmapFuncType): Column =
    Column(BitmapUuidFunc(column.expr, -1, 0, returnDataType, funcType).toAggregateExpression())

  def bitmap_uuid_page_func(column: Column, limit: Int, offset: Int,
                            returnDataType: DataType, funcType: BitmapFuncType): Column = {
    if (limit < 0 || offset < 0) {
      throw new UnsupportedOperationException(s"both limit and offset must be >= 0")
    }
    Column(BitmapUuidFunc(column.expr, limit, offset, returnDataType, funcType).toAggregateExpression())
  }

  def approx_count_distinct(column: Column, precision: Int): Column =
    Column(ApproxCountDistinct(column.expr, precision).toAggregateExpression())

  def approx_count_distinct_decode(column: Column, precision: Int): Column =
    Column(ApproxCountDistinctDecode(column.expr, Literal(precision)))

  def k_truncate(column: Column, scale: Column): Column = {
    Column(Truncate(column.expr, scale.expr))
  }

  def intersect_count(separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      k_lit(IntersectCount.RAW_STRING).expr, LongType, separator, upperBound).toAggregateExpression()
    )
  }

  def intersect_value(separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      k_lit(IntersectCount.RAW_STRING).expr, ArrayType(LongType, containsNull = false), separator, upperBound).toAggregateExpression()
    )
  }

  def intersect_bitmap(separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      k_lit(IntersectCount.RAW_STRING).expr, BinaryType, separator, upperBound).toAggregateExpression()
    )
  }


  def intersect_count_v2(filterType: Column, separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      filterType.expr, LongType, separator, upperBound
    ).toAggregateExpression())
  }

  def intersect_value_v2(filterType: Column, separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      filterType.expr, ArrayType(LongType, containsNull = false), separator, upperBound
    ).toAggregateExpression())
  }

  def intersect_bitmap_v2(filterType: Column, separator: String, upperBound: Int, columns: Column*): Column = {
    require(columns.size == 3, s"Input columns size ${columns.size} don't equal to 3.")
    val expressions = columns.map(_.expr)
    Column(IntersectCount(expressions.head, expressions.apply(1), expressions.apply(2),
      filterType.expr, BinaryType, separator, upperBound
    ).toAggregateExpression())
  }

  def dict_encode(column: Column, dictParams: Column, bucketSize: Column, buildVersion: Column): Column = {
    Column(DictEncode(column.expr, dictParams.expr, bucketSize.expr, buildVersion.expr))
  }

  def dict_encode_v3(column: Column, colName: String): Column = {
    Column(DictEncodeV3(column.expr, colName))
  }

  val builtin: Seq[FunctionEntity] = Seq(
    // string functions
    FunctionEntity(expression[KylinSplitPart]("split_part")),
    FunctionEntity(expression[InitCap]("initcapb")),
    FunctionEntity(expression[StringInstr](name = "strpos")),
    FunctionEntity(expression[KylinInstr](name = "instr")),
    // arithmetic functions
    FunctionEntity(expression[Truncate]("TRUNCATE")),
    // bitmap functions
    FunctionEntity(expression[ReusePreciseCountDistinct]("bitmap_or")),
    FunctionEntity(expression[PreciseCardinality]("bitmap_cardinality")),
    FunctionEntity(expression[PreciseCountDistinctAndValue]("bitmap_and_value")),
    FunctionEntity(expression[PreciseCountDistinctAndArray]("bitmap_and_ids")),
    FunctionEntity(expression[PreciseBitmapBuildPushDown]("bitmap_build")),
    // decode & encode functions
    FunctionEntity(expression[PreciseCountDistinctDecode]("precise_count_distinct_decode")),
    FunctionEntity(expression[ApproxCountDistinctDecode]("approx_count_distinct_decode")),
    FunctionEntity(expression[PercentileDecode]("percentile_decode")),
    FunctionEntity(expression[DictEncode]("DICTENCODE")),
    // datetime functions
    FunctionEntity(expression[KylinTimestampAdd]("TIMESTAMPADD")),
    FunctionEntity(expression[KylinTimestampDiff]("TIMESTAMPDIFF")),
    FunctionEntity(expression[FloorDateTime]("floor_datetime")),
    FunctionEntity(expression[CeilDateTime]("ceil_datetime")),
    FunctionEntity(expression[YMDintBetween]("_ymdint_between"))
  )

  val percentileFunction: FunctionEntity = FunctionEntity(
    ExpressionUtils.expression[Percentile]("percentile_approx")
  )
}

case class FunctionEntity(name: FunctionIdentifier,
                          info: ExpressionInfo,
                          builder: FunctionBuilder)

object FunctionEntity {
  def apply(tuple: (String, (ExpressionInfo, FunctionBuilder))): FunctionEntity = {
    new FunctionEntity(FunctionIdentifier.apply(tuple._1), tuple._2._1, tuple._2._2)
  }
}
