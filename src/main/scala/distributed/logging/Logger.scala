package distributed
package logging

import sbt.{Logger=> SbtLogger, LogEvent}
import sys.process.ProcessLogger
import sbt.Level._

trait Logger extends SbtLogger with ProcessLogger
object Logger {}


abstract class BasicLogger extends sbt.BasicLogger with Logger

/** Logs to an output stream. */
class StreamLogger(out: java.io.PrintStream) extends BasicLogger {
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