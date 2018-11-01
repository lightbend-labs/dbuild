package com.typesafe.dbuild.support

import _root_.java.net.URI

object UriUtil {
  def dropFragment(base: URI): URI =
    new URI(base.getScheme, base.getSchemeSpecificPart, null)

  def dropFragmentAndQuery(base: URI): URI = {
    val part = base.getSchemeSpecificPart
    val index = part.lastIndexOf('?')
    val newPart = if (index<0)
      part
    else
      part.substring(0,index)
    new URI(base.getScheme, newPart, null)
  }
}
