package distributed.build

import java.io.File

case class Creds(host: String, user: String, pass: String)
object Creds {
  def loadCreds(fromPath: String): Creds = {
    import util.control.Exception.catching

    def loadProps(f: File): Option[_root_.java.util.Properties] =
      catching(classOf[_root_.java.io.IOException]) opt {
        val props = new _root_.java.util.Properties()
        props.load(new _root_.java.io.FileReader(f))
        props
      }

    val propsFile = new File(fromPath)
    (for {
      f <- if (propsFile.exists) Some(propsFile) else sys.error("Credentials file not found: " + propsFile)
      props <- loadProps(f)
      host <- Option(props get "host")
      user <- Option(props get "user")
      pass <- Option(props get "password")
    } yield Creds(host.toString, user.toString, pass.toString)) getOrElse sys.error("Unable to load properties from " + propsFile)
  }
}
