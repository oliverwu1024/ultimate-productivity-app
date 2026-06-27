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
     * `Context.getFilesDir()/alarm_refs/{alarmId}.jpg`. A live capture matches
     * when the cosine similarity between its MobileNet-V3 embedding and the
     * reference photo's embedding is ≥ [threshold] (see [PhotoEmbedder]). The
     * reference embedding is recomputed from the saved JPEG at mission time, so
     * nothing but the URI + threshold needs to live in the config blob.
     */
    data class Photo(val referenceUri: String?, val threshold: Float)

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

    /** Safe parser — missing referenceUri returns Photo(null, default threshold).
     *  Legacy configs (pre-v2.18) carried `phash_hex` + `tolerance` instead of a
     *  `threshold`; those keys are ignored and the saved reference JPEG is simply
     *  re-embedded at mission time, so old photo alarms keep working. */
    fun parsePhoto(json: String): Photo {
        val obj = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
        val uri = obj.optString("reference_uri").takeIf { it.isNotBlank() }
        val threshold = obj.optDouble("threshold", DEFAULT_PHOTO_THRESHOLD.toDouble())
            .toFloat().coerceIn(MIN_PHOTO_THRESHOLD, MAX_PHOTO_THRESHOLD)
        return Photo(uri, threshold)
    }

    fun buildPhoto(
        referenceUri: String?,
        threshold: Float = DEFAULT_PHOTO_THRESHOLD,
    ): String = JSONObject().apply {
        if (referenceUri != null) put("reference_uri", referenceUri)
        put("threshold", threshold.coerceIn(MIN_PHOTO_THRESHOLD, MAX_PHOTO_THRESHOLD).toDouble())
    }.toString()

    private const val DEFAULT_MATH_COUNT = 3
    private const val MIN_MATH_COUNT = 1
    private const val MAX_MATH_COUNT = 20

    /** Fixed options surfaced in the edit UI — keeps the picker tight while
     *  still letting older alarms with intermediate values round-trip cleanly
     *  through parseMath()/buildMath(). */
    val MATH_COUNT_OPTIONS: List<Int> = listOf(3, 5, 10, 20)

    private const val DEFAULT_SHAKES = 30
    private const val MIN_SHAKES = 10
    private const val MAX_SHAKES = 100

    /** Cosine-similarity threshold for the photo mission. 0.60 is deliberately
     *  lenient — the groggy user is not an adversary, and false *rejects* (can't
     *  dismiss a legitimately-matched scene) are the failure mode we're fixing.
     *  Tunable; the mission screen shows the live match % so it can be calibrated. */
    const val DEFAULT_PHOTO_THRESHOLD = 0.60f
    private const val MIN_PHOTO_THRESHOLD = 0.30f
    private const val MAX_PHOTO_THRESHOLD = 0.95f
}
