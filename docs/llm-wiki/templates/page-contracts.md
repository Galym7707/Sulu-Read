# Контракты страниц (frontmatter + обязательные секции)

Общие правила: язык страницы = язык источников; каждая страница достижима из `wiki/index.md`;
секция Related в конце — wikilinks на соседей (минимум 2, иначе страница станет orphan).

## source_note → `wiki/sources/<name>.md`
```yaml
---
type: source_note
source_name: <имя для маркеров цитирования>
status: ingested
confidence: high
last_updated: YYYY-MM-DD
---
```
Секции: **Profile** (что за документ, откуда, объём) · **Core Concepts** (таблица: концепт →
суть → куда внедрено в vault) · **Принято / Отклонено** (что из документа взято в работу,
что отброшено и почему) · **Fact-check** (если внешние утверждения проверялись) · **Related**.

## cluster_page → `wiki/clusters/<slug>.md`
```yaml
---
type: cluster_page
cluster_id: <slug>
status: active
confidence: high|medium
last_updated: YYYY-MM-DD
tags: [<метки из таксономии манифеста>]
---
```
Секции: **Summary** (2–3 предложения) · **Evidence** (таблица: Source | Section | Quote/Rule |
Signal — каждая строка с точной цитатой) · **Implications** (что это значит для проекта;
допущения помечены) · **Related**.

## question_page → `wiki/questions/q<N>-<slug>.md`
```yaml
---
type: question_page
question_id: q<N>-<slug>
status: answered
confidence: high|medium
last_updated: YYYY-MM-DD
---
```
Секции: **заголовок-вопрос** · **Short Answer** (прямой ответ, 3–6 предложений, с маркерами) ·
**Evidence** (таблица: Finding | Evidence | Confidence) · **Recommended Actions** (если уместно) ·
**Related**. Errata-секция сверху, если ответ исправляет прежнюю версию.

## concept_page → `wiki/concepts/<slug>.md`
```yaml
---
type: concept_page
concept_id: <slug>
status: active
confidence: high|medium
last_updated: YYYY-MM-DD
tags: [<метки>]
---
```
Для проектных решений СВЕРХ источников. Секции: **Summary** · содержательные секции решения ·
**Related**. Всё, чего нет в источниках, помечается «Strategic addition» / «допущение» —
concept_page не освобождает от честности, она лишь легализует выход за рамки источника.

## content_idea → `wiki/content-ideas/<slug>.md`
```yaml
---
type: content_idea
idea_id: <slug>
status: draft
confidence: high|medium
last_updated: YYYY-MM-DD
---
```
Заготовка внешнего артефакта (питч, статья, план). Секции: **Audience/Pain** · поэлементная
структура артефакта (послайдово/посекционно) с маркерами цитирования · **Risks / What Not To
Promise** · **Related**.

## index.md
Заголовок проекта + секция «Системные артефакты» (wikilinks на log, lint-report, source notes) +
секции по типам страниц, каждая страница — wikilink с однострочным описанием.
Обновляется при каждом ингесте; страница, не достижимая из index, — orphan и провалит lint.

## log.md
```
## [YYYY-MM-DD] <op> | <title>
- маркированные пункты: что сделано, какие страницы созданы/изменены, нерешённые вопросы
```
`<op>` ∈ ingest | query | refactor | lint | setup | context. Новые записи СВЕРХУ под шапкой.
Append-only: старые записи не редактируются.

## lint-report.md
Заголовок с датой прогона; секции: Structural (links/orphans) · Evidence (цитаты/числа) ·
Taxonomy · Scope/Never-Do · Fixes Applied · Open Suggestions. Новый прогон сверху, старые ниже.
