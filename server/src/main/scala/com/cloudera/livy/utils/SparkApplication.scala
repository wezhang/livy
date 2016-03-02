/*
 * Licensed to Cloudera, Inc. under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  Cloudera, Inc. licenses this file
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

package com.cloudera.livy.utils

import java.util.UUID

import com.cloudera.livy.{LivyConf, Logging}

/**
 * Provide factory methods for SparkApplication.
 */
object SparkApplication extends Logging {
  def create(
      builder: SparkProcessBuilder,
      file: Option[String],
      args: List[String],
      livyConf: LivyConf): SparkApplication = {
    if (livyConf.isSparkMasterYarn) {
      val applicationTag = s"livy_${UUID.randomUUID()}"
      builder.conf("spark.yarn.tags", applicationTag)
      builder.conf("spark.yarn.maxAppAttempts", "1")
      val process = builder.start(file, args)
      new SparkYarnApplication(applicationTag, Option(process))
    } else {
      new SparkLocalApplication(builder.start(file, args))
    }
  }
}

/**
 * Encapsulate a Spark application.
 * It provides state tracking & logging.
 */
abstract class SparkApplication() {
  def stop(): Unit
  def log(): IndexedSeq[String]
  def waitFor(): Int
}