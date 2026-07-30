package com.example.sulu_read.domain.repository

import com.example.sulu_read.data.SuluReadApi
import com.example.sulu_read.data.dto.AiGenerateRequestDto
import com.example.sulu_read.data.dto.AiGenerateResponseDto
import com.example.sulu_read.domain.model.AppLanguage

class AiRepository(
    private val api: SuluReadApi
) {
    suspend fun generateAiHelp(
        task: String,
        text: String,
        languageCode: String,
        level: String? = null,
        mode: String = "explain",
        extra: Map<String, String> = emptyMap()
    ): AiGenerateResponseDto {
        return api.generateAi(
            AiGenerateRequestDto(
                task = task,
                text = text,
                language = AppLanguage.backendHintFor(languageCode),
                level = level,
                mode = mode,
                extra = extra
            )
        )
    }

    suspend fun explainTextWithAi(text: String, languageCode: String): AiGenerateResponseDto {
        return generateAiHelp(
            task = "Explain the selected reading text clearly and briefly.",
            text = text,
            languageCode = languageCode,
            mode = "reading_help"
        )
    }

    suspend fun hintForWordWithAi(word: String, languageCode: String): AiGenerateResponseDto {
        return generateAiHelp(
            task = "A child with dyslexia is stuck on one word while reading aloud. " +
                "Answer in at most two very short lines, in the student's language. " +
                "Line 1: a concrete sensory clue to what the word means, the kind of thing " +
                "you could picture or touch. Line 2: one everyday synonym. " +
                "No definitions, no grammar terms, no encouragement, no extra words.",
            text = word,
            languageCode = languageCode,
            mode = "reading_help"
        )
    }

    suspend fun checkAnswerWithAi(
        question: String,
        correctAnswer: String,
        userAnswer: String,
        languageCode: String
    ): AiGenerateResponseDto {
        return generateAiHelp(
            task = "Check the student's answer and explain any mistake.",
            text = question,
            languageCode = languageCode,
            mode = "check_answer",
            extra = mapOf(
                "correct_answer" to correctAnswer,
                "user_answer" to userAnswer
            )
        )
    }

    suspend fun generateExerciseWithAi(
        text: String,
        languageCode: String,
        level: String? = null
    ): AiGenerateResponseDto {
        return generateAiHelp(
            task = "Generate a short reading or morphology exercise for the student.",
            text = text,
            languageCode = languageCode,
            level = level,
            mode = "generate_task"
        )
    }
}
