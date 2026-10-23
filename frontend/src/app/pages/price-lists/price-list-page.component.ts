import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { numText, parseNum, saveBlob } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { curShort, rateText } from '../../shared/rates';
import { hintMoney } from '../../shared/price-hint';
import { dmy, listState } from '../../shared/price-list';
import type { PriceLinkCandidates, PriceListField, PriceListRow, PriceListView, PriceRowField } from '../../shared/price-list';

/**
 * Экран проверки прайса (спека 2026-10-23 §5.3): шапка, строки с закупкой, тенге, РУ, связью и «было …»; правка — по полю,
 * «Сохранить прайс» — в подсказки. Оператор видит экран без правки.
 */
@Component({
  selector: 'app-price-list-page',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="pp-page" *ngIf="v as p; else loadingTpl">
      <a class="xref" routerLink="/price-lists">← Прайсы поставщиков</a>
      <div class="pp-head">
        <h2>{{ p.distributorName }} <span class="pp-chip" [attr.data-tone]="state(p).tone">{{ state(p).text }}</span></h2>
        <span class="pp-save">{{ saveText }}</span>
      </div>
      <div class="pp-actions">
        <button type="button" class="btn btn-line" (click)="download()">Скачать файл</button>
        <ng-container *ngIf="admin">
          <button type="button" class="btn btn-primary" *ngIf="p.status === 'DRAFT'" [disabled]="busy" (click)="activate()">Сохранить прайс</button>
          <button type="button" class="btn btn-line danger" [disabled]="busy" (click)="remove()">Удалить прайс</button>
        </ng-container>
      </div>
      <p class="pp-note warn" *ngIf="p.parseNote">{{ p.parseNote }}</p>

      <div class="pp-card pp-fields">
        <label>Поставщик
          <select [ngModel]="p.distributorId" (ngModelChange)="patch('DISTRIBUTOR', $event)" [disabled]="!admin">
            <option *ngFor="let d of suppliers" [ngValue]="d.id">{{ d.name }}</option>
          </select></label>
        <label>Дата прайса <input type="date" [value]="p.listDate || ''" (change)="patch('LIST_DATE', val($event))" [disabled]="!admin" /></label>
        <label>Действует до <input type="date" [value]="p.validTo || ''" (change)="patch('VALID_TO', val($event))" [disabled]="!admin" />
          <span class="pp-hint" *ngIf="!p.validTo">срок не указан</span></label>
        <label>Валюта
          <select [ngModel]="p.currency" (ngModelChange)="patch('CURRENCY', $event)" [disabled]="!admin">
            <option *ngIf="p.currency === 'MIXED'" [ngValue]="'MIXED'">не ясна — выберите</option>
            <option *ngFor="let c of currencies" [ngValue]="c">{{ c }}</option>
          </select></label>
        <label>Наша закупка — колонка
          <select [ngModel]="p.ourColumn" (ngModelChange)="patch('OUR_COLUMN', $event == null ? null : String($event))" [disabled]="!admin || !p.columns.length">
            <option *ngFor="let c of p.columns" [ngValue]="c.index">{{ c.header }}</option>
          </select></label>
        <label>Наша скидка, %
          <input inputmode="decimal" [value]="numText(p.discountPct)" (change)="patch('DISCOUNT_PCT', val($event))" [disabled]="!admin" />
          <span class="pp-hint warn" *ngIf="p.retail && !p.discountPct">цены розничные — укажите нашу скидку</span></label>
        <label class="wide">Условия
          <textarea rows="2" maxlength="1000" [value]="p.terms || ''" (change)="patch('TERMS', val($event))" [disabled]="!admin"></textarea></label>
        <p class="pp-hint wide" *ngIf="p.currency && p.currency !== p.marketCurrency && p.currency !== 'MIXED'">
          <ng-container *ngIf="p.ourRate != null; else noRate">Наш курс: 1 {{ short(p.currency) }} = {{ rate(p.ourRate) }} {{ short(p.marketCurrency) }}</ng-container>
          <ng-template #noRate>Нашего курса {{ short(p.currency) }} нет — впишите его в «Система → Курсы валют»</ng-template>
          <ng-container *ngIf="p.todayRate != null"> · сегодня {{ rate(p.todayRate) }} ({{ dmy(p.todayDate) }})</ng-container></p>
      </div>

      <div class="pp-rows">
        <ng-container *ngFor="let r of p.rows; let i = index">
          <div class="pp-section" *ngIf="r.section && r.section !== p.rows[i - 1]?.section">{{ r.section }}</div>
          <div class="pp-row" [class.pp-noprice]="r.ourPrice == null">
            <div class="pp-name">
              <input class="pp-in" [value]="r.name" (change)="patchRow(r, 'NAME', val($event))" [disabled]="!admin" aria-label="Наименование" />
              <span class="pp-model">{{ r.model || '' }}</span>
            </div>
            <div class="pp-prices">
              <label *ngFor="let c of p.columns" [class.pp-our]="c.index === p.ourColumn">
                <span class="pp-cap">{{ c.header }}</span>
                <input inputmode="decimal" [value]="numText(r.prices[c.index])" (change)="patchRow(r, priceField(c.index), val($event))"
                       [disabled]="!admin" [attr.aria-label]="c.header" />
              </label>
            </div>
            <div class="pp-buy">
              <span class="pp-cap">закупка</span>
              <b>{{ money(r.ourPrice, p.currency) || '—' }}</b>
              <span *ngIf="r.converted != null"> → {{ money(r.converted, p.marketCurrency) }}</span>
              <span class="pp-today" *ngIf="r.today != null">сегодня {{ money(r.today, p.marketCurrency) }}</span>
              <span class="pp-prev" *ngIf="r.prevPrice != null">было {{ money(r.prevPrice, p.currency) }} в прайсе от {{ dmy(r.prevDate) }}
                <ng-container *ngIf="r.prevPct != null"> — {{ r.prevPct > 0 ? '+' : '' }}{{ pct(r.prevPct) }} %</ng-container></span>
            </div>
            <div class="pp-reg">
              <span class="pp-ru" [attr.data-st]="r.regStatus">{{ ruText(r) }}</span>
              <input class="pp-in small" [value]="r.regText || ''" (change)="patchRow(r, 'REG_TEXT', val($event))" [disabled]="!admin"
                     placeholder="РУ из прайса" aria-label="РУ из прайса" />
            </div>
            <div class="pp-link">
              <ng-container *ngIf="r.siteProductId; else eqTpl">наш товар: <a [href]="r.siteProductUrl" target="_blank" rel="noopener" *ngIf="r.siteProductUrl; else spName">{{ r.siteProductName }} ↗</a>
                <ng-template #spName>{{ r.siteProductName }}</ng-template></ng-container>
              <ng-template #eqTpl><ng-container *ngIf="r.medEquipmentId; else noneTpl">каталог: {{ r.medEquipmentName }}</ng-container></ng-template>
              <ng-template #noneTpl><span class="pp-muted">без связи</span></ng-template>
              <ng-container *ngIf="admin">
                <button type="button" class="link" (click)="openLink(r)">{{ r.siteProductId || r.medEquipmentId ? 'сменить' : 'связать' }}</button>
                <button type="button" class="link" *ngIf="r.siteProductId || r.medEquipmentId" (click)="unlink(r)">снять</button>
                <button type="button" class="link danger" (click)="deleteRow(r)">удалить строку</button>
              </ng-container>
              <div class="pp-cands" *ngIf="linkFor === r.id">
                <input type="search" [(ngModel)]="linkQuery" (input)="onLinkQuery()" placeholder="Наш товар или позиция каталога" aria-label="Поиск товара" />
                <button type="button" class="pp-cand" *ngFor="let c of cands?.products ?? []" (click)="link(r, 'SITE_PRODUCT', c.productId)">🛒 {{ c.name }}</button>
                <button type="button" class="pp-cand" *ngFor="let c of cands?.equipment ?? []" (click)="link(r, 'EQUIPMENT', c.id)">📦 {{ c.name }}</button>
              </div>
            </div>
          </div>
        </ng-container>
        <button type="button" class="btn btn-line" *ngIf="admin" [disabled]="busy" (click)="addRow()">+ строка</button>
      </div>
    </div>
    <ng-template #loadingTpl><p class="pp-hint">Загружаю…</p></ng-template>
  `,
  styles: [`
    .pp-page { max-width: 1100px; display: flex; flex-direction: column; gap: 10px; }
    .pp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .pp-head h2 { margin: 0; }
    .pp-save, .pp-hint, .pp-muted, .pp-model, .pp-cap, .pp-today, .pp-prev { font-size: 12px; color: var(--text-muted); }
    .pp-hint.warn, .pp-note.warn { color: var(--warn-text); }
    .pp-note { margin: 0; font-size: 13px; }
    .pp-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .pp-chip { font-size: 12px; padding: 2px 9px; border-radius: 999px; vertical-align: middle; }
    .pp-chip[data-tone="ok"] { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pp-chip[data-tone="warn"] { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pp-chip[data-tone="muted"] { color: var(--text-muted); border: 1px solid var(--border); }
    .pp-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface); }
    .pp-fields { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 12px; }
    .pp-fields label { display: flex; flex-direction: column; gap: 3px; font-size: 12px; color: var(--text-muted); }
    .pp-fields .wide { grid-column: 1 / -1; }
    .pp-fields input, .pp-fields select, .pp-fields textarea { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px;
              background: var(--surface); color: var(--text); font: inherit; font-size: 14px; min-width: 0; width: 100%; box-sizing: border-box; }
    .pp-fields textarea { resize: vertical; }
    .pp-rows { display: flex; flex-direction: column; gap: 6px; }
    .pp-section { margin-top: 8px; font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .pp-row { display: grid; grid-template-columns: minmax(0, 2fr) minmax(0, 1.4fr) minmax(0, 1fr); gap: 6px 12px; padding: 8px 12px;
              border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .pp-row.pp-noprice { border-color: color-mix(in srgb, var(--warn) 60%, var(--border)); }
    .pp-name { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .pp-in { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .pp-in.small { font-size: 12px; }
    .pp-prices { display: flex; gap: 6px; flex-wrap: wrap; }
    .pp-prices label { display: flex; flex-direction: column; gap: 2px; min-width: 110px; flex: 1; }
    .pp-prices label.pp-our input { border-color: var(--accent); }
    .pp-prices input { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .pp-buy { display: flex; flex-direction: column; gap: 2px; }
    .pp-reg, .pp-link { grid-column: 1 / -1; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .pp-ru { font-size: 12px; padding: 2px 9px; border-radius: 999px; border: 1px solid var(--border); color: var(--text-muted); white-space: nowrap; }
    .pp-ru[data-st="ACTIVE"] { border: 0; color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pp-ru[data-st="EXPIRED"], .pp-ru[data-st="NOT_FOUND"], .pp-ru[data-st="NONE"] { border: 0; color: var(--warn-text);
              background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pp-cands { flex-basis: 100%; display: flex; flex-direction: column; gap: 4px; }
    .pp-cand { text-align: left; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
               color: var(--text); cursor: pointer; min-height: 36px; }
    .link { background: none; border: 0; padding: 0; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px; }
    .link:hover { text-decoration: underline; }
    .link.danger, .btn.danger { color: var(--danger-text); }
    @media (max-width: 900px) {
      /* minmax(0, …): длинный заголовок колонки цены в выборе иначе распирал колонку (живая проверка 390 px) */
      .pp-fields { grid-template-columns: minmax(0, 1fr); }
      .pp-row { grid-template-columns: minmax(0, 1fr); }
      .pp-in, .pp-prices input, .pp-fields input, .pp-fields select, .pp-fields textarea { font-size: 16px; min-height: 40px; }
      .link { min-height: 40px; }
    }
  `],
})
export class PriceListPageComponent implements OnInit {
  v: PriceListView | null = null;
  suppliers: { id: number; name: string }[] = [];
  busy = false;
  saveText = '';
  linkFor: number | null = null;
  linkQuery = '';
  cands: PriceLinkCandidates | null = null;
  readonly currencies = ['KZT', 'RUB', 'USD', 'EUR', 'CNY'];
  readonly dmy = dmy;
  readonly state = listState;
  readonly numText = numText;
  readonly short = curShort;
  readonly rate = rateText;
  readonly String = String;
  private id = 0;
  private timer: any = null;

  constructor(private api: ApiService, private auth: AuthService, private confirm: ConfirmService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.api.getDistributors().subscribe({ next: d => { this.suppliers = d.map(x => ({ id: x.id, name: x.name })); this.cdr.markForCheck(); } });
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(pm => {
      this.id = Number(pm.get('id'));
      this.v = null;
      this.api.getPriceList(this.id).subscribe({
        next: v => { this.v = v; this.cdr.markForCheck(); },
        error: e => { this.notify.error(errorText(e)); this.router.navigate(['/price-lists']); },
      });
    });
  }

  val(ev: Event): string | null {
    const t = (ev.target as HTMLInputElement).value.trim();
    return t === '' ? null : t;
  }

  priceField(i: number): PriceRowField { return `PRICE:${i}`; }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }
  pct(v: number): string { return String(v).replace('.', ','); }

  ruText(r: PriceListRow): string {
    switch (r.regStatus) {
      case 'ACTIVE': return 'РУ ✓' + (r.regExpires ? ' до ' + dmy(r.regExpires) : '');
      case 'EXPIRED': return 'РУ истекло' + (r.regExpires ? ' ' + dmy(r.regExpires) : '');
      case 'NONE': return 'нет РУ';
      case 'NOT_FOUND': return 'РУ не найдено';
      default: return 'не проверено';
    }
  }

  patch(field: PriceListField, value: string | null) {
    this.run(this.api.patchPriceList(this.id, field, value));
  }

  patchRow(r: PriceListRow, field: PriceRowField, value: string | null) {
    if (field.startsWith('PRICE:') && value != null && parseNum(value) == null) { this.notify.error('Цена — числом'); return; }
    this.run(this.api.patchPriceListRow(this.id, r.id, field, field.startsWith('PRICE:') && value != null ? String(parseNum(value)) : value));
  }

  addRow() { this.run(this.api.addPriceListRow(this.id)); }

  deleteRow(r: PriceListRow) {
    this.confirm.ask('Удалить строку прайса?', r.name, { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => {
      if (ok) this.run(this.api.deletePriceListRow(this.id, r.id));
    });
  }

  activate() {
    this.run(this.api.activatePriceList(this.id), () => this.notify.success('Прайс сохранён — его цены в подсказках'));
  }

  remove() {
    this.confirm.ask('Удалить прайс?', 'Строки КП, взятые из него, останутся со своей закупкой.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePriceList(this.id).subscribe({
          next: () => { this.notify.success('Прайс удалён'); this.router.navigate(['/price-lists']); },
          error: e => this.notify.error(errorText(e)),
        });
      });
  }

  download() {
    this.api.downloadPriceListFile(this.id).subscribe({
      next: r => { const f = responseFile(r, this.v?.fileName || 'прайс'); if (f) saveBlob(f, f.name); },
      error: () => this.notify.error('Файл не скачался — попробуйте ещё раз'),
    });
  }

  openLink(r: PriceListRow) {
    this.linkFor = this.linkFor === r.id ? null : r.id;
    this.linkQuery = r.model || r.name;
    this.cands = null;
    if (this.linkFor) this.searchLink();
  }

  onLinkQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.searchLink(), 300);
  }

  private searchLink() {
    const q = this.linkQuery.trim();
    if (q.length < 2) { this.cands = null; this.cdr.markForCheck(); return; }
    this.api.getPriceLinkCandidates(q).subscribe({ next: c => { this.cands = c; this.cdr.markForCheck(); } });
  }

  link(r: PriceListRow, field: 'SITE_PRODUCT' | 'EQUIPMENT', id: number) {
    this.linkFor = null;
    this.run(this.api.patchPriceListRow(this.id, r.id, field, String(id)));
  }

  unlink(r: PriceListRow) {
    this.run(this.api.patchPriceListRow(this.id, r.id, r.siteProductId ? 'SITE_PRODUCT' : 'EQUIPMENT', null));
  }

  private run(obs: Observable<PriceListView>, done?: () => void) {
    this.busy = true;
    this.saveText = 'Сохраняю…';
    obs.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        this.v = v;
        this.busy = false;
        const d = new Date();
        this.saveText = `Сохранено · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
        done?.();
        this.cdr.markForCheck();
      },
      error: e => { this.busy = false; this.saveText = 'Не сохранено: ' + errorText(e); this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
