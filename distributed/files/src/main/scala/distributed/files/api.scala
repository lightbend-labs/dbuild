package distributed
package files

import java.io.File

// Requests
case class AddRepository(name: String)
case class AddFile(uri: String, file: File)
case class GetFile(uri: String)

// Respones
case class FileFound(uri: String, file: File)
case class FileNotFound(uri: String)
case class RepositoryNotFound(name: String)