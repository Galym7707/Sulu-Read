<!-- READ-ONLY. Не редактировать. Только добавление новых файлов-источников. -->

# Происхождение

- Источник: постановка задачи от заказчика Sulu Read в переписке, 2026-07-30.
- Тип: техническое требование к продукту (не научный источник).
- Фиксация: текст требования приведён дословно.

# Текст требования

Core App Workflow & Mechanics. Please acknowledge and design around the following
step-by-step user journey:

1. Input & OCR (Optical Character Recognition):
   - The user takes a photo of a physical text (book, document, sign) written in Russian
     or Kazakh via the app's camera.
   - The app scans and extracts the text perfectly.

2. The "Focus" UI Display:
   - Font Constraint: The extracted text must be rendered strictly in Times New Roman font
     (as explicitly requested for this specific project).
   - Blur Effect: The entire text is immediately blurred on the screen. This is a critical
     feature to ensure the surrounding text does not distract, overwhelm, or create visual
     crowding for the dyslexic user.
   - Single Word Highlight: Only the very first word of the text is unblurred, crystal clear,
     and highlighted with a bright but soft/soothing color (e.g., a warm pastel yellow or
     soft peach).

3. Voice-Activated Pacing:
   - The app uses the device's microphone to listen to the user.
   - The user must read the currently highlighted word aloud.
   - Condition: The highlight will only move to the next word if the user pronounces the
     current word correctly (also consider applying fastword technology here).
   - Once pronounced correctly, the first word blurs again, and the second word unblurs and
     highlights. This creates a step-by-step, distraction-free reading pace.

4. AI-Powered Hint System:
   - If the user struggles to read the word, makes multiple incorrect attempts, or taps
     a "Help" button, the AI steps in.
   - The AI provides a dyslexia-friendly hint. These hints must be simple, easy to process,
     and not rely on heavy reading. Examples include:
     - Breaking the word down into simple, color-coded syllables.
     - Providing a short, simple audio cue or visual icon representing the word's meaning.
     - Giving a gentle, easy-to-understand synonym or context clue in Russian/Kazakh.
