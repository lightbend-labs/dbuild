import com.typesafe.config.ConfigValue
import collection.JavaConverters._
package object hashing {

  def sha1(t: Any): String =
    messageDigestHex(java.security.MessageDigest.getInstance("SHA-1"))(t)

  def sha512(t: Any): String =
    messageDigestHex(java.security.MessageDigest.getInstance("SHA-512"))(t)
  
  def messageDigestHex(md: java.security.MessageDigest)(t: Any): String = {
    def addBytes(obj: Any): Unit = obj match {
      case b: Byte            => md update b
      case bytes: Array[Byte] => md update bytes
      case s: String          => md update s.getBytes
      case s: Product         =>
        // Add a product marker
        md update 5.toByte
        s.productIterator foreach addBytes
      case map: Map[String,_] =>
        val data = map.toSeq.sortBy(_._1)
        data foreach { case (k,v) =>
          addBytes(k)
          addBytes(v)
        }        
      case s: Traversable[_] =>
        // First add a traversable marker..
        md update 1.toByte
        s foreach addBytes
      case list: java.util.List[_] =>
        md update 1.toByte
        list.asScala foreach addBytes
      case map: java.util.Map[String,_] =>
        val data = map.entrySet.iterator.asScala.toSeq.sortBy(_.getKey)
        data foreach { kv =>
          addBytes(kv.getKey)
          addBytes(kv.getValue)
        }
      case b: Boolean =>
        md update (if(b) 0.toByte else 1.toByte)
      case c: ConfigValue =>
        addBytes(c.unwrapped)
        
    }
    addBytes(t)
    convertToHex(md.digest)
  }
  
  def convertToHex(data: Array[Byte]): String = {
    val buf = new StringBuffer
    def byteToHex(b: Int) =
      if ((0 <= b) && (b <= 9)) ('0' + b).toChar
      else ('a' + (b-10)).toChar
    for (i <- 0 until data.length) {
      buf append byteToHex((data(i) >>> 4) & 0x0F)
      buf append byteToHex(data(i) & 0x0F)
    }
    buf.toString
  }

}