package com.typesafe.dbuild.http

import scala.language.postfixOps
import dispatch._
import scala.concurrent._
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global
import com.ning.http.client.{AsyncHttpClientConfig, AsyncHttpClient}
import java.io.File
import com.typesafe.dbuild.adapter.Defaults
import sbt.IO

case class Credentials(user: String, pw: String)

class HttpTransfer extends java.io.Closeable {

  // The "as.File" facility of dispatch leaks file descriptors,
  // so we use this abstraction instead.
  object AsFile {
    import com.ning.http.client
    def apply(file: java.io.File) = {
      val tFile = new java.io.RandomAccessFile(file, "rw")
      (new client.resumable.ResumableAsyncHandler with OkHandler[Nothing] {
        override def onThrowable(t: Throwable): Unit = {
          super.onThrowable(t)
          try tFile.close() catch { case e: Throwable => }
        }
      }).setResumableListener(
        new client.extra.ResumableRandomAccessFileListener(
          tFile
        )
      )
    }
  }

  // I cannot use directly Http.configure(_ setFollowRedirect true),
  // otherwise dispatch creates one instance for Http and another
  // one for Http.configure(..). Both must be shut down, otherwise
  // the application hangs and never exits. If the first one is
  // shutdown once, it cannot be reused (it's a singleton). So, a mess.
  // Therefore we manually create an instance here, which can be safely
  // shut down at any time, and another one recreated using the same code.
  val http = new Http(
    new AsyncHttpClient(
      new AsyncHttpClientConfig.Builder()
        .setUserAgent("dbuild/%s" format Defaults.version)
        .setRequestTimeout(-1)
        .setUseProxyProperties(true)
        .setFollowRedirect(true)
        .build()
    )
  )

  // If downloadTo() throws exception, or when the
  // downloads are all complete, the caller MUST
  // invoke .close() before exiting anyway, otherwise
  // the application will hang.
  def close():Unit = {
    http.shutdown()
  }

  private val encoding = java.nio.charset.StandardCharsets.UTF_8.toString()

  def download(uri: String, file: File, timeOut: Duration = 10 minutes) = {
    // The uri is URL encoded in order to build a temporary
    // filename out of it
    val saneUri = java.net.URLEncoder.encode(uri, encoding)
    val suffix=saneUri.substring(Math.max(0,saneUri.length-45))
    val absFile = file.getAbsoluteFile()
    IO.withTemporaryFile("dbuild-download", suffix) { tmp =>
      try {
        // Delete an old file; we do it always, to prevent
        // an incorrect download w/ exception to leave behind
        // an old file that may be misinterpreted as the new one.
        if (absFile.exists) absFile.delete()
        val r = http(url(uri) > AsFile(tmp))
        Await.result(r, timeOut)
        // did all go ok? Move the file to the right place.
        // Note that IO.move() may choke if the dest file is not absolute
        IO.move(tmp, absFile)
      } catch {
        case e:Exception =>
        throw new Exception("Error downloading " + absFile.getPath() + " from " + uri, e)
      }
    }
  }

  def upload(uri: String, file: File, cred: Credentials, timeOut: Duration = 10 minutes)(handleResponseBody: String => Unit ) = {
    val absFile = file.getAbsoluteFile()
    try {
      val request = url(uri).PUT.as(cred.user,cred.pw).setBody(absFile).setBodyEncoding("application/octet-stream")
      val r = http(request OK { response => handleResponseBody(response.getResponseBody) })
      Await.result(r, timeOut)
    } catch {
      case e:Exception =>
      throw new Exception("Error uploading " + absFile.getPath() + " to " + uri, e)
    }
  }

}
