import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { KZ_REGIONS } from '../../shared/kz-regions';
import { dayText, monthText, planMoney, unitShort, whenText } from '../../shared/product-watch-pages';
import type { PlanPoint, ProductGroup, WatchStatus } from '../../shared/product-watch';

/** «Планы закупок» (спека 2026-10-19-product-watch §10.2): пункты планов с нашими видами, ещё не объявленные. */
@Component({
  selector: 'app-procurement-plans',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="pp-page">
      <h2>Планы закупок</h2>
      <p class="subtitle">Пункты планов закупок больниц с нашими видами изделий, ещё не объявленные (статус «Утверждён»):
        за месяцы до тендера можно подготовить КП, регистрацию и поставщика.</p>
      <p class="pp-note" *ngIf="!kz">Только для Казахстана — переключите рынок на West-Med (KZ).</p>
      <ng-container *ngIf="kz">
        <div class="pp-status" *ngIf="watch">
          <span *ngIf="!watch.enabled">Сбор планов выключен (PRODUCT_WATCH_ENABLED).</span>
          <span *ngIf="watch.enabled">Собрано: {{ watch.lastPlansAt ? when(watch.lastPlansAt) : 'ещё не собирали' }}<ng-container
            *ngIf="watch.lastPlans?.message"> — {{ watch.lastPlans?.message }}</ng-container></span>
          <button type="button" class="btn btn-line pp-small" *ngIf="admin && watch.enabled" [disabled]="!!watch.current" (click)="collect()">
            {{ watch.current === 'планы закупок' ? 'Собираю…' : 'Собрать сейчас' }}</button>
        </div>
        <div class="pp-filters">
          <select [(ngModel)]="group" (change)="load()" aria-label="Группа">
            <option value="">Все группы</option>
            <option *ngFor="let g of groups" [value]="'' + g.id">{{ g.name }}</option>
          </select>
          <select [(ngModel)]="region" (change)="load()" aria-label="Регион">
            <option value="">Все регионы</option>
            <option *ngFor="let r of regions" [value]="r">{{ r }}</option>
          </select>
          <select [(ngModel)]="sort" (change)="load()" aria-label="Порядок">
            <option value="month">По месяцу закупки</option><option value="amount">Дороже сверху</option><option value="new">Новые сверху</option>
          </select>
          <input type="search" [(ngModel)]="q" (keyup.enter)="load()" (search)="load()" placeholder="Название, заказчик…" aria-label="Поиск" />
          <label><input type="checkbox" [(ngModel)]="soon" (change)="load()" /> ближайшие 3 месяца</label>
          <label><input type="checkbox" [(ngModel)]="fresh" (change)="load()" /> новые за 7 дней</label>
          <label><input type="checkbox" [(ngModel)]="all" (change)="load()" /> с объявленными и снятыми</label>
        </div>
        <p class="pp-note" *ngIf="loading">Загружаю…</p>
        <p class="pp-note" *ngIf="!loading && !points.length">Ничего не найдено.</p>
        <div class="pp-card" *ngFor="let p of points" [class.pp-gone]="p.goneStatus">
          <div class="pp-top">
            <span class="pp-month" [class.pp-passed]="p.monthPassed">{{ monthText(p.year, p.month) }}<ng-container
              *ngIf="p.monthPassed && !p.goneStatus"> · месяц прошёл</ng-container></span>
            <span class="pp-star" *ngIf="p.level !== 'TYPE'" title="Назван наш товар">★</span>
            <span class="pp-gone-st" *ngIf="p.goneStatus">{{ p.goneStatus === 'снят' ? 'снят из плана' : 'объявлен: ' + p.goneStatus }}</span>
          </div>
          <div class="pp-cust">{{ p.customerName }}<ng-container *ngIf="p.region"> · {{ p.region }}</ng-container></div>
          <div class="pp-name">{{ p.name }}</div>
          <div class="pp-desc" *ngIf="p.description" [class.pp-open]="opened.has(p.id)" (click)="toggle(p.id)">{{ p.description }}</div>
          <div class="pp-sum">
            <span *ngIf="p.quantity != null">{{ qty(p.quantity) }} {{ unit(p.unit) }}</span>
            <span *ngIf="p.unitPrice != null"> × {{ money(p.unitPrice) }}</span>
            <span *ngIf="p.amount != null"> = <b>{{ money(p.amount) }} ₸</b></span>
            <span class="pp-method" *ngIf="p.tradeMethod"> · {{ p.tradeMethod }}</span>
          </div>
          <div class="pp-groups">{{ p.groups.join(' · ') }} · в плане с {{ day(p.firstSeenAt) }}</div>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .pp-page { max-width: 1000px; display: flex; flex-direction: column; gap: 10px; }
    .pp-page h2 { margin: 0; }
    .pp-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .pp-status { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--text-muted); }
    .pp-small { padding: 4px 10px; font-size: 12px; }
    .pp-filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .pp-filters select, .pp-filters input[type=search] { padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--surface); color: var(--text); font: inherit; }
    .pp-filters label { font-size: 13px; color: var(--text); display: flex; gap: 6px; align-items: center; }
    .pp-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface);
               display: flex; flex-direction: column; gap: 4px; }
    .pp-card.pp-gone { opacity: 0.6; }
    .pp-top { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .pp-month { font-weight: 600; color: var(--text); }
    .pp-month.pp-passed { color: var(--warn-text); }
    .pp-star { color: var(--success-text); font-weight: 700; }
    .pp-gone-st { color: var(--text-muted); }
    .pp-cust { font-size: 13px; color: var(--text-muted); }
    .pp-name { font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
    .pp-desc { font-size: 13px; color: var(--text); cursor: pointer; display: -webkit-box; -webkit-line-clamp: 2;
               -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
    .pp-desc.pp-open { display: block; }
    .pp-sum { font-size: 13px; color: var(--text); }
    .pp-method, .pp-groups { color: var(--text-muted); }
    .pp-groups { font-size: 12px; }
    @media (max-width: 900px) {
      .pp-filters select, .pp-filters input[type=search] { font-size: 16px; flex: 1 1 100%; }
    }
  `],
})
export class ProcurementPlansComponent implements OnInit {
  readonly regions = KZ_REGIONS;
  readonly money = planMoney;
  readonly monthText = monthText;
  readonly when = whenText;
  readonly day = dayText;
  readonly unit = unitShort;
  points: PlanPoint[] = [];
  groups: ProductGroup[] = [];
  watch: WatchStatus | null = null;
  group = ''; region = ''; sort = 'month'; q = ''; soon = false; fresh = false; all = false;
  loading = false;
  opened = new Set<number>();
  private req: Subscription | null = null;

  constructor(private api: ApiService, private auth: AuthService, private market: MarketService, private notify: NotificationService,
              private route: ActivatedRoute, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get kz(): boolean { return this.market.value === 'KZ'; }
  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    if (!this.kz) return;
    this.fresh = this.route.snapshot.queryParamMap.get('fresh') === '1';   // ссылка из сводки в Telegram
    this.api.getProductGroups().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { this.groups = s.groups.filter(g => !g.brandGroup); this.watch = s.watch; this.cdr.detectChanges(); },
      error: () => {},
    });
    this.load();
  }

  load() {
    const params: Record<string, string> = { sort: this.sort, limit: '500' };
    if (this.group) params['group'] = this.group;
    if (this.region) params['region'] = this.region;
    if (this.q.trim()) params['q'] = this.q.trim();
    if (this.soon) params['soon'] = 'true';
    if (this.fresh) params['fresh'] = 'true';
    if (this.all) params['all'] = 'true';
    this.req?.unsubscribe();   // ответ на прежние фильтры не перетирает новый
    this.loading = true;
    this.req = this.api.getProcurementPlans(params).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ps => { this.points = ps; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Планы не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  collect() {
    this.api.collectProcurementPlans().subscribe({
      next: w => { this.watch = w; this.notify.success('Сбор планов запущен — займёт несколько минут'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  toggle(id: number) { if (this.opened.has(id)) this.opened.delete(id); else this.opened.add(id); }

  qty(n: number): string { return Number.isInteger(n) ? String(n) : String(n).replace('.', ','); }
}
