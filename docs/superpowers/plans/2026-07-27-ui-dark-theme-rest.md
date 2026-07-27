# Тёмная тема на остальных экранах через глобальный UI-kit — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Перевести оставшиеся 24 фронтовых компонента на семантические CSS-токены, чтобы тёмная тема перестала давать светлые пятна, — вынеся дублирующиеся примитивы в глобальный UI-kit вместо ~845 точечных замен.

**Architecture:** В `frontend/src/styles.scss` добавляется слой примитивов на токенах (кнопки, бейджи, базовые элементы, общие утилиты). Angular-scoped стиль компилируется в `.btn[_ngcontent-abc]` (специфичность 0,2,0) и всегда перебивает глобальный `.btn` (0,1,0), поэтому kit вливается без визуального эффекта и «просыпается» по мере того, как из компонентов удаляют локальные дубли. Дальше — четыре волны по-экранной зачистки, каждая своей веткой с мержем и пушем в прод.

**Tech Stack:** Angular 21 (standalone-компоненты, инлайн `template` + `styles: []`), SCSS, CSS Custom Properties, `color-mix(in srgb, …)`. Тестов на фронте нет — гейт составляют `npm run build` и живая проверка в браузере через Playwright MCP.

**Спека:** `docs/superpowers/specs/2026-07-27-ui-dark-theme-rest-design.md`

---

## Global Constraints

Требования этого раздела действуют для **каждой** задачи плана.

1. **Только фронт.** Ни строки в `src/` (бэкенд). Проверка перед мержем волны: `git diff main...HEAD -- src/` — пусто.
2. **Только цвет и общая геометрия примитивов.** Редизайна макетов, типографики, отступов страниц, переделки таблиц в карточки — нет.

   **Но унификация из kit — санкционирована оператором.** Там, где экраны сейчас расходятся между собой в геометрии одного и того же примитива (у `.btn` встречаются `6px 14px`, `8px 16px`, `8px 18px`; у `.btn-primary` радиусы `4px`/`6px`/`8px`; у `.badge` формы `10px`/`999px`), приведение к единому виду kit — **ожидаемый результат, а не дефект**. Изменение вида в светлой теме на таких экранах допустимо и предусмотрено. Не считать это нарушением «редизайна нет»: запрещён редизайн макета и типографики, а не выравнивание разъехавшихся примитивов.
3. **Мобильную эргономику НЕ чиним.** Найденные на 390px проблемы фиксируются скриншотом в отчёт волны, а не правятся.
4. **`@media` — последним блоком в `styles`** (CLAUDE.md §14: при равной специфичности позднее базовое правило молча перебивает раннюю @media-переопределялку; ловили на `.stat-cards` дашборда).
5. **Не хардкодить новые хексы.** Исключения — элементы, которые лежат поверх произвольного фона и держат белый текст на насыщенной заливке; токенизация им вредит, потому что в тёмной теме `--success`/`--danger` осветлены и контраст с белым падает ниже 3:1:
   - фикс-цветные кнопки, уже одобренные оператором: `#0e9f6e` («КП»), `#6366f1` («ТЗ»), `#8b5cf6` (bulk);
   - заливки тостов: `#059669` / `#dc2626` / `#1a56db` (Task 3).

   У всех них меняется только `color: #fff` → `var(--accent-contrast)` и тень → `var(--shadow)`. Рядом оставлять комментарий с причиной, иначе следующий разработчик «дочинит» это до токенов и сломает контраст.
6. **Коммит заканчивать** строкой `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` (CLAUDE.md §3).
7. **Bash cwd персистит между вызовами** (CLAUDE.md §14): `cd frontend && npm run build` оставляет cwd во `frontend`. Команды git/gradlew — компаундом от корня: `cd /Users/vlad/IdeaProjects/AIS && …`.
8. **Оверлей ошибок `ng serve` залипает и врёт** (CLAUDE.md §14). Если оверлей показывает ошибку — не верить: проверить `git status`, `npm run build` и перезагрузить страницу.
9. **Работать на ветке волны, не на `main`.** Ветку создаёт контроллер перед первой задачей волны — исполнителю она уже дана, переключаться и создавать ветки самому не нужно.
10. **На фронте нет тестового набора — и это не дефект задачи.** Гейт фронта в этом репозитории: `npm run build` + живая проверка в браузере (CLAUDE.md §13: «Фронт — тестов нет, гейт = `npm run build`»). Задачи этого плана меняют только CSS внутри `styles: []`. Писать юнит-тесты на цвета не нужно и не просят; отсутствие новых тестов не является замечанием. Доказательство работоспособности — зелёная сборка и скриншоты живой проверки, которую делает контроллер.
11. **Не трогать шаблоны (`template:`) и TypeScript-логику.** Задачи меняют только содержимое блока `styles: [` … `]`. Если кажется, что нужно поправить разметку — это сигнал остановиться и написать об этом в отчёте, а не менять.

### Таблица соответствия hex → токен (обязательная, единая)

Извлечена из фактических коммитов `81b3baa`/`b8a8e14`. Отклоняться нельзя — иначе приложение расслоится на два цветовых диалекта.

| Хекс | Токен |
|---|---|
| `#111827`, `#374151` | `var(--text)` |
| `#6b7280`, `#9ca3af`, `#4b5563` | `var(--text-muted)` |
| `#e5e7eb`, `#d1d5db`, `#eee`, `#f0f0f0` | `var(--border)` |
| `#f3f4f6`, `#f9fafb` | `var(--surface-2)` |
| `#fff`, `#ffffff` | `var(--surface)` — если это **фон**; `var(--accent-contrast)` — если это **текст на цветной кнопке** |
| `#1a56db`, `#1e40af`, `#1e3a8a`, `#2563eb`, `#4f46e5` | `var(--accent)` |
| `#ef4444`, `#dc2626`, `#b91c1c`, `#991b1b` | `var(--danger)` |
| `#f59e0b`, `#92400e`, `#b45309` | `var(--warn)` |
| `#059669`, `#065f46`, `#10b981`, `#047857`, `#166534`, `#0e9f6e`\* | `var(--success)` |
| `box-shadow: 0 2px 8px rgba(0,0,0,.08)` | `var(--shadow)` |

\* `#0e9f6e` — только там, где это НЕ фикс-цветная кнопка «КП» (см. ограничение 5).

**Светлые тинты** (`#dbeafe`, `#eff6ff`, `#fef3c7`, `#d1fae5`, `#fee2e2`, `#fef2f2`, `#ecfdf5`, `#dcfce7`, `#eef2ff`, `#a7f3d0`, `#bbf7d0`, `#e0e7ff`, `#f0fdf4`) — **по роли, а не по значению**:

- **чип / бейдж / мелкий элемент:**
  `background: color-mix(in srgb, var(--TOKEN) 15%, transparent); color: var(--TOKEN);`
- **подсветка области** (строка таблицы, карточка, панель):
  `background: color-mix(in srgb, var(--TOKEN) 8%, var(--surface)); border-color: var(--TOKEN);`

> ⚠️ **Урок, оплаченный багом `6b8dc3f`.** Подсветку строки `tr.kp-hit td { background:#ecfdf5 }` перевели наивно в `var(--surface-2)` — она совпала с фоном самой панели, преотмеченные (сильнейшие) поставщики утонули в фоне, а слабые светились белым. Смысл инвертировался, чинили отдельным коммитом. Поэтому подсветка области смешивается с `--surface` (непрозрачно) и подкрепляется рамкой, а **не** заменяется на `--surface-2`.

### Процедура зачистки одного компонента (применяется в задачах 3–13)

1. Открыть блок `styles: [` … `]` компонента.
2. **Удалить** правила, которые теперь даёт kit **и которые совпадают с kit дословно**: `.btn` (база), `.btn-primary/add/save/open/login/search`, `.btn-cancel/reset/reset-filter`, `.btn-back`, `.btn-delete/danger/pdf`, `.btn-edit`, `.btn-line`, `.badge` (база), `.badge-<СТАТУС>`, `.empty`, `.subtitle`, `.counter`, `.field-error`, `.error-banner`, `.form-actions`, `th` (фон и цвет текста).

   ⚠️ **Три исключения, проверенные на эталоне в Волне 0 — их удалять НЕЛЬЗЯ:**
   - **`th, td` с рамкой.** Kit даёт только `border-color`, а не саму рамку. Компонентное `th, td { border-bottom: 1px solid … }` несёт геометрию — удалишь, и линии таблицы исчезнут. Правило оставить, хекс в нём перевести на `var(--border)`.
   - **`tr:hover`.** В kit такого правила нет вообще. Оставить и токенизировать (`var(--surface-2)`).
   - **`.btn-close`.** Намеренно не в kit: единственное использование — прозрачный крестик модалки, kit превратил бы его в кнопку с заливкой. Токенизировать на месте.
   ⚠️ **Побочный эффект удаления, о котором надо знать (найден ревью Волны 0): оживают `:hover` из kit.** Раньше локальное `.btn-add` (0,2,0) выигрывало у kit-овского `.btn-add:hover` (тоже 0,2,0) **по порядку** — Angular вставляет стили компонента в `<head>` после глобального листа. Удалив локальное правило, ты включаешь hover из kit: кнопки начинают темнеть/светлеть при наведении. Обычно это улучшение и так задумано. Но в этом коде местами **осознанно** писали «hover, повторяющий базовый фон» (пример: `.btn-accept-pr:hover`, `.btn-create-apply:hover` в `tenders.component.ts`) — если у кнопки был свой `:hover`, равный её обычному фону, значит отсутствие подсветки было намеренным: сохрани такое правило локально.

3. **Сохранить локально** (kit их не задаёт — иначе поедет раскладка):
   - любые `margin` / `flex` / `width` / `position` с этих классов; пример: было `.btn-edit { background:#f59e0b; color:#fff; margin-right:4px; }` → остаётся `.btn-edit { margin-right: 4px; }`;
   - геометрию, если она осознанно отличается от kit (см. список расхождений в задачах);
   - доменные переопределения цвета (пример: `.badge-CANCELLED` в `applies` — нейтральный, а не красный).
4. **Перевести** оставшиеся хексы по таблице выше.
5. Проверить, что `@media` остался последним блоком.
6. `cd frontend && npm run build` — зелёный.

### Гейт волны (обязателен перед мержем)

1. `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёный, без превышения бюджета `anyComponentStyle` (24 kB).
2. `cd /Users/vlad/IdeaProjects/AIS && git diff main...HEAD -- src/` — пусто.
3. Живая проверка Playwright: каждый тронутый экран в **4 состояниях** — 1280px и 390px × светлая и тёмная тема.
   - Логин `admin`/`admin` на `http://localhost:4200`. Сессия слетает при рестарте бэка — логиниться заново.
   - Тема переключается тумблером ☀/☾ в шапке; можно и `localStorage.setItem('ais.theme','dark')` + reload.
   - Рынок: `localStorage.setItem('ais.market','KZ')` + reload — для KZ-специфичных экранов.
   - Ширина — `browser_resize`.
   - Refs в снапшотах устаревают: снимать свежий снапшот прямо перед кликом.
4. Скриншоты проблемных мобильных мест — в отчёт волны (не чинить).

---

## Волна 0 — UI-kit (ветка `feature/ui-kit`)

### Task 1: Слой примитивов в `styles.scss`

**Files:**
- Modify: `frontend/src/styles.scss` (добавить новый блок перед секцией «Адаптив под мобилку», т.е. после правила `body { … }`)

**Interfaces:**
- Produces: глобальные классы `.btn`, `.btn-primary|add|save|open|login|search`, `.btn-cancel|back|close|reset|reset-filter`, `.btn-delete|danger|pdf`, `.btn-edit`, `.btn-line`, `.badge`, `.badge-DRAFT|ACTIVE|SUBMITTED|COMPLETED|WON|LOST|CANCELLED|REJECTED|UNDER_REVIEW`, `.badge-pr-CREATED|SENT|RESPONDED|ACCEPTED|DECLINED|CLOSED`, `.badge-d-NONE|ORDERED|DELIVERED|PAID`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`; цветовые дефолты для `h2/h3`, `th/td`, `input/select/textarea`. Задачи 2–13 удаляют локальные дубли этих правил и полагаются на них.

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/ui-kit
```

- [ ] **Step 2: Зафиксировать эталон «до»**

До правки снять скриншоты страницы тендеров, чтобы потом было с чем сравнивать. Playwright: логин, `http://localhost:4200/tenders`, 1280px светлая и тёмная — два скриншота в `.playwright-mcp/`. Это референс для Step 5.

- [ ] **Step 3: Вставить слой kit в `frontend/src/styles.scss`**

Вставить сразу после блока `body { … }` и **до** комментария `/* ===== Адаптив под мобилку … */`:

```scss
/* ============================================================
   UI-kit: общие примитивы на семантических токенах.

   Зачем: те же правила были скопированы по компонентам (.empty в 14 файлах,
   .subtitle в 13, .btn-cancel в 12, база th,td в 11, .badge* в 11) — из-за
   этого тёмная тема требовала 24 одинаковых правки, а новый экран снова
   приносил хексы.

   Специфичность: Angular-scoped правило компилируется в `.btn[_ngcontent-x]`
   (0,2,0) и перебивает глобальный `.btn` (0,1,0). Значит этот слой не влияет
   ни на что, пока в компоненте лежит собственный блок, и «просыпается» ровно
   тогда, когда локальный дубль удалили. Элементные правила (0,0,1) перебивает
   вообще любое компонентное.

   Правило дополнения: цвет и общая геометрия примитива — здесь; раскладка
   (margin/flex/width/position) — в компоненте.
   ============================================================ */

/* --- базовые элементы: ТОЛЬКО цвет, без геометрии --- */
h2, h3 { color: var(--text); }
th, td { border-color: var(--border); }
th { background: var(--surface-2); color: var(--text-muted); }
input, select, textarea { background: var(--surface); color: var(--text); border-color: var(--border); }

/* --- кнопки --- */
.btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }

.btn-primary, .btn-add, .btn-save, .btn-open, .btn-login, .btn-search {
  background: var(--accent); color: var(--accent-contrast);
}
.btn-primary:hover, .btn-add:hover, .btn-save:hover,
.btn-open:hover, .btn-login:hover, .btn-search:hover { background: var(--accent-hover); }

.btn-cancel, .btn-back, .btn-close, .btn-reset, .btn-reset-filter {
  background: var(--surface-2); color: var(--text);
}
.btn-cancel:hover, .btn-back:hover, .btn-close:hover,
.btn-reset:hover, .btn-reset-filter:hover { background: color-mix(in srgb, var(--text) 12%, var(--surface-2)); }

.btn-delete, .btn-danger, .btn-pdf { background: var(--danger); color: var(--accent-contrast); }
.btn-edit { background: var(--warn); color: var(--accent-contrast); }
.btn-line { background: var(--surface); color: var(--text); border: 1px solid var(--border); }

/* --- бейджи: тинт семантического токена, читается в обеих темах --- */
.badge { display: inline-block; padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }

.badge-DRAFT, .badge-pr-CREATED { background: var(--surface-2); color: var(--text); }
.badge-pr-CLOSED, .badge-d-NONE  { background: var(--surface-2); color: var(--text-muted); }

.badge-ACTIVE, .badge-SUBMITTED, .badge-pr-SENT, .badge-d-DELIVERED {
  background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent);
}
.badge-COMPLETED, .badge-WON, .badge-d-PAID {
  background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success);
}
.badge-pr-ACCEPTED {
  background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); font-weight: 700;
}
.badge-UNDER_REVIEW, .badge-pr-RESPONDED, .badge-d-ORDERED {
  background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn);
}
.badge-CANCELLED, .badge-REJECTED, .badge-LOST {
  background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger);
}
.badge-pr-DECLINED {
  background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); font-weight: 700;
}

/* --- общие утилиты страниц --- */
.subtitle { color: var(--text-muted); font-size: 13px; margin: 4px 0 16px; }
.counter { color: var(--text-muted); font-size: 13px; }
.empty { color: var(--text-muted); font-size: 14px; padding: 32px 0; text-align: center; }
.field-error { display: block; color: var(--danger); font-size: 12px; margin-top: 2px; }
.error-banner {
  background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger);
  padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px;
}
.form-actions { margin-top: 16px; }
```

- [ ] **Step 4: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```
Ожидаемо: зелёная сборка. Слой глобальный, в бюджет `anyComponentStyle` не входит.

- [ ] **Step 5: Проверить, что уже готовые экраны НЕ изменились**

Проверено скриптом при планировании: ни один токенизированный компонент (`tenders`, `tender-lots`, `lot-kp-panel`, `lot-registry-panel`, `tenders-filters`, `layout`) не использует kit-класс как отдельный токен без собственного объявления — все их классы либо объявлены, либо это дефисные имена (`btn-close-pr`, `score-badge`, `lrp-empty`, `result-subtitle`), которых kit не касается.

Подтвердить живьём: `/tenders` на 1280px в светлой и тёмной — сравнить со скриншотами Step 2. Отличий быть не должно.

- [ ] **Step 6: Проверить экраны, которые kit «разбудит» досрочно**

Эти пять полагаются на kit-класс, которого сами не объявляют, — они изменятся уже сейчас, до своей волны. Проверить, что изменение к лучшему (было: браузерный дефолт / без стиля), а не поломка:

| Экран | URL | Что разбудится |
|---|---|---|
| `equipment-types` | `/equipment-types` | `.btn-save` — станет синей акцентной |
| `login` | `/login` (выйти из сессии) | `.btn` — база геометрии |
| `private-requests` | `/private-requests` | `.empty` — пустое состояние |
| `registry-reconciliation` | `/registry-reconciliation` | `.empty` |
| `reports` | `/reports` | `.btn` — база геометрии |

Плюс `inbound` (`/inbound`) — единственный из непереведённых, у кого `<table>` без собственных табличных стилей: теперь `th` получит фон `--surface-2` и приглушённый текст. Убедиться, что шапка таблицы читается в обеих темах.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/styles.scss && git commit -F - <<'EOF'
feat(ui): глобальный UI-kit примитивов на токенах

Те же правила были скопированы по компонентам: .empty в 14 файлах,
.subtitle в 13, .btn-cancel в 12, база th,td в 11, .badge* в 11. Из-за
этого тёмная тема требовала 24 одинаковых правки, а каждый новый экран
приносил хексы заново.

Слой не меняет вид ничего: Angular-scoped правило (0,2,0) перебивает
глобальное (0,1,0), поэтому kit спит под каждым компонентом и просыпается
ровно тогда, когда локальный дубль удалят.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
```

---

### Task 2: Зачистка эталонных компонентов — проверка точности kit

Смысл задачи: страница тендеров уже на токенах и в проде. Если убрать из неё локальные дубли и **ничего не сдвинется**, kit извлечён верно и на него можно переводить остальные 18 компонентов. Если сдвинется — чинить надо kit, и дёшево это сделать именно сейчас.

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`
- Modify: `frontend/src/app/pages/tenders/tender-lots.component.ts`
- Modify: `frontend/src/app/pages/tenders/lot-kp-panel.component.ts`
- Modify: `frontend/src/app/pages/tenders/lot-registry-panel.component.ts`

**Interfaces:**
- Consumes: все классы kit из Task 1.

- [ ] **Step 1: Удалить дубли в `tenders.component.ts`**

Удалить правила, совпадающие с kit: `.btn` (база), `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-back`, `.btn-delete`, `.btn-edit`, `.btn-line`, `.badge` (база), `.badge-DRAFT`, `.badge-ACTIVE`, `.badge-COMPLETED`, `.badge-CANCELLED`, `.badge-pr-*`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`, `th`, `tr:hover`.

Сохранить: `.btn-close-pr`, `.btn-add-bulk`, `.btn-kp`, `.btn-tz`, `.btn-kp-selected` (фикс-цветные и доменные), любые `margin-*` с удаляемых классов — вынести отдельной строкой.

- [ ] **Step 2: Удалить дубли в остальных трёх компонентах**

- `tender-lots.component.ts`: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.counter`, `.field-error`, `.error-banner`, `.form-actions`, `.badge-*`.
- `lot-kp-panel.component.ts`: `.btn`, `.btn-save`, `.btn-cancel`, `.btn-line`.
- `lot-registry-panel.component.ts`: `.btn`, `.btn-primary`, `.btn-cancel`.

Не трогать `.sup-hit`, `.kp-hit`, `.lrp-empty`, `.kp-empty`, `.lots-empty`, `.score-badge`, `.badge-proposed`, `.badge-reg-ok` — это доменные классы, kit их не покрывает.

- [ ] **Step 3: Проверить на дубли и собрать**

Правка больших файлов иногда даёт дубль метода или съеденную соседнюю строку (CLAUDE.md §14):

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 4: Живая сверка с эталоном**

`/tenders` в 4 состояниях (1280/390 × светлая/тёмная) + открыть карточку тендера (`?openId=<id>` KZ-тендера) и развернуть лот с панелями «Подбор» и «КП». Сравнить со скриншотами Task 1 Step 2.

**Критерий:** отличий нет. Если что-то сдвинулось — **править kit в `styles.scss`**, а не возвращать локальный блок (иначе расхождение уедет во все остальные волны).

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/ && git commit -F - <<'EOF'
refactor(ui): убрать из страницы тендеров дубли, покрытые UI-kit

Проверка точности kit на эталоне: страница тендеров уже была на токенах,
после удаления локальных копий .btn*/.badge*/th,td/.empty/.subtitle её вид
не изменился — значит kit извлечён верно и на него можно переводить
остальные экраны.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
```

- [ ] **Step 6: Гейт волны, мерж, пуш**

Прогнать «Гейт волны» из Global Constraints, затем:

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --no-ff feature/ui-kit -m "Merge: глобальный UI-kit примитивов на токенах" && git branch -d feature/ui-kit && git push origin main
```

Пуш = автодеплой в прод (~3–5 мин). Дождаться и проверить, что прод поднялся.

---

## Волна 1 — то, что открывается поверх тендеров, + глобальные оверлеи (ветка `feature/ui-dark-wave1`)

После этой волны страница тендеров выглядит законченной, а `confirm`/`notification` перестают светить белым **на всех** страницах приложения.

### Task 3: Глобальные оверлеи — `confirm` и `notification`

**Files:**
- Modify: `frontend/src/app/components/confirm/confirm.component.ts`
- Modify: `frontend/src/app/components/notification/notification.component.ts`

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/ui-dark-wave1
```

- [ ] **Step 2: `confirm.component.ts`**

Применить «Процедуру зачистки компонента». Конкретно:

- Удалить `.btn`, `.btn-primary`, `.btn-primary:hover`, `.btn-cancel`, `.btn-cancel:hover`, `.btn-danger` — их даёт kit.
- **Расхождение геометрии, требующее решения:** локальный `.btn { padding: 8px 16px; font-size: 14px; font-weight: 500; }` крупнее kit (`6px 14px`, `13px`). Это модалка подтверждения, крупная кнопка там осмысленна. Оставить локально **только геометрию**, без цвета:
  ```scss
  .btn { padding: 8px 16px; font-size: 14px; font-weight: 500; }
  ```
- Перевести оставшееся:
  ```scss
  .confirm-overlay { background: rgba(17, 24, 39, 0.5); }   /* оставить как есть — затемнение фона, не поверхность */
  .confirm-modal { background: var(--surface); box-shadow: var(--shadow); }
  .confirm-message { color: var(--text); }
  .confirm-details { color: var(--text-muted); }
  ```
  Бэкдроп `rgba(17,24,39,.5)` **не трогать**: это затемняющая вуаль, она уместна в обеих темах.

- [ ] **Step 3: `notification.component.ts`**

Kit-классов не объявляет — чистый перевод, удалять нечего. Фактические классы: `.toast`, `.toast-success`, `.toast-error`, `.toast-info` (сплошная цветная заливка + белый текст).

**Заливки тостов остаются хексами — это осознанное исключение из ограничения 5.** Тост лежит поверх произвольного фона в обеих темах, и белый текст на насыщенной заливке читается везде. Если перевести фон на `var(--success)`/`var(--danger)`, в тёмной теме токены осветлены (`#34d399`, `#f87171`) и белый текст на них даёт контраст ниже 3:1. Это ровно та же категория, что уже одобренные фикс-цветные кнопки «КП»/«ТЗ»/bulk.

Меняется только то, что безопасно:

```scss
.toast { color: var(--accent-contrast); box-shadow: var(--shadow); }
/* .toast-success/.toast-error/.toast-info — заливки #059669/#dc2626/#1a56db НЕ трогать */
```

Добавить рядом комментарий с причиной, чтобы следующий разработчик не «дочинил» это до токенов.

- [ ] **Step 4: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 5: Живая проверка**

`confirm` вызывается удалением любой сущности: `/equipment-types` → «Удалить» на строке. `notification` — успешным сохранением там же (зелёный) и ошибкой (например, сохранить пустую форму).

4 состояния (1280/390 × светлая/тёмная). Особое внимание: в тёмной теме модалка не должна быть белым прямоугольником, текст тоста обязан читаться.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/components/confirm/ frontend/src/app/components/notification/ && git commit -F - <<'EOF'
feat(ui): тёмная тема глобальных оверлеев confirm и notification

Оба рендерятся поверх любой страницы, поэтому светили белым даже на уже
переведённой странице тендеров.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
```

---

### Task 4: `smart-match` (67 хексов)

Модалка подбора оборудования из каталога, открывается кнопкой «Подобрать» на лоте (только рынок РФ и `lotHasCriteria`).

**Files:**
- Modify: `frontend/src/app/components/smart-match/smart-match.component.ts`

- [ ] **Step 1: Перевод по таблице соответствия**

**Удалять нечего:** компонент не объявляет ни одного kit-класса (проверено при планировании) — это чистый перевод 67 хексов по таблице. Своих `th, td` тоже нет, поэтому таблица кандидатов уже получила цвета из kit в Task 1 — проверить, что шапка читается и согласована с остальной модалкой.

Особое внимание — подсветка рекомендованной строки: это **подсветка области**, применять формулу
`background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: var(--success);`
а не `--surface-2` (урок `6b8dc3f`).

- [ ] **Step 2: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 3: Живая проверка**

Рынок RF (`localStorage.setItem('ais.market','RF')` + reload) → `/tenders` → открыть тендер с лотами, у которых есть критерии → «Подобрать» на строке лота. Должны быть видны: список кандидатов, проценты, рекомендация СППР, баннер «нужна техспецификация» на лоте без критериев.

4 состояния. Проверить, что зелёная подсветка рекомендованного кандидата видна в тёмной теме и не сливается с фоном модалки.

- [ ] **Step 4: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/components/smart-match/ && git commit -m "feat(ui): тёмная тема модалки подбора оборудования (smart-match)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: `bulk-price-modal` (42) + `offer-comparison` (17)

**Files:**
- Modify: `frontend/src/app/pages/tenders/bulk-price-modal.component.ts`
- Modify: `frontend/src/app/pages/tenders/offer-comparison.component.ts`

- [ ] **Step 1: Завести токен `--shadow-lg` (находка ревью Task 3)**

В kit есть единственный токен высоты `--shadow`, и он «карточный» (`0 2px 8px`). Крупные тени модалок живут захардкоженными в трёх местах: `bulk-price-modal.component.ts` (`0 20px 60px`), `applies.component.ts` (`0 12px 32px`), `confirm` (уже переведён на `--shadow`). Без общего токена каждая следующая модалка решает вопрос заново.

В `frontend/src/styles.scss`, в блоки `:root` и `:root[data-theme="dark"]`, рядом с `--shadow`:

```scss
/* :root */          --shadow-lg:0 12px 32px rgba(0,0,0,.18);
/* :root[dark] */    --shadow-lg:0 12px 32px rgba(0,0,0,.55);
```

Дальше модалки этой и следующих задач используют `var(--shadow-lg)` вместо своих крупных теней. `confirm` не трогать — он лежит на 50% вуали, `var(--shadow)` там признан достаточным.

- [ ] **Step 2: `bulk-price-modal.component.ts`**

Зачистка по процедуре. Удалить `.btn`, `.btn-cancel`, `.empty`.

⚠️ `.btn-close` **не удалять** — его намеренно нет в kit (это прозрачный крестик модалки, kit дал бы ему серую заливку). Токенизировать на месте: `color: var(--text-muted)`, ховер `color: var(--danger)`.

Крупную тень модалки перевести на `var(--shadow-lg)` из Step 1. Остальное — по таблице.

- [ ] **Step 3: `offer-comparison.component.ts`**

**Удалять нечего** — kit-классов не объявляет (проверено при планировании), это чистый перевод 17 хексов. Своих `th, td` тоже нет, цвета шапки пришли из kit в Task 1.

Ключевой момент: **зелёная подсветка минимальной цены по лоту** — это подсветка области, применять
`background: color-mix(in srgb, var(--success) 8%, var(--surface));`
Если оставить `--surface-2`, минимум перестанет читаться как минимум.

- [ ] **Step 4: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 5: Живая проверка**

Рынок KZ. `/tenders` → тендер, у которого ≥2 КП с введёнными ценами → кнопка «Сравнить предложения» (модалка сравнения, зелёный минимум, строка «Итого», контрол наценки, кнопка «✓ Назначить победителем»). Bulk-модалка: ручной (не импортный) тендер → «КП по всему тендеру».

4 состояния. Матрица лоты×поставщики широкая — на 390px она в `.table-scroll`; горизонтальный скролл здесь ожидаем, **не чинить**, а сфотографировать в отчёт.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/styles.scss frontend/src/app/pages/tenders/bulk-price-modal.component.ts frontend/src/app/pages/tenders/offer-comparison.component.ts && git commit -m "feat(ui): тёмная тема модалок bulk-КП и сравнения предложений

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: `equipment-detail-modal` (59)

**Files:**
- Modify: `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`

- [ ] **Step 1: Зачистка по процедуре**

Удалить `.subtitle`, `.empty`, `th, td`.

⚠️ **Бейджи здесь удалять НЕЛЬЗЯ — это находка ревью Task 1.** Компонент использует **параллельное нижнерегистровое семейство** (`.badge-created`, `.badge-sent`, `.badge-responded`, `.badge-closed`, `.badge-other`), которого kit не покрывает: kit знает только SCREAMING_CASE (`.badge-DRAFT`, `.badge-pr-SENT`, …). Если удалить локальные правила, бейджи останутся вообще без стиля.

Поэтому здесь: `.badge` (базу) удалить можно — её kit даёт; а нижнерегистровые варианты **токенизировать на месте** тинт-формулой для чипов (`created` → `--text-muted`/`--surface-2`, `sent` → `--accent`, `responded` → `--warn`, `closed` → `--text-muted`, `other` → `--text-muted`). Бейдж статуса регистрации (`REGISTERED` и пр.) — тоже тинт-формула.

- [ ] **Step 2: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 3: Живая проверка**

`/equipment` → клик по позиции каталога → модалка с характеристиками, бейджем регистрации и таблицей.

4 состояния.

- [ ] **Step 4: Коммит + гейт волны + мерж + пуш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/components/equipment-detail-modal/ && git commit -m "feat(ui): тёмная тема карточки оборудования

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Прогнать «Гейт волны», затем:

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --no-ff feature/ui-dark-wave1 -m "Merge: тёмная тема модалок поверх тендеров и глобальных оверлеев" && git branch -d feature/ui-dark-wave1 && git push origin main
```

---

## Волна 2 — крупные рабочие экраны (ветка `feature/ui-dark-wave2`)

### Task 7: `applies` (131 хекс — самый большой компонент)

**Files:**
- Modify: `frontend/src/app/pages/applies/applies.component.ts`

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/ui-dark-wave2
```

- [ ] **Step 2: Зачистка по процедуре**

Удаляется больше всего: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-back`, `.btn-reset-filter`, `.btn-delete`, `.btn-pdf`, `.btn-edit`, `.badge` (база), `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`, `th`, `tr:hover`, а из `.badge-*` — `DRAFT`, `SUBMITTED`, `UNDER_REVIEW`, `WON`, `LOST`, `REJECTED`, `.badge-d-*`.

**Сохранить локально:**

- Доменное расхождение — статус `CANCELLED` здесь нейтральный («заявка отменена»), а kit даёт красный (как у тендеров). Оставить одну строку:
  ```scss
  .badge-CANCELLED { background: var(--surface-2); color: var(--text-muted); }
  ```
- Отступы с удаляемых кнопок: `.btn-cancel { margin-left: 8px; }`, `.btn-edit { margin-right: 4px; }`, `.btn-autofill/.btn-pdf/.btn-submit/.btn-withdraw/.btn-won/.btn-lost { margin-left: 8px; margin-bottom: 16px; }` — вынести отдельными строками без цвета.
- Одноразовые кнопки, которых нет в kit: `.btn-autofill`, `.btn-submit`, `.btn-withdraw`, `.btn-won` (все → `var(--success)`), `.btn-lost` (→ `var(--danger)`), `.btn-open` (kit покрывает цвет), `.btn-submit-hint` (тинт `--warn`).
- `.positive` → `var(--success)`, `.negative` → `var(--danger)`.
- `.profit-summary`, `.apply-info`, `.edit-form` — фон `var(--surface-2)`, рамка `var(--border)`.
- `.af-modal-backdrop` — вуаль `rgba(17,24,39,0.5)` оставить; `.af-modal` — `background: var(--surface); box-shadow: var(--shadow);`.
- `.af-hint` — подсветка области: `background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-left-color: var(--accent); color: var(--text);`.
- `.af-presets button.active` → `background: var(--accent); color: var(--accent-contrast); border-color: var(--accent);`.

- [ ] **Step 3: Проверить на дубли и собрать**

Файл большой (787 строк) — после крупной правки проверить, что автоформат не продублировал и не съел соседние правила:

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 4: Живая проверка**

`/applies`: список заявок с бейджами статусов; открыть заявку → инфо-блок, таблица позиций, сводка прибыли (положительная/отрицательная), кнопки воронки (Подать/Отозвать/Выиграна/Проиграна), модалка наценки «Собрать из КП», форма редактирования с ошибкой валидации (для `.field-error`/`.error-banner`).

4 состояния. Проверить, что зелёная прибыль и красный убыток различимы в тёмной теме.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/applies/ && git commit -m "feat(ui): тёмная тема экрана заявок

CANCELLED здесь нейтральный (заявка отменена), а не красный как у тендеров —
оставлено доменным переопределением поверх kit.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: `private-requests` (44) + `private-request-card` (53)

**Files:**
- Modify: `frontend/src/app/pages/private-requests/private-requests.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-card.component.ts`

- [ ] **Step 1: `private-requests.component.ts`**

Зачистка по процедуре. Удалить `.btn-primary`, `.badge`, `.form-actions`; `.empty` компонент не объявляет — уже пришёл из kit в Task 1.

**Расхождения, требующие решения:**
- `.btn-primary { padding: 8px 14px; border-radius: 8px; }` — геометрия крупнее kit. По решению оператора (унификация) — удалить целиком, кнопка станет как везде.
- `.btn-line` здесь **пунктирный** (`1px dashed #9ca3af`) — это осознанный вид кнопки «добавить ещё». Kit даёт сплошную рамку, поэтому сохранить локально стиль рамки:
  ```scss
  .btn-line { border-style: dashed; border-radius: 6px; padding: 5px 12px; font-size: 12px; }
  ```

- [ ] **Step 2: `private-request-card.component.ts`**

Зачистка по процедуре: `.btn-primary`, `.btn-line`, `.badge`, `.subtitle`, `.empty`, `th, td`.

- `.btn-primary:disabled { background: #93c5fd; }` — удалить, kit даёт `.btn:disabled { opacity: .5 }`. Убедиться, что кнопка в шаблоне имеет класс `btn` (иначе `:disabled` из kit не применится — тогда оставить локально `.btn-primary:disabled { opacity: .5; cursor: not-allowed; }`).
- `.btn-line` — тот же пунктир, что в Step 1.
- Инлайн реестр-статус строки (Зарегистрировано / Не найдено), НДС-бейдж, топ-кандидат РУ — тинт-формула для чипов: `success` / `danger` / `accent`.

- [ ] **Step 3: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 4: Живая проверка**

Рынок KZ. `/private-requests`: список заявок ЧЗ-…; открыть карточку → строки с реестр-статусом, блок «Подобрать поставщиков», «Запросить КП», ввод ответов, режим «✎ Редактировать» (инлайн-грид), импорт файла.

4 состояния. Проверить пунктирную кнопку «добавить» и читаемость реестр-бейджей в тёмной.

- [ ] **Step 5: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/private-requests/ && git commit -m "feat(ui): тёмная тема частных заявок и карточки заявки

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: `dashboard` (48) + `reports` (39) + `chart` (9)

**Files:**
- Modify: `frontend/src/app/pages/dashboard/dashboard.component.ts`
- Modify: `frontend/src/app/pages/reports/reports.component.ts`
- Modify: `frontend/src/app/components/chart/chart.component.ts`

- [ ] **Step 1: `dashboard.component.ts`**

Зачистка по процедуре: `.badge`, `.badge-*`, `.subtitle`, `.empty`, `th, td`. Стат-карточки — `background: var(--surface)`, рамка `var(--border)`, тень `var(--shadow)`; акцентные числа — `var(--accent)`.

⚠️ У дашборда мобильная сетка стат-карточек уже ловила гочу (`@media` должен быть последним блоком, `repeat(N, 1fr)` не сжимается ниже min-content). **Не трогать геометрию сетки**, только цвета; убедиться, что `@media` остался в конце.

- [ ] **Step 2: `reports.component.ts`**

Зачистка по процедуре: `.btn-pdf`, `.subtitle`, `.empty`, `th, td`. `.btn` компонент не объявляет — база уже пришла из kit в Task 1.

⚠️ **Здесь развилка, найденная ревью Task 3.** В компоненте есть `.btn-pdf:hover { background: #b91c1c; }`. Буквальный перевод по таблице (`#b91c1c → var(--danger)`) даст **no-op**: ховер совпадёт с обычным фоном, кнопка потеряет отклик, а в файле останется мёртвое правило, выглядящее осмысленным. При этом в kit ховера для danger-группы нет вовсе.

Правильное действие — **поднять ховер в kit**, а не решать локально, иначе в приложении заведётся два разных затемнения для одной роли (в `confirm` уже лежит `.btn-danger:hover` через `color-mix`). В `frontend/src/styles.scss`, сразу под строкой `.btn-delete, .btn-danger, .btn-pdf { … }`:

```scss
/* Затемнение к чёрному, а не к --text: у залитой кнопки белая подпись, а --text
   в тёмной теме почти белый и уронил бы её контраст. Замер: база 2,77:1 → ховер 3,74:1. */
.btn-delete:hover, .btn-danger:hover, .btn-pdf:hover { background: color-mix(in srgb, black 15%, var(--danger)); }
```

Затем удалить локальный `.btn-pdf:hover` здесь и локальный `.btn-danger:hover` из `confirm.component.ts` — он станет дублем.

- [ ] **Step 3: `chart.component.ts`**

9 хексов — это цвета серий графика. Диаграммы должны читаться в обеих темах:
- оси, сетка, подписи → `var(--border)` / `var(--text-muted)`;
- цвета серий — если это семантика (доход/расход, выиграно/проиграно), брать `var(--success)` / `var(--danger)` / `var(--accent)`; если это просто палитра категорий — оставить хексы, но проверить читаемость на тёмном фоне и при необходимости осветлить.

- [ ] **Step 4: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 5: Живая проверка**

`/dashboard` (стат-карточки, панели, пустые состояния с CTA) и `/reports` (графики, таблицы, выгрузка PDF/Excel).

4 состояния. На 390px дашборд обязан остаться сеткой 2×2, графики — в столбик. Проверить, что линии/подписи графиков видны на тёмном фоне.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/dashboard/ frontend/src/app/pages/reports/ frontend/src/app/components/chart/ && git commit -m "feat(ui): тёмная тема дашборда, отчётов и графиков

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 10: `inbound` (41) + `registry-reconciliation` (41)

**Files:**
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts`
- Modify: `frontend/src/app/pages/registry-reconciliation/registry-reconciliation.component.ts`

- [ ] **Step 1: `inbound.component.ts`**

Зачистка по процедуре: `.btn-primary`, `.badge`, `.empty`.

**Расхождения:**
- `.btn-primary { background: #2563eb; border-radius: 6px; padding: 8px 16px; }` — и цвет, и геометрия отличаются от остальных экранов. По решению об унификации удалить целиком.
- `.badge { border-radius: 999px; font-size: 11px; padding: 2px 8px; }` — таблетка вместо общей формы. Удалить, kit даёт единую форму бейджа.
- Бейджи типа письма (`SUPPLIER_RESPONSE` / `CLIENT_REQUEST` / `UNMATCHED`) — тинт-формула для чипов: `accent` / `success` / `text-muted`.
- Превью текста письма — фон `var(--surface-2)`, рамка `var(--border)`.

- [ ] **Step 2: `registry-reconciliation.component.ts`**

Зачистка по процедуре: `.badge`. `.empty` уже из kit. Бейджи «Зарегистрировано / Не найдено / Истёк срок» — тинт-формула: `success` / `danger` / `warn`.

- [ ] **Step 3: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 4: Живая проверка**

Рынок KZ. `/inbound`: список писем с бейджами типа, колонка «Получено», превью текста (свернуть/развернуть), диалог «Импортировать» с гридом разметки колонок и «➕ Новый клиент». `/registry-reconciliation`: строки сверки с бейджами.

4 состояния. Особое внимание: таблица `inbound` получила цвета шапки из kit ещё в Task 1 — убедиться, что она согласована с остальным экраном после зачистки.

- [ ] **Step 5: Коммит + гейт волны + мерж + пуш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/inbound/ frontend/src/app/pages/registry-reconciliation/ && git commit -m "feat(ui): тёмная тема входящих писем и реестр-сверки

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

Прогнать «Гейт волны», затем:

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --no-ff feature/ui-dark-wave2 -m "Merge: тёмная тема крупных рабочих экранов" && git branch -d feature/ui-dark-wave2 && git push origin main
```

---

## Волна 3 — справочники и мелочь (ветка `feature/ui-dark-wave3`)

### Task 11: `distributors` (41) + `equipment` (32) + `facilities` (31)

**Files:**
- Modify: `frontend/src/app/pages/distributors/distributors.component.ts`
- Modify: `frontend/src/app/pages/equipment/equipment.component.ts`
- Modify: `frontend/src/app/pages/facilities/facilities.component.ts`

- [ ] **Step 1: Создать ветку**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout -b feature/ui-dark-wave3
```

- [ ] **Step 2: `distributors.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-delete`, `.btn-edit`, `.btn-line`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`.

`.btn-line` здесь **пунктирный** — сохранить локально `border-style: dashed` (как в Task 8).
Чипы брендов и чекбоксы видов МИ — тинт-формула для чипов (`--success` для брендов, как на странице тендеров).

- [ ] **Step 3: `equipment.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-reset-filter`, `.btn-delete`, `.btn-edit`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`.

- [ ] **Step 4: `facilities.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-delete`, `.btn-edit`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`, `.badge-*`.

Бейдж «🔔 тендеры» (KZ, `monitor_tenders`) — тинт-формула для чипов на `--accent`.

- [ ] **Step 5: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 6: Живая проверка**

`/distributors` (карточка поставщика: чипы брендов, чекбоксы видов МИ, ссылка на сайт), `/equipment` (список + фильтры + форма), `/facilities` (рынок KZ: селектор региона, чекбокс «Мониторить тендеры», бейдж «🔔 тендеры»).

4 состояния каждый. Списки помечены `responsive-cards` — на 390px обязаны быть карточками, а не таблицей.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/distributors/ frontend/src/app/pages/equipment/ frontend/src/app/pages/facilities/ && git commit -m "feat(ui): тёмная тема справочников поставщиков, оборудования и учреждений

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 12: `users` (26) + `equipment-types` (22) + `tender-search` (26)

**Files:**
- Modify: `frontend/src/app/pages/users/users.component.ts`
- Modify: `frontend/src/app/pages/equipment-types/equipment-types.component.ts`
- Modify: `frontend/src/app/pages/tender-search/tender-search.component.ts`

- [ ] **Step 1: `users.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-add`, `.btn-save`, `.btn-cancel`, `.btn-delete`, `.btn-edit`, `.subtitle`, `.counter`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`.

Бейджи ролей («Администратор» / «Оператор») — тинт-формула: `--accent` / `--text-muted`.

- [ ] **Step 2: `equipment-types.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-add`, `.btn-cancel`, `.btn-delete`, `.btn-edit`, `.subtitle`, `.empty`, `.field-error`, `.error-banner`, `.form-actions`, `th, td`. `.btn-save` не объявлен — уже пришёл из kit в Task 1, проверить, что кнопка выглядит правильно.

- [ ] **Step 3: `tender-search.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-reset`, `.badge`, `.badge-*`, `.subtitle`, `.counter`, `.empty`.

**Расхождение:** `.btn { padding: 8px 18px; }` — шире kit. Это заметная кнопка поиска. По решению об унификации удалить; если на живой проверке кнопка станет визуально мелкой для своей роли, вернуть одну строку `.btn-search { padding: 8px 18px; }`.

- [ ] **Step 4: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 5: Живая проверка**

`/users` (только под админом), `/equipment-types` (только под админом), `/tenders/search`.

4 состояния каждый.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/users/ frontend/src/app/pages/equipment-types/ frontend/src/app/pages/tender-search/ && git commit -m "feat(ui): тёмная тема пользователей, типов оборудования и поиска тендеров

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 13: `searchable-select` (18) + `login` (15) + `email-template` (14) + `about` (11)

**Files:**
- Modify: `frontend/src/app/components/searchable-select/searchable-select.component.ts`
- Modify: `frontend/src/app/pages/login/login.component.ts`
- Modify: `frontend/src/app/pages/email-template/email-template.component.ts`
- Modify: `frontend/src/app/pages/about/about.component.ts`

- [ ] **Step 1: `searchable-select.component.ts`**

Общий контрол, используется на многих формах. Перевести по таблице: поле — `background: var(--surface); color: var(--text); border-color: var(--border)`; выпадающий список — `background: var(--surface); box-shadow: var(--shadow)`; подсвеченный пункт — **подсветка области**: `background: color-mix(in srgb, var(--accent) 8%, var(--surface))`.

- [ ] **Step 2: `login.component.ts`**

Перевести по таблице. `.btn` не объявлен — база из kit (Task 1). Экран вне `LayoutComponent`, поэтому фон задаёт он сам: `background: var(--app-bg)`, карточка — `var(--surface)`.

⚠️ Логотип-медкрест на логине — **инлайн-SVG с захардкоженными path** (динамическая lucide-иконка приходила пустым `<svg>`, CLAUDE.md §14). **`path` не трогать ни при каких обстоятельствах** — иначе иконка снова станет невидимой.

**Санкционированное исключение из ограничения 11:** если у этого SVG атрибут `fill`/`stroke` задан хексом прямо в шаблоне, его разрешено заменить на `currentColor` (и задать цвет из CSS токеном). Это единственная правка шаблона во всём плане, и она нужна ровно затем, чтобы логотип был виден в обеих темах. Менять что-либо ещё в разметке нельзя. Обязательно проверить живьём, что после правки иконка отрисована (`svg.children.length > 0`, а не «svg есть»).

- [ ] **Step 3: `email-template.component.ts`**

Зачистка по процедуре: `.btn`, `.btn-save`, `.btn-line`.

**Расхождения:** `.btn { padding: 8px 16px; border-radius: 6px; font-size: 14px; }` и `.btn-save { background: #4f46e5 }` (индиго вместо бренд-синего). Оба — случайные расхождения, удалить: кнопка станет как везде.

Легенда-чипы плейсхолдеров (`{{позиции}}` и пр.) — тинт-формула для чипов на `--accent`; поля темы и тела — `var(--surface)` / `var(--border)`.

- [ ] **Step 4: `about.component.ts`**

Зачистка по процедуре: `.subtitle`. Остальное — по таблице.

- [ ] **Step 5: Собрать**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build
```

- [ ] **Step 6: Живая проверка**

`/login` (выйти из сессии), `/email-template` (под админом; вставка чипа-плейсхолдера, «Сохранить», «Сбросить»), `/about` (на обоих рынках — страница рыночно-зависима), `searchable-select` — на любой форме с выбором учреждения (например, форма тендера на `/tenders`).

4 состояния каждый.

- [ ] **Step 7: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/components/searchable-select/ frontend/src/app/pages/login/ frontend/src/app/pages/email-template/ frontend/src/app/pages/about/ && git commit -m "feat(ui): тёмная тема логина, шаблона письма, «О системе» и общего селектора

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

- [ ] **Step 8: Вернуть в kit правило полей ввода**

Правило `input, select, textarea` было **намеренно изъято** из kit в Волне 0 (фикс-раунд Task 1): оно единственное во всём слое не инертно — фон поля на непереведённом экране обычно не задан, поэтому поле сразу становилось тёмным и садилось на ещё белую карточку компонента (живой замер на `/login`: карточка `rgb(255,255,255)`, поле `rgb(41,40,39)`). На месте правила лежит комментарий, объясняющий это.

Сейчас все экраны переведены, и правило становится тем, ради чего задумано: страховкой для полей, у которых фон не задан вовсе, и для экранов, написанных после этой работы. Заменить комментарий-заглушку на само правило, в том же элементном слое рядом с `h2, h3` / `th, td`:

```scss
input, select, textarea { background: var(--surface); color: var(--text); border-color: var(--border); }
```

**Плюс отдельный рычаг для НАТИВНЫХ контролов** (находка ревью Task 4 — правило выше их не покрывает): чекбоксы, радио и особенно `<input type="range">` рисуются браузером, и в тёмной теме останутся светлыми, пока не задан `color-scheme`. Конкретный известный адрес — ползунки весов `.sm-sliders` в `smart-match` (видны только при пресете «Свой», поэтому обычная живая проверка их не показывает). В тот же элементный слой:

```scss
:root { color-scheme: light; }
:root[data-theme="dark"] { color-scheme: dark; }
input[type="range"], input[type="checkbox"], input[type="radio"] { accent-color: var(--accent); }
```

Проверить живьём: `smart-match` с пресетом «Свой» (четыре ползунка), чекбоксы видов МИ в карточке дистрибьютора, чекбоксы выбора лотов на карточке тендера.

Затем **обязательно** пройти живой проверкой по экранам с формами в тёмной теме — `/login`, `/equipment`, `/facilities`, `/distributors`, `/users`, `/private-requests`, форма лота на `/tenders` — и убедиться, что поля согласованы со своими карточками, а не наоборот. Если где-то правило снова рассогласовывает, это значит, что тот экран переведён не полностью: чинить экран, а не выкидывать правило.

**Известный заранее адрес такой починки** (найдено re-review'ом Волны 0): в `tenders.component.ts` три группы полей не объявляют собственные `background`/`color` — `.pmc-custom input`, `.edit-form input/select/textarea`, `.pr-items input`. Это преэкзистующий пробел, он уже в проде и к этой работе отношения не имеет, но всплывёт ровно здесь, когда правило вернётся. Дать им `background: var(--surface); color: var(--text);` в самом компоненте.

- [ ] **Step 9: Финальная проверка полноты**

Убедиться, что незакрытых хексов не осталось там, где они не разрешены:

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend/src/app && grep -rnoE '#[0-9a-fA-F]{3,8}\b' --include="*.component.ts" . \
  | grep -vE '#0e9f6e|#6366f1|#8b5cf6|#059669|#dc2626|#1a56db' | sort
```

Отфильтрованы разрешённые исключения из ограничения 5 (фикс-цветные кнопки и заливки тостов). Ожидаемо: пусто либо единичные находки, каждая из которых объяснима. Вуали `rgba(17,24,39,.5)` под этот grep не попадают — они не хексы и остаются намеренно.

- [ ] **Step 10: Гейт волны, мерж, пуш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git checkout main && git merge --no-ff feature/ui-dark-wave3 -m "Merge: тёмная тема справочников и остальных экранов" && git branch -d feature/ui-dark-wave3 && git push origin main
```

- [ ] **Step 11: Обновить документацию**

- `CLAUDE.md` §12: описать UI-kit (что лежит в `styles.scss`, правило «цвет и общая геометрия — в kit, раскладка — в компоненте», что новый экран на классах kit получает тёмную тему бесплатно); убрать из §16 пункт «СЛЕДУЮЩИЙ ШАГ: доработка UI + тёмная тема на остальных экранах» и заменить его на итог + список мобильных проблем, собранный за волны.
- `docs/PROGRESS.md`: секция сессии — что сделано, чем проверено, что дальше.

---

## Отчёт по мобильным проблемам

Собирается по ходу всех волн (Global Constraints, п. 3) и оформляется в Task 13 Step 10. Формат записи по каждой находке:

- экран и URL;
- что именно неудобно на 390px (горизонтальный скролл, обрезка, налезание, слишком мелкая цель нажатия);
- скриншот в `.playwright-mcp/`;
- предполагаемое лечение (карточки-аккордеоны как `tender-lots` / `responsive-cards` + `data-label` / перекомпоновка).

Заранее известные кандидаты (в этом заходе **не чиним**): матрица сравнения предложений (`offer-comparison`), таблица ввода цен по КП, грид разметки колонок при импорте (`inbound`, `private-requests`).
