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
        <label>Длина до (мм)<input type="number" min="1" [(ngModel)]="maxLength" (input)="applyFilter()" /></label>
        <label>Ширина до (мм)<input type="number" min="1" [(ngModel)]="maxWidth" (input)="applyFilter()" /></label>
        <label>Высота до (мм)<input type="number" min="1" [(ngModel)]="maxHeight" (input)="applyFilter()" /></label>
        <label>Вес до (кг)<input type="number" min="0.01" step="0.1" [(ngModel)]="maxWeight" (input)="applyFilter()" /></label>
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
        <label>Длина (мм)<input type="number" min="1" formControlName="lengthMm" [class.input-error]="validationErrors.lengthMm" /><span class="field-error" *ngIf="validationErrors.lengthMm">{{ validationErrors.lengthMm }}</span></label>
        <label>Ширина (мм)<input type="number" min="1" formControlName="widthMm" [class.input-error]="validationErrors.widthMm" /><span class="field-error" *ngIf="validationErrors.widthMm">{{ validationErrors.widthMm }}</span></label>
        <label>Высота (мм)<input type="number" min="1" formControlName="heightMm" [class.input-error]="validationErrors.heightMm" /><span class="field-error" *ngIf="validationErrors.heightMm">{{ validationErrors.heightMm }}</span></label>
      </div>
      <label>Вес (кг)<input type="number" min="0.01" step="0.01" formControlName="weightKg" [class.input-error]="validationErrors.weightKg" /><span class="field-error" *ngIf="validationErrors.weightKg">{{ validationErrors.weightKg }}</span></label>
      <label>Спецификация<textarea formControlName="spec" rows="3"></textarea></label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredEquipment.length === 0 && !showForm" class="empty">Нет данных</div>

    <table class="responsive-cards card-grid equipment-list" *ngIf="filteredEquipment.length > 0">
      <thead>
        <tr><th>Название</th><th>Производитель</th><th>Тип</th><th>Д×Ш×В (мм)</th><th>Вес (кг)</th><th *ngIf="auth.isAdmin()">Действия</th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let e of filteredEquipment" class="row-clickable" (click)="detailEquipment = e">
          <td data-label="Название">{{ e.name }}</td><td data-label="Производитель">{{ e.manufact }}</td><td data-label="Тип">{{ e.equipmentType?.name }}</td>
          <td data-label="Д×Ш×В (мм)">{{ e.lengthMm }}×{{ e.widthMm }}×{{ e.heightMm }}</td><td data-label="Вес (кг)">{{ e.weightKg }}</td>
          <td class="actions" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
            <button class="btn btn-edit" (click)="onEdit(e)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(e.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
    .search-input { width: 100%; max-width: 400px; padding: 8px 16px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; margin-bottom: 16px; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .search-input:focus { outline: none; border-color: var(--accent); }
    .filter-block { margin-bottom: 16px; }
    .dims-filters { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 8px; align-items: flex-end; }
    .dims-filters label { font-size: 12px; color: var(--text-muted); font-weight: 500; }
    /* фон/цвет полям задаём явно: без этого в тёмной теме поля фильтра остались бы белыми */
    .dims-filters input { display: block; width: 110px; padding: 6px 8px; margin-top: 2px; border: 1px solid var(--border); border-radius: 4px; font-size: 13px; background: var(--surface); color: var(--text); }
    /* кнопка несёт базовый «btn» — заливку, геометрию и ховер даёт kit; здесь только раскладка в ряду фильтров */
    .btn-reset-filter { height: 32px; align-self: flex-end; }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    table { width: 100%; border-collapse: collapse; }
    /* Саму рамку и раскладку ячеек kit не задаёт (только border-color) — правило остаётся */
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 14px; }
    th { font-weight: 600; }
    /* tr:hover в kit нет вовсе — оставлено и токенизировано */
    tr:hover { background: var(--surface-2); }
    tr.row-clickable { cursor: pointer; }
    .actions { white-space: nowrap; }
    /* от .btn-cancel/.btn-edit остаётся только раскладка: цвет и геометрию даёт kit */
    .btn-cancel { margin-left: 8px; }
    .btn-edit { margin-right: 4px; }
    .edit-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
    /* фон/цвет полям задаём явно: контейнер формы на --surface-2, иначе в тёмной теме поля остались бы белыми */
    .edit-form input, .edit-form select, .edit-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; font-family: inherit; background: var(--surface); color: var(--text); }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .input-error { border-color: var(--danger) !important; }

    /* ============================================================
       МОБИЛЬНАЯ КАРТОЧКА — ПОСЛЕДНИЙ БЛОК В styles (CLAUDE.md §14: при равной
       специфичности позднее базовое правило молча перебивает @media-правило).
       Общее поведение card-grid — в глобальном styles.scss; здесь только
       раскладка этого списка.
       ============================================================ */
    @media (max-width: 900px) {
      /* Было 5 строк «подпись: значение» — 186–266px на карточку. Стало три:
           НАЗВАНИЕ (2 строки, «…»)        [✎]
           производитель                   [🗑]
           тип
         Скрыты «Д×Ш×В» и «Вес»: в списке это не опознавательные признаки, а
         КРИТЕРИИ ОТБОРА — для них прямо над списком стоит блок фильтров
         «Длина/Ширина/Высота/Вес до». Сами значения видны в карточке позиции
         (клик по строке) и в форме редактирования. На KZ они к тому же пусты
         почти везде: каталог наполняется из реестра НЦЭЛС, а там габаритов нет. */
      .equipment-list tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "name act"
          "manu act"
          "type act";
      }
      /* Клэмп работает прямо на ячейке: внутри неё только текст. Там, где в
         ячейке лежит вложенный флекс (facilities), -webkit-box блокифицируется
         и клэмп молча пропадает — здесь этого случая нет. Две строки, а не
         одна: у KZ-позиций имя из реестра длиной до 196 символов, и по первой
         строке «Тест-система иммуноферментная для определения…» они не
         различаются. */
      .equipment-list td[data-label="Название"] {
        grid-area: name; font-size: 15px; font-weight: 600; overflow: hidden;
        display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
      }
      /* display:block (а не флекс механического слоя) — иначе текст внутри
         становится анонимным флекс-элементом и многоточие до него не достаёт */
      .equipment-list td[data-label="Производитель"] {
        grid-area: manu; display: block; font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      /* Тип приглушён сильнее производителя: на РФ он почти дословно повторяет
         начало названия («Компьютерный томограф Canon Aquilion ONE» → тип
         «Компьютерный томограф»), на KZ пуст у 6 позиций из 8 — тогда строка
         схлопывается в ноль, и карточка сама становится на 14px ниже. */
      .equipment-list td[data-label="Тип"] {
        grid-area: type; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .equipment-list td[data-label="Д×Ш×В (мм)"],
      .equipment-list td[data-label="Вес (кг)"] { display: none; }
      /* Действий две и обе иконочные — по правилу шага 4 остаются в ряд,
         меню «⋯» тут не нужно. Своя колонка на все три ряда: если отдать
         кнопки в общую раскладку, грид раздаёт max-content растянутого
         названия трекам с интринсик-размером и колонка действий раздувается
         (ловили в facilities). У оператора-неадмина ячейки нет вовсе —
         колонка схлопывается в 0 и весь текст получает полную ширину. */
      .equipment-list td.actions { grid-area: act; justify-content: flex-end; gap: 6px; }
      .equipment-list td.actions .btn-edit { margin-right: 0; }
    }
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
    lengthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    widthMm: new FormControl<number | null>(null, [Validators.min(1)]),
    heightMm: new FormControl<number | null>(null, [Validators.min(1)]),
    weightKg: new FormControl<number | null>(null, [Validators.min(0.01)]),
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
