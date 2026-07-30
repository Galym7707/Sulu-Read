---
type: source_note
source_name: tz-zakazchika
status: ingested
confidence: high
last_updated: 2026-07-30
---

# ТЗ заказчика на режим «Focus Reader»

## Profile

Постановка задачи от заказчика Sulu Read (2026-07-30), 4 блока: OCR-вход, «Focus» UI,
голосовой темп, ИИ-подсказки. Не научный источник — источник **требований**. Приоритет
над остальными источниками в вопросах «что должно быть в продукте»; уступает им в вопросах
«как работает дислексия».

## Core Concepts

| Концепт | Суть | Куда внедрено |
|---|---|---|
| Times New Roman строго | Шрифт задан явно как требование проекта | [[../clusters/trebovaniya-zakazchika-k-ui\|Требования заказчика к UI]] |
| Размытие всего текста | Окружающий текст не должен отвлекать и создавать визуальную скученность | [[../clusters/trebovaniya-zakazchika-k-ui\|Требования заказчика к UI]] |
| Одно слово в фокусе | Первое слово — резкое, подсвечено мягким тёплым цветом | [[../clusters/trebovaniya-zakazchika-k-ui\|Требования заказчика к UI]] |
| Голосовой шлюз | Переход к следующему слову только при верном произнесении | [[../clusters/trebovaniya-zakazchika-k-ui\|Требования заказчика к UI]] |
| Fast ForWord внутри шлюза | Прямое указание рассмотреть технологию | [[../clusters/temporalnaya-obrabotka-fastforword\|Темпоральная обработка]] |
| ИИ-подсказка трёх видов | Цветные слоги · короткий аудио/иконка смысла · синоним или контекст на RU/KK | [[../clusters/trebovaniya-zakazchika-k-ui\|Требования заказчика к UI]] |

## Принято / Отклонено

**Принято:** все четыре блока целиком — это спецификация продукта.

**Отклонено / уточнено при реализации:**
- «The app scans and extracts the text perfectly» — идеальный OCR недостижим; в проекте уже
  есть слой исправления OCR и его метрика (`scripts/ocr_eval.py`), режим обязан работать
  на несовершенном тексте.
- «Times New Roman» — сам шрифт Microsoft лицензионно нельзя вкладывать в APK; берём
  метрически совместимый клон и называем его в коде честно (допущение реализации,
  см. [[../concepts/focus-reading-method|Метод]]).
- «highlight will only move... if the user pronounces the current word correctly» — буквальное
  прочтение делает застревание возможным, что противоречит порогу замешательства; вводим
  выход после эскалации (Never Do #5).

## Fact-check

Не применимо: источник требований, а не фактов о дислексии.

## Related

- [[../clusters/trebovaniya-zakazchika-k-ui|Требования заказчика к UI]]
- [[../concepts/focus-reading-method|Метод Focus Reading]]
- [[fastfword-razvitie-rechi|Источник: fastfword.com]]
