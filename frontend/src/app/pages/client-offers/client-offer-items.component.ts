import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import {
  ClientOffer, ItemKind, OfferItem, RegStatus, UNIT_SUGGESTIONS, money, newItem, numText, parseNum, uid, vatLabel,
} from '../../shared/client-offer';

/** Пределы наценки — те же, что проверяет сервер (ClientOfferService): вне них отказало бы всё автосохранение, а не одно поле. */
const MIN_MARKUP = -100;
const MAX_MARKUP = 1000;

/**
 * Строки КП (спека §8.2, блок «Позиции»): позиция / раздел / «включено в стоимость». Главное — в строку, остальное —
 * «Подробнее». Числовые поля сохраняются по change (не на каждый символ — иначе «12,» превращалось бы в 12); не число —
 * поле возвращается к прежнему значению с подсказкой, а не стирает цену.
 * Цена клиенту: ввод = ручная цена (наценка выводится сервером обратно), «↺» — вернуть расчёт по наценке.
 * Числа строк (calc) только показываются — считает сервер. Строки правятся на месте: автосохранение сводит ответ по key.
 *
 * Раскладка — по ширине самого блока (@container kp-items), а не окна: в редакторе рядом предпросмотр, и при окне 1280 px
 * строкам достаётся ~500 px. ≥ 960 px — одна линия под шапкой колонок; уже — наименование, под ним числа с подписями.
 * Телефон (≤ 900 px окна) — карточка: номер и «3 шт × 105 600,00 = 316 800,00», тап — поля и «Подробнее».
 * Подсветка строки (спека §5.4): убыток (прибыль строки ≤ 0) и строка без цены закупки — и в свёрнутой строке, и в карточке.
 */
@Component({
  selector: 'app-client-offer-items',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="toolbar" *ngIf="!readonly">
      <button type="button" class="btn btn-line" (click)="add('ITEM')">+ Позиция</button>
      <button type="button" class="btn btn-line" (click)="add('SECTION')">+ Раздел</button>
      <button type="button" class="btn btn-line" (click)="add('INCLUDED')">+ Включено в стоимость</button>
      <label class="sel-all" *ngIf="offer.items.length">
        <input type="checkbox" [checked]="allSelected()" [indeterminate]="someSelected()" (change)="selectAll($any($event.target).checked)" />
        Выбрать все
      </label>
    </div>

    <div class="bulk" *ngIf="!readonly && selectedCount() > 0" role="group" aria-label="Действия с выбранными строками">
      <span class="bulk-count">Выбрано: {{ selectedCount() }}</span>
      <span class="bulk-field">
        <input class="bulk-input" inputmode="decimal" placeholder="Наценка, %" [(ngModel)]="bulkMarkup" (keydown.enter)="applyBulkMarkup()"
               aria-label="Наценка для выбранных, %" />
        <button type="button" class="btn btn-line" (click)="applyBulkMarkup()" title="Ручные цены выбранных строк сбросятся">Наценку</button>
      </span>
      <span class="bulk-field">
        <select [(ngModel)]="bulkVat" aria-label="НДС для выбранных" [disabled]="!offer.vatEnabled">
          <option [ngValue]="undefined" disabled>Ставка НДС</option>
          <option *ngFor="let r of rates" [ngValue]="r">{{ vatLabel(r) }}</option>
        </select>
        <button type="button" class="btn btn-line" [disabled]="!offer.vatEnabled || bulkVat === undefined" (click)="applyBulkVat()">НДС</button>
      </span>
      <button type="button" class="btn btn-danger" (click)="bulkDelete()">Удалить</button>
      <button type="button" class="btn btn-cancel" (click)="selectAll(false)">Снять выбор</button>
    </div>

    <div class="head line" *ngIf="offer.items.length">
      <span class="c-grip"></span>
      <span class="c-sel"><input type="checkbox" [checked]="allSelected()" [indeterminate]="someSelected()" (change)="selectAll($any($event.target).checked)"
                                 [disabled]="readonly" aria-label="Выбрать все строки" /></span>
      <span class="c-num">№</span><span class="c-name">Наименование</span><span class="c-qty r">Кол-во</span>
      <span class="c-buy r" [title]="BUY_HINT">Закупка</span><span class="c-mk r">Наценка, %</span><span class="c-price r">Цена клиенту</span>
      <span class="c-vat">НДС</span><span class="c-sum r">Сумма</span><span class="c-menu"></span>
    </div>

    <div class="rows" cdkDropList [cdkDropListDisabled]="readonly" (cdkDropListDropped)="drop($event)">
      <!-- превью перетаскивания — в этом же списке, а не в body: иначе до него не дошла бы раскладка по ширине блока -->
      <div class="row" *ngFor="let it of offer.items; let i = index; trackBy: trackKey"
           cdkDrag [cdkDragDisabled]="readonly" cdkDragLockAxis="y" cdkDragPreviewContainer="parent"
           [attr.data-key]="it.key" [attr.data-kind]="it.kind" [class.sel]="it._sel" [class.open]="it._open"
           [class.flag-loss]="flag(it) === 'loss'" [class.flag-nobuy]="flag(it) === 'nobuy'" [attr.title]="flagText(it)">
        <div class="line">
          <!-- с клавиатуры порядок меняют «⋯ → Выше / Ниже»: ручка без клавиатурного перетаскивания — лишняя остановка Tab -->
          <button type="button" class="c-grip grip" cdkDragHandle [disabled]="readonly" tabindex="-1"
                  [attr.aria-label]="'Перетащить строку ' + (i + 1)" title="Перетащить">
            <svg lucideIcon="grip-vertical" [size]="16"></svg>
          </button>
          <label class="c-sel"><input type="checkbox" [(ngModel)]="it._sel" [disabled]="readonly" [attr.aria-label]="'Выбрать строку ' + (i + 1)" /></label>

          <ng-container *ngIf="it.kind === 'ITEM'">
            <span class="c-num">{{ number(i) }}</span>
            <div class="c-name">
              <textarea rows="1" class="name" [(ngModel)]="it.name" (ngModelChange)="emit()" [disabled]="readonly" maxlength="4000"
                        placeholder="Наименование" [attr.aria-label]="'Наименование, строка ' + (i + 1)"></textarea>
              <span class="chip-warn" *ngIf="needsReg(it)">РУ не указано</span>
              <span class="sr" *ngIf="flag(it)">{{ flagText(it) }}</span>
            </div>
            <button type="button" class="c-summary" (click)="toggleOpen(it)" [attr.aria-expanded]="!!it._open">
              <span class="s-no">№ {{ number(i) }}</span>
              <span>{{ numText(it.quantity) || '—' }} {{ it.unit }} × {{ money(clientPrice(it)) || '—' }} =</span>
              <b>{{ money(it.calc?.sum) || '—' }}</b>
              <span class="s-chev" aria-hidden="true">{{ it._open ? '▴' : '▾' }}</span>
            </button>
            <div class="nums">
              <label class="c-qty"><span class="cap">Кол-во</span>
                <input inputmode="decimal" [value]="numText(it.quantity)" (change)="setQty(it, $event)" [disabled]="readonly"
                       [attr.aria-label]="'Количество, строка ' + (i + 1)" /></label>
              <!-- подсказка — на подписи, не на всей ячейке: title строки («Нет цены закупки…») над полем остаётся -->
              <label class="c-buy"><span class="cap" [title]="BUY_HINT">Закупка</span>
                <input inputmode="decimal" [value]="numText(it.purchasePrice)" (change)="setPurchase(it, $event)" [disabled]="readonly"
                       placeholder="—" [attr.aria-label]="'Цена закупки за единицу, как в счёте поставщика, строка ' + (i + 1)" /></label>
              <label class="c-mk"><span class="cap">Наценка, %</span>
                <input inputmode="decimal" [value]="markupText(it)" [placeholder]="it.priceOverride != null ? '—' : numText(offer.defaultMarkupPct)"
                       [disabled]="readonly || it.priceOverride != null" (change)="setMarkup(it, $event)"
                       [attr.title]="it.priceOverride != null ? 'Цена задана вручную — наценка выведена из неё; «↺» у цены вернёт расчёт' : null"
                       [attr.aria-label]="'Наценка, %, строка ' + (i + 1)" /></label>
              <label class="c-price"><span class="cap">Цена клиенту</span>
                <span class="price-wrap">
                  <input inputmode="decimal" [class.manual]="it.priceOverride != null" [value]="priceText(it)"
                         (focus)="startPriceEdit(it, $event)" (blur)="endPriceEdit(it)" (change)="setPrice(it, $event)" [disabled]="readonly"
                         [attr.aria-label]="'Цена клиенту за единицу, строка ' + (i + 1)" />
                  <button type="button" class="reset" *ngIf="it.priceOverride != null && !readonly" (click)="resetPrice(it)"
                          title="Вернуть расчёт по наценке" [attr.aria-label]="'Вернуть расчёт по наценке, строка ' + (i + 1)">↺</button>
                </span></label>
              <label class="c-vat"><span class="cap">НДС</span>
                <select *ngIf="offer.vatEnabled; else noVat" [ngModel]="it.vatRate" (ngModelChange)="setVat(it, $event)" [disabled]="readonly"
                        [attr.aria-label]="'Ставка НДС, строка ' + (i + 1)">
                  <option *ngFor="let r of vatOptions(it)" [ngValue]="r">{{ vatLabel(r) }}</option>
                </select>
                <ng-template #noVat><span class="muted">Без НДС</span></ng-template></label>
              <span class="c-sum"><span class="cap">Сумма</span>{{ money(it.calc?.sum) || '—' }}</span>
            </div>
          </ng-container>

          <div class="c-wide" *ngIf="it.kind === 'SECTION'">
            <input class="section" [(ngModel)]="it.name" (ngModelChange)="emit()" [disabled]="readonly" maxlength="4000"
                   placeholder="Заголовок раздела, например «Основные комплектующие:»" [attr.aria-label]="'Заголовок раздела, строка ' + (i + 1)" />
          </div>

          <div class="c-wide included" *ngIf="it.kind === 'INCLUDED'">
            <input [(ngModel)]="it.name" (ngModelChange)="emit()" [disabled]="readonly" maxlength="4000"
                   placeholder="Что включено, например «Гарантийное обслуживание 12 месяцев»" [attr.aria-label]="'Что включено, строка ' + (i + 1)" />
            <input [(ngModel)]="it.note" (ngModelChange)="emit()" [disabled]="readonly" maxlength="4000"
                   placeholder="Включено в стоимость" [attr.aria-label]="'Текст на месте цены, строка ' + (i + 1)" />
          </div>

          <!-- при чтении у раздела и «включено» в меню нечего показать — без «⋯», а не с пустым меню -->
          <span class="c-menu" *ngIf="!readonly || it.kind === 'ITEM'" (click)="$event.stopPropagation()">
            <button type="button" class="btn btn-more" (click)="toggleMenu(it.key)" [attr.aria-expanded]="openMenuKey === it.key"
                    [attr.aria-label]="'Действия со строкой ' + (i + 1)">⋯</button>
            <span class="row-menu" *ngIf="openMenuKey === it.key">
              <button type="button" *ngIf="it.kind === 'ITEM'" (click)="toggleOpen(it); openMenuKey = null">{{ it._open ? 'Свернуть' : 'Подробнее' }}</button>
              <ng-container *ngIf="!readonly">
                <button type="button" [disabled]="i === 0" (click)="move(i, -1)">Выше</button>
                <button type="button" [disabled]="i === offer.items.length - 1" (click)="move(i, 1)">Ниже</button>
                <button type="button" (click)="duplicateRow(i)">Дублировать</button>
                <button type="button" class="danger" (click)="removeRow(i)">Удалить</button>
              </ng-container>
            </span>
          </span>
        </div>

        <div class="details" *ngIf="it.kind === 'ITEM' && it._open">
          <label>Модель / артикул <input [(ngModel)]="it.model" (ngModelChange)="emit()" [disabled]="readonly" maxlength="255" /></label>
          <label>Производитель <input [(ngModel)]="it.manufacturer" (ngModelChange)="emit()" [disabled]="readonly" maxlength="500" /></label>
          <label>Страна <input [(ngModel)]="it.country" (ngModelChange)="emit()" [disabled]="readonly" maxlength="200" /></label>
          <label>Ед. изм. <input [(ngModel)]="it.unit" (ngModelChange)="emit()" [disabled]="readonly" maxlength="30" [attr.list]="'units-' + it.key" /></label>
          <datalist [id]="'units-' + it.key"><option *ngFor="let u of units" [value]="u"></option></datalist>
          <label class="wide">Поставщик <input [(ngModel)]="it.supplierName" (ngModelChange)="emit()" [disabled]="readonly" maxlength="255" placeholder="только для вас" /></label>
          <label class="wide">Регистрация
            <span class="reg">
              <select [ngModel]="it.registrationStatus" (ngModelChange)="setRegStatus(it, $event)" [disabled]="readonly">
                <option value="UNCHECKED">не указана</option>
                <option value="MANUAL">указать номер РУ</option>
                <option value="NOT_REQUIRED">не подлежит регистрации</option>
                <!-- подтверждение и подсказку ставит только реестр на сервере: выбрать их нельзя, только оставить -->
                <option *ngIf="it.registrationStatus === 'CONFIRMED'" value="CONFIRMED">подтверждена по реестру</option>
                <option *ngIf="it.registrationStatus === 'SUGGESTED'" value="SUGGESTED">подсказка реестра</option>
              </select>
              <input *ngIf="it.registrationStatus === 'MANUAL'" [(ngModel)]="it.registrationText" (ngModelChange)="emit()" [disabled]="readonly"
                     maxlength="1000" placeholder="№ РК-МИ (МТ)-0№023037 от 28.10.2021 г." aria-label="Текст регистрации" />
              <span class="muted" *ngIf="it.registrationStatus === 'CONFIRMED' || it.registrationStatus === 'NOT_REQUIRED'">{{ it.registrationText }}</span>
            </span></label>
          <label class="wide">Примечание <textarea rows="2" [(ngModel)]="it.note" (ngModelChange)="emit()" [disabled]="readonly" maxlength="4000"></textarea></label>
          <!-- «Себестоимость» строки убрана (2026-10-05): НДС поставщика не вычитается, она всегда равна закупке в этой же строке -->
          <div class="profit wide">
            <span>Прибыль: <b [class.neg]="(it.calc?.profit ?? 0) < 0">{{ money(it.calc?.profit) || '—' }}</b></span>
            <span class="muted">только для вас</span>
          </div>
        </div>
      </div>
    </div>
    <p class="empty" *ngIf="!offer.items.length">{{ readonly ? 'Позиций нет' : 'Позиций пока нет — нажмите «+ Позиция»' }}</p>
  `,
  styles: [`
    :host { display: block; container: kp-items / inline-size; }
    .toolbar { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 10px; }
    /* «Выбрать все» — когда шапки колонок нет (узко); в широкой раскладке эта галочка в шапке */
    .sel-all { display: none; align-items: center; gap: 6px; margin-left: auto; font-size: 13px; color: var(--text); cursor: pointer; }
    /* подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit) */
    .bulk { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 10px; margin-bottom: 10px; border-radius: 8px;
            background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid var(--accent); }
    .bulk-count { font-weight: 600; color: var(--text); }
    .bulk-field { display: inline-flex; align-items: center; gap: 6px; }
    .bulk input, .bulk select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .bulk-input { width: 110px; }

    /* Широко (≥ 960 px блока): строка — одна линия под шапкой колонок; .nums растворяется в сетке строки. */
    .line { display: grid; gap: 6px; align-items: center;
            grid-template-columns: 24px 24px 28px minmax(160px, 1fr) 64px 104px 80px 128px 88px 112px 36px;
            grid-template-areas: "grip sel num name qty buy mk price vat sum menu"; }
    .nums { display: contents; }
    .head { font-size: 12px; color: var(--text-muted); padding: 0 9px 4px 11px; }
    .head .c-sum { font-weight: inherit; color: inherit; }
    .r { text-align: right; }
    .rows { display: flex; flex-direction: column; gap: 6px; }
    .row { background: var(--surface); border: 1px solid var(--border); border-left: 3px solid transparent; border-radius: 8px; padding: 6px 8px; }
    .row[data-kind="SECTION"] { background: var(--surface-2); }
    /* подсветка ОБЛАСТИ (спека §5.4, правило kit): убыток и строка без закупки — 8% тинта поверх --surface и цветная
       кромка; выбранная строка — своим тинтом, но кромка слева остаётся сигналом */
    .row.flag-loss { background: color-mix(in srgb, var(--danger) 8%, var(--surface)); border-color: var(--danger); }
    .row.flag-nobuy { background: color-mix(in srgb, var(--warn) 8%, var(--surface)); border-color: var(--warn); }
    .row.sel { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .row.sel.flag-loss { border-left-color: var(--danger); }
    .row.sel.flag-nobuy { border-left-color: var(--warn); }
    /* причина подсветки — экранному диктору (зрячим — title строки) */
    .sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
    .c-grip { grid-area: grip; }
    .c-sel { grid-area: sel; display: flex; align-items: center; justify-content: center; cursor: pointer; }
    .c-sel:has(input:disabled) { cursor: default; }
    .c-num { grid-area: num; text-align: center; color: var(--text-muted); font-size: 13px; }
    .c-name { grid-area: name; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .c-qty { grid-area: qty; } .c-buy { grid-area: buy; } .c-mk { grid-area: mk; } .c-price { grid-area: price; } .c-vat { grid-area: vat; }
    .c-sum { grid-area: sum; text-align: right; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    .c-menu { grid-area: menu; position: relative; justify-self: end; }
    .c-wide { grid-column: 3 / -2; display: flex; gap: 6px; min-width: 0; }
    .c-wide input { flex: 1; min-width: 0; }
    .c-summary { display: none; }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 2px; display: flex; align-items: center; justify-content: center; }
    .grip:disabled { cursor: default; opacity: .4; }
    .line input:not([type="checkbox"]), .line select, .line textarea, .details input, .details select, .details textarea {
      width: 100%; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 13px; }
    /* недоступное поле должно быть видно: явные цвет и фон выше сделали бы его неотличимым от живого (наценка при ручной цене, чтение) */
    .line input:disabled, .line select:disabled, .line textarea:disabled, .details input:disabled, .details select:disabled, .details textarea:disabled {
      background: var(--surface-2); cursor: not-allowed; }
    .line input[inputmode="decimal"] { text-align: right; font-variant-numeric: tabular-nums; }
    /* field-sizing: content — наименование растёт по тексту (где браузер умеет; иначе rows="1" + ручная растяжка) */
    .name { resize: vertical; min-height: 32px; field-sizing: content; }
    .section { font-weight: 600; }
    .included input:last-child { color: var(--text-muted); }
    .price-wrap { display: flex; gap: 2px; align-items: center; }
    .price-wrap input { min-width: 0; }
    /* после :disabled — ручная цена видна и при чтении */
    .price-wrap input.manual { border-color: var(--warn); background: color-mix(in srgb, var(--warn) 8%, var(--surface)); }
    .reset { background: none; border: none; color: var(--warn-text); cursor: pointer; font-size: 15px; padding: 2px 4px; }
    .cap { display: none; }
    .muted { color: var(--text-muted); font-size: 13px; }
    /* чип: 15% тинта + текстовый токен (правило kit) */
    .chip-warn { align-self: flex-start; font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 10px;
                 background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    /* kit красит пункты меню явным цветом — недоступные «Выше»/«Ниже» без этого выглядели бы живыми */
    .row-menu button:disabled { color: var(--text-muted); cursor: default; }
    .row-menu button:disabled:hover { background: none; }
    .details { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px 10px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--border); }
    .details label { display: flex; flex-direction: column; gap: 3px; font-size: 12px; color: var(--text-muted); }
    .details .wide { grid-column: 1 / -1; }
    .reg { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .reg select { width: auto; }
    /* номер РУ уже 200 px не сжимается, а уходит под список строкой целиком */
    .reg input { flex: 1 1 200px; min-width: 0; }
    .profit { display: flex; gap: 16px; flex-wrap: wrap; font-size: 13px; color: var(--text); }
    .neg { color: var(--danger-text); }

    /* Средне (< 960 px блока): шапки колонок нет — сверху наименование, под ним числа с подписями одной линией.
       Сетка из двух линий — только у позиций: у раздела и «включено» пустая вторая линия добавила бы лишний зазор. */
    @container kp-items (max-width: 959px) {
      .head { display: none; }
      .sel-all { display: flex; }
      .line { grid-template-columns: 24px 24px 28px minmax(0, 1fr) 36px; grid-template-areas: "grip sel num name menu"; }
      .row[data-kind="ITEM"] .line { grid-template-areas: "grip sel num name menu" "nums nums nums nums nums"; }
      .nums { display: grid; grid-area: nums; gap: 6px; align-items: end;
              grid-template-columns: minmax(0, 5fr) minmax(0, 8fr) minmax(0, 6fr) minmax(0, 9.5fr) minmax(0, 8fr) minmax(0, 8.5fr);
              grid-template-areas: "qty buy mk price vat sum"; }
      .cap { display: block; margin-bottom: 2px; font-size: 11px; font-weight: 400; color: var(--text-muted); text-align: left; }
      .c-sum { padding-bottom: 7px; }
      .details { grid-template-columns: repeat(3, minmax(0, 1fr)); }
    }
    /* Узко (< 560 px блока: окно 1200–1366 px с предпросмотром рядом): числа — двумя рядами по три. */
    @container kp-items (max-width: 559px) {
      .nums { grid-template-columns: repeat(3, minmax(0, 1fr)); grid-template-areas: "qty buy mk" "price vat sum"; }
      .included { flex-direction: column; }
      .details { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }

    /* Телефон: строка — карточка. Свёрнута — номер и «3 шт × 105 600,00 = 316 800,00», тап — поля и «Подробнее» (спека §8.2).
       Галочка — под ручкой, рядом со сводкой: наименованию достаются две колонки (~210 px вместо ~165 px на 390 px). */
    @media (max-width: 900px) {
      .head { display: none; }
      .sel-all { display: flex; min-height: 40px; }
      .line { grid-template-columns: 40px 40px minmax(0, 1fr) 40px; grid-template-areas: "grip sel name menu"; }
      .row[data-kind="ITEM"] .line { grid-template-areas: "grip name name menu" "sel summary summary summary" "nums nums nums nums"; }
      .row[data-kind="ITEM"]:not(.open) .line { grid-template-areas: "grip name name menu" "sel summary summary summary"; }
      .row[data-kind="INCLUDED"] .line { grid-template-areas: "grip wide wide menu" "sel wide wide ."; }
      .row[data-kind="INCLUDED"] .c-wide { grid-area: wide; }
      .row:not(.open) .nums { display: none; }
      .c-num { display: none; }
      .c-summary { display: flex; grid-area: summary; align-items: center; flex-wrap: wrap; gap: 4px 6px; min-height: 40px; padding: 6px 10px;
                   border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
                   font: inherit; font-size: 14px; text-align: left; cursor: pointer; font-variant-numeric: tabular-nums; }
      .s-no { color: var(--text-muted); }
      .s-chev { margin-left: auto; color: var(--text-muted); }
      .nums { display: grid; grid-area: nums; gap: 6px; align-items: end;
              grid-template-columns: repeat(6, minmax(0, 1fr));
              grid-template-areas: "qty qty buy buy mk mk" "price price price price vat vat" "sum sum sum sum sum sum"; }
      .cap { display: block; margin-bottom: 2px; font-size: 11px; font-weight: 400; color: var(--text-muted); text-align: left; }
      .c-sum { text-align: left; padding-bottom: 0; }
      .c-wide { flex-direction: column; }
      .details { grid-template-columns: 1fr; }
      /* 16px — iOS не зумит поле при фокусе; локальные 13px сильнее глобального правила, поэтому повтор здесь */
      .line input:not([type="checkbox"]), .line select, .line textarea, .details input, .details select, .details textarea,
      .bulk input, .bulk select { font-size: 16px; }
      .name, .details textarea { min-height: 40px; }
      /* тач-цели 40 px: галочка — вся ячейка-подпись, ручка — вся колонка */
      .c-sel { min-width: 40px; min-height: 40px; }
      .grip { width: 100%; }
      .btn-more, .reset { min-width: 40px; }
    }
  `],
})
export class ClientOfferItemsComponent {
  @Input({ required: true }) offer!: ClientOffer;
  @Input({ required: true }) profile: any;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  bulkMarkup = '';
  /** undefined — ставка ещё не выбрана (null — это «Без НДС»): иначе «НДС» без выбора молча снял бы налог у выбранных. */
  bulkVat: number | null | undefined = undefined;
  openMenuKey: string | null = null;
  /**
   * «Цена клиенту» — единственное поле строки, чьё значение приходит и с сервера (calc.price). Пока оно в фокусе, поле
   * показывает текст на момент фокуса: [value] сравнивает с прошлой привязкой, а не с полем, и ответ автосохранения
   * иначе переписал бы набираемое («105» → «105 600», дальше ввод в конец — спека §8.3). После ухода из поля — снова расчёт.
   */
  private editingPriceKey: string | null = null;
  private editingPriceText = '';
  /**
   * Подсказка к «Закупке»: для подписи длиннее одного слова в колонке (104 px) места нет. НДС поставщика не вычитается — решение оператора
   * 2026-10-05 (спека §5.1); пометки «НДС в цене закупки» у строки больше нет, расчёт её не читает.
   */
  readonly BUY_HINT = 'Цена закупки за единицу — как в счёте поставщика';
  readonly units = UNIT_SUGGESTIONS;
  money = money;
  numText = numText;
  vatLabel = vatLabel;

  constructor(private confirm: ConfirmService, private notify: NotificationService, private host: ElementRef<HTMLElement>,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get rates(): (number | null)[] {
    return this.profile?.vatRates ?? [];
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeMenu() {
    this.openMenuKey = null;
  }

  trackKey(_: number, it: OfferItem) {
    return it.key;
  }

  emit() {
    this.changed.emit();
  }

  /** Номер позиции — только по строкам ITEM, как в документе. */
  number(i: number): number {
    let n = 0;
    for (let k = 0; k <= i; k++) if (this.offer.items[k].kind === 'ITEM') n++;
    return n;
  }

  /** Цена клиенту на экране: ручная или посчитанная сервером — своей формулы здесь нет. */
  clientPrice(it: OfferItem): number | null {
    return it.priceOverride ?? it.calc?.price ?? null;
  }

  priceText(it: OfferItem): string {
    return this.editingPriceKey === it.key ? this.editingPriceText : numText(this.clientPrice(it));
  }

  startPriceEdit(it: OfferItem, ev: FocusEvent) {
    this.editingPriceKey = it.key;
    this.editingPriceText = (ev.target as HTMLInputElement).value;
  }

  endPriceEdit(it: OfferItem) {
    if (this.editingPriceKey === it.key) this.editingPriceKey = null;
  }

  add(kind: ItemKind) {
    const it = newItem(kind, this.profile?.vatDefault ?? null);
    this.offer.items.push(it);
    this.emit();
    this.focusRow(it.key);
  }

  /** Новая строка — сразу в поле ввода: панель «+ Позиция» над списком, и без фокуса строка добавилась бы за краем экрана. */
  private focusRow(key: string) {
    this.cdr.detectChanges();
    const row = this.host.nativeElement.querySelector(`.row[data-key="${CSS.escape(key)}"]`);
    row?.querySelector<HTMLElement>('textarea, input:not([type="checkbox"])')?.focus();
  }

  drop(e: CdkDragDrop<OfferItem[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.offer.items, e.previousIndex, e.currentIndex);
    this.emit();
  }

  toggleMenu(key: string) {
    this.openMenuKey = this.openMenuKey === key ? null : key;
  }

  toggleOpen(it: OfferItem) {
    it._open = !it._open;
  }

  move(i: number, delta: number) {
    const j = i + delta;
    this.openMenuKey = null;
    if (j < 0 || j >= this.offer.items.length) return;
    moveItemInArray(this.offer.items, i, j);
    this.emit();
  }

  duplicateRow(i: number) {
    const src = this.offer.items[i];
    const copy: OfferItem = { ...src, id: null, key: uid(), lineNo: undefined, calc: src.calc ? { ...src.calc } : null, _sel: false };
    // «Подтверждена по реестру» и «подсказка реестра» ставит только сервер: новая строка с ними — 400 на каждое
    // автосохранение. Подтверждённый текст переносится ручным вводом (тот же товар), подсказка — нет.
    if (copy.registrationStatus === 'CONFIRMED') copy.registrationStatus = 'MANUAL';
    if (copy.registrationStatus === 'SUGGESTED') { copy.registrationStatus = 'UNCHECKED'; copy.registrationText = null; }
    copy.regNumber = null;
    // Ставку, которой больше нет в настройках рынка, сервер оставляет только уже сохранённой строке: копия — новая
    // строка, с такой ставкой каждое автосохранение получало бы 400. Пока профиль не пришёл, ставки не известны — не трогаем.
    if (copy.kind === 'ITEM' && this.profile && !this.hasRate(copy.vatRate)) copy.vatRate = this.profile.vatDefault ?? null;
    this.offer.items.splice(i + 1, 0, copy);
    this.openMenuKey = null;
    this.emit();
  }

  removeRow(i: number) {
    this.offer.items.splice(i, 1);
    this.openMenuKey = null;
    this.emit();
  }

  selectedCount(): number {
    return this.offer.items.filter(i => i._sel).length;
  }

  allSelected(): boolean {
    return this.offer.items.length > 0 && this.offer.items.every(i => i._sel);
  }

  someSelected(): boolean {
    const n = this.selectedCount();
    return n > 0 && n < this.offer.items.length;
  }

  selectAll(on: boolean) {
    this.offer.items.forEach(i => (i._sel = on));
  }

  /** Наценка и НДС — только позициям: у раздела и «включено» их нет. */
  private selectedItems(): OfferItem[] {
    return this.offer.items.filter(it => it._sel && it.kind === 'ITEM');
  }

  applyBulkMarkup() {
    const v = parseNum(this.bulkMarkup);
    if (v == null || v < MIN_MARKUP || v > MAX_MARKUP) { this.notify.error('Наценка — число от −100 до 1000'); return; }
    const items = this.selectedItems();
    if (!items.length) { this.notify.info('Среди выбранных строк нет позиций'); return; }
    for (const it of items) { it.markupPct = v; it.priceOverride = null; }
    this.emit();
  }

  applyBulkVat() {
    if (this.bulkVat === undefined || !this.hasRate(this.bulkVat)) { this.notify.error('Выберите ставку НДС'); return; }
    const items = this.selectedItems();
    if (!items.length) { this.notify.info('Среди выбранных строк нет позиций'); return; }
    for (const it of items) it.vatRate = this.bulkVat;
    this.emit();
  }

  bulkDelete() {
    const n = this.selectedCount();
    if (!n) return;
    this.confirm.ask(`Удалить строк: ${n}?`, 'Строки исчезнут из КП.', { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        for (let i = this.offer.items.length - 1; i >= 0; i--) if (this.offer.items[i]._sel) this.offer.items.splice(i, 1);
        this.emit();
        this.cdr.detectChanges();
      });
  }

  /** Пусто — null; не число — undefined (поле вернётся к прежнему значению, а не сотрёт его: «88 000 тг» ≠ «пусто»). */
  private read(input: HTMLInputElement): number | null | undefined {
    if (!input.value.trim()) return null;
    return parseNum(input.value) ?? undefined;
  }

  setQty(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = this.read(input);
    if (v == null || v <= 0) {
      input.value = numText(it.quantity);
      this.notify.error('Количество — число больше нуля');
      return;
    }
    it.quantity = v;
    input.value = numText(v);
    this.emit();
  }

  /** Пусто — закупки нет. */
  setPurchase(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = this.read(input);
    if (v === undefined || (v != null && v < 0)) {
      input.value = numText(it.purchasePrice);
      this.notify.error(v === undefined ? 'Цена закупки — числом' : 'Цена закупки не может быть отрицательной');
      return;
    }
    it.purchasePrice = v;
    input.value = numText(v);
    this.emit();
  }

  /** Пусто — общая наценка КП. */
  setMarkup(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = this.read(input);
    if (v === undefined || (v != null && (v < MIN_MARKUP || v > MAX_MARKUP))) {
      input.value = this.markupText(it);
      this.notify.error('Наценка — число от −100 до 1000 (пусто — общая наценка КП)');
      return;
    }
    it.markupPct = v;
    input.value = numText(v);
    this.emit();
  }

  /** Ввод цены = ручная цена; пусто — вернуть расчёт; та же цена, что посчитана, — не ручная (просто прошли Tab'ом). */
  setPrice(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = this.read(input);
    if (v === null) {
      if (it.priceOverride != null) { it.priceOverride = null; this.emit(); }
      input.value = numText(this.clientPrice(it));
      return;
    }
    if (v === undefined || v < 0) {
      input.value = numText(this.clientPrice(it));
      this.notify.error(v === undefined ? 'Цена клиенту — числом (пусто — расчёт по наценке)' : 'Цена клиенту не может быть отрицательной');
      return;
    }
    if (it.priceOverride == null && it.calc?.price != null && Math.abs(v - it.calc.price) < 0.005) {
      input.value = numText(this.clientPrice(it));
      return;
    }
    it.priceOverride = v;
    input.value = numText(v);
    this.emit();
  }

  resetPrice(it: OfferItem) {
    it.priceOverride = null;
    this.emit();
  }

  markupText(it: OfferItem): string {
    return numText(it.priceOverride != null ? (it.calc?.markupPct ?? null) : it.markupPct);
  }

  setVat(it: OfferItem, rate: number | null) {
    it.vatRate = rate;
    this.emit();
  }

  hasRate(rate: number | null | undefined): boolean {
    return rate !== undefined && this.rates.some(r => r === rate);
  }

  /** Ставки рынка + сохранённая ставка строки, если её уже нет в настройках (старое КП): иначе список показал бы пусто. */
  vatOptions(it: OfferItem): (number | null)[] {
    return this.hasRate(it.vatRate) ? this.rates : [...this.rates, it.vatRate];
  }

  /** РУ указано → ставка «подтверждённого РУ»; «не подлежит» → её ставка (настройки рынка, как в КП отца: 5% / 16%). */
  setRegStatus(it: OfferItem, status: RegStatus) {
    const prev = it.registrationStatus;
    it.registrationStatus = status;
    // «Не подлежит регистрации» — не номер РУ: в поле номера его не переносим
    if (status === 'UNCHECKED' || (status === 'MANUAL' && prev === 'NOT_REQUIRED')) it.registrationText = null;
    if (status === 'NOT_REQUIRED') {
      it.registrationText = 'Не подлежит регистрации';
      if (this.offer.vatEnabled && this.profile && 'vatNotRegistrable' in this.profile) it.vatRate = this.profile.vatNotRegistrable;
    }
    if (status === 'MANUAL' && this.offer.vatEnabled && this.profile && 'vatRegistered' in this.profile) {
      it.vatRate = this.profile.vatRegistered;
    }
    this.emit();
  }

  /**
   * Подсветка позиции (спека §5.4): «нет закупки» — цены закупки нет (и при ручной цене: прибыль не посчитать; как
   * totals.noPurchaseCount сервера), «убыток» — прибыль строки ноль или меньше. Числа — сервера (calc), своих формул нет.
   */
  flag(it: OfferItem): 'loss' | 'nobuy' | null {
    if (it.kind !== 'ITEM') return null;
    if (it.purchasePrice == null) return 'nobuy';
    const profit = it.calc?.profit;
    return profit != null && profit <= 0 ? 'loss' : null;
  }

  flagText(it: OfferItem): string | null {
    const f = this.flag(it);
    return f === 'loss' ? 'Убыток: прибыль строки — ноль или меньше' : f === 'nobuy' ? 'Нет цены закупки — прибыль не посчитана' : null;
  }

  needsReg(it: OfferItem): boolean {
    return it.kind === 'ITEM' && this.offer.columns.some(c => c.key === 'REGISTRATION')
      && (it.registrationStatus === 'UNCHECKED' || it.registrationStatus === 'SUGGESTED');
  }
}
