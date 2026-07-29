import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';

@Component({
  selector: 'app-users',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, LucideDynamicIcon],
  template: `
    <h2>Пользователи</h2>
    <p class="subtitle">Управление учётными записями пользователей системы</p>

    <div class="toolbar">
      <button class="btn btn-add" *ngIf="!showForm" (click)="onAdd()"><svg lucideIcon="plus" [size]="14"></svg> Добавить</button>
      <span class="counter" *ngIf="users.length">Найдено: {{ users.length }} записей</span>
    </div>

    <form *ngIf="showForm" [formGroup]="form" (ngSubmit)="onSave()" class="edit-form">
      <div *ngIf="validationErrors._general" class="error-banner">{{ validationErrors._general }}</div>
      <label>Логин *<input formControlName="username" [class.input-error]="validationErrors.username" /><span class="field-error" *ngIf="validationErrors.username">{{ validationErrors.username }}</span></label>
      <label>ФИО<input formControlName="fullName" [class.input-error]="validationErrors.fullName" /><span class="field-error" *ngIf="validationErrors.fullName">{{ validationErrors.fullName }}</span></label>
      <label>Роль
        <select formControlName="role" [class.input-error]="validationErrors.role">
          <option value="ROLE_ADMIN">Администратор</option>
          <option value="ROLE_USER">Оператор</option>
        </select>
        <span class="field-error" *ngIf="validationErrors.role">{{ validationErrors.role }}</span>
      </label>
      <label>{{ editingId ? 'Новый пароль (оставьте пустым чтобы не менять)' : 'Пароль *' }}
        <input type="password" formControlName="password" [class.input-error]="validationErrors.password" autocomplete="new-password" />
        <span class="field-error" *ngIf="validationErrors.password">{{ validationErrors.password }}</span>
      </label>
      <div class="form-actions">
        <button class="btn btn-save" type="submit" [disabled]="form.invalid">Сохранить</button>
        <button class="btn btn-cancel" type="button" (click)="onCancel()">Отмена</button>
      </div>
    </form>

    <div *ngIf="users.length === 0 && !showForm" class="empty">Нет данных</div>

    <table class="responsive-cards card-grid users-list" *ngIf="users.length > 0">
      <thead><tr><th>Логин</th><th>ФИО</th><th>Роль</th><th>Действия</th></tr></thead>
      <tbody>
        <tr *ngFor="let u of users">
          <td data-label="Логин">{{ u.username }}</td><td data-label="ФИО">{{ u.fullName }}</td><td data-label="Роль">{{ u.role === 'ROLE_ADMIN' ? 'Администратор' : 'Оператор' }}</td>
          <td class="actions">
            <button class="btn btn-edit" (click)="onEdit(u)" title="Редактировать"><svg lucideIcon="pencil" [size]="14"></svg></button>
            <button class="btn btn-delete" (click)="onDelete(u.id)" title="Удалить"><svg lucideIcon="trash-2" [size]="14"></svg></button>
          </td>
        </tr>
      </tbody>
    </table>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; }
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
    /* Фон и цвет заданы локально и совпадают с kit (правило input/select/textarea
       вернулось в styles.scss последней задачей волны): объявление несёт ещё и
       геометрию, поэтому описывает поле целиком — --surface на подложке --surface-2. */
    .edit-form input, .edit-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 14px; background: var(--surface); color: var(--text); }
    .input-error { border-color: var(--danger) !important; }

    /* ============================================================
       МОБИЛЬНАЯ КАРТОЧКА — ПОСЛЕДНИЙ БЛОК В styles (CLAUDE.md §14: при равной
       специфичности позднее базовое правило молча перебивает @media-правило).
       Общее поведение card-grid — в глобальном styles.scss; здесь только
       раскладка этого списка.
       ============================================================ */
    @media (max-width: 900px) {
      /* Было 4 строки «подпись: значение» — 156px на карточку (замер живьём,
         выше ориентира 140). Стало три:
           ЛОГИН            [✎]
           ФИО              [🗑]
           роль
         Ничего не скрыто: у списка три колонки, и каждая работает. */
      .users-list tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "login act"
          "name  act"
          "role  act";
      }
      .users-list td[data-label="Логин"] { grid-area: login; font-size: 15px; font-weight: 600; }
      /* display:block — иначе текст становится анонимным флекс-элементом и
         многоточие до него не достаёт */
      .users-list td[data-label="ФИО"] {
        grid-area: name; display: block; color: var(--text-muted); font-size: 13px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .users-list td[data-label="Роль"] { grid-area: role; color: var(--text-muted); font-size: 12px; }
      /* Действий две и обе иконочные — по правилу шага 4 остаются в ряд.
         Своя колонка на все три ряда: в общей раскладке грид раздаёт
         max-content растянутой ячейки интринсик-трекам и колонка действий
         раздувается (ловили в facilities). */
      .users-list td.actions { grid-area: act; justify-content: flex-end; gap: 6px; }
      .users-list td.actions .btn-edit { margin-right: 0; }
    }
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
    role: new FormControl('ROLE_USER'),
    password: new FormControl('')
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

  onAdd() { this.editingId = null; this.form.reset({ role: 'ROLE_USER', password: '' }); this.validationErrors = {}; this.showForm = true; }
  onEdit(u: any) { this.editingId = u.id; this.form.patchValue({ ...u, password: '' }); this.validationErrors = {}; this.showForm = true; }
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
