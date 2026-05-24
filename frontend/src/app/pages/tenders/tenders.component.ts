import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { SearchableSelectComponent } from '../../components/searchable-select/searchable-select.component';
import { BulkPriceModalComponent } from './bulk-price-modal.component';

@Component({
  selector: 'app-tenders',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, SearchableSelectComponent, BulkPriceModalComponent],
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
        </select>
        <select [(ngModel)]="filterFacilityId" (change)="applyTendersFilter()" class="filter-select">
          <option [ngValue]="null">Все учреждения</option>
          <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
        </select>
        <input type="date" [(ngModel)]="filterDeadlineFrom" (change)="applyTendersFilter()" class="filter-date" title="Дедлайн от" />
        <input type="date" [(ngModel)]="filterDeadlineTo" (change)="applyTendersFilter()" class="filter-date" title="Дедлайн до" />
        <button class="btn btn-reset-filter" (click)="resetTendersFilter()">Сбросить</button>
      </div>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showTenderForm" (click)="onAddTender()">Добавить тендер</button>
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

      <div class="tender-card" *ngFor="let t of filteredTenders" (click)="onOpen(t)">
        <div class="tender-card-header">
          <div class="tender-meta">
            <span class="tender-number">&#8470; {{ t.tenderNumber }}</span>
            <span class="badge" [class]="'badge-' + t.status">{{ getStatusLabel(t.status) }}</span>
            <span class="purchase-type">{{ getPurchaseTypeLabel(t.purchaseType) }}</span>
          </div>
          <div class="tender-price">{{ formatPrice(t.totalCost) }} &#8381;</div>
        </div>
        <div class="tender-card-title">{{ t.description || 'Без описания' }}</div>
        <div class="tender-card-details">
          <div class="detail-row">
            <div class="detail"><span class="detail-label">Заказчик</span><span>{{ t.facility?.name || '—' }}</span></div>
            <div class="detail"><span class="detail-label">Способ закупки</span><span>{{ getPurchaseTypeLabel(t.purchaseType) }}</span></div>
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

      <button class="btn btn-back" (click)="onBack()">&#8592; Назад к списку</button>

      <div class="tender-info">
        <h2>Тендер &#8470; {{ selectedTender.tenderNumber }}</h2>
        <div class="info-grid">
          <div class="info-item"><span class="info-label">Заказчик</span><span>{{ selectedTender.facility?.name || '—' }}</span></div>
          <div class="info-item"><span class="info-label">Статус</span><span class="badge" [class]="'badge-' + selectedTender.status">{{ getStatusLabel(selectedTender.status) }}</span></div>
          <div class="info-item"><span class="info-label">Способ закупки</span><span>{{ getPurchaseTypeLabel(selectedTender.purchaseType) }}</span></div>
          <div class="info-item"><span class="info-label">Начальная цена (по лотам)</span><span class="price">{{ formatPrice(selectedTender.totalCost) }} &#8381;</span></div>
          <div class="info-item"><span class="info-label">Дата публикации</span><span>{{ formatDate(selectedTender.publishDate) }}</span></div>
          <div class="info-item"><span class="info-label">Окончание приёма заявок</span><span class="deadline" [class.overdue]="isOverdue(selectedTender.deadline)">{{ formatDate(selectedTender.deadline) }}</span></div>
          <div class="info-item"><span class="info-label">Адрес поставки</span><span>{{ selectedTender.deliveryAddress || '—' }}</span></div>
          <div class="info-item"><span class="info-label">Контактное лицо</span><span>{{ formatContact(selectedTender) }}</span></div>
          <div class="info-item"><span class="info-label">Телефон</span><span>{{ selectedTender.contactPhone || '—' }}</span></div>
          <div class="info-item"><span class="info-label">Эл. почта</span><span>{{ selectedTender.contactEmail || '—' }}</span></div>
        </div>
        <p *ngIf="selectedTender.description" class="info-desc"><strong>Описание:</strong> {{ selectedTender.description }}</p>
      </div>

      <h3>Лоты тендера</h3>

      <div class="toolbar">
        <button class="btn btn-add" *ngIf="!showLotForm" (click)="onAddLot()">Добавить лот</button>
        <button class="btn btn-add-bulk" *ngIf="lots.length > 0" (click)="bulkPriceTenderId = selectedTender.id">
          Запросить КП по всему тендеру
        </button>
        <span class="counter" *ngIf="lots.length">Найдено: {{ lots.length }} лотов</span>
      </div>

      <form *ngIf="showLotForm" [formGroup]="lotForm" (ngSubmit)="onSaveLot()" class="edit-form">
        <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
        <div class="dims-row">
          <label>&#8470; лота<input type="number" formControlName="lotNumber" [class.input-error]="validationErrors.lotNumber" /><span class="field-error" *ngIf="validationErrors.lotNumber">{{ validationErrors.lotNumber }}</span></label>
          <label>Кол-во *<input type="number" formControlName="quantity" [class.input-error]="validationErrors.quantity" /><span class="field-error" *ngIf="validationErrors.quantity">{{ validationErrors.quantity }}</span></label>
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
        <label>Макс. цена<input type="number" step="0.01" formControlName="maxCost" [class.input-error]="validationErrors.maxCost" /><span class="field-error" *ngIf="validationErrors.maxCost">{{ validationErrors.maxCost }}</span></label>
        <div class="dims-row">
          <label>Макс. длина<input type="number" formControlName="maxLengthMm" [class.input-error]="validationErrors.maxLengthMm" /><span class="field-error" *ngIf="validationErrors.maxLengthMm">{{ validationErrors.maxLengthMm }}</span></label>
          <label>Макс. ширина<input type="number" formControlName="maxWidthMm" [class.input-error]="validationErrors.maxWidthMm" /><span class="field-error" *ngIf="validationErrors.maxWidthMm">{{ validationErrors.maxWidthMm }}</span></label>
          <label>Макс. высота<input type="number" formControlName="maxHeightMm" [class.input-error]="validationErrors.maxHeightMm" /><span class="field-error" *ngIf="validationErrors.maxHeightMm">{{ validationErrors.maxHeightMm }}</span></label>
        </div>
        <label>Макс. вес (кг)<input type="number" step="0.01" formControlName="maxWeightKg" [class.input-error]="validationErrors.maxWeightKg" /><span class="field-error" *ngIf="validationErrors.maxWeightKg">{{ validationErrors.maxWeightKg }}</span></label>
        <label>Требования к спецификации<textarea formControlName="requiredSpec" rows="2"></textarea></label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showLotForm = false">Отмена</button>
        </div>
      </form>

      <div *ngIf="lots.length === 0 && !showLotForm" class="empty">Нет лотов</div>

      <table *ngIf="lots.length > 0">
        <thead>
          <tr><th>&#8470;</th><th>Название</th><th>Тип</th><th>Кол-во</th><th>Макс. цена</th><th>Габариты (макс.)</th><th>Макс. вес</th><th>Спецификация</th><th>Действия</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of lots">
            <td>{{ l.lotNumber }}</td><td>{{ l.equipName }}</td><td>{{ l.equipType }}</td><td>{{ l.quantity }}</td>
            <td>{{ formatPrice(l.maxCost) }} &#8381;</td><td>{{ l.maxLengthMm || '—' }}x{{ l.maxWidthMm || '—' }}x{{ l.maxHeightMm || '—' }}</td><td>{{ l.maxWeightKg ? l.maxWeightKg + ' кг' : '—' }}</td><td>{{ l.requiredSpec || '—' }}</td>
            <td class="actions">
              <button class="btn btn-match" (click)="onMatch(l)">Подобрать</button>
              <button class="btn btn-pr" (click)="onRequestPrice(l)">Запросить КП</button>
              <button class="btn btn-edit" (click)="onEditLot(l)">Редактировать</button>
              <button class="btn btn-delete" (click)="onDeleteLot(l.id)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>

      <div *ngIf="matchLotId !== null" class="match-results">
        <h3>Подходящее оборудование для лота #{{ matchLotNumber }}</h3>
        <p *ngIf="matchResults.length === 0">Ничего не найдено</p>
        <table *ngIf="matchResults.length > 0">
          <thead><tr><th>Название</th><th>Производитель</th><th>Цена</th><th>Д x Ш x В (мм)</th><th>Вес (кг)</th></tr></thead>
          <tbody>
            <tr *ngFor="let m of matchResults">
              <td>{{ m.name }}</td><td>{{ m.manufact }}</td><td>{{ formatPrice(m.cost) }} &#8381;</td>
              <td>{{ m.lengthMm }}x{{ m.widthMm }}x{{ m.heightMm }}</td><td>{{ m.weightKg }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <!-- ========== ЗАПРОСЫ КП ========== -->
      <h3>Запросы коммерческих предложений</h3>

      <form *ngIf="showPriceRequestForm" [formGroup]="priceRequestForm" (ngSubmit)="onSavePriceRequest()" class="edit-form pr-form">
        <p class="form-hint">Создание запроса КП для лота #{{ priceRequestLotNumber }}</p>
        <label>Оборудование {{ matchedEquipForPR.length > 0 ? '(подходящее по параметрам)' : '(весь каталог)' }}
          <app-searchable-select
            [items]="equipmentForSelect"
            labelField="name"
            [subLabelFields]="['manufact', 'equipType']"
            [searchFields]="['name', 'manufact', 'equipType']"
            placeholder="— выберите оборудование —"
            [value]="priceRequestForm.value.medEquipId"
            (valueChange)="priceRequestForm.patchValue({medEquipId: $event})"
            [groupLabel]="matchedEquipForPR.length > 0 ? 'Подходящее по параметрам' : 'Весь каталог'">
          </app-searchable-select>
        </label>
        <label>Дистрибьютор
          <app-searchable-select
            [items]="distributors"
            labelField="name"
            [subLabelFields]="['phone', 'email']"
            [searchFields]="['name', 'inn']"
            placeholder="— выберите дистрибьютора —"
            [value]="priceRequestForm.value.distributorId"
            (valueChange)="priceRequestForm.patchValue({distributorId: $event})">
          </app-searchable-select>
        </label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit" [disabled]="priceRequestForm.invalid">Отправить запрос</button>
          <button class="btn btn-cancel" type="button" (click)="showPriceRequestForm = false">Отмена</button>
        </div>
      </form>

      <form *ngIf="showPrUpdateForm" [formGroup]="prUpdateForm" (ngSubmit)="onSavePrUpdate()" class="edit-form pr-form">
        <p class="form-hint">Обновление запроса КП #{{ updatingPrId }}</p>
        <label>Статус
          <select formControlName="status">
            <option value="CREATED">Создан</option>
            <option value="SENT">Отправлен</option>
            <option value="RESPONDED">Получен ответ</option>
            <option value="ACCEPTED">Принят</option>
            <option value="REJECTED">Отклонён</option>
          </select>
        </label>
        <label>Цена ответа<input type="number" step="0.01" formControlName="responsePrice" /></label>
        <label>Дата ответа<input type="date" formControlName="responseDate" /></label>
        <label>Примечание<textarea formControlName="responseNote" rows="2"></textarea></label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showPrUpdateForm = false">Отмена</button>
        </div>
      </form>

      <div *ngIf="priceRequests.length === 0 && !showPriceRequestForm" class="empty">Запросов КП пока нет</div>

      <table *ngIf="priceRequests.length > 0">
        <thead>
          <tr><th>Лот</th><th>Оборудование</th><th>Дистрибьютор</th><th>Статус</th><th>Цена ответа</th><th>Дата ответа</th><th>Действия</th></tr>
        </thead>
        <tbody>
          <tr *ngFor="let pr of priceRequests">
            <td>{{ pr.lotEquipName }}</td>
            <td>{{ pr.medEquipment?.name }}</td>
            <td>{{ pr.distributor?.name }}</td>
            <td><span class="badge" [class]="'badge-pr-' + pr.status">{{ getPrStatusLabel(pr.status) }}</span></td>
            <td>{{ pr.responsePrice ? (formatPrice(pr.responsePrice) + ' ₽') : '—' }}</td>
            <td>{{ formatDate(pr.responseDate) }}</td>
            <td class="actions">
              <button *ngIf="pr.status === 'ACCEPTED'" class="btn btn-add-to-apply" (click)="onAddToApply(pr)">В заявку</button>
              <button class="btn btn-edit" (click)="onUpdatePr(pr)">Обновить статус</button>
              <button class="btn btn-delete" (click)="onDeletePr(pr.id)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- ========== ШАБЛОН EMAIL ========== -->
      <div *ngIf="generatedEmail" class="email-preview">
        <h3>Шаблон письма для дистрибьютора</h3>
        <div class="email-header">
          <div class="email-field"><span class="email-label">Кому:</span> {{ generatedEmail.to }}</div>
          <div class="email-field"><span class="email-label">Тема:</span> {{ generatedEmail.subject }}</div>
        </div>
        <pre class="email-body">{{ generatedEmail.body }}</pre>
        <div class="email-actions">
          <button class="btn btn-save" (click)="copyEmail()">Копировать в буфер</button>
          <button *ngIf="emailConfigured" class="btn btn-send-email" (click)="sendEmailFromApp()">Отправить из системы</button>
          <button class="btn btn-open-mail" (click)="openMailto()">Открыть почтовый клиент</button>
          <button class="btn btn-cancel" (click)="generatedEmail = null">Закрыть</button>
        </div>
      </div>
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
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn-match { background: #10b981; color: #fff; margin-right: 4px; }
    .btn-pr { background: #8b5cf6; color: #fff; margin-right: 4px; }
    .btn-add-to-apply { background: #059669; color: #fff; margin-right: 4px; }
    .btn-open-mail { background: #10b981; color: #fff; }
    .btn-send-email { background: #1a56db; color: #fff; }
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-ACTIVE { background: #dbeafe; color: #1a56db; }
    .badge-COMPLETED { background: #d1fae5; color: #065f46; }
    .badge-pr-CREATED { background: #e5e7eb; color: #374151; }
    .badge-pr-SENT { background: #dbeafe; color: #1a56db; }
    .badge-pr-RESPONDED { background: #fef3c7; color: #92400e; }
    .badge-pr-ACCEPTED { background: #d1fae5; color: #065f46; }
    .badge-pr-REJECTED { background: #fee2e2; color: #991b1b; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 700px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; }
    .pr-form { background: #faf5ff; border-color: #ddd6fe; }
    .form-hint { font-size: 13px; color: #6b7280; margin: 0 0 12px; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .match-results { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 6px; padding: 16px; margin-top: 16px; }
    .match-results table { margin-top: 8px; }
    .email-preview { background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 8px; padding: 20px; margin-top: 16px; }
    .email-preview h3 { margin: 0 0 12px; font-size: 16px; color: #1e40af; }
    .email-header { margin-bottom: 12px; }
    .email-field { font-size: 14px; margin-bottom: 4px; }
    .email-label { font-weight: 600; color: #374151; }
    .email-body { background: #fff; border: 1px solid #d1d5db; border-radius: 4px; padding: 16px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; font-family: inherit; margin-bottom: 12px; max-height: 300px; overflow-y: auto; }
    .email-actions { display: flex; gap: 8px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
  `]
})
export class TendersComponent {
  tenders: any[] = [];
  filteredTenders: any[] = [];
  filterQuery = '';
  filterStatus = '';
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
    lotNumber: new FormControl<number | null>(null),
    equipName: new FormControl(''),
    equipType: new FormControl(''),
    quantity: new FormControl<number | null>(null),
    maxCost: new FormControl<number | null>(null),
    maxLengthMm: new FormControl<number | null>(null),
    maxWidthMm: new FormControl<number | null>(null),
    maxHeightMm: new FormControl<number | null>(null),
    maxWeightKg: new FormControl<number | null>(null),
    requiredSpec: new FormControl('')
  });

  // ===== Запросы КП =====
  priceRequests: any[] = [];
  showPriceRequestForm = false;
  priceRequestLotId: number | null = null;
  priceRequestLotNumber: number | null = null;
  matchedEquipForPR: any[] = [];
  allEquipment: any[] = [];
  priceRequestForm = new FormGroup({
    medEquipId: new FormControl<number | null>(null),
    distributorId: new FormControl<number | null>(null)
  });

  showPrUpdateForm = false;
  updatingPrId: number | null = null;
  prUpdateForm = new FormGroup({
    status: new FormControl('CREATED'),
    responsePrice: new FormControl<number | null>(null),
    responseDate: new FormControl(''),
    responseNote: new FormControl('')
  });

  generatedEmail: any = null;
  emailConfigured = false;

  // Массовый подбор КП по тендеру
  bulkPriceTenderId: number | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private route: ActivatedRoute,
              private notify: NotificationService, private confirm: ConfirmService) {
    this.loadTenders();
    this.api.getFacilities().subscribe({ next: data => { this.facilities = data; this.cdr.detectChanges(); } });
    this.api.getDistributors().subscribe({ next: data => { this.distributors = data; this.cdr.detectChanges(); } });
    this.api.getEmailStatus().subscribe({ next: (s: any) => { this.emailConfigured = s.configured; this.cdr.detectChanges(); } });
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
    const labels: any = { DRAFT: 'Подготовка', ACTIVE: 'Приём заявок', COMPLETED: 'Завершён' };
    return labels[status] || status;
  }

  getPurchaseTypeLabel(type: string): string {
    if (type === 'ELECTRONIC_AUCTION') return 'Электронный аукцион';
    if (type === 'PAPER_TENDER') return 'Конкурс с подачей документов';
    return type || '—';
  }

  getPrStatusLabel(s: string): string {
    return ({ CREATED: 'Создан', SENT: 'Отправлен', RESPONDED: 'Получен ответ', ACCEPTED: 'Принят', REJECTED: 'Отклонён' } as any)[s] || s;
  }

  formatPrice(n: number): string { return n ? Number(n).toLocaleString('ru-RU') : '0'; }

  formatDate(d: string): string {
    if (!d) return '—';
    return new Date(d).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', year: 'numeric' });
  }

  formatContact(t: any): string {
    const parts = [t?.contactLastName, t?.contactFirstName, t?.contactMiddleName].filter(Boolean);
    return parts.length ? parts.join(' ') : '—';
  }

  get equipmentForSelect(): any[] {
    if (this.matchedEquipForPR.length > 0) return this.matchedEquipForPR;
    return this.allEquipment;
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
      return true;
    });
  }

  resetTendersFilter() {
    this.filterQuery = '';
    this.filterStatus = '';
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
    this.showPriceRequestForm = false;
    this.showPrUpdateForm = false;
    this.loadLots();
  }

  onBack() {
    this.selectedTender = null;
    this.showLotForm = false;
    this.matchResults = [];
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
    this.priceRequests = [];
    for (let lot of this.lots) {
      this.api.getPriceRequestsByLot(lot.id).subscribe(data => {
        this.priceRequests = [...this.priceRequests, ...data.map((pr: any) => ({ ...pr, lotEquipName: lot.equipName }))];
        this.cdr.detectChanges();
      });
    }
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
    this.api.getMatchingEquipment(lot.id).subscribe(data => { this.matchResults = data; this.cdr.detectChanges(); });
  }

  // ===== Запросы КП =====
  onRequestPrice(lot: any) {
    this.priceRequestLotId = lot.id;
    this.priceRequestLotNumber = lot.lotNumber;
    this.priceRequestForm.reset({ medEquipId: null, distributorId: null });
    this.showPriceRequestForm = true;
    this.showPrUpdateForm = false;
    this.generatedEmail = null;
    this.allEquipment = [];
    this.api.getMatchingEquipment(lot.id).subscribe(data => {
      this.matchedEquipForPR = data;
      if (data.length === 0) {
        this.api.getEquipment().subscribe(all => {
          this.allEquipment = all;
          this.cdr.detectChanges();
        });
      }
      this.cdr.detectChanges();
    });
  }

  onSavePriceRequest() {
    const v = this.priceRequestForm.value;
    if (!v.medEquipId || !v.distributorId || !this.priceRequestLotId) {
      this.notify.error('Выберите оборудование и дистрибьютора');
      return;
    }
    const body = {
      tenderLotId: this.priceRequestLotId,
      medEquipId: v.medEquipId,
      distributorId: v.distributorId,
      status: 'CREATED'
    };
    this.api.createPriceRequest(body).subscribe((created: any) => {
      this.showPriceRequestForm = false;
      this.loadPriceRequests();
      const dist = this.distributors.find((d: any) => d.id === v.distributorId);
      const equip = this.matchedEquipForPR.find((e: any) => e.id === v.medEquipId)
                 || this.allEquipment.find((e: any) => e.id === v.medEquipId);
      const lot = this.lots.find((l: any) => l.id === this.priceRequestLotId);
      this.generatedEmail = {
        to: dist?.email || '',
        subject: `Запрос КП: ${equip?.name || ''} для тендера №${this.selectedTender.tenderNumber}`,
        body: this.buildEmailBody(dist, equip, lot)
      };
      this.cdr.detectChanges();
    });
  }

  buildEmailBody(dist: any, equip: any, lot: any): string {
    return `Уважаемый(ая) ${dist?.lastName || ''} ${dist?.firstName || ''} ${dist?.middleName || ''}!

Компания ООО «Регион-Мед» просит предоставить коммерческое предложение на поставку следующего медицинского оборудования:

Наименование: ${equip?.name || ''}
Производитель: ${equip?.manufact || ''}
Тип оборудования: ${equip?.equipType || ''}
Количество: ${lot?.quantity || 1} шт.

Просим направить коммерческое предложение с указанием:
- Цена за единицу и общая стоимость
- Сроки поставки
- Условия оплаты
- Гарантийные обязательства

Ответ просим направить на адрес эл. почты отправителя.

С уважением,
ООО «Регион-Мед»`;
  }

  copyEmail() {
    const text = `Кому: ${this.generatedEmail.to}\nТема: ${this.generatedEmail.subject}\n\n${this.generatedEmail.body}`;
    navigator.clipboard.writeText(text);
    this.notify.success('Скопировано в буфер обмена');
  }

  openMailto() {
    const subj = encodeURIComponent(this.generatedEmail.subject);
    const body = encodeURIComponent(this.generatedEmail.body);
    window.open(`mailto:${this.generatedEmail.to}?subject=${subj}&body=${body}`);
  }

  sendEmailFromApp() {
    if (!this.generatedEmail) return;
    this.api.sendEmail(this.generatedEmail.to, this.generatedEmail.subject, this.generatedEmail.body)
      .subscribe({
        next: (res: any) => {
          if (res.status === 'OK') {
            this.notify.success('Письмо успешно отправлено на ' + this.generatedEmail.to);
            const lastPr = this.priceRequests[this.priceRequests.length - 1];
            if (lastPr && lastPr.status === 'CREATED') {
              this.api.updatePriceRequest(lastPr.id, { ...lastPr, status: 'SENT', sentAt: new Date().toISOString() })
                .subscribe(() => this.loadPriceRequests());
            }
          } else {
            this.notify.error('Ошибка отправки: ' + res.message);
          }
        },
        error: err => this.notify.error('Ошибка отправки: ' + (err.error?.message || err.message))
      });
  }

  onAddToApply(pr: any) {
    this.api.getApplies().subscribe((applies: any[]) => {
      let apply = applies.find((a: any) => a.tender?.id === this.selectedTender.id && a.status === 'DRAFT');
      if (apply) {
        this.addItemToApply(apply.id, pr);
      } else {
        this.api.create('applies', { tenderId: this.selectedTender.id, status: 'DRAFT' }).subscribe((newApply: any) => {
          this.addItemToApply(newApply.id, pr);
        });
      }
    });
  }

  addItemToApply(applyId: number, pr: any) {
    const item = {
      applyId: applyId,
      tenderLotId: pr.tenderLot?.id,
      medEquipId: pr.medEquipment?.id,
      distributorId: pr.distributor?.id,
      offeredCost: pr.responsePrice,
      quantity: pr.tenderLot?.quantity || 1
    };
    this.api.create('apply-items', item).subscribe(() => {
      this.notify.success('Позиция добавлена в заявку');
    });
  }

  onUpdatePr(pr: any) {
    this.updatingPrId = pr.id;
    this.prUpdateForm.patchValue({
      status: pr.status,
      responsePrice: pr.responsePrice,
      responseDate: pr.responseDate,
      responseNote: pr.responseNote
    });
    this.showPrUpdateForm = true;
  }

  onSavePrUpdate() {
    if (!this.updatingPrId) return;
    const existing = this.priceRequests.find(p => p.id === this.updatingPrId);
    if (!existing) return;
    const body = {
      tenderLotId: existing.tenderLot?.id,
      medEquipId: existing.medEquipment?.id,
      distributorId: existing.distributor?.id,
      status: this.prUpdateForm.value.status,
      responsePrice: this.prUpdateForm.value.responsePrice,
      responseDate: this.prUpdateForm.value.responseDate || null,
      responseNote: this.prUpdateForm.value.responseNote
    };
    this.api.updatePriceRequest(this.updatingPrId, body).subscribe(() => {
      this.showPrUpdateForm = false;
      this.loadPriceRequests();
    });
  }

  onDeletePr(id: number) {
    this.confirm.ask('Удалить запрос КП?', undefined, { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePriceRequest(id).subscribe({
          next: () => { this.notify.success('Запрос КП удалён'); this.loadPriceRequests(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
