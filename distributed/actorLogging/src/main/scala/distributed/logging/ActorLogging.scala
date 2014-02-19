package distributed
package logging

import akka.actor.{ActorRef, Actor, Props, PoisonPill, Terminated}
import sbt.{ 
  Level, 
  LogEvent,
  Success,
  Log,
  Trace,
  SetLevel,
  SetTrace,
  SetSuccess,
  ControlEvent,
  StackTrace
}

/** A command to the logging system to log a given event into a particular path. */
case class LogCmd(path: String, evt: LogEvent)


/** A logger that sends LogCmds to an actor rather than doing anything directly. */
class ActorLogger(nested: ActorRef, path: String = "root") extends Logger {
  import Level._
  // TODO - Keep a reference of what's enabled locally...
  def newNestedLogger(name: String): Logger = {
    new ActorLogger(nested, path + "/" + name)
  }
  def trace(t: => Throwable): Unit = 
    sendLogEvent(new Trace(t))
  def success(msg: => String): Unit =
    sendLog(Level.Info, msg)
  def buffer[T](t: => T): T = t  // TODO - buffer actor messages....
  def err(s: => String): Unit =
    sendLog(Error, s)
  def out(s: => String): Unit =
    sendLog(Info, s)  
  def control(event: sbt.ControlEvent.Value, msg: => String): Unit =
    sendLogEvent(new ControlEvent(event, msg))
  def logAll(events: Seq[sbt.LogEvent]): Unit = {
    events foreach sendLogEvent
  }
  def log(level: sbt.Level.Value, msg: => String): Unit =
    sendLog(level, msg)
  private def sendLog(level: Level.Value, msg: => String): Unit = 
    sendLogEvent(new Log(level, msg))
  private def sendLogEvent(event: LogEvent): Unit =
    nested ! LogCmd(path, event)
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
    case "exit" =>
      loggers.values.foreach { _ ! PoisonPill }
    case Terminated(p) =>
      val name = p.path.name
      if (!loggers.valuesIterator.contains(p))
        sys.error("Internal error: loggers of LogDirManagerActor does not contain " + p.path)
      loggers = loggers.filterNot(_._2 == p)
      if (loggers.isEmpty) {
        context.stop(self)
      }
  }

  def findOrCreateLogger(path: String): ActorRef =
    loggers get path getOrElse {
      val logger = context.actorOf(Props(new LoggerFileWriteActor(logDir, path)), "Logger-" + ActorLogHelper.cleanPath(path))
      context.watch(logger)
      loggers = loggers.updated(path, logger)
      logger
    }
}

/** Helper for logger implementations to write out LogCmds. */
trait LogToOutput {
  def log(l: LogCmd): Unit = l.evt match {
    case t: Trace =>
      // TODO - Real trace levels...
      writeLog(sbt.StackTrace.trimmed(t.exception, 1))
    case l: Log if l.level >= level =>
      val sb = new StringBuffer()
      for(line <- l.msg.split("""\n"""")) {
        sb append ("[") append (l.level.toString) append("] ")
        sb append line append "\n"
      }
      writeLog(sb.toString)
    case _ => ()  // Ignore all else...
  } 
  
  def writeLog(in: String): Unit
  def level: sbt.Level.Value
}

/** An actor that takes LogCmd messages and writes them to a file. */
class LoggerFileWriteActor(logDir: java.io.File, path: String) extends Actor with LogToOutput {
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
  
  override def preStart() = {
    output.write("------ LOG - "+path+" --------\n")
  }
  
  override def preRestart(r: Throwable, msg: Option[Any]): Unit = {
    // TODO - just check for IO exception that's not closing
    try output.close catch { case _: java.io.IOException => () }
    output = newWriter
    output.write("Restarting log after " + msg + "!\n")
    output.write(r.getMessage)
    output.write("\n------ "+path+" --------\n")
  } 
  
  def receive = {
    //case l: LogCmd if l.path != path =>
      // Make new logger for that path and forward messages to it...
    case l : LogCmd =>
      // TODO - Check paths...
      log(l)
  }
    
   def writeLog(msg: String): Unit = {
     output write msg
     output.flush()
   }
}



/** An actor that takes LogCmd messages and writes them to a file. */
class SystemOutLoggerActor(debug: Boolean) extends Actor with LogToOutput {
  import sbt.Path._
  val output = System.out  
  val level = if (debug) Level.Debug else Level.Info
  
  def receive = {
    case l : LogCmd => log(l)
    case "exit" =>
      context.stop(self)
  }
    
   def writeLog(msg: String): Unit = {
     output print msg
     output.flush()
   }
}

/** An actor that chains to other loggers. */
class ChainedLoggerSupervisorActor extends Actor {
  var loggers: Seq[ActorRef] = List.empty
  var terminationSender:ActorRef = self
  def receive = {
    case p: Props => 
      val logger = context actorOf p
      context.watch(logger)
      loggers = logger +: loggers
      sender ! logger
    case l: LogCmd => loggers foreach (_ forward l)
    case "exit" =>
      terminationSender = sender
      loggers.foreach { _ ! "exit" }
    case Terminated(p) =>
      if (!(loggers.contains(p)))
        sys.error("Internal error: loggers of ChainedLoggerSupervisorActor does not contain " + p.path)
      loggers = loggers.filter(_ != p)
      if (loggers.isEmpty) {
        terminationSender ! "stopped"
        context.stop(self)
      }
  }
}