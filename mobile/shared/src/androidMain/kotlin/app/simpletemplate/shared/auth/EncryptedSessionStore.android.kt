package app.simpletemplate.shared.auth

import android.content.Context
import app.simpletemplate.shared.KeystoreCipher
import app.simpletemplate.shared.writeAtomically
import kotlinx.serialization.json.Json
import java.io.File

/** Keeps the session (incl. refresh token) in a file encrypted with a Keystore key. */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private val localFile = File(context.noBackupFilesDir, "local-workspace.bin")
    private val cipher = KeystoreCipher("simpletemplate.session")

    override fun load(): Session? {
        if (!file.exists()) return null
        return Json.decodeFromString<Session>(cipher.decrypt(file.readBytes()).decodeToString())
    }

    override fun save(session: Session) {
        file.writeAtomically(cipher.encrypt(Json.encodeToString(session).encodeToByteArray()))
    }

    override fun clear() {
        file.delete()
    }

    override fun loadLocal(): Session? {
        if (!localFile.exists()) return null
        return Json.decodeFromString<Session>(cipher.decrypt(localFile.readBytes()).decodeToString())
    }

    override fun saveLocal(session: Session) {
        localFile.writeAtomically(cipher.encrypt(Json.encodeToString(session).encodeToByteArray()))
    }
}
