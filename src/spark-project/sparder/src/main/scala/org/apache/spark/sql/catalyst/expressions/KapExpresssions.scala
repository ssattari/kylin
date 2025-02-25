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
package org.apache.spark.sql.catalyst.expressions

import org.apache.kylin.common.util.TimeUtil
import org.apache.spark.dict.{NBucketDictionary, NGlobalDictionaryV2}
import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.analysis.{FunctionRegistry, TypeCheckResult}
import org.apache.spark.sql.catalyst.expressions.aggregate.DeclarativeAggregate
import org.apache.spark.sql.catalyst.expressions.codegen.Block._
import org.apache.spark.sql.catalyst.expressions.codegen.{CodeGenerator, CodegenContext, ExprCode, FalseLiteral}
import org.apache.spark.sql.catalyst.util.{DateTimeUtils, GenericArrayData, KapDateTimeUtils, TypeUtils}
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.sql.types._
import org.apache.spark.sql.udf._
import org.apache.spark.unsafe.types.UTF8String

import java.math.RoundingMode
import java.time.ZoneId
import java.util.Locale
import scala.collection.JavaConverters._

// Returns the date that is num_months after start_date.
// scalastyle:off line.size.limit
@ExpressionDescription(
  usage =
    "_FUNC_(start_date, num_months) - Returns the date that is `num_months` after `start_date`.",
  extended =
    """
    Examples:
      > SELECT _FUNC_('2016-08-31', 1);
       2016-09-30
  """
)
// scalastyle:on line.size.limit
case class KapAddMonths(startDate: Expression, numMonths: Expression)
  extends BinaryExpression
    with ImplicitCastInputTypes {

  override def left: Expression = startDate

  override def right: Expression = numMonths

  override def inputTypes: Seq[AbstractDataType] = Seq(TimestampType, IntegerType)

  override def dataType: TimestampType = TimestampType

  override def nullSafeEval(start: Any, months: Any): Any = {
    val time = start.asInstanceOf[Long]
    val month = months.asInstanceOf[Int]
    KapDateTimeUtils.addMonths(time, month)
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val dtu = KapDateTimeUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (sd, m) => {
      s"""$dtu.addMonths($sd, $m)"""
    })
  }

  override def prettyName: String = "kap_add_months"

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }
}

// Returns the date that is num_months after start_date.
// scalastyle:off line.size.limit
@ExpressionDescription(
  usage =
    "_FUNC_(date0, date1) - Returns the num of months between `date0` after `date1`.",
  extended =
    """
    Examples:
      > SELECT _FUNC_('2016-08-31', '2017-08-31');
       12
  """
)
// scalastyle:on line.size.limit
case class KapSubtractMonths(a: Expression, b: Expression)
  extends BinaryExpression
    with ImplicitCastInputTypes {

  override def left: Expression = a

  override def right: Expression = b

  override def inputTypes: Seq[AbstractDataType] = Seq(DateType, DateType)

  override def dataType: DataType = IntegerType

  override def nullSafeEval(date0: Any, date1: Any): Any = {
    KapDateTimeUtils.dateSubtractMonths(date0.asInstanceOf[Int],
      date1.asInstanceOf[Int])
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val dtu = KapDateTimeUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (d0, d1) => {
      s"""$dtu.dateSubtractMonths($d0, $d1)"""
    })
  }

  override def prettyName: String = "kap_months_between"

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }

}

@ExpressionDescription(
  usage = "_FUNC_(expr) - Returns the sum calculated from values of a group. " +
    "It differs in that when no non null values are applied zero is returned instead of null")
case class Sum0(child: Expression)
  extends DeclarativeAggregate
    with ImplicitCastInputTypes {

  override def children: Seq[Expression] = child :: Nil

  override def nullable: Boolean = true

  // Return data type.
  override def dataType: DataType = resultType

  override def inputTypes: Seq[AbstractDataType] = Seq(NumericType)

  override def checkInputDataTypes(): TypeCheckResult =
    TypeUtils.checkForNumericExpr(child.dataType, "function sum")

  private lazy val resultType = child.dataType match {
    case DecimalType.Fixed(precision, scale) =>
      DecimalType.bounded(precision + 10, scale)
    case _: IntegralType => LongType
    case _ => DoubleType
  }

  private lazy val sumDataType = resultType

  private lazy val sum = AttributeReference("sum", sumDataType)()

  private lazy val zero = Cast(Literal(0), sumDataType)

  override lazy val aggBufferAttributes = sum :: Nil

  override lazy val initialValues: Seq[Expression] = Seq(
    //    /* sum = */ Literal.create(0, sumDataType)
    //    /* sum = */ Literal.create(null, sumDataType)
    Cast(Literal(0), sumDataType)
  )

  override lazy val updateExpressions: Seq[Expression] = {
    if (child.nullable) {
      Seq(
        /* sum = */
        Coalesce(
          Seq(Add(Coalesce(Seq(sum, zero)), Cast(child, sumDataType)), sum))
      )
    } else {
      Seq(
        /* sum = */
        Add(Coalesce(Seq(sum, zero)), Cast(child, sumDataType))
      )
    }
  }

  override lazy val mergeExpressions: Seq[Expression] = {
    Seq(
      /* sum = */
      Coalesce(Seq(Add(Coalesce(Seq(sum.left, zero)), sum.right), sum.left))
    )
  }

  override lazy val evaluateExpression: Expression = sum

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression =
    super.legacyWithNewChildren(newChildren)
}

case class KylinTimestampAdd(left: Expression, mid: Expression, right: Expression) extends TernaryExpression with ImplicitCastInputTypes {

  override def dataType: DataType = getResultDataType

  override def inputTypes: Seq[AbstractDataType] =
    Seq(StringType, TypeCollection(IntegerType, LongType), TypeCollection(TimestampType, DateType))

  def getResultDataType(): DataType = {
    if (canConvertTimestamp()) {
      TimestampType
    } else {
      right.dataType
    }
  }

  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any): Any = {
    (mid.dataType, right.dataType) match {
      case (IntegerType, DateType) =>
        if (canConvertTimestamp()) {
          TimestampAddImpl.evaluateTimestamp(input1.toString, input2.asInstanceOf[Int], input3.asInstanceOf[Int])
        } else {
          TimestampAddImpl.evaluateDays(input1.toString, input2.asInstanceOf[Int], input3.asInstanceOf[Int])
        }
      case (LongType, DateType) =>
        if (canConvertTimestamp()) {
          TimestampAddImpl.evaluateTimestamp(input1.toString, input2.asInstanceOf[Long], input3.asInstanceOf[Int])
        } else {
          TimestampAddImpl.evaluateDays(input1.toString, input2.asInstanceOf[Long], input3.asInstanceOf[Int])
        }
      case (IntegerType, TimestampType) =>
        TimestampAddImpl.evaluateTimestamp(input1.toString, input2.asInstanceOf[Int], input3.asInstanceOf[Long])
      case (LongType, TimestampType) =>
        TimestampAddImpl.evaluateTimestamp(input1.toString, input2.asInstanceOf[Long], input3.asInstanceOf[Long])
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val ta = TimestampAddImpl.getClass.getName.stripSuffix("$")
    (mid.dataType, right.dataType) match {
      case ((IntegerType, DateType) | (LongType, DateType)) =>
        if (canConvertTimestamp()) {
          defineCodeGen(ctx, ev, (arg1, arg2, arg3) => {
            s"""$ta.evaluateTimestamp($arg1.toString(), $arg2, $arg3)"""
          })
        } else {
          defineCodeGen(ctx, ev, (arg1, arg2, arg3) => {
            s"""$ta.evaluateDays($arg1.toString(), $arg2, $arg3)"""
          })
        }
      case (IntegerType, TimestampType) | (LongType, TimestampType) =>
        defineCodeGen(ctx, ev, (arg1, arg2, arg3) => {
          s"""$ta.evaluateTimestamp($arg1.toString(), $arg2, $arg3)"""
        })
    }
  }

  override def first: Expression = left

  override def second: Expression = mid

  override def third: Expression = right

  def canConvertTimestamp(): Boolean = {
    if (left.isInstanceOf[Literal] && left.asInstanceOf[Literal].value != null) {
      val unit = left.asInstanceOf[Literal].value.toString.toUpperCase(Locale.ROOT)
      if (TimestampAddImpl.TIME_UNIT.contains(unit) && right.dataType.isInstanceOf[DateType]) {
        return true
      }
    }
    false
  }

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression, newThird: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird)
    super.legacyWithNewChildren(newChildren)
  }
}

case class KylinTimestampDiff(left: Expression, mid: Expression, right: Expression) extends TernaryExpression
  with ImplicitCastInputTypes {

  override def inputTypes: Seq[AbstractDataType] =
    Seq(StringType, TypeCollection(TimestampType, DateType), TypeCollection(TimestampType, DateType))


  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any): Any = {
    (mid.dataType, right.dataType) match {
      case (DateType, DateType) => TimestampDiffImpl.evaluate(input1.toString, input2.asInstanceOf[Int], input3.asInstanceOf[Int])
      case (DateType, TimestampType) => TimestampDiffImpl.evaluate(input1.toString, input2.asInstanceOf[Int], input3.asInstanceOf[Long])
      case (TimestampType, DateType) => TimestampDiffImpl.evaluate(input1.toString, input2.asInstanceOf[Long], input3.asInstanceOf[Int])
      case (TimestampType, TimestampType) =>
        TimestampDiffImpl.evaluate(input1.toString, input2.asInstanceOf[Long], input3.asInstanceOf[Long])
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val td = TimestampDiffImpl.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (arg1, arg2, arg3) => {
      s"""$td.evaluate($arg1.toString(), $arg2, $arg3)"""
    })
  }

  override def first: Expression = left

  override def second: Expression = mid

  override def third: Expression = right

  override def dataType: DataType = LongType

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression, newThird: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird)
    super.legacyWithNewChildren(newChildren)
  }
}

case class Truncate(child: Expression, scale: Expression) extends BinaryExpression {

  override def left: Expression = child
  override def right: Expression = scale

  private val mode: RoundingMode = RoundingMode.DOWN
  private val modeStr: String = "ROUND_DOWN"

  def this(child: Expression) = this(child, Literal(0, IntegerType))

  override lazy val dataType: DataType = child.dataType match {
    // if the new scale is bigger which means we are scaling up,
    // keep the original scale as `Decimal` does
    case DecimalType.Fixed(p, s) =>
      try {
        val scaleEV = scale.eval(EmptyRow)
        val scaleValue = scaleEV.asInstanceOf[Int]
        DecimalType(p, scala.math.max(scala.math.min(s, scaleValue), 0))
      } catch {
        case _: Exception => left.dataType
      }
    case t => t
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val ce = child.genCode(ctx)
    val scaleEV = scale.genCode(ctx)
    val scaleValue = if (scaleEV == null) null else scaleEV.value

    val evaluationCode = left.dataType match {
      case DecimalType.Fixed(_, _) =>
        s"""
           |java.math.BigDecimal value = ${ce.value}.toJavaBigDecimal().setScale(${scaleValue}, java.math.BigDecimal.${modeStr});
           |${ev.value} = Decimal.apply(value);
           |${ev.isNull} = ${ev.value} == null;
         """.stripMargin
      case ByteType =>
        s"""
          ${ev.value} = new java.math.BigDecimal(${ce.value}).
            setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).byteValue();"""
      case ShortType =>
        s"""
          ${ev.value} = new java.math.BigDecimal(${ce.value}).
            setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).shortValue();"""
      case IntegerType =>
        s"""
          ${ev.value} = new java.math.BigDecimal(${ce.value}).
            setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).intValue();"""
      case LongType =>
        s"""
          ${ev.value} = new java.math.BigDecimal(${ce.value}).
            setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).longValue();"""
      case FloatType => // if child eval to NaN or Infinity, just return it.
        s"""
          if (Float.isNaN(${ce.value}) || Float.isInfinite(${ce.value})) {
            ${ev.value} = ${ce.value};
          } else {
            ${ev.value} = java.math.BigDecimal.valueOf(${ce.value}).
              setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).floatValue();
          }"""
      case DoubleType => // if child eval to NaN or Infinity, just return it.
        s"""
          if (Double.isNaN(${ce.value}) || Double.isInfinite(${ce.value})) {
            ${ev.value} = ${ce.value};
          } else {
            ${ev.value} = java.math.BigDecimal.valueOf(${ce.value}).
              setScale(${scaleValue}, java.math.BigDecimal.${modeStr}).doubleValue();
          }"""
    }

    val javaType = CodeGenerator.javaType(dataType)
    if (scaleEV == null || java.lang.Boolean.parseBoolean(s"${scaleEV.isNull}")) { // if scale is null, no need to eval its child at all
      ev.copy(code = code"""
        boolean ${ev.isNull} = true;
        $javaType ${ev.value} = ${CodeGenerator.defaultValue(dataType)};""")
    } else {
      ev.copy(code = code"""
        ${ce.code}
        ${scaleEV.code}
        boolean ${ev.isNull} = ${ce.isNull};
        $javaType ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
        if (!${ev.isNull} && !${scaleEV.isNull}) {
          $evaluationCode
        }""")
    }
  }

  override def eval(input: InternalRow): Any = {
    val scaleEV = scale.eval(input)
    if (scaleEV == null) { // if scale is null, no need to eval its child at all
      null
    } else {
      val evalE = child.eval(input)
      if (evalE == null) {
        null
      } else {
        nullSafeEval(evalE, scaleEV.asInstanceOf[Int])
      }
    }
  }

  override def nullSafeEval(input1: Any, input2: Any): Any = {
    val scaleValue = input2.asInstanceOf[Int]
    left.dataType match {
      case DecimalType.Fixed(p, s) =>
        val decimal = input1.asInstanceOf[Decimal]
        val value = decimal.toJavaBigDecimal.setScale(scaleValue, mode)
        Decimal.apply(value)
      case ByteType => new java.math.BigDecimal(input1.asInstanceOf[Byte]).setScale(scaleValue, mode).byteValue()
      case ShortType => new java.math.BigDecimal(input1.asInstanceOf[Short]).setScale(scaleValue, mode).shortValue()
      case IntegerType => new java.math.BigDecimal(input1.asInstanceOf[Int]).setScale(scaleValue, mode).intValue()
      case LongType => new java.math.BigDecimal(input1.asInstanceOf[Long]).setScale(scaleValue, mode).longValue()
      case FloatType =>
        val f = input1.asInstanceOf[Float]
        if (f.isNaN || f.isInfinite) {
          f
        } else {
          new java.math.BigDecimal(input1.asInstanceOf[Float]).setScale(scaleValue, mode).floatValue()
        }
      case DoubleType =>
        val d = input1.asInstanceOf[Double]
        if (d.isNaN || d.isInfinite) {
          d
        } else {
          new java.math.BigDecimal(input1.asInstanceOf[Double]).setScale(scaleValue, mode).doubleValue()
        }
    }
  }

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Truncate = {
    copy(child = newLeft, scale = newRight)
  }
}

case class DictEncodeV3(child: Expression, col: String) extends UnaryExpression {
  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = defineCodeGen(ctx, ev, c => c)

  override def dataType: DataType = StringType

  override protected def withNewChildInternal(newChild: Expression): DictEncodeV3 = copy(child = newChild)

  override def eval(input: InternalRow): Any = {
    if (input != null) {
      super.eval(input)
    } else {
      0L
    }
  }

  override protected def nullSafeEval(input: Any): Any = super.nullSafeEval(input)
}

case class DictEncode(left: Expression, mid: Expression,
                      right: Expression, buildVersion: Expression)
  extends QuaternaryExpression with ExpectsInputTypes {

  def maxFields: Int = SQLConf.get.maxToStringFields

  override def first: Expression = left

  override def second: Expression = mid

  override def third: Expression = right

  override def fourth: Expression = buildVersion

  override def inputTypes: Seq[AbstractDataType] = Seq(AnyDataType, StringType, StringType, LongType)

  override protected def doGenCode(ctx: CodegenContext,
                                   ev: ExprCode): ExprCode = {
    val globalDictClass = classOf[NGlobalDictionaryV2].getName
    val bucketDictClass = classOf[NBucketDictionary].getName
    val globalDictTerm = ctx.addMutableState(globalDictClass,
      s"${
        mid.simpleString(maxFields)
          .replace("[", "").replace("]", "")
      }_globalDict")
    val bucketDictTerm = ctx.addMutableState(bucketDictClass,
      s"${
        mid.simpleString(maxFields)
          .replace("[", "").replace("]", "")
      }_bucketDict")

    val dictParamsTerm = mid.simpleString(maxFields)
    val bucketSizeTerm = right.simpleString(maxFields).toInt
    val version = buildVersion.simpleString(maxFields).toLong

    val initBucketDictFuncName = ctx.addNewFunction(s"init${bucketDictTerm.replace("[", "").replace("]", "")}BucketDict",
      s"""
         | private void init${bucketDictTerm.replace("[", "").replace("]", "")}BucketDict(int idx) {
         |   try {
         |     int bucketId = idx % $bucketSizeTerm;
         |     $globalDictTerm = new org.apache.spark.dict.NGlobalDictionaryV2("$dictParamsTerm",${version}L);
         |     $bucketDictTerm = $globalDictTerm.loadBucketDictionary(bucketId, true);
         |   } catch (Exception e) {
         |     throw new RuntimeException(e);
         |   }
         | }
        """.stripMargin)

    ctx.addPartitionInitializationStatement(s"$initBucketDictFuncName(partitionIndex);");

    defineCodeGen(ctx, ev, (arg1, arg2, arg3, arg4) => {
      s"""$bucketDictTerm.encode($arg1)"""
    })
  }

  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any, input4: Any): Any = {
    DictEncodeImpl.evaluate(input1.toString, input2.toString, input3.toString, input4.toString)
  }

  override def eval(input: InternalRow): Any = {
    if (input != null) {
      super.eval(input)
    } else {
      0L
    }
  }

  override def dataType: DataType = LongType

  override def prettyName: String = "DICTENCODE"

  override protected def withNewChildrenInternal(newFirst: Expression,
                                                 newSecond: Expression,
                                                 newThird: Expression,
                                                 newFourth: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird, newFourth)
    super.legacyWithNewChildren(newChildren)
  }
}


case class KylinSplitPart(left: Expression, mid: Expression, right: Expression) extends TernaryExpression with ExpectsInputTypes {

  override def dataType: DataType = left.dataType

  override def nullable: Boolean = true

  override def inputTypes: Seq[AbstractDataType] = Seq(StringType, StringType, IntegerType)

  override def first: Expression = left

  override def second: Expression = mid

  override def third: Expression = right

  override protected def nullSafeEval(input1: Any, input2: Any, input3: Any): Any = {
    SplitPartImpl.evaluate(input1.toString, input2.toString, input3.asInstanceOf[Int])
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val ta = SplitPartImpl.getClass.getName.stripSuffix("$")
    nullSafeCodeGen(ctx, ev, (arg1, arg2, arg3) => {
      s"""
          org.apache.spark.unsafe.types.UTF8String result = $ta.evaluate($arg1.toString(), $arg2.toString(), $arg3);
          if (result == null) {
            ${ev.isNull} = true;
          } else {
            ${ev.value} = result;
          }
        """
    })
  }

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression, newThird: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird)
    super.legacyWithNewChildren(newChildren)
  }
}

case class FloorDateTime(timestamp: Expression,
                         format: Expression,
                         timeZoneId: Option[String] = None)
  extends TruncInstant with TimeZoneAwareExpression {

  override def left: Expression = timestamp

  override def right: Expression = format

  override def inputTypes: Seq[AbstractDataType] = Seq(TimestampType, StringType)

  override def dataType: TimestampType = TimestampType

  override def prettyName: String = "floor_datetime"

  override val instant = timestamp

  override def withTimeZone(timeZoneId: String): TimeZoneAwareExpression =
    copy(timeZoneId = Option(timeZoneId))

  def this(timestamp: Expression, format: Expression) = this(timestamp, format, None)

  override def eval(input: InternalRow): Any = {
    evalHelper(input, minLevel = DateTimeUtils.TRUNC_TO_SECOND) { (t: Any, level: Int) =>
      DateTimeUtils.truncTimestamp(t.asInstanceOf[Long], level, zoneId)
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val tz = ctx.addReferenceObj("timeZone", zoneId, classOf[ZoneId].getName)
    codeGenHelper(ctx, ev, minLevel = DateTimeUtils.TRUNC_TO_SECOND, true) {
      (date: String, fmt: String) =>
        s"truncTimestamp($date, $fmt, $tz);"
    }
  }

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }
}

case class CeilDateTime(timestamp: Expression,
                        format: Expression,
                        timeZoneId: Option[String] = None)
  extends TruncInstant with TimeZoneAwareExpression {

  override def left: Expression = timestamp

  override def right: Expression = format

  override def inputTypes: Seq[AbstractDataType] = Seq(TimestampType, StringType)

  override def dataType: TimestampType = TimestampType

  override def prettyName: String = "ceil_datetime"

  override val instant = timestamp

  override def withTimeZone(timeZoneId: String): TimeZoneAwareExpression =
    copy(timeZoneId = Option(timeZoneId))

  def this(timestamp: Expression, format: Expression) = this(timestamp, format, None)

  // scalastyle:off
  override def eval(input: InternalRow): Any = {
    evalHelper(input, minLevel = DateTimeUtils.TRUNC_TO_SECOND) { (t: Any, level: Int) =>
      DateTimeUtils.ceilTimestamp(t.asInstanceOf[Long], level, zoneId)
    }
  }

  override def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val zid = ctx.addReferenceObj("zoneId", zoneId, classOf[ZoneId].getName)
    codeGenHelper(ctx, ev, minLevel = DateTimeUtils.TRUNC_TO_SECOND, orderReversed = true) {
      (date: String, fmt: String) =>
        s"ceilTimestamp($date, $fmt, $zid);"
    }
  }

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }
}

case class IntersectCountByCol(childrenExp: Seq[Expression]) extends Expression {
  override def nullable: Boolean = false

  override def children: Seq[Expression] = childrenExp

  override def eval(input: InternalRow): Long = {
    val array = children.map(_.eval(input).asInstanceOf[Array[Byte]]).toList.asJava
    IntersectCountByColImpl.evaluate(array)
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val codes = children.map(_.genCode(ctx))
    val list = ctx.addMutableState("java.util.List<Byte[]>", s"bytesList",
      v => s"$v = new java.util.LinkedList();", forceInline = true)

    val ic = IntersectCountByColImpl.getClass.getName.stripSuffix("$")

    val builder = new StringBuilder()
    builder.append(s"$list.clear();\n")
    codes.map(_.value).foreach { code =>
      builder.append(s"$list.add($code);\n")
    }

    val resultCode =
      s"""
         ${builder.toString()}
         ${ev.value} = $ic.evaluate($list);"""

    builder.clear()
    codes.map(_.code).foreach { code =>
      builder.append(s"${code.code}\n")
    }

    ev.copy(code =
      code"""
        ${builder.toString()}
        ${CodeGenerator.javaType(dataType)} ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
        $resultCode""", isNull = FalseLiteral)
  }

  override def dataType: DataType = LongType

  override protected def withNewChildrenInternal(newChildren: IndexedSeq[Expression]): Expression =
    super.legacyWithNewChildren(newChildren)
}

case class PreciseCountDistinctDecode(_child: Expression)
  extends UnaryExpression with ExpectsInputTypes {

  override def child: Expression = _child

  override def inputTypes: Seq[AbstractDataType] = Seq(BinaryType)

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val expressionUtils = ExpressionUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (bytes) => {
      s"""$expressionUtils.preciseCountDistinctDecodeHelper($bytes)"""
    })
  }

  override protected def nullSafeEval(bytes: Any): Any = {
    ExpressionUtils.preciseCountDistinctDecodeHelper(bytes)
  }

  override def eval(input: InternalRow): Any = {
    if (input != null) {
      super.eval(input)
    } else {
      0L
    }
  }

  override def dataType: DataType = LongType

  override def prettyName: String = "precise_count_distinct_decode"

  override protected def withNewChildInternal(newChild: Expression): Expression =
    copy(_child = newChild)
}

case class ApproxCountDistinctDecode(expr: Expression, precision: Expression)
  extends BinaryExpression with ExpectsInputTypes {

  def left: Expression = expr

  def right: Expression = precision

  override def inputTypes: Seq[AbstractDataType] = Seq(BinaryType, IntegerType)

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val expressionUtils = ExpressionUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (bytes, precision) => {
      s"""$expressionUtils.approxCountDistinctDecodeHelper($bytes, $precision)"""
    })
  }

  override protected def nullSafeEval(bytes: Any, precision: Any): Any = {
    ExpressionUtils.approxCountDistinctDecodeHelper(bytes, precision)
  }

  override def eval(input: InternalRow): Any = {
    if (input != null) {
      super.eval(input)
    } else {
      0L
    }
  }

  override def dataType: DataType = LongType

  override def prettyName: String = "approx_count_distinct_decode"

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }
}

case class PercentileDecode(bytes: Expression, quantile: Expression, precision: Expression) extends TernaryExpression with ExpectsInputTypes {

  override def inputTypes: Seq[AbstractDataType] = Seq(BinaryType, DecimalType, IntegerType)

  override def first: Expression = bytes

  override def second: Expression = quantile

  override def third: Expression = precision

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val expressionUtils = ExpressionUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (bytes, quantile, precision) => {
      s"""$expressionUtils.percentileDecodeHelper($bytes, $quantile, $precision)"""
    })
  }

  override protected def nullSafeEval(bytes: Any, quantile: Any, precision: Any): Any = {
    ExpressionUtils.percentileDecodeHelper(bytes, quantile, precision)
  }

  override def dataType: DataType = DoubleType

  override def prettyName: String = "percentile_decode"

  override def nullable: Boolean = false

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression, newThird: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond, newThird)
    super.legacyWithNewChildren(newChildren)
  }
}

case class SumLCDecode(bytes: Expression, wrapDataTypeExpr: Expression) extends BinaryExpression with ExpectsInputTypes {
  override def left: Expression = bytes;

  override def right: Expression = wrapDataTypeExpr

  override def inputTypes: Seq[AbstractDataType] = Seq(BinaryType, StringType)

  def wrapDataType = DataType.fromJson(wrapDataTypeExpr.toString)

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val leftGen = left.genCode(ctx)
    val rightGen = right.genCode(ctx)
    val expressionUtils = ExpressionUtils.getClass.getName.stripSuffix("$")
    val decimalUtil = classOf[Decimal].getName
    val evalValue = ctx.freshName("evalValue")
    val javaType = CodeGenerator.javaType(dataType)
    val boxedJavaType = CodeGenerator.boxedType(javaType)
    val commonCodeBlock =
      code"""
        ${leftGen.code}
        ${rightGen.code}
        Number $evalValue = $expressionUtils.sumLCDecodeHelper(${leftGen.value}, ${rightGen.value});
        boolean ${ev.isNull} = $evalValue == null;
        $javaType ${ev.value} = ${CodeGenerator.defaultValue(dataType)};
          """
    val conditionCodeBlock = if (wrapDataType.isInstanceOf[DecimalType]) {
      code"""
        if(!${ev.isNull}) {
            ${ev.value} = $decimalUtil.fromDecimal($evalValue);
        }
          """
    } else {
      code"""
        if(!${ev.isNull}) {
            ${ev.value} = ($boxedJavaType) $evalValue;
        }
          """
    }
    ev.copy(code = commonCodeBlock + conditionCodeBlock)
  }

  override protected def nullSafeEval(bytes: Any, wrapDataTypeExpr: Any): Any = {
    val decodeVal = ExpressionUtils.sumLCDecodeHelper(bytes, wrapDataTypeExpr)
    wrapDataType match {
      case DecimalType() =>
        Decimal.fromDecimal(decodeVal.asInstanceOf[java.math.BigDecimal])
      case _ =>
        decodeVal
    }
  }

  override def dataType: DataType = wrapDataType

  override def prettyName: String = "sum_lc_decode"

  override def nullable: Boolean = true

  override protected def withNewChildrenInternal(newLeft: Expression, newRight: Expression): Expression = {
    val newChildren = Seq(newLeft, newRight)
    super.legacyWithNewChildren(newChildren)
  }
}

case class BitmapUuidToArray(_child: Expression) extends UnaryExpression with ExpectsInputTypes {
  override def child: Expression = _child

  override def inputTypes: Seq[AbstractDataType] = Seq(BinaryType)

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val expressionUtils = ExpressionUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, bytes => {
      s"""$expressionUtils.bitmapUuidToArray($bytes)"""
    })
  }

  override protected def nullSafeEval(bytes: Any): Any = {
    ExpressionUtils.bitmapUuidToArray(bytes)
  }

  override def eval(input: InternalRow): Any = {
    val bitmapByte = _child.eval(input)
    if (bitmapByte != null) {
      ExpressionUtils.bitmapUuidToArray(bitmapByte)
    } else {
      new GenericArrayData(new Array[Long](0))
    }
  }

  override def dataType: DataType = ArrayType(LongType, false)

  override def prettyName: String = "bitmap_uuid_to_array"

  override protected def withNewChildInternal(newChild: Expression): Expression =
    copy(_child = newChild)
}

case class YMDintBetween(first: Expression, second: Expression) extends BinaryExpression with ImplicitCastInputTypes {

  override def left: Expression = first

  override def right: Expression = second

  override def dataType: DataType = StringType

  override def inputTypes: Seq[AbstractDataType] = Seq(DateType, DateType)

  override protected def nullSafeEval(input1: Any, input2: Any): Any = {
    (first.dataType, second.dataType) match {
      case (DateType, DateType) =>
        UTF8String.fromString(TimeUtil.ymdintBetween(KapDateTimeUtils.daysToMillis(input1.asInstanceOf[Int]),
          KapDateTimeUtils.daysToMillis(input2.asInstanceOf[Int])))
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val td = classOf[TimeUtil].getName
    val dtu = KapDateTimeUtils.getClass.getName.stripSuffix("$")
    defineCodeGen(ctx, ev, (arg1, arg2) => {
      s"""org.apache.spark.unsafe.types.UTF8String.fromString($td.ymdintBetween($dtu.daysToMillis($arg1),
         |$dtu.daysToMillis($arg2)))""".stripMargin
    })
  }

  override protected def withNewChildrenInternal(newFirst: Expression, newSecond: Expression): Expression = {
    val newChildren = Seq(newFirst, newSecond)
    super.legacyWithNewChildren(newChildren)
  }
}

// support 2 or 3 param instr function, refer to StringLocate in Spark.
case class KylinInstr(str: Expression, substr: Expression, start: Expression)
  extends TernaryExpression with ImplicitCastInputTypes {

  def this(str: Expression, substr: Expression) = {
    this(str, substr, Literal(1))
  }

  override def first: Expression = str

  override def second: Expression = substr

  override def third: Expression = start

  override def nullable: Boolean = str.nullable || substr.nullable

  override def dataType: DataType = IntegerType

  override def inputTypes: Seq[DataType] = Seq(StringType, StringType, IntegerType)

  override def eval(input: InternalRow): Any = {
    val s = start.eval(input)
    if (s == null) {
      0
    } else {
      val r = substr.eval(input)
      if (r == null) {
        null
      } else {
        val l = str.eval(input)
        if (l == null) {
          null
        } else {
          val sVal = s.asInstanceOf[Int]
          if (sVal < 1) {
            0
          } else {
            l.asInstanceOf[UTF8String].indexOf(
              r.asInstanceOf[UTF8String],
              s.asInstanceOf[Int] - 1) + 1
          }
        }
      }
    }
  }

  override protected def doGenCode(ctx: CodegenContext, ev: ExprCode): ExprCode = {
    val strGen = str.genCode(ctx)
    val substrGen = substr.genCode(ctx)
    val startGen = start.genCode(ctx)
    ev.copy(code =
      code"""
      int ${ev.value} = 0;
      boolean ${ev.isNull} = false;
      ${startGen.code}
      if (!${startGen.isNull}) {
        ${substrGen.code}
        if (!${substrGen.isNull}) {
          ${strGen.code}
          if (!${strGen.isNull}) {
            if (${startGen.value} > 0) {
              ${ev.value} = ${strGen.value}.indexOf(${substrGen.value},
                ${startGen.value} - 1) + 1;
            }
          } else {
            ${ev.isNull} = true;
          }
        } else {
          ${ev.isNull} = true;
        }
      }
     """)
  }

  override def prettyName: String =
    getTagValue(FunctionRegistry.FUNC_ALIAS).getOrElse("instr")

  override protected def withNewChildrenInternal(newFirst: Expression,
                                                 newSecond: Expression,
                                                 newThird: Expression): KylinInstr =
    copy(str = newFirst, substr = newSecond, start = newThird)

}
