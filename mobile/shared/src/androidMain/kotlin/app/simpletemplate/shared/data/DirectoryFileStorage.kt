package app.simpletemplate.shared.data

import app.simpletemplate.shared.sync.FileStorage
import app.simpletemplate.shared.writeAtomically
import java.io.File

/**
 * Files of synced records in the app's private storage (never the photo library or shared
 * storage). Names come from the records; anything that is not a plain file name is refused.
 */
class DirectoryFileStorage(private val dir: File) : FileStorage {
    override fun read(fileName: String): ByteArray? = file(fileName)?.takeIf { it.exists() }?.readBytes()

    override fun write(fileName: String, bytes: ByteArray) {
        val target = file(fileName) ?: error("Not a file name: $fileName")
        dir.mkdirs()
        target.writeAtomically(bytes)
    }

    override fun delete(fileNames: List<String>) {
        fileNames.forEach { name -> file(name)?.delete() }
    }

    private fun file(name: String): File? =
        name.takeIf { it.isNotEmpty() && '/' !in it && '\\' !in it && it != "." && it != ".." }?.let { File(dir, it) }
}
