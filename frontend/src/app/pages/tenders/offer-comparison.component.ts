import { Component, EventEmitter, Input, Output, OnChanges, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-offer-comparison',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, DecimalPipe],
  template: `
    <div class="oc-overlay" *ngIf="tenderId != null" (click)="onOverlay($event)">
      <div class="oc-window" (click)="$event.stopPropagation()">
        <div class="oc-head">
          <h2>Сравнение предложений</h2>
          <button class="oc-close" (click)="close.emit()">&times;</button>
        </div>
        <div *ngIf="loading" class="oc-loading">Загрузка…</div>
        <div *ngIf="!loading">
          <div class="oc-controls">
            <label>Наценка: <input type="number" [(ngModel)]="markup" min="0" class="oc-markup" /> %</label>
            <span class="oc-hint">Зелёным — минимальная цена по лоту. «с наценкой» = цена × (1 + наценка/100).</span>
          </div>
          <div class="oc-empty" *ngIf="!data || !data.lots?.length">Нет ответов с ценами для сравнения.</div>
          <table class="oc-table" *ngIf="data && data.lots?.length">
            <thead>
              <tr>
                <th>Лот</th>
                <th *ngFor="let s of data.suppliers">{{ s.distributorName }}</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let lot of data.lots">
                <td class="oc-lot">№{{ lot.lotNumber || '—' }} {{ lot.lotName }} <small>×{{ lot.quantity }}</small></td>
                <td *ngFor="let s of data.suppliers"
                    [class.oc-best]="data.bestByLot[lot.lotId] === s.priceRequestId">
                  <ng-container *ngIf="price(lot.lotId, s.priceRequestId) as p">
                    {{ p | number:'1.0-0' }} {{ sym }}
                    <small class="oc-marked">→ {{ withMarkup(p) | number:'1.0-0' }}</small>
                  </ng-container>
                  <span *ngIf="!price(lot.lotId, s.priceRequestId)">—</span>
                </td>
              </tr>
              <tr class="oc-totals">
                <td>Итого</td>
                <td *ngFor="let s of data.suppliers">
                  {{ (data.totalsBySupplier[s.priceRequestId] || 0) | number:'1.0-0' }} {{ sym }}
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .oc-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .oc-window { background: #fff; border-radius: 10px; padding: 20px; width: min(960px, 94vw); max-height: 88vh; overflow: auto; }
    .oc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .oc-close { background: none; border: none; font-size: 24px; cursor: pointer; color: #6b7280; }
    .oc-loading, .oc-empty { color: #6b7280; padding: 20px 0; }
    .oc-controls { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
    .oc-markup { width: 64px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; }
    .oc-hint { color: #6b7280; font-size: 12px; }
    .oc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .oc-table th, .oc-table td { border: 1px solid #e5e7eb; padding: 7px 10px; text-align: left; }
    .oc-table thead th { background: #f9fafb; }
    .oc-lot { max-width: 320px; }
    .oc-best { background: #ecfdf5; font-weight: 600; }
    .oc-marked { color: #6b7280; }
    .oc-totals td { background: #f3f4f6; font-weight: 600; }
  `],
})
export class OfferComparisonComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Output() close = new EventEmitter<void>();

  data: any = null;
  loading = false;
  markup = 25;
  sym = '';

  constructor(private api: ApiService, private notify: NotificationService,
              private market: MarketService, private cdr: ChangeDetectorRef) {
    this.sym = this.market.symbol();
  }

  ngOnChanges() {
    if (this.tenderId == null) { this.data = null; return; }
    this.loading = true; this.cdr.detectChanges();
    this.api.getOfferComparison(this.tenderId).subscribe({
      next: (r) => { this.data = r; this.loading = false; this.cdr.detectChanges(); },
      error: (e) => { this.loading = false; this.notify.error('Ошибка сравнения: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  onOverlay(_: Event) { this.close.emit(); }

  price(lotId: number, prId: number): number | null {
    const c = (this.data?.cells || []).find((x: any) => x.lotId === lotId && x.priceRequestId === prId);
    return c ? Number(c.responsePrice) : null;
  }
  withMarkup(p: number): number { return p * (1 + (Number(this.markup) || 0) / 100); }
}
