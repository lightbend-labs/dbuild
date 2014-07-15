package distributed
package build

import sbt._
import Path._
import project.model._
import repo.core._
import java.io.File
import java.net.URI
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import dispatch.classic.{ Logger => _, _ }
import com.jsuereth.pgp.{ PGP, SecretKey }
import org.bouncycastle.openpgp.{ PGPSecretKeyRingCollection, PGPSecretKeyRing }
import org.omg.PortableInterceptor.SUCCESSFUL
import logging.Logger.prepareLogMsg
import distributed.logging.Logger
import Creds.loadCreds
import com.jcraft.jsch.{ IO => sshIO, _ }
import java.util.Date
import com.jcraft.jsch.ChannelSftp

class DeployBuild(options: GeneralOptions, log: logging.Logger) extends OptionTask(log) {
  def id = "Deploy"
  def beforeBuild(projectNames: Seq[String]) = {
    options.deploy foreach { d =>
      // just a sanity check on the project list (we don't use the result)
      val _ = d.projects.flattenAndCheckProjectList(projectNames.toSet)
    }
    checkDeployFullBuild(options.deploy)
  }
  def afterBuild(optRepBuild: Option[RepeatableDistributedBuild], outcome: BuildOutcome) = {
    def dontRun() = log.error("*** Deploy cannot run: build did not complete.")
    // we do not run deploy if we reached time out, or if we never arrived past extraction (or both)
    if (outcome.isInstanceOf[TimedOut]) dontRun() else
      optRepBuild match {
        case None => dontRun
        case Some(repBuild) => deployFullBuild(repBuild, outcome)
      }
  }

  // first, a proper sanity check
  def checkDeployFullBuild(optionsSeq: Seq[DeployOptions]) = {
    optionsSeq foreach { options =>
      val uri = new _root_.java.net.URI(options.uri)
      uri.getScheme match {
        case "file" =>
          if (options.credentials != None) log.warn("Credentials will be ignored while deploying to " + uri)
        case "ssh" | "http" | "https" | "s3" =>
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

  def isNotChecksum(path: String): Boolean = !(path.endsWith(".sha1") || path.endsWith(".md5"))

  /**
   * The semantics of selection is:
   * - For each node, the set of artifacts available for deploy is: the set of artifacts of all the
   *   successful children, plus its own if successful.
   * - The root build, denoted by ".", has no artifacts of its own.
   * - This rule also applies applies to nested hierarchical build systems, if they are in turn recursive.
   */
  def deployFullBuild(build: RepeatableDistributedBuild, outcome: BuildOutcome) = {
    val projectOutcomes = outcome.outcomes.map(o => (o.project, o)).toMap
    // This does not contain the root ".", but we know that the root has no artifacts to be published of its own,
    // therefore the set for "." is the union of those of the children, regardless if "." failed or not.
    val optionsSeq = options.deploy
    if (optionsSeq.nonEmpty) {
      log.info("--== Deploying Artifacts  ==--")
      runRememberingExceptions(true, optionsSeq) { options =>
        log info ("Processing: " + options.uri)
        // we need to retrieve the artifacts from the repository
        // for build.uuid. We stage them in a temporary directory first,
        // to make sure all is in order
        try IO.withTemporaryDirectory { dir =>
          val (good, goodArts, bad) = rematerialize(options.projects, outcome, build, dir, "deploy",
            msgGood = "Deploying: ",
            msgBad = "Cannot deploy: ",
            partialOK = true, log)

          if (good.nonEmpty) try {
            // Are we signing? If so, proceed
            options.sign foreach { signOptions =>
              val secretRingFile = signOptions.secretRing map { new File(_) } getOrElse (GlobalDirs.userhome / ".gnupg" / "secring.gpg")
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
            Deploy(target = options, dir)
          } catch {
            case e: NumberFormatException =>
              log.error("***ERROR*** Not a valid hexadecimal value: " + options.sign.get.id.get)
              log.error("***ERROR*** Will not deploy.")
              throw e
          }

          // Now we need to prepare an index file, if requested
          options.index foreach { indexOptions =>
            try IO.withTemporaryDirectory { indexDir =>
              val indexFile = new File(indexDir, indexOptions.filename)
              // extraction of ModuleInfo:
              // We need the info contained in ArtifactLocation instances
              // They are inside each BuildArtifactsOut
              // which is inside all Outcomes that are instances of BuildGood.
              // The retrieval is already done by rematerialize(), so we reuse that returned value.
//              goodArts map { art =>
//                val crossVer = if (art.crossSuffix.startsWith("_")
//                    Some(art.crossSuffix.drop(1))
//                    else
//                      None
//                ModuleInfo(art.info.organization,art.info.name,art.version,
//                    CrossBuildProperties(crossVer,
//                
//              }

              val builds: Seq[ProjectConfigAndExtracted] = build.builds
              val projMetas = builds map { _.extracted.getHead }
              //            case class ModuleInfo(
              //  organization: String,
              //  name: String,
              //  version: String,
              //  cross: CrossBuildProperties)
              //// TODO- Hard-coded or loose map?
              //case class CrossBuildProperties(scalaVersion: Option[String], sbtVersion: Option[String])

            }
            catch {
              case e =>
                log.error("***ERROR*** Encountered an error while generating or deploying the index file to " + url(indexOptions.uri).host)
                throw e
            }
          }
        }
        catch {
          case e =>
            log.error("***ERROR*** Encountered an error while deploying to " + url(options.uri).host)
            throw e
        }
      }
      log.info("--== End Deploying Artifacts ==--")
    }
  }

  /**
   * Some mechanism to deploy the files contained in the
   * given directory to some target.
   */
  abstract class Deploy[T] {
    def deploy[T](options: DeployTarget, dir: File)
  }
  object Deploy {
    def apply(target: DeployTarget, dir: File) = {
      val uri = new _root_.java.net.URI(target.uri)
      val deployer = uri.getScheme match {
        case "file" => new DeployFiles
        case "http" | "https" => new DeployHTTP
        case "ssh" => new DeploySSH
        case "s3" => new DeployS3
      }
      deployer.deploy(target, dir)
    }
  }

  /**
   * Some method to deploy stuff by scanning files one at a time;
   * special care is taken to deploy items in an order that is
   * compatible with Artifactory and the like.
   */
  abstract class IterativeDeploy[T] extends Deploy[T] {
    protected def init(credentials: Creds): T
    protected def message(relative: String)
    protected def deployItem(handler: T, credentials: Creds, relative: String, file: File, targetURI: URI)
    protected def close(handler: T) = ()

    /**
     * Generic code that deploys to some repository. The subclasses provide
     * implementations for the "init", "message", "deploy", and "close"
     * handlers.
     *
     * This routine takes special care to upload artifacts in the proper
     * order, in order to comply with the peculiar requirements of
     * Maven/Ivy repositories (Artifactory, in particular).
     */
    def deploy[T](options: DeployTarget, dir: File) {
      val Some(credsFile) = options.credentials
      val credentials = loadCreds(credsFile)
      val handler = init(credentials)
      try {
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
          message(relative)
          // see http://help.eclipse.org/indigo/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/URIUtil.html#append(java.net.URI,%20java.lang.String)
          val targetURI = org.eclipse.core.runtime.URIUtil.append(targetBaseURI, relative)
          deployItem(handler, credentials, relative, file, targetURI)
        }
      } finally { close(handler) }
    }
  }

  class DeploySSH extends IterativeDeploy[ChannelSftp] {
    protected def init(credentials: Creds) = {
      val jsch = new JSch()
      JSch.setConfig("StrictHostKeyChecking", "no")
      import log.{ debug => ld, info => li, warn => lw, error => le }
      JSch.setLogger(new com.jcraft.jsch.Logger {
        def isEnabled(level: Int) = true
        import com.jcraft.jsch.Logger._
        def log(level: Int, message: String) = level match {
          // levels are arranged so that ssh info is debug for dbuild
          case DEBUG => ld(message)
          case INFO => ld(message)
          case WARN => li(message)
          case ERROR => lw(message)
          case FATAL => le(message)
        }
      })
      // try to locate a private key; if it exists, add
      // the identity (for passwordless authentication)
      // Only the default location is supported, and no passphrase
      val privateKeyLocation = new File(System.getProperty("user.home")) / ".ssh" / "id_rsa"
      try {
        val is = new java.io.FileInputStream(privateKeyLocation)
        val privateKey = org.apache.commons.io.IOUtils.toByteArray(is)
        val passPrivateKey = "".getBytes()
        jsch.addIdentity(credentials.user, privateKey, null, passPrivateKey)
      } catch {
        case e: java.io.FileNotFoundException => // ignore
      }
      val session = jsch.getSession(credentials.user, credentials.host, 22)
      session.setPassword(credentials.pass)
      session.connect(900)
      val channel = session.openChannel("sftp")
      channel.connect(900)
      channel match {
        case sftp: ChannelSftp => sftp
        case _ => sys.error("Could not open an SFTP channel.")
      }
    }

    protected def message(relative: String) = log.info("Deploying: " + relative)

    protected def deployItem(sftp: ChannelSftp, credentials: Creds, relative: String, file: File, uri: URI) = {
      val path = uri.getPath
      def mkParents(s: String): Unit = {
        val l = s.lastIndexOf('/')
        val dir = s.substring(0, l)
        try {
          sftp.cd(dir)
        } catch {
          case e: Exception =>
            mkParents(dir)
            sftp.mkdir(dir)
        }
      }
      if (!path.startsWith("/")) sys.error("Internal error: ssh upload uri path is not absolute")
      mkParents(path)
      sftp.put(file.getCanonicalPath, path)
    }

    override protected def close(sftp: ChannelSftp) = {
      if (sftp != null) {
        sftp.disconnect()
        val session = sftp.getSession()
        if (session != null)
          session.disconnect()
      }
    }
  }

  class DeployS3 extends IterativeDeploy[AmazonS3Client] {
    protected def init(credentials: Creds) =
      new AmazonS3Client(new BasicAWSCredentials(credentials.user, credentials.pass),
        new ClientConfiguration().withProtocol(Protocol.HTTPS))
    protected def message(relative: String) = if (isNotChecksum(relative))
      log.info("Uploading: " + relative)
    protected def deployItem(client: AmazonS3Client, credentials: Creds, relative: String, file: File, uri: URI) =
      // putObject() will automatically calculate an MD5, upload, and compare with the response
      // from the server. Any upload failure results in an exception; so no need to process
      // the sha1/md5 here.
      if (isNotChecksum(uri.getPath))
        client.putObject(new PutObjectRequest(credentials.host, uri.getPath.replaceFirst("^/", ""), file))
  }

  class DeployHTTP extends IterativeDeploy[Unit] {
    protected def init(credentials: Creds) = ()
    protected def message(relative: String) =
      if (isNotChecksum(relative))
        log.info("Deploying: " + relative)
      else
        log.info("Verifying checksum: " + relative)
    protected def deployItem(handler: Unit, credentials: Creds, relative: String, file: File, uri: URI) = {
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
    }
  }

  class DeployFiles extends Deploy[Unit] {
    def deploy[Unit](options: DeployTarget, dir: File) = {
      // copy to a local path
      val target = (new _root_.java.net.URI(options.uri)).getPath
      log.info("Copying artifacts to " + target + "...")
      // Overwrite, and preserve timestamps
      IO.copyDirectory(dir, new File(target), true, true)
    }
  }
}
// Response from Artifactory
case class ArtifactoryResponse(path: Option[String])
