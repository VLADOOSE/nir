import { Component, ChangeDetectorRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { DecimalPipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { kpToastFromResults } from '../../shared/kp-toast';

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

        <label class="sup" *ngFor="let e of panel._relevant; let i = index" [class.sup-hit]="e.preselect" [class.sup-reco]="i === 0">
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
                    [class.reason-type]="r.kind === 'TYPE'" [class.reason-brand]="r.kind === 'BRAND'">
                {{ r.kind === 'TYPE' ? '✓' : 'возит' }} {{ r.label }}
              </span>
            </span>
          </span>
        </label>

        <div class="kp-nonrel" *ngIf="panel._nonrel.length">
          <button class="zero-toggle" (click)="panel._showNonrel = !panel._showNonrel">
            {{ panel._showNonrel ? '▴ скрыть нерелевантных' : '▾ ещё ' + panel._nonrel.length + ' нерелевантных' }}
          </button>
          <ng-container *ngIf="panel._showNonrel">
            <label class="sup" *ngFor="let e of panel._nonrel">
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
          </ng-container>
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
        <p class="kp-preview-note">Метка [КП-№] будет присвоена автоматически при отправке. Письмо уйдёт {{ preview.distributorIds.length }} поставщик(ам).</p>
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
    .no-email { color: var(--danger); font-size: 11px; margin-left: 6px; }
    .reason-chip { display: inline-block; border-radius: 999px; padding: 2px 8px; font-size: 11px; }
    .reason-type { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success); }
    .reason-brand { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .zero-toggle { background: none; border: none; color: var(--text-muted); cursor: pointer; font-size: 12px; padding: 6px 0; }
    .zero-toggle:hover { color: var(--text); text-decoration: underline; }
    .kp-actions { margin-top: 10px; display: flex; justify-content: flex-end; }
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
    .btn { padding: 6px 14px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn:disabled { opacity: .5; cursor: not-allowed; }
    .btn-cancel { background: var(--surface-2); color: var(--text); }
    .btn-save { background: var(--accent); color: var(--accent-contrast); }
    .btn-line { background: var(--surface); color: var(--text); border: 1px solid var(--border); }

    @media (max-width: 900px) {
      .kp-controls { flex-direction: column; align-items: stretch; }
      .kp-actions .btn { width: 100%; }
    }
  `],
})
export class LotKpPanelComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Input() lots: any[] = [];
  @Input() equipmentTypes: any[] = [];
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

  preview: { subject: string; body: string; sending: boolean; distributorIds: number[]; items: any[] } | null = null;

  /**
   * Значение селектора «Вид МИ» — ОТДЕЛЬНОЕ поле, а не выражение по `panel.detectedType`:
   * одностороннее `[ngModel]` не перезаписывает DOM, если выражение не изменилось, поэтому откат
   * после неудавшегося сохранения (оператор-неадмин получает 403) до селектора не доезжал.
   */
  typeSelId: number | '' = '';

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService) {}

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
        const entries = (r?.distributors || []).map((e: any) => ({ ...e, _checked: !!e.preselect }));
        this.panel = {
          loading: false, sending: false, entries,
          _relevant: entries.filter((e: any) => e.relevant),
          _nonrel: entries.filter((e: any) => !e.relevant),
          _showNonrel: false,
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
    const items = this.lots
      .map((l: any) => ({ tenderLotId: l.id, medEquipmentId: l.proposedEquipment?.id ?? null, requestedQuantity: l.quantity ?? 1 }));
    if (!distributorIds.length || !items.length) return;
    this.panel.sending = true;
    this.api.previewKp({ tenderId, distributorIds, items }).subscribe({
      next: (p) => {
        this.preview = { subject: p.subject, body: p.body, sending: false, distributorIds, items };
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
    if (!tenderId || !this.preview) return;
    this.preview.sending = true;
    this.api.sendPriceRequests({
      tenderId,
      distributorIds: this.preview.distributorIds,
      items: this.preview.items,
      subjectOverride: this.subjectHuman(this.preview.subject),
      bodyOverride: this.preview.body,
    }).subscribe({
      next: (results) => {
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
