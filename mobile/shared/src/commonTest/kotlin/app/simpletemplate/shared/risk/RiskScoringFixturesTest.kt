package app.simpletemplate.shared.risk

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.long
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
 * A fixture with `criteria` rates on the framework's core grid too (ADR 0031 §4): `level` and
 * `rank` are then the common level's, `native_level` the methodology's own; an estimate's bands
 * and annual expectation are checked as well (ADR 0033). Invalid criteria, incomplete criteria
 * and methodologies that do not fit their criteria are refused with the server's codes.
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
            val criteria = fixture["criteria"]
            RiskScoring.validate(definition)
            if (criteria != null) {
                RiskScoring.validateCriteria(criteria)
                RiskScoring.checkCompatible(definition, criteria)
            }
            for (case in fixture["cases"]!!.jsonArray) {
                val c = case.jsonObject
                val label = "$name: ${c["name"]!!.jsonPrimitive.content}"
                val expected = c["expected"]!!.jsonObject
                cases++
                val error = expected["error"]
                if (error != null) {
                    val thrown = assertFailsWith<ScoringException>(label) { RiskScoring.rate(definition, c["input"]!!, criteria) }
                    assertEquals(error.jsonPrimitive.content, thrown.code, "$label: ${thrown.message}")
                    continue
                }
                val rating = try {
                    RiskScoring.rate(definition, c["input"]!!, criteria)
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
                        "native_level" -> rating.nativeLevel?.key
                        "common_likelihood" -> rating.common?.likelihood
                        "common_consequence" -> rating.common?.consequence
                        "common_score" -> rating.common?.score
                        "estimate_likelihood" -> rating.estimate?.likelihood
                        "estimate_consequence" -> rating.estimate?.consequence
                        "annual_expectation" -> rating.estimate?.annualExpectation
                        "annual_expectation_low" -> rating.estimate?.annualExpectationLow
                        "annual_expectation_high" -> rating.estimate?.annualExpectationHigh
                        else -> fail("$label: unknown expectation $key")
                    }
                    assertEquals(plain(value), whole(actual), "$label: $key")
                }
            }
        }
        assertTrue(cases > 70, "only $cases fixture cases found")
        println("risk scoring fixtures: $cases cases")
    }

    @Test
    fun everyCriteriaFixtureIsChecked() {
        val names = riskFixtureNames().filter { it.endsWith(".json") }
        val withCriteria = names.filter { load(it)["criteria"] != null }
        assertTrue(withCriteria.size >= 6, "only ${withCriteria.size} fixtures with criteria")
        val known = setOf("name", "description", "definition", "criteria", "cases", "invalid_definitions", "invalid_criteria", "completeness", "incompatible")
        for (name in names) {
            val unknown = load(name).keys - known
            assertTrue(unknown.isEmpty(), "$name has parts this test does not run: $unknown")
        }
    }

    @Test
    fun everyInvalidCriteriaIsRefused() {
        val data = load("invalid_criteria.json")
        val invalid = data["invalid_criteria"]!!.jsonArray
        assertTrue(invalid.isNotEmpty())
        for (item in invalid) {
            val o = item.jsonObject
            val thrown = assertFailsWith<ScoringException>(o["name"].toString()) { RiskScoring.validateCriteria(o["criteria"]!!) }
            assertEquals(o["code"]!!.jsonPrimitive.content, thrown.code, "${o["name"]}: ${thrown.message}")
        }
        val completeness = data["completeness"]!!.jsonArray
        assertTrue(completeness.isNotEmpty())
        for (item in completeness) {
            val o = item.jsonObject
            val criteria = o["criteria"]!!
            val enabled = o["enabled"]!!.jsonArray.map { it.jsonPrimitive.content }
            RiskScoring.validateCriteria(criteria) // a draft may be incomplete
            val code = o["code"]
            if (code == null || code is JsonNull) {
                RiskScoring.validateCriteria(criteria, complete = true, enabled = enabled)
                continue
            }
            val thrown = assertFailsWith<ScoringException>(o["name"].toString()) {
                RiskScoring.validateCriteria(criteria, complete = true, enabled = enabled)
            }
            assertEquals(code.jsonPrimitive.content, thrown.code, "${o["name"]}: ${thrown.message}")
        }
        val incompatible = data["incompatible"]!!.jsonArray
        assertTrue(incompatible.isNotEmpty())
        for (item in incompatible) {
            val o = item.jsonObject
            RiskScoring.validate(o["definition"]!!)
            val thrown = assertFailsWith<ScoringException>(o["name"].toString()) {
                RiskScoring.checkCompatible(o["definition"]!!, o["criteria"]!!)
            }
            assertEquals(o["code"]!!.jsonPrimitive.content, thrown.code, "${o["name"]}: ${thrown.message}")
        }
    }

    /** Whole numbers compared as Long (an estimate's amounts are Long, the levels Int). */
    private fun whole(value: Any?): Any? = when (value) {
        is Int -> value.toLong()
        is Map<*, *> -> value.mapValues { whole(it.value) }
        is List<*> -> value.map { whole(it) }
        else -> value
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
        is JsonPrimitive -> if (value.isString) value.content else value.long
        is JsonObject -> value.mapValues { plain(it.value) }
        is JsonArray -> value.map { plain(it) }
    }
}
