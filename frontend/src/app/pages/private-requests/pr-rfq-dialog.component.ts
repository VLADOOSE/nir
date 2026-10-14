import { ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, OnInit, Output, ViewChild } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import {
  DocFormat, PrLine, RfqPreviewBody, SendResult, Sourcing, dateText, dispositionFileName, plural, sameEmails,
} from '../../shared/private-request';
import { canShareFiles, openShareSheet, responseFile } from '../../shared/share';
import { readWithDocument, storeWithDocument, todayIso } from '../../shared/rfq-options';
import { saveBlob } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

type Reason = 'MANUFACTURER' | 'BRAND' | 'TYPE';

interface DlgLine {
  lotId: number;
  name: string;
  brand: string | null;
  quantity: number | null;
  unit: string | null;
  /** Из подбора; null — поставщик добавлен руками. */
  reason: Reason | null;
  label: string;
  requestedAt: string | null;
  on: boolean;
}

interface DlgSupplier {
  /** «d:<id>» — из справочника, «new:<название>» — новый, ещё без id. */
  key: string;
  distributorId: number | null;
  name: string;
  /** Почта из карточки ('' — пусто): от неё — подсказка, что станет с вписанной почтой. */
  cardEmail: string;
  email: string;
  manufacturer: boolean;
  trusted: boolean;
  isNew: boolean;
  rememberBrands: boolean;
  disclose: boolean;
  /** «Передам сам»: письма нет, запрос записывается, его PDF пересылают руками (WhatsApp). */
  manual: boolean;
  lines: DlgLine[];
}

/** Записанный запрос «передам сам» — его PDF готовится сразу: «Поделиться» браузер пускает только прямо из нажатия. */
interface DoneFile { result: SendResult; file: File | null; loading: boolean; error: string; wordBusy: boolean; }

interface DirEntry { id: number; name: string; email: string | null; trusted: boolean; manufacturer: boolean; }

/**
 * «Запросить цены» (спека 2026-10-12 §6.2): поставщики с причинами подбора и отметками по умолчанию (у бренда с
 * производителем — производитель), почта каждого — полем (пустая карточка — сохранится, другая — только для письма),
 * «Указать клиента» (по умолчанию — у доверенных), новый поставщик, «Ответить до», запрос на бланке (Word) с подписью и
 * печатью, предпросмотр письма и документа (PDF или Word) — ровно того, что уйдёт. «Передам сам» — письма нет: запрос
 * записывается, и окно показывает его PDF — скачать или «Поделиться» (WhatsApp) — с номером записанного запроса.
 */
@Component({
  selector: 'app-pr-rfq-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Запросить цены" (click)="$event.stopPropagation()">
        <h3 *ngIf="!done">Запросить цены<ng-container *ngIf="suppliers.length"> — {{ suppliers.length }} {{ supplierWord(suppliers.length) }}</ng-container></h3>
        <h3 *ngIf="done">{{ done.files.length === 1 ? 'Запрос записан' : 'Запросы записаны' }} — передайте сами</h3>

        <div class="done" *ngIf="done">
          <p class="done-lead">{{ doneLead() }}</p>
          <div class="done-row" *ngFor="let f of done.files">
            <div class="done-name">{{ f.result.distributorName }} · Исх. № {{ f.result.priceRequestId }}</div>
            <div class="done-actions">
              <span class="hint" *ngIf="f.loading">Готовлю PDF…</span>
              <ng-container *ngIf="f.file">
                <button type="button" class="btn btn-primary" *ngIf="canShare" (click)="share(f)">Поделиться</button>
                <button type="button" class="btn btn-line" (click)="savePdf(f)">Скачать PDF</button>
              </ng-container>
              <button type="button" class="link" [disabled]="f.wordBusy" (click)="saveWord(f)">Word</button>
            </div>
            <p class="done-err" *ngIf="f.error">{{ f.error }} <button type="button" class="link" *ngIf="!f.file"
               (click)="loadPdf(f)">Повторить</button></p>
          </div>
          <div class="form-actions">
            <button type="button" class="btn btn-cancel" (click)="finish()">Готово</button>
          </div>
        </div>

        <ng-container *ngIf="!done">
        <div class="empty" *ngIf="loading">Подбираю поставщиков…</div>

        <div class="sup" *ngFor="let s of suppliers; trackBy: trackSupplier" [class.on]="isOn(s)">
          <div class="sup-head">
            <label class="sup-pick">
              <input type="checkbox" [checked]="isOn(s)" (change)="toggleSupplier(s, $any($event.target).checked)"
                     [attr.aria-label]="'Запросить у ' + s.name" />
              <span class="sup-name">{{ s.name }}</span>
              <span class="flag" *ngIf="s.manufacturer">производитель</span>
              <span class="flag" *ngIf="s.trusted">доверенный</span>
              <span class="flag" *ngIf="s.isNew">новый</span>
            </label>
            <div class="sup-opts">
              <label class="disclose" [class.off]="!clientName"
                     [attr.title]="clientName ? 'Строка о конечном заказчике в письме и документе' : 'Клиент в заявке не указан'">
                <input type="checkbox" [(ngModel)]="s.disclose" [disabled]="!clientName" /> Указать клиента
              </label>
              <label class="disclose" title="Письма не будет: запрос запишется, его PDF перешлёте сами — например, в WhatsApp">
                <input type="checkbox" [(ngModel)]="s.manual" /> Передам сам
              </label>
            </div>
          </div>
          <ng-container *ngIf="!s.manual">
            <input class="mail" type="text" inputmode="email" autocomplete="off" [(ngModel)]="s.email"
                   placeholder="впишите почту — несколько через запятую" [attr.aria-label]="'Почта ' + s.name" />
            <p class="mail-hint" *ngIf="mailHint(s) as hint">{{ hint }}</p>
          </ng-container>
          <ul class="sup-lines">
            <li *ngFor="let l of s.lines">
              <label class="check">
                <input type="checkbox" [(ngModel)]="l.on" />
                <span class="lt"><span class="ln">{{ l.name }} · {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span><span class="why"
                      *ngIf="l.label">{{ l.label }}</span><span class="was" *ngIf="l.requestedAt">запрошено {{ shortDate(l.requestedAt) }}</span></span>
              </label>
            </li>
          </ul>
          <p class="warn" *ngIf="isOn(s) && !s.manual && !s.email.trim()">Письмо не уйдёт — запрос сохранится, цену внесёте вручную (например, по телефону).</p>
          <p class="note" *ngIf="isOn(s) && s.manual">Письма не будет — после записи скачайте PDF или поделитесь им (например, в WhatsApp); цену внесёте, когда поставщик ответит.</p>
          <label class="remember" *ngIf="s.isNew && brandsText(s) as brands">
            <input type="checkbox" [(ngModel)]="s.rememberBrands" /> Запомнить: возит {{ brands }}
          </label>
          <div class="sup-actions">
            <button type="button" class="link" *ngIf="!s.manual" [disabled]="!isOn(s) || busy" (click)="showPreview(s)">Посмотреть письмо</button>
            <ng-container *ngIf="withDocument || s.manual">
              <button type="button" class="link" [disabled]="!isOn(s) || busy" (click)="downloadDocument(s, 'pdf')">Скачать PDF</button>
              <button type="button" class="link" [disabled]="!isOn(s) || busy" (click)="downloadDocument(s, 'docx')">Скачать Word</button>
            </ng-container>
          </div>
        </div>

        <p class="hint" *ngIf="!loading && unmatched.length">Без поставщика: {{ unmatchedText() }} — добавьте поставщика ниже.</p>

        <div class="add" *ngIf="!loading">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">＋ Добавить поставщика</button>
          <div class="add-box" *ngIf="adding">
            <input type="search" [(ngModel)]="addQuery" placeholder="Поставщик из справочника или название нового…"
                   aria-label="Поиск поставщика" />
            <ul class="add-list">
              <li *ngFor="let d of addOptions()">
                <button type="button" (click)="addSupplier(d)">
                  <span>{{ d.name }}</span><span class="sup-mail" [class.none]="!d.email">{{ d.email || 'нет почты' }}</span>
                </button>
              </li>
              <li *ngIf="canAddNew()">
                <button type="button" class="new-sup" (click)="addNew()">＋ Новый поставщик «{{ addQuery.trim() }}»</button>
              </li>
            </ul>
            <button type="button" class="btn btn-cancel" (click)="adding = false">Готово</button>
          </div>
        </div>

        <div class="opts">
          <label class="reply">Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>
          <label class="opt"><input type="checkbox" [ngModel]="withDocument" (ngModelChange)="setWithDocument($event)" />
            Приложить запрос на бланке (Word)</label>
          <label class="opt" [class.off]="!hasStamp || !docWanted()" [attr.title]="stampTitle()">
            <input type="checkbox" [ngModel]="withStamp && docWanted()" (ngModelChange)="withStamp = $event"
                   [disabled]="!hasStamp || !docWanted()" /> Печать
          </label>
        </div>

        <div class="preview" *ngIf="preview">
          <div class="pv-head">Письмо для «{{ previewFor }}»</div>
          <div class="pv-subject">Тема: {{ preview.subject }}</div>
          <pre class="pv-body">{{ preview.body }}</pre>
        </div>

        <!-- плашка — над кнопками: при 3+ поставщиках оператор внизу, у «Отправить», и верх окна ему не виден -->
        <div class="error-banner" *ngIf="error" role="alert">{{ error }}</div>
        <div class="form-actions" #actions>
          <button type="button" class="btn btn-primary" [disabled]="sending || !planCount()" (click)="send()">{{ sendLabel() }}</button>
          <button type="button" class="btn btn-cancel" [disabled]="sending" (click)="dismiss()">Отмена</button>
        </div>
        </ng-container>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 760px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 12px; }
    h3 { margin: 0; }
    .sup { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 6px; }
    .sup.on { background: color-mix(in srgb, var(--accent) 6%, var(--surface)); border-color: var(--accent); }
    .sup-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; flex-wrap: wrap; }
    .sup-pick { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
    .sup-name { font-weight: 600; }
    .flag { font-size: 11px; border-radius: 999px; padding: 1px 8px; background: color-mix(in srgb, var(--accent) 15%, transparent);
            color: var(--accent); }
    .sup-opts { display: flex; align-items: center; gap: 4px 14px; flex-wrap: wrap; }
    .disclose { display: flex; align-items: center; gap: 6px; font-size: 13px; cursor: pointer; }
    .disclose.off, .opt.off { color: var(--text-muted); cursor: default; }
    .mail { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text);
            font: inherit; font-size: 13px; }
    .mail-hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .sup-mail { font-size: 12px; color: var(--text-muted); }
    .sup-mail.none { color: var(--warn-text); }
    .sup-lines { list-style: none; margin: 0; padding: 0 0 0 24px; display: flex; flex-direction: column; gap: 4px; }
    /* флажок слева, текст позиции с пометками течёт справа: длинное наименование переносится рядом с флажком */
    .check { display: flex; align-items: flex-start; gap: 8px; font-size: 13px; }
    .check input { flex: none; margin-top: 2px; }
    .lt { flex: 1; min-width: 0; }
    .ln, .why { margin-right: 8px; }
    .why, .was { font-size: 12px; color: var(--text-muted); }
    .was { color: var(--warn-text); }
    .warn { margin: 0; font-size: 12px; color: var(--warn-text); }
    .note { margin: 0; font-size: 12px; color: var(--text-muted); }
    .done { display: flex; flex-direction: column; gap: 10px; }
    .done-lead { margin: 0; font-size: 13px; color: var(--text-muted); }
    .done-row { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; }
    .done-name { font-weight: 600; }
    .done-actions { display: flex; align-items: center; gap: 8px 12px; flex-wrap: wrap; }
    .done-actions .hint { margin: 0; }
    .done-actions .link { align-self: center; }
    .done-err { margin: 0; font-size: 12px; color: var(--danger-text); }
    .remember { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .sup-actions { display: flex; gap: 16px; flex-wrap: wrap; }
    .link { align-self: flex-start; background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; }
    .link:disabled { color: var(--text-muted); cursor: default; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .add-box { display: flex; flex-direction: column; gap: 6px; }
    .add-box input, .reply input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                                    color: var(--text); font: inherit; font-size: 14px; }
    .add-list { list-style: none; margin: 0; padding: 0; max-height: 200px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
    .add-list button { width: 100%; display: flex; justify-content: space-between; gap: 8px; text-align: left; background: var(--surface);
                       border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px; cursor: pointer; color: var(--text); }
    .add-list button:hover { background: var(--surface-2); }
    .new-sup { color: var(--accent) !important; }
    .opts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; }
    .reply, .opt { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .opt { cursor: pointer; }
    .preview { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; background: var(--surface-2); }
    .pv-head { font-size: 12px; color: var(--text-muted); }
    .pv-subject { font-size: 13px; font-weight: 600; margin: 4px 0; }
    .pv-body { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .sup-lines { padding-left: 0; }
      .check, .disclose, .opt, .remember { min-height: 40px; }
      .mail, .add-box input, .reply input { font-size: 16px; min-height: 40px; }
      .form-actions .btn, .add .btn, .link, .done-actions .btn { min-height: 40px; }
    }
  `],
})
export class PrRfqDialogComponent implements OnInit {
  @Input() requestId!: number;
  @Input() lines: PrLine[] = [];
  /** Клиент заявки — учреждение или название; пусто — «Указать клиента» недоступна. */
  @Input() clientName: string | null = null;
  @Output() close = new EventEmitter<void>();
  @Output() sent = new EventEmitter<SendResult[]>();

  suppliers: DlgSupplier[] = [];
  unmatched: { lotId: number; name: string }[] = [];
  loading = true;
  error = '';
  sending = false;
  /** Идёт предпросмотр письма или сборка документа. */
  busy = false;
  adding = false;
  addQuery = '';
  replyBy = '';
  readonly today = todayIso();
  withDocument = readWithDocument();
  withStamp = false;
  hasStamp = false;
  preview: { subject: string; body: string } | null = null;
  previewFor = '';
  /** Записано с «передам сам» — окно показывает их PDF; итог отправки уходит странице по «Готово». */
  done: { results: SendResult[]; files: DoneFile[] } | null = null;
  /** Итог уже отдан странице: «Готово» и Esc в одном кадре не дают два тоста. */
  private finished = false;
  readonly canShare = canShareFiles();
  private directory: DirEntry[] = [];
  @ViewChild('actions') private actions?: ElementRef<HTMLElement>;
  private destroyed = false;

  constructor(private api: ApiService, private profiles: CompanyProfileService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    destroyRef.onDestroy(() => this.destroyed = true);
  }

  ngOnInit() {
    // профиль из кеша приходит синхронно — markForCheck, не detectChanges (CLAUDE.md §12)
    this.profiles.profile$().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.hasStamp = !!p?.hasStamp; this.cdr.markForCheck(); },
      error: () => { /* без профиля «Печать» просто недоступна */ },
    });
    this.api.getPrivateRequestSourcing(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (s: Sourcing) => {
        this.suppliers = s.suppliers.map(x => ({
          key: 'd:' + x.distributorId, distributorId: x.distributorId, name: x.name,
          cardEmail: x.email || '', email: x.email || '', manufacturer: x.manufacturer, trusted: x.trusted,
          isNew: false, rememberBrands: false, disclose: x.trusted && !!this.clientName, manual: false,
          lines: x.lines.map(l => ({ lotId: l.lotId, name: l.name, brand: this.brandOf(l.lotId), quantity: l.quantity,
                                     unit: l.unit, reason: l.reason, label: l.label, requestedAt: l.requestedAt,
                                     on: l.preselected })),
        }));
        this.unmatched = s.unmatched;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => { this.loading = false; this.fail('Подбор поставщиков не загрузился: ' + errorText(e)); },
    });
  }

  /** Закрыть окно (фон, Esc, «Отмена»), но не во время отправки: ответ отписался бы, итога пользователь не увидел бы. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (this.sending) return;
    if (this.done) { this.finish(); return; }   // запросы уже записаны — странице нужен итог, а не «отмена»
    this.close.emit();
  }

  supplierWord(n: number) {
    return plural(n, 'поставщик', 'поставщика', 'поставщиков');
  }

  /** «12.10» — как в спеке: «запрошено ДД.ММ». */
  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  trackSupplier(_: number, s: DlgSupplier) {
    return s.key;
  }

  isOn(s: DlgSupplier): boolean {
    return s.lines.some(l => l.on);
  }

  /** Отметить поставщика: позиции по бренду (производитель или «возит»), ещё не запрошенные; нет таких — все незапрошенные. */
  toggleSupplier(s: DlgSupplier, on: boolean) {
    if (!on) {
      s.lines.forEach(l => l.on = false);
      return;
    }
    const brand = s.lines.filter(l => (l.reason === 'MANUFACTURER' || l.reason === 'BRAND') && !l.requestedAt);
    const fresh = s.lines.filter(l => !l.requestedAt);
    (brand.length ? brand : fresh.length ? fresh : s.lines).forEach(l => l.on = true);
  }

  /** Что станет с вписанной почтой (спека §6.2): в пустую карточку — сохранится; другая — только для этого письма. */
  mailHint(s: DlgSupplier): string {
    const typed = s.email.trim();
    if (!typed || s.isNew) return '';
    if (!s.cardEmail) return 'Почта сохранится в карточку поставщика';
    return sameEmails(typed, s.cardEmail) ? '' : 'Письмо уйдёт на вписанный адрес; карточка поставщика не изменится';
  }

  /** Бренды отмеченных позиций — для «Запомнить: возит …» у нового поставщика. */
  brandsText(s: DlgSupplier): string {
    const seen = new Map<string, string>();
    for (const l of s.lines) if (l.on && l.brand && l.brand.trim()) seen.set(l.brand.trim().toLowerCase(), l.brand.trim());
    return [...seen.values()].join(', ');
  }

  unmatchedText(): string {
    const names = this.unmatched.map(u => u.name);
    return names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    if (this.directory.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.directory = (list || []).map((d: any) => ({ id: d.id, name: d.name, email: d.email || null,
                                                         trusted: !!d.trusted, manufacturer: !!d.manufacturer }));
        this.cdr.markForCheck();
      },
      error: e => this.fail('Справочник поставщиков не загрузился: ' + errorText(e)),
    });
  }

  addOptions(): DirEntry[] {
    const q = this.addQuery.trim().toLowerCase();
    const taken = new Set(this.suppliers.map(s => s.distributorId));
    return this.directory.filter(d => !taken.has(d.id) && (!q || d.name.toLowerCase().includes(q))).slice(0, 10);
  }

  /** «Новый поставщик» — когда такого названия нет ни в справочнике, ни среди выбранных. */
  canAddNew(): boolean {
    const q = this.addQuery.trim().toLowerCase();
    return q.length >= 2 && !this.directory.some(d => d.name.toLowerCase() === q)
      && !this.suppliers.some(s => s.name.toLowerCase() === q);
  }

  addSupplier(d: DirEntry) {
    const email = d.email && d.email.trim() ? d.email.trim() : '';
    this.push({ key: 'd:' + d.id, distributorId: d.id, name: d.name, cardEmail: email, email, manufacturer: d.manufacturer,
                trusted: d.trusted, isNew: false, rememberBrands: false, disclose: d.trusted && !!this.clientName,
                manual: false, lines: this.allLines() });
  }

  /** Новый поставщик заводится в «Дистрибьюторах» при отправке (спека §6.2). */
  addNew() {
    const name = this.addQuery.trim();
    this.push({ key: 'new:' + name.toLowerCase(), distributorId: null, name, cardEmail: '', email: '', manufacturer: false,
                trusted: false, isNew: true, rememberBrands: true, disclose: false, manual: false, lines: this.allLines() });
  }

  private push(s: DlgSupplier) {
    this.suppliers = [...this.suppliers, s];
    this.unmatched = [];
    this.adding = false;
  }

  /** Позиции заявки, все отмечены (оператор снимет лишние). */
  private allLines(): DlgLine[] {
    return this.lines.map(l => ({ lotId: l.lotId, name: l.name, brand: l.brand, quantity: l.quantity, unit: l.unit,
                                  reason: null, label: '', requestedAt: null, on: true }));
  }

  private brandOf(lotId: number): string | null {
    return this.lines.find(l => l.lotId === lotId)?.brand ?? null;
  }

  setWithDocument(on: boolean) {
    this.withDocument = on;
    if (!on && !this.docWanted()) this.withStamp = false;
    storeWithDocument(on);
  }

  /** Документ будет хоть у кого-то: во вложении письма («Приложить запрос») или у «передам сам» — печати есть куда встать. */
  docWanted(): boolean {
    return this.withDocument || this.suppliers.some(s => s.manual && this.isOn(s));
  }

  stampTitle(): string | null {
    if (!this.hasStamp) return 'Печать не загружена — «Система → Реквизиты и печать»';
    return this.docWanted() ? null : 'Печать ставится в документ: отметьте «Приложить запрос на бланке» или «Передам сам»';
  }

  private previewBody(s: DlgSupplier): RfqPreviewBody {
    return {
      ...(s.distributorId != null ? { distributorId: s.distributorId } : { newSupplierName: s.name }),
      lotIds: s.lines.filter(l => l.on).map(l => l.lotId),
      replyBy: this.replyBy || null,
      discloseClient: s.disclose && !!this.clientName,
      withDocument: this.withDocument || s.manual,
      withStamp: (this.withDocument || s.manual) && this.withStamp,
    };
  }

  showPreview(s: DlgSupplier) {
    const body = this.previewBody(s);
    if (!body.lotIds.length) return;
    this.busy = true;
    this.api.previewRfq(this.requestId, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: p => { this.busy = false; this.preview = p; this.previewFor = s.name; this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.fail(errorText(e)); },
    });
  }

  /** Документ ровно для этого поставщика — PDF или Word; «№ —» — номер появится при записи запроса. */
  downloadDocument(s: DlgSupplier, format: DocFormat) {
    const body = this.previewBody(s);
    if (!body.lotIds.length) return;
    this.busy = true;
    this.api.downloadRfqDocument(this.requestId, body, format).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        this.busy = false;
        if (r.body) saveBlob(r.body, dispositionFileName(r.headers.get('Content-Disposition'), 'Запрос КП.' + format));
        this.cdr.markForCheck();
      },
      error: e => this.blobError(e),
    });
  }

  /** Ошибка ответа-blob: текст сервера лежит JSON-ом внутри Blob — достаём его (через .then, не await — §14). */
  private blobError(e: any) {
    this.busy = false;
    if (e?.error instanceof Blob) {
      e.error.text().then((t: string) => {
        let text = errorText(e);
        try { text = JSON.parse(t).message || text; } catch { /* тело не JSON — общий текст */ }
        this.fail(text);
      });
    } else {
      this.fail(errorText(e));
    }
  }

  /**
   * Ошибка — в плашке прямо над кнопками, и окно прокручивается к кнопкам (плашка над ними видна тоже): иначе «Отправить»
   * просто возвращалась бы в исходное состояние (ошибка вверху окна, а оператор — внизу). detectChanges — чтобы плашка
   * уже стояла в DOM и сдвинула кнопки до прокрутки.
   */
  private fail(text: string) {
    if (this.destroyed) return;
    this.error = text;
    this.cdr.detectChanges();
    this.actions?.nativeElement.scrollIntoView({ block: 'nearest' });
  }

  private plan() {
    return this.suppliers
      .map(s => ({ s, lotIds: s.lines.filter(l => l.on).map(l => l.lotId) }))
      .filter(p => p.lotIds.length > 0);
  }

  planCount(): number {
    return this.plan().length;
  }

  sendLabel(): string {
    if (this.sending) return 'Отправляю…';
    const plan = this.plan();
    if (!plan.length) return 'Отметьте поставщиков';
    const manual = plan.filter(p => p.s.manual).length;
    const mails = plan.filter(p => !p.s.manual && p.s.email.trim()).length;
    const noMail = plan.length - manual - mails;
    if (!mails) {
      const n = plan.length;
      return manual === n
        ? `Записать ${n} ${plural(n, 'запрос', 'запроса', 'запросов')} — передам сам`
        : `Сохранить ${n} ${plural(n, 'запрос', 'запроса', 'запросов')} без письма`;
    }
    return `Отправить ${mails} ${plural(mails, 'письмо', 'письма', 'писем')}`
      + (manual ? ` · передам сам: ${manual}` : '') + (noMail ? ` · без письма: ${noMail}` : '');
  }

  send() {
    const plan = this.plan();
    if (!plan.length) return;
    this.sending = true;
    this.error = '';
    this.api.sendRfq(this.requestId, {
      replyBy: this.replyBy || null,
      withDocument: this.withDocument,
      withStamp: this.docWanted() && this.withStamp,
      suppliers: plan.map(p => ({
        distributorId: p.s.distributorId,
        newSupplier: p.s.isNew ? { name: p.s.name, rememberBrands: p.s.rememberBrands } : null,
        email: p.s.manual ? null : p.s.email.trim(),
        discloseClient: p.s.disclose && !!this.clientName,
        manual: p.s.manual,
        lotIds: p.lotIds,
      })),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: results => {
        this.sending = false;
        const manual = results.filter(r => r.reason === 'MANUAL');
        if (!manual.length) { this.sent.emit(results); return; }
        this.done = { results, files: manual.map(r => ({ result: r, file: null, loading: true, error: '', wordBusy: false })) };
        this.done.files.forEach(f => this.loadPdf(f));
        this.cdr.markForCheck();
      },
      error: e => { this.sending = false; this.fail(errorText(e)); },
    });
  }

  doneLead(): string {
    const d = this.done;
    if (!d) return '';
    const mails = d.results.filter(r => r.emailSent).length;
    // письма, которые не ушли (нет почты, сбой) — видно сразу, а не только в тосте после «Готово»
    const failed = d.results.filter(r => r.reason === 'NO_EMAIL' || r.reason === 'SEND_FAILED').map(r => r.distributorName);
    return (mails ? `Писем отправлено: ${mails}. ` : '')
      + (failed.length ? `Письмо не ушло: ${failed.join(', ')}. ` : '')
      + 'Перешлите PDF поставщику сами — например, в WhatsApp. Цену впишите в таблицу цен, когда поставщик ответит.';
  }

  /** PDF записанного запроса — с его номером; готовится сразу, чтобы «Поделиться» ушло прямо из нажатия. */
  loadPdf(f: DoneFile) {
    f.loading = true;
    f.error = '';
    this.api.downloadPriceRequestDocument(f.result.priceRequestId, 'pdf').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { f.loading = false; f.file = responseFile(r, 'Запрос КП.pdf'); this.cdr.markForCheck(); },
      error: () => { f.loading = false; f.error = 'PDF не собрался.'; this.cdr.markForCheck(); },
    });
  }

  share(f: DoneFile) {
    if (!f.file) return;
    openShareSheet(f.file, text => { f.error = text; this.cdr.markForCheck(); });
  }

  savePdf(f: DoneFile) {
    if (f.file) saveBlob(f.file, f.file.name);
  }

  saveWord(f: DoneFile) {
    if (f.wordBusy) return;
    f.wordBusy = true;
    this.api.downloadPriceRequestDocument(f.result.priceRequestId, 'docx').pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        f.wordBusy = false;
        const file = responseFile(r, 'Запрос КП.docx');
        if (file) saveBlob(file, file.name);
        this.cdr.markForCheck();
      },
      error: () => { f.wordBusy = false; f.error = 'Word не собрался — попробуйте ещё раз.'; this.cdr.markForCheck(); },
    });
  }

  /** «Готово» (и закрытие окна после записи) — итог отправки странице: тост, таблица цен, этап; один раз. */
  finish() {
    if (!this.done || this.finished) return;
    this.finished = true;
    this.sent.emit(this.done.results);
  }
}
