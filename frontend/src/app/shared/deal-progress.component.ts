import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ACTION_LABELS } from './deal';
import type { DealAction, DealNext, DealProgress, StepState } from './deal';

/** Этапы сделки и строка «Дальше» (спека 2026-10-18 §3.1, §4.1). Кнопки — только при canAct (админ). */
@Component({
  selector: 'app-deal-progress',
  standalone: true,
  imports: [NgFor, NgIf],
  template: `
    <section class="dp" *ngIf="progress">
      <ol class="dp-steps">
        <li *ngFor="let s of progress.steps" [attr.data-state]="s.state">
          <button type="button" class="dp-step" (click)="stepClick.emit(s.key)" [attr.aria-current]="s.state === 'CURRENT' ? 'step' : null">
            <span class="dp-mark" aria-hidden="true">{{ mark(s.state) }}</span>
            <span class="dp-label">{{ s.label }}</span>
            <span class="dp-detail" *ngIf="s.detail">{{ s.detail }}</span>
          </button>
        </li>
      </ol>
      <div class="dp-compact" *ngIf="current() as c">
        Этап {{ c.index + 1 }} из {{ progress.steps.length }}: {{ c.label }}<ng-container *ngIf="c.detail"> · {{ c.detail }}</ng-container>
      </div>
      <div class="dp-next" *ngIf="progress.next as n">
        <span class="dp-next-text"><b>Дальше:</b> {{ n.text }}</span>
        <span class="dp-actions" *ngIf="canAct && n.action">
          <ng-container *ngIf="n.action === 'OFFER_DECISION'; else hintTpl">
            <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n, 'ACCEPTED')">Согласился</button>
            <button type="button" class="btn btn-line btn-sm" [disabled]="busy" (click)="emit(n, 'REJECTED')">Отказался</button>
          </ng-container>
          <ng-template #hintTpl>
            <ng-container *ngIf="isHint(n); else one">
              <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n)">{{ label(n.action) }}</button>
              <button type="button" class="btn btn-line btn-sm" [disabled]="busy" (click)="dismissHint(n)">{{ n.action === 'MARK_OFFER_SENT' ? 'Нет' : 'Не КП' }}</button>
            </ng-container>
          </ng-template>
          <ng-template #one>
            <button type="button" class="btn btn-primary btn-sm" [disabled]="busy" (click)="emit(n)">{{ label(n.action) }}</button>
          </ng-template>
        </span>
      </div>
    </section>
  `,
  styles: [`
    .dp { display: flex; flex-direction: column; gap: 8px; }
    .dp-steps { display: flex; flex-wrap: wrap; gap: 4px 16px; list-style: none; margin: 0; padding: 0; }
    .dp-step { display: grid; grid-template-columns: auto auto; column-gap: 6px; align-items: baseline; text-align: left;
               background: none; border: none; padding: 2px 0; cursor: pointer; color: var(--text-muted); }
    .dp-mark { grid-row: 1; }
    .dp-label { grid-row: 1; font-weight: 600; }
    .dp-detail { grid-column: 2; font-size: 12px; }
    li[data-state="DONE"] .dp-step { color: var(--success-text); }
    li[data-state="CURRENT"] .dp-step { color: var(--accent); }
    li[data-state="SKIPPED"] .dp-label { text-decoration: line-through; }
    .dp-compact { display: none; font-weight: 600; color: var(--text); }
    .dp-next { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 8px 12px; border-radius: 10px;
               background: color-mix(in srgb, var(--accent) 8%, var(--surface));
               border: 1px solid color-mix(in srgb, var(--accent) 30%, var(--border)); }
    .dp-next-text { flex: 1; min-width: 0; color: var(--text); }
    .dp-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .dp-steps { display: none; }
      .dp-compact { display: block; }
      .dp-actions { width: 100%; }
      .dp-actions .btn { flex: 1; }
    }
  `],
})
export class DealProgressComponent {
  @Input() progress: DealProgress | null = null;
  @Input() canAct = false;
  @Input() busy = false;
  @Output() act = new EventEmitter<DealAction>();
  @Output() stepClick = new EventEmitter<string>();

  /** Текущий шаг; всё сделано — последний сделанный. */
  current(): { index: number; label: string; detail: string | null } | null {
    const steps = this.progress?.steps ?? [];
    let index = steps.findIndex(s => s.state === 'CURRENT');
    if (index < 0) {
      for (let i = steps.length - 1; i >= 0; i--) if (steps[i].state === 'DONE') { index = i; break; }
    }
    return index >= 0 ? { index, label: steps[index].label, detail: steps[index].detail } : null;
  }

  mark(s: StepState) { return s === 'DONE' ? '✓' : s === 'CURRENT' ? '●' : s === 'SKIPPED' ? '–' : '○'; }
  label(a: string | null) { return (a && ACTION_LABELS[a]) || 'Открыть'; }

  isHint(n: DealNext) { return n.action === 'ATTACH_OFFER_FILE' || n.action === 'MARK_OFFER_SENT'; }

  emit(n: DealNext, decision?: 'ACCEPTED' | 'REJECTED') {
    if (n.action) this.act.emit({ action: n.action, refId: n.refId, hintId: n.hintId ?? null, decision });
  }

  /** «Не КП» / «Нет» — подсказка про этот файл больше не показывается. */
  dismissHint(n: DealNext) {
    this.act.emit({ action: 'DISMISS_HINT', refId: null, hintId: n.hintId ?? null });
  }
}
