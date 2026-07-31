package com.example.sulu_read.domain.model

import java.util.Locale

enum class AppLanguage(
    val code: String,
    val backendHint: String,
    /**
     * Name given to a profile created before the reader has typed one in. Not a UI string
     * resource: it is sent to the server at profile creation, where there is no Context, and it
     * is stored rather than re-rendered. Leaving it at a single hardcoded Kazakh default put the
     * word "Оқушы" on the profile card of every English and Russian reader.
     */
    val defaultDisplayName: String
) {
    English(code = "en", backendHint = "en", defaultDisplayName = "Student"),
    Russian(code = "ru", backendHint = "ru", defaultDisplayName = "Ученик"),
    Kazakh(code = "kk", backendHint = "kk", defaultDisplayName = "Оқушы");

    companion object {
        val supportedCodes: Set<String> = entries.map { it.code }.toSet()

        fun defaultDisplayNameFor(code: String?): String {
            return fromCode(code).defaultDisplayName
        }

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
