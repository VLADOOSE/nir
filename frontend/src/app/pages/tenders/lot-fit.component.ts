import { Component, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { NgClass, NgFor, NgIf } from '@angular/common';
import { NO_RU, shortProduct } from '../../shared/product-watch';
import type { FitCandidateView, FitMark, LotProductMatch } from '../../shared/product-watch';

/**
 * «Сверка с нашим товаром» и «Требования ТЗ» у лота (спека 2026-10-20-product-spec-fit §8.1): сначала ✗, потом ≈, ✓,
 * «нет данных» (порядок строк — с сервера); «ещё: …» переключает сравнение без запроса. Большие ТЗ (у ИВЛ — 150
 * требований, почти все «нет данных»; отзыв оператора 2026-10-20 «читать неудобно, особенно с телефона»): над списком —
 * переключатели ✗ / ≈ / ✓ / «нет данных» (оставляют только свои строки), «нет данных» свёрнуты под «ещё N без данных ▾»;
 * в широком блоке — две колонки «Требование ТЗ | У нас» (по ширине самого блока — container query); длинный список
 * «Требования ТЗ» без сверки — первые {@link PLAIN_SHOWN} и «ещё N ▾».
 */
@Component({
  selector: 'app-lot-fit',
  standalone: true,
  imports: [NgIf, NgFor, NgClass],
  template: `
    <section class="lf" *ngIf="list.length && current as c">
      <div class="lf-head">
        <span class="lf-title">{{ title }}</span>
        <span class="lf-foreign" *ngIf="match?.foreignBrand">ТЗ под {{ match!.foreignBrand }}</span>
      </div>
      <div class="lf-product">
        <a *ngIf="c.url; else plainName" [href]="c.url" target="_blank" rel="noopener">{{ c.name }} ↗</a>
        <ng-template #plainName><span>{{ c.name }}</span></ng-template>
        <span class="lf-reg" [ngClass]="c.regStatus === 'ACTIVE' ? 'lf-reg-ok' : 'lf-reg-bad'">{{ regText(c) }}</span>
        <button type="button" class="lf-pin" *ngIf="canPin && c.productId && c.productId !== pinnedProductId"
                (click)="pin.emit(c.productId)" title="Закрепить этот товар моделью в работе лота">📌 Закрепить</button>
        <span class="lf-pinned" *ngIf="isPinned(c)">📌 в работе</span>
      </div>
      <div class="lf-filters" role="group" aria-label="Какие строки сверки показать">
        <ng-container *ngFor="let m of marks">
          <button type="button" class="lf-f" *ngIf="count(c, m) > 0" [ngClass]="'lf-f-' + m" [class.on]="filter === m"
                  [attr.aria-pressed]="filter === m" (click)="toggle(m)" [title]="filter === m ? 'Показать все строки' : 'Только: ' + markLabel(m)">
            {{ m === 'UNKNOWN' ? 'нет данных' : markIcon(m) }} {{ count(c, m) }}</button>
        </ng-container>
        <button type="button" class="lf-f lf-f-all" *ngIf="filter" (click)="filter = null">все строки</button>
      </div>
      <div class="lf-alts" *ngIf="list.length > 1">
        <span class="lf-alts-label">ещё:</span>
        <ng-container *ngFor="let a of list; let i = index">
          <button type="button" class="lf-alt" *ngIf="i !== index" (click)="index = i"
                  [title]="'Сравнить ТЗ с ' + a.name">{{ short(a.name) }}{{ i === 0 ? ' (лучший)' : '' }}</button>
        </ng-container>
      </div>
      <!-- закреплённый товар — те же строки, что в «Сверке с моделью в работе» выше: не повторяем -->
      <p class="lf-dup" *ngIf="isPinned(c)">Строки сверки — выше, в «Сверке с моделью в работе».</p>
      <ng-container *ngIf="!isPinned(c)">
        <div class="lf-cols" aria-hidden="true"><span></span><span>Требование ТЗ</span><span>{{ oursHead }}</span></div>
        <ol class="lf-rows">
          <li *ngFor="let r of visible(c)" [ngClass]="'lf-' + r.mark">
            <span class="lf-mark" [attr.aria-label]="markLabel(r.mark)">{{ markIcon(r.mark) }}</span>
            <span class="lf-text">{{ r.text }}</span>
            <span class="lf-ours"><ng-container *ngIf="r.ours; else noData"><span class="lf-ol">{{ oursLabel }}: </span>{{ r.ours }}{{ r.source ? ' (' + r.source + ')' : '' }}</ng-container><ng-template #noData>нет данных</ng-template></span>
          </li>
        </ol>
        <button type="button" class="lf-more" *ngIf="!filter && count(c, 'UNKNOWN') > 0 && hasConfirmedOrDiff(c)" (click)="showUnknown = !showUnknown">
          {{ showUnknown ? 'скрыть без данных ▴' : 'ещё ' + count(c, 'UNKNOWN') + ' без данных ▾' }}</button>
      </ng-container>
    </section>
    <section class="lf" *ngIf="!match && !candidates && requirements?.length">
      <div class="lf-head"><span class="lf-title">Требования ТЗ: {{ requirements!.length }}</span></div>
      <ol class="lf-rows lf-plain"><li *ngFor="let r of plainShown()"><span class="lf-text">{{ r }}</span></li></ol>
      <button type="button" class="lf-more" *ngIf="requirements!.length > PLAIN_SHOWN" (click)="showAllPlain = !showAllPlain">
        {{ showAllPlain ? 'свернуть ▴' : 'ещё ' + (requirements!.length - PLAIN_SHOWN) + ' ▾' }}</button>
    </section>
  `,
  styles: [`
    :host { display: block; container: lot-fit / inline-size; }
    .lf { margin: 8px 0; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .lf-head { display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; }
    .lf-title { font-weight: 600; color: var(--text); }
    .lf-foreign { font-size: 12px; padding: 2px 8px; border-radius: 999px; background: var(--surface-2); color: var(--text-muted); }
    .lf-product { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 8px; align-items: baseline; overflow-wrap: anywhere; }
    .lf-product a { color: var(--accent); }
    .lf-reg { font-size: 12px; padding: 1px 8px; border-radius: 999px; }
    .lf-reg-ok { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); }
    .lf-reg-bad { color: var(--warn-text); background: color-mix(in srgb, var(--warn) 15%, transparent); }
    .lf-filters { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; }
    .lf-f { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface);
      color: var(--text); cursor: pointer; font-variant-numeric: tabular-nums; }
    .lf-f-FAIL { color: var(--danger-text); }
    .lf-f-DIFF { color: var(--warn-text); }
    .lf-f-OK { color: var(--success-text); }
    .lf-f-UNKNOWN, .lf-f-all { color: var(--text-muted); }
    .lf-f.on { border-color: currentColor; background: color-mix(in srgb, currentColor 12%, var(--surface)); font-weight: 600; }
    .lf-more { margin-top: 6px; font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--border);
      background: var(--surface-2); color: var(--text); cursor: pointer; }
    .lf-cols { display: none; }
    .lf-alts { margin-top: 6px; display: flex; flex-wrap: wrap; gap: 6px; align-items: center; font-size: 12px; color: var(--text-muted); }
    .lf-alt { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--border); background: var(--surface-2);
      color: var(--text); cursor: pointer; }
    .lf-pin { font-size: 12px; padding: 2px 10px; border-radius: 999px; border: 1px solid var(--accent); background: var(--surface);
      color: var(--accent); cursor: pointer; }
    .lf-pinned { font-size: 12px; padding: 1px 8px; border-radius: 999px; color: var(--accent);
      background: color-mix(in srgb, var(--accent) 15%, transparent); }
    .lf-dup { margin: 6px 0 0; font-size: 12px; color: var(--text-muted); }
    .lf-rows { list-style: none; margin: 8px 0 0; padding: 0; display: grid; gap: 6px; }
    .lf-rows li { display: grid; grid-template-columns: 22px minmax(0, 1fr); column-gap: 6px; align-items: start; }
    .lf-rows li .lf-ours { grid-column: 2; }
    .lf-plain li { grid-template-columns: 1fr; }
    .lf-mark { font-weight: 700; text-align: center; }
    .lf-OK .lf-mark { color: var(--success-text); }
    .lf-FAIL .lf-mark { color: var(--danger-text); }
    .lf-DIFF .lf-mark { color: var(--warn-text); }
    .lf-UNKNOWN .lf-mark { color: var(--text-muted); }
    .lf-text { color: var(--text); overflow-wrap: anywhere; }
    .lf-ours { display: block; font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    /* широкий блок — две колонки «Требование ТЗ | У нас»: сравнивать строку со строкой, а не читать столбиком */
    @container lot-fit (min-width: 720px) {
      .lf-cols { display: grid; grid-template-columns: 22px minmax(0, 3fr) minmax(0, 2fr); column-gap: 10px; margin-top: 8px;
        font-size: 11px; color: var(--text-muted); text-transform: uppercase; letter-spacing: .02em; }
      .lf-rows:not(.lf-plain) li { grid-template-columns: 22px minmax(0, 3fr) minmax(0, 2fr); column-gap: 10px;
        padding-bottom: 6px; border-bottom: 1px solid var(--border); }
      .lf-rows:not(.lf-plain) li .lf-ours { grid-column: 3; font-size: 13px; }
      /* подпись колонки «У нас» уже в шапке — прячем с экрана, диктору оставляем (шапка ему не видна) */
      .lf-ol { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
    }
    @media (max-width: 900px) {
      .lf-alt, .lf-pin, .lf-f, .lf-more { min-height: 40px; }
    }
  `]
})
export class LotFitComponent implements OnChanges {
  @Input() match: LotProductMatch | null = null;
  @Input() requirements: string[] | null = null;
  @Input() title = 'Сверка с нашим товаром';
  /** Свои кандидаты — сверка модели в работе (без отметки «наш товар»). */
  @Input() candidates: FitCandidateView[] | null = null;
  /** Админ на KZ: у нашего товара — «📌 Закрепить». */
  @Input() canPin = false;
  /** Наш товар, уже закреплённый за лотом, — вместо кнопки метка «📌 в работе». */
  @Input() pinnedProductId: number | null = null;
  @Output() pin = new EventEmitter<number>();
  /** Чьи данные в строке: «у нас» — наш товар; у модели в работе из реестра или каталога — «у модели». */
  @Input() oursLabel = 'у нас';
  /** Строк списка «Требования ТЗ» без сверки до «ещё N ▾». */
  readonly PLAIN_SHOWN = 12;
  readonly marks: FitMark[] = ['FAIL', 'DIFF', 'OK', 'UNKNOWN'];
  private _index = 0;
  /** Показать только строки этой отметки; null — все (без «нет данных», пока не раскрыты). */
  filter: FitMark | null = null;
  showUnknown = false;
  showAllPlain = false;

  get list(): FitCandidateView[] { return this.candidates ?? this.match?.candidates ?? []; }
  get current(): FitCandidateView | null { return this.list[this.index] ?? null; }
  get index(): number { return this._index; }
  /** Другой товар — свои строки: фильтр и раскрытие сбрасываются. */
  set index(i: number) { this._index = i; this.filter = null; this.showUnknown = false; }
  get oursHead(): string { return this.oursLabel === 'у нас' ? 'У нас' : 'У модели'; }

  ngOnChanges(): void { this.index = 0; this.showAllPlain = false; }

  count(c: FitCandidateView, m: FitMark): number { return c.rows.filter(r => r.mark === m).length; }
  /** Есть что показать, кроме «нет данных», — иначе «нет данных» и так весь список (без кнопки «ещё»). */
  hasConfirmedOrDiff(c: FitCandidateView): boolean { return c.rows.some(r => r.mark !== 'UNKNOWN'); }
  visible(c: FitCandidateView) {
    if (this.filter) return c.rows.filter(r => r.mark === this.filter);
    if (this.showUnknown || !this.hasConfirmedOrDiff(c)) return c.rows;
    return c.rows.filter(r => r.mark !== 'UNKNOWN');
  }
  toggle(m: FitMark): void { this.filter = this.filter === m ? null : m; }
  plainShown(): string[] {
    const all = this.requirements ?? [];
    return this.showAllPlain ? all : all.slice(0, this.PLAIN_SHOWN);
  }

  short(name: string): string { return shortProduct(name); }
  isPinned(c: FitCandidateView): boolean { return !!c.productId && c.productId === this.pinnedProductId; }
  regText(c: FitCandidateView): string {
    return c.regStatus === 'ACTIVE' ? 'РУ действует' + (c.regNumber ? ' · ' + c.regNumber : '') : (NO_RU[c.regStatus] ?? 'РУ не проверено');
  }
  markIcon(m: FitMark): string { return m === 'OK' ? '✓' : m === 'FAIL' ? '✗' : m === 'DIFF' ? '≈' : '?'; }
  markLabel(m: FitMark): string {
    return m === 'OK' ? 'проходит' : m === 'FAIL' ? 'не проходит' : m === 'DIFF' ? 'отличается' : 'нет данных';
  }
}
