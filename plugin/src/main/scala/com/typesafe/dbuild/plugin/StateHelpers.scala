package com.typesafe.dbuild.plugin

import sbt._
import sbt.dbuild.hack.DbuildHack.ExceptionCategory
import org.apache.commons.io.FileUtils.writeStringToFile

object StateHelpers {
  def getProjectRefs(extracted: Extracted): Seq[ProjectRef] =
    extracted.structure.allProjectRefs

  private def saveMsg(e: Throwable, lastMsgFile: File): Nothing = {
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
    import ExceptionCategory.{ AlreadyHandled, MessageOnly, Full }
    ExceptionCategory(e) match {
      case AlreadyHandled => ()
      case m: MessageOnly => System.err.println(m.message)
      case _: Full        => e.printStackTrace()
    }
    throw e
  }
  
  def saveLastMsg(lastMsgFile: File, f: (State, Seq[String]) => State)(state: State, args: Seq[String]): State = try {
    f(state, args)
  } catch {
    case e: Throwable => saveMsg(e, lastMsgFile)
  }
  
  def saveLastMsg(lastMsgFile: File, f: State => State)(state: State): State = try {
    f(state)
  } catch {
    case e: Throwable => saveMsg(e, lastMsgFile)
  }

}
