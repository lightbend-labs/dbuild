package distributed.project.model

import ClassLoaderMadness.withContextLoader
import com.typesafe.config.ConfigFactory.{parseString,parseFile}
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.Config
import com.lambdaworks.jacks._
import JacksOption._
import java.io.File

object Utils {
  private val mapper=JacksMapper.withOptions(CaseClassCheckNulls(true),CaseClassSkipNulls(true))
  private def readValueT[T](c:Config)(implicit m: Manifest[T]) = 
    withContextLoader(getClass.getClassLoader){
      mapper.readValue[T](c.resolve.root.render(ConfigRenderOptions.concise))
    }
  def readValue[T](f:File)(implicit m: Manifest[T])=readValueT[T](parseFile(f))
  def readValue[T](s:String)(implicit m: Manifest[T])=readValueT[T](parseString(s))
  def writeValue[T](t:T)(implicit m: Manifest[T]) = 
    withContextLoader(getClass.getClassLoader){mapper.writeValueAsString[T](t)}
}
