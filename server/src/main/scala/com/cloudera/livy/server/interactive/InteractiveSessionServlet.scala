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

package com.cloudera.livy.server.interactive

import java.net.URL
import java.util.concurrent.TimeUnit
import javax.servlet.http.HttpServletRequest

import scala.concurrent._
import scala.concurrent.duration._

import org.json4s.jackson.Json4sScalaModule
import org.scalatra._

import com.cloudera.livy.{ExecuteRequest, LivyConf, Logging}
import com.cloudera.livy.recovery.SessionStore
import com.cloudera.livy.server.SessionServlet
import com.cloudera.livy.server.testpoint.TestpointManager
import com.cloudera.livy.sessions._
import com.cloudera.livy.sessions.interactive.Statement

object InteractiveSessionServlet extends Logging

class InteractiveSessionServlet(
    sessionManager: SessionManager[InteractiveSession],
    sessionStore: SessionStore,
    livyConf: LivyConf)
  extends SessionServlet[InteractiveSession](livyConf, sessionManager)
  with SessionHeartbeatNotifier[InteractiveSession]
{

  mapper.registerModule(new SessionKindModule())
    .registerModule(new Json4sScalaModule())

  override protected def createSession(req: HttpServletRequest): InteractiveSession = {
    val createRequest = bodyAs[CreateInteractiveRequest](req)
    InteractiveSession.create(
      sessionManager.nextId(),
      remoteUser(req),
      livyConf,
      createRequest,
      sessionStore)
  }

  override protected def clientSessionView(
      session: InteractiveSession,
      req: HttpServletRequest): Any = {
    val logs =
      if (isOwner(session, req)) {
        val lines = session.logLines()

        val size = 10
        var from = math.max(0, lines.length - size)
        val until = from + size

        lines.view(from, until)
      } else {
        Nil
      }

    Map(
      "id" -> session.id,
      "state" -> session.state.toString,
      "kind" -> session.kind.toString,
      "proxyUser" -> session.proxyUser,
      "appId" -> session.appId,
      "appInfo" -> session.appInfo,
      "log" -> logs)
  }

  private def statementView(statement: Statement): Any = {
    val output = try {
      Await.result(statement.output(), Duration(100, TimeUnit.MILLISECONDS))
    } catch {
      case _: TimeoutException => null
    }
    Map(
      "id" -> statement.id,
      "state" -> statement.state.toString,
      "output" -> output)
  }

  jpost[CallbackRequest]("/:id/callback") { callback =>
    withUnprotectedSession { session =>
      if (session.state == SessionState.Starting()) {
        TestpointManager.get.checkpoint("InteractiveSessionServlet.set.beforeStoringCallback")
        session.url = new URL(callback.url)
        Accepted()
      } else if (session.state.isActive) {
        BadRequest("Callback for this session has previously been registered.")
      } else {
        BadRequest("Session is in wrong state")
      }
    }
  }

  post("/:id/stop") {
    withSession { session =>
      val future = session.stop()
      new AsyncResult() { val is = for { _ <- future } yield NoContent() }
    }
  }

  post("/:id/interrupt") {
    withSession { session =>
      val future = for {
        _ <- session.interrupt()
      } yield Ok(Map("msg" -> "interrupted"))

      // FIXME: this is silently eating exceptions.
      new AsyncResult() { val is = future }
    }
  }

  get("/:id/statements") {
    withSession { session =>
      val from = params.get("from").map(_.toInt).getOrElse(0)
      val size = params.get("size").map(_.toInt).getOrElse(session.statements.length)

      Map(
        "total_statements" -> session.statements.length,
        "statements" -> session.statements.view(from, from + size).map(statementView)
      )
    }
  }

  val getStatement = get("/:id/statements/:statementId") {
    withSession { session =>
      val statementId = params("statementId").toInt
      val from = params.get("from").map(_.toInt)
      val size = params.get("size").map(_.toInt)

      session.statements.lift(statementId) match {
        case None => NotFound("Statement not found")
        case Some(statement) =>
          statementView(statement)
      }
    }
  }

  jpost[ExecuteRequest]("/:id/statements") { req =>
    withSession { session =>
      if (session.state == SessionState.Busy())
      {
        InternalServerError("Server still busy running previous statement.")
      }
      else
      {
        val statement = session.executeStatement(req)

        Created(statementView(statement),
          headers = Map(
            "Location" -> url(getStatement,
              "id" -> session.id.toString,
              "statementId" -> statement.id.toString)))
      }
    }
  }

}

private case class CallbackRequest(url: String)
