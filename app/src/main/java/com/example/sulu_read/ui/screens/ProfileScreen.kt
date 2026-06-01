package com.example.sulu_read.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
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
    modifier: Modifier = Modifier
) {
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SuluCard {
            Text(text = stringResource(R.string.profile_student, user?.displayName ?: "..."))
            user?.username?.let { Text(text = stringResource(R.string.profile_username, it)) }
            Text(text = stringResource(R.string.profile_user_id, user?.userId ?: "..."))
            Text(text = stringResource(R.string.profile_backend_language, user?.languagePreference ?: selectedLanguageCode))
        }

        SuluCard {
            Text(
                text = stringResource(R.string.account_title),
                style = MaterialTheme.typography.titleMedium
            )
            authErrorResId?.let {
                Text(
                    text = stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
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
            OutlinedTextField(
                value = displayName,
                onValueChange = { displayName = it },
                label = { Text(text = stringResource(R.string.account_display_name)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onLogin(username.trim(), password) },
                    enabled = !isBusy && username.length >= 3 && password.length >= 6,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.account_login))
                }
                Button(
                    onClick = { onRegister(username.trim(), password, displayName.trim()) },
                    enabled = !isBusy && username.length >= 3 && password.length >= 6,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(text = stringResource(R.string.account_register))
                }
            }
        }

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
