import { Component, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { MarketService } from '../services/market.service';
import { marketHint, WhatsappStatus, whatsappStatusLine } from './whatsapp-status';

/**
 * Строка «WhatsApp …» на «Чатах» и «Обращениях» (спека §7). Красная, если что-то мешает приёму. Под ней —
 * подсказка, если выбран не тот рынок: иначе пустой список при «подключён» выглядит как поломка.
 */
@Component({
  selector: 'app-whatsapp-status-line',
  standalone: true,
  imports: [NgIf],
  template: `
    <div class="wa-plate" *ngIf="line as l" [class.is-error]="l.error">{{ l.text }}</div>
    <div class="wa-plate is-hint" *ngIf="hint as h" role="note">{{ h }}</div>
  `,
  styles: [`
    .wa-plate { font-size: 13px; color: var(--text-muted); background: var(--surface-2); border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
    .wa-plate.is-error { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .wa-plate.is-hint { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
  `],
})
export class WhatsappStatusLineComponent {
  @Input() status: WhatsappStatus | null = null;

  constructor(private market: MarketService) {}

  get line() { return whatsappStatusLine(this.status); }
  get hint() { return marketHint(this.status, this.market.value); }
}
