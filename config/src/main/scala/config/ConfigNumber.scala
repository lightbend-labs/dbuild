package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}
import java.lang.Number


object ConfigNumber {
  def unapply(obj: Any): Option[Number] = 
    obj match {
      case t: j.ConfigValue if t.valueType == ConfigValueType.NUMBER => 
        Some(t.unwrapped.asInstanceOf[Number])
      case _ => None
    }
}