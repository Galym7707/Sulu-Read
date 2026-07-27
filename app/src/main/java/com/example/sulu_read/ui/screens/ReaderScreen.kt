package com.example.sulu_read.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sulu_read.SuluReadRoute
import com.example.sulu_read.domain.repository.SuluReadRepository

@Composable
fun ReaderScreen(
    repository: SuluReadRepository,
    languageCode: String,
    onCreateTraining: (List<String>) -> Unit,
    modifier: Modifier = Modifier
) {
    val factory = remember(repository) { SuluReadViewModelFactory(repository) }
    val aiViewModel: AiHelpViewModel = viewModel(factory = factory)
    val aiState by aiViewModel.state.collectAsStateWithLifecycle()

    SuluReadRoute(
        modifier = modifier,
        repository = repository,
        languageCode = languageCode,
        onCreateTrainingFromText = onCreateTraining,
        aiHelpState = aiState,
        onExplainTextWithAi = { text -> aiViewModel.explainTextWithAi(text, languageCode) },
        onDismissAiHelp = aiViewModel::clearAiHelp
    )
}
