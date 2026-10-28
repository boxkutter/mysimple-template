package app.simpletemplate.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ServerAddressTest {
    private class MemoryStore(var saved: SavedServerAddress? = null) : ServerAddressStore {
        override fun load() = saved
        override fun save(address: SavedServerAddress) { saved = address }
    }

    @Test
    fun anAddressIsTypedAsTheAuditorWouldTypeIt() {
        assertEquals("http://192.168.1.10:8081", normaliseServerAddress(" 192.168.1.10:8081/ "))
        assertEquals("https://api.example.eu", normaliseServerAddress("https://api.example.eu"))
        assertEquals("http://dev.local/api", normaliseServerAddress("http://dev.local/api/"))
        assertNull(normaliseServerAddress(""))
        assertNull(normaliseServerAddress("ftp://host"))
        assertNull(normaliseServerAddress("http://"))
        assertNull(normaliseServerAddress("192.168.1.10 8081"))
    }

    @Test
    fun aChangedAddressAndTheIssuerThePlatformNamedAreKept() {
        val store = MemoryStore()
        val server = ServerAddress(store, "https://api.default", "https://auth.default", editable = true)
        server.change("192.168.1.10:8081")
        server.issuerFound("http://192.168.1.10:8082/")
        assertEquals(SavedServerAddress("http://192.168.1.10:8081", "http://192.168.1.10:8082"), store.saved)

        val again = ServerAddress(store, "https://api.default", "https://auth.default", editable = true)
        assertEquals("http://192.168.1.10:8081", again.baseUrl.value)
        assertEquals("http://192.168.1.10:8082", again.issuer.value)
    }

    @Test
    fun aReleaseBuildKeepsItsOwnAddress() {
        val store = MemoryStore(SavedServerAddress("http://192.168.1.10:8081", "http://192.168.1.10:8082"))
        val server = ServerAddress(store, "https://api.default", "https://auth.default", editable = false)
        assertEquals("https://api.default", server.baseUrl.value)
        assertEquals("https://auth.default", server.issuer.value)
        assertFailsWith<IllegalStateException> { server.change("192.168.1.11:8081") }
    }
}
