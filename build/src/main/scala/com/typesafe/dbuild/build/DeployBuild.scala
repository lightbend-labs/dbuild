package com.typesafe.dbuild.build

import sbt._
import com.typesafe.dbuild.adapter.Adapter
import Adapter.IO
import Adapter.syntaxio._
import Adapter.allPaths
import com.typesafe.dbuild.model._
import com.typesafe.dbuild.repo.core._
import java.io.File
import java.net.URI
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.PutObjectRequest
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.ClientConfiguration
import com.amazonaws.Protocol
import dispatch.classic.{ Logger => _, _ }
import com.jsuereth.pgp.{ PGP, SecretKey }
import org.bouncycastle.openpgp.{ PGPSecretKeyRingCollection, PGPSecretKeyRing }
import org.omg.PortableInterceptor.SUCCESSFUL
import com.typesafe.dbuild.logging.Logger.prepareLogMsg
import com.typesafe.dbuild.logging.Logger
import com.typesafe.dbuild.deploy.Creds.loadCreds
import com.jcraft.jsch.{ IO => sshIO, _ }
import java.util.Date
import com.jcraft.jsch.ChannelSftp
import com.typesafe.dbuild.deploy.Deploy

class DeployBuild(options: GeneralOptions, log: Logger) extends OptionTask(log) {
  import Deploy.isNotChecksum
  def id = "Deploy"
  def beforeBuild(projectNames: Seq[String]) = {
    IO.withTemporaryDirectory { indexDir =>
      options.deploy foreach { d =>
        // just a sanity check on the project list (we don't use the result)
        val _ = d.projects.flattenAndCheckProjectList(projectNames.toSet)
        d.index foreach { indexOptions =>
          // sanity check, in case the supplied file name is something silly like "../xyz" or "/xyz/..."
          val indexFile = new File(indexDir, indexOptions.filename).getCanonicalFile
          if (!(indexFile.getCanonicalPath().startsWith(indexDir.getCanonicalPath())))
            sys.error("The specified file name \"" + indexOptions.filename + "\" is illegal, as it refers to a location outside the target URI")
        }
      }
    }
    checkDeployFullBuild(options.deploy)
  }
  def afterBuild(optRepBuild: Option[RepeatableDBuildConfig], outcome: BuildOutcome) = {
    def dontRun() = log.error("*** Deploy cannot run: build did not complete.")
    // we do not run deploy if we reached time out, or if we never arrived past extraction (or both)
    if (outcome.isInstanceOf[TimedOut]) dontRun() else
      optRepBuild match {
        case None => dontRun
        case Some(repBuild) => deployFullBuild(repBuild, outcome)
      }
  }

  // first, a proper sanity check
  def checkDeployFullBuild(optionsSeq: Seq[DeployOptions]) = {
    optionsSeq foreach { options =>
      val uri = new _root_.java.net.URI(options.uri)
      uri.getScheme match {
        case "file" | "null" =>
          if (options.credentials != None) log.warn("Credentials will be ignored while deploying to " + uri)
        case "ssh" | "http" | "https" | "s3" =>
          options.credentials match {
            case None => sys.error("Credentials are required when deploying to " + uri)
            case Some(credsFile) =>
              if (loadCreds(credsFile).host != uri.getHost)
                sys.error("The credentials file " + credsFile + " does not contain information for host " + uri.getHost)
          }
        case "bintray" =>
          options.credentials match {
            case None => sys.error("Credentials are required when deploying to " + uri)
            case Some(credsFile) =>
              if (loadCreds(credsFile).host != "api.bintray.com")
                sys.error("The credentials file " + credsFile + " does not contain information for host api.bintray.com")
          }
        case scheme => sys.error("Unknown scheme in deploy: " + scheme)
      }
    }
  }

  /**
   * The semantics of selection is:
   * - For each node, the set of artifacts available for deploy is: the set of artifacts of all the
   *   successful children, plus its own if successful.
   * - The root build, denoted by ".", has no artifacts of its own.
   * - This rule also applies applies to nested hierarchical build systems, if they are in turn recursive.
   */
  def deployFullBuild(build: RepeatableDBuildConfig, outcome: BuildOutcome) = {
    val projectOutcomes = outcome.outcomes.map(o => (o.project, o)).toMap
    // This does not contain the root ".", but we know that the root has no artifacts to be published of its own,
    // therefore the set for "." is the union of those of the children, regardless if "." failed or not.
    val optionsSeq = options.deploy
    if (optionsSeq.nonEmpty) {
      log.info("--== Deploying Artifacts  ==--")
      runRememberingExceptions(true, optionsSeq) { options =>
        log info ("Processing: " + options.uri)
        // we need to retrieve the artifacts from the repository
        // for build.uuid. We stage them in a temporary directory first,
        // to make sure all is in order
        val moduleInfos = try IO.withTemporaryDirectory { dir =>
          val (good, goodArts, goodModInfos, bad) = rematerialize(options.projects, outcome, build, dir, "deploy",
            msgGood = "Deploying: ",
            msgBad = "Cannot deploy: ",
            partialOK = true, log)

          if (good.nonEmpty) try {
            // Are we signing? If so, proceed
            options.sign foreach { signOptions =>
              val secretRingFile = signOptions.secretRing map { new File(_) } getOrElse (GlobalDirs.userhome / ".gnupg" / "secring.gpg")
              PGP.init
              val passPhrase = {
                val passPhraseFile = io.Source.fromFile(signOptions.passphrase)
                val p = passPhraseFile.getLines.next
                passPhraseFile.close()
                p
              }
              val secretKey = signOptions.id match {
                case Some(keyID) =>
                  log.info("Signing with key: " + keyID)
                  new PGPSecretKeyRingCollection(new java.io.FileInputStream(secretRingFile)).getSecretKey(new java.math.BigInteger(keyID, 16).longValue)
                case None =>
                  log.info("Signing with default key...")
                  new PGPSecretKeyRing(new java.io.FileInputStream(secretRingFile)).getSecretKey
              }
              if (secretKey == null) sys.error("The key was not found in the pgp keyring.")
              (allPaths(dir)).get.filter(f => !f.isDirectory && isNotChecksum(f.getName)) foreach { f =>
                SecretKey(secretKey).sign(f, new File(f.getAbsolutePath() + ".asc"), passPhrase.toArray)
              }
            }
            // dir is staged; time to deploy
            Deploy.deploy(target = options, dir, log)
          } catch {
            case e: NumberFormatException =>
              log.error("***ERROR*** Not a valid hexadecimal value: " + options.sign.get.id.get)
              log.error("***ERROR*** Will not deploy.")
              throw e
          }
          goodModInfos
        } catch {
          case e =>
            log.error("***ERROR*** Encountered an error while deploying to " + options.uri)
            throw e
        }

        // We now need to prepare an index file, if requested
        options.index foreach { indexOptions =>
          try IO.withTemporaryDirectory { indexDir =>
            val indexFile = new File(indexDir, indexOptions.filename).getCanonicalFile
            val index = com.typesafe.dbuild.manifest.Index(moduleInfos.toSeq)
            IO.write(indexFile, Utils.writeValue(index))
            Deploy.deploy(target = indexOptions, indexDir, log)
          }
          catch {
            case e =>
              log.error("***ERROR*** Encountered an error while generating or deploying the index file to " + indexOptions.uri)
              throw e
          }
        }

      }
      log.info("--== End Deploying Artifacts ==--")
    }
  }
}
