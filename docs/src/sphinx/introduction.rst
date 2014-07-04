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

Let's assume we want to make sure that a specific branch or commit of
`sperformance <http://github.com/jsuereth/sperformance>`_ works with a specific branch or commit
of `scala <http://github.com/scala/scala>`_.

A suitable build configuration file is:

.. code-block:: javascript

   build.projects:[
     {
       name:   scala
       system: scala
       uri:    "git://github.com/scala/scala.git#2.10.x"
     }, {
       name:   sperformance
       system: sbt
       uri:    "git://github.com/jsuereth/sperformance.git#community"
     }
   ]

We will put this into a file called ``test.dbuild``. The line "system" specifies the build mechanism used to build each of the
projects (a custom "scala" build system for scala, and "sbt" for sperformance). Let's download dbuild, and try it out (you can
find the exact download instructions and other details later in this manual):

.. code-block:: bash

   $ [...download and unzip dbuild...]
   $ cd dbuild
   $ [...edit test.dbuild...]
   $ bin/dbuild test.dbuild

Now dbuild will download, parse, and build in turn scala and sperformance, from the branches
"2.10.x" and "community" respectively. You can also specify a commit hash, in place of the branch.

It is important to notice that here sperformance will be forced to use the scala version that
you specify, overriding its normal build settings. In case the build file contains many different projects,
each of them will be tweaked on-the-fly, in order to use the exact combination of branches/commits that you
specified in the configuration file.

dbuild will keep an index of the actual commit ids (git hashes, svn versions, etc) used during that particular
run: the same configuration can later be reproduced again, and prepared for debugging.

At the end of the build, all the of generated artifacts and other information on the build will be stored
in a local repository, contained in your ``~/.dbuild/cache`` directory. If the build ends successfully, you know
that all the network of inter-dependent projects you specified builds together as desired.

What if the build fails?
------------------------

While testing, at some point it is almost inevitable that one of the projects will fail to build,
possibly affecting some other projects listed in the dbuild file. At that point, it is useful to be
able to set up a debugging environment, in order to be able to reproduce the failure and to perform
some debugging. In dbuild that is very easy to do.

During the dbuild run, you will see two lines like:

.. code-block:: text

   [info] ---==  RepeatableBuild ==---
   [info]  uuid = 48baab8156458005cb2e0569e8e8c2c39221d56e

The uuid uniquely identifies the tested combination of projects and commits. Let us assume that
sperformance failed, and that we want to debug it. We can reconstruct the very same configuration,
and debug interactively the project, using the command:

.. code-block:: text

   $ dbuild checkout 48baab8156458005cb2e0569e8e8c2c39221d56e sperformance newdir
   [...]

   Ready! You can start the debugging environment by running: [...]/newdir/start

   You can also rebuild the project just like dbuild would do, by issuing "dbuild-build" at the sbt prompt.

   $

At this point the project has been checked out in the directory "newdir", and configured exactly as it
was during the dbuild run. The script "start" will take you to an interactive sbt session where you will
be able to try things out, and proceed with debugging.

|

.. note::
   dbuild is currently under active development, and should still be considered experimental.
   New features and improvements are constantly being introduced: the syntax and other details of
   the tool may change over time.

|

*Next:* :doc:`download`.
