import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-equipment-types',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Типы оборудования</h2>
    <p class="subtitle">Справочник типов медицинского оборудования</p>

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить тип</button>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Название *
        <input formControlName="name" [class.input-error]="validationErrors.name" />
        <span class="field-error" *ngIf="validationErrors.name">{{ validationErrors.name }}</span>
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="showForm = false">Отмена</button>
      </div>
    </form>

    <div *ngIf="types.length === 0 && !showForm" class="empty">Нет типов</div>

    <table class="responsive-cards" *ngIf="types.length > 0">
      <thead><tr><th>Название</th><th>Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let t of types">
          <td data-label="Название">{{ t.name }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(t)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(t.id)">Удалить</button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .toolbar { margin-bottom: 16px; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }
    table { width: 100%; max-width: 600px; border-collapse: collapse; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #e5e7eb; font-size: 14px; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; }
    .actions { white-space: nowrap; }
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; margin-right: 4px; }
    .btn-add, .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; margin-left: 8px; }
    .btn-edit { background: #f59e0b; color: #fff; }
    .btn-delete { background: #ef4444; color: #fff; }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 400px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
    .field-error { display: block; color: #dc2626; font-size: 12px; margin-top: 2px; }
    .input-error { border-color: #dc2626 !important; }
    .error-banner { background: #fee2e2; color: #991b1b; padding: 8px 12px; border-radius: 4px; font-size: 13px; margin-bottom: 12px; }
  `]
})
export class EquipmentTypesComponent {
  types: any[] = [];
  validationErrors: any = {};
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({ name: new FormControl('', Validators.required) });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService) {
    this.load();
  }

  load() {
    this.api.getEquipmentTypes().subscribe({
      next: data => { this.types = data; this.cdr.detectChanges(); },
      error: err => this.notify.error('Ошибка загрузки: ' + (err.error?.message || err.message))
    });
  }

  onAdd() { this.editingId = null; this.form.reset(); this.validationErrors = {}; this.showForm = true; }
  onEdit(t: any) { this.editingId = t.id; this.form.patchValue(t); this.validationErrors = {}; this.showForm = true; }

  onSave() {
    const body = this.form.value;
    const wasEditing = this.editingId !== null;
    const req = this.editingId
      ? this.api.updateEquipmentType(this.editingId, body)
      : this.api.createEquipmentType(body);
    req.subscribe({
      next: () => {
        this.showForm = false; this.validationErrors = {};
        this.notify.success(wasEditing ? 'Тип обновлён' : 'Тип добавлен');
        this.load();
      },
      error: (err: any) => {
        if (err.status === 400 && err.error?.errors) { this.validationErrors = err.error.errors; }
        else if (err.status === 400 && err.error?.message) { this.validationErrors = { _general: err.error.message }; }
        else { this.validationErrors = { _general: 'Ошибка сохранения' }; }
        this.cdr.detectChanges();
      }
    });
  }

  onDelete(id: number) {
    this.confirm.ask('Удалить тип оборудования?', 'Если тип используется в оборудовании или лотах — удалить нельзя.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteEquipmentType(id).subscribe({
          next: () => { this.notify.success('Тип удалён'); this.load(); },
          error: err => this.notify.error(err.error?.message || 'Ошибка удаления')
        });
      });
  }
}
