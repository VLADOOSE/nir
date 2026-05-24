import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-tender-search',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
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
        <div class="tender-price">{{ formatPrice(t.totalCost) }} &#8381;</div>
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
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .counter { color: #6b7280; font-size: 13px; display: block; margin-bottom: 12px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }

    .filter-form { margin-bottom: 20px; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 16px; }
    .filter-row { display: flex; gap: 16px; flex-wrap: wrap; margin-bottom: 12px; }
    .filter-row label { font-size: 13px; color: #374151; font-weight: 500; flex: 1; min-width: 160px; }
    .filter-row input, .filter-row select { display: block; width: 100%; padding: 6px 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; box-sizing: border-box; }
    .filter-actions { display: flex; gap: 8px; }
    .btn { padding: 8px 18px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-search { background: #1a56db; color: #fff; }
    .btn-reset { background: #e5e7eb; color: #374151; }

    .tender-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px 20px; margin-bottom: 12px; cursor: pointer; transition: box-shadow 0.2s; }
    .tender-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.08); border-color: #d1d5db; }
    .tender-card-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
    .tender-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .tender-number { font-weight: 600; color: #1a56db; font-size: 15px; }
    .tender-price { font-size: 18px; font-weight: 700; color: #111827; white-space: nowrap; }
    .tender-card-title { font-size: 14px; color: #374151; margin-bottom: 12px; line-height: 1.5; }
    .tender-card-details { margin-bottom: 4px; }
    .detail-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
    .detail { display: flex; flex-direction: column; }
    .detail-label { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin-bottom: 2px; }
    .detail span:not(.detail-label) { font-size: 14px; }
    .deadline { font-weight: 500; }
    .deadline.overdue { color: #ef4444; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-ACTIVE { background: #dbeafe; color: #1a56db; }
    .badge-COMPLETED { background: #d1fae5; color: #065f46; }
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
