import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { RouterLink } from '@angular/router';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';

@Component({
  selector: 'app-dashboard',
  standalone: true,
  imports: [NgFor, NgIf, NgClass, RouterLink, LucideDynamicIcon, MarketMoneyPipe],
  template: `
    <h2>Главная</h2>
    <p class="subtitle">Сводка по текущей деятельности компании</p>

    <div class="alerts-row" *ngIf="urgentTenders.length > 0">
      <div class="alert-banner" [class.alert-overdue]="urgentTenders[0]._overdue">
        <svg lucideIcon="triangle-alert" [size]="20"></svg>
        <div class="alert-text">
          <strong>{{ urgentTenders[0]._overdue ? 'Просрочены' : 'Срочно (≤ 7 дней)' }}:</strong>
          {{ urgentTenders.length }}
          {{ urgentTenders.length === 1 ? 'тендер' : (urgentTenders.length < 5 ? 'тендера' : 'тендеров') }}
        </div>
        <div class="alert-items">
          <a *ngFor="let t of urgentTenders.slice(0, 3)" class="alert-tender" routerLink="/tenders" [queryParams]="{ openId: t.id }">
            № {{ t.tenderNumber }} <span>{{ getDaysLabel(t.deadline) }}</span>
          </a>
        </div>
      </div>
    </div>

    <div class="stat-cards">
      <div class="stat-card blue">
        <div class="stat-card-value"><svg lucideIcon="clipboard-list" [size]="18" class="card-icon"></svg> {{ activeCount }}</div>
        <div class="stat-card-label">Активные тендеры</div>
      </div>
      <div class="stat-card gray">
        <div class="stat-card-value"><svg lucideIcon="file-text" [size]="18" class="card-icon"></svg> {{ draftCount }}</div>
        <div class="stat-card-label">На подготовке</div>
      </div>
      <div class="stat-card yellow">
        <div class="stat-card-value"><svg lucideIcon="clock" [size]="18" class="card-icon"></svg> {{ appliesInWork }}</div>
        <div class="stat-card-label">Заявки в работе</div>
      </div>
      <div class="stat-card green">
        <div class="stat-card-value"><svg lucideIcon="circle-check" [size]="18" class="card-icon"></svg> {{ wonCount }}</div>
        <div class="stat-card-label">Выиграно</div>
      </div>
    </div>

    <div class="profit-card" *ngIf="profitSummary">
      <div class="profit-card-header">
        <span class="profit-card-icon"><svg lucideIcon="trending-up" [size]="24"></svg></span>
        <div>
          <div class="profit-card-label">Чистая прибыль по выигранным тендерам</div>
          <div class="profit-card-meta">{{ profitSummary.wonApplies || 0 }} заявок · средняя прибыль {{ profitSummary.avgChequeProfit | money }}</div>
        </div>
      </div>
      <div class="profit-card-value">
        {{ profitSummary.totalProfit | money }}
        <span class="profit-card-margin" *ngIf="profitSummary.marginPercent != null">({{ profitSummary.marginPercent }}% маржинальность)</span>
      </div>
    </div>

    <div class="dashboard-row">
      <div class="dashboard-panel">
        <h3>Ближайшие дедлайны</h3>
        <div *ngIf="upcomingDeadlines.length === 0" class="empty">Нет активных тендеров</div>
        <div class="deadline-item" *ngFor="let t of upcomingDeadlines">
          <div class="deadline-info">
            <a class="tender-link" routerLink="/tenders" [queryParams]="{ openId: t.id }">№ {{ t.tenderNumber }}</a>
            <div class="facility-name">{{ t.facility?.name || '—' }}</div>
            <div class="deadline-date">{{ formatDate(t.deadline) }}</div>
          </div>
          <div class="days-left" [ngClass]="getDaysClass(t.deadline)">
            {{ getDaysLabel(t.deadline) }}
          </div>
        </div>
      </div>

      <div class="dashboard-panel">
        <h3>Спрос на оборудование</h3>
        <div *ngIf="equipmentDemandList.length === 0" class="empty">Нет данных</div>
        <div class="bar-container" *ngFor="let item of equipmentDemandList">
          <div class="bar-label">
            <span>{{ item.type }}</span>
            <span>{{ item.count }} лот.</span>
          </div>
          <div class="bar"><div class="bar-fill" [style.width.%]="getPercent(item.count, maxDemand)"></div></div>
        </div>
      </div>
    </div>

    <div class="dashboard-row single">
      <div class="dashboard-panel">
        <h3>Последние заявки</h3>
        <div *ngIf="recentApplies.length === 0" class="empty">Нет заявок</div>
        <table *ngIf="recentApplies.length > 0">
          <thead>
            <tr><th>ID</th><th>Тендер</th><th>Статус</th><th>Дата</th></tr>
          </thead>
          <tbody>
            <tr *ngFor="let a of recentApplies">
              <td>#{{ a.id }}</td>
              <td>{{ a.tender?.tenderNumber || '—' }}</td>
              <td><span class="badge" [class]="'badge-' + a.status">{{ getApplyStatusLabel(a.status) }}</span></td>
              <td>{{ formatDateTime(a.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 20px; }
    .empty { color: #9ca3af; font-size: 13px; padding: 16px 0; text-align: center; }

    .stat-cards { display: grid; grid-template-columns: repeat(4, 1fr); gap: 16px; margin-bottom: 24px; }
    .alerts-row { margin-bottom: 16px; }
    .alert-banner { display: flex; align-items: center; gap: 12px; background: #fef3c7; border-left: 4px solid #f59e0b; padding: 12px 16px; border-radius: 6px; color: #92400e; flex-wrap: wrap; }
    .alert-banner.alert-overdue { background: #fee2e2; border-left-color: #ef4444; color: #991b1b; }
    .alert-text { font-size: 14px; }
    .alert-text strong { font-weight: 700; }
    .alert-items { display: flex; gap: 10px; margin-left: auto; flex-wrap: wrap; }
    .alert-tender { font-size: 12px; padding: 4px 10px; background: rgba(255,255,255,0.6); border-radius: 4px; text-decoration: none; color: inherit; font-weight: 500; }
    .alert-tender:hover { background: rgba(255,255,255,0.9); }
    .alert-tender span { color: #6b7280; margin-left: 4px; font-weight: 400; }
    .stat-card { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; }
    .stat-card-value { font-size: 32px; font-weight: 700; margin-bottom: 4px; }
    .stat-card-label { font-size: 13px; color: #6b7280; }
    .stat-card.blue .stat-card-value { color: #1a56db; }
    .stat-card.gray .stat-card-value { color: #6b7280; }
    .stat-card.yellow .stat-card-value { color: #f59e0b; }
    .stat-card.green .stat-card-value { color: #10b981; }

    .profit-card { background: linear-gradient(135deg, #ecfdf5 0%, #d1fae5 100%); border: 1px solid #a7f3d0; border-radius: 8px; padding: 20px 24px; margin-bottom: 24px; display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
    .profit-card-header { display: flex; align-items: center; gap: 14px; }
    .profit-card-icon { font-size: 32px; }
    .profit-card-label { font-size: 13px; color: #047857; font-weight: 600; text-transform: uppercase; letter-spacing: 0.4px; }
    .profit-card-meta { font-size: 12px; color: #047857; opacity: 0.8; margin-top: 2px; }
    .profit-card-value { font-size: 28px; font-weight: 700; color: #047857; }
    .profit-card-margin { font-size: 14px; font-weight: 500; margin-left: 8px; opacity: 0.85; }

    .dashboard-row { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-bottom: 24px; }
    .dashboard-row.single { grid-template-columns: 1fr; }
    .dashboard-panel { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; }
    .dashboard-panel h3 { font-size: 16px; margin: 0 0 16px; color: #111827; }

    .deadline-item { display: flex; justify-content: space-between; align-items: center; padding: 10px 0; border-bottom: 1px solid #f3f4f6; font-size: 14px; }
    .deadline-item:last-child { border-bottom: none; }
    .deadline-info { display: flex; flex-direction: column; gap: 2px; }
    .tender-link { color: #1a56db; font-weight: 600; text-decoration: none; }
    .tender-link:hover { text-decoration: underline; }
    .facility-name { color: #374151; font-size: 13px; }
    .deadline-date { color: #6b7280; font-size: 12px; }
    .days-left { font-weight: 600; font-size: 13px; }
    .days-left.urgent { color: #ef4444; }
    .days-left.soon { color: #f59e0b; }
    .days-left.ok { color: #10b981; }

    .bar-container { margin-bottom: 12px; }
    .bar-label { font-size: 13px; margin-bottom: 4px; display: flex; justify-content: space-between; color: #374151; }
    .bar { height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
    .bar-fill { height: 100%; background: #1a56db; border-radius: 4px; transition: width 0.3s; }

    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-SUBMITTED { background: #dbeafe; color: #1a56db; }
    .badge-WON { background: #d1fae5; color: #065f46; }
    .badge-REJECTED { background: #fee2e2; color: #991b1b; }
  `]
})
export class DashboardComponent {
  activeCount = 0;
  draftCount = 0;
  completedCount = 0;
  appliesInWork = 0;
  wonCount = 0;
  upcomingDeadlines: any[] = [];
  urgentTenders: any[] = [];
  equipmentDemandList: { type: string; count: number }[] = [];
  maxDemand = 0;
  recentApplies: any[] = [];
  profitSummary: any = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {
    this.loadAll();
  }

  loadAll() {
    this.api.getProfitabilityReport().subscribe({
      next: data => { this.profitSummary = data?.summary || null; this.cdr.detectChanges(); },
      error: () => { this.profitSummary = null; }
    });

    this.api.getTenders().subscribe(data => {
      this.activeCount = data.filter(t => t.status === 'ACTIVE').length;
      this.draftCount = data.filter(t => t.status === 'DRAFT').length;
      this.completedCount = data.filter(t => t.status === 'COMPLETED').length;
      const activeSorted = data
        .filter(t => t.status === 'ACTIVE')
        .sort((a, b) => new Date(a.deadline).getTime() - new Date(b.deadline).getTime());
      this.upcomingDeadlines = activeSorted.slice(0, 5);
      this.urgentTenders = activeSorted
        .map(t => ({ ...t, _overdue: this.getDaysLeft(t.deadline) < 0 }))
        .filter(t => this.getDaysLeft(t.deadline) <= 7);
      this.cdr.detectChanges();
    });

    this.api.getApplies().subscribe(data => {
      this.appliesInWork = data.filter(a => a.status === 'DRAFT' || a.status === 'SUBMITTED').length;
      this.wonCount = data.filter(a => a.status === 'WON').length;
      this.recentApplies = [...data]
        .sort((a, b) => new Date(b.createdAt || 0).getTime() - new Date(a.createdAt || 0).getTime())
        .slice(0, 5);
      this.cdr.detectChanges();
    });

    this.api.getEquipmentDemand().subscribe((data: any) => {
      this.equipmentDemandList = Object.entries(data || {}).map(([type, count]) => ({
        type, count: count as number
      }));
      this.maxDemand = Math.max(1, ...this.equipmentDemandList.map(i => i.count));
      this.cdr.detectChanges();
    });
  }

  getDaysLeft(deadline: string): number {
    const d = new Date(deadline).getTime();
    const now = new Date().getTime();
    return Math.ceil((d - now) / (1000 * 60 * 60 * 24));
  }

  getDaysLabel(deadline: string): string {
    const days = this.getDaysLeft(deadline);
    if (days < 0) return 'Просрочен';
    if (days === 0) return 'Сегодня';
    if (days === 1) return 'Завтра';
    return `${days} дн.`;
  }

  getDaysClass(deadline: string): string {
    const days = this.getDaysLeft(deadline);
    if (days < 0) return 'urgent';
    if (days <= 3) return 'urgent';
    if (days <= 7) return 'soon';
    return 'ok';
  }

  getPercent(value: number, max: number): number {
    return max > 0 ? Math.round((value / max) * 100) : 0;
  }

  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatPrice(n: any): string {
    if (n == null) return '0';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: 2 });
  }

  formatDateTime(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit' });
  }

  getApplyStatusLabel(s: string): string {
    return ({ DRAFT: 'Черновик', SUBMITTED: 'Подана', WON: 'Выиграна', REJECTED: 'Отклонена' } as any)[s] || s;
  }
}
