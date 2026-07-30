# Serif font bundled with the Focus Reader

The Focus Reader must render text in a Times New Roman-metric serif (client requirement,
see `docs/llm-wiki/wiki/sources/tz-zakazchika-focus-reader.md`). Microsoft's Times New Roman
cannot be redistributed inside an APK, so the app bundles a metrically compatible substitute
as `app/src/main/res/font/sulu_serif_{regular,bold}.ttf`.

- Font shipped: **Tinos** (Steve Matteson), metrically compatible with Times New Roman.
  Source: https://github.com/google/fonts/tree/main/ofl/tinos
  Licence: SIL Open Font License 1.1 — the font's own name table (record 14) points at
  https://openfontlicense.org. Google Fonts moved Tinos from `apache/` to `ofl/`, so any
  older reference to Apache-2.0 for this font is stale.
- Fallback if Kazakh coverage ever regresses: Liberation Serif (also Times-metric, OFL 1.1).
  Source: https://github.com/liberationfonts/liberation-fonts
- Verified glyph coverage in both weights, on 2026-07-30:
  ә ғ қ ң ө ұ ү һ і Ә Ғ Қ Ң Ө Ұ Ү Һ І ё Ё — none missing.

Re-verify coverage after any font swap:

```bash
.venv/Scripts/python -c "from fontTools.ttLib import TTFont; f=TTFont('app/src/main/res/font/sulu_serif_regular.ttf'); cps={c for t in f['cmap'].tables for c in t.cmap}; print('missing:', [ch for ch in 'әғқңөұүһіӘҒҚҢӨҰҮҺІёЁ' if ord(ch) not in cps] or 'none')"
```

`fonttools` is a development-only tool. It is deliberately absent from `requirements.txt`.

Never label this font "Times New Roman" in code, resources, or user-visible copy.
