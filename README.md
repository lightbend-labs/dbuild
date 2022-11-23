# dbuild

...is a build and debugging tool based on sbt.

It is used to coordinate the development of multiple, independent projects
that evolve in parallel: dbuild uses a multi-project definition file to build
all the requested projects, and makes sure that they all work together, even
though each of them may evolve independently.

You can find the complete dbuild documentation at the
[dbuild web site](https://lightbend-labs.github.io/dbuild).

## Maintenance status

dbuild is now only used by the [Scala 2 community build](http://github.com/scala/community-build).
It receives only light maintenance, as needed, from the Scala team at Lightbend.

## Release Notes

see [CHANGELOG.md](CHANGELOG.md)
