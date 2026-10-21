import { ChangeDetectorRef, Component, EventEmitter, HostListener, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import type { AiAnswerResult } from '../../shared/ai-answer';

/** Ответ ИИ с компьютера (спека 2026-10-21-tender-telegram-bot §10 п. 3): поле → «Разобрать» → итог и «КП ›» по письмам. */
@Component({
  selector: 'app-ai-answer-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="aa-veil" (click)="close()"></div>
    <div class="aa-dlg" role="dialog" aria-modal="true" aria-label="Ответ ИИ">
      <h3 class="aa-title">✍️ Ответ ИИ</h3>
      <ng-container *ngIf="!result">
        <p class="aa-hint">Вставьте блок «Для АИС» из конца ответа ИИ — можно весь ответ, АИС найдёт блок сама.</p>
        <textarea class="aa-text" [(ngModel)]="text" [disabled]="busy" placeholder="L5826 | идти | P123 | почему"></textarea>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <div class="aa-actions">
          <button type="button" class="btn" (click)="close()" [disabled]="busy">Отмена</button>
          <button type="button" class="btn btn-primary" (click)="apply()" [disabled]="busy || !text.trim()">{{ busy ? 'Разбираю…' : 'Разобрать' }}</button>
        </div>
      </ng-container>
      <ng-container *ngIf="result">
        <ul class="aa-list"><li *ngFor="let l of result.lots">{{ l.text }}</li></ul>
        <p class="aa-warn" *ngIf="result.unparsed.length">⚠ Не понял строк: {{ result.unparsed.length }} — «{{ result.unparsed[0] }}»</p>
        <p class="aa-note" *ngFor="let n of result.notes">• {{ n }}</p>
        <h4 class="aa-sub">📨 Письма к отправке</h4>
        <p class="aa-note" *ngIf="!result.letters.length">Писем к отправке нет</p>
        <a class="aa-letter" *ngFor="let p of result.letters" [routerLink]="['/tenders']"
           [queryParams]="{ openId: p.tenderId, lot: p.lotIds[0], kp: p.distributorId }" (click)="close()">
          КП › {{ p.distributorName }} ({{ p.manufacturer ? 'завод' : 'доверенный' }}) — {{ p.lotNames.join(', ') }}</a>
        <div class="aa-actions"><button type="button" class="btn btn-primary" (click)="close()">Готово</button></div>
      </ng-container>
    </div>
  `,
  styles: [`
    .aa-veil { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; }
    .aa-dlg { position: fixed; top: 50%; left: 50%; transform: translate(-50%, -50%); width: min(680px, calc(100vw - 32px));
              max-height: calc(100vh - 32px); overflow: auto; display: flex; flex-direction: column; gap: 10px; padding: 16px;
              background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 10px;
              box-shadow: var(--shadow-lg); z-index: 1101; }
    .aa-title { margin: 0; font-size: 17px; }
    .aa-sub { margin: 6px 0 0; font-size: 14px; }
    .aa-hint, .aa-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .aa-warn { margin: 0; font-size: 13px; color: var(--warn-text); }
    .aa-text { min-height: 180px; width: 100%; box-sizing: border-box; padding: 8px; font: 13px/1.4 monospace; color: var(--text);
               background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; resize: vertical; }
    .aa-list { margin: 0; padding-left: 18px; font-size: 14px; display: flex; flex-direction: column; gap: 4px; }
    .aa-letter { display: block; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; color: var(--accent);
                 text-decoration: none; font-size: 14px; }
    .aa-letter:hover { background: var(--surface-2); }
    .aa-actions { display: flex; justify-content: flex-end; gap: 8px; }
    @media (max-width: 900px) {
      .aa-dlg { inset: 0; top: 0; left: 0; transform: none; width: 100vw; max-height: none; height: 100vh; border-radius: 0; }
      .aa-text { font-size: 16px; }
      .aa-letter { min-height: 40px; }
    }
  `],
})
export class AiAnswerDialogComponent {
  @Output() closed = new EventEmitter<void>();
  @Output() applied = new EventEmitter<void>();
  text = '';
  busy = false;
  error: string | null = null;
  result: AiAnswerResult | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  @HostListener('document:keydown.escape')
  onEsc() { this.close(); }

  apply() {
    if (this.busy || !this.text.trim()) return;
    this.busy = true;
    this.error = null;
    this.api.postAiAnswer(this.text).subscribe({
      next: r => { this.busy = false; this.result = r; this.applied.emit(); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.error = e?.error?.message || 'Не удалось разобрать ответ'; this.cdr.markForCheck(); },
    });
  }

  close() { if (!this.busy) this.closed.emit(); }
}
