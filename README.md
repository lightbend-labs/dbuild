# dbuild

## Description

*dbuild* is a build and debugging tool based on sbt.

It is used to coordinate the development of multiple, independent projects
that evolve in parallel: dbuild uses a multi-project definition file to build
all the requested projects, and makes sure that they all work together, even
though each of them may evolve independently.

You can find the complete dbuild documentation at the
[dbuild web site](http://lightbend.github.io/dbuild).

This project is active, but unsupported. It is maintained by the Tooling Team at Lightbend.

## Releasing

To create a dbuild release (if you belong to the Typesafe organization on Bintray):

1. Type "^publish"
2. Check https://bintray.com/typesafe/ivy-releases/dbuild/view to ensure files are as expected (Optional)
3. Type "root/bintrayRelease" to make the release public

*DO NOT* try to push snapshots to Bintray; instead, add your custom version
suffix if necessary. The documentation pages on the dbuild website must be
published separately (but only for final releases).

If you are not part of the Typesafe organization on Bintray, use:

    set every bintrayOrganization := None

to publish to "ivy-releases/dbuild" to your own Bintray repository
(or to a different repository by changing the settings described
in the bintray-sbt plugin documentation pages).

If you would like to publish instead to Artifactory, for instance if you
you need to publish dbuild snapshots, or if you do not have an account on
Bintray yet, you can use:

    set every publishTo := Some(Resolver.url("somelabel", new URL("http://artifactoryhost/artifactory/repository/"))(Resolver.ivyStylePatterns))
    set every credentials := Seq(Credentials(Path.userHome / "some" / "path" / "credentials-file"))

Then, proceed with "^publish" as usual to issue the snapshot to your Artifactory server.

You can also publish a test version locally to any directory of your choice, by using:

    set every publishTo := Some(Resolver.file("dbuild-publish-temp", new File("/home/user/here/"))(Resolver.ivyStylePatterns))
    ^publish


## Get Involved

dbuild has a [mailing list](http://groups.google.com/d/forum/dbuild) for help.  Additionally, issues can be
reported to [github issue tracker](https://github.com/lightbend/dbuild/issues).

## Release Notes

see [CHANGELOG.md](CHANGELOG.md)

## License

This software is licensed under the Apache 2 license.

### Developed by Lightbend

Maintained by the [Tooling Team](https://github.com/orgs/lightbend/teams/tooling-team)

Feel free to ping above maintainers for code review or discussions. 
Pull requests are very welcome–thanks in advance!
