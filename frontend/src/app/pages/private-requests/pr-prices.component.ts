import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { PriceCell, PriceLine, Prices, dateText } from '../../shared/private-request';
import { money, numText, parseNum } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

/** «18 400» — целая цена без копеек, «17 950,50» — с копейками (а не «17 950,5»). */
function priceText(v: number | null): string {
  return v == null ? '' : Number.isInteger(v) ? numText(v) : money(v);
}

/** Пометка приёма почты в начале примечания (MailIngestWriter): «💡 Цена распознана автоматически, проверьте.» */
const AUTO_MARK = /^💡[^.]*\.?\s*/u;

interface Editing { line: PriceLine; cell: PriceCell; supplier: string; price: string; note: string; }

/**
 * Таблица цен (спека 2026-10-12 §7): позиции × поставщики, кому писали. Ячейка — цена (★ лучшая по позиции), «отказ»,
 * «не доставлено», «письмо не ушло», «ждём ответа» или «—». Клик — цена и примечание вручную и «↻ Переслать».
 * На телефоне — карточка на позицию: лучшая цена и поставщики с состояниями.
 */
@Component({
  selector: 'app-pr-prices',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],
  template: `
    <div class="prices" *ngIf="prices && prices.suppliers.length">
      <div class="table-scroll desk">
        <table class="pt">
          <thead>
            <tr>
              <th class="pos">Позиция</th>
              <th *ngFor="let s of prices.suppliers">
                {{ s.name }}<span class="who" *ngIf="s.clientDisclosed" title="Клиент указан в запросе">👤</span><div class="sent" *ngIf="s.firstSentAt">письмо {{ shortDate(s.firstSentAt) }}</div>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let l of prices.lines">
              <td class="pos">{{ l.name }} <span class="q">× {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span></td>
              <td *ngFor="let c of l.cells" [class.best]="c.best">
                <button type="button" class="cell" [attr.data-state]="c.state" [disabled]="c.state === 'NONE'"
                        [attr.title]="c.note || null" (click)="edit(l, c)">{{ cellText(c) }}</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div class="mob">
        <article class="pcard" *ngFor="let l of prices.lines">
          <div class="pc-head">{{ l.name }} <span class="q">× {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span></div>
          <div class="pc-best" *ngIf="l.bestPrice != null">лучшая: {{ l.bestPrice | money:2 }} ({{ l.bestDistributorName }})</div>
          <ul>
            <ng-container *ngFor="let c of l.cells; let i = index">
              <li *ngIf="c.state !== 'NONE'">
                <button type="button" class="cell-row" [attr.data-state]="c.state" [class.best]="c.best" (click)="edit(l, c)">
                  <span>{{ prices.suppliers[i].name }}<ng-container *ngIf="prices.suppliers[i].clientDisclosed"> 👤</ng-container></span><span>{{ cellText(c) }}</span>
                </button>
              </li>
            </ng-container>
          </ul>
        </article>
      </div>
    </div>
    <p class="hint" *ngIf="error">{{ error }}</p>

    <div class="overlay" *ngIf="editing as ed" (click)="editing = null">
      <div class="modal" role="dialog" aria-label="Ответ поставщика" (click)="$event.stopPropagation()">
        <h3>{{ ed.supplier }}</h3>
        <p class="sub">{{ ed.line.name }} × {{ ed.line.quantity ?? '—' }} {{ ed.line.unit || 'шт' }}</p>
        <p class="hint">{{ stateHint(ed.cell) }}</p>
        <label>Цена за единицу <input inputmode="decimal" [(ngModel)]="ed.price" [disabled]="readonly" placeholder="0" /></label>
        <label>Примечание <input [(ngModel)]="ed.note" maxlength="2000" [disabled]="readonly" placeholder="срок, модель, условия" /></label>
        <div class="error-banner" *ngIf="editError">{{ editError }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" *ngIf="!readonly" [disabled]="busy" (click)="saveCell()">Сохранить</button>
          <button type="button" class="btn btn-line" *ngIf="!readonly && ed.cell.priceRequestId" [disabled]="busy" (click)="resend()">↻ Переслать письмо</button>
          <button type="button" class="btn btn-cancel" (click)="editing = null">Закрыть</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .prices { margin-top: 12px; }
    .pt { border-collapse: collapse; width: 100%; font-size: 13px; }
    .pt th { text-align: left; font-size: 12px; padding: 6px 8px; border-bottom: 1px solid var(--border); vertical-align: bottom; }
    .pt td { padding: 4px 6px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    .pt td.pos, .pt th.pos { min-width: 220px; }
    .sent { font-weight: 400; color: var(--text-muted); font-size: 11px; }
    .who { margin-left: 4px; }
    .q { color: var(--text-muted); white-space: nowrap; }
    .cell { width: 100%; text-align: left; background: none; border: 1px solid transparent; border-radius: 6px; padding: 6px 8px;
            cursor: pointer; color: var(--text); font-size: 13px; white-space: nowrap; font-variant-numeric: tabular-nums; }
    .cell:hover:not(:disabled) { border-color: var(--border); }
    .cell:disabled { cursor: default; color: var(--text-muted); }
    .cell[data-state="WAITING"] { color: var(--text-muted); }
    .cell[data-state="DECLINED"], .cell[data-state="BOUNCED"], .cell-row[data-state="DECLINED"], .cell-row[data-state="BOUNCED"] { color: var(--danger-text); }
    .cell[data-state="NOT_SENT"], .cell-row[data-state="NOT_SENT"] { color: var(--warn-text); }
    td.best { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    td.best .cell { color: var(--success-text); font-weight: 600; }
    .mob { display: none; }
    .hint { font-size: 12px; color: var(--text-muted); margin: 6px 0 0; }
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 460px; padding: 20px 22px; display: flex; flex-direction: column; gap: 10px; }
    .modal h3 { margin: 0; }
    .sub { margin: 0; font-size: 13px; color: var(--text-muted); }
    .modal label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    .modal input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                   color: var(--text); font: inherit; font-size: 14px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .desk { display: none; }
      .mob { display: flex; flex-direction: column; gap: 8px; }
      .pcard { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface-2); }
      .pc-head { font-weight: 600; font-size: 14px; }
      .pc-best { font-size: 13px; color: var(--success-text); margin-top: 2px; }
      .pcard ul { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; }
      .cell-row { width: 100%; min-height: 40px; display: flex; justify-content: space-between; gap: 8px; align-items: center;
                  background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 6px 10px;
                  cursor: pointer; color: var(--text); font-size: 14px; text-align: left; }
      .cell-row.best { border-color: var(--success); color: var(--success-text); font-weight: 600; }
      .overlay { padding: 0; align-items: flex-end; }
      .modal { max-width: none; border-radius: 12px 12px 0 0; padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }
      .modal input { font-size: 16px; min-height: 40px; }
      .form-actions .btn { min-height: 40px; }
    }
  `],
})
export class PrPricesComponent implements OnChanges {
  @Input() requestId!: number;
  @Input() version = 0;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  prices: Prices | null = null;
  error = '';
  editing: Editing | null = null;
  editError = '';
  busy = false;
  private sub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (ch['requestId'] || ch['version']) this.load();
  }

  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    this.editing = null;
  }

  load() {
    this.sub?.unsubscribe();
    this.sub = this.api.getPrivateRequestPrices(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.prices = p; this.error = ''; this.cdr.markForCheck(); },
      error: e => { this.error = 'Таблица цен не загрузилась: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  cellText(c: PriceCell): string {
    switch (c.state) {
      // «💡» — цена распознана из письма и ещё не проверена (примечание строки ставит приём почты)
      case 'PRICE': return priceText(c.price) + (c.best ? ' ★' : '') + (this.autoPriced(c) ? ' 💡' : '');
      case 'DECLINED': return 'отказ';
      case 'BOUNCED': return 'не доставлено';
      case 'NOT_SENT': return 'письмо не ушло';
      case 'WAITING': return 'ждём ответа';
      default: return '—';
    }
  }

  private autoPriced(c: PriceCell): boolean {
    return !!c.note && c.note.startsWith('💡');
  }

  stateHint(c: PriceCell): string {
    switch (c.state) {
      case 'PRICE': return this.autoPriced(c)
        ? 'Цена распознана из письма — сверьте с письмом и сохраните.'
        : 'Цену можно поправить — например, после звонка.';
      case 'DECLINED': return 'Поставщик отказался. Если передумал — впишите цену.';
      case 'BOUNCED': return 'Письмо вернулось — исправьте адрес в карточке поставщика и перешлите.';
      case 'NOT_SENT': return 'Письмо не ушло: у поставщика нет почты или почта его не приняла. Цену можно вписать после звонка.';
      case 'WAITING': return 'Ответа ещё нет. Цена из письма с одной позицией встанет сама.';
      default: return '';
    }
  }

  edit(line: PriceLine, cell: PriceCell) {
    if (cell.state === 'NONE' || !this.prices) return;
    const supplier = this.prices.suppliers.find(s => s.distributorId === cell.distributorId)?.name || '';
    this.editError = '';
    // пометку «💡 Цена распознана автоматически, проверьте.» в поле не переносим — «Сохранить» и есть проверка;
    // «Срок…» и «Контекст…» из письма остаются в примечании
    const note = (cell.note || '').replace(AUTO_MARK, '');
    this.editing = { line, cell, supplier, price: cell.price == null ? '' : numText(cell.price), note };
  }

  saveCell() {
    const ed = this.editing;
    if (!ed || ed.cell.priceRequestId == null || ed.cell.itemId == null) return;
    const price = parseNum(ed.price);
    if (ed.price.trim() && (price == null || price < 0)) {
      this.editError = 'Цена — число не меньше нуля';
      return;
    }
    this.busy = true;
    this.api.updatePriceRequestResponses(ed.cell.priceRequestId,
      [{ itemId: ed.cell.itemId, responsePrice: price, responseNote: ed.note.trim() || null }])
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: () => { this.busy = false; this.editing = null; this.notify.success('Ответ сохранён'); this.load(); this.changed.emit(); },
        error: e => { this.busy = false; this.editError = errorText(e); this.cdr.markForCheck(); },
      });
  }

  resend() {
    const ed = this.editing;
    if (!ed || ed.cell.priceRequestId == null) return;
    this.busy = true;
    this.api.resendPriceRequest(ed.cell.priceRequestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (r: any) => {
        this.busy = false;
        if (r?.emailSent) this.notify.success('Письмо отправлено повторно');
        else this.notify.error(r?.reason === 'NO_EMAIL' ? 'У поставщика нет почты — письмо не ушло' : 'Письмо не ушло — ошибка почты');
        this.editing = null;
        this.load();
      },
      error: e => { this.busy = false; this.editError = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
