package app.simpletemplate.shared.risk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/** The fixture files (a byte-identical copy of the platform's), by name. */
expect fun riskFixtureNames(): List<String>

expect fun readRiskFixture(name: String): ByteArray

expect fun sha256Hex(bytes: ByteArray): String

/**
 * The shared engine against the platform's canonical fixtures (My Simple ISO ADR 0030 §3): every
 * case gives the score, level, rank, response, likelihood, consequence and maximum tolerable
 * disruption the server gives, or the same error code; every invalid definition is refused.
 * The copy must match its MANIFEST.sha256, which is the same manifest the server checks.
 */
class RiskScoringFixturesTest {
    private fun load(name: String): JsonObject = Json.parseToJsonElement(readRiskFixture(name).decodeToString()).jsonObject

    @Test
    fun theCopyMatchesItsManifest() {
        val manifest = readRiskFixture("MANIFEST.sha256").decodeToString().lines().filter { it.isNotBlank() }.associate {
            val (digest, name) = it.split("  ", limit = 2)
            name to digest
        }
        val files = riskFixtureNames().filter { it.endsWith(".json") }
        assertEquals(manifest.keys.sorted(), files.sorted(), "the files and the manifest list different fixtures")
        for (name in files) assertEquals(manifest[name], sha256Hex(readRiskFixture(name)), "$name differs from the manifest")
    }

    @Test
    fun everyCaseScoresAsOnTheServer() {
        var cases = 0
        for (name in riskFixtureNames().filter { it.endsWith(".json") }) {
            val fixture = load(name)
            val definition = fixture["definition"] ?: continue
            RiskScoring.validate(definition)
            for (case in fixture["cases"]!!.jsonArray) {
                val c = case.jsonObject
                val label = "$name: ${c["name"]!!.jsonPrimitive.content}"
                val expected = c["expected"]!!.jsonObject
                cases++
                val error = expected["error"]
                if (error != null) {
                    val thrown = assertFailsWith<ScoringException>(label) { RiskScoring.score(definition, c["input"]!!) }
                    assertEquals(error.jsonPrimitive.content, thrown.code, "$label: ${thrown.message}")
                    continue
                }
                val rating = try {
                    RiskScoring.score(definition, c["input"]!!)
                } catch (e: ScoringException) {
                    fail("$label: ${e.code} ${e.message}")
                }
                for ((key, value) in expected) {
                    val actual: Any? = when (key) {
                        "score" -> rating.score
                        "likelihood" -> rating.likelihood
                        "consequence" -> rating.consequence
                        "level" -> rating.level.key
                        "rank" -> rating.level.rank
                        "response" -> rating.level.response
                        "intolerable_at" -> rating.intolerableAt
                        "max_tolerable_disruption" -> rating.maxTolerableDisruption?.let { mapOf("key" to it.key, "hours" to it.hours) }
                        else -> fail("$label: unknown expectation $key")
                    }
                    assertEquals(plain(value), actual, "$label: $key")
                }
            }
        }
        assertTrue(cases > 50, "only $cases fixture cases found")
    }

    @Test
    fun everyInvalidDefinitionIsRefused() {
        val items = load("invalid_definitions.json")["invalid_definitions"]!!.jsonArray
        assertTrue(items.isNotEmpty())
        for (item in items) {
            val o = item.jsonObject
            val thrown = assertFailsWith<ScoringException>(o["name"].toString()) { RiskScoring.validate(o["definition"]!!) }
            assertEquals("INVALID_DEFINITION", thrown.code)
        }
    }

    private fun plain(value: JsonElement): Any? = when (value) {
        JsonNull -> null
        is JsonPrimitive -> if (value.isString) value.content else value.int
        is JsonObject -> value.mapValues { plain(it.value) }
        is JsonArray -> value.map { plain(it) }
    }
}
