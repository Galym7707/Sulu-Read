package com.example.sulu_read.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's colours, in one place.
 *
 * They were previously written out as hex literals in six different files, which is how three
 * near-identical creams and two different card borders came to exist. Anything used by more than
 * one screen belongs here.
 *
 * Two rules the palette is built on, both of which matter more here than in an ordinary app:
 *
 * Nothing the reader looks at for long is pure white. A white page under a bright screen is a
 * documented source of visual stress for dyslexic readers — the effect usually described as the
 * text swimming — and an off-white takes it away at no cost to anyone else. The reading surfaces
 * are therefore cream, not #FFFFFF.
 *
 * Every text colour here clears WCAG AA against the surface it is used on, checked rather than
 * assumed. The darkest text is deliberately not black either: near-black on cream is easier to
 * settle on than maximum contrast.
 */

// Page and card surfaces, lightest first.
val WarmCream = Color(0xFFFFFDF6)
val ReadingSurface = Color(0xFFFFFBF0)
val FieldSurface = Color(0xFFFFFCF4)
val WelcomeSurface = Color(0xFFFFF7E7)
val WarningSurface = Color(0xFFFFF3DF)
val SoftMint = Color(0xFFEAF5ED)

// Text. TextPrimary is near-black rather than black; TextMuted still clears AA at 5.6:1.
val TextPrimary = Color(0xFF2B2A24)
val TextMuted = Color(0xFF6A665D)

// Accents and edges.
val DeepSageGreen = Color(0xFF2E6F40)
val SoftSage = Color(0xFF6C8F73)
val SoftSageBorder = Color(0xFFCFE3D4)
val WelcomeBorder = Color(0xFFF1DFC1)

// Reading feedback. The focus highlight is a marker-pen amber that keeps dark text well clear
// of AA (13.3:1), so the emphasised word stays the easiest thing on the page to read.
val FocusHighlight = Color(0xFFFFD54F)
val FocusWordColor = Color(0xFF14110C)
val RestingWordColor = Color(0xFF3F3A33)
val PlaybackHighlight = Color(0xFFFFF9C4)

// Status. Green for listening, a warm brown-orange for "try again" — chosen over red, which
// reads as punishment to a child who has just made a mistake.
val ListeningColor = Color(0xFF2E6F40)
val TryAgainColor = Color(0xFFA24A1E)
