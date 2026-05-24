import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgIf, NgFor, NgClass } from '@angular/common';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-equipment-detail-modal',
  standalone: true,
  imports: [NgIf, NgFor, NgClass],
  template: `
    <div *ngIf="equipment" class="overlay" (click)="onClose()">
      <aside class="sidebar" (click)="$event.stopPropagation()">
        <header class="head">
          <div class="title-block">
            <h2 class="title">{{ equipment.name }}</h2>
            <div class="subtitle">
              <span>{{ equipment.manufact }}</span>
              <span class="dot" *ngIf="equipment.equipmentType?.name">·</span>
              <span class="type-pill" *ngIf="equipment.equipmentType?.name">{{ equipment.equipmentType?.name }}</span>
            </div>
          </div>
          <button class="close-btn" type="button" (click)="onClose()" aria-label="Закрыть">&times;</button>
        </header>

        <div class="body">
          <!-- Характеристики -->
          <section class="section">
            <h3 class="section-title">Характеристики</h3>
            <div class="spec-grid">
              <div class="spec-cell">
                <div class="spec-label">Габариты (Д×Ш×В), мм</div>
                <div class="spec-value">{{ equipment.lengthMm ?? '—' }} × {{ equipment.widthMm ?? '—' }} × {{ equipment.heightMm ?? '—' }}</div>
              </div>
              <div class="spec-cell">
                <div class="spec-label">Вес, кг</div>
                <div class="spec-value">{{ equipment.weightKg ?? '—' }}</div>
              </div>
              <div class="spec-cell">
                <div class="spec-label">Производитель</div>
                <div class="spec-value">{{ equipment.manufact || '—' }}</div>
              </div>
            </div>
            <div class="spec-spec" *ngIf="equipment.spec">
              <div class="spec-label">Спецификация</div>
              <div class="spec-text">{{ equipment.spec }}</div>
            </div>
          </section>

          <!-- Потенциальные поставщики -->
          <section class="section">
            <h3 class="section-title">Потенциальные поставщики</h3>
            <table *ngIf="stats?.potentialDistributors?.length; else noDistributors">
              <thead>
                <tr><th>Дистрибьютор</th><th>Email</th><th>Телефон</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let d of stats.potentialDistributors">
                  <td class="name-cell">{{ d.name }}</td>
                  <td>{{ d.email || '—' }}</td>
                  <td>{{ d.phone || '—' }}</td>
                </tr>
              </tbody>
            </table>
            <ng-template #noDistributors>
              <div class="empty">Нет дистрибьюторов с подходящей специализацией</div>
            </ng-template>
          </section>

          <!-- Сводка -->
          <section class="section" *ngIf="stats?.summary">
            <h3 class="section-title">Сводка</h3>
            <div class="summary-card">
              <div class="summary-row">
                Запрашивали
                <strong>{{ stats.summary.requestsCount || 0 }}</strong>
                {{ requestsWord(stats.summary.requestsCount) }} у
                <strong>{{ stats.summary.distinctDistributors || 0 }}</strong>
                {{ distributorsWord(stats.summary.distinctDistributors) }}
              </div>
              <div class="summary-row" *ngIf="stats.summary.minPrice != null">
                Цены ответов: от <strong>{{ formatPrice(stats.summary.minPrice) }} &#8381;</strong>
                до <strong>{{ formatPrice(stats.summary.maxPrice) }} &#8381;</strong>,
                средняя <strong>{{ formatPrice(stats.summary.avgPrice) }} &#8381;</strong>
              </div>
              <div class="summary-row muted" *ngIf="stats.summary.minPrice == null && stats.summary.requestsCount">
                Ответов с ценой пока не получено
              </div>
            </div>
          </section>

          <!-- Рейтинг -->
          <section class="section" *ngIf="stats?.ranking?.length">
            <h3 class="section-title">Рейтинг дистрибьюторов</h3>
            <table>
              <thead>
                <tr><th class="w-30">#</th><th>Дистрибьютор</th><th class="w-90">Ответов</th><th class="w-140">Средняя цена</th></tr>
              </thead>
              <tbody>
                <tr *ngFor="let r of stats.ranking; let i = index" [class.best-row]="i === 0">
                  <td class="rank-cell">{{ i + 1 }}</td>
                  <td class="name-cell">
                    {{ r.distributor?.name }}
                    <span *ngIf="i === 0" class="best-tag">← лучший</span>
                  </td>
                  <td>{{ r.responsesCount }}</td>
                  <td>{{ formatPrice(r.avgPrice) }} &#8381;</td>
                </tr>
              </tbody>
            </table>
          </section>

          <!-- История запросов -->
          <section class="section">
            <h3 class="section-title">История запросов</h3>
            <table *ngIf="stats?.history?.length; else noHistory">
              <thead>
                <tr>
                  <th class="w-100">Дата</th>
                  <th>Дистрибьютор</th>
                  <th>Тендер</th>
                  <th class="w-60">Кол-во</th>
                  <th class="w-140">Цена ответа</th>
                  <th class="w-110">Статус</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let h of stats.history">
                  <td>{{ formatDate(h.date) }}</td>
                  <td>{{ h.distributor?.name || '—' }}</td>
                  <td>{{ h.tenderNumber || '—' }}</td>
                  <td>{{ h.requestedQuantity ?? '—' }}</td>
                  <td>{{ h.responsePrice != null ? formatPrice(h.responsePrice) + ' ₽' : '—' }}</td>
                  <td>
                    <span class="badge" [ngClass]="statusClass(h.status)">{{ statusLabel(h.status) }}</span>
                  </td>
                </tr>
              </tbody>
            </table>
            <ng-template #noHistory>
              <div class="empty">Запросов по этому оборудованию ещё не было</div>
            </ng-template>
          </section>

          <div *ngIf="loading" class="loading">Загрузка статистики…</div>
        </div>
      </aside>
    </div>
  `,
  styles: [`
    .overlay {
      position: fixed; inset: 0;
      background: rgba(17, 24, 39, 0.5);
      z-index: 1000;
      animation: fadeIn 0.15s ease-out;
    }
    @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
    .sidebar {
      position: fixed; top: 0; right: 0; bottom: 0;
      width: 720px; max-width: 100vw;
      background: #fff;
      box-shadow: -8px 0 30px rgba(0,0,0,0.18);
      display: flex; flex-direction: column;
      animation: slideIn 0.2s ease-out;
    }
    @keyframes slideIn { from { transform: translateX(20px); opacity: 0; } to { transform: translateX(0); opacity: 1; } }

    .head {
      display: flex; align-items: flex-start; justify-content: space-between;
      gap: 12px;
      padding: 24px 32px 16px;
      border-bottom: 1px solid #e5e7eb;
      flex-shrink: 0;
    }
    .title-block { min-width: 0; flex: 1; }
    .title {
      margin: 0; font-size: 20px; line-height: 1.3; font-weight: 600;
      color: #111827; word-break: break-word;
    }
    .subtitle {
      margin-top: 6px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap;
      font-size: 13px; color: #6b7280;
    }
    .dot { color: #d1d5db; }
    .type-pill {
      display: inline-block; padding: 2px 10px;
      background: #eef2ff; color: #3730a3;
      border-radius: 999px; font-size: 12px; font-weight: 500;
    }
    .close-btn {
      background: transparent; border: none; cursor: pointer;
      font-size: 28px; line-height: 1; color: #9ca3af;
      padding: 0 4px; border-radius: 4px;
      transition: color 0.15s, background 0.15s;
    }
    .close-btn:hover { color: #ef4444; background: #fef2f2; }

    .body {
      flex: 1; overflow-y: auto;
      padding: 24px 32px 32px;
    }

    .section { margin-bottom: 28px; }
    .section:last-child { margin-bottom: 0; }
    .section-title {
      margin: 0 0 12px; font-size: 13px; font-weight: 600;
      color: #6b7280; text-transform: uppercase; letter-spacing: 0.04em;
    }

    .spec-grid {
      display: grid; grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 12px;
    }
    .spec-cell {
      background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px;
      padding: 10px 14px;
    }
    .spec-label { font-size: 11px; color: #6b7280; text-transform: uppercase; letter-spacing: 0.04em; margin-bottom: 4px; }
    .spec-value { font-size: 14px; color: #111827; font-weight: 500; }
    .spec-price { color: #1a56db; font-weight: 600; font-size: 15px; }
    .spec-spec { margin-top: 12px; background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 10px 14px; }
    .spec-text { font-size: 13px; color: #374151; white-space: pre-wrap; margin-top: 4px; line-height: 1.5; }

    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #f3f4f6; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
    tbody tr:hover { background: #f9fafb; }
    tr.best-row { background: #fefce8; }
    tr.best-row:hover { background: #fef9c3; }
    .name-cell { font-weight: 500; color: #111827; }
    .rank-cell { font-weight: 600; color: #6b7280; }
    .best-tag { color: #b45309; font-size: 11px; font-weight: 600; margin-left: 6px; }
    .w-30 { width: 30px; } .w-60 { width: 60px; } .w-90 { width: 90px; }
    .w-100 { width: 100px; } .w-110 { width: 110px; } .w-140 { width: 140px; }

    .summary-card {
      background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px;
      padding: 14px 16px;
    }
    .summary-row { font-size: 14px; color: #374151; margin-bottom: 6px; line-height: 1.5; }
    .summary-row:last-child { margin-bottom: 0; }
    .summary-row strong { color: #111827; font-weight: 600; }
    .summary-row.muted { color: #6b7280; }

    .empty {
      color: #9ca3af; font-size: 13px; padding: 16px;
      background: #f9fafb; border: 1px dashed #e5e7eb; border-radius: 6px;
      text-align: center;
    }
    .loading {
      color: #6b7280; font-size: 13px; text-align: center; padding: 12px;
    }

    .badge {
      display: inline-block; padding: 2px 10px;
      font-size: 11px; font-weight: 600; border-radius: 999px;
      text-transform: uppercase; letter-spacing: 0.04em; white-space: nowrap;
    }
    .badge-created { background: #f3f4f6; color: #4b5563; }
    .badge-sent { background: #dbeafe; color: #1e40af; }
    .badge-responded { background: #dcfce7; color: #166534; }
    .badge-closed { background: #fee2e2; color: #991b1b; }
    .badge-other { background: #f3f4f6; color: #4b5563; }

    @media (max-width: 768px) {
      .sidebar { width: 100vw; }
      .head { padding: 16px 20px 12px; }
      .body { padding: 16px 20px 24px; }
      .spec-grid { grid-template-columns: 1fr; }
    }
  `]
})
export class EquipmentDetailModalComponent implements OnChanges {
  @Input() equipment: any = null;
  @Output() close = new EventEmitter<void>();

  stats: any = null;
  loading = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnChanges(changes: SimpleChanges) {
    if (changes['equipment']) {
      const cur = changes['equipment'].currentValue;
      const prev = changes['equipment'].previousValue;
      if (cur && cur.id && (!prev || prev.id !== cur.id)) {
        this.loadStats(cur.id);
      }
      if (!cur) {
        this.stats = null;
      }
    }
  }

  loadStats(id: number) {
    this.loading = true;
    this.stats = null;
    this.cdr.detectChanges();
    this.api.getEquipmentStats(id).subscribe({
      next: (data) => {
        this.stats = data || null;
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: () => {
        this.stats = null;
        this.loading = false;
        this.cdr.detectChanges();
      }
    });
  }

  onClose() {
    this.close.emit();
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.equipment) this.onClose();
  }

  formatPrice(n: any): string {
    return n != null ? Number(n).toLocaleString('ru-RU') : '0';
  }

  formatDate(d: string): string {
    return d ? new Date(d).toLocaleDateString('ru-RU') : '—';
  }

  statusClass(s: string): string {
    switch ((s || '').toUpperCase()) {
      case 'CREATED': return 'badge-created';
      case 'SENT': return 'badge-sent';
      case 'RESPONDED': return 'badge-responded';
      case 'CLOSED': return 'badge-closed';
      default: return 'badge-other';
    }
  }

  statusLabel(s: string): string {
    switch ((s || '').toUpperCase()) {
      case 'CREATED': return 'Создан';
      case 'SENT': return 'Отправлен';
      case 'RESPONDED': return 'Ответ получен';
      case 'CLOSED': return 'Закрыт';
      default: return s || '—';
    }
  }

  requestsWord(n: number): string {
    const v = Math.abs(n || 0) % 100;
    const last = v % 10;
    if (v > 10 && v < 20) return 'раз';
    if (last === 1) return 'раз';
    if (last >= 2 && last <= 4) return 'раза';
    return 'раз';
  }

  distributorsWord(n: number): string {
    const v = Math.abs(n || 0) % 100;
    const last = v % 10;
    if (v > 10 && v < 20) return 'дистрибьюторов';
    if (last === 1) return 'дистрибьютора';
    if (last >= 2 && last <= 4) return 'дистрибьюторов';
    return 'дистрибьюторов';
  }
}
