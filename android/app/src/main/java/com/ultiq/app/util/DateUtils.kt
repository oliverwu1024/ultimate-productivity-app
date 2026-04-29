package com.ultiq.app.util

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

object DateUtils {
    private val displayFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    private val timeFormatter = DateTimeFormatter.ofPattern("hh:mm a")

    fun formatDate(dateTime: LocalDateTime): String = dateTime.format(displayFormatter)
    fun formatTime(dateTime: LocalDateTime): String = dateTime.format(timeFormatter)
}
