dbuild: Introduction
====================

Terminology
-----------

Since there are many layers involved, it is useful to introduce first the terminology used in the rest
of this guide.

dbuild build
  A configuration file, and related build process, that builds a set of independent software projects
  (for instance Scala, Akka, Scalacheck) to make sure that they all work together.


dbuild project
  It is one software projects in a dbuild build. A dbuild project can be based on sbt or (in the future)
  on Maven, or other tools. Hence, an entire sbt build (which can contain multiple sbt subprojects) counts
  as just one dbuild project.

A quick example
---------------

Let's assume we want to make sure that specific versions of certain libraries and applications
work correctly together, even though the library dependencies within the source tree of each may
refer to some other version. For this example, we will try scala-xml, scalacheck, and sbinary.

A suitable build configuration file is:

.. code-block:: javascript

   build.projects:[
     {
       name:   sbinary
       uri:    "https://github.com/sbt/sbinary.git#v0.4.4"
     }, {
       name:   scalacheck
       uri:    "https://github.com/rickynils/scalacheck.git#e65c90a"
       extra.projects: jvm
     }, {
       name:   scala-xml
       uri:    "https://github.com/scala/scala-xml.git#v1.0.6"
     }
   ]

Here, for each project we specify a name, the location of the source repository,
and the commit, tag, or branch that we want to verity. For the scalacheck project,
we also indicate that we only want to build one specific subproject ("jvm").

We will write this configuration into a file called ``test.dbuild``. Let's download
dbuild, and try it out (you can find the exact download instructions and other
details later in this manual):

.. code-block:: bash

   $ [...download and unzip dbuild...]
   $ cd dbuild
   $ [...edit test.dbuild...]
   $ bin/dbuild test.dbuild

Now dbuild will download, parse, and build in turn each of the projects, from the
tags and commits we want to verify. In detail, during an initial "extraction" phase,
each project will be inspected in order to determine its library dependencies, and
the artifacts that it provides. It will then match the projects, so that it determines
that certain projects can provide the dependencies needed by some other projects, and
will print a short report, like:

.. code-block:: text

   [info] ---== Dependency Information ===---
   [info] Project scalacheck
   [info]   depends on:
   [info] Project scala-xml
   [info]   depends on:
   [info] Project sbinary
   [info]   depends on: scala-xml, scalacheck
   [info] ---== End Dependency Information ===---


In this case, sbinary will be forced to use the binaries provided by the other two projects,
overriding its normal build settings. Each of the projects will be tweaked on-the-fly and
rebuilt, in turn, in order to use the exact combination of branches/commits that you
specified in the configuration file.

At the end of the build, all the of generated artifacts and other information on the build
will be stored in a local repository, contained in your ``~/.dbuild/cache`` directory.
If the build run ends successfully, you will know that the various inter-dependent projects
that you specified worked together as desired; if not, a handy compilation summary will be
printed in order to help you pinpoint the exact reason for the failure, and the build log
will show you the relevant compilation or test errors.

What if the build fails?
------------------------

When testing many projects together, which may evolve independently, it may happen that
some of them will fail to build, possibly affecting in turn other projects. For instance,
a functional change or an API change may cause a failure. At that point, it may useful
to be able to set up a debugging environment, in order to be able to reproduce the exact
failure and to perform additional debugging. In dbuild that is very easy to do.

During the dbuild run, you will see two lines like:

.. code-block:: text

   [info] ---==  RepeatableBuild ==---
   [info]  uuid = c474e7a4caa376e240d530cd7786ffd3e6a37dac

This uuid uniquely identifies the tested combination of projects and commits. Let us assume that
sbinary failed, and that we want to debug it. We can reconstruct the very same configuration,
and debug interactively the project, using the command:

.. code-block:: text

   $ dbuild checkout c474e7a4caa376e240d530cd7786ffd3e6a37dac sbinary newdir
   [...]

   Ready! You can start the debugging environment by running: [...]/newdir/start

   You can also rebuild the project just like dbuild would do, by issuing "dbuild-build" at the sbt prompt.

   $

At this point the project has been checked out in the directory "newdir", and configured exactly as it
was during the dbuild run. The script "start" will take you to an interactive sbt session where you will
be able to try things out, and proceed with debugging.

|

.. note::
   dbuild is under active development, and new features and improvements are introduced over time:
   the syntax and other details of the tool may change in the future.

|

*Next:* :doc:`download`.
