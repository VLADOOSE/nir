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
    h2 { margin: 0; font-size: 20px; }
    .toolbar { margin-bottom: 16px; }
    table { width: 100%; max-width: 600px; border-collapse: collapse; }
    /* Саму рамку и раскладку ячеек kit не задаёт (только border-color) — правило остаётся */
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 14px; }
    th { font-weight: 600; }
    .actions { white-space: nowrap; }
    /* от .btn/.btn-cancel остаётся только раскладка: цвет и геометрию даёт kit.
       margin-right на базовом .btn — не декор, а зазор между «Редактировать» и «Удалить»
       в колонке действий (kit отступов не даёт), поэтому правило сохранено */
    .btn { margin-right: 4px; }
    .btn-cancel { margin-left: 8px; }
    .edit-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 400px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
    /* фон/цвет полю задаём явно: контейнер формы на --surface-2, а правила input в kit
       намеренно нет — иначе поле названия осталось бы белым в тёмной теме */
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); color: var(--text); }
    .input-error { border-color: var(--danger) !important; }
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
