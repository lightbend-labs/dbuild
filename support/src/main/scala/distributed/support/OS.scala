package distributed.support

object OS {
  val isWindowsShell = {
    val ostype = System.getenv("OSTYPE")
    val isCygwin = ostype != null && ostype.toLowerCase.contains("cygwin")
    val isWindows = System.getProperty("os.name", "").toLowerCase.contains("windows")
    isWindows && !isCygwin
  }

  def callCmdIfWindows(cmd: String): Seq[String] =
    if (isWindowsShell) Seq("cmd", "/c", cmd)
    else Seq(cmd)
}
