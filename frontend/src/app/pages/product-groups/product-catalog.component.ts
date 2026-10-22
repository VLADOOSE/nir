import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { whenText } from '../../shared/product-watch-pages';
import type { ProductCatalogItem, ProductCatalogState } from '../../shared/product-watch';

type Filter = 'ALL' | 'NO_ATTR' | 'BAD_RU';

/**
 * «Наша продукция → Товары» (спека 2026-10-20-product-spec-fit §8.2): товары westmed.kz, их РУ по ndda.kz и число
 * характеристик — видно, у каких товаров сверке ТЗ не с чем сравнивать.
 */
@Component({
  selector: 'app-product-catalog',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <p class="pc-note" *ngIf="loading">Загружаю…</p>
    <p class="pc-note" *ngIf="state && !state.kz">Только для Казахстана — переключите рынок на West-Med (KZ).</p>
    <ng-container *ngIf="state?.kz">
      <div class="pc-bar">
        <div class="pc-chips" role="group" aria-label="Какие товары показать">
          <button type="button" class="pc-chip" *ngFor="let f of filters" [class.pc-on]="filter === f.key"
                  [attr.aria-pressed]="filter === f.key" (click)="filter = f.key">{{ f.label }} · {{ count(f.key) }}</button>
        </div>
        <span class="pc-checked">{{ state!.checkedAt ? 'Проверено РУ: ' + when(state!.checkedAt) : 'РУ ещё не проверялись' }}</span>
        <button type="button" class="btn btn-line pc-small" *ngIf="admin" [disabled]="syncing" (click)="sync()">Обновить</button>
      </div>
      <p class="pc-note" *ngIf="!shown().length">{{ state!.items.length ? 'Нет товаров под этот фильтр.' : 'Каталог сайта ещё не загружен.' }}</p>
      <div class="pc-list">
        <div class="pc-row" *ngFor="let p of shown(); trackBy: byId">
          <div class="pc-name">
            <a *ngIf="p.url; else plain" [href]="p.url" target="_blank" rel="noopener">{{ p.name }} ↗</a>
            <ng-template #plain>{{ p.name }}</ng-template>
            <span class="pc-brand" *ngIf="p.brand">{{ p.brand }}</span>
          </div>
          <div class="pc-meta">
            <span class="pc-mkt" *ngIf="hint(p) as h">{{ h }}</span>
            <span class="pc-ru" [class]="'pc-' + ruTone(p)" [title]="p.regNumber ? 'РУ ' + p.regNumber : 'Номер РУ на сайте не указан'">РУ {{ ruText(p) }}</span>
            <span class="pc-attr" [class.pc-attr-none]="!p.attributesCount">{{ p.attributesCount ? 'характеристик: ' + p.attributesCount : 'нет характеристик' }}</span>
          </div>
        </div>
      </div>
    </ng-container>
  `,
  styles: [`
    .pc-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .pc-bar { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; }
    .pc-chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .pc-chip { font-size: 12px; padding: 4px 12px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface);
               color: var(--text); cursor: pointer; }
    .pc-chip.pc-on { background: color-mix(in srgb, var(--accent) 15%, transparent); border-color: var(--accent); color: var(--accent); }
    .pc-checked { font-size: 13px; color: var(--text-muted); }
    .pc-small { padding: 4px 10px; font-size: 12px; }
    .pc-list { display: flex; flex-direction: column; gap: 6px; }
    .pc-row { display: grid; grid-template-columns: 1fr auto; gap: 4px 12px; align-items: center; padding: 8px 12px;
              border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .pc-name { min-width: 0; overflow-wrap: anywhere; color: var(--text); }
    .pc-name a { color: var(--accent); }
    .pc-brand { margin-left: 8px; font-size: 12px; color: var(--text-muted); }
    .pc-meta { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; justify-content: flex-end; }
    .pc-mkt { font-size: 12px; color: var(--text-muted); }
    .pc-ru { font-size: 12px; padding: 2px 9px; border-radius: 999px; white-space: nowrap; }
    .pc-ok { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .pc-warn { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .pc-muted { border: 1px solid var(--border); color: var(--text-muted); }
    .pc-attr { font-size: 12px; color: var(--text-muted); white-space: nowrap; }
    .pc-attr.pc-attr-none { color: var(--warn-text); }
    @media (max-width: 900px) {
      .pc-row { grid-template-columns: 1fr; }
      .pc-meta { justify-content: flex-start; }
    }
  `],
})
export class ProductCatalogComponent implements OnInit {
  state: ProductCatalogState | null = null;
  loading = true;
  syncing = false;
  filter: Filter = 'ALL';
  readonly filters: { key: Filter; label: string }[] = [
    { key: 'ALL', label: 'Все' }, { key: 'NO_ATTR', label: 'Без характеристик' }, { key: 'BAD_RU', label: 'РУ истекло или не найдено' },
  ];
  readonly when = whenText;

  constructor(private api: ApiService, private auth: AuthService, private notify: NotificationService,
              private cdr: ChangeDetectorRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.api.getProductCatalog().subscribe({
      next: s => {
        this.state = s;
        this.loading = false;
        this.cdr.detectChanges();
        if (s.kz && s.items.length) {
          this.api.marketHints(s.items.map(p => p.name)).subscribe({
            next: hs => { hs.forEach(h => { if (h.line) this.hints.set(h.text, h.line); }); this.cdr.detectChanges(); },
            error: () => { /* без подсказок */ },
          });
        }
      },
      error: e => { this.loading = false; this.notify.error('Товары не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  private passes(p: ProductCatalogItem, f: Filter): boolean {
    if (f === 'NO_ATTR') return !p.attributesCount;
    if (f === 'BAD_RU') return p.regStatus === 'EXPIRED' || p.regStatus === 'NOT_FOUND';
    return true;
  }

  /** «госзакуп: медиана …» по названию товара (спека 2026-10-22-tender-results-market-prices §10). */
  private hints = new Map<string, string>();
  hint(p: ProductCatalogItem): string | null { return this.hints.get(p.name) ?? null; }

  shown(): ProductCatalogItem[] { return (this.state?.items || []).filter(p => this.passes(p, this.filter)); }
  count(f: Filter): number { return (this.state?.items || []).filter(p => this.passes(p, f)).length; }
  byId(_: number, p: ProductCatalogItem): number { return p.id; }

  ruText(p: ProductCatalogItem): string {
    switch (p.regStatus) {
      case 'ACTIVE': return 'действует' + (p.regUnlimited ? ' · бессрочно' : p.regExpires ? ' · до ' + day(p.regExpires) : '');
      case 'EXPIRED': return 'истекло' + (p.regExpires ? ' ' + day(p.regExpires) : '');
      case 'NOT_FOUND': return 'не найдено';
      case 'NOT_SPECIFIED': return 'не указано';
      default: return 'не проверено';
    }
  }

  ruTone(p: ProductCatalogItem): 'ok' | 'warn' | 'muted' {
    if (p.regStatus === 'ACTIVE') return 'ok';
    return p.regStatus === 'EXPIRED' || p.regStatus === 'NOT_FOUND' ? 'warn' : 'muted';
  }

  sync() {
    this.syncing = true;
    this.api.syncSiteCatalog().subscribe({
      next: () => { this.syncing = false; this.notify.success('Каталог и РУ обновляются — через минуту обновите вкладку'); this.cdr.detectChanges(); },
      error: e => { this.syncing = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }
}

/** «2026-06-28» → «28.06.2026» строкой: без new Date — дата без времени в браузере западнее UTC съехала бы на день. */
function day(iso: string): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})/.exec(iso);
  return m ? `${m[3]}.${m[2]}.${m[1]}` : iso;
}
