/*
 * Copyright (c) 2021 Couchbase, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.couchbase.spark.kv

import com.couchbase.client.core.error.DocumentExistsException
import com.couchbase.client.scala.codec.RawJsonTranscoder
import com.couchbase.client.scala.durability.Durability
import com.couchbase.client.scala.json.JsonObject
import com.couchbase.client.scala.kv.{InsertOptions, UpsertOptions}
import com.couchbase.spark.DefaultConstants
import com.couchbase.spark.config.{CouchbaseConfig, CouchbaseConnection}
import org.apache.spark.api.java.function.ForeachPartitionFunction
import org.apache.spark.internal.Logging
import org.apache.spark.sql.{DataFrame, SQLContext, SaveMode, SparkSession}
import org.apache.spark.sql.sources.{BaseRelation, CreatableRelationProvider, DataSourceRegister}
import org.apache.spark.sql.types.StructType
import reactor.core.scala.publisher.{SFlux, SMono}

import scala.collection.JavaConverters._
import java.util
import scala.concurrent.duration.Duration

class KeyValueTableProvider extends Logging with DataSourceRegister with CreatableRelationProvider {

  private lazy val sparkSession = SparkSession.active
  private lazy val conf = CouchbaseConfig(sparkSession.sparkContext.getConf)

  override def shortName(): String = "couchbase.kv"

  override def createRelation(ctx: SQLContext, mode: SaveMode, parameters: Map[String, String], data: DataFrame): BaseRelation = {
    val couchbaseConfig = CouchbaseConfig(ctx.sparkContext.getConf)
    data.toJSON.foreachPartition(new RelationPartitionWriter(writeConfig(parameters.asJava), couchbaseConfig, mode))

    if (mode == SaveMode.Append) {
      throw new IllegalArgumentException("SaveMode.Append is not supported - please use the other SaveMode types.")
    }

    new BaseRelation {
      override def sqlContext: SQLContext = ctx
      override def schema: StructType = data.schema
    }
  }

  def writeConfig(properties: util.Map[String, String]): KeyValueWriteConfig = {
    KeyValueWriteConfig(
      conf.implicitBucketNameOr(properties.get(KeyValueOptions.Bucket)),
      conf.implicitScopeNameOr(properties.get(KeyValueOptions.Scope)),
      conf.implicitCollectionName(properties.get(KeyValueOptions.Collection)),
      Option(properties.get(KeyValueOptions.IdFieldName)).getOrElse(DefaultConstants.DefaultIdFieldName),
      Option(properties.get(KeyValueOptions.Durability)),
      Option(properties.get(KeyValueOptions.Timeout))
    )
  }

}

class RelationPartitionWriter(writeConfig: KeyValueWriteConfig, couchbaseConfig: CouchbaseConfig, mode: SaveMode)
  extends ForeachPartitionFunction[String]
    with Logging {
  override def call(t: util.Iterator[String]): Unit = {
    val scopeName = writeConfig.scope.getOrElse(DefaultConstants.DefaultScopeName)
    val collectionName = writeConfig.collection.getOrElse(DefaultConstants.DefaultCollectionName)

    val keyValues = t.asScala.map(encoded => {
      val decoded = JsonObject.fromJson(encoded)
      val id = decoded.str(writeConfig.idFieldName)
      decoded.remove(writeConfig.idFieldName)
      (id, decoded.toString)
    }).toList

    val coll = CouchbaseConnection()
      .cluster(couchbaseConfig)
      .bucket(writeConfig.bucket)
      .scope(scopeName)
      .collection(collectionName)

    val durability = writeConfig.durability match {
      case Some(v) if v == KeyValueOptions.MajorityDurability => Durability.Majority
      case Some(v) if v == KeyValueOptions.MajorityAndPersistToActiveDurability => Durability.MajorityAndPersistToActive
      case Some(v) if v == KeyValueOptions.PersistToMajorityDurability => Durability.PersistToMajority
      case None => Durability.Disabled
      case d => throw new IllegalArgumentException("Unknown/Unsupported durability provided: " + d)
    }

    SFlux
      .fromIterable(keyValues)
      .flatMap(v => {
        mode match {
          case SaveMode.Overwrite =>
            coll.reactive.upsert(v._1, v._2, buildUpsertOptions(durability))
          case SaveMode.ErrorIfExists =>
            coll.reactive.insert(v._1, v._2, buildInsertOptions(durability))
          case SaveMode.Ignore =>
            coll.reactive.insert(v._1, v._2, buildInsertOptions(durability)).onErrorResume(t => {
              if (t.isInstanceOf[DocumentExistsException]) {
                SMono.empty
              } else {
                SMono.error(t)
              }
            })
          case m =>
            throw new IllegalStateException("Unsupported SaveMode: " + m)
        }
      })
      .blockLast()
  }

  def buildInsertOptions(durability: Durability): InsertOptions = {
    var opts = InsertOptions().transcoder(RawJsonTranscoder.Instance).durability(durability)
    writeConfig.timeout.foreach(t => opts = opts.timeout(Duration(t)))
    opts
  }

  def buildUpsertOptions(durability: Durability): UpsertOptions = {
    var opts = UpsertOptions().transcoder(RawJsonTranscoder.Instance).durability(durability)
    writeConfig.timeout.foreach(t => opts = opts.timeout(Duration(t)))
    opts
  }

}