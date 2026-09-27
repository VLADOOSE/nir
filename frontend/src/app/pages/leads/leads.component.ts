import { Component, ChangeDetectorRef, OnDestroy } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';
import { LEAD_STATUS_LABELS, leadChannelLabel } from '../../shared/lead-labels';
import { LeadCardComponent } from './lead-card.component';
import { LeadFormComponent } from './lead-form.component';

/**
 * «Обращения» — вход коммерческой воронки West-Med: заявки с сайта westmed.kz (фоновый опрос сайта),
 * звонки и WhatsApp (ручной ввод). Спека: docs/superpowers/specs/2026-09-27-leads-westmed-intake-design.md §10.
 */
@Component({
  selector: 'app-leads',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink, LeadCardComponent, LeadFormComponent],
  template: `
    <div class="page-head">
      <div>
        <h2>Обращения</h2>
        <p class="subtitle">Заявки с сайтов, звонки и WhatsApp — всё, что клиенты просят найти</p>
      </div>
      <div class="head-actions" *ngIf="auth.isAdmin()">
        <button type="button" class="btn btn-line" *ngIf="sync?.enabled" [disabled]="syncing" (click)="runSync()">
          {{ syncing ? 'Проверяю…' : 'Проверить сейчас' }}
        </button>
        <button type="button" class="btn btn-primary" (click)="formOpen = true">+ Обращение</button>
      </div>
    </div>

    <div class="sync-plate" *ngIf="sync" [class.is-error]="sync.enabled && sync.lastError">{{ syncText() }}</div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Статус">
        <button type="button" class="chip" *ngFor="let f of statusFilters"
                [class.on]="statusKey === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <div class="filters-right">
        <select [(ngModel)]="channel" (change)="load()" aria-label="Канал">
          <option value="">Все каналы</option>
          <option value="SITE">Сайт</option>
          <option value="PHONE">Звонок</option>
          <option value="WHATSAPP">WhatsApp</option>
          <option value="OTHER">Другое</option>
        </select>
        <input type="search" [(ngModel)]="q" (input)="onSearch()"
               placeholder="Имя, телефон, компания, текст…" aria-label="Поиск" />
      </div>
    </div>

    <div class="empty" *ngIf="!loading && !leads.length">Обращений нет</div>

    <div class="lead-list">
      <article class="lead-card" *ngFor="let l of leads; trackBy: trackById" tabindex="0"
               [class.is-new]="l.status === 'NEW'" (click)="open(l.id)" (keydown.enter)="open(l.id)">
        <div class="lc-top">
          <span class="ch" [attr.data-channel]="l.channel">{{ channelLabel(l) }}</span>
          <span class="subj">{{ l.subject }}<ng-container *ngIf="l.itemsCount"> · {{ l.itemsCount }} поз.</ng-container></span>
          <span class="st" [attr.data-status]="l.status">{{ statusLabel(l.status) }}</span>
        </div>
        <div class="lc-what">{{ whatText(l) }}</div>
        <div class="lc-bottom">
          <span class="who">{{ l.contactName || '—' }}<ng-container *ngIf="l.company"> · {{ l.company }}</ng-container><ng-container *ngIf="l.contactPhone"> · {{ l.contactPhone }}</ng-container></span>
          <span class="meta">
            <span class="warn" *ngIf="l.syncError" title="Статус на сайте не обновлён — повторим автоматически">⚠</span>
            <a *ngIf="l.privateRequestId" [routerLink]="['/private-requests']" [queryParams]="{ openId: l.privateRequestId }"
               (click)="$event.stopPropagation()">{{ l.privateRequestNumber }}</a>
            <time [attr.title]="full(l.receivedAt)">{{ ago(l.receivedAt) }}</time>
          </span>
        </div>
      </article>
    </div>

    <app-lead-card [leadId]="cardId" (close)="closeCard()" (changed)="load(true)" (openLead)="open($event)"></app-lead-card>
    <app-lead-form *ngIf="formOpen" (close)="formOpen = false" (created)="onCreated($event)"></app-lead-form>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .head-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    .sync-plate { font-size: 13px; color: var(--text-muted); background: var(--surface-2); border-radius: 8px; padding: 8px 12px; margin-bottom: 12px; }
    .sync-plate.is-error { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters-right { display: flex; gap: 8px; flex-wrap: wrap; }
    .filters-right select, .filters-right input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .filters-right input { min-width: 240px; }
    .lead-list { display: flex; flex-direction: column; gap: 8px; }
    .lead-card { background: var(--surface); border: 1px solid var(--border); border-left: 3px solid transparent; border-radius: 10px; padding: 12px 14px; cursor: pointer; transition: box-shadow .15s; }
    .lead-card:hover { box-shadow: var(--shadow); }
    .lead-card:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    /* подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit, styles.scss) */
    .lead-card.is-new { border-left-color: var(--accent); background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .lc-top { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    /* чипы: 15% тинта + текстовый токен (правило kit) */
    .ch { font-size: 12px; font-weight: 600; padding: 2px 8px; border-radius: 6px; background: var(--surface-2); color: var(--text-muted); }
    .ch[data-channel="SITE"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .ch[data-channel="WHATSAPP"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .ch[data-channel="PHONE"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .subj { font-weight: 600; color: var(--text); flex: 1; min-width: 0; }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); white-space: nowrap; }
    .st[data-status="NEW"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="IN_WORK"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .st[data-status="CONVERTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .lc-what { margin-top: 6px; color: var(--text); font-size: 14px; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
    .lc-bottom { margin-top: 6px; display: flex; justify-content: space-between; gap: 8px; flex-wrap: wrap; font-size: 13px; color: var(--text-muted); }
    .who { min-width: 0; overflow-wrap: anywhere; }
    .meta { display: flex; gap: 10px; align-items: center; white-space: nowrap; }
    .meta a { color: var(--accent); text-decoration: none; font-weight: 600; }
    .warn { color: var(--warn-text); }
    @media (max-width: 900px) {
      .filters-right { width: 100%; }
      .filters-right select, .filters-right input { flex: 1; min-width: 0; font-size: 16px; }
      .lead-card { padding: 12px; }
      /* карточка не должна расти в «простыню» (порог ~140px, §12): тема не ломается между чипами,
         контакт и время — одной строкой, контакт обрезается многоточием (полностью — в карточке) */
      .subj { font-size: 14px; }
      .lc-bottom { flex-wrap: nowrap; }
      .who { flex: 1; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    }
  `]
})
export class LeadsComponent implements OnDestroy {
  leads: any[] = [];
  loading = false;
  sync: any = null;
  syncing = false;
  statusKey = 'NEW,IN_WORK';
  channel = '';
  q = '';
  cardId: number | null = null;
  formOpen = false;
  readonly statusFilters = [
    { key: 'NEW,IN_WORK', label: 'Активные' },
    { key: 'NEW', label: 'Новые' },
    { key: 'IN_WORK', label: 'В работе' },
    { key: 'CONVERTED', label: 'Заявка создана' },
    { key: 'CLOSED', label: 'Закрытые' },
    { key: 'ALL', label: 'Все' },
  ];
  private searchTimer: any = null;
  private readonly refreshTimer: any;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef) {
    // Без detectChanges: первое значение приходит синхронно ещё в конструкторе, до создания вида
    // (dev-режим падает «Should be run in update mode»); смену ?openId отрисует обычный цикл роутера.
    this.route.queryParams.subscribe(p => {
      this.cardId = p['openId'] ? +p['openId'] : null;
    });
    this.load();
    this.loadSync();
    // заявки с сайта приходят фоном (~90 с) — список и плашка обновляются, пока экран открыт
    this.refreshTimer = setInterval(() => { this.load(true); this.loadSync(); }, 60000);
  }

  ngOnDestroy() {
    clearInterval(this.refreshTimer);
    clearTimeout(this.searchTimer);
  }

  load(silent = false) {
    if (!silent) this.loading = true;
    this.api.getLeads({ status: this.statusKey, channel: this.channel || undefined, q: this.q.trim() || undefined }).subscribe({
      next: d => { this.leads = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => {
        this.loading = false;
        if (!silent) this.notify.error('Ошибка загрузки обращений: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  loadSync() {
    this.api.getLeadSyncStatus().subscribe({ next: s => { this.sync = s; this.cdr.detectChanges(); }, error: () => {} });
  }

  runSync() {
    this.syncing = true;
    this.api.runLeadSync().subscribe({
      next: s => {
        this.sync = s;
        this.syncing = false;
        if (s?.lastError) this.notify.error('westmed.kz: ' + s.lastError);
        else this.notify.success(s?.lastCreated ? `Новых обращений: ${s.lastCreated}` : 'Новых обращений нет');
        this.load();
      },
      error: e => { this.syncing = false; this.notify.error(e.error?.message || 'Не удалось проверить сайт'); this.cdr.detectChanges(); },
    });
  }

  setStatus(key: string) { this.statusKey = key; this.load(); }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  open(id: number) {
    this.router.navigate([], { relativeTo: this.route, queryParams: { openId: id }, queryParamsHandling: 'merge' });
  }

  closeCard() {
    this.router.navigate([], { relativeTo: this.route, queryParams: { openId: null }, queryParamsHandling: 'merge' });
  }

  onCreated(lead: any) {
    this.formOpen = false;
    this.load();
    this.open(lead.id);
  }

  syncText(): string {
    const s = this.sync;
    if (!s.enabled) return 'westmed.kz: приём заявок выключен';
    if (s.lastError) return 'westmed.kz: ' + s.lastError;
    if (s.lastSuccessAt) return 'westmed.kz · синхронизировано ' + relativeTime(s.lastSuccessAt);
    return 'westmed.kz · ещё не синхронизировано';
  }

  whatText(l: any): string {
    if (l.itemsPreview?.length) {
      const rest = l.itemsCount - l.itemsPreview.length;
      return l.itemsPreview.join(' · ') + (rest > 0 ? ` · ещё ${rest}` : '');
    }
    return l.messagePreview || '—';
  }

  trackById(_: number, l: any) { return l.id; }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
  statusLabel(s: string) { return LEAD_STATUS_LABELS[s] || s; }
  channelLabel(l: any) { return leadChannelLabel(l.channel, l.source); }
}
