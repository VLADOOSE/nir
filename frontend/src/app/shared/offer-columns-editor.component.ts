import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { COLUMN_CATALOG, OfferColumn, columnInfo, defaultColumnLabel } from './client-offer';

/** Колонки таблицы КП: порядок перетаскиванием, своя подпись, «Наименование» — обязательна (спека §6.2). */
@Component({
  selector: 'app-offer-columns-editor',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="cols" cdkDropList (cdkDropListDropped)="drop($event)">
      <div class="col" *ngFor="let c of columns; let i = index" cdkDrag [class.muted]="!vatEnabled && vatOnly(c.key)">
        <button type="button" class="grip" cdkDragHandle aria-label="Перетащить колонку" title="Перетащить">
          <svg lucideIcon="grip-vertical" [size]="16"></svg>
        </button>
        <span class="c-name">{{ defaultLabel(c.key) }}</span>
        <!-- maxlength — предел сервера (OfferSettingsValidator.columns): длиннее — каждое автосохранение получало бы 400 -->
        <input class="c-label" [(ngModel)]="c.label" (ngModelChange)="changed.emit()" [placeholder]="'Подпись: ' + defaultLabel(c.key)"
               maxlength="120" [attr.aria-label]="'Своя подпись колонки ' + defaultLabel(c.key)" />
        <span class="c-note" *ngIf="!vatEnabled && vatOnly(c.key)">без НДС не печатается</span>
        <button type="button" class="c-del" *ngIf="c.key !== 'NAME'" (click)="remove(i)"
                [attr.aria-label]="'Убрать колонку ' + defaultLabel(c.key)" title="Убрать">×</button>
      </div>
    </div>
    <div class="more" *ngIf="hidden.length">
      <span class="hint">Добавить колонку:</span>
      <button type="button" class="chip" *ngFor="let h of hidden" (click)="add(h.key)">+ {{ defaultLabel(h.key) }}</button>
    </div>
  `,
  styles: [`
    .cols { display: flex; flex-direction: column; gap: 4px; }
    .col { display: grid; grid-template-columns: 28px 150px minmax(120px, 1fr) auto 32px; gap: 6px; align-items: center; background: var(--surface); }
    .col.muted .c-name { color: var(--text-muted); }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 4px 2px; display: flex; }
    .c-name { font-size: 13px; color: var(--text); }
    .c-label { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    /* Явные колонки: пометка и крестик есть не в каждой строке — без них автораскладка сдвинула бы «×» в колонку пометки. */
    .c-note { grid-column: 4; font-size: 11px; color: var(--text-muted); }
    .c-del { grid-column: 5; background: none; border: none; color: var(--text-muted); font-size: 18px; cursor: pointer; }
    .c-del:hover { color: var(--danger-text); }
    .more { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    .chip { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border: none; border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip:hover { background: color-mix(in srgb, var(--accent) 25%, transparent); }
    @media (max-width: 900px) {
      .col { grid-template-columns: 28px 1fr 32px; grid-template-areas: "grip name del" "grip label del" "grip note del"; }
      .grip { grid-area: grip; } .c-name { grid-area: name; } .c-label { grid-area: label; } .c-note { grid-area: note; } .c-del { grid-area: del; }
      /* 16px — iOS не зумит поле при фокусе; локальные 13px сильнее глобального правила, поэтому повтор здесь */
      .c-label { font-size: 16px; }
    }
  `],
})
export class OfferColumnsEditorComponent {
  @Input({ required: true }) columns!: OfferColumn[];
  @Input() vatEnabled = true;
  @Output() changed = new EventEmitter<void>();

  get hidden() {
    return COLUMN_CATALOG.filter(c => !this.columns.some(x => x.key === c.key));
  }

  vatOnly(key: string): boolean {
    return !!columnInfo(key)?.vatOnly;
  }

  defaultLabel(key: string): string {
    return defaultColumnLabel(key, this.vatEnabled);
  }

  drop(e: CdkDragDrop<OfferColumn[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.columns, e.previousIndex, e.currentIndex);
    this.changed.emit();
  }

  add(key: string) {
    this.columns.push({ key, label: null });
    this.changed.emit();
  }

  remove(i: number) {
    if (this.columns[i].key === 'NAME') return;
    this.columns.splice(i, 1);
    this.changed.emit();
  }
}
