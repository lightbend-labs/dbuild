package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import org.eclipse.core.runtime.SubProgressMonitor
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._

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
    val props = new Properties();
    props.put("mail.smtp.host", "my-mail-server")
    val session = Session.getInstance(props, null)
    log.info("Sending to " + n.to + " the outcome of project " + outcome.project + " using template " + templ.id +" from: "+n.from)
    /*
    try {
      val msg = new MimeMessage(session)
      msg.setFrom(new InternetAddress("me@example.com"))
      msg.setRecipients(Message.RecipientType.TO,
        "you@example.com");
      msg.setSubject("JavaMail hello world example")
      msg.setSentDate(new Date())
      msg.setText("Hello, world!\n");
      Transport.send(msg, Array(new InternetAddress("me@example.com"), new InternetAddress("my-password")))
    } catch {
      case mex: MessagingException =>
        log.error("ERROR SENDING to " + n.to + " the outcome of project " + outcome.project + " using template " + templ.id)
        log.error(mex.toString)
    }
    */
    Some("Couldn't send, sorry")
  }
}



class Notifications(conf: DBuildConfiguration, log: Logger) extends OptionTask {
  def id="Notifications"
  val consoleCtx=new ConsoleNotificationContext(log)
  val emailCtx=new EmailNotificationContext(log)
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
    usedNotificationKindIDs foreach { kind =>
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
    definedNotifications.filter(kind == _.kind).foreach { n =>
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
          println("This is: "+n)
          println("default is: "+defn)
          println("default default is: "+defDef)
          val newWhen = if (n.when != defDef.when) n.when else defn.when
          val newTempl = if (n.template != defDef.template) n.template else defn.template
          val newProjects = if (n.projects != defDef.projects) n.projects else defn.projects
          val newSend = allContexts(kind).mergeOptionsK(n.send, defn.send)
          Notification(kind = kind, send = Some(newSend), when = newWhen, template = newTempl, projects = newProjects)
      }

      def processOneNotification(n: Notification, outcome: BuildOutcome) = {
        val resolvedTempl = n.resolveTemplate(outcome, definedTemplates)
        if (outcome.whenIDs.intersect(n.when).nonEmpty) {
          val formatter = new TemplateFormatter(resolvedTempl, outcome)
          allContexts(n.kind).notify(n.send, formatter, outcome) map (log.warn(_))
        }
      }
      // For notifications we do things a bit differently than in
      // deploy. For deploy, we need to obtain a flattened list in
      // order to retrieve the artifacts, and the root has no artifacts
      // of its own. But, for notifications, there exists a report
      // for the root that is distinct from those of the children.
      // So we take the list literally: "." is really the report for
      // the root (no expansion).
      n.projects foreach { p =>
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
