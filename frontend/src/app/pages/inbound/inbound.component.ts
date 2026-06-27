import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-inbound',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
  <div class="page">
    <div class="head">
      <div>
        <h1>Входящие письма</h1>
        <p class="sub">Входящие запросы клиентов с почты info@westmed.kz — письма с таблицами оборудования.</p>
      </div>
      <button class="btn-primary" [disabled]="polling" (click)="poll()">⟳ Проверить почту</button>
    </div>

    <table class="grid" *ngIf="rows.length">
      <thead>
        <tr><th>Отправитель</th><th>Тема</th><th>Тип</th><th>Статус</th><th></th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let r of rows">
          <td>{{ r.fromAddress }}</td>
          <td>{{ r.subject }}</td>
          <td>
            <span class="badge" [class.b-sup]="r.type==='SUPPLIER_RESPONSE'"
                  [class.b-cli]="r.type==='CLIENT_REQUEST'" [class.b-unm]="r.type==='UNMATCHED'">
              {{ typeLabel(r.type) }}
            </span>
            <span *ngIf="r.type==='SUPPLIER_RESPONSE' && r.matchedPriceRequestId" class="muted"> · КП #{{ r.matchedPriceRequestId }}</span>
          </td>
          <td>{{ r.status==='PROCESSED' ? 'Обработано' : 'Новое' }}</td>
          <td>
            <button *ngIf="r.type==='CLIENT_REQUEST' && r.hasAttachment && r.status!=='PROCESSED'"
                    class="btn-line-solid" (click)="openImport(r)">Импортировать</button>
          </td>
        </tr>
      </tbody>
    </table>
    <p class="empty" *ngIf="!rows.length && !loading">Писем пока нет. Нажмите «Проверить почту».</p>

    <!-- Импорт письма клиники через грид D1 -->
    <div class="import-panel" *ngIf="importEmailId !== null">
      <div class="import-head">
        <h3>Импорт заявки из письма</h3>
        <button class="x" (click)="closeImport()">×</button>
      </div>

      <div class="msg-preview">
        <div class="msg-meta">
          <span class="msg-from">{{ importFrom || '—' }}</span>
          <span class="msg-subj" *ngIf="importSubject">· {{ importSubject }}</span>
        </div>
        <div class="msg-body" [class.clamped]="!messageExpanded">{{ importExcerpt || '(в письме нет текста)' }}</div>
        <button type="button" class="msg-toggle" *ngIf="importExcerpt && importExcerpt.length > 160"
                (click)="messageExpanded = !messageExpanded">
          {{ messageExpanded ? '▲ Свернуть' : '▼ Развернуть сообщение' }}
        </button>
      </div>

      <div *ngIf="importPreview">
        <label class="lbl">Клиент</label>
        <div class="client-row">
          <select [(ngModel)]="importClientId" class="client-sel" [disabled]="newClientMode">
            <option [ngValue]="null" disabled>— выберите —</option>
            <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
          </select>
          <button type="button" class="btn-line-solid" (click)="toggleNewClient()">
            {{ newClientMode ? '✕ Отмена' : '＋ Новый клиент' }}
          </button>
        </div>
        <div class="new-client" *ngIf="newClientMode">
          <input [(ngModel)]="newClientName" [ngModelOptions]="{standalone:true}"
                 placeholder="Название клиента/клиники из письма" />
          <span class="hint-sm">Создастся новое учреждение и привяжется к заявке.</span>
        </div>
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
          <button class="btn-line-solid" (click)="closeImport()">Отмена</button>
        </div>
      </div>
    </div>
  </div>
  `,
  styles: [`
    .page { padding: 20px; }
    .head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    .head h1 { margin: 0; }
    .sub { color: #6b7280; font-size: 13px; margin: 4px 0 0; }
    .btn-primary { background: #2563eb; color: #fff; border: none; border-radius: 6px; padding: 8px 16px; cursor: pointer; }
    .grid { width: 100%; border-collapse: collapse; background: #fff; border-radius: 8px; overflow: hidden; }
    .grid th { background: #f9fafb; text-align: left; padding: 10px; font-size: 12px; color: #374151; }
    .grid td { padding: 10px; border-top: 1px solid #f0f0f0; font-size: 13px; }
    .badge { font-size: 11px; padding: 2px 8px; border-radius: 999px; }
    .b-sup { background: #dcfce7; color: #166534; }
    .b-cli { background: #dbeafe; color: #1e40af; }
    .b-unm { background: #f3f4f6; color: #6b7280; }
    .muted { color: #6b7280; font-size: 12px; }
    .empty { color: #6b7280; padding: 20px 0; }
    .import-panel { border: 1px solid #e5e7eb; border-radius: 10px; padding: 16px; margin-top: 16px; background: #fff; }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: #6b7280; }
    .lbl { display: block; font-size: 12px; color: #374151; margin: 8px 0 4px; }
    .client-sel { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; margin-bottom: 12px; min-width: 260px; }
    .grid-wrap { overflow-x: auto; border: 1px solid #eee; border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    .import-grid th { background: #f9fafb; padding: 8px; border: 1px solid #eee; vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 12px; }
    .import-grid td { padding: 6px 8px; border: 1px solid #f0f0f0; white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
    .err { color: #b91c1c; font-size: 13px; margin: 8px 0; }
    .btn-line-solid { background: #fff; border: 1px solid #9ca3af; border-radius: 6px; padding: 6px 14px; cursor: pointer; font-size: 13px; color: #374151; }
    .client-row { display: flex; gap: 8px; align-items: center; }
    .client-sel:disabled { background: #f3f4f6; color: #9ca3af; }
    .new-client { margin: 8px 0 4px; display: flex; align-items: center; flex-wrap: wrap; }
    .new-client input { padding: 6px 10px; border: 1px solid #d1d5db; border-radius: 6px; min-width: 320px; }
    .hint-sm { color: #6b7280; font-size: 12px; margin-left: 8px; }
    .msg-preview { background: #f9fafb; border: 1px solid #eef0f3; border-radius: 8px; padding: 10px 12px; margin: 8px 0 14px; }
    .msg-meta { font-size: 12px; color: #6b7280; margin-bottom: 6px; }
    .msg-from { font-weight: 600; color: #374151; }
    .msg-body { font-size: 13px; color: #1f2937; white-space: pre-wrap; line-height: 1.5; }
    .msg-body.clamped { max-height: 4.5em; overflow: hidden; -webkit-mask-image: linear-gradient(180deg, #000 60%, transparent); }
    .msg-toggle { margin-top: 6px; background: none; border: none; color: #2563eb; cursor: pointer; font-size: 12px; padding: 0; }
  `],
})
export class InboundComponent {
  rows: any[] = [];
  facilities: any[] = [];
  loading = false;
  polling = false;

  importEmailId: number | null = null;
  importPreview: any = null;
  importClientId: number | null = null;
  importError = '';
  importing = false;
  importFrom = '';
  importSubject = '';
  importExcerpt = '';
  messageExpanded = false;
  newClientMode = false;
  newClientName = '';
  fieldOptions = [
    { v: 'NAME', l: 'Наименование' },
    { v: 'MANUFACT', l: 'Бренд' },
    { v: 'QUANTITY', l: 'Кол-во' },
    { v: 'IGNORE', l: 'Игнорировать' },
  ];

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private notify: NotificationService) {
    this.api.getFacilities().subscribe({ next: d => { this.facilities = d; this.cdr.detectChanges(); } });
    this.load();
  }

  load() {
    this.loading = true;
    this.api.getInbound().subscribe({
      next: d => { this.rows = d || []; this.loading = false; this.cdr.detectChanges(); },
      error: () => { this.loading = false; this.cdr.detectChanges(); },
    });
  }

  poll() {
    this.polling = true;
    this.api.pollInbound().subscribe({
      next: (r: any) => {
        this.polling = false;
        if (r && r.enabled === false) {
          this.notify.error(r.message || 'Приём почты выключен');
        } else {
          this.notify.success((r && r.message) || 'Почта проверена');
          this.load();
        }
        this.cdr.detectChanges();
      },
      error: (e: any) => {
        this.polling = false;
        this.notify.error('Ошибка: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      },
    });
  }

  typeLabel(t: string): string {
    return t === 'SUPPLIER_RESPONSE' ? 'Ответ поставщика'
      : t === 'CLIENT_REQUEST' ? 'Письмо клиники' : 'Прочее';
  }

  openImport(r: any) {
    this.importEmailId = r.id;
    this.importPreview = null;
    this.importClientId = null;
    this.importError = '';
    this.importFrom = r.fromAddress || '';
    this.importSubject = r.subject || '';
    this.importExcerpt = r.excerpt || '';
    this.messageExpanded = false;
    this.newClientMode = false;
    this.newClientName = '';
    this.api.previewInbound(r.id).subscribe({
      next: (p) => { this.importPreview = p; this.cdr.detectChanges(); },
      error: (e) => { this.importError = e.error?.message || 'Не удалось разобрать вложение'; this.cdr.detectChanges(); },
    });
  }

  closeImport() { this.importEmailId = null; this.importPreview = null; }

  toggleNewClient() {
    this.newClientMode = !this.newClientMode;
    this.importError = '';
    if (this.newClientMode) {
      this.importClientId = null;
      if (!this.newClientName) this.newClientName = this.displayName(this.importFrom);
    }
  }

  private displayName(from: string): string {
    if (!from) return '';
    const m = from.match(/^(.*?)\s*<.*>$/);
    return (m ? m[1] : from).trim().replace(/^"|"$/g, '');
  }

  createFromImport() {
    if (this.importEmailId === null) return;
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

    if (this.newClientMode) {
      const name = this.newClientName.trim();
      if (!name) { this.importError = 'Введите название клиента'; return; }
      this.importing = true;
      this.api.create('facilities', { name }).subscribe({
        next: (created: any) => { this.doCommit(created.id, mappings, lines); },
        error: (e: any) => {
          this.importing = false;
          this.importError = 'Не удалось создать клиента: ' + (e.error?.message || e.message);
          this.cdr.detectChanges();
        },
      });
    } else {
      if (!this.importClientId) { this.importError = 'Выберите клиента или создайте нового'; return; }
      this.importing = true;
      this.doCommit(this.importClientId, mappings, lines);
    }
  }

  private doCommit(clientId: number, mappings: any[], lines: any[]) {
    const emailId = this.importEmailId;
    this.api.commitImport({ clientFacilityId: clientId, mappings, lines }).subscribe({
      next: () => {
        this.api.markInboundProcessed(emailId as number).subscribe({ next: () => {}, error: () => {} });
        this.importing = false;
        this.closeImport();
        this.notify.success('Заявка создана из письма');
        this.load();
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
