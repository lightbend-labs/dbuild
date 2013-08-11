package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import Creds.loadCreds
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._
import Message.RecipientType
import RecipientType._
import Creds.loadCreds
import distributed.project.model.TemplateFormatter

//
// Ideally, the ConsoleNotification should become the mechanism by which the entire log of the
// dbuild compilation is printed on-screen: since builds can (will) work in parallel, the logs
// should be captured separately and printed at the end, one per project, via this mechanism
// rather than being mixed together while work progresses.
//
// Note: when implementing send(), only use templ.long, templ.short, and templ.summary (and templ.id for diagnostics only).
// Do not add any text to them, before or after: the entire text must be definable using the templates only.
class ConsoleNotificationContext(log: Logger) extends NotificationContext[ConsoleNotification] {
  val defaultOptions = ConsoleNotification()
  def mergeOptions(over: ConsoleNotification, under: ConsoleNotification) = defaultOptions
  override def before() = {
    log.info("--== Console Notifications ==--")
  }
  override def after() = {
    log.info("--== Done Console Notifications ==--")
  }
  def send(n: ConsoleNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    templ.long.split("\n").foreach(log.info(_))
    None
  }
}

class EmailNotificationContext(log: Logger) extends NotificationContext[EmailNotification] {
  val defaultOptions = EmailNotification()
  def mergeOptions(over: EmailNotification, under: EmailNotification) = {
    val newTO = if (over.to != defaultOptions.to) over.to else under.to
    val newCC = if (over.cc != defaultOptions.cc) over.cc else under.cc
    val newBCC = if (over.bcc != defaultOptions.bcc) over.bcc else under.bcc
    val newSMTP = if (over.smtp != defaultOptions.smtp) over.smtp else under.smtp
    val newFROM = if (over.from != defaultOptions.from) over.from else under.from
    EmailNotification(to = newTO, cc = newCC, bcc = newBCC, smtp = newSMTP, from = newFROM)
  }
  override def before() = {
    log.info("--== Sending Email ==--")
  }
  override def after() = {
    log.info("--== Done Sending Email ==--")
  }
  def send(n: EmailNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    import n._
    log.info("Sending outcome of project " + outcome.project + " to: " + ((to ++ cc ++ bcc).distinct.mkString(", ")))
    val props = new Properties()
    props.put("mail.smtp.host", smtp.server)

    smtp.encryption match {
      case "ssl" =>
        if (!smtp.checkCertificate)
          props.put("mail.smtp.ssl.checkserveridentity", "false");
        props.put("mail.smtp.ssl.trust", "*")
        props.put("mail.smtp.ssl.enable", "true")
        props.put("mail.smtp.port", "465")
      case "starttls" =>
        if (!smtp.checkCertificate)
          props.put("mail.smtp.starttls.checkserveridentity", "false");
        props.put("mail.smtp.starttls.enable", "true")
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.port", "25")
      case "submission" =>
        if (!smtp.checkCertificate)
          props.put("mail.smtp.starttls.checkserveridentity", "false");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.starttls.enable", "true")
        props.put("mail.smtp.port", "587")
      case "none" =>
        props.put("mail.smtp.port", "25")
      case x => sys.error("Unknown authentication scheme: " + x)
    }
    if (!smtp.credentials.isEmpty)
      props.put("mail.smtp.auth", "true")

    try {
      val creds = smtp.credentials map { credFile =>
        val c = loadCreds(credFile)
        if (c.host != smtp.server)
          sys.error("The credentials file " + credFile + " does not contain information for host " + smtp.server)
        c
      }
      val session = Session.getInstance(props, creds match {
        case None => null
        case Some(c) =>
          new javax.mail.Authenticator() {
            protected override def getPasswordAuthentication(): javax.mail.PasswordAuthentication = {
              return new PasswordAuthentication(c.user, c.pass)
            }
          }
      })
      // session.setDebug(true)
      val msg = new MimeMessage(session)
      msg.setFrom(new InternetAddress({
        val userName = System.getProperty("user.name")
        from match {
          case Some(address) => address
          case None =>
            val host = try {
              java.net.InetAddress.getLocalHost().getHostName()
            } catch {
              // getLocalHost() will throw an exception if the current hostname is set but unrecognized
              // by the local dns server. As a last resort, we fall back to "localhost".
              case e => "localhost"
            }
            "dbuild at " + host + " <" + userName + "@" + host + ">"
        }
      }, /*strict checking*/ true): Address)
      def setData(field: RecipientType, data: Seq[String]) =
        msg.setRecipients(field,
          data.map(new InternetAddress(_, true): Address).toArray)
      setData(TO, to)
      setData(CC, cc)
      setData(BCC, bcc)
      msg.setSubject(templ.short)
      msg.setSentDate(new Date())
      msg.setText(templ.long + "\n")
      // message is ready. Now, the delivery.
      Transport.send(msg)
    } catch {
      case mex: MessagingException =>
        log.error("ERROR SENDING to " + n.to.mkString(", ") + " the outcome of project " + outcome.project + " using template " + templ.id)
        throw mex
    }
  }
}

class Notifications(conf: DBuildConfiguration, confName: String, log: Logger) extends OptionTask(log) {
  def id = "Notifications"
  val consoleCtx = new ConsoleNotificationContext(log)
  val emailCtx = new EmailNotificationContext(log)
  val allContexts = Map("console" -> consoleCtx, "email" -> emailCtx)
  def definedNotifications = conf.options.notifications.send
  val usedNotificationKindIDs = definedNotifications.map { _.kind }.distinct
  val defaultsMap = conf.options.notifications.default.map { d => (d.kind, d) }.toMap
  val defDef = Notification(send = None) // defaults of defaults

  def beforeBuild() = {
    val definedDefaults = conf.options.notifications.default
    val defaultsKindIDs = definedDefaults.map { _.kind }.distinct
    val unknown = (usedNotificationKindIDs ++ defaultsKindIDs).toSet -- allContexts.keySet
    if (unknown.nonEmpty) {
      sys.error(unknown.mkString("These notification kinds are unknown: ", ",", ""))
    }
    definedDefaults.groupBy(_.kind).foreach { kd =>
      if (kd._2.length > 1) {
        sys.error("There can only be one default record for the kind: " + kd._1)
      }
    }
    (conf.options.notifications.send ++ conf.options.notifications.default) foreach { n =>
      // just a sanity check on the project list (we don't use the result)
      val _ = n.flattenAndCheckProjectList(conf.build.projects.map { _.name }.toSet)
    }
  }

  def afterBuild(repBuild: Option[RepeatableDistributedBuild], rootOutcome: BuildOutcome) = {
    runRememberingExceptions(false, usedNotificationKindIDs) { kind =>
      allContexts(kind).before
      sendNotifications(kind, rootOutcome)
      allContexts(kind).after
    }
  }

  def sendNotifications(kind: String, rootOutcome: BuildOutcome) = {
    val outcomes = rootOutcome.outcomes // children of the dbuild root
    val userDefinedTemplates = conf.options.notifications.templates
    val defnOpt = defaultsMap.get(kind) // defaults from the default records

    // scan the kinds for which at least one notification exists
    runRememberingExceptions(false, definedNotifications.filter(kind == _.kind)) { n =>
      // Expansion from defaults; we do it manually. We must consider
      // both the notification record, as well as the internals of the
      // 'send' options; however, the latter are kind-specific, so we
      // ask the corresponding NotificationContext to do it on our behalf.
      // There are three stages: the initial standard values (defined in
      // the source code), which appear if the fields are not specified
      // in the JSON file. Then there are the notification defaults,
      // specified in conf.options.notifications.defaults, and finally
      // the topmost user record from conf.options.notifications.send.
      // The last two have the first as their common default.
      // The three have to be superimposed, and merged.
      val combined = defnOpt match {
        case None => n // no explicit default, pick n as-is
        case Some(defn) =>
          val newWhen = if (n.when != defDef.when) n.when else defn.when
          val newTempl = if (n.template != defDef.template) n.template else defn.template
          val newProjects = if (n.projects != defDef.projects) n.projects else defn.projects
          val newSend = allContexts(kind).mergeOptionsK(n.send, defn.send)
          Notification(kind = kind, send = Some(newSend), when = newWhen, template = newTempl, projects = newProjects)
      }

      def processOneNotification(n: Notification, outcome: BuildOutcome) = {
        // we append the standard templates at the end, so that the user can override them
        val resolvedTempl = n.resolveTemplate(userDefinedTemplates ++ standardTemplates)
        if (outcome.whenIDs.intersect(n.when).nonEmpty) {
          val formatter = new TemplateFormatter(resolvedTempl, outcome, confName)
          allContexts(n.kind).notify(n.send, formatter, outcome)
        }
      }
      // For notifications we do things a bit differently than in
      // deploy. For deploy, we need to obtain a flattened list in
      // order to retrieve the artifacts, and the root has no artifacts
      // of its own. But, for notifications, there exists a report
      // for the root that is distinct from those of the children.
      // So we take the list literally: "." is really the report for
      // the root (no expansion).
      runRememberingExceptions(true, n.projects) { p =>
        val projectOutcomes = (rootOutcome +: outcomes).filter(_.project == p.name)
        if (projectOutcomes.isEmpty)
          sys.error("Internal error: no outcome detected for project " + p.name + ". Please report.")
        if (projectOutcomes.length > 1)
          sys.error("Internal error: multiple outcomes detected for project " + p.name + ". Please report.")
        processOneNotification(combined, projectOutcomes.head)
      }
    }
  }

  val standardTemplates = Seq(
    NotificationTemplate("console",
      "${dbuild.template-vars.padded-project-description}: ${dbuild.template-vars.status}",
      None,
      Some("""---==  Execution Report ==---
Report from the dbuild run for ${dbuild.template-vars.project-description}:
${dbuild.template-vars.subprojects-report}>>> ${dbuild.template-vars.padded-project-description}: ${dbuild.template-vars.status}
---==  End Execution Report ==---""")),
    NotificationTemplate("email",
      "[dbuild] [${JOB_NAME}] ${dbuild.template-vars.project-description}: ${dbuild.template-vars.status}",
      None,
      Some("""This is a test report for ${dbuild.template-vars.project-description} in the dbuild configuration "${dbuild.template-vars.config-name}"
running under the Jenkins job "${JOB_NAME}" on ${NODE_NAME}.

${dbuild.template-vars.subprojects-report}
** The current status of ${dbuild.template-vars.project-description} is:
${dbuild.template-vars.status}


A more detailed report of this dbuild run is available at:
${BUILD_URL}console
""")))
}
