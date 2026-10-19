import { AfterViewInit, ChangeDetectorRef, Component, ElementRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { ChatMessageComponent } from './chat-message.component';
import { errorText } from './error-text';
import { marketDate } from './deadline';
import { MarketService } from '../services/market.service';
import { KIND_ICONS, timelineLink } from './deal';
import type { DealKind, DealTimelineItem } from './deal';

/** Лента «Переписка и история» (спека 2026-10-18 §3.2): частями по 50, прижата к низу, обновляется раз в 30 с. */
@Component({
  selector: 'app-deal-timeline',
  standalone: true,
  imports: [NgFor, NgIf, RouterLink, FormsModule, ChatMessageComponent],
  template: `
    <div class="tl-scroll" #scroll (scroll)="onScroll()">
      <button type="button" class="tl-older" *ngIf="hasMore" [disabled]="loadingOlder" (click)="loadOlder()">
        {{ loadingOlder ? 'Загружаю…' : 'Показать раньше' }}
      </button>
      <div class="tl-note" *ngIf="loading && !items.length">Загрузка…</div>
      <div class="tl-note" *ngIf="!loading && !items.length && !error">Событий пока нет</div>
      <div class="tl-note tl-err" *ngIf="error">{{ error }} · <button type="button" class="tl-link" (click)="load()">Повторить</button></div>
      <ol class="tl" #list>
        <li *ngFor="let i of items; trackBy: track" [attr.data-kind]="i.kind" [class.msg]="i.kind === 'MESSAGE'">
          <app-chat-message *ngIf="i.kind === 'MESSAGE'; else line" [m]="i.message" [chatId]="i.linkId!"></app-chat-message>
          <ng-template #line>
            <time class="tl-time" [attr.datetime]="i.at">{{ when(i) }}</time>
            <span class="tl-icon" aria-hidden="true">{{ icon(i.kind) }}</span>
            <span class="tl-text">
              <b *ngIf="prefix(i.kind) as p">{{ p }} </b>
              <a *ngIf="link(i) as l; else local" [routerLink]="l.path" [queryParams]="l.query">{{ i.text }}</a>
              <ng-template #local>
                <button type="button" class="tl-link" *ngIf="block(i) as b; else plain" (click)="localLink.emit(b)">{{ i.text }}</button>
              </ng-template>
              <ng-template #plain>{{ i.text }}</ng-template>
              <span class="tl-author" *ngIf="i.author"> · {{ i.author }}</span>
            </span>
          </ng-template>
        </li>
      </ol>
    </div>
    <div class="tl-foot" *ngIf="canWrite || writeLink()">
      <div class="tl-form" *ngIf="canWrite && panel">
        <textarea [(ngModel)]="draft" rows="3" maxlength="2000" [attr.aria-label]="panel === 'NOTE' ? 'Текст заметки' : 'О чём звонок'"
                  [placeholder]="panel === 'NOTE' ? 'Заметка по сделке' : 'О чём говорили'"></textarea>
        <div class="tl-form-actions">
          <button type="button" class="btn btn-primary btn-sm" [disabled]="saving || !draft.trim()" (click)="save()">Сохранить</button>
          <button type="button" class="btn btn-line btn-sm" (click)="panel = null">Отмена</button>
        </div>
      </div>
      <div class="tl-buttons" *ngIf="!panel">
        <ng-container *ngIf="canWrite">
          <button type="button" class="btn btn-line btn-sm" (click)="open('NOTE')">+ Заметка</button>
          <button type="button" class="btn btn-line btn-sm" (click)="open('CALL')">+ Звонок</button>
        </ng-container>
        <a class="btn btn-line btn-sm" *ngIf="writeLink() as w" [href]="w.href" target="_blank" rel="noopener">{{ w.label }} ↗</a>
      </div>
    </div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; flex: 1; min-height: 0; }
    .tl-scroll { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 12px; }
    .tl { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .tl li:not(.msg) { display: grid; grid-template-columns: auto auto minmax(0, 1fr); gap: 6px; align-items: baseline;
                       font-size: 13px; color: var(--text); }
    .tl-time { color: var(--text-muted); font-size: 12px; white-space: nowrap; font-variant-numeric: tabular-nums;
               min-width: 6.2em; }   /* «18.10 22:48»: значки строк «только дата» — в той же колонке */
    .tl-text a { color: var(--accent); text-decoration: none; }
    .tl-author { color: var(--text-muted); }
    li[data-kind="LETTER_FAILED"] .tl-text, li[data-kind="BOUNCED"] .tl-text { color: var(--danger-text); }
    li[data-kind="DECLINED"] .tl-text { color: var(--warn-text); }
    .tl-older { display: block; margin: 0 auto 8px; background: none; border: none; color: var(--accent); cursor: pointer; }
    .tl-note { color: var(--text-muted); font-size: 13px; padding: 8px 0; }
    .tl-err { color: var(--danger-text); }
    .tl-link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; text-decoration: underline; font: inherit; text-align: left; }
    .tl-foot { border-top: 1px solid var(--border); padding: 8px 12px; display: flex; flex-direction: column; gap: 8px; }
    .tl-buttons, .tl-form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .tl-form textarea { width: 100%; box-sizing: border-box; resize: vertical; }
  `],
})
export class DealTimelineComponent implements OnChanges, AfterViewInit, OnDestroy {
  @Input({ required: true }) kind!: DealKind;
  @Input({ required: true }) dealId!: number;
  @Input() canWrite = false;
  @Input() phone: string | null = null;
  @Input() email: string | null = null;
  /** Строка исполнения — ключ блока раздела на этой же странице (спека 2026-10-19-deal-execution §10). */
  @Output() localLink = new EventEmitter<string>();
  @ViewChild('scroll') scrollRef?: ElementRef<HTMLElement>;
  @ViewChild('list') listRef?: ElementRef<HTMLElement>;

  items: DealTimelineItem[] = [];
  hasMore = false;
  loading = false;
  loadingOlder = false;
  error = '';
  panel: 'NOTE' | 'CALL' | null = null;
  draft = '';
  saving = false;
  private pinned = true;
  private sub?: Subscription;
  private timer?: ReturnType<typeof setInterval>;
  private ro?: ResizeObserver;
  private readonly onVisible = () => { if (document.visibilityState === 'visible') this.refresh(); };

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private market: MarketService) {}

  ngOnChanges(ch: SimpleChanges) {
    if (ch['dealId'] || ch['kind']) this.load();
  }

  ngAfterViewInit() {
    this.ro = new ResizeObserver(() => { if (this.pinned) this.stick(); });
    if (this.listRef) this.ro.observe(this.listRef.nativeElement);
    if (this.scrollRef) this.ro.observe(this.scrollRef.nativeElement);
    this.timer = setInterval(() => { if (document.visibilityState === 'visible') this.refresh(); }, 30_000);
    document.addEventListener('visibilitychange', this.onVisible);
  }

  ngOnDestroy() {
    this.sub?.unsubscribe();
    this.ro?.disconnect();
    if (this.timer) clearInterval(this.timer);
    document.removeEventListener('visibilitychange', this.onVisible);
  }

  /** Первая страница — самые новые события; лента прижата к низу. */
  load() {
    this.sub?.unsubscribe();
    this.loading = true;
    this.error = '';
    this.pinned = true;
    const id = this.dealId;
    this.sub = this.api.getDealTimeline(this.kind, id).subscribe({
      next: r => {
        if (id !== this.dealId) return;
        this.items = r.items;
        this.hasMore = r.hasMore;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => { this.loading = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }

  /** После действий на странице и по таймеру: свежая первая страница заменяет свой хвост, раньше загруженное остаётся. */
  refresh() {
    if (this.loading) return;
    if (!this.items.length) { this.load(); return; }
    const id = this.dealId;
    this.api.getDealTimeline(this.kind, id).subscribe({
      next: r => {
        if (id !== this.dealId || !r.items.length) return;
        const from = new Date(r.items[0].at).getTime();
        const older = this.items.filter(i => new Date(i.at).getTime() < from);
        this.items = [...older, ...r.items];
        if (!older.length) this.hasMore = r.hasMore;
        this.cdr.markForCheck();
      },
      error: () => { /* тихо: следующий тик повторит */ },
    });
  }

  loadOlder() {
    if (!this.items.length || this.loadingOlder) return;
    const id = this.dealId;
    const el = this.scrollRef?.nativeElement;
    const fromBottom = el ? el.scrollHeight - el.scrollTop : 0;
    this.loadingOlder = true;
    this.pinned = false;
    this.api.getDealTimeline(this.kind, id, this.items[0].at).subscribe({
      next: r => {
        if (id !== this.dealId) return;
        this.items = [...r.items, ...this.items];
        this.hasMore = r.hasMore;
        this.loadingOlder = false;
        this.cdr.detectChanges();
        if (el) el.scrollTop = el.scrollHeight - fromBottom;   // держим место чтения
      },
      error: e => { this.loadingOlder = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  open(type: 'NOTE' | 'CALL') { this.panel = type; this.draft = ''; }

  save() {
    const text = this.draft.trim();
    const type = this.panel;
    if (!text || !type) return;
    this.saving = true;
    const id = this.dealId;
    this.api.addDealEvent(this.kind, id, type, text).subscribe({
      next: item => {
        this.saving = false;
        this.panel = null;
        this.draft = '';
        if (id === this.dealId) { this.items = [...this.items, item]; this.pinned = true; }
        this.cdr.markForCheck();
      },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  onScroll() {
    const el = this.scrollRef?.nativeElement;
    this.pinned = !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  private stick() {
    const el = this.scrollRef?.nativeElement;
    if (el) el.scrollTop = el.scrollHeight;
  }

  writeLink(): { href: string; label: string } | null {
    let digits = (this.phone || '').replace(/\D/g, '');
    if (digits.length === 11 && digits.startsWith('8')) digits = '7' + digits.slice(1);
    if (digits.length >= 10) return { href: 'https://wa.me/' + digits, label: 'Написать в WhatsApp' };
    if (this.email) return { href: 'mailto:' + this.email, label: 'Написать письмо' };
    return null;
  }

  when(i: DealTimelineItem): string {
    if (i.dateOnly) {   // дата из документа (договор, публикация) — по часам рынка, иначе у браузера западнее съедет на день
      const [, m, d] = marketDate(i.at, this.market.value).split('-');
      return d + '.' + m;
    }
    const t = new Date(i.at);
    const p = (n: number) => String(n).padStart(2, '0');
    return p(t.getDate()) + '.' + p(t.getMonth() + 1) + ' ' + p(t.getHours()) + ':' + p(t.getMinutes());
  }

  icon(kind: string) { return KIND_ICONS[kind] ?? '•'; }
  prefix(kind: string) { return kind === 'NOTE' || kind === 'LEAD_NOTE' ? 'Заметка:' : kind === 'CALL' || kind === 'LEAD_CALL' ? 'Звонок:' : null; }
  link(i: DealTimelineItem) { return timelineLink(i); }
  block(i: DealTimelineItem): string | null { return i.linkType?.startsWith('EXEC_') ? i.linkType.slice(5) : null; }
  track(_: number, i: DealTimelineItem) { return i.kind + ':' + i.at + ':' + (i.message?.id ?? i.linkId ?? '') + ':' + (i.text ?? ''); }
}
