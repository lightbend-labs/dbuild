package distributed
package logging

import sbt.{ Logger => SbtLogger, LogEvent }
import sys.process.ProcessLogger
import sbt.Level._

trait Logger extends SbtLogger with ProcessLogger {
  def newNestedLogger(name: String, projName: String = ""): Logger
}
object Logger {
  private def processLogMsg(log: Logger, t: Throwable, short: Boolean) = {
    // usually, prepareLogMsg only prints the first
    // few lines of the stack trace. However, a graph cycle
    // description may be longer than that, so we print out
    // the description separately
    val longDescription = t match {
      case e:graph.CycleException =>
        e.description.split("\n") foreach {log.error(_)}
        true
      case _ => false
    }
    val errors = new java.io.StringWriter
    val pw = new java.io.PrintWriter(errors)
    t.printStackTrace(pw)
    val errStack=errors.toString.split("\n")
    (if (short) errStack.take(12) else errStack) foreach { log.error(_) }
    val msg1 = t.getClass.getSimpleName + (Option(t.getMessage) map { ": " + _.split("\n")(0) } getOrElse "")
    if (msg1.length < 60) msg1 else msg1.take(57) + "..."
  }
  def prepareLogMsg(log: Logger, t: Throwable): String = processLogMsg(log, t, true)
  def logFullStackTrace(log: Logger, t: Throwable): Unit = processLogMsg(log, t, false)
}


object ConsoleLogger {
  def apply(debug: Boolean): Logger = new StreamLogger(System.out, debug)
}

//
//  Below this line, unused code
//

abstract class BasicLogger extends sbt.BasicLogger with Logger

/** Logs to an output stream. */
class StreamLogger(out: java.io.PrintStream, debug: Boolean) extends BasicLogger {

  if (debug) setLevel(Debug)

  def newNestedLogger(name: String, projName: String): Logger = this
  def trace(t: => Throwable): Unit =
    out.synchronized {
      val traceLevel = getTrace
      if (traceLevel >= 0) out.print(sbt.StackTrace.trimmed(t, traceLevel))
    }
  def success(message: => String): Unit =
    if (successEnabled) log(SuccessLabel, message)
  def buffer[T](t: => T): T = t
  def err(s: => String): Unit =
    log(Error, s)

  def out(s: => String): Unit =
    log(Info.toString, s)
  def control(event: sbt.ControlEvent.Value, message: => String): Unit =
    log(Info.toString, message)
  def logAll(events: Seq[LogEvent]) = out.synchronized { events foreach log }

  def log(level: sbt.Level.Value, message: => String): Unit =
    if (atLevel(level)) log(level.toString, message)
  private def log(label: String, message: String): Unit =
    out.synchronized {
      for (line <- message.split("""\n""")) {
        out.print("[")
        out.print(label)
        out.print("] ")
        out.print(line)
        out.println()
      }
    }
}
