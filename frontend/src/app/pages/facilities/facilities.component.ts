import { Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-facilities',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule],
  template: `
    <h2>Учреждения</h2>

    <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()">Добавить</button>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <label>Название *
        <input formControlName="name" />
      </label>
      <label>ИНН
        <input formControlName="inn" />
      </label>
      <label>Адрес
        <input formControlName="address" />
      </label>
      <label>Контакт
        <input formControlName="contact" />
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
          <th>ИНН</th>
          <th>Адрес</th>
          <th>Контакт</th>
          <th>Действия</th>
        </tr>
      </thead>
      <tbody>
        <tr *ngFor="let f of facilities">
          <td>{{ f.name }}</td>
          <td>{{ f.inn }}</td>
          <td>{{ f.address }}</td>
          <td>{{ f.contact }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(f)">Редактировать</button>
            <button class="btn btn-delete" (click)="onDelete(f.id)">Удалить</button>
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
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; }
    .form-actions { margin-top: 16px; }
  `]
})
export class FacilitiesComponent implements OnInit {
  facilities: any[] = [];
  showForm = false;
  editingId: number | null = null;
  form = new FormGroup({
    name: new FormControl('', Validators.required),
    inn: new FormControl(''),
    address: new FormControl(''),
    contact: new FormControl('')
  });

  constructor(private api: ApiService) {}

  ngOnInit() {
    this.loadData();
  }

  loadData() {
    this.api.getFacilities().subscribe(data => this.facilities = data);
  }

  onAdd() {
    this.editingId = null;
    this.form.reset();
    this.showForm = true;
  }

  onEdit(f: any) {
    this.editingId = f.id;
    this.form.patchValue(f);
    this.showForm = true;
  }

  onCancel() {
    this.showForm = false;
  }

  onSave() {
    const body = this.form.value;
    const req = this.editingId
      ? this.api.update('facilities', this.editingId, body)
      : this.api.create('facilities', body);
    req.subscribe(() => {
      this.showForm = false;
      this.loadData();
    });
  }

  onDelete(id: number) {
    if (confirm('Удалить?')) {
      this.api.delete('facilities', id).subscribe(() => this.loadData());
    }
  }
}
