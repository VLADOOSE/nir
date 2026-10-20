import { AfterViewInit, ChangeDetectorRef, Component, DestroyRef, ElementRef, EventEmitter, HostListener, Input, Output, ViewChild,
  inject } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { Subject, catchError, debounceTime, distinctUntilChanged, map, of, switchMap } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { errorText } from '../../shared/error-text';
import { isoDate, regShort } from '../../shared/lot-model';
import type { LotModelSearch, OurModelHit, RegistryModelHit } from '../../shared/lot-model';

interface Picked { kind: 'our' | 'reg'; key: number | string; name: string; }

/** Окно «📌 Модель в работе» (спека 2026-10-20-lot-model-in-work §7): наши товары и реестр НЦЭЛС одним поиском. */
@Component({
  selector: 'app-lot-model-dialog',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="overlay" (click)="dismiss()">
      <div class="modal" role="dialog" aria-label="Модель в работе" (click)="$event.stopPropagation()">
        <h3>📌 Модель в работе — {{ lot.equipName }}</h3>
        <input #qInput class="q" type="search" [(ngModel)]="q" (ngModelChange)="query$.next($event)"
               placeholder="Название, бренд, модель или № РУ" aria-label="Поиск модели">
        <p class="hint" *ngIf="q.trim().length < 2">Вставьте модель из ответа ИИ — найдём её среди наших товаров и в реестре НЦЭЛС.</p>
        <p class="hint" *ngIf="searching">Ищу…</p>
        <ng-container *ngIf="result as r">
          <section *ngIf="r.ours.length">
            <h4>Наши товары (westmed.kz)</h4>
            <button type="button" class="hit" *ngFor="let o of r.ours" [class.on]="isPicked('our', o.productId)" (click)="pickOur(o)">
              <span class="h-name">{{ o.name }}</span>
              <span class="h-meta">{{ o.brand || '' }}{{ o.sku ? ' · ' + o.sku : '' }} ·
                <span [class.bad]="o.regStatus !== 'ACTIVE'">{{ regShort(o.regStatus, o.regExpires) }}</span></span>
            </button>
          </section>
          <section *ngIf="r.registry.length">
            <h4>Реестр НЦЭЛС</h4>
            <button type="button" class="hit" *ngFor="let g of r.registry" [class.on]="isPicked('reg', g.regNumber)" (click)="pickReg(g)">
              <span class="h-name">{{ g.name }}</span>
              <span class="h-meta">{{ g.producer || '' }}{{ g.country ? ', ' + g.country : '' }} · РУ {{ g.regNumber }} ·
                <span [class.bad]="g.expired">{{ regTerm(g) }}</span></span>
            </button>
          </section>
          <p class="hint" *ngIf="!searching && !r.ours.length && !r.registry.length">Ничего не нашлось — уточните запрос.</p>
        </ng-container>
        <!-- выбор виден и после нового поиска: «Закрепить» закрепит именно его -->
        <p class="picked" *ngIf="picked">Выбрано: <b>{{ picked.name }}</b> · {{ picked.kind === 'our' ? 'наш товар' : 'реестр, РУ ' + picked.key }}</p>
        <label class="note" *ngIf="picked">Почему эта модель (можно вставить вывод ИИ)
          <textarea [(ngModel)]="note" maxlength="1000" rows="3"></textarea>
        </label>
        <p class="replace" *ngIf="picked && lot.proposedEquipment">Заменит «{{ lot.proposedEquipment.name }}».</p>
        <div class="error-banner" *ngIf="error">{{ error }}</div>
        <div class="form-actions">
          <button type="button" class="btn btn-line" (click)="dismiss()" [disabled]="busy">Отмена</button>
          <button type="button" class="btn btn-primary" (click)="pin()" [disabled]="!picked || busy">{{ busy ? 'Закрепляю…' : '📌 Закрепить' }}</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1100; display: flex; align-items: center;
               justify-content: center; padding: 16px; }
    .modal { background: var(--surface); color: var(--text); border-radius: 12px; box-shadow: var(--shadow-lg); width: 100%;
             max-width: 680px; max-height: 90vh; overflow-y: auto; padding: 20px 22px; display: flex; flex-direction: column; gap: 10px; }
    h3 { margin: 0; overflow-wrap: anywhere; }
    h4 { margin: 6px 0 4px; font-size: 13px; color: var(--text-muted); font-weight: 600; }
    .q { padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    section { display: flex; flex-direction: column; gap: 6px; }
    .hit { text-align: left; border: 1px solid var(--border); border-radius: 10px; padding: 8px 10px; background: var(--surface);
           color: var(--text); cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .hit.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .h-name { font-weight: 600; overflow-wrap: anywhere; }
    .h-meta { font-size: 12px; color: var(--text-muted); overflow-wrap: anywhere; }
    .h-meta .bad { color: var(--warn-text); }
    .note { display: flex; flex-direction: column; gap: 4px; font-size: 13px; }
    .note textarea { font: inherit; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .picked { margin: 0; font-size: 13px; overflow-wrap: anywhere; }
    .replace { margin: 0; font-size: 13px; color: var(--warn-text); overflow-wrap: anywhere; }
    .form-actions { display: flex; gap: 8px; flex-wrap: wrap; justify-content: flex-end; margin-top: 0; }
    @media (max-width: 900px) {
      .overlay { padding: 0; align-items: stretch; }
      .modal { max-width: none; max-height: none; height: 100%; border-radius: 0; }
      .q, .note textarea { font-size: 16px; }
      .hit, .form-actions .btn { min-height: 40px; }
    }
  `],
})
export class LotModelDialogComponent implements AfterViewInit {
  @Input({ required: true }) lot!: any;
  @Output() pinned = new EventEmitter<void>();
  @Output() closed = new EventEmitter<void>();
  @ViewChild('qInput') qInput?: ElementRef<HTMLInputElement>;

  q = '';
  note = '';
  result: LotModelSearch | null = null;
  searching = false;
  picked: Picked | null = null;
  busy = false;
  error = '';
  readonly query$ = new Subject<string>();
  readonly regShort = regShort;
  private destroyRef = inject(DestroyRef);

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {
    this.query$.pipe(
      debounceTime(300),
      map(s => s.trim()),
      distinctUntilChanged(),
      switchMap(s => {
        if (s.length < 2) return of(null);
        this.searching = true;
        this.error = '';
        this.cdr.markForCheck();
        return this.api.searchLotModels(this.lot.id, s).pipe(catchError(e => {
          this.error = 'Поиск не удался: ' + errorText(e);
          return of(null);
        }));
      }),
      takeUntilDestroyed(),
    ).subscribe(r => {
      this.searching = false;
      this.result = r;
      this.cdr.detectChanges();
    });
  }

  ngAfterViewInit(): void { this.qInput?.nativeElement.focus(); }

  isPicked(kind: 'our' | 'reg', key: number | string): boolean { return this.picked?.kind === kind && this.picked.key === key; }
  pickOur(o: OurModelHit) { this.picked = { kind: 'our', key: o.productId, name: o.name }; }
  pickReg(g: RegistryModelHit) { this.picked = { kind: 'reg', key: g.regNumber, name: g.name }; }

  regTerm(g: RegistryModelHit): string {
    if (g.unlimited) return 'бессрочно';
    if (g.expired) return 'истекло ' + isoDate(g.expires);
    return g.expires ? 'до ' + isoDate(g.expires) : 'срок не указан';
  }

  pin() {
    if (!this.picked || this.busy) return;
    this.busy = true;
    this.error = '';
    const note = this.note.trim() || null;
    const body = this.picked.kind === 'our' ? { siteProductId: this.picked.key as number, note } : { regNumber: this.picked.key as string, note };
    this.api.pinLotModel(this.lot.id, body).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.busy = false; this.pinned.emit(); },
      error: e => { this.busy = false; this.error = errorText(e); this.cdr.detectChanges(); },
    });
  }

  /** Закрыть (фон, Esc, «Отмена»), но не во время закрепления. */
  @HostListener('document:keydown.escape')
  dismiss() {
    if (!this.busy) this.closed.emit();
  }
}
