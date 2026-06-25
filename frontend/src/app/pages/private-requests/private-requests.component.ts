import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { PrivateRequestCardComponent } from './private-request-card.component';

@Component({
  selector: 'app-private-requests',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, PrivateRequestCardComponent],
  template: `
    <div class="page">
      <header class="head">
        <div>
          <h1>Частные заявки</h1>
          <p class="sub">Заявки от частных клиник ({{ market.companyLabel() }}). Клиника называет бренд/модель — проверяем регистрацию и запрашиваем КП.</p>
        </div>
        <div class="head-actions">
          <button class="btn-line-solid" (click)="openImport()">⬆ Импорт из файла</button>
          <button class="btn-primary" (click)="openForm()">+ Новая заявка</button>
        </div>
      </header>

      <!-- панель импорта -->
      <div class="import-panel" *ngIf="showImport">
        <div class="import-head">
          <h3>Импорт заявки из Excel</h3>
          <button class="x" (click)="showImport=false">×</button>
        </div>
        <input type="file" accept=".xlsx,.xls" (change)="onImportFile($event)" />
        <p class="hint">Загрузите таблицу — система разметит колонки сама, поправьте при необходимости.</p>

        <div *ngIf="importPreview">
          <label class="lbl">Клиент</label>
          <select [(ngModel)]="importClientId" class="client-sel">
            <option [ngValue]="null" disabled>— выберите —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>

          <div class="grid-wrap">
            <table class="import-grid">
              <thead>
                <tr>
                  <th *ngFor="let c of importPreview.columns">
                    <div class="ih">{{ c.header || '—' }}</div>
                    <select [(ngModel)]="c.field" [ngModelOptions]="{standalone:true}">
                      <option *ngFor="let o of fieldOptions" [ngValue]="o.v">{{ o.l }}</option>
                    </select>
                  </th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let row of importPreview.rows">
                  <td *ngFor="let cell of row">{{ cell }}</td>
                </tr>
              </tbody>
            </table>
          </div>

          <div class="err" *ngIf="importError">{{ importError }}</div>
          <div class="import-actions">
            <button class="btn-primary" [disabled]="importing" (click)="createFromImport()">Создать заявку</button>
            <button class="btn-line-solid" (click)="showImport=false">Отмена</button>
          </div>
        </div>
        <div class="err" *ngIf="importError && !importPreview">{{ importError }}</div>
      </div>

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
        <thead><tr><th>Номер</th><th>Клиент</th><th>Позиций</th><th>Реестр</th><th>Статус</th></tr></thead>
        <tbody>
          <tr class="row" *ngFor="let r of rows" (click)="openCard(r)">
            <td class="num">{{ r.number }}</td>
            <td>{{ r.client?.name || '—' }}</td>
            <td>{{ r.lineCount ?? 0 }}</td>
            <td><span class="reg-summary" [style.color]="(r.registeredCount ?? 0) > 0 ? '#065f46' : '#6b7280'">{{ r.registeredCount ?? 0 }} из {{ r.lineCount ?? 0 }} в реестре</span></td>
            <td><span class="badge">{{ r.status }}</span></td>
          </tr>
        </tbody>
      </table>
      <div class="empty" *ngIf="!loading && !rows.length">Заявок пока нет.</div>

      <app-private-request-card *ngIf="cardId !== null" [requestId]="cardId" (close)="cardId = null; load()"></app-private-request-card>
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
    .reg-summary { font-size: 12px; font-weight: 600; }
    .loading, .empty { padding: 30px; text-align: center; color: #9ca3af; }
    .head-actions { display: flex; gap: 8px; align-items: center; }
    .import-panel { border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin: 12px 0; background: #fff; }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: #6b7280; }
    .import-panel .hint { color: #6b7280; font-size: 12px; margin: 6px 0 12px; }
    .import-panel .lbl { display: block; font-size: 12px; color: #374151; margin-bottom: 4px; }
    .client-sel { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; margin-bottom: 12px; min-width: 260px; }
    .grid-wrap { overflow-x: auto; border: 1px solid #eee; border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    .import-grid th { background: #f9fafb; padding: 8px; border: 1px solid #eee; vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; }
    .import-grid td { padding: 6px 8px; border: 1px solid #f0f0f0; white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
    .btn-line-solid { background: #fff; border: 1px solid #9ca3af; border-radius: 6px; padding: 6px 14px; cursor: pointer; font-size: 13px; color: #374151; }
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

  showImport = false;
  importPreview: any = null;
  importClientId: number | null = null;
  importError = '';
  importing = false;
  fieldOptions = [
    { v: 'NAME', l: 'Наименование' },
    { v: 'MANUFACT', l: 'Бренд' },
    { v: 'QUANTITY', l: 'Кол-во' },
    { v: 'IGNORE', l: 'Игнорировать' },
  ];

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

  openImport() {
    this.showImport = true;
    this.importPreview = null;
    this.importClientId = null;
    this.importError = '';
  }

  onImportFile(event: any) {
    const file: File = event.target?.files?.[0];
    if (!file) return;
    this.importError = '';
    this.api.previewImport(file).subscribe({
      next: (p) => { this.importPreview = p; this.cdr.detectChanges(); },
      error: (e) => { this.importError = e.error?.message || 'Не удалось прочитать файл'; this.cdr.detectChanges(); },
    });
  }

  createFromImport() {
    if (!this.importClientId) { this.importError = 'Выберите клиента'; return; }
    const cols = this.importPreview?.columns || [];
    const nameCol = cols.find((c: any) => c.field === 'NAME');
    if (!nameCol) { this.importError = 'Отметьте колонку с наименованием'; return; }
    const manuCol = cols.find((c: any) => c.field === 'MANUFACT');
    const qtyCol = cols.find((c: any) => c.field === 'QUANTITY');
    const lines = (this.importPreview.rows || [])
      .map((row: string[]) => ({
        name: row[nameCol.index],
        manufact: manuCol ? row[manuCol.index] : null,
        quantity: qtyCol ? (parseInt(row[qtyCol.index], 10) || 1) : 1,
      }))
      .filter((l: any) => l.name && String(l.name).trim());
    if (!lines.length) { this.importError = 'Нет строк с наименованием'; return; }
    const mappings = cols
      .filter((c: any) => c.field && c.field !== 'IGNORE')
      .map((c: any) => ({ header: c.header, field: c.field }));
    this.importing = true;
    this.api.commitImport({ clientFacilityId: this.importClientId, mappings, lines }).subscribe({
      next: (created: any) => {
        this.importing = false;
        this.showImport = false;
        this.notify.success('Заявка создана из файла');
        this.load();
        if (created?.id) this.cardId = created.id;
        this.cdr.detectChanges();
      },
      error: (e: any) => {
        this.importing = false;
        this.importError = e.error?.message || 'Ошибка импорта';
        this.cdr.detectChanges();
      },
    });
  }
}
