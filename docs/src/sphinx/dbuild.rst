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

One important case, however, is the customization of the resolvers list: you can just change the
``[repositories]`` stanza in this properties file, adding or removing resolvers as needed. The list
specified in this properties file will completely override the normal list of library resolvers,
for all of the projects invoked by dbuild. By specifying here a local Artifactory cache, for instance,
the artifactory resolution can be made considerably faster.

The build configuration file
----------------------------

The central part of the build tool operation revolves around the build configuration file, which describes
in detail which software projects are involved in the build, which version of each should be used, and many
other aspects of the build process.

The build file is written in a JSON format. The file is parsed by using the Typesafe
`config library <http://github.com/typesafehub/config>`_, therefore the syntax of the file can be simplified
by using the conventions of that library. The order of items in the configuration file is never relevant,
unless explicitly mentioned in the documentation.

The top level of the configuration file is:

.. code-block:: javascript

   {"projects": [ <dbuild_project1>, <dbuild_project2>,...],
    "deploy":   [ <dbuild_deploy1>, <dbuild_deploy2>,...]}

Each of the dbuild projects has the structure:

.. code-block:: javascript

   {
    "name"        : <project-name>,
    "system"      : <build-system>,
    "uri"         : <source-repository-uri>,
    "set-version" : <optional-output-version>
    "extra"       : <optional-extra-build-parameters>
   }

Within a project description:

project-name
  A string identifying the software project. This can be arbitrary, and is only used within dbuild,
  although you will want to use something meaningful, like "akka" for Akka, or "scala-arm" for the
  Scala ARM project.

build-system
  A string that describes the build mechanism used by this software project. Valid values are currently
  "scala" (custom for the Scala project) and "sbt"; additional mechanisms will be added soon (Maven
  support is in the works). If not specified, "sbt" is assumed.

source-repository-uri
  A string pointing to the source repository for this project. It can be git-based (if the uri begins
  with ``git://`` or ends with ``.git``), or svn (schemes ``http://``, ``https://``, ``svn://``, only
  if an svn repository is detected). Further formats may be added at a later time.

  The uri string may optionally be prefixed with a ``'#'`` and either a commit hash, an svn version, or a
  branch name. For example:

  .. code-block:: javascript

     "uri":  "git://github.com/scala/scala.git#2.10.x"

  dbuild will download and extract the most recent available version in the specified branch, or the
  exact version or commit in case if specified . If no prefix is added, dbuild will fetch the most recent
  version in git master, or svn head.

optional-output-version
  This component is optional, and normally not used. During compilation, dbuild will automatically
  generate a version string that is used for the various artifacts that are produced by each
  project. However, in case you need to obtain artifacts with a specific version string, you can
  override the default value by specifying a specific version string here.

optional-extra-build-parameters
  The "extra" component is optional, as are all of its sub-components; it describes additional
  parameters used while building the project, and its content depends on the build system. At this
  time it is only used for sbt builds, in which case its structure is:

.. code-block:: javascript

   {
    "sbt-version"    : <sbt-version>,
    "projects"       : [ subproj1, subproj2,... ]
    "exclude"        : [ subproj1, subproj2,... ]
    "run-tests"      : <run-tests>
    "options"        : [ opt1, opt2,... ]
    "commands"       : [ cmd1, cmd2,... ]
   }

Each of them is optional, and their meaning is:

sbt-version
  A string that specifies the version of sbt that should be used to compile
  this dbuild project.

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

The optional section ``deploy`` is described on the next page.

*Next:* :doc:`deploy`.

