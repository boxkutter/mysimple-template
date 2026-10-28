package app.simpletemplate.android

import android.content.Context
import app.simpletemplate.shared.SavedServerAddress
import app.simpletemplate.shared.ServerAddressStore

/** The platform's address the auditor set in the app (debug builds), in the app's preferences. */
class ServerAddressPreferences(context: Context) : ServerAddressStore {
    private val prefs = context.getSharedPreferences("server_address", Context.MODE_PRIVATE)

    override fun load(): SavedServerAddress? =
        prefs.getString(BASE_URL, null)?.let { SavedServerAddress(it, prefs.getString(ISSUER, null)) }

    override fun save(address: SavedServerAddress) {
        prefs.edit().putString(BASE_URL, address.baseUrl).putString(ISSUER, address.issuer).apply()
    }

    private companion object {
        const val BASE_URL = "base_url"
        const val ISSUER = "issuer"
    }
}
