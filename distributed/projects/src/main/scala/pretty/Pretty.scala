package pretty

/** Pretty print a value into a String.
 * 
 * Note: This is specifically used to take case class configuration and
 *  return it into its parsed format...
 */
trait PrettyPrint[T] {
  def apply(t: T): String
}

object PrettyPrint {
  def apply[T](t: T)(implicit ev: PrettyPrint[T]) = ev(t)

  /** Support for sequences of settings. */
  implicit def seqPretty[T : PrettyPrint]: PrettyPrint[Seq[T]] = new PrettyPrint[Seq[T]] {
    def apply(t: Seq[T]): String = 
      (t map { i => PrettyPrint(i) }).mkString("[", ",\n","]")
  } 
}