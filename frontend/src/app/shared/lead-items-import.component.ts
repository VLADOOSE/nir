import { ChangeDetectorRef, Component, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { ImportGridComponent } from './import-grid.component';
import { buildImportLines, ImportPreview } from './import-lines';

/**
 * Excel из чата → грид D1 → позиции обращения: заполнить / заменить / добавить (спека whatsapp-chats §9.4).
 * Хозяин может ограничить высоту (экран «Чаты»): тогда прокручивается грид, а кнопки остаются на виду.
 */
@Component({
  selector: 'app-lead-items-import',
  standalone: true,
  imports: [NgIf, ImportGridComponent],
  template: `
    <section class="lii" aria-label="Позиции из Excel">
      <div class="lii-head">
        <strong>Позиции из «{{ attachment.fileName || 'файла' }}»</strong>
        <button type="button" class="x" (click)="cancel.emit()" aria-label="Закрыть">×</button>
      </div>
      <p class="hint" *ngIf="loading">Разбираю файл…</p>
      <p class="hint" *ngIf="preview">Проверьте роли колонок — система разметила их сама и запомнит ваши правки.</p>
      <div class="lii-grid" *ngIf="preview"><app-import-grid [preview]="preview"></app-import-grid></div>
      <div class="err" *ngIf="error">{{ error }}</div>
      <div class="lii-actions" *ngIf="preview">
        <ng-container *ngIf="existingItems > 0; else fill">
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="apply('REPLACE')">Заменить позиции ({{ existingItems }})</button>
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="apply('APPEND')">Добавить к позициям</button>
        </ng-container>
        <ng-template #fill>
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="apply('REPLACE')">Заполнить позиции</button>
        </ng-template>
        <button type="button" class="btn btn-cancel" (click)="cancel.emit()">Отмена</button>
      </div>
    </section>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; min-height: 0; }
    .lii { border: 1px solid var(--border); border-radius: 10px; padding: 12px; margin: 10px 0; background: var(--surface); display: flex; flex-direction: column; gap: 8px; flex: 1 1 auto; min-height: 0; }
    .lii-head, .hint, .err, .lii-actions { flex: none; }
    .lii-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .lii-grid { flex: 1 1 auto; min-height: 0; overflow-y: auto; }
    .x { background: none; border: none; font-size: 22px; line-height: 1; cursor: pointer; color: var(--text-muted); }
    .hint { font-size: 12px; color: var(--text-muted); margin: 0; }
    .err { color: var(--danger-text); font-size: 13px; }
    .lii-actions { display: flex; gap: 8px; flex-wrap: wrap; }
  `],
})
export class LeadItemsImportComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) chatId!: number;
  @Input({ required: true }) attachment!: any;
  @Input({ required: true }) leadId!: number;
  @Input() existingItems = 0;
  /** Свежая карточка обращения после сохранения. */
  @Output() done = new EventEmitter<any>();
  @Output() cancel = new EventEmitter<void>();

  preview: ImportPreview | null = null;
  loading = true;
  busy = false;
  error = '';
  private previewSub?: Subscription;
  /** Импорт в полёте привязан к файлу: сменили файл — ответ по прежнему не закроет панель нового с чужим тостом. */
  private applySub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  /**
   * Превью — на КАЖДУЮ смену файла, а не только при создании: панель, открытая на файле A, при «Разобрать»
   * у файла B компонент не пересоздаёт — и грид с «Заменить позиции» оставался от A (ревью 2026-09-28).
   */
  ngOnChanges(ch: SimpleChanges) {
    if (ch['attachment'] || ch['chatId']) this.loadPreview();
  }

  ngOnDestroy() {
    this.previewSub?.unsubscribe();
    this.applySub?.unsubscribe();
  }

  apply(mode: 'REPLACE' | 'APPEND') {
    const built = buildImportLines(this.preview);
    if (built.error) { this.error = built.error; return; }
    this.busy = true;
    this.error = '';
    const items = built.lines.map(l => ({
      name: String(l.name).trim(),
      brand: l.manufact && String(l.manufact).trim() ? String(l.manufact).trim() : null,
      quantity: l.quantity,
    }));
    this.applySub?.unsubscribe();
    this.applySub = this.api.importLeadItems(this.leadId, { mappings: built.mappings, items, mode }).subscribe({
      next: card => { this.busy = false; this.done.emit(card); },
      error: e => { this.busy = false; this.error = e.error?.message || 'Позиции не сохранились'; this.cdr.detectChanges(); },
    });
  }

  private loadPreview() {
    this.previewSub?.unsubscribe();   // ответ по прежнему файлу больше не нужен
    this.applySub?.unsubscribe();
    this.preview = null;
    this.error = '';
    this.busy = false;
    this.loading = true;
    this.previewSub = this.api.previewChatAttachment(this.chatId, this.attachment.id).subscribe({
      next: p => { this.preview = p; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.error = e.error?.message || 'Файл не разобрался'; this.cdr.detectChanges(); },
    });
  }
}
