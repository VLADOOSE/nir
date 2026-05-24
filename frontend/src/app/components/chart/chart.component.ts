import { Component, ElementRef, Input, OnChanges, OnDestroy, ViewChild } from '@angular/core';
import { Chart, ChartConfiguration, ChartType, registerables } from 'chart.js';

Chart.register(...registerables);

@Component({
  selector: 'app-chart',
  standalone: true,
  template: `<canvas #canvas></canvas>`,
  styles: [`:host { display: block; position: relative; height: 280px; } canvas { max-height: 100%; }`]
})
export class ChartComponent implements OnChanges, OnDestroy {
  @ViewChild('canvas', { static: true }) canvasRef!: ElementRef<HTMLCanvasElement>;
  @Input() type: ChartType = 'bar';
  @Input() labels: string[] = [];
  @Input() values: number[] = [];
  @Input() label: string = '';
  @Input() colors: string[] = ['#1a56db', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#06b6d4', '#ec4899', '#84cc16'];
  @Input() horizontal = false;

  private chart?: Chart;

  ngOnChanges(): void {
    if (!this.canvasRef) return;
    if (this.chart) {
      this.chart.destroy();
    }

    const isPie = this.type === 'pie' || this.type === 'doughnut';
    const config: ChartConfiguration = {
      type: this.type,
      data: {
        labels: this.labels,
        datasets: [{
          label: this.label,
          data: this.values,
          backgroundColor: isPie ? this.colors.slice(0, this.values.length) : this.colors[0],
          borderColor: isPie ? '#fff' : this.colors[0],
          borderWidth: isPie ? 2 : 0
        }]
      },
      options: {
        responsive: true,
        maintainAspectRatio: false,
        indexAxis: this.horizontal && !isPie ? 'y' : 'x',
        plugins: {
          legend: { display: isPie, position: 'right' },
          tooltip: {
            callbacks: {
              label: (ctx) => {
                const v = ctx.parsed.y ?? ctx.parsed.x ?? ctx.parsed;
                return ` ${ctx.label}: ${this.formatValue(v as number)}`;
              }
            }
          }
        },
        scales: isPie ? undefined : {
          y: { beginAtZero: true, ticks: { callback: (v) => this.formatValue(v as number) } }
        }
      }
    };
    this.chart = new Chart(this.canvasRef.nativeElement, config);
  }

  ngOnDestroy(): void {
    this.chart?.destroy();
  }

  private formatValue(n: number): string {
    if (n == null) return '0';
    return Number(n).toLocaleString('ru-RU', { maximumFractionDigits: 0 });
  }
}
