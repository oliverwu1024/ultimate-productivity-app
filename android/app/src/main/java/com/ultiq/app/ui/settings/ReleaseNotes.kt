package com.ultiq.app.ui.settings

import androidx.annotation.StringRes
import com.ultiq.app.R

data class ReleaseNote(
    val versionName: String,
    val versionCode: Int,
    @StringRes val summaryRes: Int,
)

object ReleaseNotes {
    val history: List<ReleaseNote> = listOf(
        ReleaseNote("2.14.5", 82, R.string.relnote_2_14_5),
        ReleaseNote("2.13.19", 73, R.string.relnote_2_13_19),
        ReleaseNote("2.13.4", 58, R.string.relnote_2_13_4),
        ReleaseNote("2.13.3", 57, R.string.relnote_2_13_3),
        ReleaseNote("2.13.2", 56, R.string.relnote_2_13_2),
        ReleaseNote("2.13.1", 55, R.string.relnote_2_13_1),
        ReleaseNote("2.13.0", 54, R.string.relnote_2_13_0),
        ReleaseNote("2.12.4", 53, R.string.relnote_2_12_4),
        ReleaseNote("2.12.3", 52, R.string.relnote_2_12_3),
        ReleaseNote("2.12.2", 51, R.string.relnote_2_12_2),
        ReleaseNote("2.12.1", 50, R.string.relnote_2_12_1),
        ReleaseNote("2.12.0", 49, R.string.relnote_2_12_0),
        ReleaseNote("2.11.9", 48, R.string.relnote_2_11_9),
        ReleaseNote("2.11.8", 47, R.string.relnote_2_11_8),
        ReleaseNote("2.11.7", 46, R.string.relnote_2_11_7),
        ReleaseNote("2.11.6", 45, R.string.relnote_2_11_6),
        ReleaseNote("2.11.5", 44, R.string.relnote_2_11_5),
        ReleaseNote("2.11.4", 43, R.string.relnote_2_11_4),
        ReleaseNote("2.11.3", 42, R.string.relnote_2_11_3),
        ReleaseNote("2.11.2", 41, R.string.relnote_2_11_2),
        ReleaseNote("2.11.1", 40, R.string.relnote_2_11_1),
        ReleaseNote("2.11.0", 39, R.string.relnote_2_11_0),
        ReleaseNote("2.10.0", 31, R.string.relnote_2_10_0),
        ReleaseNote("2.9.0", 27, R.string.relnote_2_9_0),
        ReleaseNote("2.8.0", 26, R.string.relnote_2_8_0),
        ReleaseNote("2.7", 22, R.string.relnote_2_7),
        ReleaseNote("2.6", 21, R.string.relnote_2_6),
        ReleaseNote("2.5", 20, R.string.relnote_2_5),
        ReleaseNote("2.4", 19, R.string.relnote_2_4),
        ReleaseNote("2.3", 18, R.string.relnote_2_3),
        ReleaseNote("2.2", 17, R.string.relnote_2_2),
        ReleaseNote("2.1", 16, R.string.relnote_2_1),
        ReleaseNote("1.14", 15, R.string.relnote_1_14),
        ReleaseNote("1.13", 14, R.string.relnote_1_13),
        ReleaseNote("1.12", 13, R.string.relnote_1_12),
        ReleaseNote("1.10", 11, R.string.relnote_1_10),
    )
}
