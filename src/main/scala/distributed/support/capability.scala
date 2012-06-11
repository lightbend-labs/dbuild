package distributed
package support

case class Capability(name: String, version: String, props: Map[String, String] = Map.empty)
