import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef, HostListener, ElementRef, ViewChild } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';
import { LEAD_CLOSE_REASONS, LEAD_STATUS_LABELS, leadChannelLabel } from '../../shared/lead-labels';
import { LeadConvertDialogComponent } from './lead-convert-dialog.component';
import { ChatMessageComponent } from '../../shared/chat-message.component';
import { LeadItemsImportComponent } from '../../shared/lead-items-import.component';

type Panel = 'none' | 'note' | 'call' | 'close' | 'items';

/** Карточка обращения: контакт, что просят, текст клиента, лента, действия (спека §10). */
@Component({
  selector: 'app-lead-card',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink, LeadConvertDialogComponent, ChatMessageComponent, LeadItemsImportComponent],
  template: `
    <div *ngIf="leadId !== null" class="overlay" (click)="close.emit()">
      <aside class="drawer" (click)="$event.stopPropagation()" aria-label="Карточка обращения">
        <header class="head">
          <div class="title-block">
            <h2 class="title">{{ lead?.subject || 'Обращение' }}</h2>
            <div class="subtitle" *ngIf="lead">
              <span>{{ channelLabel() }}</span>
              <span class="dot">·</span>
              <time [attr.title]="full(lead.receivedAt)">{{ ago(lead.receivedAt) }}</time>
              <span class="dot">·</span>
              <span class="st" [attr.data-status]="lead.status">{{ statusLabel(lead.status) }}</span>
            </div>
          </div>
          <button class="close-btn" type="button" (click)="close.emit()" aria-label="Закрыть">&times;</button>
        </header>

        <div class="body">
          <div *ngIf="loading" class="empty">Загрузка…</div>
          <ng-container *ngIf="lead && !loading">
            <div class="error-banner" *ngIf="lead.extSyncError">
              Статус на сайте не обновлён: {{ lead.extSyncError }}. Повторим автоматически.
            </div>

            <div class="actions">
              <ng-container *ngIf="auth.isAdmin()">
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW'" [disabled]="busy" (click)="take()">Взять в работу</button>
                <button type="button" class="btn btn-primary" *ngIf="lead.status === 'NEW' || lead.status === 'IN_WORK'" [disabled]="busy" (click)="convertOpen = true">Создать частную заявку</button>
                <button type="button" class="btn btn-line" *ngIf="lead.status === 'CLOSED'" [disabled]="busy" (click)="reopen()">Вернуть в работу</button>
              </ng-container>
              <a class="btn btn-line" *ngIf="lead.privateRequestId" [routerLink]="['/private-requests']"
                 [queryParams]="{ openId: lead.privateRequestId }">Открыть заявку {{ lead.privateRequestNumber }}</a>
              <ng-container *ngIf="auth.isAdmin()">
                <button type="button" class="btn btn-line" [disabled]="busy" (click)="togglePanel('note')">+ Заметка</button>
                <button type="button" class="btn btn-line" [disabled]="busy" (click)="togglePanel('call')">+ Звонок</button>
                <button type="button" class="btn btn-line" *ngIf="lead.status !== 'CLOSED'" [disabled]="busy" (click)="togglePanel('close')">Закрыть…</button>
              </ng-container>
            </div>

            <div class="panel" *ngIf="panel === 'note'">
              <textarea [(ngModel)]="noteText" rows="3" placeholder="Что уточнили, что пообещали клиенту…" aria-label="Заметка"></textarea>
              <div class="panel-actions">
                <button type="button" class="btn btn-primary" [disabled]="busy || !noteText.trim()" (click)="saveNote()">Сохранить</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <div class="panel" *ngIf="panel === 'call'">
              <div class="radio-row" role="radiogroup" aria-label="Направление звонка">
                <label><input type="radio" name="leadCallDir" value="IN" [(ngModel)]="callDir" /> Входящий</label>
                <label><input type="radio" name="leadCallDir" value="OUT" [(ngModel)]="callDir" /> Исходящий</label>
              </div>
              <textarea [(ngModel)]="callText" rows="3" placeholder="Итог разговора" aria-label="Итог звонка"></textarea>
              <div class="panel-actions">
                <button type="button" class="btn btn-primary" [disabled]="busy || !callText.trim()" (click)="saveCall()">Сохранить</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <div class="panel" *ngIf="panel === 'close'">
              <select [(ngModel)]="closeReason" aria-label="Причина закрытия">
                <option value="" disabled>Причина…</option>
                <option *ngFor="let r of reasons" [value]="r.v">{{ r.l }}</option>
              </select>
              <input type="text" [(ngModel)]="closeComment" placeholder="Комментарий (необязательно)" aria-label="Комментарий" />
              <div class="panel-actions">
                <button type="button" class="btn btn-danger" [disabled]="busy || !closeReason" (click)="doClose()">Закрыть обращение</button>
                <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
              </div>
            </div>

            <section class="section">
              <h3 class="section-title">Контакт</h3>
              <div class="contact">
                <div class="c-name">{{ lead.contactName || '—' }}<span *ngIf="lead.company" class="c-company"> · {{ lead.company }}</span></div>
                <div class="c-row" *ngIf="lead.contactPhone">
                  <span>{{ lead.contactPhone }}</span>
                  <a class="btn btn-line btn-sm" [href]="'tel:' + (lead.phoneNorm || lead.contactPhone)">Позвонить</a>
                  <a class="btn btn-line btn-sm" *ngIf="waLink()" [href]="waLink()" target="_blank" rel="noopener">Написать в WhatsApp</a>
                </div>
                <div class="c-row" *ngIf="lead.contactEmail"><a [href]="'mailto:' + lead.contactEmail">{{ lead.contactEmail }}</a></div>
                <div class="c-row muted">Клиент: {{ lead.facilityName || 'не определён — выберите при создании заявки' }}</div>
                <div class="c-same" *ngIf="lead.samePhone?.length">
                  <span>Этот номер уже обращался:</span>
                  <button type="button" class="linklike" *ngFor="let s of lead.samePhone" (click)="openLead.emit(s.id)">
                    {{ s.subject }} · {{ ago(s.receivedAt) }} ({{ statusLabel(s.status) }})
                  </button>
                </div>
              </div>
            </section>

            <section class="section">
              <div class="section-head">
                <h3 class="section-title">Что просят</h3>
                <button type="button" class="btn btn-line btn-sm" *ngIf="auth.isAdmin() && canEditItems() && panel !== 'items'" (click)="startItems()">✎ Править</button>
              </div>
              <ng-container *ngIf="panel !== 'items'">
                <div class="empty-inline" *ngIf="!lead.items.length">Позиций нет — суть в тексте клиента</div>
                <ul class="items" *ngIf="lead.items.length">
                  <li *ngFor="let i of lead.items">
                    <span class="i-name">{{ i.name }}</span>
                    <span class="i-meta"><ng-container *ngIf="i.brand">{{ i.brand }} · </ng-container>{{ i.quantity }} шт</span>
                    <a *ngIf="i.productUrl" class="i-link" [href]="i.productUrl" target="_blank" rel="noopener">на сайте ↗</a>
                  </li>
                </ul>
              </ng-container>
              <div class="items-edit" *ngIf="panel === 'items'">
                <div class="ie-row" *ngFor="let i of editItems; let idx = index">
                  <input [(ngModel)]="i.name" placeholder="Наименование / модель" aria-label="Наименование" />
                  <input [(ngModel)]="i.brand" placeholder="Бренд" aria-label="Бренд" />
                  <input type="number" min="1" [(ngModel)]="i.quantity" aria-label="Количество" />
                  <button type="button" class="btn btn-cancel btn-sm" (click)="editItems.splice(idx, 1)" aria-label="Удалить строку">✕</button>
                </div>
                <div class="panel-actions">
                  <button type="button" class="btn btn-line btn-sm" (click)="editItems.push({ name: '', brand: '', quantity: 1 })">+ Строка</button>
                </div>
                <div class="panel-actions">
                  <button type="button" class="btn btn-primary" [disabled]="busy" (click)="saveItems()">Сохранить позиции</button>
                  <button type="button" class="btn btn-cancel" (click)="panel = 'none'">Отмена</button>
                </div>
              </div>
              <app-lead-items-import *ngIf="parseAttachment && lead.chatId" [chatId]="lead.chatId" [attachment]="parseAttachment"
                                     [leadId]="lead.id" [existingItems]="lead.items.length"
                                     (done)="onItemsImported($event)" (cancel)="parseAttachment = null"></app-lead-items-import>
            </section>

            <section class="section" *ngIf="lead.message">
              <h3 class="section-title">Текст клиента</h3>
              <p class="message">{{ lead.message }}</p>
            </section>

            <section class="section">
              <div class="section-head">
                <h3 class="section-title">{{ lead.chatId ? 'Лента и переписка' : 'Лента' }}</h3>
                <a class="btn btn-line btn-sm" *ngIf="lead.chatId" [routerLink]="['/chats']" [queryParams]="{ chatId: lead.chatId }">Открыть весь чат</a>
              </div>
              <ol class="timeline">
                <ng-container *ngFor="let t of timeline; trackBy: trackTimeline">
                  <li *ngIf="t.kind === 'event'" [attr.data-type]="t.e.type">
                    <div class="t-head">
                      <span class="t-type">{{ eventLabel(t.e) }}</span>
                      <time [attr.title]="full(t.e.at)">{{ ago(t.e.at) }}</time>
                      <span *ngIf="t.e.author">{{ t.e.author }}</span>
                    </div>
                    <div class="t-body" *ngIf="t.e.body">{{ t.e.body }}</div>
                  </li>
                  <li *ngIf="t.kind === 'msg'" class="msg">
                    <app-chat-message [m]="t.m" [chatId]="lead.chatId" [canParse]="auth.isAdmin() && canEditItems()"
                                      (parse)="startParse($event)"></app-chat-message>
                  </li>
                </ng-container>
              </ol>
            </section>
          </ng-container>
        </div>
      </aside>
    </div>
    <app-lead-convert-dialog *ngIf="convertOpen && lead" [lead]="lead"
                             (close)="convertOpen = false" (converted)="onConverted($event)"></app-lead-convert-dialog>
  `,
  styles: [`
    /* rgba-вуаль НЕ токенизируется: затемнение под дровером уместно в обеих темах (самое частое значение в приложении) */
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1000; }
    /* Тень направленная: панель у правого края, тень падает влево; токенизирована только сила (--shadow-color) */
    .drawer { position: fixed; top: 0; right: 0; bottom: 0; width: 640px; max-width: 100vw; background: var(--surface); box-shadow: -8px 0 30px var(--shadow-color); display: flex; flex-direction: column; }
    .head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 20px 24px 14px; border-bottom: 1px solid var(--border); }
    .title-block { min-width: 0; flex: 1; }
    .title { margin: 0; font-size: 19px; font-weight: 600; word-break: break-word; }
    /* цвет и размер — из kit-овского .subtitle, здесь только раскладка */
    .subtitle { margin: 6px 0 0; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
    .dot { color: color-mix(in srgb, var(--text-muted) 45%, transparent); }
    .close-btn { background: transparent; border: none; cursor: pointer; font-size: 28px; line-height: 1; color: var(--text-muted); padding: 0 4px; border-radius: 4px; }
    .close-btn:hover { color: var(--danger); background: color-mix(in srgb, var(--danger) 8%, var(--surface)); }
    .body { flex: 1; overflow-y: auto; padding: 16px 24px 28px; }
    .actions { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
    a.btn { display: inline-flex; align-items: center; text-decoration: none; }
    .btn-sm { padding: 4px 10px; font-size: 12px; }
    .panel { background: var(--surface-2); border-radius: 8px; padding: 12px; margin-bottom: 14px; display: flex; flex-direction: column; gap: 8px; }
    .panel textarea, .panel select, .panel input, .items-edit input { width: 100%; box-sizing: border-box; padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); font-family: inherit; }
    .panel-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .radio-row { display: flex; gap: 16px; font-size: 13px; }
    .section { margin-bottom: 22px; }
    /* приглушённая подпись секции: kit красит h3 в --text, роль здесь другая */
    .section-title { margin: 0 0 10px; font-size: 12px; font-weight: 600; color: var(--text-muted); text-transform: uppercase; letter-spacing: .04em; }
    .section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 10px; }
    .section-head .section-title { margin: 0; }
    .contact { display: flex; flex-direction: column; gap: 6px; font-size: 14px; }
    .c-name { font-weight: 600; }
    .c-company { font-weight: 400; color: var(--text-muted); }
    .c-row { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .c-row a:not(.btn) { color: var(--accent); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .c-same { font-size: 13px; color: var(--text-muted); display: flex; flex-direction: column; gap: 4px; align-items: flex-start; }
    .linklike { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; text-align: left; }
    .items { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
    .items li { display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; font-size: 14px; }
    .i-name { font-weight: 500; }
    .i-meta { color: var(--text-muted); font-size: 13px; }
    .i-link { color: var(--accent); font-size: 12px; text-decoration: none; }
    .empty-inline { color: var(--text-muted); font-size: 13px; }
    .items-edit { display: flex; flex-direction: column; gap: 8px; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .message { white-space: pre-wrap; margin: 0; font-size: 14px; background: var(--surface-2); border-radius: 8px; padding: 10px 12px; }
    .timeline { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
    .timeline li { border-left: 2px solid var(--border); padding-left: 10px; }
    .timeline li[data-type="STATUS"] { border-left-color: var(--accent); }
    .timeline li[data-type="CALL"], .timeline li[data-type="MESSAGE"] { border-left-color: var(--success); }
    .timeline li[data-type="SYNC"] { border-left-color: var(--warn); }
    .timeline li.msg { border-left: none; padding-left: 0; }
    .t-head { display: flex; gap: 8px; flex-wrap: wrap; font-size: 12px; color: var(--text-muted); }
    .t-type { font-weight: 600; color: var(--text); }
    .t-body { font-size: 14px; white-space: pre-wrap; margin-top: 2px; }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    @media (max-width: 900px) {
      .drawer { width: 100vw; }
      .head { padding: 14px 16px 10px; }
      .body { padding: 12px 16px 24px; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .panel textarea, .panel select, .panel input, .items-edit input { font-size: 16px; }
    }
  `]
})
export class LeadCardComponent implements OnChanges {
  @Input() leadId: number | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() changed = new EventEmitter<void>();
  @Output() openLead = new EventEmitter<number>();

  lead: any = null;
  loading = false;
  busy = false;
  panel: Panel = 'none';
  noteText = '';
  callDir: 'IN' | 'OUT' = 'IN';
  callText = '';
  closeReason = '';
  closeComment = '';
  editItems: any[] = [];
  convertOpen = false;
  readonly reasons = LEAD_CLOSE_REASONS;
  /** Переписка чата обращения (спека whatsapp-chats §9.3) и лента вперемешку с ней. */
  chatMessages: any[] = [];
  timeline: any[] = [];
  parseAttachment: any = null;
  @ViewChild(LeadItemsImportComponent, { read: ElementRef }) importRef?: ElementRef<HTMLElement>;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private cdr: ChangeDetectorRef, private router: Router) {}

  ngOnChanges(ch: SimpleChanges) {
    if (ch['leadId']) {
      this.panel = 'none';
      this.parseAttachment = null;
      this.chatMessages = [];
      this.timeline = [];
      if (this.leadId != null) this.load(this.leadId);
      else this.lead = null;
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.leadId !== null && !this.convertOpen) this.close.emit();   // открыт диалог — Esc закрывает только его
  }

  load(id: number) {
    this.loading = true;
    this.lead = null;
    this.cdr.detectChanges();
    this.api.getLead(id).subscribe({
      next: d => {
        this.lead = d;
        this.loading = false;
        this.rebuildTimeline();
        this.loadChatMessages();
        this.cdr.detectChanges();
      },
      error: e => {
        this.loading = false;
        this.notify.error('Обращение не открылось: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  /** Любое действие возвращает свежую карточку целиком — ею и заменяем текущую. */
  private apply(req: Observable<any>, ok: string) {
    this.busy = true;
    req.subscribe({
      next: d => {
        this.lead = d;
        this.rebuildTimeline();
        this.busy = false;
        this.panel = 'none';
        this.notify.success(ok);
        this.changed.emit();
        this.cdr.detectChanges();
      },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не получилось'); this.cdr.detectChanges(); },
    });
  }

  take() { this.apply(this.api.takeLead(this.lead.id), 'Взято в работу'); }
  reopen() { this.apply(this.api.reopenLead(this.lead.id), 'Обращение снова в работе'); }
  doClose() { this.apply(this.api.closeLead(this.lead.id, this.closeReason, this.closeComment), 'Обращение закрыто'); }
  saveNote() { this.apply(this.api.addLeadEvent(this.lead.id, { type: 'NOTE', body: this.noteText.trim() }), 'Заметка добавлена'); }
  saveCall() {
    this.apply(this.api.addLeadEvent(this.lead.id, { type: 'CALL', direction: this.callDir, body: this.callText.trim() }), 'Звонок записан');
  }

  startItems() {
    this.editItems = this.lead.items.map((i: any) => ({ ...i }));
    if (!this.editItems.length) this.editItems.push({ name: '', brand: '', quantity: 1 });
    this.panel = 'items';
  }

  saveItems() {
    const items = this.editItems
      .filter(i => i.name && String(i.name).trim())
      .map(i => ({
        name: String(i.name).trim(),
        brand: i.brand && String(i.brand).trim() ? String(i.brand).trim() : null,
        quantity: parseInt(i.quantity, 10) || 1,
        productUrl: i.productUrl || null,
      }));
    this.apply(this.api.updateLeadItems(this.lead.id, items), 'Позиции сохранены');
  }

  /** Переписка чата обращения — с момента обращения (спека whatsapp-chats §9.3). */
  private loadChatMessages() {
    const lead = this.lead;
    if (!lead?.chatId) return;
    this.api.getLeadChatMessages(lead.id).subscribe({
      next: msgs => {
        if (this.lead?.id !== lead.id) return;
        this.chatMessages = msgs;
        this.rebuildTimeline();
        this.cdr.detectChanges();
      },
      error: () => {},
    });
  }

  /** Лента и сообщения по времени; сортировка стабильная — при равном времени событие раньше сообщения. */
  private rebuildTimeline() {
    const time = (iso: string) => new Date(iso).getTime();
    const events = (this.lead?.events || []).map((e: any) => ({ kind: 'event', at: e.at, e }));
    const msgs = this.chatMessages.map((m: any) => ({ kind: 'msg', at: m.sentAt, m }));
    this.timeline = [...events, ...msgs].sort((a, b) => time(a.at) - time(b.at));
  }

  trackTimeline(_: number, t: any) { return t.kind + ':' + (t.kind === 'event' ? t.e.id : t.m.id); }

  startParse(attachment: any) {
    this.parseAttachment = attachment;
    this.panel = 'none';
    this.cdr.detectChanges();
    setTimeout(() => this.importRef?.nativeElement.scrollIntoView({ behavior: 'smooth', block: 'center' }));
  }

  onItemsImported(card: any) {
    this.lead = card;
    this.parseAttachment = null;
    this.rebuildTimeline();
    this.notify.success('Позиции обновлены: ' + (card.items?.length || 0) + ' поз.');
    this.changed.emit();
    this.cdr.detectChanges();
  }

  onConverted(r: { privateRequestId: number; number: string }) {
    this.convertOpen = false;
    this.notify.success('Частная заявка ' + r.number + ' создана');
    this.changed.emit();
    this.router.navigate(['/private-requests'], { queryParams: { openId: r.privateRequestId } });
  }

  togglePanel(p: Panel) {
    const opening = this.panel !== p;
    this.panel = opening ? p : 'none';
    if (!opening) return;
    if (p === 'note') this.noteText = '';
    if (p === 'call') { this.callText = ''; this.callDir = 'IN'; }
    if (p === 'close') { this.closeReason = ''; this.closeComment = ''; }
  }

  canEditItems() { return this.lead?.status === 'NEW' || this.lead?.status === 'IN_WORK'; }

  /** wa.me открывает чат с номером уже сейчас — без бизнес-API. */
  waLink(): string | null {
    const d = (this.lead?.phoneNorm || '').replace(/\D/g, '');
    return d.length >= 10 ? 'https://wa.me/' + d : null;
  }

  channelLabel() { return leadChannelLabel(this.lead.channel, this.lead.source); }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }

  eventLabel(e: any): string {
    switch (e.type) {
      case 'RECEIVED': return 'Получено';
      case 'NOTE': return 'Заметка';
      case 'CALL': return e.direction === 'OUT' ? 'Исходящий звонок' : 'Входящий звонок';
      case 'MESSAGE': return e.direction === 'OUT' ? 'Сообщение клиенту' : 'Сообщение от клиента';
      case 'STATUS': return 'Статус';
      case 'SYNC': return 'Сайт';
      default: return e.type;
    }
  }
}
