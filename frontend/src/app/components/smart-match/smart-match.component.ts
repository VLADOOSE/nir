import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';

type Preset = 'BALANCED' | 'MAX_PROFIT' | 'RELIABILITY' | 'CUSTOM';

interface Weights { price: number; margin: number; track: number; dim: number; }

const LS_KEY = 'smartMatch.v1';

@Component({
  selector: 'app-smart-match',
  standalone: true,
  imports: [CommonModule, FormsModule, MarketMoneyPipe],
  template: `
    <div class="sm-panel">
      <div class="sm-header">
        <strong>Подбор оборудования для лота №{{ lotNumber }}</strong>
        <button class="sm-close" (click)="close.emit()" title="Закрыть">✕</button>
      </div>

      <div class="sm-presets">
        <button [class.active]="preset === 'BALANCED'"    (click)="setPreset('BALANCED')">Баланс</button>
        <button [class.active]="preset === 'MAX_PROFIT'"  (click)="setPreset('MAX_PROFIT')">Макс. прибыль</button>
        <button [class.active]="preset === 'RELIABILITY'" (click)="setPreset('RELIABILITY')">Надёжность</button>
        <button [class.active]="preset === 'CUSTOM'"      (click)="setPreset('CUSTOM')">Свой</button>
      </div>

      <div class="sm-sliders" *ngIf="preset === 'CUSTOM'">
        <label>Цена<input type="range" min="0" max="100" [(ngModel)]="weights.price"  (ngModelChange)="onSlider()"><span>{{ weights.price }}</span></label>
        <label>Маржа<input type="range" min="0" max="100" [(ngModel)]="weights.margin" (ngModelChange)="onSlider()"><span>{{ weights.margin }}</span></label>
        <label>Опыт<input type="range" min="0" max="100" [(ngModel)]="weights.track"  (ngModelChange)="onSlider()"><span>{{ weights.track }}</span></label>
        <label>Габар.<input type="range" min="0" max="100" [(ngModel)]="weights.dim"    (ngModelChange)="onSlider()"><span>{{ weights.dim }}</span></label>
        <span class="sm-sum">Σ = {{ weights.price + weights.margin + weights.track + weights.dim }}</span>
      </div>

      <div class="sm-coldstart" *ngIf="result && !result.hasHistory">
        ⚠ Истории сделок пока нет — рекомендации основаны только на габаритах. После первых выигранных тендеров система будет учитывать маржу, цену и опыт.
      </div>

      <div class="sm-loading" *ngIf="loading">Загрузка…</div>

      <div class="sm-recommend" *ngIf="recommended">
        ⭐ <strong>Рекомендация СППР:</strong> {{ recommended.name }} ({{ recommended.score }} баллов)
        <span *ngIf="recommended.bestDistributor"> · лучший дистрибьютор: {{ recommended.bestDistributor.name }} (ср. маржа {{ recommended.bestDistributor.avgMarginPercent }} %)</span>
      </div>

      <table class="sm-table" *ngIf="result?.candidates?.length">
        <thead>
          <tr>
            <th>#</th><th>Наименование</th><th>Score</th><th>Цена</th><th>Маржа</th><th>Опыт</th><th>Габар.</th><th></th>
          </tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let c of result.candidates">
            <tr [class.top]="c.recommended">
              <td>{{ c.rank }}</td>
              <td>
                <div class="cand-name">{{ c.name }}</div>
                <small class="cand-meta">{{ c.manufact }} · {{ c.equipType }}</small>
              </td>
              <td class="score">{{ c.score }}</td>
              <td><div class="bar" [title]="c.breakdown.price.raw"><span [style.width.%]="c.breakdown.price.value" [class.nodata]="c.breakdown.price.noData"></span></div></td>
              <td><div class="bar" [title]="c.breakdown.margin.raw"><span [style.width.%]="c.breakdown.margin.value" [class.nodata]="c.breakdown.margin.noData"></span></div></td>
              <td><div class="bar" [title]="c.breakdown.track.raw"><span [style.width.%]="c.breakdown.track.value"></span></div></td>
              <td><div class="bar" [title]="c.breakdown.dim.raw"><span [style.width.%]="c.breakdown.dim.value"></span></div></td>
              <td><button class="sm-toggle" (click)="toggle(c.equipmentId)">{{ expanded.has(c.equipmentId) ? '−' : '+' }}</button></td>
            </tr>
            <tr *ngIf="expanded.has(c.equipmentId)" class="expand">
              <td colspan="8">
                <ul class="raw-list">
                  <li><strong>Цена:</strong> {{ c.breakdown.price.raw }}</li>
                  <li><strong>Маржа:</strong> {{ c.breakdown.margin.raw }}</li>
                  <li><strong>Опыт:</strong> {{ c.breakdown.track.raw }}</li>
                  <li><strong>Габариты:</strong> {{ c.breakdown.dim.raw }}</li>
                </ul>
                <p *ngIf="c.estimatedPrice" class="estim">Оценочная цена: <strong>{{ c.estimatedPrice | money }}</strong> · ожидаемая маржа: <strong>{{ c.estimatedMargin | money }}</strong></p>
                <button *ngIf="c.bestDistributor" class="btn-pr" (click)="requestPrice.emit({ candidate: c, distributorId: c.bestDistributor.distributorId, distributorName: c.bestDistributor.name })">
                  Запросить КП у {{ c.bestDistributor.name }}
                </button>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>

      <div *ngIf="result && !result.candidates?.length && !loading" class="sm-empty">Нет кандидатов под габариты лота.</div>
    </div>
  `,
  styles: [`
    .sm-panel { border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px; background: #fff; margin: 12px 0; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
    .sm-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid #f3f4f6; }
    .sm-header strong { font-size: 15px; color: #111827; }
    .sm-close { background: none; border: none; font-size: 18px; cursor: pointer; color: #6b7280; padding: 2px 6px; border-radius: 4px; }
    .sm-close:hover { background: #f3f4f6; color: #111827; }
    .sm-presets { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
    .sm-presets button { padding: 6px 14px; border: 1px solid #d1d5db; background: #f9fafb; border-radius: 4px; cursor: pointer; font-size: 13px; transition: all 0.15s; }
    .sm-presets button:hover { background: #f3f4f6; }
    .sm-presets button.active { background: #1a56db; color: #fff; border-color: #1a56db; }
    .sm-sliders { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; padding: 14px; background: #f9fafb; border-radius: 6px; margin-bottom: 12px; border: 1px dashed #d1d5db; }
    .sm-sliders label { display: grid; grid-template-columns: 60px 1fr 30px; align-items: center; gap: 8px; font-size: 13px; color: #374151; }
    .sm-sliders input { width: 100%; }
    .sm-sliders span { font-weight: 600; color: #1a56db; text-align: right; }
    .sm-sum { grid-column: span 2; text-align: right; font-weight: 600; color: #6b7280; font-size: 12px; padding-top: 4px; border-top: 1px solid #e5e7eb; }
    .sm-coldstart { background: #fef3c7; border-left: 3px solid #f59e0b; padding: 10px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: #92400e; }
    .sm-recommend { background: linear-gradient(to right, #eff6ff, #fff); border-left: 3px solid #1a56db; padding: 12px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: #1e3a8a; }
    .sm-recommend strong { color: #1a56db; }
    .sm-loading { padding: 24px; text-align: center; color: #6b7280; font-size: 13px; }
    .sm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .sm-table th { background: #f3f4f6; padding: 8px 10px; text-align: left; font-weight: 600; color: #374151; border-bottom: 1px solid #d1d5db; font-size: 12px; text-transform: uppercase; letter-spacing: 0.3px; }
    .sm-table td { padding: 10px; border-bottom: 1px solid #f3f4f6; vertical-align: middle; }
    .sm-table tr.top td { background: #eff6ff; }
    .sm-table .cand-name { font-weight: 500; color: #111827; }
    .sm-table .cand-meta { color: #6b7280; font-size: 11px; }
    .sm-table .score { font-weight: 700; font-size: 15px; color: #1a56db; text-align: center; min-width: 50px; }
    .sm-table .bar { width: 70px; height: 8px; background: #e5e7eb; border-radius: 4px; overflow: hidden; }
    .sm-table .bar span { display: block; height: 100%; background: linear-gradient(to right, #10b981, #059669); border-radius: 4px; transition: width 0.25s; }
    .sm-table .bar span.nodata { background: repeating-linear-gradient(45deg, #d1d5db, #d1d5db 4px, #e5e7eb 4px, #e5e7eb 8px); }
    .sm-table tr.expand td { background: #fafafa; padding: 14px 16px; }
    .sm-table .raw-list { margin: 0 0 8px 0; padding: 0; list-style: none; display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px; }
    .sm-table .raw-list li { font-size: 12px; color: #4b5563; }
    .sm-table .raw-list strong { color: #111827; font-weight: 600; }
    .sm-table .estim { margin: 6px 0; font-size: 13px; color: #374151; }
    .sm-table .estim strong { color: #059669; }
    .btn-pr { background: #1a56db; color: #fff; border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; }
    .btn-pr:hover { background: #1e40af; }
    .sm-toggle { background: #f3f4f6; border: 1px solid #d1d5db; width: 24px; height: 24px; border-radius: 4px; cursor: pointer; font-weight: 700; color: #374151; }
    .sm-toggle:hover { background: #e5e7eb; }
    .sm-empty { text-align: center; color: #6b7280; padding: 24px; font-size: 13px; }
  `]
})
export class SmartMatchComponent implements OnChanges {
  @Input() lotId!: number;
  @Input() lotNumber: number = 0;
  @Output() close = new EventEmitter<void>();
  @Output() requestPrice = new EventEmitter<{ candidate: any; distributorId: number; distributorName: string }>();

  preset: Preset = 'BALANCED';
  weights: Weights = { price: 25, margin: 25, track: 25, dim: 25 };
  result: any = null;
  loading = false;
  expanded = new Set<number>();
  private debouncer = new Subject<void>();

  constructor(private api: ApiService) {
    const saved = localStorage.getItem(LS_KEY);
    if (saved) {
      try {
        const parsed = JSON.parse(saved);
        if (parsed.preset) this.preset = parsed.preset;
        if (parsed.weights) this.weights = parsed.weights;
      } catch {}
    }
    this.debouncer.pipe(debounceTime(300)).subscribe(() => this.fetch());
  }

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['lotId'] && this.lotId) {
      this.fetch();
    }
  }

  setPreset(p: Preset) {
    this.preset = p;
    this.save();
    this.fetch();
  }

  onSlider() {
    this.save();
    this.debouncer.next();
  }

  toggle(id: number) {
    if (this.expanded.has(id)) this.expanded.delete(id);
    else this.expanded.add(id);
  }

  get recommended(): any | null {
    if (!this.result?.candidates?.length) return null;
    const top = this.result.candidates[0];
    return top.recommended ? top : null;
  }

  formatMoney(v: any): string {
    if (v == null) return '0';
    return Number(v).toLocaleString('ru-RU', { maximumFractionDigits: 0 });
  }

  private save() {
    localStorage.setItem(LS_KEY, JSON.stringify({ preset: this.preset, weights: this.weights }));
  }

  private fetch() {
    if (!this.lotId) return;
    this.loading = true;
    const body: any = { preset: this.preset };
    if (this.preset === 'CUSTOM') body.weights = this.weights;
    this.api.postMatchEquipment(this.lotId, body).subscribe({
      next: (r) => { this.result = r; this.loading = false; },
      error: () => { this.loading = false; this.result = null; }
    });
  }
}
