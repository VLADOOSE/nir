import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-distributors',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule],
  template: `
    <h2>Дистрибьюторы</h2>
    <p class="subtitle">Поставщики медицинского оборудования</p>

    <input type="text" placeholder="Поиск по названию, ИНН, фамилии..." [(ngModel)]="searchQuery" (input)="applyFilter()" class="search-input" />

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить</button>
      <span class="counter" *ngIf="filteredDistributors.length">Найдено: {{ filteredDistributors.length }} записей</span>
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
      <label>Сайт<input formControlName="website" [class.input-error]="validationErrors.website" /><span class="field-error" *ngIf="validationErrors.website">{{ validationErrors.website }}</span></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredDistributors.length === 0 && !showForm" class="empty">Нет данных</div>

    <table *ngIf="filteredDistributors.length > 0">
      <thead><tr><th>Название</th><th>ИНН</th><th>Контактное лицо</th><th>Телефон</th><th>Эл. почта</th><th>Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let d of filteredDistributors">
          <td>{{ d.name }}</td><td>{{ d.inn }}</td>
          <td>{{ d.lastName }} {{ d.firstName }}</td><td>{{ d.phone }}</td><td>{{ d.email }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(d)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(d.id)">Удалить</button>
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
export class DistributorsComponent {
  distributors: any[] = [];
  validationErrors: any = {};
  filteredDistributors: any[] = [];
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
    email: new FormControl(''),
    website: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService) { this.loadData(); }

  loadData() {
    this.api.getDistributors().subscribe({
      next: data => { this.distributors = data; this.applyFilter(); this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки дистрибьюторов: ' + (err.error?.message || err.message))
    });
  }

  applyFilter() {
    const q = this.searchQuery.toLowerCase();
    this.filteredDistributors = this.distributors.filter((d: any) =>
      (d.name || '').toLowerCase().includes(q) ||
      (d.inn || '').includes(q) ||
      (d.lastName || '').toLowerCase().includes(q)
    );
  }

  onAdd() { this.editingId = null; this.form.reset(); this.validationErrors = {}; this.showForm = true; }
  onEdit(d: any) { this.editingId = d.id; this.form.patchValue(d); this.validationErrors = {}; this.showForm = true; }
  onCancel() { this.showForm = false; }

  onSave() {
    const body = this.form.value;
    const req = this.editingId ? this.api.update('distributors', this.editingId, body) : this.api.create('distributors', body);
    const wasEditing = this.editingId !== null;
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Дистрибьютор обновлён' : 'Дистрибьютор создан');
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
    this.confirm.ask('Удалить дистрибьютора?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('distributors', id).subscribe({
          next: () => { this.notify.success('Дистрибьютор удалён'); this.loadData(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
