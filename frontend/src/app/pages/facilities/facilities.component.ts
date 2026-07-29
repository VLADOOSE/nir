import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { FormsModule } from '@angular/forms';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { AuthService } from '../../services/auth.service';
import { MarketService } from '../../services/market.service';
import { KZ_REGIONS } from '../../shared/kz-regions';

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
      <label>{{ isKz() ? 'БИН' : 'ИНН' }}<input formControlName="inn" [class.input-error]="validationErrors.inn" /><span class="field-error" *ngIf="validationErrors.inn">{{ validationErrors.inn }}</span></label>
      <label>Адрес<input formControlName="address" [class.input-error]="validationErrors.address" /><span class="field-error" *ngIf="validationErrors.address">{{ validationErrors.address }}</span></label>
      <label>Фамилия<input formControlName="lastName" [class.input-error]="validationErrors.lastName" /><span class="field-error" *ngIf="validationErrors.lastName">{{ validationErrors.lastName }}</span></label>
      <label>Имя<input formControlName="firstName" [class.input-error]="validationErrors.firstName" /><span class="field-error" *ngIf="validationErrors.firstName">{{ validationErrors.firstName }}</span></label>
      <label>Отчество<input formControlName="middleName" [class.input-error]="validationErrors.middleName" /><span class="field-error" *ngIf="validationErrors.middleName">{{ validationErrors.middleName }}</span></label>
      <label>Телефон<input formControlName="phone" [class.input-error]="validationErrors.phone" /><span class="field-error" *ngIf="validationErrors.phone">{{ validationErrors.phone }}</span></label>
      <label>Эл. почта<input formControlName="email" [class.input-error]="validationErrors.email" /><span class="field-error" *ngIf="validationErrors.email">{{ validationErrors.email }}</span></label>
      <label *ngIf="isKz()">Регион
        <select formControlName="region">
          <option value="">— не выбран —</option>
          <option *ngFor="let r of REGIONS" [value]="r">{{ r }}</option>
        </select>
      </label>
      <label *ngIf="isKz()" class="check">
        <input type="checkbox" formControlName="monitorTenders" /> Мониторить тендеры (goszakup)
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="filteredFacilities.length === 0 && !showForm" class="empty">Нет данных</div>

    <table class="responsive-cards card-grid facilities-list" *ngIf="filteredFacilities.length > 0">
      <thead>
        <tr><th>Название</th><th>{{ isKz() ? 'БИН' : 'ИНН' }}</th><th>Регион</th><th>Адрес</th><th>Контактное лицо</th><th>Телефон</th><th>Эл. почта</th><th *ngIf="auth.isAdmin()">Действия</th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let f of filteredFacilities">
          <!-- Обёртки нужны мобильной карточке: голый текстовый узел рядом с
               бейджем стилями не достанешь, а многоточие на второй строке даёт
               только -webkit-line-clamp, который требует display:-webkit-box и
               потому НЕ работает на флекс-элементе (тот блокифицируется).
               Поэтому .f-name — флекс-элемент рядом с бейджем, а клэмп висит на
               вложенном .f-name-text. На десктопе оба — обычные inline-span
               без единого правила, вид таблицы не меняется. -->
          <td data-label="Название"><span class="f-name"><span class="f-name-text">{{ f.name }}</span></span><span class="badge-monitor" *ngIf="f.monitorTenders">🔔 тендеры</span></td><td data-label="БИН/ИНН">{{ f.inn }}</td><td data-label="Регион">{{ f.region }}</td><td data-label="Адрес">{{ f.address }}</td>
          <td data-label="Контактное лицо">{{ f.lastName }} {{ f.firstName }}</td><td data-label="Телефон">{{ f.phone }}</td><td data-label="Эл. почта">{{ f.email }}</td>
          <td class="actions" *ngIf="auth.isAdmin()">
            <button class="btn btn-edit" (click)="onEdit(f)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(f.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
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
    /* фон/цвет полям задаём явно: контейнер формы на --surface-2, иначе в тёмной теме поля и селектор региона остались бы белыми */
    .edit-form input { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); color: var(--text); }
    .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); color: var(--text); }
    .edit-form label.check { display: flex; align-items: center; gap: 8px; font-weight: 500; }
    .edit-form label.check input { display: inline-block; width: auto; margin-top: 0; }
    /* бейдж «тендеры» — не kit-овский .badge-<СТАТУС>, геометрия остаётся здесь;
       синий тинт переведён тинт-формулой на акцент */
    .badge-monitor { display: inline-block; margin-left: 6px; padding: 1px 7px; background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border-radius: 10px; font-size: 11px; vertical-align: middle; }
    .input-error { border-color: var(--danger) !important; }

    /* ============================================================
       МОБИЛЬНАЯ КАРТОЧКА — ПОСЛЕДНИЙ БЛОК В styles (CLAUDE.md §14: при равной
       специфичности позднее базовое правило молча перебивает @media-правило).
       Общее поведение card-grid — в глобальном styles.scss; здесь только
       раскладка этого списка.
       ============================================================ */
    @media (max-width: 900px) {
      /* Было 7 строк «подпись: значение»: 286–308px на РФ, 175–339px на KZ.
         Стало три строки:
           НАЗВАНИЕ (до двух строк)              [🔔 тендеры]
           регион · адрес                            [✎][🗑]
           телефон · почта
         Скрыты БИН/ИНН (техническое поле, видно при редактировании) и
         «Контактное лицо»: ФИО пусто у всех 31 KZ-учреждения, а на РФ
         дублирует телефон/почту — по ним и связываются.
         Регион и адрес стоят в одной строке намеренно: они дополняют друг
         друга — на KZ заполнен регион (адрес пуст у 14 из 31), на РФ регион
         пуст у всех, а адрес несёт город. Пустая ячейка схлопывается в 0. */
      /* Текстовые колонки — minmax(0, 1fr), а не content-based треки: ячейка
         названия растянута на две колонки, и её max-content (всё имя в одну
         строку, ~500px) грид раздаёт трекам с ИНТРИНСИК-размером. На «auto»
         это ломало раскладку: колонка действий раздувалась до 151px, а с
         max-content — до 513px, и карточка уезжала за экран. 1fr не
         интринсик — вклад ячейки в него не течёт, ширины предсказуемы.
         Кнопки стоят в СВОЕЙ колонке (act на все три ряда, название её не
         пересекает), поэтому auto там честно даёт ширину двух иконок. */
      /* 1.1fr / 0.9fr, а не поровну: в левой колонке стоит телефон — единственная
         ячейка с ЖЁСТКОЙ шириной («+7 (846) 372-30-30» = 118px, переносить и
         обрезать номер нельзя). При 113px он вылезал за ячейку и наезжал на
         почту. 124px его держат, а правой колонке (адрес/почта) 102px хватает —
         они и так с многоточием. */
      .facilities-list tr {
        grid-template-columns: minmax(0, 1.1fr) minmax(0, 0.9fr) auto;
        grid-template-areas:
          "name name act"
          "reg  addr act"
          "tel  mail act";
      }
      /* flex-wrap: nowrap ОБЯЗАТЕЛЕН: механический слой ставит ячейкам
         flex-wrap: wrap, и бейдж уезжал на вторую строку под название —
         строка названия занимала 62px вместо 39px. */
      .facilities-list td[data-label="Название"] {
        grid-area: name; display: flex; flex-wrap: nowrap; align-items: flex-start;
        gap: 6px; font-size: 15px; font-weight: 600; line-height: 1.25;
      }
      /* Две строки, а не одна: у KZ-больниц различающая часть в СЕРЕДИНЕ
         («ГКП на ПХВ «Областной онкологический диспансер» УЗ акимата ЗКО»),
         и однострочная обрезка схлопнула бы весь список в одинаковый
         префикс «ГКП на ПХВ «Областной…».
         Клэмп висит на ВЛОЖЕННОМ .f-name-text: -webkit-line-clamp работает
         только при display:-webkit-box, а флекс-элемент блокифицируется
         (замерено: display приходил flow-root) и свойство молча ничего не
         делало. Вложенный элемент флекс-элементом уже не является — клэмп
         действует и даёт «…» на второй строке. */
      .facilities-list .f-name { flex: 0 1 auto; min-width: 0; overflow: hidden; }
      .facilities-list .f-name-text {
        display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
        overflow: hidden;
      }
      .facilities-list .badge-monitor { flex: 0 0 auto; margin-left: auto; }
      .facilities-list td[data-label="Регион"] {
        grid-area: reg; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .facilities-list td[data-label="Адрес"] {
        grid-area: addr; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      /* многоточие здесь — страховка на случай длинного формата номера:
         лучше «…», чем номер, наезжающий на почту соседней колонки */
      .facilities-list td[data-label="Телефон"] {
        grid-area: tel; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .facilities-list td[data-label="Эл. почта"] {
        grid-area: mail; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .facilities-list td[data-label="БИН/ИНН"],
      .facilities-list td[data-label="Контактное лицо"] { display: none; }
      /* Действий две и обе иконочные — по правилу шага 4 остаются в ряд,
         меню «⋯» тут не нужно. Колонка занимает обе нижние строки. */
      .facilities-list td.actions { grid-area: act; justify-content: flex-end; gap: 6px; }
      .facilities-list td.actions .btn-edit { margin-right: 0; }
    }
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
    email: new FormControl(''),
    region: new FormControl(''),
    monitorTenders: new FormControl(false)
  });

  readonly REGIONS = KZ_REGIONS;
  isKz(): boolean { return this.market.value === 'KZ'; }

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService, private confirm: ConfirmService,
              public auth: AuthService, public market: MarketService) {
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
