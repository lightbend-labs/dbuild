package distributed
package support

/** Represents something that can detect the capabilities of a local system. */
trait CapabilityDetector {
  def detectLocal(): Seq[Capability]
}

object CapabilityDetector extends CapabilityDetector {
  private val detectors: Seq[CapabilityDetector] = 
    Seq(git.GitDetector,
        java.JavaDetector)
  def detectLocal() = detectors flatMap (_.detectLocal())
}