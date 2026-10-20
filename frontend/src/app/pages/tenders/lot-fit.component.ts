import { Component, Input, OnChanges } from '@angular/core';
import { NgClass, NgFor, NgIf } from '@angular/common';
import { NO_RU, shortProduct } from '../../shared/product-watch';
import type { FitCandidateView, FitMark, LotProductMatch } from '../../shared/product-watch';

/**
 * «Сверка с нашим товаром» и «Требования ТЗ» у лота (спека 2026-10-20-product-spec-fit §8.1): одна колонка и на телефоне;
 * сначала ✗, потом ≈, ✓, «нет данных» (порядок строк — с сервера); «ещё: …» переключает сравнение без запроса.
 */
@Component({
  selector: 'app-lot-fit',
  standalone: true,
  imports: [NgIf, NgFor, NgClass],
  template: `
    <section class="lf" *ngIf="match && current as c">
      <div class="lf-head">
        <span class="lf-title">Сверка с нашим товаром</span>
        <span class="lf-foreign" *ngIf="match.foreignBrand">ТЗ под {{ match.foreignBrand }}</span>
      </div>
      <div class="lf-product">
        <a *ngIf="c.url; else plainName" [href]="c.url" target="_blank" rel="noopener">{{ c.name }} ↗</a>
        <ng-template #plainName><span>{{ c.name }}</span></ng-template>
        <span class="lf-reg" [ngClass]="c.regStatus === 'ACTIVE' ? 'lf-reg-ok' : 'lf-reg-bad'">{{ regText(c) }}</span>
      </div>
      <div class="lf-count">✓ {{ c.met }} · ✗ {{ c.failed }} · ≈ {{ diff(c) }} · нет данных {{ c.unknown }}</div>
      <div class="lf-alts" *ngIf="match.candidates.length > 1">
        <span class="lf-alts-label">ещё:</span>
        <ng-container *ngFor="let a of match.candidates; let i = index">
          <button type="button" class="lf-alt" *ngIf="i !== index" (click)="index = i"
                  [title]="'Сравнить ТЗ с ' + a.name">{{ short(a.name) }}{{ i === 0 ? ' (лучший)' : '' }}</button>
        </ng-container>
      </div>
      <ol class="lf-rows">
        <li *ngFor="let r of c.rows" [ngClass]="'lf-' + r.mark">
          <span class="lf-mark" [attr.aria-label]="markLabel(r.mark)">{{ markIcon(r.mark) }}</span>
          <span class="lf-text">{{ r.text }}
            <span class="lf-ours">{{ r.ours ? 'у нас: ' + r.ours + (r.source ? ' (' + r.source + ')' : '') : 'нет данных' }}</span>
          </span>
        </li>
      </ol>
    </section>
    <section class="lf" *ngIf="!match && requirements?.length">
      <div class="lf-head"><span class="lf-title">Требования ТЗ: {{ requirements!.length }}</span></div>
      <ol class="lf-rows lf-plain"><li *ngFor="let r of requirements"><span class="lf-text">{{ r }}</span></li></ol>
    </section>
  `,
  styles: [`
    .lf { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .lf-head { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
    .lf-title { font-weight: 600; color: var(--text); }
    .lf-foreign { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text-muted); }
    .lf-product { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; overflow-wrap: anywhere; }
    .lf-product a { color: var(--accent); }
    .lf-reg { font-size: 12px; padding: 1px 8px; border-radius: 999px; }
    .lf-reg-ok { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .lf-reg-bad { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .lf-count { margin-top: 4px; font-size: 13px; color: var(--text-muted); }
    .lf-alts { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12px; color: var(--text-muted); }
    .lf-alt { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface-2);
      color: var(--text); cursor: pointer; }
    .lf-rows { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; }
    .lf-rows li { display: grid; grid-template-columns: 22px 1fr; gap: 6px; align-items: start; }
    .lf-plain li { grid-template-columns: 1fr; }
    .lf-mark { font-weight: 700; text-align: center; }
    .lf-OK .lf-mark { color: var(--success-text); }
    .lf-FAIL .lf-mark { color: var(--danger-text); }
    .lf-DIFF .lf-mark { color: var(--warn-text); }
    .lf-UNKNOWN .lf-mark { color: var(--text-muted); }
    .lf-text { color: var(--text); overflow-wrap: anywhere; }
    .lf-ours { display: block; font-size: 12px; color: var(--text-muted); }
    @media (max-width: 900px) {
      .lf-alt { min-height: 40px; }
    }
  `]
})
export class LotFitComponent implements OnChanges {
  @Input() match: LotProductMatch | null = null;
  @Input() requirements: string[] | null = null;
  index = 0;

  get current(): FitCandidateView | null { return this.match?.candidates?.[this.index] ?? null; }

  ngOnChanges(): void { this.index = 0; }

  short(name: string): string { return shortProduct(name); }
  diff(c: FitCandidateView): number { return c.rows.filter(r => r.mark === 'DIFF').length; }
  regText(c: FitCandidateView): string {
    return c.regStatus === 'ACTIVE' ? 'РУ действует' + (c.regNumber ? ' · ' + c.regNumber : '') : (NO_RU[c.regStatus] ?? 'РУ не проверено');
  }
  markIcon(m: FitMark): string { return m === 'OK' ? '✓' : m === 'FAIL' ? '✗' : m === 'DIFF' ? '≈' : '?'; }
  markLabel(m: FitMark): string {
    return m === 'OK' ? 'проходит' : m === 'FAIL' ? 'не проходит' : m === 'DIFF' ? 'отличается' : 'нет данных';
  }
}
