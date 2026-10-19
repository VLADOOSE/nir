import { ChangeDetectorRef, Component, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../services/api.service';
import { NotificationService } from '../services/notification.service';
import { errorText } from './error-text';
import { UPLOAD_LIMIT } from './client-mail';
import type { ClientMailSendBody, ClientMailSendResult, ComposeContext, ComposeOffer, ComposeTarget, OfferPick, ReplyTemplate } from './client-mail';

interface Pick { on: boolean; word: boolean; }

function newToken(): string {
  return typeof crypto !== 'undefined' && 'randomUUID' in crypto ? crypto.randomUUID()
    : Date.now().toString(36) + Math.random().toString(36).slice(2);
}

/**
 * Окно письма клиенту с info@ (спека 2026-10-19-client-mail-reply §4): ответ в переписку или новое письмо, шаблоны,
 * подпись, файлы и КП сделки. Ленивое — хозяева держат его только внутри @defer. Подтверждения — строкой в самом окне:
 * общее окно подтверждения рисуется под модальными окнами.
 */
@Component({
  selector: 'app-email-composer',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="ec-overlay" (click)="tryClose()">
      <div class="ec-modal" role="dialog" aria-modal="true" [attr.aria-label]="title()" (click)="$event.stopPropagation()">
        <header class="ec-head">
          <h3>{{ title() }}</h3>
          <button type="button" class="btn btn-line btn-sm" (click)="tryClose()" [disabled]="sending" aria-label="Закрыть">✕</button>
        </header>
        <p class="ec-note" *ngIf="loading">Загружаю…</p>
        <p class="ec-err" *ngIf="loadError" role="alert">{{ loadError }}</p>
        <ng-container *ngIf="ctx as c">
          <p class="ec-err" *ngIf="!c.enabled">{{ c.reason }}</p>
          <div class="ec-meta">
            <div><span class="ec-k">От</span>{{ c.from }}</div>
            <ng-container *ngIf="c.reply && !newTopic; else fields">
              <div><span class="ec-k">Кому</span>{{ c.to.join(', ') }}</div>
              <div><span class="ec-k">Тема</span>{{ c.reply.replySubject }}</div>
              <div class="ec-sub">Ответ на письмо от {{ when(c.reply.sentAt) }} ·
                <button type="button" class="ec-link" (click)="newTopic = true">Новой темой</button></div>
            </ng-container>
            <ng-template #fields>
              <label class="ec-field"><span class="ec-k">Кому</span>
                <input [(ngModel)]="to" [readonly]="!!c.chatId" placeholder="адрес@почта.kz, второй@почта.kz" autocomplete="off" /></label>
              <label class="ec-field"><span class="ec-k">Тема</span><input [(ngModel)]="subject" maxlength="255" /></label>
              <div class="ec-sub" *ngIf="c.reply">
                <button type="button" class="ec-link" (click)="newTopic = false">Ответом на письмо от {{ when(c.reply.sentAt) }}</button></div>
            </ng-template>
          </div>
          <div class="ec-tpl" *ngIf="c.templates.length" role="group" aria-label="Шаблоны">
            <button type="button" class="btn btn-line btn-sm" *ngFor="let t of c.templates" (click)="useTemplate(t)">{{ t.title }}</button>
          </div>
          <div class="ec-ask" *ngIf="pendingTemplate">
            <span>Заменить текст на «{{ pendingTemplate.title }}»?</span>
            <button type="button" class="btn btn-primary btn-sm" (click)="applyTemplate(pendingTemplate)">Заменить</button>
            <button type="button" class="btn btn-line btn-sm" (click)="pendingTemplate = null">Нет</button>
          </div>
          <textarea class="ec-text" [(ngModel)]="text" rows="8" maxlength="20000" aria-label="Текст письма"></textarea>
          <div class="ec-sig" *ngIf="c.signature">{{ c.signature }}</div>
          <div class="ec-sub" *ngIf="c.reply && !newTopic">Ниже — цитата письма от {{ when(c.reply.sentAt) }}</div>
          <div class="ec-offers" *ngIf="c.offers.length">
            <span class="ec-k">КП</span>
            <div class="ec-offer" *ngFor="let o of c.offers">
              <label class="ec-check"><input type="checkbox" [checked]="pick(o).on" (change)="toggle(o, 'on')" />
                {{ o.label }} <span class="ec-muted">{{ o.origin === 'FILE' ? o.fileName : 'PDF' }}</span></label>
              <label class="ec-check" *ngIf="o.origin === 'BUILDER' && pick(o).on">
                <input type="checkbox" [checked]="pick(o).word" (change)="toggle(o, 'word')" /> + Word</label>
            </div>
          </div>
          <div class="ec-files">
            <label class="btn btn-line btn-sm ec-pick">📎 Файл<input type="file" multiple (change)="addFiles($event)" /></label>
            <span class="ec-sub" [class.ec-over]="overLimit()">{{ files.length ? size(filesTotal()) + ' из 15 МБ' : 'файлы — до 15 МБ вместе' }}</span>
          </div>
          <ul class="ec-list" *ngIf="files.length">
            <li *ngFor="let f of files; let i = index">
              <span class="ec-fname">{{ f.name }}</span> <span class="ec-muted">{{ size(f.size) }}</span>
              <button type="button" class="ec-link" (click)="files.splice(i, 1)" [attr.aria-label]="'Убрать ' + f.name">×</button>
            </li>
          </ul>
          <div class="ec-err" *ngIf="error" role="alert">{{ error }}</div>
          <div class="ec-ask" *ngIf="confirmClose">
            <span>Закрыть без отправки? Текст и файлы пропадут.</span>
            <button type="button" class="btn btn-danger btn-sm" (click)="closed.emit()">Закрыть</button>
            <button type="button" class="btn btn-line btn-sm" (click)="confirmClose = false">Нет</button>
          </div>
          <footer class="ec-actions">
            <button type="button" class="btn btn-line" (click)="tryClose()" [disabled]="sending">Отмена</button>
            <button type="button" class="btn btn-primary" *ngIf="c.enabled" (click)="send()" [disabled]="sending || !canSend()">
              {{ sending ? 'Отправляю…' : 'Отправить' }}</button>
          </footer>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    /* rgba-вуаль не токенизируется: затемнение уместно в обеих темах; окно выше дровера обращения (z-index 1000) */
    .ec-overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
                  justify-content: center; padding: 16px; }
    .ec-modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
                max-width: 720px; max-height: 92vh; overflow-y: auto; padding: 18px 20px; display: flex; flex-direction: column; gap: 10px; }
    .ec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .ec-head h3 { margin: 0; }
    .ec-meta { display: flex; flex-direction: column; gap: 4px; font-size: 13px; overflow-wrap: anywhere; }
    .ec-k { display: inline-block; min-width: 3.6em; color: var(--text-muted); }
    .ec-field { display: flex; align-items: center; gap: 8px; }
    .ec-field input { flex: 1; min-width: 0; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px;
                      background: var(--surface); color: var(--text); font: inherit; }
    .ec-sub { font-size: 12px; color: var(--text-muted); }
    .ec-link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; text-decoration: underline; }
    .ec-tpl { display: flex; gap: 6px; flex-wrap: wrap; }
    .ec-ask { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; padding: 8px 10px; border-radius: 8px; font-size: 13px;
              background: color-mix(in srgb, var(--warn) 8%, var(--surface)); border: 1px solid color-mix(in srgb, var(--warn) 40%, var(--border)); }
    .ec-text { width: 100%; box-sizing: border-box; resize: vertical; min-height: 160px; padding: 8px 10px; border: 1px solid var(--border);
               border-radius: 8px; background: var(--surface); color: var(--text); font: inherit; }
    .ec-sig { white-space: pre-wrap; font-size: 13px; color: var(--text-muted); border-left: 3px solid var(--border); padding-left: 8px; }
    .ec-offers { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    .ec-offer { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
    .ec-check { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; }
    .ec-muted { color: var(--text-muted); }
    .ec-files { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .ec-pick input { display: none; }
    .ec-over { color: var(--danger-text); }
    .ec-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 2px; font-size: 13px; }
    .ec-fname { overflow-wrap: anywhere; }
    .ec-err { color: var(--danger-text); font-size: 13px; margin: 0; }
    .ec-note { color: var(--text-muted); margin: 0; }
    .ec-actions { display: flex; gap: 8px; justify-content: flex-end; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .ec-overlay { padding: 0; align-items: stretch; }
      .ec-modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .ec-tpl .btn, .ec-actions .btn, .ec-pick, .ec-check, .ec-link { min-height: 40px; }
      .ec-link { display: inline-flex; align-items: center; }
      .ec-text, .ec-field input { font-size: 16px; }
    }
  `],
})
export class EmailComposerComponent implements OnInit {
  @Input({ required: true }) target!: ComposeTarget;
  @Output() sent = new EventEmitter<ClientMailSendResult>();
  @Output() closed = new EventEmitter<void>();

  ctx: ComposeContext | null = null;
  loading = true;
  loadError = '';
  newTopic = false;
  to = '';
  subject = '';
  text = '';
  files: File[] = [];
  error = '';
  sending = false;
  pendingTemplate: ReplyTemplate | null = null;
  confirmClose = false;
  private readonly picks = new Map<number, Pick>();
  private readonly token = newToken();

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.api.getClientMailCompose(this.target).subscribe({
      next: c => { this.ctx = c; this.prefill(c); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.loadError = errorText(e); this.cdr.detectChanges(); },
    });
  }

  /** Новое письмо — адреса и тема из окна; первое письмо — шаблон «в первое письмо», письмо с КП — «в письмо с КП». */
  private prefill(c: ComposeContext) {
    this.to = c.to.join(', ');
    this.subject = c.newSubject || '';
    this.newTopic = !c.reply;
    const role = c.offerId != null ? 'OFFER' : c.firstLetter ? 'FIRST_REPLY' : null;
    const t = role ? c.templates.find(x => x.role === role) : undefined;
    this.text = t ? t.body : '';
    for (const o of c.offers) this.picks.set(o.id, { on: o.id === c.offerId, word: false });
  }

  title(): string {
    const c = this.ctx;
    if (!c) return 'Письмо клиенту';
    if (c.offerId != null) return 'Письмо с КП';
    return c.reply && !this.newTopic ? 'Ответить письмом' : 'Новое письмо';
  }

  when(iso: string): string {
    return new Date(iso).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  }

  pick(o: ComposeOffer): Pick {
    let p = this.picks.get(o.id);
    if (!p) { p = { on: false, word: false }; this.picks.set(o.id, p); }
    return p;
  }

  toggle(o: ComposeOffer, k: keyof Pick) {
    const p = this.pick(o);
    p[k] = !p[k];
  }

  /** Свой текст — не пусто и не текст какого-то шаблона. */
  private ownText(): boolean {
    const t = this.text.trim();
    return !!t && !(this.ctx?.templates || []).some(x => x.body.trim() === t);
  }

  useTemplate(t: ReplyTemplate) {
    if (this.ownText()) this.pendingTemplate = t;
    else this.applyTemplate(t);
  }

  applyTemplate(t: ReplyTemplate) {
    this.text = t.body;
    this.pendingTemplate = null;
  }

  addFiles(ev: Event) {
    const input = ev.target as HTMLInputElement;
    for (const f of Array.from(input.files || [])) this.files.push(f);
    input.value = '';
    this.error = this.overLimit() ? 'Файлы больше 15 МБ вместе — уберите часть' : '';
  }

  filesTotal(): number { return this.files.reduce((s, f) => s + f.size, 0); }
  overLimit(): boolean { return this.filesTotal() > UPLOAD_LIMIT; }

  size(n: number): string {
    return n >= 1024 * 1024 ? (n / 1024 / 1024).toFixed(1).replace('.', ',') + ' МБ' : Math.max(1, Math.round(n / 1024)) + ' КБ';
  }

  private replyMode(): boolean { return !!this.ctx?.reply && !this.newTopic; }

  canSend(): boolean {
    const c = this.ctx;
    if (!c || !this.text.trim() || this.overLimit()) return false;
    if (this.replyMode()) return true;
    return !!this.subject.trim() && (!!c.chatId || !!this.to.trim());
  }

  private offersBody(): OfferPick[] {
    const out: OfferPick[] = [];
    for (const o of this.ctx?.offers || []) {
      const p = this.picks.get(o.id);
      if (!p?.on) continue;
      if (o.origin === 'FILE') out.push({ id: o.id, format: 'FILE' });
      else {
        out.push({ id: o.id, format: 'PDF' });
        if (p.word) out.push({ id: o.id, format: 'DOCX' });
      }
    }
    return out;
  }

  send() {
    const c = this.ctx;
    if (!c || this.sending || !this.canSend()) return;
    const reply = this.replyMode();
    const body: ClientMailSendBody = {
      chatId: c.chatId, replyToMessageId: reply ? c.reply!.messageId : null,
      to: c.chatId ? null : this.to, subject: reply ? null : this.subject, text: this.text,
      leadId: c.leadId, tenderId: c.tenderId, offers: this.offersBody(), token: this.token,
    };
    this.sending = true;
    this.error = '';
    this.api.sendClientMail(body, this.files).subscribe({
      next: r => {
        this.sending = false;
        if (r.recorded) this.notify.success(r.offersSent.length ? 'Письмо с КП отправлено, КП — «Отправлено»' : 'Письмо отправлено');
        else this.notify.info(r.warning || 'Письмо отправлено');
        this.sent.emit(r);
      },
      error: e => {
        this.sending = false;
        this.error = e?.status === 413 ? 'Файлы больше 15 МБ вместе — уберите часть' : errorText(e);
        this.cdr.detectChanges();
      },
    });
  }

  tryClose() {
    if (this.sending) return;
    if (this.ownText() || this.files.length) this.confirmClose = true;
    else this.closed.emit();
  }

  @HostListener('document:keydown.escape')
  onEsc() {
    if (this.confirmClose) this.confirmClose = false;
    else this.tryClose();
  }
}
