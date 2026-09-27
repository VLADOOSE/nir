import { Component, Output, EventEmitter, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

/** «+ Обращение»: звонок, WhatsApp или другое, внесённое вручную (спека §10). */
@Component({
  selector: 'app-lead-form',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="close.emit()">
      <div class="modal" role="dialog" aria-label="Новое обращение" (click)="$event.stopPropagation()">
        <h3>Новое обращение</h3>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <label class="fld">Канал
          <select [(ngModel)]="form.channel">
            <option value="PHONE">Звонок</option>
            <option value="WHATSAPP">WhatsApp</option>
            <option value="OTHER">Другое</option>
          </select>
        </label>
        <div class="grid2">
          <label class="fld">Имя<input [(ngModel)]="form.contactName" maxlength="255" /></label>
          <label class="fld">Телефон<input [(ngModel)]="form.contactPhone" maxlength="50" inputmode="tel" placeholder="+7 7XX XXX XX XX" /></label>
          <label class="fld">Компания / клиника<input [(ngModel)]="form.company" maxlength="255" /></label>
          <label class="fld">Email<input [(ngModel)]="form.contactEmail" maxlength="255" type="email" /></label>
        </div>
        <label class="fld">Что ищут
          <textarea [(ngModel)]="form.message" rows="4" maxlength="5000"
                    placeholder="Например: УЗИ-аппарат для гинекологии, бюджет до 10 млн"></textarea>
        </label>
        <div class="block">
          <span class="lbl">Позиции <span class="muted">(необязательно)</span></span>
          <div class="ie-row" *ngFor="let i of form.items; let idx = index">
            <input [(ngModel)]="i.name" placeholder="Наименование / модель" aria-label="Наименование" />
            <input [(ngModel)]="i.brand" placeholder="Бренд" aria-label="Бренд" />
            <input type="number" min="1" [(ngModel)]="i.quantity" aria-label="Количество" />
            <button type="button" class="btn btn-cancel" (click)="form.items.splice(idx, 1)" aria-label="Удалить строку">✕</button>
          </div>
          <div><button type="button" class="btn btn-line" (click)="form.items.push({ name: '', brand: '', quantity: 1 })">+ Позиция</button></div>
        </div>
        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Создать' }}</button>
          <button type="button" class="btn btn-cancel" (click)="close.emit()">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%; max-width: 640px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    .modal h3 { margin: 0; }
    .fld { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-muted); }
    .fld input, .fld select, .fld textarea, .ie-row input { padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; background: var(--surface); color: var(--text); font-family: inherit; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .block { display: flex; flex-direction: column; gap: 8px; }
    .lbl { font-size: 13px; font-weight: 600; color: var(--text-muted); }
    .muted { font-weight: 400; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .grid2 { grid-template-columns: 1fr; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .fld input, .fld select, .fld textarea, .ie-row input { font-size: 16px; }
    }
  `]
})
export class LeadFormComponent {
  @Output() close = new EventEmitter<void>();
  @Output() created = new EventEmitter<any>();

  form = { channel: 'PHONE', contactName: '', contactPhone: '', company: '', contactEmail: '', message: '', items: [] as any[] };
  error = '';
  saving = false;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  @HostListener('document:keydown.escape')
  onEscape() { this.close.emit(); }

  save() {
    if (!this.form.contactPhone.trim() && !this.form.contactEmail.trim()) {
      this.error = 'Укажите телефон или email клиента';
      return;
    }
    const items = this.form.items
      .filter(i => i.name && String(i.name).trim())
      .map(i => ({
        name: String(i.name).trim(),
        brand: i.brand && String(i.brand).trim() ? String(i.brand).trim() : null,
        quantity: parseInt(i.quantity, 10) || 1,
      }));
    this.saving = true;
    this.error = '';
    this.api.createLead({ ...this.form, items }).subscribe({
      next: lead => { this.saving = false; this.notify.success('Обращение создано'); this.created.emit(lead); },
      error: e => { this.saving = false; this.error = e.error?.message || 'Не удалось создать обращение'; this.cdr.detectChanges(); },
    });
  }
}
