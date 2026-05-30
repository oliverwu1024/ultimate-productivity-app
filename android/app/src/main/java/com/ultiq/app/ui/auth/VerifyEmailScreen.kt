package com.ultiq.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ultiq.app.util.SecureWindow

@Composable
fun VerifyEmailScreen(
    uiState: AuthUiState,
    token: String,
    onVerify: (String) -> Unit,
    onContinue: () -> Unit,
) {
    SecureWindow()
    // Fire the verification call once, when the screen mounts with a token.
    // Re-entering after success / failure won't re-trigger because uiState
    // already reflects the previous outcome.
    LaunchedEffect(token) {
        if (token.isNotBlank() &&
            !uiState.verifyEmailLoading &&
            !uiState.verifyEmailSuccess &&
            uiState.verifyEmailError == null
        ) {
            onVerify(token)
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = "Verify your email",
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))

            when {
                token.isBlank() -> {
                    Text(
                        text = "No verification token in this link. Open the page directly from the email we sent.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onContinue) { Text("Continue") }
                }

                uiState.verifyEmailLoading -> {
                    CircularProgressIndicator()
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Verifying…",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                uiState.verifyEmailSuccess -> {
                    Text(
                        text = "Email verified. Coach and other AI features are now unlocked.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(onClick = onContinue) { Text("Open Ultiq") }
                }

                uiState.verifyEmailError != null -> {
                    Text(
                        text = uiState.verifyEmailError,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Verification links expire after 24 hours. Sign in and tap Resend verification email from Settings to get a fresh one.",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    TextButton(onClick = onContinue) { Text("Continue") }
                }
            }
        }
    }
}
