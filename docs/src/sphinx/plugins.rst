sbt plugins support
===================

.. _section-sbtplugins:

Working with sbt plugins
------------------------

Some of the dbuild "build systems" may need to operate simultaneously on
completely independent sets of artifacts, living in entirely separate
"universes" of files and dependencies. That is the case of the sbt build
definitions, in which the usual compiled files and their dependencies
are completely independent from the space in which the sbt plugins live.
For instance, when using sbt 0.12, the sbt plugins are all compiled
using Scala 2.9, while the main artifacts and their dependencies may
be compiled using Scala 2.10 or 2.11.

dbuild offers a native support for this case, offering a simple
mechanism that can be used to compile, in the same configuration file,
the main artifacts as well as sbt plugins. The mechanism relies on
dbuild's "spaces", and is easily used via a small extension of the
usual build configuration syntax.

Build levels
------------

In order to specify which options apply to the main artifacts and which
to plugins, some options can be defined as arrays of values, rather than
individual values. If an array is used, then the first element will apply
to the regular build, and the second element to the plugins space.

Should it be ever needed, further elements can be even used to specify
further sbt levels: plugins of the plugins build, and so on. You may refer to the
sbt manual for further details on the recursive nature of sbt's build definitions.

In practice, here is an example of a (somewhat convoluted) build definition that
operates on plugins as well:

.. code-block:: text

  build: [{
    space.to: default
    space.from: [ default, sbtplugins ]
    check-missing: [ true, false ]
    cross-version: [ disabled, standard ]
    extraction-version: "2.11.0"
    projects: [
    {
      name: parboiled
      uri: "https://github.com/..."
    }
    {
      name: ...
    } ... ]
  }
  {
    space.to: sbtplugins
    check-missing: [ true, false ]
    cross-version: [ disabled, standard ]
    space.from: [ sbtplugins, sbtplugins ]
    sbt-version:   "0.13.5"
    extraction-version: "2.10.4"
    projects:[
    {
      name:   scala-210
      system: scala
      uri:    "git://github.com/scala/scala.git#2.10.x"
    }
    {
      name: sbt-pgp
      uri: "git://github.com/sbt/sbt-pgp.git"
    } ... ]
  }]

In this example, the build file is divided into two sections, each of which
has certain defaults for the contained projects. The first section publishes
to the space "default", and compiles artifacts using Scala 2.11. The second
section compiles its projects using Scala 2.10, and its results are published
to the separate space "sbtplugins".

Notice how the options "check-missing", "cross-version", and "space.from",
which are usually single values, are now arrays. In each case, the first
element applies to the main definition of each sbt-based project, while
the second element applies to its plugins. Therefore, in the case of
parboiled, its sbt plugins will be fetched from the space "sbtplugins",
and therefore it will use the artifacts of the sbt-pgp project listed
below.

Using this mechanism, it is possible to compile in the same configuration
file a number of main community projects, and a number of projects that
generate sbt plugins, making sure that the whole system works together
even though it uses two different Scala versions, and two separate
ecosystems of artifacts.

Worth noting is that, in the example above, the sbtplugins space
is used as a source space for both the main artifacts of plugins,
as well as the source of the plugins used while compiling said
plugins. So, if sbt-pgp is used as a plugin to compile another
plugin, that will also work correctly.

The file above is just an example; as described elsewhere in this
guide, the options "cross-version" and "check-missing" should
normally remain set to "disabled" and "true", for plugins as well,
unless the file is used for releasing.

The settings
------------

The settings that can be extended to control sbt plugins, as well as
the main artifacts, are the following:

``space.from``
  If multiple values are used, the corresponding artifacts
  for the main build, the plugins, and so on will be retrieved from
  the corresponding spaces.
  When a single element is used for "space.from", the remaining
  elements default to the space "" (the empty string), meaning that
  the dependencies will not be rewritten at all (the standard
  behavior, for sbt plugins in a dbuild project).
  Please note that, even though "space.to" is also an array, it does
  not refer to different build levels: it just means that the project
  artifacts are published to all the listed spaces.

``check-missing``
  The default values are "true" for the first element,
  and "false" for the following. If you are planning to build in your
  configuration file also sbt plugins, you will probably want to use
  the setting ``[true,true]``. In that case, you will also need to
  set cross-version accordingly, as described below.

``cross-version``
  The default values are "disabled" for the first
  element, and "standard" for the others. The values after the first
  will be used, if present,
  while sbt compiles its own internal build description structures.
  You will mostly see no direct effect, since the resulting build
  description is not published by sbt. However, you will need to
  set this explicitly to "disabled" or "full" for plugins in order
  to be able to set "check-missing" to true. The relationship
  between cross-version and check-missing is explained in detail
  at :doc:`buildOptions`.

``deps``
  The dependency modifiers (``deps.inject`` and ``deps.ignore``)
  can also be applied to plugins, by specifying as a value for
  ``deps`` an array, in which each element is a record containing
  one or both of ``inject`` and ``ignore``, or an empty record if
  no modification is needed for that level of the build. The
  default value for ``deps`` is an empty sequence (no changes are
  applied to the detected dependencies). This is an advanced
  setting.

``settings`` and ``sbt-settings``
  Additional settings can be inserted into the main project definition,
  as well as into the plugins build definition, and further upper
  levels if needed.

  If you wish to inject settings for plugin definitions, these options
  may be written as an array of arrays of strings; some of the inner
  arrays may also be the empty array```[]```. In place of some of the
  inner arrays, simple strings can also be used as long as at least
  one of the inner elements is an array. For instance, possible
  definitions could be ```[xx,[yy,zz]]```, or ```[[],fff]```,
  where ``[yy,zz]`` and ``fff`` refer to plugins.

  If no inner arrays
  are detected, as for example in ``[aa,bb,cc]``, then the specified
  settings will all be taken to refer to the main build level.


You can use the example above as a starting point in order
to experiment with building sbt plugins in a dbuild configuration file.

*Next:* :doc:`comparison`.
