package com.ultiq.app.alarm.mission

/**
 * A single math problem the user must solve. [display] is the prettified
 * Unicode form (`×` `÷` `−` rather than `*` `/` `-`). [answer] is always a
 * non-negative integer so the keypad never needs a sign key.
 */
data class MathProblem(
    val display: String,
    val answer: Int,
)
