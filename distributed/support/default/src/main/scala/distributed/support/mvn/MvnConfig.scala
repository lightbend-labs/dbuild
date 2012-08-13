package distributed
package support
package mvn

import project.model._
import config.{ConfigRead, ConfigPrint}

/** Configuration used for SBT builds. */
case class MvnConfig(
  directory: String
  // TODO - Repositories...    
)
object MvnConfig {    
  implicit object Configured extends ConfigRead[MvnConfig] with ConfigPrint[MvnConfig] {
    import config._
    import ConfigPrint.makeMember
    import ConfigRead.readMember
    import _root_.sbt.Types.:+:
    import _root_.sbt.HNil
    private val defaultObj: ConfigObject =
      config.parseString("""{
        directory = ""
      }""").resolve.root
    private val Members = (
        readMember[String]("directory")
    )
    def apply(c: MvnConfig): String = {
      val sb = new StringBuffer("{")
      sb append makeMember("directory", c.directory)
      sb append "}"
      sb.toString
    }
    def unapply(c: ConfigValue): Option[MvnConfig] = 
      (c withFallback defaultObj) match {
        case Members(directory) =>
          Some(MvnConfig(directory))
        case _ => None
      }
  } 
}