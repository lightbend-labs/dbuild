package distributed
package support
package git


object GitDetector extends CapabilityDetector {
  def detectLocal(): Seq[Capability] = 
    try {
      Git.version() match {
        case s @ Version(v) => Seq(Capability("git", v, Map("--version" -> s)))
        case _ => Seq.empty
      }
    } catch {
      case e: Exception => Seq.empty
    }
    
  private object Version {
    val Regex = new util.matching.Regex(".*\\s([\\d]+\\.[\\d]+\\.[\\d]+(\\.[\\d]+)?).*")
    def unapply(in: String): Option[String] = in match {
      case Regex(v, _) => Some(v)
      case _ => None
    }
  }
}