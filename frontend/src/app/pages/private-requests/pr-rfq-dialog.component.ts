import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnInit, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { PrLine, SendResult, Sourcing, dateText, plural } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';

interface DlgLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  /** BRAND / TYPE — из подбора; null — поставщик добавлен из справочника. */
  reason: 'BRAND' | 'TYPE' | null;
  label: string;
  requestedAt: string | null;
  on: boolean;
}

interface DlgSupplier {
  distributorId: number;
  name: string;
  email: string | null;
  hasEmail: boolean;
  lines: DlgLine[];
}

/**
 * «Запросить цены» (спека 2026-10-12 §6.2, этап 1): поставщики с причинами подбора и отметками по умолчанию, позиции
 * у каждого, «＋ Добавить поставщика» из справочника, «Ответить до», предпросмотр письма, отправка — каждому одно письмо
 * только с его позициями. Почта — из карточки поставщика (вписать свою — этап 2).
 */
@Component({
  selector: 'app-pr-rfq-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Запросить цены" (click)="$event.stopPropagation()">
        <h3>Запросить цены<ng-container *ngIf="suppliers.length"> — {{ suppliers.length }} {{ supplierWord(suppliers.length) }}</ng-container></h3>
        <div class="empty" *ngIf="loading">Подбираю поставщиков…</div>
        <div class="error-banner" *ngIf="error">{{ error }}</div>

        <div class="sup" *ngFor="let s of suppliers" [class.on]="isOn(s)">
          <label class="sup-head">
            <input type="checkbox" [checked]="isOn(s)" (change)="toggleSupplier(s, $any($event.target).checked)"
                   [attr.aria-label]="'Запросить у ' + s.name" />
            <span class="sup-name">{{ s.name }}</span>
            <span class="sup-mail" [class.none]="!s.hasEmail">{{ s.hasEmail ? s.email : 'нет почты' }}</span>
          </label>
          <ul class="sup-lines">
            <li *ngFor="let l of s.lines">
              <label class="check">
                <input type="checkbox" [(ngModel)]="l.on" />
                <span class="ln">{{ l.name }} · {{ l.quantity ?? '—' }} {{ l.unit || 'шт' }}</span>
                <span class="why" *ngIf="l.label">{{ l.label }}</span>
                <span class="was" *ngIf="l.requestedAt">запрошено {{ shortDate(l.requestedAt) }}</span>
              </label>
            </li>
          </ul>
          <p class="warn" *ngIf="isOn(s) && !s.hasEmail">Письмо не уйдёт — запрос сохранится, цену внесёте вручную (например, по телефону).</p>
          <button type="button" class="link" [disabled]="!isOn(s) || previewBusy" (click)="showPreview(s)">Посмотреть письмо</button>
        </div>

        <p class="hint" *ngIf="!loading && unmatched.length">Без поставщика: {{ unmatchedText() }} — добавьте поставщика ниже.</p>

        <div class="add" *ngIf="!loading">
          <button type="button" class="btn btn-line" *ngIf="!adding" (click)="startAdd()">＋ Добавить поставщика</button>
          <div class="add-box" *ngIf="adding">
            <input type="search" [(ngModel)]="addQuery" placeholder="Поставщик из справочника…" aria-label="Поиск поставщика" />
            <ul class="add-list">
              <li *ngFor="let d of addOptions()">
                <button type="button" (click)="addSupplier(d)">
                  <span>{{ d.name }}</span><span class="sup-mail" [class.none]="!d.email">{{ d.email || 'нет почты' }}</span>
                </button>
              </li>
            </ul>
            <button type="button" class="btn btn-cancel" (click)="adding = false">Готово</button>
          </div>
        </div>

        <label class="reply">Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>

        <div class="preview" *ngIf="preview">
          <div class="pv-head">Письмо для «{{ previewFor }}»</div>
          <div class="pv-subject">Тема: {{ preview.subject }}</div>
          <pre class="pv-body">{{ preview.body }}</pre>
        </div>

        <div class="form-actions">
          <button type="button" class="btn btn-primary" [disabled]="sending || !planCount()" (click)="send()">{{ sendLabel() }}</button>
          <button type="button" class="btn btn-cancel" [disabled]="sending" (click)="dismiss()">Отмена</button>
        </div>
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
    .sup-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; cursor: pointer; }
    .sup-name { font-weight: 600; }
    .sup-mail { font-size: 12px; color: var(--text-muted); }
    .sup-mail.none { color: var(--warn-text); }
    .sup-lines { list-style: none; margin: 0; padding: 0 0 0 24px; display: flex; flex-direction: column; gap: 4px; }
    .check { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; font-size: 13px; }
    .why, .was { font-size: 12px; color: var(--text-muted); }
    .was { color: var(--warn-text); }
    .warn { margin: 0; font-size: 12px; color: var(--warn-text); }
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
    .reply { display: flex; align-items: center; gap: 8px; font-size: 13px; }
    .preview { border: 1px solid var(--border); border-radius: 8px; padding: 10px 12px; background: var(--surface-2); }
    .pv-head { font-size: 12px; color: var(--text-muted); }
    .pv-subject { font-size: 13px; font-weight: 600; margin: 4px 0; }
    .pv-body { margin: 0; white-space: pre-wrap; font-family: inherit; font-size: 13px; color: var(--text); }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .sup-lines { padding-left: 0; }
      .check { min-height: 40px; }
      .add-box input, .reply input { font-size: 16px; min-height: 40px; }
      .form-actions .btn, .add .btn { min-height: 40px; }
      .link { min-height: 40px; }
    }
  `],
})
export class PrRfqDialogComponent implements OnInit {
  @Input() requestId!: number;
  @Input() lines: PrLine[] = [];
  @Output() close = new EventEmitter<void>();
  @Output() sent = new EventEmitter<SendResult[]>();

  suppliers: DlgSupplier[] = [];
  unmatched: { lotId: number; name: string }[] = [];
  loading = true;
  error = '';
  sending = false;
  adding = false;
  addQuery = '';
  replyBy = '';
  /** Сегодня по часам браузера (toISOString — UTC: до 05:00 по Уральску дал бы вчерашнее число). */
  readonly today = (() => {
    const d = new Date();
    d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
    return d.toISOString().slice(0, 10);
  })();
  preview: { subject: string; body: string } | null = null;
  previewFor = '';
  previewBusy = false;
  private allDistributors: { id: number; name: string; email: string | null }[] = [];

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnInit() {
    this.api.getPrivateRequestSourcing(this.requestId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: (s: Sourcing) => {
        this.suppliers = s.suppliers.map(x => ({
          distributorId: x.distributorId, name: x.name, email: x.email, hasEmail: x.hasEmail,
          lines: x.lines.map(l => ({ lotId: l.lotId, name: l.name, quantity: l.quantity, unit: l.unit, reason: l.reason,
                                     label: l.label, requestedAt: l.requestedAt, on: l.preselected })),
        }));
        this.unmatched = s.unmatched;
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => { this.loading = false; this.error = 'Подбор поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  /**
   * Закрыть окно (фон, Esc, «Отмена»), но не во время отправки: закрытие отписало бы ответ — письма ушли бы,
   * а итог по поставщикам (тост) пользователь не увидел бы.
   */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (this.sending) return;
    this.close.emit();
  }

  supplierWord(n: number) {
    return plural(n, 'поставщик', 'поставщика', 'поставщиков');
  }

  /** «12.10» — как в спеке: «запрошено ДД.ММ». */
  shortDate(iso: string | null): string {
    return dateText(iso).slice(0, 5);
  }

  isOn(s: DlgSupplier): boolean {
    return s.lines.some(l => l.on);
  }

  /** Отметить поставщика: позиции с совпавшим брендом, ещё не запрошенные; если таких нет — все незапрошенные; иначе все. */
  toggleSupplier(s: DlgSupplier, on: boolean) {
    if (!on) {
      s.lines.forEach(l => l.on = false);
      return;
    }
    const brand = s.lines.filter(l => l.reason === 'BRAND' && !l.requestedAt);
    const fresh = s.lines.filter(l => !l.requestedAt);
    (brand.length ? brand : fresh.length ? fresh : s.lines).forEach(l => l.on = true);
  }

  unmatchedText(): string {
    const names = this.unmatched.map(u => u.name);
    return names.slice(0, 3).join(', ') + (names.length > 3 ? ` и ещё ${names.length - 3}` : '');
  }

  startAdd() {
    this.adding = true;
    this.addQuery = '';
    if (this.allDistributors.length) return;
    this.api.getDistributors().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => {
        this.allDistributors = (list || []).map((d: any) => ({ id: d.id, name: d.name, email: d.email || null }));
        this.cdr.markForCheck();
      },
      error: e => { this.error = 'Справочник поставщиков не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  addOptions() {
    const q = this.addQuery.trim().toLowerCase();
    const taken = new Set(this.suppliers.map(s => s.distributorId));
    return this.allDistributors
      .filter(d => !taken.has(d.id) && (!q || d.name.toLowerCase().includes(q)))
      .slice(0, 10);
  }

  /** Поставщик из справочника — со всеми позициями заявки, отмеченными (оператор снимет лишние). */
  addSupplier(d: { id: number; name: string; email: string | null }) {
    const email = d.email && d.email.trim() ? d.email.trim() : null;
    this.suppliers = [...this.suppliers, {
      distributorId: d.id, name: d.name, email, hasEmail: !!email,
      lines: this.lines.map(l => ({ lotId: l.lotId, name: l.name, quantity: l.quantity, unit: l.unit, reason: null,
                                    label: '', requestedAt: null, on: true })),
    }];
    this.unmatched = [];
    this.adding = false;
  }

  showPreview(s: DlgSupplier) {
    const lotIds = s.lines.filter(l => l.on).map(l => l.lotId);
    if (!lotIds.length) return;
    this.previewBusy = true;
    this.api.previewRfq(this.requestId, { distributorId: s.distributorId, lotIds, replyBy: this.replyBy || null })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: p => { this.previewBusy = false; this.preview = p; this.previewFor = s.name; this.cdr.markForCheck(); },
        error: e => { this.previewBusy = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
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
    const mails = plan.filter(p => p.s.hasEmail).length;
    const noMail = plan.length - mails;
    if (!plan.length) return 'Отметьте поставщиков';
    if (!mails) return `Сохранить ${noMail} ${plural(noMail, 'запрос', 'запроса', 'запросов')} без письма`;
    return `Отправить ${mails} ${plural(mails, 'письмо', 'письма', 'писем')}` + (noMail ? ` · без письма: ${noMail}` : '');
  }

  send() {
    const plan = this.plan();
    if (!plan.length) return;
    this.sending = true;
    this.error = '';
    this.api.sendRfq(this.requestId, {
      replyBy: this.replyBy || null,
      suppliers: plan.map(p => ({ distributorId: p.s.distributorId, lotIds: p.lotIds })),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: results => { this.sending = false; this.sent.emit(results); },
      error: e => { this.sending = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }
}
