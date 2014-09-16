package com.typesafe.dbuild

import sbt._
import distributed.project.model
import org.apache.commons.io.FileUtils.writeStringToFile

object StateHelpers {
  def getProjectRefs(extracted: Extracted): Seq[ProjectRef] =
    extracted.structure.allProjectRefs

  private def saveMsg(e: Throwable, lastMsgFile: File) = {
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
    writeStringToFile(lastMsgFile, msg, "UTF-8")
    e.printStackTrace()
    throw e
  }
  
  def saveLastMsg(lastMsgFile: File, f: (State, Seq[String]) => State)(state: State, args: Seq[String]): State = try {
    f(state, args)
  } catch {
    case e => saveMsg(e, lastMsgFile)
  }
  
  def saveLastMsg(lastMsgFile: File, f: State => State)(state: State): State = try {
    f(state)
  } catch {
    case e => saveMsg(e, lastMsgFile)
  }

}