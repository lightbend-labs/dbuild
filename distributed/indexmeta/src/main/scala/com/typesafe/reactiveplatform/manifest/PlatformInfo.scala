package com.typesafe.reactiveplatform.manifest

import java.util.Date

/**
* Represents information about the version/end-of-life of a typeasfe-reactive-platform release.
*/
case class PlatformInfo(
  version: String, // Specific version, e.g. 2014-10-patch-1
  family: String, // Version for this "family", e.g. 2014-10
  // for details on Date serialization, see http://wiki.fasterxml.com/JacksonFAQDateHandling
  endOfLife: Date // The time when we EOL this platform.
  )