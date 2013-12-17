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
    sbt-version         : <sbt-version>
    extraction-version  : <compiler-version-string>
    use-jgit            : <true-or-false>

    projects: [...]
   }

Each of the listed options is optional (the defaults are listed below). Further, each of
them can be again overridden individually in each of the projects. Some examples are below.

cross-version
  This option controls the way in which the various sbt-based projects will use the cross-version
  facility, which appends a Scala version suffix to the artifact id. Consider the following two cases.

  1. If a dbuild configuration file includes a project that has a Scala-based dependent project,
     but the dependency is not included, sbt may try to resolve the dependency against an external
     Maven repositories, built with a different version of Scala. This is undesirable during
     the development and testing of a new Scala version, as the aim is to compile all of the
     dependencies against the same new version of Scala. Dbuild, in this case, is able to
     enforce the policy that all the dependencies must use the exact same version of scala:
     compilation will fail with an informative message otherwise.

  2. Conversely, when releasing, it is desirable to preserve the same cross-version policy
     that the various projects normally use. In this case, we can ask dbuild not to adjust
     cross-versioning, so that the published artifacts follow their native publishing scheme.

  This option can therefore control how strict or lax the cross-version suffix should be
  during building, and how sbt will look for alternatives in case of dependencies that
  are not part of the current configuration file.

  The available options are:

  disabled
    This is the default setting, and it will be used if a "build-options" section is not
    specified. The cross-version suffixes are disabled, and each project is published
    using just a single version, which contains a dbuild-specific version suffix
    (unless "set-version" is used).
    The library dependencies that refer to Scala projects that are not included in this build
    configuration, and which have "binary" or "full" CrossVersion, will be adjusted;
    as a result, missing dependent projects will be detected.

  standard
    Each project will compile with its own natural cross-version suffix.
    This setting is necessary when releasing, usually in conjunction with "set-version",
    in order to make sure that the standard suffix is used while publishing.

  full
    Similar to "disabled", except the full Scala version string is used as a
    cross-version suffix while publishing (even for those projects that would normally
    have cross-version disabled). Missing dependent projects will be detected.

  binaryFull
    The sbt projects that publish artifacts using the "Binary" cross-version setting are
    forced to use the full Scala version string in place of a shortened version ("_2.10");
    however, the projects that publish without cross-version will remain unchanged.
    Missing dependent projects will be detected. This option exists mainly for testing,
    and is not intended for regular use.

  In practice, you can omit the "build-options" section entirely during normal use, and
  just add the following if you are releasing using "set-version":

.. code-block:: javascript

   build.options:{cross-version:standard}

sbt-version
  You can optionally specify here the sbt version that should be used to compile
  all the sbt-based projects. If not specified, sbt 0.12.4 will be used.

extraction-version
  Specifies the version of the compiler that should be used during dependency
  extraction; please refer to the section :ref:`sbt-options`.

use-jgit
  The default is false: dbuild will normally invoke the regular git command in
  order to resolve a git repository. As an alternative, a jgit-based implementation
  can also be selected by setting this flag to true. Be aware that, due to the
  lack of hard-linking in jgit, more disk space will be necessary in order to
  compile the projects in that case.


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

|

*Next:* :doc:`spaces`.
