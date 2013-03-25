package com.typesafe.dbuild

import sbt._
import distributed.project.model

object StateHelpers {
  def getProjectRefs(settings: Seq[Setting[_]]): Set[ProjectRef] =
    (settings map (_.key.scope) collect {
      case Scope(Select(p @ ProjectRef(_,_)),_,_,_) => p
    } toSet)
}