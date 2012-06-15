package distributed
package logging

import akka.actor.{ActorRef, Actor, Props}
import sbt.{Level}
import sbt.ControlEvent
import sbt.StackTrace

sealed trait LogCmd {
  def path: String
}
case class Log(path: String, level: Level.Value, msg: Function0[String]) extends LogCmd
case class Trace(path: String, trace: Function0[Throwable]) extends LogCmd

/** An ordered sequence of loggign commands to perform. */
case class LogCommands(cmds: Seq[LogCmd])


// TODO - Use SBT paths more often....
class ActorLogger(nested: ActorRef, path: String = "root") extends Logger {
  import Level._
  // TODO - Keep a reference of what's enabled locally...
  def newNestedLogger(name: String): Logger = {
    new ActorLogger(nested, path + "/" + name)
  }
  def trace(t: => Throwable): Unit = 
    nested ! Trace(path, () => t)
  def success(msg: => String): Unit =
    sendLog(Level.Info, msg)
  def buffer[T](t: => T): T = t  // TODO - buffer actor messages....
  def err(s: => String): Unit =
    sendLog(Error, s)
  def out(s: => String): Unit =
    sendLog(Info, s)  
  def control(event: sbt.ControlEvent.Value, msg: => String): Unit = ()
  def logAll(events: Seq[sbt.LogEvent]): Unit = {
    // TODO - convert into our actor logging messages....
  }
  def log(level: sbt.Level.Value, msg: => String): Unit =
    sendLog(level, msg)
  private def sendLog(level: Level.Value, msg: => String): Unit = 
    nested ! Log(path, level, () => msg)
}

object ActorLogHelper {
  def cleanPath(path: String) =
    path.replaceAll("""[\s/\\]+""", "-")
}

class LogDirManagerActor(logDir: java.io.File) extends Actor {
  // TODO - evicting cache and other magikz.
  var loggers = Map.empty[String, ActorRef]
  
   def receive = {
    case log: LogCmd =>
      findOrCreateLogger(log.path) ! log
  }
  
  def findOrCreateLogger(path: String): ActorRef =
    loggers get path getOrElse {
      val logger = context.actorOf(Props(new LoggerFileWriteActor(logDir, path)), "Logger-" + ActorLogHelper.cleanPath(path))
      loggers = loggers.updated(path, logger)
      logger
    }
}

// TODO - More class in this logger...
class LoggerFileWriteActor(logDir: java.io.File, path: String) extends Actor {
  import sbt.Path._
  def logFile = logDir / (ActorLogHelper.cleanPath(path) + ".log")
  def newWriter = {
    val f = logFile
    sbt.IO touch f
    new java.io.FileWriter(f)
  }
  var level: sbt.Level.Value = Level.Debug  
  var output = newWriter
  
  override def postStop() = output.close()
  
  override def preRestart(r: Throwable, msg: Option[Any]): Unit = {
    // TODO - just check for IO exception that's not closing
    try output.close catch { case _: java.io.IOException => () }
    output = newWriter
    output.write("Restarting log after " + msg + "!\n")
    output.write(r.getMessage)
    output.write("------ START LOGGING --------")
  } 
  
  def receive = {
    //case l: LogCmd if l.path != path =>
      // Make new logger for that path and forward messages to it...
    case l : LogCmd =>
      // TODO - Check paths...
      log(l)
  }
  
  
  def log(l: LogCmd): Unit = l match {
    case Trace(path, err) =>
      // TODO - Real trace levels...
      writeLog(sbt.StackTrace.trimmed(err(), 1))
    case Log(path, l, msg) if l >= level =>
      val sb = new StringBuffer()
      for(line <- msg().split("""\n"""")) {
        sb append ("[") append (l.toString) append("] ")
        sb append line append "\n"
      }
      writeLog(sb.toString)
    case _ => ()
  } 
    
   def writeLog(msg: String): Unit = {
     output write msg
     output.flush()
   }
}