import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';

@Component({
  selector: 'app-tender-search',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, MarketMoneyPipe],
  template: `
    <h2>Поиск тендеров</h2>
    <p class="subtitle">Расширенный поиск с фильтрацией на сервере</p>

    <form [formGroup]="filterForm" (ngSubmit)="onSearch()" class="filter-form">
      <div class="filter-row">
        <label>Статус
          <select formControlName="status">
            <option value="">Все</option>
            <option value="DRAFT">Подготовка</option>
            <option value="ACTIVE">Приём заявок</option>
            <option value="COMPLETED">Завершён</option>
          </select>
        </label>
        <label>Тип оборудования
          <select formControlName="equipType">
            <option value="">Все</option>
            <option value="УЗИ">УЗИ</option>
            <option value="Рентген">Рентген</option>
            <option value="ИВЛ">ИВЛ</option>
            <option value="Монитор">Монитор</option>
          </select>
        </label>
        <label>Учреждение
          <select formControlName="facilityId">
            <option value="">Все</option>
            <option *ngFor="let f of facilities" [value]="f.id">{{ f.name }}</option>
          </select>
        </label>
      </div>
      <div class="filter-row">
        <label>Цена от<input type="number" formControlName="minCost" /></label>
        <label>Цена до<input type="number" formControlName="maxCost" /></label>
        <label>Дата окончания от<input type="date" formControlName="dateFrom" /></label>
        <label>Дата окончания до<input type="date" formControlName="dateTo" /></label>
      </div>
      <div class="filter-actions">
        <button type="submit" class="btn btn-search">Найти</button>
        <button type="button" class="btn btn-reset" (click)="onReset()">Сбросить</button>
      </div>
    </form>

    <span class="counter">Найдено: {{ results.length }} результатов</span>

    <div *ngIf="results.length === 0" class="empty">Ничего не найдено</div>

    <div class="tender-card" *ngFor="let t of results" (click)="onOpen(t)">
      <div class="tender-card-header">
        <div class="tender-meta">
          <span class="tender-number">&#8470; {{ t.tenderNumber }}</span>
          <span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>
        </div>
        <div class="tender-price">{{ t.totalCost | money }}</div>
      </div>
      <div class="tender-card-title">{{ t.description || 'Без описания' }}</div>
      <div class="tender-card-details">
        <div class="detail-row">
          <div class="detail"><span class="detail-label">Заказчик</span><span>{{ t.facility?.name || '—' }}</span></div>
          <div class="detail"><span class="detail-label">Дата окончания</span><span class="deadline" [class.overdue]="isOverdue(t.deadline)">{{ formatDate(t.deadline) }}</span></div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
    /* от .counter остаётся только раскладка: цвет и размер даёт kit */
    .counter { display: block; margin-bottom: 12px; }

    .filter-form { margin-bottom: 20px; background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 16px; }
    .filter-row { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
    .filter-row label { font-size: 13px; color: var(--text); font-weight: 500; flex: 1; min-width: 160px; }
    /* фон/цвет полям задаём явно: контейнер фильтра на --surface-2, а правила
       input/select в kit намеренно нет — иначе все семь полей поиска остались бы
       белыми в тёмной теме */
    .filter-row input, .filter-row select { display: block; width: 100%; padding: 6px 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .filter-actions { display: flex; gap: 8px; }
    /* .btn/.btn-search/.btn-reset удалены целиком — цвет, ховеры и геометрию даёт kit.
       Локальный .btn был шире kit (8px 18px против 6px 14px); по решению оператора об
       унификации примитивов кнопки поиска приведены к общему виду приложения. */

    .tender-card { border: 1px solid var(--border); border-radius: 8px; padding: 16px 20px; margin-bottom: 12px; cursor: pointer; transition: box-shadow 0.2s; }
    /* в ховере было ещё border-color:#d1d5db, но и рамка карточки (#e5e7eb), и он
       схлопываются в один --border — объявление стало бы мёртвым, поэтому убрано;
       отклик несёт тень, ровно как на карточках /tenders */
    .tender-card:hover { box-shadow: var(--shadow); }
    .tender-card-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
    .tender-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .tender-number { font-weight: 600; color: var(--accent); font-size: 15px; }
    .tender-price { font-size: 18px; font-weight: 700; color: var(--text); white-space: nowrap; }
    .tender-card-title { font-size: 14px; color: var(--text); margin-bottom: 12px; line-height: 1.5; }
    .tender-card-details { margin-bottom: 4px; }
    .detail-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .detail { display: flex; flex-direction: column; }
    .detail-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; margin-bottom: 2px; }
    .detail span:not(.detail-label) { font-size: 14px; }
    .deadline { font-weight: 500; }
    /* слот color → текстовый токен (заливочный --danger как текст даёт ~3:1 в светлой);
       та же строка на карточках /tenders */
    .deadline.overdue { color: var(--danger-text); }
  `]
})
export class TenderSearchComponent {
  results: any[] = [];
  facilities: any[] = [];

  filterForm = new FormGroup({
    status: new FormControl(''),
    equipType: new FormControl(''),
    facilityId: new FormControl(''),
    minCost: new FormControl(''),
    maxCost: new FormControl(''),
    dateFrom: new FormControl(''),
    dateTo: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private router: Router) {
    this.api.getFacilities().subscribe(data => { this.facilities = data; this.cdr.detectChanges(); });
    this.onSearch();
  }

  onSearch() {
    const v = this.filterForm.value;
    const params: any = {};
    if (v.status) params.status = v.status;
    if (v.equipType) params.equipType = v.equipType;
    if (v.facilityId) params.facilityId = v.facilityId;
    if (v.minCost) params.minCost = v.minCost;
    if (v.maxCost) params.maxCost = v.maxCost;
    if (v.dateFrom) params.dateFrom = v.dateFrom;
    if (v.dateTo) params.dateTo = v.dateTo;

    this.api.searchTenders(params).subscribe({
      next: data => { this.results = data; this.cdr.detectChanges(); },
      error: err => console.error('Ошибка поиска:', err)
    });
  }

  onReset() {
    this.filterForm.reset({ status: '', equipType: '', facilityId: '', minCost: '', maxCost: '', dateFrom: '', dateTo: '' });
    this.onSearch();
  }

  onOpen(t: any) {
    this.router.navigate(['/tenders'], { queryParams: { openId: t.id } });
  }

  formatPrice(n: number): string { return n ? n.toLocaleString('ru-RU') : '0'; }
  getStatusLabel(s: string): string { return ({ DRAFT: 'Подготовка', ACTIVE: 'Приём заявок', COMPLETED: 'Завершён' } as any)[s] || s; }
  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }
  isOverdue(d: string): boolean {
    if (!d) return false;
    return new Date(d) < new Date();
  }
}
