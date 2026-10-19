import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import type { OfferFileCandidate } from '../../shared/client-offer';
import { marketToday } from '../../shared/deadline';
import { errorText } from '../../shared/error-text';

/** Предел загрузки — как у сервера и nginx фронта (15 МБ). */
const UPLOAD_LIMIT = 15 * 1024 * 1024;

/**
 * «Готовое КП» (спека 2026-10-19 §3.2): файл из переписки сделки (наши письма с info@ и сообщения WhatsApp) или с
 * компьютера, статус «Отправлено ДД.ММ.ГГГГ» / «Клиент уже согласился» → КП из файла, сразу редактор. PDF самой АИС с
 * черновиком этой сделки не заносится — у него «Отметить … отправленным».
 */
@Component({
  selector: 'app-pr-offer-file-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Готовое КП" (click)="$event.stopPropagation()">
        <h3>Готовое КП</h3>
        <div class="seg" role="radiogroup" aria-label="Откуда файл">
          <button type="button" role="radio" [attr.aria-checked]="mode === 'chat'" [class.on]="mode === 'chat'"
                  [disabled]="!candidates.length" (click)="setMode('chat')">Из переписки</button>
          <button type="button" role="radio" [attr.aria-checked]="mode === 'pc'" [class.on]="mode === 'pc'" (click)="setMode('pc')">С компьютера</button>
        </div>
        <p class="hint" *ngIf="loading">Загружаю файлы переписки…</p>
        <p class="hint" *ngIf="!loading && !candidates.length">В переписке сделки наших файлов нет — загрузите файл с компьютера.</p>

        <div class="files" *ngIf="mode === 'chat'">
          <div class="file" *ngFor="let c of candidates" [class.on]="picked?.attachmentId === c.attachmentId">
            <label class="f-pick">
              <input type="radio" name="kpf" [checked]="picked?.attachmentId === c.attachmentId" [disabled]="!!c.aisOfferId" (change)="pick(c)" />
              <span class="f-main">
                <span class="f-meta">{{ when(c) }} · {{ c.channel === 'EMAIL' ? 'письмо' : 'WhatsApp' }}<ng-container *ngIf="c.subject"> «{{ c.subject }}»</ng-container></span>
                <span class="f-name">📎 {{ c.fileName }}</span>
                <span class="f-flags">
                  <span class="flag" *ngIf="c.looksLikeOffer && !c.aisOfferId">похоже на КП</span>
                  <span class="flag muted" *ngIf="c.usedByOfferLabel">занесено: {{ c.usedByOfferLabel }}</span>
                  <span class="flag muted" *ngIf="c.aisOfferLabel">это {{ c.aisOfferLabel }} из АИС</span>
                </span>
              </span>
            </label>
            <button type="button" class="btn btn-line btn-sm" *ngIf="c.aisOfferDraft" [disabled]="busy" (click)="markSent(c)">
              Отметить {{ c.aisOfferLabel }} отправленным</button>
          </div>
        </div>

        <div class="pc" *ngIf="mode === 'pc'">
          <label class="btn btn-line pick-file">{{ file ? 'Другой файл' : 'Выбрать файл' }}
            <input type="file" hidden accept=".docx,.doc,.xlsx,.xls,.pdf,.jpg,.jpeg,.png,.webp,.gif" (change)="onFile($event)" /></label>
          <span class="f-name" *ngIf="file">{{ file.name }}</span>
          <p class="hint">Word, Excel, PDF или картинка, до 15 МБ. Цены из Word и Excel прочитаются сами, из PDF и картинки — впишите их по документу.</p>
        </div>

        <fieldset class="st">
          <legend>Статус</legend>
          <label class="check"><input type="radio" name="kps" [checked]="status === 'SENT'" (change)="status = 'SENT'" /> Отправлено
            <input type="date" [max]="today" [(ngModel)]="sentOn" aria-label="Дата отправки" /></label>
          <label class="check"><input type="radio" name="kps" [checked]="status === 'ACCEPTED'" (change)="status = 'ACCEPTED'" /> Клиент уже согласился</label>
        </fieldset>

        <div class="error-banner" *ngIf="error" role="alert">{{ error }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-line" [disabled]="busy" (click)="dismiss()">Отмена</button>
          <button type="button" class="btn btn-primary" [disabled]="busy || !ready()" (click)="submit()">{{ busy ? 'Заношу…' : 'Занести' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 640px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .seg button:disabled { cursor: not-allowed; opacity: .6; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .files { display: flex; flex-direction: column; gap: 6px; }
    .file { border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; display: flex; flex-direction: column; gap: 6px; }
    .file.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .f-pick { display: flex; gap: 8px; align-items: flex-start; cursor: pointer; }
    .f-main { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .f-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .f-name { font-weight: 600; overflow-wrap: anywhere; }
    .f-flags { display: flex; gap: 6px; flex-wrap: wrap; }
    .flag { font-size: 11px; border-radius: 999px; padding: 1px 8px; background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .flag.muted { background: var(--surface-2); color: var(--text); }
    .pc { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
    .pick-file { cursor: pointer; }
    .st { border: 1px solid var(--border); border-radius: 10px; padding: 8px 12px; display: flex; flex-direction: column; gap: 6px; margin: 0; }
    .st legend { font-size: 12px; color: var(--text-muted); padding: 0 4px; }
    .check { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 14px; }
    .check input[type="date"] { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .seg button, .check, .f-pick, .form-actions .btn, .file .btn, .pick-file { min-height: 40px; }
      .check input[type="date"] { font-size: 16px; }
    }
  `],
})
export class PrOfferFileDialogComponent implements OnInit {
  @Input({ required: true }) requestId!: number;
  /** Файл переписки из подсказки «Дальше» — выбран сразу. */
  @Input() preselect: number | null = null;
  @Output() close = new EventEmitter<void>();
  /** КП отмечено отправленным прямо из окна — странице перечитать этапы. */
  @Output() changed = new EventEmitter<void>();

  candidates: OfferFileCandidate[] = [];
  loading = true;
  mode: 'chat' | 'pc' = 'chat';
  picked: OfferFileCandidate | null = null;
  file: File | null = null;
  status: 'SENT' | 'ACCEPTED' = 'SENT';
  readonly today: string;
  sentOn: string;
  busy = false;
  error = '';

  constructor(private api: ApiService, private router: Router, private notify: NotificationService, market: MarketService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {
    this.today = marketToday(market.value);
    this.sentOn = this.today;
  }

  ngOnInit() {
    this.api.getOfferFileCandidates(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.loading = false;
        this.candidates = list;
        const first = list.find(c => c.attachmentId === this.preselect && !c.aisOfferId)
          ?? list.find(c => c.looksLikeOffer && !c.aisOfferId && !c.usedByOfferId) ?? null;
        if (first) this.pick(first);
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

  /** Закрыть (фон, Esc, «Отмена»), но не во время занесения. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.close.emit();
  }

  setMode(m: 'chat' | 'pc') {
    this.mode = m;
    this.error = '';
    this.sentOn = m === 'chat' && this.picked ? this.picked.sentOn : this.today;
  }

  pick(c: OfferFileCandidate) {
    if (c.aisOfferId) return;
    this.picked = c;
    this.sentOn = c.sentOn;
    this.error = '';
  }

  onFile(ev: Event) {
    const input = ev.target as HTMLInputElement;
    this.file = input.files?.[0] ?? null;
    input.value = '';
    this.error = '';
  }

  ready(): boolean {
    return !!this.sentOn && (this.mode === 'pc' ? !!this.file : !!this.picked);
  }

  when(c: OfferFileCandidate): string {
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
    const body = this.mode === 'pc'
      ? { file: this.file!, status: this.status, sentOn: this.sentOn }
      : { attachmentId: this.picked!.attachmentId, status: this.status, sentOn: this.sentOn };
    this.api.attachOfferFile(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: o => { this.router.navigate(['/client-offers', o.id]); },
      error: e => {
        this.busy = false;
        this.error = e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  markSent(c: OfferFileCandidate) {
    if (this.busy) return;
    this.busy = true;
    this.api.markOfferHintSent(this.requestId, c.attachmentId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.notify.success(`${c.aisOfferLabel} — отправлено`); this.changed.emit(); this.close.emit(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
