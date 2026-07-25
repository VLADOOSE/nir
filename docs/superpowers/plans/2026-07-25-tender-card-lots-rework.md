# Переработка карточки тендера (лоты и панели лота) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Лоты в карточке тендера становятся карточками-аккордеонами (без таблиц и горизонтального скролла) на обеих ширинах, панели «Подбор»/«Комплектность»/«КП» открываются внутри своего лота, а зона лотов выносится из разросшегося `tenders.component.ts` в три компонента.

**Architecture:** Чистый рефакторинг фронта + перекладка вёрстки. Три новых standalone-компонента в `frontend/src/app/pages/tenders/`: `app-lot-registry-panel`, `app-lot-kp-panel`, `app-tender-lots`. Логика подбора/матчинга/отправки переносится **дословно**; меняются только вёрстка, место рендера и владелец состояния. Порядок задач — «сначала извлечь панели на их текущем месте, потом перестроить список лотов», чтобы каждый шаг проверялся отдельно.

**Tech Stack:** Angular 21 (standalone-компоненты, инлайн-шаблоны + `styles: []`), `ApiService`, `NotificationService`, `ConfirmService`, `MarketService`, `MarketMoneyPipe` (`| money`), `LucideDynamicIcon`.

**Спека:** `docs/superpowers/specs/2026-07-25-tender-card-lots-rework-design.md` — читать перед началом, особенно §6 «Инварианты».

## Global Constraints

- **Бэкенд и API не меняются.** Ни одного файла вне `frontend/`. Никаких новых эндпоинтов, никаких правок DTO.
- **Фронт-тестов в проекте нет.** Гейт каждой задачи — `cd frontend && npm run build` (зелёный, включая бюджеты) **плюс** живая проверка в браузере через Playwright MCP. Не заводить Jest/Karma/Vitest — их в проекте нет и заводить их эта работа не просит.
- **Стили только на токенах:** `--app-bg`, `--surface`, `--surface-2`, `--border`, `--text`, `--text-muted`, `--accent`, `--accent-hover`, `--accent-contrast`, `--danger`, `--warn`, `--success`, `--shadow`. Хексы не хардкодить. Исключение — уже существующие фикс-цветные кнопки (зелёная «КП» `#0e9f6e`, индиго «ТЗ» `#6366f1`, фиолетовая bulk `#8b5cf6`), они читаются в обеих темах.
- **Тинт-чипы:** `background: color-mix(in srgb, <токен> 15%, transparent); color: <токен>;` — так тёмная тема получается сама.
- **`@media`-блоки — ТОЛЬКО в самом конце блока `styles`.** Гоча CLAUDE.md §14: при равной специфичности позднее базовое правило молча перебивает более раннюю @media-переопределялку.
- **Брейкпоинт мобилки — 900px** (`@media (max-width: 900px)`), как во всём проекте.
- **Не использовать `repeat(N, 1fr)` в grid** там, где содержимое может не сжаться ниже min-content (гоча §14: переполнение вместо ужатия).
- **После async-операций звать `this.cdr.detectChanges()`** — так устроен весь этот код.
- **Bash cwd персистит между вызовами:** после `cd frontend && npm run build` следующий `git`/`./gradlew` вызывать из корня — `cd /Users/vlad/IdeaProjects/AIS && …`.
- **Коммиты** заканчивать строкой `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Ветка:** `feature/tender-card-lots-rework` (уже создана, спека в ней закоммичена).
- **Номера строк** в этом плане даны по состоянию `tenders.component.ts` на коммите `a1c0bf4` и сдвигаются по мере выполнения задач — ориентироваться в первую очередь на **имена методов**, номера строк вторичны.

---

## Структура файлов

| Файл | Ответственность |
|---|---|
| `frontend/src/app/pages/tenders/lot-registry-panel.component.ts` | **Создать.** Панель «Подбор»: кандидаты реестра НЦЭЛС, разворот карточки НЦЭЛС, блок «Комплектность аппаратов», обе кнопки «Взять в работу» |
| `frontend/src/app/pages/tenders/lot-kp-panel.component.ts` | **Создать.** Панель «КП»: вид МИ, точечный поиск, список поставщиков, превью письма, отправка |
| `frontend/src/app/pages/tenders/tender-lots.component.ts` | **Создать.** Тулбар лотов, список лотов-аккордеонов, чип стадии, выбор лотов, форма лота, хостинг обеих панелей |
| `frontend/src/app/pages/tenders/tenders.component.ts` | **Изменить.** Отдаёт зону лотов новым компонентам; остаётся список тендеров, фильтры, форма тендера, инфо-шапка, «Запросы КП», модалки bulk/сравнения, smart-match |
| `docs/PROGRESS.md`, `CLAUDE.md` | **Изменить** в последней задаче |

---

### Task 1: Компонент `app-lot-registry-panel`

Извлечь панель «Подбор» (реестр НЦЭЛС + комплектность) в свой компонент **на её нынешнем месте** — под списком лотов. Вёрстка внутри панели переводится с таблиц на карточки. Место рендера пока не меняем: так поломку видно сразу и она не смешивается с перестройкой лотов.

**Files:**
- Create: `frontend/src/app/pages/tenders/lot-registry-panel.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `ApiService.getLotRegistryCandidates(lotId)`, `.getRegistryDetail(regNumber)`, `.complectSearch(lotId, term?)`, `.adoptRegistryForLot(lotId, regNumber)`, `.adoptComponent(lotId, regNumber, partNumber)`; `NotificationService`.
- Produces (на них опирается Task 3):
  ```ts
  @Component({ selector: 'app-lot-registry-panel', standalone: true, … })
  export class LotRegistryPanelComponent implements OnChanges {
    @Input() lot: any | null = null;      // null → панель не рендерится
    @Input() imported = false;            // показывать подсказку «нажмите ТЗ»
    @Output() adopted = new EventEmitter<any>();   // payload = lot, по которому взяли в работу
    @Output() close = new EventEmitter<void>();
  }
  ```

- [ ] **Step 1: Создать компонент-скелет с входами/выходами**

Создать `frontend/src/app/pages/tenders/lot-registry-panel.component.ts`:

```ts
import { Component, ChangeDetectorRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

/**
 * Панель «Подбор» по лоту: кандидаты реестра НЦЭЛС + комплектность аппаратов.
 * Реестр и комплектность живут вместе: комплектность открывается из реестра по тому же лоту.
 */
@Component({
  selector: 'app-lot-registry-panel',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: ``,   // Step 3
  styles: [``],   // Step 4
})
export class LotRegistryPanelComponent implements OnChanges {
  @Input() lot: any | null = null;
  @Input() imported = false;
  @Output() adopted = new EventEmitter<any>();
  @Output() close = new EventEmitter<void>();

  registry: { loading: boolean; items: any[]; distinctive?: boolean; techSpecParsed?: boolean;
              openReg?: string | null; detail?: any; detailLoading?: boolean; detailError?: string | null } | null = null;
  complect: { term: string; loading: boolean; searched: boolean; apparatuses: any[] } | null = null;
  adoptBusy = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService) {}

  /** Смена лота = новый подбор с нуля (панель переиспользуется между лотами). */
  ngOnChanges(ch: SimpleChanges) {
    if (ch['lot']) {
      this.complect = null;
      if (this.lot) this.loadRegistry(); else this.registry = null;
    }
  }

  formatDate(d: string): string {
    if (!d) return '—';
    const dt = new Date(d);
    return isNaN(dt.getTime()) ? d : dt.toLocaleDateString('ru-RU');
  }
}
```

- [ ] **Step 2: Перенести логику из `tenders.component.ts`**

Перенести в класс `LotRegistryPanelComponent` **дословно** (меняются только имена полей состояния и источник лота), взяв из `tenders.component.ts`:

| Откуда (метод) | Куда | Правка при переносе |
|---|---|---|
| `onLotRegistry(l)` (~1348) | `loadRegistry()` | `this.registryPanel = {lot: l, …}` → `this.registry = {…}` без поля `lot`; лот берётся из `this.lot`; при ошибке вместо `this.registryPanel = null` ставить `this.registry = { loading: false, items: [] }` и показывать тост (панель не должна исчезать — её жизнью управляет родитель) |
| `toggleRegistryDetail(c)` (~1439) | так же | `const p = this.registryPanel` → `const p = this.registry` |
| `registryDetailEmpty(d)` (~1468) | так же | без правок |
| `scorePct(c)` (~1472) | так же | без правок |
| `adoptFromRegistry(c)` (~1699) | так же | лот из `this.lot`; вместо `this.closeRegistryPanel(); this.loadLots(); this.openKpPanelFor(lot);` → `this.adopted.emit(this.lot);` (оркестрацию делает родитель, см. Task 3) |
| `openComplect(l)` (~1373) | `openComplect()` | лот из `this.lot`; `this.complect = { term: '', loading: true, searched: false, apparatuses: [] }` |
| `runComplect(l, term?)` (~1380) | `runComplect(term?)` | лот из `this.lot`; `this.complectPanel` → `this.complect` (без поля `lot`) |
| `closeComplect()` (~1406) | так же | `this.complect = null` |
| `adoptComponent(c, comp)` (~1420) | так же | лот из `this.lot`; вместо `this.closeComplect(); this.loadLots();` → `this.closeComplect(); this.adopted.emit(this.lot);` |

Сохранить дословно: разделение компонентов на `_relevant`/`_zero`/`_showZero` в `runComplect`, фронтовый кеш `c._detail` в `toggleRegistryDetail` (при ошибке НЕ кешировать — повторный разворот = retry), общий флаг `adoptBusy` на обе кнопки «Взять в работу».

- [ ] **Step 3: Написать шаблон — таблицы заменить карточками**

Заполнить `template`. Исходник — `tenders.component.ts:316-444` (блоки `.registry-panel`). Структура сохраняется, но три таблицы (`registry-table` кандидатов, `registry-table` компонентов) становятся списками карточек, и **ни одного `.table-scroll`**:

```html
<div class="lrp" *ngIf="lot">
  <div class="lrp-head">
    <span><b>Реестр НЦЭЛС РК:</b> {{ lot.equipName }}</span>
    <button class="btn btn-cancel" (click)="close.emit()">✕ Закрыть</button>
  </div>
  <div class="lrp-note">Реестр НЦЭЛС — допуск (№ РУ); габариты/вес здесь не хранятся, соответствие — по совпадению наименования.</div>

  <div *ngIf="registry && !registry.loading && !registry.distinctive && !registry.techSpecParsed && imported" class="lrp-hint">
    ⚠ Совпадение только по названию — модели в реестре неразличимы. Нажмите «ТЗ», чтобы разбор техспецификации уточнил подбор.
  </div>
  <div *ngIf="registry?.loading" class="lrp-loading">Ищем похожие изделия в реестре…</div>
  <div *ngIf="registry && !registry.loading && !registry.items.length" class="lrp-empty">
    Похожих записей в реестре не найдено — вероятно, это не медизделие (услуга/расходник) или нужен другой запрос
  </div>

  <div class="cand" *ngFor="let c of registry?.items || []">
    <div class="cand-main" (click)="toggleRegistryDetail(c)"
         [title]="registry?.openReg === c.regNumber ? 'Свернуть описание' : 'Показать описание из карточки НЦЭЛС'">
      <div class="cand-row1">
        <span *ngIf="registry?.distinctive" class="score-badge" [class.score-good]="c.score >= 0.35">{{ scorePct(c) }}%</span>
        <span *ngIf="!registry?.distinctive" class="score-badge score-name" title="Совпало наименование; для различения моделей разберите ТЗ">✓ по названию</span>
        <span class="cand-name">{{ c.name }}</span>
        <span class="cand-chev">{{ registry?.openReg === c.regNumber ? '▴' : '▾' }}</span>
      </div>
      <div class="cand-row2">
        РУ {{ c.regNumber }} · {{ c.producer || '—' }} · {{ c.country || '—' }} ·
        {{ c.unlimited ? 'бессрочно' : (c.expirationDate ? formatDate(c.expirationDate) : '—') }}
      </div>
    </div>
    <div class="cand-actions">
      <button class="btn btn-adopt" [disabled]="adoptBusy" (click)="adoptFromRegistry(c)"
              title="Создать модель каталога из этого РУ и предложить лоту">Взять в работу</button>
    </div>

    <div *ngIf="registry?.openReg === c.regNumber" class="cand-detail">
      <div *ngIf="registry?.detailLoading" class="lrp-loading">Загружаем карточку НЦЭЛС…</div>
      <div *ngIf="registry?.detailError && !registry?.detailLoading" class="cand-detail-error">
        {{ registry?.detailError }} — сверните и разверните строку, чтобы повторить.
      </div>
      <div *ngIf="registry?.detail && !registry?.detailLoading" class="cand-detail-cols">
        <div *ngIf="lot?.requiredSpec" class="cand-detail-col">
          <div class="cand-detail-h">ТЗ лота</div>
          <pre class="cand-detail-pre">{{ lot.requiredSpec }}</pre>
        </div>
        <div class="cand-detail-col">
          <div class="cand-detail-h">Из реестра НЦЭЛС</div>
          <div *ngIf="registryDetailEmpty(registry?.detail)" class="lrp-empty">В карточке НЦЭЛС описание не заполнено</div>
          <div *ngIf="registry?.detail?.riskClass || registry?.detail?.miKind" class="cand-detail-meta">
            <span *ngIf="registry?.detail?.riskClass">{{ registry.detail.riskClass }}</span>
            <span *ngIf="registry?.detail?.riskClass && registry?.detail?.miKind"> · </span>
            <span *ngIf="registry?.detail?.miKind">{{ registry.detail.miKind }}</span>
            <div *ngIf="registry?.detail?.miKindDef" class="cand-detail-def">{{ registry.detail.miKindDef }}</div>
          </div>
          <div *ngIf="registry?.detail?.purpose" class="cand-detail-block"><b>Назначение:</b> {{ registry.detail.purpose }}</div>
          <div *ngIf="registry?.detail?.useArea" class="cand-detail-block"><b>Область применения:</b> {{ registry.detail.useArea }}</div>
          <div *ngIf="registry?.detail?.techChars" class="cand-detail-block"><b>Краткие тех. характеристики:</b>
            <pre class="cand-detail-pre">{{ registry.detail.techChars }}</pre>
          </div>
        </div>
      </div>
    </div>
  </div>

  <div class="complect-cta" *ngIf="!complect">
    <button class="btn btn-registry" (click)="openComplect()"
            title="Найти лот в комплектности родительского аппарата (для электродов/пластин/принадлежностей)">
      🔧 Комплектность аппаратов
    </button>
    <span class="lrp-note">Если лот — принадлежность к аппарату (электрод, пластина), допуск может быть в комплектности аппарата.</span>
  </div>

  <div class="complect" *ngIf="complect">
    <div class="lrp-head">
      <span><b>Комплектность аппаратов:</b> {{ lot.equipName }}</span>
      <button class="btn btn-cancel" (click)="closeComplect()">✕ Закрыть</button>
    </div>
    <div class="complect-term">
      <input type="text" [(ngModel)]="complect.term" placeholder="Название аппарата (напр. Элэскулап)"
             (keyup.enter)="runComplect(complect.term)">
      <button class="btn btn-primary" [disabled]="complect.loading" (click)="runComplect(complect.term)">Искать</button>
    </div>
    <div *ngIf="complect.loading" class="lrp-loading">Ищем в комплектности аппаратов…</div>
    <div *ngIf="!complect.loading && complect.searched && !complect.apparatuses.length" class="lrp-empty">
      Аппарат не найден — уточните его название в поле выше и нажмите «Искать».
    </div>

    <div *ngFor="let a of complect.apparatuses" class="app-box">
      <div class="app-head">{{ a.name }} · <b>{{ a.country || '—' }}</b> · {{ a.producer || '—' }} · РУ {{ a.regNumber }}</div>
      <div *ngIf="!a._relevant.length && !a._zero.length" class="lrp-empty">Комплектность у этого аппарата не заполнена.</div>

      <div class="comp" *ngFor="let comp of a._relevant; let i = index" [class.comp-reco]="i === 0">
        <div class="comp-row1">
          <span class="score-badge" [class.score-good]="i === 0">{{ scorePct(comp) }}%</span>
          <span *ngIf="i === 0" class="reco-chip">★ рекомендуем</span>
        </div>
        <pre class="comp-pre">{{ comp.productName }}</pre>
        <div class="comp-row2">{{ comp.component || '—' }} · {{ comp.country || '—' }}</div>
        <button class="btn" [class.btn-adopt]="i === 0" [class.btn-adopt-muted]="i !== 0" [disabled]="adoptBusy"
                (click)="adoptComponent(a, comp)"
                title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button>
      </div>

      <button class="zero-toggle" *ngIf="a._zero.length" (click)="a._showZero = !a._showZero">
        {{ a._showZero ? '▴ скрыть нерелевантные' : '▾ ещё ' + a._zero.length + ' нерелевантных (0%)' }}
      </button>
      <ng-container *ngIf="a._showZero">
        <div class="comp comp-zero" *ngFor="let comp of a._zero">
          <div class="comp-row1"><span class="score-badge">0%</span></div>
          <pre class="comp-pre">{{ comp.productName }}</pre>
          <div class="comp-row2">{{ comp.component || '—' }} · {{ comp.country || '—' }}</div>
          <button class="btn btn-adopt-muted" [disabled]="adoptBusy" (click)="adoptComponent(a, comp)"
                  title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button>
        </div>
      </ng-container>
    </div>
  </div>
</div>
```

- [ ] **Step 4: Написать стили на токенах**

Заполнить `styles`. Перенести смысл правил `.registry-panel`, `.registry-*`, `.complect-*`, `.score-badge`, `.reco-chip`, `.btn-adopt*` из `tenders.component.ts:732-768` под новые классы. Обязательно:

```css
.lrp { margin: 10px 0 16px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface-2); }
.lrp-head { display: flex; justify-content: space-between; align-items: center; gap: 12px; margin-bottom: 8px; flex-wrap: wrap; }
.lrp-note { font-size: 12px; color: var(--text-muted); margin: 4px 0 8px; }
.lrp-loading { color: var(--text-muted); padding: 6px 0; }
.lrp-empty { color: var(--text-muted); font-size: 14px; padding: 12px 0; }
.lrp-hint { background: color-mix(in srgb, var(--warn) 15%, transparent); border-left: 3px solid var(--warn);
            padding: 8px 12px; border-radius: 4px; margin-bottom: 8px; font-size: 13px; color: var(--warn); }
.cand { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); margin-bottom: 8px; padding: 10px 12px; }
.cand-main { cursor: pointer; }
.cand-row1 { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.cand-name { font-size: 14px; color: var(--text); flex: 1; min-width: 0; }
.cand-chev { color: var(--text-muted); font-size: 11px; }
.cand-row2 { font-size: 12px; color: var(--text-muted); margin-top: 4px; }
.cand-actions { margin-top: 8px; }
.cand-detail { margin-top: 10px; border-top: 1px solid var(--border); padding-top: 10px; }
.cand-detail-cols { display: flex; gap: 16px; align-items: flex-start; }
.cand-detail-col { flex: 1; min-width: 0; }
.cand-detail-h { font-size: 11px; font-weight: 600; color: var(--text-muted); margin-bottom: 6px; text-transform: uppercase; letter-spacing: .04em; }
.cand-detail-pre { white-space: pre-wrap; max-height: 300px; overflow-y: auto; background: var(--surface-2);
                   border: 1px solid var(--border); border-radius: 6px; padding: 8px 10px; font: inherit; margin: 4px 0 0; }
.cand-detail-meta { margin-bottom: 8px; font-weight: 600; color: var(--text); }
.cand-detail-def { font-size: 12px; color: var(--text-muted); font-weight: 400; margin-top: 2px; }
.cand-detail-block { margin-bottom: 6px; color: var(--text); }
.cand-detail-error { color: var(--danger); padding: 4px 0; }
.score-badge { background: var(--surface-2); color: var(--text); border-radius: 8px; padding: 2px 8px; font-size: 12px; white-space: nowrap; }
.score-badge.score-good { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
.score-badge.score-name { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.complect-cta { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
.complect { margin-top: 12px; border-top: 1px solid var(--border); padding-top: 10px; }
.complect-term { display: flex; gap: 8px; margin-bottom: 10px; flex-wrap: wrap; }
.complect-term input { flex: 1; min-width: 180px; max-width: 360px; padding: 6px 10px; border: 1px solid var(--border);
                       border-radius: 6px; background: var(--surface); color: var(--text); }
.app-box { margin: 10px 0; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
.app-head { font-size: 13px; margin-bottom: 6px; color: var(--text); }
.comp { border-top: 1px solid var(--border); padding: 8px 0; }
.comp-reco { box-shadow: inset 3px 0 0 var(--success); padding-left: 8px; }
.comp-row1 { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.comp-row2 { font-size: 12px; color: var(--text-muted); margin: 4px 0 6px; }
.comp-pre { white-space: pre-wrap; margin: 6px 0 0; font: inherit; color: var(--text); }
.comp-zero { opacity: .75; }
.reco-chip { background: var(--success); color: var(--accent-contrast); border-radius: 8px; padding: 1px 7px; font-size: 11px; white-space: nowrap; }
.zero-toggle { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 12px; padding: 6px 0; }
.zero-toggle:hover { color: var(--text); text-decoration: underline; }
.btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn-cancel { background: var(--surface-2); color: var(--text); }
.btn-primary { background: var(--accent); color: var(--accent-contrast); }
.btn-registry { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.btn-adopt { background: #0e9f6e; color: var(--accent-contrast); }
.btn-adopt-muted { background: var(--surface-2); color: var(--text); }

@media (max-width: 900px) {
  .cand-detail-cols { flex-direction: column; gap: 10px; }
  .cand-actions .btn { width: 100%; }
}
```

- [ ] **Step 5: Подключить компонент в `tenders.component.ts` на текущем месте**

В `tenders.component.ts`:
1. Добавить импорт `import { LotRegistryPanelComponent } from './lot-registry-panel.component';` и `LotRegistryPanelComponent` в массив `imports` декоратора.
2. Удалить из шаблона оба блока `<div class="registry-panel" *ngIf="registryPanel">` и `<div class="registry-panel" *ngIf="complectPanel">` (`tenders.component.ts:316-444`), поставив на их место:

```html
<app-lot-registry-panel
  [lot]="registryLot" [imported]="isImportedTender()"
  (adopted)="onRegistryAdopted($event)"
  (close)="registryLot = null">
</app-lot-registry-panel>
```

3. Заменить поля/методы панели на минимальную обвязку (остальное удалить: `registryPanel`, `onLotRegistry`, `closeRegistryPanel`, `toggleRegistryDetail`, `registryDetailEmpty`, `scorePct`, `adoptFromRegistry`, `adoptBusy`, `complectPanel`, `openComplect`, `runComplect`, `closeComplect`, `adoptComponent`):

```ts
  registryLot: any = null;

  onLotRegistry(l: any) { this.registryLot = l; this.cdr.detectChanges(); }

  /** «Взять в работу» из реестра/комплектности: обновить лоты и сразу предложить запросить КП. */
  onRegistryAdopted(lot: any) {
    this.registryLot = null;
    this.loadLots();
    this.openKpPanelFor(lot);   // сохраняем прежнюю сцепку adoptFromRegistry → панель КП
  }
```

4. Удалить осиротевшие правила стилей `.registry-panel`, `.registry-*`, `.complect-*`, `.score-badge*`, `.reco-chip`, `.btn-adopt*`, `.complect-zero-toggle` из блока `styles` — **кроме** `.registry-panel-head` (его делит селектор с `.pr-section-head`: правило `.registry-panel-head, .pr-section-head { … }` оставить, убрав из него первый селектор) и **кроме** `.btn-registry` и `.complect-zero-toggle`, которые ещё используются в KP-панели и кнопке «Подбор» (удалять их будет Task 2/3).

- [ ] **Step 6: Собрать фронт**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка успешна, ошибок бюджета нет.

- [ ] **Step 7: Живая проверка панели «Подбор»**

Поднять стек, если не поднят: бэкенд `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (Bash с `dangerouslyDisableSandbox: true`), фронт `cd frontend && npm start`.

Через Playwright MCP: `http://localhost:4200` → логин `admin`/`admin` → `localStorage.setItem('ais.market','KZ')` → открыть импортный KZ-тендер с лотами → у лота нажать «Подбор». Проверить:
1. Кандидаты НЦЭЛС отображаются карточками, горизонтального скролла нет.
2. Клик по кандидату разворачивает описание (ТЗ лота ↔ карточка НЦЭЛС); повторный клик сворачивает.
3. «🔧 Комплектность аппаратов» открывает блок, поиск по термину работает.
4. «Взять в работу» → тост, панель закрывается, у лота появляется «Предложено: …», **сразу открывается панель КП** по этому лоту.
5. Повторить на 390px (`browser_resize`) и в тёмной теме (тумблер ☀/☾ в шапке) — читаемо, ничего не уезжает.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/tenders/lot-registry-panel.component.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "$(cat <<'EOF'
refactor(ui): панель «Подбор» (реестр + комплектность) — отдельный компонент, карточки вместо таблиц

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Компонент `app-lot-kp-panel`

Извлечь панель «КП» (подбор поставщиков + превью письма + отправка) в свой компонент, снова **на её нынешнем месте**. Список поставщиков переводится с таблиц на карточки.

**Files:**
- Create: `frontend/src/app/pages/tenders/lot-kp-panel.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `ApiService.getLotSourcing(tenderId, lotIds, term?)`, `.setLotEquipmentType(lotId, typeId)`, `.previewKp({tenderId, distributorIds, items})`, `.sendPriceRequests({tenderId, distributorIds, items, subjectOverride?, bodyOverride?})`; `NotificationService`.
- Produces (на них опирается Task 3):
  ```ts
  @Component({ selector: 'app-lot-kp-panel', standalone: true, … })
  export class LotKpPanelComponent implements OnChanges {
    @Input() tenderId: number | null = null;
    @Input() lots: any[] = [];             // выбранные лоты целиком (нужны quantity и proposedEquipment)
    @Input() equipmentTypes: any[] = [];
    @Output() sent = new EventEmitter<void>();       // КП отправлены → родитель перегружает лоты и запросы
    @Output() typeChanged = new EventEmitter<void>();// вид МИ лота сохранён → родитель перегружает лоты
    @Output() close = new EventEmitter<void>();
  }
  ```

- [ ] **Step 1: Создать компонент-скелет**

Создать `frontend/src/app/pages/tenders/lot-kp-panel.component.ts`:

```ts
import { Component, ChangeDetectorRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

/** Панель «Запрос КП» по одному или нескольким лотам: подбор поставщиков, превью письма, отправка. */
@Component({
  selector: 'app-lot-kp-panel',
  standalone: true,
  imports: [NgFor, NgIf, DecimalPipe, FormsModule],
  template: ``,   // Step 3
  styles: [``],   // Step 4
})
export class LotKpPanelComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Input() lots: any[] = [];
  @Input() equipmentTypes: any[] = [];
  @Output() sent = new EventEmitter<void>();
  @Output() typeChanged = new EventEmitter<void>();
  @Output() close = new EventEmitter<void>();

  panel: {
    loading: boolean; sending: boolean; entries: any[];
    _relevant: any[]; _nonrel: any[]; _showNonrel: boolean;
    singleLot: boolean;
    detectedType: { id: number; name: string; confidence: number } | null;
    typeAlternatives: { id: number; name: string }[];
    sourcingTerm: string;
    lotId: number | null;
  } | null = null;

  preview: { subject: string; body: string; sending: boolean; distributorIds: number[]; items: any[] } | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService) {}

  /** Смена набора лотов = новый подбор. */
  ngOnChanges(ch: SimpleChanges) {
    if (ch['lots']) {
      this.preview = null;
      if (this.lots?.length && this.tenderId) this.load(); else this.panel = null;
    }
  }
}
```

- [ ] **Step 2: Перенести логику из `tenders.component.ts`**

Перенести **дословно**, взяв из `tenders.component.ts`:

| Откуда | Куда | Правка при переносе |
|---|---|---|
| `openKpPanel(term?)` (~1746) | `load(term?)` | `this.selectedTender.id` → `this.tenderId`; `[...this.lotSel]` → `this.lots.map(l => l.id)`; `single = this.lots.length === 1`; `this.kpPanel` → `this.panel` |
| `changeLotType(typeId)` (~1780) | так же | `this.kpPanel` → `this.panel`; вместо `this.loadLots(); this.openKpPanel(term);` → `this.typeChanged.emit(); this.load(term);` |
| `researchSupplier()` (~1791) | так же | `this.load(this.panel?.sourcingTerm || undefined)` |
| `checkedSuppliers()` (~1793) | так же | `this.panel?.entries` |
| `sendKpRequests()` (~1827) | так же | `items` строятся из `this.lots` напрямую: `this.lots.map(l => ({ tenderLotId: l.id, medEquipmentId: l.proposedEquipment?.id ?? null, requestedQuantity: l.quantity ?? 1 }))`; `this.kpPreview` → `this.preview` |
| `confirmSendKp()` (~1849) | так же | `this.selectedTender.id` → `this.tenderId`; вместо `this.kpPreview = null; this.kpPanel = null; this.lotSel.clear(); this.loadPriceRequests();` → `this.preview = null; this.panel = null; this.sent.emit();` |
| `subjectHuman(subject)` (~1873, private) | так же | без правок — **обязателен**: снимает `[КП-…]` из отредактированной темы, токен всегда серверный |
| `cancelKpPreview()` (~1877) | `cancelPreview()` | `this.preview = null` |
| `kpToastFromResults(results)` (~1816) | так же | **копируется, а не переносится** — в родителе он остаётся, им пользуются `onSmartMatchRequest` и `resendPr`. Дублирование намеренное: тащить ради 10 строк общий сервис — лишняя сущность |

- [ ] **Step 3: Написать шаблон — поставщики карточками**

Исходник — `tenders.component.ts:446-542`. Структура сохраняется, таблицы `kp-suppliers` → карточки, `.table-scroll` не остаётся:

```html
<div class="kp" *ngIf="panel">
  <div class="kp-head">
    <span><b>Запрос КП</b> · выбрано лотов: {{ lots.length }}</span>
    <button class="btn btn-cancel" (click)="close.emit()">✕ Закрыть</button>
  </div>

  <div *ngIf="panel.loading" class="kp-loading">Подбираем поставщиков…</div>

  <ng-container *ngIf="!panel.loading">
    <div class="kp-controls" *ngIf="panel.singleLot">
      <label>Вид МИ:
        <select [ngModel]="panel.detectedType?.id ?? ''" (ngModelChange)="changeLotType($event)">
          <option value="">— не задан —</option>
          <option *ngFor="let t of equipmentTypes" [value]="t.id">{{ t.name }}</option>
        </select>
      </label>
      <span class="kp-conf" *ngIf="panel.detectedType && panel.detectedType.confidence < 1">
        авто · {{ (panel.detectedType.confidence * 100) | number:'1.0-0' }}%
      </span>
      <label class="kp-term">Поиск поставщика:
        <input type="text" [(ngModel)]="panel.sourcingTerm" placeholder="бренд/аппарат" (keyup.enter)="researchSupplier()">
      </label>
      <button class="btn btn-line" (click)="researchSupplier()">Найти</button>
    </div>

    <div class="kp-empty" *ngIf="!panel.entries.length">На этом рынке нет поставщиков — добавьте их в справочнике «Дистрибьюторы»</div>
    <div class="kp-empty" *ngIf="panel.entries.length && !panel._relevant.length && !panel.detectedType">
      Нужна техспецификация или вид МИ, чтобы подобрать по специализации.
    </div>

    <label class="sup" *ngFor="let e of panel._relevant; let i = index" [class.sup-hit]="e.preselect" [class.sup-reco]="i === 0">
      <input type="checkbox" [(ngModel)]="e._checked" [ngModelOptions]="{standalone: true}" />
      <span class="sup-body">
        <span class="sup-name">
          <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener"
             class="supplier-link" title="Открыть сайт поставщика" (click)="$event.stopPropagation()">{{ e.distributor?.name }} ↗</a>
          <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
          <span *ngIf="!e.distributor?.equipmentTypes?.length" class="tag-all"> · все виды</span>
        </span>
        <span class="sup-mail">{{ e.distributor?.email || '—' }}
          <span class="no-email" *ngIf="!e.distributor?.email">письмо не уйдёт</span>
        </span>
        <span class="sup-reasons">
          <span class="reason-chip" *ngFor="let r of e.reasons"
                [class.reason-type]="r.kind === 'TYPE'" [class.reason-brand]="r.kind === 'BRAND'">
            {{ r.kind === 'TYPE' ? '✓' : 'возит' }} {{ r.label }}
          </span>
        </span>
      </span>
    </label>

    <div class="kp-nonrel" *ngIf="panel._nonrel.length">
      <button class="zero-toggle" (click)="panel._showNonrel = !panel._showNonrel">
        {{ panel._showNonrel ? '▴ скрыть нерелевантных' : '▾ ещё ' + panel._nonrel.length + ' нерелевантных' }}
      </button>
      <ng-container *ngIf="panel._showNonrel">
        <label class="sup" *ngFor="let e of panel._nonrel">
          <input type="checkbox" [(ngModel)]="e._checked" [ngModelOptions]="{standalone: true}" />
          <span class="sup-body">
            <span class="sup-name">
              <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener"
                 class="supplier-link" (click)="$event.stopPropagation()">{{ e.distributor?.name }} ↗</a>
              <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
            </span>
            <span class="sup-mail">{{ e.distributor?.email || '—' }}</span>
          </span>
        </label>
      </ng-container>
    </div>

    <div class="kp-actions" *ngIf="panel.entries.length">
      <button class="btn btn-save" [disabled]="panel.sending || checkedSuppliers().length === 0" (click)="sendKpRequests()">
        {{ panel.sending ? 'Отправка…' : 'Отправить запросы (' + checkedSuppliers().length + ')' }}
      </button>
    </div>
  </ng-container>
</div>

<div class="kp-preview-overlay" *ngIf="preview" (click)="cancelPreview()">
  <div class="kp-preview" (click)="$event.stopPropagation()">
    <h3>Текст письма — проверьте перед отправкой</h3>
    <p class="kp-preview-note">Метка [КП-№] будет присвоена автоматически при отправке. Письмо уйдёт {{ preview.distributorIds.length }} поставщик(ам).</p>
    <label class="kp-preview-lbl">Тема</label>
    <input class="kp-preview-subject" [(ngModel)]="preview.subject" />
    <label class="kp-preview-lbl">Текст</label>
    <textarea class="kp-preview-body" rows="16" [(ngModel)]="preview.body"></textarea>
    <div class="kp-preview-actions">
      <button class="btn btn-cancel" (click)="cancelPreview()">Отмена</button>
      <button class="btn btn-save" [disabled]="preview.sending" (click)="confirmSendKp()">
        {{ preview.sending ? 'Отправка…' : 'Отправить' }}
      </button>
    </div>
  </div>
</div>
```

- [ ] **Step 4: Написать стили на токенах**

```css
.kp { border: 1px solid var(--border); background: var(--surface-2); border-radius: 8px; padding: 12px 14px; margin: 12px 0; }
.kp-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; flex-wrap: wrap; }
.kp-loading { color: var(--text-muted); padding: 6px 0; }
.kp-empty { color: var(--text-muted); font-size: 14px; padding: 12px 0; }
.kp-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
.kp-controls select, .kp-term input { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px;
                                      font-size: 13px; background: var(--surface); color: var(--text); }
.kp-conf { font-size: 12px; color: var(--text-muted); }
.sup { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; margin-bottom: 6px; cursor: pointer;
       border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
.sup-hit { background: var(--surface-2); }
.sup-reco { box-shadow: inset 3px 0 0 var(--success); }
.sup-body { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
.sup-name { font-size: 14px; color: var(--text); }
.sup-mail { font-size: 12px; color: var(--text-muted); }
.sup-reasons { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 2px; }
.supplier-link { color: var(--accent); text-decoration: none; }
.supplier-link:hover { text-decoration: underline; }
.tag-all { color: var(--text-muted); font-size: 11px; }
.no-email { color: var(--danger); font-size: 11px; margin-left: 6px; }
.reason-chip { display: inline-block; border-radius: 999px; padding: 2px 8px; font-size: 11px; }
.reason-type { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
.reason-brand { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.zero-toggle { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 12px; padding: 6px 0; }
.zero-toggle:hover { color: var(--text); text-decoration: underline; }
.kp-actions { margin-top: 10px; display: flex; justify-content: flex-end; }
.kp-preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center;
                      justify-content: center; z-index: 1000; }
.kp-preview { background: var(--surface); color: var(--text); border-radius: 10px; padding: 20px;
              width: min(720px, 92vw); max-height: 88vh; overflow: auto; }
.kp-preview-note { color: var(--text-muted); font-size: 12.5px; margin: 4px 0 12px; }
.kp-preview-lbl { display: block; font-size: 12px; color: var(--text); margin: 8px 0 3px; }
.kp-preview-subject, .kp-preview-body { width: 100%; padding: 8px 10px; border: 1px solid var(--border);
                                        border-radius: 6px; background: var(--surface); color: var(--text); }
.kp-preview-body { font: inherit; resize: vertical; }
.kp-preview-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; }
.btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn-cancel { background: var(--surface-2); color: var(--text); }
.btn-save { background: var(--accent); color: var(--accent-contrast); }
.btn-line { background: var(--surface); color: var(--text); border: 1px solid var(--border); }

@media (max-width: 900px) {
  .kp-controls { flex-direction: column; align-items: stretch; }
  .kp-actions .btn { width: 100%; }
}
```

- [ ] **Step 5: Подключить в `tenders.component.ts` на текущем месте**

1. Импорт `LotKpPanelComponent` + в `imports` декоратора.
2. Заменить блоки `<div class="kp-panel" *ngIf="kpPanel">` и `<div class="kp-preview-overlay" *ngIf="kpPreview">` (`tenders.component.ts:446-542`) на:

```html
<app-lot-kp-panel
  [tenderId]="selectedTender?.id ?? null" [lots]="kpLots" [equipmentTypes]="equipmentTypesList"
  (sent)="onKpSent()" (typeChanged)="loadLots()" (close)="kpLots = []">
</app-lot-kp-panel>
```

3. Заменить поля/методы (удалить `kpPanel`, `kpPreview`, `openKpPanel`, `changeLotType`, `researchSupplier`, `checkedSuppliers`, `sendKpRequests`, `confirmSendKp`, `subjectHuman`, `cancelKpPreview`; **`kpToastFromResults` оставить** — им пользуются `onSmartMatchRequest` и `resendPr`):

```ts
  kpLots: any[] = [];

  /** Открыть панель КП по одному лоту. */
  openKpPanelFor(l: any) { this.lotSel.clear(); this.lotSel.add(l.id); this.kpLots = [l]; this.cdr.detectChanges(); }

  /** Открыть панель КП по всем отмеченным лотам. */
  openKpPanel() { this.kpLots = this.lots.filter((l: any) => this.lotSel.has(l.id)); this.cdr.detectChanges(); }

  onKpSent() { this.kpLots = []; this.lotSel.clear(); this.loadPriceRequests(); this.cdr.detectChanges(); }
```

4. В `onBack()` заменить `this.kpPanel = null;` на `this.kpLots = [];`.
5. Удалить осиротевшие стили `.kp-panel*`, `.kp-suppliers`, `.kp-controls`, `.kp-conf`, `.kp-term`, `.reason-chip`, `.reason-*`, `.tag-all`, `.supplier-link`, `.no-email`, `.kp-preview*`, `.brand-chip` из блока `styles`.

- [ ] **Step 6: Собрать фронт**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка успешна.

- [ ] **Step 7: Живая проверка панели «КП»**

Playwright, KZ-тендер:
1. Кнопка «КП» у лота → панель, поставщики карточками, релевантные сверху с чипами причин, лучший подсвечен, сильнейшие преотмечены.
2. Смена «Вид МИ» в селекторе → подбор пересобирается, значение сохраняется (перезагрузить лот и проверить).
3. Поле «Поиск поставщика» + «Найти» → подбор по термину.
4. «ещё N нерелевантных» разворачивается.
5. «Отправить запросы (N)» → превью письма → **тема содержит `[КП-…]`? нет — токен приклеит сервер**; отредактировать тему и тело → «Отправить» → тост, панель закрывается, в «Запросах КП» новый запрос.
6. Отметить 2+ лота → «Запросить КП по выбранным (N)» → панель в мульти-режиме (селектор вида МИ и поле термина скрыты).
7. Повторить на 390px и в тёмной теме.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/tenders/lot-kp-panel.component.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "$(cat <<'EOF'
refactor(ui): панель «КП» — отдельный компонент, поставщики карточками

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: Компонент `app-tender-lots` — лоты-аккордеоны и чип стадии

Ядро работы: таблица лотов заменяется списком карточек-аккордеонов, панели переезжают внутрь развёрнутого лота. Форма лота на этом шаге **остаётся в родителе** (её переносит Task 4) — чтобы задача оставалась обозримой.

**Files:**
- Create: `frontend/src/app/pages/tenders/tender-lots.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `LotRegistryPanelComponent`, `LotKpPanelComponent` (Task 1–2); `ApiService.parseLotTechSpec(lotId)`, `.clearProposedEquipment(lotId)`; `MarketService`, `NotificationService`, `MarketMoneyPipe`.
- Produces:
  ```ts
  @Component({ selector: 'app-tender-lots', standalone: true, … })
  export class TenderLotsComponent {
    @Input() tender: any = null;
    @Input() lots: any[] = [];
    @Input() priceRequests: any[] = [];
    @Input() equipmentTypes: any[] = [];
    @Output() lotsChanged = new EventEmitter<void>();
    @Output() priceRequestsChanged = new EventEmitter<void>();
    @Output() bulkPriceRequested = new EventEmitter<void>();
    @Output() matchRequested = new EventEmitter<{ lotId: number; lotNumber: number }>();
    @Output() addLotRequested = new EventEmitter<void>();
    @Output() editLotRequested = new EventEmitter<any>();
    @Output() deleteLotRequested = new EventEmitter<number>();
  }
  ```

- [ ] **Step 1: Создать компонент с состоянием и вычислением стадии**

Создать `frontend/src/app/pages/tenders/tender-lots.component.ts`:

```ts
import { Component, ChangeDetectorRef, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { LotRegistryPanelComponent } from './lot-registry-panel.component';
import { LotKpPanelComponent } from './lot-kp-panel.component';

/** Стадия работы по лоту — считается на клиенте из уже загруженных лотов и запросов КП. */
export interface LotStage { code: string; label: string; tone: 'muted' | 'accent' | 'warn' | 'success' | 'danger'; filled: boolean; }

@Component({
  selector: 'app-tender-lots',
  standalone: true,
  imports: [NgFor, NgIf, MarketMoneyPipe, LotRegistryPanelComponent, LotKpPanelComponent],
  template: ``,   // Step 3
  styles: [``],   // Step 4
})
export class TenderLotsComponent {
  @Input() tender: any = null;
  @Input() lots: any[] = [];
  @Input() priceRequests: any[] = [];
  @Input() equipmentTypes: any[] = [];
  @Output() lotsChanged = new EventEmitter<void>();
  @Output() priceRequestsChanged = new EventEmitter<void>();
  @Output() bulkPriceRequested = new EventEmitter<void>();
  @Output() matchRequested = new EventEmitter<{ lotId: number; lotNumber: number }>();
  @Output() addLotRequested = new EventEmitter<void>();
  @Output() editLotRequested = new EventEmitter<any>();
  @Output() deleteLotRequested = new EventEmitter<number>();

  expandedLotId: number | null = null;
  lotSel = new Set<number>();
  tzBusy = new Set<number>();
  openMenuLotId: number | null = null;

  registryLot: any = null;   // для какого лота открыта панель «Подбор»
  kpLots: any[] = [];        // по каким лотам открыта панель «КП» (1 — внутри лота, >1 — над списком)

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, public market: MarketService) {}

  // ===== разворот =====
  toggleLot(l: any) {
    this.expandedLotId = this.expandedLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  isExpanded(l: any): boolean { return this.expandedLotId === l.id; }

  // ===== стадия лота =====
  /** Все позиции запросов КП, относящиеся к лоту. */
  private itemsForLot(lotId: number): any[] {
    const out: any[] = [];
    for (const pr of this.priceRequests || []) {
      for (const it of (pr.items || [])) if (it.tenderLot?.id === lotId) out.push(it);
    }
    return out;
  }
  /** Запросы КП, в которых есть этот лот. */
  private prsForLot(lotId: number): any[] {
    return (this.priceRequests || []).filter((pr: any) => (pr.items || []).some((it: any) => it.tenderLot?.id === lotId));
  }

  lotStage(l: any): LotStage {
    const items = this.itemsForLot(l.id);
    // считаем по персистентной цене, не по редактируемой it._editPrice
    const priced = items.filter((it: any) => it.responsePrice != null).length;
    if (priced > 0) return { code: 'PRICED', label: `Есть цены: ${priced}`, tone: 'success', filled: true };
    const prs = this.prsForLot(l.id);
    if (prs.length && prs.every((pr: any) => pr.status === 'DECLINED'))
      return { code: 'DECLINED', label: 'Отказы', tone: 'danger', filled: true };
    if (prs.length) return { code: 'KP_SENT', label: 'КП отправлено', tone: 'warn', filled: true };
    if (l.proposedEquipment) return { code: 'MODEL', label: 'Модель выбрана', tone: 'accent', filled: true };
    if (l.requiredSpec) return { code: 'SPEC', label: 'Есть ТЗ', tone: 'accent', filled: true };
    return { code: 'NO_SPEC', label: 'Нужно ТЗ', tone: 'muted', filled: false };
  }

  kpDistributorsFor(lotId: number): string[] {
    const names: string[] = [];
    for (const pr of this.priceRequests || []) {
      if ((pr.items || []).some((it: any) => it.tenderLot?.id === lotId)
          && pr.distributor?.name && !names.includes(pr.distributor.name)) {
        names.push(pr.distributor.name);
      }
    }
    return names;
  }

  // ===== деградации (переносятся дословно из tenders.component) =====
  isKz(): boolean { return this.market.value === 'KZ'; }
  isImportedTender(): boolean { return this.isKz() && /^\d+-\d+$/.test(this.tender?.tenderNumber || ''); }
  lotHasCriteria(l: any): boolean {
    return !!(l.equipmentType || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg);
  }
  hasDetails(l: any): boolean {
    return !!(l.equipmentType?.name || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg || l.manufact);
  }
  dimsText(l: any): string {
    return `${l.maxLengthMm || '—'}×${l.maxWidthMm || '—'}×${l.maxHeightMm || '—'}`;
  }

  // ===== выбор лотов =====
  toggleLotSel(l: any, ev: Event) {
    ev.stopPropagation();   // клик по чекбоксу не разворачивает лот
    if (this.lotSel.has(l.id)) this.lotSel.delete(l.id); else this.lotSel.add(l.id);
    this.cdr.detectChanges();
  }
  allLotsSelected(): boolean { return this.lots.length > 0 && this.lots.every((l: any) => this.lotSel.has(l.id)); }
  toggleAllLots(checked: boolean) {
    this.lotSel.clear();
    if (checked) for (const l of this.lots) this.lotSel.add(l.id);
    this.cdr.detectChanges();
  }

  // ===== overflow-меню строки лота =====
  toggleLotMenu(l: any, ev: Event) {
    ev.stopPropagation();   // иначе document:click тут же закроет
    this.openMenuLotId = this.openMenuLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  @HostListener('document:click')
  closeLotMenu() {
    if (this.openMenuLotId !== null) { this.openMenuLotId = null; this.cdr.detectChanges(); }
  }

  // ===== панели =====
  openRegistry(l: any, ev: Event) { ev.stopPropagation(); this.registryLot = l; this.kpLots = []; this.cdr.detectChanges(); }
  openKpFor(l: any, ev: Event) { ev.stopPropagation(); this.lotSel.clear(); this.lotSel.add(l.id); this.kpLots = [l]; this.registryLot = null; this.cdr.detectChanges(); }
  openKpSelected() { this.kpLots = this.lots.filter((l: any) => this.lotSel.has(l.id)); this.registryLot = null; this.cdr.detectChanges(); }

  /** «Взять в работу» из реестра/комплектности → обновить лоты и сразу предложить запросить КП по этому лоту. */
  onRegistryAdopted(lot: any) {
    this.registryLot = null;
    this.lotsChanged.emit();
    this.lotSel.clear(); this.lotSel.add(lot.id);
    this.kpLots = [lot];
    this.cdr.detectChanges();
  }
  onKpSent() { this.kpLots = []; this.lotSel.clear(); this.priceRequestsChanged.emit(); this.cdr.detectChanges(); }

  // ===== действия лота =====
  parseTechSpec(l: any, ev: Event) {
    ev.stopPropagation();
    this.tzBusy.add(l.id);
    this.cdr.detectChanges();
    this.api.parseLotTechSpec(l.id).subscribe({
      next: (r: any) => {
        this.tzBusy.delete(l.id);
        const dims = r.dimsFound ? 'габариты ✓' : 'габариты —';
        const weight = r.weightFound ? 'вес ✓' : 'вес —';
        const amb = r.ambiguous ? ' (неоднозначный матч лота — проверьте вручную)' : '';
        const specLen = (r.lot?.requiredSpec || '').length;
        this.notify.success(`ТЗ разобрано: спека ${specLen} симв., ${dims}, ${weight}${amb}`);
        this.lotsChanged.emit();
      },
      error: (e: any) => {
        this.tzBusy.delete(l.id);
        this.notify.error(e.error?.message || e.message || 'Не удалось разобрать ТЗ');
        this.cdr.detectChanges();
      }
    });
  }

  clearProposed(l: any, ev: Event) {
    ev.stopPropagation();
    this.api.clearProposedEquipment(l.id).subscribe({
      next: () => { this.notify.success('Предложение модели снято'); this.lotsChanged.emit(); },
      error: (e: any) => this.notify.error(e.error?.message || 'Ошибка'),
    });
  }
}
```

- [ ] **Step 2: Дописать разворот спецификации**

Добавить в класс (заменяет прежние `toggleSpec`/`specPreview` — превью-обрезка больше не нужна, спека живёт в аккордеоне):

```ts
  specOpenLotId: number | null = null;
  toggleSpec(l: any, ev: Event) {
    ev.stopPropagation();
    this.specOpenLotId = this.specOpenLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  isSpecOpen(l: any): boolean { return this.specOpenLotId === l.id; }
```

- [ ] **Step 3: Написать шаблон — тулбар + лоты-аккордеоны**

```html
<div class="lots-toolbar">
  <button class="btn btn-add" (click)="addLotRequested.emit()">Добавить лот</button>
  <button class="btn btn-bulk" *ngIf="lots.length > 0 && !isImportedTender()" (click)="bulkPriceRequested.emit()">
    Запросить КП по всему тендеру
  </button>
  <button class="btn btn-kp-sel" *ngIf="lots.length > 0" [disabled]="lotSel.size === 0" (click)="openKpSelected()">
    Запросить КП по выбранным ({{ lotSel.size }})
  </button>
  <label class="select-all" *ngIf="lots.length > 0">
    <input type="checkbox" [checked]="allLotsSelected()" (change)="toggleAllLots($any($event.target).checked)" />
    выбрать все
  </label>
  <span class="counter" *ngIf="lots.length">Найдено: {{ lots.length }} лотов</span>
</div>

<!-- мульти-лотовая панель КП относится не к одному лоту → живёт над списком -->
<app-lot-kp-panel *ngIf="kpLots.length > 1"
  [tenderId]="tender?.id ?? null" [lots]="kpLots" [equipmentTypes]="equipmentTypes"
  (sent)="onKpSent()" (typeChanged)="lotsChanged.emit()" (close)="kpLots = []">
</app-lot-kp-panel>

<div class="lots-empty" *ngIf="lots.length === 0">Нет лотов</div>

<div class="lot" *ngFor="let l of lots" [class.lot-open]="isExpanded(l)">
  <div class="lot-head" (click)="toggleLot(l)">
    <input class="lot-check" type="checkbox" [checked]="lotSel.has(l.id)" (click)="toggleLotSel(l, $event)" />
    <div class="lot-head-body">
      <div class="lot-title">
        <span class="lot-num" *ngIf="l.lotNumber">&#8470;{{ l.lotNumber }}</span>
        <span class="lot-name">{{ l.equipName }}</span>
        <span class="stage-chip" [class]="'stage-' + lotStage(l).tone">
          {{ lotStage(l).filled ? '●' : '○' }} {{ lotStage(l).label }}
        </span>
        <span class="lot-chev">{{ isExpanded(l) ? '▴' : '▾' }}</span>
      </div>
      <div class="lot-metrics">
        {{ l.quantity }} шт<span *ngIf="l.maxCost"> · до {{ l.maxCost | money }}</span>
      </div>
      <div class="lot-proposed" *ngIf="l.proposedEquipment">
        <span class="badge-proposed">Предложено:</span>
        {{ l.proposedEquipment.name }} ({{ l.proposedEquipment.manufact }})
        <span class="badge-reg-ok" *ngIf="l.proposedEquipment.registrationStatus === 'REGISTERED'"
              [title]="'РУ ' + (l.proposedEquipment.regNumber || '')">РУ ✓</span>
        <button class="x-mini" (click)="clearProposed(l, $event)" title="Снять предложение">✕</button>
      </div>
      <div class="lot-kp" *ngIf="kpDistributorsFor(l.id).length">✉ КП: {{ kpDistributorsFor(l.id).join(', ') }}</div>
    </div>
  </div>

  <div class="lot-body" *ngIf="isExpanded(l)">
    <div class="lot-details" *ngIf="hasDetails(l)">
      <span *ngIf="l.equipmentType?.name"><b>Тип:</b> {{ l.equipmentType.name }}</span>
      <span *ngIf="l.maxLengthMm || l.maxWidthMm || l.maxHeightMm"><b>Габариты (макс.):</b> {{ dimsText(l) }}</span>
      <span *ngIf="l.maxWeightKg"><b>Макс. вес:</b> {{ l.maxWeightKg }} кг</span>
      <span *ngIf="l.manufact"><b>Бренд/модель:</b> {{ l.manufact }}</span>
    </div>

    <div class="lot-actions">
      <button class="btn btn-tz" *ngIf="isImportedTender()" [disabled]="tzBusy.has(l.id)" (click)="parseTechSpec(l, $event)"
              title="Скачать и разобрать техспецификацию с площадки">{{ tzBusy.has(l.id) ? '…' : 'ТЗ' }}</button>
      <button class="btn btn-registry" *ngIf="isKz()" (click)="openRegistry(l, $event)"
              title="Подбор из реестра НЦЭЛС (кандидаты + комплектность аппаратов)">Подбор</button>
      <button class="btn btn-kp" (click)="openKpFor(l, $event)">КП</button>
      <!-- каталог-матч: только РФ (KZ-каталог наполняется из реестра, там подбор — через «Подбор») -->
      <button class="btn btn-match" *ngIf="lotHasCriteria(l) && !isKz()"
              (click)="$event.stopPropagation(); matchRequested.emit({ lotId: l.id, lotNumber: l.lotNumber })">Подобрать</button>
      <span class="lot-menu-wrap">
        <button class="btn btn-more" (click)="toggleLotMenu(l, $event)" title="Ещё действия">⋯</button>
        <span class="lot-menu" *ngIf="openMenuLotId === l.id">
          <button (click)="editLotRequested.emit(l); openMenuLotId = null">✎ Редактировать</button>
          <button class="danger" (click)="deleteLotRequested.emit(l.id); openMenuLotId = null">🗑 Удалить</button>
        </span>
      </span>
    </div>

    <div class="lot-spec" *ngIf="l.requiredSpec">
      <button class="spec-toggle" (click)="toggleSpec(l, $event)">
        {{ isSpecOpen(l) ? '▴' : '▾' }} Техническая спецификация
      </button>
      <div class="spec-body" *ngIf="isSpecOpen(l)">{{ l.requiredSpec }}</div>
    </div>

    <app-lot-registry-panel *ngIf="registryLot?.id === l.id"
      [lot]="registryLot" [imported]="isImportedTender()"
      (adopted)="onRegistryAdopted($event)" (close)="registryLot = null">
    </app-lot-registry-panel>

    <app-lot-kp-panel *ngIf="kpLots.length === 1 && kpLots[0].id === l.id"
      [tenderId]="tender?.id ?? null" [lots]="kpLots" [equipmentTypes]="equipmentTypes"
      (sent)="onKpSent()" (typeChanged)="lotsChanged.emit()" (close)="kpLots = []">
    </app-lot-kp-panel>
  </div>
</div>
```

- [ ] **Step 4: Написать стили на токенах**

```css
.lots-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
.select-all { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-muted); cursor: pointer; }
.counter { color: var(--text-muted); font-size: 13px; }
.lots-empty { color: var(--text-muted); font-size: 14px; padding: 32px 0; text-align: center; }

.lot { border: 1px solid var(--border); border-radius: 10px; background: var(--surface); margin-bottom: 10px; overflow: visible; }
.lot-open { border-color: var(--accent); }
.lot-head { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; cursor: pointer; }
.lot-head:hover { background: var(--surface-2); }
.lot-check { margin-top: 3px; flex: 0 0 auto; }
.lot-head-body { flex: 1; min-width: 0; }
.lot-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.lot-num { font-weight: 600; color: var(--accent); font-size: 14px; }
.lot-name { font-size: 15px; color: var(--text); font-weight: 500; flex: 1; min-width: 0; }
.lot-chev { color: var(--text-muted); font-size: 11px; }
.lot-metrics { font-size: 13px; color: var(--text-muted); margin-top: 3px; }
.lot-proposed { margin-top: 5px; font-size: 12px; color: var(--text); }
.lot-kp { margin-top: 4px; font-size: 12px; color: var(--text-muted); }
.badge-proposed { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success);
                  border-radius: 8px; padding: 1px 7px; font-size: 11px; font-weight: 600; margin-right: 4px; }
.badge-reg-ok { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent);
                border-radius: 8px; padding: 1px 6px; font-size: 11px; font-weight: 600; margin-left: 4px; }
.x-mini { background: none; border: none; color: var(--danger); cursor: pointer; font-size: 13px; margin-left: 4px; }

.stage-chip { border-radius: 999px; padding: 2px 9px; font-size: 11px; font-weight: 600; white-space: nowrap; }
.stage-muted { background: var(--surface-2); color: var(--text-muted); }
.stage-accent { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.stage-warn { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn); }
.stage-success { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
.stage-danger { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); }

.lot-body { padding: 0 14px 14px; border-top: 1px solid var(--border); }
.lot-details { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; color: var(--text); padding: 10px 0; }
.lot-details b { color: var(--text-muted); font-weight: 600; }
.lot-actions { display: flex; gap: 8px; flex-wrap: wrap; padding-bottom: 4px; }
.lot-spec { margin-top: 10px; }
.spec-toggle { background: none; border: none; padding: 0; cursor: pointer; color: var(--accent); font-size: 13px; }
.spec-toggle:hover { text-decoration: underline; }
.spec-body { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.6; color: var(--text);
             padding: 10px 12px; margin-top: 8px; max-height: 340px; overflow-y: auto;
             background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; }

.lot-menu-wrap { position: relative; display: inline-block; }
.lot-menu { position: absolute; right: 0; top: 100%; margin-top: 4px; background: var(--surface);
            border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow); z-index: 20;
            display: flex; flex-direction: column; min-width: 150px; overflow: hidden; }
.lot-menu button { background: none; border: none; text-align: left; padding: 8px 12px; cursor: pointer;
                   font-size: 13px; color: var(--text); white-space: nowrap; }
.lot-menu button:hover { background: var(--surface-2); }
.lot-menu button.danger { color: var(--danger); }

.btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
.btn:disabled { opacity: .5; cursor: not-allowed; }
.btn-add { background: var(--accent); color: var(--accent-contrast); }
.btn-bulk { background: #8b5cf6; color: var(--accent-contrast); }
.btn-kp-sel { background: #0e9f6e; color: var(--accent-contrast); }
.btn-kp { background: #0e9f6e; color: var(--accent-contrast); }
.btn-tz { background: #6366f1; color: var(--accent-contrast); }
.btn-registry { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
.btn-match { background: var(--success); color: var(--accent-contrast); }
.btn-more { background: var(--surface-2); color: var(--text); font-weight: 700; padding: 4px 9px; }

@media (max-width: 900px) {
  .lots-toolbar .btn { flex: 1 1 auto; }
  .lot-details { flex-direction: column; gap: 4px; }
  .lot-actions .btn { flex: 1 1 auto; }
}
```

- [ ] **Step 5: Подключить в `tenders.component.ts`, удалить таблицу лотов**

1. Импорт `TenderLotsComponent` + в `imports`.
2. Удалить из шаблона: блок `<div class="toolbar">` зоны лотов (`tenders.component.ts:206-216`), `<div *ngIf="lots.length === 0 …" class="empty">Нет лотов</div>`, весь `<div class="table-scroll" *ngIf="lots.length > 0">…</table></div>` (`250-314`), а также вставленные в Task 1/2 теги `<app-lot-registry-panel>` и `<app-lot-kp-panel>` (они переезжают внутрь `app-tender-lots`). На их место — один тег после `<h3>Лоты тендера</h3>` и формы лота:

```html
<app-tender-lots
  [tender]="selectedTender" [lots]="lots" [priceRequests]="priceRequests" [equipmentTypes]="equipmentTypesList"
  (lotsChanged)="loadLots()"
  (priceRequestsChanged)="loadPriceRequests()"
  (bulkPriceRequested)="bulkPriceTenderId = selectedTender.id"
  (matchRequested)="onMatchRequested($event)"
  (addLotRequested)="onAddLot()"
  (editLotRequested)="onEditLot($event)"
  (deleteLotRequested)="onDeleteLot($event)">
</app-tender-lots>
```

3. Удалить из класса переехавшие члены: `lotSel`, `toggleLotSel`, `allLotsSelected`, `toggleAllLots`, `tzBusy`, `parseTechSpec`, `clearProposed`, `kpDistributorsFor`, `lotHasCriteria`, `isImportedTender`, `hasAnyType`, `hasAnyDims`, `hasAnyWeight`, `toggleSpec`, `specPreview`, `openMenuLotId`, `toggleLotMenu`, `closeLotMenu` (+ его `@HostListener`), `registryLot`, `onLotRegistry`, `onRegistryAdopted`, `kpLots`, `openKpPanelFor`, `openKpPanel`, `onKpSent`. Если `HostListener` больше нигде не нужен — убрать его из импорта `@angular/core`.
4. Заменить `onMatch(lot)` на:

```ts
  onMatchRequested(ev: { lotId: number; lotNumber: number }) {
    this.matchLotId = ev.lotId;
    this.matchLotNumber = ev.lotNumber;
    this.cdr.detectChanges();
  }
```

5. В `onBack()` убрать `this.lotSel.clear();` и `this.kpLots = [];` (состояние живёт в дочернем компоненте, который пересоздаётся вместе с карточкой).
6. `onSmartMatchRequest` оставить как есть — он использует `this.lots`, `kpToastFromResults`, `loadPriceRequests`, все они остаются в родителе.
7. Удалить осиротевшие стили зоны лотов из `styles`: `.actions`, `.w-36`, `.btn-kp`, `.btn-tz`, `.btn-kp-selected`, `.btn-add-bulk`, `.btn-match`, `.btn-registry`, `.btn-more`, `.proposed-line`, `.badge-proposed`, `.badge-reg-ok`, `.x-mini`, `.kp-line`, `.lot-menu*`, `.spec-cell`, `.spec-toggle`, `.spec-empty`, `.spec-row`, `.spec-full*`, `.complect-zero-toggle`, `.recommended`, `.reco-chip`. **Оставить** `.lot-mini*` (мини-чипы лотов в списке тендеров) и общие `table`/`th`/`td` (их использует секция «Запросы КП»).

- [ ] **Step 6: Собрать фронт**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка успешна; в выводе бюджета `tenders.component` должен стать заметно меньше 18 kB.

- [ ] **Step 7: Живая проверка списка лотов**

Playwright на KZ-тендере с несколькими лотами, 1280px:
1. Лоты — карточки; горизонтального скролла нет нигде.
2. Чипы стадий: лот без ТЗ → «○ Нужно ТЗ»; с ТЗ → «● Есть ТЗ»; с предложенной моделью → «● Модель выбрана»; с отправленным КП → «● КП отправлено»; с введённой ценой → «● Есть цены: N».
3. Клик по шапке разворачивает лот, повторный — сворачивает; развёрнут всегда один.
4. Клик по чекбоксу **не** разворачивает лот; выбор сохраняется при сворачивании.
5. «Подбор» и «КП» открываются **внутри** лота; «Взять в работу» → панель КП по тому же лоту.
6. «выбрать все» + «Запросить КП по выбранным (N)» → панель над списком.
7. Меню «⋯» → «Редактировать» открывает форму лота, «Удалить» спрашивает подтверждение.
8. РФ-рынок: «Подобрать» открывает smart-match, кнопки «Подбор» нет.
9. Повторить на 390px и в тёмной теме.

- [ ] **Step 8: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/tenders/tender-lots.component.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "$(cat <<'EOF'
feat(ui): лоты тендера — карточки-аккордеоны с чипом стадии, панели внутри лота

Таблица лотов на 10 колонок заменена списком карточек: свёрнутый лот показывает
название, количество, цену, предложенную модель, получателей КП и чип стадии
работы; развёрнутый — детали, действия, спецификацию и панели «Подбор»/«КП»
прямо в контексте лота. Горизонтальный скролл на мобилке ушёл.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: Форма лота переезжает в `app-tender-lots` + фикс селекта типа

Единственная задача, которая меняет поведение: селект «Тип оборудования» перестаёт быть хардкодом и начинает сохраняться.

**Files:**
- Modify: `frontend/src/app/pages/tenders/tender-lots.component.ts`
- Modify: `frontend/src/app/pages/tenders/tenders.component.ts`

**Interfaces:**
- Consumes: `@Input() equipmentTypes` (уже есть с Task 3), `ApiService.create('lots', body)`, `.update('lots', id, body)`, `.delete('lots', id)`, `ConfirmService.ask(...)`.
- Produces: `@Input() applyItems: any[]` (для запрета удаления используемого лота); выходы `addLotRequested`/`editLotRequested`/`deleteLotRequested` **удаляются** — форма и удаление теперь внутри компонента.

- [ ] **Step 1: Перенести состояние формы в `tender-lots.component.ts`**

Добавить в класс (импортировать `ReactiveFormsModule, FormGroup, FormControl, Validators` из `@angular/forms`, `ConfirmService`, и добавить `ReactiveFormsModule` в `imports` декоратора):

```ts
  @Input() applyItems: any[] = [];

  showLotForm = false;
  editingLotId: number | null = null;
  validationErrors: any = {};

  lotForm = new FormGroup({
    lotNumber: new FormControl<number | null>(null, [Validators.min(1)]),
    equipName: new FormControl(''),
    equipTypeId: new FormControl<number | null>(null),   // было equipType: string — API ждёт equipTypeId
    quantity: new FormControl<number | null>(null, [Validators.min(1)]),
    maxCost: new FormControl<number | null>(null, [Validators.min(0.01)]),
    maxLengthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWidthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxHeightMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWeightKg: new FormControl<number | null>(null, [Validators.min(0.01)]),
    requiredSpec: new FormControl('')
  });

  onAddLot() { this.editingLotId = null; this.lotForm.reset(); this.validationErrors = {}; this.showLotForm = true; this.cdr.detectChanges(); }

  onEditLot(l: any) {
    this.editingLotId = l.id;
    this.lotForm.reset();
    // patchValue не проставит equipTypeId: в ответе лота тип лежит объектом equipmentType
    this.lotForm.patchValue({ ...l, equipTypeId: l.equipmentType?.id ?? null });
    this.validationErrors = {};
    this.showLotForm = true;
    this.openMenuLotId = null;
    this.cdr.detectChanges();
  }

  onSaveLot() {
    this.validationErrors = {};
    if (!this.tender?.id) {
      this.validationErrors = { _general: 'Ошибка: не выбран тендер. Перезагрузите страницу.' };
      return;
    }
    const v: any = this.lotForm.value;
    const body: any = { ...v, equipTypeId: v.equipTypeId === '' || v.equipTypeId == null ? null : Number(v.equipTypeId), tenderId: this.tender.id };
    const wasEditing = this.editingLotId !== null;
    const req = this.editingLotId ? this.api.update('lots', this.editingLotId, body) : this.api.create('lots', body);
    req.subscribe({
      next: () => {
        this.showLotForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Лот обновлён' : 'Лот добавлен');
        this.lotsChanged.emit();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDeleteLot(id: number) {
    this.openMenuLotId = null;
    const usedCount = (this.applyItems || []).filter((it: any) => it.tenderLot?.id === id).length;
    if (usedCount > 0) {
      this.notify.error(`Невозможно удалить: лот используется в ${usedCount} позици${usedCount === 1 ? 'и' : 'ях'} заявок`);
      return;
    }
    this.confirm.ask('Удалить лот?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('lots', id).subscribe({
          next: () => { this.notify.success('Лот удалён'); this.lotsChanged.emit(); },
          error: (err: any) => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
```

Добавить `private confirm: ConfirmService` в конструктор.

- [ ] **Step 2: Добавить форму в шаблон `tender-lots.component.ts`**

Вставить сразу после блока `.lots-toolbar`:

```html
<form *ngIf="showLotForm" [formGroup]="lotForm" (ngSubmit)="onSaveLot()" class="lot-form">
  <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
  <div class="form-row">
    <label>&#8470; лота<input type="number" min="1" formControlName="lotNumber" [class.input-error]="validationErrors.lotNumber" /><span class="field-error" *ngIf="validationErrors.lotNumber">{{ validationErrors.lotNumber }}</span></label>
    <label>Кол-во *<input type="number" min="1" formControlName="quantity" [class.input-error]="validationErrors.quantity" /><span class="field-error" *ngIf="validationErrors.quantity">{{ validationErrors.quantity }}</span></label>
  </div>
  <label>Название оборудования *<input formControlName="equipName" [class.input-error]="validationErrors.equipName" /><span class="field-error" *ngIf="validationErrors.equipName">{{ validationErrors.equipName }}</span></label>
  <label>Тип оборудования
    <select formControlName="equipTypeId">
      <option [ngValue]="null">— не выбран —</option>
      <option *ngFor="let t of equipmentTypes" [ngValue]="t.id">{{ t.name }}</option>
    </select>
  </label>
  <label>Макс. цена<input type="number" min="0.01" step="0.01" formControlName="maxCost" [class.input-error]="validationErrors.maxCost" /><span class="field-error" *ngIf="validationErrors.maxCost">{{ validationErrors.maxCost }}</span></label>
  <div class="form-row">
    <label>Макс. длина<input type="number" min="1" formControlName="maxLengthMm" [class.input-error]="validationErrors.maxLengthMm" /><span class="field-error" *ngIf="validationErrors.maxLengthMm">{{ validationErrors.maxLengthMm }}</span></label>
    <label>Макс. ширина<input type="number" min="1" formControlName="maxWidthMm" [class.input-error]="validationErrors.maxWidthMm" /><span class="field-error" *ngIf="validationErrors.maxWidthMm">{{ validationErrors.maxWidthMm }}</span></label>
    <label>Макс. высота<input type="number" min="1" formControlName="maxHeightMm" [class.input-error]="validationErrors.maxHeightMm" /><span class="field-error" *ngIf="validationErrors.maxHeightMm">{{ validationErrors.maxHeightMm }}</span></label>
  </div>
  <label>Макс. вес (кг)<input type="number" min="0.01" step="0.01" formControlName="maxWeightKg" [class.input-error]="validationErrors.maxWeightKg" /><span class="field-error" *ngIf="validationErrors.maxWeightKg">{{ validationErrors.maxWeightKg }}</span></label>
  <label>Требования к спецификации<textarea formControlName="requiredSpec" rows="2"></textarea></label>
  <div class="form-actions">
    <button class="btn btn-save" type="submit">Сохранить</button>
    <button class="btn btn-cancel" type="button" (click)="showLotForm = false">Отмена</button>
  </div>
</form>
```

Заменить в тулбаре `(click)="addLotRequested.emit()"` на `(click)="onAddLot()"`, а в меню лота — `(click)="editLotRequested.emit(l); openMenuLotId = null"` на `(click)="onEditLot(l)"` и `(click)="deleteLotRequested.emit(l.id); openMenuLotId = null"` на `(click)="onDeleteLot(l.id)"`. Удалить из класса выходы `addLotRequested`, `editLotRequested`, `deleteLotRequested`.

Добавить стили формы (в начало блока `styles`, до `@media`):

```css
.lot-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 14px; max-width: 700px; }
.lot-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
.lot-form input, .lot-form select, .lot-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px;
  border: 1px solid var(--border); border-radius: 4px; font-size: 14px; font-family: inherit;
  background: var(--surface); color: var(--text); }
.form-row { display: flex; gap: 12px; }
.form-row label { flex: 1; }
.form-actions { margin-top: 16px; display: flex; gap: 8px; }
.field-error { display: block; color: var(--danger); font-size: 12px; margin-top: 2px; }
.input-error { border-color: var(--danger) !important; }
.error-banner { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger);
                padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
.btn-save { background: var(--accent); color: var(--accent-contrast); }
.btn-cancel { background: var(--surface-2); color: var(--text); }
```

И в `@media (max-width: 900px)` добавить: `.form-row { flex-direction: column; gap: 0; }`

- [ ] **Step 3: Убрать форму лота из `tenders.component.ts`**

1. Удалить из шаблона блок `<form *ngIf="showLotForm" [formGroup]="lotForm" …>` (`tenders.component.ts:218-246`) и `<div *ngIf="lots.length === 0 && !showLotForm" …>` если ещё остался.
2. Удалить из класса: `showLotForm`, `editingLotId`, `lotForm`, `onAddLot`, `onEditLot`, `onSaveLot`, `onDeleteLot`. `validationErrors` **оставить** — им пользуется форма тендера.
3. В `onBack()` убрать `this.showLotForm = false;`.
4. В теге `<app-tender-lots>` убрать выходы `(addLotRequested)`, `(editLotRequested)`, `(deleteLotRequested)` и добавить вход `[applyItems]="allApplyItems"`.

- [ ] **Step 4: Собрать фронт**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка успешна.

- [ ] **Step 5: Живая проверка формы лота и сохранения типа**

Playwright:
1. «Добавить лот» → форма; в селекте «Тип оборудования» — **весь справочник** (18 типов из `equipment_type`), а не 4 зашитые строки.
2. Заполнить название/кол-во, выбрать тип, сохранить → тост «Лот добавлен».
3. Развернуть новый лот → в деталях **`Тип: <выбранный>`**. Перезагрузить страницу (F5, снова открыть тендер) → тип на месте (это и есть починка дефекта).
4. «⋯ → Редактировать» существующий лот → в селекте предвыбран текущий тип; сменить и сохранить → изменение видно.
5. «⋯ → Удалить» на лоте, используемом в заявке → красный тост «Невозможно удалить…»; на неиспользуемом → диалог подтверждения и удаление.
6. Проверить форму на 390px — поля в одну колонку.

- [ ] **Step 6: Коммит**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add frontend/src/app/pages/tenders/tender-lots.component.ts frontend/src/app/pages/tenders/tenders.component.ts
git commit -m "$(cat <<'EOF'
fix(ui): форма лота — справочник типов вместо хардкода, тип наконец сохраняется

Селект «Тип оборудования» содержал 4 зашитые строки и слал поле equipType,
которого нет в TenderLotRequest — выбор молча терялся. Теперь селект
наполняется из справочника equipment_type и шлёт equipTypeId.
Форма переехала в app-tender-lots вместе с добавлением/удалением лота.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: Сквозная живая проверка и документация

**Files:**
- Modify: `CLAUDE.md` (§12 «Frontend», §16 «Roadmap»)
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Прогнать полный сценарий §10 спеки**

Playwright, **четыре комбинации**: 1280px и 390px × светлая и тёмная тема. По каждой пройти сценарии 1–9 из §10 спеки (`docs/superpowers/specs/2026-07-25-tender-card-lots-rework-design.md`). Особое внимание:
- ни одного горизонтального скролла в зоне лотов ни в одной комбинации;
- чипы стадий читаемы в тёмной теме;
- меню «⋯» не обрезается краем карточки;
- панели внутри лота не ломают ширину на 390px.

Скриншоты сохранять в `/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/*/scratchpad/`, не в корень репозитория.

- [ ] **Step 2: Прогнать проверку инвариантов §6 спеки**

По списку §6: «ТЗ» только на импортных, «Подбор» только KZ, «Подобрать» только РФ+критерии, «КП по всему тендеру» только на ручных, блокировка «КП по выбранным» при пустом выборе, сохранение вида МИ с пересборкой подбора, серверный токен `[КП-id]`, `adoptBusy` на обеих кнопках «Взять в работу», снятие предложенной модели. Каждый пункт — отметить проверенным или завести дефект и починить.

- [ ] **Step 3: Убедиться, что бэкенд не тронут**

Run: `cd /Users/vlad/IdeaProjects/AIS && git diff --stat main...HEAD -- src/`
Expected: пустой вывод (ни одного изменённого файла бэкенда).

- [ ] **Step 4: Финальная сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: зелёная сборка, ни одного предупреждения бюджета `anyComponentStyle`.

- [ ] **Step 5: Обновить `CLAUDE.md`**

В §12 «Frontend»: заменить абзац про карточку тендера (`tenders.component.ts` — большой, 5 инлайн-панелей, «➡️ Карточка тендера — следующая на переработку») описанием новой структуры — три компонента (`tender-lots`, `lot-registry-panel`, `lot-kp-panel`), лоты-аккордеоны с чипом стадии, панели внутри лота, `.table-scroll` в зоне лотов не осталось. Описать правило вычисления стадии (приоритет цены → отказы → КП → модель → ТЗ → нужно ТЗ, считается на клиенте).
В §16: снять пункт «➡️ СЛЕДУЮЩИЙ ШАГ: переработка карточки тендера» и пункт про хардкод-селект типов в форме лота (оба закрыты), пометив их сделанными.

- [ ] **Step 6: Обновить `docs/PROGRESS.md`**

Добавить запись сессии 2026-07-25 (что сделано, какие компоненты появились, что проверено вживую), обновить «Последнее обновление» и строку рассинхрона main ↔ прод. В «Что дальше» снять пункт про переработку карточки тендера.

- [ ] **Step 7: Коммит документации**

```bash
cd /Users/vlad/IdeaProjects/AIS
git add CLAUDE.md docs/PROGRESS.md
git commit -m "$(cat <<'EOF'
docs: карточка тендера — лоты-аккордеоны, три компонента, чип стадии лота

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>
EOF
)"
```

---

## Замечания для исполнителя

- **Не «улучшать» переносимую логику.** Подбор, скоринг, тексты тостов, порядок вызовов API — переносятся дословно. Единственное намеренное изменение поведения во всём плане — селект типа оборудования в Task 4.
- **Сцепка, которую легко потерять:** после «Взять в работу» (реестр или комплектность) старый код закрывал панель реестра, перезагружал лоты и **сразу открывал панель КП** по этому лоту. Это преднамеренно (предотметка поставщиков по бренду производителя) — сохранить.
- **Токен `[КП-id]`** приклеивает сервер. Метод `subjectHuman` снимает любой `[КП-…]` из отредактированной оператором темы — без него правка темы в превью сломает round-trip приёма ответов по токену.
- **Смысл «пусто — не рисуем»** усиливается: было «колонка скрыта, если пуста у всех лотов», стало «поле скрыто, если пусто у этого лота».
- Если субагент вернул 0 tool_uses (транзиентный срыв) — переотправить задачу либо доделать инлайн.
