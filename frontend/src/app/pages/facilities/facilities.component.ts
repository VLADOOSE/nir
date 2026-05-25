import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-facilities',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, LucideDynamicIcon],
  template: `
    <h2>Учреждения</h2>
    <p class="subtitle">Справочник медицинских учреждений — заказчиков тендеров</p>

    <input type="text" placeholder="Поиск по названию, ИНН, адресу, фамилии..." [(ngModel)]="searchQuery" (input)="applyFilter()" class="search-input" />

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm && auth.isAdmin()" (click)="onAdd()"><svg lucideIcon="plus" [size]="14"></svg> Добавить</button>
      <span class="counter" *ngIf="filteredFacilities.length">Найдено: {{ filteredFacilities.length }} записей</span>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Название *<input formControlName="name" [class.input-error]="validationErrors.name" /><span class="field-error" *ngIf="validationErrors.name">{{ validationErrors.name }}</span></label>
      <label>ИНН<input formControlName="inn" [class.input-error]="validationErrors.inn" /><span class="field-error" *ngIf="validationErrors.inn">{{ validationErrors.inn }}</span></label>
      <label>Адрес<input formControlName="address" [class.input-error]="validationErrors.address" /><span class="field-error" *ngIf="validationErrors.address">{{ validationErrors.address }}</span></label>
      <label>Фамилия<input formControlName="lastName" [class.input-error]="validationErrors.lastName" /><span class="field-error" *ngIf="validationErrors.lastName">{{ validationErrors.lastName }}</span></label>
      <label>Имя<input formControlName="firstName" [class.input-error]="validationErrors.firstName" /><span class="field-error" *ngIf="validationErrors.firstName">{{ validationErrors.firstName }}</span></label>
      <label>Отчество<input formControlName="middleName" [class.input-error]="validationErrors.middleName" /><span class="field-error" *ngIf="validationErrors.middleName">{{ validationErrors.middleName }}</span></label>
      <label>Телефон<input formControlName="phone" [class.input-error]="validationErrors.phone" /><span class="field-error" *ngIf="validationErrors.phone">{{ validationErrors.phone }}</span></label>
      <label>Эл. почта<input formControlName="email" [class.input-error]="validationErrors.email" /><span class="field-error" *ngIf="validationErrors.email">{{ validationErrors.email }}</span></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredFacilities.length === 0 && !showForm" class="empty">Нет данных</div>

    <table *ngIf="filteredFacilities.length > 0">
      <thead>
        <tr><th>Название</th><th>ИНН</th><th>Адрес</th><th>Контактное лицо</th><th>Телефон</th><th>Эл. почта</th><th *ngIf="auth.isAdmin()">Действия</th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let f of filteredFacilities">
          <td>{{ f.name }}</td><td>{{ f.inn }}</td><td>{{ f.address }}</td>
          <td>{{ f.lastName }} {{ f.firstName }}</td><td>{{ f.phone }}</td><td>{{ f.email }}</td>
          <td class="actions" *ngIf="auth.isAdmin()">
            <button class="btn btn-edit" (click)="onEdit(f)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(f.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
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
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    .counter { color: #6b7280; font-size: 13px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }
    table { width: 100%; border-collapse: collapse; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    tr:hover { background: #f9fafb; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-add { background: #1a56db; color: #fff; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; margin-right: 4px; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 500px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
  `]
})
export class FacilitiesComponent {
  facilities: any[] = [];
  tenders: any[] = [];
  validationErrors: any = {};
  filteredFacilities: any[] = [];
  searchQuery = '';
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({
    name: new FormControl('', Validators.required),
    inn: new FormControl(''),
    address: new FormControl(''),
    lastName: new FormControl(''),
    firstName: new FormControl(''),
    middleName: new FormControl(''),
    phone: new FormControl(''),
    email: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              public auth: AuthService) {
    this.loadData();
    this.api.getTenders().subscribe({
      next: data => { this.tenders = data || []; },
      error: () => { this.tenders = []; }
    });
  }

  loadData() {
    this.api.getFacilities().subscribe({
      next: data => { this.facilities = data; this.applyFilter(); this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки учреждений: ' + (err.error?.message || err.message))
    });
  }

  applyFilter() {
    const q = this.searchQuery.toLowerCase();
    this.filteredFacilities = this.facilities.filter((f: any) =>
      (f.name || '').toLowerCase().includes(q) ||
      (f.inn || '').includes(q) ||
      (f.address || '').toLowerCase().includes(q) ||
      (f.lastName || '').toLowerCase().includes(q)
    );
  }

  onAdd() { this.editingId = null; this.form.reset(); this.validationErrors = {}; this.showForm = true; }
  onEdit(f: any) { this.editingId = f.id; this.form.patchValue(f); this.validationErrors = {}; this.showForm = true; }
  onCancel() { this.showForm = false; }

  onSave() {
    const body = this.form.value;
    const req = this.editingId ? this.api.update('facilities', this.editingId, body) : this.api.create('facilities', body);
    const wasEditing = this.editingId !== null;
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Учреждение обновлено' : 'Учреждение добавлено');
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
    const usedCount = this.tenders.filter(t => t.facility?.id === id).length;
    if (usedCount > 0) {
      this.notify.error(`Невозможно удалить: с учреждением связано ${usedCount} тендер${usedCount === 1 ? '' : usedCount < 5 ? 'а' : 'ов'}`);
      return;
    }
    this.confirm.ask('Удалить учреждение?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('facilities', id).subscribe({
          next: () => { this.notify.success('Учреждение удалено'); this.loadData(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
