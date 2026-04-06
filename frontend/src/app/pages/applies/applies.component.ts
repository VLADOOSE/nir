import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf, SlicePipe } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-applies',
  standalone: true,
  imports: [NgFor, NgIf, SlicePipe, ReactiveFormsModule],
  template: `
    <!-- ========== СПИСОК ЗАЯВОК ========== -->
    <ng-container *ngIf="!selectedApply">
      <h2>Заявки</h2>

      <button class="btn btn-add" *ngIf="!showApplyForm" (click)="onAddApply()">Добавить</button>

      <form *ngIf="showApplyForm" [formGroup]="applyForm" (ngSubmit)="onSaveApply()" class="edit-form">
        <label>Тендер
          <select formControlName="tenderId">
            <option [ngValue]="null">— не выбран —</option>
            <option *ngFor="let t of tenders" [ngValue]="t.id">{{ t.tenderNumber }} — {{ t.facility?.name || '?' }}</option>
          </select>
        </label>
        <label>Статус
          <select formControlName="status">
            <option value="DRAFT">DRAFT</option>
            <option value="SUBMITTED">SUBMITTED</option>
            <option value="WON">WON</option>
            <option value="REJECTED">REJECTED</option>
          </select>
        </label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showApplyForm = false">Отмена</button>
        </div>
      </form>

      <table>
        <thead>
          <tr>
            <th>ID</th>
            <th>Тендер</th>
            <th>Статус</th>
            <th>Дата создания</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let a of applies">
            <td>{{ a.id }}</td>
            <td>{{ a.tender?.tenderNumber || '—' }}</td>
            <td><span class="badge" [class]="'badge-' + a.status">{{ a.status }}</span></td>
            <td>{{ a.createdAt | slice:0:10 }}</td>
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

      <div class="apply-info">
        <h2>Заявка #{{ selectedApply.id }}</h2>
        <p><strong>Тендер:</strong> {{ selectedApply.tender?.tenderNumber || '—' }}</p>
        <p><strong>Статус:</strong> <span class="badge" [class]="'badge-' + selectedApply.status">{{ selectedApply.status }}</span></p>
        <p><strong>Дата создания:</strong> {{ selectedApply.createdAt | slice:0:10 }}</p>
      </div>

      <!-- Позиции -->
      <h3>Позиции заявки</h3>

      <button class="btn btn-add" *ngIf="!showItemForm" (click)="onAddItem()">Добавить позицию</button>

      <form *ngIf="showItemForm" [formGroup]="itemForm" (ngSubmit)="onSaveItem()" class="edit-form">
        <label>Лот тендера
          <select formControlName="tenderLotId">
            <option [ngValue]="null">— не выбран —</option>
            <option *ngFor="let l of lots" [ngValue]="l.id">Лот {{ l.lotNumber }}: {{ l.equipName }}</option>
          </select>
        </label>
        <label>Оборудование
          <select formControlName="medEquipId">
            <option [ngValue]="null">— не выбрано —</option>
            <option *ngFor="let e of equipment" [ngValue]="e.id">{{ e.name }} ({{ e.manufact }})</option>
          </select>
        </label>
        <label>Дистрибьютор
          <select formControlName="distributorId">
            <option [ngValue]="null">— не выбран —</option>
            <option *ngFor="let d of distributors" [ngValue]="d.id">{{ d.name }}</option>
          </select>
        </label>
        <div class="dims-row">
          <label>Предложенная цена
            <input type="number" step="0.01" formControlName="offeredCost" />
          </label>
          <label>Количество
            <input type="number" formControlName="quantity" />
          </label>
        </div>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-cancel" type="button" (click)="showItemForm = false">Отмена</button>
        </div>
      </form>

      <table>
        <thead>
          <tr>
            <th>Лот</th>
            <th>Оборудование</th>
            <th>Дистрибьютор</th>
            <th>Предл. цена</th>
            <th>Кол-во</th>
            <th>Действия</th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let it of items">
            <td>{{ it.tenderLot?.equipName || '—' }}</td>
            <td>{{ it.medEquipment?.name || '—' }}</td>
            <td>{{ it.distributor?.name || '—' }}</td>
            <td>{{ it.offeredCost }}</td>
            <td>{{ it.quantity }}</td>
            <td class="actions">
              <button class="btn btn-edit" (click)="onEditItem(it)">Ред.</button>
              <button class="btn btn-delete" (click)="onDeleteItem(it.id)">Удл.</button>
            </td>
          </tr>
        </tbody>
      </table>
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
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 16px; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .badge { padding: 2px 10px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .badge-DRAFT { background: #e5e7eb; color: #374151; }
    .badge-SUBMITTED { background: #dbeafe; color: #1a56db; }
    .badge-WON { background: #d1fae5; color: #065f46; }
    .badge-REJECTED { background: #fee2e2; color: #991b1b; }
    .apply-info { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 16px; margin-bottom: 16px; }
    .apply-info p { margin: 4px 0; font-size: 14px; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
  `]
})
export class AppliesComponent implements OnInit {
  applies: any[] = [];
  tenders: any[] = [];
  selectedApply: any = null;
  items: any[] = [];
  lots: any[] = [];
  equipment: any[] = [];
  distributors: any[] = [];

  showApplyForm = false;
  editingApplyId: number | null = null;
  applyForm = new FormGroup({
    tenderId: new FormControl<number | null>(null),
    status: new FormControl('DRAFT')
  });

  showItemForm = false;
  editingItemId: number | null = null;
  itemForm = new FormGroup({
    tenderLotId: new FormControl<number | null>(null),
    medEquipId: new FormControl<number | null>(null),
    distributorId: new FormControl<number | null>(null),
    offeredCost: new FormControl<number | null>(null),
    quantity: new FormControl<number | null>(null)
  });

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadApplies();
    this.api.getTenders().subscribe(data => this.tenders = data);
  }

  loadApplies() {
    this.api.getApplies().subscribe(data => this.applies = data);
  }

  // === Apply CRUD ===

  onAddApply() {
    this.editingApplyId = null;
    this.applyForm.reset({ status: 'DRAFT' });
    this.showApplyForm = true;
  }

  onEditApply(a: any) {
    this.editingApplyId = a.id;
    this.applyForm.patchValue({
      tenderId: a.tender?.id || null,
      status: a.status
    });
    this.showApplyForm = true;
  }

  onSaveApply() {
    const v = this.applyForm.value;
    const body: any = {
      status: v.status,
      tender: v.tenderId ? { id: v.tenderId } : null
    };
    const req = this.editingApplyId
      ? this.api.update('applies', this.editingApplyId, body)
      : this.api.create('applies', body);
    req.subscribe(() => {
      this.showApplyForm = false;
      this.loadApplies();
    });
  }

  onDeleteApply(id: number) {
    if (confirm('Удалить заявку?')) {
      this.api.delete('applies', id).subscribe(() => this.loadApplies());
    }
  }

  // === Detail view ===

  onOpen(a: any) {
    this.selectedApply = a;
    this.loadItems();
    this.loadReferenceData();
  }

  onBack() {
    this.selectedApply = null;
    this.showItemForm = false;
    this.loadApplies();
  }

  loadItems() {
    this.api.getApplyItems(this.selectedApply.id).subscribe(data => this.items = data);
  }

  loadReferenceData() {
    if (this.selectedApply.tender?.id) {
      this.api.getTenderLots(this.selectedApply.tender.id).subscribe(data => this.lots = data);
    }
    this.api.getEquipment().subscribe(data => this.equipment = data);
    this.api.getDistributors().subscribe(data => this.distributors = data);
  }

  // === Item CRUD ===

  onAddItem() {
    this.editingItemId = null;
    this.itemForm.reset();
    this.showItemForm = true;
  }

  onEditItem(it: any) {
    this.editingItemId = it.id;
    this.itemForm.patchValue({
      tenderLotId: it.tenderLot?.id || null,
      medEquipId: it.medEquipment?.id || null,
      distributorId: it.distributor?.id || null,
      offeredCost: it.offeredCost,
      quantity: it.quantity
    });
    this.showItemForm = true;
  }

  onSaveItem() {
    const v = this.itemForm.value;
    const body: any = {
      apply: { id: this.selectedApply.id },
      tenderLot: v.tenderLotId ? { id: v.tenderLotId } : null,
      medEquipment: v.medEquipId ? { id: v.medEquipId } : null,
      distributor: v.distributorId ? { id: v.distributorId } : null,
      offeredCost: v.offeredCost,
      quantity: v.quantity
    };
    const req = this.editingItemId
      ? this.api.update('apply-items', this.editingItemId, body)
      : this.api.create('apply-items', body);
    req.subscribe(() => {
      this.showItemForm = false;
      this.loadItems();
    });
  }

  onDeleteItem(id: number) {
    if (confirm('Удалить позицию?')) {
      this.api.delete('apply-items', id).subscribe(() => this.loadItems());
    }
  }
}
