package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}
import java.lang.Number


object ConfigBoolean {
  def unapply(obj: Any): Option[Boolean] = 
    obj match {
      case t: ConfigValue if t.valueType == ConfigValueType.BOOLEAN => 
        Some(t.unwrapped.asInstanceOf[Boolean])
      case _ => None
    }
  def apply(b: Boolean): ConfigValue =
    j.ConfigValueFactory.fromAnyRef(b)
}