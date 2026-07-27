package com.example.sulu_read.domain.model

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val backendHint: String
) {
    English(code = "en", backendHint = "en"),
    Russian(code = "ru", backendHint = "ru"),
    Kazakh(code = "kk", backendHint = "kk");

    companion object {
        val supportedCodes: Set<String> = entries.map { it.code }.toSet()

        fun fromCode(code: String?): AppLanguage {
            return entries.firstOrNull { it.code == code } ?: Kazakh
        }

        fun backendHintFor(code: String?): String {
            return fromCode(code).backendHint
        }

        fun normalizeCode(code: String?): String {
            return fromCode(code).code
        }

        fun defaultCode(systemLanguage: String = Locale.getDefault().language): String {
            return if (systemLanguage in supportedCodes) systemLanguage else Kazakh.code
        }

        fun localeFor(code: String?): Locale {
            return when (fromCode(code)) {
                English -> Locale.ENGLISH
                Russian -> Locale("ru", "RU")
                Kazakh -> Locale("kk", "KZ")
            }
        }
    }
}
