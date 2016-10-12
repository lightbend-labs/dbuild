sourceGenerators in Compile += task {
    val dir = (sourceManaged in Compile).value
    val fileName = "Adapter.scala"
    val file = dir / fileName
    val sv = scalaVersion.value
    val v = sbtVersion.value
    if(!dir.isDirectory) dir.mkdirs()
    val content = if (v.startsWith("1.0"))
"""
object SyntaxAdapter {
  val syntax = sbt.syntax
  val syntaxio = sbt.io.syntax
  val syntaxCompile = sbt.syntax.Compile
}""" else """
object SyntaxAdapter {
  class Empty {}
  val syntax = new Empty
  val syntaxio = syntax
  val syntaxCompile = sbt.Compile
}
"""
    IO.write(file, content)
    Seq(file)
}
