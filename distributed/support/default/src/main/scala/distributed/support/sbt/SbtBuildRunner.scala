package distributed
package support
package sbt

import project.model._
import _root_.sbt.{IO, Path, PathExtra}
import Path._
import _root_.java.io.File
import sys.process.Process
import config.{makeConfigString, parseFileInto}

// Yeah, this need a ton of cleanup, but hey it was pulled from a BASH
// script...
object SbtBuilder {
  
  def writeRepoFile(config: File, repo: File): Unit =
    Repositories.writeRepoFile(config, "build-local" -> repo.toURI.toASCIIString)
    
  
  def makeDebugPak(dir: File, name: String, config: SbtBuildConfig, log: logging.Logger): File = 
    IO.withTemporaryDirectory { tmpDir =>
      val dsbtDir = tmpDir / ".dsbt"
      val readme = tmpDir / "DBUILD-README"
      val pluginDir = dsbtDir / "plugins"
      pluginDir.mkdirs()
      val pluginFile = pluginDir / "dplugin.sbt"
      SbtRunner writeDeps pluginFile
      val depsFile = dsbtDir / "deps.dsbt"
      val repoFile = dsbtDir / "repositories"
      val runner = tmpDir / "dbuild"
      val launchJar = tmpDir / "sbt-launch.jar"
      val repoDir = dsbtDir / "repository"
      // Move artifacts into subdirectory
      val localRepo = config.artifacts.localRepo
      val arts = 
        for( (file, loc) <- (tmpDir.*** --- tmpDir x (f => IO.relativize(tmpDir, f))))
        yield file -> (".dsbt/repository/" + loc)
      IO.copyDirectory(config.artifacts.localRepo, repoDir)
      // TODO - Rewire artifacts
      IO.write(depsFile, makeConfigString(config))
      SbtRunner.transferResource("sbt-launch.jar", launchJar)
      Repositories.writeRepoFile(repoFile, "build-local" -> "file://${sbt.global.base}/repository")
      IO.write(readme, """Distributed builder Debug PAK README
          
This software is designed to make debugging a community/aggregate build simpler.   here's what you need to do:

1. Unzip this zip file in the project it's intended to debug.
2. Ensure you have no project/build.properties or that the sbt.version is set to 0.12.0
3. Clean your `target/` `project/target` and `project/target/target` directories.
4. `chmod a+x dbuild`.
5. `./dbuild` in the directory you expldoed the zip.
6. Type `dsbt-setup` in the SBT prompt.  If this doesn't work, you may have forgotten step 3.
7. Run normal SBT options.  DO NOT use the `set` or `reload` commands without also running `dsbt-setup` afterwords.
          
  Let me know if you experience issues!
          - Josh
          Joshua.Suereth@typesafe.com
""")
      
      
      val cmd = "#!/bin/bash\n" + SbtRunner.makeShell("sbt-launch.jar",
        Map("sbt.version" -> SbtConfig.sbtVersion,
            "sbt.global.base" -> "$(pwd)/.dsbt",
            "sbt.override.build.repos" -> "true",
            "sbt.log.noformat" -> "true",
            "sbt.repository.config" -> ".dsbt/repositories",
            "project.build.results.file" -> "results.dsbt",
            "project.build.deps.file" -> ".dsbt/deps.dsbt",
            "sbt.ivy.home" -> ".dsbt/ivy2"))().mkString("", " \\\n  ", "  ")
      IO.write(runner, cmd)
      runner.setExecutable(true, true)
      
      if(!dir.isDirectory()) dir.mkdirs()
      val zip = dir / (name + ".zip")
      log.debug("Creating debug pak at: " + zip.getAbsolutePath)
      val tmpFiles = tmpDir.*** --- tmpDir x (f => IO.relativize(tmpDir, f))
      IO.zip(tmpFiles ++ arts, zip)
      zip
    }
    
    
  def buildSbtProject(runner: SbtRunner)(project: File, config: SbtBuildConfig, log: logging.Logger): BuildArtifacts = {    
    IO.withTemporaryDirectory { tmpDir => 
      val resultFile = tmpDir / "results.dsbt"
      // TODO - Where should depsfile + repo file be?  
      // For debugging/reproducing issues, we're putting them in a local directory for now.
      val dsbtDir = project / ".dsbt"
      val depsFile = dsbtDir / "deps.dsbt"
      val repoFile = dsbtDir / "repositories"
      // We need a new ivy cache to ensure no corruption of minors (or projects)
      val ivyCache = dsbtDir / "ivy2"
      IO.write(depsFile, makeConfigString(config))
      writeRepoFile(repoFile, config.artifacts.localRepo)
      log.debug("Runing SBT build in " + project + " with depsFile " + depsFile)
      runner.run(
        projectDir = project,
        log = log,
        javaProps = Map(
            "sbt.repository.config" -> repoFile.getAbsolutePath,
            "project.build.results.file" -> resultFile.getAbsolutePath,
            "project.build.deps.file" -> depsFile.getAbsolutePath,
            "sbt.ivy.home" -> ivyCache.getAbsolutePath)
      )("dsbt-build")      
      (parseFileInto[BuildArtifacts](resultFile) getOrElse
        sys.error("Failed to generate or load build results!"))
    }
  }
}