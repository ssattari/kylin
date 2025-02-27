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

package org.apache.kylin.query.util

import org.apache.calcite.sql.SqlKind
import org.apache.kylin.guava30.shaded.common.collect.Maps
import org.apache.kylin.metadata.cube.cuboid.NLayoutCandidate
import org.apache.kylin.metadata.cube.model.NDataSegment
import org.apache.kylin.metadata.model.DeriveInfo.DeriveType
import org.apache.kylin.metadata.model.NonEquiJoinCondition.SimplifiedJoinCondition
import org.apache.kylin.metadata.model.util.scd2.Scd2Simplifier
import org.apache.kylin.metadata.model.{DeriveInfo, NDataModel, NTableMetadataManager, TblColRef}
import org.apache.kylin.metadata.table.InternalTableManager
import org.apache.kylin.metadata.tuple.TupleInfo
import org.apache.spark.sql.catalyst.plans.JoinType
import org.apache.spark.sql.catalyst.plans.logical.{Join, JoinHint, LogicalPlan}
import org.apache.spark.sql.derived.DerivedInfo
import org.apache.spark.sql.execution.utils.{DeriveTableColumnInfo, SchemaProcessor}
import org.apache.spark.sql.functions.col
import org.apache.spark.sql.manager.SparderLookupManager
import org.apache.spark.sql.{Column, SparkOperation}

import java.util
import scala.collection.JavaConverters._
import scala.collection.mutable

// scalastyle:off
case class SparderDerivedUtil(gtInfoTableName: String,
                              dataSeg: NDataSegment,
                              gtColIdx: Array[Int],
                              tupleInfo: TupleInfo,
                              layoutCandidate: NLayoutCandidate) {
  val hostToDerivedInfo: util.Map[util.List[Integer], util.List[DeriveInfo]] =
    layoutCandidate.makeHostToDerivedMap

  var hasDerived = false
  var hostToDeriveds: List[DerivedInfo] = List.empty

  val model: NDataModel = layoutCandidate.getLayoutEntity.getModel

  //  make hostToDerivedInfo with two keys prior to one keys
  //  so that joinDerived will choose LOOKUP prior to PK_FK derive
  hostToDerivedInfo.asScala.toList
    .flatMap(pair => {
      pair._2.asScala.map(di => (pair._1, di))
    })
    .sortBy(p => p._1.size())
    .reverse
    .foreach(entry => findDerivedColumn(entry._1, entry._2))

  val derivedColumnNameMapping: util.HashMap[DerivedInfo, Seq[String]] =
    Maps.newHashMap[DerivedInfo, Seq[String]]()

  def findDerivedColumn(hostColIds: util.List[Integer], deriveInfo: DeriveInfo): Unit = {

    //  for PK_FK derive on composite join keys, hostCols may be incomplete
    val hostCols = hostColIds.asScala.map(model.getColRef).toArray
    var hostFkCols: Array[TblColRef] = null
    if (deriveInfo.`type` == DeriveType.PK_FK) {
      if (deriveInfo.join.getForeignKeyColumns.contains(hostCols(0))) {
        // FK -> PK, most cases
        hostFkCols = deriveInfo.join.getForeignKeyColumns
      } else {
        hostFkCols = deriveInfo.join.getPrimaryKeyColumns
      }
    } else {
      hostFkCols = hostCols
    }

    val hostFkIdx = hostFkCols.map(hostCol => indexOnTheGTValues(hostCol))
    // fix for test src/kylin-it/src/test/resources/query/sql_rawtable/query03.sql
    if (!hostFkIdx.exists(_ >= 0)) {
      return
    }

    val hostIndex = hostCols.map(hostCol => indexOnTheGTValues(hostCol))
    if (hostIndex.exists(_ < 0)) {
      return
    }

    val calciteIdx = deriveInfo.columns.asScala
      .map(colId => {
        val column = model.getColRef(colId)
        if (tupleInfo.hasColumn(column)) {
          tupleInfo.getColumnIndex(column)
        } else {
          -1
        }
      })
      .filter(_ >= 0).toArray

    if (calciteIdx.isEmpty) {
      return
    }

    val (path, tableName, aliasTableName, pkIndex) =
      getLookupTablePathAndPkIndex(deriveInfo)

    val lookupIdx = deriveInfo.columns.asScala.map(model.getColRef).map(_.getColumnDesc.getZeroBasedIndex).toArray
    hostToDeriveds ++= List(
      DerivedInfo(hostIndex,
        hostFkIdx,
        pkIndex,
        calciteIdx,
        lookupIdx,
        path,
        tableName,
        aliasTableName,
        deriveInfo.join,
        deriveInfo.`type`))
    hasDerived = true
  }

  def indexOnTheGTValues(col: TblColRef): Int = {
    val cuboidIdx = indexOnTheCuboidValues(col)
    if (gtColIdx.contains(cuboidIdx)) {
      cuboidIdx
    } else {
      -1
    }
  }

  def indexOnTheCuboidValues(col: TblColRef): Int = {
    val cuboidDims = layoutCandidate.getLayoutEntity.getColumns
    val cuboidIdx = cuboidDims.indexOf(col)
    cuboidIdx
  }

  def joinDerived(plan: LogicalPlan): LogicalPlan = {
    var joinedPlan: LogicalPlan = plan
    val joinedLookups = scala.collection.mutable.Set[String]()

    for (hostToDerived <- hostToDeriveds) {
      if (hostToDerived.deriveType != DeriveType.PK_FK) {
        //  PK_FK derive does not need joining
        if (!joinedLookups.contains(hostToDerived.aliasTableName)) {
          joinedPlan = joinLookUpTable(plan.output.map(c => c.name).toArray,
            joinedPlan,
            hostToDerived)
          joinedLookups.add(hostToDerived.aliasTableName)
        }
      }
    }

    joinedPlan
  }

  def getLookupTablePathAndPkIndex(deriveInfo: DeriveInfo): (String, String, String, Array[Int]) = {
    val join = deriveInfo.join
    val metaMgr = NTableMetadataManager.getInstance(dataSeg.getConfig, dataSeg.getProject)
    val derivedTableName = join.getPKSide.getTableIdentity
    val pkCols = join.getPrimaryKey
    val tableDesc = metaMgr.getTableDesc(derivedTableName)
    val pkIndex = pkCols.map(pkCol => tableDesc.findColumnByName(pkCol).getZeroBasedIndex)
    val path: String = if (dataSeg.getConfig.isInternalTableEnabled && tableDesc.isHasInternal) {
      InternalTableManager.getInstance(dataSeg.getConfig, dataSeg.getProject)
        .getInternalTableDesc(tableDesc.getIdentity)
        .getLocation
    } else {
      tableDesc.getLastSnapshotPath
    }
    if (path == null && deriveInfo.`type` != DeriveType.PK_FK) {
      throw new IllegalStateException(
        "No snapshot for table '" + derivedTableName + "' found on cube segment"
          + dataSeg.getIndexPlan.getUuid + "/" + dataSeg)
    }
    (path,
      dataSeg.getProject + "@" + derivedTableName,
      s"${gtInfoTableName}_${join.getPKSide.getAlias}",
      pkIndex)
  }

  def joinLookUpTable(gTInfoNames: Array[String],
                      plan: LogicalPlan,
                      derivedInfo: DerivedInfo): LogicalPlan = {
    val lookupTableAlias = derivedInfo.aliasTableName

    val lookupPlan =
      SparderLookupManager.getOrCreate(derivedInfo.tableIdentity,
        derivedInfo.path,
        dataSeg.getConfig)

    val newNames = lookupPlan.output
      .map(c => SchemaProcessor.parseDeriveTableSchemaName(c.name))
      .sortBy(_.columnId)
      .map(
        deriveInfo =>
          DeriveTableColumnInfo(lookupTableAlias,
            deriveInfo.columnId,
            deriveInfo.columnName).toString)
    derivedColumnNameMapping.put(derivedInfo, newNames)
    val newNameLookupPlan = SparkOperation.projectAsAlias(newNames, lookupPlan)
    if (derivedInfo.fkIdx.length != derivedInfo.pkIdx.length) {
      throw new IllegalStateException(
        s"unequal host key num ${derivedInfo.fkIdx.length} " +
          s"vs derive pk num ${derivedInfo.pkIdx.length} ")
    }
    val zipIndex = derivedInfo.fkIdx.zip(derivedInfo.pkIdx)
    var joinCol: Column = null
    zipIndex.foreach {
      case (hostIndex, pkIndex) =>
        if (joinCol == null) {
          joinCol = col(gTInfoNames.apply(hostIndex))
            .equalTo(col(newNames(pkIndex)))
        } else {
          joinCol = joinCol.and(
            col(gTInfoNames.apply(hostIndex))
              .equalTo(col(newNames(pkIndex))))
        }
    }

    if (derivedInfo.deriveType == DeriveType.LOOKUP_NON_EQUI) {
      val simplifiedCond = Scd2Simplifier.INSTANCE
        .simplifyScd2Conditions(derivedInfo.join)
        .getSimplifiedNonEquiJoinConditions
      joinCol = genNonEquiJoinColumn(newNameLookupPlan, gTInfoNames, joinCol, simplifiedCond.asScala)
    }
    Join(plan, newNameLookupPlan, JoinType.apply(derivedInfo.join.getType), Option(joinCol.expr), JoinHint.NONE)
  }

  def genNonEquiJoinColumn(newNameLookupPlan: LogicalPlan,
                           gTInfoNames: Array[String],
                           colOrigin: Column,
                           simplifiedConds: mutable.Buffer[SimplifiedJoinCondition]): Column = {

    var joinCol = colOrigin
    for (simplifiedCond <- simplifiedConds) {
      val colFk = col(gTInfoNames.apply(indexOnTheCuboidValues(simplifiedCond.getFk)))
      val colPk = col(newNameLookupPlan.output.apply(simplifiedCond.getPk.getColumnDesc.getZeroBasedIndex).name)
      val colOp = simplifiedCond.getOp

      val newCol = colOp match {
        case SqlKind.LESS_THAN_OR_EQUAL => colFk.<=(colPk)
        case SqlKind.GREATER_THAN_OR_EQUAL => colFk.>=(colPk)
        case SqlKind.LESS_THAN => colFk.<(colPk)
        case SqlKind.GREATER_THAN => colFk.>(colPk)
      }

      joinCol = joinCol.and(newCol)
    }

    joinCol

  }
}
