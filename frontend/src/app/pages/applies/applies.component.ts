import { Component, ChangeDetectorRef } from '@angular/core';
import { ActivatedRoute } from '@angular/router';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, FormsModule, Validators } from '@angular/forms';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { SearchableSelectComponent } from '../../components/searchable-select/searchable-select.component';

@Component({
  selector: 'app-applies',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, SearchableSelectComponent, LucideDynamicIcon, MarketMoneyPipe],
  template: `
    <!-- ========== СПИСОК ЗАЯВОК ========== -->
    <ng-container *ngIf="!selectedApply">
      <h2>Заявки на участие</h2>
      <p class="subtitle">Заявки на участие в тендерах на медицинское оборудование</p>

      <div class="filters">
        <input type="text" placeholder="Поиск по номеру тендера или заказчику..." [(ngModel)]="filterQuery" (input)="applyFilters()" class="filter-input" />
        <select [(ngModel)]="filterStatus" (change)="applyFilters()" class="filter-select">
          <option value="">Все статусы</option>
          <option value="DRAFT">Черновик</option>
          <option value="SUBMITTED">Подана</option>
          <option value="UNDER_REVIEW">На рассмотрении</option>
          <option value="WON">Выиграна</option>
          <option value="LOST">Проиграна</option>
          <option value="CANCELLED">Отменена</option>
        </select>
        <button class="btn btn-reset-filter" (click)="resetFilters()">Сбросить</button>
      </div>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showApplyForm" (click)="onAddApply()">Добавить</button>
        <span class="counter" *ngIf="filteredApplies.length">Найдено: {{ filteredApplies.length }} записей</span>
      </div>

      <form *ngIf="showApplyForm" [formGroup]="applyForm" (ngSubmit)="onSaveApply()" class="edit-form">
        <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
        <label>Тендер
          <app-searchable-select
            [items]="tenders"
            labelField="tenderNumber"
            [subLabelFields]="['description']"
            [searchFields]="['tenderNumber', 'description']"
            placeholder="— выберите тендер —"
            [value]="applyForm.value.tenderId"
            (valueChange)="applyForm.patchValue({tenderId: $event})">
          </app-searchable-select>
        </label>
        <label>Статус
          <select formControlName="status">
            <option value="DRAFT">Черновик</option>
            <option value="SUBMITTED">Подана</option>
            <option value="UNDER_REVIEW">На рассмотрении</option>
            <option value="WON">Выиграна</option>
            <option value="LOST">Проиграна</option>
            <option value="CANCELLED">Отменена</option>
          </select>
        </label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showApplyForm = false">Отмена</button>
        </div>
      </form>

      <div *ngIf="filteredApplies.length === 0 && !showApplyForm" class="empty">Нет данных</div>

      <table *ngIf="filteredApplies.length > 0">
        <thead><tr><th>ID</th><th>Номер тендера</th><th>Заказчик</th><th>Статус</th><th>Поставка</th><th>Позиций</th><th>Сумма</th><th>Дата создания</th><th>Действия</th></tr></thead>
        <tbody>
          <tr *ngFor="let a of filteredApplies">
            <td>{{ a.id }}</td>
            <td>{{ a.tender?.tenderNumber || '—' }}</td>
            <td>{{ a.tender?.facility?.name || '—' }}</td>
            <td><span class="badge" [class]="'badge-' + a.status">{{ getStatusLabel(a.status) }}</span></td>
            <td>
              <span *ngIf="a.status === 'WON' && a.deliveryStatus" class="badge" [class]="'badge-d-' + a.deliveryStatus">
                {{ deliveryLabel(a.deliveryStatus) }}
              </span>
              <span *ngIf="a.status !== 'WON'" class="muted">—</span>
            </td>
            <td>{{ a._itemCount || 0 }}</td>
            <td>{{ a._totalCost | money }}</td>
            <td>{{ formatDate(a.createdAt) }}</td>
            <td class="actions">
              <button class="btn btn-open" (click)="onOpen(a)">Открыть</button>
              <button class="btn btn-edit" (click)="onEditApply(a)">Редактировать</button>
              <button class="btn btn-delete" (click)="onDeleteApply(a.id)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>
    </ng-container>

    <!-- ========== ДЕТАЛИ ЗАЯВКИ ========== -->
    <ng-container *ngIf="selectedApply">
      <button class="btn btn-back" (click)="onBack()">&#8592; Назад к списку</button>
      <button class="btn btn-pdf" (click)="downloadPdf()">Скачать PDF</button>
      <button *ngIf="selectedApply?.status === 'DRAFT'" class="btn btn-submit"
              (click)="onMarkSubmitted()"
              [disabled]="items.length === 0 || isDeadlinePassed()"
              [title]="submitButtonReason()">Подали заявку</button>
      <span *ngIf="selectedApply?.status === 'DRAFT' && (items.length === 0 || isDeadlinePassed())" class="btn-submit-hint">
        ⚠ {{ submitButtonReason() }}
      </span>
      <button *ngIf="selectedApply?.status === 'SUBMITTED'" class="btn btn-withdraw" (click)="onWithdrawApply()">Вернуть в черновик</button>
      <button *ngIf="selectedApply?.status === 'SUBMITTED'" class="btn btn-won" (click)="onMarkResult('WON')">Тендер выигран</button>
      <button *ngIf="selectedApply?.status === 'SUBMITTED'" class="btn btn-lost" (click)="onMarkResult('LOST')">Тендер проигран</button>

      <div class="apply-info">
        <h2>Заявка #{{ selectedApply.id }}</h2>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">Тендер</span><span>&#8470; {{ selectedApply.tender?.tenderNumber || '—' }}
            <a *ngIf="selectedApply.tender?.tenderNumber && !isDemoTender(selectedApply.tender.tenderNumber)" class="eis-link-inline" [href]="eisLink(selectedApply.tender.tenderNumber)" target="_blank" rel="noopener" title="Открыть в ЕИС">
              <svg lucideIcon="external-link" [size]="11"></svg> ЕИС
            </a>
            <span *ngIf="selectedApply.tender?.tenderNumber && isDemoTender(selectedApply.tender.tenderNumber)" class="demo-badge-inline" title="Контрольный пример, не существует в ЕИС">Демо</span></span>
          </div>
          <div class="info-item"><span class="info-label">Заказчик</span><span>{{ selectedApply.tender?.facility?.name || '—' }}</span></div>
          <div class="info-item"><span class="info-label">Статус</span><span class="badge" [class]="'badge-' + selectedApply.status">{{ getStatusLabel(selectedApply.status) }}</span></div>
          <div class="info-item"><span class="info-label">Дата создания</span><span>{{ formatDate(selectedApply.createdAt) }}</span></div>
        </div>
        <p *ngIf="selectedApply.tender?.description" class="info-desc">{{ selectedApply.tender.description }}</p>
      </div>

      <h3>Позиции заявки</h3>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showItemForm" (click)="onAddItem()">Добавить позицию</button>
        <button class="btn btn-autofill" *ngIf="selectedApply?.status === 'DRAFT' && !showItemForm" (click)="onAutoFill()">Собрать из КП</button>
        <span class="counter" *ngIf="items.length">{{ items.length }} позиций — итого: {{ itemsTotal | money }}</span>
      </div>

      <div *ngIf="showAutoFillModal" class="af-modal-backdrop" (click)="showAutoFillModal = false">
        <div class="af-modal" (click)="$event.stopPropagation()">
          <h3>Собрать позиции из принятых КП</h3>
          <p class="af-desc">Для каждого лота возьмётся <strong>самое дешёвое предложение</strong> из ответов КП.
            Лоты, по которым позиция уже есть, пропускаются.</p>
          <div class="af-presets-label">Наценка:</div>
          <div class="af-presets">
            <button *ngFor="let p of markupPresets" type="button"
                    [class.active]="+autoFillMarkup === p"
                    (click)="autoFillMarkup = p">{{ p }}%</button>
            <label class="af-custom">
              <span>Своё:</span>
              <input type="number" min="0" max="200" step="1" [(ngModel)]="autoFillMarkup" />
              <span>%</span>
            </label>
          </div>
          <p class="af-hint">
            <strong>Предл. цена = закупка × (1 + {{ autoFillMarkup }}%)</strong>,
            но не выше максимума лота (потолка заказчика).<br>
            При наценке 0 — прибыль = 0. Рекомендуется 20–30%.
          </p>
          <div class="af-actions">
            <button class="btn btn-cancel" (click)="showAutoFillModal = false">Отмена</button>
            <button class="btn btn-save" (click)="confirmAutoFill()">Собрать</button>
          </div>
        </div>
      </div>

      <form *ngIf="showItemForm" [formGroup]="itemForm" (ngSubmit)="onSaveItem()" class="edit-form">
        <label>Лот тендера
          <select formControlName="tenderLotId" (change)="onLotSelected()">
            <option [ngValue]="null">— не выбран —</option>
            <option *ngFor="let l of lots" [ngValue]="l.id">Лот {{ l.lotNumber }}: {{ l.equipName }}</option>
          </select>
        </label>
        <label>Оборудование
          <select formControlName="medEquipId">
            <option [ngValue]="null">— не выбрано —</option>
            <option *ngFor="let e of filteredEquipmentForItem" [ngValue]="e.id">{{ e.name }} ({{ e.manufact }})</option>
          </select>
          <div *ngIf="currentLotTypeName" class="filter-hint">Показаны только аппараты типа «{{ currentLotTypeName }}»</div>
        </label>
        <label>Дистрибьютор
          <select formControlName="distributorId">
            <option [ngValue]="null">— не выбран —</option>
            <option *ngFor="let d of distributors" [ngValue]="d.id">{{ d.name }}</option>
          </select>
        </label>
        <div class="dims-row">
          <label>Предложенная цена<input type="number" min="0.01" step="0.01" formControlName="offeredCost" [class.input-error]="validationErrors.offeredCost" /><span class="field-error" *ngIf="validationErrors.offeredCost">{{ validationErrors.offeredCost }}</span></label>
          <label>Количество<input type="number" min="1" formControlName="quantity" [class.input-error]="validationErrors.quantity" /><span class="field-error" *ngIf="validationErrors.quantity">{{ validationErrors.quantity }}</span></label>
        </div>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showItemForm = false">Отмена</button>
        </div>
      </form>

      <div *ngIf="items.length === 0 && !showItemForm" class="empty">Нет позиций</div>

      <table *ngIf="items.length > 0">
        <thead><tr>
          <th>Лот</th><th>Оборудование</th><th>Дистрибьютор</th>
          <th class="num-col">Предл. цена</th>
          <th class="num-col">Закупка</th>
          <th class="num-col">Маржа</th>
          <th class="num-col">%</th>
          <th class="num-col">Кол-во</th>
          <th>Действия</th>
        </tr></thead>
        <tbody>
          <tr *ngFor="let it of items">
            <td>{{ it.tenderLot?.equipName || '—' }}</td>
            <td>{{ it.medEquipment?.name || '—' }}</td>
            <td>{{ it.distributor?.name || '—' }}</td>
            <td class="num-col">{{ it.offeredCost | money }}</td>
            <td class="num-col">{{ it.procurementCost != null ? (it.procurementCost | money) : '—' }}</td>
            <td class="num-col" [class.positive]="it.margin > 0" [class.negative]="it.margin < 0">
              {{ it.margin != null ? (it.margin | money) : '—' }}
            </td>
            <td class="num-col" [class.positive]="it.marginPercent > 0" [class.negative]="it.marginPercent < 0">
              {{ it.marginPercent != null ? it.marginPercent + ' %' : '—' }}
            </td>
            <td class="num-col">{{ it.quantity }}</td>
            <td class="actions">
              <button class="btn btn-edit" (click)="onEditItem(it)">Редактировать</button>
              <button class="btn btn-delete" (click)="onDeleteItem(it.id)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div *ngIf="items.length > 0 && selectedApply" class="profit-summary">
        <div class="ps-row">
          <span class="ps-label">Выручка</span>
          <span class="ps-value">{{ selectedApply.totalRevenue | money }}</span>
        </div>
        <div class="ps-row" *ngIf="selectedApply.totalProcurement != null">
          <span class="ps-label">Закупка</span>
          <span class="ps-value">{{ selectedApply.totalProcurement | money }}</span>
        </div>
        <div class="ps-row ps-profit" *ngIf="selectedApply.totalProfit != null"
             [class.positive]="selectedApply.totalProfit > 0"
             [class.negative]="selectedApply.totalProfit < 0">
          <span class="ps-label">Прибыль</span>
          <span class="ps-value">{{ selectedApply.totalProfit | money }}
            <span *ngIf="selectedApply.marginPercent != null" class="ps-percent">({{ selectedApply.marginPercent }}%)</span>
          </span>
        </div>
        <div class="ps-row ps-hint" *ngIf="selectedApply.totalProcurement == null">
          <span class="ps-hint-text">Закупочные цены не определены — заявка собрана без привязки к ответам КП</span>
        </div>
      </div>

      <div *ngIf="selectedApply?.status === 'WON'" class="won-block">
        <h3>После победы</h3>

        <div class="contract-form">
          <div class="cf-field">
            <label>Номер договора с заказчиком</label>
            <input [(ngModel)]="contractEdit.contractNumber" placeholder="напр. №42/2026" />
          </div>
          <div class="cf-field">
            <label>Дата подписания</label>
            <input type="date" [(ngModel)]="contractEdit.contractSignedAt" />
          </div>
          <div class="cf-actions">
            <button class="btn btn-save" (click)="saveContract()">Сохранить договор</button>
          </div>
        </div>

        <div class="delivery-flow">
          <h4>Поставка</h4>
          <div class="delivery-steps">
            <div class="delivery-step" [class.active]="!selectedApply.deliveryStatus || selectedApply.deliveryStatus === 'NONE'">
              <span class="ds-icon"><svg lucideIcon="clipboard-list" [size]="16"></svg></span>
              <span class="ds-label">Не начата</span>
            </div>
            <span class="ds-arrow">→</span>
            <div class="delivery-step" [class.active]="selectedApply.deliveryStatus === 'ORDERED'" [class.done]="['DELIVERED','PAID'].includes(selectedApply.deliveryStatus)">
              <span class="ds-icon"><svg lucideIcon="file-box" [size]="16"></svg></span>
              <span class="ds-label">Заказано</span>
            </div>
            <span class="ds-arrow">→</span>
            <div class="delivery-step" [class.active]="selectedApply.deliveryStatus === 'DELIVERED'" [class.done]="selectedApply.deliveryStatus === 'PAID'">
              <span class="ds-icon"><svg lucideIcon="truck" [size]="16"></svg></span>
              <span class="ds-label">Поставлено<small *ngIf="selectedApply.deliveredAt">{{ formatDate(selectedApply.deliveredAt) }}</small></span>
            </div>
            <span class="ds-arrow">→</span>
            <div class="delivery-step" [class.active]="selectedApply.deliveryStatus === 'PAID'">
              <span class="ds-icon"><svg lucideIcon="handshake" [size]="16"></svg></span>
              <span class="ds-label">Оплачено<small *ngIf="selectedApply.paidAt">{{ formatDate(selectedApply.paidAt) }}</small></span>
            </div>
          </div>

          <div class="delivery-actions">
            <button *ngIf="!selectedApply.deliveryStatus || selectedApply.deliveryStatus === 'NONE'" class="btn btn-step" (click)="setDeliveryStatus('ORDERED')">Заказать у дистрибьютора</button>
            <button *ngIf="selectedApply.deliveryStatus === 'ORDERED'" class="btn btn-step" (click)="setDeliveryStatus('DELIVERED')">Отметить поставку</button>
            <button *ngIf="selectedApply.deliveryStatus === 'DELIVERED'" class="btn btn-step btn-step-paid" (click)="setDeliveryStatus('PAID')">Отметить оплату</button>
            <span *ngIf="selectedApply.deliveryStatus === 'PAID'" class="all-done">✓ Сделка закрыта</span>
          </div>
        </div>
      </div>
    </ng-container>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .eis-link-inline { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; padding: 2px 8px; background: #eff6ff; color: #1a56db; border-radius: 4px; text-decoration: none; font-weight: 500; margin-left: 8px; vertical-align: middle; }
    .eis-link-inline:hover { background: #dbeafe; }
    .demo-badge-inline { display: inline-flex; align-items: center; font-size: 10px; padding: 2px 6px; background: #fef3c7; color: #92400e; border-radius: 4px; font-weight: 600; margin-left: 8px; vertical-align: middle; text-transform: uppercase; letter-spacing: 0.04em; }
    .af-modal-backdrop { position: fixed; inset: 0; background: rgba(17,24,39,0.5); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .af-modal { background: #fff; border-radius: 10px; padding: 24px; width: 440px; max-width: 90vw; box-shadow: 0 12px 32px rgba(0,0,0,0.18); }
    .af-modal h3 { margin: 0 0 8px; font-size: 17px; color: #111827; }
    .af-desc { color: #4b5563; font-size: 13px; margin: 0 0 16px; line-height: 1.5; }
    .af-presets-label { font-size: 13px; color: #374151; margin: 0 0 8px; font-weight: 500; }
    .af-presets { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 14px; align-items: center; }
    .af-presets button { padding: 6px 12px; border: 1px solid #d1d5db; background: #f9fafb; border-radius: 4px; cursor: pointer; font-size: 13px; color: #374151; min-width: 50px; }
    .af-presets button:hover { background: #f3f4f6; }
    .af-presets button.active { background: #1a56db; color: #fff; border-color: #1a56db; font-weight: 600; }
    .af-custom { display: inline-flex; align-items: center; gap: 4px; margin-left: 6px; font-size: 13px; color: #6b7280; }
    .af-custom input { width: 70px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 13px; text-align: right; }
    .af-hint { background: #eff6ff; border-left: 3px solid #1a56db; padding: 10px 12px; border-radius: 4px; font-size: 12px; color: #1e3a8a; margin: 0 0 16px; line-height: 1.5; }
    .af-hint strong { color: #1a56db; }
    .af-actions { display: flex; justify-content: flex-end; gap: 8px; }
    h3 { margin: 24px 0 12px; font-size: 17px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
    .filter-input { flex: 1; min-width: 240px; max-width: 400px; padding: 8px 14px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; }
    .filter-input:focus { outline: none; border-color: #1a56db; }
    .filter-select { padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; background: #fff; }
    .btn-reset-filter { background: #e5e7eb; color: #374151; padding: 8px 14px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .counter { color: #6b7280; font-size: 13px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }
    table { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: #1a56db; color: #fff; }
    .btn-autofill { background: #059669; color: #fff; margin-left: 8px; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn-open { background: #1a56db; color: #fff; margin-right: 4px; }
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 16px; }
    .btn-pdf { background: #dc2626; color: #fff; margin-left: 8px; margin-bottom: 16px; }
    .btn-submit { background: #059669; color: #fff; margin-left: 8px; margin-bottom: 16px; }
    .btn-submit:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-submit-hint { display: inline-block; margin-left: 12px; font-size: 12px; color: #b45309; background: #fef3c7; padding: 4px 10px; border-radius: 4px; vertical-align: middle; }
    .btn-withdraw { background: #6b7280; color: #fff; margin-left: 8px; margin-bottom: 16px; }
    .btn-won { background: #059669; color: #fff; margin-left: 8px; margin-bottom: 16px; }
    .btn-lost { background: #dc2626; color: #fff; margin-left: 8px; margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-SUBMITTED { background: #dbeafe; color: #1a56db; }
    .badge-UNDER_REVIEW { background: #fef3c7; color: #92400e; }
    .badge-WON { background: #d1fae5; color: #065f46; }
    .badge-LOST { background: #fee2e2; color: #991b1b; }
    .badge-CANCELLED { background: #f3f4f6; color: #6b7280; }
    .badge-REJECTED { background: #fee2e2; color: #991b1b; }
    .apply-info { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-bottom: 16px; }
    .apply-info p { margin: 4px 0; font-size: 14px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 24px; margin-top: 12px; }
    .info-item { display: flex; flex-direction: column; }
    .info-label { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin-bottom: 2px; }
    .info-item span:not(.info-label) { font-size: 14px; }
    .info-desc { margin-top: 12px; font-size: 13px; color: #6b7280; line-height: 1.5; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
    .filter-hint { font-size: 12px; color: #6b7280; margin-top: 4px; font-style: italic; }
    .num-col { text-align: right; white-space: nowrap; }
    .positive { color: #059669; font-weight: 500; }
    .negative { color: #dc2626; font-weight: 500; }
    .profit-summary { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 16px 20px; margin-top: 16px; max-width: 480px; }
    .ps-row { display: flex; justify-content: space-between; align-items: baseline; padding: 4px 0; font-size: 14px; }
    .ps-label { color: #6b7280; }
    .ps-value { font-weight: 600; color: #111827; }
    .ps-profit { border-top: 1px solid #e5e7eb; padding-top: 8px; margin-top: 4px; font-size: 16px; }
    .ps-profit.positive .ps-value { color: #059669; }
    .ps-profit.negative .ps-value { color: #dc2626; }
    .ps-percent { font-size: 13px; opacity: 0.85; font-weight: 500; }
    .ps-hint { font-size: 12px; color: #9ca3af; font-style: italic; padding-top: 4px; }
    .ps-hint-text { display: block; }

    .won-block { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 8px; padding: 20px 24px; margin-top: 20px; }
    .won-block h3 { margin: 0 0 14px; font-size: 16px; color: #047857; }
    .won-block h4 { margin: 20px 0 12px; font-size: 14px; color: #065f46; }
    .contract-form { display: grid; grid-template-columns: 2fr 1fr auto; gap: 12px; align-items: end; }
    .cf-field label { display: block; font-size: 12px; color: #6b7280; margin-bottom: 4px; }
    .cf-field input { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; box-sizing: border-box; }
    .cf-actions { display: flex; }
    .delivery-flow { margin-top: 16px; padding-top: 16px; border-top: 1px solid #bbf7d0; }
    .delivery-steps { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; margin-bottom: 12px; }
    .delivery-step { display: flex; align-items: center; gap: 8px; padding: 6px 12px; border-radius: 6px; background: #e5e7eb; color: #6b7280; font-size: 13px; }
    .delivery-step.active { background: #1a56db; color: #fff; font-weight: 500; }
    .delivery-step.done { background: #d1fae5; color: #047857; }
    .ds-icon { font-size: 16px; }
    .ds-label { display: flex; flex-direction: column; line-height: 1.2; }
    .ds-label small { font-size: 10px; opacity: 0.8; }
    .ds-arrow { color: #9ca3af; font-size: 14px; }
    .delivery-actions { display: flex; gap: 8px; align-items: center; }
    .btn-step { background: #1a56db; color: #fff; }
    .btn-step-paid { background: #059669; }
    .all-done { color: #047857; font-weight: 600; font-size: 14px; }
    .badge-d-NONE { background: #e5e7eb; color: #6b7280; }
    .badge-d-ORDERED { background: #fef3c7; color: #92400e; }
    .badge-d-DELIVERED { background: #dbeafe; color: #1a56db; }
    .badge-d-PAID { background: #d1fae5; color: #047857; }
    .muted { color: #9ca3af; font-size: 12px; }
  `]
})
export class AppliesComponent {
  applies: any[] = [];
  filteredApplies: any[] = [];
  filterQuery = '';
  filterStatus = '';
  tenders: any[] = [];
  validationErrors: any = {};
  selectedApply: any = null;
  items: any[] = [];
  itemsTotal = 0;
  lots: any[] = [];
  equipment: any[] = [];
  distributors: any[] = [];

  showApplyForm = false;
  editingApplyId: number | null = null;
  applyForm = new FormGroup({ tenderId: new FormControl<number | null>(null), status: new FormControl('DRAFT') });

  contractEdit: { contractNumber: string; contractSignedAt: string } = { contractNumber: '', contractSignedAt: '' };

  showItemForm = false;
  showAutoFillModal = false;
  autoFillMarkup = 25;
  markupPresets = [0, 10, 15, 20, 25, 30, 40, 50];
  editingItemId: number | null = null;
  itemForm = new FormGroup({
    tenderLotId: new FormControl<number | null>(null), medEquipId: new FormControl<number | null>(null),
    distributorId: new FormControl<number | null>(null),
    offeredCost: new FormControl<number | null>(null, [Validators.min(0.01)]),
    quantity: new FormControl<number | null>(null, [Validators.min(1)])
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              private route: ActivatedRoute, public market: MarketService) {
    this.loadApplies();
    this.api.getTenders().subscribe({ next: data => { this.tenders = data; this.cdr.detectChanges(); } });
  }

  applyFilters() {
    const q = (this.filterQuery || '').toLowerCase();
    this.filteredApplies = this.applies.filter(a => {
      const tn = (a.tender?.tenderNumber || '').toLowerCase();
      const fn = (a.tender?.facility?.name || '').toLowerCase();
      const textMatch = !q || tn.includes(q) || fn.includes(q);
      const statusMatch = !this.filterStatus || a.status === this.filterStatus;
      return textMatch && statusMatch;
    });
  }

  resetFilters() {
    this.filterQuery = '';
    this.filterStatus = '';
    this.applyFilters();
  }

  getStatusLabel(s: string): string {
    return ({ DRAFT: 'Черновик', SUBMITTED: 'Подана', UNDER_REVIEW: 'На рассмотрении', WON: 'Выиграна', LOST: 'Проиграна', CANCELLED: 'Отменена', REJECTED: 'Отклонена' } as any)[s] || s;
  }

  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatPrice(n: number): string { return n ? Number(n).toLocaleString('ru-RU') : '0'; }

  eisLink(tenderNumber: string): string {
    return `https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=${encodeURIComponent(tenderNumber)}`;
  }

  isDemoTender(tenderNumber: string): boolean {
    return !!tenderNumber && tenderNumber.startsWith('DEMO-');
  }

  loadApplies() {
    this.api.getApplies().subscribe({
      next: data => {
        this.applies = data;
        this.applyFilters();
        // Подгружаем позиции для каждой заявки для подсчёта
        for (const a of this.applies) {
          a._itemCount = 0;
          a._totalCost = 0;
          this.api.getApplyItems(a.id).subscribe(items => {
            a._itemCount = items.length;
            a._totalCost = items.reduce((s: number, it: any) => s + ((it.offeredCost || 0) * (it.quantity || 1)), 0);
            this.cdr.detectChanges();
          });
        }
        // Авто-открытие заявки через ?openId=N (бридж КП → Заявка)
        const openId = Number(this.route.snapshot.queryParamMap.get('openId'));
        if (openId && !this.selectedApply) {
          const apply = this.applies.find((a: any) => a.id === openId);
          if (apply) this.onOpen(apply);
        }
        this.cdr.detectChanges();
      },
      error: err => this.notify.error('Ошибка загрузки заявок: ' + (err.error?.message || err.message))
    });
  }

  onAddApply() { this.editingApplyId = null; this.applyForm.reset({ status: 'DRAFT' }); this.validationErrors = {}; this.showApplyForm = true; }
  onEditApply(a: any) { this.editingApplyId = a.id; this.applyForm.patchValue({ tenderId: a.tender?.id || null, status: a.status }); this.validationErrors = {}; this.showApplyForm = true; }

  onSaveApply() {
    const v = this.applyForm.value;
    const body: any = { status: v.status, tenderId: v.tenderId || null };
    const wasEditing = this.editingApplyId !== null;
    const req = this.editingApplyId ? this.api.update('applies', this.editingApplyId, body) : this.api.create('applies', body);
    req.subscribe({
      next: () => {
        this.showApplyForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Заявка обновлена' : 'Заявка создана');
        this.loadApplies();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения данных' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDeleteApply(id: number) {
    this.confirm.ask('Удалить заявку?', 'Это действие нельзя отменить. Будут удалены все позиции этой заявки.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('applies', id).subscribe({
          next: () => { this.notify.success('Заявка удалена'); this.loadApplies(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }

  onOpen(a: any) {
    this.selectedApply = a;
    this.contractEdit = {
      contractNumber: a.contractNumber || '',
      contractSignedAt: a.contractSignedAt || ''
    };
    this.loadItems();
    this.loadReferenceData();
  }

  saveContract() {
    const body: any = {
      status: this.selectedApply.status,
      tenderId: this.selectedApply.tender?.id || null,
      contractNumber: this.contractEdit.contractNumber || null,
      contractSignedAt: this.contractEdit.contractSignedAt || null
    };
    this.api.update('applies', this.selectedApply.id, body).subscribe({
      next: (updated: any) => {
        this.selectedApply = updated;
        this.notify.success('Договор сохранён');
        this.cdr.detectChanges();
      },
      error: err => this.notify.error(err.error?.message || 'Ошибка сохранения')
    });
  }

  setDeliveryStatus(status: 'ORDERED' | 'DELIVERED' | 'PAID') {
    const today = new Date().toISOString().slice(0, 10);
    const body: any = {
      status: this.selectedApply.status,
      tenderId: this.selectedApply.tender?.id || null,
      deliveryStatus: status
    };
    if (status === 'DELIVERED' && !this.selectedApply.deliveredAt) body.deliveredAt = today;
    if (status === 'PAID' && !this.selectedApply.paidAt) body.paidAt = today;

    this.confirm.ask('Перевести поставку в статус «' + this.deliveryLabel(status) + '»?', undefined, { confirmLabel: 'Подтвердить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.update('applies', this.selectedApply.id, body).subscribe({
          next: (updated: any) => {
            this.selectedApply = updated;
            this.notify.success('Статус поставки обновлён');
            this.cdr.detectChanges();
          },
          error: err => this.notify.error(err.error?.message || 'Ошибка')
        });
      });
  }

  deliveryLabel(s: string): string {
    return ({ NONE: 'Не начата', ORDERED: 'Заказано', DELIVERED: 'Поставлено', PAID: 'Оплачено' } as any)[s] || s;
  }

  onBack() { this.selectedApply = null; this.showItemForm = false; this.loadApplies(); }

  loadItems() {
    this.api.getApplyItems(this.selectedApply.id).subscribe({
      next: data => {
        this.items = data;
        this.itemsTotal = data.reduce((s: number, it: any) => s + ((it.offeredCost || 0) * (it.quantity || 1)), 0);
        this.cdr.detectChanges();
      },
      error: err => this.notify.error('Ошибка загрузки позиций: ' + (err.error?.message || err.message))
    });
  }

  loadReferenceData() {
    if (this.selectedApply.tender?.id) { this.api.getTenderLots(this.selectedApply.tender.id).subscribe(data => this.lots = data); }
    this.api.getEquipment().subscribe(data => this.equipment = data);
    this.api.getDistributors().subscribe(data => this.distributors = data);
  }

  onAddItem() { this.editingItemId = null; this.itemForm.reset(); this.validationErrors = {}; this.showItemForm = true; }

  onAutoFill() {
    this.autoFillMarkup = 25;
    this.showAutoFillModal = true;
  }

  confirmAutoFill() {
    this.showAutoFillModal = false;
    const markup = Math.max(0, Math.min(200, Number(this.autoFillMarkup) || 0));
    this.api.autoFillApply(this.selectedApply.id, markup).subscribe({
      next: (resp: any) => {
        this.notify.success(`Добавлено позиций: ${resp.addedItems} (наценка ${markup}%)`);
        if (resp.lotsWithoutResponse?.length) {
          this.notify.info('Нет КП с ответом по: ' + resp.lotsWithoutResponse.join(', '));
        }
        this.loadItems();
        this.api.getById('applies', this.selectedApply.id).subscribe((updated: any) => {
          this.selectedApply = updated;
          this.cdr.detectChanges();
        });
        this.loadApplies();
      },
      error: err => this.notify.error(err.error?.message || 'Ошибка автосборки')
    });
  }
  onEditItem(it: any) {
    this.editingItemId = it.id;
    this.itemForm.patchValue({ tenderLotId: it.tenderLot?.id || null, medEquipId: it.medEquipment?.id || null, distributorId: it.distributor?.id || null, offeredCost: it.offeredCost, quantity: it.quantity });
    this.showItemForm = true;
  }

  onSaveItem() {
    this.validationErrors = {};
    const v = this.itemForm.value;

    if (v.offeredCost && v.tenderLotId) {
      const lot = this.lots.find((l: any) => l.id === +v.tenderLotId!);
      if (lot && lot.maxCost != null && +v.offeredCost! > +lot.maxCost) {
        this.validationErrors = { offeredCost: 'Превышает макс. цену лота (' + lot.maxCost + ' ' + this.market.symbol() + ')' };
        return;
      }
    }

    const body: any = { applyId: this.selectedApply.id, tenderLotId: v.tenderLotId || null, medEquipId: v.medEquipId || null, distributorId: v.distributorId || null, offeredCost: v.offeredCost, quantity: v.quantity };
    const req = this.editingItemId ? this.api.update('apply-items', this.editingItemId, body) : this.api.create('apply-items', body);
    req.subscribe({
      next: () => { this.showItemForm = false; this.validationErrors = {}; this.loadItems(); },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения данных' }; }
        this.cdr.detectChanges();
      }
    });
  }

  get filteredEquipmentForItem(): any[] {
    const lotId = this.itemForm.value.tenderLotId;
    if (!lotId) return this.equipment;
    const lot = this.lots.find((l: any) => l.id === +lotId!);
    if (!lot || !lot.equipmentType?.id) return this.equipment;
    return this.equipment.filter((e: any) => e.equipmentType?.id === lot.equipmentType.id);
  }

  get currentLotTypeName(): string | null {
    const lotId = this.itemForm.value.tenderLotId;
    if (!lotId) return null;
    const lot = this.lots.find((l: any) => l.id === +lotId!);
    return lot?.equipmentType?.name || null;
  }

  onLotSelected() {
    const lotId = this.itemForm.value.tenderLotId;
    if (lotId) {
      const lot = this.lots.find((l: any) => l.id === +lotId);
      if (lot) {
        this.itemForm.patchValue({ quantity: lot.quantity });
      }
    }
  }

  isDeadlinePassed(): boolean {
    if (!this.selectedApply?.tender?.deadline) return false;
    return new Date(this.selectedApply.tender.deadline) < new Date();
  }

  submitButtonReason(): string {
    if (this.items.length === 0) return 'Добавьте хотя бы одну позицию (вручную или через «Собрать из КП»)';
    if (this.isDeadlinePassed()) return 'Срок подачи заявок по этому тендеру истёк';
    return '';
  }

  onMarkSubmitted() {
    if (this.items.length === 0) {
      this.notify.error('Нельзя отметить подачу без позиций в заявке');
      return;
    }
    if (this.isDeadlinePassed()) {
      this.notify.error('Срок подачи заявок по этому тендеру истёк');
      return;
    }
    this.confirm.ask('Отметить заявку как поданную?', 'Это означает, что заявка отправлена на тендерную площадку.', { confirmLabel: 'Отметить' })
      .subscribe(ok => { if (ok) this.updateApplyStatus('SUBMITTED', 'Заявка отмечена как поданная'); });
  }

  onWithdrawApply() {
    this.confirm.ask('Вернуть заявку в черновики?', 'Статус сменится с «Подана» на «Черновик».', { confirmLabel: 'Вернуть' })
      .subscribe(ok => { if (ok) this.updateApplyStatus('DRAFT', 'Заявка возвращена в черновики'); });
  }

  onMarkResult(result: 'WON' | 'LOST') {
    const won = result === 'WON';
    this.confirm.ask(
      won ? 'Отметить тендер как выигранный?' : 'Отметить тендер как проигранный?',
      'Это финальный статус заявки.',
      { danger: !won, confirmLabel: won ? 'Выигран' : 'Проигран' }
    ).subscribe(ok => {
      if (ok) this.updateApplyStatus(result, won ? 'Заявка отмечена как выигранная' : 'Заявка отмечена как проигранная');
    });
  }

  private updateApplyStatus(status: string, successMsg: string) {
    const body = {
      status,
      tenderId: this.selectedApply.tender?.id || null
    };
    this.api.update('applies', this.selectedApply.id, body).subscribe({
      next: (updated: any) => {
        this.selectedApply = updated;
        this.notify.success(successMsg);
        this.cdr.detectChanges();
      },
      error: () => this.notify.error('Ошибка при обновлении статуса')
    });
  }

  downloadPdf() {
    this.api.downloadApplyReport(this.selectedApply.id).subscribe(blob => {
      const url = window.URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = 'apply_' + this.selectedApply.id + '.pdf';
      a.click();
      window.URL.revokeObjectURL(url);
    });
  }

  onDeleteItem(id: number) {
    this.confirm.ask('Удалить позицию заявки?', undefined, { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('apply-items', id).subscribe({
          next: () => { this.notify.success('Позиция удалена'); this.loadItems(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
