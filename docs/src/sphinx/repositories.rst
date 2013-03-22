Repositories
============

Local and remote
----------------

The dbuild tool stores its artifacts and build/project information in a custom repository, which can be
either local (typically in the ``~/.dsbt/cache`` directory), or remote, in a special Ivy repository that has
a custom layout.

At this time, the typical usage of dbuild is with a local repository: this is the default configuration
and no special setting is required.

However, you may want to experiment with the remote repositories support. The related code is still evolving,
but should be functional at this time. That may be convenient if you would like to run
dbuild on a Jenkins server, for instance, uploading the results to a shared repository: you can then fetch
the configurations and artifacts automatically from that remote repository, while debugging using the
dbuild sbt plugin, or using the ``drepo`` inspection tool, below.

Remote repository configuration
-------------------------------

In order to use a remote repository, you will need to add a file called ``~/.dsbt/remote.cache.properties``
with the following content:

.. code-block:: text

   remote.url=https://sometesthost.com/path-to-your-dbuild-repository
   remote.user=...
   remote.password=...

There is currently a Typesafe dbuild repository that can be used for testing at the address:

.. code-block:: text

   https://typesafe.artifactoryonline.com/typesafe/temp-distributed-builds

The content of this particular repository may be purged without warning, as it is currently
mainly used for testing and development of the dbuild tool itself.

.. Caution::

   If a dbuild run is interrupted prematurely manually during the build stage, and a remote repository
   is being used, the dbuild run may be erroneously marked as failed, and a subsequent run may not fix
   the situation. A more robust handling of this situation will be added in subsequent releases.

drepo
-----

Enclosed in the distribution archive is also a repository inspection tool, called ``drepo``. You can
invoke it as:

.. code-block:: bash

   $ bin/drepo <options>

If no options are present, it will print a list of options and commands. The main ones are:

``drepo`` ``list-builds``
  Lists all the dbuild builds whose information has been stored in the repository. Each build will be
  listed with its build ID, and with the list of projects and their IDs. Each project ID refers to a
  specific combination of project version, configuration, and dependencies, as listed below.

  Please note that only the builds that are already in your local repository will be listed (not the
  remote ones).

``drepo`` ``build`` ``<id>``
  Shows the details of one build, including an expanded build configuration that shows the exact version
  of the various software project that were used during that dbuild run. A typical output could be:

  .. parsed-literal:: :class: highlight

     :nv:`$` bin/drepo build 48baab8156458005cb2e0569e8e8c2c39221d56e
     --- RepeatableBuild: 48baab8156458005cb2e0569e8e8c2c39221d56e
      = Projects = 
       - 26a265808a6abe8e8451a9f0b43d6dc02176ed16 scala
       - 9fc2901cdc4f1e4ce4209bfad5dfbf02633fcd5f sperformance
      = Repeatable Config =
     {"projects":[{"name":"scala","system":"scala","uri":"git://github.com/scala/scala.git#2f3a7fa417f7ba92251fdae53e5548f081c2fd04","extra":{}},{"name":"sperformance","system":"sbt","uri":"git://github.com/jsuereth/sperformance.git#8c472f2a1ae8da817c43c873e3126c486aa79446","extra":{}}]}

   Notably, if the build ID that you specify refers to a build in the remote repository, the
   relevant information will be automatically fetched from the remote repository and copied to the
   local repository, which acts as a cache. You will then see it listed when using ``dbuild list-builds``.

``drepo`` ``list-projects``
  Lists all of the known project that have been built by a dbuild build. Each project in this list is
  really a combination of version, configuration, and dependencies, and is identified by a unique project ID.
  Only the projects already in the local repository will be listed.

``drepo`` ``project`` ``<id>``
  Prints the details of that build project. For example:

  .. parsed-literal:: :class: highlight

     :nv:`$` bin/drepo project 384bd35367f6ea7489007f5b550455ba791725e9
     --- Project Build: 384bd35367f6ea7489007f5b550455ba791725e9
      -- Dependencies --
         * d8bcebcdf6c21d4aa95b16ad17c6044c40d891ad
         * ac54d173fb38755efeaaf69bccb8d875e1be7560
         * 2865b37b10e2f62efa43568cc86eb7aa3a0cf283
      -- Artifacts -- 
       - org.scala-stm : scala-stm :         : pom : 0.7-384bd35367f6ea7489007f5b550455ba791725e9
       - org.scala-stm : scala-stm :         : jar : 0.7-384bd35367f6ea7489007f5b550455ba791725e9
       - org.scala-stm : scala-stm : sources : jar : 0.7-384bd35367f6ea7489007f5b550455ba791725e9
       - org.scala-stm : scala-stm : javadoc : jar : 0.7-384bd35367f6ea7489007f5b550455ba791725e9
      -- Files -- 
       org/scala-stm/scala-stm/0.7-384bd35367f6ea7489007f5b550455ba791725e9
         bd...42bc      40  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-javadoc.jar.sha1
         9b...ae3e  909.4k  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-javadoc.jar
         89...d6d1      40  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.pom.sha1
         cf...5db4      32  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-javadoc.jar.md5
         5c...0c59      32  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-sources.jar.md5
         28...c6d4      40  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-sources.jar.sha1
         2d...12b4  113.5k  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9-sources.jar
         f2...e1dd    2.6k  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.pom
         46...1c0f      40  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.jar.sha1
         1f...39ff      32  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.pom.md5
         28...f1c2  635.2k  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.jar
         5a...1f26      32  scala-stm-0.7-384bd35367f6ea7489007f5b550455ba791725e9.jar.md5

  Again, if you specify the ID of a project in the remote repository, the relevant files will be copied
  to your local repository (both information and artifacts), and the project details will be displayed.

.. Note::
   The layout of the repository may change in future versions of dbuild.

*Next:* :doc:`plugin`.

