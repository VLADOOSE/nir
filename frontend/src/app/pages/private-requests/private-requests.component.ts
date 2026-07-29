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
      <table class="responsive-cards" *ngIf="!loading && rows.length">
        <thead><tr><th>Номер</th><th>Клиент</th><th>Позиций</th><th>Реестр</th><th>Статус</th></tr></thead>
        <tbody>
          <tr class="row" *ngFor="let r of rows" (click)="openCard(r)">
            <td class="num" data-label="Номер">{{ r.number }}</td>
            <td data-label="Клиент">{{ r.client?.name || '—' }}</td>
            <td data-label="Позиций">{{ r.lineCount ?? 0 }}</td>
            <td data-label="Реестр">
              <span *ngIf="(r.registeredCount ?? -1) >= 0" class="reg-summary"
                    [class.reg-has]="r.registeredCount > 0">{{ r.registeredCount }} из {{ r.lineCount ?? 0 }} в реестре</span>
              <span *ngIf="(r.registeredCount ?? -1) < 0" class="reg-summary">в карточке</span>
            </td>
            <td data-label="Статус"><span class="badge">{{ r.status }}</span></td>
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
    h1 { font-size: 22px; color: var(--text); }
    .sub { color: var(--text-muted); font-size: 13px; margin-top: 4px; max-width: 640px; }

    /* Кнопки этого экрана стоят в шаблоне БЕЗ базового класса «btn»
       («class="btn-primary"»), а шаблоны эта задача не трогает. Поэтому заливка
       и :hover приходят из kit, а геометрию базовой «.btn» приходится повторить
       здесь — значениями ровно kit-овскими (6px 14px / radius 4px / 13px), чтобы
       кнопка стала «как везде», ради чего унификация и делалась. Без этого
       правила кнопка осталась бы нативной: браузерная рамка и padding 1px 6px. */
    .btn-primary { border: none; padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }
    /* Кнопка не несёт базового класса «btn», поэтому «.btn:disabled» из kit до неё
       не достаёт, а «.btn-primary:hover» — достаёт, и Chrome применяет ховер к
       заблокированной кнопке. Без этих двух строк она подсвечивается как живая
       ровно тогда, когда не работает. Прецедент: private-request-card. */
    .btn-primary:disabled { opacity: .5; cursor: not-allowed; }
    .btn-primary:disabled:hover { background: var(--accent); }
    /* .btn-ghost и .btn-line-solid — контурные кнопки, которых в kit нет: цвет
       живёт здесь. Геометрия выровнена по kit-овской «.btn», потому что обе
       стоят в одном ряду с .btn-primary (форма создания и шапка) — иначе после
       унификации соседи разъехались бы по высоте и радиусу. */
    .btn-ghost { background: var(--surface); border: 1px solid var(--border); color: var(--text); padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-line-solid { background: var(--surface); border: 1px solid var(--border); color: var(--text); padding: 6px 14px; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .form-card { background: var(--surface-2); border: 1px solid var(--border); border-radius: 10px; padding: 16px; margin-bottom: 18px; }
    .form-card h3 { font-size: 15px; margin-bottom: 10px; }
    .form-card label { display: block; font-size: 13px; color: var(--text); margin-bottom: 10px; }
    /* Фон и цвет заданы локально и совпадают с kit (правило input/select/textarea
       вернулось в styles.scss последней задачей волны): объявление несёт ещё и
       геометрию, поэтому описывает поля целиком — --surface на подложке --surface-2. */
    .form-card select, .line input { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .form-card select { min-width: 320px; margin-top: 4px; }
    .lines { margin: 8px 0; }
    .line-head, .line { display: grid; grid-template-columns: 1fr 200px 90px 32px; gap: 8px; align-items: center; margin-bottom: 6px; }
    .line-head span { font-size: 11px; color: var(--text-muted); text-transform: uppercase; }
    .line input { width: 100%; }
    .btn-del { background: var(--surface); border: 1px solid var(--border); border-radius: 6px; cursor: pointer; color: var(--danger-text); }
    /* Пунктир намеренный: это кнопка «добавить ещё», а не обычная линейная.
       Заливку/цвет/рамку даёт kit-овская .btn-line — здесь только стиль рамки и
       геометрия (мельче kit, это отдельная мелкая аффорданса), плюс cursor:
       базового класса «btn», который его даёт, у кнопки в шаблоне нет. */
    .btn-line { border-style: dashed; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; }
    /* margin-top даёт kit, здесь остаётся только раскладка ряда кнопок */
    .form-actions { display: flex; gap: 8px; }
    .err { color: var(--danger-text); font-size: 13px; margin-top: 8px; }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    /* цвет текста и фон шапки даёт kit («th»), здесь остаётся геометрия и линия:
       саму рамку kit не задаёт, только её цвет */
    thead th { text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--border); }
    .row { cursor: pointer; border-bottom: 1px solid var(--border); }
    .row:hover { background: var(--surface-2); }
    .row td { padding: 9px 10px; }
    .num { font-weight: 600; color: var(--accent); }
    /* У бейджа в шаблоне нет класса-статуса («badge-<СТАТУС>»), которым kit
       раскрашивает бейджи, — статус приходит строкой с бэкенда. Поэтому
       нейтральная заливка задана здесь (та же пара, что у kit-овского
       .badge-DRAFT); форму бейджа даёт kit. */
    .badge { background: var(--surface-2); color: var(--text); }
    /* цвет колонки «Реестр» перенесён из инлайн-биндинга: инлайн перебивал лист
       и в тёмной теме давал 2,1:1 */
    .reg-summary { font-size: 12px; font-weight: 600; color: var(--text-muted); }
    .reg-summary.reg-has { color: var(--success-text); }
    .loading { padding: 30px; text-align: center; color: var(--text-muted); }
    .head-actions { display: flex; gap: 8px; align-items: center; }
    .import-panel { border: 1px solid var(--border); border-radius: 10px; padding: 16px; margin: 12px 0; background: var(--surface); }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: var(--text-muted); }
    .import-panel .hint { color: var(--text-muted); font-size: 12px; margin: 6px 0 12px; }
    .import-panel .lbl { display: block; font-size: 12px; color: var(--text); margin-bottom: 4px; }
    .client-sel { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; margin-bottom: 12px; min-width: 260px; background: var(--surface); color: var(--text); }
    .grid-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    /* Цвет текста НЕ приглушённый из kit: в шапке грида импорта стоят заголовки
       ИЗ ФАЙЛА пользователя — это данные, которые он глазами сверяет с разметкой
       колонок, а не служебная подпись. Фон шапки приходит из kit. */
    .import-grid th { color: var(--text); padding: 8px; border: 1px solid var(--border); vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; background: var(--surface); color: var(--text); }
    .import-grid td { padding: 6px 8px; border: 1px solid var(--border); white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
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
