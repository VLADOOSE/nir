import { ChangeDetectorRef, Component, DestroyRef, HostListener } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { CLOSE_OUTCOMES, PrStatus, PrView, SaveState, stageTone, timeText } from '../../shared/private-request';
import { errorText } from '../../shared/error-text';
import { fullDateTime } from '../../shared/relative-time';
import { PrClientSectionComponent } from './pr-client-section.component';
import { PrLinesComponent } from './pr-lines.component';
import { MarketService } from '../../services/market.service';

/**
 * Страница частной заявки (спека 2026-10-12 §5): шапка с этапом и меню, разделы — клиент, позиции, поставщики и цены.
 * Разделы сохраняют поля сами (по выходу из поля) и отдают странице новый вид заявки и состояние сохранения.
 */
@Component({
  selector: 'app-private-request-page',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink, PrClientSectionComponent, PrLinesComponent],
  template: `
    <div class="pr-page" *ngIf="view as v; else stateTpl">
      <header class="pr-top">
        <a routerLink="/private-requests" class="back">← Частные заявки</a>
        <div class="pr-title">
          <h2>{{ v.number }}</h2>
          <span class="pr-stage" [attr.data-tone]="tone(v.stage)">{{ v.stageText }}</span>
          <span class="save-state" [class.err]="saveError" aria-live="polite">{{ saveText }}</span>
          <button type="button" class="retry" *ngIf="saveError && retryFn" (click)="retry()">Повторить</button>
        </div>
        <span class="pr-menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
          <button type="button" class="btn btn-more" (click)="menuOpen = !menuOpen" [attr.aria-expanded]="menuOpen"
                  [disabled]="busy" aria-label="Действия с заявкой">⋯</button>
          <span class="row-menu" *ngIf="menuOpen">
            <ng-container *ngIf="v.status === 'NEW'">
              <button type="button" *ngFor="let o of outcomes" (click)="close(o.v)">Закрыть: {{ o.l }}</button>
            </ng-container>
            <button type="button" *ngIf="v.status !== 'NEW'" (click)="reopen()">Вернуть в работу</button>
            <button type="button" class="danger" (click)="remove()">Удалить заявку</button>
          </span>
        </span>
      </header>
      <div class="from-lead" *ngIf="v.lead as ld">
        Из обращения:
        <a [routerLink]="['/leads']" [queryParams]="{ openId: ld.id }">«{{ ld.subject }}» · {{ leadSource(ld.source) }} · {{ when(ld.receivedAt) }}</a>
      </div>
      <app-pr-client-section [view]="v" [readonly]="!auth.isAdmin()" (viewChange)="onView($event)"
                             (saveState)="onSaveState($event)"></app-pr-client-section>
      <app-pr-lines [view]="v" [readonly]="!auth.isAdmin()" [isKz]="isKz" (viewChange)="onView($event)"
                    (saveState)="onSaveState($event)"></app-pr-lines>
      <!-- sections -->
    </div>
    <ng-template #stateTpl>
      <div class="empty" *ngIf="loading">Загрузка…</div>
      <div class="error-banner" *ngIf="loadError">
        {{ loadError }} · <a routerLink="/private-requests">к списку заявок</a>
      </div>
    </ng-template>
  `,
  styles: [`
    :host { display: block; max-width: 1100px; }
    .pr-page { display: flex; flex-direction: column; gap: 12px; }
    .pr-top { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .back { color: var(--accent); text-decoration: none; font-size: 13px; padding-top: 4px; }
    .pr-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; flex: 1; min-width: 0; }
    .pr-title h2 { margin: 0; font-size: 20px; }
    .save-state { font-size: 12px; color: var(--text-muted); }
    .save-state.err { color: var(--danger-text); }
    .retry { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 12px; text-decoration: underline; }
    .pr-menu { position: relative; }
    .from-lead { font-size: 13px; color: var(--text-muted); }
    .from-lead a { color: var(--accent); text-decoration: none; }
    @media (max-width: 900px) {
      .pr-top { gap: 8px; }
      .back { width: 100%; padding-top: 0; }
      .btn-more { min-width: 40px; }
    }
  `],
})
export class PrivateRequestPageComponent {
  view: PrView | null = null;
  loading = false;
  loadError = '';
  busy = false;
  menuOpen = false;
  saveText = '';
  saveError = false;
  retryFn?: () => void;
  readonly outcomes = CLOSE_OUTCOMES;
  private loadSub?: Subscription;

  constructor(private route: ActivatedRoute, private router: Router, private api: ApiService, public auth: AuthService,
              private notify: NotificationService, private confirm: ConfirmService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef, private market: MarketService) {
    // маршрут переиспользует компонент при переходе на другую заявку — грузим по каждому id
    this.route.paramMap.pipe(takeUntilDestroyed()).subscribe(p => {
      const id = Number(p.get('id'));
      if (!(id > 0)) {
        this.view = null;
        this.loadError = 'Заявка не найдена';
        return;
      }
      this.load(id);
    });
  }

  @HostListener('document:click')
  @HostListener('document:keydown.escape')
  closeMenu() {
    this.menuOpen = false;
  }

  tone(stage: string) {
    return stageTone(stage);
  }

  leadSource(source: string): string {
    return source === 'manual' ? 'внесено вручную' : source;
  }

  when(iso: string): string {
    return fullDateTime(iso);
  }

  load(id: number) {
    this.loadSub?.unsubscribe();
    this.loading = true;
    this.loadError = '';
    this.view = null;
    this.saveText = '';
    this.saveError = false;
    this.loadSub = this.api.getPrivateRequest(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.view = v; this.loading = false; this.afterLoad(); this.cdr.markForCheck(); },
      error: e => {
        this.loading = false;
        this.loadError = e?.status === 404 ? 'Заявка не найдена — возможно, она другого рынка или удалена' : errorText(e);
        this.cdr.markForCheck();
      },
    });
  }

  get isKz() { return this.market.value === 'KZ'; }

  /** Позиции без статуса РУ (заявки до V26, старые пути) — одна проверка при открытии; только KZ и только админ. */
  private afterLoad() {
    const v = this.view;
    if (!v || !this.isKz || !this.auth.isAdmin() || !v.lines.some(l => l.regStatus == null)) return;
    this.api.checkPrivateRequestRegistry(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { if (this.view?.id === nv.id) { this.view = nv; this.cdr.markForCheck(); } },
      error: () => {},   // не вышло — метки остаются «РУ —», повтор при следующем открытии
    });
  }

  onView(v: PrView) {
    this.view = v;
    this.cdr.markForCheck();
  }

  onSaveState(s: SaveState) {
    if (s.kind === 'saving') {
      this.saveText = 'Сохраняю…';
      this.saveError = false;
      this.retryFn = undefined;
    } else if (s.kind === 'saved') {
      this.saveText = 'Сохранено · ' + timeText();
      this.saveError = false;
      this.retryFn = undefined;
    } else {
      this.saveText = 'Не сохранено: ' + (s.message || 'ошибка');
      this.saveError = true;
      this.retryFn = s.retry;
    }
    this.cdr.markForCheck();
  }

  retry() {
    const f = this.retryFn;
    this.retryFn = undefined;
    f?.();
  }

  close(outcome: PrStatus) {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.busy = true;
    this.api.closePrivateRequest(v.id, outcome).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { this.busy = false; this.view = nv; this.notify.success('Заявка закрыта'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  reopen() {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.busy = true;
    this.api.reopenPrivateRequest(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: nv => { this.busy = false; this.view = nv; this.notify.success('Заявка снова в работе'); this.cdr.markForCheck(); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  remove() {
    const v = this.view;
    if (!v) return;
    this.menuOpen = false;
    this.confirm.ask(`Удалить заявку ${v.number}?`,
      'Удалить можно заявку без запросов цен и КП клиенту. Это действие нельзя отменить.',
      { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef))
      .subscribe(ok => {
        if (!ok) return;
        this.api.deletePrivateRequest(v.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: () => { this.notify.success('Заявка удалена'); this.router.navigate(['/private-requests']); },
          error: e => this.notify.error(errorText(e)),
        });
      });
  }
}
