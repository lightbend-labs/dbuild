package com.typesafe.dbuild.deploy

import sbt._
import Path._
import java.io.File
import java.net.URI
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import dispatch.classic.{ Logger => _, _ }
import org.omg.PortableInterceptor.SUCCESSFUL
import sbt.Logger
import Creds.loadCreds
import com.jcraft.jsch.{ IO => sshIO, _ }
import java.util.Date
import com.jcraft.jsch.ChannelSftp
import com.lambdaworks.jacks.JacksMapper

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
    if (c.host == host)
      sys.error("The credentials supplied to Deploy refer to host \"" + c.host + "\" but the uri refers to \"" + host + "\"")
  }
}

/**
 * This class describes some mechanism used to deploy a set of files, contained in the
 * given directory, to some remote (or local) target.
 */
abstract class Deploy[T] {
  def log: Logger
  def deploy[T](options: DeployInfo, dir: File)
}
/**
 * Use Deploy.deploy(target,dir,log) to deploy a set of artifacts, contained in the directory "dir",
 * to some external target, logging the results to the logger "log".
 */
object Deploy {
  def deploy(target: DeployInfo, dir: File, log: Logger) = {
    val uri = new _root_.java.net.URI(target.uri)
    val deployer = uri.getScheme match {
      case "file" => new DeployFiles(log)
      case "http" | "https" => new DeployHTTP(log)
      case "ssh" => new DeploySSH(log)
      case "s3" => new DeployS3(log)
      case s => sys.error("Unknown scheme in deploy uri: " + s)
    }
    deployer.deploy(target, dir)
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
abstract class IterativeDeploy[T] extends Deploy[T] {
  import Deploy.isNotChecksum
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
  def deploy[T](options: DeployInfo, dir: File) {
    val targetBaseURI = new java.net.URI(options.uri)
    val credentials = options.creds getOrElse sys.error("No credentials supplied for uri " + options.uri)
    val handler = init(credentials)
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

class DeploySSH(val log: Logger) extends IterativeDeploy[ChannelSftp] {
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

class DeployS3(val log: Logger) extends IterativeDeploy[AmazonS3Client] {
  import Deploy.isNotChecksum
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

class DeployHTTP(val log: Logger) extends IterativeDeploy[Unit] {
  import Deploy.isNotChecksum
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
      Deploy.readSomePath[ArtifactoryResponse](str)
    })
    try {
      if (response != None && response.get.path != None && response.get.path.get != null) {
        val out = response.get.path.get.replaceFirst("^/", "")
        if (out != relative) log.info("Deployed:  " + out)
      }
    } catch {
       case e: NullPointerException => log.info("Deployed:  Response: " + response)
    }
  }
}

class DeployFiles(val log: Logger) extends Deploy[Unit] {
  def deploy[Unit](options: DeployInfo, dir: File) = {
    // copy to a local path
    val target = (new _root_.java.net.URI(options.uri)).toURL.getPath
    log.info("Copying artifacts to " + target + "...")
    // Overwrite, and preserve timestamps
    IO.copyDirectory(dir, new File(target), true, true)
  }
}

// Response from Artifactory
case class ArtifactoryResponse(path: Option[String])
