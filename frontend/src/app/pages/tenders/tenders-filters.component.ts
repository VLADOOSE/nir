import { Component, Input, Output, EventEmitter } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';

export interface TendersFilters {
  query: string; status: string; sortMode: string; stage: string; platform: string;
  facilityId: number | null; deadlineFrom: string; deadlineTo: string; region: string;
}

@Component({
  selector: 'app-tenders-filters',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="filters-row">
      <input class="f-search" type="text" placeholder="Поиск по номеру или описанию…" [(ngModel)]="filters.query" (input)="emit()" />
      <select class="f-sel" [(ngModel)]="filters.status" (change)="emit()" title="Статус">
        <option value="">Все статусы</option><option value="DRAFT">Подготовка</option>
        <option value="ACTIVE">Приём заявок</option><option value="COMPLETED">Завершён</option><option value="CANCELLED">Отменён</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.sortMode" (change)="emit()" title="Сортировка">
        <option value="published">Сначала новые</option><option value="deadline">Скоро дедлайн</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.stage" (change)="emit()" title="Стадия">
        <option value="">Все стадии</option><option value="NOT_STARTED">Не начат</option>
        <option value="REQUESTED">Запрос отправлен</option><option value="PRICED">Есть цены</option><option value="WINNER_SELECTED">Победитель выбран</option>
      </select>
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.platform" (change)="emit()" title="Площадка">
        <option value="">Все площадки</option><option value="GOSZAKUP">Госзакуп</option><option value="SK_PHARMACY">СК-Фармация</option>
      </select>
      <select class="f-sel" [(ngModel)]="filters.facilityId" (change)="emit()" title="Учреждение">
        <option [ngValue]="null">Все учреждения</option>
        <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
      </select>
      <input class="f-date" type="date" [(ngModel)]="filters.deadlineFrom" (change)="emit()" title="Дедлайн от" />
      <input class="f-date" type="date" [(ngModel)]="filters.deadlineTo" (change)="emit()" title="Дедлайн до" />
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.region" (change)="emit()" title="Регион">
        <option value="">Все регионы</option><option [value]="noRegionValue">Регион не указан</option>
        <option *ngFor="let r of regions" [value]="r">{{ r }}</option>
      </select>
      <button class="f-reset" (click)="reset.emit()">Сбросить</button>
    </div>

    <div class="filters-mobile">
      <input class="f-search" type="text" placeholder="Поиск по тендерам…" [(ngModel)]="filters.query" (input)="emit()" />
      <div class="fm-bar">
        <button class="fm-open" (click)="sheetOpen = true"><svg viewBox="0 0 24 24" width="15" height="15" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><path d="M3 5h18M6 12h12M10 19h4"/></svg> Фильтры<span class="fm-count" *ngIf="activeChips.length"> · {{ activeChips.length }}</span></button>
        <button class="fm-reset" *ngIf="activeChips.length" (click)="reset.emit()">Сбросить</button>
      </div>
      <div class="fm-chips" *ngIf="activeChips.length">
        <span class="fm-chip" *ngFor="let c of activeChips">{{ c.label }}<button (click)="clear(c.key)">✕</button></span>
      </div>
    </div>

    <div class="fm-backdrop" *ngIf="sheetOpen" (click)="sheetOpen = false"></div>
    <div class="fm-sheet" [class.open]="sheetOpen">
      <div class="fm-sheet-head"><b>Фильтры</b><button (click)="sheetOpen = false">✕</button></div>
      <label>Статус<select [(ngModel)]="filters.status" (change)="emit()"><option value="">Все</option><option value="DRAFT">Подготовка</option><option value="ACTIVE">Приём заявок</option><option value="COMPLETED">Завершён</option><option value="CANCELLED">Отменён</option></select></label>
      <label>Сортировка<select [(ngModel)]="filters.sortMode" (change)="emit()"><option value="published">Сначала новые</option><option value="deadline">Скоро дедлайн</option></select></label>
      <label>Стадия<select [(ngModel)]="filters.stage" (change)="emit()"><option value="">Все</option><option value="NOT_STARTED">Не начат</option><option value="REQUESTED">Запрос отправлен</option><option value="PRICED">Есть цены</option><option value="WINNER_SELECTED">Победитель выбран</option></select></label>
      <label *ngIf="isKz">Площадка<select [(ngModel)]="filters.platform" (change)="emit()"><option value="">Все</option><option value="GOSZAKUP">Госзакуп</option><option value="SK_PHARMACY">СК-Фармация</option></select></label>
      <label *ngIf="isKz">Регион<select [(ngModel)]="filters.region" (change)="emit()"><option value="">Все</option><option [value]="noRegionValue">Регион не указан</option><option *ngFor="let r of regions" [value]="r">{{ r }}</option></select></label>
      <label>Учреждение<select [(ngModel)]="filters.facilityId" (change)="emit()"><option [ngValue]="null">Все</option><option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option></select></label>
      <label class="fm-dates">Дедлайн<span><input type="date" [(ngModel)]="filters.deadlineFrom" (change)="emit()" /><input type="date" [(ngModel)]="filters.deadlineTo" (change)="emit()" /></span></label>
      <div class="fm-sheet-actions"><button class="fm-apply" (click)="sheetOpen = false">Применить</button><button class="fm-clear" (click)="reset.emit(); sheetOpen = false">Сброс</button></div>
    </div>
  `,
  styles: [`
    .filters-row { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
    .f-search, .f-sel, .f-date { height: 38px; border: 1px solid var(--border); border-radius: 8px; font-size: 14px; background: var(--surface); color: var(--text); box-sizing: border-box; }
    .f-search { flex: 1 1 220px; min-width: 180px; padding: 0 14px; }
    .f-search:focus, .f-sel:focus, .f-date:focus { outline: none; border-color: var(--accent); }
    .f-sel { max-width: 220px; padding: 0 34px 0 12px; appearance: none; -webkit-appearance: none; cursor: pointer;
      background-image: url("data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='12' height='12' viewBox='0 0 24 24' fill='none' stroke='%23888' stroke-width='2'><path d='M6 9l6 6 6-6'/></svg>");
      background-repeat: no-repeat; background-position: right 12px center; }
    .f-date { padding: 0 10px; }
    .f-reset { height: 38px; background: var(--surface-2); color: var(--text); padding: 0 16px; border: 1px solid var(--border); border-radius: 8px; cursor: pointer; font-size: 13px; }
    .f-reset:hover { background: var(--border); }

    .filters-mobile { display: none; }
    .fm-backdrop, .fm-sheet { display: none; }

    @media (max-width: 900px) {
      .filters-row { display: none; }
      .filters-mobile { display: block; margin-bottom: 12px; }
      .filters-mobile .f-search { width: 100%; height: 44px; font-size: 16px; margin-bottom: 8px; }
      .fm-bar { display: flex; gap: 8px; }
      .fm-open { flex: 1; height: 44px; display: inline-flex; align-items: center; justify-content: center; gap: 8px; background: var(--surface); color: var(--text); border: 1px solid var(--border); border-radius: 10px; font-size: 15px; cursor: pointer; }
      .fm-count { color: var(--accent); font-weight: 600; }
      .fm-reset { height: 44px; padding: 0 16px; background: var(--surface-2); color: var(--text); border: 1px solid var(--border); border-radius: 10px; cursor: pointer; }
      .fm-chips { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 8px; }
      .fm-chip { display: inline-flex; align-items: center; gap: 6px; background: color-mix(in srgb, var(--accent) 14%, transparent); color: var(--accent); border-radius: 999px; padding: 4px 6px 4px 12px; font-size: 13px; }
      .fm-chip button { background: none; border: none; color: inherit; cursor: pointer; font-size: 13px; line-height: 1; padding: 2px; }
      .fm-backdrop { display: block; position: fixed; inset: 0; background: rgba(0,0,0,0.45); z-index: 300; }
      .fm-sheet { display: flex; flex-direction: column; gap: 12px; position: fixed; left: 0; right: 0; bottom: 0; z-index: 310; background: var(--surface); border-radius: 16px 16px 0 0; padding: 16px 16px calc(16px + env(safe-area-inset-bottom)); max-height: 85vh; overflow-y: auto; transform: translateY(100%); transition: transform 0.25s ease; }
      .fm-sheet.open { transform: translateY(0); }
      .fm-sheet-head { display: flex; justify-content: space-between; align-items: center; font-size: 17px; color: var(--text); }
      .fm-sheet-head button { background: none; border: none; font-size: 20px; color: var(--text-muted); cursor: pointer; }
      .fm-sheet label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: var(--text-muted); }
      .fm-sheet select, .fm-sheet input { height: 44px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); color: var(--text); font-size: 16px; padding: 0 12px; }
      .fm-dates span { display: flex; gap: 8px; }
      .fm-dates input { flex: 1; }
      .fm-sheet-actions { display: flex; gap: 10px; margin-top: 4px; }
      .fm-apply { flex: 1; height: 46px; background: var(--accent); color: var(--accent-contrast); border: none; border-radius: 10px; font-size: 15px; font-weight: 500; cursor: pointer; }
      .fm-clear { height: 46px; padding: 0 18px; background: var(--surface-2); color: var(--text); border: 1px solid var(--border); border-radius: 10px; cursor: pointer; }
    }
  `]
})
export class TendersFiltersComponent {
  @Input() filters!: TendersFilters;
  @Input() facilities: any[] = [];
  @Input() regions: string[] = [];
  @Input() isKz = false;
  @Input() noRegionValue = '__none__';
  @Output() filtersChange = new EventEmitter<TendersFilters>();
  @Output() reset = new EventEmitter<void>();

  sheetOpen = false;
  private static STATUS: Record<string, string> = { DRAFT: 'Подготовка', ACTIVE: 'Приём заявок', COMPLETED: 'Завершён', CANCELLED: 'Отменён' };
  private static STAGE: Record<string, string> = { NOT_STARTED: 'Не начат', REQUESTED: 'Запрос отправлен', PRICED: 'Есть цены', WINNER_SELECTED: 'Победитель выбран' };
  private static PLATFORM: Record<string, string> = { GOSZAKUP: 'Госзакуп', SK_PHARMACY: 'СК-Фармация' };

  emit() { this.filtersChange.emit({ ...this.filters }); }

  get activeChips(): { label: string; key: keyof TendersFilters }[] {
    const f = this.filters, out: { label: string; key: keyof TendersFilters }[] = [];
    if (!f) return out;
    if (f.status) out.push({ label: TendersFiltersComponent.STATUS[f.status] || f.status, key: 'status' });
    if (f.stage) out.push({ label: TendersFiltersComponent.STAGE[f.stage] || f.stage, key: 'stage' });
    if (f.platform) out.push({ label: TendersFiltersComponent.PLATFORM[f.platform] || f.platform, key: 'platform' });
    if (f.region) out.push({ label: f.region === this.noRegionValue ? 'Без региона' : f.region, key: 'region' });
    if (f.facilityId != null) out.push({ label: (this.facilities.find(x => x.id === f.facilityId)?.name) || 'Учреждение', key: 'facilityId' });
    if (f.deadlineFrom) out.push({ label: 'от ' + f.deadlineFrom, key: 'deadlineFrom' });
    if (f.deadlineTo) out.push({ label: 'до ' + f.deadlineTo, key: 'deadlineTo' });
    return out;
  }
  clear(key: keyof TendersFilters) {
    (this.filters as any)[key] = key === 'facilityId' ? null : '';
    this.emit();
  }
}
