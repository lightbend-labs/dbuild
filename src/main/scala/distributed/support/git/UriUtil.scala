package distributed
package support
package git


object UriUtil {
  def dropFragment(base: java.net.URI): java.net.URI = 
    if(base.getFragment eq null) base 
    else new java.net.URI(base.getScheme, base.getSchemeSpecificPart, null)
}
