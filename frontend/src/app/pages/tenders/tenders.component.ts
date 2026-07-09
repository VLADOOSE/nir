import { Component, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, FormsModule, Validators } from '@angular/forms';
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

@Component({
  selector: 'app-tenders',
  standalone: true,
  imports: [NgFor, NgIf, DecimalPipe, ReactiveFormsModule, FormsModule, SearchableSelectComponent, BulkPriceModalComponent, OfferComparisonComponent, SmartMatchComponent, LucideDynamicIcon, MarketMoneyPipe],
  template: `
    <!-- ========== СПИСОК ТЕНДЕРОВ ========== -->
    <ng-container *ngIf="!selectedTender">
      <h2>Тендеры</h2>
      <p class="subtitle">Управление тендерами на закупку медицинского оборудования</p>

      <div class="filters">
        <input type="text" placeholder="Поиск по номеру или описанию..." [(ngModel)]="filterQuery" (input)="applyTendersFilter()" class="filter-input" />
        <select [(ngModel)]="filterStatus" (change)="applyTendersFilter()" class="filter-select">
          <option value="">Все статусы</option>
          <option value="DRAFT">Подготовка</option>
          <option value="ACTIVE">Приём заявок</option>
          <option value="COMPLETED">Завершён</option>
          <option value="CANCELLED">Отменён</option>
        </select>
        <select [(ngModel)]="sortMode" (change)="applyTendersFilter()" class="filter-select" title="Сортировка">
          <option value="published">Сначала новые</option>
          <option value="deadline">Скоро дедлайн</option>
        </select>
        <select [(ngModel)]="filterFacilityId" (change)="applyTendersFilter()" class="filter-select">
          <option [ngValue]="null">Все учреждения</option>
          <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
        </select>
        <input type="date" [(ngModel)]="filterDeadlineFrom" (change)="applyTendersFilter()" class="filter-date" title="Дедлайн от" />
        <input type="date" [(ngModel)]="filterDeadlineTo" (change)="applyTendersFilter()" class="filter-date" title="Дедлайн до" />
        <select *ngIf="isKz()" [(ngModel)]="filterRegion" (change)="applyTendersFilter()" class="filter-select" title="Регион">
          <option value="">Все регионы</option>
          <option [value]="NO_REGION">Регион не указан</option>
          <option *ngFor="let r of REGIONS" [value]="r">{{ r }}</option>
        </select>
        <button class="btn btn-reset-filter" (click)="resetTendersFilter()">Сбросить</button>
      </div>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showTenderForm" (click)="onAddTender()">Добавить тендер</button>
        <button class="btn btn-add" *ngIf="isKz() && !showTenderForm" (click)="onImportKz()" [disabled]="importing || importStatus?.running"
                [title]="importRegion() ? 'Импорт с goszakup только по региону: ' + importRegion() : 'Импорт всей ленты goszakup'">
          {{ (importing || importStatus?.running) ? 'Обновление…' : ('Обновить тендеры' + (importRegion() ? ' — ' + importRegion() : '')) }}
        </button>
        <div class="import-progress" *ngIf="isKz() && importStatus?.running">
          <div class="import-bar"><div class="import-bar-fill" [style.width.%]="importPct()"></div></div>
          <span class="import-progress-text">
            стр. {{ importStatus.lastSummary?.pagesRead || 0 }}/{{ importStatus.lastSummary?.maxPages || '…' }}
            · получено {{ importStatus.lastSummary?.fetched || 0 }}
            · подходящих {{ importStatus.lastSummary?.matched || 0 }}
            · создано {{ importStatus.lastSummary?.created || 0 }}
            · обновлено {{ importStatus.lastSummary?.updated || 0 }}<ng-container *ngIf="importStatus.lastSummary?.errors"> · ошибок {{ importStatus.lastSummary.errors }}</ng-container>
          </span>
        </div>
        <span class="import-status" *ngIf="isKz() && importStatus && !importStatus.running && importStatus.lastFinishedAt">
          Обновлено {{ formatImportTime(importStatus.lastFinishedAt) }}<ng-container *ngIf="importStatus.lastSummary"> · создано {{ importStatus.lastSummary.created }}, обновлено {{ importStatus.lastSummary.updated }}</ng-container>
        </span>
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
            <a *ngIf="!isDemoTender(t.tenderNumber)" class="eis-link" [href]="eisLink(t.tenderNumber)" target="_blank" rel="noopener" (click)="$event.stopPropagation()" [title]="'Открыть в ' + procurementPortalLabel() + ' ' + procurementPortalHost()">
              <svg lucideIcon="external-link" [size]="12"></svg> {{ procurementPortalLabel() }}
            </a>
            <span *ngIf="isDemoTender(t.tenderNumber)" class="demo-badge" title="Контрольный пример, не существует в реестре закупок">Демо</span>
            <span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>
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
          <a *ngIf="!isDemoTender(selectedTender.tenderNumber)" class="eis-link-h2" [href]="eisLink(selectedTender.tenderNumber)" target="_blank" rel="noopener" [title]="'Открыть на ' + procurementPortalHost()">
            <svg lucideIcon="external-link" [size]="14"></svg> Открыть в {{ procurementPortalLabel() }}
          </a>
          <span *ngIf="isDemoTender(selectedTender.tenderNumber)" class="demo-badge-h2" title="Контрольный пример, не существует в реестре закупок">Контрольный пример</span>
        </h2>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">Заказчик</span><span>{{ selectedTender.facility?.name || selectedTender.customerName || '—' }}</span></div>
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

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showLotForm" (click)="onAddLot()">Добавить лот</button>
        <button class="btn btn-add-bulk" *ngIf="lots.length > 0 && !isImportedTender()" (click)="bulkPriceTenderId = selectedTender.id">
          Запросить КП по всему тендеру
        </button>
        <button class="btn btn-kp-selected" *ngIf="lots.length > 0" [disabled]="lotSel.size === 0"
                (click)="openKpPanel()">
          Запросить КП по выбранным ({{ lotSel.size }})
        </button>
        <span class="counter" *ngIf="lots.length">Найдено: {{ lots.length }} лотов</span>
      </div>

      <form *ngIf="showLotForm" [formGroup]="lotForm" (ngSubmit)="onSaveLot()" class="edit-form">
        <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
        <div class="dims-row">
          <label>&#8470; лота<input type="number" min="1" formControlName="lotNumber" [class.input-error]="validationErrors.lotNumber" /><span class="field-error" *ngIf="validationErrors.lotNumber">{{ validationErrors.lotNumber }}</span></label>
          <label>Кол-во *<input type="number" min="1" formControlName="quantity" [class.input-error]="validationErrors.quantity" /><span class="field-error" *ngIf="validationErrors.quantity">{{ validationErrors.quantity }}</span></label>
        </div>
        <label>Название оборудования *<input formControlName="equipName" [class.input-error]="validationErrors.equipName" /><span class="field-error" *ngIf="validationErrors.equipName">{{ validationErrors.equipName }}</span></label>
        <label>Тип оборудования
          <select formControlName="equipType">
            <option value="">— не выбран —</option>
            <option value="УЗИ">УЗИ</option>
            <option value="Рентген">Рентген</option>
            <option value="ИВЛ">ИВЛ</option>
            <option value="Монитор">Монитор</option>
          </select>
        </label>
        <label>Макс. цена<input type="number" min="0.01" step="0.01" formControlName="maxCost" [class.input-error]="validationErrors.maxCost" /><span class="field-error" *ngIf="validationErrors.maxCost">{{ validationErrors.maxCost }}</span></label>
        <div class="dims-row">
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

      <div *ngIf="lots.length === 0 && !showLotForm" class="empty">Нет лотов</div>

      <table *ngIf="lots.length > 0">
        <thead>
          <tr><th class="w-36"><input type="checkbox" [checked]="allLotsSelected()" (change)="toggleAllLots($any($event.target).checked)" title="Выбрать все лоты" /></th><th>&#8470;</th><th>Название</th><th *ngIf="hasAnyType()">Тип</th><th>Кол-во</th><th>Макс. цена</th><th *ngIf="hasAnyDims()">Габариты (макс.)</th><th *ngIf="hasAnyWeight()">Макс. вес</th><th>Спецификация</th><th>Действия</th></tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let l of lots">
          <tr>
            <td class="w-36"><input type="checkbox" [checked]="lotSel.has(l.id)" (change)="toggleLotSel(l)" /></td>
            <td>{{ l.lotNumber }}</td>
            <td>
              {{ l.equipName }}
              <div class="proposed-line" *ngIf="l.proposedEquipment">
                <span class="badge-proposed">Предложено:</span>
                {{ l.proposedEquipment.name }} ({{ l.proposedEquipment.manufact }})
                <span class="badge-reg-ok" *ngIf="l.proposedEquipment.registrationStatus === 'REGISTERED'"
                      [title]="'РУ ' + (l.proposedEquipment.regNumber || '')">РУ ✓</span>
                <button class="x-mini" (click)="clearProposed(l)" title="Снять предложение">✕</button>
              </div>
              <div class="kp-line" *ngIf="kpDistributorsFor(l.id).length">КП: {{ kpDistributorsFor(l.id).join(', ') }}</div>
            </td>
            <td *ngIf="hasAnyType()">{{ l.equipmentType?.name || '—' }}</td><td>{{ l.quantity }}</td>
            <td>{{ l.maxCost | money }}</td><td *ngIf="hasAnyDims()">{{ l.maxLengthMm || '—' }}x{{ l.maxWidthMm || '—' }}x{{ l.maxHeightMm || '—' }}</td><td *ngIf="hasAnyWeight()">{{ l.maxWeightKg ? l.maxWeightKg + ' кг' : '—' }}</td>
            <td class="spec-cell">
              <button *ngIf="l.requiredSpec" class="spec-toggle" [class.open]="l._specOpen" (click)="toggleSpec(l)"
                      [title]="l._specOpen ? 'Свернуть спецификацию' : 'Читать спецификацию'">
                <span class="spec-preview">{{ specPreview(l.requiredSpec) }}</span>
                <span class="spec-chevron">{{ l._specOpen ? '▴' : '▾' }}</span>
              </button>
              <span *ngIf="!l.requiredSpec" class="spec-empty">—</span>
            </td>
            <td class="actions">
              <button class="btn btn-tz" *ngIf="isImportedTender()" [disabled]="tzBusy.has(l.id)"
                      (click)="parseTechSpec(l)" title="Скачать и разобрать техспецификацию с goszakup">
                {{ tzBusy.has(l.id) ? '…' : 'ТЗ' }}
              </button>
              <button class="btn btn-kp" (click)="openKpPanelFor(l)">КП</button>
              <button class="btn btn-registry" *ngIf="isKz()" (click)="onLotRegistry(l)"
                      title="Подбор из реестра НЦЭЛС (кандидаты + комплектность аппаратов)">Подбор</button>
              <!-- каталог-матч: только РФ (KZ-каталог наполняется из реестра, там подбор — через «Подбор») -->
              <button class="btn btn-match" *ngIf="lotHasCriteria(l) && !isKz()" (click)="onMatch(l)">Подобрать</button>
              <span class="lot-menu-wrap">
                <button class="btn btn-more" (click)="toggleLotMenu(l, $event)" title="Ещё действия">⋯</button>
                <span class="lot-menu" *ngIf="openMenuLotId === l.id">
                  <button (click)="onEditLot(l); openMenuLotId = null">✎ Редактировать</button>
                  <button class="danger" (click)="onDeleteLot(l.id); openMenuLotId = null">🗑 Удалить</button>
                </span>
              </span>
            </td>
          </tr>
          <tr *ngIf="l._specOpen" class="spec-row">
            <td [attr.colspan]="10">
              <div class="spec-full">
                <div class="spec-full-head">
                  <b>Техническая спецификация{{ l.lotNumber ? ' — лот №' + l.lotNumber : '' }}</b>
                  <button class="btn btn-cancel" (click)="toggleSpec(l)">✕ Свернуть</button>
                </div>
                <div class="spec-full-body">{{ l.requiredSpec }}</div>
              </div>
            </td>
          </tr>
          </ng-container>
        </tbody>
      </table>

      <div class="registry-panel" *ngIf="registryPanel">
        <div class="registry-panel-head">
          <span><b>Реестр НЦЭЛС РК:</b> {{ registryPanel.lot.equipName }}</span>
          <button class="btn btn-cancel" (click)="closeRegistryPanel()">✕ Закрыть</button>
        </div>
        <div class="registry-note">Реестр НЦЭЛС — допуск (№ РУ); габариты/вес здесь не хранятся, соответствие — по совпадению наименования.</div>
        <div *ngIf="!registryPanel.loading && !registryPanel.distinctive && !registryPanel.techSpecParsed && isImportedTender()" class="registry-hint">
          ⚠ Совпадение только по названию — модели в реестре неразличимы. Нажмите «ТЗ», чтобы разбор техспецификации уточнил подбор.
        </div>
        <div *ngIf="registryPanel.loading" class="registry-loading">Ищем похожие изделия в реестре…</div>
        <div *ngIf="!registryPanel.loading && !registryPanel.items.length" class="empty">Похожих записей в реестре не найдено — вероятно, это не медизделие (услуга/расходник) или нужен другой запрос</div>
        <table *ngIf="!registryPanel.loading && registryPanel.items.length" class="registry-table">
          <thead><tr><th>Соответствие</th><th>РУ &#8470;</th><th>Наименование в реестре</th><th>Производитель</th><th>Страна</th><th>Действует</th><th></th></tr></thead>
          <tbody>
            <ng-container *ngFor="let c of registryPanel.items">
              <tr class="registry-row" (click)="toggleRegistryDetail(c)"
                  [title]="registryPanel.openReg === c.regNumber ? 'Свернуть описание' : 'Показать описание из карточки НЦЭЛС'">
                <td>
                  <span *ngIf="registryPanel.distinctive" class="score-badge" [class.score-good]="c.score >= 0.35">{{ scorePct(c) }}%</span>
                  <span *ngIf="!registryPanel.distinctive" class="score-badge score-name" title="Совпало наименование; для различения моделей разберите ТЗ">✓ по названию</span>
                </td>
                <td>{{ c.regNumber }}</td>
                <td>{{ c.name }} <span class="registry-desc-chip">{{ registryPanel.openReg === c.regNumber ? '▴ свернуть' : '▾ описание' }}</span></td>
                <td>{{ c.producer || '—' }}</td>
                <td>{{ c.country || '—' }}</td>
                <td>{{ c.unlimited ? 'бессрочно' : (c.expirationDate ? formatDate(c.expirationDate) : '—') }}</td>
                <td><button class="btn btn-adopt" [disabled]="adoptBusy" (click)="$event.stopPropagation(); adoptFromRegistry(c)" title="Создать модель каталога из этого РУ и предложить лоту">Взять в работу</button></td>
              </tr>
              <tr *ngIf="registryPanel.openReg === c.regNumber" class="registry-detail-row">
                <td colspan="7">
                  <div *ngIf="registryPanel.detailLoading" class="registry-loading">Загружаем карточку НЦЭЛС…</div>
                  <div *ngIf="registryPanel.detailError && !registryPanel.detailLoading" class="registry-detail-error">
                    {{ registryPanel.detailError }} — сверните и разверните строку, чтобы повторить.
                  </div>
                  <div *ngIf="registryPanel.detail && !registryPanel.detailLoading" class="registry-detail-cols">
                    <div *ngIf="registryPanel.lot?.requiredSpec" class="registry-detail-col">
                      <div class="registry-detail-h">ТЗ лота</div>
                      <pre class="registry-detail-pre">{{ registryPanel.lot.requiredSpec }}</pre>
                    </div>
                    <div class="registry-detail-col">
                      <div class="registry-detail-h">Из реестра НЦЭЛС</div>
                      <div *ngIf="registryDetailEmpty(registryPanel.detail)" class="empty">В карточке НЦЭЛС описание не заполнено</div>
                      <div *ngIf="registryPanel.detail.riskClass || registryPanel.detail.miKind" class="registry-detail-meta">
                        <span *ngIf="registryPanel.detail.riskClass">{{ registryPanel.detail.riskClass }}</span>
                        <span *ngIf="registryPanel.detail.riskClass && registryPanel.detail.miKind"> · </span>
                        <span *ngIf="registryPanel.detail.miKind">{{ registryPanel.detail.miKind }}</span>
                        <div *ngIf="registryPanel.detail.miKindDef" class="registry-detail-def">{{ registryPanel.detail.miKindDef }}</div>
                      </div>
                      <div *ngIf="registryPanel.detail.purpose" class="registry-detail-block"><b>Назначение:</b> {{ registryPanel.detail.purpose }}</div>
                      <div *ngIf="registryPanel.detail.useArea" class="registry-detail-block"><b>Область применения:</b> {{ registryPanel.detail.useArea }}</div>
                      <div *ngIf="registryPanel.detail.techChars" class="registry-detail-block"><b>Краткие тех. характеристики:</b>
                        <pre class="registry-detail-pre">{{ registryPanel.detail.techChars }}</pre>
                      </div>
                    </div>
                  </div>
                </td>
              </tr>
            </ng-container>
          </tbody>
        </table>
        <div class="complect-cta">
          <button class="btn btn-registry" (click)="openComplect(registryPanel.lot)"
                  title="Найти лот в комплектности родительского аппарата (для электродов/пластин/принадлежностей)">
            🔧 Комплектность аппаратов
          </button>
          <span class="registry-note">Если лот — принадлежность к аппарату (электрод, пластина), допуск может быть в комплектности аппарата.</span>
        </div>
      </div>

      <div class="registry-panel" *ngIf="complectPanel">
        <div class="registry-panel-head">
          <span><b>Комплектность аппаратов:</b> {{ complectPanel.lot.equipName }}</span>
          <button class="btn btn-cancel" (click)="closeComplect()">✕ Закрыть</button>
        </div>
        <div class="complect-term">
          <input type="text" [(ngModel)]="complectPanel.term" placeholder="Название аппарата (напр. Элэскулап)"
                 (keyup.enter)="runComplect(complectPanel.lot, complectPanel.term)">
          <button class="btn btn-primary" [disabled]="complectPanel.loading"
                  (click)="runComplect(complectPanel.lot, complectPanel.term)">Искать</button>
        </div>
        <div *ngIf="complectPanel.loading" class="registry-loading">Ищем в комплектности аппаратов…</div>
        <div *ngIf="!complectPanel.loading && complectPanel.searched && !complectPanel.apparatuses.length" class="empty">
          Аппарат не найден — уточните его название в поле выше и нажмите «Искать».
        </div>
        <div *ngFor="let a of complectPanel.apparatuses" class="complect-apparatus">
          <div class="complect-app-head">
            {{ a.name }} · <b>{{ a.country || '—' }}</b> · {{ a.producer || '—' }} · РУ {{ a.regNumber }}
          </div>
          <div *ngIf="!a._relevant.length && !a._zero.length" class="empty">Комплектность у этого аппарата не заполнена.</div>
          <table class="registry-table" *ngIf="a._relevant.length || a._zero.length">
            <thead><tr><th>Совпадение</th><th>Компонент (состав)</th><th>Тип</th><th>Страна</th><th></th></tr></thead>
            <tbody>
              <tr *ngFor="let comp of a._relevant; let i = index" [class.recommended]="i === 0">
                <td>
                  <span class="score-badge" [class.score-good]="i === 0">{{ scorePct(comp) }}%</span>
                  <span *ngIf="i === 0" class="reco-chip">★ рекомендуем</span>
                </td>
                <td><pre class="complect-pre">{{ comp.productName }}</pre></td>
                <td>{{ comp.component || '—' }}</td>
                <td>{{ comp.country || '—' }}</td>
                <td><button class="btn" [class.btn-adopt]="i === 0" [class.btn-adopt-muted]="i !== 0" [disabled]="adoptBusy"
                            (click)="adoptComponent(a, comp)"
                            title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button></td>
              </tr>
              <tr *ngIf="a._zero.length">
                <td colspan="5">
                  <button class="complect-zero-toggle" (click)="a._showZero = !a._showZero">
                    {{ a._showZero ? '▴ скрыть нерелевантные' : '▾ ещё ' + a._zero.length + ' нерелевантных (0%)' }}
                  </button>
                </td>
              </tr>
              <ng-container *ngIf="a._showZero">
                <tr *ngFor="let comp of a._zero" class="complect-zero-row">
                  <td><span class="score-badge">0%</span></td>
                  <td><pre class="complect-pre">{{ comp.productName }}</pre></td>
                  <td>{{ comp.component || '—' }}</td>
                  <td>{{ comp.country || '—' }}</td>
                  <td><button class="btn btn-adopt-muted" [disabled]="adoptBusy" (click)="adoptComponent(a, comp)"
                              title="Создать позицию каталога из компонента (РУ аппарата) и предложить лоту">Взять в работу</button></td>
                </tr>
              </ng-container>
            </tbody>
          </table>
        </div>
      </div>

      <div class="kp-panel" *ngIf="kpPanel">
        <div class="kp-panel-head">
          <span><b>Запрос КП</b> · выбрано лотов: {{ lotSel.size }}</span>
          <button class="btn btn-cancel" (click)="kpPanel = null">✕ Закрыть</button>
        </div>
        <div *ngIf="kpPanel.loading" class="registry-loading">Подбираем поставщиков…</div>
        <ng-container *ngIf="!kpPanel.loading">
          <div class="kp-controls" *ngIf="kpPanel.singleLot">
            <label>Вид МИ:
              <select [ngModel]="kpPanel.detectedType?.id ?? ''" (ngModelChange)="changeLotType($event)">
                <option value="">— не задан —</option>
                <option *ngFor="let t of equipmentTypesList" [value]="t.id">{{ t.name }}</option>
              </select>
            </label>
            <span class="kp-conf" *ngIf="kpPanel.detectedType && kpPanel.detectedType.confidence < 1">
              авто · {{ (kpPanel.detectedType.confidence * 100) | number:'1.0-0' }}%
            </span>
            <label class="kp-term">Поиск поставщика:
              <input type="text" [(ngModel)]="kpPanel.sourcingTerm" placeholder="бренд/аппарат"
                     (keyup.enter)="researchSupplier()">
            </label>
            <button class="btn btn-line" (click)="researchSupplier()">Найти</button>
          </div>

          <div class="empty" *ngIf="!kpPanel.entries.length">На этом рынке нет поставщиков — добавьте их в справочнике «Дистрибьюторы»</div>
          <div class="empty" *ngIf="kpPanel.entries.length && !kpPanel._relevant.length && !kpPanel.detectedType">
            Нужна техспецификация или вид МИ, чтобы подобрать по специализации.
          </div>

          <table *ngIf="kpPanel._relevant.length" class="kp-suppliers">
            <thead><tr><th class="w-36"></th><th>Поставщик</th><th>Email</th><th>Почему</th></tr></thead>
            <tbody>
              <tr *ngFor="let e of kpPanel._relevant; let i = index" [class.kp-hit]="e.preselect" [class.recommended]="i === 0">
                <td class="w-36"><input type="checkbox" [(ngModel)]="e._checked" /></td>
                <td>
                  <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener" class="supplier-link" title="Открыть сайт поставщика">{{ e.distributor?.name }} ↗</a>
                  <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
                  <span *ngIf="!e.distributor?.equipmentTypes?.length" class="tag-all"> · все виды</span>
                </td>
                <td>{{ e.distributor?.email || '—' }} <span class="no-email" *ngIf="!e.distributor?.email">письмо не уйдёт</span></td>
                <td>
                  <span class="reason-chip" *ngFor="let r of e.reasons"
                        [class.reason-type]="r.kind === 'TYPE'" [class.reason-brand]="r.kind === 'BRAND'">
                    {{ r.kind === 'TYPE' ? '✓' : 'возит' }} {{ r.label }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>

          <div class="kp-nonrel" *ngIf="kpPanel._nonrel.length">
            <button class="complect-zero-toggle" (click)="kpPanel._showNonrel = !kpPanel._showNonrel">
              {{ kpPanel._showNonrel ? '▴ скрыть нерелевантных' : '▾ ещё ' + kpPanel._nonrel.length + ' нерелевантных' }}
            </button>
            <table *ngIf="kpPanel._showNonrel" class="kp-suppliers">
              <tbody>
                <tr *ngFor="let e of kpPanel._nonrel">
                  <td class="w-36"><input type="checkbox" [(ngModel)]="e._checked" /></td>
                  <td>
                    <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener" class="supplier-link" title="Открыть сайт поставщика">{{ e.distributor?.name }} ↗</a>
                    <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
                  </td>
                  <td>{{ e.distributor?.email || '—' }}</td>
                  <td></td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="kp-panel-actions" *ngIf="kpPanel.entries.length">
            <button class="btn btn-save" [disabled]="kpPanel.sending || checkedSuppliers().length === 0" (click)="sendKpRequests()">
              {{ kpPanel.sending ? 'Отправка…' : 'Отправить запросы (' + checkedSuppliers().length + ')' }}
            </button>
          </div>
        </ng-container>
      </div>

      <div class="kp-preview-overlay" *ngIf="kpPreview" (click)="cancelKpPreview()">
        <div class="kp-preview" (click)="$event.stopPropagation()">
          <h3>Текст письма — проверьте перед отправкой</h3>
          <p class="kp-preview-note">Метка [КП-№] будет присвоена автоматически при отправке. Письмо уйдёт {{ kpPreview.distributorIds.length }} поставщик(ам).</p>
          <label class="kp-preview-lbl">Тема</label>
          <input class="kp-preview-subject" [(ngModel)]="kpPreview.subject" />
          <label class="kp-preview-lbl">Текст</label>
          <textarea class="kp-preview-body" rows="16" [(ngModel)]="kpPreview.body"></textarea>
          <div class="kp-preview-actions">
            <button class="btn btn-cancel" (click)="cancelKpPreview()">Отмена</button>
            <button class="btn btn-save" [disabled]="kpPreview.sending" (click)="confirmSendKp()">
              {{ kpPreview.sending ? 'Отправка…' : 'Отправить' }}
            </button>
          </div>
        </div>
      </div>

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
            <div class="pr-actions">
              <button class="btn btn-save" (click)="saveResponses(pr)">Сохранить ответы</button>
              <button *ngIf="pr.status === 'SENT' || pr.status === 'CREATED'" class="btn btn-line" (click)="resendPr(pr)">↻ Переслать</button>
              <button *ngIf="pr.status === 'RESPONDED'" class="btn btn-accept-pr cta-pulse" (click)="acceptPr(pr)">Принять КП</button>
              <button *ngIf="pr.status === 'ACCEPTED' || pr.status === 'RESPONDED'" class="btn btn-create-apply cta-pulse" (click)="createApplyFromPr(pr)">
                <svg lucideIcon="clipboard-list" [size]="14"></svg> Сформировать заявку
              </button>
              <button *ngIf="pr.status === 'RESPONDED' || pr.status === 'ACCEPTED'" class="btn btn-close-pr" (click)="closePr(pr)">Закрыть запрос</button>
              <button class="btn btn-delete" (click)="deletePr(pr.id)">Удалить запрос</button>
            </div>
          </div>
        </div>
      </section>
    </ng-container>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    h3 { margin: 24px 0 12px; font-size: 17px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .counter { color: #6b7280; font-size: 13px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }

    .filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; margin-bottom: 16px; }
    .filter-input { flex: 1; min-width: 200px; max-width: 320px; padding: 8px 14px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; }
    .filter-input:focus { outline: none; border-color: #1a56db; }
    .filter-select { padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; background: #fff; max-width: 220px; }
    .filter-date { padding: 7px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; background: #fff; }
    .btn-reset-filter { background: #e5e7eb; color: #374151; padding: 8px 14px; border: none; border-radius: 6px; cursor: pointer; font-size: 13px; }
    .tender-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px 20px; margin-bottom: 12px; cursor: pointer; transition: box-shadow 0.2s; }
    .tender-card:hover { box-shadow: 0 2px 8px rgba(0,0,0,0.08); border-color: #d1d5db; }
    .tender-card.tender-urgent { border-left: 4px solid #f59e0b; }
    .tender-card.tender-overdue { border-left: 4px solid #ef4444; background: #fef2f2; }
    .eis-link { display: inline-flex; align-items: center; gap: 3px; font-size: 11px; padding: 2px 8px; background: #f3f4f6; color: #1a56db; border-radius: 4px; text-decoration: none; font-weight: 500; vertical-align: middle; }
    .eis-link:hover { background: #dbeafe; }
    .eis-link-h2 { display: inline-flex; align-items: center; gap: 4px; font-size: 13px; padding: 4px 10px; background: #eff6ff; color: #1a56db; border-radius: 6px; text-decoration: none; font-weight: 500; margin-left: 12px; }
    .eis-link-h2:hover { background: #dbeafe; }
    .demo-badge { display: inline-flex; align-items: center; font-size: 10px; padding: 2px 6px; background: #fef3c7; color: #92400e; border-radius: 4px; font-weight: 600; text-transform: uppercase; letter-spacing: 0.04em; }
    .demo-badge-h2 { display: inline-flex; align-items: center; font-size: 12px; padding: 4px 10px; background: #fef3c7; color: #92400e; border-radius: 6px; font-weight: 600; margin-left: 12px; }
    .tender-card-header { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 8px; }
    .tender-meta { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
    .tender-number { font-weight: 600; color: #1a56db; font-size: 15px; }
    .tender-price { font-size: 18px; font-weight: 700; color: #111827; white-space: nowrap; }
    .purchase-type { font-size: 12px; color: #6b7280; background: #f3f4f6; padding: 2px 8px; border-radius: 4px; }
    .tender-card-title { font-size: 14px; color: #374151; margin-bottom: 12px; line-height: 1.5; }
    .tender-card-details { margin-bottom: 12px; }
    .detail-row { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; margin-bottom: 8px; }
    .detail { display: flex; flex-direction: column; }
    .detail-label { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin-bottom: 2px; }
    .detail span:not(.detail-label) { font-size: 14px; }
    .price { font-weight: 600; color: #111827; }
    .deadline { font-weight: 500; }
    .deadline.overdue { color: #ef4444; }
    .tender-card-actions { display: flex; gap: 8px; border-top: 1px solid #f3f4f6; padding-top: 12px; }

    .tender-info { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-bottom: 20px; }
    .tender-info h2 { margin-bottom: 16px; }
    .info-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px 24px; }
    .info-item { display: flex; flex-direction: column; }
    .info-label { font-size: 11px; color: #9ca3af; text-transform: uppercase; margin-bottom: 2px; }
    .info-item span:not(.info-label) { font-size: 14px; }
    .info-desc { margin-top: 16px; font-size: 14px; color: #374151; line-height: 1.5; }

    table { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: #1a56db; color: #fff; }
    .btn-add-bulk { background: #8b5cf6; color: #fff; margin-left: 8px; }
    .w-36 { width: 36px; text-align: center; }
    .btn-kp { background: #0e9f6e; color: #fff; margin-right: 4px; }
    .btn-tz { background: #6366f1; color: #fff; margin-right: 4px; }
    .btn-tz:disabled { opacity: 0.6; cursor: wait; }
    .btn-kp-selected { background: #0e9f6e; color: #fff; margin-left: 8px; }
    .btn-kp-selected:disabled { opacity: 0.5; cursor: not-allowed; }
    .proposed-line { margin-top: 4px; font-size: 12px; color: #374151; }
    .badge-proposed { background: #d1fae5; color: #065f46; border-radius: 8px; padding: 1px 7px; font-size: 11px; font-weight: 600; margin-right: 4px; }
    .badge-reg-ok { background: #dbeafe; color: #1e40af; border-radius: 8px; padding: 1px 6px; font-size: 11px; font-weight: 600; margin-left: 4px; }
    .x-mini { background: none; border: none; color: #ef4444; cursor: pointer; font-size: 13px; margin-left: 4px; }
    .kp-line { margin-top: 3px; font-size: 11px; color: #6b7280; }
    .kp-panel { border: 1px solid #a7f3d0; background: #f0fdf4; border-radius: 8px; padding: 12px 14px; margin: 12px 0; }
    .kp-panel-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .kp-suppliers { background: #fff; }
    .kp-suppliers tr.kp-hit td { background: #ecfdf5; }
    .kp-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .kp-controls select, .kp-term input { padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .kp-conf { font-size: 12px; color: #6b7280; }
    .reason-chip { display: inline-block; border-radius: 999px; padding: 2px 8px; font-size: 11px; margin: 0 3px 3px 0; }
    .reason-type { background: #dcfce7; color: #166534; }
    .reason-brand { background: #eef2ff; color: #3730a3; }
    .tag-all { color: #9ca3af; font-size: 11px; }
    .supplier-link { color: #4f46e5; text-decoration: none; }
    .supplier-link:hover { text-decoration: underline; }
    .btn-line { background: #fff; color: #374151; border: 1px solid #d1d5db; }
    .brand-chip { display: inline-block; background: #d1fae5; color: #065f46; border-radius: 10px; padding: 2px 8px; font-size: 11px; font-weight: 600; margin: 1px 4px 1px 0; }
    .no-email { color: #b91c1c; font-size: 11px; margin-left: 6px; }
    .kp-panel-actions { margin-top: 10px; display: flex; justify-content: flex-end; }
    .kp-preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center; justify-content: center; z-index: 1000; }
    .kp-preview { background: #fff; border-radius: 10px; padding: 20px; width: min(720px, 92vw); max-height: 88vh; overflow: auto; }
    .kp-preview-note { color: #6b7280; font-size: 12.5px; margin: 4px 0 12px; }
    .kp-preview-lbl { display: block; font-size: 12px; color: #374151; margin: 8px 0 3px; }
    .kp-preview-subject, .kp-preview-body { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .kp-preview-body { font: inherit; resize: vertical; }
    .kp-preview-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn-match { background: #10b981; color: #fff; margin-right: 4px; }
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-ACTIVE { background: #dbeafe; color: #1a56db; }
    .badge-COMPLETED { background: #d1fae5; color: #065f46; }
    .badge-CANCELLED { background: #fee2e2; color: #b91c1c; }
    .import-status { color: #6b7280; font-size: 12.5px; margin-left: 10px; }
    .import-progress { display: inline-flex; align-items: center; gap: 8px; margin-left: 10px; }
    .import-bar { width: 150px; height: 6px; background: #e5e7eb; border-radius: 3px; overflow: hidden; }
    .import-bar-fill { height: 100%; background: #2563eb; border-radius: 3px; transition: width .5s ease; }
    .import-progress-text { color: #374151; font-size: 12.5px; white-space: nowrap; }
    .btn-registry { background: #ede9fe; color: #5b21b6; }
    .registry-panel { margin: 10px 0 16px; padding: 12px 14px; border: 1px solid #ddd6fe; border-radius: 8px; background: #faf5ff; }
    .registry-panel-head, .pr-section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; }
    .registry-loading { color: #6b7280; padding: 6px 0; }
    .registry-table { width: 100%; }
    .registry-table th { text-align: left; font-size: 12px; color: #6b7280; }
    .score-badge { background: #e5e7eb; color: #374151; border-radius: 8px; padding: 2px 8px; font-size: 12px; }
    .btn-adopt { background: #0e9f6e; color: #fff; }
    .score-badge.score-good { background: #d1fae5; color: #065f46; }
    .score-badge.score-name { background: #eef2ff; color: #3730a3; }
    .registry-note { font-size: 12px; color: #6b7280; margin: 4px 0 8px; }
    .registry-hint { background: #fef3c7; border-left: 3px solid #f59e0b; padding: 8px 12px; border-radius: 4px; margin-bottom: 8px; font-size: 13px; color: #92400e; }
    .registry-row { cursor: pointer; }
    .registry-row:hover td { background: #f5f3ff; }
    .registry-desc-chip { font-size: 11px; color: #7c3aed; white-space: nowrap; margin-left: 6px; }
    .registry-detail-row td { background: #f5f3ff; padding: 10px 14px; }
    .registry-detail-cols { display: flex; gap: 16px; align-items: flex-start; }
    .registry-detail-col { flex: 1; min-width: 0; }
    .registry-detail-h { font-size: 11px; font-weight: 600; color: #6b7280; margin-bottom: 6px; text-transform: uppercase; letter-spacing: .04em; }
    .registry-detail-pre { white-space: pre-wrap; max-height: 300px; overflow-y: auto; background: #fff; border: 1px solid #e5e7eb; border-radius: 6px; padding: 8px 10px; font: inherit; margin: 4px 0 0; }
    .registry-detail-meta { margin-bottom: 8px; font-weight: 600; }
    .registry-detail-def { font-size: 12px; color: #6b7280; font-weight: 400; margin-top: 2px; }
    .registry-detail-block { margin-bottom: 6px; }
    .registry-detail-error { color: #b91c1c; padding: 4px 0; }
    .complect-cta { display: flex; align-items: center; gap: 10px; margin-top: 10px; flex-wrap: wrap; }
    .complect-term { display: flex; gap: 8px; margin-bottom: 10px; }
    .complect-term input { flex: 1; max-width: 360px; padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .complect-apparatus { margin: 10px 0; padding: 8px 10px; border: 1px solid #ddd6fe; border-radius: 8px; background: #fff; }
    .complect-app-head { font-size: 13px; margin-bottom: 6px; color: #374151; }
    .complect-pre { white-space: pre-wrap; margin: 0; font: inherit; max-width: 520px; }
    .recommended td { background: #ecfdf5; }
    .recommended td:first-child { box-shadow: inset 3px 0 0 #10b981; }
    .reco-chip { display: inline-block; margin-left: 6px; background: #10b981; color: #fff; border-radius: 8px; padding: 1px 7px; font-size: 11px; white-space: nowrap; }
    .complect-zero-toggle { background: none; border: none; color: #6b7280; cursor: pointer; font-size: 12px; padding: 4px 0; }
    .complect-zero-toggle:hover { color: #374151; text-decoration: underline; }
    .complect-zero-row td { color: #9ca3af; }
    .btn-adopt-muted { background: #e5e7eb; color: #4b5563; }
    .lot-menu-wrap { position: relative; display: inline-block; }
    .btn-more { background: #f3f4f6; color: #374151; font-weight: 700; padding: 4px 9px; }
    .lot-menu { position: absolute; right: 0; top: 100%; margin-top: 4px; background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; box-shadow: 0 6px 20px rgba(0,0,0,.12); z-index: 20; display: flex; flex-direction: column; min-width: 150px; overflow: hidden; }
    .lot-menu button { background: none; border: none; text-align: left; padding: 8px 12px; cursor: pointer; font-size: 13px; color: #374151; white-space: nowrap; }
    .lot-menu button:hover { background: #f3f4f6; }
    .lot-menu button.danger { color: #b91c1c; }
    .lot-mini-list { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0 2px; }
    .lot-mini { background: #f3f4f6; color: #374151; border-radius: 10px; padding: 2px 9px; font-size: 12px;
                max-width: 320px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .lot-mini-more { background: #e5e7eb; color: #6b7280; }
    .spec-cell { max-width: 260px; }
    .spec-toggle { display: inline-flex; align-items: center; gap: 6px; max-width: 240px; background: none; border: none; padding: 0; cursor: pointer; color: #1a56db; font-size: 13px; text-align: left; }
    .spec-toggle .spec-preview { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    .spec-toggle .spec-chevron { flex-shrink: 0; font-size: 11px; color: #6b7280; }
    .spec-toggle:hover .spec-preview { text-decoration: underline; }
    .spec-toggle.open { color: #111827; font-weight: 600; }
    .spec-empty { color: #9ca3af; }
    .spec-row td { padding: 0 !important; background: #f9fafb; }
    .spec-full { border-left: 3px solid #1a56db; margin: 4px 8px 12px; background: #fff; border-radius: 6px; box-shadow: 0 1px 3px rgba(0,0,0,0.06); }
    .spec-full-head { display: flex; justify-content: space-between; align-items: center; padding: 10px 16px; border-bottom: 1px solid #f3f4f6; }
    .spec-full-head b { font-size: 13px; color: #374151; }
    .spec-full-body { white-space: pre-wrap; word-break: break-word; font-size: 13px; line-height: 1.6; color: #1f2937; padding: 14px 16px; max-height: 340px; overflow-y: auto; }
    .badge-pr-CREATED { background: #e5e7eb; color: #374151; }
    .badge-pr-SENT { background: #dbeafe; color: #1a56db; }
    .badge-pr-RESPONDED { background: #fef3c7; color: #92400e; }
    .badge-pr-ACCEPTED { background: #d1fae5; color: #065f46; font-weight: 700; }
    .badge-pr-CLOSED { background: #f3f4f6; color: #6b7280; }
    .pr-card.pr-accepted { border-color: #10b981; box-shadow: 0 0 0 1px #10b981; }
    .pr-markup-calc { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 10px 12px; background: #f9fafb; border-radius: 6px; margin-bottom: 12px; border: 1px dashed #d1d5db; }
    .pmc-label { font-size: 13px; font-weight: 600; color: #374151; margin-right: 4px; }
    .pr-markup-calc button { padding: 5px 11px; border: 1px solid #d1d5db; background: #fff; border-radius: 4px; cursor: pointer; font-size: 12px; color: #374151; min-width: 48px; }
    .pr-markup-calc button:hover { background: #f3f4f6; }
    .pr-markup-calc button.active { background: #1a56db; color: #fff; border-color: #1a56db; font-weight: 600; }
    .pmc-custom { display: inline-flex; align-items: center; gap: 4px; font-size: 12px; color: #6b7280; margin-left: 6px; }
    .pmc-custom input { width: 60px; padding: 4px 6px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; text-align: right; }
    .pmc-calc { font-weight: 600; color: #1a56db; }
    .pmc-calc small { display: block; color: #f59e0b; font-size: 10px; font-weight: 400; }
    .pmc-profit { color: #059669; font-weight: 600; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 700px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; }
    .form-hint { font-size: 13px; color: #6b7280; margin: 0 0 12px; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .match-results { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 6px; padding: 16px; margin-top: 16px; }
    .match-results table { margin-top: 8px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
    .pr-section { margin-top: 24px; }
    .pr-card { border: 1px solid #e5e7eb; border-radius: 6px; margin-bottom: 8px; background: #fff; }
    .pr-card.expanded { border-color: #1a56db; }
    .pr-header { display: flex; justify-content: space-between; align-items: center; padding: 12px 16px; cursor: pointer; }
    .pr-header:hover { background: #f9fafb; }
    .pr-header-main { display: flex; align-items: center; gap: 10px; }
    .pr-header-meta { display: flex; align-items: center; gap: 12px; color: #6b7280; font-size: 13px; }
    .chevron { color: #9ca3af; font-size: 11px; }
    .pr-body { padding: 12px 16px; border-top: 1px solid #e5e7eb; background: #fafafa; }
    .pr-items { width: 100%; border-collapse: collapse; }
    .pr-items th { background: #f9fafb; padding: 6px 10px; font-size: 12px; color: #6b7280; }
    .pr-items td { padding: 6px 10px; border-bottom: 1px solid #f3f4f6; font-size: 13px; }
    .pr-items input { width: 100%; padding: 4px 8px; border: 1px solid #d1d5db; border-radius: 3px; font-size: 13px; }
    .pr-actions { display: flex; gap: 8px; margin-top: 12px; justify-content: flex-end; }
    .btn-close-pr { background: #6b7280; color: #fff; padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-accept-pr { background: #10b981; color: #fff; padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 600; }
    .btn-accept-pr:hover { background: #059669; }
    .btn-create-apply { background: #1a56db; color: #fff; padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; font-weight: 600; display: inline-flex; align-items: center; gap: 6px; }
    .btn-create-apply:hover { background: #1e40af; }
    @keyframes cta-pulse-anim { 0%, 100% { box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.45); } 50% { box-shadow: 0 0 0 6px rgba(16, 185, 129, 0); } }
    .cta-pulse { animation: cta-pulse-anim 1.8s infinite; }
  `]
})
export class TendersComponent {
  tenders: any[] = [];
  filteredTenders: any[] = [];
  filterQuery = '';
  filterStatus = '';
  filterRegion = '';
  protected readonly NO_REGION = '__none__';
  readonly REGIONS: string[] = [
    'г. Астана', 'г. Алматы', 'г. Шымкент',
    'Абайская область', 'Акмолинская область', 'Актюбинская область', 'Алматинская область',
    'Атырауская область', 'Восточно-Казахстанская область', 'Жамбылская область',
    'Жетысуская область', 'Западно-Казахстанская область', 'Карагандинская область',
    'Костанайская область', 'Кызылординская область', 'Мангистауская область',
    'Павлодарская область', 'Северо-Казахстанская область', 'Туркестанская область',
    'Улытауская область'
  ];
  filterFacilityId: number | null = null;
  filterDeadlineFrom = '';
  filterDeadlineTo = '';
  allApplyItems: any[] = [];
  facilities: any[] = [];
  distributors: any[] = [];
  selectedTender: any = null;
  lots: any[] = [];
  matchResults: any[] = [];
  matchLotId: number | null = null;
  matchLotNumber: number | null = null;

  // Лотовый запрос КП
  lotSel = new Set<number>();
  kpPanel: {
    loading: boolean; sending: boolean; entries: any[];
    _relevant: any[]; _nonrel: any[]; _showNonrel: boolean;
    singleLot: boolean;
    detectedType: { id: number; name: string; confidence: number } | null;
    typeAlternatives: { id: number; name: string }[];
    sourcingTerm: string;
    lotId: number | null;
  } | null = null;
  kpPreview: { subject: string; body: string; sending: boolean;
               distributorIds: number[]; items: any[] } | null = null;
  equipmentTypesList: any[] = [];
  // Разбор техспеки (кнопка «ТЗ»)
  tzBusy = new Set<number>();
  // «Взять из реестра в работу»
  adoptBusy = false;

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
    contactEmail: new FormControl('')
  });

  validationErrors: any = {};

  showLotForm = false;
  editingLotId: number | null = null;
  lotForm = new FormGroup({
    lotNumber: new FormControl<number | null>(null, [Validators.min(1)]),
    equipName: new FormControl(''),
    equipType: new FormControl(''),
    quantity: new FormControl<number | null>(null, [Validators.min(1)]),
    maxCost: new FormControl<number | null>(null, [Validators.min(0.01)]),
    maxLengthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWidthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxHeightMm: new FormControl<number | null>(null, [Validators.min(1)]),
    maxWeightKg: new FormControl<number | null>(null, [Validators.min(0.01)]),
    requiredSpec: new FormControl('')
  });

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
    this.api.getDistributors().subscribe({ next: data => { this.distributors = data; this.cdr.detectChanges(); } });
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
    return ({ CREATED: 'Создан', SENT: 'Отправлен', RESPONDED: 'Ответ получен', ACCEPTED: 'Принят', CLOSED: 'Закрыт' } as any)[s] || s;
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

  eisLink(tenderNumber: string): string { return this.market.portalLink(tenderNumber); }
  procurementPortalLabel(): string { return this.market.portalLabel(); }
  procurementPortalHost(): string { return this.market.portalHost(); }

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

  formatPrice(n: number): string { return n ? Number(n).toLocaleString('ru-RU') : '0'; }

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
  }

  importing = false;

  isKz(): boolean { return this.market.value === 'KZ'; }

  /** Регион для импорта: выбранный в фильтре, кроме спец-значения «Регион не указан». */
  importRegion(): string | undefined {
    return this.filterRegion && this.filterRegion !== this.NO_REGION ? this.filterRegion : undefined;
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

  toggleSpec(l: any) {
    l._specOpen = !l._specOpen;
    this.cdr.detectChanges();
  }

  /** Короткое превью спеки для ячейки-триггера (полный текст — в раскрытом аккордеоне). */
  specPreview(s: string): string {
    if (!s) return '';
    const flat = s.replace(/\s+/g, ' ').trim();
    return flat.length > 55 ? flat.slice(0, 55) + '…' : flat;
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
    if (!s?.maxPages) return 5;
    return Math.max(5, Math.min(100, Math.round(100 * (s.pagesRead || 0) / s.maxPages)));
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

  registryPanel: { lot: any; loading: boolean; items: any[]; distinctive?: boolean; techSpecParsed?: boolean;
                   openReg?: string | null; detail?: any; detailLoading?: boolean; detailError?: string | null } | null = null;

  onLotRegistry(l: any) {
    this.registryPanel = { lot: l, loading: true, items: [], distinctive: true, techSpecParsed: true };
    this.cdr.detectChanges();
    this.api.getLotRegistryCandidates(l.id).subscribe({
      next: (r: any) => {
        this.registryPanel = {
          lot: l, loading: false,
          items: r?.candidates || [],
          distinctive: !!r?.distinctive,
          techSpecParsed: !!r?.techSpecParsed,
        };
        this.cdr.detectChanges();
      },
      error: err => {
        this.registryPanel = null;
        this.notify.error('Реестр: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  closeRegistryPanel() { this.registryPanel = null; this.cdr.detectChanges(); }

  complectPanel: { lot: any; term: string; loading: boolean; searched: boolean; apparatuses: any[] } | null = null;

  openComplect(l: any) {
    this.complectPanel = { lot: l, term: '', loading: true, searched: false, apparatuses: [] };
    this.cdr.detectChanges();
    // первый прогон — без term: бэк сам извлечёт бренд из ТЗ
    this.runComplect(l, undefined);
  }

  runComplect(l: any, term?: string) {
    if (!this.complectPanel) return;
    this.complectPanel.loading = true;
    this.cdr.detectChanges();
    this.api.complectSearch(l.id, term).subscribe({
      next: (r: any) => {
        const apparatuses = (r?.apparatuses || []).map((a: any) => ({
          ...a,
          // разделяем на релевантные (есть совпадение) и нерелевантные (0%) — 0% прячем под тоглом
          _relevant: (a.components || []).filter((c: any) => (c.score || 0) > 0),
          _zero: (a.components || []).filter((c: any) => !((c.score || 0) > 0)),
          _showZero: false,
        }));
        this.complectPanel = {
          lot: l, term: r?.term || '', loading: false, searched: true, apparatuses
        };
        this.cdr.detectChanges();
      },
      error: err => {
        if (this.complectPanel) { this.complectPanel.loading = false; this.complectPanel.searched = true; }
        this.notify.error('Комплектность: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  closeComplect() { this.complectPanel = null; this.cdr.detectChanges(); }

  // overflow-меню строки лота (Ред./Удалить) — разгружает строку
  openMenuLotId: number | null = null;
  toggleLotMenu(l: any, ev: Event) {
    ev.stopPropagation(); // иначе document:click тут же закроет
    this.openMenuLotId = this.openMenuLotId === l.id ? null : l.id;
    this.cdr.detectChanges();
  }
  @HostListener('document:click')
  closeLotMenu() {
    if (this.openMenuLotId !== null) { this.openMenuLotId = null; this.cdr.detectChanges(); }
  }

  adoptComponent(c: any, comp: any) {
    if (!this.complectPanel) return;
    this.adoptBusy = true;
    this.cdr.detectChanges();
    this.api.adoptComponent(this.complectPanel.lot.id, c.regNumber, comp.partNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success('Компонент взят в работу — предложенная модель лота обновлена');
        this.closeComplect();
        this.loadLots();
      },
      error: err => {
        this.adoptBusy = false;
        this.notify.error('Не удалось взять компонент: ' + (err.error?.message || err.message));
        this.cdr.detectChanges();
      }
    });
  }

  toggleRegistryDetail(c: any) {
    const p = this.registryPanel;
    if (!p) return;
    if (p.openReg === c.regNumber) { p.openReg = null; this.cdr.detectChanges(); return; }
    p.openReg = c.regNumber;
    p.detail = c._detail || null;
    p.detailError = null;
    p.detailLoading = !p.detail;
    if (!p.detail) {
      this.api.getRegistryDetail(c.regNumber).subscribe({
        next: d => {
          c._detail = d; // фронтовый кеш на объекте кандидата — повторный разворот без запроса
          if (p.openReg === c.regNumber) { p.detail = d; p.detailLoading = false; }
          this.cdr.detectChanges();
        },
        error: err => {
          // ошибка живёт в развороте (панель не закрываем, тост не нужен);
          // detail остаётся null и в c._detail не кешируется → повторное открытие = retry
          if (p.openReg === c.regNumber) {
            p.detailLoading = false;
            p.detailError = err.error?.message || 'Не удалось получить карточку НЦЭЛС';
          }
          this.cdr.detectChanges();
        }
      });
    }
    this.cdr.detectChanges();
  }

  registryDetailEmpty(d: any): boolean {
    return !!d && !d.riskClass && !d.purpose && !d.useArea && !d.techChars && !d.miKind;
  }

  scorePct(c: any): number { return Math.round((c?.score || 0) * 100); }

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
    this.filterRegion = '';
    this.filterFacilityId = null;
    this.filterDeadlineFrom = '';
    this.filterDeadlineTo = '';
    this.applyTendersFilter();
  }

  onAddTender() { this.editingTenderId = null; this.tenderForm.reset({ status: 'DRAFT' }); this.validationErrors = {}; this.showTenderForm = true; }

  onEditTender(t: any) {
    this.editingTenderId = t.id;
    this.tenderForm.patchValue({
      tenderNumber: t.tenderNumber, facilityId: t.facility?.id || null, status: t.status,
      purchaseType: t.purchaseType, deadline: t.deadline, publishDate: t.publishDate,
      description: t.description, deliveryAddress: t.deliveryAddress,
      contactLastName: t.contactLastName, contactFirstName: t.contactFirstName,
      contactMiddleName: t.contactMiddleName, contactPhone: t.contactPhone, contactEmail: t.contactEmail
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
      facilityId: v.facilityId || null
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
    this.matchResults = [];
    this.matchLotId = null;
    this.priceRequests = [];
    this.lotSel.clear();
    this.kpPanel = null;
    this.loadLots();
  }

  onBack() {
    this.selectedTender = null;
    this.showLotForm = false;
    this.matchResults = [];
    this.matchLotId = null;
    this.priceRequests = [];
    this.lotSel.clear();
    this.kpPanel = null;
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

  onAddLot() { this.editingLotId = null; this.lotForm.reset(); this.validationErrors = {}; this.showLotForm = true; }
  onEditLot(l: any) { this.editingLotId = l.id; this.lotForm.patchValue(l); this.validationErrors = {}; this.showLotForm = true; }

  onSaveLot() {
    this.validationErrors = {};

    if (!this.selectedTender || !this.selectedTender.id) {
      this.validationErrors = { _general: 'Ошибка: не выбран тендер. Перезагрузите страницу.' };
      return;
    }

    const body: any = { ...this.lotForm.value, tenderId: this.selectedTender.id };
    const wasEditing = this.editingLotId !== null;
    const req = this.editingLotId ? this.api.update('lots', this.editingLotId, body) : this.api.create('lots', body);
    req.subscribe({
      next: () => {
        this.showLotForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Лот обновлён' : 'Лот добавлен');
        this.loadLots();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDeleteLot(id: number) {
    const usedCount = this.allApplyItems.filter(it => it.tenderLot?.id === id).length;
    if (usedCount > 0) {
      this.notify.error(`Невозможно удалить: лот используется в ${usedCount} позици${usedCount === 1 ? 'и' : 'ях'} заявок`);
      return;
    }
    this.confirm.ask('Удалить лот?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('lots', id).subscribe({
          next: () => { this.notify.success('Лот удалён'); this.loadLots(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }

  onMatch(lot: any) {
    this.matchLotId = lot.id;
    this.matchLotNumber = lot.lotNumber;
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
        this.kpToastFromResults(results);
        this.loadPriceRequests();
        this.matchLotId = null;
        this.matchLotNumber = null;
      },
      error: err => this.notify.error(err.error?.message || 'Не удалось отправить запрос КП')
    });
  }

  // ===== Лотовый запрос КП =====
  toggleLotSel(l: any) {
    if (this.lotSel.has(l.id)) this.lotSel.delete(l.id); else this.lotSel.add(l.id);
  }
  allLotsSelected(): boolean {
    return this.lots.length > 0 && this.lots.every((l: any) => this.lotSel.has(l.id));
  }
  toggleAllLots(checked: boolean) {
    this.lotSel.clear();
    if (checked) for (const l of this.lots) this.lotSel.add(l.id);
  }

  isImportedTender(): boolean {
    return this.isKz() && /^\d+-\d+$/.test(this.selectedTender?.tenderNumber || '');
  }

  // ===== чистка UI: показывать только заполненное =====
  hasContactPerson(): boolean { return this.formatContact(this.selectedTender) !== '—'; }
  hasAnyType(): boolean { return (this.lots || []).some((l: any) => l.equipmentType?.name); }
  hasAnyDims(): boolean { return (this.lots || []).some((l: any) => l.maxLengthMm || l.maxWidthMm || l.maxHeightMm); }
  hasAnyWeight(): boolean { return (this.lots || []).some((l: any) => l.maxWeightKg); }
  lotHasCriteria(l: any): boolean {
    return !!(l.equipmentType || l.maxLengthMm || l.maxWidthMm || l.maxHeightMm || l.maxWeightKg);
  }

  adoptFromRegistry(c: any) {
    const lot = this.registryPanel?.lot;
    if (!lot || !c?.regNumber) return;
    this.adoptBusy = true;
    this.api.adoptRegistryForLot(lot.id, c.regNumber).subscribe({
      next: () => {
        this.adoptBusy = false;
        this.notify.success(`Модель из реестра предложена для лота: ${c.name}`);
        this.closeRegistryPanel();
        this.loadLots();
        this.openKpPanelFor(lot); // сразу к запросу КП (предотметка по бренду производителя)
      },
      error: (e) => {
        this.adoptBusy = false;
        this.notify.error(e.error?.message || 'Не удалось взять РУ в работу');
        this.cdr.detectChanges();
      }
    });
  }

  parseTechSpec(l: any) {
    this.tzBusy.add(l.id);
    this.cdr.detectChanges();
    this.api.parseLotTechSpec(l.id).subscribe({
      next: (r) => {
        this.tzBusy.delete(l.id);
        const dims = r.dimsFound ? 'габариты ✓' : 'габариты —';
        const weight = r.weightFound ? 'вес ✓' : 'вес —';
        const amb = r.ambiguous ? ' (неоднозначный матч лота — проверьте вручную)' : '';
        const specLen = (r.lot?.requiredSpec || '').length;
        this.notify.success(`ТЗ разобрано: спека ${specLen} симв., ${dims}, ${weight}${amb}`);
        this.loadLots();
      },
      error: (e) => {
        this.tzBusy.delete(l.id);
        this.notify.error(e.error?.message || e.message || 'Не удалось разобрать ТЗ');
        this.cdr.detectChanges();
      }
    });
  }

  openKpPanelFor(l: any) {
    this.lotSel.clear();
    this.lotSel.add(l.id);
    this.openKpPanel();
  }

  openKpPanel(term?: string) {
    if (!this.selectedTender || this.lotSel.size === 0) return;
    const single = this.lotSel.size === 1;
    const lotId = single ? [...this.lotSel][0] : null;
    this.kpPanel = {
      loading: true, sending: false, entries: [], _relevant: [], _nonrel: [], _showNonrel: false,
      singleLot: single, detectedType: null, typeAlternatives: [], sourcingTerm: '', lotId,
    };
    this.cdr.detectChanges();
    this.api.getLotSourcing(this.selectedTender.id, [...this.lotSel], term).subscribe({
      next: (r) => {
        const entries = (r?.distributors || []).map((e: any) => ({ ...e, _checked: !!e.preselect }));
        this.kpPanel = {
          loading: false, sending: false, entries,
          _relevant: entries.filter((e: any) => e.relevant),
          _nonrel: entries.filter((e: any) => !e.relevant),
          _showNonrel: false,
          singleLot: !!r?.singleLot,
          detectedType: r?.detectedType || null,
          typeAlternatives: r?.typeAlternatives || [],
          sourcingTerm: r?.sourcingTerm || '',
          lotId,
        };
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.kpPanel = null;
        this.notify.error('Ошибка подбора поставщиков: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Сменить вид МИ лота из панели → сохранить и пересобрать подбор. */
  changeLotType(typeId: any) {
    const id = typeId === '' || typeId == null ? null : Number(typeId);
    if (!this.kpPanel?.lotId) return;
    const term = this.kpPanel.sourcingTerm || undefined;
    this.api.setLotEquipmentType(this.kpPanel.lotId, id).subscribe({
      next: () => { this.loadLots(); this.openKpPanel(term); },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка сохранения типа'),
    });
  }

  /** Точечный поиск поставщика по введённому термину (Tier 2). */
  researchSupplier() { this.openKpPanel(this.kpPanel?.sourcingTerm || undefined); }

  checkedSuppliers(): any[] {
    return (this.kpPanel?.entries || []).filter((e: any) => e._checked);
  }

  kpDistributorsFor(lotId: number): string[] {
    const names: string[] = [];
    for (const pr of this.priceRequests) {
      if ((pr.items || []).some((it: any) => it.tenderLot?.id === lotId)
          && pr.distributor?.name && !names.includes(pr.distributor.name)) {
        names.push(pr.distributor.name);
      }
    }
    return names;
  }

  clearProposed(l: any) {
    this.api.clearProposedEquipment(l.id).subscribe({
      next: () => { this.notify.success('Предложение модели снято'); this.loadLots(); },
      error: (e) => this.notify.error(e.error?.message || 'Ошибка'),
    });
  }

  /** Единый тост по результатам /send. */
  kpToastFromResults(results: any[]) {
    const list = results || [];
    const sent = list.filter((r: any) => r.emailSent).length;
    const noEmail = list.filter((r: any) => r.reason === 'NO_EMAIL').map((r: any) => r.distributorName);
    const failed = list.filter((r: any) => r.reason === 'SEND_FAILED').map((r: any) => r.distributorName);
    let msg = `Создано запросов: ${list.length}, писем отправлено: ${sent}`;
    if (noEmail.length) msg += `; без email: ${noEmail.join(', ')}`;
    if (failed.length) msg += `; ошибка отправки: ${failed.join(', ')}`;
    if (noEmail.length || failed.length) this.notify.error(msg); else this.notify.success(msg);
  }

  sendKpRequests() {
    if (!this.selectedTender || !this.kpPanel) return;
    const distributorIds = this.checkedSuppliers().map((e: any) => e.distributor.id);
    const items = this.lots
      .filter((l: any) => this.lotSel.has(l.id))
      .map((l: any) => ({ tenderLotId: l.id, medEquipmentId: l.proposedEquipment?.id ?? null, requestedQuantity: l.quantity ?? 1 }));
    if (!distributorIds.length || !items.length) return;
    this.kpPanel.sending = true;
    this.api.previewKp({ tenderId: this.selectedTender.id, distributorIds, items }).subscribe({
      next: (p) => {
        this.kpPreview = { subject: p.subject, body: p.body, sending: false, distributorIds, items };
        if (this.kpPanel) this.kpPanel.sending = false;
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.kpPanel) this.kpPanel.sending = false;
        this.notify.error('Ошибка превью: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  confirmSendKp() {
    if (!this.selectedTender || !this.kpPreview) return;
    this.kpPreview.sending = true;
    this.api.sendPriceRequests({
      tenderId: this.selectedTender.id,
      distributorIds: this.kpPreview.distributorIds,
      items: this.kpPreview.items,
      subjectOverride: this.subjectHuman(this.kpPreview.subject),
      bodyOverride: this.kpPreview.body,
    }).subscribe({
      next: (results) => {
        this.kpToastFromResults(results);
        this.kpPreview = null; this.kpPanel = null; this.lotSel.clear();
        this.loadPriceRequests(); this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.kpPreview) this.kpPreview.sending = false;
        this.notify.error('Ошибка отправки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Убрать токен [КП-…] из отредактированной темы — сервер добавит свой. */
  private subjectHuman(subject: string): string {
    return (subject || '').replace(/\[КП-\d+\]\s*/g, '').trim();
  }

  cancelKpPreview() { this.kpPreview = null; this.cdr.detectChanges(); }

  resendPr(pr: any) {
    this.api.resendPriceRequest(pr.id).subscribe({
      next: (r) => { this.kpToastFromResults([r]); this.loadPriceRequests(); },
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
