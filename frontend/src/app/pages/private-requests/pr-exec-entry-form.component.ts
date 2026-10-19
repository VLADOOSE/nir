import { Component, EventEmitter, Input, OnInit, Output } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { MarketService } from '../../services/market.service';
import { marketToday } from '../../shared/deadline';
import { numText, parseNum } from '../../shared/client-offer';
import { execCurrency } from '../../shared/deal-execution';
import type { ExecEntry } from '../../shared/deal-execution';

/** Отметка (дата и заметка) или платёж (дата, сумма, заметка) — строкой в блоке раздела «Исполнение». */
@Component({
  selector: 'app-pr-exec-entry-form',
  standalone: true,
  imports: [NgIf, FormsModule],
  template: `
    <form class="entry" (ngSubmit)="submit()" (keydown.escape)="cancel.emit()">
      <label>Дата
        <input type="date" name="date" [max]="today" [(ngModel)]="date" required /></label>
      <label *ngIf="withAmount">Сумма{{ currency ? ', ' + label(currency) : '' }}
        <input name="amount" inputmode="decimal" [(ngModel)]="amountText" autocomplete="off" /></label>
      <label class="note">Заметка
        <input name="note" [(ngModel)]="note" maxlength="2000" [placeholder]="notePlaceholder" autocomplete="off" /></label>
      <span class="acts">
        <button type="submit" class="btn btn-primary" [disabled]="busy || !ready()">{{ busy ? 'Сохраняю…' : saveLabel }}</button>
        <button type="button" class="btn btn-line" [disabled]="busy" (click)="cancel.emit()">Отмена</button>
      </span>
    </form>
  `,
  styles: [`
    .entry { display: flex; flex-wrap: wrap; gap: 8px 10px; align-items: flex-end; padding: 8px 10px; border: 1px solid var(--border);
             border-radius: 8px; background: color-mix(in srgb, var(--accent) 6%, var(--surface)); }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.note { flex: 1; min-width: 180px; }
    input { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
            font: inherit; font-size: 14px; }
    .acts { display: flex; gap: 6px; }
    @media (max-width: 900px) {
      .entry { flex-direction: column; align-items: stretch; }
      input { font-size: 16px; min-height: 40px; box-sizing: border-box; }
      .acts .btn { flex: 1; min-height: 40px; }
    }
  `],
})
export class PrExecEntryFormComponent implements OnInit {
  @Input() withAmount = false;
  @Input() currency: string | null = null;
  /** Начальные значения: дата (по умолчанию — сегодня по часам рынка), сумма, заметка. */
  @Input() initial: Partial<ExecEntry> | null = null;
  @Input() saveLabel = 'Сохранить';
  @Input() notePlaceholder = '';
  @Input() busy = false;
  @Output() save = new EventEmitter<ExecEntry>();
  @Output() cancel = new EventEmitter<void>();

  readonly today: string;
  date = '';
  amountText = '';
  note = '';

  constructor(market: MarketService) {
    this.today = marketToday(market.value);
  }

  ngOnInit() {
    this.date = this.initial?.date || this.today;
    this.amountText = numText(this.initial?.amount ?? null);
    this.note = this.initial?.note || '';
  }

  label(c: string): string { return execCurrency(c); }

  ready(): boolean {
    return !!this.date && (!this.withAmount || (parseNum(this.amountText) ?? 0) > 0);
  }

  submit() {
    if (this.busy || !this.ready()) return;
    this.save.emit({ date: this.date, amount: this.withAmount ? parseNum(this.amountText) : null, note: this.note.trim() || null });
  }
}
