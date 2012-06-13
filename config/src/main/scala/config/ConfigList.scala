package config

import com.typesafe.{config=>j}
import collection.JavaConverters._
import collection.mutable.Buffer
  
object ConfigList {
  def unapply(obj: Any): Option[Buffer[ConfigValue]] = 
    obj match {
      case t: ConfigValue if t.valueType == ConfigValueType.LIST => 
        Some(t.asInstanceOf[ConfigList].asScala)
      case _ => None
    }
  def apply(value: Iterable[Any]): ConfigValue =
    j.ConfigValueFactory.fromIterable(value.asJava)
}