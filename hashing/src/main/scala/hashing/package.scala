package object hashing {

  def sha1Sum(t: Any): String = {
    val md = java.security.MessageDigest.getInstance("SHA-1")
    def addBytes(obj: Any): Unit = obj match {
      case b: Byte            => md update b
      case bytes: Array[Byte] => md update bytes
      case s: String          => md update s.getBytes
      case s: Product         =>
        // Add a product marker
        md update 5.toByte
        s.productIterator foreach addBytes
      case s: Traversable[_] =>
        md update 1.toByte
        s foreach addBytes
    }
    addBytes(t)
    convertToHex(md.digest)
  }
  def convertToHex(data: Array[Byte]): String = {
    val buf = new StringBuffer
    def byteToHex(b: Int) =
      if ((0 <= b) && (b <= 9)) ('0' + b).toChar
      else ('a' + b).toChar
    for (i <- 0 until data.length) {
      buf append byteToHex((data(i) >>> 4) & 0x0F)
      buf append byteToHex(data(i) & 0x0F)
    }
    return buf.toString
  }

}