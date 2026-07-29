import { AfterViewInit, Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild } from '@angular/core';
import { Chart, ChartConfiguration, ChartType, registerables } from 'chart.js';

Chart.register(...registerables);

@Component({
  selector: 'app-chart',
  standalone: true,
  template: `<canvas #canvas></canvas>`,
  styles: [`:host { display: block; position: relative; height: 280px; } canvas { max-height: 100%; }`]
})
export class ChartComponent implements OnChanges, OnDestroy, AfterViewInit {
  @ViewChild('canvas', { static: true }) canvasRef!: ElementRef<HTMLCanvasElement>;
  @Input() type: ChartType = 'bar';
  @Input() labels: string[] = [];
  @Input() values: number[] = [];
  @Input() label: string = '';
  /* Палитра серий сознательно НЕ на токенах: это категорийные цвета (сегменты кольца,
     серии столбцов) — у них нет семантической роли, и токенов такой палитры в kit нет.
     Канвас к тому же не понимает var(--…): значение подставляется отсюда, из JS.
     Осветлён только первый цвет, #1a56db → #3b82f6. Он работает заливкой ВСЕХ
     столбчатых графиков, а брендовый #1a56db на тёмной подложке даёт 2,0:1 — ровно та
     причина, по которой --accent в тёмной теме осветлён. #3b82f6 даёт 3,4:1 на тёмной
     и 3,3:1 на светлой, то есть держит порог 3:1 для графики в обеих темах.
     Остальные семь проверены на тёмной подложке и порог держат (минимум — #8b5cf6). */
  @Input() colors: string[] = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];
  @Input() horizontal = false;

  private chart?: Chart;
  private themeObserver?: MutationObserver;

  ngAfterViewInit(): void {
    this.render();
    /* Оси, сетка и подписи берут цвет из токенов, но канвас не наследует CSS — они
       вычисляются в render(). Тумблер темы только меняет data-theme на <html> и НЕ
       перезагружает страницу (ThemeService), поэтому без наблюдателя график остался бы
       в цветах прошлой темы до следующей навигации. */
    this.themeObserver = new MutationObserver(() => this.render());
    this.themeObserver.observe(document.documentElement, { attributeFilter: ['data-theme'] });
  }

  ngOnChanges(): void {
    this.render();
  }

  /** Значение токена темы с <html> — канвасу цвет можно отдать только строкой. */
  private cssVar(name: string, fallback: string): string {
    const v = getComputedStyle(document.documentElement).getPropertyValue(name).trim();
    return v || fallback;
  }

  private render(): void {
    if (!this.canvasRef?.nativeElement) return;
    if (this.chart) {
      this.chart.destroy();
    }

    const isPie = this.type === 'pie' || this.type === 'doughnut';
    const muted = this.cssVar('--text-muted', '#6b7280');   // подписи осей и легенды
    const grid = this.cssVar('--border', '#e5e7eb');        // линии сетки и осей
    /* Кольцо между сегментами — это ЗАЗОР, а не рамка: раньше здесь стоял #fff под
       почти белую карточку. Карточка графика теперь --surface-2, зазор идёт за ней. */
    const gap = this.cssVar('--surface-2', '#f3f4f6');
    const config: ChartConfiguration = {
      type: this.type,
      data: {
        labels: this.labels,
        datasets: [{
          label: this.label,
          data: this.values,
          backgroundColor: isPie ? this.colors.slice(0, this.values.length) : this.colors[0],
          borderColor: isPie ? gap : this.colors[0],
          borderWidth: isPie ? 2 : 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        animation: false,
        color: muted,
        indexAxis: this.horizontal && !isPie ? 'y' : 'x',
        plugins: {
          legend: { display: isPie, position: 'right', labels: { color: muted } },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const v = ctx.parsed.y ?? ctx.parsed.x ?? ctx.parsed;
                return ` ${ctx.label}: ${this.formatValue(v as number)}`;
              }
            }
          }
        },
        scales: isPie ? undefined : (
          this.horizontal
            ? {
                x: { beginAtZero: true, ticks: { color: muted, callback: (v) => this.formatValue(v as number) }, grid: { color: grid } },
                y: { ticks: { color: muted }, grid: { color: grid } }
              }
            : {
                y: { beginAtZero: true, ticks: { color: muted, callback: (v) => this.formatValue(v as number) }, grid: { color: grid } },
                x: { ticks: { color: muted }, grid: { color: grid } }
              }
        )
      }
    };
    this.chart = new Chart(this.canvasRef.nativeElement, config);
  }

  ngOnDestroy(): void {
    this.themeObserver?.disconnect();
    this.chart?.destroy();
  }



  private formatValue(n: number): string {
    if (n == null) return '0';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: 0 });
  }
}
