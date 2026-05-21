package com.ultiq.app.util

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.ultiq.app.receiver.ApkInstallResultReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Replaces the "tap to open browser → tap download → open file → tap install"
 * dance with: tap once, app downloads the APK, system shows a single "Install?"
 * confirm. Android requires that confirm step for any sideload install — we
 * can't fully eliminate it without device-owner privileges.
 *
 * Lives as a singleton so an in-flight download survives Activity destruction
 * (back press, rotation, the user briefly visiting another app). Process death
 * still cancels it; the user retries by tapping the banner again.
 */
object ApkUpdater {

    private const val TAG = "ApkUpdater"
    private const val SESSION_FILE = "ultiq-update.apk"

    enum class Stage { Idle, Downloading, AwaitingConfirm, Installing, Failed }

    data class State(
        val stage: Stage = Stage.Idle,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = 0L,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            // Big read timeout — slow networks shouldn't kill a partway download.
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    fun startUpdate(context: Context, url: String) {
        val current = _state.value.stage
        if (current == Stage.Downloading || current == Stage.AwaitingConfirm ||
            current == Stage.Installing
        ) {
            return
        }
        val app = context.applicationContext
        job = scope.launch { runUpdate(app, url) }
    }

    fun reset() {
        job?.cancel()
        job = null
        _state.value = State()
    }

    /**
     * Restart the app cleanly after a same-package install. Android leaves
     * the old process running on most OEM ROMs (despite the SDK contract
     * suggesting otherwise), so the user keeps seeing the old code until
     * they manually kill + reopen. This helper fires the launcher intent
     * with CLEAR_TASK and then kills our own process — the launcher then
     * brings up the freshly-installed APK.
     */
    fun relaunchApp(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
        if (launch != null) {
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            context.startActivity(launch)
        }
        // Tiny delay so the launcher intent is actually queued before we go
        // away. Without it the OS sometimes drops the activity start.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            android.os.Process.killProcess(android.os.Process.myPid())
        }, 200)
    }

    /** Called by [ApkInstallResultReceiver] when the OS reports install outcome. */
    internal fun onInstallSuccess() {
        _state.value = State(stage = Stage.Installing)
    }

    internal fun onInstallFailed(message: String) {
        Log.w(TAG, "install failed: $message")
        _state.value = _state.value.copy(stage = Stage.Failed, error = message)
    }

    internal fun onAwaitingConfirm() {
        _state.value = _state.value.copy(stage = Stage.AwaitingConfirm)
    }

    private suspend fun runUpdate(context: Context, url: String) {
        var sessionId = -1
        try {
            _state.value = State(stage = Stage.Downloading)

            val req = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) {
                throw IOException("HTTP ${resp.code} fetching update")
            }
            val body = resp.body ?: throw IOException("Empty response")
            val total = body.contentLength().takeIf { it > 0 } ?: -1L

            val installer = context.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            ).apply {
                setAppPackageName(context.packageName)
                if (total > 0) setSize(total)
            }
            sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            session.use { s ->
                s.openWrite(SESSION_FILE, 0, total).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            downloaded += n
                            // Throttle UI updates so we don't thrash recomposition.
                            if (downloaded == 0L || total <= 0 || downloaded == total ||
                                downloaded % (256 * 1024) < n
                            ) {
                                _state.value = State(
                                    stage = Stage.Downloading,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                )
                            }
                        }
                    }
                    s.fsync(out)
                }
                _state.value = State(
                    stage = Stage.AwaitingConfirm,
                    downloadedBytes = total.coerceAtLeast(0),
                    totalBytes = total,
                )
                val intent = Intent(context, ApkInstallResultReceiver::class.java).apply {
                    action = ApkInstallResultReceiver.ACTION_INSTALL_RESULT
                }
                val pending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                s.commit(pending.intentSender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "update failed", e)
            if (sessionId >= 0) {
                runCatching { context.packageManager.packageInstaller.abandonSession(sessionId) }
            }
            _state.value = State(
                stage = Stage.Failed,
                error = e.message ?: "Update failed",
            )
        }
    }

    private const val REQUEST_CODE = 71_001
}
