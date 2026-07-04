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
 */
private val LANGUAGES: List<Pair<String, String>> = listOf(
    "" to "",
    "en" to "English",
    "es" to "Español",
    "pt-BR" to "Português (Brasil)",
    "fr" to "Français",
    "de" to "Deutsch",
    "ja" to "日本語",
    "zh-Hans" to "简体中文",
    "zh-Hant" to "繁體中文",
    "ko" to "한국어",
    "hi" to "हिन्दी",
    "vi" to "Tiếng Việt",
    "th" to "ไทย",
    "ar" to "العربية",
)

@Composable
fun LanguageCard(currentTag: String, onSelect: (String) -> Unit) {
    var showDialog by remember { mutableStateOf(false) }
    val label = stringResource(R.string.settings_language)
    val systemDefault = stringResource(R.string.settings_language_system)
    fun display(tag: String, endonym: String) = if (tag.isBlank()) systemDefault else endonym

    val currentName = LANGUAGES.firstOrNull { it.first == currentTag }
        ?.let { display(it.first, it.second) } ?: systemDefault

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
                    LANGUAGES.forEach { (tag, endonym) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    showDialog = false
                                    if (tag != currentTag) onSelect(tag)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = tag == currentTag,
                                onClick = {
                                    showDialog = false
                                    if (tag != currentTag) onSelect(tag)
                                },
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(display(tag, endonym), style = MaterialTheme.typography.bodyLarge)
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
