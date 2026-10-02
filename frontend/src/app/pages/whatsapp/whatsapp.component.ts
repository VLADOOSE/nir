import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';
import { Observable, Subscription, timeout } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { WhatsappStatusLineComponent } from '../../shared/whatsapp-status-line.component';
import { formatPhone, WhatsappStatus } from '../../shared/whatsapp-status';
import { fullDateTime, relativeTime } from '../../shared/relative-time';

type Tone = 'ok' | 'wait' | 'bad';

/** Статус сессии по-русски (спека whatsapp-waha §7); у Green-API — его состояния. */
const STATUS_LABEL: Record<string, { text: string; tone: Tone }> = {
  WORKING: { text: 'подключён', tone: 'ok' },
  SCAN_QR_CODE: { text: 'ждёт привязки', tone: 'wait' },
  STARTING: { text: 'подключается', tone: 'wait' },
  PASSKEY_REQUIRED: { text: 'нужно подтверждение на телефоне', tone: 'wait' },
  PASSKEY_CONFIRMATION_REQUIRED: { text: 'нужно подтверждение на телефоне', tone: 'wait' },
  FAILED: { text: 'сессия упала', tone: 'bad' },
  STOPPED: { text: 'остановлена', tone: 'bad' },
  authorized: { text: 'подключён', tone: 'ok' },
  notAuthorized: { text: 'номер не подключён', tone: 'bad' },
  blocked: { text: 'номер заблокирован', tone: 'bad' },
  sleepMode: { text: 'телефон выключен', tone: 'wait' },
  starting: { text: 'запускается', tone: 'wait' },
  suspended: { text: 'ограничения WhatsApp', tone: 'bad' },
};

/**
 * «Система → WhatsApp» (спека whatsapp-waha §7): шлюз, состояние, номер; у WAHA — QR для привязки (код живёт 20–60 с,
 * страница обновляет его сама), «Перезапустить» и «Отвязать номер» с подтверждением в самой странице.
 * Только администратор: QR — это доступ к рабочему WhatsApp.
 */
/** Дольше бэкенд не отвечает (дедлайн WAHA — 20 с): запрос считается повисшим, опрос идёт дальше. */
const POLL_TIMEOUT_MS = 30_000;

@Component({
  selector: 'app-whatsapp',
  standalone: true,
  imports: [NgIf, WhatsappStatusLineComponent],
  template: `
    <div class="page-head">
      <div>
        <h2>WhatsApp</h2>
        <p class="subtitle">Рабочий номер, переписка которого приходит в «Чаты» и «Обращения».</p>
      </div>
    </div>

    <div class="error-banner" *ngIf="error">{{ error }}</div>

    <section class="card" *ngIf="info">
      <dl class="facts">
        <div><dt>Шлюз</dt><dd>{{ providerLabel(info.provider) }}</dd></div>
        <div><dt>Состояние</dt><dd><span class="badge" [attr.data-tone]="label(info.status).tone">{{ label(info.status).text }}</span></dd></div>
        <div *ngIf="info.number"><dt>Номер</dt><dd>{{ phone(info.number) }}<span class="muted" *ngIf="info.name"> · {{ info.name }}</span></dd></div>
        <div *ngIf="status?.lastMessageAt as at"><dt>Последнее сообщение</dt><dd [title]="full(at)">{{ ago(at) }}</dd></div>
      </dl>
      <app-whatsapp-status-line [status]="lineStatus"></app-whatsapp-status-line>
      <p class="muted" *ngIf="!info.enabled">Приём WhatsApp выключен (WHATSAPP_ENABLED=false в настройках сервера).</p>
      <p class="muted" *ngIf="info.enabled && info.provider === 'greenapi'">Привязка номера и состояние инстанса — в кабинете Green-API.</p>
      <p class="field-error" *ngIf="info.error">Шлюз не ответил: {{ info.error }}</p>
    </section>

    <section class="card" *ngIf="info?.qrAvailable">
      <h3>Привязать номер</h3>
      <div class="qr-body">
        <div class="qr">
          <img *ngIf="qrUrl" [src]="qrUrl" alt="QR-код для привязки WhatsApp" width="240" height="240" />
          <span *ngIf="!qrUrl" class="muted">QR-код загружается…</span>
        </div>
        <ol class="steps">
          <li>Откройте WhatsApp на <b>рабочем</b> телефоне.</li>
          <li>Настройки → Связанные устройства → Привязка устройства.</li>
          <li>Наведите камеру на код. Код меняется каждые 20 секунд — страница обновляет его сама.</li>
        </ol>
      </div>
    </section>

    <section class="card" *ngIf="canRestart || canLogout">
      <h3>Управление</h3>
      <div class="actions" *ngIf="!confirmLogout">
        <button type="button" class="btn btn-primary" *ngIf="canRestart" [disabled]="busy" (click)="restart()">Перезапустить</button>
        <button type="button" class="btn btn-line" *ngIf="canLogout" [disabled]="busy" (click)="confirmLogout = true">Отвязать номер</button>
      </div>
      <div class="confirm" *ngIf="confirmLogout">
        <span>Сообщения перестанут приходить в АИС, пока номер не привяжут заново. Отвязать?</span>
        <button type="button" class="btn btn-danger" [disabled]="busy" (click)="logout()">Отвязать</button>
        <button type="button" class="btn btn-cancel" (click)="confirmLogout = false">Отмена</button>
      </div>
    </section>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .card { margin-top: 16px; padding: 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .card h3 { margin: 0 0 12px; font-size: 16px; }
    .facts { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 12px 24px; margin: 0 0 12px; }
    .facts dt { font-size: 12px; color: var(--text-muted); margin-bottom: 2px; }
    .facts dd { margin: 0; font-size: 15px; color: var(--text); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .badge[data-tone="ok"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .badge[data-tone="wait"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .badge[data-tone="bad"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .qr-body { display: flex; gap: 24px; align-items: flex-start; flex-wrap: wrap; }
    /* своя белая подложка у PNG самого кода: светлый фон нужен камере в любой теме */
    .qr { width: 240px; height: 240px; display: flex; align-items: center; justify-content: center;
          border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .qr img { width: 240px; height: 240px; image-rendering: pixelated; }
    .steps { margin: 0; padding-left: 20px; line-height: 1.7; color: var(--text); max-width: 420px; }
    .actions, .confirm { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .confirm span { font-size: 13px; color: var(--danger-text); }
    @media (max-width: 900px) {
      .facts { grid-template-columns: 1fr; }
      .qr-body { flex-direction: column; align-items: stretch; }
      .qr { align-self: center; }
      .actions .btn, .confirm .btn { flex: 1 1 auto; }
    }
  `],
})
export class WhatsappComponent implements OnInit, OnDestroy {
  info: any = null;
  status: WhatsappStatus | null = null;
  /** Строка под статусом: состояние и номер — из того же живого ответа WAHA, что и бейдж (опрос цикла догоняет за секунды). */
  lineStatus: WhatsappStatus | null = null;
  qrUrl: string | null = null;
  error = '';
  busy = false;
  confirmLogout = false;
  private timer: any = null;
  private qrSub?: Subscription;
  private sessionSub?: Subscription;
  private statusSub?: Subscription;
  private destroyed = false;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.load();
    this.timer = setInterval(() => this.load(true), 5000);   // код живёт 20 с; после привязки состояние сменится сразу
  }

  /** Запросы отменяются вместе с видом: иначе поздний ответ создал бы blob-адрес QR, который уже некому отозвать. */
  ngOnDestroy() {
    this.destroyed = true;
    clearInterval(this.timer);
    this.sessionSub?.unsubscribe();
    this.statusSub?.unsubscribe();
    this.qrSub?.unsubscribe();
    this.dropQr();
  }

  get canManage(): boolean { return !!this.info && this.info.provider === 'waha' && this.info.enabled && this.info.configured; }
  get canRestart(): boolean { return this.canManage && ['FAILED', 'STOPPED'].includes(this.info.status); }
  get canLogout(): boolean { return this.canManage && !!this.info.status && this.info.status !== 'SCAN_QR_CODE'; }

  /**
   * quiet — ошибку не показывать (фоновый опрос). Фоновый опрос не перекрывает незавершённый — ответы не применятся не
   * по порядку (WAHA может отвечать до 20 с, а опрос — раз в 5 с); явный (после действия) заменяет его свежим.
   * Таймаут 30 с — страховка от повисшего запроса (сон устройства, потерянный сокет): иначе опрос встал бы до его конца.
   */
  load(quiet = false, replace = !quiet) {
    if (this.destroyed) return;
    if (replace || !this.sessionSub || this.sessionSub.closed) {
      this.sessionSub?.unsubscribe();
      this.sessionSub = this.loadSession(quiet);
    }
    if (replace || !this.statusSub || this.statusSub.closed) {
      this.statusSub?.unsubscribe();
      this.statusSub = this.api.getWhatsappStatus().pipe(timeout(POLL_TIMEOUT_MS)).subscribe({
        next: s => { this.status = s; this.syncLine(); this.cdr.detectChanges(); },
        error: () => {},
      });
    }
  }

  private loadSession(quiet: boolean): Subscription {
    return this.api.getWhatsappSession().pipe(timeout(POLL_TIMEOUT_MS)).subscribe({
      next: s => {
        this.info = s;
        this.error = '';
        this.syncLine();
        if (s.qrAvailable) {
          this.loadQr();
        } else {
          this.qrSub?.unsubscribe();
          this.dropQr();
        }
        this.cdr.detectChanges();
      },
      error: e => {
        if (!quiet) {
          this.error = e.error?.message || 'Не удалось получить состояние WhatsApp';
          this.cdr.detectChanges();
        }
      },
    });
  }

  /** Бейдж и строка не должны спорить: у WAHA состояние берём живое, у Green-API и при ошибке — как есть. */
  private syncLine() {
    const s = this.status;
    const i = this.info;
    this.lineStatus = s && i?.provider === 'waha' && i.enabled && !i.error
      ? { ...s, state: i.status, number: i.number ?? s.number }
      : s;
  }

  private loadQr() {
    if (this.destroyed) return;
    this.qrSub?.unsubscribe();
    this.qrSub = this.api.getWhatsappQr().subscribe({
      next: blob => { this.dropQr(); this.qrUrl = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => {},   // код сменился или номер уже привязан — следующий опрос покажет новое состояние
    });
  }

  private dropQr() {
    if (this.qrUrl) {
      URL.revokeObjectURL(this.qrUrl);
      this.qrUrl = null;
    }
  }

  restart() { this.act(this.api.restartWhatsappSession(), 'Сессия перезапускается — состояние обновится через несколько секунд'); }

  logout() {
    this.confirmLogout = false;
    this.act(this.api.logoutWhatsappSession(), 'Номер отвязан — привяжите заново по QR-коду');
  }

  private act(obs: Observable<any>, ok: string) {
    this.busy = true;
    obs.subscribe({
      next: s => { this.busy = false; this.info = s; this.syncLine(); this.notify.success(ok); this.load(true, true); },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось выполнить действие'); this.load(true, true); },
    });
  }

  label(status: string | null): { text: string; tone: Tone } {
    if (!status) return { text: this.info?.enabled ? 'неизвестно' : 'приём выключен', tone: 'wait' };
    return STATUS_LABEL[status] || { text: status, tone: 'bad' };
  }

  providerLabel(p: string | null): string {
    if (p === 'waha') return 'WAHA (свой шлюз на сервере)';
    if (p === 'greenapi') return 'Green-API (запасной)';
    return p || '—';
  }

  phone(n: string) { return formatPhone(n); }
  ago(iso: string) { return relativeTime(iso); }
  full(iso: string) { return fullDateTime(iso); }
}
