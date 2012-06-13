package com.typesafe.dsbt

import sbt._

trait PrettyPrint[T] {
  def apply(t: T): String
}

object PrettyPrint {
  def apply[T](t: T)(implicit ev: PrettyPrint[T]) = ev(t)

  val ScalaVersioned = new util.matching.Regex("(.+)_((\\d+)\\.(\\d+)(.+))")
  def fixName(name: String): String = name match {
    case ScalaVersioned(name, _*) => name
    case name => name
  }

  implicit object ModuleIdPretty extends PrettyPrint[ModuleID] {
    def apply(t: ModuleID): String = {
      import t._
      val sb = new StringBuilder("  {\n")
      sb append ("    name = %s\n" format (fixName(name)))
      sb append ("    organization = %s\n" format (organization))
      sb append ("  }\n")
      sb.toString
    }
  }

  implicit object MyDependencyInfoPretty extends PrettyPrint[MyDependencyInfo] {
    def apply(t: MyDependencyInfo): String = {
      import t._
      val sb = new StringBuilder("{\n")
      sb append ("  name = %s\n" format (fixName(name)))
      sb append ("  organization = %s\n" format (organization))
      sb append ("  version = \"%s\"\n" format (version))
      //sb append ("  module: %s\n" format (module))
      sb append ("  dependencies = %s\n" format (PrettyPrint(dependencies)))
      sb append ("}")
      sb.toString
    }
  }

  implicit def seqPretty[T : PrettyPrint]: PrettyPrint[Seq[T]] = new PrettyPrint[Seq[T]] {
    def apply(t: Seq[T]): String = 
      (t map { i => PrettyPrint(i) }).mkString("[", ",\n","]")
  }

  implicit object SbtBuildMetaDataPretty extends PrettyPrint[SbtBuildMetaData] {
    def apply(b: SbtBuildMetaData): String = {
      import b._
      val sb = new StringBuilder("{\n")
      sb append ("scm      = \"%s\"\n" format(scmUri))
      sb append ("projects = %s\n" format (PrettyPrint(projects)))
      sb append ("}")
      sb.toString
    }
  }  
}