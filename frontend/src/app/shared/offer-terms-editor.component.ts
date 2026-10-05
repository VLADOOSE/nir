import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { OfferTerm, TERM_SUGGESTIONS, longDateText } from './client-offer';

/** Условия КП «название — значение» (спека §8.2). Тот же редактор — в «Реквизитах и печати» (условия по умолчанию). */
@Component({
  selector: 'app-offer-terms-editor',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="terms" cdkDropList (cdkDropListDropped)="drop($event)">
      <div class="term" *ngFor="let t of terms; let i = index" cdkDrag>
        <button type="button" class="grip" cdkDragHandle aria-label="Перетащить условие" title="Перетащить">
          <svg lucideIcon="grip-vertical" [size]="16"></svg>
        </button>
        <!-- maxlength — пределы сервера (OfferSettingsValidator.terms): длиннее — каждое автосохранение получало бы 400 -->
        <input class="t-label" [(ngModel)]="t.label" (ngModelChange)="changed.emit()" placeholder="Название (можно пусто)" maxlength="300"
               aria-label="Название условия" />
        <textarea class="t-value" rows="1" [(ngModel)]="t.value" (ngModelChange)="changed.emit()" placeholder="Значение" maxlength="2000"
                  aria-label="Значение условия"></textarea>
        <button type="button" class="t-del" (click)="remove(i)" aria-label="Удалить условие" title="Удалить">×</button>
      </div>
    </div>
    <p class="none" *ngIf="!terms.length">Условий нет — добавьте из списка ниже.</p>
    <div class="chips">
      <span class="hint">Добавить:</span>
      <button type="button" class="chip" *ngFor="let s of suggestions" (click)="add(s.label, s.value)">{{ s.label }}</button>
      <button type="button" class="chip" *ngIf="offerDate" (click)="add('Дата коммерческого предложения', longDate())">Дата КП</button>
      <button type="button" class="chip" (click)="add('', '')">+ своё</button>
    </div>
  `,
  styles: [`
    .terms { display: flex; flex-direction: column; gap: 6px; }
    .term { display: grid; grid-template-columns: 28px minmax(140px, .8fr) minmax(180px, 1.2fr) 32px; gap: 6px; align-items: start; background: var(--surface); }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 6px 2px; display: flex; }
    .t-label, .t-value { width: 100%; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 13px; }
    /* field-sizing: content — значение растёт по тексту (где браузер умеет; иначе rows="1" + ручная растяжка) */
    .t-value { resize: vertical; min-height: 32px; field-sizing: content; }
    .t-del { background: none; border: none; color: var(--text-muted); font-size: 18px; cursor: pointer; }
    .t-del:hover { color: var(--danger-text); }
    .none { color: var(--text-muted); font-size: 13px; margin: 4px 0; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    /* чип: 15% тинта + текстовый токен (правило kit) */
    .chip { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border: none; border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip:hover { background: color-mix(in srgb, var(--accent) 25%, transparent); }
    @media (max-width: 900px) {
      .term { grid-template-columns: 28px 1fr 32px; grid-template-areas: "grip label del" "grip value del"; }
      .grip { grid-area: grip; } .t-label { grid-area: label; } .t-value { grid-area: value; } .t-del { grid-area: del; }
      /* 16px — iOS не зумит поле при фокусе; локальные 13px сильнее глобального правила, поэтому повтор здесь */
      .t-label, .t-value { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class OfferTermsEditorComponent {
  @Input({ required: true }) terms!: OfferTerm[];
  @Input() offerDate: string | null = null;
  @Output() changed = new EventEmitter<void>();
  readonly suggestions = TERM_SUGGESTIONS;

  drop(e: CdkDragDrop<OfferTerm[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.terms, e.previousIndex, e.currentIndex);
    this.changed.emit();
  }

  add(label: string, value: string) {
    this.terms.push({ label, value });
    this.changed.emit();
  }

  remove(i: number) {
    this.terms.splice(i, 1);
    this.changed.emit();
  }

  longDate(): string {
    return this.offerDate ? longDateText(this.offerDate) : '';
  }
}
