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

package com.cloudera.livy.sessions

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.Future

import com.cloudera.livy.utils.AppInfo

abstract class Session(
    val id: Int,
    val owner: String,
    // This is an unique identifier for the session across all session types.
    // Recovery uses it to find the app in the cluster.
    val uuid: String = UUID.randomUUID().toString,
    // spark.app.id when it's available.
    protected var _appId: Option[String] = None) {

  private var _lastActivity = System.nanoTime()

  def lastActivity: Long = state match {
    case SessionState.Error(time) => time
    case SessionState.Dead(time) => time
    case SessionState.Success(time) => time
    case _ => _lastActivity
  }

  val timeout: Long = TimeUnit.HOURS.toNanos(1)

  var appInfo: AppInfo = AppInfo()

  def appId: Option[String] = _appId

  def state: SessionState

  def stop(): Future[Unit]

  def recordActivity(): Unit = {
    _lastActivity = System.nanoTime()
  }

  def logLines(): IndexedSeq[String]
}
