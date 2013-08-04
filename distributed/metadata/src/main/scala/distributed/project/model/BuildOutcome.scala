package distributed.project.model

import com.fasterxml.jackson.annotation.JsonProperty
import distributed.project.model.Utils.{ readValue, writeValue }
import org.apache.commons.lang.StringEscapeUtils
import com.typesafe.config.ConfigFactory.parseString

/**
 * The outcome of a build. Override toString only for internal diagnostics, and define the
 * other methods instead for the user-facing messages.
 */
sealed abstract class BuildOutcome {
  /** The default long response, with a subject line (usually shorter than shortTemplate (about 50-60 characters), and a body of arbitrary length. */
  def whenIDs: Seq[String] = Seq("always")
  /** The name of the project associated with the outcome. The empty string is used to mark the root of dbuild. */
  def project: String
  /** the outcomes of all the dependencies of this project */
  def outcomes: Seq[BuildOutcome]

  /** The default notification template; see Notification for further details. */
  def defaultTemplate(): NotificationTemplate = NotificationTemplate("",
    (if (project == "") "The dbuild result is-----------" else "Project " + project.padTo(23,"-").mkString) + ": " + defaultSummary,
    None,
    Some("Report from the dbuild run"+(if (project == "") "" else "for project "+project)+":\n${notifications.vars.subprojects-report}"))

  // the utility methods below are used to build the default template, and are used in the substitutions,
  // but are /not/ part of any template per se.
  /** Default summary string. This is the only string that you should define here, all the rest goes in the template.  */
  def defaultSummary(): String
  /** A report that concatenates the summaries of subprojects, using their default template, and the summary of this project */
  def subprojectsReport: String = {
    def get(o: BuildOutcome) = o.defaultTemplate.summary
    (outcomes.map(get) :+ (">>> " + get(this))).mkString("", "\n", "\n")
  }
}

sealed abstract class BuildGood extends BuildOutcome {
  override def whenIDs: Seq[String] = super.whenIDs :+ "good"
  def artsOut: BuildArtifactsOut
}
sealed abstract class BuildBad extends BuildOutcome {
  override def whenIDs: Seq[String] = super.whenIDs :+ "bad"
}

/** We rebuilt the project, and all was ok. */
case class BuildSuccess(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  override def toString() = "BuildSuccess(" + project + ",<arts>)"
  def defaultSummary() = "SUCCESS (project rebuilt ok)"
  override def whenIDs: Seq[String] = super.whenIDs :+ "success"
}

/** It was not necessary to re-run this build, as nothing changed. */
case class BuildCached(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  override def toString() = "BuildCached(" + project + "<arts>)"
  def defaultSummary() = "SUCCESS (was unchanged, not rebuilt)"
  override def whenIDs: Seq[String] = super.whenIDs :+ "cached"
}

/** This build was attempted, but an error condition occurred while executing it. */
case class BuildFailed(project: String, outcomes: Seq[BuildOutcome], cause: String) extends BuildBad {
  def defaultSummary() = "FAILED (cause: " + cause+")"
  override def whenIDs: Seq[String] = super.whenIDs :+ "failed"
}

/** One or more of this project dependencies are broken, therefore we could not build. */
case class BuildBrokenDependency(project: String, outcomes: Seq[BuildOutcome]) extends BuildBad {
  def defaultSummary() = "DID NOT RUN (stuck on broken dependency: " +
    (outcomes.filter { case _: BuildFailed => true; case _ => false }).map { _.project }.mkString(",") + ")"
  override def whenIDs: Seq[String] = super.whenIDs :+ "depBroken"
}

/**
 * Utility classes, used to perform variable substitutions
 */
case class Substitution(template: ResolvedTemplate, notifications: SubstitutionNotifications)
case class SubstitutionNotifications(vars: SubstitutionVars)
case class SubstitutionVars(
  @JsonProperty("project-name") projectName: String,
  @JsonProperty("subprojects-report") subprojectsReport: String)
// they become:     ${notifications.vars.project-name}, etc.

/**
 * Create a TemplateFormatter with your resolved template and vars, in order to obtain the expanded
 * values, via the Typesafe config library.
 */
class TemplateFormatter(templ: ResolvedTemplate, outcome: BuildOutcome) {
  // The expansion logic is tricky to get right. The limitation is that the typesafe config library will not
  // perform replacements inside double quotes, and when we write using the same library we do get double quotes.
  // Therefore, if we have a case class with text fields that need to undergo variable replacement, we're in
  // the cold. What we do here: we escape the string in a manner that it looks equivalent to what one would
  // write in the source code as a Java string (with the right escapes everywhere, including new lines etc).
  // Then, we look for the pattern ${.*?}, and we change it into "${.*?}", so that a string like "abc${X}def"
  // becomes "abc"${X}"def". We manually build a JSON string with *that* modified string, and finally we
  // read it again using HOCON. At this point, thankfully, we have our full expansion, both with the variables
  // in the SubstitutionVars, as well as all the environment vars. Phew!
  private val expanded = {
    val notifVars = SubstitutionNotifications(SubstitutionVars(projectName = outcome.project, subprojectsReport = outcome.subprojectsReport))
    def escaped(s: String) = StringEscapeUtils.escapeJava(s)
    def preparedForReplacement(s: String) = escaped(s).replaceAll("(\\$\\{.*?\\})", "\"$1\"")
    def expand(s: String) = parseString("{key:\"" + preparedForReplacement(s) + "\",notifications:" + writeValue(notifVars) + "}").resolve.getString("key")
    ResolvedTemplate(templ.id, expand(templ.summary), expand(templ.short), expand(templ.long))
  }
  def summary = expanded.summary
  def short = expanded.short
  def long = expanded.long
  def id = expanded.id
}
