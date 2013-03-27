package distributed.project.model

import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigRenderOptions
import com.lambdaworks.jacks._
import JacksOption._
import java.io.File

object Utils {
  val mapper=JacksMapper.withOptions(CaseClassCheckNulls(true),CaseClassSkipNulls(true))
  def fromHOCON(s:String)=ConfigFactory.parseString(s).resolve.root.render(ConfigRenderOptions.concise)
  def fromHOCON(f:File)=ConfigFactory.parseFile(f).resolve.root.render(ConfigRenderOptions.concise)
}