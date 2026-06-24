import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { MarketService } from '../../services/market.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { ChartComponent } from '../../components/chart/chart.component';

@Component({
  selector: 'app-reports',
  standalone: true,
  imports: [NgFor, NgIf, NgClass, ChartComponent, LucideDynamicIcon, MarketMoneyPipe],
  template: `
    <h2>Отчёты</h2>
    <p class="subtitle">Аналитика по тендерной деятельности</p>

    <div class="report-actions">
      <h3>Скачать отчёты</h3>
      <div class="report-buttons">
        <button class="btn btn-pdf" (click)="downloadPdf()"><svg lucideIcon="download" [size]="14"></svg> PDF: все тендеры</button>
        <button class="btn btn-pdf" (click)="downloadPdf('ACTIVE')"><svg lucideIcon="download" [size]="14"></svg> PDF: активные</button>
        <button class="btn btn-pdf" (click)="downloadPdf('DRAFT')"><svg lucideIcon="download" [size]="14"></svg> PDF: подготовка</button>
        <button class="btn btn-pdf" (click)="downloadPdf('COMPLETED')"><svg lucideIcon="download" [size]="14"></svg> PDF: завершённые</button>
        <button class="btn btn-excel" (click)="downloadProfitabilityExcel()"><svg lucideIcon="file-spreadsheet" [size]="14"></svg> Excel: прибыльность</button>
      </div>
    </div>

    <div class="report-section">
      <h3>Визуализация</h3>
      <div class="charts-grid">
        <div class="chart-cell">
          <h4>Тендеры по статусам</h4>
          <app-chart *ngIf="tenderStatusLabels.length" type="doughnut" [labels]="tenderStatusLabels" [values]="tenderStatusValues"></app-chart>
          <div *ngIf="!tenderStatusLabels.length" class="empty">Нет данных</div>
        </div>
        <div class="chart-cell">
          <h4>Спрос на типы оборудования</h4>
          <app-chart *ngIf="demandLabels.length" type="bar" label="Лотов" [labels]="demandLabels" [values]="demandValues" [horizontal]="true"></app-chart>
          <div *ngIf="!demandLabels.length" class="empty">Нет данных</div>
        </div>
        <div class="chart-cell" *ngIf="profit?.profitByType?.length">
          <h4>Прибыль по типам оборудования</h4>
          <app-chart type="bar" [label]="'Прибыль, ' + market.symbol()" [labels]="profitByTypeLabels" [values]="profitByTypeValues" [horizontal]="true"></app-chart>
        </div>
        <div class="chart-cell" *ngIf="profit?.distributorRanking?.length">
          <h4>Прибыль по дистрибьюторам</h4>
          <app-chart type="bar" [label]="'Прибыль, ' + market.symbol()" [labels]="distributorProfitLabels" [values]="distributorProfitValues" [horizontal]="true"></app-chart>
        </div>
      </div>
    </div>

    <div class="report-section profit-section" *ngIf="profit">
      <h3>Прибыльность по выигранным тендерам</h3>
      <div class="summary-grid summary-grid-4">
        <div class="summary-item highlight"><span class="summary-value">{{ profit.summary?.totalProfit | money }}</span><span class="summary-label">Чистая прибыль</span></div>
        <div class="summary-item"><span class="summary-value">{{ profit.summary?.totalRevenue | money }}</span><span class="summary-label">Выручка</span></div>
        <div class="summary-item"><span class="summary-value">{{ profit.summary?.totalProcurement | money }}</span><span class="summary-label">Закупка</span></div>
        <div class="summary-item"><span class="summary-value">{{ profit.summary?.marginPercent ?? '—' }} %</span><span class="summary-label">Маржинальность</span></div>
        <div class="summary-item"><span class="summary-value">{{ profit.summary?.wonApplies || 0 }}</span><span class="summary-label">Выиграно заявок</span></div>
        <div class="summary-item"><span class="summary-value">{{ profit.summary?.avgChequeProfit | money }}</span><span class="summary-label">Прибыль / заявка</span></div>
      </div>

      <h4 class="subsection-title">Топ-5 прибыльных тендеров</h4>
      <div *ngIf="!profit.topTenders?.length" class="empty">Пока нет данных по WON-заявкам с известной закупкой</div>
      <table *ngIf="profit.topTenders?.length">
        <thead><tr><th>Тендер</th><th>Заказчик</th><th>Выручка</th><th>Прибыль</th><th>Маржа %</th></tr></thead>
        <tbody>
          <tr *ngFor="let t of profit.topTenders">
            <td>&#8470; {{ t.tenderNumber }}</td>
            <td>{{ t.facilityName }}</td>
            <td>{{ t.revenue | money }}</td>
            <td class="positive">{{ t.profit | money }}</td>
            <td>{{ t.marginPercent ?? '—' }} %</td>
          </tr>
        </tbody>
      </table>

      <h4 class="subsection-title">Рейтинг дистрибьюторов по прибыли</h4>
      <div *ngIf="!profit.distributorRanking?.length" class="empty">Нет данных</div>
      <table *ngIf="profit.distributorRanking?.length">
        <thead><tr><th>Дистрибьютор</th><th>Позиций в WON</th><th>Прибыль с них</th><th>Средняя маржа</th></tr></thead>
        <tbody>
          <tr *ngFor="let d of profit.distributorRanking">
            <td>{{ d.name }}</td>
            <td>{{ d.dealsCount }}</td>
            <td class="positive">{{ d.totalProfit | money }}</td>
            <td>{{ d.avgMarginPercent ?? '—' }} %</td>
          </tr>
        </tbody>
      </table>

      <h4 class="subsection-title">Прибыль по типам оборудования</h4>
      <div *ngIf="!profit.profitByType?.length" class="empty">Нет данных</div>
      <table *ngIf="profit.profitByType?.length">
        <thead><tr><th>Тип</th><th>Позиций</th><th>Прибыль</th><th>Средняя маржа</th></tr></thead>
        <tbody>
          <tr *ngFor="let t of profit.profitByType">
            <td>{{ t.typeName }}</td>
            <td>{{ t.positionsCount }}</td>
            <td class="positive">{{ t.totalProfit | money }}</td>
            <td>{{ t.avgMarginPercent ?? '—' }} %</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="report-section summary-section">
      <h3>Сводка</h3>
      <div class="summary-grid">
        <div class="summary-item"><span class="summary-value">{{ totalTendersCount }}</span><span class="summary-label">Всего тендеров</span></div>
        <div class="summary-item"><span class="summary-value">{{ totalSum | money }}</span><span class="summary-label">Общая сумма тендеров</span></div>
        <div class="summary-item"><span class="summary-value">{{ avgCost | money }}</span><span class="summary-label">Средняя стоимость</span></div>
        <div class="summary-item"><span class="summary-value">{{ totalApplies }}</span><span class="summary-label">Всего заявок</span></div>
        <div class="summary-item"><span class="summary-value">{{ totalPriceRequests }}</span><span class="summary-label">Запросов КП</span></div>
        <div class="summary-item"><span class="summary-value">{{ respondedPR }}</span><span class="summary-label">Получено ответов КП</span></div>
      </div>
    </div>

    <div class="report-section">
      <h3>Статистика по тендерам</h3>
      <div *ngIf="tenderStatsList.length === 0" class="empty">Нет данных</div>
      <div class="bar-container" *ngFor="let s of tenderStatsList">
        <div class="bar-label">
          <span>{{ getStatusLabel(s.status) }}</span>
          <span>{{ s.count }} ({{ getPercent(s.count, totalTenders) }}%)</span>
        </div>
        <div class="bar"><div class="bar-fill" [style.width.%]="getPercent(s.count, totalTenders)" [ngClass]="'fill-' + s.status"></div></div>
      </div>
    </div>

    <div class="report-section">
      <h3>Спрос на типы оборудования</h3>
      <div *ngIf="equipmentDemandList.length === 0" class="empty">Нет данных</div>
      <div class="bar-container" *ngFor="let item of equipmentDemandList">
        <div class="bar-label">
          <span>{{ item.type }}</span>
          <span>{{ item.count }} лот.</span>
        </div>
        <div class="bar"><div class="bar-fill" [style.width.%]="getPercent(item.count, maxDemand)"></div></div>
      </div>
    </div>

    <div class="report-section">
      <h3>Статистика по дистрибьюторам (запросы КП)</h3>
      <div *ngIf="distributorPrStats.length === 0" class="empty">Нет данных</div>
      <table *ngIf="distributorPrStats.length > 0">
        <thead>
          <tr><th>Дистрибьютор</th><th>Всего запросов КП</th><th>Получено ответов</th><th>Средняя цена ответа</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let d of distributorPrStats">
            <td>{{ d.name }}</td>
            <td>{{ d.totalRequests }}</td>
            <td>{{ d.responded }}</td>
            <td>{{ d.avgPrice | money }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    h3 { margin: 0 0 16px; font-size: 16px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 20px; }
    .empty { color: #9ca3af; font-size: 13px; padding: 16px 0; text-align: center; }

    .report-section { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-bottom: 16px; }

    .bar-container { margin-bottom: 14px; }
    .bar-label { font-size: 13px; margin-bottom: 4px; display: flex; justify-content: space-between; color: #374151; }
    .bar { height: 10px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
    .bar-fill { height: 100%; background: #1a56db; border-radius: 4px; transition: width 0.3s; }
    .bar-fill.fill-ACTIVE { background: #1a56db; }
    .bar-fill.fill-DRAFT { background: #6b7280; }
    .bar-fill.fill-COMPLETED { background: #10b981; }

    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    .report-actions { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-bottom: 24px; }
    .report-buttons { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
    .btn-pdf { background: #dc2626; color: #fff; padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-pdf:hover { background: #b91c1c; }
    .btn-excel { background: #059669; color: #fff; padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-excel:hover { background: #047857; }
    .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
    .chart-cell h4 { font-size: 13px; color: #6b7280; margin: 0 0 8px; text-align: center; }
    .chart-cell { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 12px; }
    .summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
    .summary-grid-4 { grid-template-columns: repeat(3, 1fr); }
    .summary-item { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px; text-align: center; }
    .summary-item.highlight { background: #ecfdf5; border-color: #a7f3d0; }
    .summary-item.highlight .summary-value { color: #047857; }
    .summary-value { display: block; font-size: 24px; font-weight: 700; color: #111827; margin-bottom: 4px; }
    .summary-label { font-size: 12px; color: #6b7280; }
    .profit-section { border-color: #a7f3d0; background: #fdfffe; }
    .subsection-title { font-size: 14px; font-weight: 600; color: #374151; margin: 20px 0 10px; }
    .positive { color: #059669; font-weight: 500; }
  `]
})
export class ReportsComponent {
  totalTendersCount = 0;
  totalSum = 0;
  avgCost = 0;
  totalApplies = 0;
  totalPriceRequests = 0;
  respondedPR = 0;

  tenderStatsList: { status: string; count: number }[] = [];
  totalTenders = 0;
  equipmentDemandList: { type: string; count: number }[] = [];
  maxDemand = 0;
  distributorStats: any[] = [];
  distributorPrStats: any[] = [];
  profit: any = null;

  get tenderStatusLabels(): string[] { return this.tenderStatsList.map(s => this.getStatusLabel(s.status)); }
  get tenderStatusValues(): number[] { return this.tenderStatsList.map(s => s.count); }
  get demandLabels(): string[] { return this.equipmentDemandList.map(i => i.type); }
  get demandValues(): number[] { return this.equipmentDemandList.map(i => i.count); }
  get profitByTypeLabels(): string[] { return (this.profit?.profitByType || []).map((t: any) => t.typeName); }
  get profitByTypeValues(): number[] { return (this.profit?.profitByType || []).map((t: any) => Number(t.totalProfit || 0)); }
  get distributorProfitLabels(): string[] { return (this.profit?.distributorRanking || []).map((d: any) => d.name); }
  get distributorProfitValues(): number[] { return (this.profit?.distributorRanking || []).map((d: any) => Number(d.totalProfit || 0)); }

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, public market: MarketService) {
    this.loadAll();
  }

  downloadProfitabilityExcel() {
    this.api.downloadProfitabilityExcel().subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'profitability_report.xlsx';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  loadAll() {
    this.api.getTenders().subscribe(data => {
      this.totalTendersCount = data.length;
      this.totalSum = data.reduce((s: number, t: any) => s + (t.totalCost || 0), 0);
      this.avgCost = this.totalTendersCount > 0 ? this.totalSum / this.totalTendersCount : 0;
      this.cdr.detectChanges();
    });
    this.api.getApplies().subscribe(data => { this.totalApplies = data.length; this.cdr.detectChanges(); });
    this.api.getPriceRequests().subscribe(data => {
      this.totalPriceRequests = data.length;
      this.respondedPR = data.filter((p: any) => p.status === 'RESPONDED' || p.status === 'ACCEPTED').length;
      this.cdr.detectChanges();
    });

    this.api.getTenderStats().subscribe((data: any) => {
      this.tenderStatsList = Object.entries(data || {}).map(([status, count]) => ({
        status, count: count as number
      }));
      this.totalTenders = this.tenderStatsList.reduce((sum, s) => sum + s.count, 0);
      this.cdr.detectChanges();
    });

    this.api.getEquipmentDemand().subscribe((data: any) => {
      this.equipmentDemandList = Object.entries(data || {}).map(([type, count]) => ({
        type, count: count as number
      }));
      this.maxDemand = Math.max(1, ...this.equipmentDemandList.map(i => i.count));
      this.cdr.detectChanges();
    });

    this.api.getDistributorStats().subscribe(data => {
      this.distributorStats = data;
      this.cdr.detectChanges();
    });

    this.api.getDistributorPrStats().subscribe(data => {
      this.distributorPrStats = data;
      this.cdr.detectChanges();
    });

    this.api.getProfitabilityReport().subscribe({
      next: data => { this.profit = data; this.cdr.detectChanges(); },
      error: () => { this.profit = null; }
    });
  }

  getStatusLabel(s: string): string {
    return ({ DRAFT: 'Подготовка', ACTIVE: 'Приём заявок', COMPLETED: 'Завершён' } as any)[s] || s;
  }

  getPercent(value: number, max: number): number {
    return max > 0 ? Math.round((value / max) * 100) : 0;
  }

  formatPrice(n: number): string {
    if (n == null) return '0';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: 2 });
  }

  downloadPdf(status?: string) {
    this.api.downloadTenderReport(status).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'tender_report.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }
}
