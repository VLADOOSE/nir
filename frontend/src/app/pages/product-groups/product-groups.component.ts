import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import type { ProbeResult, ProductGroup, ProductGroupsState } from '../../shared/product-watch';
import { whenText, wordsList, wordsText } from '../../shared/product-watch-pages';

interface Row {
  g: ProductGroup; words: string; require: string; exclude: string; brands: string;
  probe?: ProbeResult | null; probing?: boolean;
}

/** «Наша продукция» (спека 2026-10-19-product-watch §12.1): группы видов изделий, «Проверить», поиск по стране, каталог. */
@Component({
  selector: 'app-product-groups',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="pg-page">
      <h2>Наша продукция</h2>
      <p class="subtitle">Что АИС ищет в тендерах по всей стране: виды изделий из нашего каталога, их слова поиска, уточнения и исключения.
        Найденное — в «Тендерах» с отметкой «🏷 наш вид» или «★ точно наше» и в теме «Тендеры» в Telegram.</p>
      <p class="pg-note" *ngIf="loading">Загружаю…</p>
      <p class="pg-note" *ngIf="state && !state.kz">Только для Казахстана — переключите рынок на West-Med (KZ).</p>
      <ng-container *ngIf="state?.kz">
        <div class="pg-status" [class.pg-off]="!state!.watch?.enabled">
          <span *ngIf="!state!.watch?.enabled">Поиск по стране выключен (PRODUCT_WATCH_ENABLED).</span>
          <span *ngIf="state!.watch?.enabled">Поиск по стране включён<ng-container *ngIf="state!.watch!.lastSearchAt"> · последний
            {{ when(state!.watch!.lastSearchAt) }}<ng-container *ngIf="state!.watch!.lastSearch?.message"> —
            {{ state!.watch!.lastSearch?.message }}</ng-container></ng-container><ng-container *ngIf="state!.watch!.current"> · идёт: {{ state!.watch!.current }}</ng-container></span>
          <button type="button" class="btn btn-line pg-small" *ngIf="admin && state!.watch?.enabled"
                  [disabled]="!!state!.watch!.current" (click)="runNow()">Искать сейчас</button>
        </div>
        <div class="pg-card" *ngFor="let r of rows; let i = index; let last = last" [class.pg-inactive]="!r.g.active">
          <div class="pg-row">
            <input class="pg-name" [(ngModel)]="r.g.name" maxlength="120" [disabled]="!admin"
                   [attr.aria-label]="'Название группы ' + (i + 1)" placeholder="Название группы" />
            <label class="pg-toggle"><input type="checkbox" [(ngModel)]="r.g.active" [disabled]="!admin" /> включена</label>
            <div class="pg-tools" *ngIf="admin">
              <button type="button" class="btn btn-line pg-small" [disabled]="i === 0" (click)="move(i, -1)" aria-label="Выше">↑</button>
              <button type="button" class="btn btn-line pg-small" [disabled]="last" (click)="move(i, 1)" aria-label="Ниже">↓</button>
              <button type="button" class="btn btn-line pg-small" (click)="probe(r)" [disabled]="r.probing">{{ r.probing ? 'Проверяю…' : 'Проверить' }}</button>
              <button type="button" class="btn btn-line pg-small" (click)="remove(i)">Удалить</button>
            </div>
          </div>
          <p class="pg-hint" *ngIf="r.g.brandGroup">Слова поиска — наши бренды и модели: находки сразу «★ назван наш товар».</p>
          <label class="pg-field">Слова поиска <small>— по одному слову, как пишет площадка, через запятую</small>
            <input [(ngModel)]="r.words" [disabled]="!admin" placeholder="электрокардиограф" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Уточнения <small>— хотя бы одно должно быть в лоте (начало слова)</small>
            <input [(ngModel)]="r.require" [disabled]="!admin" placeholder="операцион, хирург" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Исключения <small>— если есть в лоте, лот не наш</small>
            <input [(ngModel)]="r.exclude" [disabled]="!admin" placeholder="уличн, прожектор" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Наши бренды и модели группы <small>— для «★ назван наш товар»</small>
            <input [(ngModel)]="r.brands" [disabled]="!admin" placeholder="аксион, дки-н" /></label>
          <div class="pg-probe" *ngIf="r.probe">
            <p class="pg-note">Площадка нашла с идущим приёмом: {{ r.probe.found }}, прошло: {{ r.probe.passed }}<ng-container
              *ngIf="r.probe.lots.length < r.probe.found"> (показаны первые {{ r.probe.lots.length }})</ng-container>.</p>
            <div class="pg-plot" *ngFor="let l of r.probe.lots" [class.pg-pass]="l.passed">
              <span class="pg-lot">{{ l.lotName }}</span>
              <span class="pg-meta">{{ l.customer }} · № {{ l.numberAnno }}<ng-container *ngIf="l.deadlineAt"> · до {{ l.deadlineAt.slice(0, 16) }}</ng-container></span>
              <span class="pg-why">{{ l.reason }}</span>
            </div>
          </div>
        </div>
        <div class="pg-actions" *ngIf="admin">
          <button type="button" class="btn btn-line" (click)="add()">+ Группа</button>
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
        <p class="pg-catalog" *ngIf="state!.catalog" [class.pg-stale]="state!.catalog!.stale">
          <span>Каталог сайта: {{ state!.catalog!.count }} товаров<ng-container *ngIf="brandsText()"> ({{ brandsText() }})</ng-container>,
            {{ state!.catalog!.syncedAt ? 'обновлён ' + when(state!.catalog!.syncedAt) : 'ещё не загружен' }}<ng-container
              *ngIf="state!.watch?.lastCatalog"> · {{ state!.watch!.lastCatalog }}</ng-container></span>
          <button type="button" class="btn btn-line pg-small" *ngIf="admin && state!.watch?.enabled" (click)="syncCatalog()">Обновить</button>
        </p>
      </ng-container>
    </div>
  `,
  styles: [`
    .pg-page { max-width: 900px; display: flex; flex-direction: column; gap: 10px; }
    .pg-page h2 { margin: 0; }
    .pg-note, .pg-hint { margin: 0; font-size: 13px; color: var(--text-muted); }
    .pg-status { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--success-text); }
    .pg-status.pg-off { color: var(--warn-text); }
    .pg-small { padding: 4px 10px; font-size: 12px; }
    .pg-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px;
               background: var(--surface); }
    .pg-card.pg-inactive { opacity: 0.6; }
    .pg-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .pg-name { flex: 1; min-width: 180px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
               color: var(--text); font: inherit; font-weight: 600; }
    .pg-toggle { font-size: 13px; color: var(--text-muted); display: flex; gap: 6px; align-items: center; }
    .pg-tools { display: flex; gap: 6px; flex-wrap: wrap; }
    .pg-field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text); }
    .pg-field small { color: var(--text-muted); font-weight: 400; }
    .pg-field input { padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .pg-probe { border-top: 1px dashed var(--border); padding-top: 8px; display: flex; flex-direction: column; gap: 6px; }
    .pg-plot { display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; font-size: 13px; padding: 6px 8px; border-radius: 6px;
               border: 1px solid var(--danger); background: color-mix(in srgb, var(--danger) 8%, var(--surface)); }
    .pg-plot.pg-pass { border-color: var(--success); background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .pg-lot { font-weight: 600; color: var(--text); }
    .pg-meta { grid-column: 1; color: var(--text-muted); }
    .pg-why { grid-column: 2; grid-row: 1 / span 2; align-self: center; color: var(--text); }
    .pg-actions { display: flex; gap: 8px; }
    .pg-catalog { margin: 4px 0 0; font-size: 13px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .pg-catalog.pg-stale { color: var(--warn-text); }
    @media (max-width: 900px) {
      .pg-name, .pg-field input { font-size: 16px; }
      .pg-tools { width: 100%; }
      .pg-plot { grid-template-columns: 1fr; }
      .pg-why { grid-column: 1; grid-row: auto; }
    }
  `],
})
export class ProductGroupsComponent implements OnInit, OnDestroy {
  state: ProductGroupsState | null = null;
  rows: Row[] = [];
  loading = true;
  saving = false;
  private poll: ReturnType<typeof setInterval> | null = null;

  constructor(private api: ApiService, private auth: AuthService, private confirm: ConfirmService,
              private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  readonly when = whenText;

  ngOnInit() {
    this.load();
    this.poll = setInterval(() => this.refreshStatus(), 5000);
  }

  ngOnDestroy() {
    if (this.poll) clearInterval(this.poll);
  }

  private load() {
    this.api.getProductGroups().subscribe({
      next: s => { this.apply(s); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Группы не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  private apply(s: ProductGroupsState) {
    this.state = s;
    this.rows = s.groups.map(g => ({ g: { ...g }, words: wordsText(g.searchWords), require: wordsText(g.requireWords),
      exclude: wordsText(g.excludeWords), brands: wordsText(g.brandWords) }));
  }

  /** Статус поиска и каталога — без перезагрузки правок групп. */
  private refreshStatus() {
    if (!this.state?.kz) return;
    this.api.getProductWatchStatus().subscribe({
      next: w => { if (this.state) { this.state.watch = w; this.cdr.detectChanges(); } },
      error: () => {},
    });
  }

  brandsText(): string {
    const b = this.state?.catalog?.byBrand || {};
    return Object.keys(b).map(k => `${k} ${b[k]}`).join(', ');
  }

  private toGroup(r: Row): ProductGroup {
    return { ...r.g, searchWords: wordsList(r.words), requireWords: wordsList(r.require), excludeWords: wordsList(r.exclude),
      brandWords: wordsList(r.brands) };
  }

  move(i: number, d: number) {
    const [x] = this.rows.splice(i, 1);
    this.rows.splice(i + d, 0, x);
  }

  add() {
    this.rows.push({ g: { id: null, name: '', searchWords: [], requireWords: [], excludeWords: [], brandWords: [], active: true, brandGroup: false },
      words: '', require: '', exclude: '', brands: '' });
  }

  remove(i: number) {
    this.confirm.ask(`Удалить группу «${this.rows[i].g.name || 'без названия'}»?`, 'Отметки этой группы на тендерах пропадут после сохранения.',
      { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => { if (ok) { this.rows.splice(i, 1); this.cdr.detectChanges(); } });
  }

  probe(r: Row) {
    r.probing = true;
    r.probe = null;
    this.api.probeProductGroup(this.toGroup(r)).subscribe({
      next: p => { r.probe = p; r.probing = false; this.cdr.detectChanges(); },
      error: e => { r.probing = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  save() {
    this.saving = true;
    this.api.saveProductGroups(this.rows.map(r => this.toGroup(r))).subscribe({
      next: s => {
        this.apply(s);
        this.saving = false;
        this.notify.success('Сохранено. Отметки на открытых тендерах пересчитываются.');
        this.cdr.detectChanges();
      },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  runNow() {
    this.api.runProductWatch().subscribe({
      next: w => { if (this.state) this.state.watch = w; this.notify.success('Поиск по стране запущен'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  syncCatalog() {
    this.api.syncSiteCatalog().subscribe({
      next: w => { if (this.state) this.state.watch = w; this.notify.success('Каталог сайта обновляется'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }
}
