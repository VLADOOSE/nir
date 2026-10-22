import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { errorText } from '../../shared/error-text';
import { LotResultView, announceUrl, instantText, plainDate, resultTone } from '../../shared/tender-results';

/** Итог лота подробно (спека 2026-10-22-tender-results-market-prices §11): участники, договор, попытки, протокол. Ленивый чанк. */
@Component({
  selector: 'app-lot-result-panel',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink, MarketMoneyPipe],
  template: `
    <div class="rp">
      <div class="rp-head">
        <b>Итог лота</b>
        <button type="button" class="rp-link" (click)="close.emit()">Скрыть</button>
      </div>
      <p class="rp-muted" *ngIf="loading">Загружаю…</p>
      <p class="rp-err" *ngIf="error">{{ error }}</p>
      <ng-container *ngIf="view as v">
        <p class="rp-text" [class]="'rp-text rp-' + tone(v.outcome)">{{ v.text }}</p>
        <p class="rp-muted">
          <span *ngIf="v.lotStatus">{{ v.lotStatus }}</span><span *ngIf="v.resultsAt"> · итоги {{ date(v.resultsAt) }}</span>
          <a *ngIf="v.protocolUrl" [href]="v.protocolUrl" target="_blank" rel="noopener"> · протокол ↗</a>
        </p>
        <p class="rp-warn" *ngIf="v.protocolStatus && v.protocolStatus !== 'PARSED'">{{ protocolText(v) }}</p>

        <div *ngIf="v.bids.length">
          <div class="rp-sub">Участники{{ v.bidsCount != null ? ': ' + v.bidsCount : '' }}{{ v.rejectedCount ? ' · отклонено ' + v.rejectedCount : '' }}</div>
          <table class="responsive-cards card-grid rp-table">
            <thead><tr><th>Место</th><th>Поставщик</th><th>БИН / ИИН</th><th>Цена за ед.</th><th>Сумма</th><th>Подано</th></tr></thead>
            <tbody>
              <tr *ngFor="let b of v.bids" [class.rp-win]="b.winner" [class.rp-ours]="b.ours" [class.rp-rej]="b.rejected">
                <td data-label="Место">{{ b.rejected ? 'отклонён' : (b.place ?? '—') }}</td>
                <td data-label="Поставщик">{{ b.name || '—' }}<span class="rp-tag" *ngIf="b.winner">победитель</span><span class="rp-tag"
                    *ngIf="b.second">2-е место</span><span class="rp-tag rp-tag-ours" *ngIf="b.ours">мы</span>
                  <div class="rp-reason" *ngIf="b.rejectReason">{{ b.rejectReason }}</div></td>
                <td data-label="БИН / ИИН">{{ b.bin || '—' }}</td>
                <td data-label="Цена за ед.">{{ b.unitPrice | money:2 }}</td>
                <td data-label="Сумма">{{ b.total | money:2 }}</td>
                <td data-label="Подано">{{ date(b.submittedAt) || '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="rp-block" *ngIf="v.contract as c">
          <div class="rp-sub">Договор</div>
          № {{ c.number }}<ng-container *ngIf="c.signedAt"> от {{ date(c.signedAt) }}</ng-container> ·
          {{ c.supplierName || c.supplierBin || 'поставщик' }} · {{ c.unitPrice | money:2 }} за ед.<ng-container *ngIf="c.sum"> ·
          сумма {{ c.sum | money:2 }}</ng-container>
        </div>

        <div class="rp-block" *ngIf="v.attempts.length > 1">
          <div class="rp-sub">Попытки закупки</div>
          <div class="rp-att" *ngFor="let a of v.attempts" [class.rp-own]="a.own">
            <a [href]="url(a.anno)" target="_blank" rel="noopener">{{ a.lotNumber || a.anno }} ↗</a>
            · {{ a.methodName }} · {{ a.status || '—' }}<ng-container *ngIf="a.supplierName || a.supplierBin"> ·
            {{ a.supplierName || ('БИН ' + a.supplierBin) }}</ng-container>
            <span class="rp-tag" *ngIf="a.own">этот лот</span>
            <a *ngIf="a.tenderId && !a.own" class="xref" [routerLink]="['/tenders']" [queryParams]="{ openId: a.tenderId }">в АИС ›</a>
          </div>
        </div>
        <p class="rp-block" *ngIf="v.nextTenderId">Дальше — повторная попытка:
          <a class="xref" [routerLink]="['/tenders']" [queryParams]="{ openId: v.nextTenderId }">№ {{ v.nextTenderNumber || v.nextTenderId }}</a></p>
        <p class="rp-muted" *ngIf="v.checkedAt">Проверено {{ instant(v.checkedAt) }}{{ v.finalAt ? ' · итог окончательный' : '' }}</p>
      </ng-container>
    </div>
  `,
  styles: [`
    .rp { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin: 10px 0; background: var(--surface); }
    .rp-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .rp-link { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 13px; padding: 4px 0; }
    .rp-muted { color: var(--text-muted); font-size: 13px; margin: 6px 0; }
    .rp-err { color: var(--danger-text); font-size: 13px; }
    .rp-warn { color: var(--warn-text); font-size: 13px; margin: 6px 0; }
    .rp-text { font-weight: 500; margin: 8px 0 4px; }
    .rp-success { color: var(--success-text); }
    .rp-danger { color: var(--danger-text); }
    .rp-muted.rp-text { color: var(--text); }
    .rp-sub { font-weight: 600; font-size: 13px; margin: 12px 0 6px; color: var(--text); }
    .rp-table { width: 100%; border-collapse: collapse; font-size: 13px; }
    .rp-table th, .rp-table td { padding: 6px 8px; border-bottom: 1px solid var(--border); text-align: left; vertical-align: top; }
    .rp-win td { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .rp-ours td { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .rp-rej td { color: var(--text-muted); }
    .rp-tag { display: inline-block; margin-left: 6px; font-size: 11px; padding: 1px 7px; border-radius: 10px;
              background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .rp-tag-ours { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .rp-reason { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
    .rp-block { font-size: 13px; color: var(--text); margin: 8px 0; }
    .rp-att { font-size: 13px; padding: 3px 0; }
    .rp-own { font-weight: 500; }
    .rp-att a { color: var(--accent); }
    @media (max-width: 900px) {
      .rp-table tr { grid-template-columns: auto 1fr auto; grid-template-areas: "place name price" "bin bin total"; }
      .rp-table td[data-label="Место"] { grid-area: place; font-weight: 600; }
      .rp-table td[data-label="Поставщик"] { grid-area: name; }
      .rp-table td[data-label="Цена за ед."] { grid-area: price; text-align: right; font-weight: 600; }
      .rp-table td[data-label="БИН / ИИН"] { grid-area: bin; color: var(--text-muted); font-size: 12px; }
      .rp-table td[data-label="Сумма"] { grid-area: total; text-align: right; color: var(--text-muted); font-size: 12px; }
      .rp-table td[data-label="Подано"] { display: none; }
      .rp-link { min-height: 40px; }
    }
  `],
})
export class LotResultPanelComponent implements OnChanges {
  @Input() lotId!: number;
  @Output() close = new EventEmitter<void>();

  view: LotResultView | null = null;
  loading = false;
  error = '';
  private seq = 0;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges() {
    const n = ++this.seq;
    this.loading = true;
    this.error = '';
    this.api.getLotResult(this.lotId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { if (n !== this.seq) return; this.view = v; this.loading = false; this.cdr.markForCheck(); },
      error: e => { if (n !== this.seq) return; this.loading = false; this.error = 'Итог не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  tone(o: string | null): string { return resultTone(o); }
  date(iso: string | null): string { return plainDate(iso); }
  instant(iso: string | null): string { return instantText(iso); }
  url(anno: string): string { return announceUrl(anno); }

  protocolText(v: LotResultView): string {
    switch (v.protocolStatus) {
      case 'UNKNOWN_FORMAT': return 'Протокол не разобран (' + (v.protocolNote || 'вид не знаком') + ') — откройте файл.';
      case 'NO_FILE': return 'Файла протокола на площадке пока нет.';
      default: return 'Протокол не скачан или не разобран' + (v.protocolNote ? ': ' + v.protocolNote : '') + '.';
    }
  }
}
