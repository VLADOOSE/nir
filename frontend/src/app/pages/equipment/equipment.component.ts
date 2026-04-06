import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-equipment',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Каталог оборудования</h2>

    <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить</button>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <label>Название *
        <input formControlName="name" />
      </label>
      <label>Производитель *
        <input formControlName="manufact" />
      </label>
      <label>Тип
        <select formControlName="equipType">
          <option value="">— не выбран —</option>
          <option value="УЗИ">УЗИ</option>
          <option value="Рентген">Рентген</option>
          <option value="ИВЛ">ИВЛ</option>
          <option value="Монитор">Монитор</option>
        </select>
      </label>
      <label>Цена (руб.) *
        <input type="number" formControlName="cost" />
      </label>
      <div class="dims-row">
        <label>Длина (мм)
          <input type="number" formControlName="lengthMm" />
        </label>
        <label>Ширина (мм)
          <input type="number" formControlName="widthMm" />
        </label>
        <label>Высота (мм)
          <input type="number" formControlName="heightMm" />
        </label>
      </div>
      <label>Вес (кг)
        <input type="number" step="0.01" formControlName="weightKg" />
      </label>
      <label>Спецификация
        <textarea formControlName="spec" rows="3"></textarea>
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <table>
      <thead>
        <tr>
          <th>Название</th>
          <th>Производитель</th>
          <th>Тип</th>
          <th>Цена</th>
          <th>Д×Ш×В (мм)</th>
          <th>Вес (кг)</th>
          <th>Действия</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let e of equipment">
          <td>{{ e.name }}</td>
          <td>{{ e.manufact }}</td>
          <td>{{ e.equipType }}</td>
          <td>{{ e.cost }}</td>
          <td>{{ e.lengthMm }}×{{ e.widthMm }}×{{ e.heightMm }}</td>
          <td>{{ e.weightKg }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(e)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(e.id)">Удалить</button>
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
    .edit-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { margin-top: 16px; }
  `]
})
export class EquipmentComponent implements OnInit {
  equipment: any[] = [];
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({
    name: new FormControl('', Validators.required),
    manufact: new FormControl('', Validators.required),
    equipType: new FormControl(''),
    cost: new FormControl<number | null>(null, Validators.required),
    lengthMm: new FormControl<number | null>(null),
    widthMm: new FormControl<number | null>(null),
    heightMm: new FormControl<number | null>(null),
    weightKg: new FormControl<number | null>(null),
    spec: new FormControl('')
  });

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    this.api.getEquipment().subscribe(data => this.equipment = data);
  }

  onAdd() {
    this.editingId = null;
    this.form.reset();
    this.showForm = true;
  }

  onEdit(e: any) {
    this.editingId = e.id;
    this.form.patchValue(e);
    this.showForm = true;
  }

  onCancel() {
    this.showForm = false;
  }

  onSave() {
    const body = this.form.value;
    const req = this.editingId
      ? this.api.update('equipment', this.editingId, body)
      : this.api.create('equipment', body);
    req.subscribe(() => {
      this.showForm = false;
      this.loadData();
    });
  }

  onDelete(id: number) {
    if (confirm('Удалить?')) {
      this.api.delete('equipment', id).subscribe(() => this.loadData());
    }
  }
}
