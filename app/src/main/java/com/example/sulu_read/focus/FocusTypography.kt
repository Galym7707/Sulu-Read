package com.example.sulu_read.focus

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.example.sulu_read.R

/**
 * The reading area uses a bundled serif that is metrically compatible with Times New Roman.
 * The Microsoft font itself cannot be redistributed in an APK, so this is a licensed
 * substitute — see docs/superpowers/notes/focus-font-license.md. Do not present it to users
 * as "Times New Roman".
 */
val SuluSerifFontFamily: FontFamily = FontFamily(
    Font(R.font.sulu_serif_regular, FontWeight.Normal),
    Font(R.font.sulu_serif_bold, FontWeight.Bold)
)
