# Полировка «Все тендеры» + тёмная тема — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans (recommended for this plan — visual work verified in-browser) or superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`).

**Goal:** Привести страницу `/tenders` в порядок (аккуратные фильтры на десктопе, кнопка «Фильтры» + шторка + чипы на мобилке, полировка карточек) и добавить тёмную тему «как Claude» с тумблером, применённую на каркасе (шапка/сайдбар) и странице тендеров.

**Architecture:** Семантические CSS-переменные (токены) в глобальном `styles.scss` — светлые на `:root`, тёмные на `:root[data-theme="dark"]`. `ThemeService` ставит `data-theme` на `<html>`, тумблер в шапке. Компоненты каркаса и тендеров переводятся с хардкод-хексов на `var(--…)`. Фильтры выносятся в презентационный компонент `app-tenders-filters` (изоляция + свой style-бюджет); родитель хранит состояние и логику как сейчас.

**Tech Stack:** Angular 21 (standalone, инлайн-шаблоны + `styles: []`), SCSS, Playwright MCP для визуальной проверки.

## Global Constraints

- Фронт без юнит-тестов — гейт каждой задачи: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` зелёный + визуальная проверка в браузере (Playwright).
- `anyComponentStyle`-бюджет (angular.json): warning 8kB, **error 18kB** на компонент. `tenders.component` близко к пределу → фильтры выносятся, чтобы не превысить.
- **Гоча @media:** `@media` в инлайн-`styles` компонента — ТОЛЬКО В КОНЦЕ блока (позднее базовое правило перебивает раннюю @media при равной специфичности).
- **Гоча иконки:** тумблер темы — инлайн-SVG с захардкоженными `path`, НЕ динамический lucide (приходит пустым svg).
- **Bash cwd персистит:** `cd frontend && npm run build` одной командой; git из корня (`cd /Users/vlad/IdeaProjects/AIS && git …`).
- Ветка `feature/tenders-ui-dark-theme` (создана, спека закоммичена).
- Тёмная тема этот заход — ТОЛЬКО каркас + тендеры. Другие контент-страницы не трогаем (останутся светлыми в тёмном режиме).
- Логика фильтрации (`applyTendersFilter`, поля `filterQuery/filterStatus/filterStage/filterPlatform/filterFacilityId/filterDeadlineFrom/filterDeadlineTo/filterRegion/sortMode`, `NO_REGION='__none__'`) НЕ меняется; import-методы (`importRegion`/`importBusy`/…) продолжают читать `this.filterPlatform`/`this.filterRegion` — поля остаются на родителе.
- Каждый commit заканчивать: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

### Общая карта цвет→токен (используется в задачах 1–3, 6)

| Хардкод | Токен |
|---|---|
| `#111827`, `#374151` (текст) | `var(--text)` |
| `#6b7280`, `#9ca3af` (муть/подписи) | `var(--text-muted)` |
| `#fff`/`#ffffff` (поверхность) | `var(--surface)` |
| `#f9fafb`, `#f8f9fb`, `#faf5ff`, `#f5f3ff` (подложки) | `var(--surface-2)` |
| `#f3f4f6` как фон | `var(--surface-2)`; как бордер | `var(--border)` |
| `#e5e7eb`, `#d1d5db`, `#ddd6fe`, `#a7f3d0` (бордеры) | `var(--border)` |
| `#1a56db`, `#2563eb` (акцент/ссылки) | `var(--accent)` |
| тени `rgba(0,0,0,0.08)`/`0.15` | `var(--shadow)` |

**Тинт-чипы** (статусы/бейджи с цветным фоном, напр. `.badge-ACTIVE`, `.reason-*`, `.brand-chip`, `.registry-*`): вместо пары «светлый фон + тёмный текст» использовать `background: color-mix(in srgb, <статус-токен> 15%, transparent); color: <статус-токен>;` — авто-адаптируется к теме (токен меняется). Статус-токены: ACTIVE/ссылки→`var(--accent)`, COMPLETED/успех→`var(--success)`, CANCELLED/ошибка→`var(--danger)`, DRAFT/нейтр→`var(--text-muted)`, warn→`var(--warn)`.

---

## Task 1: Токены + ThemeService + анти-FOUC + тумблер

**Files:**
- Modify: `frontend/src/styles.scss` (токены + body)
- Create: `frontend/src/app/services/theme.service.ts`
- Modify: `frontend/src/index.html` (анти-FOUC скрипт)
- Modify: `frontend/src/app/layout/layout.component.ts` (импорт сервиса + кнопка-тумблер)

**Interfaces:**
- Produces: `ThemeService { theme: Signal<'light'|'dark'>; current: 'light'|'dark'; toggle(): void; set(t): void }`; CSS-токены `--app-bg,--surface,--surface-2,--border,--text,--text-muted,--accent,--accent-hover,--accent-contrast,--danger,--warn,--success,--header-bg,--header-text,--shadow`.

- [ ] **Step 1: Добавить токены в styles.scss**

В начало `frontend/src/styles.scss` (после `* {…}`, заменив блок `body {…}`) вставить:

```scss
:root {
  --app-bg:#f7f7f8; --surface:#ffffff; --surface-2:#f3f4f6;
  --border:#e5e7eb; --text:#111827; --text-muted:#6b7280;
  --accent:#1a56db; --accent-hover:#1e40af; --accent-contrast:#ffffff;
  --danger:#ef4444; --warn:#f59e0b; --success:#10b981;
  --header-bg:#1a56db; --header-text:#ffffff;
  --shadow:0 2px 8px rgba(0,0,0,.08);
}
:root[data-theme="dark"] {
  --app-bg:#1f1e1d; --surface:#292827; --surface-2:#35332f;
  --border:rgba(255,255,255,.11); --text:#f4f2ee; --text-muted:#a8a29a;
  --accent:#6b93ff; --accent-hover:#8aa9ff; --accent-contrast:#ffffff;
  --danger:#f87171; --warn:#fbbf24; --success:#34d399;
  --header-bg:#242322; --header-text:#f4f2ee;
  --shadow:0 2px 10px rgba(0,0,0,.45);
}
body {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
  color: var(--text);
  background: var(--app-bg);
}
```

Также в `@media (max-width:900px)` заменить у `table.responsive-cards tr`: `background:#fff`→`background:var(--surface)`, `border:1px solid #e5e7eb`→`border:1px solid var(--border)`; у `td::before` `color:#6b7280`→`color:var(--text-muted)`; у `.drawer-*` цвета — в Task 2 (там же весь layout).

- [ ] **Step 2: Создать ThemeService**

Create `frontend/src/app/services/theme.service.ts`:

```ts
import { Injectable, signal } from '@angular/core';

export type Theme = 'light' | 'dark';

@Injectable({ providedIn: 'root' })
export class ThemeService {
  private readonly KEY = 'ais.theme';
  private cur: Theme = this.read();
  theme = signal<Theme>(this.cur);

  constructor() { this.apply(this.cur); }

  private read(): Theme {
    const v = localStorage.getItem(this.KEY);
    if (v === 'dark' || v === 'light') return v;
    return window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light';
  }
  private apply(t: Theme) { document.documentElement.setAttribute('data-theme', t); }

  get current(): Theme { return this.cur; }
  set(t: Theme) { this.cur = t; localStorage.setItem(this.KEY, t); this.theme.set(t); this.apply(t); }
  toggle() { this.set(this.cur === 'dark' ? 'light' : 'dark'); }
}
```

- [ ] **Step 3: Анти-FOUC скрипт в index.html**

В `frontend/src/index.html`, внутри `<head>` (последней строкой перед `</head>`), добавить:

```html
  <script>
    (function(){try{var k='ais.theme',v=localStorage.getItem(k);
      if(v!=='dark'&&v!=='light')v=matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';
      document.documentElement.setAttribute('data-theme',v);}catch(e){}})();
  </script>
```

- [ ] **Step 4: Тумблер в шапке layout.component**

В `layout.component.ts`:
(a) импорт: `import { ThemeService } from '../services/theme.service';`
(b) в конструктор добавить `public theme: ThemeService` (после `public market: MarketService`).
(c) в шаблоне, в `.header-right` перед `<svg lucideIcon="user"…>` (строка 47) вставить кнопку-тумблер (инлайн-SVG, оба path в шаблоне, показ по теме):

```html
          <button class="theme-toggle" (click)="theme.toggle()" [title]="theme.theme() === 'dark' ? 'Светлая тема' : 'Тёмная тема'" aria-label="Тема">
            <svg *ngIf="theme.theme() === 'light'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z"/></svg>
            <svg *ngIf="theme.theme() === 'dark'" viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/></svg>
          </button>
```

(d) в `styles` (перед `@media`) добавить:

```css
    .theme-toggle { background: rgba(255,255,255,0.15); color: var(--header-text); border: none; width: 32px; height: 32px; border-radius: 6px; cursor: pointer; display: inline-flex; align-items: center; justify-content: center; }
    .theme-toggle:hover { background: rgba(255,255,255,0.3); }
```

- [ ] **Step 5: Собрать + проверить тумблер**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL.
Проверка (Playwright, залогинившись): клик по тумблеру → `<html data-theme>` меняется, фон body/контента темнеет; reload сохраняет тему. (Шапка/сайдбар пока светлые — их Task 2.) Скриншот `theme-toggle-body.png`.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/styles.scss frontend/src/app/services/theme.service.ts frontend/src/index.html frontend/src/app/layout/layout.component.ts && git commit -m "$(printf 'feat(ui): токены темы + ThemeService + тумблер (анти-FOUC)\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 2: Тёмный каркас (шапка + сайдбар + контент)

**Files:**
- Modify: `frontend/src/app/layout/layout.component.ts` (стили)
- Modify: `frontend/src/styles.scss` (`.drawer-*` в @media)

**Interfaces:** Consumes: токены из Task 1.

- [ ] **Step 1: Перевести стили layout на токены**

В `layout.component.ts` блок `styles` заменить хардкод по карте (Global Constraints), ключевое:
- `.header { background:#1a56db → var(--header-bg); color:#fff → var(--header-text); }`
- `.content { background:#fff → var(--surface); }`
- `.sidebar { background:#f8f9fb → var(--surface); border-right:1px solid #e5e7eb → var(--border); }`
- `.sidebar a { color:#374151 → var(--text); }` ; `.sidebar a:hover { background:#e5e7eb → var(--surface-2); }`
- `.sidebar a.active { background:#1a56db → var(--accent); color:#fff → var(--accent-contrast); border-left-color:#fff → var(--accent-contrast); }`
- `.nav-group-title { color:#9ca3af → var(--text-muted); }`
- `.search-results { background:#fff → var(--surface); box-shadow → var(--shadow); }` ; `.search-result { border-bottom:#f3f4f6 → var(--border); }` ; `.search-result:hover { background:#f9fafb → var(--surface-2); }`
- `.result-title { color:#111827 → var(--text); }` ; `.result-subtitle { color:#6b7280 → var(--text-muted); }`
- `.result-type.type-*` тинт-чипы → `color-mix` по статус-токенам (tender→accent, equipment→success, facility→warn, distributor→accent).
- `.role-badge.role-admin` оставить как есть (жёлтый, читается на обеих). `.market-select option { color:#111827 → var(--text); }` + `.market-select { background: rgba(255,255,255,.2) }` оставить (на цветной шапке ок; на тёмной — тоже норм).

В `styles.scss` `@media` заменить `.drawer-search` `border-bottom:#e5e7eb→var(--border)`, `.drawer-search input { border:#d1d5db→var(--border); background:#fff→var(--surface); color:var(--text); }` `:focus border-color:#1a56db→var(--accent)`, `.drawer-results { background:#fff→var(--surface); border:#e5e7eb→var(--border); }`.

- [ ] **Step 2: Собрать + проверить каркас в тёмной**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL.
Проверка (Playwright, тема dark): шапка тёмная, сайдбар тёмный, активный пункт — акцент, текст читаем; светлая тема выглядит как раньше. Десктоп + мобилка (drawer). Скриншоты `shell-dark-desktop.png`, `shell-dark-mobile.png`.

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/layout/layout.component.ts frontend/src/styles.scss && git commit -m "$(printf 'feat(ui): тёмная тема каркаса (шапка/сайдбар/контент)\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 3: Тёмная страница тендеров (токенизация tenders.component)

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (блок `styles`, строки 662–~810)

**Interfaces:** Consumes: токены из Task 1.

- [ ] **Step 1: Токенизировать стили tenders.component**

Заменить хардкод-хексы в блоке `styles` по карте (Global Constraints). Проходы:
- Текст/подписи: `#111827/#374151→var(--text)`, `#6b7280/#9ca3af→var(--text-muted)`.
- Поверхности: `#fff→var(--surface)`, подложки `#f9fafb/#faf5ff/#f5f3ff/#f0fdf4/#eff6ff→var(--surface-2)`.
- Бордеры: `#e5e7eb/#d1d5db/#ddd6fe/#a7f3d0/#f3f4f6(border)→var(--border)`.
- Ссылки/акцент: `#1a56db/#2563eb→var(--accent)`.
- Карточка: `.tender-card { border→var(--border); }` `:hover { box-shadow→var(--shadow); border-color→var(--border); }` `.tender-urgent{border-left:4px solid var(--warn)}` `.tender-overdue{border-left:4px solid var(--danger); background: color-mix(in srgb, var(--danger) 8%, transparent);}`.
- Тинт-чипы через `color-mix` (Global Constraints): `.badge-DRAFT`(muted) `.badge-ACTIVE`(accent) `.badge-COMPLETED`(success) `.badge-CANCELLED`(danger); `.purchase-type`,`.eis-link`,`.demo-badge`,`.reason-*`,`.brand-chip`,`.badge-proposed`,`.badge-reg-ok`,`.score-badge*`,`.registry-*`,`.kp-panel`,`.kp-suppliers tr.kp-hit td`.
- Кнопки: `.btn-add/.btn-save { background:#1a56db→var(--accent); }` `.btn-cancel{background:var(--surface-2); color:var(--text);}` `.btn-line{background:var(--surface); color:var(--text); border:1px solid var(--border);}`; цветные кнопки (`.btn-kp` зелёная, `.btn-tz` индиго, `.btn-edit` оранж, `.btn-delete` красная, `.btn-registry` фиолет) оставить фикс-цветными (читаются на обеих темах) — но проверить контраст в dark.
- Прогресс: `.import-bar{background:var(--surface-2)}` `.import-bar-fill{background:var(--accent)}` `.import-progress-text{color:var(--text)}`.
- Оверлеи: `.kp-preview-overlay{background:rgba(0,0,0,.5)}` (оставить) `.kp-preview{background:var(--surface)}` инпуты превью `border→var(--border)`.
- `h2/h3 { color:var(--text) }`, `.subtitle{color:var(--text-muted)}`, `table th{background:var(--surface-2); color:var(--text-muted);}` `td{border-bottom:var(--border)}` `tr:hover{background:var(--surface-2)}`.

- [ ] **Step 2: Собрать + проверить тендеры в тёмной**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL, без превышения бюджета (если варнинг >8kB — ок; >18kB error → ускорить Task 4 выносом фильтров).
Проверка (Playwright, KZ, dark): карточки тендеров тёмные, чипы статусов/площадок читаемы, цена/номер видны, ховер работает; светлая — как раньше. Скриншот `tenders-dark-desktop.png`.

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "$(printf 'feat(ui): тёмная тема страницы тендеров (токенизация)\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 4: Компонент фильтров + аккуратный десктоп-ряд

**Files:**
- Create: `frontend/src/app/pages/tenders/tenders-filters.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (заменить инлайн-`.filters` на `<app-tenders-filters>`, добавить маппер, убрать `.filters/.filter-*` стили)

**Interfaces:**
- Produces: `TendersFilters { query:string; status:string; sortMode:string; stage:string; platform:string; facilityId:number|null; deadlineFrom:string; deadlineTo:string; region:string }`; компонент `app-tenders-filters` c `@Input() filters,facilities,regions,isKz,noRegionValue`; `@Output() filtersChange:EventEmitter<TendersFilters>`, `reset:EventEmitter<void>`.
- Consumes (родитель даёт): `filtersState` getter + `onFiltersChange(f)` + `onFiltersReset()`.

- [ ] **Step 1: Создать app-tenders-filters (десктоп-ряд)**

Create `frontend/src/app/pages/tenders/tenders-filters.component.ts`:

```ts
import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface TendersFilters {
  query: string; status: string; sortMode: string; stage: string; platform: string;
  facilityId: number | null; deadlineFrom: string; deadlineTo: string; region: string;
}

@Component({
  selector: 'app-tenders-filters',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="filters-row">
      <input class="f-search" type="text" placeholder="Поиск по номеру или описанию…" [(ngModel)]="filters.query" (input)="emit()" />
      <select class="f-sel" [(ngModel)]="filters.status" (change)="emit()" title="Статус">
        <option value="">Все статусы</option><option value="DRAFT">Подготовка</option>
        <option value="ACTIVE">Приём заявок</option><option value="COMPLETED">Завершён</option><option value="CANCELLED">Отменён</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.sortMode" (change)="emit()" title="Сортировка">
        <option value="published">Сначала новые</option><option value="deadline">Скоро дедлайн</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.stage" (change)="emit()" title="Стадия">
        <option value="">Все стадии</option><option value="NOT_STARTED">Не начат</option>
        <option value="REQUESTED">Запрос отправлен</option><option value="PRICED">Есть цены</option><option value="WINNER_SELECTED">Победитель выбран</option>
      </select>
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.platform" (change)="emit()" title="Площадка">
        <option value="">Все площадки</option><option value="GOSZAKUP">Госзакуп</option><option value="SK_PHARMACY">СК-Фармация</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.facilityId" (change)="emit()" title="Учреждение">
        <option [ngValue]="null">Все учреждения</option>
        <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
      </select>
      <input class="f-date" type="date" [(ngModel)]="filters.deadlineFrom" (change)="emit()" title="Дедлайн от" />
      <input class="f-date" type="date" [(ngModel)]="filters.deadlineTo" (change)="emit()" title="Дедлайн до" />
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.region" (change)="emit()" title="Регион">
        <option value="">Все регионы</option><option [value]="noRegionValue">Регион не указан</option>
        <option *ngFor="let r of regions" [value]="r">{{ r }}</option>
      </select>
      <button class="f-reset" (click)="reset.emit()">Сбросить</button>
    </div>
  `,
  styles: [`
    .filters-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
    .f-search, .f-sel, .f-date { height: 38px; border: 1px solid var(--border); border-radius: 8px; font-size: 14px; background: var(--surface); color: var(--text); box-sizing: border-box; }
    .f-search { flex: 1 1 220px; min-width: 180px; padding: 0 14px; }
    .f-search:focus, .f-sel:focus, .f-date:focus { outline: none; border-color: var(--accent); }
    .f-sel { max-width: 220px; padding: 0 34px 0 12px; appearance: none; -webkit-appearance: none; cursor: pointer;
      background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2'><path d='M6 9l6 6 6-6'/></svg>");
      background-repeat: no-repeat; background-position: right 12px center; }
    .f-date { padding: 0 10px; }
    .f-reset { height: 38px; background: var(--surface-2); color: var(--text); padding: 0 16px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; font-size: 13px; }
    .f-reset:hover { background: var(--border); }
  `]
})
export class TendersFiltersComponent {
  @Input() filters!: TendersFilters;
  @Input() facilities: any[] = [];
  @Input() regions: string[] = [];
  @Input() isKz = false;
  @Input() noRegionValue = '__none__';
  @Output() filtersChange = new EventEmitter<TendersFilters>();
  @Output() reset = new EventEmitter<void>();
  emit() { this.filtersChange.emit({ ...this.filters }); }
}
```

- [ ] **Step 2: Подключить в tenders.component**

В `tenders.component.ts`:
(a) импорт: `import { TendersFiltersComponent, TendersFilters } from './tenders-filters.component';` и добавить `TendersFiltersComponent` в `imports:[…]`.
(b) заменить весь инлайн-блок `<div class="filters">…</div>` (строки 27–64) на:

```html
      <app-tenders-filters
        [filters]="filtersState" [facilities]="facilities" [regions]="REGIONS"
        [isKz]="isKz()" [noRegionValue]="NO_REGION"
        (filtersChange)="onFiltersChange($event)" (reset)="resetTendersFilter()">
      </app-tenders-filters>
```

(c) в класс добавить (рядом с полями фильтров):

```ts
  get filtersState(): TendersFilters {
    return { query: this.filterQuery, status: this.filterStatus, sortMode: this.sortMode, stage: this.filterStage,
      platform: this.filterPlatform, facilityId: this.filterFacilityId, deadlineFrom: this.filterDeadlineFrom,
      deadlineTo: this.filterDeadlineTo, region: this.filterRegion };
  }
  onFiltersChange(f: TendersFilters) {
    this.filterQuery = f.query; this.filterStatus = f.status; this.sortMode = f.sortMode as any; this.filterStage = f.stage;
    this.filterPlatform = f.platform; this.filterFacilityId = f.facilityId; this.filterDeadlineFrom = f.deadlineFrom;
    this.filterDeadlineTo = f.deadlineTo; this.filterRegion = f.region;
    this.applyTendersFilter();
  }
```

(d) удалить из блока `styles` правила `.filters`, `.filter-input`, `.filter-select`, `.filter-date`, `.btn-reset-filter` (переехали в компонент фильтров).

- [ ] **Step 3: Собрать + проверить десктоп-фильтры**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL (бюджет tenders.component снизился).
Проверка (Playwright, desktop): фильтры — ровный ряд единой высоты, кастомный шеврон, работают (статус/регион/площадка/даты/поиск/сброс фильтруют список как раньше), в светлой и тёмной. Скриншоты `filters-desktop-light.png`, `filters-desktop-dark.png`.

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/tenders-filters.component.ts frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "$(printf 'feat(ui): фильтры тендеров в отдельный компонент + аккуратный десктоп-ряд\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 5: Мобилка — кнопка «Фильтры» + шторка + чипы активных

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders-filters.component.ts`

**Interfaces:** Consumes: `TendersFilters` (Task 4).

- [ ] **Step 1: Добавить мобильную панель, шторку и чипы**

В `tenders-filters.component.ts`:
(a) В шаблон ПЕРЕД `.filters-row` добавить мобильную панель (видна ≤900px через CSS):

```html
    <div class="filters-mobile">
      <input class="f-search" type="text" placeholder="Поиск по тендерам…" [(ngModel)]="filters.query" (input)="emit()" />
      <div class="fm-bar">
        <button class="fm-open" (click)="sheetOpen = true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 5h18M6 12h12M10 19h4"/></svg> Фильтры<span class="fm-count" *ngIf="activeChips.length">· {{ activeChips.length }}</span></button>
        <button class="fm-reset" *ngIf="activeChips.length" (click)="reset.emit()">Сбросить</button>
      </div>
      <div class="fm-chips" *ngIf="activeChips.length">
        <span class="fm-chip" *ngFor="let c of activeChips">{{ c.label }}<button (click)="clear(c.key)">✕</button></span>
      </div>
    </div>

    <div class="fm-backdrop" *ngIf="sheetOpen" (click)="sheetOpen = false"></div>
    <div class="fm-sheet" [class.open]="sheetOpen">
      <div class="fm-sheet-head"><b>Фильтры</b><button (click)="sheetOpen = false">✕</button></div>
      <label>Статус<select [(ngModel)]="filters.status" (change)="emit()"><option value="">Все</option><option value="DRAFT">Подготовка</option><option value="ACTIVE">Приём заявок</option><option value="COMPLETED">Завершён</option><option value="CANCELLED">Отменён</option></select></label>
      <label>Сортировка<select [(ngModel)]="filters.sortMode" (change)="emit()"><option value="published">Сначала новые</option><option value="deadline">Скоро дедлайн</option></select></label>
      <label>Стадия<select [(ngModel)]="filters.stage" (change)="emit()"><option value="">Все</option><option value="NOT_STARTED">Не начат</option><option value="REQUESTED">Запрос отправлен</option><option value="PRICED">Есть цены</option><option value="WINNER_SELECTED">Победитель выбран</option></select></label>
      <label *ngIf="isKz">Площадка<select [(ngModel)]="filters.platform" (change)="emit()"><option value="">Все</option><option value="GOSZAKUP">Госзакуп</option><option value="SK_PHARMACY">СК-Фармация</option></select></label>
      <label *ngIf="isKz">Регион<select [(ngModel)]="filters.region" (change)="emit()"><option value="">Все</option><option [value]="noRegionValue">Регион не указан</option><option *ngFor="let r of regions" [value]="r">{{ r }}</option></select></label>
      <label>Учреждение<select [(ngModel)]="filters.facilityId" (change)="emit()"><option [ngValue]="null">Все</option><option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option></select></label>
      <label class="fm-dates">Дедлайн<span><input type="date" [(ngModel)]="filters.deadlineFrom" (change)="emit()" /><input type="date" [(ngModel)]="filters.deadlineTo" (change)="emit()" /></span></label>
      <div class="fm-sheet-actions"><button class="fm-apply" (click)="sheetOpen = false">Применить</button><button class="fm-clear" (click)="reset.emit(); sheetOpen = false">Сброс</button></div>
    </div>
```

(b) В класс добавить логику чипов и очистки поля:

```ts
  sheetOpen = false;
  private static STATUS: Record<string,string> = { DRAFT:'Подготовка', ACTIVE:'Приём заявок', COMPLETED:'Завершён', CANCELLED:'Отменён' };
  private static STAGE: Record<string,string> = { NOT_STARTED:'Не начат', REQUESTED:'Запрос отправлен', PRICED:'Есть цены', WINNER_SELECTED:'Победитель выбран' };
  private static PLATFORM: Record<string,string> = { GOSZAKUP:'Госзакуп', SK_PHARMACY:'СК-Фармация' };

  get activeChips(): { label: string; key: keyof TendersFilters }[] {
    const f = this.filters, out: { label: string; key: keyof TendersFilters }[] = [];
    if (f.status) out.push({ label: TendersFiltersComponent.STATUS[f.status] || f.status, key: 'status' });
    if (f.stage) out.push({ label: TendersFiltersComponent.STAGE[f.stage] || f.stage, key: 'stage' });
    if (f.platform) out.push({ label: TendersFiltersComponent.PLATFORM[f.platform] || f.platform, key: 'platform' });
    if (f.region) out.push({ label: f.region === this.noRegionValue ? 'Без региона' : f.region, key: 'region' });
    if (f.facilityId != null) out.push({ label: (this.facilities.find(x => x.id === f.facilityId)?.name) || 'Учреждение', key: 'facilityId' });
    if (f.deadlineFrom) out.push({ label: 'от ' + f.deadlineFrom, key: 'deadlineFrom' });
    if (f.deadlineTo) out.push({ label: 'до ' + f.deadlineTo, key: 'deadlineTo' });
    return out;
  }
  clear(key: keyof TendersFilters) {
    (this.filters as any)[key] = key === 'facilityId' ? null : '';
    this.emit();
  }
```

(c) В блок `styles`: скрыть мобильную панель на десктопе, показать на мобилке; десктоп-ряд скрыть на мобилке. **@media — В КОНЦЕ блока** (гоча). Добавить:

```css
    .filters-mobile { display: none; }
    .fm-backdrop, .fm-sheet { display: none; }
    /* … существующие .filters-row/.f-* правила … */

    @media (max-width: 900px) {
      .filters-row { display: none; }
      .filters-mobile { display: block; margin-bottom: 12px; }
      .filters-mobile .f-search { width: 100%; height: 44px; font-size: 16px; margin-bottom: 8px; }
      .fm-bar { display: flex; gap: 8px; }
      .fm-open { flex: 1; height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 10px; font-size: 15px; cursor: pointer; }
      .fm-count { color: var(--accent); font-weight: 600; }
      .fm-reset { height: 44px; padding: 0 16px; background: var(--surface-2); color: var(--text); border: 1px solid var(--border); border-radius: 10px; cursor: pointer; }
      .fm-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
      .fm-chip { display: inline-flex; align-items: center; gap: 6px; background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent); border-radius: 999px; padding: 4px 6px 4px 12px; font-size: 13px; }
      .fm-chip button { background: none; border: none; color: inherit; cursor: pointer; font-size: 13px; line-height: 1; padding: 2px; }
      .fm-backdrop { display: block; position: fixed; inset: 0; background: rgba(0,0,0,0.45); z-index: 300; }
      .fm-sheet { display: flex; flex-direction: column; gap: 12px; position: fixed; left: 0; right: 0; bottom: 0; z-index: 310; background: var(--surface); border-radius: 16px 16px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom)); max-height: 85vh; overflow-y: auto; transform: translateY(100%); transition: transform 0.25s ease; }
      .fm-sheet.open { transform: translateY(0); }
      .fm-sheet-head { display: flex; justify-content: space-between; align-items: center; font-size: 17px; }
      .fm-sheet-head button { background: none; border: none; font-size: 20px; color: var(--text-muted); cursor: pointer; }
      .fm-sheet label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-muted); }
      .fm-sheet select, .fm-sheet input { height: 44px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); color: var(--text); font-size: 16px; padding: 0 12px; }
      .fm-dates span { display: flex; gap: 8px; }
      .fm-dates input { flex: 1; }
      .fm-sheet-actions { display: flex; gap: 10px; margin-top: 4px; }
      .fm-apply { flex: 1; height: 46px; background: var(--accent); color: var(--accent-contrast); border: none; border-radius: 10px; font-size: 15px; font-weight: 500; cursor: pointer; }
      .fm-clear { height: 46px; padding: 0 18px; background: var(--surface-2); color: var(--text); border: 1px solid var(--border); border-radius: 10px; cursor: pointer; }
    }
```

- [ ] **Step 2: Собрать + проверить мобилку**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL.
Проверка (Playwright, 390px, KZ): по умолчанию — только поиск + «Фильтры · N» + чипы; тап открывает шторку снизу; выбор в шторке фильтрует; «Применить» закрывает; чип ✕ сбрасывает поле; светлая и тёмная. Скриншоты `filters-mobile-light.png`, `filters-mobile-sheet.png`, `filters-mobile-dark.png`.

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/tenders-filters.component.ts && git commit -m "$(printf 'feat(ui): мобильные фильтры — кнопка + шторка + чипы активных\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 6: Полировка карточек тендера

**Files:**
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts` (стили карточки + при необходимости мелкая правка разметки карточки)

**Interfaces:** Consumes: токены.

- [ ] **Step 1: Отполировать карточку**

В `tenders.component.ts` (уже на токенах после Task 3):
- `.tender-card { padding: 16px 20px; border-radius: 10px; }` ; hover — плавная тень `var(--shadow)`.
- `.tender-meta` чипы — единый радиус 999px, паддинг 2px 10px; статусный чип и площадка-чип одного вида (тинт color-mix).
- `.detail-row` — на мобилке (≤900px, В КОНЦЕ блока) `grid-template-columns: 1fr;` (одна колонка, чтобы не жать); `.detail-label` `var(--text-muted)`, letter-spacing .03em.
- `.tender-price` — выровнять по правому краю (уже `white-space:nowrap`), `font-size: 18px`.
- Тач-таргеты кнопок карточки на мобилке ≥40px (уже глобально §styles.scss, проверить).

- [ ] **Step 2: Собрать + проверить**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: BUILD SUCCESSFUL.
Проверка (Playwright): карточка аккуратнее на десктопе и мобилке, обе темы. Скриншот `tender-card-polished.png`.

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/tenders/tenders.component.ts && git commit -m "$(printf 'feat(ui): полировка карточек тендера (сетка/чипы/мобилка)\n\nCo-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>')"
```

---

## Task 7: Финальная проверка (4 комбинации) + выверка палитры

**Files:** нет (проверочная; допускаются мелкие правки хексов токенов в `styles.scss` по итогам выверки).

- [ ] **Step 1: Playwright — 4 комбинации**

{десктоп 1280, мобилка 390} × {светлая, тёмная}: логин, KZ, `/tenders`. Снять 4 скриншота. Проверить глазами: тёплый тёмный (не чёрный), текст контрастный, чипы/бейджи читаемы, фильтры (десктоп ряд ровный; мобилка кнопка+шторка+чипы), карточки. Анти-FOUC: reload в dark не мигает светлым. Тумблер: переключает, переживает reload, уважает системную тему при чистом localStorage (`localStorage.removeItem('ais.theme')` + reload).

- [ ] **Step 2: Регресс логики фильтров**

Проверить каждый фильтр (статус/стадия/площадка/регион/учреждение/даты/поиск/сортировка) + «Сбросить» + чип-✕ — список фильтруется как раньше; переключение рынка RF/KZ не ломает (на RF нет площадки/региона).

- [ ] **Step 3: Прочие страницы не сломаны в dark**

Быстрый проход в тёмной теме по dashboard/equipment/facilities: светлый контент в тёмном каркасе допустим, но нет нечитаемого текста (тёмный на тёмном). Если где-то критично нечитаемо — отметить в бэклог (эти страницы вне скоупа).

- [ ] **Step 4: Финальная сборка + «куда смотреть»**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёный.
Дать пользователю click-by-click тур (тумблер темы в шапке; фильтры десктоп/мобилка). Затем — ревью ветки и мерж (finishing-a-development-branch).

---

## Self-Review (плана против спеки)

- §3.1 ThemeService → Task 1 Step 2. ✔  §3.2 токены → Task 1 Step 1. ✔  анти-FOUC → Task 1 Step 3. ✔  §3.3 тумблер → Task 1 Step 4. ✔
- §4 охват (каркас+тендеры, логин не трогаем, прочие позже) → Task 2 (каркас), Task 3 (тендеры); прочие — Task 7 Step 3 (только проверка). ✔
- §5.1 контракт `app-tenders-filters`/`TendersFilters` → Task 4 (типы совпадают со спекой). ✔  §5.2 десктоп-ряд → Task 4. ✔  §5.3 мобилка (кнопка+шторка) → Task 5. ✔  §5.3.1 чипы активных → Task 5 (activeChips/clear). ✔
- §6 полировка карточек → Task 6. ✔
- §7 проверка (4 комбо + build + анти-FOUC + регресс) → Task 7. ✔
- **Placeholder-скан:** новые артефакты (ThemeService, FOUC, filters component, sheet) даны полным кодом; токенизация — по явной карте цвет→токен (не «подберите цвета»). ✔
- **Type-consistency:** `TendersFilters` поля и `filtersState`/`onFiltersChange` маппинг совпадают; import-методы читают неизменные `filterPlatform/filterRegion`. ✔
- **Гоча-риск:** @media в конце блока — явно указано в Task 5 Step 1(c) и Task 6. Тумблер/иконки — инлайн-SVG. Style-бюджет — вынос фильтров (Task 4) до добавления sheet (Task 5). ✔
