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

import com.couchbase.client.scala.kv.{MutateInOptions, MutateInResult, MutateInSpec}
import com.couchbase.spark.config.{CouchbaseConfig, CouchbaseConnection}
import com.couchbase.spark.{DefaultConstants, Keyspace}
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.{Partition, SparkContext, TaskContext}

case class MutateIn(id: String, specs: Seq[MutateInSpec])

class MutateInRDD(@transient private val sc: SparkContext, val docs: Seq[MutateIn], val keyspace: Keyspace, mutateInOptions: MutateInOptions = null)
  extends RDD[MutateInResult](sc, Nil)
    with Logging {

  private val globalConfig = CouchbaseConfig(sparkContext.getConf)
  private val bucketName = globalConfig.implicitBucketNameOr(this.keyspace.bucket.orNull)

  override def compute(split: Partition, context: TaskContext): Iterator[MutateInResult] = {
    val connection = CouchbaseConnection()
    val cluster = connection.cluster(globalConfig)

    val scopeName = globalConfig
      .implicitScopeNameOr(this.keyspace.scope.orNull).
      getOrElse(DefaultConstants.DefaultScopeName)
    val collectionName = globalConfig
      .implicitCollectionName(this.keyspace.collection.orNull)
      .getOrElse(DefaultConstants.DefaultCollectionName)

    val collection = cluster.bucket(bucketName).scope(scopeName).collection(collectionName)
    val options = if (this.mutateInOptions == null) {
      MutateInOptions()
    } else {
      this.mutateInOptions
    }

    logDebug(s"Performing bulk MutateIn against docs $docs with options $options")

    docs.map(doc => collection.mutateIn(doc.id, doc.specs, options).get).iterator
  }

  override protected def getPartitions: Array[Partition] = {
    val partitions = KeyValuePartition
      .partitionsForIds(docs.map(d => d.id), CouchbaseConnection(), globalConfig, bucketName)
      .asInstanceOf[Array[Partition]]

    logDebug(s"Calculated KeyValuePartitions for MutateIn operation ${partitions.mkString("Array(", ", ", ")")}")
    partitions
  }

  override protected def getPreferredLocations(split: Partition): Seq[String] = {
    split.asInstanceOf[KeyValuePartition].location match {
      case Some(l) => Seq(l)
      case _ => Nil
    }
  }

}