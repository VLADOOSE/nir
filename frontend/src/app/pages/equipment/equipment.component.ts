import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { AuthService } from '../../services/auth.service';
import { EquipmentDetailModalComponent } from '../../components/equipment-detail-modal/equipment-detail-modal.component';

@Component({
  selector: 'app-equipment',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, EquipmentDetailModalComponent, LucideDynamicIcon],
  template: `
    <h2>Каталог оборудования</h2>
    <p class="subtitle">Медицинское оборудование для участия в тендерах</p>

    <app-equipment-detail-modal [equipment]="detailEquipment" (close)="detailEquipment = null"></app-equipment-detail-modal>

    <div class="filter-block">
      <input type="text" placeholder="Поиск по названию, производителю, типу..." [(ngModel)]="searchQuery" (input)="applyFilter()" class="search-input" />
      <div class="dims-filters">
        <label>Длина до (мм)<input type="number" [(ngModel)]="maxLength" (input)="applyFilter()" /></label>
        <label>Ширина до (мм)<input type="number" [(ngModel)]="maxWidth" (input)="applyFilter()" /></label>
        <label>Высота до (мм)<input type="number" [(ngModel)]="maxHeight" (input)="applyFilter()" /></label>
        <label>Вес до (кг)<input type="number" step="0.1" [(ngModel)]="maxWeight" (input)="applyFilter()" /></label>
        <button class="btn btn-reset-filter" (click)="resetFilters()">Сбросить</button>
      </div>
    </div>

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm && auth.isAdmin()" (click)="onAdd()"><svg lucideIcon="plus" [size]="14"></svg> Добавить</button>
      <span class="counter" *ngIf="filteredEquipment.length">Найдено: {{ filteredEquipment.length }} записей</span>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Название *<input formControlName="name" [class.input-error]="validationErrors.name" /><span class="field-error" *ngIf="validationErrors.name">{{ validationErrors.name }}</span></label>
      <label>Производитель *<input formControlName="manufact" [class.input-error]="validationErrors.manufact" /><span class="field-error" *ngIf="validationErrors.manufact">{{ validationErrors.manufact }}</span></label>
      <label>Тип
        <select [formControl]="form.controls.equipTypeId">
          <option [ngValue]="null">— не выбран —</option>
          <option *ngFor="let t of allTypes" [ngValue]="t.id">{{ t.name }}</option>
        </select>
      </label>
      <div class="dims-row">
        <label>Длина (мм)<input type="number" formControlName="lengthMm" [class.input-error]="validationErrors.lengthMm" /><span class="field-error" *ngIf="validationErrors.lengthMm">{{ validationErrors.lengthMm }}</span></label>
        <label>Ширина (мм)<input type="number" formControlName="widthMm" [class.input-error]="validationErrors.widthMm" /><span class="field-error" *ngIf="validationErrors.widthMm">{{ validationErrors.widthMm }}</span></label>
        <label>Высота (мм)<input type="number" formControlName="heightMm" [class.input-error]="validationErrors.heightMm" /><span class="field-error" *ngIf="validationErrors.heightMm">{{ validationErrors.heightMm }}</span></label>
      </div>
      <label>Вес (кг)<input type="number" step="0.01" formControlName="weightKg" [class.input-error]="validationErrors.weightKg" /><span class="field-error" *ngIf="validationErrors.weightKg">{{ validationErrors.weightKg }}</span></label>
      <label>Спецификация<textarea formControlName="spec" rows="3"></textarea></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredEquipment.length === 0 && !showForm" class="empty">Нет данных</div>

    <table *ngIf="filteredEquipment.length > 0">
      <thead>
        <tr><th>Название</th><th>Производитель</th><th>Тип</th><th>Д×Ш×В (мм)</th><th>Вес (кг)</th><th *ngIf="auth.isAdmin()">Действия</th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let e of filteredEquipment" class="row-clickable" (click)="detailEquipment = e">
          <td>{{ e.name }}</td><td>{{ e.manufact }}</td><td>{{ e.equipmentType?.name }}</td>
          <td>{{ e.lengthMm }}×{{ e.widthMm }}×{{ e.heightMm }}</td><td>{{ e.weightKg }}</td>
          <td class="actions" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
            <button class="btn btn-edit" (click)="onEdit(e)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(e.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .search-input { width: 100%; max-width: 400px; padding: 8px 16px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 14px; margin-bottom: 16px; box-sizing: border-box; }
    .search-input:focus { outline: none; border-color: #1a56db; }
    .filter-block { margin-bottom: 16px; }
    .dims-filters { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; align-items: flex-end; }
    .dims-filters label { font-size: 12px; color: #6b7280; font-weight: 500; }
    .dims-filters input { display: block; width: 110px; padding: 6px 8px; margin-top: 2px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 13px; }
    .btn-reset-filter { background: #e5e7eb; color: #374151; padding: 6px 12px; border: none; border-radius: 4px; cursor: pointer; font-size: 12px; height: 32px; align-self: flex-end; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .counter { color: #6b7280; font-size: 13px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    tr.row-clickable { cursor: pointer; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: #1a56db; color: #fff; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
  `]
})
export class EquipmentComponent {
  equipment: any[] = [];
  allApplyItems: any[] = [];
  filteredEquipment: any[] = [];
  searchQuery = '';
  maxLength: number | null = null;
  maxWidth: number | null = null;
  maxHeight: number | null = null;
  maxWeight: number | null = null;
  validationErrors: any = {};
  showForm = false;
  editingId: number | null = null;
  allTypes: any[] = [];
  detailEquipment: any = null;
  form = new FormGroup({
    name: new FormControl('', Validators.required),
    manufact: new FormControl('', Validators.required),
    equipTypeId: new FormControl<number | null>(null),
    lengthMm: new FormControl<number | null>(null),
    widthMm: new FormControl<number | null>(null),
    heightMm: new FormControl<number | null>(null),
    weightKg: new FormControl<number | null>(null),
    spec: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              public auth: AuthService) {
    this.loadData();
    this.api.getAllApplyItems().subscribe({
      next: items => { this.allApplyItems = items || []; },
      error: () => { this.allApplyItems = []; }
    });
    this.api.getEquipmentTypes().subscribe(t => {
      this.allTypes = t || [];
      this.cdr.detectChanges();
    });
  }

  loadData() {
    this.api.getEquipment().subscribe({
      next: data => { this.equipment = data; this.applyFilter(); this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки оборудования: ' + (err.error?.message || err.message))
    });
  }

  applyFilter() {
    const q = this.searchQuery.toLowerCase();
    this.filteredEquipment = this.equipment.filter((e: any) => {
      const textMatch = (e.name || '').toLowerCase().includes(q) ||
        (e.manufact || '').toLowerCase().includes(q) ||
        (e.equipmentType?.name || '').toLowerCase().includes(q);
      if (!textMatch) return false;
      if (this.maxLength != null && e.lengthMm > this.maxLength) return false;
      if (this.maxWidth != null && e.widthMm > this.maxWidth) return false;
      if (this.maxHeight != null && e.heightMm > this.maxHeight) return false;
      if (this.maxWeight != null && e.weightKg > this.maxWeight) return false;
      return true;
    });
  }

  resetFilters() {
    this.searchQuery = '';
    this.maxLength = null;
    this.maxWidth = null;
    this.maxHeight = null;
    this.maxWeight = null;
    this.applyFilter();
  }

  formatPrice(n: number): string { return n ? n.toLocaleString('ru-RU') : '0'; }
  onAdd() { this.editingId = null; this.form.reset(); this.validationErrors = {}; this.showForm = true; }
  onEdit(e: any) {
    this.editingId = e.id;
    this.form.patchValue({ ...e, equipTypeId: e.equipmentType?.id || null });
    this.validationErrors = {};
    this.showForm = true;
  }
  onCancel() { this.showForm = false; }

  onSave() {
    const body = this.form.value;
    const req = this.editingId ? this.api.update('equipment', this.editingId, body) : this.api.create('equipment', body);
    const wasEditing = this.editingId !== null;
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Оборудование обновлено' : 'Оборудование добавлено');
        this.loadData();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения данных' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDelete(id: number) {
    const usedCount = this.allApplyItems.filter(it => it.medEquipment?.id === id).length;
    if (usedCount > 0) {
      this.notify.error(`Невозможно удалить: оборудование используется в ${usedCount} позици${usedCount === 1 ? 'и' : 'ях'} заявок`);
      return;
    }
    this.confirm.ask('Удалить оборудование?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('equipment', id).subscribe({
          next: () => { this.notify.success('Оборудование удалено'); this.loadData(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
