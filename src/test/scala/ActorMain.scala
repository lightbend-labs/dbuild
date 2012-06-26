import distributed._
import distributed.build._
import project.build._
import project._
import model._
import dependencies._
import graph._
import distributed.project.resolve.ProjectResolver
import akka.actor.{Actor, ActorRef, ActorSystem, Props}

object ActorMain {
  
  def scalacheck =
    BuildConfig("scalacheck", "sbt", "git://github.com/jsuereth/scalacheck.git#origin/community", "")

  def specs2scalaz =
    BuildConfig("specs2-scalaz", "sbt", "git://github.com/jsuereth/specs2-scalaz.git#origin/community", "")
  
  def specs2 =
    BuildConfig("specs2", "sbt", "git://github.com/jsuereth/specs2.git#origin/community", "")
    
  def scalaArm =
    BuildConfig("scala-arm", "sbt", "git://github.com/jsuereth/scala-arm.git#origin/community-build", "")
    
  def scalaIo =
    BuildConfig("scala-io", "sbt", "git://github.com/jsuereth/scala-io.git#origin/community", "")
  
  def scalaConfig =
    BuildConfig("scala", "scala", "git://github.com/scala/scala.git#4c6522bab70ce8588f5688c9b4c01fe3ff8d24fc", "")
    
  def sperformance =
    BuildConfig("sperformance", "sbt", "git://github.com/jsuereth/sperformance.git#origin/community", "")
    
  def dBuildConfig =
    DistributedBuildConfig(Seq(specs2, scalacheck, /*scalaIo,*/ scalaConfig, scalaArm, sperformance, specs2scalaz))
  
  def parsedDbuildConfig =
    DistributedBuildParser.parseBuildString(repeatableConfig)
    
  def runBuild =
    LocalBuildMain build dBuildConfig
    
  def repeatableConfig = """{"projects":[
    {"name":"scala","system":"scala","uri":"git://github.com/scala/scala.git#4c6522bab70ce8588f5688c9b4c01fe3ff8d24fc","directory":""},
    {"name":"sperformance","system":"sbt","uri":"git://github.com/jsuereth/sperformance.git#8c472f2a1ae8da817c43c873e3126c486aa79446","directory":""},
    {"name":"scala-arm","system":"sbt","uri":"git://github.com/jsuereth/scala-arm.git#86d3477a7ce91b9046197f9f6f49bf9ff8a137f6","directory":""},
    {"name":"scalacheck","system":"sbt","uri":"git://github.com/jsuereth/scalacheck.git#5b74b901460920dace3feb82549157976b26fe3f","directory":""}]}"""
}