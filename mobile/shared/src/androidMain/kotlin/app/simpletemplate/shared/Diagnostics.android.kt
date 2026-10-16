package app.simpletemplate.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress

actual suspend fun resolveHost(host: String): Result<List<String>> = withContext(Dispatchers.IO) {
    runCatching { InetAddress.getAllByName(host).map { it.hostAddress ?: it.toString() } }
}
