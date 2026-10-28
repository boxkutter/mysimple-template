package app.simpletemplate.shared.risk

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject

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
 *
 * Under the framework's risk criteria (ADR 0031, the published methodology's `criteria`), [rate]
 * scores on the methodology and then on the criteria's core grid: the result's level is the
 * common one, the methodology's own kept as [Rating.nativeLevel]. An estimate (ADR 0033) stands in
 * for the likelihood and one category's level when the methodology's `assessment_mode` allows it.
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

    /** The core grid's methods under criteria; how a methodology may be rated (ADR 0033); the
     *  units of a category's value bands; the sizes a criteria scale may have. */
    val CRITERIA_METHODS = listOf("MULTIPLY", "MATRIX", "SUM")
    val ASSESSMENT_MODES = listOf("QUALITATIVE", "QUANTITATIVE", "EITHER")
    val DEPTHS = listOf("QUICK", "DETAILED")
    val REDUCTION_POLICIES = listOf("NONE", "AS_FAR_AS_POSSIBLE")
    val VALUE_UNITS = listOf("GBP_MINOR", "HOURS", "PEOPLE", "KWH")
    const val MIN_SCALE = 3
    const val MAX_SCALE = 10

    // --- reading JSON the way the server reads it --------------------------------------------

    private fun JsonElement?.int(): Int? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.content.toIntOrNull()
    }

    /** A whole number that may be large (estimates and value bands are in minor units). */
    private fun JsonElement?.long(): Long? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString || p.booleanOrNull != null) return null
        return p.content.toLongOrNull()
    }

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonElement?.isNone(): Boolean = this == null || this is JsonNull

    /** Python's `x or default`. */
    private fun JsonElement?.or(default: JsonElement): JsonElement = if (truthy()) this!! else default

    /** Python's `str(x or "").strip()`. */
    private fun JsonElement?.text(): String = when {
        !truthy() -> ""
        this is JsonPrimitive -> content.trim()
        else -> toString().trim()
    }

    /** Python's `str(x)` of a JSON key or value. */
    private fun JsonElement?.key(): String = when (this) {
        null, JsonNull -> "None"
        is JsonPrimitive -> content
        else -> toString()
    }

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
        if (definition.textOr("assessment_mode", "QUALITATIVE") !in ASSESSMENT_MODES) {
            invalid("assessment_mode is one of ${ASSESSMENT_MODES.joinToString(", ")}")
        }
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
        if (definition.textOr("depth", "DETAILED") !in DEPTHS) invalid("depth is QUICK or DETAILED")
        if (definition.textOr("reduction_policy", "NONE") !in REDUCTION_POLICIES) invalid("reduction_policy is NONE or AS_FAR_AS_POSSIBLE")
        val significant = definition["significant_from_rank"]
        if (!significant.isNone() && (significant.int() == null || significant.int()!! < 1)) {
            invalid("significant_from_rank is a rank from 1 (or left out)")
        }
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
            level = ratedLevel(level),
        )
    }

    private fun ratedLevel(level: JsonObject) = RatedLevel(
        key = level["key"].str()!!,
        label = level["label"].str()?.takeIf { it.isNotEmpty() } ?: level["key"].str()!!,
        colour = level["colour"].str(),
        rank = level["rank"].int()!!,
        response = level.textOr("response", "TREAT") ?: "TREAT",
        timeToTreatDays = level["time_to_treat_days"].int(),
        reviewMonths = level["review_months"].int(),
    )

    // --- risk criteria in the framework, and methodologies mapped onto them (ADR 0031) --------
    //
    // **Criteria** (a framework version's `criteria`, and the published methodology's) hold the
    // organisation's common scales: `likelihood` and `consequence` (3 to 10 levels each, numbered
    // from 1), the consequence `categories` (a descriptor per level, `enabled_by`: null or a
    // capability, and optionally `unit` with `value_from` bands), the core grid (`method`
    // MULTIPLY, MATRIX or SUM, `matrix`) and the core `levels`. A methodology under criteria
    // carries `mapping`: `likelihood` and `consequence` ({"<native level>": common level}; absent
    // means identity) and, for threat × vulnerability, `likelihood_from`
    // ({"factors": [a, b], "table": {"<a>-<b>": common level}}).

    /** The core grid of a set of criteria as a definition the methods above can score. */
    fun criteriaDefinition(criteriaElement: JsonElement): JsonObject {
        val criteria = criteriaElement.obj() ?: JsonObject(emptyMap())
        val consequence = criteria["consequence"].obj() ?: JsonObject(emptyMap())
        val method = criteria["method"].or(JsonPrimitive("MULTIPLY"))
        return buildJsonObject {
            put("method", method)
            put("likelihood", criteria["likelihood"] ?: JsonNull)
            put(
                "consequence",
                buildJsonObject {
                    put("label", consequence["label"].or(JsonPrimitive("Consequence")))
                    put("levels", consequence["levels"] ?: JsonNull)
                    put("categories", consequence["categories"].or(JsonArray(emptyList())))
                    put("rule", consequence["rule"].or(JsonPrimitive("WORST")))
                },
            )
            put("factors", JsonArray(emptyList()))
            put("levels", criteria["levels"] ?: JsonNull)
            if (method == JsonPrimitive("MATRIX")) put("matrix", criteria["matrix"].or(JsonObject(emptyMap())))
        }
    }

    private fun checkSize(scale: JsonElement?, name: String): List<Int> {
        val levels = scaleLevels(scale, name)
        if (levels.size !in MIN_SCALE..MAX_SCALE) {
            throw ScoringException("SCALE_SIZE", "$name needs from $MIN_SCALE to $MAX_SCALE levels, not ${levels.size}")
        }
        if (levels.sorted() != (1..levels.size).toList()) throw ScoringException("SCALE_SIZE", "$name levels are numbered from 1 without gaps")
        return levels
    }

    private fun items(element: JsonElement?): List<JsonElement> =
        if (element.truthy()) (element as? JsonArray).orEmpty() else emptyList()

    /** Check a set of criteria; [complete] (at approval) also needs every likelihood level
     *  described and every category that is always on, or switched on by [enabled] (capability
     *  keys), to describe every level. Codes: INVALID_DEFINITION, SCALE_SIZE, INVALID_BANDS. */
    fun validateCriteria(criteriaElement: JsonElement, complete: Boolean = false, enabled: Collection<String> = emptyList()) {
        val criteria = criteriaElement.obj() ?: invalid("the criteria are an object")
        if (criteria.textOr("method", "MULTIPLY") !in CRITERIA_METHODS) {
            invalid("the core grid's method is one of ${CRITERIA_METHODS.joinToString(", ")}")
        }
        checkSize(criteria["likelihood"], "likelihood")
        val levels = checkSize(criteria["consequence"], "consequence")
        val consequence = criteria["consequence"].obj()!!
        val keys = mutableSetOf<String>()
        for (category in items(consequence["categories"])) {
            val key = category.obj()?.get("key").str()
            if (key.isNullOrEmpty() || key in keys) invalid("every category needs its own key")
            val c = category.obj()!!
            if (c["label"].text().isEmpty()) invalid("category $key needs a label")
            val descriptors = c["descriptors"]
            if (descriptors.truthy()) {
                val d = descriptors.obj() ?: invalid("category $key: descriptors name consequence levels")
                if (d.keys.any { k -> k.isEmpty() || !k.all { it.isDigit() } || k.toIntOrNull() !in levels }) {
                    invalid("category $key: descriptors name consequence levels")
                }
            }
            keys += key
        }
        validate(criteriaDefinition(criteria))
        checkBands(criteria)
        if (!complete) return
        val on = enabled.toSet()
        for (level in items(criteria["likelihood"].obj()!!["levels"]).mapNotNull { it.obj() }) {
            if (level["description"].text().isEmpty()) invalid("likelihood ${level["level"].key()} needs a description")
        }
        for (category in items(consequence["categories"]).mapNotNull { it.obj() }) {
            if (category["enabled_by"].truthy() && category["enabled_by"].key() !in on) continue
            val descriptors = category["descriptors"].obj() ?: JsonObject(emptyMap())
            val missing = levels.filter { descriptors[it.toString()].text().isEmpty() }
            if (missing.isNotEmpty()) invalid("category ${category["key"].key()} needs a description for level ${missing.first()}")
        }
    }

    private fun bandsError(message: String): Nothing = throw ScoringException("INVALID_BANDS", message)

    /** Band starts: whole numbers, the first 0, each above the one below. */
    private fun checkRising(starts: List<JsonElement?>, name: String) {
        var previous: Long? = null
        for (element in starts) {
            val start = element.long()
            if (start == null || start < 0 || (previous == null && start != 0L)) bandsError("$name: the lowest band starts at 0")
            if (previous != null && start <= previous) bandsError("$name: each band starts above the one below")
            previous = start
        }
    }

    /** Optional bands for quantitative estimates (ADR 0033): `frequency_from` on every likelihood
     *  level or none (events per 1000 years), and per category a `unit` with `value_from` for
     *  every consequence level. */
    private fun checkBands(criteria: JsonObject) {
        val levels = items(criteria["likelihood"].obj()!!["levels"]).map { it.obj()!! }.sortedBy { it["level"].int()!! }
        val starts = levels.map { it["frequency_from"] }
        if (starts.any { !it.isNone() }) {
            if (starts.any { it.isNone() }) bandsError("every likelihood level needs a frequency band")
            checkRising(starts, "likelihood")
        }
        val numbers = scaleLevels(criteria["consequence"], "consequence").sorted()
        for (category in items(criteria["consequence"].obj()!!["categories"]).map { it.obj()!! }) {
            val bands = category["value_from"]
            if (bands.isNone()) continue
            val name = "category ${category["key"].key()}"
            if (category["unit"].str() !in VALUE_UNITS) bandsError("$name: unit is one of ${VALUE_UNITS.joinToString(", ")}")
            val b = bands.obj()
            if (b == null || b.keys.sorted() != numbers.map { it.toString() }.sorted()) bandsError("$name: a band for every consequence level")
            checkRising(numbers.map { b[it.toString()] }, name)
        }
    }

    private fun criteriaCategoryList(criteria: JsonObject): List<JsonObject> =
        items(criteria["consequence"].obj()?.get("categories")).mapNotNull { it.obj() }

    /** The categories an estimate can fall into (they have value bands). */
    fun bandedCategories(criteria: JsonElement?): List<String> {
        val c = criteria.obj()?.takeIf { it.isNotEmpty() } ?: return emptyList()
        return criteriaCategoryList(c).filter { !it["value_from"].isNone() }.map { it["key"].key() }
    }

    /** Criteria with frequency bands on likelihood and value bands on at least one category. */
    fun quantitativeReady(criteria: JsonElement?): Boolean {
        val c = criteria.obj()?.takeIf { it.isNotEmpty() } ?: return false
        val levels = items(c["likelihood"].obj()?.get("levels")).mapNotNull { it.obj() }
        return levels.all { !it["frequency_from"].isNone() } && bandedCategories(c).isNotEmpty()
    }

    private fun criteriaCategories(criteria: JsonObject): Map<String, JsonObject> =
        criteriaCategoryList(criteria).associateBy { it["key"].key() }

    private fun mapping(definition: JsonObject, name: String): JsonObject? =
        definition["mapping"].takeIf { it.truthy() }.obj()?.get(name).obj()

    private fun checkMap(native: List<Int>, common: List<Int>, mapping: JsonObject?, name: String) {
        if (mapping == null) {
            if (native.sorted() != common.sorted()) {
                throw ScoringException("UNMAPPED_LEVEL", "the $name scale differs from the framework's: map each level")
            }
            return
        }
        var previous: Int? = null
        for (level in native.sorted()) {
            val target = mapping[level.toString()].int()
            if (target == null || target !in common) {
                throw ScoringException("UNMAPPED_LEVEL", "$name $level maps onto none of the framework's levels")
            }
            if (previous != null && target < previous) {
                throw ScoringException("NON_MONOTONIC_MAPPING", "$name $level maps lower than the level below it")
            }
            previous = target
        }
    }

    /** A methodology fits the criteria when every native level maps onto a common level, the
     *  mappings never go down, its categories are the criteria's (ADR 0031 §3), and an estimate
     *  it allows can fall into the criteria's bands (ADR 0033). Codes: UNMAPPED_LEVEL,
     *  NON_MONOTONIC_MAPPING, CATEGORY_NOT_IN_FRAMEWORK, NOT_QUANTITATIVE, NO_BANDS. */
    fun checkCompatible(definitionElement: JsonElement, criteriaElement: JsonElement) {
        val definition = definitionElement.obj() ?: invalid("A definition is an object")
        val criteria = criteriaElement.obj() ?: invalid("the criteria are an object")
        val method = definition["method"].str()
        val commonL = scaleLevels(criteria["likelihood"], "likelihood")
        val commonC = scaleLevels(criteria["consequence"], "consequence")
        if (method in USES_LIKELIHOOD) {
            checkMap(likelihoods(definition), commonL, mapping(definition, "likelihood"), "likelihood")
        } else if (method == "THREAT_VULNERABILITY") {
            val source = mapping(definition, "likelihood_from")
                ?: throw ScoringException("UNMAPPED_LEVEL", "map threat and vulnerability onto the framework's likelihood")
            val parts = items(source["factors"]).map { factorByKey(definition, it.key()) }
            if (parts.size != 2 || parts.any { it == null }) throw ScoringException("UNMAPPED_LEVEL", "likelihood_from names two of the factors")
            val aLevels = scaleLevels(parts[0], "factor").sorted()
            val bLevels = scaleLevels(parts[1], "factor").sorted()
            val table = source["table"].takeIf { it.truthy() }.obj() ?: JsonObject(emptyMap())
            for (a in aLevels) for (b in bLevels) {
                val target = table["$a-$b"].int()
                if (target == null || target !in commonL) throw ScoringException("UNMAPPED_LEVEL", "likelihood_from $a-$b is not mapped")
            }
            for (a in aLevels) for (b in bLevels) {
                val here = table["$a-$b"].int()!!
                for ((na, nb) in listOf(a + 1 to b, a to b + 1)) {
                    val next = table["$na-$nb"].int() ?: continue
                    if (next < here) throw ScoringException("NON_MONOTONIC_MAPPING", "likelihood_from $na-$nb is lower than $a-$b")
                }
            }
        }
        checkMap(scaleLevels(definition["consequence"], "consequence"), commonC, mapping(definition, "consequence"), "consequence")
        val known = criteriaCategories(criteria)
        for (key in categories(definition)) {
            if (key !in known) throw ScoringException("CATEGORY_NOT_IN_FRAMEWORK", "$key is not one of the framework's categories")
        }
        if (definition.textOr("assessment_mode", "QUALITATIVE") != "QUALITATIVE") {
            val direct = method in GRID_METHODS && !definition["likelihood_split"].truthy() &&
                mapping(definition, "likelihood").let { it == null || it.isEmpty() } &&
                mapping(definition, "consequence").let { it == null || it.isEmpty() }
            if (!direct) {
                throw ScoringException(
                    "NOT_QUANTITATIVE",
                    "an estimate needs a likelihood × consequence methodology on the framework's own scales",
                )
            }
            if (!quantitativeReady(criteria)) throw ScoringException("NO_BANDS", "the framework's criteria have no frequency and value bands yet")
            if (bandedCategories(criteria).intersect(categories(definition).toSet()).isEmpty()) {
                throw ScoringException("NO_BANDS", "none of the methodology's categories has value bands to estimate in")
            }
        }
    }

    private fun mapped(definition: JsonObject, name: String, value: Int): Int =
        mapping(definition, name)?.let { it[value.toString()].int()!! } ?: value

    /** The rating on the framework's core grid from a native rating (ADR 0031 §4). */
    private fun commonResult(definition: JsonObject, criteria: JsonObject, data: JsonObject, native: Rating): CommonRating {
        checkCompatible(definition, criteria)
        val method = definition["method"].str()
        val likelihood = when (method) {
            "THREAT_VULNERABILITY" -> {
                val source = mapping(definition, "likelihood_from")!!
                val (a, b) = (source["factors"] as JsonArray).map { k ->
                    native.factors[k.key()] ?: throw ScoringException("MISSING_INPUT", "${k.key()} is needed")
                }
                source["table"].obj()!!["$a-$b"].int()!!
            }
            "IMPACT_OVER_TIME" -> onScale(data["likelihood"], scaleLevels(criteria["likelihood"], "likelihood"), "likelihood")
            else -> mapped(definition, "likelihood", native.likelihood!!)
        }
        val core = criteriaDefinition(criteria)
        val byCategory = if (method != "IMPACT_OVER_TIME") data["consequences"] else null
        val consequences = if (byCategory.truthy()) {
            byCategory.obj()!!.mapValues { (_, v) -> mapped(definition, "consequence", v.int()!!) }
        } else {
            null
        }
        val inputs = buildJsonObject {
            put("likelihood", JsonPrimitive(likelihood))
            if (consequences != null) {
                put("consequences", JsonObject(consequences.mapValues { JsonPrimitive(it.value) }))
            } else {
                put("consequence", JsonPrimitive(mapped(definition, "consequence", native.consequence!!)))
            }
        }
        val rated = score(core, inputs)
        return CommonRating(
            likelihood = likelihood,
            consequence = consequenceRating(core, inputs),
            consequences = consequences,
            score = rated.score,
            level = rated.level,
        )
    }

    /** Score with the methodology and, under [criteria], on the core grid as well: the result's
     *  `level` is then the common level, the native one kept as `nativeLevel` (ADR 0031 §4).
     *  Opportunities stay on the methodology's own levels. An `estimate` in the input (ADR 0033)
     *  stands in for the likelihood and one category's level: the bands it falls into. */
    fun rate(definitionElement: JsonElement, input: JsonElement, criteria: JsonElement? = null): Rating {
        val definition = definitionElement.obj() ?: invalid("A definition is an object")
        var data = input.obj() ?: JsonObject(emptyMap())
        val c = criteria.takeIf { !it.isNone() }
        var estimate: Estimate? = null
        if (!data["estimate"].isNone()) {
            val (inputs, recorded) = fromEstimate(definition, c.obj(), data)
            data = inputs
            estimate = recorded
        }
        val result = score(definition, data).copy(estimate = estimate)
        val kind = if (data["kind"].truthy()) data["kind"].key() else "RISK"
        if (c == null || kind == "OPPORTUNITY") return result
        val common = commonResult(definition, c.obj() ?: invalid("the criteria are an object"), data, result)
        return result.copy(level = common.level, nativeLevel = result.level, common = common)
    }

    /** The level whose band holds the value: the highest whose band starts at or below it. */
    private fun band(starts: List<Pair<Int, Long>>, value: Long): Int = starts.filter { it.second <= value }.maxOf { it.first }

    private fun times(a: Long, b: Long): Long {
        if (a != 0L && b > Long.MAX_VALUE / a) throw ScoringException("MISSING_INPUT", "estimate: the numbers are too large")
        return a * b
    }

    /** An estimate as levels (ADR 0033): `per_1000_years` (how often, in events per 1000 years)
     *  falls into a likelihood band; `loss` (in the category's unit) into the category's value
     *  band. `loss_low` and `loss_high` record a range; the level comes from `loss`, the most
     *  likely. The annual expectation is loss × rate ÷ 1000, rounded down. */
    private fun fromEstimate(definition: JsonObject, criteria: JsonObject?, data: JsonObject): Pair<JsonObject, Estimate> {
        if (definition.textOr("assessment_mode", "QUALITATIVE") == "QUALITATIVE") {
            throw ScoringException("QUALITATIVE_ONLY", "this methodology is rated by choosing levels")
        }
        if (criteria == null || !quantitativeReady(criteria)) {
            throw ScoringException("NO_BANDS", "the framework's criteria have no frequency and value bands")
        }
        if (data["kind"].truthy() && data["kind"].key() == "OPPORTUNITY") {
            throw ScoringException("QUALITATIVE_ONLY", "opportunities are rated by choosing levels")
        }
        val estimate = data["estimate"].obj() ?: throw ScoringException("MISSING_INPUT", "estimate is an object")
        val key = estimate["category"].str()
        if (key == null || key !in bandedCategories(criteria) || key !in categories(definition)) {
            throw ScoringException("UNKNOWN_CATEGORY", "${estimate["category"].key()} has no value bands in this methodology")
        }
        val numbers = mutableMapOf<String, Long>()
        for (name in listOf("per_1000_years", "loss", "loss_low", "loss_high")) {
            val value = estimate[name]
            if (value.isNone() && name in setOf("loss_low", "loss_high")) continue
            val n = value.long()
            if (n == null || n < 0) throw ScoringException("MISSING_INPUT", "estimate.$name is a whole number from 0")
            numbers[name] = n
        }
        val loss = numbers.getValue("loss")
        val low = numbers["loss_low"]
        val high = numbers["loss_high"]
        if ((low != null && low > loss) || (high != null && high < loss)) {
            throw ScoringException("MISSING_INPUT", "estimate: loss_low ≤ loss ≤ loss_high")
        }
        val frequency = items(criteria["likelihood"].obj()!!["levels"]).map { it.obj()!! }
            .map { it["level"].int()!! to it["frequency_from"].long()!! }
        val category = criteriaCategories(criteria).getValue(key)
        val values = category["value_from"].obj()!!.map { (level, start) -> level.toInt() to start.long()!! }
        val rate = numbers.getValue("per_1000_years")
        val likelihood = band(frequency, rate)
        val consequence = band(values, loss)
        val given = data["consequences"].takeIf { it.truthy() }.obj() ?: JsonObject(emptyMap())
        val inputs = buildJsonObject {
            for ((k, v) in data) if (k != "estimate" && k != "consequence" && k != "likelihood" && k != "consequences") put(k, v)
            put("likelihood", JsonPrimitive(likelihood))
            put("consequences", JsonObject(LinkedHashMap(given).apply { put(key, JsonPrimitive(consequence)) }))
        }
        val recorded = Estimate(
            category = key,
            unit = category["unit"].str() ?: "",
            per1000Years = rate,
            loss = loss,
            lossLow = low,
            lossHigh = high,
            likelihood = likelihood,
            consequence = consequence,
            annualExpectation = times(loss, rate) / 1000,
            annualExpectationLow = low?.let { times(it, rate) / 1000 },
            annualExpectationHigh = high?.let { times(it, rate) / 1000 },
        )
        return inputs to recorded
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

/** A rating on the framework's core grid (ADR 0031 §4): the common likelihood and consequence
 *  (and each category's, when rated by category), the core score and the common level. */
data class CommonRating(
    val likelihood: Int,
    val consequence: Int,
    val consequences: Map<String, Int>?,
    val score: Int,
    val level: RatedLevel,
)

/** An estimate as recorded (ADR 0033): what was given, the bands it fell into, and the annual
 *  expectation (loss × rate ÷ 1000, rounded down) in the category's unit. */
data class Estimate(
    val category: String,
    val unit: String,
    val per1000Years: Long,
    val loss: Long,
    val lossLow: Long?,
    val lossHigh: Long?,
    val likelihood: Int,
    val consequence: Int,
    val annualExpectation: Long,
    val annualExpectationLow: Long?,
    val annualExpectationHigh: Long?,
)

data class Rating(
    val score: Int,
    val likelihood: Int?,
    val consequence: Int?,
    val benefit: Int?,
    val factors: Map<String, Int>,
    val impacts: Map<String, Int>?,
    val intolerableAt: String?,
    val maxTolerableDisruption: MaxTolerableDisruption?,
    /** Under criteria, the common level; otherwise the methodology's own. */
    val level: RatedLevel,
    /** Under criteria, the methodology's own level. */
    val nativeLevel: RatedLevel? = null,
    val common: CommonRating? = null,
    val estimate: Estimate? = null,
)
