Using dbuild
============

The build configuration file
----------------------------

The central part of the build tool operation revolves around the build configuration file, which describes
in detail which software projects are involved in the build, which version of each should be used, and many
other aspects of the build process.

The build file is written in a JSON format. The file is parsed using the Typesafe config library,
therefore the syntax of the file can be simplified by using the conventions of that library: double
quotes and commas may be omitted, variable substitution is available, and other facilities are
available. You should check the many options that are available to simplify the syntax of
configuration file; they are all documented on the website of the
`config library <http://github.com/lightbend/config>`_. In addition, dbuild has one special
feature concerning lists of strings: whenever a list of strings is expected, in the form
``x:[a,b,c]``, if you only have one element you can also omit the square brackets altogether,
and just write ``x:a``. The same automatic "single element to array" conversion also applies
to other arrays of configuration elements; the various cases are described later.
Note that the double quotes around strings can be omitted in most cases.

The order of items in the configuration file is never relevant, unless explicitly mentioned
in the documentation. The top level of the configuration file is:

.. code-block:: javascript

   {
    "build"  : <build_section>,
    "options": <options_section>,
    "vars"   : { <substitution_var1, <substitution_var2>, ... }
    "properties" : [ <props-uri-1>, <props-uri-2>, ... ]
   }

build
  This section is required, and contains the main information needed to generate the build.
  It includes the description of the various projects, and other options that control the
  generation of the code. It is described in detail below.

options
  This section is optional. It does not contain information that affect the build, but is used
  to control other aspects of dbuild, in particular options and tasks that should be executed
  after the build. At this time, the section may contain the following:

  .. code-block:: javascript

     {
      "deploy"        : [ <deploy_1>, <deploy_2>, ... ]
      "notifications" : <notifications>
      "compare"       : [ <compare_1>, <compare_2>, ... ]
      "resolvers"     : { label1: resolv1, label2: resolv2, ...}
      "cleanup"       : <cleanup_options>
      "timeouts"      : <timeout_options>
     }

The various options are described in detail in the rest of this guide.

vars
  This section is optional; it may contain a list of variables, in JSON format, whose content
  is simply substituted in the rest of the configuration file using the conventions of the
  Typesafe config library. For example, if you define it as:

  .. code-block:: javascript

    vars: {
     a : "string1"
     b : "string2"
    }


  you can then insert in the rest of the file ``${vars.a}`` and ``${vars.b}``, which will
  be replaced with the specified replacement strings. Sequences, or other arbitrary JSON
  structures, may also be defined and expanded in the same manner.

  All of the standard Java system properties are automatically available under the
  ``vars.sys`` path, for example ``${vars.sys.user.name}`` or
  ``${vars.sys.java.runtime.version}``. The same applies to properties passed via the
  command line. For instance in:

  .. code-block:: text

    $ bin/dbuild -Dx.y=test config.dbuild

  you can refer to the value of the property by using ``${vars.sys.x.y}``, also when
  defining further variables.

  The path ``vars.auto`` contains some special utility variables, computed by dbuild
  before the build is started. At this time, ``${vars.auto.timestamp}`` contains a
  timestamp corresponding to the start of the build, which can be used to construct
  file names or artifact names, for example using the "set-version-suffix" option
  (see below).

.. _properties:

properties
  This optional section may be used to define additional variables, by means of properties
  files. You can specify a single URI (as a string), or an array of URIs (as strings).
  In order to refer to local files, please use the formats ```file:somefile.props``` for
  relative pathnames, or ```file:///absolute/path/somefile.props``` for absolute paths.
  URIs that refer to http/https resources can also be used.

  The variables will be defined using the name of the property, prefixed by "vars."; for
  example, ```scala.binary.version``` can be referred to as ```${vars.scala.binary.version}```.

  Because of the evaluation order, the strings of the URIs of property files may contain
  expansions of both system properties and shell environment variables; however, they
  may not refer to vars defined in the 'vars' section of the same file. Conversely,
  those 'vars' may contain expansions that refer to properties loaded using this
  properties list.

  Properties files that come later in the list have priority. Variables that are
  defined locally, in the 'vars' section, take precedence in any case over all of
  the properties defined in the properties files of this list.


The build section
-----------------

The build section has the following content:

.. code-block:: javascript

   {
    "projects": [ <dbuild_project1>, <dbuild_project2>,...],
    ...defaults...
   }

projects
  The "projects" section is the most important one, and is the only one that is required in a
  dbuild configuration file. If you have no other sections, you can take advantage of the
  extended JSON syntax, and introduce the project section directly by writing:
  ``build.projects: [...]``. The list of projects, enclosed in square brackets, describes
  the various software projects that should be built together by dbuild. 

defaults
  Rather than specifying for each project all of its parameters, some common options can optionally be
  just described once, and they will act as defaults for all the enclosed projects. These options
  are described in more detail on the page :doc:`buildOptions`, which also contains
  some examples.


Each project descriptions has this structure:

.. code-block:: javascript

   {
    "name"               : <project-name>
    "system"             : <build-system>
    "uri"                : <source-repository-uri>
    "set-version"        : <optional-output-version>
    "set-version-suffix" : <optional-output-version-suffix>
    "deps"               : <optional-dependencies-modifiers>
    "cross-version"      : <cross-version-selector>
    "check-missing"      : <check-missing-flag>
    "use-jgit"           : <jgit-selector>
    "extra"              : <optional-extra-build-parameters>
   }

Within a project description, only the name is mandatory; all the rest is optional, although
you will almost certainly also need to specify uri and system. The options, in detail, are:

name
  A string identifying the software project. The name can be arbitrary and it is only used within dbuild,
  although you will want to use something meaningful, like "akka" for Akka, or "scala-arm" for the
  Scala ARM project.

system
  A string that describes the build system used by this software project. Possible values are
  "sbt", "ivy", "aether", and "assemble". A build system called "scala" exists, but it refers to
  an old Ant-based build script that is no longer in use (the Scala conpiler is now build using sbt).
  Additional mechanisms might be added in the future. If unspecified, "sbt" is used.

uri
  A string pointing to the source repository for this project. It can be git-based (if the uri begins
  with ``git://`` or ends with ``.git``), or svn (schemes ``http://``, ``https://``, ``svn://``, only
  if an svn repository is detected).

  A git/svn uri may optionally be followed by a ``'#'`` and either a commit hash, an svn version, or a
  branch name. For example, in:

  .. code-block:: javascript

     "uri":  "git://github.com/scala/scala.git#2.10.x"

  dbuild will download and extract the most recent available version in the specified branch, or the
  exact version or commit in case if specified. If no prefix is added, dbuild will fetch the most recent
  version in git master, or svn head. In order to point to GitHub pull requests, it is possible to
  use a target like ``#pull/<NNN>/head``

  Some other source repository formats are used in special cases: the
  ``ivy:`` scheme is only used together with the Ivy build system (see below), and the ``nil:``
  uri means that no source files are used. This options is normally always specified, but in
  case it should be missing, "nil:" will be used.

  In order to specify private GitHub repositories, which need authentication, you may use
  the ssh scheme, and configure your GitHub account with the necessary keys. The uri will
  then have the form: ``ssh://git@github.com/account/project.git``

set-version
  This component is optional, and normally not used. During compilation, dbuild automatically
  generates a special version string that is used while producing the various artifacts of each
  project. However, in case you need to obtain artifacts with a specific version string, you can
  completely override the default value by specifying a specific version string here. If you are
  planning to use this feature in order to release artifact, then you may need to set the option
  "cross-version" to "standard", as explained in the section :ref:`section-build-options`.

set-version-suffix
  As an alternative to "set-version", this options will change only the version suffix, while
  retaining the main version number that is defined by the project itself. For example, if the
  project defines as version "0.8.1-SNAPSHOT", and set-version-suffix is "test", the resulting
  version will be "0.8.1-test". If the suffix is set to the empty string, the version
  will become just "0.8.1". If both "set-version-suffix" and "set-version" are defined, the
  latter will take over, replacing the version string entirely.

  If the special string "%commit%" (lowercase) is used for "set-version-suffix", the resulting
  suffix will be the string "-R" plus the commit of the project. If you prefer a shortened
  commit string, just append a numeric length; for example, "%commit%10" will only use
  the first ten characters of the commit hash string.

.. warning::

  An all-numeric suffix string may be interpreted by Maven-related tools as referring to a
  "SNAPSHOT" version, which may lead to unexpected results. Please make sure to include at
  least one alphabetic character in your version suffix strings, in order to avoid any
  unintended behavior.

deps
  The optional "deps" section can be used to modify the way in which dbuild rewires certain
  dependencies of this project. This is an advanced option. For instance, it can be used to
  force dbuild to "forget" about some dependencies that it detected during dependency extraction,
  or it can be used to "inject" some dependencies that dbuild was unable to detect (or both).
  The content of this section is:

  .. code-block:: javascript

   {
    "ignore" : [ mod1, mod2, ...]
    "inject" : [ mod1, mod2, ...]
   }


  Both "ignore" and "inject" are optional. The dependencies are specified in the form
  "organization#name".

  *deps.ignore:* The dependencies in the ignore list will not be rewritten by dbuild.
  They are still part of the normal library dependencies of the project, however; they
  will just be resolved as they normally would be, within the project, rather than
  being rewritten to point to some other project compiled by dbuild.

  For example:

  .. code-block:: text

   {
     name:   scala-xml
     system: ivy
     uri:    "ivy:org.scala-lang.modules#scala-xml_2.11.0-M4;1.0-RC3"
     set-version: "1.0-RC3"
     deps.ignore: "org.scala-lang#scala-library"
   }

  This option exists only to address very specific cases in which dependency cycles exist
  that cannot be solved otherwise; however, its use is inherently difficult to control, and
  it should be avoided if at all possible. In particular, excluding libraries from dbuild's
  control may cause library conflicts due to different transitive dependencies, pulled in
  by different projects.

  *deps.inject:* The opposite of the previous option, "inject" adds to the list of
  dependencies, as seen by dbuild, the specified modules. This option can be useful if, for
  whatever reason, dbuild could not detect a dependency. One case would be a transitive
  dependency that crosses a "space" boundary (see the page :doc:`spaces`, later in this
  guide, for further details on using multiple spaces).

  Please note that the options "deps.ignore" and "deps.inject"
  only affects dbuild's view of dependencies; they do not alter the list of
  library dependencies used within the project. If you wish to completely remove
  or add a dependency in an sbt project, you may need to use instead the
  "extra.commands" option, with a line like "set libraryDependencies ..."
  (see the sbt build section in this manual for further details on "extra.commands").

  The options "deps.ignore" and "deps.inject" are an advanced feature, and should
  be used sparingly, if at all.

cross-version
  Controls the cross-versioning of the resulting artifacts. Please refer to the
  description at :doc:`buildOptions` for further details.

check-missing
  When set to true, dbuild will try to detect whether any of the Scala-based dependent
  libraries of this project are not part of the configuration file. Please refer to the
  description at :doc:`buildOptions` for further details.

use-jgit
  It controls whether, for special applications, jgit should be used in place of the
  standard git utility. This option is not normally needed. 

extra
  The "extra" component is optional, as are all of its sub-components; it describes additional
  parameters used while building the project. Its content depends on the build system, as
  detailed in the following sections.

.. _sbt-options:

sbt-specific options
--------------------

In this case the "extra" argument is a record with the following content:

.. code-block:: javascript

   {
    "sbt-version"         : <sbt-version>,
    "projects"            : [ subproj1, subproj2,... ]
    "exclude"             : [ subproj1, subproj2,... ]
    "run-tests"           : <run-tests>
    "test-tasks"          : [ task1, task2,... ]
    "skip-missing-tests"  : <skip-missing-tests>
    "options"             : [ opt1, opt2,... ]
    "commands"            : [ cmd1, cmd2,... ]
    "post-commands"       : [ cmd1, cmd2,... ]
    "settings"            : [ setting1, setting2,... ]
    "extraction-version"  : <compiler-version-string>
   }

All of these fields are optional, and if missing a reasonable default value
will be used (listed below for each option). The meaning of the various
options is:

sbt-version
  A string that specifies the version of sbt that should be used to compile
  this dbuild project. If not specified, the sbt version in use will be the
  one specified in the global build options property "sbt-version" (see
  :doc:`buildOptions`). If that is also missing, the default value "standard"
  will be assumed. In that case, an attempt will be made to autodetect the
  sbt version from the "build.properties" file of the project. Should that
  also be missing, dbuild will ask you to provide a version number.

.. note::
  From dbuild 0.9, the minimum required version of sbt is 0.13.5. That is
  due to an important bug fix that is not present in previous sbt versions.
  In case you need to build a project that requires the semantics of previous
  versions of sbt, the custom versions ``0.12.5-dbuild`` and ``0.13.3-dbuild`` are
  available, which are based on 0.12.4 and 0.13.2, respectively, and include the
  necessary fix. These unsupported sbt versions are available from the following
  repository, which should be added to your list of resolvers or to your
  Artifactory/Nexus proxy:

  ``http://repo.typesafe.com/typesafe/temp-distributed-build-snapshots``

projects
  A sequence of strings (or a single string) that identifies a subset of the sbt
  subprojects that should be built within this dbuild project. For instance, you
  can specify:

  .. code-block:: javascript

     "projects":  ["akka-actor"]

  in order to compile only the "akka-actor" sbt project within Akka. For each
  of the specified subprojects, dbuild will also add recursively all of the
  subprojects that are in the same project and that are required dependencies
  of the specified ones; if the subproject is an sbt aggregate, its components
  will also be added. If the "projects" clause is not present, all of the
  subprojects will be included.

  If the project uses sbt's default projects, the actual subproject name may
  vary over time and take forms like "default-e3c4f7". In order to refer to
  sbt's default subproject, you can use the predefined name `"default-sbt-project"`.

exclude
  Sometimes it may be useful to split a single project into two or more parts.
  This clause can be used to exclude explicitly one or more of the subprojects, which
  can then be compiled in a different project within the same configuration file,
  using a different project name but using the same uri.

run-tests
  Boolean value: if set to false, the project will be built but no tests will be run.
  Normally, each project is built first, then tested; if compilation succeeds but testing
  fails, the dbuild run will abort and no artifacts will be stored into the repository.
  If you set run-tests to false, however, testing for the affected project will be skipped,
  and the artifacts will be published at the end of the compilation stage. This is useful
  in case you would like to use the artifacts of a given project, even though its testing
  stage is currently failing for whatever reason.

test-tasks
  It is possible to customize the list of tasks that should be executed during the
  testing stage. By default, its value is just ``test``, but it can be modified
  in order to take into account different commands or configurations. This setting
  can be either a single string or a list of strings; each element can be just the
  name of a task, like ``test``, or a configuration followed by a colon and a task
  name, like ``it:test``. If an element does not include an explicit configuration,
  the "test" configuration is used for that task.
  Input tasks are also supported; everything that follows the first whitespace will be taken
  as the list of arguments to the input task.

.. note::
  If you use ``scripted`` as a test task, you will need to propagate the list of
  resolvers used by dbuild to the tests: by default, the scripted tests receive
  just the default sbt list of resolvers. You can do that by adding to ``commands``
  the line:

  .. code-block:: text

    "commands" : [ ...,
      "set scriptedLaunchOpts ++= Seq(\"-Dsbt.override.build.repos=true\", (\"-Dsbt.repository.config=\"+(baseDirectory.value.getAbsolutePath())+\"/.dbuild/repositories\"), (\"-Dsbt.ivy.home=\"+(baseDirectory.value.getAbsolutePath())+\"/.dbuild/ivy2\"))"
    ]

skip-missing-tests
  Boolean value, default false. If set to true, any test tasks that are not defined in
  some of the subprojects will be just skipped for those subprojects. If set to false,
  dbuild expects the test tasks to be available in all the subprojects, and will
  stop with an error message if that is not the case.

options
  A sequence of strings; they will be passed as-is as additional JVM options,
  appended to the default ones, while launching the sbt instance that is used
  to build this project.

commands
  A sequence of sbt commands; they will be executed by sbt only after dbuild rearranges
  the project dependencies, but prior to building.
  Note that a default list of commands (as detailed in :doc:`buildOptions`) will not
  be replaced by this option: the default commands will be executed before this list.

  These commands are executed before building, but only after dbuild has adjusted the
  list of dependencies and the other settings in order to ensure that the various
  projects are built on top of each other. They should not be used therefore to
  to modify or append dependencies; you can use instead the option "settings", described
  below. These commands are also not run during extraction.

.. note::
  Prior to dbuild 0.9, commands were executed prior to dependency rewiring. If you
  were using commands like ``set libraryDependency ...``, you will need to move them
  to the "settings" section, instead.

post-commands
  An optional sequence of additional sbt commands. If present, these commands will
  be run after building and testing.

.. note::
  It is possible to run arbitrary shell commands from either ``commands`` or
  ``post-commands``, by using the ``eval`` command of sbt, in conjunction with
  Scala's ``Process`` facility. For example, a valid sbt command is:

  ``eval scala.sys.process.Process(Seq("ls","-l")).lines foreach println``

settings
  A sequence of sbt settings, in the format in which they would normally be specified
  in a ``.sbt`` file. These settings will be appended to the end of all other settings
  in the sbt project definitions, prior to the dbuild's dependency rewiring.
  It has a corresponding default in the option "sbt-settings", which can be specified
  once directly in the build section, as explained in the section :doc:`buildOptions`.
  If both defaults and project-specific settings are specified, they will be concatenated,
  with the latter caming last.

extraction-version
  This value can be used to override the Scala compiler version used during dependency
  extraction. It is optional within each project; it is also possible to specify this
  option for all projects from the global build options (see :doc:`buildOptions`). In
  that case, the corresponding choice in each project, if present, will override the
  global value. For example:

  .. code-block:: text

    build.options.extraction-version: "2.11.0-M5"
    build.projects: [{
      name: "a"
      uri: "..."
      extra.extraction-version: "2.11.0-M4"
     },{
      name: "b"
      uri: "..."
     },{...}]
   
  In this case, Scala version 2.11.0-M5 will be used to determine the library
  dependencies of all projects, except for project "a", for which Scala version
  2.11.0-M4 will be used.

  More in detail, the "extraction-version" option 
  can be either a fixed Scala version string, or the string "standard". In the
  latter case, each project will use the Scala version specified in its own build
  files in order to determine the project's dependencies. If no "extraction-version"
  option is specified anywhere, "standard" is assumed for all projects.

  It is not normally necessary to specify this value explicitly,
  but it may be useful in case the project contains code that adds specific
  library dependencies depending on the Scala version in use, and the default
  Scala compiler used by the project in that specific branch is not compatible
  with the version of Scala that is being tested. For example, if a project
  was developed until recently using Scala 2.10.x, and its master branch still
  uses a Scala 2.10.x compiler, but at the same time there is some code that
  adds specific libraries when using the Scala 2.11.x compilers, then it may
  be useful to specify an "extraction" compiler version that belongs to the 2.11
  family.

  In general, it may be simple and effective to specify the extraction
  version just once, in the global build options, as shown in the example
  above.

.. note::
  Different versions of dbuild support different pre-releases of sbt 1.0.x.
  Versions prior to 0.9.8 support sbt 0.13.x. Version 0.9.8 supports 0.13.x
  and 1.0.0-M6. Version 0.9.9 supports 0.13.x and sbt 1.0.0 final.

Ivy-specific options
--------------------

The Ivy build system works like a regular build mechanism, but rather than compiling
the needed dependency from a source repository, it asks directly a Maven/Ivy repository
for the requested binary code. Although that rather defeats the point of compiling all
code using the same Scala version, it can nonetheless be quite useful in the case in
which only a specific binary is available, for example in case of libraries that are
proprietary and closed-source, or that are currently unmaintained.

The ``uri`` field follows the syntax "ivy:organization#name;revision". For example:

.. code-block:: javascript

  {
    name:   ivytest
    system: ivy
    uri:   "ivy:org.scala-sbt#compiler-interface;0.12.4"
  }

If cross-versions are in use, the Scala version suffix must be explicitly added to the name,
for example: "ivy:org.specs2#specs2_2.10;1.12.3". The "extra" options are the following:

.. code-block:: javascript

   {
    "main-jar"    : <true-or-false>
    "sources"     : <true-or-false>
    "javadoc"     : <true-or-false>
    "artifacts"   : [ <art1>, <art2>,... ]
   }

All the fields are optional. The specification of an artifact is:

.. code-block:: javascript

   {
    "classifier"  : <classifier>
    "type"        : <type>
    "ext"         : <extension>
    "configs"     : [<conf1>, <conf2>,... ]>
   }

The option ``main-jar`` controls whether the default binary jar is fetched from the
repository, and it is true by default. The options ``sources`` grabs the source jar, and the
option ``javadoc`` the documentation jar; both options are false by default. The field
``artifact`` can be used to retrieve only specific artifacts from the module.

The four properties of the artifact specification are optional, and map directly to
the components of the Ivy resolution pattern. If no property ``classifier`` is present,
or if it is the empty string, the classifier will remain unspecified. The fields
``type`` and ``ext``, if omitted, will default to the string "jar". The field
``configs`` can optionally be used to specify one or more Ivy configuration; if missing,
the configuration ``default`` will be used. For example, the javadoc jar of a module
can also be obtained by specifying an artifact in which the classifier is
"javadoc", the type is "doc", the file extension is "jar", and the configuration
is "javadoc".

Aether-specific options
-----------------------

The Aether build system is similar to the Ivy build system, but resolves its artifacts
from a Maven repository using Aether. That means that the pom descriptor and the
directory structure are not converted into Ivy format, but are kept as they were in
the original Maven repository. This build system is also able to grab Maven-style
artifacts produced by any other project in the same dbuild configuration file, and
republish them with a different cross-version and version number; an example is
supplied later in this guide, in the "Spaces" section.

The ``uri`` field follows the syntax "aether:organization#name;revision". For example:

.. code-block:: javascript

  {
    name:   test4
    system: aether
    uri:   "aether:org.scala-sbt#compiler-interface;0.12.4"
  }

If cross-versions are in use, the Scala version suffix must be explicitly added to the name,
for example: "aether:org.specs2#specs2_2.10;1.12.3". The "extra" options are the following:

.. code-block:: javascript

   {
    "main-jar"    : <true-or-false>
    "sources"     : <true-or-false>
    "javadoc"     : <true-or-false>
   }

The ``main-jar`` flag defaults to true, the other two to false.

.. note::
  Some aspects of the Aether build system are not yet fully implemented. In particular,
  snapshots may not be resolved correctly; also, missing dependencies will not be
  detected at this time (see ``check-missing``, above).


Assemble-specific options
-------------------------

The "assemble" build system is especially designed to work in
conjunction with 2.11-style Scala modules, and in particular
to address the case in which a cycle exists between the core
(library/compiler) and the modules. It works by specifying a
nested list of projects, each of which will be built
independently. At the end, all of the resulting artifacts
will be collected, and their pom/ivy description files will
be rearranged so that they all refer to one another, as if
all of the artifacts were produced by a single project.

In this build system, the "uri" section need not be
specified, as all the source files are specified by the
nested projects. The syntax of the "extra" block is just:

.. code-block:: javascript

   {
    "parts"  : <sub-build>
   }

where "sub-build" is a build definition identical to the
"build" section of the top-level configuration file: a
record with a list of projects and a further optional
section "option". For example:

.. code-block:: text

   build.options.cross-version: full
   build.projects:[
     {
     system: assemble
     name:   scala2
     extra.parts.options: {
       cross-version: standard
       sbt-version: "0.13.0"
     }
     extra.parts.projects: [
       {
         name:   scala-xml
         system: ivy
         uri:    "ivy:org.scala-lang.modules#scala-xml_2.11.0-M6;1.0.0-RC6"
         set-version: "1.2.5-RC33"
       }, {
         name:   scala-parser-combinators
         system: ivy
         uri:    "ivy:org.scala-lang.modules#scala-parser-combinators_2.11.0-M6;1.0.0-RC4"
         set-version: "1.7.20-RC11"
       }, {
         ...

The nested projects can use any build system (including
"assemble" itself), and can generate artifacts either
in Maven or Ivy format.

Since the nested projects are built independently, each
in isolation, in case any of them relies on further
dependencies dbuild will be unable to find them, and
will stop with an error message to that effect. You
usally need to set "extra.parts.options.cross-version"
to "standard", as shown above, in order to disable
the dependency checking for the nested projects only
(the corresponding option for the top-level file
will remain unaffected).

Note that a "set-version" placed
as the same level as "system: assemble" will be
ignored, as the versions of the parts are used instead.
Conversely, a "cross-version" placed at the same level
will be used to determine the cross suffix to be
used for the output of the rewritten artifacts,
at the end of the "assemble" rewriting.

.. warning::

  The resulting aggregate project may rely on some
  external libraries; since all its parts are built
  independently, those libraries will be used at
  compile time with whatever version is requested
  by the standard build file of each part, even if
  those libraries are provided by other dbuild
  projects in the same dbuild configuration file.

  Consequently, dbuild will not "see" the dependency
  in its dependency graph. That is ok as long as
  the dependency is only needed at compile time;
  you should make sure that no ignored dependency
  is needed at runtime, as library conflicts may
  arise otherwise. A warning message will be
  displayed by dbuild during extraction (only when
  the dependencies are first extracted).

.. note::

  The "assemble" system is designed to provide a transitional
  compatibility with the initial stages of the Scala 2.11
  modularization process. Due to its limitations, and due
  to the fact that the parts are built independently, it
  does not offer the same advantages and checks of a
  standard build file, in which all projects are built
  on top of one another. It is therefore advisable to
  adopt a regular (non-cyclic) build as soon as that
  is feasible.


Scala-specific options
----------------------

The "scala" build system is no longer in use. The documentation in
this section is only retained as a reference; the Scala compiler is
currently built using sbt.

In the case of the "scala" build system, the "extra" record is:

.. code-block:: javascript

   {
    "build-number"   : <build-number>,
    "exclude"        : [ subproj1, subproj2,... ]
    "targets"        : [ ["target1","path1"],["target2,"path2"],... ]
    "build-options"  : [ opt1, opt2,... ]
   }

Each of the fields is optional. The are:

build-number
  The contents of the file `build.properties` can be overridden by
  using this option. It is specified as:

  .. code-block:: javascript

     {
      "major"  : <major>,
      "minor"  : <minor>,
      "patch"  : <patch>,
      "bnum"   : <bnum>,
     }

  See below for further details on how to change the different
  variations on the Scala version number.

exclude
  The ant-based Scala build does not support real subprojects. However,
  dbuild will simulate multiple subprojects based on the artifact names.
  This "exclude" clause can be used to prevent some artifacts from being
  published or advertised as available to the rest of the dbuild projects.
  They will still be built, however.

targets
  The Scala build system will normally generate the files by invoking
  the target "publish.local", if available. If the target
  "publish.local" is not available, it will run instead
  "distpack-maven" in "dists/maven/latest", followed by
  "deploy.local".

  If required, this options can be used to specify an alternate sequence
  of targets that should be used instead to generate the Scala compiler
  files; each element is a pair where the first element is the
  ant target name, and the second is a relative path (using "/"
  as a separator) leading to the build.xml where the target is
  defined. For the latter, a path of "." or "" can be used to refer
  to the project root.

build-options
  A sequence of strings; they will be appended to the ant options when
  compiling. This option can be used to define additional properties,
  or to set other flags. If left unspecified, no additional options
  will be passed to ant, and the default targets will
  produce a build that is **non-optimized**. In order to
  compile an optimized build, just append to build-options the
  string ``"-Dscalac.args.optimise=-optimise"``.


Scala version numbers
---------------------

The handling of version numbers in the Scala build system is made
somewhat more complicated by the variety of ways in which version
strings are passed to ant while compiling Scala. The combination
of `build-number`, `set-version` (described above), and `build-options`,
however, makes it possible to control all the various aspects.
In detail, this is the way in which versions are handled:

maven.version.number
  The first version number is the one that is passed to ant via
  a property called `maven.version.number`. If `set-version` is
  specified, the corresponding string will be used. If there is
  no set-version, the version string will be derived from the
  content of the file `build.number`, in the checked out source
  tree, with an additional build-specific suffix. If there is no
  `build.number`, the Scala build system will use instead
  the version string contained in the file `dbuild.json`, if
  present, with the build-specific suffix. If both `dbuild.json`
  and `build.number` exist, the version in `build.number` will
  be used.

build.number
  The content of the build.number, independently, will also
  affect the calculation of some of the version strings used
  by the Scala ant system. If the extra option `build-option`
  is used, its content will be used to overwrite the content
  of the `build.number` file inside the source tree. This
  replacement will not affect the calculation of `maven.version.number`
  described above.

other properties
  The Scala ant build file uses internally other properties; as
  mentioned previously, they can be set if needed by using the
  option `build-options`. The main option that is probably of
  interest is `build.release`; it can be set using:
  ``build-options:["-Dbuild.release=true"]``


.. _custom-resolvers:

Customizing the list of repositories
-------------------------------------
While compiling the various projects, dbuild will look for
artifacts (either Maven or Ivy) in a list of repositories.
The list can be customized, for instance in order to use
a local Artifactory instance that acts as a proxy (useful
to speed up resolution), or to add further custom repositories.

The list of repositories can be specified in one (or both)
of two ways: as a local configuration, or directly in the
build configuration file.

Locally, the list of resolvers can be customized by 
modifying the stanza ``[repositories]`` of the file
``dbuild.properties``, in the ``bin`` subdirectory that
also contains the ``dbuild`` executable.

Conversely, in each build configuration file, the set of
repositories can be specified by defining them under the
``options.resolvers`` path, as in this example:

.. code-block:: text

  vars.ivyPat: ", [organization]/[module]/(scala_[scalaVersion]/)...
  options.resolvers: {
    0: "local"
    1: "cachemvn: http://localhost:8088/artifactory/repo"
    2: "cacheivy: http://localhost:8088/artifactory/repo"${vars.ivyPat}
    ...
  }

The syntax for the each resolver specification is exactly
the same that is also used by sbt.

All of the properties defined under `options.resolvers` in that
manner are collected, and sorted alphabetically by key; the
resulting list is then used to resolve artifacts for that dbuild run.

The order of the definitions in the JSON configuration file
is not important; all of the resolvers found within
``options.resolver`` are collected at the end, and
sorted alphabetically by key. In the example above,
"local" (with label "0") would come before "cachemvn"
(label "1") even if the lines were swapped. The
labels need not be numerical al all, but can be any string:
they are sorted alphabetically.

In case the list is shared by multiple build files, a definition
can also be obtained using the ``vars`` facility, in conjunction
with an external property file that may live on the local file
system, or at a given URL. For example, the build configuration
file could contain:

.. code-block:: javascript

  properties: "file:/some/path/file.props"
  options.resolvers: ${vars.resolvers}

where the file ``file.props`` would contain the following:

.. code-block:: text

  resolvers.0: local
  resolvers.1: cachemvn: http://localhost:8088/artifactory/repo
  resolvers.2: cacheivy: http://localhost:8088/artifactory/repo, [organization]/[...
  ...


The way in which the local list and the build configuration
file list are used is the following:

- If no resolvers are defined in the build file, then
  the list in ``dbuild.properties`` is used.

- If at least one resolver has been defined in the build file,
  the list of default resolvers in ``dbuild.properties`` is
  ignored.

- However, if the option ``--no-resolvers`` (or ``-r``) is
  passed to dbuild, the resolvers in ``dbuild.properties``
  are always used, and the ones in the build configuration
  file are skipped.

Frequently, and especially if dbuild is used under Jenkins,
it is convenient to include the repositories directly in the
configuration file, under ``options.resolvers``, so that it
can be more easily modified. Such a list will typically
include proxies or other resolvers that may not be available
elsewhere. By using the ``--no-resolvers`` option, the same
configuration file can be tested unchanged on a local machine,
and it will use only the resolvers list defined locally 
on that specific machine.

The related options ``--no-notify`` and ``--local`` options
may also apply in that case (use ``dbuild --help`` for
details).

Building a single target project
--------------------------------

It is sometimes useful, during debugging, to build just
one specific project, out of all those listed in a configuration
file, together with its required dependencies.

That can be done by specifying the required project as
an additional argument on the command line, for example:

.. code-block:: bash

   $ bin/dbuild config.dbuild project

It is also possible to specify more than one target, by
supplying a comma-separated list of projects (with no
blanks in between).

Automatic cleanup
-----------------

During its operation, dbuild creates temporary directories
in which to perform dependency extraction and the actual
building of the various projects. Those directories are
left around at the end of the build, in case you would
like to inspect their content, for debugging purposes.

In order to avoid letting those directories accumulate
over time, dbuild will automatically clean up the
data directories that are older than a configurable
age. Such cleanup is performed in the background, while
dbuild compiles new projects.

It is not normally necessary to change anything in
the cleanup configuration, as everything is done
automatically. If, however, for some reason you prefer
to keep temporary data around for longer, or rather
to delete them sooner, the expiration deadlines can
be explicitly configured as follows:

.. code-block:: text

  options.cleanup: {
    extraction: {
      success: 120
      failure: 168
    }
    build: {
      success: 48
      failure: 168
    }
  }

The numbers are the maximum age, specified in hours;
the values in this example are the defaults. This means
that, for example, the temporary data for a failed build
will be kept around for seven days, while the build
files for a successful build will, by default, be deleted
after two days. You can of course specify only one or
more of the parameters above.

If all ages are set to zero, all prior data will be
removed when dbuild starts; the temporary files
corresponding to the current run of dbuild will
be preserved in any case.

Build timeouts
---------------

Builds can get stuck for a variety of reasons. For instance,
a test may end up in an infinite loop, or a development
version of a build tool may get stuck for some reason.
In order to avoid blocking the build indefinitely, dbuild
relies on a number of timeouts, which can be tuned individually.

The default values, which can be individually overridden,
are the following:

.. code-block:: text

  options.timeouts: {
    extraction: "1 hour"
    build:"5 hours"
    extraction-phase: "6 hours"
    build-phase: "16 hours"
    dbuild: "23 hours"
  }

The timeouts are:

extraction
  This is the timeout that we allow for each dependency extraction
  to complete. It may include git/svn checkout, maven/ivy resolution,
  and the actual extraction process.

build
  The timeout that we allow for each build to complete (during the build phase),
  again including checkout, resolution, code compilation, and possibly testing.

extraction-phase
  This is the maximum duration that we allow for the entire extraction phase
  to complete, for all of the projects together.

build-phase
  As above, for the build phase: it is the limit on the time used for the
  building and testing of all the projects. This is particularly useful if
  you would like to perform extraction but don't proceed to the build stage:
  you can just specify "0 seconds" for this timeout.

dbuild:
  This is the overall maximum duration for the entire dbuild run, including
  all the extractions, all the builds and tests, and also the time used for
  the final tasks like notifications, deploy, comparisons, and so on.

The format of each value is "<length><unit>", where whitespace is allowed
before,  between and after the parts. For example, valid durations are "2 
seconds", "1 day", "11.25 hours", "80 minutes". For all of the timeouts,
the maximum duration is 21474835 seconds, or about 248.5 days.


.. note::

  Some of the options described on this page have further extensions that are used
  when compiling and using sbt plugins. Those extensions will be described later,
  as they rely on the "spaces" feature of dbuild, which is introduced in a subsequent
  section.

|

*Next:* :doc:`buildOptions`.

