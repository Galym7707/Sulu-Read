package com.example.sulu_read.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.example.sulu_read.R
import com.example.sulu_read.domain.model.AppLanguage
import com.example.sulu_read.domain.model.UserProfile
import com.example.sulu_read.ui.components.SuluCard

@Composable
fun ProfileScreen(
    user: UserProfile?,
    selectedLanguageCode: String,
    onLanguageSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineSmall)

        SuluCard {
            Text(text = stringResource(R.string.profile_student, user?.displayName ?: "..."))
            Text(text = stringResource(R.string.profile_user_id, user?.userId ?: "..."))
            Text(text = stringResource(R.string.profile_backend_language, user?.languagePreference ?: selectedLanguageCode))
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
