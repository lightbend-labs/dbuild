package distributed
package logging

import sbt.{Logger=> SbtLogger, LogEvent}
import sys.process.ProcessLogger
import sbt.Level._

trait Logger extends SbtLogger with ProcessLogger {
  def newNestedLogger(name: String): Logger
}
object Logger {
  def prepareLogMsg(log: Logger, t: Throwable) = {
    val errors = new java.io.StringWriter
    t.printStackTrace(new java.io.PrintWriter(errors));
    errors.toString.split("\n") foreach { log.error(_) }
    val msg1 = t.getClass.getSimpleName + (Option(t.getMessage) map { ": " + _.split("\n")(0) } getOrElse "")
    if (msg1.length < 42) msg1 else msg1.take(39) + "..."
  }
}


abstract class BasicLogger extends sbt.BasicLogger with Logger

/** Logs to an output stream. */
class StreamLogger(out: java.io.PrintStream) extends BasicLogger {
  def newNestedLogger(name: String): Logger = this
  def trace(t: => Throwable): Unit =
    out.synchronized {
      val traceLevel = getTrace
      if(traceLevel >= 0) out.print(sbt.StackTrace.trimmed(t, traceLevel))
    }
  def success(message: => String): Unit = 
    if(successEnabled) log(SuccessLabel, message)
  def buffer[T](t: =>T): T = t
  def err(s: => String): Unit =
    log(Error, s)
  
  def out(s: => String): Unit =
    log(Info.toString, s)
  def control(event: sbt.ControlEvent.Value, message: => String): Unit = 
    log(Info.toString, message)
  def logAll(events: Seq[LogEvent]) = out.synchronized { events foreach log }
  
  def log(level: sbt.Level.Value, message: => String): Unit =
    if(atLevel(level)) log(level.toString, message)
  private def log(label: String, message: String): Unit =
    out.synchronized {
      for(line <- message.split("""\n""")) {
        out.print("[")
        out.print(label)
        out.print("] ")
        out.print(line)
        out.println()
      }
    }
}

object ConsoleLogger {
  def apply(): Logger = new StreamLogger(System.out)
}