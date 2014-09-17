# dbuild

## Description

*dbuild* is a build and debugging tool based on sbt.

It is used to coordinate the development of multiple, independent projects
that evolve in parallel: dbuild uses a multi-project definition file to build
all the requested projects, and makes sure that they all work together, even
though each of them may evolve independently.

You can find the complete dbuild documentation at the
[dbuild web site](http://typesafehub.github.com/dbuild).

To recompile, publish, etc., just type the following in the root project:

  ^command

where command is one of compile, clean, test, publish, publish-local, etc.

To create a full release, point publishTo and credentials to the appropriate
values, then from the root project type "^release".
