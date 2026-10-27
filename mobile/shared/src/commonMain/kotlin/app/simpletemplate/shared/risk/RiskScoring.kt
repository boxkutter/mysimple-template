package app.simpletemplate.shared.risk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The risk scoring engine: the fixed set of scoring methods a platform methodology configures
 * (My Simple ISO ADR 0022 §2, ADR 0030 §3). Pure Kotlin, no database and no platform code, so
 * every app made from the template rates a risk exactly as the server does.
 *
 * It is a port of `server/app/modules/risk_framework/scoring.py` in boxkutter/iso and is tested
 * against a byte-identical copy of the server's fixtures (`commonTest/fixtures/risk_scoring`,
 * see RiskScoringFixturesTest). Change the fixtures on the server first, copy them here with
 * `scripts/risk-fixtures-manifest.sh --sync`, then change both engines until both pass.
 *
 * A methodology's `definition` and a rating's input are the platform's JSON (the methodology
 * comes from `GET /v1/orgs/{org_id}/risk/methodologies/published`); integers only.
 */
object RiskScoring {
    val METHODS = listOf(
        "MULTIPLY", "MATRIX", "SUM", "FMEA_RPN", "FMEA_ACTION_PRIORITY", "THREAT_VULNERABILITY", "IMPACT_OVER_TIME",
    )
    val CONSEQUENCE_RULES = listOf("WORST", "AVERAGE_UP")
    val FACTOR_ROLES = listOf("DETECTION", "THREAT", "VULNERABILITY", "EXPOSURE", "PROBABILITY_PART", "INFO")
    val RESPONSES = listOf("ACCEPT", "TREAT", "ESCALATE", "STOP")
    val GRID_METHODS = setOf("MULTIPLY", "MATRIX", "SUM")
    private val USES_LIKELIHOOD = setOf("MULTIPLY", "MATRIX", "SUM", "FMEA_RPN", "FMEA_ACTION_PRIORITY")

    // --- reading JSON the way the server reads it --------------------------------------------

    private fun JsonElement?.int(): Int? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.content.toIntOrNull()
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.isNone(): Boolean = this == null || this is JsonNull

    /** Python's truthiness, which the server's `x or default` relies on. */
    private fun JsonElement?.truthy(): Boolean = when (this) {
        null, JsonNull -> false
        is JsonObject -> isNotEmpty()
        is JsonArray -> isNotEmpty()
        is JsonPrimitive -> when {
            isString -> content.isNotEmpty()
            booleanOrNull != null -> booleanOrNull == true
            else -> content.toDoubleOrNull()?.let { it != 0.0 } ?: true
        }
    }

    /** `obj.get(key, default)` when the key may be present with null. */
    private fun JsonObject.textOr(key: String, default: String): String? =
        if (!containsKey(key)) default else this[key].str()

    private fun invalid(message: String): Nothing = throw ScoringException("INVALID_DEFINITION", message)

    fun scaleLevels(scale: JsonElement?, name: String): List<Int> {
        val levelsElement = scale.obj()?.get("levels") as? JsonArray ?: invalid("$name needs a list of levels")
        val levels = mutableListOf<Int>()
        for (item in levelsElement) {
            val level = item.obj()?.get("level").int()
            if (level == null || level < 0) invalid("$name: every level needs a whole number")
            if (level in levels) invalid("$name: level $level appears twice")
            levels += level
        }
        if (levels.isEmpty()) invalid("$name needs at least one level")
        return levels
    }

    private fun factors(definition: JsonObject): List<JsonElement> {
        val f = definition["factors"]
        if (!f.truthy()) return emptyList()
        return f as? JsonArray ?: invalid("factors must be a list")
    }

    private fun factorByRole(definition: JsonObject, role: String): JsonObject? =
        factors(definition).firstOrNull { it.obj()?.get("role").str() == role }?.obj()

    private fun factorByKey(definition: JsonObject, key: String): JsonObject? =
        factors(definition).firstOrNull { it.obj()?.get("key").str() == key }?.obj()

    private fun consequenceOf(definition: JsonObject): JsonObject = definition["consequence"].obj() ?: JsonObject(emptyMap())

    private fun categories(definition: JsonObject): List<String> {
        val c = consequenceOf(definition)["categories"]
        if (!c.truthy()) return emptyList()
        return (c as? JsonArray ?: return emptyList()).mapNotNull { item ->
            item.obj()?.let { o -> o["key"].let { k -> (k as? JsonPrimitive)?.content ?: "None" } }
        }
    }

    private fun levelsOf(definition: JsonObject, kind: String): List<JsonObject> {
        if (kind == "OPPORTUNITY") {
            val opportunity = definition["opportunity"].obj()
                ?: throw ScoringException("NO_OPPORTUNITY_SCALE", "This methodology does not rate opportunities")
            return listOf(opportunity["levels"]).flatMap { if (it.truthy()) (it as? JsonArray).orEmpty() else emptyList() }
                .mapNotNull { it.obj() }
        }
        val levels = definition["levels"]
        return if (levels.truthy()) (levels as? JsonArray).orEmpty().mapNotNull { it.obj() } else emptyList()
    }

    // --- validating a definition -------------------------------------------------------------

    /** Check a definition: shapes, the method's settings, and that every score the method can
     *  produce falls in exactly one level. Throws [ScoringException] with INVALID_DEFINITION. */
    fun validate(definitionElement: JsonElement) {
        val definition = definitionElement.obj() ?: invalid("A definition is an object")
        val method = definition["method"].str()
        if (method == null || method !in METHODS) invalid("method must be one of ${METHODS.joinToString(", ")}")
        if (method in USES_LIKELIHOOD) scaleLevels(definition["likelihood"], "likelihood")
        val consequenceLevels = scaleLevels(definition["consequence"], "consequence")
        val consequence = consequenceOf(definition)
        if (consequence.textOr("rule", "WORST") !in CONSEQUENCE_RULES) invalid("consequence rule must be one of WORST, AVERAGE_UP")
        val keys = mutableListOf<String>()
        val cats = consequence["categories"]
        for (category in if (cats.truthy()) (cats as? JsonArray).orEmpty() else emptyList()) {
            val key = category.obj()?.get("key").str()
            if (key.isNullOrEmpty() || key in keys) invalid("every consequence category needs its own key")
            keys += key
        }
        val factorKeys = mutableListOf<String>()
        for (factor in factors(definition)) {
            val f = factor.obj()
            val key = f?.get("key").str()
            if (f == null || key.isNullOrEmpty() || key in factorKeys) invalid("every factor needs its own key")
            if (f.textOr("role", "INFO") !in FACTOR_ROLES) invalid("factor $key: unknown role")
            scaleLevels(f, "factor $key")
            factorKeys += key
        }
        val split = definition["likelihood_split"]
        if (!split.isNone()) checkSplit(definition, split!!)
        if (method == "MATRIX") checkMatrix(definition)
        if (method == "FMEA_RPN") needRole(definition, method, "DETECTION")
        if (method == "FMEA_ACTION_PRIORITY") {
            needRole(definition, method, "DETECTION")
            val rules = definition["action_priority"] as? JsonArray
            if (rules == null || rules.isEmpty()) invalid("FMEA_ACTION_PRIORITY needs action_priority")
            for (rule in rules) {
                val r = rule.obj()
                if (r == null || r["score"].int() == null) invalid("every action priority rule needs a score")
                for (part in listOf("severity", "occurrence", "detection")) {
                    val bounds = r[part]
                    if (!bounds.isNone()) {
                        val list = bounds as? JsonArray
                        if (list == null || list.size != 2 || list.any { it.int() == null }) invalid("rule $part must be [min, max]")
                    }
                }
            }
        }
        if (method == "THREAT_VULNERABILITY") {
            needRole(definition, method, "THREAT")
            needRole(definition, method, "VULNERABILITY")
        }
        if (method == "IMPACT_OVER_TIME") {
            val points = definition["time_points"] as? JsonArray
            if (points == null || points.isEmpty()) invalid("IMPACT_OVER_TIME needs time_points")
            val seen = mutableListOf<String>()
            var last = -1
            for (point in points) {
                val p = point.obj()
                val key = p?.get("key").str()
                val hours = p?.get("hours").int()
                if (key.isNullOrEmpty() || key in seen || hours == null) invalid("every time point needs a key and hours")
                if (hours <= last) invalid("time points must be in increasing hours")
                seen += key
                last = hours
            }
            val intolerable = definition["intolerable_level"]
            if (!intolerable.isNone() && intolerable.int() !in consequenceLevels) invalid("intolerable_level must be a consequence level")
        }
        checkLevels(definition["levels"], "levels")
        checkCoverage(definition, "RISK")
        val opportunity = definition["opportunity"]
        if (!opportunity.isNone()) {
            val o = opportunity.obj() ?: invalid("opportunity must be an object")
            scaleLevels(o["benefit"], "opportunity benefit")
            scaleLevels(definition["likelihood"], "likelihood")
            checkLevels(o["levels"], "opportunity levels")
            checkCoverage(definition, "OPPORTUNITY")
        }
    }

    private fun needRole(definition: JsonObject, method: String, role: String) {
        if (factorByRole(definition, role) == null) invalid("$method needs a $role factor")
    }

    private fun checkSplit(definition: JsonObject, split: JsonElement) {
        val s = split.obj() ?: invalid("likelihood_split must be an object")
        val names = s["factors"] as? JsonArray
        if (names == null || names.size != 2) invalid("likelihood_split names two factors")
        val a = factorByKey(definition, (names[0] as? JsonPrimitive)?.content ?: "")
        val b = factorByKey(definition, (names[1] as? JsonPrimitive)?.content ?: "")
        if (a == null || b == null) invalid("likelihood_split names unknown factors")
        val table = s["table"]
        val likelihood = scaleLevels(definition["likelihood"], "likelihood")
        val t = table.obj() ?: invalid("likelihood_split needs a table")
        for (x in scaleLevels(a, "factor")) for (y in scaleLevels(b, "factor")) {
            if (t["$x-$y"].int() !in likelihood) invalid("likelihood_split table has no likelihood for $x-$y")
        }
    }

    private fun checkMatrix(definition: JsonObject) {
        val matrix = definition["matrix"].obj() ?: invalid("MATRIX needs a matrix")
        for (lk in scaleLevels(definition["likelihood"], "likelihood")) for (ck in scaleLevels(definition["consequence"], "consequence")) {
            if (matrix["$lk-$ck"].int() == null) invalid("the matrix has no score for $lk-$ck")
        }
    }

    private fun checkLevels(levelsElement: JsonElement?, name: String) {
        val levels = levelsElement as? JsonArray
        if (levels == null || levels.isEmpty()) invalid("$name needs at least one level")
        val keys = mutableListOf<String>()
        val ranges = mutableListOf<Pair<Int, Int?>>()
        for (item in levels) {
            val level = item.obj() ?: invalid("$name: a level is an object")
            val key = level["key"].str()
            if (key.isNullOrEmpty() || key in keys) invalid("$name: every level needs its own key")
            keys += key
            val low = level["min_score"].int()
            val highElement = level["max_score"]
            val high = highElement.int()
            if (low == null || (!highElement.isNone() && (high == null || high < low))) invalid("$name $key: min_score..max_score")
            val rank = level["rank"].int()
            if (rank == null || rank < 1) invalid("$name $key: rank is a whole number from 1")
            if (level.textOr("response", "TREAT") !in RESPONSES) invalid("$name $key: unknown response")
            for (field in listOf("time_to_treat_days", "review_months")) {
                val value = level[field]
                if (!value.isNone() && (value.int() == null || value.int()!! < 0)) invalid("$name $key: $field")
            }
            for ((otherLow, otherHigh) in ranges) {
                if ((otherHigh == null || low <= otherHigh) && (high == null || otherLow <= high)) invalid("$name $key overlaps another level")
            }
            ranges += low to high
        }
    }

    private fun likelihoods(definition: JsonObject): List<Int> = scaleLevels(definition["likelihood"], "likelihood")

    /** Every score the method can produce from the definition's scales. */
    fun possibleScores(definition: JsonObject, kind: String = "RISK"): Set<Int> {
        val method = definition["method"].str()
        val consequence = scaleLevels(definition["consequence"], "consequence")
        if (kind == "OPPORTUNITY") {
            val benefit = scaleLevels(definition["opportunity"].obj()?.get("benefit"), "opportunity benefit")
            return likelihoods(definition).flatMap { l -> benefit.map { l * it } }.toSet()
        }
        if (method == "IMPACT_OVER_TIME") return consequence.toSet()
        if (method == "THREAT_VULNERABILITY") {
            val threat = scaleLevels(factorByRole(definition, "THREAT"), "threat")
            val vulnerability = scaleLevels(factorByRole(definition, "VULNERABILITY"), "vulnerability")
            return threat.flatMap { t -> vulnerability.flatMap { v -> consequence.map { t * v * it } } }.toSet()
        }
        val likelihood = likelihoods(definition)
        when (method) {
            "MULTIPLY" -> return likelihood.flatMap { l -> consequence.map { l * it } }.toSet()
            "SUM" -> return likelihood.flatMap { l -> consequence.map { l + it } }.toSet()
            "MATRIX" -> {
                val matrix = definition["matrix"].obj()!!
                return likelihood.flatMap { l -> consequence.map { matrix["$l-$it"].int()!! } }.toSet()
            }
        }
        val detection = scaleLevels(factorByRole(definition, "DETECTION"), "detection")
        if (method == "FMEA_RPN") return consequence.flatMap { c -> likelihood.flatMap { l -> detection.map { c * l * it } } }.toSet()
        val scores = mutableSetOf<Int>()
        for (c in consequence) for (l in likelihood) for (d in detection) {
            val rule = priorityRule(definition, c, l, d)
                ?: invalid("no action priority rule for severity $c, occurrence $l, detection $d")
            scores += rule["score"].int()!!
        }
        return scores
    }

    private fun checkCoverage(definition: JsonObject, kind: String) {
        val levels = levelsOf(definition, kind)
        for (score in possibleScores(definition, kind).sorted()) {
            if (levelFor(levels, score) == null) {
                invalid("score $score falls in none of the ${if (kind == "OPPORTUNITY") "opportunity levels" else "levels"}")
            }
        }
    }

    // --- scoring ---------------------------------------------------------------------------------

    private fun levelFor(levels: List<JsonObject>, score: Int): JsonObject? = levels.firstOrNull { level ->
        val low = level["min_score"].int()
        val highElement = level["max_score"]
        low != null && score >= low && (highElement.isNone() || score <= (highElement.int() ?: Int.MIN_VALUE))
    }

    private fun onScale(value: JsonElement?, levels: List<Int>, name: String): Int {
        val level = value.int() ?: throw ScoringException("MISSING_INPUT", "$name is needed")
        if (level !in levels) throw ScoringException("NOT_ON_SCALE", "$name $level is not a level of the methodology")
        return level
    }

    private fun overall(definition: JsonObject, values: JsonObject, name: String): Int {
        val consequence = consequenceOf(definition)
        val levels = scaleLevels(consequence, "consequence")
        val cats = categories(definition)
        if (values.isEmpty()) throw ScoringException("MISSING_INPUT", "$name needs at least one category rated")
        val rated = values.map { (key, value) ->
            if (key !in cats) throw ScoringException("UNKNOWN_CATEGORY", "$key is not a consequence category")
            onScale(value, levels, "$name $key")
        }
        if (consequence.textOr("rule", "WORST") == "AVERAGE_UP") {
            val sum = rated.sum()
            return (sum + rated.size - 1) / rated.size // the mean, rounded up (levels are never negative)
        }
        return rated.max()
    }

    private fun consequenceRating(definition: JsonObject, data: JsonObject): Int {
        val levels = scaleLevels(consequenceOf(definition), "consequence")
        val byCategory = data["consequences"]
        if (byCategory.truthy()) {
            val values = byCategory.obj() ?: throw ScoringException("MISSING_INPUT", "consequences is {category: level}")
            val derived = overall(definition, values, "consequence")
            val given = data["consequence"]
            if (!given.isNone() && given.int() != derived) {
                throw ScoringException("INCONSISTENT_CONSEQUENCE", "consequence differs from $derived, the overall of the categories")
            }
            return derived
        }
        return onScale(data["consequence"], levels, "consequence")
    }

    private fun factorValues(data: JsonObject): JsonObject =
        if (data["factors"].truthy()) data["factors"].obj() ?: JsonObject(emptyMap()) else JsonObject(emptyMap())

    private fun factorValue(definition: JsonObject, data: JsonObject, role: String): Int {
        val factor = factorByRole(definition, role)!!
        val key = factor["key"].str()!!
        return onScale(factorValues(data)[key], scaleLevels(factor, "factor"), key)
    }

    private fun likelihoodRating(definition: JsonObject, data: JsonObject): Int {
        val levels = likelihoods(definition)
        val split = definition["likelihood_split"]
        if (!split.isNone() && data["likelihood"].isNone()) {
            val s = split.obj()!!
            val values = factorValues(data)
            val parts = (s["factors"] as JsonArray).map { nameElement ->
                val name = (nameElement as JsonPrimitive).content
                val factor = factorByKey(definition, name)!!
                onScale(values[name], scaleLevels(factor, "factor"), name)
            }
            return s["table"].obj()!!["${parts[0]}-${parts[1]}"].int()!!
        }
        return onScale(data["likelihood"], levels, "likelihood")
    }

    private fun priorityRule(definition: JsonObject, severity: Int, occurrence: Int, detection: Int): JsonObject? {
        val rules = definition["action_priority"]
        for (rule in if (rules.truthy()) (rules as? JsonArray).orEmpty() else emptyList()) {
            val r = rule.obj() ?: continue
            val matches = listOf("severity" to severity, "occurrence" to occurrence, "detection" to detection).all { (part, value) ->
                val bounds = r[part]
                if (bounds.isNone()) true else {
                    val list = bounds as JsonArray
                    list[0].int()!! <= value && value <= list[1].int()!!
                }
            }
            if (matches) return r
        }
        return null
    }

    private fun checkFactors(definition: JsonObject, data: JsonObject): Map<String, Int> {
        val raw = data["factors"]
        if (!raw.truthy()) return emptyMap()
        val values = raw.obj() ?: throw ScoringException("MISSING_INPUT", "factors is {factor: level}")
        val checked = linkedMapOf<String, Int>()
        for ((key, value) in values) {
            val factor = factorByKey(definition, key) ?: throw ScoringException("UNKNOWN_FACTOR", "$key is not a factor of the methodology")
            checked[key] = onScale(value, scaleLevels(factor, "factor"), key)
        }
        return checked
    }

    /** Rate one input with a definition: the score, the likelihood and consequence used, the
     *  level, and for impact over time the maximum tolerable disruption. */
    fun score(definitionElement: JsonElement, input: JsonElement): Rating {
        val definition = definitionElement.obj() ?: invalid("A definition is an object")
        val data = input.obj() ?: JsonObject(emptyMap())
        val method = definition["method"].str()
        val kind = if (data["kind"].truthy()) (data["kind"] as? JsonPrimitive)?.content ?: "RISK" else "RISK"
        val factors = checkFactors(definition, data)
        var likelihood: Int? = null
        var consequence: Int? = null
        var benefit: Int? = null
        var impacts: Map<String, Int>? = null
        var intolerableAt: String? = null
        var mtd: MaxTolerableDisruption? = null
        val value: Int
        if (kind == "OPPORTUNITY") {
            val opportunity = definition["opportunity"].obj()
                ?: throw ScoringException("NO_OPPORTUNITY_SCALE", "This methodology does not rate opportunities")
            likelihood = likelihoodRating(definition, data)
            benefit = onScale(data["benefit"], scaleLevels(opportunity["benefit"], "benefit"), "benefit")
            value = likelihood * benefit
        } else if (method == "IMPACT_OVER_TIME") {
            val levels = scaleLevels(consequenceOf(definition), "consequence")
            val given = data["time_impacts"].obj() ?: throw ScoringException("MISSING_INPUT", "time_impacts is needed")
            val points = (definition["time_points"] as JsonArray).map { it.obj()!! }
            val rated = points.map { point ->
                val key = point["key"].str()!!
                val v = given[key]
                if (v.isNone()) throw ScoringException("MISSING_INPUT", "the impact at $key is needed")
                point to (if (v is JsonObject) overall(definition, v, "impact at $key") else onScale(v, levels, "impact at $key"))
            }
            val known = points.map { it["key"].str()!! }.toSet()
            val unknown = given.keys.filter { it !in known }.sorted()
            if (unknown.isNotEmpty()) throw ScoringException("UNKNOWN_TIME_POINT", "${unknown.first()} is not a time point")
            val intolerable = definition["intolerable_level"].int()
            if (intolerable != null) {
                var previous: JsonObject? = null
                var reached: JsonObject? = null
                for ((point, impact) in rated) {
                    if (impact >= intolerable) {
                        reached = point
                        break
                    }
                    previous = point
                }
                if (reached != null) {
                    intolerableAt = reached["key"].str()
                    mtd = if (previous != null) MaxTolerableDisruption(previous["key"].str(), previous["hours"].int()!!) else MaxTolerableDisruption(null, 0)
                }
            }
            impacts = rated.associate { (point, impact) -> point["key"].str()!! to impact }
            consequence = rated.maxOf { it.second }
            value = consequence
        } else {
            consequence = consequenceRating(definition, data)
            if (method == "THREAT_VULNERABILITY") {
                val threat = factorValue(definition, data, "THREAT")
                val vulnerability = factorValue(definition, data, "VULNERABILITY")
                value = threat * vulnerability * consequence
            } else {
                likelihood = likelihoodRating(definition, data)
                value = when (method) {
                    "MULTIPLY" -> likelihood * consequence
                    "SUM" -> likelihood + consequence
                    "MATRIX" -> definition["matrix"].obj()?.get("$likelihood-$consequence").int()
                        ?: throw ScoringException("NO_MATRIX_SCORE", "the matrix has no score for $likelihood-$consequence")
                    else -> {
                        val detection = factorValue(definition, data, "DETECTION")
                        if (method == "FMEA_RPN") {
                            consequence * likelihood * detection
                        } else {
                            val rule = priorityRule(definition, consequence, likelihood, detection)
                                ?: throw ScoringException("NO_PRIORITY_RULE", "no action priority rule matches these ratings")
                            rule["score"].int()!!
                        }
                    }
                }
            }
        }
        val level = levelFor(levelsOf(definition, kind), value)
            ?: throw ScoringException("OUTSIDE_LEVELS", "score $value falls in none of the levels")
        return Rating(
            score = value,
            likelihood = likelihood,
            consequence = consequence,
            benefit = benefit,
            factors = factors,
            impacts = impacts,
            intolerableAt = intolerableAt,
            maxTolerableDisruption = mtd,
            level = RatedLevel(
                key = level["key"].str()!!,
                label = level["label"].str()?.takeIf { it.isNotEmpty() } ?: level["key"].str()!!,
                colour = level["colour"].str(),
                rank = level["rank"].int()!!,
                response = level.textOr("response", "TREAT") ?: "TREAT",
                timeToTreatDays = level["time_to_treat_days"].int(),
                reviewMonths = level["review_months"].int(),
            ),
        )
    }
}

/** A definition or an input the engine cannot score; [code] is shared with the server. */
class ScoringException(val code: String, message: String) : IllegalArgumentException(message)

data class RatedLevel(
    val key: String,
    val label: String,
    val colour: String?,
    val rank: Int,
    val response: String,
    val timeToTreatDays: Int?,
    val reviewMonths: Int?,
)

data class MaxTolerableDisruption(val key: String?, val hours: Int)

data class Rating(
    val score: Int,
    val likelihood: Int?,
    val consequence: Int?,
    val benefit: Int?,
    val factors: Map<String, Int>,
    val impacts: Map<String, Int>?,
    val intolerableAt: String?,
    val maxTolerableDisruption: MaxTolerableDisruption?,
    val level: RatedLevel,
)
