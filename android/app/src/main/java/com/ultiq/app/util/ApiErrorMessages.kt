package com.ultiq.app.util

import com.google.gson.JsonParser
import retrofit2.HttpException
import java.io.IOException

fun Throwable.toUserMessage(fallback: String = "Something went wrong. Try again."): String {
    return when (this) {
        is HttpException -> parseErrorBody() ?: fallback
        is IOException -> "Couldn't reach the server. Check your internet connection."
        else -> fallback
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
