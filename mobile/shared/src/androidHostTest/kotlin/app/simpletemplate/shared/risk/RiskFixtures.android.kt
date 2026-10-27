package app.simpletemplate.shared.risk

import java.io.File
import java.security.MessageDigest

// Host tests run with the module directory (mobile/shared) as the working directory.
private val dir = File("src/commonTest/fixtures/risk_scoring")

actual fun riskFixtureNames(): List<String> =
    (dir.listFiles() ?: error("no fixtures at ${dir.absolutePath}")).filter { it.isFile }.map { it.name }.sorted()

actual fun readRiskFixture(name: String): ByteArray = File(dir, name).readBytes()

actual fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
