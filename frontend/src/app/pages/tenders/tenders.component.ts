import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-tenders',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <!-- ========== СПИСОК ТЕНДЕРОВ ========== -->
    <ng-container *ngIf="!selectedTender">
      <h2>Тендеры</h2>

      <button class="btn btn-add" *ngIf="!showTenderForm" (click)="onAddTender()">Добавить</button>

      <form *ngIf="showTenderForm" [formGroup]="tenderForm" (ngSubmit)="onSaveTender()" class="edit-form">
        <label>Номер тендера
          <input formControlName="tenderNumber" />
        </label>
        <label>Учреждение
          <select formControlName="facilityId">
            <option [ngValue]="null">— не выбрано —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
        </label>
        <label>Статус
          <select formControlName="status">
            <option value="DRAFT">DRAFT</option>
            <option value="ACTIVE">ACTIVE</option>
            <option value="COMPLETED">COMPLETED</option>
          </select>
        </label>
        <label>Дедлайн
          <input type="date" formControlName="deadline" />
        </label>
        <label>Стоимость
          <input type="number" step="0.01" formControlName="totalCost" />
        </label>
        <label>Описание
          <textarea formControlName="description" rows="3"></textarea>
        </label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showTenderForm = false">Отмена</button>
        </div>
      </form>

      <table>
        <thead>
          <tr>
            <th>№</th>
            <th>Номер тендера</th>
            <th>Учреждение</th>
            <th>Статус</th>
            <th>Дедлайн</th>
            <th>Стоимость</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let t of tenders; let i = index">
            <td>{{ i + 1 }}</td>
            <td>{{ t.tenderNumber }}</td>
            <td>{{ t.facility?.name || '—' }}</td>
            <td><span class="badge" [class]="'badge-' + t.status">{{ t.status }}</span></td>
            <td>{{ t.deadline }}</td>
            <td>{{ t.totalCost }}</td>
            <td class="actions">
              <button class="btn btn-open" (click)="onOpen(t)">Открыть</button>
              <button class="btn btn-edit" (click)="onEditTender(t)">Редактировать</button>
              <button class="btn btn-delete" (click)="onDeleteTender(t.id)">Удалить</button>
            </td>
          </tr>
        </tbody>
      </table>
    </ng-container>

    <!-- ========== ДЕТАЛИ ТЕНДЕРА ========== -->
    <ng-container *ngIf="selectedTender">
      <button class="btn btn-back" (click)="onBack()">&#8592; Назад к списку</button>

      <div class="tender-info">
        <h2>Тендер {{ selectedTender.tenderNumber }}</h2>
        <p><strong>Учреждение:</strong> {{ selectedTender.facility?.name || '—' }}</p>
        <p><strong>Статус:</strong> <span class="badge" [class]="'badge-' + selectedTender.status">{{ selectedTender.status }}</span></p>
        <p><strong>Дедлайн:</strong> {{ selectedTender.deadline }}</p>
        <p><strong>Стоимость:</strong> {{ selectedTender.totalCost }}</p>
        <p *ngIf="selectedTender.description"><strong>Описание:</strong> {{ selectedTender.description }}</p>
      </div>

      <!-- Лоты -->
      <h3>Лоты тендера</h3>

      <button class="btn btn-add" *ngIf="!showLotForm" (click)="onAddLot()">Добавить лот</button>

      <form *ngIf="showLotForm" [formGroup]="lotForm" (ngSubmit)="onSaveLot()" class="edit-form">
        <div class="dims-row">
          <label>№ лота
            <input type="number" formControlName="lotNumber" />
          </label>
          <label>Кол-во
            <input type="number" formControlName="quantity" />
          </label>
        </div>
        <label>Название оборудования
          <input formControlName="equipName" />
        </label>
        <label>Тип оборудования
          <select formControlName="equipType">
            <option value="">— не выбран —</option>
            <option value="УЗИ">УЗИ</option>
            <option value="Рентген">Рентген</option>
            <option value="ИВЛ">ИВЛ</option>
            <option value="Монитор">Монитор</option>
          </select>
        </label>
        <label>Макс. цена
          <input type="number" step="0.01" formControlName="maxCost" />
        </label>
        <div class="dims-row">
          <label>Макс. длина
            <input type="number" formControlName="maxLengthMm" />
          </label>
          <label>Макс. ширина
            <input type="number" formControlName="maxWidthMm" />
          </label>
          <label>Макс. высота
            <input type="number" formControlName="maxHeightMm" />
          </label>
        </div>
        <label>Макс. вес (кг)
          <input type="number" step="0.01" formControlName="maxWeightKg" />
        </label>
        <label>Требования к спецификации
          <textarea formControlName="requiredSpec" rows="2"></textarea>
        </label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showLotForm = false">Отмена</button>
        </div>
      </form>

      <table>
        <thead>
          <tr>
            <th>№</th>
            <th>Название</th>
            <th>Тип</th>
            <th>Кол-во</th>
            <th>Макс. цена</th>
            <th>Габариты (макс.)</th>
            <th>Спецификация</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let l of lots">
            <td>{{ l.lotNumber }}</td>
            <td>{{ l.equipName }}</td>
            <td>{{ l.equipType }}</td>
            <td>{{ l.quantity }}</td>
            <td>{{ l.maxCost }}</td>
            <td>{{ l.maxLengthMm }}×{{ l.maxWidthMm }}×{{ l.maxHeightMm }}</td>
            <td>{{ l.requiredSpec }}</td>
            <td class="actions">
              <button class="btn btn-match" (click)="onMatch(l.id)">Подобрать</button>
              <button class="btn btn-edit" (click)="onEditLot(l)">Ред.</button>
              <button class="btn btn-delete" (click)="onDeleteLot(l.id)">Удл.</button>
            </td>
          </tr>
        </tbody>
      </table>

      <!-- Результаты подбора -->
      <div *ngIf="matchLotId !== null" class="match-results">
        <h3>Подходящее оборудование для лота #{{ matchLotId }}</h3>
        <p *ngIf="matchResults.length === 0">Ничего не найдено</p>
        <table *ngIf="matchResults.length > 0">
          <thead>
            <tr>
              <th>Название</th>
              <th>Производитель</th>
              <th>Цена</th>
              <th>Д×Ш×В (мм)</th>
              <th>Вес (кг)</th>
            </tr>
          </thead>
          <tbody>
            <tr *ngFor="let m of matchResults">
              <td>{{ m.name }}</td>
              <td>{{ m.manufact }}</td>
              <td>{{ m.cost }}</td>
              <td>{{ m.lengthMm }}×{{ m.widthMm }}×{{ m.heightMm }}</td>
              <td>{{ m.weightKg }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </ng-container>
  `,
  styles: [`
    h2 { margin: 0 0 16px; font-size: 20px; color: #111827; }
    h3 { margin: 24px 0 12px; font-size: 17px; color: #111827; }
    table { width: 100%; border-collapse: collapse; margin-bottom: 8px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: #1a56db; color: #fff; margin-bottom: 16px; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn-open { background: #1a56db; color: #fff; margin-right: 4px; }
    .btn-match { background: #10b981; color: #fff; margin-right: 4px; }
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-ACTIVE { background: #dbeafe; color: #1a56db; }
    .badge-COMPLETED { background: #d1fae5; color: #065f46; }
    .tender-info { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 16px; margin-bottom: 16px; }
    .tender-info p { margin: 4px 0; font-size: 14px; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .match-results { background: #f0fdf4; border: 1px solid #bbf7d0; border-radius: 6px; padding: 16px; margin-top: 16px; }
    .match-results table { margin-top: 8px; }
  `]
})
export class TendersComponent implements OnInit {
  tenders: any[] = [];
  facilities: any[] = [];
  selectedTender: any = null;
  lots: any[] = [];
  matchResults: any[] = [];
  matchLotId: number | null = null;

  showTenderForm = false;
  editingTenderId: number | null = null;
  tenderForm = new FormGroup({
    tenderNumber: new FormControl(''),
    facilityId: new FormControl<number | null>(null),
    status: new FormControl('DRAFT'),
    deadline: new FormControl(''),
    totalCost: new FormControl<number | null>(null),
    description: new FormControl('')
  });

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

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadTenders();
    this.api.getFacilities().subscribe(data => this.facilities = data);
  }

  loadTenders() {
    this.api.getTenders().subscribe(data => this.tenders = data);
  }

  // === Tender CRUD ===

  onAddTender() {
    this.editingTenderId = null;
    this.tenderForm.reset({ status: 'DRAFT' });
    this.showTenderForm = true;
  }

  onEditTender(t: any) {
    this.editingTenderId = t.id;
    this.tenderForm.patchValue({
      tenderNumber: t.tenderNumber,
      facilityId: t.facility?.id || null,
      status: t.status,
      deadline: t.deadline,
      totalCost: t.totalCost,
      description: t.description
    });
    this.showTenderForm = true;
  }

  onSaveTender() {
    const v = this.tenderForm.value;
    const body: any = {
      tenderNumber: v.tenderNumber,
      status: v.status,
      deadline: v.deadline,
      totalCost: v.totalCost,
      description: v.description,
      facility: v.facilityId ? { id: v.facilityId } : null
    };
    const req = this.editingTenderId
      ? this.api.update('tenders', this.editingTenderId, body)
      : this.api.create('tenders', body);
    req.subscribe(() => {
      this.showTenderForm = false;
      this.loadTenders();
    });
  }

  onDeleteTender(id: number) {
    if (confirm('Удалить тендер?')) {
      this.api.delete('tenders', id).subscribe(() => this.loadTenders());
    }
  }

  // === Detail view ===

  onOpen(t: any) {
    this.selectedTender = t;
    this.matchResults = [];
    this.matchLotId = null;
    this.loadLots();
  }

  onBack() {
    this.selectedTender = null;
    this.showLotForm = false;
    this.matchResults = [];
    this.matchLotId = null;
    this.loadTenders();
  }

  loadLots() {
    this.api.getTenderLots(this.selectedTender.id).subscribe(data => this.lots = data);
  }

  // === Lot CRUD ===

  onAddLot() {
    this.editingLotId = null;
    this.lotForm.reset();
    this.showLotForm = true;
  }

  onEditLot(l: any) {
    this.editingLotId = l.id;
    this.lotForm.patchValue(l);
    this.showLotForm = true;
  }

  onSaveLot() {
    const body: any = {
      ...this.lotForm.value,
      tender: { id: this.selectedTender.id }
    };
    const req = this.editingLotId
      ? this.api.update('lots', this.editingLotId, body)
      : this.api.create('lots', body);
    req.subscribe(() => {
      this.showLotForm = false;
      this.loadLots();
    });
  }

  onDeleteLot(id: number) {
    if (confirm('Удалить лот?')) {
      this.api.delete('lots', id).subscribe(() => this.loadLots());
    }
  }

  // === Equipment matching ===

  onMatch(lotId: number) {
    this.matchLotId = lotId;
    this.api.getMatchingEquipment(lotId).subscribe(data => this.matchResults = data);
  }
}
