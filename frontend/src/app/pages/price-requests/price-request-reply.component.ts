import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ActivatedRoute, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { numText, parseNum } from '../../shared/client-offer';
import { dateText } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { backLink, currencyLabel } from '../../shared/supplier-reply';
import type { ReplyPosition, ReplyTableRow, SupplierReply } from '../../shared/supplier-reply';
import { ReplyDocumentComponent } from './reply-document.component';

const RATE = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6, useGrouping: false });

/**
 * «Ответ поставщика» (спека 2026-10-15 §4.1): позиции запроса с ценой и строкой файла рядом — сверить глазами, не
 * открывая документ; справа документ. Ручная правка цены и выбор строки = сверено; «Всё верно» снимает 💡 со всех.
 */
@Component({
  selector: 'app-price-request-reply',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink, MarketMoneyPipe, ReplyDocumentComponent],
  template: `
    <div class="page" *ngIf="view as v; else loadingTpl">
      <header class="head">
        <a class="back" [routerLink]="back().commands" [queryParams]="back().query">← {{ back().label }}</a>
        <h2>Ответ поставщика · {{ v.head.supplierName }}</h2>
        <div class="sub">Запрос цен № {{ v.head.id }} · {{ status(v.head.status) }}<ng-container *ngIf="v.head.sentAt"> · отправлен {{ date(v.head.sentAt) }}</ng-container>
          <ng-container *ngIf="v.head.responseDate"> · ответ {{ date(v.head.responseDate) }}</ng-container>
          <span class="save" *ngIf="saveText">· {{ saveText }}</span></div>
      </header>

      <div class="tabs" role="tablist">
        <button type="button" role="tab" [class.on]="tab === 'prices'" [attr.aria-selected]="tab === 'prices'" (click)="tab = 'prices'">Цены</button>
        <button type="button" role="tab" [class.on]="tab === 'doc'" [attr.aria-selected]="tab === 'doc'" (click)="tab = 'doc'">Документ</button>
      </div>

      <div class="body">
        <section class="pane-prices" [class.off]="tab !== 'prices'">
          <div class="rate" *ngIf="v.rate as rt">
            <span class="rate-t">Цены в файле — в {{ label(rt.currency) }}<ng-container *ngIf="rt.value == null">: впишите курс — цены пересчитаются и встанут с 💡</ng-container></span>
            <label class="rate-in">1 {{ label(rt.currency) }} =
              <input #rateEl inputmode="decimal" [value]="rateText(rt.value ?? rt.suggestion)" [disabled]="!admin" placeholder="курс"
                     aria-label="Курс" (keydown.enter)="setRate(rateEl.value)" /> {{ label(v.head.currency) }}</label>
            <button type="button" class="btn btn-primary" *ngIf="admin" [disabled]="busy" (click)="setRate(rateEl.value)">{{ rt.value == null ? 'Пересчитать' : 'Пересчитать заново' }}</button>
            <button type="button" class="btn btn-line" *ngIf="admin && rt.value != null" [disabled]="busy" (click)="setRate('')">Снять курс</button>
            <span class="rate-h" *ngIf="rt.value == null && rt.suggestion != null">в прошлый раз — {{ rateText(rt.suggestion) }}</span>
          </div>
          <article class="pos" *ngFor="let p of v.positions; trackBy: byItem" [class.auto]="p.auto" [class.no-price]="p.price == null">
            <div class="p-head">
              <span class="p-name">{{ p.ordinal }}. {{ p.name }}<ng-container *ngIf="p.brand"> ({{ p.brand }})</ng-container><ng-container *ngIf="p.model"> · {{ p.model }}</ng-container></span>
              <span class="p-qty">{{ p.quantity ?? '—' }} {{ p.unit || 'шт' }}</span>
            </div>
            <div class="p-row" *ngIf="p.row as r">← стр. {{ r.fileRow }}: «{{ r.text }}»<ng-container *ngIf="r.quantity != null"> · {{ r.quantity }} {{ r.unit || '' }}</ng-container><ng-container *ngIf="r.price != null"> · <ng-container *ngIf="!foreign(r.currency); else rowForeign">{{ r.price | money:2 }}</ng-container><ng-template #rowForeign><ng-container *ngIf="r.converted != null">{{ r.converted | money:2 }} = </ng-container>{{ foreignPrice(r.price, r.currency) }}<ng-container *ngIf="r.converted != null"> × {{ rateText(v.rate?.value) }}</ng-container></ng-template></ng-container></div>
            <div class="p-row muted" *ngIf="!p.row && !p.suggestion && p.price == null && v.table">нет в файле</div>
            <div class="p-sugg" *ngIf="p.suggestion as s">
              <span>строка {{ s.fileRow }}? «{{ s.text }}» ·
                <ng-container *ngIf="s.price != null; else noPrice"><ng-container *ngIf="!foreign(s.currency); else suggForeign">{{ s.price | money:2 }}</ng-container><ng-template #suggForeign><ng-container *ngIf="s.converted != null; else noRate">{{ s.converted | money:2 }} = {{ foreignPrice(s.price, s.currency) }} × {{ rateText(v.rate?.value) }}</ng-container><ng-template #noRate>{{ foreignPrice(s.price, s.currency) }} — не {{ marketIn() }}: впишите курс выше или цену вручную</ng-template></ng-template></ng-container>
                <ng-template #noPrice>в файле без цены<ng-container *ngIf="s.note"> — {{ s.note }}</ng-container></ng-template></span>
              <button type="button" class="btn btn-line" *ngIf="admin && s.price != null && (!foreign(s.currency) || s.converted != null)" (click)="applySuggestion(p)">Подставить</button>
            </div>
            <div class="p-flags" *ngIf="p.flags.length"><span class="flag" *ngFor="let f of p.flags">⚠ {{ f }}</span></div>
            <div class="p-ctl">
              <label>Цена за ед.
                <input inputmode="decimal" [value]="num(p.price)" (change)="savePrice(p, $event)" [disabled]="!admin" placeholder="—" />
              </label>
              <span class="mark" *ngIf="p.auto" title="Цена распознана из ответа и ещё не сверена">💡</span>
              <!-- поля и действия позиции не блокируются на время сохранения: каждое — запись одной позиции, а заблокированный
                   элемент на пути Tab ронял фокус (ввод цен по PDF подряд — живая проверка после ревью) -->
              <select *ngIf="admin && v.table" class="pick" (change)="pickRow(p, $event)" aria-label="Другая строка файла">
                <option value="">Другая строка ▾</option>
                <option *ngFor="let r of priced()" [value]="r.fileRow" [disabled]="foreign(r.currency) && r.converted == null">стр. {{ r.fileRow }} · {{ cut(r.text) }}<ng-container *ngIf="r.price != null"> · {{ num(r.converted ?? r.price) }}<ng-container *ngIf="foreign(r.currency) && r.converted == null"> {{ label(r.currency) }}</ng-container></ng-container></option>
                <option value="none">— нет в файле —</option>
              </select>
            </div>
            <div class="p-note" *ngIf="p.note">{{ p.note }}</div>
          </article>
          <p class="hint" *ngIf="!v.positions.length">В запросе нет позиций.</p>
        </section>

        <section class="pane-doc" [class.off]="tab !== 'doc'">
          <app-reply-document [view]="v" [requestId]="id" [admin]="admin" (changed)="view = $event" (selectFile)="load($event)"></app-reply-document>
        </section>
      </div>

      <div class="bar">
        <span class="counts">не сверено: {{ v.counts.unverified }} · без цены: {{ v.counts.noPrice }}<ng-container *ngIf="v.table"> · строк файла без позиции: {{ v.counts.unassignedRows }}</ng-container></span>
        <button type="button" class="btn btn-primary" *ngIf="admin" [disabled]="busy || !v.counts.unverified" (click)="verify()">Всё верно</button>
      </div>
    </div>
    <ng-template #loadingTpl><p class="hint">{{ error || 'Загружаю ответ поставщика…' }}</p></ng-template>
  `,
  styles: [`
    .page { display: flex; flex-direction: column; gap: 12px; }
    .head h2 { margin: 4px 0 2px; font-size: 18px; }
    .back { font-size: 13px; color: var(--accent); text-decoration: none; }
    .sub { font-size: 13px; color: var(--text-muted); }
    .save { margin-left: 4px; }
    .tabs { display: none; gap: 6px; }
    .tabs button { flex: 1; border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 8px; padding: 8px; font-size: 14px; cursor: pointer; }
    .tabs button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(420px, 46%); gap: 16px; align-items: start; }
    .pane-prices { display: flex; flex-direction: column; gap: 8px; min-width: 0; }
    .pane-doc { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); max-height: calc(100vh - 150px); overflow: auto; min-width: 0; }
    .rate { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 12px; padding: 10px 12px; font-size: 13px; border: 1px solid var(--accent);
            border-radius: 10px; background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .rate-t { flex: 1 1 100%; font-weight: 600; }
    .rate-in { display: flex; align-items: center; gap: 6px; }
    .rate-in input { width: 110px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                     font-size: 14px; font-variant-numeric: tabular-nums; }
    .rate-in input:disabled { background: var(--surface-2); }
    .rate-h { color: var(--text-muted); }
    .pos { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
    .pos.auto { border-color: var(--warn); background: color-mix(in srgb, var(--warn) 8%, var(--surface)); }
    .p-head { display: flex; justify-content: space-between; gap: 8px; font-size: 14px; }
    .p-name { font-weight: 600; overflow-wrap: anywhere; }
    .p-qty { color: var(--text-muted); white-space: nowrap; }
    .p-row { font-size: 13px; color: var(--text); overflow-wrap: anywhere; }
    .p-row.muted { color: var(--text-muted); }
    .p-sugg { display: flex; flex-wrap: wrap; gap: 6px 10px; align-items: center; font-size: 13px; color: var(--accent); overflow-wrap: anywhere; }
    .p-flags { display: flex; flex-wrap: wrap; gap: 4px 10px; font-size: 12px; color: var(--warn-text); }
    .p-ctl { display: flex; flex-wrap: wrap; gap: 8px; align-items: flex-end; }
    .p-ctl label { display: flex; flex-direction: column; gap: 2px; font-size: 12px; color: var(--text-muted); }
    .p-ctl input { width: 150px; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 14px; font-variant-numeric: tabular-nums; }
    .p-ctl input:disabled { background: var(--surface-2); }
    .mark { font-size: 16px; padding-bottom: 6px; }
    .pick { max-width: 260px; padding: 6px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .p-note { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .hint { font-size: 13px; color: var(--text-muted); }
    .bar { position: sticky; bottom: -24px; z-index: 5; display: flex; align-items: center; gap: 10px; flex-wrap: wrap; justify-content: flex-end;
           padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface); border-top: 1px solid var(--border); box-shadow: var(--shadow); }
    .counts { margin-right: auto; font-size: 13px; color: var(--text-muted); }
    @media (max-width: 1199.98px) {
      .tabs { display: flex; }
      .body { display: block; }
      .off { display: none; }
      .pane-doc { position: static; max-height: none; overflow: visible; }
    }
    @media (max-width: 900px) {
      .bar { bottom: -12px; }
      .p-ctl input, .pick { font-size: 16px; min-height: 40px; }
      .p-ctl input { width: 100%; }
      .p-ctl label { flex: 1 1 140px; }
      /* ширина select по умолчанию — по самому длинному варианту («стр. 5 · Столик процедурный…»): без width и
         min-width: 0 он вылезал за край карточки на телефоне (живая проверка) */
      .pick { max-width: none; flex: 1 1 100%; width: 100%; min-width: 0; }
      .bar .btn, .p-sugg .btn, .rate .btn { min-height: 40px; }
      .rate-in input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class PriceRequestReplyComponent implements OnInit {
  id = 0;
  view: SupplierReply | null = null;
  error = '';
  busy = false;
  tab: 'prices' | 'doc' = 'prices';
  saveText = '';
  private sub?: Subscription;

  constructor(private route: ActivatedRoute, private api: ApiService, private auth: AuthService, private notify: NotificationService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.route.paramMap.pipe(takeUntilDestroyed(this.destroyRef)).subscribe(p => {
      this.id = Number(p.get('id'));
      this.view = null;
      this.load(null);
    });
  }

  load(fileId: number | null) {
    this.sub?.unsubscribe();
    this.sub = this.api.getSupplierReply(this.id, fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.view = v; this.error = ''; this.cdr.markForCheck(); },
      error: e => { this.error = 'Ответ поставщика не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  back() { return backLink(this.view!.head); }
  byItem(_: number, p: ReplyPosition) { return p.itemId; }
  num(v: number | null) { return numText(v); }
  date(iso: string) { return dateText(iso); }
  status(s: string) {
    // SENT — «ждём ответа», как в таблице цен: дальше в строке и так «отправлен ДД.ММ.ГГГГ»
    const names: Record<string, string> = { CREATED: 'создан', SENT: 'ждём ответа', RESPONDED: 'ответил', DECLINED: 'отказ',
      ACCEPTED: 'принят', REJECTED: 'отклонён', CLOSED: 'закрыт' };
    return names[s] || s;
  }
  cut(s: string) { return s.length > 60 ? s.slice(0, 59) + '…' : s; }
  /** Валюта строки файла не рынка запроса: цена не подставляется и не выдаётся за цену рынка (ревью ветки). */
  foreign(code: string | null): boolean { return !!code && !!this.view?.head.currency && code !== this.view.head.currency; }
  label(code: string | null) { return currencyLabel(code); }
  foreignPrice(v: number | null, code: string | null) { return numText(v) + ' ' + currencyLabel(code); }
  /** «в тенге» / «в рублях» — для пометки у цены в чужой валюте. */
  marketIn() { return this.view?.head.currency === 'RUB' ? 'в рублях' : 'в тенге'; }
  priced(): ReplyTableRow[] { return (this.view?.table?.rows || []).filter(r => r.priced); }
  /** Курс без группировки разрядов и с 6 знаками: «6», «5,85555». */
  rateText(v: number | null | undefined) { return v == null ? '' : RATE.format(v); }

  /** Курс для файла в чужой валюте: цены файла × курс встают с 💡; пусто — снять курс. */
  setRate(text: string) {
    const fileId = this.view?.fileId;
    if (!fileId) return;
    const t = text.trim();
    const v = t === '' ? null : parseNum(t);
    if (t !== '' && (v == null || v <= 0)) { this.notify.error('Курс — число больше нуля, например 6 или 5,85'); return; }
    this.busy = true;
    this.saveText = 'Сохраняю…';
    this.api.setSupplierReplyRate(this.id, fileId, v).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        this.busy = false;
        this.view = r;
        this.saved();
        this.notify.success(v == null ? 'Курс снят — пересчитанные цены убраны' : `Цены пересчитаны по курсу ${this.rateText(v)} — сверьте`);
        this.cdr.markForCheck();
      },
      error: e => { this.busy = false; this.saveText = 'Не сохранено: ' + errorText(e); this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  savePrice(p: ReplyPosition, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const text = input.value.trim();
    const v = text === '' ? null : parseNum(text);
    if (text !== '' && v == null) { this.notify.error('Цена — число, например 45 000 или 45000,50'); input.value = numText(p.price); return; }
    this.put([{ itemId: p.itemId, responsePrice: v, responseNote: p.note }]);
  }

  pickRow(p: ReplyPosition, ev: Event) {
    const sel = ev.target as HTMLSelectElement;
    const val = sel.value;
    sel.value = '';
    if (!val || !this.view) return;
    if (val === 'none') { this.put([{ itemId: p.itemId, responsePrice: null, responseNote: null, clearFile: true }]); return; }
    const row = this.view.table?.rows.find(r => r.fileRow === Number(val));
    if (row) this.putPicked(p, row);
  }

  applySuggestion(p: ReplyPosition) {
    if (p.suggestion) this.putPicked(p, p.suggestion);
  }

  /** Цена выбранной строки в валюте запроса: своя — как есть, в валюте курса — пересчитанная; без курса чужая не пишется. */
  private putPicked(p: ReplyPosition, row: { fileRow: number; price: number | null; currency: string | null; converted: number | null }) {
    const price = row.converted ?? (this.foreign(row.currency) ? null : row.price);
    if (price == null) return;
    const name = this.view?.sources.find(s => s.fileId === this.view?.fileId)?.name || 'файла';
    const rate = row.converted != null && this.view?.rate?.value != null
      ? ` Цена ${numText(row.price)} ${currencyLabel(row.currency)} × курс ${this.rateText(this.view.rate.value)}.` : '';
    this.put([{ itemId: p.itemId, responsePrice: price, responseNote: `Из файла «${name}», строка ${row.fileRow} — выбрано вручную.${rate}`,
                fileId: this.view?.fileId, fileRow: row.fileRow }]);
  }

  private put(updates: any[]) {
    this.busy = true;
    this.saveText = 'Сохраняю…';
    this.api.updatePriceRequestResponses(this.id, updates).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => {
        this.busy = false;
        this.saved();
        this.load(this.view?.fileId ?? null);
      },
      error: e => { this.busy = false; this.saveText = 'Не сохранено: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  private saved() {
    const d = new Date();
    this.saveText = `Сохранено · ${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`;
  }

  verify() {
    this.busy = true;
    this.api.verifySupplierReply(this.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.view = v; this.notify.success('Цены сверены'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
