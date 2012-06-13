package com.typesafe.dsbt

import sbt._

case class SbtBuildMetaData(projects: Seq[MyDependencyInfo],
                            scmUri: String)
                            
                            
case class MyDependencyInfo(project: ProjectRef,
                            name: String, 
                            organization: String, 
                            version: String, 
                            module: ModuleID,
                            dependencies: Seq[ModuleID] = Seq()) {}