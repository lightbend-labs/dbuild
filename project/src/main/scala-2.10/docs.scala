import sbt.Keys._
import sbt.Path._
import sbt.IO
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

    def synchLocalImpl = (mappings in GhPagesKeys.synchLocal, GhPagesKeys.updatedRepository, version, isSnapshot, streams) map {
        (mappings, repo, v, snap, s) =>
            val versioned = repo / v
            if(snap) {
                    s.log.info("Replacing docs for previous snapshot in: " + versioned.getAbsolutePath)
                    IO.delete(versioned)
            } else if(versioned.exists) {
                    s.log.warn("Site for " + v + " was already in: " + versioned.getAbsolutePath)
                    s.log.info("Replacing previous docs...")
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
            s.log.info("Copied site to " + versioned)
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

  def settings:Seq[Setting[_]] = site.settings ++ site.sphinxSupport() ++
    ghpages.settings ++ Seq(
      enableOutput in generatePdf in Sphinx := false,
      enableOutput in generateEpub in Sphinx := false,
      git.remoteRepo := "git@github.com:typesafehub/dbuild.git",
      GhPagesKeys.synchLocal <<= DocsSupport.synchLocalImpl,
      publish := (),
      publishLocal := ()
    )
}
