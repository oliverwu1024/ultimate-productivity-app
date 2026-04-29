package com.ultiq.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Terms & conditions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    "Last updated: April 30, 2026",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    "Ultiq is a personal productivity app built by a single developer. By using this app, you agree to the terms below.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            section(
                heading = "1. What you give us",
                body = "We collect the records you create in the app: sleep sessions, focus sessions, calendar events, checklist items, and any targets or preferences you set. We also store basic account info — your email address and a hashed password.",
            )
            section(
                heading = "2. What we do with it",
                body = "Your data is yours. We store it on our backend so it syncs between your phone and the web dashboard. We do not sell, share, or analyze your data for advertising. The only things we use it for are the features inside the app.",
            )
            section(
                heading = "3. Phone activity",
                body = "Sleep and focus sessions check whether your screen is on or off so we can count phone pickups. We don't read what apps you use, what you type, or anything else you do on the phone.",
            )
            section(
                heading = "4. Your account",
                body = "You can sign out, reset all your data, or delete your account anytime from Settings. Deleting an account wipes every record tied to it from our servers.",
            )
            section(
                heading = "5. As-is",
                body = "Ultiq is provided \"as is\", without warranty. We try our best, but the developer isn't liable for data loss, missed reminders, or anything else that may happen while using the app. Don't rely on it for anything safety-critical.",
            )
            section(
                heading = "6. Contact",
                body = "Questions, bugs, or feedback? Reach us at support@ultiq.app.",
            )
            section(
                heading = "7. Changes",
                body = "These terms may change as the app evolves. We'll update the date at the top when they do.",
            )

            item { Spacer(Modifier.height(32.dp)) }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(heading: String, body: String) {
    item {
        Text(
            heading,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
    item {
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
