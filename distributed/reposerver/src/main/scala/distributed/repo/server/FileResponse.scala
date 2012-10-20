package distributed
package repo
package server

import unfiltered.response._


/** Returns a file as an unfiltered response. */
case class FileResponse(file: java.io.File) extends ResponseStreamer {
  override def stream(os: java.io.OutputStream): Unit = {
     val in = new java.io.FileInputStream(file)
     val buf = new Array[Byte](64*1024)
     def read(): Unit = in read buf match {
       case -1 => ()
       case n =>
         os.write(buf, 0, n)
         read()
     }
     read()
  }
}