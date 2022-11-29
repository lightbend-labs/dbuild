## Publishing (official)

You must have rights on Sonatype to publish under `com.typesafe`.

You should have a `~/.sbt/0.13/sonatype.sbt` (or similar filename)
with the right `credentials += ...`.

To publish a dbuild release,

1. Be sure you are running Java 8
2. Run `sbt`
3. In sbt, run `^publishSigned`
4. Check the staging repo in the Sonatype web UI to ensure files are as expected (optional)
5. Close and release the staging repo (using `sbt-sonatype` commands or the Sonatype web UI)

Do not try to publish snapshots to Sonatype. Instead, add a custom version
suffix (such as `-RC1`).

If everything goes well, then also:

1. Update CHANGELOG.md
2. Tag the release
3. Push the tag to GitHub,
4. Make a GitHub release from the tag
5. Run `packageZipTarball`
6. Attach the resulting `.tgz` and the `.zip` to the GitHub release
7. Publish the website (see below).

## Publishing (locally)

You can publish a test version locally to any directory of your choice, by using:

    set every publishTo := Some(Resolver.file("dbuild-publish-temp", new File("/home/user/here/"))(Resolver.ivyStylePatterns))
    ^publish

## Publishing (elsewhere)

If you would like to publish to Artifactory instead, for instance if you
you need to publish dbuild snapshots, or if you do not have the right
permissions on Sonatype, you can use:

    set every publishTo := Some(Resolver.url("somelabel", new URL("https://artifactoryhost/artifactory/repository/"))(Resolver.ivyStylePatterns))
    set every credentials := Seq(Credentials(Path.userHome / "some" / "path" / "credentials-file"))

Then, proceed with `^publish` as usual to issue the snapshot to your Artifactory server.

## Documentation

The documentation pages on the dbuild website must be published
separately. (This is done only for final releases.)
