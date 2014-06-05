Download and Setup
==================

Installing the build tool
-------------------------

The dbuild tool is distributed as a launcher, and a set of components that are downloaded
automatically. The launcher can be downloaded in one of the following formats:

Available formats, dbuild-|version|:
  * gzipped tar: tgz_
  * zip archive: zip_

The archive contains the following files:

.. code-block:: text

   ./bin
   ./bin/dbuild
   ./bin/dbuild.properties
   ./bin/drepo
   ./bin/drepo.properties
   ./bin/sbt-launch.jar
   ./samples/[...]

Where ``dbuild`` is the build tool, and ``dbuild.properties`` is its configuration file. The ``bin`` directory also contains
the ``drepo`` repository inspection tool, together with its configuration file; you can find more information in the
section :ref:`section-drepo`, later in this guide.

Using dbuild
------------

The ``dbuild`` launcher is called by specifying on the command line its build configuration file:

.. code-block:: bash

   $ bin/dbuild build-file.dbuild

The build tool will create, if not existing, a ``~/.dbuild`` directory in your home, which stores general
dbuild support files and the local repository cache, and a ``target`` in your current directory, which is
used as a staging area for the current build.

Unless specified otherwise, the end results of the build (artifacts and build information) will be stored
into a local repository in ``~/.dbuild``. It is also possible to use a remote Ivy repository as storage;
that is explained later in the section :doc:`repositories`. Additional details on the command line options
are available below.

The very first time dbuild is launched on a new machine, it will pause for several minutes while a number
of needed libraries are downloaded from the network; this is normal and will happen only upon the very
first invocation.

Properties file
---------------

By default, dbuild will use the file ``dbuild.properties`` to retrieve information about its internal
configuration. There is usually no need to touch this file, as it contains defaults that should be
adequate for most projects. However, you can modify this file if you would like to modify the standard
behavior in some way (for instance, relocating the default ``~/.dbuild`` directory, or the location of
the Ivy cache it uses).

The ``dbuild.properties`` file also contains the default list of resolvers that are used to resolve
artifacts within dbuild. The list can be modified here, or via variables or further properties files,
as explained in the section :ref:`custom-resolvers`.

Command line options
--------------------

A summary of the command line options can be obtained at any time by typing ``dbuild --help``. As of
version 0.9.0, the options are:

.. code-block:: text

   $ bin/dbuild --help
   Typesafe dbuild 0.9.0
   Usage: dbuild [OPTIONS] config-file [target]
   dbuild is a multi-project build tool that can verify the compatibility
   of multiple related projects, by building each one on top of the others.
   Options:
   
     -Dkey=value [key=value]...   One or more Java-style properties
     -d, --debug                  Print more debugging information
     -l, --local                  Equivalent to: --no-resolvers --no-notify
     -n, --no-notify              Disable the notifications defined in the
                                  configuration file, and only print a report on the
                                  console
     -r, --no-resolvers           Disable the parsing of the "options.resolvers"
                                  section from the dbuild configuration file: only
                                  use the resolvers defined in dbuild.properties
         --help                   Show help message
         --version                Show version of this program
   
    trailing arguments:
     config-file (not required)   The name of the dbuild configuration file
     target (not required)        If a target project name is specified, dbuild will
                                  build only that project and its dependencies
   
   Subcommand: checkout
   Use "dbuild checkout" to check out one project from a previously compiled
   build projects, preparing sbt for a debugging session.
   Options:
   
         --help   Show help message
   
    trailing arguments:
     uuid (required)      UUID of the build
     project (required)   name of the project
     path (required)      path into which the source will be checked out
   
   For more information: http://typesafehub.github.io/distributed-build

During common usage, the most common ways to invoke dbuild are ``dbuild file.dbuild`` (to run
a build using a configuration file), and ``dbuild checkout uuid project dir`` (to debug
a failed build).

Of particular interest is the option ``--no-resolvers``. As will be described later, each dbuild
configuration file may include a list of resolvers, which are used to retrieve the libraries
used by the various projects. If you receive a configuration file from someone
else, and the list of resolvers includes repositories that are not available to you (for
instance proxy repositories), you can use that flag to ignore the list in the build file,
and use instead the list specified in your ``dbuild.properties`` file. This options applies
both to dbuild building as well as to dbuild checkout.

|

*Next:* :doc:`dbuild`.
