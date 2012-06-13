package config

import com.typesafe.{config=>j}
import java.util.{Map=> JMap}


object ConfigObject {
  def unapply(obj: Any): Option[JMap[String, Any]] = 
    obj match {
      case t: ConfigValue if t.valueType == ConfigValueType.OBJECT  =>
        // TODO - don't unwrap...
        Some(t.unwrapped.asInstanceOf[JMap[String, Any]])
      case _ => None
    }
  import collection.JavaConverters._
  def apply(value: Map[String, Any]): ConfigValue =
    j.ConfigValueFactory.fromMap(value.asJava)
}