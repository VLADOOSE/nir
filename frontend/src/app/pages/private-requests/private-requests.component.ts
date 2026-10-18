import { ChangeDetectorRef, Component, DestroyRef, OnDestroy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { PrListItem, dateText, stageTone } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

/**
 * «Частные заявки» — список (спека 2026-10-12 §5.5): «в работе / закрытые / все», поиск, «+ Новая заявка» сразу создаёт
 * черновик и открывает его страницу. Старые ссылки «?openId=» перенаправляются на страницу заявки.
 */
@Component({
  selector: 'app-private-requests',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <div>
        <h2>Частные заявки</h2>
        <p class="subtitle">Заявки клиентов {{ company }}: позиции, регистрация, цены поставщиков</p>
      </div>
      <button type="button" class="btn btn-primary" *ngIf="auth.isAdmin()" [disabled]="creating" (click)="create()">
        {{ creating ? 'Создаю…' : '+ Новая заявка' }}
      </button>
    </div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Какие заявки показать">
        <button type="button" class="chip" *ngFor="let f of filters" [class.on]="status === f.key"
                [attr.aria-pressed]="status === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Номер, клиент, телефон или позиция…"
             aria-label="Поиск заявок" />
    </div>

    <div class="error-banner load-error" *ngIf="loadError">
      <span>Список не загрузился: {{ loadError }}</span>
      <button type="button" class="btn btn-line" (click)="load()">Повторить</button>
    </div>
    <div class="empty" *ngIf="loading && !rows.length">Загрузка…</div>
    <div class="empty" *ngIf="!loading && !loadError && !rows.length">{{ emptyText() }}</div>

    <div class="list">
      <article class="card" *ngFor="let r of rows; trackBy: trackById">
        <div class="top">
          <!-- вся карточка — эта ссылка (::after растянут на карточку): Enter, новая вкладка, «копировать ссылку» -->
          <a class="num" [routerLink]="['/private-requests', r.id]" [attr.aria-label]="'Открыть заявку ' + r.number">{{ r.number }}</a>
          <span class="pr-stage" [attr.data-tone]="tone(r.stage)">{{ r.stageText }}</span>
          <!-- своя ссылка поверх растянутой ссылки карточки (.xref позиционирована): не вложенная <a> -->
          <a class="lead-mark xref" *ngIf="r.leadId" [routerLink]="['/leads']" [queryParams]="{ openId: r.leadId }"
             [attr.aria-label]="'Открыть обращение, из которого создана заявка ' + r.number">из обращения</a>
          <span class="date">{{ dateText(r.createdAt) }}</span>
        </div>
        <div class="client" [class.muted]="!r.clientLabel || r.clientLabel === 'Клиент не указан'">{{ r.clientLabel }}</div>
        <div class="bottom">{{ r.lineCount }} поз.</div>
      </article>
    </div>
  `,
  styles: [`
    :host { display: block; max-width: 1100px; }
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters input { min-width: 280px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .load-error { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .card { position: relative; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; transition: box-shadow .15s; }
    .card:hover { box-shadow: var(--shadow); }
    .top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .num { font-weight: 700; color: var(--text); text-decoration: none; }
    .num::after { content: ''; position: absolute; inset: 0; border-radius: 10px; }
    .num:focus-visible { outline: none; }
    .num:focus-visible::after { outline: 2px solid var(--accent); outline-offset: 2px; }
    /* цвет — ссылки (.xref из kit) */
    .lead-mark { font-size: 12px; }
    .date { margin-left: auto; color: var(--text-muted); font-size: 13px; }
    .client { margin-top: 6px; color: var(--text); font-size: 14px; overflow-wrap: anywhere; }
    .client.muted { color: var(--text-muted); }
    .bottom { margin-top: 4px; color: var(--text-muted); font-size: 13px; }
    @media (max-width: 900px) {
      .page-head > .btn { margin-bottom: 12px; }
      .filters input { flex: 1; min-width: 0; font-size: 16px; }
      .card { padding: 12px; }
      .client { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      /* цель нажатия «из обращения» — 40 px без сдвига раскладки (поля погашены отрицательными отступами); kit даёт
         10 px — для 12-px строки этого мало. Ниже — только строка клиента, она не нажимается */
      .lead-mark { padding-block: 13px; margin-block: -13px; }
    }
  `],
})
export class PrivateRequestsComponent implements OnDestroy {
  rows: PrListItem[] = [];
  loading = false;
  loadError = '';
  creating = false;
  status = 'OPEN';
  q = '';
  readonly filters = [{ key: 'OPEN', label: 'В работе' }, { key: 'CLOSED', label: 'Закрытые' }, { key: 'ALL', label: 'Все' }];
  readonly dateText = dateText;
  private shownQ = '';
  private searchTimer: any = null;
  private loadSub?: Subscription;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private market: MarketService, private route: ActivatedRoute, private router: Router,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {
    // старые ссылки «?openId=» (Telegram, обращения, «Входящие») — на страницу заявки, заменой записи истории
    const openId = Number(this.route.snapshot.queryParamMap.get('openId'));
    if (openId > 0) {
      this.router.navigate(['/private-requests', openId], { replaceUrl: true });
      return;
    }
    this.load();
  }

  get company() { return this.market.companyLabel(); }

  ngOnDestroy() {
    clearTimeout(this.searchTimer);
  }

  tone(stage: string) {
    return stageTone(stage);
  }

  load() {
    this.loadSub?.unsubscribe();
    const q = this.q.trim();
    this.loading = true;
    this.loadSub = this.api.getPrivateRequests({ status: this.status, q: q || undefined })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: rows => {
          this.rows = rows;
          this.shownQ = q;
          this.loading = false;
          this.loadError = '';
          this.cdr.markForCheck();
        },
        error: e => { this.loading = false; this.loadError = errorText(e); this.cdr.markForCheck(); },
      });
  }

  setStatus(key: string) {
    this.status = key;
    this.load();
  }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  create() {
    this.creating = true;
    this.api.createPrivateRequest().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      // кнопка заблокирована до конца перехода: страница — отдельный чанк, повторное нажатие создало бы вторую заявку
      next: v => { this.router.navigate(['/private-requests', v.id]).finally(() => this.creating = false); },
      error: e => { this.creating = false; this.notify.error('Заявка не создана: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  emptyText(): string {
    if (this.shownQ) return 'Ничего не нашлось';
    if (this.status === 'CLOSED') return 'Закрытых заявок нет';
    return this.auth.isAdmin() ? 'Заявок пока нет — нажмите «+ Новая заявка»' : 'Заявок пока нет';
  }

  trackById(_: number, r: PrListItem) {
    return r.id;
  }
}
