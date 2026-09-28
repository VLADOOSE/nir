import { Component, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { WhatsappStatus, whatsappStatusLine } from './whatsapp-status';

/** Строка «WhatsApp …» на «Чатах» и «Обращениях» (спека §7). Красная, если что-то мешает приёму. */
@Component({
  selector: 'app-whatsapp-status-line',
  standalone: true,
  imports: [NgIf],
  template: `<div class="wa-plate" *ngIf="line as l" [class.is-error]="l.error">{{ l.text }}</div>`,
  styles: [`
    .wa-plate { font-size: 13px; color: var(--text-muted); background: var(--surface-2); border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
    .wa-plate.is-error { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
  `],
})
export class WhatsappStatusLineComponent {
  @Input() status: WhatsappStatus | null = null;
  get line() { return whatsappStatusLine(this.status); }
}
