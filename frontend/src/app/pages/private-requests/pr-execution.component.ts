import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, Input, Output, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { nextSaveSeq } from '../../shared/private-request';
import type { PrView, SaveState } from '../../shared/private-request';
import { errorText, retriable } from '../../shared/error-text';
import { dateText, numText, parseNum } from '../../shared/client-offer';
import { marketToday } from '../../shared/deadline';
import type { DealAction } from '../../shared/deal';
import { execMoney, filesAt } from '../../shared/deal-execution';
import type { DealExecution, ExecEntry, ExecField, ExecFileTarget, ExecMark, ExecPayment } from '../../shared/deal-execution';
import { PrExecEntryFormComponent } from './pr-exec-entry-form.component';
import { PrExecFilesComponent } from './pr-exec-files.component';
import { PrExecFileDialogComponent } from './pr-exec-file-dialog.component';
import { PrExecSuppliersComponent } from './pr-exec-suppliers.component';

/**
 * «Исполнение сделки» на странице заявки (спека 2026-10-19-deal-execution §4): сумма сделки, договор с предоплатой,
 * платежи клиента, поставщики (задача 11), поставка, закрывающие документы, файлы к каждому месту. Поля договора
 * сохраняются по выходу из поля (индикатор страницы), формы — кнопкой; ответ — новый вид заявки (этапы, «Дальше»).
 */
@Component({
  selector: 'app-pr-execution',
  standalone: true,
  imports: [NgIf, NgFor, PrExecEntryFormComponent, PrExecFilesComponent, PrExecFileDialogComponent, PrExecSuppliersComponent],
  template: `
    <section class="card" *ngIf="view.execution as e">
      <div class="sec-head">
        <h3>Исполнение сделки</h3>
        <span class="sum" *ngIf="!amountEdit">
          <ng-container *ngIf="e.amount != null; else noAmount">
            <span *ngIf="e.amountFromOffer && e.offerLabel">по {{ e.offerLabel }} · </span><b>{{ money(e.amount, e.currency) }}</b>
            <span class="muted" *ngIf="!e.amountFromOffer && e.offerAmount != null"> (по КП — {{ money(e.offerAmount) }})</span>
          </ng-container>
          <ng-template #noAmount><span class="warn">сумма сделки не указана</span></ng-template>
          <button type="button" class="icon" *ngIf="!readonly" (click)="amountEdit = true" aria-label="Изменить сумму сделки">✎</button>
        </span>
        <span class="inline-form" *ngIf="amountEdit">
          <input #amountIn [value]="numText(e.amount)" inputmode="decimal" aria-label="Сумма сделки"
                 (keydown.enter)="saveAmount(amountIn.value)" (keydown.escape)="amountEdit = false" />
          <button type="button" class="btn btn-primary" (click)="saveAmount(amountIn.value)">Сохранить</button>
          <button type="button" class="btn btn-line" *ngIf="!e.amountFromOffer && e.offerAmount != null" (click)="saveAmount('')">Вернуть по КП</button>
          <button type="button" class="btn btn-line" (click)="amountEdit = false">Отмена</button>
        </span>
      </div>

      <div class="blk" #contractBlk>
        <div class="blk-head">
          <h4>Договор</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'CONTRACT')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'CONTRACT', title: 'договор' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="!contractEdit">
          <span [class.done]="contractDone(e)">{{ contractText(e) }}</span>
          <button type="button" class="icon" *ngIf="!readonly" (click)="contractEdit = true" aria-label="Изменить договор">✎</button>
        </div>
        <div class="grid" *ngIf="contractEdit">
          <label>Вид
            <select (change)="field('CONTRACT_KIND', $any($event.target).value)">
              <option value="CONTRACT" [selected]="e.contractKind !== 'INVOICE' && e.contractKind !== 'NONE'">Договор</option>
              <option value="INVOICE" [selected]="e.contractKind === 'INVOICE'">Счёт</option>
              <option value="NONE" [selected]="e.contractKind === 'NONE'">Без договора</option>
            </select></label>
          <ng-container *ngIf="e.contractKind !== 'NONE'">
            <label>№
              <input [value]="e.contractNumber || ''" maxlength="100" (blur)="commit('CONTRACT_NUMBER', $event, e.contractNumber)"
                     (keydown.enter)="blur($event)" /></label>
            <label>Дата
              <input type="date" [max]="today" [value]="e.contractDate || ''" (change)="commit('CONTRACT_DATE', $event, e.contractDate)" /></label>
          </ng-container>
          <label>Предоплата, %
            <input [value]="pctText(e.prepayPct)" inputmode="decimal" (blur)="commit('PREPAY_PCT', $event, pctText(e.prepayPct))"
                   (keydown.enter)="blur($event)" />
            <small>0 — оплата после поставки</small></label>
          <label class="wide">Заметка
            <input [value]="e.contractNote || ''" maxlength="2000" (blur)="commit('CONTRACT_NOTE', $event, e.contractNote)"
                   (keydown.enter)="blur($event)" /></label>
          <span class="wide"><button type="button" class="btn btn-line" (click)="contractEdit = false">Готово</button></span>
        </div>
      </div>

      <div class="blk" #paymentBlk>
        <div class="blk-head">
          <h4>Оплата клиента</h4>
          <span class="muted">{{ paidText(e) }}</span>
        </div>
        <div class="pay" *ngFor="let p of e.clientPayments">
          <ng-container *ngIf="editPayment !== p.id; else payEdit">
            <span class="d">{{ dmy(p.paidOn) }}</span>
            <span class="amt">{{ money(p.amount, e.currency) }}</span>
            <span class="note" *ngIf="p.note">{{ p.note }}</span>
            <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'CLIENT_PAYMENT', p.id)" [readonly]="readonly"
                               (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
            <span class="acts" *ngIf="!readonly">
              <button type="button" class="link" (click)="attach({ stage: 'CLIENT_PAYMENT', paymentId: p.id, title: 'оплата ' + dmy(p.paidOn) })"
                      aria-label="Приложить файл к платежу">📎</button>
              <button type="button" class="link" (click)="editPayment = p.id" aria-label="Изменить платёж">✎</button>
              <button type="button" class="link danger" [disabled]="busy" (click)="removePayment(p, e.currency)" aria-label="Удалить платёж">×</button>
            </span>
          </ng-container>
          <ng-template #payEdit>
            <app-pr-exec-entry-form [withAmount]="true" [currency]="e.currency" [busy]="busy"
                                    [initial]="{ date: p.paidOn, amount: p.amount, note: p.note }"
                                    (save)="updatePayment(p, $event)" (cancel)="editPayment = null"></app-pr-exec-entry-form>
          </ng-template>
        </div>
        <app-pr-exec-entry-form *ngIf="addPayment" [withAmount]="true" [currency]="e.currency" [busy]="busy"
                                [initial]="{ amount: suggestClient(e) }" saveLabel="Внести платёж"
                                (save)="addClientPayment($event)" (cancel)="addPayment = false"></app-pr-exec-entry-form>
        <button type="button" class="btn btn-line add" *ngIf="!readonly && !addPayment" (click)="addPayment = true">+ Платёж</button>
        <span class="muted" *ngIf="readonly && !e.clientPayments.length">платежей нет</span>
      </div>

      <app-pr-exec-suppliers #suppliersPart [view]="view" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)" (attach)="attach($event)"></app-pr-exec-suppliers>

      <div class="blk" #deliveryBlk>
        <div class="blk-head">
          <h4>Поставка клиенту</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'DELIVERY')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'DELIVERY', title: 'поставка' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="e.deliveredOn && !deliveryEdit">
          <span class="done">✓ Поставлено {{ dmy(e.deliveredOn) }}<ng-container *ngIf="e.deliveryNote"> · {{ e.deliveryNote }}</ng-container></span>
          <span class="acts" *ngIf="!readonly">
            <button type="button" class="link" (click)="deliveryEdit = true" aria-label="Изменить отметку поставки">✎</button>
            <button type="button" class="link danger" [disabled]="busy" (click)="mark('DELIVERY', null, null)">Снять отметку</button>
          </span>
        </div>
        <button type="button" class="btn btn-line add" *ngIf="!e.deliveredOn && !deliveryEdit && !readonly" (click)="deliveryEdit = true">Отметить поставку…</button>
        <span class="muted" *ngIf="!e.deliveredOn && readonly">не отмечено</span>
        <app-pr-exec-entry-form *ngIf="deliveryEdit" [busy]="busy" [initial]="{ date: e.deliveredOn || undefined, note: e.deliveryNote }"
                                notePlaceholder="введено в эксплуатацию, обучение проведено"
                                (save)="mark('DELIVERY', $event.date, $event.note)" (cancel)="deliveryEdit = false"></app-pr-exec-entry-form>
      </div>

      <div class="blk" #docsBlk>
        <div class="blk-head">
          <h4>Закрывающие документы</h4>
          <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'DOCS')" [readonly]="readonly"
                             (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
          <button type="button" class="link" *ngIf="!readonly" (click)="attach({ stage: 'DOCS', title: 'закрывающие документы' })">📎 Приложить</button>
        </div>
        <div class="row" *ngIf="e.docsOn && !docsEdit">
          <span class="done">✓ Подписаны {{ dmy(e.docsOn) }}<ng-container *ngIf="e.docsNote"> · {{ e.docsNote }}</ng-container></span>
          <span class="acts" *ngIf="!readonly">
            <button type="button" class="link" (click)="docsEdit = true" aria-label="Изменить отметку документов">✎</button>
            <button type="button" class="link danger" [disabled]="busy" (click)="mark('DOCS', null, null)">Снять отметку</button>
          </span>
        </div>
        <button type="button" class="btn btn-line add" *ngIf="!e.docsOn && !docsEdit && !readonly" (click)="docsEdit = true">Документы подписаны…</button>
        <span class="muted" *ngIf="!e.docsOn && readonly">не отмечено</span>
        <app-pr-exec-entry-form *ngIf="docsEdit" [busy]="busy" [initial]="{ date: e.docsOn || undefined, note: e.docsNote }"
                                notePlaceholder="накладная № 12, акт"
                                (save)="mark('DOCS', $event.date, $event.note)" (cancel)="docsEdit = false"></app-pr-exec-entry-form>
      </div>

      <app-pr-exec-file-dialog *ngIf="fileTarget" [requestId]="view.id" [target]="fileTarget"
                               (close)="fileTarget = null" (viewChange)="viewChange.emit($event)"></app-pr-exec-file-dialog>
    </section>
  `,
  styles: [`
    /* как соседние разделы страницы: карточка, заголовок 15 px */
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px;
            display: flex; flex-direction: column; gap: 12px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    h4 { margin: 0; font-size: 14px; }
    .sum { display: inline-flex; align-items: baseline; gap: 4px; flex-wrap: wrap; }
    .blk { display: flex; flex-direction: column; gap: 6px; padding-top: 10px; border-top: 1px solid var(--border); scroll-margin-top: 12px; }
    .blk-head { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
    .row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .done { color: var(--success-text); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .warn { color: var(--warn-text); }
    .icon, .link { background: none; border: none; padding: 0 4px; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { opacity: .5; cursor: not-allowed; }
    .grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px 12px; align-items: start; }
    .grid .wide { grid-column: 1 / -1; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label small { font-size: 11px; }
    input, select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                    font: inherit; font-size: 14px; }
    .inline-form { display: inline-flex; gap: 6px; flex-wrap: wrap; align-items: center; }
    .inline-form input { width: 160px; }
    .pay { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; padding: 4px 0; }
    .pay .d { color: var(--text-muted); font-size: 13px; min-width: 84px; }
    .pay .amt { font-weight: 600; }
    .pay .note { color: var(--text-muted); font-size: 13px; }
    .acts { display: inline-flex; gap: 2px; margin-left: auto; }
    .add { align-self: flex-start; }
    @media (max-width: 900px) {
      .grid { grid-template-columns: 1fr; }
      input, select { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .icon, .link { min-width: 40px; min-height: 40px; }
      .btn { min-height: 40px; }
      .inline-form { display: flex; width: 100%; }
      .inline-form input { width: 100%; }
    }
  `],
})
export class PrExecutionComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();
  @ViewChild('contractBlk') contractBlk?: ElementRef<HTMLElement>;
  @ViewChild('paymentBlk') paymentBlk?: ElementRef<HTMLElement>;
  @ViewChild('deliveryBlk') deliveryBlk?: ElementRef<HTMLElement>;
  @ViewChild('docsBlk') docsBlk?: ElementRef<HTMLElement>;
  @ViewChild('suppliersPart') suppliersPart?: PrExecSuppliersComponent;

  readonly today: string;
  busy = false;
  amountEdit = false;
  contractEdit = false;
  addPayment = false;
  editPayment: number | null = null;
  deliveryEdit = false;
  docsEdit = false;
  fileTarget: ExecFileTarget | null = null;
  readonly money = execMoney;
  readonly numText = numText;
  readonly dmy = dateText;
  readonly filesAt = filesAt;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef, private host: ElementRef<HTMLElement>,
              market: MarketService) {
    this.today = marketToday(market.value);
  }

  /** Шаг этапов → блок раздела; неизвестный ключ — начало раздела. */
  scrollTo(key: string) {
    if (key === 'SUPPLIERS') { this.suppliersPart?.scroll(); return; }
    const el = key === 'CONTRACT' ? this.contractBlk : key === 'PAYMENT' ? this.paymentBlk
      : key === 'DELIVERY' ? this.deliveryBlk : key === 'DOCS' ? this.docsBlk : null;
    (el?.nativeElement ?? this.host.nativeElement).scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  /** Кнопка «Дальше»: открыть форму своего блока и прокрутить к нему. */
  act(a: DealAction) {
    switch (a.action) {
      case 'EXEC_CONTRACT': this.contractEdit = true; this.scrollTo('CONTRACT'); break;
      case 'EXEC_AMOUNT': this.amountEdit = true; this.scrollTo(''); break;
      case 'EXEC_PAY_CLIENT': this.addPayment = true; this.scrollTo('PAYMENT'); break;
      case 'EXEC_DELIVERY': this.deliveryEdit = true; this.scrollTo('DELIVERY'); break;
      case 'EXEC_DOCS': this.docsEdit = true; this.scrollTo('DOCS'); break;
      default: this.suppliersPart?.act(a); this.suppliersPart?.scroll();
    }
    this.cdr.markForCheck();
  }

  contractDone(e: DealExecution): boolean {
    return e.contractKind !== 'NONE' && (!!e.contractNumber || !!e.contractDate);
  }

  contractText(e: DealExecution): string {
    const pre = ' · предоплата ' + (e.prepayPct > 0 ? this.pctText(e.prepayPct) + ' %' : 'нет — оплата после поставки');
    if (e.contractKind === 'NONE') return 'Без договора' + pre;
    const invoice = e.contractKind === 'INVOICE';
    if (!this.contractDone(e)) return (invoice ? 'Счёт не выставлен' : 'Договора пока нет') + pre;
    return (invoice ? '✓ Счёт' : '✓ Договор') + (e.contractNumber ? ' № ' + e.contractNumber : '')
      + (e.contractDate ? ' от ' + dateText(e.contractDate) : '') + pre;
  }

  pctText(p: number): string {
    return String(p).replace('.', ',');
  }

  paidText(e: DealExecution): string {
    return e.amount != null ? `оплачено ${execMoney(e.clientPaid)} из ${execMoney(e.amount, e.currency)}`
      : `оплачено ${execMoney(e.clientPaid, e.currency)}`;
  }

  /** Сумма нового платежа: остаток предоплаты, иначе остаток суммы сделки. */
  suggestClient(e: DealExecution): number | null {
    if (e.amount == null) return null;
    const pre = e.prepayAmount ?? 0;
    const rest = e.clientPaid < pre ? pre - e.clientPaid : e.amount - e.clientPaid;
    const r = Math.round(rest * 100) / 100;
    return r > 0 ? r : null;
  }

  blur(ev: Event) {
    (ev.target as HTMLElement).blur();
  }

  commit(field: ExecField, ev: Event, current: string | null) {
    const value = (ev.target as HTMLInputElement).value.trim();
    if (value === (current ?? '').trim()) return;
    this.field(field, value || null);
  }

  /** Поле — PATCH, состояние — общий индикатор страницы (как поля клиента). */
  field(field: ExecField, value: string | null) {
    const key = 'exec:' + field, seq = nextSaveSeq();
    this.saveState.emit({ kind: 'saving', key, seq });
    this.api.updateExecutionField(this.view.id, field, value).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.saveState.emit({ kind: 'saved', key, seq }); this.viewChange.emit(v); },
      error: e => {
        this.saveState.emit({ kind: 'error', key, seq, message: errorText(e),
          retry: retriable(e) ? () => this.field(field, value) : undefined });
        this.cdr.markForCheck();
      },
    });
  }

  saveAmount(text: string) {
    const t = text.trim();
    const n = parseNum(t);
    if (t && n == null) {
      this.notify.error('Сумма сделки — число, например 3 070 455');
      return;
    }
    this.amountEdit = false;
    this.field('AMOUNT', n == null ? null : String(n));
  }

  private run(req: Observable<PrView>, done: () => void, ok?: string) {
    if (this.busy) return;
    this.busy = true;
    req.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; done(); if (ok) this.notify.success(ok); this.viewChange.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  addClientPayment(x: ExecEntry) {
    this.run(this.api.addExecutionPayment(this.view.id, { distributorId: null, amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.addPayment = false, 'Платёж внесён');
  }

  updatePayment(p: ExecPayment, x: ExecEntry) {
    this.run(this.api.updateExecutionPayment(this.view.id, p.id, { amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.editPayment = null);
  }

  removePayment(p: ExecPayment, currency: string) {
    this.confirm.ask(`Удалить платёж ${execMoney(p.amount, currency)} от ${dateText(p.paidOn)}?`, 'Файлы платежа удалятся вместе с ним.',
      { danger: true, confirmLabel: 'Удалить' }).pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionPayment(this.view.id, p.id), () => {}); });
  }

  mark(m: ExecMark, on: string | null, note: string | null) {
    this.run(this.api.setExecutionMark(this.view.id, m, on, note), () => {
      if (m === 'DELIVERY') this.deliveryEdit = false; else this.docsEdit = false;
    });
  }

  attach(target: ExecFileTarget) {
    this.fileTarget = target;
  }
}
