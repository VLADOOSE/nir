import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import type { PrView } from '../../shared/private-request';
import type { ExecFileCandidate, ExecFileTarget } from '../../shared/deal-execution';
import { errorText } from '../../shared/error-text';

/** Предел загрузки — как у сервера и nginx фронта (15 МБ). */
const UPLOAD_LIMIT = 15 * 1024 * 1024;

/**
 * Файл к этапу исполнения (спека 2026-10-19-deal-execution §4.4): из переписки сделки — в обе стороны (подписанный
 * договор присылает клиент) — или с компьютера. Пока идёт запрос, окно не закрывается.
 */
@Component({
  selector: 'app-pr-exec-file-dialog',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" [attr.aria-label]="'Файл: ' + target.title" (click)="$event.stopPropagation()">
        <h3>Файл: {{ target.title }}</h3>
        <div class="seg" role="radiogroup" aria-label="Откуда файл">
          <button type="button" role="radio" [attr.aria-checked]="mode === 'chat'" [class.on]="mode === 'chat'"
                  [disabled]="!candidates.length" (click)="mode = 'chat'">Из переписки</button>
          <button type="button" role="radio" [attr.aria-checked]="mode === 'pc'" [class.on]="mode === 'pc'" (click)="mode = 'pc'">С компьютера</button>
        </div>
        <p class="hint" *ngIf="loading">Загружаю файлы переписки…</p>
        <p class="hint" *ngIf="!loading && !candidates.length">В переписке сделки файлов нет — загрузите файл с компьютера.</p>
        <div class="files" *ngIf="mode === 'chat'">
          <label class="file" *ngFor="let c of candidates" [class.on]="picked?.attachmentId === c.attachmentId">
            <input type="radio" name="ef" [checked]="picked?.attachmentId === c.attachmentId" (change)="picked = c" />
            <span class="f-main">
              <span class="f-meta">{{ when(c) }} · {{ c.direction === 'IN' ? 'клиент' : 'мы' }} · {{ c.channel === 'EMAIL' ? 'письмо' : 'WhatsApp' }}<ng-container
                *ngIf="c.subject"> «{{ c.subject }}»</ng-container></span>
              <span class="f-name">📎 {{ c.fileName }}</span>
            </span>
          </label>
        </div>
        <div class="pc" *ngIf="mode === 'pc'">
          <label class="btn btn-line pick-file">{{ file ? 'Другой файл' : 'Выбрать файл' }}
            <input type="file" hidden (change)="onFile($event)" /></label>
          <span class="f-name" *ngIf="file">{{ file.name }}</span>
          <p class="hint">Любой документ или картинка, до 15 МБ.</p>
        </div>
        <div class="error-banner" *ngIf="error" role="alert">{{ error }}</div>
        <div class="acts">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="dismiss()">Отмена</button>
          <button type="button" class="btn btn-primary" [disabled]="busy || !ready()" (click)="submit()">{{ busy ? 'Прикладываю…' : 'Приложить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 600px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px;
                  font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .seg button:disabled { cursor: not-allowed; opacity: .6; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .files { display: flex; flex-direction: column; gap: 6px; }
    .file { border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; display: flex; gap: 8px; align-items: flex-start; cursor: pointer; }
    .file.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .f-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .f-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .f-name { font-weight: 600; overflow-wrap: anywhere; }
    .pc { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
    .pick-file { cursor: pointer; }
    .acts { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .seg button, .file, .acts .btn, .pick-file { min-height: 40px; }
    }
  `],
})
export class PrExecFileDialogComponent implements OnInit {
  @Input({ required: true }) requestId!: number;
  @Input({ required: true }) target!: ExecFileTarget;
  @Output() close = new EventEmitter<void>();
  @Output() viewChange = new EventEmitter<PrView>();

  candidates: ExecFileCandidate[] = [];
  loading = true;
  mode: 'chat' | 'pc' = 'chat';
  picked: ExecFileCandidate | null = null;
  file: File | null = null;
  busy = false;
  error = '';

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnInit() {
    this.api.getExecutionFileCandidates(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.loading = false;
        this.candidates = list;
        if (!list.length) this.mode = 'pc';
        this.cdr.markForCheck();
      },
      error: e => {
        this.loading = false;
        this.mode = 'pc';
        this.error = 'Файлы переписки не загрузились: ' + errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  /** Закрыть (фон, Esc, «Отмена»), но не во время запроса. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.close.emit();
  }

  onFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    input.value = '';
    this.error = '';
  }

  ready(): boolean {
    return this.mode === 'pc' ? !!this.file : !!this.picked;
  }

  when(c: ExecFileCandidate): string {
    return new Date(c.sentAt).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  submit() {
    if (this.busy || !this.ready()) return;
    if (this.mode === 'pc' && this.file && this.file.size > UPLOAD_LIMIT) {
      this.error = 'Файл больше 15 МБ — уменьшите его и загрузите снова';
      return;
    }
    this.busy = true;
    this.error = '';
    const where = { stage: this.target.stage, paymentId: this.target.paymentId ?? null, distributorId: this.target.distributorId ?? null };
    const body = this.mode === 'pc' ? { ...where, file: this.file! } : { ...where, attachmentId: this.picked!.attachmentId };
    this.api.attachExecutionFile(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.viewChange.emit(v); this.close.emit(); },
      error: e => {
        this.busy = false;
        this.error = e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }
}
