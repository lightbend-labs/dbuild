// This wrapper class is used to keep track of running Process instances,
// so that they can be preemptively killed if needed, for example if a
// timeout occurs. One wrapper can keep track of only one Process at a time,
// so there will be usually one wrapper per build/extraction worker.
package com.typesafe.dbuild.utils
import sys.process._

class TrackedProcessBuilder {
  var interrupted = false
  var processOpt: Option[Process] = None

  def abort(): Unit = synchronized {
    interrupted = true
    processOpt map { _.destroy() }
  }

  def reset(): Unit = synchronized {
    interrupted = false
    processOpt = None
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

  // Technically we shouldn't keep
  // tracker instances alive by adding
  // them to a list. However, in the usual
  // case of a single extractor and a single
  // builder, the grand total number of trackers
  // will be two. We can change indexing
  // mechanism, adding a better controller
  // object, in case we start using more
  // extractors and builders, or in the
  // (remote) case we should start creating
  // them dynamically, for some reason.
  TrackedProcessBuilder.record(this)
}

object TrackedProcessBuilder {
  val trackers = new scala.collection.mutable.MutableList[TrackedProcessBuilder]

  def record(t:TrackedProcessBuilder) = synchronized {
    trackers += t
  }

  def abortAll() = synchronized {
    trackers foreach { _.abort() }
  }
}
