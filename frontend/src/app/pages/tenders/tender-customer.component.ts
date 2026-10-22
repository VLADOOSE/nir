import { ChangeDetectorRef, Component, DestroyRef, Input, OnChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { errorText } from '../../shared/error-text';
import { CustomerContacts, CustomerHistory, plainDate } from '../../shared/tender-results';

/** Заказчик на площадке (спека 2026-10-22-tender-results-market-prices §8, §11): телефон, почта, история договоров по нажатию. */
@Component({
  selector: 'app-tender-customer',
  standalone: true,
  imports: [NgIf, NgFor, MarketMoneyPipe],
  template: `
    <div class="tc">
      <span class="tc-label">Заказчик на площадке</span>
      <span class="tc-body" *ngIf="contacts as c">
        <span>{{ c.name || '—' }}</span>
        <a *ngIf="c.phone" [href]="'tel:' + tel(c.phone)">☎ {{ c.phone }}</a>
        <a *ngIf="c.email" [href]="'mailto:' + c.email">{{ c.email }}</a>
        <span class="tc-muted" *ngIf="!c.phone && !c.email">контактов в справочнике площадки нет</span>
      </span>
      <span class="tc-muted" *ngIf="!contacts && !error">Загружаю…</span>
      <span class="tc-err" *ngIf="error">{{ error }}</span>
      <button type="button" class="tc-link" (click)="toggleHistory()">{{ historyOpen ? 'Скрыть историю заказчика' : 'История заказчика' }}</button>
    </div>
    <div class="th" *ngIf="historyOpen">
      <p class="tc-muted" *ngIf="historyLoading">Загружаю договоры заказчика — до нескольких секунд…</p>
      <p class="tc-err" *ngIf="history?.error">{{ history!.error }}</p>
      <p class="tc-muted" *ngIf="history && !historyLoading && !history.rows.length">Договоров по медизделиям за 2 года нет</p>
      <table class="responsive-cards card-grid th-table" *ngIf="history?.rows?.length">
        <thead><tr><th>Дата</th><th>Что</th><th>Кол-во</th><th>Цена за ед.</th><th>Поставщик</th></tr></thead>
        <tbody>
          <tr *ngFor="let r of history!.rows" [class.th-same]="r.sameCategory">
            <td data-label="Дата">{{ date(r.signedAt) || '—' }}</td>
            <td data-label="Что">{{ r.name || '—' }}<span class="th-tag" *ngIf="r.sameCategory">та же категория</span></td>
            <td data-label="Кол-во">{{ r.quantity ?? '—' }} {{ r.unit || '' }}</td>
            <td data-label="Цена за ед.">{{ r.unitPrice | money:2 }}</td>
            <td data-label="Поставщик">{{ r.supplierName || '—' }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .tc { display: flex; flex-wrap: wrap; gap: 6px 12px; align-items: baseline; font-size: 14px; margin-top: 12px; }
    .tc-label { color: var(--text-muted); font-size: 13px; }
    .tc-body { display: inline-flex; flex-wrap: wrap; gap: 4px 12px; color: var(--text); }
    .tc-body a, .tc-link { color: var(--accent); }
    .tc-link { background: none; border: none; cursor: pointer; font-size: 13px; padding: 4px 0; }
    .tc-muted { color: var(--text-muted); font-size: 13px; }
    .tc-err { color: var(--danger-text); font-size: 13px; }
    .th { margin-top: 8px; }
    .th-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .th-table th, .th-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .th-same td { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .th-tag { display: inline-block; margin-left: 6px; font-size: 11px; padding: 1px 7px; border-radius: 10px;
              background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    @media (max-width: 900px) {
      .tc-link { min-height: 40px; }
      .th-table tr { grid-template-columns: 1fr auto; grid-template-areas: "name price" "date sup" "qty qty"; }
      .th-table td[data-label="Что"] { grid-area: name; }
      .th-table td[data-label="Цена за ед."] { grid-area: price; text-align: right; font-weight: 600; }
      .th-table td[data-label="Дата"] { grid-area: date; color: var(--text-muted); font-size: 12px; }
      .th-table td[data-label="Поставщик"] { grid-area: sup; text-align: right; color: var(--text-muted); font-size: 12px; }
      .th-table td[data-label="Кол-во"] { grid-area: qty; color: var(--text-muted); font-size: 12px; }
    }
  `],
})
export class TenderCustomerComponent implements OnChanges {
  @Input() tenderId!: number;

  contacts: CustomerContacts | null = null;
  error = '';
  historyOpen = false;
  historyLoading = false;
  history: CustomerHistory | null = null;
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    const n = ++this.seq;
    this.contacts = null;
    this.error = '';
    this.historyOpen = false;
    this.history = null;
    this.api.getTenderCustomer(this.tenderId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: c => { if (n !== this.seq) return; this.contacts = c; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.error = 'Контакты не загрузились: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  toggleHistory() {
    this.historyOpen = !this.historyOpen;
    if (!this.historyOpen || this.history || this.historyLoading) return;
    const n = this.seq;
    this.historyLoading = true;
    this.api.getCustomerHistory(this.tenderId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { if (n !== this.seq) return; this.history = h; this.historyLoading = false; this.cdr.markForCheck(); },
      error: e => {
        if (n !== this.seq) return;
        this.historyLoading = false;
        this.history = { bin: null, rows: [], fetchedAt: null, error: 'История не загрузилась: ' + errorText(e) };
        this.cdr.markForCheck();
      },
    });
  }

  date(iso: string | null): string { return plainDate(iso); }

  /** «8 (71133) 31398» → «87113331398» для tel:. */
  tel(phone: string): string { return phone.replace(/[^\d+]/g, ''); }
}
