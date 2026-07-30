# Sulu Read LLM Wiki — карта связей

Vault по теме «чтение при дислексии» для проекта Sulu Read. Правила и таксономия —
в [манифесте](../CLAUDE.md). Поиск по vault:
`python docs/llm-wiki/rag_query.py "запрос"`.

Точка входа для вопроса «как должен работать новый режим чтения» —
[[concepts/focus-reading-method|Метод Focus Reading]].

## Системные артефакты

- [[log|Журнал операций]] — что и когда делалось с vault (append-only)
- [[lint-report|Отчёт проверки]] — результат последнего прогона lint

## Источники

- [[sources/dyslexiadar-stati|dyslexiadar.com — статьи]] — самый содержательный источник: пошаговые упражнения чтения, порог замешательства, цифры исследования
- [[sources/davismethod-com|davismethod.com]] — теоретическая рамка метода Дейвиса, маркетинговые обещания
- [[sources/fastfword-razvitie-rechi|fastfword.com]] — Fast ForWord: темп предъявления звука, адаптивная сложность
- [[sources/brosura-dejvisa-askargalieva|Брошюра метода Дейвиса (Алматы)]] — локальный контекст и терминология, перечень типовых ошибок при дисграфии
- [[sources/tz-zakazchika-focus-reader|ТЗ заказчика на Focus Reader]] — требования к продукту: шрифт, размытие, голосовой шлюз, ИИ-подсказки

## Кластеры

- [[clusters/dezorientaciya-i-porog-zameshatelstva|Дезориентация и порог замешательства]] — почему количество информации на экране это терапевтический параметр
- [[clusters/uprazhneniya-chteniya-dejvisa|Упражнения чтения Дейвиса]] — Spell-Reading, Sweep-Sweep-Spell, Пунктуация в образах: пошагово
- [[clusters/temporalnaya-obrabotka-fastforword|Темпоральная обработка и Fast ForWord]] — ось времени: что честно реализуемо на штатном TTS
- [[clusters/vizualnoe-myshlenie-i-obrazy|Визуальное мышление и образы]] — образ как диагностический инструмент, не как украшение
- [[clusters/trebovaniya-zakazchika-k-ui|Требования заказчика к UI]] — требования против кодовой базы и ограничений Android
- [[clusters/dokazatelnaya-baza-i-cifry|Доказательная база и цифры]] — единственное исследование набора и его дизайн измерения
- [[clusters/eticheskie-granicy-i-formulirovki|Этические границы формулировок]] — что нельзя обещать и как называть свои техники

## Концепции (решения сверх источников)

- [[concepts/focus-reading-method|«Одно слово — один образ»: метод чтения Sulu Read Focus]] — лестница из пяти ступеней, голосовой шлюз, адаптивный темп, проверка образа на границе сцены

## Вопросы

Пока нет. Сохранённые ответы появятся в `wiki/questions/`.

## Заготовки артефактов

Пока нет. Появятся в `wiki/content-ideas/`.
