lazy val root = (project in file(".")).dependsOn(BintrayDep212.bintrayIf212:_*)

libraryDependencies += Dispatch.dispatch
