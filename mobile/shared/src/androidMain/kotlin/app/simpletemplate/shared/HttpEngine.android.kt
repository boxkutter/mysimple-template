package app.simpletemplate.shared

import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import okhttp3.Dns
import java.net.InetAddress

actual fun platformHttpEngine(): HttpClientEngine = OkHttp.create {
    if (PlatformNetwork.resolveLocalhostToLoopback) {
        config {
            dns { host ->
                if (host == "localhost" || host.endsWith(".localhost")) listOf(InetAddress.getByName("127.0.0.1"))
                else Dns.SYSTEM.lookup(host)
            }
        }
    }
}
