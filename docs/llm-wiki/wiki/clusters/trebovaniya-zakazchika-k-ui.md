---
type: cluster_page
cluster_id: trebovaniya-zakazchika-k-ui
status: active
confidence: high
last_updated: 2026-07-30
tags: [ui-ogranicheniya]
---

# Требования заказчика к UI и технические ограничения

## Summary

ТЗ задаёт четыре блока: OCR-вход, «Focus»-отображение (Times New Roman + размытие + одно
слово в фокусе), голосовой шлюз перехода к следующему слову, ИИ-подсказки трёх видов.
Каждое требование сопоставлено ниже с состоянием кодовой базы Sulu Read и с техническим
ограничением платформы, которое придётся обойти.

## Evidence

| Source | Section | Quote/Rule | Signal |
|---|---|---|---|
| tz-zakazchika | п. 1 | «takes a photo of a physical text... written in Russian or Kazakh»; «extracts the text perfectly» | Вход уже реализован: `POST /v1/adapt-image` |
| tz-zakazchika | п. 2 | «rendered strictly in Times New Roman font (as explicitly requested for this specific project)» | Шрифт — жёсткое требование |
| tz-zakazchika | п. 2 | «The entire text is immediately blurred... does not distract, overwhelm, or create visual crowding» | Размытие обязательно, формулировка совпадает с davismethod-com |
| tz-zakazchika | п. 2 | «Only the very first word... unblurred, crystal clear, and highlighted with a bright but soft/soothing color (e.g., a warm pastel yellow or soft peach)» | Один фокус, тёплая пастель |
| tz-zakazchika | п. 3 | «The highlight will only move to the next word if the user pronounces the current word correctly» | Голосовой шлюз |
| tz-zakazchika | п. 4 | «Breaking the word down into simple, color-coded syllables» | Слоги уже есть в бэкенде |
| tz-zakazchika | п. 4 | «short, simple audio cue or visual icon representing the word's meaning» | Нужен смысловой канал |
| tz-zakazchika | п. 4 | «gentle, easy-to-understand synonym or context clue in Russian/Kazakh» | Нужен ИИ на RU/KK |
| dyslexiadar-stati | Порог замешательства | «движущиеся предметы» — триггер | Ограничение на анимацию |

## Implications

Сопоставление с кодовой базой (проектные наблюдения, не содержание источников):

| Требование | Что уже есть | Чего не хватает |
|---|---|---|
| OCR RU/KK | `main.py:/v1/adapt-image` (EasyOCR + `ocr_correction.py` + метрика `scripts/ocr_eval.py`) | ничего, переиспользуем |
| Слоги для подсказки | `backend/app/services/syllabification.py`, поле `words[].syllables` в ответе адаптации | ничего, переиспользуем |
| Озвучка слова | `audio/NaturalTts.kt` + контроллер TTS в `PremiumReadingScreen.kt` | названия букв для ступени Spell |
| ИИ-подсказка | `POST /ai/generate` (Gemini) + `AiHelpViewModel` | режим `hint` с коротким ответом и лимитом длины |
| Прогресс | `progress_service.py`, `daily_wpm`, accuracy | учёт слов, взятых с подсказкой |
| Размытие | — | `Modifier.blur` работает с API 31; `minSdk = 24` → нужен запасной вариант |
| Times New Roman | — | шрифт Microsoft нельзя вкладывать в APK; берём метрически совместимый клон |
| Микрофон | — | `RECORD_AUDIO` в манифесте отсутствует, `SpeechRecognizer` не используется |
| Казахский ASR | — | поддержка `kk-KZ` в `SpeechRecognizer` зависит от устройства, нужен путь отката |

Решения по ограничениям (все — решения проекта, не содержание источников):

1. **Шрифт.** Вкладываем метрически совместимый с Times New Roman свободный шрифт с полным
   покрытием казахской кириллицы (кандидаты: Tinos — Apache-2.0, Liberation Serif — OFL).
   Покрытие символов ә ғ қ ң ө ұ ү һ і проверяется до выбора. В коде шрифт называется своим
   настоящим именем, в UI — просто «классический шрифт»; выдавать клон за Times New Roman
   нельзя.
2. **Размытие.** API ≥ 31 — `Modifier.blur`. API 24–30 — деградация без падения:
   пониженная непрозрачность + серый цвет + уменьшенный контраст. Требование «не читаемо,
   но видно форму абзаца» выполняется оба раза.
3. **Отсутствие ASR для казахского.** Если распознавание недоступно, шлюз переключается
   в режим «прочитал сам» с кнопкой подтверждения. Режим обязан работать без микрофона —
   иначе на части устройств экран мёртвый.
4. **Анимации.** Переход между словами — только смена резкости/цвета, без движения по экрану
   (следствие из триггера «движущиеся предметы»).

## Related

- [[dezorientaciya-i-porog-zameshatelstva|Дезориентация и порог замешательства]]
- [[temporalnaya-obrabotka-fastforword|Темпоральная обработка и Fast ForWord]]
- [[../concepts/focus-reading-method|Метод Focus Reading]]
- [[../sources/tz-zakazchika-focus-reader|Источник: ТЗ заказчика]]
