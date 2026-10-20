import { ChangeDetectorRef, Component, HostListener, Input } from '@angular/core';
import { NgIf } from '@angular/common';
import { firstValueFrom } from 'rxjs';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';

type Brief = { text: string; tenders: number; total: number };

/**
 * «📋 Для ИИ» (спека 2026-10-20-product-spec-fit §9): текст по тендеру или списку тендеров с нашими лотами — в буфер
 * обмена, чтобы вставить в Claude или Gemini. Запись в буфер — из того же нажатия, через ClipboardItem с обещанием
 * текста: Safari на iPhone не пускает запись после сетевого ответа. Буфер недоступен — окно с текстом «выделите и
 * скопируйте». Результаты — через .then, не нативный await (CLAUDE.md §14: код после await идёт мимо зоны Angular).
 */
@Component({
  selector: 'app-ai-brief-button',
  standalone: true,
  imports: [NgIf],
  template: `
    <button type="button" class="btn ai-btn" [disabled]="busy" (click)="copy()"
            title="Скопировать в буфер текст для Claude или Gemini: требования ТЗ, сверка, наши товары с РУ">{{ busy ? 'Собираю…' : label }}</button>
    <div class="ai-veil" *ngIf="fallback" (click)="fallback = null"></div>
    <div class="ai-dlg" *ngIf="fallback" role="dialog" aria-modal="true" aria-label="Текст для ИИ">
      <p class="ai-msg">Не удалось записать в буфер — выделите текст и скопируйте.</p>
      <textarea readonly class="ai-text" [value]="fallback" (focus)="$any($event.target).select()"></textarea>
      <div class="ai-actions"><button type="button" class="btn" (click)="fallback = null">Закрыть</button></div>
    </div>
  `,
  styles: [`
    :host { display: inline-block; }
    .ai-btn { padding: 4px 12px; font-size: 13px; }
    .ai-veil { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; }
    .ai-dlg { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); width: min(720px, calc(100vw - 32px));
              max-height: calc(100vh - 32px); display: flex; flex-direction: column; gap: 10px; padding: 16px;
              background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 10px;
              box-shadow: var(--shadow-lg); z-index: 1101; }
    .ai-msg { margin: 0; font-size: 14px; }
    .ai-text { flex: 1; min-height: 300px; width: 100%; box-sizing: border-box; padding: 8px; font: 13px/1.4 monospace;
               color: var(--text); background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; resize: vertical; }
    .ai-actions { display: flex; justify-content: flex-end; }
    @media (max-width: 900px) {
      .ai-dlg { inset: 0; top: 0; left: 0; transform: none; width: 100vw; max-height: none; height: 100vh; border-radius: 0; }
      .ai-text { font-size: 16px; }
    }
  `],
})
export class AiBriefButtonComponent {
  /** Один тендер (карточка) или список id (экран «Тендеры» с фильтром «Наш товар»). */
  @Input() tenderId?: number;
  @Input() ids?: number[];
  @Input() label = '📋 Для ИИ';
  busy = false;
  fallback: string | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.fallback) { this.fallback = null; this.cdr.markForCheck(); }
  }

  copy(): void {
    if (this.busy) return;
    this.busy = true;
    const text$: Promise<Brief> = this.tenderId != null
      ? firstValueFrom(this.api.getAiBrief(this.tenderId)).then(t => ({ text: t, tenders: 1, total: 1 }))
      : firstValueFrom(this.api.postAiBrief(this.ids ?? []));
    const done = (r: Brief) => {
      this.busy = false;
      const n = `${r.tenders} ${tendersWord(r.tenders)}`;
      const more = r.total > r.tenders && this.tenderId == null ? ` из ${r.total}` : '';
      this.notify.success(`Скопировано: ${n}${more}, ${Math.round(r.text.length / 1000)} тыс. знаков — вставьте в Claude или Gemini`);
      this.cdr.markForCheck();
    };
    const fail = (r?: Brief) => {          // буфер недоступен — текст в окне, «выделите и скопируйте»
      this.busy = false;
      this.fallback = r?.text ?? null;
      if (!this.fallback) this.notify.error('Не удалось собрать текст для ИИ');
      this.cdr.markForCheck();
    };
    const blob = text$.then(r => new Blob([r.text], { type: 'text/plain' }));
    const write: Promise<void> = (window as any).ClipboardItem && navigator.clipboard?.write
      ? navigator.clipboard.write([new ClipboardItem({ 'text/plain': blob })])
      : text$.then(r => navigator.clipboard.writeText(r.text));
    write.then(() => text$.then(done), () => text$.then(r => fail(r), () => fail()));
  }
}

/** «1 тендер», «2 тендера», «5 тендеров». */
export function tendersWord(n: number): string {
  const m10 = n % 10, m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return 'тендер';
  return m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14) ? 'тендера' : 'тендеров';
}
