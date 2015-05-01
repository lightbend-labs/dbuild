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

To create a release, point publishTo and credentials to the appropriate
values, then from the root project type "^release". Please do not use "^publish",
as some additional preparation is necessary.

The command "^release" will deploy artifacts to the "typesafe/dbuild" Bintray
repository, but will not issue the release; follow that with a "root/bintrayRelease"
in order to actually publish the Bintray release. Publishing documentation
needs to be done separately. If you would like to create a private release
out of the typesafe organization, you will need to

  set every bintrayOrganization := None

In order to publish a snapshot, *do not* just issue "^release": snapshots
should not go to Bintray. Instead, define beforehand:

  set every publishTo := Some(Resolver.url("somelabel", new URL("http://artifactoryhost/artifactory/repository/"))(Resolver.ivyStylePatterns))
  set every credentials := Seq(Credentials(Path.userHome / "some" / "path" / "credentials-file"))

Then, proceed with "^release" as usual to issue the snapshot to some Artifactory instance.


## Get Involved

dbuild has a [mailing list](http://groups.google.com/d/forum/dbuild) for help.  Additionally, issues can be
reported to [github issue tracker](https://github.com/typesafehub/dbuild/issues).
