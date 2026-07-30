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
      <table class="responsive-cards card-grid profit-top" *ngIf="profit.topTenders?.length">
        <thead><tr><th>Тендер</th><th>Заказчик</th><th>Выручка</th><th>Прибыль</th><th>Маржа %</th></tr></thead>
        <tbody>
          <tr *ngFor="let t of profit.topTenders">
            <td data-label="Тендер">&#8470; {{ t.tenderNumber }}</td>
            <td data-label="Заказчик">{{ t.facilityName }}</td>
            <td data-label="Выручка">{{ t.revenue | money }}</td>
            <td class="positive" data-label="Прибыль">{{ t.profit | money }}</td>
            <td data-label="Маржа %">{{ t.marginPercent ?? '—' }} %</td>
          </tr>
        </tbody>
      </table>

      <h4 class="subsection-title">Рейтинг дистрибьюторов по прибыли</h4>
      <div *ngIf="!profit.distributorRanking?.length" class="empty">Нет данных</div>
      <table class="responsive-cards card-grid profit-dist" *ngIf="profit.distributorRanking?.length">
        <thead><tr><th>Дистрибьютор</th><th>Позиций в WON</th><th>Прибыль с них</th><th>Средняя маржа</th></tr></thead>
        <tbody>
          <tr *ngFor="let d of profit.distributorRanking">
            <td data-label="Дистрибьютор">{{ d.name }}</td>
            <td data-label="Позиций в WON">{{ d.dealsCount }}</td>
            <td class="positive" data-label="Прибыль с них">{{ d.totalProfit | money }}</td>
            <td data-label="Средняя маржа">{{ d.avgMarginPercent ?? '—' }} %</td>
          </tr>
        </tbody>
      </table>

      <h4 class="subsection-title">Прибыль по типам оборудования</h4>
      <div *ngIf="!profit.profitByType?.length" class="empty">Нет данных</div>
      <table class="responsive-cards card-grid profit-type" *ngIf="profit.profitByType?.length">
        <thead><tr><th>Тип</th><th>Позиций</th><th>Прибыль</th><th>Средняя маржа</th></tr></thead>
        <tbody>
          <tr *ngFor="let t of profit.profitByType">
            <td data-label="Тип">{{ t.typeName }}</td>
            <td data-label="Позиций">{{ t.positionsCount }}</td>
            <td class="positive" data-label="Прибыль">{{ t.totalProfit | money }}</td>
            <td data-label="Средняя маржа">{{ t.avgMarginPercent ?? '—' }} %</td>
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
      <table class="responsive-cards card-grid pr-stats" *ngIf="distributorPrStats.length > 0">
        <thead>
          <tr><th>Дистрибьютор</th><th>Всего запросов КП</th><th>Получено ответов</th><th>Средняя цена ответа</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let d of distributorPrStats">
            <td data-label="Дистрибьютор">{{ d.name }}</td>
            <td data-label="Всего запросов КП">{{ d.totalRequests }}</td>
            <td data-label="Получено ответов">{{ d.responded }}</td>
            <td data-label="Средняя цена ответа">{{ d.avgPrice | money }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
    h3 { margin: 0 0 16px; font-size: 16px; }

    .report-section { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 20px; margin-bottom: 16px; }

    .bar-container { margin-bottom: 14px; }
    .bar-label { font-size: 13px; margin-bottom: 4px; display: flex; justify-content: space-between; color: var(--text); }
    .bar { height: 10px; background: var(--border); border-radius: 4px; overflow: hidden; }
    .bar-fill { height: 100%; background: var(--accent); border-radius: 4px; transition: width 0.3s; }
    .bar-fill.fill-ACTIVE { background: var(--accent); }
    .bar-fill.fill-DRAFT { background: var(--text-muted); }
    .bar-fill.fill-COMPLETED { background: var(--success); }

    table { width: 100%; border-collapse: collapse; }
    /* Правило несёт саму рамку (kit даёт только её цвет) — остаётся здесь. */
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 14px; }
    th { font-weight: 600; }
    tr:hover { background: var(--surface-2); }
    .report-actions { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: 20px; margin-bottom: 24px; }
    .report-buttons { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 12px; }
    /* .btn-pdf (заливка + ховер) целиком из kit. .btn-excel в kit нет и не должно быть:
       это залитая кнопка с белой подписью, а --success в тёмной теме осветлён — белая
       надпись на нём даёт ~1,9:1. Поэтому зелёный остаётся фикс-цветным хексом (как
       кнопки «КП» и «ТЗ», Global Constraint 5), токенизирован только цвет подписи.
       Геометрию (padding 8px 16px) НЕ оставляем: .btn-pdf её теряет, забрав kit-овские
       6px 14px, а обе кнопки стоят в одном ряду и разъехались бы по высоте. */
    .btn-excel { background: #059669; color: var(--accent-contrast); }
    .btn-excel:hover { background: #047857; }
    .charts-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
    .chart-cell h4 { font-size: 13px; color: var(--text-muted); margin: 0 0 8px; text-align: center; }
    .chart-cell { background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 12px; }
    .summary-grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; }
    .summary-grid-4 { grid-template-columns: repeat(3, 1fr); }
    .summary-item { background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 16px; text-align: center; }
    .summary-item.highlight { background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: var(--success); }
    .summary-item.highlight .summary-value { color: var(--success-text); }
    .summary-value { display: block; font-size: 24px; font-weight: 700; color: var(--text); margin-bottom: 4px; }
    .summary-label { font-size: 12px; color: var(--text-muted); }
    /* 5%, а не 8% из плана, — сознательно. Внутри этой секции лежит
       .summary-item.highlight («Чистая прибыль»), собранный по идиоме на честных 8%:
       уравняй их — и подсвеченная карточка сольётся с подложкой секции, останется
       только рамка. Это ровно урок бага 6b8dc3f (подсветку утопили в фоне панели).
       Исходник тоже держал секцию бледнее карточки: #fdfffe против #ecfdf5. */
    .profit-section { border-color: var(--success); background: color-mix(in srgb, var(--success) 5%, var(--surface)); }
    .subsection-title { font-size: 14px; font-weight: 600; color: var(--text); margin: 20px 0 10px; }
    .positive { color: var(--success-text); font-weight: 500; }

    /* @media перенесён из середины блока в конец (CLAUDE.md §14): при равной
       специфичности любое базовое правило .charts-grid ниже молча перебило бы
       мобильную переопределялку. Само правило не менялось. */
    @media (max-width: 900px) {
      .charts-grid { grid-template-columns: 1fr; }

      /* ─── Сводки: ДВА грида, оба стояли на repeat(3, 1fr) ────────────────────
         «1fr» — это minmax(auto, 1fr), и нижняя граница «auto» = min-content:
         колонка не сжимается уже суммы вроде «1 372 500 000 ₽», три такие
         колонки в 390px не влезают, и лента обрезается (гоча CLAUDE.md §14).
         auto-fit сам считает, сколько колонок поместится (на 390px — две),
         minmax даёт им нижнюю границу. Правило стоит ПОСЛЕ .summary-grid-4,
         поэтому перебивает его при равной специфичности. Размер значения
         уменьшен здесь же: 24px в колонке 154px не оставлял запаса. */
      .summary-grid, .summary-grid-4 { grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 10px; }
      .summary-item { padding: 12px 10px; }
      .summary-value { font-size: 17px; }

      /* ─── Таблицы отчётов → спроектированные карточки ───────────────────────
         Механический режим «подпись: значение» дал бы 4–5 строк на запись
         (простыня выше порога ~140px), поэтому все четыре таблицы на card-grid:
         это списки, которые сканируют глазами, а не карточка записи. */
      /* .report-section сам --surface, и глобальный слой красит строку тоже в
         --surface: карточки слились бы с фоном. Подсвеченных строк, которым
         этот фон мог бы перебить подсветку, в таблицах отчётов нет. */
      table.responsive-cards tr { background: var(--surface-2); }
      table.responsive-cards td { gap: 2px 6px; overflow-wrap: anywhere; }
      /* общий вид микро-подписи; включается точечно там, где голое число
         нечитаемо («12 %», «4») — thead в режиме карточек скрыт */
      table.responsive-cards td::before { font-weight: 600; color: var(--text-muted); font-size: 12px; }
      /* ⚠️ Ячейка с подписью читается как одна фраза («позиций 11») → flex-start,
         иначе глобальное space-between расталкивает подпись и число по краям
         колонки. Правило ставится ПОКЛЕТОЧНО, а не общим «table.responsive-cards
         td»: общий селектор (0,4,2) сильнее поклеточных (0,4,1) и молча погасил
         бы flex-end у прибыли и маржи — они уехали бы влево. */

      /* Топ-5 прибыльных тендеров.
         ⚠️ Номер тендера занимает ВСЮ ширину, а прибыль вынесена отдельной
         строкой намеренно. В раскладке «номер | прибыль в одной колонке» ширину
         второй колонки задавала самая длинная сумма («прибыль 17 386 302 ₽»,
         ~150px) — номеру оставалось ~134px, и 19-значный номер ломался посреди
         цифр («01422000013250 / 24166»). Замер: запись 151,5px → 121px. */
      .profit-top tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "num    num"
          "cust   cust"
          "rev    margin"
          "profit profit";
      }
      .profit-top td[data-label="Тендер"] { grid-area: num; font-weight: 600; font-size: 15px; }
      .profit-top td[data-label="Заказчик"] { grid-area: cust; color: var(--text-muted); font-size: 13px; }
      .profit-top td[data-label="Маржа %"] { grid-area: margin; justify-content: flex-end; color: var(--text-muted); font-size: 13px; white-space: nowrap; }
      .profit-top td[data-label="Маржа %"]::before { display: inline; content: "маржа"; }
      .profit-top td[data-label="Выручка"] { grid-area: rev; justify-content: flex-start; font-size: 13px; white-space: nowrap; }
      .profit-top td[data-label="Выручка"]::before { display: inline; content: "выручка"; }
      /* цвет НЕ трогаем — он от класса .positive; здесь только вес и место */
      .profit-top td[data-label="Прибыль"] { grid-area: profit; justify-content: flex-end; font-weight: 600; }
      .profit-top td[data-label="Прибыль"]::before { display: inline; content: "прибыль"; }

      /* Рейтинг дистрибьюторов по прибыли · Прибыль по типам оборудования —
         раскладка у них одна, различаются только имена колонок */
      .profit-dist tr, .profit-type tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "name  profit"
          "count margin";
      }
      .profit-dist td[data-label="Дистрибьютор"],
      .profit-type td[data-label="Тип"] { grid-area: name; font-weight: 600; }
      .profit-dist td[data-label="Прибыль с них"],
      .profit-type td[data-label="Прибыль"] { grid-area: profit; justify-content: flex-end; font-weight: 600; }
      .profit-dist td[data-label="Позиций в WON"],
      .profit-type td[data-label="Позиций"] { grid-area: count; justify-content: flex-start; color: var(--text-muted); font-size: 13px; }
      .profit-dist td[data-label="Позиций в WON"]::before,
      .profit-type td[data-label="Позиций"]::before { display: inline; content: "позиций"; }
      .profit-dist td[data-label="Средняя маржа"],
      .profit-type td[data-label="Средняя маржа"] { grid-area: margin; justify-content: flex-end; color: var(--text-muted); font-size: 13px; white-space: nowrap; }
      .profit-dist td[data-label="Средняя маржа"]::before,
      .profit-type td[data-label="Средняя маржа"]::before { display: inline; content: "маржа"; }

      /* Статистика по дистрибьюторам (запросы КП) */
      .pr-stats tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "name  name"
          "reqs  price"
          "resp  price";
      }
      .pr-stats td[data-label="Дистрибьютор"] { grid-area: name; font-weight: 600; }
      .pr-stats td[data-label="Всего запросов КП"] { grid-area: reqs; justify-content: flex-start; color: var(--text-muted); font-size: 13px; }
      .pr-stats td[data-label="Всего запросов КП"]::before { display: inline; content: "запросов КП"; }
      .pr-stats td[data-label="Получено ответов"] { grid-area: resp; justify-content: flex-start; color: var(--text-muted); font-size: 13px; }
      .pr-stats td[data-label="Получено ответов"]::before { display: inline; content: "получено ответов"; }
      .pr-stats td[data-label="Средняя цена ответа"] { grid-area: price; justify-content: flex-end; align-items: flex-end; flex-direction: column; gap: 0; }
      .pr-stats td[data-label="Средняя цена ответа"]::before { display: block; content: "средняя цена"; }
    }
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
