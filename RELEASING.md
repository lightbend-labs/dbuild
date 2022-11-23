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

    set every publishTo := Some(Resolver.url("somelabel", new URL("https://artifactoryhost/artifactory/repository/"))(Resolver.ivyStylePatterns))
    set every credentials := Seq(Credentials(Path.userHome / "some" / "path" / "credentials-file"))

Then, proceed with "^publish" as usual to issue the snapshot to your Artifactory server.

You can also publish a test version locally to any directory of your choice, by using:

    set every publishTo := Some(Resolver.file("dbuild-publish-temp", new File("/home/user/here/"))(Resolver.ivyStylePatterns))
    ^publish
