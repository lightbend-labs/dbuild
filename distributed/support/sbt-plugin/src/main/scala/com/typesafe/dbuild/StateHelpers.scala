package com.typesafe.dbuild

import sbt._
import distributed.project.model

object StateHelpers {
  def getProjectRefs(extracted:Extracted): Seq[ProjectRef] =
    extracted.structure.allProjectRefs
}