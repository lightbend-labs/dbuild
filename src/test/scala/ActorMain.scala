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
  
  def scalacheck =
    BuildConfig("scalacheck", "sbt", "git://github.com/jsuereth/scalacheck.git#origin/community", "")

  def scalatest =
    BuildConfig("scalatest", "sbt", "https://scalatest.googlecode.com/svn/branches/cmty2.10", "")
    //BuildConfig("scalatest", "scalatest", "http://scalatest.googlecode.com/svn/branches/r18for210M4", "")

    
  def specs2scalaz =
    BuildConfig("specs2-scalaz", "sbt", "git://github.com/jsuereth/specs2-scalaz.git#origin/community", "")
  
  def specs2 =
    BuildConfig("specs2", "sbt", "git://github.com/jsuereth/specs2.git#origin/community", "")
    
  def scalaArm =
    BuildConfig("scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git#origin/community-build", "")
    
  def scalaIo =
    BuildConfig("scala-io", "sbt", "git://github.com/jsuereth/scala-io.git#origin/community", "")
  
  def scalaConfig =
    BuildConfig("scala", "scala", "git://github.com/scala/scala.git#master", "")
    //BuildConfig("scala", "scala", "git://github.com/scala/scala.git#4c6522bab70ce8588f5688c9b4c01fe3ff8d24fc", "")
    
  def sperformance =
    BuildConfig("sperformance", "sbt", "git://github.com/jsuereth/sperformance.git#origin/community", "")
    
  def scalaStm =
    BuildConfig("scala-stm", "sbt", "git://github.com/nbronson/scala-stm.git#master", "")
    
  def dBuildConfig =
    DistributedBuildConfig(Seq(scalaStm, scalatest, specs2, scalacheck, /*scalaIo,*/ scalaConfig, scalaArm, sperformance, specs2scalaz))
  
  def parsedDbuildConfig =
    parseStringInto[DistributedBuildConfig](repeatableConfig) getOrElse sys.error("Failure to parse: " + repeatableConfig)
    
  def smallBuild = DistributedBuildConfig(Seq(scalaArm))
    
  def runBuild =
    LocalBuildMain build dBuildConfig
    
  def repeatableConfig = """{"projects":[{"name":"scala","system":"scala","uri":"git://github.com/scala/scala.git#d1687d7418598b56269edbaa70a3b3ce820fdf64","directory":""},{"name":"sperformance","system":"sbt","uri":"git://github.com/jsuereth/sperformance.git#8c472f2a1ae8da817c43c873e3126c486aa79446","directory":""},{"name":"scala-arm","system":"sbt","uri":"git://github.com/jsuereth/scala-arm.git#8c324815e0f33873068a5c53e44b77eabba0a42b","directory":""},{"name":"scalacheck","system":"sbt","uri":"git://github.com/jsuereth/scalacheck.git#aa2b864673ce0f87cce43a7ff55a941cbc973b9f","directory":""},{"name":"specs2-scalaz","system":"sbt","uri":"git://github.com/jsuereth/specs2-scalaz.git#b27dc46434a53ed7c1676057ec672dac5e79d1b7","directory":""}]}"""
}