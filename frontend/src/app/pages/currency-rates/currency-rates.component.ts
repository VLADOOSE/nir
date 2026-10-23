import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { parseNum } from '../../shared/client-offer';
import { curShort, dayMonth, rateText } from '../../shared/rates';
import type { RatesView } from '../../shared/rates';

/** «Система → Курсы валют» (спека 2026-10-23 §4.1–4.2): наш курс по валютам рынка и курс НБ РК рядом. */
@Component({
  selector: 'app-currency-rates',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="cr-page">
      <h2>Курсы валют</h2>
      <p class="subtitle">Рынок: <b>{{ marketLabel }}</b>. <b>Наш курс</b> переводит закупку в чужой валюте в {{ short(view?.marketCurrency) }}
        — от неё считается цена клиенту. Ставьте с запасом: клиент может купить позже, а курс — вырасти.
        <ng-container *ngIf="view?.todayEnabled"><b>Курс сегодня</b> — Национальный банк РК: по нему видны настоящая закупка и прибыль.</ng-container></p>
      <p class="cr-note" *ngIf="loading">Загружаю…</p>
      <div class="cr-list" *ngIf="view as v">
        <div class="cr-row" *ngFor="let r of v.rates">
          <span class="cr-cur">1 {{ short(r.currency) }}</span>
          <label class="cr-our">наш курс
            <input inputmode="decimal" [(ngModel)]="draft[r.currency]" placeholder="не задан"
                   [attr.aria-label]="'Наш курс: 1 ' + short(r.currency) + ' в ' + short(v.marketCurrency)" />
            {{ short(v.marketCurrency) }}</label>
          <span class="cr-today" *ngIf="v.todayEnabled">
            <ng-container *ngIf="r.today as t; else noToday">сегодня {{ rate(t.rate) }}
              <span [class.cr-stale]="t.stale">· {{ t.stale ? 'курс от ' : '' }}{{ day(t.date) }}</span></ng-container>
            <ng-template #noToday><span class="cr-muted">курса сегодня нет</span></ng-template>
          </span>
          <span class="cr-margin" *ngIf="r.marginPct != null" [class.cr-neg]="r.marginPct < 0">
            запас {{ r.marginPct > 0 ? '+' : '' }}{{ pct(r.marginPct) }} %</span>
          <span class="cr-who" *ngIf="r.updatedBy">{{ r.updatedBy }} · {{ day(r.updatedAt) }}</span>
        </div>
        <p class="cr-note" *ngIf="!v.todayEnabled">Курс сегодня (НБ РК) — только для Казахстана: на этом рынке закупка считается по нашему курсу.</p>
        <div class="cr-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .cr-page { max-width: 820px; display: flex; flex-direction: column; gap: 10px; }
    .cr-page h2 { margin: 0; }
    .cr-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .cr-list { display: flex; flex-direction: column; gap: 6px; }
    .cr-row { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: center; padding: 10px 12px; border: 1px solid var(--border);
              border-radius: 10px; background: var(--surface); }
    .cr-cur { font-weight: 600; min-width: 64px; }
    .cr-our { display: flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-muted); }
    .cr-our input { width: 110px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                    color: var(--text); font-size: 14px; }
    .cr-today, .cr-who { font-size: 13px; color: var(--text-muted); }
    .cr-stale { color: var(--warn-text); }
    .cr-muted { color: var(--text-muted); }
    .cr-margin { font-size: 12px; padding: 2px 9px; border-radius: 999px; color: var(--success-text);
                 background: color-mix(in srgb, var(--success) 15%, transparent); }
    .cr-margin.cr-neg { color: var(--danger-text); background: color-mix(in srgb, var(--danger) 15%, transparent); }
    .cr-actions { margin-top: 4px; }
    @media (max-width: 900px) {
      .cr-our input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class CurrencyRatesComponent implements OnInit {
  view: RatesView | null = null;
  loading = true;
  saving = false;
  draft: Record<string, string> = {};
  readonly short = curShort;
  readonly rate = rateText;
  readonly day = (iso: string | null) => (iso ? dayMonth(iso.slice(0, 10)) + '.' + iso.slice(0, 4) : '');

  constructor(private api: ApiService, private market: MarketService, private notify: NotificationService,
              private cdr: ChangeDetectorRef) {}

  get marketLabel(): string { return this.market.value === 'KZ' ? 'West-Med (KZ)' : 'Регион-Мед (РФ)'; }

  ngOnInit() {
    this.api.getCurrencyRates().subscribe({
      next: v => this.show(v),
      error: e => { this.loading = false; this.notify.error('Курсы не загрузились: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  pct(v: number): string { return String(v).replace('.', ','); }

  private show(v: RatesView) {
    this.view = v;
    this.loading = false;
    this.draft = {};
    for (const r of v.rates) this.draft[r.currency] = r.ourRate == null ? '' : rateText(r.ourRate);
    this.cdr.markForCheck();
  }

  save() {
    const v = this.view;
    if (!v) return;
    const rates: { currency: string; ourRate: number | null }[] = [];
    for (const r of v.rates) {
      const text = (this.draft[r.currency] ?? '').trim();
      const n = text === '' ? null : parseNum(text);
      if (text !== '' && (n == null || n <= 0 || n > 100000 || !/^\d+([.,]\d{1,6})?$/.test(text.replace(/\s/g, '')))) {
        this.notify.error(`Курс ${curShort(r.currency)} — число больше нуля, до 100 000 и не больше 6 знаков после запятой`);
        return;
      }
      rates.push({ currency: r.currency, ourRate: n });
    }
    this.saving = true;
    this.api.saveCurrencyRates(rates).subscribe({
      next: nv => { this.saving = false; this.show(nv); this.notify.success('Курсы сохранены'); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
