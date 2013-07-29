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
import dispatch.classic.{Logger =>_,_}
import com.jsuereth.pgp.{PGP,SecretKey}
import org.bouncycastle.openpgp.{PGPSecretKeyRingCollection,PGPSecretKeyRing}

object DeployBuild {

  // first, a proper sanity check
  def checkDeployFullBuild(deployOptions: Option[Seq[DeployOptions]]) = {
    deployOptions map { optionsSeq =>
      // TODO: get a different logger (ConsoleLogger prints escape sequences)
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
    message: (Logger, String) => Unit, deploy: (T, Creds, String, File, java.net.URI) => Unit) {
    val Some(credsFile) = options.credentials
    val credentials = loadCreds(credsFile)
    val handler = init(credentials)
    val targetBaseURI = new java.net.URI(options.uri)
    //
    // We have to upload files in a certain order, in order to comply with
    // the requirements of Artifactory concerning the upload of -SNAPSHOT artifacts.
    // In particular, whenever there are a .pom and a .jar with the same name, and
    // other artifacts in the same group, these artifacts get the same id only if:
    // - the jar is uploaded first
    // - the pom is uploaded immediately afterward
    // - then the remaining artifacts are uploaded
    // - finally, the checksums are uploaded after the main artifacts
    //

    val allFiles=(dir.***).get.filter(f => !f.isDirectory)
    val poms=allFiles.filter(f=>f.getName.endsWith("-SNAPSHOT.pom"))
    //
    // we fold over the poms, reducing the set of files until we are left with just
    // the files that are not in any pom-containing directory. Meanwhile, we accumulate
    // the snapshot files in the right order, for each pom
    val (remainder,newSeq)=poms.foldLeft((allFiles,Seq[File]())){ case ((fileSeq,newSeq),pom) =>
      val pomFile=pom.getCanonicalFile()
      val thisDir=pomFile.getParentFile()
      if (thisDir==null) sys.error("Unexpected: file has not parent in deploy")
      // select the files in this directory (but not in subdirectories)
      val theseFiles=(thisDir.***).get.filter(f => !f.isDirectory && f.getCanonicalFile().getParentFile()==thisDir)
      // if there is a matching jar, upload the jar first
      val jarFile=new java.io.File(pomFile.getCanonicalPath().replaceAll("\\.[^\\.]*$", ".jar"))
      val thisSeq=if (theseFiles contains jarFile) {
        val rest=theseFiles.diff(Seq(jarFile,pomFile)).partition(f => isNotChecksum(f.getName))
        Seq(jarFile,pomFile)++rest._1++rest._2
      } else {
        // no jar?? upload the pom first anyway
        val rest=theseFiles.diff(Seq(pomFile)).partition(f => isNotChecksum(f.getName))
        Seq(pomFile)++rest._1++rest._2
      }
      //
      (fileSeq.diff(theseFiles),newSeq++thisSeq)
    }

    // We need in any case to upload the main files first,
    // and only afterwards we can upload md5 and sha1 files
    // (otherwise we get 404 errors from Artifactory)
    // Note that a 409 error means the checksum calculated on
    // the server does not match the checksum we are trying to upload
    val split=remainder.partition(f => isNotChecksum(f.getName))
    val ordered=newSeq++split._1++split._2

    ordered foreach { file =>
      val relative = IO.relativize(dir,file) getOrElse sys.error("Internal error in relative paths creation during deployment. Please report.")
      message(log, relative)
      // see http://help.eclipse.org/indigo/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/URIUtil.html#append(java.net.URI,%20java.lang.String)
      val targetURI = org.eclipse.core.runtime.URIUtil.append(targetBaseURI, relative)
      deploy(handler, credentials, relative, file, targetURI)
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
            case None => build.repeatableBuilds map {b =>  DeployElementProject(b.config.name) }
            case Some(list) => list
          }
          log.info("")
          log.info("Deploying to " + options.uri + ": " + projList.mkString("", ", ", ""))
          projList map { proj =>
            build.repeatableBuilds.find(_.config.name == proj.name) match {
              case None => sys.error("Error during deploy: \"" + proj.name + "\" is not a project name.")
              case Some(p) =>
                val subprojs = proj match {
                  case DeployElementSubProject(DeploySubProjects(from,publish)) => publish
                  case DeployElementProject(_) => Seq.empty
                }
                val (arts,msg) = LocalRepoHelper.materializePartialProjectRepository(p.uuid, subprojs, cache, dir)
                msg foreach {log.info(_)}
            }
          }

          // Are we signing? If so, proceed
          options.sign foreach { signOptions =>
            val secretRingFile = signOptions.secretRing map {new File(_)} getOrElse (ProjectDirs.userhome / ".gnupg" / "secring.gpg")
            PGP.init
            val passPhrase = {
              val passPhraseFile = io.Source.fromFile(signOptions.passphrase)
              val p=passPhraseFile.getLines.next
              passPhraseFile.close()
              p
            }
            val secretKey = try signOptions.id match {
              case Some(keyID) =>
                log.info("Signing with key: "+keyID)
                new PGPSecretKeyRingCollection(new java.io.FileInputStream(secretRingFile)).getSecretKey(new java.math.BigInteger(keyID, 16).longValue)
              case None =>
                log.info("Signing with default key...")
                new PGPSecretKeyRing(new java.io.FileInputStream(secretRingFile)).getSecretKey
            } catch {
              case e: NumberFormatException => println("Not a valid hexadecimal value: " + signOptions.id.get); throw (e)
            }
            if (secretKey==null) sys.error("The key was not found in the pgp keyring.")
            (dir.***).get.filter(f => !f.isDirectory && isNotChecksum(f.getName)) foreach { f =>
              SecretKey(secretKey).sign(f,new File(f.getAbsolutePath()+".asc"),passPhrase.toArray)
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
                }, { (_, credentials, relative, file, uri) =>
                  import dispatch._
                  val sender =
                    url(uri.toString).PUT.as(credentials.user, credentials.pass) <<< (file, "application/octet-stream")
                  val response=(new Http with NoLogging)(sender >- { str =>
                   Utils.readSomePath[ArtifactoryResponse](str)
                 })
                 if (response!=None && response.get.path!=None) {
                   val out=response.get.path.get.replaceFirst("^/","")
                   if (out!=relative) log.info("Deployed:  "+out)
                 }
                })

            case "s3" =>
              // deploy to S3
              deployStuff[AmazonS3Client](options, dir, log, { credentials =>
                new AmazonS3Client(new BasicAWSCredentials(credentials.user, credentials.pass),
                  new ClientConfiguration().withProtocol(Protocol.HTTPS))
              }, { (log, relative) =>
                if (isNotChecksum(relative))
                  log.info("Uploading: " + relative)
              }, { (client, credentials, _, file, uri) =>
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
// Response from Artifactory
case class ArtifactoryResponse(path:Option[String])
