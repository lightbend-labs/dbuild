package distributed.project.model

/**
 * The outcome of a build. Override toString only for internal diagnostics, and define the
 * other methods instead for the user-facing messages.
 */
sealed abstract class BuildOutcome {
  /** The default notification template; see Notification for further details. */
  def defaultTemplate(): NotificationTemplate
  /** The default long response, with a subject line (usually shorter than shortTemplate (about 50-60 characters), and a body of arbitrary length. */
  def whenIDs:Seq[String] = Seq("always")
  def project:String
}
sealed abstract class BuildGood extends BuildOutcome {
  override def whenIDs:Seq[String] = super.whenIDs :+ "good"
}
sealed abstract class BuildBad extends BuildOutcome {
  override def whenIDs:Seq[String] = super.whenIDs :+ "bad"
}

/** We rebuilt the project, and all was ok. */
case class BuildSuccess(project:String, artsOut: BuildArtifactsOut) extends BuildOutcome {
  override def toString() = "BuildSuccess(<arts>)"
  def defaultTemplate() = NotificationTemplate("","SUCCESS (project rebuilt ok)")
  override def whenIDs:Seq[String] = super.whenIDs :+ "success"
}

/** It was not necessary to re-run this build, as nothing changed. */
case class BuildCached(project:String, artsOut: BuildArtifactsOut) extends BuildOutcome {
  override def toString() = "BuildCached(<arts>)"
  def defaultTemplate() = NotificationTemplate("","SUCCESS (did not need to be rebuilt)")
  override def whenIDs:Seq[String] = super.whenIDs :+ "cached"
}

/** This build was attempted, but an error condition occurred while executing it. */
case class BuildFailed(project:String, cause: String) extends BuildBad {
  def defaultTemplate() = NotificationTemplate("","**** FAILED **** --- compilation failed: " + cause)
  override def whenIDs:Seq[String] = super.whenIDs :+ "failed"
}

/** One or more of this project dependencies are broken, therefore we could not build. */
case class BuildBrokenDependency(project:String, outcomes: Seq[BuildOutcome]) extends BuildBad {
  def defaultTemplate() = NotificationTemplate("","DID NOT RUN (stuck on broken dependency: " +
    (outcomes.filter { case _: BuildFailed => true; case _ => false }).map { _.project }.mkString(",") + ")")
  override def whenIDs:Seq[String] = super.whenIDs :+ "depBroken"
}
