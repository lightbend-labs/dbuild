Known issues and caveats
========================

dbuild is still in development, and certain known issues or limitations are present; they will be resolved
in further upcoming releases. The main ones are listed here.

Limitations
-----------

- If a dbuild run is prematurely interrupted, and a remote repository is in use, incomplete information
  may be uploaded, preventing further runs from completing. That can be resolved either by purging the
  repository content, or by removing the build configuration from the `meta/build` directory. In the
  unlucky case in which a project upload has been interrupted half-way through, it may be necessary to
  remove the project file from `meta/project` and the corresponding artifacts from the `raw` directory.
  Future versions of dbuild will include more robust repository handling, and consistency checking/fixing
  tools.

- dbuild-setup will modify your running sbt environment by running the equivalent of 'set' commands. These
  will show up in the session list as comments, but the session cannot be saved and restored using the
  `session save` command. In case you do, the dbuild settings will just be ignored.

- The list of resolvers used to build the projects in a dbuild build configuration file cannot be
  configured at this time. It will automatically use common repositories (Typesafe, Maven Central, etc),
  which should be ok for most projects, but libraries hosted on obscure third-party repositories
  cannot (yet) be reached at this time. This limitation will be addressed first thing in the next
  point release.

- Each dbuild build file should currently include Scala as one of the project. That should not be necessary
  in general, but at this time if Scala is not included, and cross-versioning is used in any of the
  other projects, versioning issues may arise.

- Cross-versioning is intentionally disabled in all projects during a dbuild run, and as a result of
  dbuild-setup. This is necessary, as only one specific version of Scala and of the various libraries is
  tested by dbuild.

|

(back to :doc:`the index <index>`)
