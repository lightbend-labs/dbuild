import distributed._
import distributed.build._
import project.build._
import project._
import model._
import dependencies._
import graph._
import distributed.project.resolve.ProjectResolver
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import config._

object ActorMain {
  // SBT plugins
  def sbtCommunityPluginProjectNames = Seq(
    "sbt-git-plugin",
    "sbt-release",
    "sbt-native-packager",
    "sbt-protobuf",
    "sbt-xjc",
    "sbt-onejar",
    "sbt-assembly",
    "sbt-yui-compressor",
    //"sbt-appengine",
    "sbt-appbundle",
    "sbt-ghpages-plugin",
    "sbt-site-plugin",
    "xsbt-gpg-plugin",
    "sbt-unique-version",
    "sbt-buildinfo",
    "sbt-sclaashim",
    "sbt-man",
    "sbt-twt",
    "sbt-dirty-money",
    "sbt-lify"
  )
  def sbtCommunityPlugins: Seq[BuildConfig] = 
    for(name <- sbtCommunityPluginProjectNames)
    yield BuildConfig(name, "sbt", "git://github.com/sbt/%s.git" format (name))
  
  
  def sbtTypesafeProjectNames = Seq(
      "sbt-multi-jvm" -> "master", 
      "sbteclipse" -> "master", 
      "sbtscalariform" -> "sbt-0.12", 
      //"xsbt-start-script-plugin" -> "master", 
      "sbt-idea" -> "master")
  def sbtTypesafePlugins: Seq[BuildConfig] = 
    for((name, branch) <- sbtTypesafeProjectNames)
    yield BuildConfig(name, "sbt", "git://github.com/typesafehub/%s.git#%s" format (name, branch))
  
  
  def sbtPlugins = sbtTypesafePlugins ++ sbtCommunityPlugins
  
  def scalacheck =
    //BuildConfig("scalacheck", "sbt", "git://github.com/rickynils/scalacheck.git#master", "")
    BuildConfig("scalacheck", "sbt", "git://github.com/jsuereth/scalacheck.git#origin/master")

  def scalatest =
    BuildConfig("scalatest", "sbt", "https://scalatest.googlecode.com/svn/branches/cmty2.10")
    //BuildConfig("scalatest", "scalatest", "http://scalatest.googlecode.com/svn/branches/r18for210M4")

    
  def specs2scalaz =
    BuildConfig("specs2-scalaz", "sbt", "git://github.com/jsuereth/specs2-scalaz.git#origin/community")
  
  def specs2 =
    BuildConfig("specs2", "sbt", "git://github.com/jsuereth/specs2.git#origin/community")
    
  def scalaArm =
    BuildConfig("scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git#origin/community-build")
    
  def scalaIo =
    BuildConfig("scala-io", "sbt", "git://github.com/jsuereth/scala-io.git#origin/community")
  
  def scalaConfig =
    //BuildConfig("scala", "scala", "git://github.com/scala/scala.git#d0623dc766")
    //BuildConfig("scala", "scala", "git://github.com/scala/scala.git#5c5e8d4dcd")  BORKED
    BuildConfig("scala", "scala", "git://github.com/scala/scala.git#2.10.x")
    //BuildConfig("scala", "scala", "git://github.com/scala/scala.git#4c6522bab70ce8588f5688c9b4c01fe3ff8d24fc", "")
    
  def sperformance =
    BuildConfig("sperformance", "sbt", "git://github.com/jsuereth/sperformance.git#origin/community")
    
  def scalaStm =
    BuildConfig("scala-stm", "sbt", "git://github.com/nbronson/scala-stm.git#master")
  
  def scalariform = 
    BuildConfig("scalariform", "maven", "git://github.com/mdr/scalariform.git#master")
    
  def akka =
    BuildConfig("akka", "sbt", "https://github.com/akka/akka.git#master")
    
  def scalaGraph =
    BuildConfig("scala-graph", "sbt", "http://subversion.assembla.com/svn/scala-graph/trunk")
    
  def communityProjects = 
    Seq(akka, scalaStm, specs2, scalacheck, /*scalaIo,*/ scalaConfig, scalaArm, sperformance, specs2scalaz, scalatest, scalaGraph)
    
    
  def dBuildConfig =
    //DistributedBuildConfig(Seq(scalariform))
    DistributedBuildConfig(Seq(scalaConfig, scalaGraph))
    //DistributedBuildConfig(/*sbtPlugins ++*/ communityProjects)
  
  def parsedDbuildConfig =
    parseStringInto[DistributedBuildConfig](repeatableConfig) getOrElse sys.error("Failure to parse: " + repeatableConfig)
    
  def smallBuild = DistributedBuildConfig(Seq(scalaArm))
    
  def runBuild =
    LocalBuildMain build dBuildConfig
    
  def repeatableConfig = """{"projects":[{"name":"scala","system":"scala","uri":"git://github.com/scala/scala.git#d1687d7418598b56269edbaa70a3b3ce820fdf64","directory":""},{"name":"sperformance","system":"sbt","uri":"git://github.com/jsuereth/sperformance.git#8c472f2a1ae8da817c43c873e3126c486aa79446","directory":""},{"name":"scala-arm","system":"sbt","uri":"git://github.com/jsuereth/scala-arm.git#8c324815e0f33873068a5c53e44b77eabba0a42b","directory":""},{"name":"scalacheck","system":"sbt","uri":"git://github.com/jsuereth/scalacheck.git#aa2b864673ce0f87cce43a7ff55a941cbc973b9f","directory":""},{"name":"specs2-scalaz","system":"sbt","uri":"git://github.com/jsuereth/specs2-scalaz.git#b27dc46434a53ed7c1676057ec672dac5e79d1b7","directory":""}]}"""
}
