package config

import com.typesafe.{config => j}


object Config {

    def defaultRenderOptions = j.ConfigRenderOptions.defaults

    def defaultParseOptions = j.ConfigParseOptions.defaults

    def defaultResolveOptions = j.ConfigResolveOptions.defaults

}
