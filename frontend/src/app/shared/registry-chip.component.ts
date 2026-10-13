import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { RegAction, RegistryRecord, regValidity } from './private-request';
import { ChipStatus, RegistryChipActions, chipLabel, chipTone, regExpired } from './registry';
import { marketToday } from './deadline';
import { errorText } from './error-text';

/** Открыта может быть только одна метка: клик по второй закрывает первую (клик внутри метки до document не доходит). */
let openChip: RegistryChipComponent | null = null;

/**
 * Метка РУ позиции (спека 2026-10-12 §5.3, 2026-10-13 §2.1–2.2) — одна на строку: «РУ ✓», «РУ истекло …», «РУ?»,
 * «нет в реестре», «не подлежит», «РУ вручную», «не проверено». Клик — окошко с записью реестра и действиями:
 * подтвердить, найти в реестре, «не подлежит», снять/вернуть. Состояние приходит входами, действия выполняет
 * владелец строки (`actions`): частная заявка — запросом, КП — правкой строки и автосохранением.
 */
@Component({
  selector: 'app-registry-chip',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <span class="rc" (click)="$event.stopPropagation()">
      <button type="button" class="chip" [attr.data-status]="tone()" (click)="toggle()"
              [attr.aria-expanded]="open" [attr.aria-label]="'Регистрация: ' + label()">{{ label() }}</button>
      <div class="pop" *ngIf="open" [class.start]="align === 'start'" role="dialog" aria-label="Регистрация в реестре">
        <div class="rec" *ngIf="record as r">
          <div class="rec-kind">{{ status === 'CONFIRMED' ? 'Подтверждено' : 'Похоже на' }}</div>
          <div class="rec-name">{{ r.name }}</div>
          <div class="rec-meta">{{ r.producer || 'производитель не указан' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container></div>
          <div class="rec-meta" [class.expired]="expired(r)">№ {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container>
            <ng-container *ngIf="expired(r)"> — срок истёк: больнице не продать, пока РУ не продлено</ng-container></div>
        </div>
        <div class="rec-none" *ngIf="!record">{{ noneText() }}</div>

        <div class="pop-actions" *ngIf="!readonly">
          <button type="button" class="btn btn-primary" *ngIf="status === 'SUGGESTED'" [disabled]="busy"
                  (click)="act('CONFIRM')">Подтвердить</button>
          <button type="button" class="btn btn-line" *ngIf="status === 'CONFIRMED'" [disabled]="busy"
                  (click)="act('RESET')">Снять подтверждение</button>
          <button type="button" class="btn btn-line" *ngIf="status === 'NOT_REQUIRED'" [disabled]="busy"
                  (click)="act('RESET')">Вернуть проверку</button>
          <button type="button" class="btn btn-line" *ngIf="!searching" [disabled]="busy" (click)="openSearch()">
            {{ status === 'SUGGESTED' ? 'Не то — найти в реестре' : 'Найти в реестре' }}</button>
          <button type="button" class="btn btn-line" *ngIf="status !== 'NOT_REQUIRED'" [disabled]="busy"
                  (click)="act('NOT_REQUIRED')">Не подлежит</button>
        </div>

        <div class="search" *ngIf="searching && !readonly">
          <input type="search" [(ngModel)]="query" (input)="onQuery()" placeholder="Название, модель или производитель"
                 aria-label="Поиск в реестре" />
          <div class="hint" *ngIf="loadingResults">Ищу…</div>
          <div class="hint" *ngIf="!loadingResults && searchedOnce && !results.length">Ничего не нашлось</div>
          <ul class="results">
            <li *ngFor="let r of results">
              <button type="button" [disabled]="busy" (click)="act('CONFIRM', r)">
                <span class="res-name">{{ r.name }}</span>
                <span class="res-meta">{{ r.producer || '—' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container>
                  · № {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container></span>
              </button>
            </li>
          </ul>
        </div>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
      </div>
    </span>
  `,
  styles: [`
    .rc { position: relative; display: inline-block; }
    .chip { border: none; border-radius: 10px; padding: 3px 10px; font-size: 12px; font-weight: 600; cursor: pointer; white-space: nowrap;
            background: var(--surface-2); color: var(--text); }
    .chip[data-status="CONFIRMED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .chip[data-status="SUGGESTED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .chip[data-status="NOT_FOUND"], .chip[data-status="EXPIRED"] {
      background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .chip[data-status="NOT_REQUIRED"], .chip[data-status="MANUAL"], .chip[data-status="UNCHECKED"] {
      background: transparent; border: 1px solid var(--border); color: var(--text-muted); }
    .pop { position: absolute; right: 0; top: calc(100% + 6px); z-index: 30; width: 360px; max-width: calc(100vw - 32px);
           background: var(--surface); border: 1px solid var(--border); border-radius: 10px; box-shadow: var(--shadow-lg);
           padding: 12px; display: flex; flex-direction: column; gap: 10px; text-align: left; }
    /* метка у левого края строки (наименование позиции КП): окошко открывается вправо, а не под боковое меню */
    .pop.start { left: 0; right: auto; }
    .rec-kind { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .rec-name { font-size: 14px; color: var(--text); font-weight: 600; overflow-wrap: anywhere; }
    .rec-meta, .rec-none { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .rec-meta.expired { color: var(--danger-text); }
    .pop-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .search { display: flex; flex-direction: column; gap: 6px; }
    .search input { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 14px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    .results { list-style: none; margin: 0; padding: 0; max-height: 240px; overflow-y: auto; display: flex; flex-direction: column; gap: 4px; }
    .results button { width: 100%; text-align: left; background: var(--surface); border: 1px solid var(--border); border-radius: 8px;
                      padding: 8px 10px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; color: var(--text); }
    .results button:hover { background: var(--surface-2); }
    .res-name { font-size: 13px; overflow-wrap: anywhere; }
    .res-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .error-banner { margin: 0; }
    @media (max-width: 900px) {
      .chip { min-height: 40px; padding: 0 12px; }   /* цель 40 px (спека §5.6) — строка позиции на телефоне и так 40 px */
      .pop, .pop.start { position: fixed; left: 16px; right: 16px; top: auto; bottom: 16px; width: auto; max-height: 70vh; overflow-y: auto; }
      .pop-actions .btn, .results button { min-height: 40px; }
      .search input { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class RegistryChipComponent implements OnDestroy {
  @Input() status: ChipStatus = null;
  @Input() record: RegistryRecord | null = null;
  /** Текст поиска по умолчанию: наименование, модель, бренд строки. */
  @Input() seed = '';
  @Input({ required: true }) actions!: RegistryChipActions;
  @Input() readonly = false;
  /** С какой стороны метки открывается окошко: 'end' — влево от правого края (метка в конце строки), 'start' — вправо. */
  @Input() align: 'start' | 'end' = 'end';
  @Output() changed = new EventEmitter<unknown>();

  open = false;
  searching = false;
  busy = false;
  error = '';
  query = '';
  results: RegistryRecord[] = [];
  loadingResults = false;
  searchedOnce = false;
  private timer: any = null;
  private searchSub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnDestroy() {
    clearTimeout(this.timer);
    if (openChip === this) openChip = null;
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  close() {
    if (!this.open) return;
    this.open = false;
    this.searching = false;
    if (openChip === this) openChip = null;
  }

  /** Реестр РУ — только Казахстан: «сегодня» для срока РУ — по часам KZ. */
  private today() {
    return marketToday('KZ');
  }

  label() {
    return chipLabel(this.status, this.record, this.today());
  }

  tone() {
    return chipTone(this.status, this.record, this.today());
  }

  expired(r: RegistryRecord) {
    return regExpired(r, this.today());
  }

  validity(r: RegistryRecord) {
    return regValidity(r);
  }

  noneText(): string {
    switch (this.status) {
      case 'NOT_REQUIRED': return 'Отмечено: не подлежит регистрации.';
      case 'NOT_FOUND': return this.readonly
        ? 'В реестре РК подходящего РУ нет — больнице такое не продать.'   // оператору без прав — без советов, которые ему не выполнить
        : 'В реестре РК подходящего РУ нет — найдите вручную или отметьте «не подлежит».';
      case 'MANUAL': return 'Номер РУ вписан вручную — проверьте по реестру или оставьте.';
      default: return 'Регистрация ещё не проверялась.';
    }
  }

  toggle() {
    if (this.open) {
      this.close();
      return;
    }
    const prev = openChip;   // close() обнуляет openChip — держим ссылку, чтобы отметить вид прежней метки
    if (prev && prev !== this) {
      prev.close();
      prev.cdr.markForCheck();
    }
    openChip = this;
    this.open = true;
    this.searching = false;
    this.error = '';
  }

  /** Сначала кандидаты по наименованию и бренду позиции — они точнее общего поиска; дальше — свой запрос. */
  openSearch() {
    this.searching = true;
    this.query = this.seed;
    this.results = [];          // прошлый поиск не мелькает, пока грузятся кандидаты этой позиции
    this.searchedOnce = false;
    this.loadingResults = true;
    this.searchSub?.unsubscribe();
    this.searchSub = this.actions.candidates()
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: r => { this.results = r || []; this.loadingResults = false; this.searchedOnce = true; this.cdr.markForCheck(); },
        error: e => { this.loadingResults = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
  }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.searchSub?.unsubscribe();
    if (q.length < 2) {
      this.results = [];
      return;
    }
    this.loadingResults = true;
    this.searchSub = this.api.searchRegistry(q, 10).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.results = r || []; this.loadingResults = false; this.searchedOnce = true; this.cdr.markForCheck(); },
      error: e => { this.loadingResults = false; this.error = errorText(e); this.cdr.markForCheck(); },
    });
  }

  act(action: RegAction, record?: RegistryRecord) {
    this.busy = true;
    this.error = '';
    this.actions.act(action, record)
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: res => { this.busy = false; this.close(); this.changed.emit(res); this.cdr.markForCheck(); },
        error: e => { this.busy = false; this.error = errorText(e); this.cdr.markForCheck(); },
      });
  }
}
