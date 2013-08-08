package distributed.build

import distributed.project.model._
import distributed.logging.Logger
import org.eclipse.core.runtime.SubProgressMonitor
import java.util.Properties
import java.util.Date
import javax.mail._
import internet._

// Note: when implementing send(), only use templ.long, templ.short, and templ.summary (and templ.id for diagnostics only).
// Do not add any text to them, before or after: the entire text must be definable using the templates only.

class ConsoleNotificationContext(log: Logger) extends NotificationContext[ConsoleNotification] {
  override def before() = {
    log.info("--== Console Notifications ==--")
  }
  override def after() = {
    log.info("--== Done Console Notifications ==--")
  }
  def defaultOptions = ConsoleNotification()
  def send(n: ConsoleNotification, templ: TemplateFormatter, outcome: BuildOutcome) = {
    templ.long.split("\n").foreach(log.info(_))
    None
  }
}

class EmailNotificationContext(log: Logger) extends NotificationContext[EmailNotification] {
  def defaultOptions = EmailNotification()
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
    log.info("Sending to " + n.to + " the outcome of project " + outcome.project + " using template " + templ.id)
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
  val allContexts = Map("console" -> new ConsoleNotificationContext(log),
    "email" -> new EmailNotificationContext(log))
  def definedNotifications = conf.options.notifications.send
  val usedNotificationKindIds = definedNotifications.map { _.kind }.distinct

  def beforeBuild() = {
    // just a sanity check on the project list (we don't use the result)
    val unknown = usedNotificationKindIds.toSet -- allContexts.keySet
    if (unknown.nonEmpty) {
      sys.error(unknown.mkString("These notification kinds are unknown: ", ",", ""))
    }
    conf.options.notifications.send foreach { n =>
      val _ = n.flattenAndCheckProjectList(conf.build.projects.map { _.name }.toSet)
    }
  }
  def afterBuild(repBuild: RepeatableDistributedBuild, rootOutcome: BuildOutcome) = {
    usedNotificationKindIds foreach { kind =>
      allContexts(kind).before
      sendNotifications(kind, rootOutcome)
      allContexts(kind).after
    }
  }

  def sendNotifications(kind: String, rootOutcome: BuildOutcome) = {
    val outcomes = rootOutcome.outcomes // children of the dbuild root
    val definedTemplates = conf.options.notifications.templates
    definedNotifications.filter(kind == _.kind).foreach { n =>
      // For notifications we do things a bit differently than for
      // deploy. For deploy, we need to obtain a flattened list in
      // order to retrieve the artifacts, and the root has no artifacts
      // of its own. But, for notifications, there exists a report
      // for the root that is distinct from those of the children.
      // So we take the list literally: "." is really the report for
      // the root (no expansion).
      def processOneNotification(n: Notification, outcome: BuildOutcome) = {
        val resolvedTempl = n.resolveTemplate(outcome, definedTemplates)
        if (outcome.whenIDs.intersect(n.when).nonEmpty) {
          val formatter = new TemplateFormatter(resolvedTempl, outcome)
          allContexts(n.kind).notify(n.send, formatter, outcome) map (log.warn(_))
        }
      }
      n.projects foreach { p =>
        val projectOutcomes = (rootOutcome +: outcomes).filter(_.project == p.name)
        if (projectOutcomes.isEmpty)
          sys.error("Internal error: no outcome detected for project " + p.name + ". Please report.")
        if (projectOutcomes.length > 1)
          sys.error("Internal error: multiple outcomes detected for project " + p.name + ". Please report.")
        processOneNotification(n, projectOutcomes.head)
      }
    }
  }
}
