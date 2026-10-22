import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { MarketMoneyPipe } from '../pipes/market-money.pipe';
import { errorText } from './error-text';
import { MarketPrice } from './market-price';
import { announceUrl, instantText, plainDate } from './tender-results';

/**
 * Цены рынка (спека 2026-10-22-tender-results-market-prices §10): медиана, середина рынка, минимум–максимум, цены участников,
 * фильтр по слову, единица, другая категория, строки договоров. Лот — по коду категории, текст — по ключевому слову. Ленивый чанк.
 */
@Component({
  selector: 'app-market-price-panel',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],
  template: `
    <div class="mp">
      <div class="mp-head">
        <b>Цены рынка — договоры goszakup за 2 года</b>
        <button type="button" class="mp-link" (click)="close.emit()">Скрыть</button>
      </div>
      <p class="mp-muted" *ngIf="loading">{{ data ? 'Обновляю…' : 'Собираю договоры — до 20 секунд…' }}</p>
      <p class="mp-err" *ngIf="error">{{ error }}</p>
      <ng-container *ngIf="data as d">
        <p class="mp-muted" *ngIf="!d.category">Категорию не нашли — договоров с таким товаром в наборе нет.</p>
        <ng-container *ngIf="d.category as c">
          <div class="mp-cat">{{ c.name || 'Категория' }}<span class="mp-muted"> · {{ c.code }}</span>
            <span class="mp-muted" *ngIf="c.description"> · {{ c.description }}</span></div>
          <div class="mp-row" *ngIf="d.alternatives.length">Другая категория:
            <button type="button" class="mp-chip" *ngFor="let a of d.alternatives" (click)="pickCategory(a.code)">{{ a.name || a.code }} · {{ a.count }}</button>
          </div>
          <div class="mp-row" *ngIf="d.units.length > 1">Единица:
            <button type="button" class="mp-chip" *ngFor="let u of d.units" [class.mp-on]="u === d.unit" (click)="pickUnit(u)">{{ u }}</button>
          </div>
          <form class="mp-filter" (submit)="applyFilter(); $event.preventDefault()">
            <input [(ngModel)]="filter" name="mpFilter" placeholder="Слово: автомат, монитор…" aria-label="Фильтр по слову" />
            <button type="submit" class="btn btn-line">Найти</button>
            <button type="button" class="mp-link" *ngIf="applied" (click)="filter = ''; applyFilter()">сбросить</button>
          </form>
          <p class="mp-muted" *ngIf="!d.stats.count">Договоров{{ applied ? ' со словом «' + applied + '»' : '' }} за 2 года нет.</p>
          <div class="mp-stats" *ngIf="d.stats.count">
            <div><span class="mp-label">Медиана</span><b>{{ d.stats.median | money }}</b> за {{ d.unit }}</div>
            <div><span class="mp-label">Половина договоров</span>{{ d.stats.p25 | money }} – {{ d.stats.p75 | money }}</div>
            <div><span class="mp-label">Мин – макс</span>{{ d.stats.min | money }} – {{ d.stats.max | money }}</div>
            <div><span class="mp-label">Договоров</span>{{ d.stats.count }}</div>
            <div *ngIf="d.stats.budgetMedian"><span class="mp-label">Медиана бюджета</span>{{ d.stats.budgetMedian | money }}</div>
            <div *ngIf="d.stats.lotBudget && d.stats.budgetDiffPct != null"><span class="mp-label">Бюджет здесь</span>{{ d.stats.lotBudget | money }} ({{ pct(d.stats.budgetDiffPct) }})</div>
            <div *ngIf="d.bids.count"><span class="mp-label">Цены участников</span>медиана {{ d.bids.median | money }} · {{ d.bids.count }} предл.</div>
          </div>
          <table class="responsive-cards card-grid mp-table" *ngIf="d.rows.length">
            <thead><tr><th>Дата</th><th>Регион</th><th>Заказчик</th><th>Кол-во</th><th>Цена договора</th><th>Бюджет</th><th>Поставщик</th></tr></thead>
            <tbody>
              <tr *ngFor="let r of d.rows">
                <td data-label="Дата">{{ date(r.signedAt) || '—' }}</td>
                <td data-label="Регион">{{ r.region || '—' }}</td>
                <td data-label="Заказчик"><span class="mp-cust" [title]="r.customer || ''">{{ r.customer || '—' }}</span><a *ngIf="r.anno" class="mp-anno" [href]="url(r.anno)" target="_blank" rel="noopener">№ {{ r.anno }} ↗</a></td>
                <td data-label="Кол-во">{{ r.quantity ?? '—' }} {{ r.unit }}</td>
                <td data-label="Цена договора">{{ r.unitPrice | money }}</td>
                <td data-label="Бюджет">{{ r.budgetUnitPrice | money }}</td>
                <td data-label="Поставщик">{{ r.supplierName || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </ng-container>
        <p class="mp-muted">{{ freshness(d) }}</p>
      </ng-container>
    </div>
  `,
  styles: [`
    .mp { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin: 10px 0; background: var(--surface); }
    .mp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .mp-link { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 13px; padding: 4px 0; }
    .mp-muted { color: var(--text-muted); font-size: 13px; margin: 6px 0; }
    .mp-err { color: var(--danger-text); font-size: 13px; }
    .mp-cat { margin: 8px 0 4px; font-weight: 500; color: var(--text); overflow-wrap: anywhere; }
    .mp-row { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 13px; color: var(--text-muted); margin: 6px 0; }
    .mp-chip { font-size: 12px; padding: 3px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface);
               color: var(--text); cursor: pointer; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mp-chip.mp-on { background: color-mix(in srgb, var(--accent) 15%, transparent); border-color: var(--accent); color: var(--accent); }
    .mp-filter { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin: 8px 0; }
    .mp-filter input { flex: 1 1 200px; min-width: 0; height: 34px; border: 1px solid var(--border); border-radius: 8px; padding: 0 10px;
                       background: var(--surface); color: var(--text); }
    .mp-stats { display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: 6px 16px; margin: 8px 0; font-size: 13px; color: var(--text); }
    .mp-label { display: block; font-size: 12px; color: var(--text-muted); }
    .mp-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .mp-table th, .mp-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .mp-anno { display: block; font-size: 12px; color: var(--accent); }
    @media (max-width: 900px) {
      .mp-link, .mp-chip { min-height: 40px; }
      .mp-filter input { height: 40px; }
      .mp-table td { border-bottom: none; padding: 2px 0; }
      .mp-cust { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
      .mp-table tr { grid-template-columns: 1fr auto; grid-template-areas: "cust price" "reg date" "sup qty"; }
      .mp-table td[data-label="Заказчик"] { grid-area: cust; }
      .mp-table td[data-label="Цена договора"] { grid-area: price; text-align: right; font-weight: 600; }
      .mp-table td[data-label="Регион"] { grid-area: reg; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Дата"] { grid-area: date; text-align: right; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Поставщик"] { grid-area: sup; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Кол-во"] { grid-area: qty; text-align: right; color: var(--text-muted); font-size: 12px; }
      .mp-table td[data-label="Бюджет"] { display: none; }
    }
  `],
})
export class MarketPricePanelComponent implements OnChanges {
  @Input() lotId: number | null = null;
  @Input() query: string | null = null;
  @Output() close = new EventEmitter<void>();

  data: MarketPrice | null = null;
  loading = false;
  error = '';
  filter = '';
  applied = '';
  private code: string | null = null;
  private unit: string | null = null;
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    this.code = null;
    this.unit = null;
    this.filter = this.applied = '';
    this.data = null;
    this.load();
  }

  pickCategory(code: string) { this.code = code; this.unit = null; this.load(); }
  pickUnit(u: string) { this.unit = u; this.load(); }
  applyFilter() { this.applied = this.filter.trim(); this.load(); }

  private load() {
    const n = ++this.seq;
    this.loading = true;
    this.error = '';
    this.api.getMarketPrices({ lotId: this.lotId, q: this.lotId == null ? this.query : null, code: this.code, unit: this.unit,
      filter: this.applied || null }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: d => { if (n !== this.seq) return; this.data = d; this.loading = false; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.loading = false; this.error = 'Цены рынка не загрузились: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  freshness(d: MarketPrice): string {
    const when = d.fetchedAt ? 'Обновлено с площадки ' + instantText(d.fetchedAt) : '';
    if (d.stale && d.error) return (d.error.charAt(0).toUpperCase() + d.error.slice(1)) + (when ? '; ' + when.toLowerCase() : '');
    return when;
  }

  pct(n: number): string { return (n < 0 ? '−' + (-n) : '+' + n) + ' %'; }
  date(iso: string | null): string { return plainDate(iso); }
  url(anno: string): string { return announceUrl(anno); }
}
