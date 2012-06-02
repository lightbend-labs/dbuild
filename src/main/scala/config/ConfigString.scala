package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}
import java.lang.Number


object ConfigString {
  def unapply(obj: Any): Option[String] = 
    obj match {
      case t: ConfigValue if t.valueType == ConfigValueType.STRING => 
        Some(t.unwrapped.asInstanceOf[String])
      case _ => None
    }
  def apply(value: String): ConfigValue =
    j.ConfigValueFactory.fromAnyRef(value)
}