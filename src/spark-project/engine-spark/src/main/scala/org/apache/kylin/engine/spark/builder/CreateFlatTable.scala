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

package org.apache.kylin.engine.spark.builder

import org.apache.commons.lang3.StringUtils
import org.apache.kylin.engine.spark.builder.DFBuilderHelper._
import org.apache.kylin.engine.spark.job.NSparkCubingUtil._
import org.apache.kylin.engine.spark.job.step.build.FlatTableStage
import org.apache.kylin.engine.spark.job.{FlatTableHelper, TableMetaManager}
import org.apache.kylin.engine.spark.utils.SparkDataSource._
import org.apache.kylin.engine.spark.utils.{LogEx, LogUtils}
import org.apache.kylin.guava30.shaded.common.collect.Sets
import org.apache.kylin.metadata.cube.cuboid.NSpanningTree
import org.apache.kylin.metadata.cube.model.{NCubeJoinedFlatTableDesc, NDataSegment}
import org.apache.kylin.metadata.model._
import org.apache.spark.dict.NGlobalDictionaryV2.NO_VERSION_SPECIFIED
import org.apache.spark.sql.functions.{col, expr}
import org.apache.spark.sql.{Dataset, Row, SparkSession}

import java.util.Locale
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.parallel.ForkJoinTaskSupport
import scala.concurrent.forkjoin.ForkJoinPool


@Deprecated
class CreateFlatTable(val flatTable: IJoinedFlatTableDesc,
                      var seg: NDataSegment,
                      val toBuildTree: NSpanningTree,
                      val ss: SparkSession,
                      val sourceInfo: NBuildSourceInfo) extends LogEx {

  import org.apache.kylin.engine.spark.builder.CreateFlatTable._

  def generateDataset(needEncode: Boolean = false, needJoin: Boolean = true): Dataset[Row] = {
    val model = flatTable.getDataModel
    val table = model.getRootFactTable
    val ccCols = table.getColumns.asScala.filter(_.getColumnDesc.isComputedColumn).toSet
    var rootFactDataset = generateTableDataset(table, ccCols.toSeq, table.getAlias, ss, sourceInfo)
    lazy val flatTableInfo = Map(
      "segment" -> seg,
      "table" -> table.getTableIdentity,
      "join_lookup" -> needJoin,
      "build_global_dictionary" -> needEncode
    )
    logInfo(s"Create a flat table: ${LogUtils.jsonMap(flatTableInfo)}")

    rootFactDataset = FlatTableHelper.applyPartitionDesc(flatTable, rootFactDataset, true)

    (needJoin, needEncode) match {
      case (true, true) =>
        val (dictCols, encodeCols): GlobalDictType = assemblyGlobalDictTuple(seg, toBuildTree)
        rootFactDataset = encodeWithCols(rootFactDataset, ccCols, dictCols, encodeCols)
        val cleanLookupCC = cleanComputColumn(ccCols.toSeq, rootFactDataset.columns.toSet)
        val encodedLookupMap = generateLookupTableDataset(model, cleanLookupCC, ss)
          .map(lp => (lp._1, encodeWithCols(lp._2, cleanLookupCC.toSet, dictCols, encodeCols)))

        if (encodedLookupMap.nonEmpty) {
          generateDimensionTableMeta(encodedLookupMap)
        }
        val allTableDataset = Seq(rootFactDataset) ++ encodedLookupMap.values

        rootFactDataset = joinFactTableWithLookupTables(rootFactDataset, encodedLookupMap, model, ss)
        rootFactDataset = encodeWithCols(rootFactDataset,
          filterCols(allTableDataset, ccCols),
          filterCols(allTableDataset, dictCols),
          filterCols(allTableDataset, encodeCols))
      case (true, false) =>
        val cleanLookupCC = cleanComputColumn(ccCols.toSeq, rootFactDataset.columns.toSet)
        val lookupTableDatasetMap = generateLookupTableDataset(model, cleanLookupCC, ss)

        rootFactDataset = joinFactTableWithLookupTables(rootFactDataset, lookupTableDatasetMap, model, ss)
        rootFactDataset = withColumn(rootFactDataset, ccCols)
      case (false, true) =>
        val (dictCols, encodeCols) = assemblyGlobalDictTuple(seg, toBuildTree)
        rootFactDataset = encodeWithCols(rootFactDataset, ccCols, dictCols, encodeCols)
      case _ =>
    }

    if (needEncode) {
      // checkpoint dict after all encodeWithCols actions done
      DFBuilderHelper.checkPointSegment(seg, (copied: NDataSegment) => copied.setDictReady(true))
    }

    rootFactDataset = FlatTableHelper.applyFilterCondition(flatTable, rootFactDataset, true)

    flatTable match {
      case joined: NCubeJoinedFlatTableDesc =>
        changeSchemeToColumnIndice(rootFactDataset, joined)
      case unsupported =>
        throw new UnsupportedOperationException(
          s"Unsupported flat table desc type : ${unsupported.getClass}.")
    }
  }

  protected def encodeWithCols(ds: Dataset[Row],
                               ccCols: Set[TblColRef],
                               dictCols: Set[TblColRef],
                               encodeCols: Set[TblColRef]): Dataset[Row] = {
    val ccDataset = withColumn(ds, ccCols)
    var buildVersion = System.currentTimeMillis()
    if (seg.isDictReady) {
      logInfo(s"Skip already built dict, segment: ${seg.getId} of dataflow: ${seg.getDataflow.getId}")
      buildVersion = NO_VERSION_SPECIFIED
    } else {
      buildDict(ccDataset, dictCols, buildVersion)
    }
    encodeColumn(ccDataset, encodeCols, buildVersion)
  }

  private def withColumn(ds: Dataset[Row], withCols: Set[TblColRef]): Dataset[Row] = {
    val matchedCols = selectColumnsInTable(ds, withCols)
    var withDs = ds
    matchedCols.foreach(m => withDs = withDs.withColumn(convertFromDot(m.getBackTickIdentity),
      expr(convertFromDot(m.getBackTickExp))))
    withDs
  }

  private def buildDict(ds: Dataset[Row], dictCols: Set[TblColRef], buildVersion: Long): Unit = {
    val matchedCols = if (seg.getIndexPlan.isSkipEncodeIntegerFamilyEnabled) {
      filterOutIntegerFamilyType(ds, dictCols)
    } else {
      selectColumnsInTable(ds, dictCols)
    }
    val builder = new DFDictionaryBuilder(ds, seg, ss, Sets.newHashSet(matchedCols.asJavaCollection))
    builder.buildDictSet(buildVersion)
  }

  private def encodeColumn(ds: Dataset[Row], encodeCols: Set[TblColRef], buildVersion: Long): Dataset[Row] = {
    val matchedCols = selectColumnsInTable(ds, encodeCols)
    var encodeDs = ds
    if (matchedCols.nonEmpty) {
      encodeDs = DFTableEncoder.encodeTable(ds, seg, matchedCols.asJava, buildVersion)
    }
    encodeDs
  }
}

@Deprecated
object CreateFlatTable extends LogEx {
  type GlobalDictType = (Set[TblColRef], Set[TblColRef])


  private def generateTableDataset(tableRef: TableRef,
                                   cols: Seq[TblColRef],
                                   alias: String,
                                   ss: SparkSession,
                                   sourceInfo: NBuildSourceInfo = null) = {
    var dataset: Dataset[Row] =
      if (sourceInfo != null && !StringUtils.isBlank(sourceInfo.getViewFactTablePath)) {
        ss.read.parquet(sourceInfo.getViewFactTablePath)
      } else {
        ss.table(tableRef.getTableDesc).alias(alias)
      }
    val suitableCols = chooseSuitableCols(dataset, cols)
    dataset = changeSchemaToAliasDotName(dataset, alias)
    val selectedCols = dataset.schema.fields.map(tp => col(tp.name)) ++ suitableCols
    logInfo(s"Table ${tableRef.getAlias} schema ${dataset.schema.treeString}")
    dataset.select(selectedCols: _*)
  }

  private def generateDimensionTableMeta(lookupTables: mutable.LinkedHashMap[JoinTableDesc, Dataset[Row]]): Unit = {
    val lookupTablePar = lookupTables.par
    lookupTablePar.tasksupport = new ForkJoinTaskSupport(new ForkJoinPool(lookupTablePar.size))
    lookupTablePar.foreach { case (joinTableDesc, dataset) =>
      val tableIdentity = joinTableDesc.getTable
      logTime(s"count $tableIdentity") {
        val rowCount = dataset.count()
        TableMetaManager.putTableMeta(tableIdentity, 0L, rowCount)
        logInfo(s"put meta table: $tableIdentity , count: ${rowCount}")
      }
    }
  }

  def generateLookupTableDataset(model: NDataModel,
                                 cols: Seq[TblColRef],
                                 ss: SparkSession): mutable.LinkedHashMap[JoinTableDesc, Dataset[Row]] = {
    val lookupTables = mutable.LinkedHashMap[JoinTableDesc, Dataset[Row]]()
    model.getJoinTables.asScala.map(
      joinDesc => {
        val lookupTable = generateTableDataset(joinDesc.getTableRef, cols.toSeq, joinDesc.getAlias, ss)
        lookupTables.put(joinDesc, lookupTable)
      }
    )
    lookupTables
  }

  def joinFactTableWithLookupTables(rootFactDataset: Dataset[Row],
                                    lookupTableDatasetMap: mutable.Map[JoinTableDesc, Dataset[Row]],
                                    model: NDataModel,
                                    ss: SparkSession): Dataset[Row] = {
    lookupTableDatasetMap.foldLeft(rootFactDataset)(
      (joinedDataset: Dataset[Row], tuple: (JoinTableDesc, Dataset[Row])) =>
        joinTableDataset(model.getRootFactTable.getTableDesc, tuple._1, joinedDataset, tuple._2, ss))
  }

  def joinFactTableWithLookupTables(rootFactDataset: Dataset[Row],
                                    lookupTableDatasetMap: java.util.LinkedHashMap[JoinTableDesc, Dataset[Row]],
                                    model: NDataModel,
                                    ss: SparkSession): Dataset[Row] = {
    joinFactTableWithLookupTables(rootFactDataset, lookupTableDatasetMap.asScala, model, ss)
  }

  def joinTableDataset(rootFactDesc: TableDesc,
                       lookupDesc: JoinTableDesc,
                       rootFactDataset: Dataset[Row],
                       lookupDataset: Dataset[Row],
                       ss: SparkSession): Dataset[Row] = {
    var afterJoin = rootFactDataset
    val join = lookupDesc.getJoin
    if (join != null && !StringUtils.isEmpty(join.getType)) {
      val joinType = join.getType.toUpperCase(Locale.ROOT)
      val pk = join.getPrimaryKeyColumns
      val fk = join.getForeignKeyColumns
      if (pk.length != fk.length) {
        throw new RuntimeException(
          s"Invalid join condition of fact table: $rootFactDesc,fk: ${fk.mkString(",")}," +
            s" lookup table:$lookupDesc, pk: ${pk.mkString(",")}")
      }
      logInfo(s"Lookup table schema ${lookupDataset.schema.treeString}")

      val condition = FlatTableStage.getCondition(join)
      val nonEquiv = if (join.getNonEquiJoinCondition == null) "" else "non-equi "
      logInfo(s"Root table ${rootFactDesc.getIdentity},"
        + s" join table ${lookupDesc.getAlias},"
        + s" ${nonEquiv}condition: ${condition.toString()}")
      afterJoin = afterJoin.join(lookupDataset, condition, joinType)
    }
    afterJoin
  }

  def changeSchemeToColumnIndice(ds: Dataset[Row], flatTable: NCubeJoinedFlatTableDesc): Dataset[Row] = {
    val structType = ds.schema
    val colIndices = flatTable.getIndices.asScala
    val columnNameToIndex = flatTable.getAllColumns
      .asScala
      .map(column => convertFromDot(column.getBackTickIdentity))
      .zip(colIndices)
    val columnToIndexMap = columnNameToIndex.toMap
    val encodeSeq = structType.filter(_.name.endsWith(ENCODE_SUFFIX)).map {
      tp =>
        val originNam = tp.name.replaceFirst(ENCODE_SUFFIX, "")
        val index = columnToIndexMap.apply(originNam)
        col(tp.name).alias(index.toString + ENCODE_SUFFIX)
    }
    val columns = columnNameToIndex.map(tp => expr(tp._1).alias(tp._2.toString))
    logInfo(s"Select model column is ${columns.mkString(",")}")
    logInfo(s"Select model encoding column is ${encodeSeq.mkString(",")}")
    val selectedColumns = columns ++ encodeSeq

    logInfo(s"Select model all column is ${selectedColumns.mkString(",")}")
    ds.select(selectedColumns: _*)
  }

  def replaceDot(original: String, model: NDataModel): String = {
    val sb = new StringBuilder(original)

    for (namedColumn <- model.getAllNamedColumns.asScala) {
      val colName = namedColumn.getAliasDotColumn.toLowerCase(Locale.ROOT)
      doReplaceDot(sb, colName, namedColumn.getAliasDotColumn)

      // try replacing quoted identifiers if any
      val quotedColName = colName.split('.').mkString("`", "`.`", "`");
      if (quotedColName.nonEmpty) {
        doReplaceDot(sb, quotedColName, namedColumn.getAliasDotColumn.split('.').mkString("`", "`.`", "`"))
      }
    }
    sb.toString()
  }

  private def doReplaceDot(sb: StringBuilder, namedCol: String, colAliasDotColumn: String) = {
    var start = sb.toString.toLowerCase(Locale.ROOT).indexOf(namedCol)
    while (start != -1) {
      sb.replace(start,
        start + namedCol.length,
        convertFromDot(colAliasDotColumn))
      start = sb.toString.toLowerCase(Locale.ROOT)
        .indexOf(namedCol)
    }
  }

  def assemblyGlobalDictTuple(seg: NDataSegment, toBuildTree: NSpanningTree): GlobalDictType = {
    val toBuildDictSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDictToBuild(seg, toBuildTree.getAllIndexEntities)
    val globalDictSet = DictionaryBuilderHelper.extractTreeRelatedGlobalDicts(seg, toBuildTree.getAllIndexEntities)
    (toBuildDictSet.asScala.toSet, globalDictSet.asScala.toSet)
  }

  def changeSchemaToAliasDotName(original: Dataset[Row],
                                 alias: String): Dataset[Row] = {
    val aliasConverted = convertFromDot(alias)
    val sf = original.schema.fields
    val newSchema = sf
      .map(field => convertFromDot("`" + aliasConverted + "`" + "." + "`" + field.name + "`"))
      .toSeq
    val newdf = original.toDF(newSchema: _*)
    logInfo(s"After change alias from ${original.schema.treeString} to ${newdf.schema.treeString}")
    newdf
  }


  def colName(col: TblColRef): String = {
    col.getTableAlias + "_" + col.getName
  }

  // For lookup table, CC column may be duplicate of the flat table when it dosen't belong to one specific table like '1+2'
  def cleanComputColumn(cc: Seq[TblColRef],
                        flatCols: Set[String]): Seq[TblColRef] = {
    var cleanCols = cc
    if (flatCols != null) {
      cleanCols = cc.filter(col => !flatCols.contains(convertFromDot(col.getBackTickIdentity)))
    }
    cleanCols
  }
}
