package distributed
package support
package mvn

import _root_.distributed.logging.{Logger => DLogger}
import _root_.sbt.Level
import org.apache.maven.execution.ExecutionListener
import org.apache.maven.execution.ExecutionEvent
import org.sonatype.aether.transfer.TransferListener
import org.sonatype.aether.transfer.TransferEvent
import org.codehaus.plexus.DefaultContainerConfiguration
import org.codehaus.plexus.DefaultPlexusContainer
import org.codehaus.plexus.logging.{
  LoggerManager => PLoggerManager,
  Logger => PLogger
}
import org.apache.maven.eventspy.internal.EventSpyDispatcher
import org.apache.maven.cli.DefaultEventSpyContext


// TODO - Maybe we don't use nested loggers for this?
class DLoggerManager(log: DLogger) extends PLoggerManager {
  def getLoggerForComponent( role: String): PLogger =
    new DLogDelegatingPLogger(log newNestedLogger role)
  def getLoggerForComponent( role: String, hint: String ): PLogger =
    new DLogDelegatingPLogger(log newNestedLogger (role+":"+hint))
  def returnComponentLogger( role: String ): Unit = ()
  def returnComponentLogger( role: String, hint: String): Unit = ()
  
  def getThreshold: Int = PLogger.LEVEL_INFO
  def setThreshold(threshold: Int): Unit = ()
  def setThresholds(threshold: Int ): Unit = ()
  def getActiveLoggerCount: Int = 0  
}

class DLogDelegatingPLogger(log: DLogger) extends PLogger {
  def debug(msg: String) = log.debug(msg)
  def debug(msg: String, t: Throwable) = {
    log.debug(msg)
    log.trace(t)
  }
  def info(msg: String) = log.info(msg)
  def info(msg: String, t: Throwable) = {
    log.info(msg)
    log.trace(t)
  }
  def warn(msg: String) = log.warn(msg)
  def warn(msg: String, t: Throwable) = {
    log.warn(msg)
    log.trace(t)
  }
  def error(msg: String) = log.error(msg)
  def error(msg: String, t: Throwable) = {
    log.error(msg)
    log.trace(t)
  }
  def fatalError(msg: String) = log.error(msg)
  def fatalError(msg: String, t: Throwable) = {
    log.error(msg)
    log.trace(t)
  }
  def isDebugEnabled = true
  def isInfoEnabled = true
  def isErrorEnabled = true
  def isFatalErrorEnabled = true
  def isWarnEnabled = true
  def getThreshold = PLogger.LEVEL_INFO
  def setThreshold(t: Int): Unit = ()
  def getName = ""
  def getChildLogger(name: String) = 
    new DLogDelegatingPLogger(log newNestedLogger name)
}

class TransferLoggerListener(log: DLogger) extends TransferListener {
  def transferInitiated(event: TransferEvent): Unit =
    logEvent(event, "initiated", Level.Debug)
  def transferStarted(event: TransferEvent): Unit =
    logEvent(event, "started")
  def transferProgressed(event: TransferEvent): Unit = ()
  def transferCorrupted(event: TransferEvent): Unit =
    logEvent(event, "corrupted", Level.Error)
  def transferSucceeded(event: TransferEvent): Unit =
    logEvent(event, "success")
  def transferFailed(event: TransferEvent): Unit =
    logEvent(event, "failed", Level.Error)

  private def logEvent(e: TransferEvent, msg: String, level: Level.Value = Level.Info): Unit = {
    val sb = new StringBuilder("[mvn ")
    sb append (if(e.getRequestType == TransferEvent.RequestType.GET) "GET "
               else "PUT ")
    sb append e.getResource.getRepositoryUrl
    sb append e.getResource.getResourceName
    sb append "] "
    sb append msg
    log.log(level, sb.toString)
  }
}

class ExecutionLoggerListener(log: DLogger) extends ExecutionListener {
  def projectDiscoveryStarted(event: ExecutionEvent): Unit =
    logEvent(event, "discovery.", Level.Debug)
  def sessionStarted(event: ExecutionEvent): Unit =
    logEvent(event, "started.", Level.Debug)
  def sessionEnded(event: ExecutionEvent): Unit =
    logEvent(event, "ended.", Level.Debug)
  def projectSkipped(event: ExecutionEvent): Unit =
    logEvent(event, "skipped.", Level.Debug)
  def projectStarted(event: ExecutionEvent): Unit =
    logEvent(event, " project started")
  def projectSucceeded(event: ExecutionEvent): Unit =
    logEvent(event, " project success")
  def projectFailed(event: ExecutionEvent): Unit =
    logEvent(event, "failed.", Level.Error)
  def mojoSkipped(event: ExecutionEvent): Unit =
    logEvent(event, "skipped.", Level.Debug)
  def mojoStarted(event: ExecutionEvent): Unit =
    logEvent(event, "started.", Level.Debug)
  def mojoSucceeded(event: ExecutionEvent): Unit =
    logEvent(event, "success.", Level.Debug)
  def mojoFailed(event: ExecutionEvent): Unit =
    logEvent(event, " mojo failed!", Level.Error)
  def forkStarted(event: ExecutionEvent): Unit =
    logEvent(event, "forked project started.", Level.Debug)
  def forkSucceeded(event: ExecutionEvent): Unit =
    logEvent(event, "fork success.", Level.Info)
  def forkFailed(event: ExecutionEvent): Unit =
    logEvent(event, "fork failed.", Level.Error)
  def forkedProjectStarted(event: ExecutionEvent): Unit =
    logEvent(event, "forked project started.", Level.Debug)
  def forkedProjectSucceeded(event: ExecutionEvent): Unit =
    logEvent(event, "forked project success.", Level.Info)
  def forkedProjectFailed(event: ExecutionEvent): Unit =
    logEvent(event, "forked project failed.", Level.Error)
  
  private def logEvent(e: ExecutionEvent, msg: String, logLevel: Level.Value = Level.Info): Unit = {
    val sb = new StringBuilder("[ mvn ")
    if(e.getProject != null) {
      sb append e.getProject.getArtifactId
      sb append " "
    }
    if(e.getMojoExecution != null) {
      sb append e.getMojoExecution.getArtifactId
      sb append ":"
      sb append e.getMojoExecution.getGoal
      sb append " "
    }
    sb append "] "
    sb append msg
    log.log(logLevel, sb.toString)
    if(e.getException != null)
      log.trace(e.getException)
  }
}