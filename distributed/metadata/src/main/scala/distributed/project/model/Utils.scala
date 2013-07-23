package distributed.project.model

import ClassLoaderMadness.withContextLoader
import com.typesafe.config.ConfigFactory.{parseString,parseFile}
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.Config
import com.lambdaworks.jacks.JacksOption._
import com.lambdaworks.jacks.JacksMapper
import com.fasterxml.jackson.databind.JsonMappingException
import java.io.File

object Utils {
  private val mapper=JacksMapper.withOptions(CaseClassCheckNulls(true),
      CaseClassSkipNulls(true),CaseClassRequireKnown(true))
  private def readValueT[T](c:Config)(implicit m: Manifest[T]) = 
    withContextLoader(getClass.getClassLoader){
      val expanded=c.resolve.root.render(ConfigRenderOptions.concise)
      try {
        mapper.readValue[T](c.resolve.root.render(ConfigRenderOptions.concise))
      } catch {
        case e:JsonMappingException =>
          val margin=50
          val len=expanded.length()
          val offset=e.getLocation().getCharOffset().toInt
          val (s1, o1)=if (offset>margin) {
            ("..."+expanded.substring(offset-margin+3),margin)
          } else (expanded,offset)
          val l1=s1.length()
          val s2=if (l1-o1>margin) {
            s1.substring(0, o1+margin-3)+"..."
          } else s1
          println(s2)
          println(" "*o1+"^")
          if (e.getMessage().startsWith("Can not deserialize instance of java.lang.String"))
            println("A string may have been found in place of an array, somewhere in this object.")
          throw e
      }
    }
  def readValue[T](f:File)(implicit m: Manifest[T])=readValueT[T](parseFile(f))
  def readValue[T](s:String)(implicit m: Manifest[T])=readValueT[T](parseString(s))
  def writeValue[T](t:T)(implicit m: Manifest[T]) = 
    withContextLoader(getClass.getClassLoader){mapper.writeValueAsString[T](t)}
  
  // specific simplified variant to deal with reading a path from a /possible/ Artifactory response
  private val mapper2=JacksMapper
  def readSomePath[T](s:String)(implicit m: Manifest[T]) = 
    withContextLoader(getClass.getClassLoader){
      try {
        Some(mapper2.readValue[T](s))
      } catch {
        case e:JsonMappingException => None
      }
    }
}
