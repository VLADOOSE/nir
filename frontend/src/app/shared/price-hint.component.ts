import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../services/api.service';
import { hintLine, hintMoney } from './price-hint';
import type { PriceHint, PriceHintRow, PriceHistoryEntry } from './price-hint';

/**
 * «📒 по прайсу» (спека 2026-10-23 §6.1): строка лучшей цены, по нажатию — все поставщики с курсом сегодня, условиями,
 * сроком прайса, историей и ссылкой на прайс. В не ленивой странице «Тендеры» — только под @defer.
 */
@Component({
  selector: 'app-price-hint',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <button type="button" class="ph-line" *ngIf="!open && line" (click)="open = true">{{ line }}</button>
    <div class="ph-panel" *ngIf="open && hint?.rows?.length">
      <div class="ph-head"><b>По прайсам поставщиков</b>
        <button type="button" class="ph-x" (click)="open = false; closed.emit()" aria-label="Свернуть">свернуть</button></div>
      <div class="ph-row" *ngFor="let r of hint!.rows">
        <div class="ph-top"><span class="ph-sup">{{ r.supplierName }}</span>
          <span class="ph-price">{{ money(r.price, r.currency) }}<ng-container *ngIf="r.currency !== marketCurrency">
            <ng-container *ngIf="r.converted != null; else noRate"> → {{ money(r.converted, marketCurrency) }}</ng-container>
            <ng-template #noRate> — впишите наш курс</ng-template></ng-container></span></div>
        <div class="ph-name">{{ r.name }}</div>
        <div class="ph-meta">
          <span *ngIf="r.today != null">по курсу сегодня — {{ money(r.today, marketCurrency) }} · </span>
          прайс {{ dm(r.listDate) }}<ng-container *ngIf="r.validTo"> · до {{ dmy(r.validTo) }}</ng-container>
          <span class="ph-exp" *ngIf="r.expired"> · прайс истёк</span>
        </div>
        <div class="ph-terms" *ngIf="r.terms">{{ r.terms }}</div>
        <div class="ph-links">
          <a class="xref" [routerLink]="['/price-lists', r.priceListId]">прайс ›</a>
          <button type="button" class="ph-x" (click)="toggleHistory(r)">{{ hist.has(r.rowId) ? 'скрыть историю' : 'история ▾' }}</button>
        </div>
        <ul class="ph-hist" *ngIf="hist.get(r.rowId) as h">
          <li *ngIf="!h.length">Других цен этого товара пока нет</li>
          <li *ngFor="let e of h">{{ e.kind === 'LIST' ? 'прайс' : 'ответ на запрос' }} {{ dmy(e.date) }} {{ e.supplierName }} —
            {{ money(e.price, e.currency) }}<ng-container *ngIf="e.pct != null"> ({{ e.pct > 0 ? '+' : '' }}{{ pct(e.pct) }} %)</ng-container></li>
        </ul>
      </div>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .ph-line { display: block; width: 100%; text-align: left; border: 0; background: none; padding: 2px 0; color: var(--text-muted);
               font-size: 13px; cursor: pointer; overflow-wrap: anywhere; }
    .ph-line:hover { color: var(--accent); }
    .ph-panel { margin-top: 4px; border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; background: var(--surface);
                display: flex; flex-direction: column; gap: 8px; }
    .ph-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; font-size: 13px; }
    .ph-x { border: 0; background: none; color: var(--accent); cursor: pointer; font-size: 12px; padding: 0; }
    .ph-row { border-top: 1px solid var(--border); padding-top: 6px; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
    .ph-row:first-of-type { border-top: 0; padding-top: 0; }
    .ph-top { display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
    .ph-sup { font-weight: 600; }
    .ph-price { white-space: nowrap; }
    .ph-name, .ph-meta, .ph-terms { color: var(--text-muted); font-size: 12px; overflow-wrap: anywhere; }
    .ph-terms { white-space: pre-line; }
    .ph-exp { color: var(--warn-text); }
    .ph-links { display: flex; gap: 12px; align-items: center; }
    .ph-hist { margin: 0; padding-left: 16px; font-size: 12px; color: var(--text-muted); }
    @media (max-width: 900px) { .ph-line, .ph-x { min-height: 40px; } }
  `],
})
export class PriceHintComponent {
  @Input() hint: PriceHint | null | undefined = null;
  @Input() marketCurrency = 'KZT';
  @Input() open = false;
  @Output() closed = new EventEmitter<void>();
  hist = new Map<number, PriceHistoryEntry[]>();

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get line(): string | null { return hintLine(this.hint, this.marketCurrency); }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }
  dm(iso: string | null) { return iso ? iso.slice(8, 10) + '.' + iso.slice(5, 7) : ''; }
  dmy(iso: string | null) { return iso ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}` : ''; }
  pct(v: number) { return String(v).replace('.', ','); }

  toggleHistory(r: PriceHintRow) {
    if (this.hist.has(r.rowId)) { this.hist.delete(r.rowId); return; }
    this.api.getPriceHistory(r.rowId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { this.hist.set(r.rowId, h); this.cdr.markForCheck(); },
      error: () => { this.hist.set(r.rowId, []); this.cdr.markForCheck(); },
    });
  }
}
