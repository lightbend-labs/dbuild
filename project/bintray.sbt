// Note: currently using an unofficial pre-release

resolvers += Resolver.url("bintray-eed3si9n-sbt-plugins", url("https://dl.bintray.com/eed3si9n/sbt-plugins/"))(Resolver.ivyStylePatterns)

addSbtPlugin("com.eed3si9n" % "bintray-sbt" % "0.3.0-93126a5d02f296a4e460e264ecb62b28046aeef1")

//resolvers += Resolver.url(
//  "bintray-sbt-plugin-releases",
//   url("http://dl.bintray.com/content/sbt/sbt-plugin-releases"))(
//       Resolver.ivyStylePatterns)
//
//addSbtPlugin("me.lessis" % "bintray-sbt" % "0.3.0")

