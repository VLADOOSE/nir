import { ChangeDetectorRef, Component, DestroyRef, OnDestroy, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { hintMoney } from '../../shared/price-hint';
import type { PriceHintRow } from '../../shared/price-hint';
import { dmy, listState } from '../../shared/price-list';
import type { PriceListListItem } from '../../shared/price-list';

/** «Каталог → Прайсы поставщиков» (спека 2026-10-23 §5.1, §6.2 п. 1): прайсы рынка, поиск «у кого и почём», загрузка. */
@Component({
  selector: 'app-price-lists',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink],
  template: `
    <div class="pl-page">
      <h2>Прайсы поставщиков</h2>
      <p class="subtitle">Цены из прайсов видны в заявках, тендерах и КП — без запроса цен. Закупка в чужой валюте — по нашему курсу
        («Система → Курсы валют»).</p>

      <div class="pl-upload" *ngIf="admin">
        <select [(ngModel)]="uploadSupplier" aria-label="Поставщик прайса">
          <option [ngValue]="null">Поставщик…</option>
          <option *ngFor="let d of suppliers" [ngValue]="d.id">{{ d.name }}</option>
        </select>
        <label class="btn btn-primary" [class.disabled]="!uploadSupplier || uploading">
          {{ uploading ? 'Загружаю…' : 'Загрузить прайс' }}
          <input type="file" hidden accept=".xlsx,.xls,.docx,.doc,.pdf,.png,.jpg,.jpeg" [disabled]="!uploadSupplier || uploading"
                 (change)="upload($event)" />
        </label>
        <span class="pl-hint">Excel, Word, PDF — до 15 МБ</span>
      </div>

      <input type="search" class="pl-search" [(ngModel)]="query" (input)="onQuery()" placeholder="Найти в прайсах: модель, наименование, поставщик"
             aria-label="Поиск по прайсам" />
      <div class="pl-hits" *ngIf="query.trim().length >= 2">
        <p class="pl-hint" *ngIf="searching">Ищу…</p>
        <p class="pl-hint" *ngIf="!searching && !hits.length">Ничего не нашлось</p>
        <a class="pl-hit" *ngFor="let h of hits" [routerLink]="['/price-lists', h.priceListId]">
          <span class="pl-h-name">{{ h.name }}</span>
          <span class="pl-h-meta">{{ h.supplierName }} · прайс {{ dmy(h.listDate) }}<span class="pl-exp" *ngIf="h.expired"> · истёк</span></span>
          <span class="pl-h-price">{{ money(h.price, h.currency) }}<ng-container *ngIf="h.currency !== marketCurrency">
            <ng-container *ngIf="h.converted != null; else noRate"> → {{ money(h.converted, marketCurrency) }}</ng-container>
            <ng-template #noRate> — впишите наш курс</ng-template></ng-container>
            <span class="pl-today" *ngIf="h.today != null"> · сегодня {{ money(h.today, marketCurrency) }}</span></span>
          <span class="pl-h-ru" *ngIf="h.regStatus" [class.pl-bad]="h.regStatus !== 'ACTIVE'">{{ ruText(h.regStatus) }}</span>
        </a>
      </div>

      <div class="pl-filter" *ngIf="supplierFilter">
        Поставщик: <b>{{ supplierName(supplierFilter) }}</b> · <a class="xref" routerLink="/price-lists">все прайсы</a>
      </div>
      <p class="pl-hint" *ngIf="loading">Загружаю…</p>
      <p class="pl-hint" *ngIf="!loading && !lists.length">Прайсов пока нет.</p>
      <div class="pl-list">
        <a class="pl-card" *ngFor="let p of lists" [routerLink]="['/price-lists', p.id]">
          <span class="pl-c-sup">{{ p.distributorName }}</span>
          <span class="pl-chip" [attr.data-tone]="state(p).tone">{{ state(p).text }}</span>
          <span class="pl-c-meta">прайс {{ dmy(p.listDate) || dmy(p.createdAt.slice(0, 10)) }} · {{ p.validTo ? 'до ' + dmy(p.validTo) : 'срок не указан' }}
            · {{ p.currency || '—' }} · позиций: {{ p.itemCount }}</span>
          <span class="pl-c-file">{{ p.fileName }}<ng-container *ngIf="p.createdBy"> · {{ p.createdBy }}</ng-container></span>
        </a>
      </div>
    </div>
  `,
  styles: [`
    .pl-page { max-width: 980px; display: flex; flex-direction: column; gap: 10px; }
    .pl-page h2 { margin: 0; }
    .pl-upload { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }
    .pl-upload select { min-width: 220px; max-width: 100%; }
    .pl-upload .disabled { opacity: .55; pointer-events: none; }
    .pl-hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .pl-search { padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--text); }
    .pl-hits, .pl-list { display: flex; flex-direction: column; gap: 6px; }
    .pl-hit, .pl-card { display: grid; grid-template-columns: 1fr auto; gap: 2px 12px; padding: 8px 12px; border: 1px solid var(--border);
                        border-radius: 8px; background: var(--surface); color: var(--text); text-decoration: none; }
    .pl-hit:hover, .pl-card:hover { border-color: var(--accent); }
    .pl-h-name, .pl-c-sup { overflow-wrap: anywhere; font-weight: 600; }
    .pl-h-meta, .pl-c-meta, .pl-c-file { font-size: 12px; color: var(--text-muted); grid-column: 1; }
    .pl-h-price { grid-column: 2; grid-row: 1; text-align: right; white-space: nowrap; }
    .pl-h-ru { grid-column: 2; font-size: 12px; color: var(--success-text); text-align: right; }
    .pl-h-ru.pl-bad, .pl-exp { color: var(--warn-text); }
    .pl-today { color: var(--text-muted); font-size: 12px; }
    .pl-chip { grid-column: 2; grid-row: 1; justify-self: end; font-size: 12px; padding: 2px 9px; border-radius: 999px; }
    .pl-chip[data-tone="ok"] { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .pl-chip[data-tone="warn"] { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .pl-chip[data-tone="muted"] { color: var(--text-muted); border: 1px solid var(--border); }
    .pl-filter { font-size: 13px; }
    @media (max-width: 900px) {
      .pl-hit, .pl-card { grid-template-columns: 1fr; }
      .pl-h-price, .pl-h-ru, .pl-chip { grid-column: 1; grid-row: auto; text-align: left; justify-self: start; white-space: normal; }
      .pl-search { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class PriceListsComponent implements OnInit, OnDestroy {
  lists: PriceListListItem[] = [];
  suppliers: { id: number; name: string }[] = [];
  supplierFilter: number | null = null;
  uploadSupplier: number | null = null;
  uploading = false;
  loading = true;
  query = '';
  hits: PriceHintRow[] = [];
  searching = false;
  private timer: any = null;
  private sub?: Subscription;
  readonly dmy = dmy;
  readonly state = listState;

  constructor(private api: ApiService, private auth: AuthService, private market: MarketService, private notify: NotificationService,
              private route: ActivatedRoute, private router: Router, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }
  get marketCurrency(): string { return this.market.value === 'KZ' ? 'KZT' : 'RUB'; }

  ngOnInit() {
    this.api.getDistributors().subscribe({ next: d => { this.suppliers = d.map(x => ({ id: x.id, name: x.name })); this.cdr.markForCheck(); } });
    this.route.queryParamMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(q => {
      const s = Number(q.get('supplier'));
      this.supplierFilter = Number.isFinite(s) && s > 0 ? s : null;
      if (this.supplierFilter) this.uploadSupplier = this.supplierFilter;
      this.load();
    });
  }

  ngOnDestroy() { clearTimeout(this.timer); }

  private load() {
    this.loading = true;
    this.api.getPriceLists(this.supplierFilter).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: l => { this.lists = l; this.loading = false; this.cdr.markForCheck(); },
      error: e => { this.loading = false; this.notify.error('Прайсы не загрузились: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  supplierName(id: number): string { return this.suppliers.find(s => s.id === id)?.name ?? '№ ' + id; }
  money(v: number | null, c: string | null) { return hintMoney(v, c); }

  ruText(s: string): string {
    return s === 'ACTIVE' ? 'РУ ✓' : s === 'EXPIRED' ? 'РУ истекло' : s === 'NONE' ? 'нет РУ' : 'РУ не найдено';
  }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.sub?.unsubscribe();
    if (q.length < 2) { this.hits = []; this.searching = false; this.cdr.markForCheck(); return; }
    this.searching = true;
    this.sub = this.api.searchPriceLists(q).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: h => { this.hits = h; this.searching = false; this.cdr.markForCheck(); },
      error: e => { this.searching = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  upload(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file || !this.uploadSupplier) return;
    if (file.size > 15 * 1024 * 1024) { this.notify.error('Файл больше 15 МБ — уменьшите его и загрузите снова'); return; }
    this.uploading = true;
    this.api.uploadPriceList(this.uploadSupplier, file).subscribe({
      next: v => { this.uploading = false; this.router.navigate(['/price-lists', v.id]); },
      error: e => {
        this.uploading = false;
        this.notify.error(e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : errorText(e));
        this.cdr.markForCheck();
      },
    });
  }
}
