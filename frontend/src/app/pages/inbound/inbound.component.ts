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

    <table class="grid responsive-cards card-grid inbound-list" *ngIf="rows.length">
      <thead>
        <tr><th>Отправитель</th><th>Тема</th><th>Получено</th><th>Тип</th><th>Статус</th><th></th></tr>
      </thead>
      <tbody>
        <tr *ngFor="let r of rows">
          <td data-label="Отправитель">{{ r.fromAddress }}</td>
          <td data-label="Тема">{{ r.subject }}</td>
          <td class="when" data-label="Получено">{{ formatReceived(r.receivedAt) }}</td>
          <td data-label="Тип">
            <span class="badge" [class.b-sup]="r.type==='SUPPLIER_RESPONSE'"
                  [class.b-cli]="r.type==='CLIENT_REQUEST'" [class.b-unm]="r.type==='UNMATCHED'">
              {{ typeLabel(r.type) }}
            </span>
            <span *ngIf="r.type==='SUPPLIER_RESPONSE' && r.matchedPriceRequestId" class="muted"> · КП #{{ r.matchedPriceRequestId }}</span>
          </td>
          <td data-label="Статус">{{ r.status==='PROCESSED' ? 'Обработано' : 'Новое' }}</td>
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
    .sub { color: var(--text-muted); font-size: 13px; margin: 4px 0 0; }
    /* Обе кнопки в шаблоне несут ТОЛЬКО класс .btn-primary, без базового .btn, —
       геометрия из kit (.btn) до них не достаёт. Поэтому цвет отдаём kit
       (.btn-primary = --accent/--accent-contrast), а геометрию повторяем здесь
       ровно kit-овскими значениями. Не удалять как «дубль»: без неё кнопка
       станет нативной. */
    .btn-primary { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    /* Кнопка не несёт базового класса «btn», поэтому «.btn:disabled» из kit до неё
       не достаёт, а «.btn-primary:hover» — достаёт, и Chrome применяет ховер к
       заблокированной кнопке. Без этих двух строк она подсвечивается как живая
       ровно тогда, когда не работает. Прецедент: private-request-card. */
    .btn-primary:disabled { opacity: .5; cursor: not-allowed; }
    .btn-primary:disabled:hover { background: var(--accent); }
    .grid { width: 100%; border-collapse: collapse; background: var(--surface); border-radius: 8px; overflow: hidden; }
    /* .grid th специфичнее голого th из kit — kit сюда не достаёт. Повторяем его
       цвета на месте, чтобы шапка таблицы совпадала с остальными экранами. */
    .grid th { background: var(--surface-2); text-align: left; padding: 10px; font-size: 12px; color: var(--text-muted); }
    .grid td { padding: 10px; border-top: 1px solid var(--border); font-size: 13px; }
    .b-sup { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .b-cli { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .b-unm { background: var(--surface-2); color: var(--text-muted); }
    .muted { color: var(--text-muted); font-size: 12px; }
    .when { white-space: nowrap; color: var(--text); font-size: 12px; }
    .import-panel { border: 1px solid var(--border); border-radius: 10px; padding: 16px; margin-top: 16px; background: var(--surface); }
    .import-head { display: flex; justify-content: space-between; align-items: center; }
    .import-head .x { background: none; border: none; font-size: 22px; cursor: pointer; color: var(--text-muted); }
    .lbl { display: block; font-size: 12px; color: var(--text); margin: 8px 0 4px; }
    /* Фон и цвет заданы локально и совпадают с kit (правило input/select/textarea
       вернулось в styles.scss последней задачей волны): объявление несёт ещё и
       геометрию, поэтому описывает селект целиком и не зависит от глобального слоя. */
    .client-sel { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; margin-bottom: 12px; min-width: 260px; background: var(--surface); color: var(--text); }
    .grid-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    /* Цвет текста НЕ приглушённый из kit: в шапке грида импорта стоят заголовки
       ИЗ ФАЙЛА пользователя — это данные, которые он глазами сверяет с разметкой
       колонок, а не служебная подпись. Тот же грид и то же правило живут в
       private-requests — держать их одинаковыми. */
    .import-grid th { background: var(--surface-2); color: var(--text); padding: 8px; border: 1px solid var(--border); vertical-align: top; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; background: var(--surface); color: var(--text); }
    .import-grid td { padding: 6px 8px; border: 1px solid var(--border); white-space: nowrap; }
    .import-actions { display: flex; gap: 8px; margin-top: 12px; }
    .err { color: var(--danger-text); font-size: 13px; margin: 8px 0; }
    /* .btn-line-solid — контурная кнопка, которой в kit нет: цвет живёт здесь.
       Радиус выровнен по kit-овской «.btn» (4px), потому что кнопка стоит в одном
       ряду с .btn-primary (.import-actions: «Создать заявку» + «Отмена») — иначе
       после унификации соседи разъезжаются по радиусу. Тот же класс в
       private-requests.component.ts приведён к 4px по этой же причине; не
       возвращать 6px. */
    .btn-line-solid { background: var(--surface); border: 1px solid var(--border); border-radius: 4px; padding: 6px 14px; cursor: pointer; font-size: 13px; color: var(--text); }
    .client-row { display: flex; gap: 8px; align-items: center; }
    .client-sel:disabled { background: var(--surface-2); color: var(--text-muted); }
    .new-client { margin: 8px 0 4px; display: flex; align-items: center; flex-wrap: wrap; }
    .new-client input { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; min-width: 320px; background: var(--surface); color: var(--text); }
    .hint-sm { color: var(--text-muted); font-size: 12px; margin-left: 8px; }
    .msg-preview { background: var(--surface-2); border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; margin: 8px 0 14px; }
    .msg-meta { font-size: 12px; color: var(--text-muted); margin-bottom: 6px; }
    .msg-from { font-weight: 600; color: var(--text); }
    .msg-body { font-size: 13px; color: var(--text); white-space: pre-wrap; line-height: 1.5; }
    /* #000 в mask-image — НЕ цвет, а непрозрачность маски (плавное затухание
       обрезанного текста). Маска читает альфу, тема на неё не влияет:
       токенизировать нечего, оставлено намеренно. */
    .msg-body.clamped { max-height: 4.5em; overflow: hidden; -webkit-mask-image: linear-gradient(180deg, #000 60%, transparent); }
    .msg-toggle { margin-top: 6px; background: none; border: none; color: var(--accent); cursor: pointer; font-size: 12px; padding: 0; }

    /* ============================================================
       МОБИЛЬНАЯ КАРТОЧКА — ПОСЛЕДНИЙ БЛОК В styles (CLAUDE.md §14: при равной
       специфичности позднее базовое правило молча перебивает @media-правило).
       Общее поведение card-grid — в глобальном styles.scss; здесь только
       раскладка этого списка.
       ============================================================ */
    @media (max-width: 900px) {
      /* Было 6 строк «подпись: значение» — 188–337px на карточку. Стало три:
           ТЕМА (2 строки, «…»)
           отправитель                        получено
           [бейдж типа] · КП #id              [Импортировать]
         Порядок как в почтовом клиенте: чем письмо является — сверху крупно,
         служебное — ниже мелким.
         Скрыт «Статус» («Новое»/«Обработано»): на карточке его роль несёт
         кнопка «Импортировать» — она и появляется ровно у необработанного
         письма клиники с вложением. Единственное скрытие, о котором стоит
         спорить, см. отчёт задачи. */
      /* Сброс десктопной геометрии .grid: её правила (.grid td — 10px padding и
         разделительная линия; .grid — своя подложка и радиус) СПЕЦИФИЧНЕЕ
         глобального card-grid, поэтому без явного сброса карточка распухла бы
         на 8px в каждой ячейке, а внутри неё рисовались бы линии таблицы. */
      .inbound-list { background: none; border-radius: 0; }
      .inbound-list td { padding: 2px 0; border-top: none; }
      .inbound-list tr {
        grid-template-columns: minmax(0, 1fr) auto;
        grid-template-areas:
          "subj subj"
          "from when"
          "type act";
      }
      /* Клэмп прямо на ячейке: внутри неё только текст (случай facilities с
         вложенным флексом здесь не возникает). Две строки — темы писем длинные
         и различаются в конце: «…оборудования и расходных материалов на 2026». */
      .inbound-list td[data-label="Тема"] {
        grid-area: subj; font-size: 15px; font-weight: 600; color: var(--text);
        overflow: hidden; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
      }
      /* display:block — иначе текст становится анонимным флекс-элементом и
         многоточие до него не достаёт (адреса вида «ГКП на ПХВ «Городская
         поликлиника №5 г. Уральск» <zakup@…>» длиннее ячейки всегда) */
      .inbound-list td[data-label="Отправитель"] {
        grid-area: from; display: block; color: var(--text-muted); font-size: 12px;
        overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
      }
      .inbound-list td[data-label="Получено"] {
        grid-area: when; justify-content: flex-end; color: var(--text-muted);
        font-size: 11px; white-space: nowrap;
      }
      /* nowrap обязателен: механический слой ставит ячейкам flex-wrap: wrap, и
         хвост «· КП #508» уезжал под бейдж, добавляя карточке лишнюю строку. */
      .inbound-list td[data-label="Тип"] {
        grid-area: type; justify-content: flex-start; flex-wrap: nowrap;
        gap: 4px; overflow: hidden;
      }
      .inbound-list td[data-label="Статус"] { display: none; }
      .inbound-list td:not([data-label]) { grid-area: act; justify-content: flex-end; }
    }
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

  formatReceived(iso: string): string {
    if (!iso) return '—';
    try {
      const s = new Intl.DateTimeFormat('ru-RU', {
        weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric',
        hour: '2-digit', minute: '2-digit',
      }).format(new Date(iso));
      return s.charAt(0).toUpperCase() + s.slice(1);   // «Пн, 22.06.2026, 16:56»
    } catch {
      return iso;
    }
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
