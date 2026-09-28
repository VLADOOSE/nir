import { ChangeDetectorRef, Component, ElementRef, OnDestroy, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { fullDateTime, relativeTime } from '../../shared/relative-time';
import { LEAD_STATUS_LABELS } from '../../shared/lead-labels';
import { ChatMessageComponent } from '../../shared/chat-message.component';
import { WhatsappStatusLineComponent } from '../../shared/whatsapp-status-line.component';
import { LeadItemsImportComponent } from '../../shared/lead-items-import.component';
import { formatPhone, WhatsappStatus } from '../../shared/whatsapp-status';

const PAGE = 50;

/**
 * «Чаты» — все чаты рабочего номера WhatsApp, только чтение (спека whatsapp-chats §9.2). Обращения живут поверх:
 * чип статуса, «Создать обращение», «не клиент». На телефоне — мастер-деталь: список ↔ переписка (?chatId=).
 */
@Component({
  selector: 'app-chats',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink, ChatMessageComponent, WhatsappStatusLineComponent, LeadItemsImportComponent],
  template: `
    <div class="page-head">
      <div>
        <h2>Чаты</h2>
        <p class="subtitle">Вся переписка рабочего номера WhatsApp. Отвечаете с телефона — ответ появится здесь.</p>
      </div>
    </div>
    <app-whatsapp-status-line [status]="waStatus"></app-whatsapp-status-line>

    <div class="layout" [class.has-open]="openId !== null">
      <section class="list-pane" aria-label="Чаты">
        <div class="list-tools">
          <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Имя, номер, текст…" aria-label="Поиск по чатам" />
          <div class="chips" role="group" aria-label="Фильтр чатов">
            <button type="button" class="chip" *ngFor="let f of filters" [class.on]="filter === f.key" (click)="setFilter(f.key)">{{ f.label }}</button>
          </div>
        </div>
        <div class="empty" *ngIf="!loadingList && !chats.length">Чатов нет</div>
        <div class="chat-list">
          <button type="button" class="chat-row" *ngFor="let c of chats; trackBy: trackById"
                  [class.active]="c.id === openId" (click)="openChat(c.id)">
            <span class="cr-top">
              <span class="cr-title">{{ title(c) }}</span>
              <time class="cr-time" [attr.title]="full(c.lastMessageAt)">{{ ago(c.lastMessageAt) }}</time>
            </span>
            <span class="cr-preview">{{ c.lastMessagePreview || '—' }}</span>
            <span class="cr-tags" *ngIf="c.group || c.notClient || c.lead">
              <span class="tag" *ngIf="c.group">группа</span>
              <span class="tag" *ngIf="c.notClient">не клиент</span>
              <span class="st" *ngIf="c.lead" [attr.data-status]="c.lead.status">{{ statusLabel(c.lead.status) }}</span>
            </span>
          </button>
        </div>
      </section>

      <section class="thread-pane" *ngIf="openId !== null; else pick" aria-label="Переписка">
        <header class="thread-head">
          <button type="button" class="btn btn-line back" (click)="closeChat()">← Назад</button>
          <div class="th-title" *ngIf="chat">
            <strong>{{ title(chat) }}</strong>
            <span class="th-phone" *ngIf="chat.phone && chat.title">{{ phone(chat) }}</span>
          </div>
          <div class="th-actions" *ngIf="chat">
            <a class="btn btn-line btn-sm" *ngIf="waLink(chat)" [href]="waLink(chat)" target="_blank" rel="noopener">Написать в WhatsApp</a>
            <a class="btn btn-line btn-sm" *ngIf="chat.lead" [routerLink]="['/leads']" [queryParams]="{ openId: chat.lead.id }">Обращение · {{ statusLabel(chat.lead.status) }}</a>
            <button type="button" class="btn btn-primary btn-sm" *ngIf="canCreateLead()" [disabled]="busy" (click)="createLead()">Создать обращение</button>
            <label class="nc" *ngIf="auth.isAdmin() && !chat.group">
              <input type="checkbox" [checked]="chat.notClient" [disabled]="busy" (change)="toggleNotClient($event)" /> не клиент
            </label>
          </div>
        </header>
        <app-lead-items-import *ngIf="parseAttachment && chat?.lead" [chatId]="openId!" [attachment]="parseAttachment"
                               [leadId]="chat.lead.id" [existingItems]="parseLeadItems"
                               (done)="onItemsImported($event)" (cancel)="parseAttachment = null"></app-lead-items-import>
        <div class="thread" #thread (scroll)="onThreadScroll()">
          <div #threadBody>
            <div class="more" *ngIf="hasMore">
              <button type="button" class="btn btn-line btn-sm" [disabled]="loadingMore" (click)="loadMore()">Показать раньше</button>
            </div>
            <div class="empty" *ngIf="loadingThread">Загрузка…</div>
            <app-chat-message *ngFor="let m of messages; trackBy: trackById" [m]="m" [chatId]="openId!"
                              [showAuthor]="!!chat?.group" [canParse]="canParse()" (parse)="startParse($event)"></app-chat-message>
            <div class="empty" *ngIf="!loadingThread && !messages.length">Сообщений нет</div>
          </div>
        </div>
      </section>
      <ng-template #pick><div class="thread-empty">Выберите чат слева</div></ng-template>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .layout { display: grid; grid-template-columns: minmax(260px, 340px) minmax(0, 1fr); gap: 12px; height: calc(100vh - 210px); min-height: 420px; }
    .list-pane, .thread-pane, .thread-empty { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; min-height: 0; }
    .list-pane { display: flex; flex-direction: column; overflow: hidden; }
    .list-tools { padding: 10px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 8px; }
    .list-tools input { width: 100%; box-sizing: border-box; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .chat-list { overflow-y: auto; flex: 1; }
    .chat-row { display: flex; flex-direction: column; gap: 3px; width: 100%; text-align: left; background: none; border: none; border-bottom: 1px solid var(--border); padding: 10px 12px; cursor: pointer; color: var(--text); }
    .chat-row:hover { background: color-mix(in srgb, var(--accent) 5%, var(--surface)); }
    /* выбранный чат — подсветка ОБЛАСТИ: 8% тинта поверх --surface + кромка (правило kit) */
    .chat-row.active { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); box-shadow: inset 3px 0 0 var(--accent); }
    .cr-top { display: flex; justify-content: space-between; gap: 8px; align-items: baseline; min-width: 0; }
    .cr-title { font-weight: 600; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cr-time { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
    .cr-preview { font-size: 13px; color: var(--text-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .cr-tags { display: flex; gap: 6px; flex-wrap: wrap; }
    .tag { font-size: 11px; padding: 1px 8px; border-radius: 6px; background: var(--surface-2); color: var(--text-muted); }
    /* чипы: 15% тинта + текстовый токен (правило kit) */
    .st { font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .thread-pane { display: flex; flex-direction: column; overflow: hidden; }
    .thread-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; padding: 10px 12px; border-bottom: 1px solid var(--border); }
    .th-title { display: flex; flex-direction: column; min-width: 0; flex: 1; }
    .th-title strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .th-phone { font-size: 12px; color: var(--text-muted); }
    .th-actions { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    a.btn { display: inline-flex; align-items: center; text-decoration: none; }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    .nc { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; color: var(--text-muted); cursor: pointer; }
    .back { display: none; }
    .thread { flex: 1; overflow-y: auto; padding: 10px 12px; }
    .more { display: flex; justify-content: center; margin-bottom: 8px; }
    .thread-empty { display: flex; align-items: center; justify-content: center; color: var(--text-muted); }
    @media (max-width: 900px) {
      .layout { display: block; height: auto; min-height: 0; }
      .layout.has-open .list-pane { display: none; }
      .thread-empty { display: none; }
      .thread-pane { height: calc(100dvh - 190px); min-height: 360px; }
      .chat-list { overflow: visible; }
      .back { display: inline-flex; }
      .list-tools input { font-size: 16px; }
      .chat-row { padding: 12px; }
    }
  `],
})
export class ChatsComponent implements OnDestroy {
  @ViewChild('thread') threadRef?: ElementRef<HTMLElement>;

  /**
   * Прижатая к низу лента остаётся внизу при ЛЮБОМ изменении размеров — и содержимого, и самой ленты. Растёт она
   * уже ПОСЛЕ прокрутки: догружаются миниатюры, приходит карточка чата (шапка выше — лента ниже), открывается разбор
   * Excel. Живая проверка: после одной лишь прокрутки последнее сообщение уезжало под край на 41–97px.
   */
  @ViewChild('threadBody') set threadBody(body: ElementRef<HTMLElement> | undefined) {
    this.resizeObserver?.disconnect();
    this.resizeObserver = undefined;
    if (!body) return;
    const el = body.nativeElement;
    this.resizeObserver = new ResizeObserver(() => { if (this.pinned) this.stickToBottom(); });
    this.resizeObserver.observe(el);
    if (el.parentElement) this.resizeObserver.observe(el.parentElement);
  }

  chats: any[] = [];
  loadingList = false;
  filter = 'ALL';
  q = '';
  openId: number | null = null;
  chat: any = null;
  messages: any[] = [];
  hasMore = false;
  loadingThread = false;
  loadingMore = false;
  waStatus: WhatsappStatus | null = null;
  busy = false;
  parseAttachment: any = null;
  parseLeadItems = 0;
  readonly filters = [
    { key: 'ALL', label: 'Все' },
    { key: 'WITH_LEAD', label: 'С обращением' },
    { key: 'WITHOUT_LEAD', label: 'Без обращения' },
    { key: 'GROUPS', label: 'Группы' },
  ];
  private readonly refreshTimer: any;
  private searchTimer: any = null;
  /** Лента у нижнего края; ушёл читать выше — не дёргаем. */
  private pinned = true;
  private resizeObserver?: ResizeObserver;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef) {
    // Без detectChanges: первое значение приходит синхронно ещё в конструкторе, до создания вида (урок §14)
    this.route.queryParams.subscribe(p => {
      const id = p['chatId'] ? +p['chatId'] : null;
      if (id !== this.openId) { this.openId = id; this.onOpenChanged(); }
    });
    this.loadList();
    this.loadStatus();
    // сообщения приходят фоном — список, строка состояния и открытый чат обновляются, пока экран открыт
    this.refreshTimer = setInterval(() => {
      this.loadList(true);
      this.loadStatus();
      if (this.openId !== null) this.refreshThread();
    }, 10000);
  }

  ngOnDestroy() {
    clearInterval(this.refreshTimer);
    clearTimeout(this.searchTimer);
    this.resizeObserver?.disconnect();
  }

  loadList(silent = false) {
    if (!silent) this.loadingList = true;
    this.api.getChats({ filter: this.filter, q: this.q.trim() || undefined }).subscribe({
      next: d => { this.chats = d; this.loadingList = false; this.cdr.detectChanges(); },
      error: e => {
        this.loadingList = false;
        if (!silent) this.notify.error('Чаты не загрузились: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  loadStatus() {
    this.api.getWhatsappStatus().subscribe({ next: s => { this.waStatus = s; this.cdr.detectChanges(); }, error: () => {} });
  }

  setFilter(key: string) { this.filter = key; this.loadList(); }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.loadList(), 300);
  }

  openChat(id: number) {
    this.router.navigate([], { relativeTo: this.route, queryParams: { chatId: id }, queryParamsHandling: 'merge' });
  }

  closeChat() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { chatId: null }, queryParamsHandling: 'merge' });
  }

  loadMore() {
    const id = this.openId;
    if (id === null || !this.messages.length) return;
    const el = this.threadRef?.nativeElement;
    const fromBottom = el ? el.scrollHeight - el.scrollTop : 0;
    this.loadingMore = true;
    this.api.getChatMessages(id, this.messages[0].id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        this.messages = [...page, ...this.messages];
        this.hasMore = page.length === PAGE;
        this.loadingMore = false;
        this.cdr.detectChanges();
        if (el) el.scrollTop = el.scrollHeight - fromBottom;   // держим место чтения
      },
      error: () => { this.loadingMore = false; this.cdr.detectChanges(); },
    });
  }

  createLead() {
    if (!this.chat) return;
    this.busy = true;
    this.api.createChatLead(this.chat.id).subscribe({
      next: c => {
        this.chat = c;
        this.busy = false;
        this.notify.success('Обращение создано');
        this.loadList(true);
        this.cdr.detectChanges();
      },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не получилось'); this.cdr.detectChanges(); },
    });
  }

  toggleNotClient(ev: Event) {
    if (!this.chat) return;
    const box = ev.target as HTMLInputElement;
    const value = box.checked;
    this.busy = true;
    this.api.setChatNotClient(this.chat.id, value).subscribe({
      next: c => {
        this.chat = c;
        this.busy = false;
        this.notify.success(value ? 'Чат помечен «не клиент» — обращения из него не создаются' : 'Отметка «не клиент» снята');
        this.loadList(true);
        this.cdr.detectChanges();
      },
      error: e => {
        this.busy = false;
        box.checked = !value;
        this.notify.error(e.error?.message || 'Не получилось');
        this.cdr.detectChanges();
      },
    });
  }

  startParse(attachment: any) {
    if (!this.chat?.lead) return;
    this.api.getLead(this.chat.lead.id).subscribe({
      next: card => {
        this.parseLeadItems = card.items?.length || 0;
        this.parseAttachment = attachment;
        this.cdr.detectChanges();
      },
      error: e => this.notify.error(e.error?.message || 'Обращение не открылось'),
    });
  }

  onItemsImported(card: any) {
    this.parseAttachment = null;
    this.notify.success('Позиции обращения обновлены: ' + (card.items?.length || 0) + ' поз.');
    this.cdr.detectChanges();
  }

  onThreadScroll() { this.pinned = this.isNearBottom(); }

  canCreateLead(): boolean { return this.auth.isAdmin() && !!this.chat && !this.chat.group && !this.chat.leadOpen; }

  canParse(): boolean {
    const s = this.chat?.lead?.status;
    return this.auth.isAdmin() && (s === 'NEW' || s === 'IN_WORK');
  }

  waLink(c: any): string | null {
    const d = (c?.phone || '').replace(/\D/g, '');
    return d.length >= 10 ? 'https://wa.me/' + d : null;
  }

  title(c: any): string { return c?.title || (c?.phone ? formatPhone(c.phone) : 'Без имени'); }
  phone(c: any): string { return c?.phone ? formatPhone(c.phone) : ''; }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
  trackById(_: number, x: any) { return x.id; }

  private onOpenChanged() {
    this.chat = null;
    this.messages = [];
    this.hasMore = false;
    this.parseAttachment = null;
    this.pinned = true;
    const id = this.openId;
    if (id === null) return;
    this.loadingThread = true;
    this.api.getChat(id).subscribe({
      next: c => { if (id === this.openId) { this.chat = c; this.cdr.detectChanges(); } },
      error: e => this.notify.error('Чат не открылся: ' + (e.error?.message || e.message)),
    });
    this.api.getChatMessages(id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        this.messages = page;
        this.hasMore = page.length === PAGE;
        this.loadingThread = false;
        this.cdr.detectChanges();   // вниз ленту прижмёт ResizeObserver (pinned сброшен выше)
      },
      error: () => { this.loadingThread = false; this.cdr.detectChanges(); },
    });
  }

  /** Новые сообщения открытого чата — дописываем; правки/удаления — обновляем на месте. */
  private refreshThread() {
    const id = this.openId;
    if (id === null) return;
    this.api.getChatMessages(id).subscribe({
      next: page => {
        if (id !== this.openId) return;
        const known = new Map(this.messages.map(m => [m.id, m]));
        let changed = false;
        for (const m of page) {
          const k = known.get(m.id);
          if (!k) { this.messages.push(m); changed = true; }
          else if (k.body !== m.body || k.edited !== m.edited || k.deleted !== m.deleted) { Object.assign(k, m); changed = true; }
        }
        if (!changed) return;
        const t = (x: any) => new Date(x.sentAt).getTime();
        this.messages.sort((a, b) => t(a) - t(b) || a.id - b.id);
        this.cdr.detectChanges();   // у нижнего края — новое сообщение останется в кадре (ResizeObserver)
      },
      error: () => {},
    });
    this.api.getChat(id).subscribe({
      next: c => { if (id === this.openId) { this.chat = c; this.cdr.detectChanges(); } },
      error: () => {},
    });
  }

  private isNearBottom(): boolean {
    const el = this.threadRef?.nativeElement;
    return !el || el.scrollHeight - el.scrollTop - el.clientHeight < 80;
  }

  /** Зовётся из ResizeObserver — раскладка уже посчитана, откладывать не нужно. */
  private stickToBottom() {
    const el = this.threadRef?.nativeElement;
    if (el) el.scrollTop = el.scrollHeight;
  }
}
