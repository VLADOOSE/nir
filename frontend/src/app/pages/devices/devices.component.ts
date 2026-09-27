import { Component, ChangeDetectorRef, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';

/**
 * «Устройства» — калитка ais.westmed.kz: запросы доступа, допущенные устройства, история.
 * Спека: docs/superpowers/specs/2026-09-28-device-gate-design.md §11.
 */
@Component({
  selector: 'app-devices',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="page-head">
      <div>
        <h2>Устройства</h2>
        <p class="subtitle">Кто может открыть ais.westmed.kz. Допускайте только код, который вам назвал знакомый человек.</p>
      </div>
    </div>

    <div class="error-banner" *ngIf="error">{{ error }}</div>

    <section class="block">
      <h3>Запросы доступа <span class="counter" *ngIf="data?.pending?.length">{{ data.pending.length }}</span></h3>
      <p class="empty" *ngIf="data && !data.pending.length">
        Новых запросов нет. Когда кто-то запросит доступ на ais.westmed.kz, запрос появится здесь через несколько секунд.
      </p>
      <div class="req" *ngFor="let d of data?.pending; trackBy: byId">
        <div class="req-code" [attr.aria-label]="'Код ' + d.code">{{ d.code }}</div>
        <div class="req-info">
          <div class="req-name">{{ d.requesterName }}</div>
          <div class="muted">{{ d.device }} · {{ d.ip || 'IP неизвестен' }} · <span [title]="full(d.requestedAt)">{{ ago(d.requestedAt) }}</span></div>
        </div>
        <div class="req-act">
          <input [(ngModel)]="labels[d.id]" [placeholder]="d.requesterName" maxlength="100" aria-label="Подпись устройства">
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="approve(d)">Допустить</button>
          <button type="button" class="btn btn-cancel" [disabled]="busy" (click)="reject(d)">Отклонить</button>
        </div>
      </div>
    </section>

    <section class="block">
      <h3>Допущенные</h3>
      <p class="empty" *ngIf="data && !data.trusted.length">Допущенных устройств пока нет.</p>
      <div class="dev" *ngFor="let d of data?.trusted; trackBy: byId" [class.is-current]="d.current">
        <div class="dev-main">
          <div class="dev-name">{{ d.label || d.requesterName }} <span class="badge badge-current" *ngIf="d.current">это устройство</span></div>
          <div class="muted">{{ d.device }} · допустил {{ d.decidedBy || '—' }} <span [title]="full(d.decidedAt)">{{ ago(d.decidedAt) }}</span></div>
          <div class="muted">Последний визит: {{ d.lastSeenAt ? ago(d.lastSeenAt) : 'ещё не заходило' }}</div>
        </div>
        <div class="dev-act">
          <button type="button" class="btn btn-line" *ngIf="confirmId !== d.id" [disabled]="busy" (click)="confirmId = d.id">Отозвать</button>
          <div class="confirm" *ngIf="confirmId === d.id">
            <span>{{ d.current ? 'Вы потеряете доступ с этого браузера. Вернуться — через Tailscale или SSH-туннель.' : 'Устройство сразу потеряет доступ.' }}</span>
            <button type="button" class="btn btn-danger" [disabled]="busy" (click)="revoke(d)">Отозвать</button>
            <button type="button" class="btn btn-cancel" (click)="confirmId = null">Отмена</button>
          </div>
        </div>
      </div>
    </section>

    <section class="block" *ngIf="data?.history?.length">
      <button type="button" class="toggle" (click)="showHistory = !showHistory" [attr.aria-expanded]="showHistory">
        История ({{ data.history.length }}) {{ showHistory ? '▴' : '▾' }}
      </button>
      <div class="hist" *ngIf="showHistory">
        <div class="hist-row" *ngFor="let d of data.history; trackBy: byId">
          <span class="badge" [attr.data-status]="d.status">{{ statusLabel(d.status) }}</span>
          <span>{{ d.label || d.requesterName }}</span>
          <span class="muted">{{ d.device }} · {{ ago(d.decidedAt || d.requestedAt) }}{{ d.decidedBy ? ' · ' + d.decidedBy : '' }}</span>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .block { margin-top: 20px; }
    .block h3 { margin: 0 0 10px; font-size: 16px; display: flex; align-items: center; gap: 8px; }
    .muted { color: var(--text-muted); font-size: 13px; }
    .req { display: grid; grid-template-columns: auto 1fr auto; gap: 14px; align-items: center; margin-bottom: 8px; padding: 12px 14px;
           border: 1px solid var(--border); border-left: 3px solid var(--accent); border-radius: 10px;
           background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .req-code { font: 700 22px/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: 2px; color: var(--text); }
    .req-name { font-weight: 600; }
    .req-act { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .req-act input { width: 180px; padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .dev { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; padding: 12px 14px;
           border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .dev.is-current { border-color: var(--accent); }
    .dev-name { font-weight: 600; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .badge-current { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .confirm { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--danger-text); }
    .toggle { background: none; border: none; color: var(--accent); cursor: pointer; font: inherit; padding: 4px 0; }
    .hist { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
    .hist-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 14px; }
    .badge[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .badge[data-status="REVOKED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .badge[data-status="EXPIRED"] { background: color-mix(in srgb, var(--text-muted) 15%, transparent); color: var(--text-muted); }
    @media (max-width: 900px) {
      .req { grid-template-columns: 1fr; gap: 8px; }
      .req-act, .dev-act { width: 100%; }
      .req-act input { width: 100%; font-size: 16px; }
    }
  `]
})
export class DevicesComponent implements OnInit, OnDestroy {
  data: any = null;
  labels: Record<number, string> = {};
  confirmId: number | null = null;
  showHistory = false;
  busy = false;
  error = '';
  private timer: any = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.load();
    this.timer = setInterval(() => this.load(true), 5000);      // запрос появляется, пока сотрудник ждёт у калитки
  }

  ngOnDestroy() {
    clearInterval(this.timer);
  }

  load(quiet = false) {
    this.api.getDevices().subscribe({
      next: d => { this.data = d; this.error = ''; this.cdr.detectChanges(); },
      error: e => {
        if (!quiet) { this.error = e.error?.message || 'Не удалось загрузить устройства'; this.cdr.detectChanges(); }
      },
    });
  }

  approve(d: any) {
    const label = (this.labels[d.id] || '').trim();
    this.act(this.api.approveDevice(d.id, label || null), `Устройство «${label || d.requesterName}» допущено`);
  }

  reject(d: any) {
    this.act(this.api.rejectDevice(d.id), 'Запрос отклонён');
  }

  revoke(d: any) {
    this.confirmId = null;
    this.act(this.api.revokeDevice(d.id), 'Доступ отозван');
  }

  private act(obs: Observable<any>, ok: string) {
    this.busy = true;
    obs.subscribe({
      next: () => {
        this.busy = false;
        this.notify.success(ok);
        this.load();
        window.dispatchEvent(new Event('ais-counts-changed'));   // счётчик «Устройства» в меню — сразу
      },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось выполнить действие'); this.load(); },
    });
  }

  byId = (_: number, d: any) => d.id;
  ago(iso: string | null) { return relativeTime(iso); }
  full(iso: string | null) { return fullDateTime(iso); }
  statusLabel(s: string): string {
    return ({ REJECTED: 'Отклонён', REVOKED: 'Отозван', EXPIRED: 'Истёк' } as Record<string, string>)[s] || s;
  }
}
