// This wrapper class is used to keep track of running Process instances,
// so that they can be preemptively killed if needed, for example if a
// timeout occurs. One wrapper can keep track of only one Process at a time,
// so there will be usually one wrapper per build/extraction worker.
package com.typesafe.dbuild.project
import sys.process._

class TrackedProcessBuilder {
  var interrupted = false
  var processOpt: Option[Process] = None

  def abort(): Unit = synchronized {
    interrupted = true
    processOpt map { _.destroy() }
  }

  def !!(pb: ProcessBuilder, log: ProcessLogger)  = slurp(pb, Some(log), withIn = false)
  def !(pb: ProcessBuilder, log: ProcessLogger)  = runBuffered(pb, log, connectInput = false)

  private def runBuffered(pb: ProcessBuilder, log: ProcessLogger, connectInput: Boolean) =
    log.buffer { track(pb.run(log, connectInput)) }

  private def slurp(pb: ProcessBuilder, log: Option[ProcessLogger], withIn: Boolean): String = {
    val buffer = new StringBuffer
    val code   = track(pb.run(BasicIO(withIn, buffer, log)))
    if (code == 0) buffer.toString
    else scala.sys.error("Nonzero exit value: " + code)
  }

  private def track(f: => Process): Int = {
    val p = synchronized {
      if (interrupted)
        sys.error("Cannot start process: interrupted")
      else {
        val pr = f
        processOpt = Some(pr)
        pr
      }
    }
    val exitVal = p.exitValue()
    synchronized {
      processOpt = None
    }
    exitVal
  }
}
