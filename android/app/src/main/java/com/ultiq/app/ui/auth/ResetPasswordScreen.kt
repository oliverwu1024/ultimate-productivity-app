package com.ultiq.app.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.ultiq.app.util.PasswordStrength
import com.ultiq.app.util.SecureWindow

@Composable
fun ResetPasswordScreen(
    uiState: AuthUiState,
    token: String,
    onSubmit: (String, String) -> Unit,
    onDoneNavigateBack: () -> Unit,
) {
    SecureWindow()
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var passwordMismatch by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.resetPasswordSuccess) {
        if (uiState.resetPasswordSuccess) {
            // Brief pause so the user sees the success state, then bounce back to login.
            kotlinx.coroutines.delay(1500)
            onDoneNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Set a new password",
            style = MaterialTheme.typography.headlineLarge,
        )

        if (token.isBlank()) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "This reset link is missing its token. Please request a new email.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(24.dp))
            TextButton(onClick = onDoneNavigateBack) {
                Text("Back to login")
            }
            return@Column
        }

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = password,
            onValueChange = {
                password = it
                passwordMismatch = false
            },
            label = { Text("New password") },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (passwordVisible) "Hide password" else "Show password",
                    )
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.resetPasswordSuccess,
        )

        if (password.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            PasswordStrengthChecklist(password = password)
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = confirmPassword,
            onValueChange = {
                confirmPassword = it
                passwordMismatch = false
            },
            label = { Text("Confirm new password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            isError = passwordMismatch,
            supportingText = if (passwordMismatch) {
                { Text("Passwords do not match") }
            } else null,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
            enabled = !uiState.resetPasswordSuccess,
        )

        if (uiState.resetPasswordSuccess) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Password reset. Sending you back to login…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
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
            onClick = {
                if (password != confirmPassword) {
                    passwordMismatch = true
                } else {
                    onSubmit(token, password)
                }
            },
            enabled = !uiState.isLoading
                && !uiState.resetPasswordSuccess
                && PasswordStrength.isValid(password)
                && confirmPassword.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (uiState.isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            } else {
                Text("Set new password")
            }
        }

        if (!uiState.resetPasswordSuccess) {
            Spacer(modifier = Modifier.height(12.dp))
            TextButton(onClick = onDoneNavigateBack) {
                Text("Cancel")
            }
        }
    }
}
