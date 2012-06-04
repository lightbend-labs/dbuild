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
  private def convertToHex(data: Array[Byte]): String = {
    val buf = new StringBuffer
    for (i <- 0 until data.length) {
      var halfbyte = (data(i) >>> 4) & 0x0F;
      var two_halfs = 0;
      while(two_halfs < 2) {
        if ((0 <= halfbyte) && (halfbyte <= 9))
          buf.append(('0' + halfbyte).toChar)
        else
          buf.append(('a' + (halfbyte - 10)).toChar);
        halfbyte = data(i) & 0x0F;
        two_halfs += 1
      }
    }
    return buf.toString
  }

}