Using dbuild
============

Using dbuild
------------

The stand-alone ``dbuild`` tool is called by specifying on the command line its build configuration file:

.. code-block:: bash

   $ bin/dbuild build-file.dbuild

The build tool will create, if not existing, a ``~/.dbuild`` directory in your home, which stores general
dbuild support files and the local repository cache, and a ``target`` in your current directory, which is
used as a staging area for the current build.

Unless specified otherwise, the end results of the build (artifacts and build information) will be stored
into a local repository in ``~/.dbuild``. It is also possible to use a remote Ivy repository as storage;
that is explained later in the section :doc:`repositories`.

Properties file
---------------

By default, dbuild will use the file ``dbuild.properties`` to retrieve information about its internal
configuration. There is usually no need to touch this file, as it contains defaults that should be
adequate for most projects. However, you can modify this file if you would like to modify the standard
behavior in some way (for instance, relocating the default ``~/.dbuild`` directory, or the location of
the Ivy cache it uses).

The ``dbuild.properties`` file also contains the default list of resolvers that are used to resolve
artifacts within dbuild. The list can be modified here, or via variables or further properties files,
as explained in the section :ref:`custom-resolvers`, below.

The build configuration file
----------------------------

The central part of the build tool operation revolves around the build configuration file, which describes
in detail which software projects are involved in the build, which version of each should be used, and many
other aspects of the build process.

The build file is written in a JSON format. The file is parsed by using the Typesafe config library,
therefore the syntax of the file can be simplified by using the conventions of that library: double
quotes and commas may be omitted, variable substitution is available, and other facilities are
available. You should check the many options that are available to simplify the syntax of
configuration file; they are all documented on the website of the
`config library <http://github.com/typesafehub/config>`_. In addition, dbuild has one special
feature concerning lists of strings: whenever a list of strings is expected, in the form
``"x":["a","b","c"]``, if you have only one element you can also omit the square brackets altogether.
In accordance with the typesafe config library, you can then usually omit the double quotes
and in the end just write ``x:a``.

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
  This section is required, and contains all of the information needed to generate the build.
  It includes the description of the various projects, and other options that control the
  generation of the code. It is described in detail below.

options
  This section is optional. It does not contain information that affect the build, but is used
  to control other aspects of dbuild, in particular options and tasks that should be executed
  after the build. At this time, the section may contain the following:

  .. code-block:: javascript

     {
      "deploy"        : [ <deploy_1>, <deploy_2>,...]
      "notifications" : <notifications>
      "resolvers"     : { label1: resolv1, label2: resolv2, ...}
      "cleanup"       : <cleanup_options>
     }

   The various options are described in detail in the rest of this guide.

vars
  This section is optional; it may contain a list of variables, in JSON format, whose content
  is simply substituted in the rest of the configuration file, using the conventions of the
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

  you can refer to the value of the property by using ``${vars.sys.x.y}``.  

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

  Properties files that come earlier in the list have priority. Variables that are
  defined locally, in the 'vars' section, take precedence in any case over all of
  the properties defined in the properties files of this list.


The build section
-----------------

The build section has the following content:

.. code-block:: javascript

   {
    "projects": [ <dbuild_project1>, <dbuild_project2>,...],
    "options" : <build-options>
   }

projects
  The "projects" section is the most important one, and is the only one that is required in a
  dbuild configuration file. If you have no other sections, you can take advantage of the
  extended JSON syntax, and introduce the project section directly by writing:
  ``build.projects: [...]``. The list of projects, enclosed in square brackets, describes
  the various software projects that should be built together by dbuild. 

options
  This section contains global options that affect the projects in the build; it is distinct
  from the previous one. It is optional, and is described on the page :doc:`buildOptions`.


Each project descriptions has this structure:

.. code-block:: javascript

   {
    "name"        : <project-name>,
    "system"      : <build-system>,
    "uri"         : <source-repository-uri>,
    "set-version" : <optional-output-version>
    "deps"        : <optional-dependencies-modifiers>
    "extra"       : <optional-extra-build-parameters>
   }

Within a project description:

name
  A string identifying the software project. This can be arbitrary, and is only used within dbuild,
  although you will want to use something meaningful, like "akka" for Akka, or "scala-arm" for the
  Scala ARM project.

system
  A string that describes the build system used by this software project. Valid values are currently
  "scala" (custom for the Scala project), "sbt", "ivy", and "assemble". Additional mechanisms will
  be added soon (Maven support is in the works). If not specified, "sbt" is assumed.

uri
  A string pointing to the source repository for this project. It can be git-based (if the uri begins
  with ``git://`` or ends with ``.git``), or svn (schemes ``http://``, ``https://``, ``svn://``, only
  if an svn repository is detected). Other source repository formats may be added in the future.

  The uri may optionally be prefixed with a ``'#'`` and either a commit hash, an svn version, or a
  branch name. For example:

  .. code-block:: javascript

     "uri":  "git://github.com/scala/scala.git#2.10.x"

  dbuild will download and extract the most recent available version in the specified branch, or the
  exact version or commit in case if specified . If no prefix is added, dbuild will fetch the most recent
  version in git master, or svn head.

set-version
  This component is optional, and normally not used. During compilation, dbuild will automatically
  generate a version string that is used for the various artifacts that are produced by each
  project. However, in case you need to obtain artifacts with a specific version string, you can
  override the default value by specifying a specific version string here. If you are planning to
  use this feature in order to release artifact, then you also need to set the option "cross-version"
  to "standard", as explained in the section :ref:`section-build-options`.

deps
  The optional "deps" section can be used to modify the way in which dbuild rewires certain
  dependencies of this project. At this time, it can be used to prevent dbuild from modifying
  some of the dependencies, by using the syntax:

  .. code-block:: javascript

   {
    "ignore" : [ mod1, mod2, ...]
   }

  The dependencies that match the specified modules (in the format "organization#name") will
  be resolved as they would normally be for the project, rather than being adapted by dbuild
  in order to match one of the other projects in the file. For example:

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
  by different projects. The recommended approach is instead either splitting the projects
  into sets of subprojects that do not form a cycle, or modifying the projects themselves,
  in order to remove the cyclic dependencies.

extra
  The "extra" component is optional, as are all of its sub-components; it describes additional
  parameters used while building the project, and its content depends on the build system, as
  detailed below.

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
    "options"             : [ opt1, opt2,... ]
    "commands"            : [ cmd1, cmd2,... ]
    "extraction-version"  : <compiler-version-string>
   }

Each of these fields is optional; their meaning is:

sbt-version
  A string that specifies the version of sbt that should be used to compile
  this dbuild project. If not specified, the sbt version in use will be the
  one specified in the global build options property "sbt-version" (see
  :doc:`buildOptions`). If that is also missing, sbt 0.12.4 will be used.

projects
  A sequence of strings that identifies a subset of the sbt subprojects that should be
  built within this dbuild project. For instance, you can specify:

  .. code-block:: javascript

     "projects":  ["akka-actor"]

  in order to compile only the "akka-actor" sbt project within Akka. For each
  of the specified subprojects, dbuild will also add recursively all of the
  subprojects that are in the same project and that are required dependencies
  of the specified ones; if the subproject is an sbt aggregate, its components
  will also be added. If the "projects" clause is not present, all of the
  subprojects will be included.

  If the project uses sbt's default projects, the actual subproject name may
  vary over time, and take forms like "default-e3c4f7". In order to refer to
  sbt's default subproject, you can use the predefined name `"default-sbt-project"`.

exclude
  Sometimes it may be useful to split a single project into two or more parts.
  This clause can be used to exclude explicitly some of the subprojects, which
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

options
  A sequence of strings; they will be
  passed as-is as additional JVM options, while launching the sbt instance that is used
  to build this project.

commands
  A sequence of sbt commands; they will be executed by sbt before dbuild rearranges
  the project dependencies. These commands can be used, for example, to change settings
  using forms like "set setting := ...".

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

Scala-specific options
----------------------

In the case of Scala, the "extra" record is:

.. code-block:: javascript

   {
    "build-target"   : <build-target>,
    "deploy-target"  : <deploy-target>,
    "build-options"  : [ opt1, opt2,... ]
    "build-number"   : <build-number>,
    "exclude"        : [ subproj1, subproj2,... ]
   }

Each of the fields is optional. The are:

build-target
  The Scala build system will normally generate the files by invoking
  the target "distpack-maven-opt". If required, a different target can
  be specified using this option.

deploy-target
  This is the ant target that is used to copy the generated files as
  Maven artifacts, to a local repository. The default is "deploy.local",
  but it can be overridden by using this option.

build-options
  A sequence of strings; they will be appended to the ant options when
  compiling. This option can be used to define additional properties,
  or to set other flags.

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

exclude
  The ant-based Scala build does not support real subprojects. However,
  dbuild will simulate multiple subprojects based on the artifact names.
  This "exclude" clause can be used to prevent some artifacts from being
  published or advertised as available to the rest of the dbuild projects.
  They will still be built, however.

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
will stop with an error message to that effect. In that
case, you can set "extra.parts.options.cross-version"
to "standard", as shown above, in order to disable
the dependency checking for the nested projects only
(the corresponding option for the top-level file
will remain unaffected).

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


.. _custom-resolvers:

Customizing the list of repositories
-------------------------------------
While compiling the various projects, dbuild will look for
artifacts (either Maven or Ivy) in a list of repositories.
The list can be customized, for instance in order to use
a local Artifactory instance that acts as a proxy (useful
to speed up resolution), or to add further custom repositories.

There are various ways in which the list of resolvers can
be customized. The simplest approach is just to modify the
stanza ``[repositories]`` of the file ``dbuild.properties``,
in the ``bin`` subdirectory. This is a
simple way to customize the list of resolvers for all of
the dbuild invocations on a development machine.

As a more flexible alternative, the list can also be
customized for each individual configuration file. That
is done by defining a set of repositories under the
``options.resolvers`` path.

For example, they can be described in a Java-style
properties file as in this example:

.. code-block:: text

  resolvers.0: local
  resolvers.1: cachemvn: http://localhost:8088/artifactory/repo
  resolvers.2: cacheivy: http://localhost:8088/artifactory/repo, [organization]/[...
  ...

The list can then be included in a dbuild configuration file
just by adding:

.. code-block:: javascript

  properties: "file:/some/path/file.props"
  options.resolvers: ${vars.resolvers}

This is especially convenient if you have multiple lists
of repositories: just by changing the file referred to by
the "properties" field, you can select the appropriate one.

In case you prefer to embed the list of resolvers directly in
the configuration file, the properties can be defined
there instead, as in this example:

.. code-block:: text

  vars.ivyPat: ", [organization]/[module]/(scala_[scalaVersion]/)...
  options.resolvers: {
    0: "local"
    1: "cachemvn: http://localhost:8088/artifactory/repo"
    2: "cacheivy: http://localhost:8088/artifactory/repo"${vars.ivyPat}
    ...
  }

This last approach may be more convenient in the case in which
the dbuild job runs under Jenkins, as the list of
repository can be customized together with the
rest of the configuration file, without having to change
the local setup.

It is also possible to combine variables defined in the
configuration file together with multiple properties files,
as described in more detail in the subsection
:ref:`custom-resolvers`, above.

All of the properties defined under `options.resolvers` in that
manner are collected, and sorted alphabetically by key; the
resulting list is then used to resolve artifacts for that dbuild run.

The order of the definitions in the JSON configuration file
is not important; all of the resolvers found within
``options.resolver`` are collected at the end, and
sorted alphabetically by key. In the example above,
"local" (with label "0") would come before "cachemvn"
(label "1") even if the lines were swapped. The
labels need not be numerical al all, but can be any string.

If at least one resolver has been defined via properties,
as described above, the list of default resolvers that
is specified in ``dbuild.properties`` will be ignored.

The syntax for the each resolver specification is exactly
the same that is also used by sbt.

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

|

*Next:* :doc:`buildOptions`.

