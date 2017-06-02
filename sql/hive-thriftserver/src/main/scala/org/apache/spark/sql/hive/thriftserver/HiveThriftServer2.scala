/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.hive.thriftserver

import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import org.apache.commons.logging.LogFactory
import org.apache.hadoop.hive.conf.HiveConf
import org.apache.hadoop.hive.conf.HiveConf.ConfVars
import org.apache.hive.service.cli.thrift.{ThriftBinaryCLIService, ThriftHttpCLIService}
import org.apache.hive.service.server.HiveServer2
import org.apache.spark.deploy.SparkHadoopUtil
import org.apache.spark.internal.Logging
import org.apache.spark.scheduler.{SparkListener, SparkListenerApplicationEnd, SparkListenerJobStart}
import org.apache.spark.sql.hive.HiveUtils
import org.apache.spark.sql.hive.thriftserver.ui.ThriftServerTab
import org.apache.spark.sql.internal.SQLConf
import org.apache.spark.util.{ShutdownHookManager, Utils}
import org.apache.spark.{SparkConf, SparkContext}

/**
 * The main entry point for the Spark SQL port of HiveServer2.  Starts up a `SparkSQLContext` and a
 * `HiveThriftServer2` thrift server.
 */
object HiveThriftServer2 extends Logging {
  var LOG = LogFactory.getLog(classOf[HiveServer2])
  var uiTabs: List[ThriftServerTab] = Nil
  
  var serverListeners = new mutable.HashMap[String, HiveThriftServer2Listener]()

  def main(args: Array[String]) {
    Utils.initDaemon(log)
    val optionsProcessor = new HiveServer2.ServerOptionsProcessor("HiveThriftServer2")
    optionsProcessor.parse(args)

    logInfo("Starting SparkContext")
    MultiSparkSQLEnv.init()

    ShutdownHookManager.addShutdownHook { () =>
      MultiSparkSQLEnv.stop()
      uiTabs.foreach(_.detach())
    }
    
    val sparkConf = MultiSparkSQLEnv.originConf

    val executionHive = HiveUtils.newClientForExecution(
      sparkConf,
      SparkHadoopUtil.get.newConfiguration(sparkConf))

    try {
      val server = new HiveThriftServer2
      server.init(executionHive.conf)
      server.start()
      logInfo("HiveThriftServer2 started")
      
      MultiSparkSQLEnv.userToSession.asScala.foreach(userSession => {
        val user = userSession._1
        val ss = userSession._2
        
        val serverListener = new HiveThriftServer2Listener(server, sparkConf)
        serverListeners(user) = serverListener
        ss.sparkContext.addSparkListener(serverListener)
        
        val uiTab = new ThriftServerTab(user, ss.sparkContext)
        uiTabs = uiTab :: uiTabs
      })

      // Trigger the applicationStart event
      MultiSparkSQLEnv.userToSession.values().asScala.foreach{ ss =>
        ss.sparkContext.triggerApplicationStart()
      }

      // If application was killed before HiveThriftServer2 start successfully then SparkSubmit
      // process can not exit, so check whether if SparkContext was stopped.
      if (!MultiSparkSQLEnv.userToSession.asScala.forall { case ((_, ss)) =>
        !ss.sparkContext.isStopped}) {
        logError("SparkContext has stopped even if HiveServer2 has started, so exit")
        System.exit(-1)
      }
    } catch {
      case e: Exception =>
        logError("Error starting HiveThriftServer2", e)
        System.exit(-1)
    }
  }

  private[thriftserver] class SessionInfo(
      val sessionId: String,
      val startTimestamp: Long,
      val ip: String,
      val userName: String) {
    var finishTimestamp: Long = 0L
    var totalExecution: Int = 0
    def totalTime: Long = {
      if (finishTimestamp == 0L) {
        System.currentTimeMillis - startTimestamp
      } else {
        finishTimestamp - startTimestamp
      }
    }
  }

  private[thriftserver] object ExecutionState extends Enumeration {
    val STARTED, COMPILED, FAILED, FINISHED = Value
    type ExecutionState = Value
  }

  private[thriftserver] class ExecutionInfo(
      val statement: String,
      val sessionId: String,
      val startTimestamp: Long,
      val userName: String) {
    var finishTimestamp: Long = 0L
    var executePlan: String = ""
    var detail: String = ""
    var state: ExecutionState.Value = ExecutionState.STARTED
    val jobId: ArrayBuffer[String] = ArrayBuffer[String]()
    var groupId: String = ""
    def totalTime: Long = {
      if (finishTimestamp == 0L) {
        System.currentTimeMillis - startTimestamp
      } else {
        finishTimestamp - startTimestamp
      }
    }
  }

  /**
   * An inner sparkListener called in sc.stop to clean up the HiveThriftServer2
   */
  private[thriftserver] class HiveThriftServer2Listener(
      val server: HiveServer2,
      val conf: SparkConf) extends AbstractHiveThriftServer2Listener {

    override def onApplicationEnd(applicationEnd: SparkListenerApplicationEnd): Unit = {
      if (MultiSparkSQLEnv.userToSession.asScala.forall {
        case ((_, ss)) => ss.sparkContext.isStopped
      }) {
        server.stop()
      }
    }
    private var onlineSessionNum: Int = 0
    private val sessionList = new mutable.LinkedHashMap[String, SessionInfo]
    private val executionList = new mutable.LinkedHashMap[String, ExecutionInfo]
    private val retainedStatements = conf.get(SQLConf.THRIFTSERVER_UI_STATEMENT_LIMIT)
    private val retainedSessions = conf.get(SQLConf.THRIFTSERVER_UI_SESSION_LIMIT)
    private var totalRunning = 0

    def getOnlineSessionNum: Int = synchronized { onlineSessionNum }

    def getTotalRunning: Int = synchronized { totalRunning }

    def getSessionList: Seq[SessionInfo] = synchronized { sessionList.values.toSeq }

    def getSession(sessionId: String): Option[SessionInfo] = synchronized {
      sessionList.get(sessionId)
    }

    def getExecutionList: Seq[ExecutionInfo] = synchronized { executionList.values.toSeq }

    override def onJobStart(jobStart: SparkListenerJobStart): Unit = synchronized {
      for {
        props <- Option(jobStart.properties)
        groupId <- Option(props.getProperty(SparkContext.SPARK_JOB_GROUP_ID))
        (_, info) <- executionList if info.groupId == groupId
      } {
        info.jobId += jobStart.jobId.toString
        info.groupId = groupId
      }
    }

    override  def onSessionCreated(ip: String, sessionId: String, userName: String = "UNKNOWN",
                      proxyUser: String = "UNKNOWN", rangerUser: String = "UNKNOWN"): Unit = {
      synchronized {
        val info = new SessionInfo(sessionId, System.currentTimeMillis, ip, userName)
        sessionList.put(sessionId, info)
        onlineSessionNum += 1
        trimSessionIfNecessary()
      }
    }

    override def onSessionClosed(sessionId: String): Unit = synchronized {
      sessionList(sessionId).finishTimestamp = System.currentTimeMillis
      onlineSessionNum -= 1
      trimSessionIfNecessary()
    }

    override def onStatementStart(
        id: String,
        sessionId: String,
        statement: String,
        groupId: String,
        userName: String = "UNKNOWN"): Unit = synchronized {
      val info = new ExecutionInfo(statement, sessionId, System.currentTimeMillis, userName)
      info.state = ExecutionState.STARTED
      executionList.put(id, info)
      trimExecutionIfNecessary()
      sessionList(sessionId).totalExecution += 1
      executionList(id).groupId = groupId
      totalRunning += 1
    }

    override def onStatementParsed(id: String, executionPlan: String): Unit = synchronized {
      executionList(id).executePlan = executionPlan
      executionList(id).state = ExecutionState.COMPILED
    }

    override def onStatementError(id: String, errorMessage: String, errorTrace: String): Unit = {
      synchronized {
        executionList(id).finishTimestamp = System.currentTimeMillis
        executionList(id).detail = errorMessage
        executionList(id).state = ExecutionState.FAILED
        totalRunning -= 1
        trimExecutionIfNecessary()
      }
    }

    def onStatementFinish(id: String): Unit = synchronized {
      executionList(id).finishTimestamp = System.currentTimeMillis
      executionList(id).state = ExecutionState.FINISHED
      totalRunning -= 1
      trimExecutionIfNecessary()
    }

    private def trimExecutionIfNecessary() = {
      if (executionList.size > retainedStatements) {
        val toRemove = math.max(retainedStatements / 10, 1)
        executionList.filter(_._2.finishTimestamp != 0).take(toRemove).foreach { s =>
          executionList.remove(s._1)
        }
      }
    }

    private def trimSessionIfNecessary() = {
      if (sessionList.size > retainedSessions) {
        val toRemove = math.max(retainedSessions / 10, 1)
        sessionList.filter(_._2.finishTimestamp != 0).take(toRemove).foreach { s =>
          sessionList.remove(s._1)
        }
      }

    }
  }
}

private[hive] class HiveThriftServer2 extends HiveServer2
  with ReflectedCompositeService {
  // state is tracked internally so that the server only attempts to shut down if it successfully
  // started, and then once only.
  private val started = new AtomicBoolean(false)

  override def init(hiveConf: HiveConf) {
    this.cliService = new SparkSQLCLIService(this)
    addService(cliService)
    this.thriftCLIService = if (isHTTPTransportMode(hiveConf)) {
      new ThriftHttpCLIService(cliService)
    } else {
      new ThriftBinaryCLIService(cliService)
    }
    addService(thriftCLIService)
    initCompositeService(hiveConf)
  }

  private def isHTTPTransportMode(hiveConf: HiveConf): Boolean = {
    val transportMode = hiveConf.getVar(ConfVars.HIVE_SERVER2_TRANSPORT_MODE)
    transportMode.toLowerCase(Locale.ENGLISH).equals("http")
  }


  override def start(): Unit = {
    super.start()
    started.set(true)
  }

  override def stop(): Unit = {
    if (started.getAndSet(false)) {
       super.stop()
    }
  }
}
