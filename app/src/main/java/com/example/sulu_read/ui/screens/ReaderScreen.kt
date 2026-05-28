package com.example.sulu_read.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.example.sulu_read.SuluReadRoute
import com.example.sulu_read.domain.repository.SuluReadRepository

@Composable
fun ReaderScreen(
    repository: SuluReadRepository,
    onCreateTraining: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    SuluReadRoute(
        modifier = modifier,
        repository = repository,
        onCreateTrainingFromText = onCreateTraining
    )
}
