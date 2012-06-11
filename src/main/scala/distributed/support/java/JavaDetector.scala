package distributed
package support
package java

import sys.process._

object JavaDetector extends CapabilityDetector {
   def detectLocal(): Seq[Capability] = (
       detectJava ++
       detectJavac
   )
   
   
   // TODO - Capture stderr, since this is where java versions show up.
   
   def detectJava: Seq[Capability] =
     try {
       val version = Process(Seq("java", "-version")).!!
       Seq(Capability("java", version))
     } catch {
       case e: Exception => Seq.empty
     }
   def detectJavac: Seq[Capability] = {
     // TODO - Try java home...
     try {
       val version = Process(Seq("javac", "-version")).!!
       Seq(Capability("javac", version))
     } catch {
       case e: Exception => Seq.empty
     }
   }
}