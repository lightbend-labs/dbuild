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
import dispatch.classic.{ Logger => _, _ }
import com.jsuereth.pgp.{ PGP, SecretKey }
import org.bouncycastle.openpgp.{ PGPSecretKeyRingCollection, PGPSecretKeyRing }
import org.omg.PortableInterceptor.SUCCESSFUL

class DeployBuild(conf: DBuildConfiguration, log: Logger)  extends OptionTask {
  def beforeBuild() = {
    conf.options.deploy foreach { d =>
      // just a sanity check on the project list (we don't use the result)
      val _ = d.flattenAndCheckProjectList(conf.build.projects.map{_.name}.toSet)
    }
    checkDeployFullBuild(conf.options.deploy, log)
  }
  def afterBuild(repBuild:RepeatableDistributedBuild,outcome:BuildOutcome) = {
    deployFullBuild(conf, repBuild, outcome, log)
  }

  // first, a proper sanity check
  def checkDeployFullBuild(optionsSeq: Seq[DeployOptions], log: Logger) = {
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

    val allFiles = (dir.***).get.filter(f => !f.isDirectory)
    val poms = allFiles.filter(f => f.getName.endsWith("-SNAPSHOT.pom"))
    //
    // we fold over the poms, reducing the set of files until we are left with just
    // the files that are not in any pom-containing directory. Meanwhile, we accumulate
    // the snapshot files in the right order, for each pom
    val (remainder, newSeq) = poms.foldLeft((allFiles, Seq[File]())) {
      case ((fileSeq, newSeq), pom) =>
        val pomFile = pom.getCanonicalFile()
        val thisDir = pomFile.getParentFile()
        if (thisDir == null) sys.error("Unexpected: file has not parent in deploy")
        // select the files in this directory (but not in subdirectories)
        val theseFiles = (thisDir.***).get.filter(f => !f.isDirectory && f.getCanonicalFile().getParentFile() == thisDir)
        // if there is a matching jar, upload the jar first
        val jarFile = new java.io.File(pomFile.getCanonicalPath().replaceAll("\\.[^\\.]*$", ".jar"))
        val thisSeq = if (theseFiles contains jarFile) {
          val rest = theseFiles.diff(Seq(jarFile, pomFile)).partition(f => isNotChecksum(f.getName))
          Seq(jarFile, pomFile) ++ rest._1 ++ rest._2
        } else {
          // no jar?? upload the pom first anyway
          val rest = theseFiles.diff(Seq(pomFile)).partition(f => isNotChecksum(f.getName))
          Seq(pomFile) ++ rest._1 ++ rest._2
        }
        //
        (fileSeq.diff(theseFiles), newSeq ++ thisSeq)
    }

    // We need in any case to upload the main files first,
    // and only afterwards we can upload md5 and sha1 files
    // (otherwise we get 404 errors from Artifactory)
    // Note that a 409 error means the checksum calculated on
    // the server does not match the checksum we are trying to upload
    val split = remainder.partition(f => isNotChecksum(f.getName))
    val ordered = newSeq ++ split._1 ++ split._2

    ordered foreach { file =>
      val relative = IO.relativize(dir, file) getOrElse sys.error("Internal error in relative paths creation during deployment. Please report.")
      message(log, relative)
      // see http://help.eclipse.org/indigo/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/URIUtil.html#append(java.net.URI,%20java.lang.String)
      val targetURI = org.eclipse.core.runtime.URIUtil.append(targetBaseURI, relative)
      deploy(handler, credentials, relative, file, targetURI)
    }
  }

  /**
   * The semantics of selection is:
   * - For each node, the set of artifacts available for deploy is: the set of artifacts of all the
   *   successful children, plus its own if successful.
   * - The root build, denoted by ".", has no artifacts of its own.
   * - This rule also applies applies to nested hierarchical build systems, if they are in turn recursive.
   */
  def deployFullBuild(conf: DBuildConfiguration, build: RepeatableDistributedBuild, outcome: BuildOutcome, log: Logger) = {
    val projectOutcomes = outcome.outcomes.map(o => (o.project, o)).toMap
    // This does not contain the root ".", but we know that the root has no artifacts to be published of its own,
    // therefore the set for "." is the union of those of the children, regardless if "." failed or not.
    val optionsSeq = conf.options.deploy
    if (optionsSeq.nonEmpty) {
      log.info("--== Deploying Artifacts  ==--")
      optionsSeq foreach { options =>
        log info ("Processing: " + options.uri)
        // we need to retrieve the artifacts from the repository
        // for build.uuid. We stage them in a temporary directory first,
        // to make sure all is in order
        try IO.withTemporaryDirectory { dir =>
          val cache = Repository.default
          // let's expand "."
          val projList = options.flattenAndCheckProjectList(build.builds.map { _.config.name }.toSet)

          val selected = projList.map { depl =>
            build.repeatableBuilds.find(_.config.name == depl.name) match {
              case None => sys.error("Error during deploy: \"" + depl.name + "\" is not a project name.")
              case Some(proj) => (depl, proj) // (deploy request,RepeatableProjectBuild)
            }
          }

          val (good, bad) = selected partition {
            case (depl, proj) =>
              val outcome = projectOutcomes.
                getOrElse(proj.config.name, sys.error("Internal map error in deploy, unknown \"" + proj.config.name + "\". Please report."))
              outcome.isInstanceOf[BuildGood]
          }

          def logDepl(elems: Set[(SelectorElement, RepeatableProjectBuild)]) =
            ": " + elems.map(_._1.name).toSeq.sorted.mkString("", ", ", "")
          if (good.nonEmpty) log.info("Deploying" + logDepl(good))
          if (bad.nonEmpty) log.warn("Currently broken, hence not deployed" + logDepl(bad))

          if (good.nonEmpty) try {
            good map {
              case (depl, proj) =>
                val subprojs: Seq[String] = depl match {
                  case SelectorSubProjects(SubProjects(from, publish)) => publish
                  case SelectorProject(_) => Seq[String]()
                }
                val (arts, msg) = LocalRepoHelper.materializePartialProjectRepository(proj.uuid, subprojs, cache, dir)
                msg foreach { log.info(_) }
            }

            // Are we signing? If so, proceed
            options.sign foreach { signOptions =>
              val secretRingFile = signOptions.secretRing map { new File(_) } getOrElse (ProjectDirs.userhome / ".gnupg" / "secring.gpg")
              PGP.init
              val passPhrase = {
                val passPhraseFile = io.Source.fromFile(signOptions.passphrase)
                val p = passPhraseFile.getLines.next
                passPhraseFile.close()
                p
              }
              val secretKey = signOptions.id match {
                case Some(keyID) =>
                  log.info("Signing with key: " + keyID)
                  new PGPSecretKeyRingCollection(new java.io.FileInputStream(secretRingFile)).getSecretKey(new java.math.BigInteger(keyID, 16).longValue)
                case None =>
                  log.info("Signing with default key...")
                  new PGPSecretKeyRing(new java.io.FileInputStream(secretRingFile)).getSecretKey
              }
              if (secretKey == null) sys.error("The key was not found in the pgp keyring.")
              (dir.***).get.filter(f => !f.isDirectory && isNotChecksum(f.getName)) foreach { f =>
                SecretKey(secretKey).sign(f, new File(f.getAbsolutePath() + ".asc"), passPhrase.toArray)
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
                    val response = (new Http with NoLogging)(sender >- { str =>
                      Utils.readSomePath[ArtifactoryResponse](str)
                    })
                    if (response != None && response.get.path != None) {
                      val out = response.get.path.get.replaceFirst("^/", "")
                      if (out != relative) log.info("Deployed:  " + out)
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
          } catch {
            case e: NumberFormatException =>
              log.error("***ERROR*** Not a valid hexadecimal value: " + options.sign.get.id.get)
              log.error("***ERROR*** Will not deploy.")
              log.trace(e)
          }
        }
        catch {
          case e =>
            log.error("***ERROR*** Encountered an error while deploying to " + url(options.uri).host)
            log.trace(e)
        }
      }
      log.info("--== End Deploying Artifacts ==--")
    }
  }
}
// Response from Artifactory
case class ArtifactoryResponse(path: Option[String])
