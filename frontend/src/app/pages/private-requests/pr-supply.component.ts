import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { PrView, SendResult, Sourcing, plural } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { PrRfqDialogComponent } from './pr-rfq-dialog.component';
import { PrPricesComponent } from './pr-prices.component';

/**
 * Итог отправки (спека §6.2): «Отправлено 2 из 3; «Жетысу-Мед» — без письма (нет почты)». «Передам сам» — не письмо и
 * не сбой: в счёт писем не входит, отдельной частью «передадите сами: «…»».
 */
export function rfqToast(results: SendResult[]): string {
  const letters = results.filter(r => r.reason !== 'MANUAL');
  // название поставщика обычно уже в кавычках («ООО Концерн «Аксион»») — свои не добавляем
  const byHand = results.filter(r => r.reason === 'MANUAL').map(r => r.distributorName);
  const parts = letters.length ? [`Отправлено ${letters.filter(r => r.emailSent).length} из ${letters.length}`] : [];
  for (const r of letters) {
    if (r.emailSent) continue;
    parts.push(`«${r.distributorName}» — ` + (r.reason === 'NO_EMAIL' ? 'без письма (нет почты)' : 'письмо не ушло (ошибка почты)'));
  }
  if (byHand.length) parts.push((letters.length ? 'передадите сами: ' : 'Записано, передадите сами: ') + byHand.join(', '));
  return parts.join('; ');
}

/**
 * «Поставщики и цены» (спека 2026-10-12 §6–§7): сводка подбора, «Запросить цены…», таблица цен (задача 15).
 * После отправки сообщает странице — этап заявки меняется.
 */
@Component({
  selector: 'app-pr-supply',
  standalone: true,
  imports: [NgIf, PrRfqDialogComponent, PrPricesComponent],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Поставщики и цены</h3>
        <button type="button" class="btn btn-primary" *ngIf="canRequest()" (click)="openRfq()">Запросить цены…</button>
      </div>
      <p class="summary" *ngIf="view.lines.length && sourcing">{{ summary() }}</p>
      <p class="hint" *ngIf="!view.lines.length">Добавьте позиции — здесь появятся поставщики, у которых их можно запросить.</p>
      <p class="hint warn" *ngIf="sourcingError">{{ sourcingError }}</p>
      <app-pr-prices [requestId]="view.id" [version]="pricesVersion" [readonly]="readonly"
                     (changed)="changed.emit()"></app-pr-prices>
    </section>
    <app-pr-rfq-dialog *ngIf="rfqOpen" [requestId]="view.id" [lines]="view.lines" [clientName]="clientName()"
                       (close)="rfqOpen = false" (sent)="onSent($event)"></app-pr-rfq-dialog>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    .summary { margin: 8px 0 0; font-size: 13px; color: var(--text); }
    .hint { margin: 8px 0 0; font-size: 12px; color: var(--text-muted); }
    .hint.warn { color: var(--warn-text); }
  `],
})
export class PrSupplyComponent implements OnChanges {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  sourcing: Sourcing | null = null;
  sourcingError = '';
  rfqOpen = false;
  /** Растёт после каждой отправки и правки ответа — таблица цен перечитывается. */
  pricesVersion = 0;
  private linesKey = '';
  private sub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view']) return;
    // подбор зависит от позиций (наименование, бренд), а не от контакта — перечитываем только при их изменении
    const key = this.view.id + '|' + this.view.lines.map(l => `${l.lotId}:${l.brand || ''}:${l.name}`).join(';');
    if (key !== this.linesKey) {
      this.linesKey = key;
      this.loadSourcing();
    }
  }

  canRequest(): boolean {
    return !this.readonly && this.view.status === 'NEW' && this.view.lines.length > 0;
  }

  openRfq() {
    if (this.canRequest()) this.rfqOpen = true;
  }

  /** Клиент для поставщика — учреждение или название; контакт и телефон поставщику не раскрываются (как на сервере). */
  clientName(): string | null {
    const v = this.view;
    return (v.facilityName && v.facilityName.trim()) || (v.customerName && v.customerName.trim()) || null;
  }

  private loadSourcing() {
    this.sub?.unsubscribe();
    if (!this.view.lines.length) {
      this.sourcing = null;
      return;
    }
    this.sub = this.api.getPrivateRequestSourcing(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { this.sourcing = s; this.sourcingError = ''; this.cdr.markForCheck(); },
      error: e => { this.sourcing = null; this.sourcingError = 'Подбор поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  summary(): string {
    const s = this.sourcing;
    if (!s) return '';
    if (!s.suppliers.length) return 'Подходящих поставщиков в справочнике нет — добавьте поставщика в окне запроса цен.';
    const matched = new Set<number>();
    s.suppliers.forEach(x => x.lines.forEach(l => matched.add(l.lotId)));
    const n = s.suppliers.length;
    const k = this.view.lines.length;
    let text = `Подходят ${n} ${plural(n, 'поставщик', 'поставщика', 'поставщиков')} для ${matched.size} из ${k} `
      + plural(k, 'позиции', 'позиций', 'позиций');
    if (s.unmatched.length) {
      const names = s.unmatched.map(u => u.name);
      text += ' · без поставщика: ' + names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
    }
    return text;
  }

  onSent(results: SendResult[]) {
    this.rfqOpen = false;
    // «без письма» оператор выбрал сам (предупреждение в окне) — красным только сбой отправки
    if (results.some(r => r.reason === 'SEND_FAILED')) this.notify.error(rfqToast(results));
    else this.notify.success(rfqToast(results));
    this.pricesVersion++;
    this.loadSourcing();
    this.changed.emit();
  }
}
