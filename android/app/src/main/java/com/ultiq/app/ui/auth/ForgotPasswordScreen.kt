package com.ultiq.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ForgotPasswordScreen(
    uiState: AuthUiState,
    onSubmit: (String) -> Unit,
    onPasteToken: (String) -> Unit,
    onBack: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var pastedLink by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Forgot password",
            style = MaterialTheme.typography.headlineLarge,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Enter the email tied to your account. We'll send a reset link.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.forgotPasswordEmailSent,
        )

        if (uiState.forgotPasswordEmailSent) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "If that email is registered, a reset link is on the way. " +
                    "Check your inbox (and spam) — the link expires in 1 hour.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Reset link not opening the app?",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Paste the full link from the email below and tap Continue.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = pastedLink,
                onValueChange = { pastedLink = it },
                label = { Text("ultiq://reset-password?token=…") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    val token = extractToken(pastedLink)
                    if (token != null) {
                        onPasteToken(token)
                    }
                },
                enabled = extractToken(pastedLink) != null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Continue with pasted link")
            }
        }

        if (uiState.error != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { onSubmit(email) },
            enabled = !uiState.isLoading && email.isNotBlank() && !uiState.forgotPasswordEmailSent,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text(if (uiState.forgotPasswordEmailSent) "Sent" else "Send reset link")
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        TextButton(onClick = onBack) {
            Text("Back to login")
        }
    }
}

/**
 * Pulls `token=...` out of an `ultiq://reset-password?token=...` URL or a raw token.
 * Returns null when the input doesn't contain a usable token.
 */
private fun extractToken(input: String): String? {
    val trimmed = input.trim()
    if (trimmed.isEmpty()) return null
    // If it looks like a URL with a token query param, pull it out.
    val tokenParam = Regex("[?&]token=([^&\\s]+)").find(trimmed)?.groupValues?.getOrNull(1)
    if (!tokenParam.isNullOrBlank()) return tokenParam
    // Otherwise, accept a raw UUID-shaped token.
    val uuidish = Regex("^[0-9a-fA-F-]{32,40}$")
    return if (uuidish.matches(trimmed)) trimmed else null
}
