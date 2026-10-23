import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { RegistryChipComponent } from '../../shared/registry-chip.component';
import { RegistryChipActions } from '../../shared/registry';
import { SiteProductSearchComponent } from '../../shared/site-product-search.component';
import { ImportGridComponent } from '../../shared/import-grid.component';
import { buildImportLines } from '../../shared/import-lines';
import { MarketPricePanelComponent } from '../../shared/market-price-panel.component';
import { PriceHintComponent } from '../../shared/price-hint.component';
import {
  LineInput, LinesMode, ParsedLine, PrLine, PrLineField, PrView, SaveState, SiteProduct, UNIT_SUGGESTIONS, nextSaveSeq,
} from '../../shared/private-request';
import { errorText, retriable } from '../../shared/error-text';

interface ParsedRow extends ParsedLine { on: boolean; }

/**
 * Позиции заявки (спека 2026-10-12 §5.3): строка — наименование, бренд, количество, метка РУ, «⋯»; клик «⋯» раскрывает
 * характеристики, ед. изм., ссылку и «Удалить». Поля сохраняются по выходу из поля. Добавление — поиск по сайту / свой
 * текст, «Вставить списком», «Из Excel».
 */
@Component({
  selector: 'app-pr-lines',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RegistryChipComponent, SiteProductSearchComponent, ImportGridComponent, MarketPricePanelComponent,
            PriceHintComponent],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Позиции <span class="count">({{ view.lines.length }})</span></h3>
        <div class="sec-actions" *ngIf="!readonly">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="openPaste()">Вставить списком</button>
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="openExcel()">Из Excel</button>
        </div>
      </div>

      <p class="empty-lines" *ngIf="!view.lines.length">
        Позиций пока нет{{ readonly ? '' : ' — найдите товар на сайте или впишите своё ниже' }}.
      </p>

      <ol class="lines" *ngIf="view.lines.length">
        <li class="line" *ngFor="let l of view.lines; trackBy: trackLine" [class.open]="openId === l.lotId">
          <div class="row">
            <span class="no">{{ l.lineNo }}</span>
            <ng-container *ngIf="!readonly; else roTpl">
              <input class="f-name" [value]="l.name" maxlength="255" aria-label="Наименование"
                     (blur)="commit(l, 'NAME', $event)" (keydown.enter)="blurTarget($event)" />
              <input class="f-brand" [value]="l.brand || ''" maxlength="255" placeholder="Бренд" aria-label="Бренд"
                     (blur)="commit(l, 'BRAND', $event)" (keydown.enter)="blurTarget($event)" />
              <span class="f-qty">
                <input type="number" min="1" step="1" inputmode="numeric" [value]="l.quantity ?? 1" aria-label="Количество"
                       (blur)="commit(l, 'QUANTITY', $event)" (keydown.enter)="blurTarget($event)" />
                <span class="unit">{{ l.unit || 'шт' }}</span>
              </span>
            </ng-container>
            <ng-template #roTpl>
              <span class="f-name ro">{{ l.name }}</span>
              <span class="f-brand ro">{{ l.brand || '—' }}</span>
              <span class="f-qty ro">{{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span>
            </ng-template>
            <span class="f-reg">
              <app-registry-chip *ngIf="isKz" [status]="l.regStatus" [record]="l.registry" [seed]="regSeed(l)"
                                 [actions]="regActions(l)" [readonly]="readonly" (changed)="onLine($any($event))"></app-registry-chip>
            </span>
            <button type="button" class="btn btn-more" (click)="toggle(l)" [attr.aria-expanded]="openId === l.lotId"
                    [attr.aria-label]="'Подробнее: ' + l.name">⋯</button>
          </div>
          <app-price-hint *ngIf="l.priceList?.rows?.length" class="pr-price" [hint]="l.priceList" [marketCurrency]="marketCurrency"></app-price-hint>
          <div class="details" *ngIf="openId === l.lotId">
            <label class="spec">Характеристики
              <textarea rows="3" maxlength="10000" [value]="l.spec || ''" [disabled]="readonly"
                        placeholder="Что важно клиенту: размеры, комплектация, класс…" (blur)="commit(l, 'SPEC', $event)"></textarea>
            </label>
            <label class="unit-field">Ед. изм.
              <input [value]="l.unit || ''" list="pr-units" maxlength="30" placeholder="шт" [disabled]="readonly"
                     (blur)="commit(l, 'UNIT', $event)" (keydown.enter)="blurTarget($event)" />
            </label>
            <div class="detail-actions">
              <a *ngIf="l.productUrl" [href]="l.productUrl" target="_blank" rel="noopener">на сайте ↗</a>
              <button type="button" class="btn btn-line" *ngIf="isKz" (click)="marketFor = marketFor === l.lotId ? null : l.lotId">Цены рынка</button>
              <ng-container *ngIf="!readonly">
                <button type="button" class="btn btn-line del" [disabled]="l.requested || busy" (click)="remove(l)">Удалить</button>
                <span class="hint" *ngIf="l.requested">Удалить нельзя: по позиции есть запросы цен</span>
              </ng-container>
            </div>
            @defer (when marketFor === l.lotId) {
              <app-market-price-panel class="mkt-panel" *ngIf="marketFor === l.lotId" [lotId]="l.lotId" (close)="marketFor = null"></app-market-price-panel>
            }
          </div>
        </li>
      </ol>
      <datalist id="pr-units"><option *ngFor="let u of units" [value]="u"></option></datalist>

      <app-site-product-search *ngIf="!readonly" [siteEnabled]="isKz" (pickProduct)="addFromSite($event)"
                               (addText)="addText($event)"></app-site-product-search>
    </section>

    <div class="overlay" *ngIf="pasteOpen" (click)="pasteOpen = false">
      <div class="modal" role="dialog" aria-label="Вставить списком" (click)="$event.stopPropagation()">
        <h3>Вставить списком</h3>
        <p class="hint">Каждая строка — позиция. Количество в конце строки: «— 2 шт», «x3», «(2)».</p>
        <textarea rows="8" [(ngModel)]="pasteText" aria-label="Текст списка"
                  placeholder="Тонометр OMRON M2 — 5 шт&#10;Кушетка медицинская x2"></textarea>
        <div class="form-actions">
          <button type="button" class="btn btn-line" [disabled]="busy || !pasteText.trim()" (click)="parsePaste()">Разобрать</button>
        </div>
        <table class="parsed" *ngIf="parsed.length">
          <thead><tr><th></th><th>Наименование</th><th>Кол-во</th><th>Ед.</th></tr></thead>
          <tbody>
            <tr *ngFor="let p of parsed">
              <td><input type="checkbox" [(ngModel)]="p.on" aria-label="Добавить строку" /></td>
              <td><input [(ngModel)]="p.name" maxlength="255" aria-label="Наименование" /></td>
              <td><input type="number" min="1" [(ngModel)]="p.quantity" aria-label="Количество" /></td>
              <td><input [(ngModel)]="p.unit" maxlength="30" list="pr-units" aria-label="Ед. изм." /></td>
            </tr>
          </tbody>
        </table>
        <div class="error-banner" *ngIf="dialogError">{{ dialogError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="busy || !checkedParsed()" (click)="addParsed()">
            Добавить {{ checkedParsed() }} поз.</button>
          <button type="button" class="btn btn-cancel" (click)="pasteOpen = false">Отмена</button>
        </div>
      </div>
    </div>

    <div class="overlay" *ngIf="excelOpen" (click)="excelOpen = false">
      <div class="modal wide" role="dialog" aria-label="Позиции из Excel" (click)="$event.stopPropagation()">
        <h3>Позиции из Excel</h3>
        <input type="file" accept=".xlsx,.xls" (change)="onExcelFile($event)" aria-label="Файл Excel" />
        <p class="hint">Система разметит колонки сама — поправьте, если ошиблась.</p>
        <app-import-grid *ngIf="excelPreview" [preview]="excelPreview"></app-import-grid>
        <div class="mode" *ngIf="excelPreview && view.lines.length">
          <label class="check"><input type="radio" name="xmode" [(ngModel)]="excelMode" value="APPEND" /> Добавить к позициям</label>
          <label class="check"><input type="radio" name="xmode" [(ngModel)]="excelMode" value="REPLACE" />
            Заменить позиции (запрошенные у поставщиков останутся)</label>
        </div>
        <div class="error-banner" *ngIf="dialogError">{{ dialogError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="busy || !excelPreview" (click)="importExcel()">Загрузить позиции</button>
          <button type="button" class="btn btn-cancel" (click)="excelOpen = false">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .mkt-panel { grid-column: 1 / -1; display: block; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; margin-bottom: 8px; }
    h3 { margin: 0; font-size: 15px; }
    .count { color: var(--text-muted); font-weight: 400; }
    .sec-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .empty-lines { margin: 4px 0; font-size: 13px; color: var(--text-muted); }
    .lines { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; }
    .line { border-bottom: 1px solid var(--border); padding: 6px 0; }
    .line.open { background: color-mix(in srgb, var(--accent) 4%, var(--surface)); }
    .row { display: grid; grid-template-columns: 28px minmax(0, 1fr) 160px 110px auto 40px; gap: 8px; align-items: center; }
    .no { color: var(--text-muted); font-size: 13px; text-align: right; }
    .row input { width: 100%; box-sizing: border-box; padding: 6px 8px; border: 1px solid transparent; border-radius: 6px;
                 background: transparent; color: var(--text); font: inherit; font-size: 14px; }
    .row input:hover { border-color: var(--border); }
    .row input:focus { border-color: var(--accent); background: var(--surface); outline: none; }
    .f-qty { display: flex; align-items: center; gap: 4px; }
    .f-qty input { width: 64px; }
    .unit { font-size: 12px; color: var(--text-muted); }
    .ro { font-size: 14px; color: var(--text); overflow-wrap: anywhere; }
    .f-reg { display: flex; justify-content: flex-end; }
    .details { display: grid; grid-template-columns: minmax(0, 1fr) 160px; gap: 10px 12px; padding: 8px 0 6px 36px; }
    .pr-price { padding: 0 8px 6px 36px; }
    .details label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    .details textarea, .details input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px;
                                         background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .details textarea { resize: vertical; }
    .detail-actions { grid-column: 1 / -1; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: 13px; }
    .detail-actions a { color: var(--accent); text-decoration: none; }
    .del { color: var(--danger-text); }
    .hint { font-size: 12px; color: var(--text-muted); }
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    .modal.wide { max-width: 960px; }
    .modal h3 { margin: 0; }
    .modal textarea { padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                      color: var(--text); font: inherit; font-size: 14px; resize: vertical; }
    .parsed { width: 100%; border-collapse: collapse; font-size: 13px; }
    .parsed th { text-align: left; padding: 4px 6px; font-size: 12px; color: var(--text-muted); }
    .parsed td { padding: 3px 6px; }
    .parsed input:not([type="checkbox"]) { width: 100%; box-sizing: border-box; padding: 5px 8px; border: 1px solid var(--border);
                                            border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .parsed td:nth-child(3) { width: 80px; }
    .parsed td:nth-child(4) { width: 90px; }
    .mode { display: flex; flex-direction: column; gap: 6px; }
    .check { display: flex; align-items: center; gap: 8px; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    /* Раскладка строки — по ширине самого списка: на странице сделки с открытой «Перепиской» (1280 px) списку достаётся
       ~550 px, и в одну строку наименованию оставалось ~45 px («Кушеті»). Узко — наименование на всю строку, бренд,
       количество и РУ — второй строкой. Контейнер — только на десктопе: на телефоне своя раскладка (ниже), а окошко метки
       РУ там position: fixed — внутри container-type его не проверяли на iPhone. */
    @media (min-width: 901px) {
      .lines { container: pr-lines / inline-size; }
    }
    @container pr-lines (max-width: 760px) {
      .row { grid-template-columns: 28px minmax(0, 1fr) auto auto 40px; grid-template-areas: "no name name name more" ". brand qty reg reg";
             row-gap: 4px; }
      .no { grid-area: no; }
      .f-name { grid-area: name; }
      .f-brand { grid-area: brand; }
      .f-qty { grid-area: qty; }
      .f-reg { grid-area: reg; }
      .btn-more { grid-area: more; }
    }
    @media (max-width: 900px) {
      .row { grid-template-columns: 22px minmax(0, 1fr) 40px; grid-template-areas: "no name more" ". brand brand" ". qty reg"; row-gap: 4px; }
      .no { grid-area: no; }
      .f-name { grid-area: name; }
      .f-brand { grid-area: brand; }
      .f-qty { grid-area: qty; }
      .f-reg { grid-area: reg; }
      .btn-more { grid-area: more; min-width: 40px; min-height: 40px; }
      .row input { font-size: 16px; min-height: 40px; border-color: var(--border); background: var(--surface); }
      .f-qty input { width: 80px; }
      .details { grid-template-columns: 1fr; padding-left: 0; }
      .pr-price { padding-left: 8px; }
      .details textarea, .details input { font-size: 16px; min-height: 40px; }
      .sec-actions .btn, .detail-actions .btn { min-height: 40px; }
      .overlay { padding: 0; align-items: stretch; }
      .modal, .modal.wide { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .parsed input:not([type="checkbox"]), .modal textarea { font-size: 16px; }
    }
  `],
})
export class PrLinesComponent {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Input() isKz = true;
  /** Валюта рынка — для пересчёта «📒 по прайсу». */
  get marketCurrency(): string { return this.isKz ? 'KZT' : 'RUB'; }
  /** Позиция, у которой открыта панель цен рынка (спека 2026-10-22-tender-results-market-prices §10). */
  marketFor: number | null = null;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();

  readonly units = UNIT_SUGGESTIONS;
  openId: number | null = null;
  busy = false;
  pasteOpen = false;
  pasteText = '';
  parsed: ParsedRow[] = [];
  excelOpen = false;
  excelPreview: any = null;
  excelMode: LinesMode = 'APPEND';
  dialogError = '';

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  trackLine(_: number, l: PrLine) {
    return l.lotId;
  }

  /** Действия метки РУ позиции — запросом к заявке; по позиции один объект (вход метки не меняется на каждой проверке). */
  private regActionsByLot = new Map<number, RegistryChipActions>();

  regActions(l: PrLine): RegistryChipActions {
    let a = this.regActionsByLot.get(l.lotId);
    if (!a) {
      const lotId = l.lotId;
      a = {
        candidates: () => this.api.getPrivateRequestLineCandidates(this.view.id, lotId),
        act: (action, record) => this.api.setPrivateRequestLineRegistry(this.view.id, lotId, action, record?.regNumber),
      };
      this.regActionsByLot.set(lotId, a);
    }
    return a;
  }

  regSeed(l: PrLine): string {
    return [l.name, l.brand].filter(Boolean).join(' ');
  }

  toggle(l: PrLine) {
    this.openId = this.openId === l.lotId ? null : l.lotId;
  }

  blurTarget(e: Event) {
    (e.target as HTMLElement).blur();
  }

  private fieldValue(l: PrLine, field: PrLineField): string {
    switch (field) {
      case 'NAME': return l.name || '';
      case 'BRAND': return l.brand || '';
      case 'QUANTITY': return String(l.quantity ?? 1);
      case 'UNIT': return l.unit || '';
      case 'SPEC': return l.spec || '';
      default: return '';
    }
  }

  commit(l: PrLine, field: PrLineField, e: Event) {
    const el = e.target as HTMLInputElement | HTMLTextAreaElement;
    const raw = el.value.trim();
    if (raw === this.fieldValue(l, field).trim()) return;
    if (field === 'NAME' && !raw) {
      el.value = l.name;
      this.notify.error('Наименование не может быть пустым');
      return;
    }
    if (field === 'QUANTITY') {
      const n = Number(raw);
      if (!Number.isInteger(n) || n < 1 || n > 1_000_000) {
        el.value = String(l.quantity ?? 1);
        this.notify.error('Количество — целое число от 1');
        return;
      }
    }
    this.saveLine(l.lotId, field, raw || null);
  }

  private saveLine(lotId: number, field: PrLineField, value: string | null) {
    const key = `line:${lotId}:${field}`, seq = nextSaveSeq();
    this.saveState.emit({ kind: 'saving', key, seq });
    this.api.updatePrivateRequestLine(this.view.id, lotId, field, value)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: line => { this.saveState.emit({ kind: 'saved', key, seq }); this.onLine(line); },
        error: err => {
          this.saveState.emit({ kind: 'error', key, seq, message: errorText(err),
            retry: retriable(err) ? () => this.saveLine(lotId, field, value) : undefined });
          this.cdr.markForCheck();
        },
      });
  }

  /** Позиция после правки или действия с РУ — заменить в виде заявки и отдать странице. */
  onLine(line: PrLine) {
    this.viewChange.emit({ ...this.view, lines: this.view.lines.map(x => x.lotId === line.lotId ? line : x) });
  }

  addFromSite(p: SiteProduct) {
    this.add([{ name: p.name, brand: p.brand, productUrl: p.url, quantity: 1 }]);
  }

  addText(text: string) {
    this.add([{ name: text, quantity: 1 }]);
  }

  private add(lines: LineInput[], mode: LinesMode = 'APPEND', done?: () => void) {
    this.busy = true;
    const seq = nextSaveSeq();
    this.saveState.emit({ kind: 'saving', seq });
    this.api.addPrivateRequestLines(this.view.id, lines, mode).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.saveState.emit({ kind: 'saved', seq }); this.viewChange.emit(v); done?.(); this.cdr.markForCheck(); },
      error: err => {
        this.busy = false;
        this.saveState.emit({ kind: 'error', seq, message: errorText(err) });
        this.dialogError = errorText(err);
        this.cdr.markForCheck();
      },
    });
  }

  remove(l: PrLine) {
    this.confirm.ask(`Удалить позицию «${l.name}»?`, undefined, { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.busy = true;
        this.api.deletePrivateRequestLine(this.view.id, l.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: v => { this.busy = false; this.openId = null; this.viewChange.emit(v); this.cdr.markForCheck(); },
          error: err => { this.busy = false; this.notify.error(errorText(err)); this.cdr.markForCheck(); },
        });
      });
  }

  openPaste() {
    this.pasteOpen = true;
    this.pasteText = '';
    this.parsed = [];
    this.dialogError = '';
  }

  parsePaste() {
    this.busy = true;
    this.dialogError = '';
    this.api.parsePrivateRequestLines(this.view.id, this.pasteText).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: rows => {
        this.busy = false;
        this.parsed = rows.map(r => ({ ...r, on: true }));
        if (!rows.length) this.dialogError = 'Не нашлось ни одной строки с наименованием';
        this.cdr.markForCheck();
      },
      error: err => { this.busy = false; this.dialogError = errorText(err); this.cdr.markForCheck(); },
    });
  }

  checkedParsed(): number {
    return this.parsed.filter(p => p.on && p.name && p.name.trim()).length;
  }

  addParsed() {
    const lines: LineInput[] = this.parsed
      .filter(p => p.on && p.name && p.name.trim())
      .map(p => ({ name: p.name.trim(), quantity: Math.max(1, Math.floor(Number(p.quantity) || 1)), unit: p.unit || null }));
    this.add(lines, 'APPEND', () => { this.pasteOpen = false; this.notify.success(`Добавлено позиций: ${lines.length}`); });
  }

  openExcel() {
    this.excelOpen = true;
    this.excelPreview = null;
    this.excelMode = 'APPEND';
    this.dialogError = '';
  }

  onExcelFile(e: Event) {
    const file = (e.target as HTMLInputElement).files?.[0];
    if (!file) return;
    this.dialogError = '';
    this.busy = true;
    this.api.previewImport(file).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.busy = false; this.excelPreview = p; this.cdr.markForCheck(); },
      error: err => { this.busy = false; this.dialogError = errorText(err) || 'Не удалось прочитать файл'; this.cdr.markForCheck(); },
    });
  }

  importExcel() {
    const built = buildImportLines(this.excelPreview);
    if (built.error) {
      this.dialogError = built.error;
      return;
    }
    this.busy = true;
    this.api.importPrivateRequestLines(this.view.id, { mappings: built.mappings, lines: built.lines, mode: this.excelMode })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: v => {
          this.busy = false;
          this.excelOpen = false;
          this.viewChange.emit(v);
          this.notify.success(`Позиции из файла: ${built.lines.length}`);
          this.cdr.markForCheck();
        },
        error: err => { this.busy = false; this.dialogError = errorText(err); this.cdr.markForCheck(); },
      });
  }
}
