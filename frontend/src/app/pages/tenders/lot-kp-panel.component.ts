import { Component, ChangeDetectorRef, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import { readWithDocument, storeWithDocument, todayIso } from '../../shared/rfq-options';
import { kpToastFromResults } from '../../shared/kp-toast';
import { forkJoin, Observable } from 'rxjs';

/** Панель «Запрос КП» по одному или нескольким лотам: подбор поставщиков, превью письма, отправка. */
@Component({
  selector: 'app-lot-kp-panel',
  standalone: true,
  imports: [NgFor, NgIf, DecimalPipe, FormsModule],
  template: `
    <div class="kp" *ngIf="panel">
      <div class="kp-head">
        <span><b>Запрос КП</b> · выбрано лотов: {{ lots.length }}</span>
        <button class="btn btn-cancel" (click)="close.emit()">✕ Закрыть</button>
      </div>

      <div *ngIf="panel.loading" class="kp-loading">Подбираем поставщиков…</div>

      <ng-container *ngIf="!panel.loading">
        <div class="kp-controls" *ngIf="panel.singleLot">
          <label>Вид МИ:
            <select [ngModel]="typeSelId" (ngModelChange)="changeLotType($event)">
              <option value="">— не задан —</option>
              <option *ngFor="let t of equipmentTypes" [value]="t.id">{{ t.name }}</option>
            </select>
          </label>
          <span class="kp-conf" *ngIf="panel.detectedType && panel.detectedType.confidence < 1">
            авто · {{ (panel.detectedType.confidence * 100) | number:'1.0-0' }}%
          </span>
          <label class="kp-term">Поиск поставщика:
            <input type="text" [(ngModel)]="panel.sourcingTerm" placeholder="бренд/аппарат" (keyup.enter)="researchSupplier()">
          </label>
          <button class="btn btn-line" (click)="researchSupplier()">Найти</button>
        </div>

        <div class="kp-empty" *ngIf="!panel.entries.length">На этом рынке нет поставщиков — добавьте их в справочнике «Дистрибьюторы»</div>
        <div class="kp-empty" *ngIf="panel.entries.length && !panel._relevant.length && !panel.detectedType">
          Нужна техспецификация или вид МИ, чтобы подобрать по специализации.
        </div>

        <ng-container *ngFor="let e of panel._relevant; let i = index">
        <label class="sup" [class.sup-hit]="e.preselect" [class.sup-reco]="i === 0">
          <input type="checkbox" [(ngModel)]="e._checked" [ngModelOptions]="{standalone: true}" />
          <span class="sup-body">
            <span class="sup-name">
              <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener"
                 class="supplier-link" title="Открыть сайт поставщика" (click)="$event.stopPropagation()">{{ e.distributor?.name }} ↗</a>
              <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
              <span *ngIf="!e.distributor?.equipmentTypes?.length" class="tag-all"> · все виды</span>
            </span>
            <span class="sup-mail">{{ e.distributor?.email || '—' }}
              <span class="no-email" *ngIf="!e.distributor?.email">письмо не уйдёт</span>
            </span>
            <span class="sup-reasons">
              <span class="reason-chip" *ngFor="let r of e.reasons"
                    [class.reason-type]="r.kind === 'TYPE'" [class.reason-brand]="r.kind === 'BRAND'" [class.reason-maker]="r.kind === 'MAKER'">
                {{ r.kind === 'TYPE' ? '✓ ' : r.kind === 'MAKER' ? '' : 'возит ' }}{{ r.label }}
              </span>
            </span>
          </span>
        </label>
        <!-- «Указать клиента» по поставщику (спека 2026-10-21-tender-telegram-bot §10 п. 2): по умолчанию — завод и доверенные -->
        <div class="sup-client" *ngIf="e._checked">
          <label><input type="checkbox" [(ngModel)]="e._client" [ngModelOptions]="{standalone: true}" />
            👤 Указать клиента — больница в письме и на бланке</label>
        </div>
        </ng-container>

        <div class="kp-nonrel" *ngIf="panel._nonrel.length">
          <button class="zero-toggle" (click)="panel._showNonrel = !panel._showNonrel">
            {{ panel._showNonrel ? '▴ скрыть нерелевантных' : '▾ ещё ' + panel._nonrel.length + ' нерелевантных' }}
          </button>
          <ng-container *ngIf="panel._showNonrel">
            <ng-container *ngFor="let e of panel._nonrel">
            <label class="sup">
              <input type="checkbox" [(ngModel)]="e._checked" [ngModelOptions]="{standalone: true}" />
              <span class="sup-body">
                <span class="sup-name">
                  <a *ngIf="e.distributor?.website" [href]="e.distributor.website" target="_blank" rel="noopener"
                     class="supplier-link" title="Открыть сайт поставщика" (click)="$event.stopPropagation()">{{ e.distributor?.name }} ↗</a>
                  <span *ngIf="!e.distributor?.website">{{ e.distributor?.name }}</span>
                </span>
                <span class="sup-mail">{{ e.distributor?.email || '—' }}</span>
              </span>
            </label>
            <div class="sup-client" *ngIf="e._checked">
              <label><input type="checkbox" [(ngModel)]="e._client" [ngModelOptions]="{standalone: true}" />
                👤 Указать клиента — больница в письме и на бланке</label>
            </div>
            </ng-container>
          </ng-container>
        </div>

        <div class="kp-opts" *ngIf="panel.entries.length">
          <label>Ответить до <input type="date" [(ngModel)]="replyBy" [min]="today" /></label>
          <label class="kp-opt"><input type="checkbox" [ngModel]="withDocument" (ngModelChange)="setWithDocument($event)" />
            Приложить запрос на бланке (Word)</label>
          <label class="kp-opt" [class.off]="!hasStamp || !withDocument"
                 [attr.title]="hasStamp ? null : 'Печать не загружена — «Система → Реквизиты и печать»'">
            <input type="checkbox" [(ngModel)]="withStamp" [disabled]="!hasStamp || !withDocument" /> Печать
          </label>
        </div>

        <div class="kp-actions" *ngIf="panel.entries.length">
          <button class="btn btn-save" [disabled]="panel.sending || checkedSuppliers().length === 0" (click)="sendKpRequests()">
            {{ panel.sending ? 'Отправка…' : 'Отправить запросы (' + checkedSuppliers().length + ')' }}
          </button>
        </div>
      </ng-container>
    </div>

    <div class="kp-preview-overlay" *ngIf="preview" (click)="cancelPreview()">
      <div class="kp-preview" (click)="$event.stopPropagation()">
        <h3>Текст письма — проверьте перед отправкой</h3>
        <p class="kp-preview-note">Метка [КП-№] будет присвоена автоматически при отправке. Письмо уйдёт {{ preview.distributorIds.length }} поставщик(ам).<ng-container *ngIf="withDocument"> К письму приложится запрос на бланке (Word).</ng-container></p>
        <p class="kp-preview-note" *ngIf="preview.mixed">Поставщикам с клиентом и без него уходят разные тексты: правка ниже — только для писем «{{ preview.sample ? 'с клиентом' : 'без клиента' }}», остальным — стандартный текст.</p>
        <label class="kp-preview-lbl">Тема</label>
        <input class="kp-preview-subject" [(ngModel)]="preview.subject" />
        <label class="kp-preview-lbl">Текст</label>
        <textarea class="kp-preview-body" rows="16" [(ngModel)]="preview.body"></textarea>
        <div class="kp-preview-actions">
          <button class="btn btn-cancel" (click)="cancelPreview()">Отмена</button>
          <button class="btn btn-save" [disabled]="preview.sending" (click)="confirmSendKp()">
            {{ preview.sending ? 'Отправка…' : 'Отправить' }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .kp { border: 1px solid var(--border); background: var(--surface-2); border-radius: 8px; padding: 12px 14px; margin: 12px 0; }
    .kp-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; gap: 12px; flex-wrap: wrap; }
    .kp-loading { color: var(--text-muted); padding: 6px 0; }
    .kp-empty { color: var(--text-muted); font-size: 14px; padding: 12px 0; }
    .kp-controls { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 10px; }
    .kp-controls select, .kp-term input { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px;
                                          font-size: 13px; background: var(--surface); color: var(--text); }
    .kp-conf { font-size: 12px; color: var(--text-muted); }
    .sup { display: flex; align-items: flex-start; gap: 10px; padding: 10px 12px; margin-bottom: 6px; cursor: pointer;
           border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    /* преотмеченный (сильнейший) поставщик должен ВЫДЕЛЯТЬСЯ: --surface-2 совпадал с фоном панели
       и топил лучших, пока слабые светились белым — в старой таблице та же пара читалась наоборот */
    .sup-hit { background: color-mix(in srgb, var(--success) 8%, var(--surface)); border-color: var(--success); }
    .sup-reco { box-shadow: inset 3px 0 0 var(--success); }
    .sup-body { display: flex; flex-direction: column; gap: 3px; min-width: 0; flex: 1; }
    .sup-name { font-size: 14px; color: var(--text); }
    .sup-mail { font-size: 12px; color: var(--text-muted); }
    .sup-reasons { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 2px; }
    .supplier-link { color: var(--accent); text-decoration: none; }
    .supplier-link:hover { text-decoration: underline; }
    .tag-all { color: var(--text-muted); font-size: 11px; }
    .no-email { color: var(--danger-text); font-size: 11px; margin-left: 6px; }
    .reason-chip { display: inline-block; border-radius: 999px; padding: 2px 8px; font-size: 11px; }
    .reason-type { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .reason-brand { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .reason-maker { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .sup-client { margin: -4px 0 6px 30px; font-size: 13px; }
    .sup-client label { display: inline-flex; gap: 6px; align-items: center; cursor: pointer; }
    .zero-toggle { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 12px; padding: 6px 0; }
    .zero-toggle:hover { color: var(--text); text-decoration: underline; }
    .kp-actions { margin-top: 10px; display: flex; justify-content: flex-end; }
    .kp-opts { display: flex; flex-wrap: wrap; align-items: center; gap: 8px 18px; margin-top: 10px; font-size: 13px; }
    .kp-opts input[type="date"] { padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                                  color: var(--text); font: inherit; }
    .kp-opt { display: flex; align-items: center; gap: 6px; cursor: pointer; }
    .kp-opt.off { color: var(--text-muted); cursor: default; }
    .kp-preview-overlay { position: fixed; inset: 0; background: rgba(0,0,0,.4); display: flex; align-items: center;
                          justify-content: center; z-index: 1000; }
    .kp-preview { background: var(--surface); color: var(--text); border-radius: 10px; padding: 20px;
                  width: min(720px, 92vw); max-height: 88vh; overflow: auto; }
    .kp-preview-note { color: var(--text-muted); font-size: 12.5px; margin: 4px 0 12px; }
    .kp-preview-lbl { display: block; font-size: 12px; color: var(--text); margin: 8px 0 3px; }
    .kp-preview-subject, .kp-preview-body { width: 100%; padding: 8px 10px; border: 1px solid var(--border);
                                            border-radius: 6px; background: var(--surface); color: var(--text); }
    .kp-preview-body { font: inherit; resize: vertical; }
    .kp-preview-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 14px; }
    @media (max-width: 900px) {
      .kp-controls { flex-direction: column; align-items: stretch; }
      .kp-actions .btn { width: 100%; }
      .kp-opts label { min-height: 40px; }
      .kp-opts input[type="date"] { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class LotKpPanelComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Input() lots: any[] = [];
  @Input() equipmentTypes: any[] = [];
  /** Переход из бота / окна ответа ИИ (?kp=): отмечен ровно этот поставщик (спека 2026-10-21-tender-telegram-bot §7). */
  @Input() preselectId: number | null = null;
  @Output() sent = new EventEmitter<void>();
  @Output() typeChanged = new EventEmitter<void>();
  @Output() close = new EventEmitter<void>();

  panel: {
    loading: boolean; sending: boolean; entries: any[];
    _relevant: any[]; _nonrel: any[]; _showNonrel: boolean;
    singleLot: boolean;
    detectedType: { id: number; name: string; confidence: number } | null;
    typeAlternatives: { id: number; name: string }[];
    sourcingTerm: string;
    lotId: number | null;
  } | null = null;

  /**
   * Предпросмотр письма: образец — первый поставщик; original* — текст сервера (правку видно сравнением), discloseClientIds —
   * кому строка о больнице, sample — образец «с клиентом», mixed — среди отмеченных есть и с клиентом, и без.
   */
  preview: { subject: string; body: string; originalSubject: string; originalBody: string; sending: boolean;
             distributorIds: number[]; items: any[]; discloseClientIds: number[]; sample: boolean; mixed: boolean } | null = null;

  /**
   * Значение селектора «Вид МИ» — ОТДЕЛЬНОЕ поле, а не выражение по `panel.detectedType`:
   * одностороннее `[ngModel]` не перезаписывает DOM, если выражение не изменилось, поэтому откат
   * после неудавшегося сохранения (оператор-неадмин получает 403) до селектора не доезжал.
   */
  typeSelId: number | '' = '';

  replyBy = '';
  readonly today = todayIso();
  withDocument = readWithDocument();
  withStamp = false;
  hasStamp = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService,
              profiles: CompanyProfileService, destroyRef: DestroyRef) {
    // «Печать» — только при загруженной печати; из кеша приходит синхронно — без detectChanges (§12)
    profiles.profile$().pipe(takeUntilDestroyed(destroyRef)).subscribe({
      next: p => { this.hasStamp = !!p?.hasStamp; this.cdr.markForCheck(); },
      error: () => { /* без профиля галочка просто недоступна */ },
    });
  }

  setWithDocument(on: boolean) {
    this.withDocument = on;
    if (!on) this.withStamp = false;
    storeWithDocument(on);
  }

  /** Опции письма для превью и отправки — одни и те же (спека §6.6). */
  private options() {
    return { replyBy: this.replyBy || null, withDocument: this.withDocument, withStamp: this.withDocument && this.withStamp };
  }

  /** Смена набора лотов = новый подбор. */
  ngOnChanges(ch: SimpleChanges) {
    if (ch['lots']) {
      this.preview = null;
      if (this.lots?.length && this.tenderId) this.load(); else this.panel = null;
    }
  }

  load(term?: string) {
    const tenderId = this.tenderId;
    if (!tenderId || !this.lots?.length) return;
    const lotIds = this.lots.map((l: any) => l.id);
    const single = this.lots.length === 1;
    const lotId = single ? lotIds[0] : null;
    this.panel = {
      loading: true, sending: false, entries: [], _relevant: [], _nonrel: [], _showNonrel: false,
      singleLot: single, detectedType: null, typeAlternatives: [], sourcingTerm: '', lotId,
    };
    this.cdr.detectChanges();
    this.api.getLotSourcing(tenderId, lotIds, term).subscribe({
      next: (r) => {
        // переход из бота (?kp=): отмечен ровно этот поставщик; «Указать клиента» — у завода и доверенных
        const entries = (r?.distributors || []).map((e: any) => ({ ...e,
          _checked: this.preselectId != null ? e.distributor?.id === this.preselectId : !!e.preselect,
          _client: !!(e.distributor?.manufacturer || e.distributor?.trusted) }));
        this.panel = {
          loading: false, sending: false, entries,
          _relevant: entries.filter((e: any) => e.relevant),
          _nonrel: entries.filter((e: any) => !e.relevant),
          _showNonrel: entries.some((e: any) => !e.relevant && e._checked),
          singleLot: !!r?.singleLot,
          detectedType: r?.detectedType || null,
          typeAlternatives: r?.typeAlternatives || [],
          sourcingTerm: r?.sourcingTerm || '',
          lotId,
        };
        this.typeSelId = r?.detectedType?.id ?? '';
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.panel = null;
        this.notify.error('Ошибка подбора поставщиков: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Сменить вид МИ лота из панели → сохранить и пересобрать подбор. */
  changeLotType(typeId: any) {
    const id = typeId === '' || typeId == null ? null : Number(typeId);
    if (!this.panel?.lotId) return;
    const term = this.panel.sourcingTerm || undefined;
    const prev = this.typeSelId;
    this.typeSelId = id ?? '';
    this.api.setLotEquipmentType(this.panel.lotId, id).subscribe({
      next: () => { this.typeChanged.emit(); this.load(term); },
      error: (e) => {
        // не сохранилось (штатный путь: снятие/смена типа закрыты ADMIN, оператор получает 403) →
        // возвращаем прежний вид МИ, иначе селектор показывал НЕсохранённое значение
        this.typeSelId = prev;
        this.notify.error(e.error?.message || 'Ошибка сохранения типа');
        this.cdr.detectChanges();
      },
    });
  }

  /** Точечный поиск поставщика по введённому термину (Tier 2). */
  researchSupplier() { this.load(this.panel?.sourcingTerm || undefined); }

  checkedSuppliers(): any[] {
    return (this.panel?.entries || []).filter((e: any) => e._checked);
  }

  sendKpRequests() {
    const tenderId = this.tenderId;
    if (!tenderId || !this.panel) return;
    const distributorIds = this.checkedSuppliers().map((e: any) => e.distributor.id);
    const discloseClientIds = this.checkedSuppliers().filter((e: any) => e._client).map((e: any) => e.distributor.id);
    const items = this.lots
      .map((l: any) => ({ tenderLotId: l.id, medEquipmentId: l.proposedEquipment?.id ?? null, requestedQuantity: l.quantity ?? null }));   // нет количества — «кол-во уточняется» (B4)
    if (!distributorIds.length || !items.length) return;
    this.panel.sending = true;
    this.api.previewKp({ tenderId, distributorIds, discloseClientIds, items, ...this.options() }).subscribe({
      next: (p) => {
        this.preview = { subject: p.subject, body: p.body, originalSubject: p.subject, originalBody: p.body, sending: false,
          distributorIds, items, discloseClientIds,
          sample: discloseClientIds.includes(distributorIds[0]),   // образец предпросмотра — первый поставщик
          mixed: discloseClientIds.length > 0 && discloseClientIds.length < distributorIds.length };
        if (this.panel) this.panel.sending = false;
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.panel) this.panel.sending = false;
        this.notify.error('Ошибка превью: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  confirmSendKp() {
    const tenderId = this.tenderId;
    const p = this.preview;
    if (!tenderId || !p) return;
    p.sending = true;
    // текст не правили — каждому поставщику свой (приветствие, строка о клиенте); правили — правка только поставщикам
    // того же вида, что образец (с клиентом / без), остальным — стандартный текст: строка о больнице не уйдёт дилеру
    const edited = p.subject !== p.originalSubject || p.body !== p.originalBody;
    const withClient = new Set<number>(p.discloseClientIds);
    const base = { tenderId, items: p.items, ...this.options() };
    const calls: Observable<any[]>[] = [];
    if (!edited) {
      calls.push(this.api.sendPriceRequests({ ...base, distributorIds: p.distributorIds, discloseClientIds: [...withClient] }));
    } else {
      const same = p.distributorIds.filter((id: number) => withClient.has(id) === p.sample);
      const other = p.distributorIds.filter((id: number) => withClient.has(id) !== p.sample);
      calls.push(this.api.sendPriceRequests({ ...base, distributorIds: same, discloseClientIds: p.sample ? same : [],
        subjectOverride: this.subjectHuman(p.subject), bodyOverride: p.body }));
      if (other.length) calls.push(this.api.sendPriceRequests({ ...base, distributorIds: other, discloseClientIds: p.sample ? [] : other }));
    }
    forkJoin(calls).subscribe({
      next: (rs) => {
        const results = rs.flat();
        const t = kpToastFromResults(results);
        if (t.isError) this.notify.error(t.message); else this.notify.success(t.message);
        this.preview = null; this.panel = null;
        this.sent.emit();
        this.cdr.detectChanges();
      },
      error: (e) => {
        if (this.preview) this.preview.sending = false;
        this.notify.error('Ошибка отправки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  /** Убрать токен [КП-…] из отредактированной темы — сервер добавит свой. */
  private subjectHuman(subject: string): string {
    return (subject || '').replace(/\[КП-\d+\]\s*/g, '').trim();
  }

  cancelPreview() { this.preview = null; this.cdr.detectChanges(); }
}
