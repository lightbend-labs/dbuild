package config

import sbt._
import sbt.Types._

/** A means of reading configuration into a typed variable. */
trait ConfigRead[T] {
  /** Attempts to read a value of type T from a configuration value. */
  def unapply(c: ConfigValue): Option[T]
}



object ConfigRead {
  
  def apply[T](implicit ev: ConfigRead[T]): ConfigRead[T] = ev
  
  def readMember[T](name: String)(implicit r: ConfigRead[T]): ConfigMember[T] =
    new ConfigMember(name, r)
  
  
  implicit object configObjRead extends ConfigRead[ConfigObject] {
    def unapply(t: ConfigValue): Option[ConfigObject] = t match {
      case obj: ConfigObject => Some(obj)
      case _ => None
    }
      
  }
  
  implicit def optRead[T](implicit Element: ConfigRead[T]): ConfigRead[Option[T]] =
    new ConfigRead[Option[T]] {
      def unapply(t: ConfigValue): Option[Option[T]] =
        t match {
          case Element(value) => Some(Some(value))
          case _              => Some(None)
        }
    }
  
  
  implicit def seqRead[T](implicit Element: ConfigRead[T]): ConfigRead[Seq[T]] =
    new ConfigRead[Seq[T]] {
      def unapply(t: ConfigValue): Option[Seq[T]] = t match {
        case ConfigList(list) =>
          Some(list map {
            case Element(e) => e
            // TODO - Not such lame code...
            case _ => return None
          })
        case _ => None
      }
    }
  implicit def stringRead = ConfigString
  implicit object fileRead extends ConfigRead[java.io.File] {
    def unapply(t: ConfigValue) = t match {
      case ConfigString(filename) => Some(new java.io.File(filename))
      case _                      => None
    }
  }
  implicit object doubleRead extends ConfigRead[Double] {
    import util.control.Exception.catching
    def unapply(t: ConfigValue) = t match {
      case ConfigString(value) =>
        catching(classOf[NumberFormatException]) opt value.toDouble
      case _ => None
    }
  }
}


// Extraneous tupling junk:
case class ConfigMember[T](name: String, Reader: ConfigRead[T]) {
  def unapply(c: ConfigValue): Option[T] = c match {
    case c: ConfigObject =>
      (c get name) match {
        case Reader(value) => Some(value)
        case _ => None
      }
    case _ => None
  }
  def :^:[U](other: ConfigMember[U]): ConfigMember.AndMember[U, T :+: HNil] =
    new ConfigMember.AndMember[U, T :+: HNil](other :^: this :^: KNil)
}
object ConfigMember {
  
  /** This class allows us to and together member parsers. */
  class AndMember[H, T <: HList](klist: KList[ConfigMember, H :+: T]) {
    import KList._
    def :^:[U](other: ConfigMember[U]): AndMember[U, H :+: T] =
      new AndMember(other :^: kcons(klist))
      
      
    def unapply(c: ConfigValue): Option[H :+: T] =
      klist.foldr[Option, ConfigMember](new MatchFoldr(c))
  }
  
  class MatchFoldr(c: ConfigValue) extends KFold[ConfigMember, Option] {
      def kcons[H,T <: HList](h: ConfigMember[H], acc: Option[T]): Option[H :+: T] =
        for {
          tail <- acc
          head <- h unapply c
        } yield HCons(head, tail)
      def knil: Option[HNil] = Some(HNil)
  }
}


