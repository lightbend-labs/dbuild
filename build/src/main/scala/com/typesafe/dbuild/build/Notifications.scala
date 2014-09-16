package com.typesafe.dbuild.build

import com.typesafe.dbuild.model._
import com.typesafe.dbuild.logging.Logger
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._
import Message.RecipientType
import RecipientType._
import com.typesafe.dbuild.deploy.Creds.loadCreds
import com.typesafe.dbuild.model.TemplateFormatter
import dispatch.classic.{ Logger => _, _ }

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

case class FlowdockJSON(
  content: String,
  external_user_name: String,
  tags: Seq[String])
case class FlowdockResponse(
  message: Option[String])
class FlowdockNotificationContext(log: Logger) extends NotificationContext[FlowdockNotification] {
  val defaultOptions = FlowdockNotification(token = "", from = "")
  def mergeOptions(over: FlowdockNotification, under: FlowdockNotification) = {
    val newToken = if (over.token != defaultOptions.token) over.token else under.token
    val newDetail = if (over.detail != defaultOptions.detail) over.detail else under.detail
    val newFrom = if (over.from != defaultOptions.from) over.from else under.from
    val newTags = if (over.tags != defaultOptions.tags) over.tags else under.tags
    FlowdockNotification(token = newToken, detail = newDetail, from = newFrom, tags = newTags)
  }
  override def before() = {
    log.info("--== Flowdock Notifications ==--")
  }
  override def after() = {
    log.info("--== Done Flowdock Notifications ==--")
  }
  def send(n: FlowdockNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    def diag = " to Flowdock the outcome of project " + outcome.project + " using template " + templ.id
    try {
      def checkField(s: String, n: String) =
        if (s == "") throw new RuntimeException("Field \"" + n + "\" MISSING (must be specified), when sending" + diag)
      checkField(n.token, "token")
      checkField(n.from, "from")
      val token = (scala.io.Source.fromFile(n.token)).getLines.next
      val msg = n.detail match {
        case "summary" => templ.summary
        case "short" => templ.short
        case "long" => templ.long
        case s => throw new RuntimeException("The Flowdock detail level must be one of: summary, short, long. (found: \"" + s + "\"")
      }
      val descriptor = new FlowdockJSON(content = msg, external_user_name = n.from, tags = n.tags)
      val json = Utils.writeValue(descriptor)
      val uri = "https://api.flowdock.com/v1/messages/chat/" + token
      val sender = url(uri.toString).POST << (json, "application/json")
      val response = (new Http with NoLogging)(sender >- { str =>
        Utils.readSomePath[FlowdockResponse](str)
      })
      if (response != None && response.get.message != null && response.get.message != None) {
        val out = response.get.message.get
        log.error("While sending" + diag)
        log.error("received the response: " + out)
      }
      None
    } catch {
      case mex: MessagingException =>
        log.error("ERROR SENDING" + diag)
        throw mex
    }
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

// if doNotNotify is true, then ignore the selected notification options, and only print on the console (default)
class Notifications(defaultNotifications: Boolean, options: GeneralOptions, confName: String, log: Logger) extends OptionTask(log) {
  def id = "Notifications"
  val notificationOptions = if (defaultNotifications) NotificationOptions() else options.notifications

  val consoleCtx = new ConsoleNotificationContext(log)
  val flowdockCtx = new FlowdockNotificationContext(log)
  val emailCtx = new EmailNotificationContext(log)
  val allContexts = Map("console" -> consoleCtx, "flowdock" -> flowdockCtx, "email" -> emailCtx)
  def definedNotifications = notificationOptions.send
  val usedNotificationKindIDs = definedNotifications.map { _.kind }.distinct
  val defaultsMap = notificationOptions.default.map { d => (d.kind, d) }.toMap
  val defDef = Notification(send = None) // defaults of defaults

  def beforeBuild(projectNames: Seq[String]) = {
    val definedDefaults = notificationOptions.default
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
    (notificationOptions.send ++ notificationOptions.default) foreach { n =>
      // just a sanity check on the project list (we don't use the result)
      // flattenAndCheckProjectList() will check that the listed project names actually exist
      val _ = n.projects.flattenAndCheckProjectList(projectNames.toSet)
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
    val userDefinedTemplates = notificationOptions.templates
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
        // due to the possible explicit target selection in the dbuild invocation,
        // it may be that certain projects are listed in repeatableBuilds, but do not
        // have a corresponding outcome. The check using flattenAndCheckProjectList(),
        // above, should have already verified that all listed names exist, therefore
        // if an outcome is missing do not stop, but just issue a notice.
        if (projectOutcomes.isEmpty)
          log.info("No outcome for project " + p.name + " (skipped)")
        else {
          if (projectOutcomes.length > 1)
            sys.error("Internal error: multiple outcomes detected for project " + p.name + ". Please report.")
          processOneNotification(combined, projectOutcomes.head)
        }
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
    NotificationTemplate("flowdock",
      "${JOB_NAME} on ${NODE_NAME}: ${dbuild.template-vars.status}",
      Some("""${JOB_NAME} on ${NODE_NAME}: ${dbuild.template-vars.status}
Info at: ${BUILD_URL}console"""),
      Some("""${JOB_NAME} on ${NODE_NAME}: ${dbuild.template-vars.status}
${dbuild.template-vars.subprojects-report-tabs}Info at: ${BUILD_URL}console""")),
    NotificationTemplate("email",
      "[${JOB_NAME}] ${dbuild.template-vars.project-description}: ${dbuild.template-vars.status}",
      None,
      Some("""This is a report for ${dbuild.template-vars.project-description} in the configuration "${dbuild.template-vars.config-name}"
running under the Jenkins job "${JOB_NAME}" on ${NODE_NAME}.

${dbuild.template-vars.subprojects-report}>>> ${dbuild.template-vars.padded-project-description}: ${dbuild.template-vars.status}


A more detailed report is available at:
${BUILD_URL}console
""")))
}
