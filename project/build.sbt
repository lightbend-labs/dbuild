lazy val root = (project in file(".")).dependsOn(BintrayDep211.bintrayIf211:_*)
