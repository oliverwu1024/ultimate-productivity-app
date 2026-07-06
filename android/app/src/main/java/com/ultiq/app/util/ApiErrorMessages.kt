package com.ultiq.app.util

import android.content.Context
import androidx.annotation.StringRes
import com.google.gson.JsonParser
import com.ultiq.app.R
import retrofit2.HttpException
import java.io.IOException

/**
 * Maps a Throwable to a user-facing message. The server's own error text
 * (already localized once the backend is) passes through; network + fallback
 * cases resolve from resources so they follow the app language. Resolved at
 * call time (transient), so a VM resolving it is fine.
 */
fun Throwable.toUserMessage(context: Context, @StringRes fallback: Int): String {
    return when (this) {
        is HttpException -> parseErrorBody() ?: context.getString(fallback)
        is IOException -> context.getString(R.string.err_network)
        else -> context.getString(fallback)
    }
}

private fun HttpException.parseErrorBody(): String? {
    val raw = runCatching { response()?.errorBody()?.string() }.getOrNull()
    if (raw.isNullOrBlank()) return null
    return runCatching {
        JsonParser.parseString(raw)
            .takeIf { it.isJsonObject }
            ?.asJsonObject
            ?.get("error")
            ?.takeIf { it.isJsonPrimitive }
            ?.asString
            ?.takeIf { it.isNotBlank() }
    }.getOrNull()
}
