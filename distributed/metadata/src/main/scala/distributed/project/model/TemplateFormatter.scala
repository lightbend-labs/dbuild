package distributed.project.model

import com.fasterxml.jackson.annotation.JsonProperty
import distributed.project.model.Utils.{ readValue, writeValue }
import org.apache.commons.lang.StringEscapeUtils
import com.typesafe.config.ConfigFactory.parseString

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
//    def get(o: BuildOutcome) = new TemplateFormatter(templ, o, confName).summary
//    val s = outcome.outcomes.map(get)
    val s = outcome.outcomes.map{o=>paddedProjectDescription(o)+": "+o.status}
    if (s.isEmpty) "" else s.mkString("", "\n", "\n")
  }
  def paddedProjectDescription(outcome:BuildOutcome) =
    // "." is the name of the root project
    if (outcome.project == ".") "The dbuild result is-----------" else "Project " + (outcome.project.padTo(23, "-").mkString)

  private val notifVars = SubstitutionNotifications(
    SubstitutionVars(projectName = outcome.project,
      subprojectsReport = subprojectsReport,
      status = outcome.status,
      projectDescription = if (outcome.project != ".") "project " + outcome.project else "dbuild",
      paddedProjectDescription = paddedProjectDescription(outcome),
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
