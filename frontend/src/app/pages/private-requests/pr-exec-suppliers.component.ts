import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable, switchMap } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { plural } from '../../shared/private-request';
import type { PrView } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { dateText, numText, parseNum } from '../../shared/client-offer';
import type { DealAction } from '../../shared/deal';
import { EXEC_CURRENCIES, execMoney, filesAt } from '../../shared/deal-execution';
import type { DealExecution, ExecEntry, ExecFileTarget, ExecPayment, ExecSupplier, SupplierMark } from '../../shared/deal-execution';
import { PrExecEntryFormComponent } from './pr-exec-entry-form.component';
import { PrExecFilesComponent } from './pr-exec-files.component';

interface DirEntry { id: number; name: string; }

/**
 * Поставщики сделки (спека 2026-10-19-deal-execution §4.3): из строк принятого КП и добавленные вручную; у каждого —
 * заказ, платежи (в его валюте) и отгрузка; «Сумма и валюта…», «Не заказываем», «Со склада».
 */
@Component({
  selector: 'app-pr-exec-suppliers',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, PrExecEntryFormComponent, PrExecFilesComponent],
  template: `
    <div class="blk" #blk *ngIf="view.execution as e">
      <div class="blk-head">
        <h4>Поставщики</h4>
        <span class="muted">{{ summary(e) }}</span>
      </div>
      <label class="check" *ngIf="!readonly || e.fromStock">
        <input type="checkbox" [checked]="e.fromStock" [disabled]="readonly || busy" (change)="setStock($any($event.target))" />
        Со склада — заказывать не нужно
      </label>
      <ng-container *ngIf="!e.fromStock">
        <div class="sup" *ngFor="let s of e.suppliers; trackBy: track" [class.off]="s.excluded">
          <div class="sup-head">
            <b class="sup-name">{{ s.name }}</b>
            <span class="muted">{{ amountText(s) }}</span>
            <span class="tag" *ngIf="s.excluded">не заказываем</span>
            <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'SUPPLIER', null, s.distributorId)" [readonly]="readonly"
                               (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
            <span class="menu-wrap" *ngIf="!readonly" (click)="$event.stopPropagation()">
              <button type="button" class="btn btn-more" (click)="menuFor = menuFor === s.distributorId ? null : s.distributorId"
                      [attr.aria-expanded]="menuFor === s.distributorId" [attr.aria-label]="'Действия: ' + s.name">⋯</button>
              <span class="row-menu" *ngIf="menuFor === s.distributorId">
                <button type="button" (click)="editAmount(s)">Сумма и валюта…</button>
                <button type="button" (click)="fileFor(s, null)">Приложить файл…</button>
                <button type="button" *ngIf="s.fromOffer && !s.excluded" (click)="exclude(s, true)">Не заказываем</button>
                <button type="button" *ngIf="s.excluded" (click)="exclude(s, false)">Вернуть в заказ</button>
                <button type="button" class="danger" *ngIf="s.manual" (click)="remove(s)">Удалить</button>
              </span>
            </span>
          </div>
          <div class="inline-form" *ngIf="amountFor === s.distributorId">
            <input #amt [value]="numText(s.amount)" inputmode="decimal" aria-label="Сумма к оплате"
                   (keydown.enter)="saveAmount(s, amt.value, cur.value)" (keydown.escape)="amountFor = null" />
            <select #cur aria-label="Валюта">
              <option *ngFor="let c of currencies" [value]="c.v" [selected]="c.v === s.currency">{{ c.l }}</option>
            </select>
            <button type="button" class="btn btn-primary" [disabled]="busy" (click)="saveAmount(s, amt.value, cur.value)">Сохранить</button>
            <button type="button" class="btn btn-line" *ngIf="!s.amountFromOffer && s.offerAmount != null" [disabled]="busy"
                    (click)="saveAmount(s, '', s.currency)">По КП</button>
            <button type="button" class="btn btn-line" (click)="amountFor = null">Отмена</button>
          </div>
          <div class="marks" *ngIf="!s.excluded">
            <span class="mark" [class.done]="!!s.orderedOn">
              <ng-container *ngIf="s.orderedOn; else noOrder">✓ Заказ {{ dmy(s.orderedOn) }}<ng-container *ngIf="s.orderNote"> · {{ s.orderNote }}</ng-container>
                <ng-container *ngIf="!readonly">
                  <button type="button" class="link" (click)="openMark(s, 'ORDER')" aria-label="Изменить отметку заказа">✎</button>
                  <button type="button" class="link danger" [disabled]="busy" (click)="clearMark(s, 'ORDER')">Снять</button>
                </ng-container>
              </ng-container>
              <ng-template #noOrder>○ Заказ
                <button type="button" class="link" *ngIf="!readonly" (click)="openMark(s, 'ORDER')">Отметить заказ…</button>
              </ng-template>
            </span>
            <span class="mark" [class.done]="paidUp(s)">{{ paidUp(s) ? '✓' : '○' }} {{ paidText(s) }}
              <button type="button" class="link" *ngIf="!readonly && !paidUp(s)" (click)="openPay(s)">+ Платёж</button>
            </span>
            <span class="mark" [class.done]="!!s.shippedOn">
              <ng-container *ngIf="s.shippedOn; else noShip">✓ Отгрузка {{ dmy(s.shippedOn) }}<ng-container *ngIf="s.shipNote"> · {{ s.shipNote }}</ng-container>
                <ng-container *ngIf="!readonly">
                  <button type="button" class="link" (click)="openMark(s, 'SHIP')" aria-label="Изменить отметку отгрузки">✎</button>
                  <button type="button" class="link danger" [disabled]="busy" (click)="clearMark(s, 'SHIP')">Снять</button>
                </ng-container>
              </ng-container>
              <ng-template #noShip>○ Отгрузка
                <button type="button" class="link" *ngIf="!readonly" (click)="openMark(s, 'SHIP')">Отгрузил…</button>
              </ng-template>
            </span>
          </div>
          <div class="pay" *ngFor="let p of s.payments">
            <ng-container *ngIf="editPay !== p.id; else payEdit">
              <span class="d">{{ dmy(p.paidOn) }}</span>
              <span class="amt">{{ money(p.amount, s.currency) }}</span>
              <span class="note" *ngIf="p.note">{{ p.note }}</span>
              <app-pr-exec-files [requestId]="view.id" [files]="filesAt(e, 'SUPPLIER', p.id, s.distributorId)" [readonly]="readonly"
                                 (viewChange)="viewChange.emit($event)"></app-pr-exec-files>
              <span class="acts" *ngIf="!readonly">
                <button type="button" class="link" (click)="fileFor(s, p)" aria-label="Приложить файл к платежу">📎</button>
                <button type="button" class="link" (click)="editPay = p.id" aria-label="Изменить платёж">✎</button>
                <button type="button" class="link danger" [disabled]="busy" (click)="removePay(s, p)" aria-label="Удалить платёж">×</button>
              </span>
            </ng-container>
            <ng-template #payEdit>
              <app-pr-exec-entry-form [withAmount]="true" [currency]="s.currency" [busy]="busy"
                                      [initial]="{ date: p.paidOn, amount: p.amount, note: p.note }"
                                      (save)="updatePay(p, $event)" (cancel)="editPay = null"></app-pr-exec-entry-form>
            </ng-template>
          </div>
          <app-pr-exec-entry-form *ngIf="markFor?.d === s.distributorId" [busy]="busy"
                                  [initial]="{ date: (markFor?.m === 'ORDER' ? s.orderedOn : s.shippedOn) || undefined,
                                               note: markFor?.m === 'ORDER' ? s.orderNote : s.shipNote }"
                                  [notePlaceholder]="markFor?.m === 'ORDER' ? '№ заказа' : 'накладная, трек'"
                                  (save)="saveMark(s, $event)" (cancel)="markFor = null"></app-pr-exec-entry-form>
          <app-pr-exec-entry-form *ngIf="payFor === s.distributorId" [withAmount]="true" [currency]="s.currency" [busy]="busy"
                                  [initial]="{ amount: restOf(s) }" saveLabel="Внести платёж"
                                  (save)="addPay(s, $event)" (cancel)="payFor = null"></app-pr-exec-entry-form>
        </div>
        <p class="muted" *ngIf="e.unassigned as u">В КП без поставщика: {{ u.count }} {{ positions(u.count) }}<ng-container
          *ngIf="u.purchase != null"> (закупка {{ money(u.purchase, e.currency) }})</ng-container> — добавьте поставщика вручную.</p>
        <p class="muted" *ngIf="!e.suppliers.length">Поставщиков нет — добавьте или отметьте «Со склада».</p>
        <div class="add-sup" *ngIf="!readonly">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">+ Поставщик</button>
          <div class="inline-form" *ngIf="adding">
            <span class="picker">
              <input type="search" [(ngModel)]="addQuery" (ngModelChange)="addPicked = null" placeholder="Поставщик из справочника"
                     aria-label="Поставщик" autocomplete="off" />
              <span class="opts" *ngIf="addQuery.trim() && !addPicked && options().length">
                <button type="button" *ngFor="let d of options()" (click)="addPicked = d; addQuery = d.name">{{ d.name }}</button>
              </span>
            </span>
            <input [(ngModel)]="addAmount" inputmode="decimal" placeholder="К оплате" aria-label="Сумма к оплате" />
            <select [(ngModel)]="addCurrency" aria-label="Валюта">
              <option *ngFor="let c of currencies" [ngValue]="c.v">{{ c.l }}</option>
            </select>
            <button type="button" class="btn btn-primary" [disabled]="busy || !addPicked" (click)="add()">Добавить</button>
            <button type="button" class="btn btn-line" (click)="adding = false">Отмена</button>
          </div>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .blk { display: flex; flex-direction: column; gap: 8px; padding-top: 10px; border-top: 1px solid var(--border); scroll-margin-top: 12px; }
    .blk-head { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
    h4 { margin: 0; font-size: 14px; }
    .muted { color: var(--text-muted); font-size: 13px; margin: 0; }
    .check { display: flex; align-items: center; gap: 8px; font-size: 14px; }
    .sup { border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
    .sup.off { opacity: .7; }
    .sup-head { display: flex; align-items: center; gap: 6px 10px; flex-wrap: wrap; }
    .sup-name { font-weight: 600; }
    .tag { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text); }
    .menu-wrap { position: relative; margin-left: auto; }
    .marks { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; }
    .mark { color: var(--text-muted); display: inline-flex; align-items: center; gap: 4px; flex-wrap: wrap; }
    .mark.done { color: var(--success-text); }
    .link { background: none; border: none; padding: 0 4px; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { opacity: .5; cursor: not-allowed; }
    .pay { display: flex; align-items: center; gap: 6px 12px; flex-wrap: wrap; font-size: 13px; }
    .pay .d { color: var(--text-muted); min-width: 84px; }
    .pay .amt { font-weight: 600; }
    .pay .note { color: var(--text-muted); }
    .acts { display: inline-flex; gap: 2px; margin-left: auto; }
    .inline-form { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
    input, select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                    font: inherit; font-size: 14px; }
    .picker { position: relative; }
    .opts { position: absolute; z-index: 20; left: 0; right: 0; top: 100%; margin-top: 4px; display: flex; flex-direction: column;
            background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
            max-height: 240px; overflow-y: auto; min-width: 240px; }
    .opts button { background: none; border: none; text-align: left; padding: 8px 12px; cursor: pointer; color: var(--text); font: inherit; }
    .opts button:hover { background: var(--surface-2); }
    @media (max-width: 900px) {
      .marks { flex-direction: column; gap: 4px; }
      .link, .btn-more { min-width: 40px; min-height: 40px; }
      .btn { min-height: 40px; }
      input, select { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .inline-form { flex-direction: column; align-items: stretch; }
      .check { min-height: 40px; }
    }
  `],
})
export class PrExecSuppliersComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  /** Приложить файл к поставщику или его платежу — окно у родителя. */
  @Output() attach = new EventEmitter<ExecFileTarget>();
  @ViewChild('blk') blk?: ElementRef<HTMLElement>;

  busy = false;
  menuFor: number | null = null;
  amountFor: number | null = null;
  markFor: { d: number; m: SupplierMark } | null = null;
  payFor: number | null = null;
  editPay: number | null = null;
  adding = false;
  addQuery = '';
  addPicked: DirEntry | null = null;
  addAmount = '';
  addCurrency: string;
  private directory: DirEntry[] = [];
  readonly currencies = EXEC_CURRENCIES;
  readonly money = execMoney;
  readonly numText = numText;
  readonly dmy = dateText;
  readonly filesAt = filesAt;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef, market: MarketService) {
    this.addCurrency = market.value === 'RF' ? 'RUB' : 'KZT';
  }

  @HostListener('document:click')
  closeMenu() {
    this.menuFor = null;
  }

  track(_: number, s: ExecSupplier) {
    return s.distributorId;
  }

  summary(e: DealExecution): string {
    if (e.fromStock) return 'со склада';
    const c = e.suppliers.filter(s => !s.excluded);
    if (!c.length) return '';
    const n = c.length;
    const ordered = c.filter(s => s.orderedOn).length, paid = c.filter(s => this.paidUp(s)).length;
    const shipped = c.filter(s => s.shippedOn).length;
    return `заказ ${ordered}/${n} · оплата ${paid}/${n} · отгрузка ${shipped}/${n}`;
  }

  paidUp(s: ExecSupplier): boolean {
    return s.amount != null && s.amount > 0 && s.paid >= s.amount;
  }

  paidText(s: ExecSupplier): string {
    if (s.amount != null) return `Оплачено ${execMoney(s.paid)} из ${execMoney(s.amount, s.currency)}`;
    return s.paid > 0 ? `Оплачено ${execMoney(s.paid, s.currency)} · сумма к оплате не указана` : 'Оплата · сумма к оплате не указана';
  }

  amountText(s: ExecSupplier): string {
    if (s.amount == null) return 'к оплате — впишите сумму';
    return 'к оплате ' + execMoney(s.amount, s.currency) + (s.amountFromOffer ? ' (по КП)' : '');
  }

  positions(n: number): string {
    return plural(n, 'позиция', 'позиции', 'позиций');
  }

  restOf(s: ExecSupplier): number | null {
    if (s.amount == null) return null;
    const rest = Math.round((s.amount - s.paid) * 100) / 100;
    return rest > 0 ? rest : null;
  }

  private run(req: Observable<PrView>, done: () => void, ok?: string, failed?: () => void) {
    if (this.busy) return;
    this.busy = true;
    req.pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; done(); if (ok) this.notify.success(ok); this.viewChange.emit(v); },
      error: e => { this.busy = false; failed?.(); this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  /** Отказ сервера — флажок назад: привязка [checked] не изменилась, сама она DOM не вернёт. */
  setStock(box: HTMLInputElement) {
    const on = box.checked;
    this.run(this.api.updateExecutionField(this.view.id, 'FROM_STOCK', String(on)), () => {}, undefined, () => box.checked = !on);
  }

  exclude(s: ExecSupplier, on: boolean) {
    this.menuFor = null;
    this.run(this.api.updateExecutionSupplier(this.view.id, s.distributorId, 'EXCLUDED', String(on)), () => {});
  }

  editAmount(s: ExecSupplier) {
    this.menuFor = null;
    this.amountFor = s.distributorId;
  }

  /** Валюта (если сменилась) — сначала, потом сумма; пустая сумма — снова по КП. */
  saveAmount(s: ExecSupplier, amountText: string, currency: string) {
    const t = amountText.trim();
    const n = parseNum(t);
    if (t && n == null) {
      this.notify.error('Сумма к оплате — число');
      return;
    }
    const id = this.view.id, d = s.distributorId, amount = n == null ? null : String(n);
    const req = currency !== s.currency
      ? this.api.updateExecutionSupplier(id, d, 'CURRENCY', currency).pipe(switchMap(() => this.api.updateExecutionSupplier(id, d, 'AMOUNT', amount)))
      : this.api.updateExecutionSupplier(id, d, 'AMOUNT', amount);
    this.run(req, () => this.amountFor = null);
  }

  openMark(s: ExecSupplier, m: SupplierMark) {
    this.markFor = { d: s.distributorId, m };
    this.payFor = null;
  }

  saveMark(s: ExecSupplier, x: ExecEntry) {
    const m = this.markFor?.m;
    if (!m) return;
    this.run(this.api.setExecutionSupplierMark(this.view.id, s.distributorId, m, x.date, x.note), () => this.markFor = null);
  }

  clearMark(s: ExecSupplier, m: SupplierMark) {
    this.run(this.api.setExecutionSupplierMark(this.view.id, s.distributorId, m, null, null), () => {});
  }

  openPay(s: ExecSupplier) {
    this.payFor = s.distributorId;
    this.markFor = null;
  }

  addPay(s: ExecSupplier, x: ExecEntry) {
    this.run(this.api.addExecutionPayment(this.view.id, { distributorId: s.distributorId, amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.payFor = null, 'Платёж поставщику внесён');
  }

  updatePay(p: ExecPayment, x: ExecEntry) {
    this.run(this.api.updateExecutionPayment(this.view.id, p.id, { amount: x.amount!, paidOn: x.date, note: x.note }),
      () => this.editPay = null);
  }

  removePay(s: ExecSupplier, p: ExecPayment) {
    this.confirm.ask(`Удалить платёж ${s.name} ${execMoney(p.amount, s.currency)} от ${dateText(p.paidOn)}?`,
      'Файлы платежа удалятся вместе с ним.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionPayment(this.view.id, p.id), () => {}); });
  }

  remove(s: ExecSupplier) {
    this.menuFor = null;
    this.confirm.ask(`Удалить «${s.name}» из сделки?`, 'Его платежи и файлы удалятся вместе с ним.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.run(this.api.deleteExecutionSupplier(this.view.id, s.distributorId), () => {}); });
  }

  fileFor(s: ExecSupplier, p: ExecPayment | null) {
    this.menuFor = null;
    this.attach.emit({ stage: 'SUPPLIER', distributorId: s.distributorId, paymentId: p?.id ?? null,
      title: p ? `оплата ${s.name} ${dateText(p.paidOn)}` : s.name });
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    this.addPicked = null;
    this.addAmount = '';
    if (this.directory.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => { this.directory = (list || []).map((d: any) => ({ id: d.id, name: d.name })); this.cdr.markForCheck(); },
      error: e => this.notify.error('Справочник поставщиков не загрузился: ' + errorText(e)),
    });
  }

  options(): DirEntry[] {
    const taken = new Set((this.view.execution?.suppliers ?? []).map(s => s.distributorId));
    const q = this.addQuery.trim().toLowerCase();
    return this.directory.filter(d => !taken.has(d.id) && d.name.toLowerCase().includes(q)).slice(0, 8);
  }

  add() {
    const d = this.addPicked;
    if (!d) return;
    const n = parseNum(this.addAmount);
    this.run(this.api.addExecutionSupplier(this.view.id, { distributorId: d.id, amount: n, currency: this.addCurrency }),
      () => { this.adding = false; this.addPicked = null; this.addQuery = ''; this.addAmount = ''; }, `«${d.name}» добавлен`);
  }

  /** Кнопка «Дальше» по поставщикам: открыть нужную форму у своего поставщика. */
  act(a: DealAction) {
    const s = this.view.execution?.suppliers.find(x => x.distributorId === a.refId) ?? null;
    switch (a.action) {
      case 'EXEC_SUPPLIERS': if (!this.readonly) this.startAdd(); break;
      case 'EXEC_ORDER': if (s) this.openMark(s, 'ORDER'); break;
      case 'EXEC_SUPPLIER_AMOUNT': if (s) this.editAmount(s); break;
      case 'EXEC_PAY_SUPPLIER': if (s) this.openPay(s); break;
      case 'EXEC_SHIP': if (s) this.openMark(s, 'SHIP'); break;
    }
    this.cdr.markForCheck();
  }

  scroll() {
    this.blk?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }
}
