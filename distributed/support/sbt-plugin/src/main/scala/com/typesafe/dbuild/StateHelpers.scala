package com.typesafe.dbuild

import sbt._
import distributed.project.model
import org.apache.commons.io.FileUtils.writeStringToFile

object StateHelpers {
  def getProjectRefs(extracted: Extracted): Seq[ProjectRef] =
    extracted.structure.allProjectRefs

  private def saveMsg(e: Throwable, lastMsg: String) = {
    val msg = e match {
      case x:sbt.Incomplete =>
        x.message match {
          case Some(s) => s
          case None => x.directCause match {
            case Some(i) => Option(i.getMessage) getOrElse ""
            case None => ""
          }
        }
      case x => x.getMessage
    }
    writeStringToFile(new File(lastMsg), msg, "UTF-8")
    e.printStackTrace()
    throw e
  }
  def saveLastMsg(f: State => State)(state: State): State = try {
    f(state)
  } catch {
    case e =>
      Option(System.getProperty("dbuild.sbt-runner.last-msg")) match {
        case None => throw e
        case Some(lastMsg) => saveMsg(e, lastMsg)
      }
  }
  
  def saveLastMsg(f: (State, Seq[String]) => State)(state: State, args: Seq[String]): State = try {
    f(state, args)
  } catch {
    case e =>
      Option(System.getProperty("dbuild.sbt-runner.last-msg")) match {
        case None => throw e
        case Some(lastMsg) => saveMsg(e, lastMsg)
      }
  }
}