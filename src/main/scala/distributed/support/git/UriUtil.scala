package distributed
package support
package git

import _root_.java.net.URI

object UriUtil {
  def dropFragment(base: URI): URI = 
    if(base.getFragment eq null) base 
    else new URI(base.getScheme, base.getSchemeSpecificPart, null)
}
