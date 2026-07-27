package com.example.sulu_read.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.sulu_read.R
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.UserProfile
import com.example.sulu_read.ui.components.SuluCard

@Composable
fun ProfileScreen(
    user: UserProfile?,
    selectedLanguageCode: String,
    isBusy: Boolean,
    authErrorResId: Int?,
    onLanguageSelected: (String) -> Unit,
    onRegister: (username: String, password: String, displayName: String) -> Unit,
    onLogin: (username: String, password: String) -> Unit,
    authFeedback: AuthFeedback? = null,
    modifier: Modifier = Modifier
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }
    var authMode by rememberSaveable { mutableStateOf(AuthMode.Login) }
    val canSubmitAccount = canSubmitAccountForm(username, password)
    val feedbackMessageResId = authFeedback?.messageResId ?: authErrorResId
    val feedbackIsError = authFeedback?.isError ?: (authErrorResId != null)
    val selectedLanguageLabel = when (AppLanguage.fromCode(selectedLanguageCode)) {
        AppLanguage.English -> stringResource(R.string.language_english)
        AppLanguage.Russian -> stringResource(R.string.language_russian)
        AppLanguage.Kazakh -> stringResource(R.string.language_kazakh)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SuluCard {
            Text(
                text = stringResource(R.string.settings_language_title),
                style = MaterialTheme.typography.titleMedium
            )
            AppLanguage.entries.forEach { language ->
                LanguageRadioRow(
                    label = when (language) {
                        AppLanguage.English -> stringResource(R.string.language_english)
                        AppLanguage.Russian -> stringResource(R.string.language_russian)
                        AppLanguage.Kazakh -> stringResource(R.string.language_kazakh)
                    },
                    selected = selectedLanguageCode == language.code,
                    onClick = { onLanguageSelected(language.code) }
                )
            }
        }

        SuluCard {
            Text(text = stringResource(R.string.profile_student, user?.displayName ?: "..."))
            user?.username?.let { Text(text = stringResource(R.string.profile_username, it)) }
            Text(text = stringResource(R.string.profile_selected_language, selectedLanguageLabel))
            TextButton(onClick = { showAdvanced = !showAdvanced }) {
                Text(
                    text = if (showAdvanced) {
                        stringResource(R.string.settings_hide_advanced)
                    } else {
                        stringResource(R.string.settings_advanced)
                    }
                )
            }
            if (showAdvanced) {
                Text(
                    text = stringResource(R.string.profile_user_id, user?.userId ?: "..."),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SuluCard {
            Text(
                text = stringResource(R.string.account_title),
                style = MaterialTheme.typography.titleMedium
            )

            feedbackMessageResId?.let { messageResId ->
                AuthFeedbackMessage(
                    message = stringResource(messageResId),
                    isError = feedbackIsError
                )
            }

            Text(
                text = if (authMode == AuthMode.Login) {
                    stringResource(R.string.account_login_hint)
                } else {
                    stringResource(R.string.account_register_hint)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = username,
                onValueChange = { username = it },
                label = { Text(text = stringResource(R.string.account_username)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text(text = stringResource(R.string.account_password)) },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (authMode == AuthMode.Register) {
                OutlinedTextField(
                    value = displayName,
                    onValueChange = { displayName = it },
                    label = { Text(text = stringResource(R.string.account_display_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    if (authMode == AuthMode.Login) {
                        onLogin(username.trim(), password)
                    } else {
                        onRegister(username.trim(), password, displayName.trim())
                    }
                },
                enabled = canSubmitAccount && !isBusy,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                contentPadding = ButtonDefaults.ContentPadding
            ) {
                if (isBusy) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                }
                Text(
                    text = if (authMode == AuthMode.Login) {
                        stringResource(R.string.account_login)
                    } else {
                        stringResource(R.string.account_register)
                    },
                    maxLines = 1,
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (authMode == AuthMode.Login) {
                        stringResource(R.string.account_no_account)
                    } else {
                        stringResource(R.string.account_has_account)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(
                    onClick = {
                        authMode = if (authMode == AuthMode.Login) AuthMode.Register else AuthMode.Login
                    }
                ) {
                    Text(
                        text = if (authMode == AuthMode.Login) {
                            stringResource(R.string.account_register_link)
                        } else {
                            stringResource(R.string.account_login_link)
                        },
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun AuthFeedbackMessage(
    message: String,
    isError: Boolean
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = if (isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.primaryContainer
        }
    ) {
        Text(
            text = message,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = if (isError) {
                MaterialTheme.colorScheme.onErrorContainer
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            }
        )
    }
}

@Composable
private fun LanguageRadioRow(
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun canSubmitAccountForm(username: String, password: String): Boolean {
    return username.trim().length >= 3 && password.length >= 4
}

private enum class AuthMode {
    Login,
    Register
}
