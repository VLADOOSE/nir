import { ChangeDetectorRef, Component, DestroyRef, HostListener, OnDestroy } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { OFFER_STATUS_LABELS, OfferStatus, dateText, money } from '../../shared/client-offer';

/** «КП клиентам» — журнал (спека §8.1): поиск по клиенту, номеру и позициям, фильтр статуса, дублирование. */
@Component({
  selector: 'app-client-offers',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, RouterLink],
  template: `
    <div class="page-head">
      <div>
        <h2>КП клиентам</h2>
        <p class="subtitle">Коммерческие предложения {{ company }}: черновики, отправленные, принятые</p>
      </div>
      <button type="button" class="btn btn-primary" *ngIf="auth.isAdmin()" [disabled]="creating" (click)="create()">
        {{ creating ? 'Создаю…' : '+ Новое КП' }}
      </button>
    </div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Статус">
        <button type="button" class="chip" *ngFor="let f of statusFilters" [class.on]="statusKey === f.key"
                [attr.aria-pressed]="statusKey === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Клиент, № или позиция…" aria-label="Поиск КП" />
    </div>

    <div class="error-banner load-error" *ngIf="loadError">
      <span>Журнал не загрузился: {{ loadError }}</span>
      <button type="button" class="btn btn-line" (click)="load()">Повторить</button>
    </div>
    <div class="empty" *ngIf="!loading && !loadError && !offers.length">{{ emptyText() }}</div>

    <div class="list">
      <article class="card" *ngFor="let o of offers; trackBy: trackById">
        <div class="top">
          <!-- Вся карточка — эта ссылка (::after растянут на карточку): Enter, новая вкладка, «копировать ссылку».
               «⋯» стоит в разметке позже и поэтому рисуется поверх неё. -->
          <a class="num" [routerLink]="['/client-offers', o.id]" [attr.aria-label]="'Открыть КП № ' + o.number">№ {{ o.number }}</a>
          <span class="date">{{ dateText(o.offerDate) }}</span>
          <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
          <span class="menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
            <button type="button" class="btn btn-more" (click)="toggleMenu(o.id)"
                    [attr.aria-expanded]="menuId === o.id" [attr.aria-label]="'Действия с КП № ' + o.number">⋯</button>
            <span class="row-menu" *ngIf="menuId === o.id">
              <button type="button" (click)="duplicate(o)">Дублировать</button>
              <button type="button" class="danger" *ngIf="o.status === 'DRAFT'" (click)="remove(o)">Удалить</button>
            </span>
          </span>
        </div>
        <div class="client">{{ o.clientName || 'Клиент не указан' }}</div>
        <div class="bottom">
          <span>{{ o.itemCount }} поз.</span>
          <span class="sum">{{ money(o.totalAmount) }}&nbsp;{{ symbol }}</span>
        </div>
      </article>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters input { min-width: 260px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .load-error { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .card { position: relative; background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; transition: box-shadow .15s; }
    .card:hover { box-shadow: var(--shadow); }
    .top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .num { font-weight: 700; color: var(--text); text-decoration: none; }
    /* растянутая ссылка: клик в любом месте карточки открывает КП; рамка фокуса — вокруг всей карточки */
    .num::after { content: ''; position: absolute; inset: 0; border-radius: 10px; }
    .num:focus-visible { outline: none; }
    .num:focus-visible::after { outline: 2px solid var(--accent); outline-offset: 2px; }
    .date { color: var(--text-muted); font-size: 13px; }
    /* чипы статуса: 15% тинта + текстовый токен (правило kit); черновик — как .badge-DRAFT
       (--text-muted на --surface-2 даёт 4,39:1 — ниже 4,5:1 для 12px) */
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    /* позиционирован и стоит после ссылки — рисуется поверх её ::after; z-index не ставим: он запер бы
       выпадающее меню (z-index 20 из kit) под «⋯» соседней карточки */
    .menu { margin-left: auto; position: relative; }
    .client { margin-top: 6px; color: var(--text); font-size: 14px; overflow-wrap: anywhere; }
    .bottom { margin-top: 6px; display: flex; justify-content: space-between; gap: 8px; color: var(--text-muted); font-size: 13px; }
    .sum { font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    @media (max-width: 900px) {
      /* кнопка уезжает под подзаголовок — без отступа она вплотную к чипам статуса */
      .page-head > .btn { margin-bottom: 12px; }
      .filters input { flex: 1; min-width: 0; font-size: 16px; }
      .card { padding: 12px; }
      .client { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .btn-more { min-width: 40px; }
    }
  `],
})
export class ClientOffersComponent implements OnDestroy {
  offers: any[] = [];
  loading = false;
  loadError = '';
  creating = false;
  statusKey = 'ALL';
  q = '';
  menuId: number | null = null;
  readonly statusFilters = [
    { key: 'ALL', label: 'Все' }, { key: 'DRAFT', label: 'Черновики' }, { key: 'SENT', label: 'Отправлены' },
    { key: 'ACCEPTED', label: 'Приняты' }, { key: 'REJECTED', label: 'Отклонены' },
  ];
  dateText = dateText;
  money = money;
  /** Поиск и статус, по которым показан список, — для текста пустого журнала (поле поиска к этому моменту может быть другим). */
  private shownQ = '';
  private shownStatus = 'ALL';
  private searchTimer: any = null;
  private loadSub?: Subscription;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private confirm: ConfirmService, private market: MarketService, private router: Router,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {
    this.load();
  }

  get company() { return this.market.companyLabel(); }
  get symbol() { return this.market.symbol(); }

  ngOnDestroy() {
    clearTimeout(this.searchTimer);
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeMenu() {
    this.menuId = null;
  }

  load() {
    // ответ прежнего запроса (другой фильтр или поиск) не должен перетереть свежий
    this.loadSub?.unsubscribe();
    const q = this.q.trim();
    const status = this.statusKey;
    this.loading = true;
    this.loadSub = this.api.getClientOffers({ status, q: q || undefined }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: d => {
        this.offers = d;
        this.shownQ = q;
        this.shownStatus = status;
        this.loading = false;
        this.loadError = '';
        this.cdr.detectChanges();
      },
      error: e => { this.loading = false; this.loadError = errorText(e); this.cdr.detectChanges(); },
    });
  }

  setStatus(key: string) {
    this.statusKey = key;
    this.load();
  }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  create() {
    this.creating = true;
    this.api.createClientOffer().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      // Кнопка заблокирована до конца перехода: редактор — отдельный чанк, и на медленной сети
      // повторное нажатие успело бы создать второе КП (и занять ещё один «исх. №»).
      next: o => { this.router.navigate(['/client-offers', o.id]).finally(() => this.creating = false); },
      error: e => { this.creating = false; this.notify.error('КП не создано: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  toggleMenu(id: number) {
    this.menuId = this.menuId === id ? null : id;
  }

  duplicate(o: any) {
    this.menuId = null;
    this.api.duplicateClientOffer(o.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: copy => { this.notify.success(`Создана копия — КП № ${copy.number}`); this.router.navigate(['/client-offers', copy.id]); },
      error: e => this.notify.error('Копия не создана: ' + errorText(e)),
    });
  }

  remove(o: any) {
    this.menuId = null;
    this.confirm.ask(`Удалить черновик КП № ${o.number}?`, 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteClientOffer(o.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => { this.notify.success('Черновик удалён'); this.load(); },
          // сервер сам объясняет отказ («Удалить можно только черновик…» — статус сменили в другой вкладке)
          error: e => this.notify.error(e?.error?.message || 'Не удалось удалить: ' + errorText(e)),
        });
      });
  }

  emptyText(): string {
    if (this.shownQ) return 'Ничего не нашлось';
    if (this.shownStatus !== 'ALL') return 'КП в этом статусе нет';
    return this.auth.isAdmin() ? 'КП пока нет — нажмите «+ Новое КП»' : 'КП пока нет';
  }

  statusLabel(s: OfferStatus) {
    return OFFER_STATUS_LABELS[s] || s;
  }

  trackById(_: number, o: any) {
    return o.id;
  }
}

/**
 * Текст ошибки сервера (ApiError.message — его несут все ответы бэкенда, и 5xx тоже). Нет ответа или 5xx без него —
 * это прокси перед лежащим бэкендом (nginx 502/504, dev-прокси 500 с пустым телом): «нет связи с сервером».
 */
function errorText(e: any): string {
  if (e?.error?.message) return e.error.message;
  return !e?.status || e.status >= 500 ? 'нет связи с сервером' : `ошибка ${e.status}`;
}
