import sbt.Keys._
import sbt.Path._
import sbt.IO
import sbt.Logger
import java.io.File
import sbt.{Setting,PatternFilter,DirectoryFilter}
import com.typesafe.sbt.SbtSite.site
import com.typesafe.sbt.SbtGhPages.{ghpages, GhPagesKeys}
import com.typesafe.sbt.SbtGit.{git, GitKeys}
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.{ enableOutput, generatePdf, generatedPdf, generateEpub,
                                             generatedEpub, sphinxInputs, sphinxPackages, Sphinx }

// based on the related sbt source code
object DocsSupport {
    val VersionPattern = """(\d+)\.(\d+)\.(\d+)(-.+)?""".r.pattern

    def synchLocalImpl(mappings:Seq[(java.io.File, String)], repo:File, v:String, snap:Boolean, log:Logger) = {
            val versioned = repo / v
            if(snap) {
                    log.info("Replacing docs for previous snapshot in: " + versioned.getAbsolutePath)
                    IO.delete(versioned)
            } else if(versioned.exists) {
                    log.warn("Site for " + v + " was already in: " + versioned.getAbsolutePath)
                    log.info("Replacing previous docs...")
                    IO.delete(versioned)
            }
            IO.copy(mappings map { case (file, target) => (file, versioned / target) })
            IO.touch(repo / ".nojekyll")
            IO.write(repo / "versions.js", versionsJs(sortVersions(collectVersions(repo))))
            if (!snap) IO.write(repo / "index.html",
                                """|<!DOCTYPE HTML PUBLIC "-//W3C//DTD HTML 4.0 Transitional//EN">
                                   |<HTML><HEAD><title>dbuild</title>
                                   |<meta http-equiv="REFRESH" content="0;url=""".stripMargin+v+
                                """|/index.html"></HEAD>
                                   |<BODY><p><a href=""".stripMargin+"\""+v+"""/index.html"> </a></p></BODY>
                                   |</HTML>""".stripMargin)
            log.info("Copied site to " + versioned)
            repo
    }
    def versionsJs(vs: Seq[String]): String = "var availableDocumentationVersions = " + vs.mkString("['", "', '", "']")
    // names of all directories that are explicit versions
    def collectVersions(base: File): Seq[String] = (base * versionFilter).get.map(_.getName)
    def sortVersions(vs: Seq[String]): Seq[String] = vs.sortBy(versionComponents).reverse
    def versionComponents(v: String): Option[(Int,Int,Int,Option[String])] = {
            val m = VersionPattern.matcher(v)
            if(m.matches())
                    Some( (m.group(1).toInt, m.group(2).toInt, m.group(3).toInt, Option(m.group(4))) )
            else
                    None
    }
    def versionFilter = new PatternFilter(VersionPattern) && DirectoryFilter

  import com.typesafe.sbt.SbtSite.SiteKeys.makeSite
  import com.typesafe.sbt.SbtSite.SiteKeys.siteSourceDirectory
  def settings:Seq[Setting[_]] = site.settings ++ site.sphinxSupport() ++
    ghpages.settings ++ Seq(
      enableOutput in generatePdf in Sphinx := false,
      enableOutput in generateEpub in Sphinx := false,
      git.remoteRepo := "git@github.com:lightbend/dbuild.git",
      GhPagesKeys.synchLocal := {
        val maps = (mappings in GhPagesKeys.synchLocal).value
        val repo = GhPagesKeys.updatedRepository.value
        val v = version.value
        val snap = isSnapshot.value
        val log = streams.value.log
        DocsSupport.synchLocalImpl(maps, repo, v, snap, log)
      },
      publish := (),
      publishLocal := (),
      makeSite := makeSite.dependsOn(sbt.Def.task {
        val file = (siteSourceDirectory in Sphinx).value / "version.py"
        IO.write(file, ("release = '%s'\n" format (version.value)))
      }).value
  )
}
