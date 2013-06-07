package distributed
package build

import sbt._
import Path._
import project.model._
import repo.core._
import java.io.File
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol

object DeployBuild {

  // first, a proper sanity check
  def checkDeployFullBuild(deployOptions: Option[Seq[DeployOptions]]) = {
    deployOptions map { optionsSeq =>
      val log = sbt.ConsoleLogger()
      optionsSeq foreach { options =>
        val uri = new _root_.java.net.URI(options.uri)
        uri.getScheme match {
          case "file" =>
            if (options.credentials != None) log.warn("Credentials will be ignored while deploying to " + uri)
          case "http" | "https" | "s3" =>
            options.credentials match {
              case None => sys.error("Credentials are required when deploying to " + uri)
              case Some(credsFile) =>
                if (loadCreds(credsFile).host != uri.getHost)
                  sys.error("The credentials file " + credsFile + " does not contain information for host " + uri.getHost)
            }
          case scheme => sys.error("Unknown scheme in deploy: " + scheme)
        }
      }
    }
  }

  private case class Creds(host: String, user: String, pass: String)

  private def loadCreds(fromPath: String): Creds = {
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

  private def isNotChecksum(path: String): Boolean =
    !(path.endsWith(".sha1") || path.endsWith(".md5"))

  def deployStuff[T](options: DeployOptions, dir: File, log: Logger, init: Creds => T,
    message: (Logger, String) => Unit, deploy: (T, Creds, File, java.net.URI) => Unit) {
    val Some(credsFile) = options.credentials
    val credentials = loadCreds(credsFile)
    val handler = init(credentials)
    val targetBaseURI = new java.net.URI(options.uri)
    // First, we upload the main files,
    // and only afterwards we can upload md5 and sha1 files
    // (otherwise we get 404 errors from Artifactory)
    // Note that a 409 error means the checksum calculated on
    // the server does not match the checksum we are trying to upload
    val sorted = (dir.***).get.filter(f => !f.isDirectory).partition(f => isNotChecksum(f.getName))
    (sorted._1 ++ sorted._2) foreach { file =>
      // IO.relativize seems not to do what I need
      val relative = dir.toURI.relativize(file.toURI).getPath
      message(log, relative)
      // see http://help.eclipse.org/indigo/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/URIUtil.html#append(java.net.URI,%20java.lang.String)
      val targetURI = org.eclipse.core.runtime.URIUtil.append(targetBaseURI, relative)
      deploy(handler, credentials, file, targetURI)
    }
  }

  def deployFullBuild(build: RepeatableDistributedBuild) = {
    build.deployOptions map { optionsSeq =>
      val log = sbt.ConsoleLogger()
      optionsSeq foreach { options =>
        // we need to retrieve the artifacts from the repository
        // for build.uuid. We stage them in a temporary directory first,
        // to make sure all is in order
        IO.withTemporaryDirectory { dir =>
          val cache = Repository.default
          val projList = options.projects match {
            case None => build.repeatableBuilds map { _.config.name }
            case Some(list) => list
          }
          log.info("")
          log.info("Deploying to " + options.uri + ": " + projList.mkString("", ", ", "."))
          projList map { proj =>
            build.repeatableBuilds.find(_.config.name == proj) match {
              case None => sys.error("Error during deploy: \"" + proj + "\" is a project name.")
              case Some(p) =>
                val arts = LocalRepoHelper.materializeProjectRepository(p.uuid, cache, dir)
                log.info("Retrieved from project " + proj + ": " + arts.length + " artifacts")
            }
          }
          // dir is staged; time to deploy
          val uri = new _root_.java.net.URI(options.uri)
          uri.getScheme match {
            case "file" =>
              // copy to a local path
              val target = uri.getPath
              log.info("Copying artifacts to " + target + "...")
              // Overwrite, and preserve timestamps
              IO.copyDirectory(dir, new File(target), true, true)

            case "http" | "https" =>
              // deploy to a Maven repository
              deployStuff[Unit](options, dir, log, { _ => () },
                { (log, relative) =>
                  if (isNotChecksum(relative))
                    log.info("Deploying: " + relative)
                  else
                    log.info("Verifying checksum: " + relative)
                }, { (_, credentials, file, uri) =>
                  import dispatch._
                  val sender =
                    url(uri.toString).PUT.as(credentials.user, credentials.pass) <<< (file, "application/octet-stream")
                  (new Http with NoLogging)(sender >|)
                })

            case "s3" =>
              // deploy to S3
              deployStuff[AmazonS3Client](options, dir, log, { credentials =>
                new AmazonS3Client(new BasicAWSCredentials(credentials.user, credentials.pass),
                  new ClientConfiguration().withProtocol(Protocol.HTTPS))
              }, { (log, relative) =>
                if (isNotChecksum(relative))
                  log.info("Uploading: " + relative)
              }, { (client, credentials, file, uri) =>
                // putObject() will automatically calculate an MD5, upload, and compare with the response
                // from the server. Any upload failure results in an exception; so no need to process
                // the sha1/md5 here.
                if (isNotChecksum(uri.getPath))
                  client.putObject(new PutObjectRequest(credentials.host, uri.getPath.replaceFirst("^/", ""), file))
              })
          }
        }
      }
    }
  }
}
