package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}
import java.lang.Number


object ConfigNull {
  def unapply(obj: Any): Option[Null] = 
    obj match {
      case t: ConfigValue if t.valueType == ConfigValueType.NULL => 
        Some(t.unwrapped.asInstanceOf[Null])
      case _ => None
    }
  def apply(): ConfigValue =
    j.ConfigValueFactory.fromAnyRef(null)
}