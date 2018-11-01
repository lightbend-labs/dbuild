Build options
==============

.. _section-build-options:

The build options defaults
--------------------------

Some options can be specified just once, directly in the build section, and they will act as
default for all of the projects enclosed in the same section. The available options are:

.. code-block:: javascript

   build: {
    cross-version       : <cross-version-level>
    check-missing       : <check-missing-flag>
    rewrite-overrides   : <rewrite-overrides-flag>
    sbt-version         : <sbt-version>
    sbt-commands        : [<command1>,<command2>,...]
    sbt-post-commands   : [<command1>,<command2>,...]
    sbt-settings        : [<setting1>,<setting2>,...]
    sbt-java-options    : [<jopt1>,<jopt2>,...]
    extraction-version  : <compiler-version-string>
    space               : <space-definition>

    projects: [...]
   }

Each of the listed options is optional (the defaults are listed below). Further, each of
them can be again overridden individually in each of the projects. Some examples are below.

cross-version
  This options controls the way in which sbt's cross-version suffixes are used when the
  compiled artifacts are generated. It also, indirectly, affects dbuild's ability to
  detect missing dependencies, as explained in the documentation of "check-missing", below.

  The possible values for the "cross-version" option in an sbt build are:
  
  disabled
    This is the default setting, and it will be used if a "build-options" section is not
    specified. The cross-version suffixes are disabled, and each project is published
    using just plain name, organization, and version.

  standard
    Each project will compile with its own natural cross-version suffix.
    This setting is necessary when releasing, usually in conjunction with "set-version",
    in order to make sure that the standard suffix is used while publishing. It is not
    recommended that this option is selected during the usual testing cycle, as it causes
    the option "check-missing" to become ineffective (see below).

  full
    In this case the full Scala version string is used as a
    cross-version suffix while publishing (even for those projects that would normally
    have cross-version disabled).

  binaryFull
    The sbt projects that publish artifacts using the "Binary" cross-version setting are
    forced to use the full Scala version string in place of a shortened version ("_2.10");
    however, the projects that publish without cross-version will remain unchanged.
    Missing dependent projects will be detected. This option exists mainly for testing,
    and is not intended for regular use.

For some other build systems, notably Assemble, Aether and Scala, the possible values are
"disabled", "full", and "binary", where:

  binary
    The Scala version string will always be truncated to the first two components,
    regardless of suffixes or other conditions. Therefore, "2.11.0-xyz" would result
    in the cross-version suffix "_2.11"/

The Scala build system supports the levels "full", "binary", and "standard", where the
latter will use the value of the property "scala.binary.version" from the file
"version.properties" in the root of the Scala source tree. Note that this property may
be misaligned with the new version number that you may have set using "set-version",
and may lead therefore to incorrect pom files. If unsure, select "binary" for the
Scala build system.

check-missing
  This option can be true or false; the default is true. This option is only effective when
  "cross-version" is set to "disable" or "full".

  Consider the case in which a dbuild configuration file contains a selection of projects.
  Each of the projects will have their own set of dependencies. The ones that are supplied
  by other projects in the same file will be used automatically, but for the ones that
  are not available, sbt will try to resolve those dependencies against an external
  Maven repository. In this case, dbuild has no control over the resolution process:
  different projects may use different versions of the same libraries, or in certain
  cases versions that have been compiled against different Scala versions.

  In order to avoid that case, the option "check-missing" can be used. When enabled, dbuild
  will inspect the dependencies of each project, and will try to detect which Scala-based
  dependencies are missing from the configuration file. If any are found, a message
  will be printed with detailed information.

  This option works by comparing the cross-version suffix of the requested dependencies
  against the cross-version suffix used within each project. Because of this reason,
  the option "check-missing" is only effective when "cross-version" is set to "disable"
  or to "full". Also, in the unlikely case in which a Scala project uses a pure Java
  library, and that Java library uses a Scala library, dbuild will no longer be able
  to detect the missing transitive Scala dependency, which may manifest itself with
  an sbt error message about conflicting cross version suffixes. These errors can
  be solved by explicitly including the transitive dependencies as well
  in the build configuration file. That may require manual inspection of the Ivy
  resolution reports, in order to identify which Scala project is being pulled in
  by the Java project; however, those cases should be fairly rare.

  As previously mentioned, if dbuild is used to issue a release, the option "cross-version"
  will normally be set to "standard" in order to generate artifacts that contain the
  correct cross-version suffix. However, the option "cross-version" should normally
  be omitted during normal use, as the use of "cross-version:standard" will cause
  the missing dependencies check to become ineffective.

rewrite-overrides
  Some build systems may specify, in addition to a list of dependencies, a list
  of "forced" dependency versions. For instance, in sbt there is the
  "dependencyOverrides" setting for this purpose. By default, dbuild will also
  rewrite those overridden dependencies, unless this flag is explicitly set
  to false. It is not normally necessary to specify this option; it is currently
  only supported when building a project using sbt.

sbt-version
  You can optionally specify here the sbt version that should be used to compile
  all the sbt-based projects. If not specified, sbt 0.12.4 will be used.

sbt-commands
  It can be either a single string, or an array of strings, each of which will be used
  as a pre-build sbt command in sbt-based builds. These commands will
  be applied to all the contained projects. Notice that, if the "extra.commands" field
  of a project contains additional commands, they will not replace this default list,
  but they will be appended to it.

sbt-post-commands
  It can be either a single string, or an array of strings, each of which will be used
  as a post-build and post-test sbt command in sbt-based builds. These commands will
  be applied to all the contained projects. Notice that, if the "extra.post-commands"
  field of a project contains additional commands, they will not replace this default
  list, but they will be appended to it.

sbt-settings
  It can be either a single string, or an array of strings, each of which will be used
  as an additional sbt setting in the project. These settings will
  be applied to all the contained projects. Notice that, if the "extra.settings" field
  of a project contains additional settings, they will not replace this default list,
  but they will be appended to it.

sbt-java-options
  Normally, sbt will be invoked using a default list of common java options
  that should be suitable in most cases. In case the list needs to be customized,
  this option can be used to supply the relevant values to all the projects
  in this build section.
  Please note that this option only applies while using the sbt build system.
  The default value of java options used while invoking sbt is:

.. code-block:: text

    ["-XX:+CMSClassUnloadingEnabled",
     "-XX:+DoEscapeAnalysis",
     "-Xms1536m",
     "-Xmx1536m",
     "-Xss2m",
     "-XX:MaxPermSize=640m",
     "-XX:ReservedCodeCacheSize=192m"]

extraction-version
  Specifies the version of the compiler that should be used during dependency
  extraction; please refer to the section :ref:`sbt-options`.

space-definition
  This option specifies the space that will be used to build the contained projects;
  the "spaces" feature will be introduced shortly, in the following section of this guide.
  If unspecified, the space "default" is used.

Organizing defaults
--------------------

This is an example of the way in which common defaults can be defined
for multiple projects:

.. code-block:: text

  build: {
    sbt-version: "0.13.0"
    projects: [
      {
        name: a, ...
      },{
        name: b, ...
      },{
        name: c, ...
      }
    ]
  }

In the example above, the selected sbt version will be applied to all of the projects.
Let's assume that we have a long list of projects, but we want to use a different
value for just one of them. We can write:

.. code-block:: text

  build: {
    sbt-version: "0.13.0"
    projects: [
      {
        name: a, ...
      },{
        name: b, ...
        sbt-version: "0.12.4"
      },{
        name: c, ...
      }
    ]
  }

Here, sbt 0.13.0 will be selected for all of the projects, except for b, which
will use sbt 0.12.4, instead.

If the configuration file is long and complex, and logically structured into
sections, it is also possible to split the list of projects into multiple blocks,
applying different defaults. That is done just by using an array of records,
rather than a single one. For example:

.. code-block:: text

  build: [{
    sbt-version: "0.13.0"
    projects: [
      {
        name: a, ...
      },{
        name: b, ...
        sbt-version: "0.13.1"
      },{
        ...
      }
    ]
  },{
    sbt-version: "0.12.4"
    projects: [
      {
        name: c, ...
      },{
        name: d, ...
      },{
        ...
      }
    ]
  }]

In this case, we used an array for the "build" section. Each of the two lists
of projects can use a different set of defaults, which can again be overridden
inside each project.

Note that for some selected options the general default and the project-specific
value may combine in a slightly different manner. That is currently the case only
for the list of sbt commands: the supplied default and the project-specific value
will be concatenated together, and all of the resulting commands will be used.

|

*Next:* :doc:`spaces`.
