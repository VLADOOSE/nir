import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, FormsModule } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';
import { SearchableSelectComponent } from '../../components/searchable-select/searchable-select.component';
import { BulkPriceModalComponent } from './bulk-price-modal.component';
import { OfferComparisonComponent } from './offer-comparison.component';
import { SmartMatchComponent } from '../../components/smart-match/smart-match.component';
import { LucideDynamicIcon } from '@lucide/angular';
import { KZ_REGIONS } from '../../shared/kz-regions';
import { kpToastFromResults } from '../../shared/kp-toast';
import { TendersFiltersComponent, TendersFilters } from './tenders-filters.component';
import { TenderLotsComponent } from './tender-lots.component';

@Component({
  selector: 'app-tenders',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, SearchableSelectComponent, BulkPriceModalComponent, OfferComparisonComponent, SmartMatchComponent, LucideDynamicIcon, MarketMoneyPipe, TendersFiltersComponent, TenderLotsComponent],
  template: `
    <!-- ========== СПИСОК ТЕНДЕРОВ ========== -->
    <ng-container *ngIf="!selectedTender">
      <h2>Тендеры</h2>
      <p class="subtitle">Управление тендерами на закупку медицинского оборудования</p>

      <app-tenders-filters
        [filters]="filtersState" [facilities]="facilities" [regions]="REGIONS"
        [isKz]="isKz()" [noRegionValue]="NO_REGION"
        (filtersChange)="onFiltersChange($event)" (reset)="resetTendersFilter()">
      </app-tenders-filters>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showTenderForm" (click)="onAddTender()">Добавить тендер</button>
        <button class="btn btn-add" *ngIf="isKz() && !showTenderForm" (click)="onImportTenders()" [disabled]="importBusy()"
                [title]="importButtonTitle()">
          {{ importButtonLabel() }}
        </button>
        <div class="import-progress" *ngIf="isKz() && importStatus?.running">
          <div class="import-bar"><div class="import-bar-fill" [style.width.%]="importPct()"></div></div>
          <span class="import-progress-text">
            больница {{ importStatus.lastSummary?.orgsProcessed || 0 }}/{{ importStatus.lastSummary?.orgsTotal || '…' }}
            · получено {{ importStatus.lastSummary?.fetched || 0 }}
            · подходящих {{ importStatus.lastSummary?.matched || 0 }}
            · создано {{ importStatus.lastSummary?.created || 0 }}
            · обновлено {{ importStatus.lastSummary?.updated || 0 }}<ng-container *ngIf="importStatus.lastSummary?.errors"> · ошибок {{ importStatus.lastSummary.errors }}</ng-container>
          </span>
        </div>
        <span class="import-status" *ngIf="isKz() && importStatus && !importStatus.running && importStatus.lastFinishedAt">
          Обновлено {{ formatImportTime(importStatus.lastFinishedAt) }}<ng-container *ngIf="importStatus.lastSummary"> · создано {{ importStatus.lastSummary.created }}, обновлено {{ importStatus.lastSummary.updated }}</ng-container>
        </span>
        <!-- СК-Фармация импортируется той же кнопкой «Обновить тендеры» (см. onImportTenders); своя полоса прогресса ниже -->
        <div class="import-progress" *ngIf="isKz() && skImportStatus?.running">
          <div class="import-bar"><div class="import-bar-fill" [style.width.%]="skImportPct()"></div></div>
          <span class="import-progress-text">
            СК-Ф стр. {{ skImportStatus.lastSummary?.pagesRead || 0 }}/{{ skImportStatus.lastSummary?.maxPages || '…' }}
            · получено {{ skImportStatus.lastSummary?.fetched || 0 }}
            · подходящих {{ skImportStatus.lastSummary?.matched || 0 }}
            · создано {{ skImportStatus.lastSummary?.created || 0 }}
            · обновлено {{ skImportStatus.lastSummary?.updated || 0 }}<ng-container *ngIf="skImportStatus.lastSummary?.errors"> · ошибок {{ skImportStatus.lastSummary.errors }}</ng-container>
          </span>
        </div>
        <span class="counter" *ngIf="filteredTenders.length">Найдено: {{ filteredTenders.length }} записей</span>
      </div>

      <form *ngIf="showTenderForm" [formGroup]="tenderForm" (ngSubmit)="onSaveTender()" class="edit-form">
        <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
        <label>Номер тендера *<input formControlName="tenderNumber" [class.input-error]="validationErrors.tenderNumber" /><span class="field-error" *ngIf="validationErrors.tenderNumber">{{ validationErrors.tenderNumber }}</span></label>
        <label>Учреждение
          <app-searchable-select
            [items]="facilities"
            labelField="name"
            [subLabelFields]="['inn', 'address']"
            [searchFields]="['name', 'inn']"
            placeholder="— выберите учреждение —"
            [value]="tenderForm.value.facilityId"
            (valueChange)="tenderForm.patchValue({facilityId: $event})">
          </app-searchable-select>
        </label>
        <div class="dims-row">
          <label>Статус
            <select formControlName="status">
              <option value="DRAFT">Подготовка</option>
              <option value="ACTIVE">Приём заявок</option>
              <option value="COMPLETED">Завершён</option>
            </select>
          </label>
          <label>Способ закупки *
            <select formControlName="purchaseType" [class.input-error]="validationErrors.purchaseType">
              <option value="">— не выбран —</option>
              <option value="ELECTRONIC_AUCTION">Электронный аукцион</option>
              <option value="PAPER_TENDER">Конкурс с подачей документов</option>
            </select>
            <span class="field-error" *ngIf="validationErrors.purchaseType">{{ validationErrors.purchaseType }}</span>
          </label>
          <label *ngIf="isKz()">Площадка
            <select formControlName="platform">
              <option value="">— по рынку (Госзакуп) —</option>
              <option value="GOSZAKUP">Госзакуп</option>
              <option value="SK_PHARMACY">СК-Фармация</option>
            </select>
          </label>
        </div>
        <div class="dims-row">
          <label>Дата публикации<input type="date" formControlName="publishDate" [class.input-error]="validationErrors.publishDate" /><span class="field-error" *ngIf="validationErrors.publishDate">{{ validationErrors.publishDate }}</span></label>
          <label>Окончание приёма заявок *<input type="date" formControlName="deadline" [class.input-error]="validationErrors.deadline" /><span class="field-error" *ngIf="validationErrors.deadline">{{ validationErrors.deadline }}</span></label>
        </div>
        <label>Описание<textarea formControlName="description" rows="3"></textarea></label>
        <label>Адрес поставки<input formControlName="deliveryAddress" [class.input-error]="validationErrors.deliveryAddress" /><span class="field-error" *ngIf="validationErrors.deliveryAddress">{{ validationErrors.deliveryAddress }}</span></label>
        <div class="dims-row">
          <label>Фамилия<input formControlName="contactLastName" [class.input-error]="validationErrors.contactLastName" /><span class="field-error" *ngIf="validationErrors.contactLastName">{{ validationErrors.contactLastName }}</span></label>
          <label>Имя<input formControlName="contactFirstName" [class.input-error]="validationErrors.contactFirstName" /><span class="field-error" *ngIf="validationErrors.contactFirstName">{{ validationErrors.contactFirstName }}</span></label>
          <label>Отчество<input formControlName="contactMiddleName" [class.input-error]="validationErrors.contactMiddleName" /><span class="field-error" *ngIf="validationErrors.contactMiddleName">{{ validationErrors.contactMiddleName }}</span></label>
        </div>
        <div class="dims-row">
          <label>Телефон<input formControlName="contactPhone" [class.input-error]="validationErrors.contactPhone" /><span class="field-error" *ngIf="validationErrors.contactPhone">{{ validationErrors.contactPhone }}</span></label>
          <label>Эл. почта<input formControlName="contactEmail" [class.input-error]="validationErrors.contactEmail" /><span class="field-error" *ngIf="validationErrors.contactEmail">{{ validationErrors.contactEmail }}</span></label>
        </div>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showTenderForm = false">Отмена</button>
        </div>
      </form>

      <div *ngIf="filteredTenders.length === 0 && !showTenderForm" class="empty">Нет данных</div>

      <div class="tender-card" *ngFor="let t of filteredTenders" (click)="onOpen(t)"
           [class.tender-overdue]="t.status === 'ACTIVE' && isOverdue(t.deadline)"
           [class.tender-urgent]="t.status === 'ACTIVE' && !isOverdue(t.deadline) && isUrgent(t.deadline)">
        <div class="tender-card-header">
          <div class="tender-meta">
            <span class="tender-number">&#8470; {{ t.tenderNumber }}</span>
            <a *ngIf="!isDemoTender(t.tenderNumber)" class="eis-link" [href]="eisLink(t)" target="_blank" rel="noopener" (click)="$event.stopPropagation()" [title]="'Открыть в ' + procurementPortalLabel(t) + ' ' + procurementPortalHost(t)">
              <svg lucideIcon="external-link" [size]="12"></svg> {{ procurementPortalLabel(t) }}
            </a>
            <span *ngIf="isDemoTender(t.tenderNumber)" class="demo-badge" title="Контрольный пример, не существует в реестре закупок">Демо</span>
            <span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>
            <span *ngIf="stageByTenderId[t.id]" [style]="stageChipStyle(stageByTenderId[t.id])">{{ stageLabel(stageByTenderId[t.id]) }}</span>
            <span class="purchase-type">{{ getPurchaseTypeLabel(t.purchaseType) }}</span>
          </div>
          <div class="tender-price">{{ t.totalCost | money }}</div>
        </div>
        <div class="tender-card-title">{{ t.description || 'Без описания' }}</div>
        <div class="lot-mini-list" *ngIf="t.lots?.length">
          <span class="lot-mini" *ngFor="let l of t.lots.slice(0, 3)" [title]="l.requiredSpec || l.equipName">{{ l.equipName }}</span>
          <span class="lot-mini lot-mini-more" *ngIf="t.lots.length > 3">+{{ t.lots.length - 3 }} ещё</span>
        </div>
        <div class="tender-card-details">
          <div class="detail-row">
            <div class="detail"><span class="detail-label">Заказчик</span><span>{{ t.facility?.name || '—' }}</span></div>
            <div class="detail"><span class="detail-label">Способ закупки</span><span>{{ getPurchaseTypeLabel(t.purchaseType) }}</span></div>
          </div>
          <div class="detail-row" *ngIf="t.region || t.customerName">
            <div class="detail"><span class="detail-label">Регион</span><span>{{ t.region || '—' }}</span></div>
            <div class="detail"><span class="detail-label">Госзаказчик</span><span>{{ t.customerName || '—' }}</span></div>
          </div>
          <div class="detail-row">
            <div class="detail"><span class="detail-label">Дата публикации</span><span>{{ formatDate(t.publishDate) }}</span></div>
            <div class="detail"><span class="detail-label">Окончание подачи заявок</span><span class="deadline" [class.overdue]="isOverdue(t.deadline)">{{ formatDate(t.deadline) }}</span></div>
          </div>
          <div class="detail-row">
            <div class="detail"><span class="detail-label">Контактное лицо</span><span>{{ formatContact(t) }}</span></div>
            <div class="detail"><span class="detail-label">Лотов</span><span>{{ t.lots?.length || 0 }}</span></div>
          </div>
        </div>
        <div class="tender-card-actions">
          <button class="btn btn-edit" (click)="onEditTender(t); $event.stopPropagation()">Редактировать</button>
          <button class="btn btn-delete" (click)="onDeleteTender(t.id); $event.stopPropagation()">Удалить</button>
        </div>
      </div>
    </ng-container>

    <!-- ========== ДЕТАЛИ ТЕНДЕРА ========== -->
    <ng-container *ngIf="selectedTender">
      <app-bulk-price-modal [tenderId]="bulkPriceTenderId" (close)="bulkPriceTenderId = null; loadPriceRequests()"></app-bulk-price-modal>
      <app-offer-comparison [tenderId]="compareTenderId" (close)="compareTenderId = null"></app-offer-comparison>

      <button class="btn btn-back" (click)="onBack()">&#8592; Назад к списку</button>

      <div class="tender-info">
        <h2>Тендер &#8470; {{ selectedTender.tenderNumber }}
          <a *ngIf="!isDemoTender(selectedTender.tenderNumber)" class="eis-link-h2" [href]="eisLink(selectedTender)" target="_blank" rel="noopener" [title]="'Открыть на ' + procurementPortalHost(selectedTender)">
            <svg lucideIcon="external-link" [size]="14"></svg> Открыть в {{ procurementPortalLabel(selectedTender) }}
          </a>
          <span *ngIf="isDemoTender(selectedTender.tenderNumber)" class="demo-badge-h2" title="Контрольный пример, не существует в реестре закупок">Контрольный пример</span>
        </h2>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">Заказчик</span><span>{{ selectedTender.facility?.name || selectedTender.customerName || '—' }}</span></div>
          <div class="info-item" *ngIf="selectedTender.region"><span class="info-label">Регион</span><span>{{ selectedTender.region }}</span></div>
          <div class="info-item"><span class="info-label">Статус</span><span class="badge" [class]="'badge-' + selectedTender.status">{{ getStatusLabel(selectedTender.status) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.purchaseType"><span class="info-label">Способ закупки</span><span>{{ getPurchaseTypeLabel(selectedTender.purchaseType) }}</span></div>
          <div class="info-item"><span class="info-label">Начальная цена (по лотам)</span><span class="price">{{ selectedTender.totalCost | money }}</span></div>
          <div class="info-item" *ngIf="selectedTender.publishDate"><span class="info-label">Дата публикации</span><span>{{ formatDate(selectedTender.publishDate) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.deadline"><span class="info-label">Окончание приёма заявок</span><span class="deadline" [class.overdue]="isOverdue(selectedTender.deadline)">{{ formatDate(selectedTender.deadline) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.deliveryAddress"><span class="info-label">Адрес поставки</span><span>{{ selectedTender.deliveryAddress }}</span></div>
          <div class="info-item" *ngIf="hasContactPerson()"><span class="info-label">Контактное лицо</span><span>{{ formatContact(selectedTender) }}</span></div>
          <div class="info-item" *ngIf="selectedTender.contactPhone"><span class="info-label">Телефон</span><span>{{ selectedTender.contactPhone }}</span></div>
          <div class="info-item" *ngIf="selectedTender.contactEmail"><span class="info-label">Эл. почта</span><span>{{ selectedTender.contactEmail }}</span></div>
        </div>
        <p *ngIf="selectedTender.description" class="info-desc"><strong>Описание:</strong> {{ selectedTender.description }}</p>
      </div>

      <h3>Лоты тендера</h3>

      <app-tender-lots
        [tender]="selectedTender" [lots]="lots" [priceRequests]="priceRequests" [equipmentTypes]="equipmentTypesList"
        [applyItems]="allApplyItems"
        (lotsChanged)="loadLots()"
        (priceRequestsChanged)="loadPriceRequests()"
        (bulkPriceRequested)="bulkPriceTenderId = selectedTender.id"
        (matchRequested)="onMatchRequested($event)">
      </app-tender-lots>

      <app-smart-match
        *ngIf="matchLotId !== null"
        [lotId]="matchLotId"
        [lotNumber]="matchLotNumber || 0"
        [proposedEquipmentId]="matchLotProposedId()"
        (proposedChanged)="loadLots()"
        (close)="closeMatch()"
        (requestPrice)="onSmartMatchRequest($event)">
      </app-smart-match>

      <!-- ========== ЗАПРОСЫ КП ========== -->
      <section *ngIf="priceRequests.length > 0" class="pr-section">
        <div class="pr-section-head">
          <h3>Запросы КП</h3>
          <button class="btn btn-line" (click)="checkKpResponses()">Проверить ответы</button>
          <button class="btn btn-line" *ngIf="canCompare" (click)="compareTenderId = selectedTender.id">Сравнить предложения</button>
        </div>
        <div *ngFor="let pr of priceRequests" class="pr-card" [class.expanded]="pr._expanded" [class.pr-accepted]="pr.status === 'ACCEPTED'">
          <header class="pr-header" (click)="togglePr(pr)">
            <div class="pr-header-main">
              <strong>{{ pr.distributor?.name }}</strong>
              <span class="badge badge-pr-{{ pr.status }}">{{ getPrStatusLabel(pr.status) }}</span>
            </div>
            <div class="pr-header-meta">
              <small *ngIf="pr.sentAt">отправлено {{ formatDate(pr.sentAt) }}</small>
              <small class="counter">{{ pr.items?.length || 0 }} позиций</small>
              <span class="chevron">{{ pr._expanded ? '▲' : '▼' }}</span>
            </div>
          </header>
          <div *ngIf="pr.status === 'DECLINED' && pr.note" class="pr-decline-reason">
            <strong>Причина отказа:</strong> {{ declineReason(pr.note) }}
          </div>
          <div *ngIf="pr._expanded" class="pr-body">
            <div class="pr-markup-calc">
              <span class="pmc-label">Калькулятор наценки:</span>
              <button *ngFor="let p of markupPresets" type="button"
                      [class.active]="(pr._markup ?? 25) === p"
                      (click)="pr._markup = p">{{ p }}%</button>
              <label class="pmc-custom">
                <span>Своё:</span>
                <input type="number" min="0" max="200" step="1"
                       [ngModel]="pr._markup ?? 25"
                       (ngModelChange)="pr._markup = $event"
                       [ngModelOptions]="{standalone: true}" />
                <span>%</span>
              </label>
            </div>
            <div class="table-scroll">
            <table class="pr-items">
              <thead><tr>
                <th>Лот</th><th>Модель</th><th>Кол-во</th>
                <th>Цена ответа ({{ market.symbol() }})</th>
                <th>Предл. цена при {{ pr._markup ?? 25 }}%</th>
                <th>Маржа</th>
                <th>Заметка</th>
              </tr></thead>
              <tbody>
                <tr *ngFor="let it of pr.items">
                  <td>{{ it.tenderLot?.lotNumber }} — {{ it.tenderLot?.equipName }}</td>
                  <td>{{ it.medEquipment?.name || '— по лоту' }}</td>
                  <td>{{ it.requestedQuantity }}</td>
                  <td><input type="number" min="0" step="0.01" [(ngModel)]="it._editPrice" [ngModelOptions]="{standalone: true}" /></td>
                  <td class="pmc-calc">{{ markedPrice(it, pr) | money }}
                    <small *ngIf="markedClamped(it, pr)" title="Ограничено максимумом лота">⚠ потолок</small>
                  </td>
                  <td class="pmc-profit">+{{ markedProfit(it, pr) | money }}</td>
                  <td><input [(ngModel)]="it._editNote" [ngModelOptions]="{standalone: true}" /></td>
                </tr>
              </tbody>
            </table>
            </div>
            <div class="pr-actions">
              <button class="btn btn-save" (click)="saveResponses(pr)">Сохранить ответы</button>
              <button *ngIf="pr.status === 'SENT' || pr.status === 'CREATED'" class="btn btn-line" (click)="resendPr(pr)">↻ Переслать</button>
              <button *ngIf="pr.status === 'RESPONDED'" class="btn btn-accept-pr cta-pulse" (click)="acceptPr(pr)">Принять КП</button>
              <button *ngIf="pr.status === 'ACCEPTED' || pr.status === 'RESPONDED'" class="btn btn-create-apply cta-pulse" (click)="createApplyFromPr(pr)">
                <svg lucideIcon="clipboard-list" [size]="14"></svg> Сформировать заявку
              </button>
              <button *ngIf="pr.status === 'RESPONDED' || pr.status === 'ACCEPTED' || pr.status === 'DECLINED'" class="btn btn-close-pr" (click)="closePr(pr)">Закрыть запрос</button>
              <button class="btn btn-delete" (click)="deletePr(pr.id)">Удалить запрос</button>
            </div>
          </div>
        </div>
      </section>
    </ng-container>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: var(--text); }
    h3 { margin: 24px 0 12px; font-size: 17px; color: var(--text); }
    .subtitle { color: var(--text-muted); font-size: 13px; margin: 4px 0 16px; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .counter { color: var(--text-muted); font-size: 13px; }
    .empty { color: var(--text-muted); font-size: 14px; padding: 32px 0; text-align: center; }

    .tender-card { border: 1px solid var(--border); border-radius: 10px; padding: 16px 20px; margin-bottom: 12px; cursor: pointer; transition: box-shadow 0.2s, border-color 0.2s; }
    .tender-card:hover { box-shadow: var(--shadow); border-color: var(--border); }
    .tender-card.tender-urgent { border-left: 4px solid var(--warn); }
    .tender-card.tender-overdue { border-left: 4px solid var(--danger); background: var(--surface-2); }
    .eis-link { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; padding: 2px 8px; background: var(--surface-2); color: var(--accent); border-radius: 4px; text-decoration: none; font-weight: 500; vertical-align: middle; }
    .eis-link:hover { background: color-mix(in srgb, var(--text) 15%, transparent); }
    .eis-link-h2 { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; padding: 4px 10px; background: var(--surface-2); color: var(--accent); border-radius: 6px; text-decoration: none; font-weight: 500; margin-left: 12px; }
    .eis-link-h2:hover { background: color-mix(in srgb, var(--text) 15%, transparent); }
    .demo-badge { display: inline-flex; align-items: center; font-size: 10px; padding: 2px 6px; background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn); border-radius: 4px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
    .demo-badge-h2 { display: inline-flex; align-items: center; font-size: 12px; padding: 4px 10px; background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn); border-radius: 6px; font-weight: 600; margin-left: 12px; }
    .tender-card-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
    .tender-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .tender-number { font-weight: 600; color: var(--accent); font-size: 15px; }
    .tender-price { font-size: 18px; font-weight: 700; color: var(--text); white-space: nowrap; }
    .purchase-type { font-size: 12px; color: var(--text-muted); background: var(--surface-2); padding: 2px 8px; border-radius: 4px; }
    .tender-card-title { font-size: 14px; color: var(--text); margin-bottom: 12px; line-height: 1.5; }
    .tender-card-details { margin-bottom: 12px; }
    .detail-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px; }
    .detail { display: flex; flex-direction: column; }
    .detail-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; margin-bottom: 2px; }
    .detail span:not(.detail-label) { font-size: 14px; }
    .price { font-weight: 600; color: var(--text); }
    .deadline { font-weight: 500; }
    .deadline.overdue { color: var(--danger); }
    .tender-card-actions { display: flex; gap: 8px; border-top: 1px solid var(--border); padding-top: 12px; }

    .tender-info { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: 20px; margin-bottom: 20px; }
    .tender-info h2 { margin-bottom: 16px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 24px; }
    .info-item { display: flex; flex-direction: column; }
    .info-label { font-size: 11px; color: var(--text-muted); text-transform: uppercase; margin-bottom: 2px; }
    .info-item span:not(.info-label) { font-size: 14px; }
    .info-desc { margin-top: 16px; font-size: 14px; color: var(--text); line-height: 1.5; }

    table { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 14px; }
    th { background: var(--surface-2); color: var(--text-muted); font-weight: 600; }
    tr:hover { background: var(--surface-2); }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: var(--accent); color: var(--accent-contrast); }
    .btn-line { background: var(--surface); color: var(--text); border: 1px solid var(--border); }
    .btn-save { background: var(--accent); color: var(--accent-contrast); }
    .btn-cancel { background: var(--surface-2); color: var(--text); margin-left: 8px; }
    .btn-edit { background: var(--warn); color: var(--accent-contrast); margin-right: 4px; }
    .btn-delete { background: var(--danger); color: var(--accent-contrast); }
    .btn-back { background: var(--text-muted); color: var(--accent-contrast); margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: var(--surface-2); color: var(--text); }
    .badge-ACTIVE { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .badge-COMPLETED { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
    .badge-CANCELLED { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); }
    .import-status { color: var(--text-muted); font-size: 12.5px; margin-left: 10px; }
    .import-progress { display: inline-flex; align-items: center; gap: 8px; margin-left: 10px; }
    .import-bar { width: 150px; height: 6px; background: var(--surface-2); border-radius: 3px; overflow: hidden; }
    .import-bar-fill { height: 100%; background: var(--accent); border-radius: 3px; transition: width .5s ease; }
    .import-progress-text { color: var(--text); font-size: 12.5px; white-space: nowrap; }
    .pr-section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; }
    .lot-mini-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0 2px; }
    .lot-mini { background: var(--surface-2); color: var(--text); border-radius: 10px; padding: 2px 9px; font-size: 12px;
                max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .lot-mini-more { background: var(--surface-2); color: var(--text-muted); }
    .badge-pr-CREATED { background: var(--surface-2); color: var(--text); }
    .badge-pr-SENT { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .badge-pr-RESPONDED { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn); }
    .badge-pr-ACCEPTED { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); font-weight: 700; }
    .badge-pr-DECLINED { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); font-weight: 700; }
    .badge-pr-CLOSED { background: var(--surface-2); color: var(--text-muted); }
    .pr-decline-reason { padding: 7px 14px; background: var(--surface-2); color: var(--danger); font-size: 12px; border-top: 1px solid var(--border); }
    .pr-card.pr-accepted { border-color: var(--success); box-shadow: 0 0 0 1px var(--success); }
    .pr-markup-calc { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 10px 12px; background: var(--surface-2); border-radius: 6px; margin-bottom: 12px; border: 1px dashed var(--border); }
    .pmc-label { font-size: 13px; font-weight: 600; color: var(--text); margin-right: 4px; }
    .pr-markup-calc button { padding: 5px 11px; border: 1px solid var(--border); background: var(--surface); border-radius: 4px; cursor: pointer; font-size: 12px; color: var(--text); min-width: 48px; }
    .pr-markup-calc button:hover { background: var(--surface-2); }
    .pr-markup-calc button.active { background: var(--accent); color: var(--accent-contrast); border-color: var(--accent); font-weight: 600; }
    .pmc-custom { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: var(--text-muted); margin-left: 6px; }
    .pmc-custom input { width: 60px; padding: 4px 6px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; text-align: right; }
    .pmc-calc { font-weight: 600; color: var(--accent); }
    .pmc-calc small { display: block; color: var(--warn); font-size: 10px; font-weight: 400; }
    .pmc-profit { color: var(--success); font-weight: 600; }
    .edit-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 700px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; font-family: inherit; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: var(--danger); font-size: 12px; margin-top: 2px; }
    .input-error { border-color: var(--danger) !important; }
    .error-banner { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger); padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
    .pr-section { margin-top: 24px; }
    .pr-card { border: 1px solid var(--border); border-radius: 6px; margin-bottom: 8px; background: var(--surface); }
    .pr-card.expanded { border-color: var(--accent); }
    .pr-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; cursor: pointer; }
    .pr-header:hover { background: var(--surface-2); }
    .pr-header-main { display: flex; align-items: center; gap: 10px; }
    .pr-header-meta { display: flex; align-items: center; gap: 12px; color: var(--text-muted); font-size: 13px; }
    .chevron { color: var(--text-muted); font-size: 11px; }
    .pr-body { padding: 12px 16px; border-top: 1px solid var(--border); background: var(--surface-2); }
    .pr-items { width: 100%; border-collapse: collapse; }
    .pr-items th { background: var(--surface-2); padding: 6px 10px; font-size: 12px; color: var(--text-muted); }
    .pr-items td { padding: 6px 10px; border-bottom: 1px solid var(--border); font-size: 13px; }
    .pr-items input { width: 100%; padding: 4px 8px; border: 1px solid var(--border); border-radius: 3px; font-size: 13px; }
    .pr-actions { display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end; }
    .btn-close-pr { background: var(--text-muted); color: var(--accent-contrast); padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-accept-pr { background: var(--success); color: var(--accent-contrast); padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 600; }
    .btn-accept-pr:hover { background: var(--success); }
    .btn-create-apply { background: var(--accent); color: var(--accent-contrast); padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .btn-create-apply:hover { background: var(--accent); }
    @keyframes cta-pulse-anim { 0%, 100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45); } 50% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); } }
    .cta-pulse { animation: cta-pulse-anim 1.8s infinite; }

    @media (max-width: 900px) {
      .detail-row { grid-template-columns: 1fr; gap: 4px; }
      .info-grid { grid-template-columns: 1fr; }
      .tender-card { padding: 14px 16px; }
      .tender-card-actions { flex-wrap: wrap; }
    }
  `]
})
export class TendersComponent {
  tenders: any[] = [];
  filteredTenders: any[] = [];
  filterQuery = '';
  filterStatus = '';
  filterStage = '';
  filterPlatform = '';
  stageByTenderId: { [id: number]: string } = {};
  filterRegion = '';
  protected readonly NO_REGION = '__none__';
  readonly REGIONS: string[] = KZ_REGIONS;
  filterFacilityId: number | null = null;
  filterDeadlineFrom = '';
  filterDeadlineTo = '';

  get filtersState(): TendersFilters {
    return { query: this.filterQuery, status: this.filterStatus, sortMode: this.sortMode, stage: this.filterStage,
      platform: this.filterPlatform, facilityId: this.filterFacilityId, deadlineFrom: this.filterDeadlineFrom,
      deadlineTo: this.filterDeadlineTo, region: this.filterRegion };
  }
  onFiltersChange(f: TendersFilters) {
    this.filterQuery = f.query; this.filterStatus = f.status; this.sortMode = f.sortMode as any; this.filterStage = f.stage;
    this.filterPlatform = f.platform; this.filterFacilityId = f.facilityId; this.filterDeadlineFrom = f.deadlineFrom;
    this.filterDeadlineTo = f.deadlineTo; this.filterRegion = f.region;
    this.applyTendersFilter();
  }
  allApplyItems: any[] = [];
  facilities: any[] = [];
  selectedTender: any = null;
  lots: any[] = [];
  matchLotId: number | null = null;
  matchLotNumber: number | null = null;

  // Лоты, выбор лотов и панели «Подбор»/«КП» живут в app-tender-lots
  equipmentTypesList: any[] = [];

  showTenderForm = false;
  editingTenderId: number | null = null;
  tenderForm = new FormGroup({
    tenderNumber: new FormControl(''),
    facilityId: new FormControl<number | null>(null),
    status: new FormControl('DRAFT'),
    purchaseType: new FormControl(''),
    deadline: new FormControl(''),
    publishDate: new FormControl(''),
    description: new FormControl(''),
    deliveryAddress: new FormControl(''),
    contactLastName: new FormControl(''),
    contactFirstName: new FormControl(''),
    contactMiddleName: new FormControl(''),
    contactPhone: new FormControl(''),
    contactEmail: new FormControl(''),
    platform: new FormControl('')
  });

  // относится к форме ТЕНДЕРА (форма лота живёт в app-tender-lots со своей копией)
  validationErrors: any = {};

  // ===== Запросы КП =====
  priceRequests: any[] = [];

  // Массовый подбор КП по тендеру
  bulkPriceTenderId: number | null = null;

  // Сравнение предложений (модалка offer-comparison)
  compareTenderId: number | null = null;

  get canCompare(): boolean {
    const withPrice = (this.priceRequests || []).filter((pr: any) =>
      (pr.items || []).some((it: any) => it.responsePrice != null));
    return withPrice.length >= 2;
  }

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private route: ActivatedRoute,
              private router: Router,
              private notify: NotificationService, private confirm: ConfirmService, public market: MarketService) {
    this.loadTenders();
    this.refreshImportStatus();
    this.api.getFacilities().subscribe({ next: data => { this.facilities = data; this.cdr.detectChanges(); } });
    this.api.getEquipmentTypes().subscribe(ts => { this.equipmentTypesList = ts || []; this.cdr.detectChanges(); });
    this.api.getAllApplyItems().subscribe({
      next: items => { this.allApplyItems = items || []; },
      error: () => { this.allApplyItems = []; }
    });

    // Поддержка перехода через ?openId=...
    this.route.queryParams.subscribe(params => {
      const openId = params['openId'];
      if (openId) {
        this.api.getById('tenders', +openId).subscribe((t: any) => {
          this.onOpen(t);
        });
      }
    });
  }

  getStatusLabel(status: string): string {
    const labels: any = { DRAFT: 'Подготовка', ACTIVE: 'Приём заявок', COMPLETED: 'Завершён', CANCELLED: 'Отменён' };
    return labels[status] || status;
  }

  getPurchaseTypeLabel(type: string): string {
    if (type === 'ELECTRONIC_AUCTION') return 'Электронный аукцион';
    if (type === 'PAPER_TENDER') return 'Конкурс с подачей документов';
    return type || '—';
  }

  getPrStatusLabel(s: string): string {
    return ({ CREATED: 'Создан', SENT: 'Отправлен', RESPONDED: 'Ответ получен', ACCEPTED: 'Принят', DECLINED: 'Отказ', CLOSED: 'Закрыт' } as any)[s] || s;
  }

  /** Причина отказа для бейджа DECLINED — начало письма поставщика (сам ответ идёт сверху). */
  declineReason(note: string | null): string {
    const t = (note || '').trim();
    return t.length > 220 ? t.slice(0, 220) + '…' : t;
  }

  acceptPr(pr: any) {
    this.confirm.ask('Принять КП?', 'Позиции из этого КП попадут в авто-сборку заявки как приоритетные.', { confirmLabel: 'Принять' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.acceptPriceRequest(pr.id).subscribe({
          next: () => { this.notify.success('КП принят'); this.loadPriceRequests(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка')
        });
      });
  }

  createApplyFromPr(_pr: any) {
    const tenderId = this.selectedTender?.id;
    if (!tenderId) { this.notify.error('Тендер не определён'); return; }
    this.api.getApplies().subscribe(applies => {
      const existing = (applies || []).find((a: any) => a.tender?.id === tenderId && a.status === 'DRAFT');
      const navigate = (applyId: number) => {
        this.notify.success('Заявка собрана из принятых КП');
        this.router.navigate(['/applies'], { queryParams: { openId: applyId } });
      };
      if (existing) {
        this.api.autoFillApply(existing.id).subscribe({
          next: () => navigate(existing.id),
          error: () => navigate(existing.id)
        });
      } else {
        this.api.create('applies', { tenderId, status: 'DRAFT' }).subscribe({
          next: (apply: any) => {
            this.api.autoFillApply(apply.id).subscribe({
              next: () => navigate(apply.id),
              error: () => navigate(apply.id)
            });
          },
          error: err => this.notify.error(err.error?.message || 'Не удалось создать заявку')
        });
      }
    });
  }

  eisLink(t: any): string { return this.market.portalLink(t?.tenderNumber, t?.platform); }
  procurementPortalLabel(t?: any): string { return this.market.portalLabel(t?.platform); }
  procurementPortalHost(t?: any): string { return this.market.portalHost(t?.platform); }

  isDemoTender(tenderNumber: string): boolean {
    return !!tenderNumber && tenderNumber.startsWith('DEMO-');
  }

  markupPresets = [0, 10, 15, 20, 25, 30, 40, 50];

  private calcMarked(it: any, pr: any): { price: number; clamped: boolean } {
    const proc = Number(it._editPrice ?? it.responsePrice ?? 0);
    const markup = Number(pr._markup ?? 25);
    let price = proc * (1 + markup / 100);
    const maxCost = Number(it.tenderLot?.maxCost ?? 0);
    let clamped = false;
    if (maxCost > 0 && price > maxCost) {
      price = maxCost;
      clamped = true;
    }
    return { price, clamped };
  }

  markedPrice(it: any, pr: any): number { return this.calcMarked(it, pr).price; }
  markedClamped(it: any, pr: any): boolean { return this.calcMarked(it, pr).clamped; }
  markedProfit(it: any, pr: any): number {
    const proc = Number(it._editPrice ?? it.responsePrice ?? 0);
    return this.calcMarked(it, pr).price - proc;
  }

  private daysLeft(deadline: string): number {
    if (!deadline) return Infinity;
    return Math.ceil((new Date(deadline).getTime() - new Date().getTime()) / (1000 * 60 * 60 * 24));
  }
  isUrgent(deadline: string): boolean { return this.daysLeft(deadline) <= 7; }

  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatContact(t: any): string {
    const parts = [t?.contactLastName, t?.contactFirstName, t?.contactMiddleName].filter(Boolean);
    return parts.length ? parts.join(' ') : '—';
  }

  isOverdue(d: string): boolean {
    if (!d) return false;
    return new Date(d) < new Date();
  }

  loadTenders() {
    this.api.getTenders().subscribe({
      next: data => { this.tenders = data; this.applyTendersFilter(); this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки тендеров: ' + (err.error?.message || err.message))
    });
    this.api.getTenderWorkStages().subscribe({
      next: (m) => { this.stageByTenderId = m || {}; this.applyTendersFilter(); this.cdr.detectChanges(); },
      error: () => { /* стадии не критичны — список работает без них */ }
    });
  }

  stageLabel(code: string): string {
    return ({ REQUESTED: 'Запрос отправлен', PRICED: 'Есть цены', WINNER_SELECTED: 'Победитель' } as any)[code] || '';
  }

  // Инлайн-стиль (НЕ CSS-класс: style-бюджет tenders.component исчерпан — §12 CLAUDE.md).
  stageChipStyle(code: string): string {
    const c: { [k: string]: string } = {
      REQUESTED: 'background:#fef3c7;color:#92400e',
      PRICED: 'background:#dbeafe;color:#1e40af',
      WINNER_SELECTED: 'background:#d1fae5;color:#065f46',
    };
    return (c[code] || 'background:#e5e7eb;color:#374151')
      + ';display:inline-block;padding:2px 8px;border-radius:10px;font-size:11px;font-weight:600;margin-left:6px';
  }

  importing = false;

  isKz(): boolean { return this.market.value === 'KZ'; }

  /** Регион для импорта: выбранный в фильтре, кроме спец-значения «Регион не указан». */
  importRegion(): string | undefined {
    return this.filterRegion && this.filterRegion !== this.NO_REGION ? this.filterRegion : undefined;
  }

  /** Единая кнопка «Обновить тендеры»: без фильтра площадки — обе площадки параллельно, иначе только выбранная. */
  onImportTenders() {
    if (this.filterPlatform === 'GOSZAKUP') { this.onImportKz(); }
    else if (this.filterPlatform === 'SK_PHARMACY') { this.onImportSk(); }
    else { this.onImportKz(); this.onImportSk(); }   // «Все площадки» → обе
  }

  /** Занята, пока идёт импорт той площадки(-ок), которую кнопка запустит для текущего фильтра. */
  importBusy(): boolean {
    const gos = this.importing || !!this.importStatus?.running;
    const sk = this.skImporting || !!this.skImportStatus?.running;
    if (this.filterPlatform === 'GOSZAKUP') return gos;
    if (this.filterPlatform === 'SK_PHARMACY') return sk;
    return gos || sk;
  }

  importButtonLabel(): string {
    if (this.importBusy()) return 'Обновление…';
    if (this.filterPlatform === 'SK_PHARMACY') return 'Обновить СК-Фармация';
    const base = this.filterPlatform === 'GOSZAKUP' ? 'Обновить Госзакуп' : 'Обновить тендеры';
    return base + (this.importRegion() ? ' — ' + this.importRegion() : '');
  }

  importButtonTitle(): string {
    const reg = this.importRegion();
    if (this.filterPlatform === 'SK_PHARMACY') return 'Импорт медизделий с портала СК-Фармации (fms.ecc.kz)';
    if (this.filterPlatform === 'GOSZAKUP') return reg ? 'Импорт с goszakup только по региону: ' + reg : 'Импорт всей ленты goszakup';
    return 'Обновить обе площадки: goszakup' + (reg ? ' (регион ' + reg + ')' : '') + ' + СК-Фармация (fms.ecc.kz)';
  }

  onImportKz() {
    this.importing = true;
    // POST стартует импорт в фоне и сразу возвращает статус — дальше поллим прогресс
    this.api.importKzTenders(this.importRegion()).subscribe({
      next: (st: any) => {
        this.importStatus = st;
        if (st?.lastSummary?.enabled === false) {
          this.importing = false;
          this.notify.error(st.lastSummary.message || 'Импорт выключен: не настроен токен goszakup');
        } else {
          this.startImportPolling();
        }
        this.cdr.detectChanges();
      },
      error: err => {
        this.importing = false;
        this.notify.error('Ошибка импорта: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  // --- Импорт СК-Фармации (fms.ecc.kz) — отдельный поток, зеркалит goszakup-импорт ---
  skImporting = false;
  skImportStatus: any = null;
  private skImportPollTimer: any = null;

  onImportSk() {
    this.skImporting = true;
    this.api.importSkTenders().subscribe({
      next: (st: any) => { this.skImportStatus = st; this.startSkImportPolling(); this.cdr.detectChanges(); },
      error: err => {
        this.skImporting = false;
        this.notify.error('Ошибка импорта СК-Фармации: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  private startSkImportPolling() {
    if (this.skImportPollTimer) return;
    this.skImportPollTimer = setInterval(() => {
      this.api.getSkImportStatus().subscribe({
        next: st => {
          const wasRunning = this.skImportStatus?.running;
          this.skImportStatus = st;
          if (!st.running) {
            clearInterval(this.skImportPollTimer); this.skImportPollTimer = null; this.skImporting = false;
            if (wasRunning) {
              if (st.lastSummary?.message) this.notify.success('СК-Фармация: ' + st.lastSummary.message);
              this.loadTenders();
            }
          }
          this.cdr.detectChanges();
        },
        error: () => { clearInterval(this.skImportPollTimer); this.skImportPollTimer = null; this.skImporting = false; this.cdr.detectChanges(); }
      });
    }, 2500);
  }

  skImportPct(): number {
    const s = this.skImportStatus?.lastSummary;
    if (!s?.maxPages) return 5;
    return Math.max(5, Math.min(100, Math.round(100 * (s.pagesRead || 0) / s.maxPages)));
  }

  sortMode: 'published' | 'deadline' = 'published';

  applyTendersFilter() {
    const q = (this.filterQuery || '').toLowerCase();
    const from = this.filterDeadlineFrom ? new Date(this.filterDeadlineFrom) : null;
    const to = this.filterDeadlineTo ? new Date(this.filterDeadlineTo) : null;
    this.filteredTenders = this.tenders.filter(t => {
      if (q) {
        const num = (t.tenderNumber || '').toLowerCase();
        const desc = (t.description || '').toLowerCase();
        if (!num.includes(q) && !desc.includes(q)) return false;
      }
      if (this.filterStatus && t.status !== this.filterStatus) return false;
      if (this.filterStage) {
        const stage = this.stageByTenderId[t.id] || 'NOT_STARTED';
        if (stage !== this.filterStage) return false;
      }
      if (this.filterPlatform) {
        const p = t.platform || 'GOSZAKUP';   // null → Госзакуп (KZ-дефолт)
        if (p !== this.filterPlatform) return false;
      }
      if (this.filterFacilityId != null && t.facility?.id !== this.filterFacilityId) return false;
      if (from && t.deadline && new Date(t.deadline) < from) return false;
      if (to && t.deadline && new Date(t.deadline) > to) return false;
      if (this.filterRegion === this.NO_REGION) { if (t.region) return false; }
      else if (this.filterRegion && t.region !== this.filterRegion) return false;
      return true;
    });
    this.sortTenders();
  }

  private sortTenders() {
    const ts = (d: any) => d ? new Date(d).getTime() : null;
    if (this.sortMode === 'deadline') {
      // «скоро дедлайн»: будущие — ближайший первым; затем прошедшие (свежие выше); без дедлайна — вниз
      const now = Date.now();
      this.filteredTenders.sort((a, b) => {
        const da = ts(a.deadline), db = ts(b.deadline);
        if (da == null && db == null) return 0;
        if (da == null) return 1;
        if (db == null) return -1;
        const fa = da >= now ? 0 : 1, fb = db >= now ? 0 : 1;
        if (fa !== fb) return fa - fb;
        return fa === 0 ? da - db : db - da;
      });
    } else {
      // «сначала новые»: по дате публикации DESC; без даты — вниз; при равенстве — новее по id
      this.filteredTenders.sort((a, b) => {
        const pa = ts(a.publishDate), pb = ts(b.publishDate);
        if (pa == null && pb == null) return (b.id || 0) - (a.id || 0);
        if (pa == null) return 1;
        if (pb == null) return -1;
        return (pb - pa) || ((b.id || 0) - (a.id || 0));
      });
    }
  }

  importStatus: any = null;
  private importPollTimer: any = null;

  refreshImportStatus() {
    if (!this.isKz()) { this.importStatus = null; return; }
    this.api.getKzImportStatus().subscribe({
      next: st => {
        this.importStatus = st;
        if (st?.running) this.startImportPolling(); // импорт мог быть запущен из другой вкладки/сессии
        this.cdr.detectChanges();
      },
      error: () => { /* статус — вспомогательная информация, ошибку не показываем */ }
    });
  }

  importPct(): number {
    const s = this.importStatus?.lastSummary;
    if (!s?.orgsTotal) return 5;
    return Math.max(5, Math.min(100, Math.round(100 * (s.orgsProcessed || 0) / s.orgsTotal)));
  }

  startImportPolling() {
    if (this.importPollTimer) return;
    this.importPollTimer = setInterval(() => {
      this.api.getKzImportStatus().subscribe({
        next: st => {
          const wasRunning = this.importStatus?.running;
          this.importStatus = st;
          if (!st.running) {
            this.stopImportPolling();
            this.importing = false;
            if (wasRunning) { // прогон закончился на наших глазах — итоговый тост + обновить список
              const s = st.lastSummary;
              if (s?.enabled === false) {
                this.notify.error(s.message || 'Импорт выключен: не настроен токен goszakup');
              } else {
                if (s?.message) this.notify.success('Импорт завершён: ' + s.message);
                this.loadTenders();
              }
            }
          }
          this.cdr.detectChanges();
        },
        error: () => { this.stopImportPolling(); this.importing = false; this.cdr.detectChanges(); }
      });
    }, 2500);
  }

  private stopImportPolling() {
    if (this.importPollTimer) { clearInterval(this.importPollTimer); this.importPollTimer = null; }
  }

  formatImportTime(iso: string): string {
    const t = new Date(iso).getTime();
    const mins = Math.round((Date.now() - t) / 60000);
    if (mins < 1) return 'только что';
    if (mins < 60) return `${mins} мин назад`;
    return new Date(iso).toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
  }

  resetTendersFilter() {
    this.filterQuery = '';
    this.filterStatus = '';
    this.filterStage = '';
    this.filterPlatform = '';
    this.filterRegion = '';
    this.filterFacilityId = null;
    this.filterDeadlineFrom = '';
    this.filterDeadlineTo = '';
    this.applyTendersFilter();
  }

  onAddTender() { this.editingTenderId = null; this.tenderForm.reset({ status: 'DRAFT', platform: '' }); this.validationErrors = {}; this.showTenderForm = true; }

  onEditTender(t: any) {
    this.editingTenderId = t.id;
    this.tenderForm.patchValue({
      tenderNumber: t.tenderNumber, facilityId: t.facility?.id || null, status: t.status,
      purchaseType: t.purchaseType, deadline: t.deadline, publishDate: t.publishDate,
      description: t.description, deliveryAddress: t.deliveryAddress,
      contactLastName: t.contactLastName, contactFirstName: t.contactFirstName,
      contactMiddleName: t.contactMiddleName, contactPhone: t.contactPhone, contactEmail: t.contactEmail,
      platform: t.platform || ''
    });
    this.showTenderForm = true;
  }

  onSaveTender() {
    const v = this.tenderForm.value;
    const body: any = {
      tenderNumber: v.tenderNumber, status: v.status, purchaseType: v.purchaseType,
      deadline: v.deadline, publishDate: v.publishDate || null,
      description: v.description, deliveryAddress: v.deliveryAddress,
      contactLastName: v.contactLastName, contactFirstName: v.contactFirstName,
      contactMiddleName: v.contactMiddleName, contactPhone: v.contactPhone, contactEmail: v.contactEmail,
      facilityId: v.facilityId || null,
      platform: v.platform || null
    };
    const wasEditing = this.editingTenderId !== null;
    const req = this.editingTenderId ? this.api.update('tenders', this.editingTenderId, body) : this.api.create('tenders', body);
    req.subscribe({
      next: () => {
        this.showTenderForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Тендер обновлён' : 'Тендер создан');
        this.loadTenders();
      },
      error: (err: any) => {
        if (err.status === 400) {
          if (err.error?.errors) { this.validationErrors = err.error.errors; }
          else if (err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        } else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDeleteTender(id: number) {
    this.confirm.ask('Удалить тендер?', 'Это действие нельзя отменить. Удаление невозможно, если к тендеру привязаны заявки.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('tenders', id).subscribe({
          next: () => { this.notify.success('Тендер удалён'); this.loadTenders(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }

  onOpen(t: any) {
    this.selectedTender = t;
    this.matchLotId = null;
    this.priceRequests = [];
    this.loadLots();
  }

  onBack() {
    this.selectedTender = null;
    this.matchLotId = null;
    this.priceRequests = [];
    this.loadTenders();
  }

  loadLots() {
    this.api.getTenderLots(this.selectedTender.id).subscribe({
      next: data => {
        this.lots = data;
        this.cdr.detectChanges();
        this.loadPriceRequests();
      },
      error: err => this.notify.error('Ошибка загрузки лотов: ' + (err.error?.message || err.message))
    });
  }

  loadPriceRequests() {
    if (!this.selectedTender) return;
    this.api.getPriceRequestsByTender(this.selectedTender.id).subscribe({
      next: data => { this.priceRequests = data; this.cdr.detectChanges(); },
      error: () => this.priceRequests = []
    });
  }

  onMatchRequested(ev: { lotId: number; lotNumber: number }) {
    this.matchLotId = ev.lotId;
    this.matchLotNumber = ev.lotNumber;
    this.cdr.detectChanges();
  }

  closeMatch() {
    this.matchLotId = null;
    this.matchLotNumber = null;
  }

  matchLotProposedId(): number | null {
    const lot = this.lots.find((l: any) => l.id === this.matchLotId);
    return lot?.proposedEquipment?.id ?? null;
  }

  onSmartMatchRequest(ev: { candidate: any; distributorId: number; distributorName: string }) {
    if (!this.matchLotId || !this.selectedTender) {
      this.notify.error('Лот не определён');
      return;
    }
    const lot = this.lots.find((l: any) => l.id === this.matchLotId);
    this.api.sendPriceRequests({
      tenderId: this.selectedTender.id,
      distributorIds: [ev.distributorId],
      items: [{
        tenderLotId: this.matchLotId,
        medEquipmentId: ev.candidate.equipmentId,
        requestedQuantity: lot?.quantity ?? 1
      }]
    }).subscribe({
      next: (results) => {
        const t = kpToastFromResults(results);
        if (t.isError) this.notify.error(t.message); else this.notify.success(t.message);
        this.loadPriceRequests();
        this.matchLotId = null;
        this.matchLotNumber = null;
      },
      error: err => this.notify.error(err.error?.message || 'Не удалось отправить запрос КП')
    });
  }

  // ===== чистка UI: показывать только заполненное =====
  hasContactPerson(): boolean { return this.formatContact(this.selectedTender) !== '—'; }

  resendPr(pr: any) {
    this.api.resendPriceRequest(pr.id).subscribe({
      next: (r) => {
        const t = kpToastFromResults([r]);
        if (t.isError) this.notify.error(t.message); else this.notify.success(t.message);
        this.loadPriceRequests();
      },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка пересылки'),
    });
  }

  checkKpResponses() {
    this.api.pollInbound().subscribe({
      next: () => { this.notify.success('Почта проверена'); this.loadPriceRequests(); },
      error: (e) => this.notify.error('Проверка почты: ' + (e.error?.message || e.message)),
    });
  }

  // ===== Запросы КП =====
  togglePr(pr: any) {
    pr._expanded = !pr._expanded;
    if (pr._expanded) {
      // pre-populate editing fields from current values
      for (const it of (pr.items || [])) {
        if (it._editPrice === undefined) it._editPrice = it.responsePrice;
        if (it._editNote === undefined) it._editNote = it.responseNote;
      }
    }
  }

  saveResponses(pr: any) {
    const updates = (pr.items || []).map((it: any) => ({
      itemId: it.id,
      responsePrice: it._editPrice ?? it.responsePrice,
      responseNote: it._editNote ?? it.responseNote
    }));
    const bad = updates.find((u: any) => u.responsePrice != null && Number(u.responsePrice) < 0);
    if (bad) {
      this.notify.error('Цена ответа КП не может быть отрицательной');
      return;
    }
    this.api.updatePriceRequestResponses(pr.id, updates).subscribe({
      next: () => { this.notify.success('Ответы сохранены'); this.loadPriceRequests(); },
      error: err => this.notify.error(err.error?.message || 'Ошибка сохранения')
    });
  }

  closePr(pr: any) {
    this.confirm.ask('Закрыть запрос КП?', 'После закрытия редактирование станет невозможно.', { confirmLabel: 'Закрыть' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.closePriceRequest(pr.id).subscribe({
          next: () => { this.notify.success('Запрос закрыт'); this.loadPriceRequests(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка')
        });
      });
  }

  deletePr(id: number) {
    this.confirm.ask('Удалить запрос КП?', 'Все позиции запроса будут удалены.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePriceRequest(id).subscribe({
          next: () => { this.notify.success('Запрос удалён'); this.loadPriceRequests(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
