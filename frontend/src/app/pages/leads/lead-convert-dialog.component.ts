import { Component, Input, Output, EventEmitter, OnInit, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

/** ФИО по простому правилу спеки §10: 1 слово → имя; 2 → имя + фамилия; 3+ → фамилия, имя, отчество. Всё правится. */
export function splitName(full: string | null | undefined): { lastName: string; firstName: string; middleName: string } {
  const parts = (full || '').trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return { lastName: '', firstName: '', middleName: '' };
  if (parts.length === 1) return { lastName: '', firstName: parts[0], middleName: '' };
  if (parts.length === 2) return { lastName: parts[1], firstName: parts[0], middleName: '' };
  return { lastName: parts[0], firstName: parts[1], middleName: parts.slice(2).join(' ') };
}

/** «Создать частную заявку» из обращения — сервер делает всё одной транзакцией (спека §9). */
@Component({
  selector: 'app-lead-convert-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="close.emit()">
      <div class="modal" role="dialog" aria-label="Частная заявка из обращения" (click)="$event.stopPropagation()">
        <h3>Частная заявка из обращения</h3>
        <div class="error-banner" *ngIf="error">{{ error }}</div>

        <div class="block">
          <div class="block-head">
            <span class="lbl">Клиент</span>
            <button type="button" class="btn btn-line" (click)="toggleNew()">{{ newMode ? '✕ Выбрать из списка' : '＋ Новый клиент' }}</button>
          </div>
          <select *ngIf="!newMode" class="client" [(ngModel)]="clientId" aria-label="Клиент">
            <option [ngValue]="null" disabled>Выберите клиента…</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
          <div class="grid2" *ngIf="newMode">
            <label class="fld">Название<input [(ngModel)]="nc.name" maxlength="255" /></label>
            <label class="fld">Телефон<input [(ngModel)]="nc.phone" maxlength="50" inputmode="tel" /></label>
            <label class="fld">Email<input [(ngModel)]="nc.email" maxlength="255" type="email" /></label>
            <label class="fld">Фамилия<input [(ngModel)]="nc.lastName" maxlength="100" /></label>
            <label class="fld">Имя<input [(ngModel)]="nc.firstName" maxlength="100" /></label>
            <label class="fld">Отчество<input [(ngModel)]="nc.middleName" maxlength="100" /></label>
          </div>
        </div>

        <div class="block">
          <span class="lbl">Строки заявки</span>
          <p class="client-text" *ngIf="lead.message && !lead.items?.length">Текст клиента: {{ lead.message }}</p>
          <div class="ie-row" *ngFor="let l of lines; let idx = index">
            <input [(ngModel)]="l.name" placeholder="Наименование / модель" aria-label="Наименование" />
            <input [(ngModel)]="l.manufact" placeholder="Бренд" aria-label="Бренд" />
            <input type="number" min="1" [(ngModel)]="l.quantity" aria-label="Количество" />
            <button type="button" class="btn btn-cancel" (click)="removeLine(idx)" aria-label="Удалить строку">✕</button>
          </div>
          <div><button type="button" class="btn btn-line" (click)="lines.push({ name: '', manufact: '', quantity: 1 })">+ Строка</button></div>
        </div>

        <label class="fld">Примечание к заявке<textarea [(ngModel)]="note" rows="3" maxlength="5000"></textarea></label>

        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="submit()">{{ saving ? 'Создаю…' : 'Создать заявку' }}</button>
          <button type="button" class="btn btn-cancel" (click)="close.emit()">Отмена</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* выше дровера карточки (z 1000); вуаль — самое частое значение в приложении */
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center; justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%; max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 14px; }
    .modal h3 { margin: 0; }
    .block { display: flex; flex-direction: column; gap: 8px; }
    .block-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
    .lbl { font-size: 13px; font-weight: 600; color: var(--text-muted); }
    .fld { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text-muted); }
    .fld input, .fld textarea, .ie-row input, select.client { padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; font-size: 14px; background: var(--surface); color: var(--text); font-family: inherit; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
    .ie-row { display: grid; grid-template-columns: 1fr 140px 70px auto; gap: 6px; }
    .client-text { margin: 0; font-size: 13px; color: var(--text-muted); white-space: pre-wrap; background: var(--surface-2); border-radius: 8px; padding: 8px 10px; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .grid2 { grid-template-columns: 1fr; }
      .ie-row { grid-template-columns: 1fr 80px auto; }
      .ie-row input:first-child { grid-column: 1 / -1; }
      .fld input, .fld textarea, .ie-row input, select.client { font-size: 16px; }
    }
  `]
})
export class LeadConvertDialogComponent implements OnInit {
  @Input() lead: any;
  @Output() close = new EventEmitter<void>();
  @Output() converted = new EventEmitter<{ privateRequestId: number; number: string }>();

  facilities: any[] = [];
  clientId: number | null = null;
  newMode = false;
  nc = { name: '', phone: '', email: '', lastName: '', firstName: '', middleName: '' };
  lines: any[] = [];
  note = '';
  error = '';
  saving = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.clientId = this.lead.facilityId ?? null;
    this.newMode = this.clientId == null;   // клиент не узнан — сразу форма нового, заполненная из обращения
    this.nc = {
      name: this.lead.company || this.lead.contactName || '',
      phone: this.lead.contactPhone || '',
      email: this.lead.contactEmail || '',
      ...splitName(this.lead.contactName),
    };
    this.lines = (this.lead.items || []).map((i: any) => ({ name: i.name, manufact: i.brand || '', quantity: i.quantity || 1 }));
    if (!this.lines.length) this.lines.push({ name: '', manufact: '', quantity: 1 });
    this.note = this.lead.message || '';
  }

  @HostListener('document:keydown.escape')
  onEscape() { this.close.emit(); }

  toggleNew() { this.newMode = !this.newMode; this.error = ''; }

  removeLine(i: number) {
    this.lines.splice(i, 1);
    if (!this.lines.length) this.lines.push({ name: '', manufact: '', quantity: 1 });
  }

  submit() {
    const lines = this.lines
      .filter(l => l.name && String(l.name).trim())
      .map(l => ({
        name: String(l.name).trim(),
        manufact: l.manufact && String(l.manufact).trim() ? String(l.manufact).trim() : null,
        quantity: parseInt(l.quantity, 10) || 1,
      }));
    if (!lines.length) { this.error = 'Нужна хотя бы одна строка с наименованием'; return; }
    if (!this.newMode && this.clientId == null) { this.error = 'Выберите клиента или создайте нового'; return; }
    if (this.newMode && !this.nc.name.trim()) { this.error = 'Введите название клиента'; return; }
    const body: any = { note: this.note, lines };
    if (this.newMode) body.newClient = { ...this.nc, name: this.nc.name.trim() };
    else body.clientFacilityId = this.clientId;
    this.saving = true;
    this.error = '';
    this.api.convertLead(this.lead.id, body).subscribe({
      next: r => { this.saving = false; this.converted.emit(r); },
      error: e => { this.saving = false; this.error = e.error?.message || 'Не удалось создать заявку'; this.cdr.detectChanges(); },
    });
  }
}
