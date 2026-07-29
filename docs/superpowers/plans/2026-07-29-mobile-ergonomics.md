# Мобильная эргономика — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сделать приложение пригодным для работы с телефона: списки перестают быть простынями, таблицы перестают требовать горизонтальной прокрутки, а десктоп не меняется.

**Architecture:** Строка таблицы на мобилке становится CSS-гридом, ячейки раскладываются по именованным областям через адресацию `td[data-label="…"]`. Разметка не дублируется, порядок колонок не важен, десктоп не затронут (всё внутри `@media (max-width: 900px)`). Общие правила — в глобальном `styles.scss`, раскладка каждого списка — в его компоненте.

**Tech Stack:** Angular 21 (standalone, инлайн `template` + `styles: []`), SCSS, CSS Grid, существующая система токенов.

**Спека:** `docs/superpowers/specs/2026-07-29-mobile-ergonomics-design.md`

---

## Global Constraints

Требования этого раздела действуют для **каждой** задачи плана.

1. **Десктоп НЕ МЕНЯЕТСЯ.** Всё новое — строго внутри `@media (max-width: 900px)`. Проверка обязательна на каждой задаче: замер ключевых элементов на 1280px до и после должен совпасть (метод — как в работе с тёмной темой: `getComputedStyle` по списку селекторов).
2. **`@media` — последним блоком в `styles`** (CLAUDE.md §14: при равной специфичности позднее базовое правило молча перебивает раннюю @media-переопределялку; ловили на `.stat-cards` дашборда).
3. **Только фронт.** Ни строки в `src/` (бэкенд). Проверка перед мержем волны: `git diff main...HEAD -- src/` — пусто.
4. **Разметку править МОЖНО** — это предмет работы (расстановка `data-label`, вынос действий в меню, перестройка блоков). Но **логику и данные менять НЕЛЬЗЯ**: `*ngIf`-условия, интерполяции, обработчики, привязки остаются как есть. Если кажется, что нужно поменять поведение — остановись и напиши в отчёте.
5. **Новых цветов не вводить.** Работа с цветом закончена прошлым заходом: только существующие токены. Правила выбора — CLAUDE.md §12 (токен по слоту CSS; заливка vs текст; две формулы тинта).
6. **Коммит заканчивать** строкой `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>` (CLAUDE.md §3).
7. **Bash cwd персистит между вызовами** (CLAUDE.md §14): `cd frontend && npm run build` оставляет cwd во `frontend`. Команды git — компаундом от корня: `cd /Users/vlad/IdeaProjects/AIS && …`.
8. **⚠️ Обратные кавычки в CSS-комментариях ломают сборку**: блок `styles: [` … `]` — шаблонный литерал JS, любой `` ` `` в комментарии закрывает его досрочно, Angular ругается непрозрачно («Failed to resolve styles at position 0 to a string»). Использовать «ёлочки».
9. **⚠️ НИКОГДА не использовать `git add -A` / `git add .`** — в репозитории лежит неотслеживаемая папка `docs/diploma` на 73 МБ. Только явные пути к файлам, которые правил.
10. **Тестов на фронте нет — и это не дефект задачи.** Гейт: `npm run build` + живая проверка в браузере, которую делает контроллер. Юнит-тесты на раскладку писать не нужно.
11. **Работать на ветке волны, не на `main`.** Ветку создаёт контроллер.

### Механизм: строка-грид с адресацией по `data-label`

Прототип проверен вживую на `/applies` до написания плана: 307px → 124px, 2,7 → 6,8 карточек на экран, 51 → 21 экран прокрутки.

```scss
@media (max-width: 900px) {
  table.responsive-cards tr {
    display: grid;
    grid-template-columns: 1fr auto;
    grid-template-areas:
      "num   status"
      "cust  cust"
      "sum   cnt"
      "act   act";
    gap: 2px 8px;
    align-items: center;
  }
  table.responsive-cards td { padding: 2px 0; }
  /* подпись «Колонка:» больше не нужна — смысл несёт раскладка */
  table.responsive-cards td::before { display: none; }

  td[data-label="Номер тендера"] { grid-area: num; font-weight: 600; font-size: 15px; }
  td[data-label="Статус"]        { grid-area: status; justify-content: flex-end; }
  td[data-label="Заказчик"]      { grid-area: cust; color: var(--text-muted); font-size: 13px; }
  td[data-label="Сумма"]         { grid-area: sum; font-weight: 700; font-size: 16px; }
  td[data-label="ID"]            { display: none; }
  td:not([data-label])           { grid-area: act; }   /* колонка действий */
}
```

**Почему именно так — не менять без причины:**

- **Адресация по `data-label`, а не по `nth-child`.** Порядок колонок поменяется — раскладка не сломается. И правило читается как документация.
- **Разметка не дублируется.** Держать отдельный блок карточек для мобилки и таблицу для десктопа — удваивает шаблон и разъезжается при первой правке.
- **`::before` с подписью выключается.** В механическом паттерне подпись несла смысл («Сумма: 1 200 000»), в спроектированной карточке смысл несёт позиция и типографика. Где подпись всё же нужна (например «позиций: 5»), включать её точечно и с явным `content`.
- **Скрывать (`display: none`)** то, что не участвует в просмотре списка: технические id, даты создания, служебные счётчики. Это не потеря данных — всё видно в карточке записи.

### Метод проверки (обязателен в каждой задаче)

**Замер до и после**, а не «на глаз». Мерить на 390×844:

```js
// высота карточек списка и сколько влезает на экран
const rows = [...document.querySelectorAll('tbody tr')];
const h = rows.slice(0,5).map(r => Math.round(r.getBoundingClientRect().height));
const VH = document.documentElement.clientHeight;
({ высоты: h, наЭкран: (VH/h[0]).toFixed(1), экрановПрокрутки: Math.round(rows.length*h[0]/VH) });
```

```js
// переполнение и горизонтальный скролл внутри (только видимое)
const de = document.documentElement, VW = de.clientWidth;
const vis = el => { const r = el.getBoundingClientRect(), c = getComputedStyle(el);
  return r.width>0 && r.height>0 && c.visibility!=='hidden' && c.display!=='none' && r.left<VW && r.right>0; };
const sc = [];
document.querySelectorAll('*').forEach(el => {
  if (el.scrollWidth > el.clientWidth+4 && el.clientWidth > 100 && vis(el)) sc.push(el.className || el.tagName);
});
({ переполнение: de.scrollWidth-VW, скроллеры: [...new Set(sc)] });
```

Замер десктопа (1280) до и после — тем же приёмом, что в работе с тёмной темой: `getComputedStyle` по ключевым селекторам экрана, значения должны совпасть.

---

## Волна A — списки (ветка `feature/mobile-lists`)

### Task 1: Общий слой мобильных карточек + `applies` как эталон

`applies` — худший случай (9 колонок, 307px, 51 экран) и он же задаёт образец для остальных.

**Files:**
- Modify: `frontend/src/styles.scss`
- Modify: `frontend/src/app/pages/applies/applies.component.ts`

- [ ] **Step 1: Общие правила в `styles.scss`**

В секцию адаптива (`@media (max-width: 900px)`), рядом с существующим `table.responsive-cards`, добавить слой для нового режима. Ключевое: старый механический режим **остаётся** для экранов, которые ещё не переведены, а новый включается opt-in классом `card-grid` на той же таблице.

```scss
/* Спроектированная карточка списка: строка становится гридом, ячейки
   раскладываются по областям через td[data-label="…"] в компоненте.
   Старый механический режим (подпись: значение) остаётся для таблиц без
   этого класса — переводим экраны по одному. */
table.responsive-cards.card-grid tr {
  display: grid;
  gap: 2px 8px;
  align-items: center;
  padding: 10px 12px;
}
table.responsive-cards.card-grid td { padding: 2px 0; text-align: left; }
table.responsive-cards.card-grid td::before { display: none; }
table.responsive-cards.card-grid td > * { min-width: 0; }
```

- [ ] **Step 2: `applies` — раскладка списка**

⚠️ **В файле ДВЕ таблицы `responsive-cards`, и ни у одной нет отличительного класса** (проверено при планировании): список заявок (`~строка 73`) и позиции внутри карточки заявки (`~строка 199`, 8 колонок: Лот · Оборудование · Дистрибьютор · Кол-во · Закупка · Предл. цена · Маржа · %). Селектор без различения попадёт в обе.

Поэтому: добавить в шаблоне **разные** классы — `card-grid applies-list` списку и `card-grid applies-items` таблице позиций, и адресоваться к ним раздельно.

Фактические `data-label` списка (сверено с шаблоном): `ID` · `Номер тендера` · `Заказчик` · `Статус` · `Поставка` · `Позиций` · `Сумма` · `Дата создания` (+ колонка действий без `data-label`).

В `styles` компонента, в блок `@media (max-width: 900px)`:

```scss
.applies-list tr {
  grid-template-columns: 1fr auto;
  grid-template-areas:
    "num   status"
    "cust  cust"
    "sum   cnt"
    "act   act";
}
td[data-label="Номер тендера"] { grid-area: num; font-weight: 600; font-size: 15px; }
td[data-label="Статус"]        { grid-area: status; justify-content: flex-end; }
td[data-label="Заказчик"]      { grid-area: cust; color: var(--text-muted); font-size: 13px;
                                 overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
td[data-label="Сумма"]         { grid-area: sum; font-weight: 700; font-size: 16px; }
td[data-label="Позиций"]       { grid-area: cnt; justify-content: flex-end; color: var(--text-muted); font-size: 12px; }
td[data-label="Позиций"]::before { display: inline; content: "позиций: "; }
td[data-label="ID"], td[data-label="Дата создания"], td[data-label="Поставка"] { display: none; }
td:not([data-label]) { grid-area: act; }
```

Точные имена в `data-label` сверить с шаблоном — они уже проставлены.

- [ ] **Step 3: `applies` — таблица позиций внутри карточки**

Восемь колонок (`Лот` · `Оборудование` · `Дистрибьютор` · `Кол-во` · `Закупка` · `Предл. цена` · `Маржа` · `%`) — это вторая простыня, и она внутри карточки заявки, то есть открывается на каждой заявке.

Раскладка: **оборудование** (крупно, вся ширина) → **дистрибьютор** (приглушённо) → строка цифр: `кол-во × закупка → предл. цена` и **маржа с процентом** справа. Лот скрыть, если он дублирует оборудование; иначе — мелким над названием.

Маржа несёт знак (`.positive`/`.negative`) — цвет сохранить, он уже на токенах.

```scss
.applies-items tr {
  grid-template-columns: 1fr auto;
  grid-template-areas:
    "equip equip"
    "dist  dist"
    "money margin";
}
td[data-label="Оборудование"] { grid-area: equip; font-weight: 600; }
td[data-label="Дистрибьютор"] { grid-area: dist; color: var(--text-muted); font-size: 13px; }
td[data-label="Маржа"]        { grid-area: margin; justify-content: flex-end; font-weight: 600; }
td[data-label="Лот"], td[data-label="%"] { display: none; }
```

Колонки `Кол-во` / `Закупка` / `Предл. цена` собрать в область `money` — они читаются вместе как одна фраза.

- [ ] **Step 4: Ряд действий**

Кнопки в области `act` не должны переносить карточку в простыню. Если их больше двух — свернуть в overflow-меню «⋯» по образцу `tender-lots.component.ts` (там уже есть `openMenuLotId` + `@HostListener('document:click')` для закрытия). Если две и меньше — оставить в ряд, растянув на всю ширину.

- [ ] **Step 5: Замер и сборка**

Прогнать замер из Global Constraints на 390px. Ожидание: высота карточки ≈120–130px, на экран ≈6–7, прокрутка на 141 заявку ≈20 экранов (было 307 / 2,7 / 51). Затем `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`.

- [ ] **Step 6: Проверка, что десктоп не изменился**

Снять `getComputedStyle` на 1280px по `.applies-table th`, `.applies-table td`, `.badge`, `.btn` до и после правки — значения должны совпасть. Если что-то поехало — правило вышло за `@media`.

- [ ] **Step 7: Коммит**

```
feat(ui): список заявок — спроектированная карточка вместо простыни

Механический responsive-cards давал 9 строк «подпись: значение»: карточка
307px, 2,7 на экран, 51 экран прокрутки на 141 заявку. Строка стала гридом,
ячейки разложены по областям через data-label, технические колонки скрыты.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 2: `facilities` + `distributors`

**Files:**
- Modify: `frontend/src/app/pages/facilities/facilities.component.ts` (7 колонок)
- Modify: `frontend/src/app/pages/distributors/distributors.component.ts` (6 колонок)

- [ ] **Step 1: `facilities`**

В карточке оставить: **название** (крупно) · **город/регион** · **контакт**. Скрыть на мобилке технические поля (ИНН/БИН, служебные счётчики) — они видны при редактировании. Бейдж «🔔 тендеры» (KZ) — в область статуса справа от названия.

- [ ] **Step 2: `distributors`**

Оставить: **название** (крупно, это ссылка на сайт) · **бренды** (чипы, максимум 2–3 видимых, остальные «+N») · **email**. Виды МИ и прочее — скрыть.

⚠️ Чипы брендов на мобилке не должны переносить карточку в простыню: ограничить показ и не давать им переполнять ширину.

- [ ] **Step 3: Замер, сборка, проверка десктопа, коммит**

Метод из Global Constraints. Коммит:

```
feat(ui): карточки учреждений и поставщиков под мобилку

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 3: `equipment` + `inbound` + `private-requests`

**Files:**
- Modify: `frontend/src/app/pages/equipment/equipment.component.ts` (5)
- Modify: `frontend/src/app/pages/inbound/inbound.component.ts` (5)
- Modify: `frontend/src/app/pages/private-requests/private-requests.component.ts` (5)

- [ ] **Step 1: `equipment`** — оставить: **название** · **производитель** · **тип**. Габариты/вес скрыть.
- [ ] **Step 2: `inbound`** — оставить: **тема** (крупно) · **отправитель** · **бейдж типа** · **получено**. Тема — главное, она длинная: дать ей всю ширину с обрезкой в одну-две строки.
- [ ] **Step 3: `private-requests`** — оставить: **номер** · **клиент** · **позиций** · **статус**. Колонка «Реестр» (`.reg-summary`) — вторая строка мелким.
- [ ] **Step 4: Замер, сборка, проверка десктопа, коммит**

```
feat(ui): карточки каталога, входящих и частных заявок под мобилку

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

- [ ] **Step 5: Проверить оставшиеся списки**

`users` (3 колонки), `equipment-types` (1), `dashboard` (4), `registry-reconciliation` (4) — по замеру должны быть приемлемы без переделки. Прогнать на них замер; если карточка выше ~140px — перевести тем же приёмом, если нет — оставить и записать в отчёт, что проверено.

---

## Волна B — горизонтальный скролл (ветка `feature/mobile-tables`)

### Task 4: `offer-comparison` — переворот матрицы

Самый сложный случай и единственный настоящий редизайн в плане. Матрица лоты × поставщики на телефоне не работает в принципе: и строк, и колонок произвольное количество.

**Files:**
- Modify: `frontend/src/app/pages/tenders/offer-comparison.component.ts`

- [ ] **Step 1: Переворот на мобилке**

Вместо матрицы — **карточка на лот**, внутри список предложений: строка «поставщик — цена», минимум подсвечен зелёным (существующая идиома `.oc-best`), назначенный победитель — своей (`.oc-winner`). Итоги по поставщику и контрол наценки — отдельным блоком под списком.

Десктопную матрицу сохранить как есть (`@media` только вниз).

- [ ] **Step 2: Кнопка «Назначить победителем»**

Она должна остаться доступной в мобильной раскладке — по кнопке в каждой строке предложения.

- [ ] **Step 3: Замер, сборка, проверка десктопа, коммит**

⚠️ **Живая проверка этого экрана требует данных**, которых в локальной БД нет (нужен тендер с ≥2 КП с введёнными ценами) — это отмечено как непроверенное ещё в прошлом заходе. Контроллер заводит данные вручную (ввод цен в двух КП по одному тендеру) либо фиксирует в отчёте, что проверить не удалось.

```
feat(ui): сравнение предложений на мобилке — карточка на лот вместо матрицы

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 5: Ввод цен по КП + `smart-match`

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (таблица ввода цен в секции «Запросы КП»)
- Modify: `frontend/src/app/components/smart-match/smart-match.component.ts`

- [ ] **Step 1: Ввод цен** — строка «позиция + поле цены» вместо таблицы: наименование сверху, поле ввода и кол-во под ним. Поле ввода не меньше 40px по высоте (тач-таргет).
- [ ] **Step 2: `smart-match`** — таблица кандидатов в карточки: модель · производитель · процент соответствия · кнопка «Утвердить». Шкалы score оставить, они узкие.
- [ ] **Step 3: Замер, сборка, проверка десктопа, коммит**

```
feat(ui): ввод цен и подбор оборудования под мобилку

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 6: `equipment-detail-modal`

**Files:**
- Modify: `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`

- [ ] **Step 1** — три таблицы характеристик в дровере: перевести в пары «подпись / значение» в столбик (здесь механический режим как раз уместен — это карточка записи, а не список), убрать `.table-scroll`.
- [ ] **Step 2: Замер, сборка, проверка десктопа, коммит**

```
feat(ui): карточка оборудования без горизонтального скролла

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

## Волна C — остальное и полировка (ветка `feature/mobile-rest`)

### Task 7: `private-request-card` + `reports`

**Files:**
- Modify: `frontend/src/app/pages/private-requests/private-request-card.component.ts`
- Modify: `frontend/src/app/pages/reports/reports.component.ts`

- [ ] **Step 1: `private-request-card`** — таблицы позиций в дровере не адаптированы вовсе. Позиция = блок: наименование · бренд · кол-во · реестр-статус. Режим редактирования (инлайн-грид) — поля в столбик.
- [ ] **Step 2: `reports`** — `.summary-grid` на `repeat(3, 1fr)`: на 390px не сжимается ниже min-content (гоча §14, `repeat(N, 1fr)` не сжимается). Перевести на `repeat(auto-fit, minmax(…, 1fr))` или в одну колонку под `@media`. Таблицы отчётов — в карточки.
- [ ] **Step 3: Замер, сборка, проверка десктопа, коммит**

```
feat(ui): карточка частной заявки и отчёты под мобилку

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```

---

### Task 8: Полировка, финальный свип, документация

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (чипы `.lot-mini`)
- Modify: `frontend/src/styles.scss` (тач-таргеты, если нужно)
- Modify: `CLAUDE.md`, `docs/PROGRESS.md`

- [ ] **Step 1: Чипы `.lot-mini`** — на карточках списка тендеров скроллятся вбок (замер: 666→320px). На мобилке: показывать 2–3 чипа и «+N», либо переносить строкой, но не скроллить.
- [ ] **Step 2: Тач-таргеты** — пройти замером по основным потокам, добить оставшееся меньше 36px. Глобальное правило `button, .btn { min-height: 40px }` в `styles.scss` уже есть — проверить, где оно не срабатывает (обычно из-за собственной высоты или `line-height` компонента).
- [ ] **Step 3: Финальный свип по всем экранам**

Пройти замером все 16 экранов на 390px. Ожидание: переполнения нет нигде, горизонтальный скролл — только там, где осознанно оставлен и прокомментирован, тач-таргетов меньше 36px нет в основных потоках. Результат — таблицей в отчёт.

- [ ] **Step 4: Документация**

- `CLAUDE.md` §12: описать новый режим карточек (`card-grid` + адресация по `data-label`), правило «десктоп внутри `@media` не трогаем», и что старый механический режим остался для простых таблиц.
- `CLAUDE.md` §16: цель закрыть, выписать хвосты.
- `docs/PROGRESS.md`: запись сессии.

- [ ] **Step 5: Коммит**

```
feat(ui): полировка мобилки + документация

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
```
