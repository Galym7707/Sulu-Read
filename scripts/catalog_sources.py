"""What goes in the reading catalogue.

Every entry is a work whose author died long enough ago that it is public domain in Kazakhstan
(life + 70 years), and every entry is on the Kazakhstan school reading list. Works still in
copyright are deliberately absent — Auezov, Nurpeisov, Musrepov and the modern Russian-language
set texts cannot be redistributed, and a reader who needs one of those can open their own copy
through the file upload instead.

`grade` is the school year the work is usually met in, and is a guide for sorting rather than a
claim about any particular curriculum edition.
"""

from __future__ import annotations

from dataclasses import dataclass

RU_HOST = "ru.wikisource.org"
# Kazakh has no Wikisource of its own; its texts live on the multilingual one.
KK_HOST = "wikisource.org"


@dataclass(frozen=True)
class CatalogSource:
    book_id: str
    host: str
    page: str
    title: str
    author: str
    author_died: int
    language: str
    grade: int


SOURCES: tuple[CatalogSource, ...] = (
    # --- Kazakh -------------------------------------------------------------------------
    CatalogSource("kk-altynsarin-ozen", KK_HOST, "Өзен", "Өзен",
                  "Ыбырай Алтынсарин", 1889, "kk", 2),
    CatalogSource("kk-altynsarin-ananyn-suiui", KK_HOST, "Ананың сүюі", "Ананың сүюі",
                  "Ыбырай Алтынсарин", 1889, "kk", 2),
    CatalogSource("kk-altynsarin-bul-kim", KK_HOST, "Бұл кім?", "Бұл кім?",
                  "Ыбырай Алтынсарин", 1889, "kk", 1),
    CatalogSource("kk-zhumabaev-zhastarga", KK_HOST, "Мен жастарға сенемін",
                  "Мен жастарға сенемін", "Мағжан Жұмабаев", 1938, "kk", 7),
    CatalogSource("kk-zhumabaev-kazak-tili", KK_HOST, "Қазақ тілі", "Қазақ тілі",
                  "Мағжан Жұмабаев", 1938, "kk", 5),
    CatalogSource("kk-zhumabaev-tutkyn", KK_HOST, "Тұтқын", "Тұтқын",
                  "Мағжан Жұмабаев", 1938, "kk", 8),
    CatalogSource("kk-abai-nazarga", KK_HOST, "Назарға", "Назарға",
                  "Абай Құнанбайұлы", 1904, "kk", 6),
    CatalogSource("kk-abai-kun-artynan", KK_HOST, "Күн артынан күн туар",
                  "Күн артынан күн туар", "Абай Құнанбайұлы", 1904, "kk", 6),
    CatalogSource("kk-abai-kushik", KK_HOST, "Күшік асырап, ит еттім",
                  "Күшік асырап, ит еттім", "Абай Құнанбайұлы", 1904, "kk", 7),
    CatalogSource("kk-abai-suisine", KK_HOST, "Сүйсіне алмадым, сүймедім",
                  "Сүйсіне алмадым, сүймедім", "Абай Құнанбайұлы", 1904, "kk", 8),

    # --- Russian ------------------------------------------------------------------------
    CatalogSource("ru-krylov-vorona", RU_HOST, "Ворона и Лисица (Крылов)",
                  "Ворона и Лисица", "Иван Крылов", 1844, "ru", 3),
    CatalogSource("ru-krylov-strekoza", RU_HOST, "Стрекоза и Муравей (Крылов)",
                  "Стрекоза и Муравей", "Иван Крылов", 1844, "ru", 3),
    CatalogSource("ru-krylov-kvartet", RU_HOST, "Квартет (Крылов)",
                  "Квартет", "Иван Крылов", 1844, "ru", 4),
    CatalogSource("ru-krylov-lebed", RU_HOST, "Лебедь, Щука и Рак (Крылов)",
                  "Лебедь, Щука и Рак", "Иван Крылов", 1844, "ru", 3),
    CatalogSource("ru-krylov-slon", RU_HOST, "Слон и Моська (Крылов)",
                  "Слон и Моська", "Иван Крылов", 1844, "ru", 4),
    CatalogSource("ru-pushkin-rybak", RU_HOST, "Сказка о рыбаке и рыбке (Пушкин)",
                  "Сказка о рыбаке и рыбке", "Александр Пушкин", 1837, "ru", 2),
    CatalogSource("ru-pushkin-zimnee-utro", RU_HOST, "Зимнее утро (Пушкин)",
                  "Зимнее утро", "Александр Пушкин", 1837, "ru", 5),
    CatalogSource("ru-lermontov-parus", RU_HOST, "Парус (Лермонтов)",
                  "Парус", "Михаил Лермонтов", 1841, "ru", 6),
    CatalogSource("ru-lermontov-borodino", RU_HOST, "Бородино (Лермонтов)",
                  "Бородино", "Михаил Лермонтов", 1841, "ru", 5),
    CatalogSource("ru-tolstoy-filipok", RU_HOST, "Филипок (Толстой)",
                  "Филипок", "Лев Толстой", 1910, "ru", 2),
    CatalogSource("ru-tolstoy-lev-i-sobachka", RU_HOST, "Лев и собачка (Толстой)",
                  "Лев и собачка", "Лев Толстой", 1910, "ru", 3),
    CatalogSource("ru-chekhov-vanka", RU_HOST, "Ванька (Чехов, 1886)",
                  "Ванька", "Антон Чехов", 1904, "ru", 6),
    CatalogSource("ru-ushinsky-chetyre-zhelaniya", RU_HOST, "Четыре желания (Ушинский)",
                  "Четыре желания", "Константин Ушинский", 1870, "ru", 2),
)

# Kazakhstan follows life + 70. Anything later than this has to stay out of the catalogue.
PUBLIC_DOMAIN_DEATH_YEAR_LIMIT = 1955


def public_domain_sources() -> tuple[CatalogSource, ...]:
    return tuple(s for s in SOURCES if s.author_died <= PUBLIC_DOMAIN_DEATH_YEAR_LIMIT)
