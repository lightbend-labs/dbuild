package hashing

import java.io.File
import java.security.MessageDigest

object files {
  def sha1(f: File): String =
    digest(MessageDigest.getInstance("SHA-1"))(f)
  
  def sha512(f: File): String =
    digest(MessageDigest.getInstance("SHA-512"))(f)
    
      // This should calculate the SHA sum of a file the same as the linux process.
  def digest(digest: MessageDigest)(file: File): String = {
    val in = new java.io.FileInputStream(file);
    val buffer = new Array[Byte](8192)
    try {
       def read(): Unit = in.read(buffer) match {
         case x if x <= 0 => ()
         case size => digest.update(buffer, 0, size); read()
       }
       read()
    } finally in.close()
    val sha = convertToHex(digest.digest())
    sha
  }
}