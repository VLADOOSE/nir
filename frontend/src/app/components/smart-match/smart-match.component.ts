import { Component, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subject } from 'rxjs';
import { debounceTime } from 'rxjs/operators';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
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
        <strong>Подбор оборудования{{ lotNumber ? ' для лота №' + lotNumber : '' }}</strong>
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

      <div class="sm-nocriteria" *ngIf="result?.noCriteria">
        🚫 Недостаточно данных для подбора: у лота нет типа оборудования и габаритов/веса, а в спецификации их распознать не удалось.
        Нажмите «ТЗ» у лота — система скачает и разберёт техспецификацию с goszakup, — либо задайте тип/габариты вручную («Редактировать»).
      </div>

      <div class="sm-coldstart" *ngIf="result && !result.hasHistory && !result.noCriteria">
        ⚠ Истории сделок пока нет — рекомендации основаны только на габаритах. После первых выигранных тендеров система будет учитывать маржу, цену и опыт.
      </div>

      <div class="sm-specderived" *ngIf="result?.specDerived as sd">
        📐 Ограничения из спеки:
        <ng-container *ngIf="sd.lengthMm">≤ {{ sd.lengthMm }}×{{ sd.widthMm }}×{{ sd.heightMm }} мм</ng-container>
        <ng-container *ngIf="sd.weightKg"><span *ngIf="sd.lengthMm">, </span>≤ {{ sd.weightKg }} кг</ng-container>
      </div>

      <div class="sm-loading" *ngIf="loading">Загрузка…</div>

      <div class="sm-recommend" *ngIf="recommended">
        ⭐ <strong>Рекомендация СППР:</strong> {{ recommended.name }} ({{ recommended.score }} баллов)
        <span *ngIf="recommended.bestDistributor"> · лучший дистрибьютор: {{ recommended.bestDistributor.name }} (ср. маржа {{ recommended.bestDistributor.avgMarginPercent }} %)</span>
      </div>

      <div class="table-scroll" *ngIf="result?.candidates?.length">
      <table class="sm-table">
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
                <button class="btn-approve" *ngIf="proposedEquipmentId !== c.equipmentId" (click)="approve(c)">
                  ☑ Утвердить модель
                </button>
                <span class="approved-badge" *ngIf="proposedEquipmentId === c.equipmentId">Предложена для лота</span>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>
      </div>

      <div *ngIf="result && !result.candidates?.length && !loading && !result.noCriteria" class="sm-empty">Нет кандидатов под габариты лота.</div>
    </div>
  `,
  styles: [`
    .sm-panel { border: 1px solid var(--border); border-radius: 8px; padding: 16px; background: var(--surface); margin: 12px 0; box-shadow: 0 1px 3px rgba(0,0,0,0.04); }
    .sm-header { display: flex; justify-content: space-between; align-items: center; margin-bottom: 14px; padding-bottom: 10px; border-bottom: 1px solid var(--border); }
    .sm-header strong { font-size: 15px; color: var(--text); }
    .sm-close { background: none; border: none; font-size: 18px; cursor: pointer; color: var(--text-muted); padding: 2px 6px; border-radius: 4px; }
    .sm-close:hover { background: var(--surface-2); color: var(--text); }
    .sm-presets { display: flex; gap: 6px; margin-bottom: 12px; flex-wrap: wrap; }
    /* color задан явно: у <button> цвет текста не наследуется (системный buttontext),
       и на тёмной заливке --surface-2 подпись осталась бы чёрной. */
    .sm-presets button { padding: 6px 14px; border: 1px solid var(--border); background: var(--surface-2); color: var(--text); border-radius: 4px; cursor: pointer; font-size: 13px; transition: all 0.15s; }
    /* Оба исходных серых (фон и ховер) дают --surface-2, буквальный перевод сделал бы
       правило no-op. Затемнение — идиомой kit (.btn-cancel:hover в styles.scss). */
    .sm-presets button:hover { background: color-mix(in srgb, var(--text) 12%, var(--surface-2)); }
    .sm-presets button.active { background: var(--accent); color: var(--accent-contrast); border-color: var(--accent); }
    .sm-sliders { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 18px; padding: 14px; background: var(--surface-2); border-radius: 6px; margin-bottom: 12px; border: 1px dashed var(--border); }
    .sm-sliders label { display: grid; grid-template-columns: 60px 1fr 30px; align-items: center; gap: 8px; font-size: 13px; color: var(--text); }
    .sm-sliders input { width: 100%; }
    .sm-sliders span { font-weight: 600; color: var(--accent); text-align: right; }
    .sm-sum { grid-column: span 2; text-align: right; font-weight: 600; color: var(--text-muted); font-size: 12px; padding-top: 4px; border-top: 1px solid var(--border); }
    .sm-coldstart { background: color-mix(in srgb, var(--warn) 15%, transparent); border-left: 3px solid var(--warn); padding: 10px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: var(--warn-text); }
    .sm-recommend { background: linear-gradient(to right, color-mix(in srgb, var(--accent) 15%, transparent), transparent); border-left: 3px solid var(--accent); padding: 12px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: var(--accent); }
    .sm-recommend strong { color: var(--accent); }
    .sm-loading { padding: 24px; text-align: center; color: var(--text-muted); font-size: 13px; }
    .sm-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .sm-table th { background: var(--surface-2); padding: 8px 10px; text-align: left; font-weight: 600; color: var(--text); border-bottom: 1px solid var(--border); font-size: 12px; text-transform: uppercase; letter-spacing: 0.3px; }
    .sm-table td { padding: 10px; border-bottom: 1px solid var(--border); vertical-align: middle; }
    /* Подсветка ОБЛАСТИ (рекомендованный кандидат): непрозрачный микс с --surface + рамка.
       НЕ --surface-2 — урок 6b8dc3f: там подсветка совпала с фоном панели и смысл инвертировался.
       Токен --accent, а не --success: исходный тинт синий, и вся семантика рекомендации
       в этой модалке синяя (баннер СППР, score) — зелёный был бы перекраской, а не переводом. */
    .sm-table tr.top td { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .sm-table .cand-name { font-weight: 500; color: var(--text); }
    .sm-table .cand-meta { color: var(--text-muted); font-size: 11px; }
    .sm-table .score { font-weight: 700; font-size: 15px; color: var(--accent); text-align: center; min-width: 50px; }
    .sm-table .bar { width: 70px; height: 8px; background: var(--border); border-radius: 4px; overflow: hidden; }
    .sm-table .bar span { display: block; height: 100%; background: var(--success); border-radius: 4px; transition: width 0.25s; }
    /* Штриховка «нет данных»: обе исходные краски полос дают --border, то есть слились бы
       с дорожкой бара. Полосы — полупрозрачный --text-muted поверх дорожки. */
    .sm-table .bar span.nodata { background: repeating-linear-gradient(45deg, color-mix(in srgb, var(--text-muted) 35%, transparent), color-mix(in srgb, var(--text-muted) 35%, transparent) 4px, transparent 4px, transparent 8px); }
    .sm-table tr.expand td { background: var(--surface-2); padding: 14px 16px; }
    .sm-table .raw-list { margin: 0 0 8px 0; padding: 0; list-style: none; display: grid; grid-template-columns: 1fr 1fr; gap: 4px 16px; }
    .sm-table .raw-list li { font-size: 12px; color: var(--text-muted); }
    .sm-table .raw-list strong { color: var(--text); font-weight: 600; }
    .sm-table .estim { margin: 6px 0; font-size: 13px; color: var(--text); }
    .sm-table .estim strong { color: var(--success-text); }
    .btn-pr { background: var(--accent); color: var(--accent-contrast); border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; }
    /* --accent-hover, а не --accent: в светлой теме токен равен прежнему тёмно-синему
       значению, а перевод по букве таблицы (в --accent) сделал бы ховер no-op. */
    .btn-pr:hover { background: var(--accent-hover); }
    .sm-specderived { background: color-mix(in srgb, var(--accent) 15%, transparent); border-left: 3px solid var(--accent); padding: 10px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: var(--accent); }
    /* Фикс-цветная заливка (ограничение 5 плана; тот же зелёный у .btn-adopt в lot-registry-panel):
       белая подпись на осветлённом --success в тёмной теме даёт контраст <2:1.
       Токенизируется только цвет текста; ховер остаётся собственным затемнением. */
    .btn-approve { background: #0e9f6e; color: var(--accent-contrast); border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 12px; margin-left: 8px; }
    .btn-approve:hover { background: #057a55; }
    .approved-badge { display: inline-block; margin-left: 8px; background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); border-radius: 10px; padding: 4px 10px; font-size: 12px; font-weight: 600; }
    .sm-toggle { background: var(--surface-2); border: 1px solid var(--border); width: 24px; height: 24px; border-radius: 4px; cursor: pointer; font-weight: 700; color: var(--text); }
    .sm-toggle:hover { background: var(--border); }
    .sm-empty { text-align: center; color: var(--text-muted); padding: 24px; font-size: 13px; }
    .sm-nocriteria { background: color-mix(in srgb, var(--danger) 15%, transparent); border-left: 3px solid var(--danger); padding: 10px 14px; border-radius: 4px; margin-bottom: 12px; font-size: 13px; color: var(--danger-text); }
  `]
})
export class SmartMatchComponent implements OnChanges {
  @Input() lotId!: number;
  @Input() lotNumber: number = 0;
  @Input() proposedEquipmentId: number | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() requestPrice = new EventEmitter<{ candidate: any; distributorId: number; distributorName: string }>();
  @Output() proposedChanged = new EventEmitter<void>();

  preset: Preset = 'BALANCED';
  weights: Weights = { price: 25, margin: 25, track: 25, dim: 25 };
  result: any = null;
  loading = false;
  expanded = new Set<number>();
  private debouncer = new Subject<void>();

  constructor(private api: ApiService, private notify: NotificationService) {
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

  approve(c: any) {
    this.api.setProposedEquipment(this.lotId, c.equipmentId).subscribe({
      next: () => {
        this.notify.success(`Модель «${c.name}» предложена для лота`);
        this.proposedChanged.emit();
      },
      error: (e: any) => this.notify.error(e.error?.message || 'Не удалось утвердить модель'),
    });
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
