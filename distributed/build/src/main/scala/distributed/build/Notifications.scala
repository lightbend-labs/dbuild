package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import Creds.loadCreds
import org.apache.commons.mail.{ Email, SimpleEmail, DefaultAuthenticator }
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._
import Message.RecipientType
import RecipientType._
import Creds.loadCreds

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
    val newCRED = if (over.smtpCredentials != defaultOptions.smtpCredentials) over.smtpCredentials else under.smtpCredentials
    val newAUTH = if (over.smtpAuth != defaultOptions.smtpAuth) over.smtpAuth else under.smtpAuth
    EmailNotification(to = newTO, cc = newCC, bcc = newBCC, smtp = newSMTP, from = newFROM, smtpAuth = newAUTH, smtpCredentials = newCRED)
  }
  override def before() = {
    log.info("--== Sending Email ==--")
  }
  override def after() = {
    log.info("--== Done Sending Email ==--")
  }
  def send(n: EmailNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    import n._
    try {
      log.info("Sending status of project " + outcome.project + " to: " + ((to ++ cc ++ bcc).distinct.mkString(", ")))
      val email = new SimpleEmail()
      val smtpServer = smtp match {
        case None =>
          log.warn("WARNING: the smtp server is not set. Will try to send to localhost...")
          "localhost"
        case Some(server) => server
      }

      email.setHostName(smtpServer)
      val sendFrom = {
        val userName = System.getProperty("user.name")
        from match {
          case Some(address) => address
          case None =>
            val host = try {
              java.net.InetAddress.getLocalHost().getHostName()
            } catch {
              // getLocalHost() will throw an exception if the current hostname is set but unrecognized
              // by the local dns server. As a last resort, we fall back to "localhost".
              case e =>
                log.warn("WARNING: could not get the local hostname; your DNS settings might be misconfigured.")
                "localhost"
            }
            "dbuild at " + host + " <" + userName + "@" + host + ">"
        }
      }
      email.setFrom(sendFrom)
      email.setSubject(templ.short)
      email.setMsg(templ.long + "\n")
      if (to.nonEmpty) email.addTo(to: _*)
      if (cc.nonEmpty) email.addCc(cc: _*)
      if (bcc.nonEmpty) email.addBcc(bcc: _*)

      def needCreds = {
        if (smtpCredentials.isEmpty) {
          log.error("Please either add \"smtp-credentials\", or set \"smtp-auth\" to none.")
          sys.error("smpt-credentials are needed with authentication \"" + smtpAuth + "\".")
        }
      }
      smtpAuth match {
        case "ssl" =>
          email.setSSLOnConnect(true)
          email.setStartTLSEnabled(false)
          email.setStartTLSRequired(false)
          needCreds
        case "starttls" =>
          email.setSSLOnConnect(false)
          email.setStartTLSEnabled(true)
          email.setStartTLSRequired(true)
          needCreds
        case "none" =>
          email.setSSLOnConnect(false)
          email.setStartTLSEnabled(false)
        case x => sys.error("Unknown authentication scheme: "+x)
      }
      
      smtpCredentials map { credFile =>
        val c = loadCreds(credFile)
        if (c.host != smtpServer)
          sys.error("The credentials file " + credFile + " does not contain information for host " + smtpServer)
        email.setAuthentication(c.user, c.pass)
      }
      email.send()
      None
    } catch {
      case mex =>
        log.error("ERROR SENDING to " + n.to.mkString(", ") + " the outcome of project " + outcome.project)
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
    val definedTemplates = conf.options.notifications.templates
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
        val resolvedTempl = n.resolveTemplate(outcome, definedTemplates)
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
}
