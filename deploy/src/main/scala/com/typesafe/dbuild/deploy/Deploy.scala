package com.typesafe.dbuild.deploy

import sbt._
import java.io.File
import java.net.URI
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import org.omg.PortableInterceptor.SUCCESSFUL
import Creds.loadCreds
import com.jcraft.jsch.{ IO => sshIO, Logger => _, _ }
import java.util.Date
import com.jcraft.jsch.ChannelSftp
import com.typesafe.dbuild.adapter.Adapter
import Adapter.{IO,Logger,allPaths}
import com.lambdaworks.jacks.JacksMapper
import Adapter.Path._
import Adapter.syntaxio._
import dispatch.{url => dispUrl, Http}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent._
import scala.concurrent.duration._

/**
 * A generic (S3, http, https, etc) deploy location.
 * The uri refers to a remote directory or container, and
 * does not include the deployed file names.
 * 
 * If you are supplying host/user/pass directly, you
 * can prepare a "Creds" record, and extend a
 * DeployInfo.
 */
abstract class DeployInfo {
  def uri: String
  def creds: Option[Creds] // not all uris use credentials
}
/**
 * if you are supplying host/user/pass via a file, you can use
 * a DeployTarget.
 */
abstract class DeployTarget extends DeployInfo {
  def uri: String
  private val host = Option(new java.net.URI(uri).getHost) getOrElse ""
  def credentials: Option[String]
  val creds = credentials map loadCreds
  creds foreach { c =>
    val scheme = new _root_.java.net.URI(uri).getScheme
    if (scheme == "bintray") {
      if (c.host != "api.bintray.com")
        sys.error("The credentials supplied to Deploy refer to host \"" + c.host + "\", but it must be \"api.bintray.com\" to be usable with a bintray uri.")
    } else if (c.host != host)
      sys.error("The credentials supplied to Deploy refer to host \"" + c.host + "\" but the uri refers to \"" + host + "\"")
  }
}

/**
 * This class describes some mechanism used to deploy a set of files, contained in the
 * given directory, to some remote (or local) target.
 */
abstract class Deploy[T](options: DeployInfo) {
  def deploy[T](dir: File)
}
/**
 * Use Deploy.deploy(target,dir,log) to deploy a set of artifacts, contained in the directory "dir",
 * to some external target, logging the results to the logger "log".
 */
object Deploy {
  def deploy(target: DeployInfo, dir: File, log: Logger) = {
    val uri = new _root_.java.net.URI(target.uri)
    val deployer = uri.getScheme match {
      case "file" => new DeployFiles(log, target)
      case "http" | "https" => new DeployHTTP(log, target)
      case "bintray" => new DeployBintray(log, target)
      case "ssh" => new DeploySSH(log, target)
      case "s3" => new DeployS3(log, target)
      case "null" => new DeployNull(log, target)
      case s => sys.error("Unknown scheme in deploy uri: " + s)
    }
    deployer.deploy(dir)
  }
  def isNotChecksum(path: String): Boolean = !(path.endsWith(".sha1") || path.endsWith(".md5"))

  // Use jacks and Jackson to read a /possible/ response from Artifactory
  // This readSomePath() is essentially a duplicate of the one defined in package Utils,
  // replicated here in order to allow the use of this "deploy" package without importing
  // additional dbuild packages, or unnecessary dependencies.
  private val mapper = JacksMapper
  private[deploy] def readSomePath[T](s: String)(implicit m: Manifest[T]) = {
    val current = Thread.currentThread.getContextClassLoader
    try {
      Thread.currentThread setContextClassLoader getClass.getClassLoader
      try {
        Some(mapper.readValue[T](s))
      } catch {
        case e: com.fasterxml.jackson.databind.JsonMappingException => None
      }
    } finally Thread.currentThread setContextClassLoader current
  }
}

/**
 * Some method to deploy stuff by scanning files one at a time;
 * special care is taken to deploy items in an order that is
 * compatible with Artifactory and the like.
 */
abstract class IterativeDeploy[T](options: DeployInfo) extends Deploy[T](options) {
  import Deploy.isNotChecksum
  protected def init(): T
  protected def message(relative: String)
  protected def deployItem(handler: T, relative: String, file: File, targetURI: URI)
  protected def close(handler: T) = ()

  val credentials = options.creds getOrElse sys.error("No credentials supplied for uri " + options.uri)

  /**
   * Generic code that deploys to some repository. The subclasses provide
   * implementations for the "init", "message", "deploy", and "close"
   * handlers.
   *
   * This routine takes special care to upload artifacts in the proper
   * order, in order to comply with the peculiar requirements of
   * Maven/Ivy repositories (Artifactory, in particular).
   */
  def deploy[T](dir: File) {
    val targetBaseURI = new java.net.URI(options.uri)
    val handler = init()
    try {
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

      val allFiles = allPaths(dir).get.filter(f => !f.isDirectory)
      val poms = allFiles.filter(f => f.getName.endsWith("-SNAPSHOT.pom"))
      //
      // we fold over the poms, reducing the set of files until we are left with just
      // the files that are not in any pom-containing directory. Meanwhile, we accumulate
      // the snapshot files in the right order, for each pom
      val (remainder, newSeq) = poms.foldLeft((allFiles, Seq[File]())) {
        case ((fileSeq, newSeq), pomFile) =>
          val thisDir = pomFile.getParentFile()
          if (thisDir == null) sys.error("Unexpected: file has not parent in deploy")
          // select the files in this directory (but not in subdirectories)
          val theseFiles = allPaths(thisDir).get.filter(f => !f.isDirectory && f.getParentFile() == thisDir)
          // if there is a matching jar, upload the jar first
          val jarFile = new java.io.File(pomFile.getPath().replaceAll("\\.[^\\.]*$", ".jar"))
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
        val relative = IO.relativize(dir, file) getOrElse sys.error("Internal error relativizing "+ file +" from "+ dir +" during deployment. Please report.")
        message(relative)
        // see http://help.eclipse.org/indigo/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/core/runtime/URIUtil.html#append(java.net.URI,%20java.lang.String)
        val targetURI = org.eclipse.core.runtime.URIUtil.append(targetBaseURI, relative) // will append the fragment, if present in the base uri, to the result
        deployItem(handler, relative, file, targetURI)
      }
    } finally { close(handler) }
  }
}

class DeploySSH(log: Logger, options: DeployInfo) extends IterativeDeploy[ChannelSftp](options) {
  protected def init() = {
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
    import Adapter.syntaxio._
    import Adapter.Path._
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

  protected def deployItem(sftp: ChannelSftp, relative: String, file: File, uri: URI) = {
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

class DeployS3(log: Logger, options: DeployInfo) extends IterativeDeploy[AmazonS3Client](options) {
  import Deploy.isNotChecksum
  protected def init() = {
    new AmazonS3Client(new BasicAWSCredentials(credentials.user, credentials.pass),
      new ClientConfiguration().withProtocol(Protocol.HTTPS))
  }
  protected def message(relative: String) = if (isNotChecksum(relative))
    log.info("Uploading: " + relative)
  protected def deployItem(client: AmazonS3Client, relative: String, file: File, uri: URI) = {
    // putObject() will automatically calculate an MD5, upload, and compare with the response
    // from the server. Any upload failure results in an exception; so no need to process
    // the sha1/md5 here.
    if (isNotChecksum(uri.getPath))
      client.putObject(new PutObjectRequest(credentials.host, uri.getPath.replaceFirst("^/", ""), file))
  }
}

class DeployNull(log: Logger, options: DeployInfo) extends
  // fake creds, so that we can reuse IterativeDeploy
    IterativeDeploy[Unit](new DeployInfo { def creds = Some(Creds("","","")); def uri = options.uri }) {
  import Deploy.isNotChecksum
  protected def init() = ()
  protected def message(relative: String) = log.debug("Ignoring: " + relative)
  protected def deployItem(nothing: Unit, relative: String, file: File, uri: URI) = {}
}

class DeployHTTP(log: Logger, options: DeployInfo, timeOut: Duration = 20 minutes)
  extends IterativeDeploy[Unit](options) {
  import Deploy.isNotChecksum
  protected def init() = ()
  protected def message(relative: String) =
    if (isNotChecksum(relative))
      log.info("Deploying: " + relative)
    else
      log.info("Verifying checksum: " + relative)
  protected def deployItem(handler: Unit, relative: String, file: File, uri: URI) = {
    val sender =
      dispUrl(uri.toString).PUT.as(credentials.user, credentials.pass).setBody(file).setContentType("application/octet-stream","UTF-8")
    val response = Await.result(Http(sender OK { response =>
      Deploy.readSomePath[ArtifactoryResponse](response.getResponseBody)
    }), timeOut)
    try {
      if (response != None && response.get.path != None && response.get.path.get != null) {
        val out = response.get.path.get.replaceFirst("^/", "")
        if (out != relative) log.info("Deployed:  " + out)
      }
    } catch {
       case e: NullPointerException => log.debug("Response: " + response)
    }
  }
}

// (pass to the constructor the deploy target uri as well)
class DeployBintray(log: Logger, options: DeployInfo, timeOut: Duration = 20 minutes) extends DeployHTTP(log, options, timeOut) {
  override protected def init() = {
    val target = new java.net.URI(options.uri)
    val path = target.getPath
    if (Option(path) == None) sys.error("In a Bintray deploy section, the uri path must begin with a '/' character")
    val parts = (if (path.head == '/') path.tail else path).split("/")
    if (parts.length != 4) sys.error("In a Bintray deploy section, the path must contain four elements")
    log.debug("Deploying to Bintray")
    log.debug("owner  : " + parts(0))
    log.debug("repo   : " + parts(1))
    log.debug("package: " + parts(2))
    log.debug("version: " + parts(3))
    Option(target.getFragment) match {
      case Some("release") => log.debug("The uri fragment is \"release\", so we will release after deploy.")
      case Some(fragment) => log.debug("The uri fragment is \""+fragment+"\", so we will not release after deploy (must be \"release\").")
      case None => log.debug("There is no fragment, so we will not release after deploy.")
    }
  }
  private val bintrayBase = "https://api.bintray.com/content/"
  override protected def deployItem(handler: Unit, relative: String, file: File, uri: URI) = {
    val path = uri.getPath
    val dest = new java.net.URI(bintrayBase + (if (path.head == '/') path.tail else path))
    super.deployItem(handler, relative, file, dest)
  }
  override protected def close(handler: Unit) = {
    val target = new java.net.URI(options.uri)
    if (Option(target.getFragment) == Some("release")) {
      val path = target.getPath
      val dest = new java.net.URI(bintrayBase + (if (path.head == '/') path.tail else path) + "/publish")
      val sender =
        dispUrl(dest.toString).POST.as(credentials.user, credentials.pass)
      val response = Await.result(Http(sender OK { response =>
        Deploy.readSomePath[ArtifactoryResponse](response.getResponseBody)
      }), timeOut)
    }
  }
}

class DeployFiles(log: Logger, options: DeployInfo) extends Deploy[Unit](options) {
  def deploy[Unit](dir: File) = {
    // copy to a local path
    val target = (new _root_.java.net.URI(options.uri)).toURL.getPath
    log.info("Copying artifacts to " + target + "...")
    // Overwrite, and preserve timestamps
    IO.copyDirectory(dir, new File(target), true, true)
  }
}

// Response from Artifactory
case class ArtifactoryResponse(path: Option[String])
