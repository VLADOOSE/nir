import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Пользователи</h2>

    <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить</button>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <label>Логин *
        <input formControlName="username" />
      </label>
      <label>ФИО
        <input formControlName="fullName" />
      </label>
      <label>Роль
        <select formControlName="role">
          <option value="ROLE_ADMIN">ROLE_ADMIN</option>
          <option value="ROLE_USER">ROLE_USER</option>
        </select>
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <table>
      <thead>
        <tr>
          <th>Логин</th>
          <th>ФИО</th>
          <th>Роль</th>
          <th>Действия</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let u of users">
          <td>{{ u.username }}</td>
          <td>{{ u.fullName }}</td>
          <td>{{ u.role }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(u)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(u.id)">Удалить</button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0 0 16px; font-size: 20px; color: #111827; }
    table { width: 100%; border-collapse: collapse; }
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
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 500px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
  `]
})
export class UsersComponent implements OnInit {
  users: any[] = [];
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({
    username: new FormControl('', Validators.required),
    fullName: new FormControl(''),
    role: new FormControl('ROLE_USER')
  });

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    this.api.getUsers().subscribe(data => this.users = data);
  }

  onAdd() {
    this.editingId = null;
    this.form.reset({ role: 'ROLE_USER' });
    this.showForm = true;
  }

  onEdit(u: any) {
    this.editingId = u.id;
    this.form.patchValue(u);
    this.showForm = true;
  }

  onCancel() {
    this.showForm = false;
  }

  onSave() {
    const body = this.form.value;
    const req = this.editingId
      ? this.api.update('users', this.editingId, body)
      : this.api.create('users', body);
    req.subscribe(() => {
      this.showForm = false;
      this.loadData();
    });
  }

  onDelete(id: number) {
    if (confirm('Удалить?')) {
      this.api.delete('users', id).subscribe(() => this.loadData());
    }
  }
}
