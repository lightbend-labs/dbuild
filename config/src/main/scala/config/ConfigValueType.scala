package config

import com.typesafe.{ config => j }

// Annoyingly, this needs to be here to delegate to the Java Enum.
object ConfigValueType {
  val OBJECT = j.ConfigValueType.OBJECT
  val LIST = j.ConfigValueType.LIST
  val NUMBER = j.ConfigValueType.NUMBER
  val BOOLEAN = j.ConfigValueType.BOOLEAN
  val NULL = j.ConfigValueType.NULL
  val STRING = j.ConfigValueType.STRING
}