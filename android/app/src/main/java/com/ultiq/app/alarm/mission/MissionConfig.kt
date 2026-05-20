package com.ultiq.app.alarm.mission

import org.json.JSONObject

/**
 * Parsers for the AlarmEntity.missionConfigJson blob. Use [JSONObject] —
 * NOT Gson — to dodge the R8 `-keep` footgun that bit calendar/checklist
 * entities earlier in the project (see `project_r8_gson_keep` memory).
 */
object MissionConfig {

    data class Math(val difficulty: MathDifficulty, val count: Int)
    data class Shake(val intensity: ShakeIntensity, val shakesRequired: Int)

    /**
     * [referenceUri] is a `file://` URI of the reference photo stored under
     * `Context.getFilesDir()/alarm_refs/{alarmId}.jpg`. [phash] is the 64-bit
     * DCT perceptual hash computed at setup. A live capture matches when its
     * pHash is within [tolerance] bits (Hamming distance ≤ tolerance).
     */
    data class Photo(val referenceUri: String?, val phash: Long, val tolerance: Int)

    /** Safe parser — bad JSON degrades to medium / 3. */
    fun parseMath(json: String): Math {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val diff = MathDifficulty.fromConfig(obj.optString("difficulty"))
        val count = obj.optInt("count", DEFAULT_MATH_COUNT).coerceIn(MIN_MATH_COUNT, MAX_MATH_COUNT)
        return Math(diff, count)
    }

    fun buildMath(difficulty: MathDifficulty, count: Int = DEFAULT_MATH_COUNT): String =
        JSONObject().apply {
            put("difficulty", difficulty.configKey)
            put("count", count.coerceIn(MIN_MATH_COUNT, MAX_MATH_COUNT))
        }.toString()

    /** Safe parser — bad JSON degrades to medium / 30. */
    fun parseShake(json: String): Shake {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val intensity = ShakeIntensity.fromConfig(obj.optString("intensity"))
        val required = obj.optInt("shakes_required", DEFAULT_SHAKES)
            .coerceIn(MIN_SHAKES, MAX_SHAKES)
        return Shake(intensity, required)
    }

    fun buildShake(
        intensity: ShakeIntensity,
        shakesRequired: Int = DEFAULT_SHAKES,
    ): String = JSONObject().apply {
        put("intensity", intensity.configKey)
        put("shakes_required", shakesRequired.coerceIn(MIN_SHAKES, MAX_SHAKES))
    }.toString()

    /** Safe parser — missing referenceUri returns Photo(null, 0L, default). */
    fun parsePhoto(json: String): Photo {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val uri = obj.optString("reference_uri").takeIf { it.isNotBlank() }
        val phash = runCatching {
            // pHash is sent as a hex string to dodge JSON's lack of a 64-bit
            // unsigned integer type — `Long.parseUnsignedLong` keeps the high
            // bit safe across the JSON round-trip.
            java.lang.Long.parseUnsignedLong(obj.optString("phash_hex", "0"), 16)
        }.getOrDefault(0L)
        val tolerance = obj.optInt("tolerance", DEFAULT_PHOTO_TOLERANCE)
            .coerceIn(MIN_PHOTO_TOLERANCE, MAX_PHOTO_TOLERANCE)
        return Photo(uri, phash, tolerance)
    }

    fun buildPhoto(
        referenceUri: String?,
        phash: Long,
        tolerance: Int = DEFAULT_PHOTO_TOLERANCE,
    ): String = JSONObject().apply {
        if (referenceUri != null) put("reference_uri", referenceUri)
        put("phash_hex", java.lang.Long.toUnsignedString(phash, 16))
        put("tolerance", tolerance.coerceIn(MIN_PHOTO_TOLERANCE, MAX_PHOTO_TOLERANCE))
    }.toString()

    private const val DEFAULT_MATH_COUNT = 3
    private const val MIN_MATH_COUNT = 1
    private const val MAX_MATH_COUNT = 10

    private const val DEFAULT_SHAKES = 30
    private const val MIN_SHAKES = 10
    private const val MAX_SHAKES = 100

    const val DEFAULT_PHOTO_TOLERANCE = 12
    private const val MIN_PHOTO_TOLERANCE = 4
    private const val MAX_PHOTO_TOLERANCE = 24
}
