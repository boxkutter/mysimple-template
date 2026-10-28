package app.simpletemplate.shared.auth

import android.content.Context
import app.simpletemplate.shared.KeystoreCipher
import app.simpletemplate.shared.writeAtomically
import kotlinx.serialization.json.Json
import java.io.File

/** Fields an earlier version stored and this one no longer has are skipped, not an error. */
private val json = Json { ignoreUnknownKeys = true }

/**
 * Keeps the sessions (the open workspace, the local workspace and the account, with its refresh
 * and ID tokens) in files encrypted with a Keystore key, outside backups.
 */
class EncryptedSessionStore(context: Context) : SessionStore {
    private val file = File(context.noBackupFilesDir, "session.bin")
    private val localFile = File(context.noBackupFilesDir, "local-workspace.bin")
    private val accountFile = File(context.noBackupFilesDir, "account.bin")
    private val cipher = KeystoreCipher("simpletemplate.session")

    override fun load(): Session? = read(file)

    override fun save(session: Session) = write(file, session)

    override fun clear() {
        file.delete()
    }

    override fun loadLocal(): Session? = read(localFile)

    override fun saveLocal(session: Session) = write(localFile, session)

    override fun clearLocal() {
        localFile.delete()
    }

    override fun loadAccount(): Session? = read(accountFile)

    override fun saveAccount(session: Session) = write(accountFile, session)

    override fun clearAccount() {
        accountFile.delete()
    }

    private fun read(from: File): Session? {
        if (!from.exists()) return null
        return json.decodeFromString<Session>(cipher.decrypt(from.readBytes()).decodeToString())
    }

    private fun write(to: File, session: Session) {
        to.writeAtomically(cipher.encrypt(json.encodeToString(session).encodeToByteArray()))
    }
}
