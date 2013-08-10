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
  /** The name of the project associated with the outcome. The project name "." is used to refer to the root of dbuild. */
  def project: String
  /** the outcomes of all the dependencies of this project */
  def outcomes: Seq[BuildOutcome]
  /** A short status string. This is the only string that you should define in a BuildOutcome, all the rest goes in the template.  */
  def status(): String
}

sealed abstract class BuildGood extends BuildOutcome {
  override def whenIDs: Seq[String] = "good" +: super.whenIDs
  def artsOut: BuildArtifactsOut
}
sealed abstract class BuildBad extends BuildOutcome {
  override def whenIDs: Seq[String] = "bad" +: super.whenIDs
}

/** We rebuilt the project, and all was ok. */
case class BuildSuccess(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  override def toString() = "BuildSuccess(" + project + ",<arts>)"
  def status() = "SUCCESS (project rebuilt ok)"
  override def whenIDs: Seq[String] = "success" +: super.whenIDs
}

/** It was not necessary to re-run this build, as nothing changed. */
case class BuildUnchanged(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  override def toString() = "BuildCached(" + project + ",<arts>)"
  def status() = "SUCCESS (unchanged, not rebuilt)"
  override def whenIDs: Seq[String] = "unchanged" +: super.whenIDs
}

/** This build was attempted, but an error condition occurred while executing it. */
case class BuildFailed(project: String, outcomes: Seq[BuildOutcome], cause: String) extends BuildBad {
  def status() = "FAILED (" + cause + ")"
  override def whenIDs: Seq[String] = "failed" +: super.whenIDs
}

/** One or more of this project dependencies are broken, therefore we could not build. */
case class BuildBrokenDependency(project: String, outcomes: Seq[BuildOutcome]) extends BuildBad {
  def status() = "DID NOT RUN (stuck on broken dependency: " +
    (outcomes.filter { case _: BuildFailed => true; case _ => false }).map { _.project }.mkString(",") + ")"
  override def whenIDs: Seq[String] = "dep-broken" +: super.whenIDs
}

/** Outcome of Extraction; subclass of BuildBad since, if this status is returned,
 *  something went wrong and we did not get to building. */
sealed abstract class ExtractionOutcome extends BuildBad {
  override def whenIDs: Seq[String] = "extraction" +: super.whenIDs  
}
/** Extraction was OK, but we have not proceeded to the building stage yet.
 *  Returns the set of nested outcomes (in case extraction is done
 *  hierarchically, for example on multiple machines), and the
 *  set of successful RepeatableDistributedBuilds collected along the way. */
case class ExtractionOK(project: String, outcomes: Seq[BuildOutcome], pces: Seq[ProjectConfigAndExtracted]) extends ExtractionOutcome {
  def status() = "Extraction ok, could not proceed"
  override def whenIDs: Seq[String] = "extraction-ok" +: super.whenIDs
}
/** Something went wrong during extraction (for instance, could not resolve).
 *  We do not bother collecting the RepeatableDistributedBuilds, since we
 *  cannot proceed anyway. */
case class ExtractionFailed(project: String, outcomes: Seq[BuildOutcome], cause: String) extends ExtractionOutcome {
  def status() = "EXTRACTION FAILED ("+ cause + ")"
  override def whenIDs: Seq[String] = "extraction-failed" +: super.whenIDs
}

/**
 * We run post-build tasks even if extraction or build failed. If tasks complete successfully,
 * we return the original status, otherwise we return a special combo status.
 */
case class TaskFailed(project: String, outcomes: Seq[BuildOutcome], original:BuildOutcome, taskCause: String) extends BuildBad {
  def status() = original.status()+" + TASK FAILED ("+ taskCause + ")"
  override def whenIDs: Seq[String] = "task-failed" +: original.whenIDs
}

/**
 * Utility classes, used to perform variable substitutions
 */
case class SubstitutionNotifications(@JsonProperty("template-vars") vars: SubstitutionVars)
case class SubstitutionVars(
  @JsonProperty("project-name") projectName: String,
  @JsonProperty("subprojects-report") subprojectsReport: String,
  @JsonProperty("project-description") projectDescription: String,
  @JsonProperty("padded-project-description") paddedProjectDescription: String,
  @JsonProperty("config-name") configName: String,
  status: String)
// they become:     ${notifications.vars.project-name}, etc.

/**
 * Create a TemplateFormatter with your resolved template and vars, in order to obtain the expanded
 * values, via the Typesafe config library.
 */
class TemplateFormatter(templ: ResolvedTemplate, outcome: BuildOutcome, confName:String) {
  /** A report that concatenates the summaries of subprojects, using the same template */
  lazy val subprojectsReport: String = {
    def get(o: BuildOutcome) = new TemplateFormatter(templ, o, confName).summary
    val s = outcome.outcomes.map(get)
    if (s.isEmpty) "" else s.mkString("", "\n", "\n")
  }
  val paddedProjectDescription =
    // "." is the name of the root project
    if (outcome.project == ".") "The dbuild result is-----------" else "Project " + (outcome.project.padTo(23, "-").mkString)

  private val notifVars = SubstitutionNotifications(
    SubstitutionVars(projectName = outcome.project,
      subprojectsReport = subprojectsReport,
      status = outcome.status,
      projectDescription = if (outcome.project != ".") "project " + outcome.project else "the configuration \""+confName+"\"",
      paddedProjectDescription = paddedProjectDescription,
      configName = confName))

  // The expansion logic is tricky to get right. The limitation is that the typesafe config library will not
  // perform replacements inside double quotes, and when we write using the same library we do get double quotes.
  // Therefore, if we have a case class with text fields that need to undergo variable replacement, we're in
  // the cold. What we do here: we escape the string in a manner that it looks equivalent to what one would
  // write in the source code as a Java string (with the right escapes everywhere, including new lines etc).
  // Then, we look for the pattern ${.*?}, and we change it into "${.*?}", so that a string like "abc${X}def"
  // becomes "abc"${X}"def". We manually build a JSON string with *that* modified string, and finally we
  // read it again using HOCON. At this point, thankfully, we have our full expansion, both with the variables
  // in the SubstitutionVars, as well as all the environment vars. Phew!
  private def escaped(s: String) = StringEscapeUtils.escapeJava(s)
  private def preparedForReplacement(s: String) = escaped(s).replaceAll("(\\$\\{.*?\\})", "\"$1\"")
  private def expand(s: String) =
    parseString("{key:\"" + preparedForReplacement(s) + "\",dbuild:" + writeValue(notifVars) + "}").resolve.getString("key")

  val summary = expand(templ.summary)
  val short = expand(templ.short)
  lazy val long = expand(templ.long)
  val id = templ.id
  // Note: each time a template is instantiated, the subproject report calls recursively summary() on
  // all subprojects, and in order to initialize their substitution, their own subproject reports are generated
  // recursively; then it is all thrown away and all starts again with the next notification. The lazy vals
  // above avoid most of this recursion; the code does works even without laziness, in any case.
}
