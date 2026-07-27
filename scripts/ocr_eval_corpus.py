"""Ground-truth snippets for OCR accuracy evaluation.

Short school-textbook style sentences written for this repository. Each entry
is its own ground truth; nothing here is copied from a published textbook.
"""

CORPUS = [
    {"id": "kk-01", "language_hint": "kk", "text": "Мен мектепке барамын."},
    {"id": "kk-02", "language_hint": "kk", "text": "Қазақстан — біздің Отанымыз."},
    {"id": "kk-03", "language_hint": "kk", "text": "Бүгін ауа райы жақсы."},
    {"id": "kk-04", "language_hint": "kk", "text": "Оқушылар кітап оқиды."},
    {"id": "kk-05", "language_hint": "kk", "text": "Ана тілі — халықтың жаны."},
    {"id": "kk-06", "language_hint": "kk", "text": "Біздің сыныпта отыз оқушы бар."},
    {"id": "kk-07", "language_hint": "kk", "text": "Мұғалім тақтаға жазды."},
    {"id": "kk-08", "language_hint": "kk", "text": "Күн шығып, таң атты."},
    {"id": "kk-09", "language_hint": "kk", "text": "Ағаштың жапырақтары сарғайды."},
    {"id": "kk-10", "language_hint": "kk", "text": "Балалар аулада ойнап жүр."},
    {"id": "kk-11", "language_hint": "kk", "text": "Әжем маған ертегі айтты."},
    {"id": "kk-12", "language_hint": "kk", "text": "Өзен жағасында құстар ұшады."},
    {"id": "kk-13", "language_hint": "kk", "text": "Ұлы дала — байлығымыз."},
    {"id": "kk-14", "language_hint": "kk", "text": "Түлкі қуып, қоян қашты."},
    {"id": "kk-15", "language_hint": "kk", "text": "Жаңбыр жауып, жер көгерді."},
    {"id": "kk-16", "language_hint": "kk", "text": "Дәптерге тапсырманы жаздым."},
    {"id": "kk-17", "language_hint": "kk", "text": "Қыстың күні қысқа болады."},
    {"id": "kk-18", "language_hint": "kk", "text": "Үйде әкем кітап оқып отыр."},
    {"id": "kk-19", "language_hint": "kk", "text": "Астана — еліміздің астанасы."},
    {"id": "kk-20", "language_hint": "kk", "text": "Алматы қаласында тау бар."},
    # -ын/-ін possessive-accusative (3rd-person possessive "-ы"/"-і" plus
    # accusative "-н"): a productive, correctly-spelled pattern that a
    # string-pattern rule cannot distinguish from a flattened genitive
    # "-ның"/"-дың" on stems ending in н/д. Added after a deleted repair
    # rule was found rewriting these into a different, wrong word (see
    # docs/superpowers/specs/2026-07-26-kazakh-russian-ocr-accuracy-design.md).
    {"id": "kk-21", "language_hint": "kk", "text": "Ол телефонын үстелге қойды."},
    {"id": "kk-22", "language_hint": "kk", "text": "Кітаптың орнын тауып алдым."},
    {"id": "kk-23", "language_hint": "kk", "text": "Ол кітабын сөмкеге салды."},
    {"id": "kk-24", "language_hint": "kk", "text": "Асан жанын аямай еңбек етті."},
    {"id": "ru-01", "language_hint": "ru", "text": "Мы идём в школу каждый день."},
    {"id": "ru-02", "language_hint": "ru", "text": "Ученики читают новую книгу."},
    {"id": "ru-03", "language_hint": "ru", "text": "Учитель написал задание на доске."},
    {"id": "ru-04", "language_hint": "ru", "text": "Сегодня хорошая погода."},
    {"id": "ru-05", "language_hint": "ru", "text": "В нашем классе тридцать учеников."},
    {"id": "ru-06", "language_hint": "ru", "text": "Осенью листья становятся жёлтыми."},
    {"id": "ru-07", "language_hint": "ru", "text": "Дети играют во дворе."},
    {"id": "ru-08", "language_hint": "ru", "text": "Бабушка рассказала мне сказку."},
    {"id": "ru-09", "language_hint": "ru", "text": "Река течёт через город."},
    {"id": "ru-10", "language_hint": "ru", "text": "Зимой дни становятся короче."},
    {"id": "mx-01", "language_hint": "kk", "text": "Қазақстан Республикасы, 1991 жыл."},
    {"id": "mx-02", "language_hint": "kk", "text": "Сабақ 5. Менің отбасым."},
    {"id": "mx-03", "language_hint": "kk", "text": "§ 12. Табиғат және адам."},
    {"id": "mx-04", "language_hint": "kk", "text": "Мұғалім: «Кітапты ашыңдар», — деді."},
    {"id": "mx-05", "language_hint": "kk", "text": "Алматы — Astana аралығы 1200 км."},
    {"id": "mx-06", "language_hint": "kk", "text": "Оқулық: Ана тілі, 3-сынып."},
    {"id": "mx-07", "language_hint": "ru", "text": "Домашнее задание: упражнение 7."},
    {"id": "mx-08", "language_hint": "kk", "text": "Тест: A, B, C, D нұсқалары."},
    {"id": "mx-09", "language_hint": "kk", "text": "Жаттығу 15. Сөздерді буынға бөл."},
    {"id": "mx-10", "language_hint": "kk", "text": "Физика пәні бойынша сабақ кестесі."},
    # Ordinary Russian sentences under the *default* language_hint="kk"
    # (POST /v1/adapt-image defaults to "kk" -- see main.py). Without rows
    # like these the clean-input no-op gate cannot see a Russian page
    # scanned with no hint override, which is exactly the case a Critical
    # review finding was found in: real Russian words such as "доска",
    # "кошка", "шапка", "бумага", "карман" have a Kazakh-restored form that
    # is also a real Kazakh word, so the lexicon repair rewrote them under
    # the "kk" hint until the Russian-lexicon guard was added.
    {"id": "rk-01", "language_hint": "kk", "text": "Учитель написал задание на доске."},
    {"id": "rk-02", "language_hint": "kk", "text": "Бумага и карандаш лежат в кармане пальто."},
    {"id": "rk-03", "language_hint": "kk", "text": "Кошка спит на стуле, а рядом висит шапка."},
]
