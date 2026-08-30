package com.example.sulu_read.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The app's colours, in one place.
 *
 * The previous palette put the page background at #FFFDF6 and the cards on it at #FFFCF4 — a
 * difference of about one percent in luminance. Nothing read as a card, every screen looked like
 * one flat sheet, and no amount of spacing fixed it. The page is now a warm greige and the cards
 * sit on it in near-white paper, so a card is visibly a card before anything is read.
 *
 * Three rules this palette is built on:
 *
 * Nothing the reader looks at for long is pure white. A white page under a bright screen is a
 * documented source of visual stress for dyslexic readers — the effect usually described as the
 * text swimming — and warm paper takes it away at no cost to anyone else.
 *
 * Every text colour clears WCAG AA against the surface it is actually used on, checked rather
 * than assumed. The darkest text is near-black, not black: maximum contrast is harder to settle
 * on than slightly less of it.
 *
 * One accent, used consistently. The teal carries every primary action; green and amber are
 * reserved for the reading feedback, where they mean something specific.
 */

// Surfaces, from the page up. PageBackground is deliberately the darkest of these.
// Mazir's own scaffold is #FAFAF8 with white cards, but that pair is one percent apart in
// luminance — the exact flat-sheet bug described above — so the page here is a step darker
// in the same warm-neutral family and the cards keep their near-white paper.
val PageBackground = Color(0xFFF0F0EA)
val CardSurface = Color(0xFFFFFEFA)
val ReadingSurface = Color(0xFFFDFCF6)
val FieldSurface = Color(0xFFFFFEFA)
val AccentTint = Color(0xFFD9EBE6)
val WelcomeSurface = Color(0xFFEDF4F0)
val WarningSurface = Color(0xFFF7E9D2)
val WarningBorder = Color(0xFFE3CBA3)
val WarningIcon = Color(0xFF8A5A1E)

// The reader's body text. Near-black with a hint of ink rather than the old navy, which fought
// the accent blue for attention on the same screen.
val ReaderTextColor = Color(0xFF1F2328)

// Text.
val TextPrimary = Color(0xFF23252A)
val TextMuted = Color(0xFF585D66)

// The single accent. Mazir's deep teal seed: calm rather than bright, 5.3:1 against white,
// and it sits behind every primary action so it is on screen constantly.
val AccentTeal = Color(0xFF00796B)
val AccentTealSoft = Color(0xFF439889)
val CardBorder = Color(0xFFD8DCD3)
val AccentBorder = Color(0xFFBBD9D1)

// Reading feedback. Amber for the word being read, and it stays amber because dark text on it
// clears AA by a wide margin (13.3:1), which keeps the emphasised word the easiest thing on the
// page to read.
val FocusHighlight = Color(0xFFFFD54F)
val FocusWordColor = Color(0xFF14110C)
val RestingWordColor = Color(0xFF3F3A33)
val PlaybackHighlight = Color(0xFFFFF3C4)

// Status. Green for listening, a warm brown-orange for "try again" — chosen over red, which
// reads as punishment to a child who has just made a mistake.
val ListeningColor = Color(0xFF26694B)
val TryAgainColor = Color(0xFFA84B24)

// Exercise outcome. Same green and brown-orange as the reading status, deliberately: right and
// wrong should look the same everywhere in the app rather than each screen inventing a pair.
val CorrectColor = ListeningColor
val IncorrectColor = TryAgainColor

// One colour per training skill, so a reader recognises which kind of exercise they are on
// before reading its label. Retuned for the accent: the old decoding blue (#3F5F8F) sat
// almost on top of it, which made a skill badge look like a button.
val SkillPhonology = Color(0xFF55701F)
val SkillDecoding = Color(0xFF7A4E9E)
val SkillVisual = Color(0xFF9A5B2C)
val SkillMorphology = Color(0xFF8A4C62)

// The reading ruler: a translucent band the reader drags down the page. Warm so it reads as a
// highlighter laid on paper rather than a selection.
val RulerBand = Color(0xFFFFD166)
val RulerEdgeColor = Color(0xFF8A6D1D)
