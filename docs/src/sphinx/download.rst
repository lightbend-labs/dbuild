Download and Setup
==================

Overview
--------

dbuild provides both the main multi-project build tool, as well as an sbt plugin that is used as for debugging. The two
components share much of the code, but they can be used and installed independently.

#. The build tool is distributed as a tgz/zip archive. You can download it and run the "dbuild" executable in order to build your multi-project configuration.

#. The sbt plugin is easily obtained by configuring a global sbt plugin file (see below)

Installing the build tool
-------------------------

The stand-alone build compontent of dbuild can be downloaded in one of the following formats:

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

.. _installing-sbt:

Installing the sbt plugin
-------------------------

If you need to use the results of a dbuild build in order to debug one of the projects, you can add to your system the dbuild sbt plugin.
That is done by adding a small file to the global ``~/.sbt/plugins`` directory, as follows:

.. parsed-literal:: :class: highlight

      :nv:`$` mkdir -p ~/.sbt/plugins
      :nv:`$` cat <<EOF >~/.sbt/plugins/dbuild.sbt
      resolvers ++= Seq(Resolver.url(:s2:`"dbuild-snapshots"`,url("http://repo.typesafe.com/typesafe/temp-distributed-build-snapshots/"))(Resolver.ivyStylePatterns), :s2:`"akka-releases"` at "http://repo.akka.io/releases")

      |addSbtplugin|
      EOF
      :nv:`$`

Once the file is in place, just launch sbt in your project as usual. You will get access to the ``dbuild-setup`` command,
which is explained on the next page of this guide (:doc:`dbuild`). This sbt plugin was compiled for sbt |sbtversion|.

.. note::
   For the time being all of the releases of dbuild are published to the ``"dbuild-snapshots"`` repository,
   shown above. Future releases will be published to either the sbt or the standard Typesafe repositories.

|

*Next:* :doc:`dbuild`.
