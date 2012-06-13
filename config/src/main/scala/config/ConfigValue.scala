package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}
import java.lang.Number


object ConfigValue {
  def unapply(obj: Any): Option[j.ConfigValue] = 
    obj match {
      case t: j.ConfigValue => Some(t) 
      case _                => None
    }
}