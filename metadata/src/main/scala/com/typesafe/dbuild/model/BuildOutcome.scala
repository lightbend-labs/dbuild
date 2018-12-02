package com.typesafe.dbuild.model

import com.fasterxml.jackson.annotation.JsonProperty
import com.typesafe.dbuild.model.Utils.{ readValue, writeValue }
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
  /** a copy of this outcome, with replaced nested outcomes */
  def withOutcomes(os:Seq[BuildOutcome]):BuildOutcome
}

// this marker trait is only used internally, in order to
// recognize outcomes that are caused by timeout conditions.
// However, this is not a marker exposed to the user (at this time)
trait TimedOut

sealed abstract class BuildGood extends BuildOutcome {
  override def whenIDs: Seq[String] = "good" +: super.whenIDs
  def artsOut: BuildArtifactsOut
}
sealed abstract class BuildBad extends BuildOutcome {
  override def whenIDs: Seq[String] = "bad" +: super.whenIDs
}

/** We rebuilt the project, and all was ok. */
case class BuildSuccess(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  override def toString() = "BuildSuccess(" + project + ",<arts>)"
  def status() = "SUCCESS (project rebuilt ok)"
  override def whenIDs: Seq[String] = "success" +: super.whenIDs
}

/** It was not necessary to re-run this build, as nothing changed. */
case class BuildUnchanged(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  override def toString() = "BuildCached(" + project + ",<arts>)"
  def status() = "SUCCESS (unchanged, not rebuilt)"
  override def whenIDs: Seq[String] = "unchanged" +: super.whenIDs
}

/** It was not necessary to run this build, since no projects were selected. */
case class BuildEmpty(project: String, outcomes: Seq[BuildOutcome], artsOut: BuildArtifactsOut) extends BuildGood {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  override def toString() = "BuildEmpty(" + project + ")"
  def status() = "SUCCESS (empty: no subprojects selected)"
  override def whenIDs: Seq[String] = "empty" +: super.whenIDs
}

/** This build was attempted, but an error condition occurred while executing it. */
case class BuildFailed(project: String, outcomes: Seq[BuildOutcome], cause: String) extends BuildBad {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = "FAILED (" + cause + ")"
  override def whenIDs: Seq[String] = "failed" +: super.whenIDs
}

/** One or more of this project dependencies are broken, therefore we could not build. */
case class BuildBrokenDependency(project: String, outcomes: Seq[BuildOutcome]) extends BuildBad {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = "DID NOT RUN (stuck on broken dependencies: " +
    (outcomes.filter { case _: BuildFailed => true; case _ => false }).map { _.project }.mkString(", ") + ")"
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
 *  set of successful RepeatableDBuildConfigs collected along the way. */
case class ExtractionOK(project: String, outcomes: Seq[BuildOutcome], pces: Seq[ProjectConfigAndExtracted]) extends ExtractionOutcome {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = "EXTRACTION OK, but could not proceed"
  override def whenIDs: Seq[String] = "extraction-ok" +: super.whenIDs
}
/** Something went wrong during extraction (for instance, could not resolve).
 *  We do not bother collecting the RepeatableDBuildConfigs, since we
 *  cannot proceed anyway. */
case class ExtractionFailed(project: String, outcomes: Seq[BuildOutcome], cause: String) extends ExtractionOutcome {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = "EXTRACTION FAILED ("+ cause + ")"
  override def whenIDs: Seq[String] = "extraction-failed" +: super.whenIDs
}

/**
 * We run post-build tasks even if extraction or build failed. If tasks complete successfully,
 * we return the original status, otherwise we return a special combo status.
 */
case class TaskFailed(project: String, outcomes: Seq[BuildOutcome], original:BuildOutcome, taskCause: String) extends BuildBad {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = original.status()+" + TASK FAILED ("+ taskCause + ")"
  override def whenIDs: Seq[String] = ("task-failed" +: (super.whenIDs ++ original.whenIDs)).distinct
}

/**
 * Something unexpected happened. Maybe an internal dbuild failure, or some other anomalous condition that
 * we have been unable to capture. We may be able to capture nested outcomes or not, depending on the
 * circumstances.
 */
case class UnexpectedOutcome(project: String, outcomes: Seq[BuildOutcome], cause: String) extends BuildBad {
  def withOutcomes(os:Seq[BuildOutcome])=copy(outcomes=os)
  def status() = "UNEXPECTED ("+ cause + ")"
  override def whenIDs: Seq[String] = "unexpected" +: super.whenIDs
}
