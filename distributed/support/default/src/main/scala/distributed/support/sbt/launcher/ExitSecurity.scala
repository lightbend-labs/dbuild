package distributed.support.sbt.launcher

import java.security.Permission


case class SystemExitCalled() extends SecurityException("System.exit called")
/** Custom security manager that prevents system.exit */
class ExitManager(val original: SecurityManager) extends SecurityManager {

  /** Deny permission to exit the VM. */
  override def checkExit(status: Int): Unit = throw(new SystemExitCalled())

  // Allow pretty much everything here....
  // TODO - more restrictions on 3rd party code?
  override def checkPermission(perm: Permission): Unit = ()
}

object ExitSecurity {
  /** Temporarily installs a security manager that disables System.exit and instead throws exceptions. */
  def withNoExits[A](f: => A): Option[A] = {
    val sm = new ExitManager( System.getSecurityManager() );
    System setSecurityManager sm
    try Some(f)
    catch { case t: SystemExitCalled => None }
    finally System setSecurityManager sm.original
  }
}