dbuild
======

What is it?
-----------
**dbuild**, or distributed-build, is a build and debugging tool based on sbt.

It is used to coordinate the development of multiple, independent projects that evolve in parallel: dbuild
uses a multi-project definition file to build all the requested projects, and make sure that they all work
together, even though each of them may evolve independently.

In case a failure is detected, typically because one change in one project affects one or more of the others,
dbuild can be used as a debugging tool: an affected project can be automatically configured with the exact set
of dependencies that caused the failure, preparing the necessary environment for further debugging.

Please the rest of this guide to discover how dbuild works, and how you can use it with your project.

This guide starts with :doc:`introduction`.

Contents
--------

.. toctree::
   :maxdepth: 2

   introduction
   download
   dbuild
   repositories
   plugin
   caveats
