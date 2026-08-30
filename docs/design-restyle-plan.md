# Design restyle plan — Mäzïr style

Source of the style: `ttumashh/mazir-app`, `mobile/lib/core/theme.dart`. Its design language:

- One seed color: deep teal `#00796B`; everything else derived, restrained.
- Warm off-white page (`#FAFAF8`), flat surfaces, zero/near-zero elevation.
- Consistent radii: 10–12 chips/inputs, 14 buttons, 16–20 cards/dialogs/sheets.
- Tall full-width primary buttons (52dp), 16sp/semibold labels.
- Left-aligned bold 22sp titles, flat app bar.
- Hairline dividers at low alpha, outlined chips, tabular figures for numbers.

## What we keep (functional, dyslexia-specific — not aesthetics)

- Amber `FocusHighlight` and the reading feedback pair (green "listening", warm
  brown-orange "try again"). These carry meaning and clear WCAG AA.
- Skill colors (phonology/decoding/visual/morphology).
- The rule that no long-look surface is pure white (`ReadingSurface` stays warm).
- The rule that the page is visibly darker than the cards on it (mazir's own
  `#FAFAF8` + white cards would reproduce the flat-sheet bug documented in
  `Palette.kt`; we keep a two-step surface ladder, tuned toward mazir's hue).

## Changes

1. **Palette.kt** — accent moves from blue `#215F7E` to mazir teal `#00796B`
   (contrast vs white 5.3:1, AA for text and UI). `AccentBlue`/`AccentBlueSoft`
   renamed `AccentTeal`/`AccentTealSoft`. Page becomes a light warm greige-green
   step below the near-white cards; tints and borders re-derived from teal.
2. **MainActivity.kt** — shapes drop to mazir radii (10/12/14/20/24), display
   weight ExtraBold → Bold, color scheme picks up the renamed constants.
3. **SuluCard.kt** — flat: elevation 0, radius 16, keeps its 1dp border (mazir
   separates surfaces with borders, not shadows).
4. **Web version** — new `web/` static site using the same tokens (teal seed,
   warm surfaces, mazir radii): product landing plus an interactive focus-reader
   demo replicating the app's word-by-word mode (reader moves the focus, click
   any word to jump). Deployed to Vercel.

Verification: `gradlew :app:compileDebugKotlin` for the Android side; browser
check of `web/` before deploy.
