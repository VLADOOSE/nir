import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Пользователи</h2>
    <p class="subtitle">Управление учётными записями пользователей системы</p>

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить</button>
      <span class="counter" *ngIf="users.length">Найдено: {{ users.length }} записей</span>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Логин *<input formControlName="username" [class.input-error]="validationErrors.username" /><span class="field-error" *ngIf="validationErrors.username">{{ validationErrors.username }}</span></label>
      <label>ФИО<input formControlName="fullName" [class.input-error]="validationErrors.fullName" /><span class="field-error" *ngIf="validationErrors.fullName">{{ validationErrors.fullName }}</span></label>
      <label>Роль
        <select formControlName="role" [class.input-error]="validationErrors.role">
          <option value="ROLE_ADMIN">ROLE_ADMIN</option>
          <option value="ROLE_USER">ROLE_USER</option>
        </select>
        <span class="field-error" *ngIf="validationErrors.role">{{ validationErrors.role }}</span>
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="users.length === 0 && !showForm" class="empty">Нет данных</div>

    <table *ngIf="users.length > 0">
      <thead><tr><th>Логин</th><th>ФИО</th><th>Роль</th><th>Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let u of users">
          <td>{{ u.username }}</td><td>{{ u.fullName }}</td><td>{{ u.role }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(u)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(u.id)">Удалить</button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
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
    .edit-form input, .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
  `]
})
export class UsersComponent {
  users: any[] = [];
  validationErrors: any = {};
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({
    username: new FormControl('', Validators.required),
    fullName: new FormControl(''),
    role: new FormControl('ROLE_USER')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService) {
    this.loadData();
  }

  loadData() {
    this.api.getUsers().subscribe({
      next: data => { this.users = data; this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки пользователей: ' + (err.error?.message || err.message))
    });
  }

  onAdd() { this.editingId = null; this.form.reset({ role: 'ROLE_USER' }); this.validationErrors = {}; this.showForm = true; }
  onEdit(u: any) { this.editingId = u.id; this.form.patchValue(u); this.validationErrors = {}; this.showForm = true; }
  onCancel() { this.showForm = false; }

  onSave() {
    const body = this.form.value;
    const req = this.editingId ? this.api.update('users', this.editingId, body) : this.api.create('users', body);
    const wasEditing = this.editingId !== null;
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Пользователь обновлён' : 'Пользователь создан');
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
    this.confirm.ask('Удалить пользователя?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.delete('users', id).subscribe({
          next: () => { this.notify.success('Пользователь удалён'); this.loadData(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
