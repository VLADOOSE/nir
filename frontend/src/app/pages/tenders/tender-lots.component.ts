import { Component, ChangeDetectorRef, EventEmitter, HostListener, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { LotRegistryPanelComponent } from './lot-registry-panel.component';
import { LotKpPanelComponent } from './lot-kp-panel.component';

/** Стадия работы по лоту — считается на клиенте из уже загруженных лотов и запросов КП. */
export interface LotStage { label: string; tone: 'muted' | 'accent' | 'warn' | 'success' | 'danger'; filled: boolean; }

/**
 * Лоты тендера списком карточек-аккордеонов: свёрнутый лот несёт название, количество, цену,
 * предложенную модель, получателей КП и чип стадии; развёрнутый — детали, действия, спецификацию
 * и панели «Подбор»/«КП» прямо в контексте лота (горизонтального скролла на мобилке нет).
 * Здесь же живёт форма добавления/редактирования лота и удаление лота.
 */
@Component({
  selector: 'app-tender-lots',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, MarketMoneyPipe, LotRegistryPanelComponent, LotKpPanelComponent],
  template: `
    <div class="lots-toolbar">
      <!-- пока форма открыта, кнопки нет: клик по ней сбрасывал форму в режим создания и терял правки -->
      <button class="btn btn-add" *ngIf="!showLotForm" (click)="onAddLot()">Добавить лот</button>
      <button class="btn btn-bulk" *ngIf="lots.length > 0 && !isImportedTender()" (click)="bulkPriceRequested.emit()">
        Запросить КП по всему тендеру
      </button>
      <button class="btn btn-kp-sel" *ngIf="lots.length > 0" [disabled]="lotSel.size === 0" (click)="openKpSelected()">
        Запросить КП по выбранным ({{ lotSel.size }})
      </button>
      <label class="select-all" *ngIf="lots.length > 0">
        <input type="checkbox" [checked]="allLotsSelected()" (change)="toggleAllLots($any($event.target).checked)" />
        выбрать все
      </label>
      <span class="counter" *ngIf="lots.length">Найдено: {{ lots.length }} лотов</span>
    </div>

    <form *ngIf="showLotForm" [formGroup]="lotForm" (ngSubmit)="onSaveLot()" class="lot-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <div class="form-row">
        <label>&#8470; лота<input type="number" min="1" formControlName="lotNumber" [class.input-error]="validationErrors.lotNumber" /><span class="field-error" *ngIf="validationErrors.lotNumber">{{ validationErrors.lotNumber }}</span></label>
        <label>Кол-во *<input type="number" min="1" formControlName="quantity" [class.input-error]="validationErrors.quantity" /><span class="field-error" *ngIf="validationErrors.quantity">{{ validationErrors.quantity }}</span></label>
      </div>
      <label>Название оборудования *<input formControlName="equipName" [class.input-error]="validationErrors.equipName" /><span class="field-error" *ngIf="validationErrors.equipName">{{ validationErrors.equipName }}</span></label>
      <label>Тип оборудования
        <select formControlName="equipTypeId">
          <option [ngValue]="null">— не выбран —</option>
          <option *ngFor="let t of equipmentTypes" [ngValue]="t.id">{{ t.name }}</option>
        </select>
      </label>
      <label>Макс. цена<input type="number" min="0.01" step="0.01" formControlName="maxCost" [class.input-error]="validationErrors.maxCost" /><span class="field-error" *ngIf="validationErrors.maxCost">{{ validationErrors.maxCost }}</span></label>
      <div class="form-row">
        <label>Макс. длина<input type="number" min="1" formControlName="maxLengthMm" [class.input-error]="validationErrors.maxLengthMm" /><span class="field-error" *ngIf="validationErrors.maxLengthMm">{{ validationErrors.maxLengthMm }}</span></label>
        <label>Макс. ширина<input type="number" min="1" formControlName="maxWidthMm" [class.input-error]="validationErrors.maxWidthMm" /><span class="field-error" *ngIf="validationErrors.maxWidthMm">{{ validationErrors.maxWidthMm }}</span></label>
        <label>Макс. высота<input type="number" min="1" formControlName="maxHeightMm" [class.input-error]="validationErrors.maxHeightMm" /><span class="field-error" *ngIf="validationErrors.maxHeightMm">{{ validationErrors.maxHeightMm }}</span></label>
      </div>
      <label>Макс. вес (кг)<input type="number" min="0.01" step="0.01" formControlName="maxWeightKg" [class.input-error]="validationErrors.maxWeightKg" /><span class="field-error" *ngIf="validationErrors.maxWeightKg">{{ validationErrors.maxWeightKg }}</span></label>
      <label>Требования к спецификации<textarea formControlName="requiredSpec" rows="2"></textarea></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="showLotForm = false">Отмена</button>
      </div>
    </form>

    <!-- мульти-лотовая панель КП относится не к одному лоту → живёт над списком -->
    <app-lot-kp-panel *ngIf="kpLots.length > 1"
      [tenderId]="tender?.id ?? null" [lots]="kpLots" [equipmentTypes]="equipmentTypes"
      (sent)="onKpSent()" (typeChanged)="lotsChanged.emit()" (close)="kpLots = []">
    </app-lot-kp-panel>

    <!-- «Нет лотов» не рисуем над открытой формой первого лота -->
    <div class="lots-empty" *ngIf="lots.length === 0 && !showLotForm">Нет лотов</div>

    <div class="lot" *ngFor="let l of lots; trackBy: trackLot" [class.lot-open]="isExpanded(l)">
      <div class="lot-head" (click)="toggleLot(l)">
        <input class="lot-check" type="checkbox" [checked]="lotSel.has(l.id)" (click)="toggleLotSel(l, $event)" />
        <div class="lot-head-body">
          <div class="lot-title">
            <span class="lot-num" *ngIf="l.lotNumber">&#8470;{{ l.lotNumber }}</span>
            <span class="lot-name">{{ l.equipName }}</span>
            <!-- один вызов lotStage на лот: метод обходит priceRequests×items, в шаблоне звался трижды -->
            <ng-container *ngIf="lotStage(l) as st">
              <span class="stage-chip" [class]="'stage-' + st.tone">
                {{ st.filled ? '●' : '○' }} {{ st.label }}
              </span>
            </ng-container>
            <span class="lot-chev">{{ isExpanded(l) ? '▴' : '▾' }}</span>
          </div>
          <div class="lot-metrics">
            {{ l.quantity }} шт<span *ngIf="l.maxCost"> · до {{ l.maxCost | money }}</span>
          </div>
          <div class="lot-proposed" *ngIf="l.proposedEquipment">
            <span class="badge-proposed">Предложено:</span>
            {{ l.proposedEquipment.name }} ({{ l.proposedEquipment.manufact }})
            <span class="badge-reg-ok" *ngIf="l.proposedEquipment.registrationStatus === 'REGISTERED'"
                  [title]="'РУ ' + (l.proposedEquipment.regNumber || '')">РУ ✓</span>
            <button class="x-mini" (click)="clearProposed(l, $event)" title="Снять предложение">✕</button>
          </div>
          <div class="lot-kp" *ngIf="kpDistributorsFor(l.id).length">✉ КП: {{ kpDistributorsFor(l.id).join(', ') }}</div>
        </div>
      </div>

      <div class="lot-body" *ngIf="isExpanded(l)">
        <div class="lot-details" *ngIf="hasDetails(l)">
          <span *ngIf="l.equipmentType?.name"><b>Тип:</b> {{ l.equipmentType.name }}</span>
          <span *ngIf="l.maxLengthMm || l.maxWidthMm || l.maxHeightMm"><b>Габариты (макс.):</b> {{ dimsText(l) }}</span>
          <span *ngIf="l.maxWeightKg"><b>Макс. вес:</b> {{ l.maxWeightKg }} кг</span>
          <span *ngIf="l.manufact"><b>Бренд/модель:</b> {{ l.manufact }}</span>
        </div>

        <!-- всплытие в действиях НЕ гасим: .lot-body — сосед .lot-head, до toggleLot клик не дойдёт,
             а document:click должен получить событие и закрыть открытое меню «⋯» -->
        <div class="lot-actions">
          <button class="btn btn-tz" *ngIf="isImportedTender()" [disabled]="tzBusy.has(l.id)" (click)="parseTechSpec(l)"
                  title="Скачать и разобрать техспецификацию с площадки">{{ tzBusy.has(l.id) ? '…' : 'ТЗ' }}</button>
          <button class="btn btn-registry" *ngIf="isKz()" (click)="openRegistry(l)"
                  title="Подбор из реестра НЦЭЛС (кандидаты + комплектность аппаратов)">Подбор</button>
          <button class="btn btn-kp" (click)="openKpFor(l)">КП</button>
          <!-- каталог-матч: только РФ (KZ-каталог наполняется из реестра, там подбор — через «Подбор») -->
          <button class="btn btn-match" *ngIf="lotHasCriteria(l) && !isKz()"
                  (click)="matchRequested.emit({ lotId: l.id, lotNumber: l.lotNumber })">Подобрать</button>
          <span class="lot-menu-wrap">
            <button class="btn btn-more" (click)="toggleLotMenu(l, $event)" title="Ещё действия">⋯</button>
            <span class="lot-menu" *ngIf="openMenuLotId === l.id">
              <button (click)="onEditLot(l)">✎ Редактировать</button>
              <button class="danger" (click)="onDeleteLot(l.id)">🗑 Удалить</button>
            </span>
          </span>
        </div>

        <div class="lot-spec" *ngIf="l.requiredSpec">
          <button class="spec-toggle" (click)="toggleSpec(l)">
            {{ isSpecOpen(l) ? '▴' : '▾' }} Техническая спецификация
          </button>
          <div class="spec-body" *ngIf="isSpecOpen(l)">{{ l.requiredSpec }}</div>
        </div>

        <app-lot-registry-panel *ngIf="registryLot?.id === l.id"
          [lot]="registryLot" [imported]="isImportedTender()"
          (adopted)="onRegistryAdopted($event)" (close)="registryLot = null">
        </app-lot-registry-panel>

        <app-lot-kp-panel *ngIf="kpLots.length === 1 && kpLots[0].id === l.id"
          [tenderId]="tender?.id ?? null" [lots]="kpLots" [equipmentTypes]="equipmentTypes"
          (sent)="onKpSent()" (typeChanged)="lotsChanged.emit()" (close)="kpLots = []">
        </app-lot-kp-panel>
      </div>
    </div>
  `,
  styles: [`
    .lots-toolbar { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; flex-wrap: wrap; }
    .select-all { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; color: var(--text-muted); cursor: pointer; }
    .counter { color: var(--text-muted); font-size: 13px; }
    .lots-empty { color: var(--text-muted); font-size: 14px; padding: 32px 0; text-align: center; }

    .lot { border: 1px solid var(--border); border-radius: 10px; background: var(--surface); margin-bottom: 10px; overflow: visible; }
    .lot-open { border-color: var(--accent); }
    .lot-head { display: flex; align-items: flex-start; gap: 10px; padding: 12px 14px; cursor: pointer; }
    .lot-head:hover { background: var(--surface-2); }
    .lot-check { margin-top: 3px; flex: 0 0 auto; }
    .lot-head-body { flex: 1; min-width: 0; }
    .lot-title { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .lot-num { font-weight: 600; color: var(--accent); font-size: 14px; }
    .lot-name { font-size: 15px; color: var(--text); font-weight: 500; flex: 1; min-width: 0; }
    .lot-chev { color: var(--text); font-size: 14px; line-height: 1; }
    .lot-open .lot-chev { color: var(--accent); }
    .lot-metrics { font-size: 13px; color: var(--text-muted); margin-top: 3px; }
    .lot-proposed { margin-top: 5px; font-size: 12px; color: var(--text); }
    .lot-kp { margin-top: 4px; font-size: 12px; color: var(--text-muted); }
    .badge-proposed { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success);
                      border-radius: 8px; padding: 1px 7px; font-size: 11px; font-weight: 600; margin-right: 4px; }
    .badge-reg-ok { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent);
                    border-radius: 8px; padding: 1px 6px; font-size: 11px; font-weight: 600; margin-left: 4px; }
    .x-mini { background: none; border: none; color: var(--danger); cursor: pointer; font-size: 13px; margin-left: 4px; }

    .stage-chip { border-radius: 999px; padding: 2px 9px; font-size: 11px; font-weight: 600; white-space: nowrap; }
    .stage-muted { background: var(--surface-2); color: var(--text-muted); }
    .stage-accent { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .stage-warn { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn); }
    .stage-success { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
    .stage-danger { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); }

    /* отступ от разделителя даёт САМО тело: у импортных лотов строки деталей нет (hasDetails=false),
       и без padding-top кнопки действий упирались в border-top */
    .lot-body { padding: 10px 14px 14px; border-top: 1px solid var(--border); }
    .lot-details { display: flex; flex-wrap: wrap; gap: 6px 18px; font-size: 13px; color: var(--text); padding: 0 0 10px; }
    .lot-details b { color: var(--text-muted); font-weight: 600; }
    .lot-actions { display: flex; gap: 8px; flex-wrap: wrap; padding-bottom: 4px; }
    .lot-spec { margin-top: 10px; }
    .spec-toggle { background: none; border: none; padding: 0; cursor: pointer; color: var(--accent); font-size: 13px; }
    .spec-toggle:hover { text-decoration: underline; }
    .spec-body { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.6; color: var(--text);
                 padding: 10px 12px; margin-top: 8px; max-height: 340px; overflow-y: auto;
                 background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; }

    .lot-menu-wrap { position: relative; display: inline-block; }
    .lot-menu { position: absolute; right: 0; top: 100%; margin-top: 4px; background: var(--surface);
                border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow); z-index: 20;
                display: flex; flex-direction: column; min-width: 150px; overflow: hidden; }
    .lot-menu button { background: none; border: none; text-align: left; padding: 8px 12px; cursor: pointer;
                       font-size: 13px; color: var(--text); white-space: nowrap; }
    .lot-menu button:hover { background: var(--surface-2); }
    .lot-menu button.danger { color: var(--danger); }

    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn:disabled { opacity: .5; cursor: not-allowed; }
    .btn-add { background: var(--accent); color: var(--accent-contrast); }
    .btn-bulk { background: #8b5cf6; color: var(--accent-contrast); }
    .btn-kp-sel { background: #0e9f6e; color: var(--accent-contrast); }
    .btn-kp { background: #0e9f6e; color: var(--accent-contrast); }
    .btn-tz { background: #6366f1; color: var(--accent-contrast); }
    /* разбор ТЗ — многосекундная операция (сеть + PDF): «идёт работа», а не «запрещено».
       Правило ПОСЛЕ .btn:disabled — специфичность равная, побеждает позднее */
    .btn-tz:disabled { opacity: .6; cursor: wait; }
    .btn-registry { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .btn-match { background: var(--success); color: var(--accent-contrast); }
    .btn-more { background: var(--surface-2); color: var(--text); font-weight: 700; padding: 4px 9px; }

    /* ===== форма добавления/редактирования лота ===== */
    .lot-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: 16px; margin-bottom: 14px; max-width: 700px; }
    .lot-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
    .lot-form input, .lot-form select, .lot-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px;
      border: 1px solid var(--border); border-radius: 4px; font-size: 14px; font-family: inherit;
      background: var(--surface); color: var(--text); }
    .form-row { display: flex; gap: 12px; }
    .form-row label { flex: 1; }
    .form-actions { margin-top: 16px; display: flex; gap: 8px; }
    .field-error { display: block; color: var(--danger); font-size: 12px; margin-top: 2px; }
    .input-error { border-color: var(--danger) !important; }
    .error-banner { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger);
                    padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
    .btn-save { background: var(--accent); color: var(--accent-contrast); }
    .btn-cancel { background: var(--surface-2); color: var(--text); }

    /* @media — ВСЕГДА последним в блоке styles: при равной специфичности позднее базовое
       правило перебивает мобильную переопределялку (грабли проекта) */
    @media (max-width: 900px) {
      .lots-toolbar .btn { flex: 1 1 auto; }
      .lot-details { flex-direction: column; gap: 4px; }
      .lot-actions .btn { flex: 1 1 auto; }
      .form-row { flex-direction: column; gap: 0; }
    }
  `],
})
export class TenderLotsComponent implements OnChanges {
  @Input() tender: any = null;
  @Input() lots: any[] = [];
  @Input() priceRequests: any[] = [];
  @Input() equipmentTypes: any[] = [];
  /** Позиции заявок всех тендеров — по ним считается запрет удаления используемого лота. */
  @Input() applyItems: any[] = [];
  @Output() lotsChanged = new EventEmitter<void>();
  @Output() priceRequestsChanged = new EventEmitter<void>();
  @Output() bulkPriceRequested = new EventEmitter<void>();
  @Output() matchRequested = new EventEmitter<{ lotId: number; lotNumber: number }>();

  showLotForm = false;
  editingLotId: number | null = null;
  validationErrors: any = {};

  lotForm = new FormGroup({
    lotNumber: new FormControl<number | null>(null, [Validators.min(1)]),
    equipName: new FormControl(''),
    equipTypeId: new FormControl<number | null>(null),   // было equipType: string — API ждёт equipTypeId
    quantity: new FormControl<number | null>(null, [Validators.min(1)]),
    maxCost: new FormControl<number | null>(null, [Validators.min(0.01)]),
    maxLengthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWidthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxHeightMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWeightKg: new FormControl<number | null>(null, [Validators.min(0.01)]),
    requiredSpec: new FormControl('')
  });

  expandedLotId: number | null = null;
  lotSel = new Set<number>();
  tzBusy = new Set<number>();
  openMenuLotId: number | null = null;

  registryLot: any = null;   // для какого лота открыта панель «Подбор»
  kpLots: any[] = [];        // по каким лотам открыта панель «КП» (1 — внутри лота, >1 — над списком)
  /** Лот, по которому панель «КП» надо открыть, как только придут СВЕЖИЕ лоты (после «Взять в работу»). */
  private pendingKpLotId: number | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              public market: MarketService) {}

  /**
   * Пришли новые лоты (родитель перезагрузил список):
   * 1) объекты в `kpLots` заменяем НА МЕСТЕ — панель КП строит позиции письма из захваченного входа `lots`,
   *    и без ресинка в письмо уехал бы устаревший лот (без только что предложенной модели). Ссылка на массив
   *    сохраняется намеренно: новая ссылка перезапустила бы подбор поставщиков в панели и стёрла отметки оператора;
   * 2) отложенное открытие панели КП после «Взять в работу» — открываем уже на свежем объекте лота.
   */
  ngOnChanges(ch: SimpleChanges) {
    if (!ch['lots']) return;
    this.kpLots.forEach((k, i) => {
      const f = this.lots.find((l: any) => l.id === k.id);
      if (f) this.kpLots[i] = f;
    });
    if (this.pendingKpLotId !== null) {
      const fresh = this.lots.find((l: any) => l.id === this.pendingKpLotId);
      this.pendingKpLotId = null;
      // detectChanges тут НЕ звать: мы внутри прохода change detection, шаблон отрисуется сразу после хуков
      if (fresh) this.openKp([fresh]);
    }
  }

  /** Лоты трекаются по id: перезагрузка списка не должна пересоздавать открытые панели (потеря их состояния). */
  trackLot = (_: number, l: any) => l.id;

  // ===== разворот =====
  toggleLot(l: any) {
    this.expandedLotId = this.expandedLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  isExpanded(l: any): boolean { return this.expandedLotId === l.id; }

  specOpenLotId: number | null = null;
  toggleSpec(l: any) {
    this.specOpenLotId = this.specOpenLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  isSpecOpen(l: any): boolean { return this.specOpenLotId === l.id; }

  // ===== стадия лота =====
  /** Все позиции запросов КП, относящиеся к лоту. */
  private itemsForLot(lotId: number): any[] {
    const out: any[] = [];
    for (const pr of this.priceRequests || []) {
      for (const it of (pr.items || [])) if (it.tenderLot?.id === lotId) out.push(it);
    }
    return out;
  }
  /** Запросы КП, в которых есть этот лот. */
  private prsForLot(lotId: number): any[] {
    return (this.priceRequests || []).filter((pr: any) => (pr.items || []).some((it: any) => it.tenderLot?.id === lotId));
  }

  lotStage(l: any): LotStage {
    const items = this.itemsForLot(l.id);
    // считаем по персистентной цене, не по редактируемой it._editPrice
    const priced = items.filter((it: any) => it.responsePrice != null).length;
    if (priced > 0) return { label: `Есть цены: ${priced}`, tone: 'success', filled: true };
    const prs = this.prsForLot(l.id);
    if (prs.length && prs.every((pr: any) => pr.status === 'DECLINED'))
      return { label: 'Отказы', tone: 'danger', filled: true };
    if (prs.length) return { label: 'КП отправлено', tone: 'warn', filled: true };
    if (l.proposedEquipment) return { label: 'Модель выбрана', tone: 'accent', filled: true };
    if (l.requiredSpec) return { label: 'Есть ТЗ', tone: 'accent', filled: true };
    return { label: 'Нужно ТЗ', tone: 'muted', filled: false };
  }

  kpDistributorsFor(lotId: number): string[] {
    const names: string[] = [];
    for (const pr of this.priceRequests || []) {
      if ((pr.items || []).some((it: any) => it.tenderLot?.id === lotId)
          && pr.distributor?.name && !names.includes(pr.distributor.name)) {
        names.push(pr.distributor.name);
      }
    }
    return names;
  }

  // ===== деградации (переносятся дословно из tenders.component) =====
  isKz(): boolean { return this.market.value === 'KZ'; }
  isImportedTender(): boolean { return this.isKz() && /^\d+-\d+$/.test(this.tender?.tenderNumber || ''); }
  lotHasCriteria(l: any): boolean {
    return !!(l.equipmentType || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg);
  }
  hasDetails(l: any): boolean {
    return !!(l.equipmentType?.name || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg || l.manufact);
  }
  dimsText(l: any): string {
    return `${l.maxLengthMm || '—'}×${l.maxWidthMm || '—'}×${l.maxHeightMm || '—'}`;
  }

  // ===== выбор лотов =====
  toggleLotSel(l: any, ev: Event) {
    ev.stopPropagation();   // клик по чекбоксу не разворачивает лот
    if (this.lotSel.has(l.id)) this.lotSel.delete(l.id); else this.lotSel.add(l.id);
    this.cdr.detectChanges();
  }
  allLotsSelected(): boolean { return this.lots.length > 0 && this.lots.every((l: any) => this.lotSel.has(l.id)); }
  toggleAllLots(checked: boolean) {
    this.lotSel.clear();
    if (checked) for (const l of this.lots) this.lotSel.add(l.id);
    this.cdr.detectChanges();
  }

  // ===== overflow-меню строки лота =====
  toggleLotMenu(l: any, ev: Event) {
    ev.stopPropagation();   // иначе document:click тут же закроет
    this.openMenuLotId = this.openMenuLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  @HostListener('document:click')
  closeLotMenu() {
    if (this.openMenuLotId !== null) { this.openMenuLotId = null; this.cdr.detectChanges(); }
  }

  // ===== панели =====
  // Явное действие оператора отменяет отложенное открытие КП (иначе висящий pendingKpLotId
  // после упавшей перезагрузки лотов внезапно откроет панель на следующем обновлении списка).
  openRegistry(l: any) {
    this.pendingKpLotId = null;
    this.registryLot = l; this.kpLots = [];
    this.expandedLotId = l.id;   // панель живёт внутри лота — он должен быть развёрнут
    this.cdr.detectChanges();
  }
  openKpFor(l: any) { this.pendingKpLotId = null; this.openKp([l]); this.cdr.detectChanges(); }
  openKpSelected() {
    this.pendingKpLotId = null;
    this.openKp(this.lots.filter((l: any) => this.lotSel.has(l.id)));
    this.cdr.detectChanges();
  }

  /**
   * Панель КП по набору лотов. Одиночная панель живёт ВНУТРИ лота, поэтому лот разворачиваем —
   * иначе «Запросить КП по выбранным (1)» открыл бы панель в свёрнутом лоте, и её никто бы не увидел.
   * Без detectChanges: вызывается и из ngOnChanges (уже внутри change detection) — его зовёт вызывающий.
   */
  private openKp(lots: any[]) {
    if (!lots.length) return;
    this.registryLot = null;
    this.kpLots = lots;
    if (lots.length === 1) {
      this.lotSel.clear(); this.lotSel.add(lots[0].id);
      this.expandedLotId = lots[0].id;
    }
  }

  /**
   * «Взять в работу» из реестра/комплектности → обновить лоты и предложить запросить КП по этому лоту.
   * Панель КП открываем НЕ сразу: объект `lot` из панели «Подбор» ещё дореестровый (без proposedEquipment),
   * и в письмо ушёл бы голый лот. Ждём свежие лоты от родителя — см. ngOnChanges.
   */
  onRegistryAdopted(lot: any) {
    this.registryLot = null;
    this.pendingKpLotId = lot?.id ?? null;
    this.lotsChanged.emit();
    this.cdr.detectChanges();
  }
  onKpSent() {
    this.pendingKpLotId = null;
    this.kpLots = []; this.lotSel.clear();
    this.priceRequestsChanged.emit();
    this.cdr.detectChanges();
  }

  // ===== действия лота =====
  parseTechSpec(l: any) {
    this.tzBusy.add(l.id);
    this.cdr.detectChanges();
    this.api.parseLotTechSpec(l.id).subscribe({
      next: (r: any) => {
        this.tzBusy.delete(l.id);
        const dims = r.dimsFound ? 'габариты ✓' : 'габариты —';
        const weight = r.weightFound ? 'вес ✓' : 'вес —';
        const amb = r.ambiguous ? ' (неоднозначный матч лота — проверьте вручную)' : '';
        const specLen = (r.lot?.requiredSpec || '').length;
        this.notify.success(`ТЗ разобрано: спека ${specLen} симв., ${dims}, ${weight}${amb}`);
        this.lotsChanged.emit();
      },
      error: (e: any) => {
        this.tzBusy.delete(l.id);
        this.notify.error(e.error?.message || e.message || 'Не удалось разобрать ТЗ');
        this.cdr.detectChanges();
      }
    });
  }

  clearProposed(l: any, ev: Event) {
    ev.stopPropagation();
    this.api.clearProposedEquipment(l.id).subscribe({
      next: () => { this.notify.success('Предложение модели снято'); this.lotsChanged.emit(); },
      error: (e: any) => this.notify.error(e.error?.message || 'Ошибка'),
    });
  }

  // ===== форма лота (создание/правка/удаление) =====
  onAddLot() { this.editingLotId = null; this.lotForm.reset(); this.validationErrors = {}; this.showLotForm = true; this.cdr.detectChanges(); }

  onEditLot(l: any) {
    this.editingLotId = l.id;
    this.lotForm.reset();
    // patchValue не проставит equipTypeId: в ответе лота тип лежит объектом equipmentType
    this.lotForm.patchValue({ ...l, equipTypeId: l.equipmentType?.id ?? null });
    this.validationErrors = {};
    this.showLotForm = true;
    this.openMenuLotId = null;
    this.cdr.detectChanges();
  }

  onSaveLot() {
    this.validationErrors = {};
    if (!this.tender?.id) {
      this.validationErrors = { _general: 'Ошибка: не выбран тендер. Перезагрузите страницу.' };
      return;
    }
    const v: any = this.lotForm.value;
    const typeId = v.equipTypeId === '' || v.equipTypeId == null ? null : Number(v.equipTypeId);
    const body: any = { ...v, equipTypeId: typeId, tenderId: this.tender.id };
    const editingId = this.editingLotId;
    const wasEditing = editingId !== null;
    // «— не выбран —» на правке: PUT /lots молча игнорирует null-поля (маппер updateEntity — IGNORE),
    // поэтому тип снимаем ОТДЕЛЬНЫМ эндпоинтом (тем же, что селектор «Вид МИ» панели КП).
    // «Тип был задан» смотрим по входным лотам: значение формы к этому моменту уже новое (пустое).
    const hadType = wasEditing
      && (this.lots || []).find((l: any) => l.id === editingId)?.equipmentType?.id != null;
    const needsTypeClear = wasEditing && typeId === null && hadType;
    const req = editingId ? this.api.update('lots', editingId, body) : this.api.create('lots', body);
    req.subscribe({
      next: () => {
        // порядок важен: сначала снять тип, потом эмитить lotsChanged — иначе список перезагрузится
        // раньше снятия и оператор увидит старый тип
        if (needsTypeClear && editingId) {
          this.api.setLotEquipmentType(editingId, null).subscribe({
            next: () => this.finishSaveLot(wasEditing),
            error: () => this.finishSaveLot(wasEditing, true)
          });
          return;
        }
        this.finishSaveLot(wasEditing);
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  /**
   * Общий хвост успешного сохранения лота: закрыть форму, сообщить, перезагрузить список.
   * `typeClearFailed` — основное сохранение прошло, но снять тип оборудования не удалось:
   * тогда вместо успеха показываем ошибку, но список всё равно обновляем.
   */
  private finishSaveLot(wasEditing: boolean, typeClearFailed = false) {
    this.showLotForm = false; this.validationErrors = {};
    if (typeClearFailed) this.notify.error('Лот сохранён, но не удалось снять тип оборудования');
    else this.notify.success(wasEditing ? 'Лот обновлён' : 'Лот добавлен');
    this.lotsChanged.emit();
    this.cdr.detectChanges();
  }

  onDeleteLot(id: number) {
    this.openMenuLotId = null;
    // запрет ДО диалога: лот, используемый в заявках, удалить нельзя (FK)
    const usedCount = (this.applyItems || []).filter((it: any) => it.tenderLot?.id === id).length;
    if (usedCount > 0) {
      this.notify.error(`Невозможно удалить: лот используется в ${usedCount} позици${usedCount === 1 ? 'и' : 'ях'} заявок`);
      return;
    }
    this.confirm.ask('Удалить лот?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('lots', id).subscribe({
          next: () => { this.notify.success('Лот удалён'); this.lotsChanged.emit(); },
          error: (err: any) => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
