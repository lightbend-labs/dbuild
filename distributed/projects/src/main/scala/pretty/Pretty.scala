package pretty

// TODO - Rename config print....

/** Pretty print a value into a String.
 * 
 * Note: This is specifically used to take case class configuration and
 *  return it into its parsed format...
 */
trait ConfigPrint[T] {
  def apply(t: T): String
}

object ConfigPrint {
  def apply[T](t: T)(implicit ev: ConfigPrint[T]) = ev(t)

  /** Support for sequences of settings. */
  implicit def seqPretty[T : ConfigPrint]: ConfigPrint[Seq[T]] = new ConfigPrint[Seq[T]] {
    def apply(t: Seq[T]): String = 
      (t map { i => ConfigPrint(i) }).mkString("[", ",","]")
  } 
  
  implicit object stringConfig extends ConfigPrint[String] {
    def apply(in: String): String =
      '"' + in.replaceAll("\"", "\\\"") + '"'
  }
  
  implicit object fileConfig extends ConfigPrint[java.io.File] {
    def apply(f: java.io.File): String = f.getAbsolutePath
  }
  
  def makeMember[A: ConfigPrint](name: String, value: A): String =
    "%s:%s" format (stringConfig(name), ConfigPrint(value))
}