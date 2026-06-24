import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-private-requests',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1>Частные заявки</h1>
          <p class="sub">Заявки от частных клиник ({{ market.companyLabel() }}). Клиника называет бренд/модель — проверяем регистрацию и запрашиваем КП.</p>
        </div>
        <button class="btn-primary" (click)="openForm()">+ Новая заявка</button>
      </header>

      <!-- форма создания -->
      <div class="form-card" *ngIf="showForm">
        <h3>Новая частная заявка</h3>
        <label>Клиент (клиника)
          <select [(ngModel)]="form.clientFacilityId">
            <option [ngValue]="null" disabled>— выберите —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
        </label>
        <div class="lines">
          <div class="line-head"><span>Наименование/модель</span><span>Бренд</span><span>Кол-во</span><span></span></div>
          <div class="line" *ngFor="let l of form.lines; let i = index">
            <input [(ngModel)]="l.name" placeholder="Тонометр OMRON M2" />
            <input [(ngModel)]="l.manufact" placeholder="OMRON" />
            <input type="number" [(ngModel)]="l.quantity" min="1" />
            <button class="btn-del" (click)="removeLine(i)" [disabled]="form.lines.length === 1">✕</button>
          </div>
        </div>
        <button class="btn-line" (click)="addLine()">+ строка</button>
        <div class="form-actions">
          <button class="btn-primary" (click)="save()">Создать заявку</button>
          <button class="btn-ghost" (click)="showForm = false">Отмена</button>
        </div>
        <div class="err" *ngIf="formError">{{ formError }}</div>
      </div>

      <div class="loading" *ngIf="loading">Загрузка…</div>
      <table *ngIf="!loading && rows.length">
        <thead><tr><th>Номер</th><th>Клиент</th><th>Строк</th><th>Статус</th></tr></thead>
        <tbody>
          <tr class="row" *ngFor="let r of rows" (click)="openCard(r)">
            <td class="num">{{ r.number }}</td>
            <td>{{ r.client?.name || '—' }}</td>
            <td>{{ r.lines?.length ?? '—' }}</td>
            <td><span class="badge">{{ r.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" *ngIf="!loading && !rows.length">Заявок пока нет.</div>

      <!-- карточка подключается в Task 5 -->
      <!-- <app-private-request-card *ngIf="cardId !== null" [requestId]="cardId" (close)="cardId = null; load()"></app-private-request-card> -->
    </div>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    h1 { font-size: 22px; color: #111827; }
    .sub { color: #6b7280; font-size: 13px; margin-top: 4px; max-width: 640px; }
    .btn-primary { background: #1a56db; color: #fff; border: none; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .btn-ghost { background: #fff; border: 1px solid #d1d5db; color: #374151; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .form-card { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin-bottom: 18px; }
    .form-card h3 { font-size: 15px; margin-bottom: 10px; }
    .form-card label { display: block; font-size: 13px; color: #374151; margin-bottom: 10px; }
    .form-card select, .line input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .form-card select { min-width: 320px; margin-top: 4px; }
    .lines { margin: 8px 0; }
    .line-head, .line { display: grid; grid-template-columns: 1fr 200px 90px 32px; gap: 8px; align-items: center; margin-bottom: 6px; }
    .line-head span { font-size: 11px; color: #6b7280; text-transform: uppercase; }
    .line input { width: 100%; }
    .btn-del { background: #fff; border: 1px solid #d1d5db; border-radius: 6px; cursor: pointer; color: #991b1b; }
    .btn-line { background: #fff; border: 1px dashed #9ca3af; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; color: #374151; }
    .form-actions { display: flex; gap: 8px; margin-top: 12px; }
    .err { color: #991b1b; font-size: 13px; margin-top: 8px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    thead th { text-align: left; padding: 8px 10px; color: #6b7280; border-bottom: 1px solid #e5e7eb; }
    .row { cursor: pointer; border-bottom: 1px solid #f3f4f6; }
    .row:hover { background: #f9fafb; }
    .row td { padding: 9px 10px; }
    .num { font-weight: 600; color: #1a56db; }
    .badge { padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; background: #e5e7eb; color: #374151; }
    .loading, .empty { padding: 30px; text-align: center; color: #9ca3af; }
  `]
})
export class PrivateRequestsComponent {
  rows: any[] = [];
  facilities: any[] = [];
  loading = false;
  showForm = false;
  formError = '';
  cardId: number | null = null;
  form: { clientFacilityId: number | null; note: string; lines: any[] } = this.emptyForm();

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private route: ActivatedRoute, private notify: NotificationService,
              public market: MarketService) {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.route.queryParams.subscribe(p => { if (p['openId']) { this.cardId = +p['openId']; } });
    this.load();
  }

  emptyForm() { return { clientFacilityId: null, note: '', lines: [{ name: '', manufact: '', quantity: 1 }] }; }
  openForm() { this.form = this.emptyForm(); this.formError = ''; this.showForm = true; }
  addLine() { this.form.lines.push({ name: '', manufact: '', quantity: 1 }); }
  removeLine(i: number) { if (this.form.lines.length > 1) this.form.lines.splice(i, 1); }
  openCard(r: any) { this.cardId = r.id; }

  load() {
    this.loading = true;
    this.api.getPrivateRequests().subscribe({
      next: d => { this.rows = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Ошибка загрузки: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }

  save() {
    if (!this.form.clientFacilityId) { this.formError = 'Выберите клиента'; return; }
    const lines = this.form.lines.filter(l => l.name && l.name.trim());
    if (!lines.length) { this.formError = 'Добавьте хотя бы одну строку с наименованием'; return; }
    this.api.createPrivateRequest({ clientFacilityId: this.form.clientFacilityId, note: this.form.note, lines }).subscribe({
      next: () => { this.showForm = false; this.notify.success('Заявка создана'); this.load(); },
      error: e => { this.formError = e.error?.message || 'Ошибка создания'; this.cdr.detectChanges(); }
    });
  }
}
