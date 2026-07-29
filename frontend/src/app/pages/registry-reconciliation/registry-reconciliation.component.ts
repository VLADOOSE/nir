import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf, DecimalPipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-registry-reconciliation',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, DecimalPipe],
  template: `
    <div class="page">
      <header class="page-head">
        <div>
          <h1>Сверка с реестром РК</h1>
          <p class="sub">Привязка позиций каталога к № РУ (НЦЭЛС). Зелёный = зарегистрировано (НДС-льгота).</p>
        </div>
        <button class="btn-refresh" (click)="onRefresh()" [disabled]="refreshing">
          ↻ {{ refreshing ? 'Обновляю…' : 'Обновить реестр' }}
        </button>
      </header>

      <div class="filters">
        <label>Статус:</label>
        <select [(ngModel)]="statusFilter" (change)="load()">
          <option value="">Все</option>
          <option value="UNCHECKED">Не проверено</option>
          <option value="REGISTERED">Зарегистрировано</option>
          <option value="NOT_REGISTERED">Не зарегистрировано</option>
          <option value="NOT_MEDICAL">Не медизделие</option>
        </select>
        <span class="count" *ngIf="!loading">{{ rows.length }} позиций</span>
      </div>

      <div class="loading" *ngIf="loading">Загрузка…</div>

      <table class="responsive-cards" *ngIf="!loading && rows.length">
        <thead>
          <tr><th></th><th>Позиция каталога</th><th>Производитель</th><th>Статус</th><th>Топ-кандидат</th></tr>
        </thead>
        <tbody>
          <ng-container *ngFor="let r of rows">
            <tr class="row" [class.focused]="r.equipmentId === focusId" (click)="toggle(r)">
              <td class="chev">{{ expanded[r.equipmentId] ? '▾' : '▸' }}</td>
              <td class="name" data-label="Позиция каталога">{{ r.equipmentName }}</td>
              <td data-label="Производитель">{{ r.manufact }}</td>
              <td data-label="Статус">
                <span class="badge" [class]="'b-' + r.status">{{ statusLabel(r.status) }}</span>
                <span class="vat" *ngIf="r.status === 'REGISTERED'">НДС-льгота</span>
                <span class="vat vat-no" *ngIf="r.status === 'NOT_REGISTERED' || r.status === 'NOT_MEDICAL'">НДС 12%</span>
              </td>
              <td class="top" data-label="Топ-кандидат">
                <span *ngIf="r.candidates?.length">{{ r.candidates[0].producer }} · {{ r.candidates[0].score | number:'1.2-2' }}</span>
                <span class="muted" *ngIf="!r.candidates?.length">нет кандидатов</span>
              </td>
            </tr>
            <tr class="detail" *ngIf="expanded[r.equipmentId]">
              <td colspan="5">
                <div class="cands" *ngIf="r.candidates?.length">
                  <div class="cand" *ngFor="let c of r.candidates" [class.current]="c.regNumber === r.currentRegNumber">
                    <div class="cand-main">
                      <div class="cand-name">{{ c.name }}</div>
                      <div class="cand-meta">{{ c.producer }} · {{ c.country }} · {{ c.regNumber }}
                        <span *ngIf="c.unlimited">· бессрочно</span>
                        <span *ngIf="!c.unlimited && c.expirationDate">· до {{ c.expirationDate }}</span>
                      </div>
                      <div class="bar"><div class="bar-fill" [style.width.%]="(c.score || 0) * 100"></div></div>
                    </div>
                    <button class="btn-confirm" (click)="confirm(r, c.regNumber); $event.stopPropagation()">✓ Подтвердить</button>
                  </div>
                </div>
                <div class="actions">
                  <button class="btn-sm" (click)="mark(r, 'NOT_REGISTERED'); $event.stopPropagation()">Нет в реестре</button>
                  <button class="btn-sm" (click)="mark(r, 'NOT_MEDICAL'); $event.stopPropagation()">Не медизделие</button>
                  <button class="btn-sm" (click)="mark(r, 'RESET'); $event.stopPropagation()">Сбросить</button>
                </div>
              </td>
            </tr>
          </ng-container>
        </tbody>
      </table>

      <div class="empty" *ngIf="!loading && !rows.length">Нет позиций для выбранного фильтра.</div>
    </div>
  `,
  styles: [`
    .page { padding: 24px; max-width: 1100px; }
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; margin-bottom: 16px; }
    h1 { font-size: 22px; color: var(--text); }
    .sub { color: var(--text-muted); font-size: 13px; margin-top: 4px; }
    .btn-refresh { display: inline-flex; align-items: center; gap: 6px; background: var(--accent); color: var(--accent-contrast); border: none; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .btn-refresh:disabled { opacity: .6; cursor: default; }
    .filters { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; font-size: 13px; color: var(--text); }
    /* Полю фон и цвет проставлены ЯВНО: правила input,select,textarea в kit
       намеренно нет, без этого в тёмной теме селект остаётся белым пятном
       (те же прецеденты — tenders-filters, applies, private-requests). */
    .filters select { padding: 6px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .count { color: var(--text-muted); }
    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    /* Правило оставлено: kit даёт только border-COLOR, саму рамку и отступы шапки
       несёт оно. Фон шапки приходит из kit (голый th), здесь он не задан. */
    thead th { text-align: left; padding: 8px 10px; color: var(--text-muted); border-bottom: 1px solid var(--border); font-weight: 600; }
    /* #f3f4f6 стоял в слоте border — это разделитель строк, а не поверхность,
       поэтому --border, а не --surface-2 (иначе линия схлопнулась бы с фоном). */
    .row { cursor: pointer; border-bottom: 1px solid var(--border); }
    .row:hover { background: var(--surface-2); }
    /* Подсветка выбранной строки — формула подсветки ОБЛАСТИ (непрозрачный микс
       акцента с --surface), а не --surface-2: иначе «выбрана» стала бы неотличима
       от ховера соседней строки. Правило идёт после :hover — оно и должно
       побеждать при наведении на выбранную строку. */
    .row.focused { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .row td { padding: 9px 10px; vertical-align: middle; }
    .chev { width: 24px; color: var(--text-muted); }
    .name { font-weight: 500; color: var(--text); }
    .b-UNCHECKED { background: var(--surface-2); color: var(--text); }
    .b-REGISTERED { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .b-NOT_REGISTERED { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .b-NOT_MEDICAL { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .vat { margin-left: 8px; font-size: 11px; color: var(--success-text); font-weight: 600; }
    .vat-no { color: var(--warn-text); }
    .top .muted { color: var(--text-muted); }
    .detail td { background: var(--surface-2); padding: 12px 16px; }
    .cands { display: flex; flex-direction: column; gap: 8px; margin-bottom: 10px; }
    .cand { display: flex; align-items: center; justify-content: space-between; gap: 12px; background: var(--surface); border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; }
    .cand.current { border-color: var(--success); box-shadow: 0 0 0 1px var(--success); }
    .cand-main { flex: 1; }
    .cand-name { font-weight: 500; color: var(--text); }
    .cand-meta { color: var(--text-muted); font-size: 12px; margin: 2px 0 5px; }
    .bar { height: 5px; background: var(--border); border-radius: 3px; overflow: hidden; max-width: 280px; }
    .bar-fill { height: 100%; background: var(--accent); }
    .btn-confirm { background: var(--success); color: var(--accent-contrast); border: none; padding: 6px 12px; border-radius: 6px; cursor: pointer; font-size: 12px; white-space: nowrap; }
    .actions { display: flex; gap: 8px; }
    .btn-sm { background: var(--surface); border: 1px solid var(--border); color: var(--text); padding: 5px 11px; border-radius: 6px; cursor: pointer; font-size: 12px; }
    .btn-sm:hover { background: var(--surface-2); }
    /* .empty ушёл из этого селектора — его даёт kit. .loading в kit нет: остаётся. */
    .loading { padding: 30px; text-align: center; color: var(--text-muted); }
  `]
})
export class RegistryReconciliationComponent {
  rows: any[] = [];
  loading = false;
  refreshing = false;
  statusFilter = 'UNCHECKED';
  expanded: Record<number, boolean> = {};
  focusId: number | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef,
              private route: ActivatedRoute, private notify: NotificationService) {
    this.route.queryParams.subscribe(p => {
      if (p['focus']) {
        this.focusId = +p['focus'];
        this.statusFilter = '';
        this.expanded[this.focusId] = true;
      }
      this.load();
    });
  }

  load() {
    this.loading = true;
    this.api.getRegistryReconciliation(this.statusFilter || undefined, 5).subscribe({
      next: data => { this.rows = data; this.loading = false; this.cdr.detectChanges(); },
      error: err => { this.loading = false; this.notify.error('Ошибка загрузки сверки: ' + (err.error?.message || err.message)); this.cdr.detectChanges(); }
    });
  }

  toggle(r: any) { this.expanded[r.equipmentId] = !this.expanded[r.equipmentId]; }

  statusLabel(s: string): string {
    return { UNCHECKED: 'Не проверено', REGISTERED: 'Зарегистрировано', NOT_REGISTERED: 'Не зарегистрировано', NOT_MEDICAL: 'Не медизделие' }[s] || s;
  }

  confirm(r: any, regNumber: string) {
    this.api.setEquipmentRegistration(r.equipmentId, 'CONFIRM', regNumber).subscribe({
      next: () => { this.notify.success('Привязка сохранена: ' + regNumber); this.load(); },
      error: err => this.notify.error(err.error?.message || 'Ошибка привязки')
    });
  }

  mark(r: any, action: string) {
    this.api.setEquipmentRegistration(r.equipmentId, action).subscribe({
      next: () => { this.notify.success('Статус обновлён'); this.load(); },
      error: err => this.notify.error(err.error?.message || 'Ошибка')
    });
  }

  onRefresh() {
    this.refreshing = true;
    this.api.refreshRegistry().subscribe({
      next: (res: any) => { this.refreshing = false; this.notify.success('Реестр обновлён: ' + res.imported + ' записей'); this.load(); this.cdr.detectChanges(); },
      error: err => { this.refreshing = false; this.notify.error(err.error?.message || 'Ошибка обновления'); this.cdr.detectChanges(); }
    });
  }
}
