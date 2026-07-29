import { Component, EventEmitter, Input, Output, OnChanges, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-offer-comparison',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, DecimalPipe, RouterLink],
  template: `
    <div class="oc-overlay" *ngIf="tenderId != null" (click)="onOverlay($event)">
      <div class="oc-window" (click)="$event.stopPropagation()">
        <div class="oc-head">
          <h2>Сравнение предложений</h2>
          <button class="oc-close" (click)="close.emit()">&times;</button>
        </div>
        <div *ngIf="loading" class="oc-loading">Загрузка…</div>
        <div *ngIf="!loading" class="oc-body">
          <div class="oc-controls">
            <label>Наценка: <input type="number" [(ngModel)]="markup" min="0" class="oc-markup" /> %</label>
            <span class="oc-hint">Зелёным — минимальная цена по лоту. «с наценкой» = цена × (1 + наценка/100).</span>
          </div>
          <div class="oc-empty" *ngIf="!data || !data.lots?.length">Нет ответов с ценами для сравнения.</div>
          <div class="table-scroll" *ngIf="data && data.lots?.length">
          <!-- data-label на ячейках-поставщиках: на десктопе инертен, на ≤900px из него
               строится подпись строки предложения (глобальный режим responsive-cards). -->
          <table class="oc-table responsive-cards">
            <thead>
              <tr>
                <th>Лот</th>
                <th *ngFor="let s of data.suppliers">{{ s.distributorName }}</th>
              </tr>
            </thead>
            <tbody>
              <tr *ngFor="let lot of data.lots">
                <td class="oc-lot">№{{ lot.lotNumber || '—' }} {{ lot.lotName }} <small>×{{ lot.quantity }}</small></td>
                <td *ngFor="let s of data.suppliers" [attr.data-label]="s.distributorName"
                    [class.oc-best]="data.bestByLot[lot.lotId] === s.priceRequestId"
                    [class.oc-winner]="assignedByLot[lot.lotId] === s.priceRequestId">
                  <ng-container *ngIf="price(lot.lotId, s.priceRequestId) as p">
                    <!-- &ngsp; — неудаляемый пробел: без него компилятор срезает пробел между
                         ценой и «→ с наценкой», и десктопная ячейка съезжает на 0.1px. -->
                    <span class="oc-price">{{ p | number:'1.0-0' }} {{ sym }}</span>&ngsp;<small class="oc-marked">→ {{ withMarkup(p) | number:'1.0-0' }}</small>
                    <div class="oc-actions">
                      <span *ngIf="assignedByLot[lot.lotId] === s.priceRequestId" class="oc-badge">★ победитель</span>
                      <button *ngIf="assignedByLot[lot.lotId] !== s.priceRequestId" class="oc-assign"
                              (click)="assign(lot, s)">✓ Назначить</button>
                    </div>
                  </ng-container>
                  <span class="oc-nodata" *ngIf="!price(lot.lotId, s.priceRequestId)">—</span>
                </td>
              </tr>
              <tr class="oc-totals">
                <td class="oc-totals-head">Итого</td>
                <td *ngFor="let s of data.suppliers" [attr.data-label]="s.distributorName">
                  {{ (data.totalsBySupplier[s.priceRequestId] || 0) | number:'1.0-0' }} {{ sym }}
                </td>
              </tr>
            </tbody>
          </table>
          </div>
          <div class="oc-apply-link" *ngIf="assignedApplyId">
            Победители сохранены в заявку.
            <a [routerLink]="['/applies']" [queryParams]="{ openId: assignedApplyId }">Открыть заявку →</a>
          </div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .oc-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .oc-window { background: var(--surface); border-radius: 10px; padding: 20px; width: min(960px, 94vw); max-height: 88vh; overflow: auto; }
    .oc-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .oc-close { background: none; border: none; font-size: 24px; cursor: pointer; color: var(--text-muted); }
    .oc-loading, .oc-empty { color: var(--text-muted); padding: 20px 0; }
    .oc-controls { display: flex; align-items: center; gap: 14px; margin-bottom: 12px; flex-wrap: wrap; }
    .oc-markup { width: 64px; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .oc-hint { color: var(--text-muted); font-size: 12px; }
    .oc-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .oc-table th, .oc-table td { border: 1px solid var(--border); padding: 7px 10px; text-align: left; }
    .oc-table thead th { background: var(--surface-2); }
    .oc-lot { max-width: 320px; }
    /* Подсветка ОБЛАСТИ (ячейка), не чип: минимум по лоту — слабее, назначенный победитель — сильнее. */
    .oc-best { background: color-mix(in srgb, var(--success) 8%, var(--surface)); font-weight: 600; }
    .oc-marked { color: var(--text-muted); }
    .oc-totals td { background: var(--surface-2); font-weight: 600; }
    .oc-winner { background: color-mix(in srgb, var(--success) 18%, var(--surface)); }
    .oc-actions { margin-top: 4px; }
    .oc-badge { color: var(--success-text); font-weight: 600; font-size: 11px; }
    .oc-assign { font-size: 11px; padding: 2px 6px; border: 1px solid var(--success); color: var(--success-text); background: var(--surface); border-radius: 4px; cursor: pointer; }
    .oc-assign:hover { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .oc-apply-link { margin-top: 14px; font-size: 13px; }
    .oc-apply-link a { color: var(--accent); }

    /* ─── Мобилка: переворот матрицы (ТОЛЬКО ≤900px, десктоп не трогаем) ────────
       Матрица лоты × поставщики на телефоне не работает в принципе: и строк, и
       колонок произвольное число, поэтому горизонтальный скролл тут не «неудобно»,
       а «нечитаемо» (на 390px из 8 поставщиков видно 2.5, сравнивать нечем).
       Переворачиваем: карточка на ЛОТ, внутри — вертикальный список предложений
       «поставщик — цена».

       Шасси карточки берём из глобального механического режима responsive-cards
       (styles.scss): tr → карточка, thead скрыт, td → строка «подпись: значение».
       Матрица ложится на него точно: подпись строки — это имя КОЛОНКИ, то есть
       поставщик, и оно приезжает через [attr.data-label] в разметке. Ниже —
       только то, чего механическому режиму не хватает под матрицу; ни одного
       нового цвета, подсветки .oc-best / .oc-winner работают как были (класс
       висит на той же ячейке, просто она теперь строка во всю ширину карточки).

       Блок последний в styles намеренно: при равной специфичности позднее
       базовое правило перебило бы @media (гоча репозитория). */
    @media (max-width: 900px) {
      /* На 390px каждый пиксель на счету: модалка шире, поля меньше */
      .oc-window { width: 96vw; padding: 14px; max-height: 92vh; }
      .oc-head h2 { font-size: 18px; }
      .oc-head { margin-bottom: 8px; }

      /* Наценка с подсказкой уезжают ПОД список — вместе с итогами это нижний
         блок сводки; сверху остаётся то, ради чего экран открыли (предложения). */
      .oc-body { display: flex; flex-direction: column; }
      .oc-body .table-scroll { order: 1; }
      .oc-body .oc-controls { order: 2; margin: 14px 0 0; }
      .oc-body .oc-apply-link { order: 3; }

      /* Скролл-обёртка на мобилке не нужна: карточки укладываются в ширину.
         Элемент оставлен — на десктопе при узком окне он всё ещё осмыслен. */
      .table-scroll { overflow-x: visible; }

      /* Шапка карточки: название лота (и «Итого» у карточки итогов) во всю
         ширину. В механическом режиме ячейка без data-label прижимается вправо —
         возвращаем влево и делаем полосой в цвет шапки таблицы на десктопе. */
      .oc-table .oc-lot,
      .oc-table .oc-totals-head {
        display: block; max-width: none; text-align: left;
        font-weight: 600; font-size: 14px;
        margin: -4px -12px 4px; padding: 8px 12px;
        background: var(--surface-2); border-radius: 8px 8px 0 0;
      }

      /* Строка предложения — грид, а не флекс механического режима:
         строка 1 «поставщик … цена», строка 2 «→ с наценкой … [кнопка]».
         Флекс с переносом рвал строку в непредсказуемом месте на длинных
         названиях ТОО; грид держит цену и кнопку у правого края всегда.
         Поля отрицательные — чтобы подсветка минимума/победителя шла от края
         до края карточки, а не полосой с отступами. */
      .oc-table td[data-label] {
        display: grid; grid-template-columns: 1fr auto;
        align-items: center; gap: 2px 10px;
        margin: 0 -12px; padding: 7px 12px; text-align: left;
      }
      .oc-table td[data-label]::before {
        font-size: 13px; font-weight: 500; color: var(--text);
        min-width: 0; overflow-wrap: anywhere;
      }
      /* разделитель между предложениями (границы ячеек глобально сняты) */
      .oc-table td[data-label] + td[data-label] { box-shadow: inset 0 1px 0 var(--border); }

      .oc-table .oc-price { justify-self: end; white-space: nowrap; font-weight: 600; }
      .oc-table .oc-marked { grid-column: 1; grid-row: 2; }
      .oc-table .oc-actions { grid-column: 2; grid-row: 2; justify-self: end; margin-top: 0; }
      .oc-table .oc-nodata { justify-self: end; color: var(--text-muted); }
      .oc-assign { font-size: 13px; padding: 6px 10px; }

      /* Итоги: карточка как у лота (белая с шапкой), а не сплошная плашка —
         базовое .oc-totals td красит surface-2 всю ячейку, включая шапку. */
      .oc-table .oc-totals td[data-label] { background: transparent; }
    }
  `],
})
export class OfferComparisonComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Output() close = new EventEmitter<void>();

  data: any = null;
  loading = false;
  markup = 25;
  sym = '';
  assignedByLot: { [lotId: number]: number } = {};
  assignedApplyId: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService,
              private market: MarketService, private cdr: ChangeDetectorRef) {
    this.sym = this.market.symbol();
  }

  ngOnChanges() {
    this.assignedByLot = {};        // сброс стейта победителей при (пере)открытии — иначе бейджи/ссылка от прошлого тендера
    this.assignedApplyId = null;
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

  assign(lot: any, s: any) {
    if (this.tenderId == null) return;
    this.api.assignWinner(this.tenderId, { lotId: lot.lotId, priceRequestId: s.priceRequestId }).subscribe({
      next: (r) => {
        this.assignedByLot[lot.lotId] = s.priceRequestId;
        this.assignedApplyId = r.applyId;
        this.notify.success(`Назначен ${r.distributorName} по лоту №${lot.lotNumber || '—'}`);
        this.cdr.detectChanges();
      },
      error: (e) => this.notify.error('Не удалось назначить: ' + (e.error?.message || e.message)),
    });
  }
}
