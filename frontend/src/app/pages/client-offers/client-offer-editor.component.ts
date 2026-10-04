import { ChangeDetectorRef, Component, DestroyRef, HostListener, OnDestroy, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import { OfferTermsEditorComponent } from '../../shared/offer-terms-editor.component';
import { OfferColumnsEditorComponent } from '../../shared/offer-columns-editor.component';
import {
  ClientOffer, OFFER_STATUS_LABELS, OfferItem, OfferStatus, ROUNDING_OPTIONS, TermsStyle,
  dateText, money, numText, parseNum, saveBlob, toRequest, vatLabel,
} from '../../shared/client-offer';
import { ClientOfferItemsComponent } from './client-offer-items.component';
import { ClientOfferPreviewComponent } from './client-offer-preview.component';

/** Пределы общей наценки — те же, что проверяет сервер (ClientOfferUpdateRequest). */
const MIN_MARKUP = -100;
const MAX_MARKUP = 1000;

/**
 * Редактор КП (спека §8.2–§8.4): блоки слева, предпросмотр справа (уже 1200 px — вкладки), панель выгрузки снизу.
 *
 * Автосохранение (§8.3): правка → через 600 мс PUT; одновременно идёт один запрос, меняющий версию (PUT или статус);
 * из ответа берётся только вычисляемое (merge) — набираемое не затирается; правки за время запроса уходят следующим PUT.
 * 409 — плашка и блокировка вкладки. Выгрузка, статус и копия сперва дожидаются сохранения (flushThen).
 * Маршрут на другое КП (копия, «назад») переиспользует компонент — прежнее КП дописывается без экрана (leave).
 */
@Component({
  selector: 'app-client-offer-editor',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink, LucideDynamicIcon, ClientOfferItemsComponent, ClientOfferPreviewComponent,
            OfferTermsEditorComponent, OfferColumnsEditorComponent],
  template: `
    <div class="editor" *ngIf="offer as o; else stateTpl">
      <!-- место под плашку конфликта есть всегда, нулевой высоты: плашка ложится поверх шапки, а не сдвигает форму под курсором
           (иначе на месте первого ряда полей оказывалось «Обновить»); липкая — видна при любой прокрутке -->
      <div class="conflict-slot">
        <div class="error-banner conflict" *ngIf="conflict" role="alert">
          КП изменено в другой вкладке — правки этой вкладки не сохранены. <button type="button" class="btn btn-line" (click)="reload()">Обновить</button>
        </div>
      </div>
      <header class="ed-top">
        <a routerLink="/client-offers" class="back">← КП клиентам</a>
        <div class="ed-title">
          <h2>КП № {{ o.number }} от {{ dateText(o.offerDate) }}</h2>
          <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
          <span class="save-state" [class.err]="!!saveError || conflict" [class.wrap]="!!saveError && !conflict" [attr.title]="saveText()"
                aria-live="polite">{{ saveText() }}</span>
          <button type="button" class="btn btn-line" *ngIf="saveError && !conflict" (click)="save()">Повторить</button>
        </div>
        <span class="ed-menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
          <button type="button" class="btn btn-more" (click)="menuOpen = !menuOpen" [attr.aria-expanded]="menuOpen"
                  [disabled]="conflict" aria-label="Ещё действия">⋯</button>
          <span class="row-menu" *ngIf="menuOpen">
            <button type="button" [disabled]="busy" (click)="duplicate()">Дублировать</button>
            <button type="button" class="danger" *ngIf="o.status === 'DRAFT'" (click)="remove()">Удалить черновик</button>
          </span>
        </span>
      </header>

      <div class="tabs" role="tablist">
        <button type="button" role="tab" [class.on]="tab === 'edit'" [attr.aria-selected]="tab === 'edit'" (click)="tab = 'edit'">Редактор</button>
        <button type="button" role="tab" [class.on]="tab === 'preview'" [attr.aria-selected]="tab === 'preview'" (click)="tab = 'preview'">Просмотр</button>
      </div>

      <div class="ed-body">
        <section class="pane-edit" [class.off]="tab !== 'edit'">
          <fieldset [disabled]="!auth.isAdmin() || conflict">
            <div class="card">
              <h3>Шапка</h3>
              <div class="grid2">
                <label>Исх. № <input type="number" min="1" step="1" [(ngModel)]="o.number" (ngModelChange)="changed()" /></label>
                <label>Дата <input type="date" [(ngModel)]="o.offerDate" (ngModelChange)="changed()" /></label>
                <label class="wide">Клиент
                  <select [ngModel]="o.facilityId" (ngModelChange)="onFacility($event)">
                    <option [ngValue]="null">— без клиента —</option>
                    <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
                  </select></label>
                <label class="wide">Кому <textarea rows="2" maxlength="2000" [(ngModel)]="o.recipient" (ngModelChange)="changed()" placeholder="Главному врачу&#10;ГКП на ПХВ «…»"></textarea></label>
                <label class="wide">Заголовок <input maxlength="200" [(ngModel)]="o.title" (ngModelChange)="changed()" /></label>
                <label class="wide">Предмет <input maxlength="1000" [(ngModel)]="o.subject" (ngModelChange)="changed()" placeholder="Например: Аппарат ИВЛ для экстренной помощи А-ИВЛ-Э-03" /></label>
                <label class="wide">Вводная фраза <textarea rows="2" maxlength="4000" [(ngModel)]="o.intro" (ngModelChange)="changed()"></textarea></label>
              </div>
            </div>
          </fieldset>

          <!-- строки — вне fieldset: у них свой [readonly], а отключённый fieldset заблокировал бы и просмотр
               («⋯ → Подробнее», раскрытие карточки на телефоне) -->
          <div class="card">
            <h3>Позиции</h3>
            <app-client-offer-items [offer]="o" [profile]="profile" [readonly]="!auth.isAdmin() || conflict" (changed)="changed()"></app-client-offer-items>
            <div class="mini-total">
              Итого: <b>{{ money(o.totals.sum) }} {{ currencyShort }}</b>
              <span *ngFor="let v of o.totals.vat"> · в т.ч. НДС {{ vatLabel(v.rate) }}: {{ money(v.amount) }}</span>
            </div>
          </div>

          <fieldset [disabled]="!auth.isAdmin() || conflict">
            <div class="card">
              <h3>Цены</h3>
              <div class="grid3">
                <label>Общая наценка, % <input inputmode="decimal" [value]="numText(o.defaultMarkupPct)" (change)="setMarkup($event)" /></label>
                <label>Округление цены
                  <select [(ngModel)]="o.rounding" (ngModelChange)="changed()"><option *ngFor="let r of roundings" [ngValue]="r.v">{{ r.l }}</option></select></label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.vatEnabled" (ngModelChange)="changed()" /> Цены с НДС</label>
              </div>
            </div>

            <div class="card">
              <h3>Условия</h3>
              <div class="seg" role="radiogroup" aria-label="Как печатать условия">
                <button type="button" *ngFor="let s of termStyles" role="radio" [attr.aria-checked]="o.termsStyle === s.v"
                        [class.on]="o.termsStyle === s.v" (click)="setTermsStyle(s.v)">{{ s.l }}</button>
              </div>
              <app-offer-terms-editor [terms]="o.terms" [offerDate]="o.offerDate" (changed)="changed()"></app-offer-terms-editor>
            </div>

            <div class="card">
              <h3>Оформление</h3>
              <app-offer-columns-editor [columns]="o.columns" [vatEnabled]="o.vatEnabled" (changed)="changed()"></app-offer-columns-editor>
              <div class="toggles">
                <label class="check"><input type="checkbox" [(ngModel)]="o.detailsInName" (ngModelChange)="changed()" /> Модель, производителя и страну — к наименованию, если у них нет своей колонки</label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.showAmountInWords" (ngModelChange)="changed()" /> Сумма прописью</label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.showVatBreakdown" (ngModelChange)="changed()" [disabled]="!o.vatEnabled" /> Разбивка НДС по ставкам</label>
                <div class="orient">
                  <label class="check"><input type="checkbox" [(ngModel)]="o.landscape" (ngModelChange)="changed()" /> Альбомная ориентация</label>
                  <span class="hint warn" *ngIf="crowdedHint() as h" role="status">{{ h }}</span>
                </div>
                <label class="inline">Подпись
                  <select [(ngModel)]="o.signoff" (ngModelChange)="changed()">
                    <option value="DIRECTOR">Директор ____ Фамилия И. О.</option>
                    <option value="COMPANY">Только «С уважением, компания»</option>
                  </select></label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.signoffContacts" (ngModelChange)="changed()" /> Контакты под подписью</label>
                <label class="check stamp">
                  <input type="checkbox" [(ngModel)]="o.withStamp" (ngModelChange)="changed()" [disabled]="!profile?.hasStamp" />
                  <span><b>Подпись и печать</b><span class="hint" *ngIf="profile && !profile.hasStamp"> — загрузите печать в «Реквизитах и печати»</span></span>
                </label>
              </div>
            </div>

            <div class="card">
              <h3>Маржа <span class="hint">только для вас — в документ не попадает</span></h3>
              <dl class="margin">
                <div><dt>Закупка</dt><dd>{{ money(o.totals.purchase) }}</dd></div>
                <div><dt>Себестоимость</dt><dd>{{ money(o.totals.cost) }}</dd></div>
                <div><dt>Выручка без НДС</dt><dd>{{ money(o.totals.revenueNet) }}</dd></div>
                <div><dt>Прибыль</dt><dd [class.neg]="o.totals.profit < 0">{{ money(o.totals.profit) }}</dd></div>
                <div><dt>Средняя наценка</dt><dd>{{ o.totals.markupAvg == null ? '—' : numText(o.totals.markupAvg) + '%' }}</dd></div>
              </dl>
              <p class="hint warn" *ngIf="o.totals.noPurchaseCount">Строк без цены закупки: {{ o.totals.noPurchaseCount }} — их прибыль не посчитана</p>
              <label class="wide">Заметка для себя <textarea rows="2" maxlength="4000" [(ngModel)]="o.internalNote" (ngModelChange)="changed()"></textarea></label>
            </div>
          </fieldset>
        </section>

        <aside class="pane-preview" [class.off]="tab !== 'preview'" [class.zoomed]="zoomed">
          <app-client-offer-preview [offerId]="o.id" [tick]="previewTick" [active]="previewActive()" (crowded)="onCrowded($event)"
                                    (zoomChange)="zoomed = $event"></app-client-offer-preview>
        </aside>
      </div>

      <footer class="ed-bar">
        <span class="warns">
          <span *ngIf="!o.totals.itemCount">позиций нет</span>
          <span *ngIf="o.totals.noPurchaseCount">без закупки: {{ o.totals.noPurchaseCount }}</span>
          <span *ngIf="regWarnings()">без регистрации: {{ regWarnings() }}</span>
        </span>
        <button type="button" class="btn btn-primary" (click)="download('pdf')" [disabled]="busy || conflict"><svg lucideIcon="file-down" [size]="16"></svg> PDF</button>
        <button type="button" class="btn btn-line" (click)="download('docx')" [disabled]="busy || conflict">Word</button>
        <button type="button" class="btn btn-line" *ngIf="canShare && !shareFile" (click)="prepareShare()" [disabled]="busy || conflict">
          <svg lucideIcon="share-2" [size]="16"></svg> {{ sharePreparing ? 'Готовлю PDF…' : 'Поделиться' }}</button>
        <button type="button" class="btn btn-primary" *ngIf="shareFile" (click)="sendShare()">Отправить PDF</button>
        <select *ngIf="auth.isAdmin()" class="status" [ngModel]="statusSel" (ngModelChange)="setStatus($event)"
                [disabled]="conflict || statusBusy" aria-label="Статус КП">
          <option *ngFor="let s of statuses" [ngValue]="s">{{ statusLabel(s) }}</option>
        </select>
      </footer>
    </div>
    <ng-template #stateTpl><p class="empty">{{ loadError || 'Загрузка…' }}</p></ng-template>
  `,
  styles: [`
    .editor { display: flex; flex-direction: column; gap: 12px; }
    .ed-top { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .back { color: var(--accent); text-decoration: none; font-size: 13px; padding-top: 4px; }
    .ed-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; flex: 1; min-width: 0; }
    .ed-title h2 { margin: 0; font-size: 20px; }
    /* чип: 15% тинта + текстовый токен (правило kit); черновик — --text, как в журнале (--text-muted на --surface-2 — 4,39:1) */
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .save-state { font-size: 12px; color: var(--text-muted); }
    .save-state.err { color: var(--danger-text); }
    .ed-menu { position: relative; }
    .row-menu button:disabled { color: var(--text-muted); cursor: default; }
    /* нулевая высота и минус зазор колонки (12 px): место под плашку не меняет раскладку ни до конфликта, ни при нём;
       липкая граница — край содержимого прокрутки (как у нижней панели), поэтому минус отступ main.content */
    .conflict-slot { position: sticky; top: -24px; z-index: 5; height: 0; margin-bottom: -12px; }
    /* поверх шапки: тот же тинт kit, но непрозрачный — сквозь плашку не просвечивает то, что под ней */
    .conflict { position: absolute; top: 0; left: 0; right: 0; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; margin: 0;
                background: color-mix(in srgb, var(--danger) 15%, var(--surface)); box-shadow: var(--shadow); }
    .tabs { display: none; gap: 6px; }
    .tabs button { flex: 1; border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 8px; padding: 8px; font-size: 14px; cursor: pointer; }
    .tabs button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .ed-body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(420px, 44%); gap: 16px; align-items: start; }
    .pane-edit { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .pane-preview { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); max-height: calc(100vh - 150px); overflow: auto; }
    /* липкая панель — свой слой, и крупная страница (fixed внутри) уходила бы под нижнюю панель (5). Поднимать панель всегда
       нельзя: пока она не прилипла, её низ заходит на нижнюю панель и закрывает кнопки — поэтому только пока страница крупно */
    .pane-preview.zoomed { z-index: 6; }
    fieldset { border: none; padding: 0; margin: 0; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; min-width: 0; }
    .card h3 { margin: 0 0 10px; font-size: 15px; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 12px; align-items: end; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.wide { grid-column: 1 / -1; }
    label.check { flex-direction: row; align-items: center; gap: 8px; color: var(--text); font-size: 13px; }
    label.inline { flex-direction: row; align-items: center; gap: 8px; color: var(--text); font-size: 13px; }
    /* список сжимается вместе со строкой: иначе на телефоне длинный вариант подписи вылезал за карточку */
    label.inline select { min-width: 0; }
    label.stamp { padding: 8px 10px; border-radius: 8px; background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid var(--accent); }
    .card input:not([type="checkbox"]), .card select, .card textarea { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    /* недоступное поле видно (оператор, конфликт): явный фон выше сделал бы его неотличимым от живого */
    fieldset:disabled input:not([type="checkbox"]), fieldset:disabled select, fieldset:disabled textarea { background: var(--surface-2); cursor: not-allowed; }
    .card textarea { resize: vertical; }
    .mini-total { margin-top: 10px; font-size: 13px; color: var(--text-muted); text-align: right; }
    .mini-total b { color: var(--text); }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .seg button:disabled { cursor: not-allowed; }
    .toggles { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
    .orient { display: flex; align-items: center; gap: 4px 12px; flex-wrap: wrap; }
    .hint { font-size: 12px; color: var(--text-muted); font-weight: 400; }
    .hint.warn { color: var(--warn-text); }
    /* по ширине карточки, а не окна: рядом предпросмотр, и при окне 1280 px карточке достаётся ~500 px */
    .margin { display: grid; grid-template-columns: repeat(auto-fit, minmax(120px, 1fr)); gap: 8px; margin: 0 0 8px; }
    .margin dt { font-size: 12px; color: var(--text-muted); }
    .margin dd { margin: 2px 0 0; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    .neg { color: var(--danger-text) !important; }
    /* липкая граница — край содержимого прокрутки (main.content, нижний отступ 24 px; 12 px ≤ 900 px): минус отступ —
       панель прилегает к низу экрана, а не висит над полосой, в которой видно прокручиваемое под ней */
    .ed-bar { position: sticky; bottom: -24px; z-index: 5; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end;
              padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface); border-top: 1px solid var(--border); box-shadow: var(--shadow); }
    .ed-bar .btn { display: inline-flex; align-items: center; gap: 6px; }
    .warns { margin-right: auto; display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--warn-text); }
    .status { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    @media (max-width: 1199.98px) {
      .tabs { display: flex; }
      .ed-body { display: block; }
      .off { display: none; }
      .pane-preview { position: static; max-height: none; overflow: visible; }
    }
    @media (max-width: 900px) {
      .grid2, .grid3 { grid-template-columns: minmax(0, 1fr); }
      .ed-title h2 { font-size: 17px; }
      /* одна строка рядом со статусом: «Сохранено · 12:04», «Изменено в другой вкладке» длиннее «Сохранено» и уходили
         на новую строку — шапка росла на 25 px и форма съезжала под пальцем; текст ошибки сохранения — целиком */
      .save-state { flex: 1 1 0; min-width: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
      .save-state.wrap { white-space: normal; }
      .card { padding: 12px; }
      /* панель в две строки, а не в три: кнопки — первой, предупреждения — рядом со статусом (липкая панель на телефоне
         постоянно занимает экран, лишняя строка — ~25 px полей) */
      .ed-bar { bottom: -12px; }
      .conflict-slot { top: -12px; }
      .ed-bar > .btn { order: 1; }
      .warns { order: 2; flex: 1 1 140px; margin-right: 0; }
      .status { order: 3; }
      .back { display: inline-flex; align-items: center; min-height: 40px; padding-top: 0; }
      /* тач-цели 40 px: вся строка-подпись галочки нажимается */
      label.check, label.inline { min-height: 40px; }
      .btn-more { min-width: 40px; }
      /* 16px — iOS не зумит поле при фокусе; локальные 13–14px сильнее глобального правила, поэтому повтор здесь */
      .card input:not([type="checkbox"]), .card select, .card textarea, .status { font-size: 16px; }
    }
  `],
})
export class ClientOfferEditorComponent implements OnInit, OnDestroy {
  offer: ClientOffer | null = null;
  profile: any = null;
  facilities: any[] = [];
  loadError = '';
  tab: 'edit' | 'preview' = 'edit';
  wide = false;
  saving = false;
  dirty = false;
  saveError = '';
  conflict = false;
  savedAt: Date | null = null;
  previewTick = 0;
  /** Последний предпросмотр: таблица тесная — и для какой ориентации он собран (подсказка гаснет, пока нет нового). */
  crowded = false;
  busy = false;
  sharePreparing = false;
  statusBusy = false;
  /** Значение списка статусов: при отказе возвращается к статусу КП (o.status меняет только ответ сервера). */
  statusSel: OfferStatus = 'DRAFT';
  menuOpen = false;
  /** Страница предпросмотра открыта крупно — только тогда липкая панель предпросмотра выше нижней панели. */
  zoomed = false;
  shareFile: File | null = null;
  readonly canShare = canShareFiles();
  readonly statuses: OfferStatus[] = ['DRAFT', 'SENT', 'ACCEPTED', 'REJECTED'];
  readonly roundings = ROUNDING_OPTIONS;
  readonly termStyles: { v: TermsStyle; l: string }[] = [
    { v: 'LIST', l: 'Списком под итогом' }, { v: 'TABLE', l: 'Таблицей над позициями' }, { v: 'NONE', l: 'Не печатать' },
  ];
  dateText = dateText;
  money = money;
  numText = numText;
  vatLabel = vatLabel;

  private id = 0;
  /** Поколение открытого КП: ответы запросов прежнего КП (или после ухода) экран не трогают. */
  private gen = 0;
  private changeSeq = 0;
  private sentSeq = 0;
  private timer: any = null;
  private afterSave: (() => void)[] = [];
  /** КП, с которого ушли, пока шёл его запрос с версией: правки после него дописываются из ответа (нужна версия). */
  private chainAway: ClientOffer | null = null;
  private crowdedLandscape: boolean | null = null;
  private savedLandscape = false;
  private destroyed = false;
  private media: MediaQueryList | null = null;
  private readonly onMedia = (e: MediaQueryListEvent) => { this.wide = e.matches; this.render(); };
  private routeSub: Subscription | null = null;

  constructor(private route: ActivatedRoute, private router: Router, private api: ApiService, public auth: AuthService,
              private profiles: CompanyProfileService, private notify: NotificationService, private confirm: ConfirmService,
              private market: MarketService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get currencyShort(): string {
    return this.market.value === 'RF' ? 'руб.' : 'тг';
  }

  ngOnInit() {
    this.media = window.matchMedia('(min-width: 1200px)');
    this.wide = this.media.matches;
    this.media.addEventListener('change', this.onMedia);
    this.profiles.profile$().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: p => { this.profile = p; this.render(); }, error: () => {} });
    this.api.getFacilities().pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe({ next: f => { this.facilities = f; this.render(); }, error: () => {} });
    this.routeSub = this.route.paramMap.subscribe(p => this.open(Number(p.get('id'))));
  }

  ngOnDestroy() {
    this.destroyed = true;
    this.media?.removeEventListener('change', this.onMedia);
    this.routeSub?.unsubscribe();
    this.leave();
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(e: BeforeUnloadEvent) {
    if (this.dirty || this.saving) { e.preventDefault(); e.returnValue = ''; }
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeMenu() {
    this.menuOpen = false;
  }

  /** Открыть КП; если компонент уже показывал другое (маршрут сменил id), прежнее сперва дописывается. */
  private open(id: number) {
    if (this.offer) this.leave();
    this.load(id);
  }

  load(id: number) {
    const gen = ++this.gen;
    this.id = id;
    this.offer = null;
    this.loadError = '';
    this.conflict = false;
    this.saveError = '';
    this.saving = false;
    this.statusBusy = false;
    this.dirty = false;
    this.busy = false;
    this.sharePreparing = false;
    this.savedAt = null;
    this.shareFile = null;
    this.menuOpen = false;
    this.zoomed = false;   // предпросмотр пересоздаётся вместе с КП — крупной страницы больше нет
    this.crowded = false;
    this.crowdedLandscape = null;
    if (!Number.isInteger(id) || id <= 0) { this.loadError = 'КП не найдено'; return; }
    this.api.getClientOffer(id).subscribe({
      next: o => {
        if (gen !== this.gen) return;
        o.items.forEach((it: OfferItem) => { if (!it.key) it.key = 'i' + it.id; });
        this.offer = o;
        this.statusSel = o.status;
        this.savedLandscape = o.landscape;
        this.previewTick++;
        this.render();
      },
      error: e => {
        if (gen !== this.gen) return;
        this.loadError = e.status === 404 ? 'КП не найдено' : 'Не удалось загрузить КП: ' + errorText(e);
        this.render();
      },
    });
  }

  /** «Обновить» после 409 выбрасывает правки этой вкладки — при несохранённых сперва спросить. */
  reload() {
    if (!this.dirty) { this.open(this.id); return; }
    this.confirm.ask('Правки этой вкладки не сохранены и пропадут. Обновить?', undefined, { danger: true, confirmLabel: 'Обновить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => { if (ok) this.open(this.id); });
  }

  /**
   * Уход с КП — со страницы или на другое КП тем же компонентом: последние правки дописываются без экрана.
   * Идёт запрос с версией (PUT или статус) — правки после него отправит его ответ (finishAway), иначе — сразу.
   */
  private leave() {
    clearTimeout(this.timer);
    this.gen++;
    this.afterSave = [];
    const o = this.offer;
    if (!o || !this.dirty || this.conflict || !this.auth.isAdmin()) return;
    if (this.saving || this.statusBusy) {
      if (this.changeSeq > this.sentSeq) this.chainAway = o;
      return;
    }
    this.api.saveClientOffer(o.id, toRequest(o)).subscribe({ error: () => {} });
  }

  /** Ответ запроса уже закрытого КП: правки, сделанные после его отправки, уходят с версией из ответа. */
  private finishAway(o: ClientOffer, version: number) {
    if (this.chainAway !== o) return;
    this.chainAway = null;
    this.api.saveClientOffer(o.id, { ...toRequest(o), version }).subscribe({ error: () => {} });
  }

  previewActive(): boolean {
    return this.wide || this.tab === 'preview';
  }

  changed() {
    if (!this.auth.isAdmin() || this.conflict) return;
    this.dirty = true;
    this.changeSeq++;
    this.shareFile = null;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.save(), 600);
  }

  save() {
    const o = this.offer;
    if (!o || this.conflict || !this.auth.isAdmin() || this.saving || this.statusBusy) return;
    clearTimeout(this.timer);
    this.saving = true;
    this.saveError = '';
    this.sentSeq = this.changeSeq;
    const gen = this.gen;
    const body = toRequest(o);
    this.api.saveClientOffer(o.id, body).subscribe({
      next: r => {
        if (gen !== this.gen) { this.finishAway(o, r.version); return; }
        this.merge(o, r);
        this.saving = false;
        this.savedAt = new Date();
        this.savedLandscape = body.landscape;
        if (this.changeSeq > this.sentSeq) {
          this.save();
        } else {
          this.dirty = false;
          this.previewTick++;
          this.runAfterSave();
        }
        this.render();
      },
      error: e => {
        if (gen !== this.gen) return;
        this.saving = false;
        const waiting = this.afterSave.length > 0;
        this.afterSave = [];
        this.statusSel = o.status;   // смена статуса ждала этого сохранения — список обратно к настоящему
        if (e.status === 409) {
          this.conflict = true;
        } else {
          this.saveError = errorText(e);
          if (waiting) this.notify.error('Правки не сохранены: ' + this.saveError);
        }
        this.render();
      },
    });
  }

  /** Из ответа — только вычисляемое; набираемые поля не трогаем (спека §8.3). */
  private merge(o: ClientOffer, r: ClientOffer) {
    o.version = r.version;
    o.totals = r.totals;
    o.fileBaseName = r.fileBaseName;
    o.updatedAt = r.updatedAt;
    o.facilityName = r.facilityName;
    const byKey = new Map(r.items.map(it => [it.key, it]));
    for (const it of o.items) {
      const s = byKey.get(it.key);
      if (s) { it.id = s.id; it.lineNo = s.lineNo; it.calc = s.calc; }
    }
  }

  /** Выгрузка, статус, копия — только после сохранения последних правок. */
  private flushThen(fn: () => void) {
    if (this.conflict) return;
    if (!this.dirty && !this.saving && !this.statusBusy) { fn(); return; }
    this.afterSave.push(fn);
    if (!this.saving && !this.statusBusy) this.save();
  }

  private runAfterSave() {
    const list = this.afterSave;
    this.afterSave = [];
    list.forEach(f => f());
  }

  saveText(): string {
    if (!this.auth.isAdmin()) return 'Только просмотр';
    if (this.conflict) return 'Изменено в другой вкладке';
    if (this.saveError) return 'Не сохранено: ' + this.saveError;
    if (this.saving || this.dirty) return 'Сохраняю…';
    return this.savedAt ? 'Сохранено · ' + this.savedAt.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : 'Сохранено';
  }

  onCrowded(crowded: boolean) {
    this.crowded = crowded;
    this.crowdedLandscape = this.savedLandscape;
    this.render();
  }

  /** Подсказка у «Альбомной» — по последнему предпросмотру и только для той ориентации, в которой он собран. */
  crowdedHint(): string {
    const o = this.offer;
    if (!o || !this.crowded || this.crowdedLandscape !== o.landscape) return '';
    return o.landscape ? 'Таблица тесная — шрифт уменьшен; уберите лишние колонки'
                       : 'Таблица тесная — шрифт уменьшен, включите «Альбомная»';
  }

  setMarkup(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null || v < MIN_MARKUP || v > MAX_MARKUP) {
      input.value = numText(this.offer!.defaultMarkupPct);
      this.notify.error('Общая наценка — число от −100 до 1000');
      return;
    }
    this.offer!.defaultMarkupPct = v;
    input.value = numText(v);
    this.changed();
  }

  setTermsStyle(style: TermsStyle) {
    this.offer!.termsStyle = style;
    this.changed();
  }

  /** «Кому» подставляется названием клиента, если пусто или было названием прежнего клиента. */
  onFacility(id: number | null) {
    const o = this.offer!;
    const prev = this.facilities.find(f => f.id === o.facilityId)?.name;
    o.facilityId = id;
    const f = this.facilities.find(x => x.id === id);
    if (f && (!o.recipient || o.recipient === prev)) o.recipient = f.name;
    this.changed();
  }

  download(kind: 'pdf' | 'docx') {
    this.flushThen(() => {
      const o = this.offer!;
      const gen = this.gen;
      this.busy = true;
      this.render();
      const req = kind === 'pdf' ? this.api.downloadClientOfferPdf(o.id) : this.api.downloadClientOfferDocx(o.id);
      req.subscribe({
        next: blob => {
          saveBlob(blob, o.fileBaseName + '.' + kind);
          if (gen === this.gen) { this.busy = false; this.render(); }
        },
        error: e => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.fileError(e, 'Файл не собран');
          this.render();
        },
      });
    });
  }

  /** «Поделиться» в два нажатия: браузер требует, чтобы share шёл прямо из нажатия, а скачивание PDF его «съедает». */
  prepareShare() {
    this.flushThen(() => {
      const o = this.offer!;
      const gen = this.gen;
      const seq = this.changeSeq;   // поля во время сборки остаются живыми
      this.busy = true;
      this.sharePreparing = true;
      this.render();
      this.api.downloadClientOfferPdf(o.id).subscribe({
        next: blob => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.sharePreparing = false;
          // правка во время сборки: PDF старше экрана — не отдаём его клиенту, кнопка остаётся «Поделиться»
          if (this.changeSeq !== seq || this.dirty) {
            this.notify.info('КП изменилось, пока собирался PDF — нажмите «Поделиться» ещё раз');
          } else {
            this.shareFile = new File([blob], o.fileBaseName + '.pdf', { type: 'application/pdf' });
          }
          this.render();
        },
        error: e => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.sharePreparing = false;
          this.fileError(e, 'PDF не собран');
          this.render();
        },
      });
    });
  }

  sendShare() {
    const file = this.shareFile;
    if (!file) return;
    (navigator as any).share({ files: [file], title: file.name }).then(() => {}, () => {});
  }

  setStatus(status: OfferStatus) {
    const o = this.offer!;
    this.statusSel = status;
    if (status === o.status) return;
    this.flushThen(() => {
      const gen = this.gen;
      this.statusBusy = true;
      this.render();
      this.api.setClientOfferStatus(o.id, status).subscribe({
        next: r => {
          if (gen !== this.gen) { this.finishAway(o, r.version); return; }
          this.statusBusy = false;
          o.status = r.status;
          o.sentAt = r.sentAt;
          o.version = r.version;
          o.updatedAt = r.updatedAt;
          this.statusSel = r.status;
          this.notify.success('Статус: ' + this.statusLabel(r.status));
          this.afterStatus();
        },
        error: e => {
          if (gen !== this.gen) return;
          this.statusBusy = false;
          this.statusSel = o.status;
          this.notify.error('Статус не изменён: ' + errorText(e));
          this.afterStatus();
        },
      });
    });
  }

  /** Правки, сделанные пока шёл запрос статуса, сохраняются сейчас (он менял версию); ждавшие действия — после них. */
  private afterStatus() {
    if (this.dirty) this.save();
    else this.runAfterSave();
    this.render();
  }

  duplicate() {
    this.menuOpen = false;
    this.flushThen(() => {
      const o = this.offer!;
      const gen = this.gen;
      this.busy = true;
      this.render();
      this.api.duplicateClientOffer(o.id).subscribe({
        next: r => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.notify.success(`Создана копия — КП № ${r.number}`);
          // тот же компонент: правки, сделанные за время запроса, допишет leave() при смене id
          this.router.navigate(['/client-offers', r.id]);
        },
        error: e => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.notify.error('Копия не создана: ' + errorText(e));
          this.render();
        },
      });
    });
  }

  remove() {
    this.menuOpen = false;
    const o = this.offer!;
    this.confirm.ask(`Удалить черновик КП № ${o.number}?`, 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok || o !== this.offer) return;
        clearTimeout(this.timer);
        this.api.deleteClientOffer(o.id).subscribe({
          next: () => {
            if (o !== this.offer) return;
            this.dirty = false;   // КП больше нет — дописывать при уходе нечего
            this.notify.success('Черновик удалён');
            this.router.navigate(['/client-offers']);
          },
          error: e => {
            if (o !== this.offer) return;
            // сервер сам объясняет отказ («Удалить можно только черновик…» — статус сменили в другой вкладке)
            this.notify.error(e?.error?.message || 'Не удалось удалить: ' + errorText(e));
            if (this.dirty) this.save();
          },
        });
      });
  }

  statusLabel(s: OfferStatus): string {
    return OFFER_STATUS_LABELS[s] || s;
  }

  regWarnings(): number {
    const o = this.offer;
    if (!o || !o.columns.some(c => c.key === 'REGISTRATION')) return 0;
    return o.totals.unconfirmedRegistrationCount;
  }

  /** Тост с причиной (спека §12): ошибка файла приходит blob'ом — текст ApiError читается из него (.then, не await). */
  private fileError(e: any, prefix: string) {
    const show = (msg?: string) => this.notify.error(prefix + (msg ? ': ' + msg : ' — попробуйте ещё раз'));
    const body = e?.error;
    if (body instanceof Blob) {
      body.text().then(t => { let msg: string | undefined; try { msg = JSON.parse(t)?.message; } catch { msg = undefined; } show(msg); }, () => show());
    } else {
      show(body?.message);
    }
  }

  private render() {
    if (!this.destroyed) this.cdr.detectChanges();
  }
}

function canShareFiles(): boolean {
  try {
    const nav = navigator as any;
    return !!nav.share && !!nav.canShare && nav.canShare({ files: [new File(['x'], 'x.pdf', { type: 'application/pdf' })] });
  } catch {
    return false;
  }
}

/**
 * Текст ошибки сервера: первое сообщение проверки полей, иначе ApiError.message. Нет ответа или 5xx без текста —
 * это прокси перед лежащим бэкендом: «нет связи с сервером» (как в журнале).
 */
function errorText(e: any): string {
  const errors = e?.error?.errors;
  if (errors && typeof errors === 'object') {
    const first = Object.values(errors)[0];
    if (first) return String(first);
  }
  if (e?.error?.message) return e.error.message;
  return !e?.status || e.status >= 500 ? 'нет связи с сервером' : `ошибка ${e.status}`;
}
