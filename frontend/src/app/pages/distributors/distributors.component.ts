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
  selector: 'app-distributors',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, FormsModule, LucideDynamicIcon],
  template: `
    <h2>Дистрибьюторы</h2>
    <p class="subtitle">Поставщики медицинского оборудования</p>

    <input type="text" placeholder="Поиск по названию, ИНН, фамилии..." [(ngModel)]="searchQuery" (input)="applyFilter()" class="search-input" />

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm && auth.isAdmin()" (click)="onAdd()"><svg lucideIcon="plus" [size]="14"></svg> Добавить</button>
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

      <label class="specialization-label">Специализация</label>
      <div class="specialization-block">
        <label class="type-checkbox universal">
          <input type="checkbox" [checked]="isUniversal" [disabled]="isUniversal" (change)="onUniversalToggle()">
          <span>Все типы</span>
        </label>
        <label class="type-checkbox" *ngFor="let t of allTypes">
          <input type="checkbox" [checked]="selectedTypeIds.has(t.id)" (change)="onTypeToggle(t.id, $event)">
          <span>{{ t.name }}</span>
        </label>
      </div>

      <label class="specialization-label">Бренды (что возит)</label>
      <div class="brands-block">
        <span class="brand-chip" *ngFor="let b of brands; let i = index">
          {{ b }} <button type="button" class="brand-x" (click)="removeBrand(i)">×</button>
        </span>
      </div>
      <div class="brand-add">
        <input [(ngModel)]="newBrand" [ngModelOptions]="{standalone: true}" placeholder="напр. Mindray" (keyup.enter)="addBrand()" />
        <button type="button" class="btn-line" (click)="addBrand()">+ бренд</button>
      </div>

      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredDistributors.length === 0 && !showForm" class="empty">Нет данных</div>

    <table class="responsive-cards card-grid distributors-list" *ngIf="filteredDistributors.length > 0">
      <thead><tr><th>Название</th><th class="brands-col">Бренды</th><th>ИНН</th><th>Контактное лицо</th><th>Телефон</th><th>Эл. почта</th><th>Специализация</th><th *ngIf="auth.isAdmin()">Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let d of filteredDistributors">
          <!-- «Бренды» — колонка только для мобильной карточки: на десктопе скрыта
               (.brands-col), таблица там остаётся прежней. Бренды уже приходят в
               ответе списка (DistributorResponse.brands) — это тот же массив, что
               показывает форма редактирования. -->
          <td data-label="Название">{{ d.name }}</td><td class="brands-col" data-label="Бренды"><span class="brand-chip" *ngFor="let b of d.brands">{{ b }}</span></td><td data-label="ИНН">{{ d.inn }}</td>
          <td data-label="Контактное лицо">{{ d.lastName }} {{ d.firstName }}</td><td data-label="Телефон">{{ d.phone }}</td><td data-label="Эл. почта">{{ d.email }}</td>
          <td data-label="Специализация">
            <span *ngIf="!d.equipmentTypes || d.equipmentTypes.length === 0" class="tag tag-all">Все типы</span>
            <span *ngFor="let t of d.equipmentTypes" class="tag">{{ t.name }}</span>
          </td>
          <td class="actions" *ngIf="auth.isAdmin()">
            <button class="btn btn-edit" (click)="onEdit(d)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(d.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
    .search-input { width: 100%; max-width: 400px; padding: 8px 16px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; margin-bottom: 16px; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .search-input:focus { outline: none; border-color: var(--accent); }
    .toolbar { display: flex; align-items: center; gap: 16px; margin-bottom: 16px; }
    table { width: 100%; border-collapse: collapse; }
    /* Саму рамку и раскладку ячеек kit не задаёт (только border-color) — правило остаётся */
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid var(--border); font-size: 14px; }
    th { font-weight: 600; }
    /* tr:hover в kit нет вовсе — оставлено и токенизировано */
    tr:hover { background: var(--surface-2); }
    .actions { white-space: nowrap; }
    /* от .btn-cancel/.btn-edit остаётся только раскладка: цвет и геометрию даёт kit */
    .btn-cancel { margin-left: 8px; }
    .btn-edit { margin-right: 4px; }
    .edit-form { background: var(--surface-2); border: 1px solid var(--border); border-radius: 6px; padding: 20px; margin-bottom: 16px; max-width: 500px; }
    .edit-form label { display: block; margin-bottom: 12px; font-size: 14px; color: var(--text); font-weight: 500; }
    /* фон/цвет полям задаём явно: контейнер формы на --surface-2, иначе в тёмной теме поля остались бы белыми */
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); color: var(--text); }
    .input-error { border-color: var(--danger) !important; }
    .specialization-label { display: block; margin: 12px 0 6px; font-weight: 500; font-size: 14px; color: var(--text); }
    .specialization-block { display: flex; flex-wrap: wrap; gap: 8px 16px; padding: 8px 12px; background: var(--surface); border-radius: 6px; border: 1px solid var(--border); margin-bottom: 12px; }
    .type-checkbox { display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; margin: 0 !important; font-weight: 400 !important; }
    .type-checkbox.universal { font-weight: 600 !important; }
    .type-checkbox input { width: auto !important; padding: 0 !important; margin: 0 !important; display: inline !important; }
    .type-checkbox input[disabled] { opacity: 0.6; cursor: not-allowed; }
    .tag { display: inline-block; padding: 2px 8px; background: var(--border); border-radius: 4px; font-size: 12px; margin-right: 4px; margin-bottom: 2px; }
    .tag-all { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); font-weight: 600; }
    .brands-block { display: flex; flex-wrap: wrap; gap: 6px; margin: 6px 0; }
    /* чип бренда был индиго-тинтом — переведён тинт-формулой на акцент, как чип «возит <бренд>» в панели КП */
    .brand-chip { display: inline-flex; align-items: center; gap: 4px; background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border-radius: 999px; padding: 3px 10px; font-size: 12px; }
    .brand-x { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 14px; line-height: 1; }
    .brand-add { display: flex; gap: 8px; align-items: center; }
    .brand-add input { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    /* Пунктир намеренный: это кнопка «добавить ещё», а не обычная линейная.
       Заливку/цвет/рамку даёт kit-овская .btn-line — здесь только стиль рамки и
       геометрия (мельче kit, отдельная мелкая аффорданса), плюс cursor:
       базового класса «btn», который его даёт, у кнопки в шаблоне НЕТ.
       Не удалять как дубль. */
    .btn-line { border-style: dashed; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }

    /* Колонка «Бренды» живёт только в мобильной карточке — на десктопе скрыта,
       чтобы таблица осталась ровно такой, какой была (там бренды видны при
       редактировании). Показывается правилом в @media ниже. */
    .brands-col { display: none; }

    /* ============================================================
       МОБИЛЬНАЯ КАРТОЧКА — ПОСЛЕДНИЙ БЛОК В styles (CLAUDE.md §14: при равной
       специфичности позднее базовое правило молча перебивает @media-правило).
       Общее поведение card-grid — в глобальном styles.scss; здесь только
       раскладка этого списка.
       ============================================================ */
    @media (max-width: 900px) {
      /* Было 6 строк «подпись: значение» — 243–461px на карточку (простыню
         раздували чипы «Специализации»: до 17 видов МИ у одного поставщика).
         Стало три строки:
           НАЗВАНИЕ
           [бренд] [бренд] [бренд] +N
           почта                              [✎][🗑]
         Скрыты ИНН (техническое поле), «Специализация» (виды МИ — та самая
         простыня, разметка есть в карточке), «Контактное лицо» и «Телефон»:
         у всех 20 KZ-поставщиков и то и другое пусто, а рабочий канал —
         почта, ей же уходит запрос КП. */
      .distributors-list tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "name   name"
          "brands brands"
          "mail   act";
      }
      .distributors-list td[data-label="Название"] {
        grid-area: name; font-size: 15px; font-weight: 600; overflow: hidden;
        display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
      }
      /* Чипы в ОДНУ строку без переноса: у «Медико-Инновационных Технологий»
         22 бренда — переносом они дали бы карточку в пол-экрана. */
      /* justify-content: flex-start обязателен — механический слой ставит
         ячейкам space-between, и три чипа расползались по всей ширине,
         читаясь как три отдельных поля, а «+N» улетал к правому краю. */
      .distributors-list td.brands-col {
        grid-area: brands; display: flex; flex-wrap: nowrap; align-items: center;
        justify-content: flex-start; gap: 4px;
        position: relative; overflow: hidden; counter-reset: more;
      }
      /* display:block (а не inline-flex базового чипа) — иначе текст внутри
         становится анонимным флекс-элементом и многоточие до него не достаёт */
      .distributors-list .brand-chip {
        display: block; flex: 0 1 auto; min-width: 0;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      /* Видны первые три, остальные — счётчиком «+N». Считать без TypeScript
         умеет только CSS-счётчик, а он не работает на display:none: такой
         элемент не создаёт бокс и счётчик не увеличивает. Поэтому лишние чипы
         убираются ИЗ ПОТОКА (position:absolute) и гасятся visibility — бокс
         остаётся, счётчик считает, места не занимает. */
      .distributors-list .brand-chip:nth-child(n+4) {
        position: absolute; visibility: hidden; counter-increment: more;
      }
      .distributors-list td.brands-col:has(.brand-chip:nth-child(n+4))::after {
        display: block; content: "+" counter(more); flex: 0 0 auto;
        color: var(--text-muted); font-size: 11px;
      }
      .distributors-list td[data-label="Эл. почта"] {
        grid-area: mail; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .distributors-list td[data-label="ИНН"],
      .distributors-list td[data-label="Контактное лицо"],
      .distributors-list td[data-label="Телефон"],
      .distributors-list td[data-label="Специализация"] { display: none; }
      /* Действий две и обе иконочные — по правилу шага 4 остаются в ряд,
         меню «⋯» тут не нужно. */
      .distributors-list td.actions { grid-area: act; justify-content: flex-end; gap: 6px; }
      .distributors-list td.actions .btn-edit { margin-right: 0; }
    }
  `]
})
export class DistributorsComponent {
  distributors: any[] = [];
  validationErrors: any = {};
  filteredDistributors: any[] = [];
  searchQuery = '';
  showForm = false;
  editingId: number | null = null;
  allTypes: any[] = [];
  selectedTypeIds: Set<number> = new Set();
  brands: string[] = [];
  newBrand = '';
  get isUniversal(): boolean { return this.selectedTypeIds.size === 0; }
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
              private notify: NotificationService, private confirm: ConfirmService,
              public auth: AuthService) {
    this.loadData();
    this.api.getEquipmentTypes().subscribe(types => { this.allTypes = types; this.cdr.detectChanges(); });
  }

  onUniversalToggle() { this.selectedTypeIds.clear(); }
  onTypeToggle(id: number, e: Event) {
    const checked = (e.target as HTMLInputElement).checked;
    if (checked) this.selectedTypeIds.add(id); else this.selectedTypeIds.delete(id);
  }

  addBrand() {
    const b = (this.newBrand || '').trim();
    if (b && !this.brands.some(x => x.toLowerCase() === b.toLowerCase())) {
      this.brands.push(b);
    }
    this.newBrand = '';
  }
  removeBrand(i: number) { this.brands.splice(i, 1); }

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

  onAdd() {
    this.editingId = null; this.form.reset(); this.validationErrors = {};
    this.selectedTypeIds = new Set();
    this.brands = [];
    this.showForm = true;
  }
  onEdit(d: any) {
    this.editingId = d.id; this.form.patchValue(d); this.validationErrors = {};
    this.selectedTypeIds = new Set((d.equipmentTypes || []).map((t: any) => t.id));
    this.brands = [...(d.brands || [])];
    this.showForm = true;
  }
  onCancel() { this.showForm = false; }

  onSave() {
    const body: any = { ...this.form.value, equipmentTypeIds: Array.from(this.selectedTypeIds), brands: this.brands };
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
