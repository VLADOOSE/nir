import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import type { PrView } from '../../shared/private-request';
import { money } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';
import { positionsText, stateText } from '../../shared/product-watch-pages';
import type { AnnouncementResult, TenderSearchHit, TenderSearchStatus } from '../../shared/product-watch';

/**
 * «Тендеры по стране» на странице заявки (спека 2026-10-19-product-watch §11): тендеры и планы закупок по всей стране
 * с позициями заявки — кто ещё покупает такое, и не перекупщик ли клиент под тендер. Только KZ; искать могут все.
 */
@Component({
  selector: 'app-pr-tender-search',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Тендеры по стране</h3>
        <button type="button" class="btn btn-line" *ngIf="view.lines.length" [disabled]="running" (click)="start()">
          {{ running ? 'Ищу…' : (status ? 'Искать заново' : 'Найти тендеры по стране') }}</button>
      </div>
      <p class="hint" *ngIf="!status && !running">Тендеры за 90 дней и планы закупок на этот и следующий год с позициями
        этой заявки — по всей стране. Поиск занимает до минуты.</p>
      <p class="hint" *ngIf="running">{{ status?.waiting || ('Ищу… слово ' + (status?.progress || 0) + ' из ' + (status?.total || '…')) }}</p>
      <p class="ts-error" *ngIf="status?.error">{{ status?.error }}</p>
      <p class="hint" *ngIf="status && !running && !status.error && !status.hits.length">Ничего похожего по стране за 90 дней и в планах.</p>
      <div class="ts-hit" *ngFor="let h of status?.hits || []">
        <div class="ts-top">
          <span class="ts-state" [attr.data-state]="h.state">{{ stateText(h) }}</span>
          <span class="ts-match" *ngIf="h.specPositions.length">совпали позиции {{ positionsText(h.specPositions) }} (ТЗ)</span>
          <span class="ts-weak" *ngIf="!h.specPositions.length">похоже на {{ h.positions.length > 1 ? 'позиции' : 'позицию' }} {{ positionsText(h.positions) }}</span>
        </div>
        <div class="ts-cust">{{ h.customer }}<ng-container *ngIf="h.region"> · {{ h.region }}</ng-container></div>
        <div class="ts-lot">{{ h.lotName }}<ng-container *ngIf="h.quantity != null"> — {{ qty(h.quantity) }} шт.</ng-container><ng-container
          *ngIf="h.unitPrice != null"> · {{ money(h.unitPrice) }} ₸ за ед.</ng-container><ng-container
          *ngIf="h.unitPrice == null && h.amount != null"> · {{ money(h.amount) }} ₸</ng-container></div>
        <div class="ts-method" *ngIf="h.tradeMethod">{{ h.tradeMethod }}</div>
        <div class="ts-acts" *ngIf="h.kind === 'TENDER' && h.numberAnno as anno">
          <a class="xref" [href]="portal(anno)" target="_blank" rel="noopener">goszakup № {{ anno }} ↗</a>
          <button type="button" class="btn btn-line ts-small" *ngIf="h.state === 'DONE' || h.state === 'CLOSED'"
                  [disabled]="loadingResult.has(anno)" (click)="result(anno)">Итоги ›</button>
          <a class="btn btn-line ts-small" *ngIf="h.tenderId" [routerLink]="['/tenders']" [queryParams]="{ openId: h.tenderId }">Открыть в АИС</a>
          <button type="button" class="btn btn-line ts-small" *ngIf="!h.tenderId && !readonly" [disabled]="importing === anno"
                  (click)="importIt(h, anno)">{{ importing === anno ? 'Завожу…' : 'Завести в АИС' }}</button>
          <button type="button" class="btn btn-line ts-small" *ngIf="!readonly" [disabled]="notedAnno.has(anno)"
                  (click)="note(h, anno)">{{ notedAnno.has(anno) ? 'В истории ✓' : 'В историю сделки' }}</button>
        </div>
        <div class="ts-result" *ngIf="h.numberAnno && results[h.numberAnno] as r">{{ resultText(r) }}</div>
        <div class="ts-acts" *ngIf="h.kind === 'PLAN'"><a class="xref" routerLink="/procurement-plans">Планы закупок ›</a></div>
      </div>
    </section>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px;
            display: flex; flex-direction: column; gap: 8px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .ts-error { margin: 0; font-size: 13px; color: var(--danger-text); }
    .ts-hit { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
    .ts-top { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .ts-state { font-weight: 600; padding: 2px 8px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .ts-state[data-state="OPEN"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .ts-state[data-state="FAILED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .ts-state[data-state="PLANNED"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .ts-match { color: var(--success-text); font-weight: 600; }
    .ts-weak, .ts-cust, .ts-method { color: var(--text-muted); }
    .ts-cust, .ts-method { font-size: 13px; }
    .ts-lot { color: var(--text); overflow-wrap: anywhere; }
    .ts-acts { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; font-size: 13px; }
    .ts-result { font-size: 13px; color: var(--text); }
    .ts-small { padding: 4px 10px; font-size: 12px; }
    @media (max-width: 900px) { .ts-acts .btn { min-height: 40px; } }
  `],
})
export class PrTenderSearchComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  /** Заметка ушла в историю — странице перечитать ленту. */
  @Output() changed = new EventEmitter<void>();

  readonly money = money;
  readonly stateText = stateText;
  readonly positionsText = positionsText;
  status: TenderSearchStatus | null = null;
  results: Record<string, AnnouncementResult> = {};
  loadingResult = new Set<string>();
  notedAnno = new Set<string>();
  importing: string | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private forId: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  get running(): boolean { return !!this.status?.running; }

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view'] || this.view.id === this.forId) return;
    this.forId = this.view.id;   // другая заявка: прежний результат не показываем
    this.stop();
    this.status = null; this.results = {}; this.notedAnno.clear(); this.importing = null;
    const id = this.view.id;
    this.api.getTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { if (this.forId !== id) return; this.status = s.startedAt ? s : null; this.schedule(); this.cdr.detectChanges(); },
      error: () => {},   // токена нет или чужой рынок — кнопка покажет причину при нажатии
    });
  }

  ngOnDestroy() { this.stop(); }

  start() {
    const id = this.view.id;
    this.api.startTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { if (this.forId !== id) return; this.status = s; this.schedule(); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  private schedule() {
    this.stop();
    if (!this.status?.running) return;
    const id = this.view.id;
    this.timer = setTimeout(() => {
      this.api.getTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: s => { if (this.forId !== id) return; this.status = s; this.schedule(); this.cdr.detectChanges(); },
        error: () => this.schedule(),
      });
    }, 2000);
  }

  private stop() { if (this.timer) { clearTimeout(this.timer); this.timer = null; } }

  portal(anno: string): string { return `https://goszakup.gov.kz/ru/announce/index/${anno.split('-')[0]}`; }

  qty(n: number): string { return Number.isInteger(n) ? String(n) : String(n).replace('.', ','); }

  result(anno: string) {
    this.loadingResult.add(anno);
    this.api.announcementResult(anno).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.loadingResult.delete(anno); this.results[anno] = r; this.cdr.detectChanges(); },
      error: e => { this.loadingResult.delete(anno); this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  resultText(r: AnnouncementResult): string {
    if (r.status !== 'договор есть') return r.status;
    const who = r.supplierName || (r.supplierBin ? 'БИН ' + r.supplierBin : 'поставщик не указан');
    const sum = r.contractSum != null ? `, договор ${money(r.contractSum)} ₸` : ', договор';
    const date = r.signDate ? ` от ${r.signDate.slice(8, 10)}.${r.signDate.slice(5, 7)}.${r.signDate.slice(0, 4)}` : '';
    return `победитель: ${who}${sum}${date}`;
  }

  importIt(h: TenderSearchHit, anno: string) {
    this.importing = anno;
    this.api.importAnnouncement(anno).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.importing = null; h.tenderId = r.tenderId; this.notify.success(`Тендер ${anno} заведён в АИС`); this.cdr.detectChanges(); },
      error: e => { this.importing = null; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  note(h: TenderSearchHit, anno: string) {
    const who = [h.customer, h.region].filter(x => !!x).join(', ');
    const text = `Запрос клиента совпадает с тендером ${anno}${who ? ' (' + who + ')' : ''} — ${stateText(h)}`;
    this.api.addDealEvent('private-request', this.view.id, 'NOTE', text).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.notedAnno.add(anno); this.notify.success('Записано в историю сделки'); this.changed.emit(); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }
}
