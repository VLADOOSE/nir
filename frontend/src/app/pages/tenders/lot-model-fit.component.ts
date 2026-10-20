import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, inject } from '@angular/core';
import { NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { LotFitComponent } from './lot-fit.component';
import { errorText } from '../../shared/error-text';
import type { FitCandidateView } from '../../shared/product-watch';
import type { LotModelFit } from '../../shared/lot-model';

/** «Сверка с моделью в работе» в раскрытом лоте (спека 2026-10-20-lot-model-in-work §6, §7); заметка — у админа. */
@Component({
  selector: 'app-lot-model-fit',
  standalone: true,
  imports: [NgIf, FormsModule, LotFitComponent],
  template: `
    <ng-container *ngIf="data?.model">
      <app-lot-fit *ngIf="fitList.length" [title]="'Сверка с моделью в работе'" [candidates]="fitList"></app-lot-fit>
      <div class="lm-box" *ngIf="!fitList.length">
        <span class="lm-title">Сверка с моделью в работе</span>
        <span *ngIf="data!.card === 'MISSING'">{{ data!.error }}
          <button type="button" class="lm-link" (click)="load()">Повторить</button></span>
        <span *ngIf="data!.card === 'NONE'">Характеристик модели нет — сверять не с чем</span>
      </div>
      <div class="lm-note" *ngIf="canEdit || data!.note || data!.setBy">
        <ng-container *ngIf="!editing">
          <span *ngIf="data!.note" class="lm-note-text">Почему: {{ data!.note }}</span>
          <span *ngIf="data!.setBy" class="lm-who">закрепил {{ data!.setBy }}{{ data!.setAt ? ', ' + when(data!.setAt) : '' }}</span>
          <button *ngIf="canEdit" type="button" class="lm-link" (click)="startEdit()">✎ Заметка</button>
        </ng-container>
        <ng-container *ngIf="editing">
          <textarea [(ngModel)]="draft" maxlength="1000" rows="3" placeholder="Почему эта модель — можно вставить вывод ИИ"></textarea>
          <div class="lm-actions">
            <button type="button" class="btn btn-line" (click)="editing = false" [disabled]="saving">Отмена</button>
            <button type="button" class="btn btn-primary" (click)="saveNote()" [disabled]="saving">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
          </div>
        </ng-container>
      </div>
    </ng-container>
    <div class="lm-box" *ngIf="loading && !data">Сверяю модель в работе…</div>
  `,
  styles: [`
    .lm-box { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface);
      display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; color: var(--text-muted); font-size: 13px; }
    .lm-title { font-weight: 600; color: var(--text); }
    .lm-link { border: none; background: none; color: var(--accent); cursor: pointer; padding: 0; font-size: 13px; }
    .lm-note { margin: 4px 0 8px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; font-size: 13px; }
    .lm-note-text { color: var(--text); white-space: pre-line; overflow-wrap: anywhere; }
    .lm-who { color: var(--text-muted); }
    .lm-note textarea { width: 100%; box-sizing: border-box; font: inherit; padding: 6px 8px; border: 1px solid var(--border);
      border-radius: 6px; background: var(--surface); color: var(--text); }
    .lm-actions { display: flex; gap: 8px; justify-content: flex-end; width: 100%; }
    @media (max-width: 900px) {
      .lm-link { min-height: 40px; }
      .lm-note textarea { font-size: 16px; }
    }
  `],
})
export class LotModelFitComponent implements OnChanges {
  @Input({ required: true }) lotId!: number;
  /** Меняется при смене модели или заметки — перечитать сверку. */
  @Input() modelKey = '';
  @Input() canEdit = false;
  @Output() changed = new EventEmitter<void>();

  data: LotModelFit | null = null;
  fitList: FitCandidateView[] = [];
  loading = false;
  editing = false;
  saving = false;
  draft = '';
  private seq = 0;
  private destroyRef = inject(DestroyRef);

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnChanges(): void { this.load(); }

  load() {
    const seq = ++this.seq;
    this.loading = true;
    this.api.getLotModelFit(this.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: d => {
        if (seq !== this.seq) return;
        this.data = d;
        this.fitList = d.card === 'OK' && d.fit ? [d.fit] : [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => {
        if (seq !== this.seq) return;
        this.loading = false;
        this.notify.error('Сверка модели не загрузилась: ' + errorText(e));
        this.cdr.markForCheck();
      },
    });
  }

  startEdit() { this.draft = this.data?.note ?? ''; this.editing = true; }

  saveNote() {
    this.saving = true;
    this.api.setLotModelNote(this.lotId, this.draft.trim() || null).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.saving = false; this.editing = false; this.notify.success('Заметка сохранена'); this.changed.emit(); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  /** Дата и время — по Уральску (модель в работе — только KZ). */
  when(iso: string): string {
    return new Date(iso).toLocaleString('ru-RU', { timeZone: 'Asia/Oral', day: '2-digit', month: '2-digit', year: 'numeric',
      hour: '2-digit', minute: '2-digit' });
  }
}
