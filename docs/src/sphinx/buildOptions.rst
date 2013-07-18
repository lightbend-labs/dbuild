Global build options
====================

.. _section-build-options:

The ``build-options`` section
-----------------------------

Some additional options can affect the global operation of dbuild during the building stage;
these options have an effect on all projects. They are specified in the "build-options"
section, which is entirely optional and lives at the same level as the "deploy" and
"projects" sections. Its structure is currently:

.. code-block:: javascript

   "build-options":{
    "cross-version"  : <cross-version-level>
   }

The options are:

cross-version
  Controls the way in which the various sbt-based projects will use the cross-versioning
  facility, appending a version suffix to the artifact id. This is of importance in two
  main cases.

  If a dbuild configuration file has a project that has required Scala-based dependency,
  and the dependency is not included, sbt will try to resolve it against the standard
  Maven repositories. During development and testing, therefore, it is useful to set the
  cross-version level to a more strict level, so that sbt will only look for versions
  that have been compiled with the exact same version of Scala we are interested in, and
  fail otherwise.

  Conversely, when releasing, we want to set the version numbers of the various projects
  (using the "set-version" option), and leave the cross versioning level to whatever each
  project specifies, so that the published artifacts follow the same scheme that
  each project would normally use.

  This option can therefore control how strict or lax the cross-version suffix should be
  during building, and how sbt will look for alternatives in case of dependencies that
  are not part of the current configuration file.

  The available options are:

  "disabled"
    This is the default. All cross-version suffixes will be disabled, and each project
    will be published with just a dbuild-specific version suffix (unless "set-version" is used).

  "standard"
    Each project will compile with its own natural cross-version suffix.
    This setting is necessary when releasing, usually in conjunction with "set-version",
    in order to make sure that the standard suffix is used while publishing.


  "binaryFull"
    The sbt projects that publish artifacts using the "Binary" cross-version setting are
    forced to use the full Scala version string in place of a shortened version ("_2.10"). The projects
    that do not use the Binary cross-version setting will be unaffected. This options works
    by forcing sbt's scalaBinaryVersion setting to scalaVersion.

  "full"
    Every sbt-based project is changed so that the full Scala version string is used as a cross-
    version suffix, including those projects that would normally have cross versioning disabled.
 
  "binary"
    As above, except the sbt projects are changed to use sbt's "Binary" setting. That includes
    even the projects that would normally have cross-version disabled or set to full.
    The ScalaBinaryVersion of all projects is reset to the default sbt scala binary
    compatibility model.

*Next:* :doc:`repositories`.
