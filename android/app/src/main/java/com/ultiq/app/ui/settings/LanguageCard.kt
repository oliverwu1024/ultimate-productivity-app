package com.ultiq.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ultiq.app.R

/**
 * §13 (i18n) — in-app language picker (Settings → Appearance). Selecting a
 * language calls back into SettingsViewModel, which applies it via LocaleManager
 * (recreating the activity so the whole UI re-resolves), persists it, and syncs
 * it to /auth/me. Endonyms are shown in each language's own script and are never
 * translated. The "" tag = follow the system, rendered from a localized string.
 * Tags mirror res/xml/locales_config.xml + LocaleManager.SUPPORTED.
 *
 * System default + English are the fully-reviewed options; the other 12 are
 * machine-translated, so they're grouped under an "Experimental languages"
 * header and annotated with their English name (`english`) to make them easy to
 * identify. `english` is empty for System + English (which need no annotation).
 */
private data class Lang(val tag: String, val endonym: String, val english: String)

private val LANGUAGES: List<Lang> = listOf(
    Lang("", "", ""),
    Lang("en", "English", ""),
    Lang("es", "Español", "Spanish"),
    Lang("pt-BR", "Português (Brasil)", "Portuguese (Brazil)"),
    Lang("fr", "Français", "French"),
    Lang("de", "Deutsch", "German"),
    Lang("ja", "日本語", "Japanese"),
    Lang("zh-Hans", "简体中文", "Chinese (Simplified)"),
    Lang("zh-Hant", "繁體中文", "Chinese (Traditional)"),
    Lang("ko", "한국어", "Korean"),
    Lang("hi", "हिन्दी", "Hindi"),
    Lang("vi", "Tiếng Việt", "Vietnamese"),
    Lang("th", "ไทย", "Thai"),
    Lang("id", "Bahasa Indonesia", "Indonesian"),
    Lang("ar", "العربية", "Arabic"),
)

@Composable
fun LanguageCard(currentTag: String, onSelect: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_language)
    val systemDefault = stringResource(R.string.settings_language_system)

    val current = LANGUAGES.firstOrNull { it.tag == currentTag }
    val currentName = when {
        current == null || current.tag.isBlank() -> systemDefault
        current.english.isEmpty() -> current.endonym                 // English
        else -> "${current.endonym} (${current.english})"            // experimental
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                Icons.Default.Language,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    currentName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text(label) },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    LANGUAGES.forEachIndexed { index, lang ->
                        val isExperimental = lang.english.isNotEmpty()
                        // Section header once, above the first machine-translated language.
                        if (isExperimental && LANGUAGES[index - 1].english.isEmpty()) {
                            Text(
                                stringResource(R.string.settings_language_experimental),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp, top = 16.dp, bottom = 4.dp),
                            )
                        }
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    if (lang.tag != currentTag) onSelect(lang.tag)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = lang.tag == currentTag,
                                onClick = {
                                    showDialog = false
                                    if (lang.tag != currentTag) onSelect(lang.tag)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(
                                    if (lang.tag.isBlank()) systemDefault else lang.endonym,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (isExperimental) {
                                    Text(
                                        lang.english,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}
