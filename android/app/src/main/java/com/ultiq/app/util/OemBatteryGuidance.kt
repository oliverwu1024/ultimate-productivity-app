package com.ultiq.app.util

import android.content.Context
import android.os.Build
import android.os.PowerManager

/**
 * Per-OEM guidance for keeping wake-up alarms reliable. Several Chinese OEMs
 * (Xiaomi, Huawei, OnePlus, Oppo, Vivo, Realme) and to a lesser extent
 * Samsung aggressively kill background services beyond what stock Android
 * does, which is the most common "alarm didn't fire" failure mode for
 * Phase 8.
 *
 * `dontkillmyapp.com` maintains per-manufacturer instructions; we deep-link
 * users straight to their device's page. Stock Android (Pixel / Google) and
 * unrecognised OEMs return `null` — no callout, no advice they don't need.
 */
object OemBatteryGuidance {

    /** `https://dontkillmyapp.com/<slug>` for OEMs known to over-kill, else null. */
    fun urlFor(manufacturer: String = Build.MANUFACTURER): String? {
        val slug = slugFor(manufacturer) ?: return null
        return "https://dontkillmyapp.com/$slug"
    }

    /** Pretty display name for the callout (`"Xiaomi"` / `"OnePlus"` etc.). */
    fun displayName(manufacturer: String = Build.MANUFACTURER): String {
        val lower = manufacturer.lowercase()
        return KNOWN_DISPLAY_NAMES[lower] ?: manufacturer
    }

    /**
     * True when the OS is *not* exempting us from battery optimisation — i.e.
     * Doze / the OEM killer can still freeze our background alarm work, so the
     * guidance card is worth showing. Once the user excludes Ultiq this flips
     * to false and the callout can hide. Reading the exemption state needs no
     * permission (only the direct-request intent is Play-restricted, and we
     * don't use it — the card deep-links to dontkillmyapp.com instead).
     */
    fun isBatteryOptimized(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        return !pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    private fun slugFor(manufacturer: String): String? {
        val lower = manufacturer.lowercase()
        return KNOWN_SLUGS[lower]
    }

    private val KNOWN_SLUGS = mapOf(
        "xiaomi" to "xiaomi",
        "redmi" to "xiaomi",
        "poco" to "xiaomi",
        "huawei" to "huawei",
        "honor" to "huawei",
        "oneplus" to "oneplus",
        "oppo" to "oppo",
        "realme" to "realme",
        "vivo" to "vivo",
        "samsung" to "samsung",
        "asus" to "asus",
        "nokia" to "nokia",
        "meizu" to "meizu",
        "lenovo" to "lenovo",
        "tecno" to "tecno",
        "wiko" to "wiko",
        "sony" to "sony",
        "htc" to "htc",
    )

    private val KNOWN_DISPLAY_NAMES = mapOf(
        "xiaomi" to "Xiaomi",
        "redmi" to "Redmi",
        "poco" to "POCO",
        "huawei" to "Huawei",
        "honor" to "Honor",
        "oneplus" to "OnePlus",
        "oppo" to "OPPO",
        "realme" to "realme",
        "vivo" to "vivo",
        "samsung" to "Samsung",
    )
}
