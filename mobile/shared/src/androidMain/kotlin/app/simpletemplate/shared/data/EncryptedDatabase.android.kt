package app.simpletemplate.shared.data

import android.content.Context
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.simpletemplate.shared.KeystoreCipher
import app.simpletemplate.shared.db.SimpleTemplateDatabase
import app.simpletemplate.shared.writeAtomically
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import java.io.File
import java.security.SecureRandom

/**
 * Opens the local database encrypted with SQLCipher. The database key is random,
 * stored only in wrapped form, and unwrapped with a non-exportable Android Keystore key.
 */
object EncryptedDatabase {
    private const val DB_NAME = "simpletemplate.db"

    fun open(context: Context): LocalStore {
        System.loadLibrary("sqlcipher")
        val key = databaseKey(context)
        // Raw-key form (x'…'): SQLCipher uses the key as is and skips its slow passphrase KDF.
        val passphrase = "x'${key.toHex()}'".encodeToByteArray()
        key.fill(0)
        val driver = AndroidSqliteDriver(
            schema = SimpleTemplateDatabase.Schema,
            context = context,
            name = DB_NAME,
            factory = SupportOpenHelperFactory(passphrase),
        )
        return LocalStore(SimpleTemplateDatabase(driver))
    }

    private fun databaseKey(context: Context): ByteArray {
        // noBackupFilesDir: the wrapped key is useless on another device anyway.
        val file = File(context.noBackupFilesDir, "db.key")
        val cipher = KeystoreCipher("simpletemplate.db")
        if (file.exists()) return cipher.decrypt(file.readBytes())
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }
        file.writeAtomically(cipher.encrypt(key))
        return key
    }
}

private fun ByteArray.toHex(): String = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
