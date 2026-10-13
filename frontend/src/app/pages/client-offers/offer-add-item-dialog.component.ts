import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Subscription, catchError, forkJoin, of } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { regValidity } from '../../shared/private-request';
import type { RegistryRecord } from '../../shared/private-request';
import type { EquipmentHit } from '../../shared/client-offer';
import { regExpired } from '../../shared/registry';
import { marketToday } from '../../shared/deadline';

export type AddPick = { kind: 'registry'; record: RegistryRecord } | { kind: 'catalog'; item: EquipmentHit };

/** «Из реестра и каталога» (спека 2026-10-13 §2.4): поиск — две группы, выбор добавляет позицию в конец КП. */
@Component({
  selector: 'app-offer-add-item-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="veil" (click)="close.emit()"></div>
    <div class="dlg" role="dialog" [attr.aria-label]="'Добавить позицию — ' + title()" (click)="$event.stopPropagation()">
      <div class="head">
        <h3>{{ title() }}</h3>
        <button type="button" class="btn btn-line" (click)="close.emit()" aria-label="Закрыть">✕</button>
      </div>
      <input type="search" [(ngModel)]="query" (input)="onQuery()" placeholder="Название, модель или производитель"
             aria-label="Поиск в реестре и каталоге" />
      <p class="hint" *ngIf="loading">Ищу…</p>
      <p class="hint" *ngIf="!loading && searched && !catalog.length && !registry.length">Ничего не нашлось</p>
      <div class="group" *ngIf="catalog.length">
        <div class="g-title">Каталог</div>
        <button type="button" class="hit" *ngFor="let e of catalog" (click)="picked.emit({ kind: 'catalog', item: e })">
          <span class="h-name">{{ e.name }}</span>
          <span class="h-meta">{{ e.manufact || 'производитель не указан' }}<ng-container *ngIf="e.registry"> · РУ № {{ e.registry.regNumber }}</ng-container>
            <ng-container *ngIf="!e.registry"> · РУ не привязан</ng-container></span>
        </button>
      </div>
      <div class="group" *ngIf="registry.length">
        <div class="g-title">Реестр НЦЭЛС</div>
        <button type="button" class="hit" *ngFor="let r of registry" (click)="picked.emit({ kind: 'registry', record: r })">
          <span class="h-name">{{ r.name }}</span>
          <span class="h-meta" [class.expired]="expired(r)">{{ r.producer || '—' }}<ng-container *ngIf="r.country"> · {{ r.country }}</ng-container>
            · № {{ r.regNumber }}<ng-container *ngIf="validity(r)"> · {{ validity(r) }}</ng-container><ng-container *ngIf="expired(r)"> — срок истёк</ng-container></span>
        </button>
      </div>
      <p class="error-banner" *ngIf="error">{{ error }}</p>
    </div>
  `,
  styles: [`
    .veil { position: fixed; inset: 0; background: rgba(17, 24, 39, .5); z-index: 40; }
    .dlg { position: fixed; z-index: 41; top: 10vh; left: 50%; transform: translateX(-50%); width: 560px; max-width: calc(100vw - 32px);
           max-height: 80vh; overflow-y: auto; background: var(--surface); border: 1px solid var(--border); border-radius: 12px;
           box-shadow: var(--shadow-lg); padding: 16px; display: flex; flex-direction: column; gap: 10px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
    h3 { margin: 0; font-size: 16px; }
    input[type="search"] { padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--text); font-size: 14px; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .group { display: flex; flex-direction: column; gap: 4px; }
    .g-title { font-size: 11px; text-transform: uppercase; letter-spacing: .04em; color: var(--text-muted); }
    .hit { width: 100%; text-align: left; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 8px 10px;
           cursor: pointer; display: flex; flex-direction: column; gap: 2px; color: var(--text); min-height: 40px; }
    .hit:hover { background: var(--surface-2); }
    .h-name { font-size: 13px; overflow-wrap: anywhere; }
    .h-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .h-meta.expired { color: var(--danger-text); }
    @media (max-width: 900px) {
      .dlg { top: 0; left: 0; transform: none; width: 100vw; max-width: 100vw; height: 100vh; max-height: 100vh; border-radius: 0; }
      input[type="search"] { font-size: 16px; min-height: 40px; }
    }
  `],
})
export class OfferAddItemDialogComponent implements OnDestroy {
  /** Реестр РУ — только Казахстан; на РФ — только каталог. */
  @Input() kz = false;
  @Output() picked = new EventEmitter<AddPick>();
  @Output() close = new EventEmitter<void>();

  query = '';
  catalog: EquipmentHit[] = [];
  registry: RegistryRecord[] = [];
  loading = false;
  searched = false;
  error = '';
  private timer: any = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  @HostListener('document:keydown.escape')
  onEsc() {
    this.close.emit();
  }

  ngOnDestroy() {
    clearTimeout(this.timer);
  }

  /** На РФ реестра нет — только каталог (спека 2026-10-13 §3). */
  title() {
    return this.kz ? 'Из реестра и каталога' : 'Из каталога';
  }

  validity(r: RegistryRecord) {
    return regValidity(r);
  }

  expired(r: RegistryRecord) {
    return regExpired(r, marketToday('KZ'));
  }

  onQuery() {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.search(), 300);
  }

  private search() {
    const q = this.query.trim();
    this.sub?.unsubscribe();
    this.error = '';
    // запрос в полёте отменён — «Ищу…» снимаем вместе с ним (ревью ветки, M2)
    if (q.length < 2) { this.catalog = []; this.registry = []; this.searched = false; this.loading = false; this.cdr.markForCheck(); return; }
    this.loading = true;
    let failed = '';
    const fail = (what: string) => catchError(() => { failed = what; return of([]); });
    this.sub = forkJoin({
      catalog: this.api.searchEquipment(q, 8).pipe(fail('каталог')),
      registry: this.kz ? this.api.searchRegistry(q, 8).pipe(fail('реестр')) : of([] as RegistryRecord[]),
    }).pipe(takeUntilDestroyed(this.destroyRef)).subscribe(r => {
      this.catalog = r.catalog as EquipmentHit[];
      this.registry = r.registry as RegistryRecord[];
      this.loading = false;
      this.searched = true;
      this.error = failed ? `Поиск не ответил: ${failed} — повторите` : '';
      this.cdr.markForCheck();
    });
  }
}
