package com.ultiq.app.util

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.gson.Gson
import com.ultiq.app.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Public manifest published next to the APKs at https://ultiqapp.com/version.json. */
data class VersionManifest(
    val versionCode: Int,
    val versionName: String,
    val url: String,
    val releaseNotes: String? = null,
)

/** What MainActivity exposes to the banner — null means "no update available". */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val releaseNotes: String?,
)

/**
 * Polls a static version manifest hosted next to the APKs on CloudFront.
 * Run once on app start; cheap (<1 KB JSON, served from edge cache).
 */
object UpdateChecker {
    private const val TAG = "UpdateChecker"
    private const val MANIFEST_URL = "https://ultiqapp.com/version.json"

    private val _state = MutableStateFlow<UpdateInfo?>(null)
    val state: StateFlow<UpdateInfo?> = _state.asStateFlow()

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    fun checkOnce(context: Context) {
        // Play Store builds get updates via Play Store itself — don't compete
        // with that, and definitely don't tell the user to grab a sideload APK
        // that won't install over a Play-signed install anyway.
        if (isInstalledFromPlayStore(context)) return
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val req = Request.Builder().url(MANIFEST_URL).build()
                val resp = client.newCall(req).execute()
                if (!resp.isSuccessful) {
                    Log.w(TAG, "manifest fetch failed: ${resp.code}")
                    return@launch
                }
                val body = resp.body?.string().orEmpty()
                val manifest = Gson().fromJson(body, VersionManifest::class.java)
                if (manifest != null && manifest.versionCode > BuildConfig.VERSION_CODE) {
                    _state.value = UpdateInfo(
                        versionName = manifest.versionName,
                        downloadUrl = manifest.url,
                        releaseNotes = manifest.releaseNotes,
                    )
                    Log.i(TAG, "update available: ${BuildConfig.VERSION_NAME} -> ${manifest.versionName}")
                } else {
                    _state.value = null
                }
            } catch (e: Exception) {
                Log.w(TAG, "update check failed", e)
            }
        }
    }

    fun dismiss() {
        _state.value = null
    }

    private fun isInstalledFromPlayStore(context: Context): Boolean {
        val pm = context.packageManager
        val pkg = context.packageName
        val installer: String? = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                pm.getInstallSourceInfo(pkg).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                pm.getInstallerPackageName(pkg)
            }
        } catch (_: Exception) {
            null
        }
        return installer == "com.android.vending"
    }
}
