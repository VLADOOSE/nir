import { Component, ChangeDetectorRef, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService, AuthUser } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { PasskeyService } from '../../services/passkey.service';
import { fullDateTime, relativeTime } from '../../shared/relative-time';

/**
 * «Мой профиль» — учётная запись и вход по Face ID / Touch ID (passkeys): свои ключи, добавление, удаление.
 * Доступен всем вошедшим. Спека: docs/superpowers/specs/2026-09-28-passkeys-login-design.md §7.3.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <h2>Мой профиль</h2>

    <section class="block card" *ngIf="user">
      <div class="kv"><span class="k">ФИО</span><span>{{ user.fullName || '—' }}</span></div>
      <div class="kv"><span class="k">Логин</span><span>{{ user.username }}</span></div>
      <div class="kv"><span class="k">Роль</span><span>{{ user.role === 'ROLE_ADMIN' ? 'Администратор' : 'Оператор' }}</span></div>
    </section>

    <section class="block">
      <h3>Вход по Face ID / Touch ID</h3>
      <p class="subtitle">Ключ хранится на устройстве, Face ID и Touch ID его не покидают. Пароль продолжает работать.</p>

      <div class="error-banner" *ngIf="error">{{ error }}</div>

      <p class="empty" *ngIf="keys && !keys.length">Ключей пока нет. Добавьте — и входите без пароля.</p>
      <div class="key" *ngFor="let k of keys; trackBy: byId">
        <div class="key-main">
          <div class="key-name">{{ k.label }}</div>
          <div class="muted">
            добавлен <span [title]="full(k.createdAt)">{{ day(k.createdAt) }}</span> ·
            <ng-container *ngIf="k.lastUsedAt; else unused">последний вход: <span [title]="full(k.lastUsedAt)">{{ ago(k.lastUsedAt) }}</span></ng-container>
            <ng-template #unused>ещё не использовался</ng-template>
          </div>
        </div>
        <button type="button" class="btn btn-line" [disabled]="busy" (click)="remove(k)">Удалить</button>
      </div>

      <div class="add" *ngIf="usable">
        <input [(ngModel)]="label" maxlength="60" aria-label="Подпись ключа" placeholder="Подпись, например «iPhone · Safari»">
        <button type="button" class="btn btn-primary" [disabled]="busy || !label.trim()" (click)="add()">
          {{ busy ? 'Ждём Face ID / Touch ID…' : 'Добавить вход по Face ID / Touch ID' }}
        </button>
      </div>
      <p class="muted hint" *ngIf="!usable && !supported">Этот браузер не поддерживает вход по ключу.</p>
      <p class="muted hint" *ngIf="!usable && supported && rpId">Добавить ключ можно на https://{{ rpId }}.</p>
    </section>
  `,
  styles: [`
    h2 { margin: 0; }
    .block { margin-top: 20px; max-width: 720px; }
    .block h3 { margin: 0 0 4px; font-size: 16px; }
    .card { padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .kv { display: flex; gap: 12px; padding: 4px 0; font-size: 14px; }
    .kv .k { flex: none; width: 70px; color: var(--text-muted); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .key { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
           margin-top: 8px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .key-name { font-weight: 600; }
    .add { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 14px; }
    .add input { flex: 1 1 220px; max-width: 320px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px;
                 background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .hint { margin-top: 14px; }
    @media (max-width: 900px) {
      .add { flex-direction: column; align-items: stretch; }
      .add input { max-width: none; font-size: 16px; }
      .key .btn { width: 100%; }
    }
  `]
})
export class ProfileComponent implements OnInit {
  user: AuthUser | null = null;
  keys: any[] | null = null;
  label = '';
  rpId: string | null = null;
  readonly supported = typeof window.PublicKeyCredential === 'function';
  usable = false;
  busy = false;
  error = '';

  constructor(private auth: AuthService, private api: ApiService, private passkeys: PasskeyService,
              private confirm: ConfirmService, private notify: NotificationService,
              private router: Router, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.user = this.auth.getUser();
    this.api.getPasskeyConfig().subscribe({
      next: c => { this.rpId = c.rpId; this.usable = this.passkeys.usableHere(c.rpId); this.cdr.detectChanges(); },
    });
    this.load(true);
  }

  load(resetLabel = false) {
    this.api.getPasskeys().subscribe({
      next: r => {
        this.keys = r.keys;
        if (resetLabel || !this.label.trim()) this.label = r.suggestedLabel;
        this.error = '';
        this.cdr.detectChanges();
      },
      error: e => { this.error = e.error?.message || 'Не удалось загрузить ключи'; this.cdr.detectChanges(); },
    });
  }

  add() {
    const label = this.label.trim();
    if (!label || this.busy) return;
    this.busy = true;
    this.cdr.detectChanges();
    // .then, а не await: после нативного await код шёл бы мимо зоны Angular и тосты не появлялись (см. PasskeyService)
    this.passkeys.register(label).then(outcome => {
      this.busy = false;
      switch (outcome) {
        case 'ok':
          this.notify.success('Ключ добавлен. В следующий раз нажмите «Войти с Face ID / Touch ID»');
          this.load(true);
          break;
        case 'exists':
          this.notify.info('На этом устройстве ключ для вашей учётки уже есть');
          break;
        case 'session':
          this.notify.error('Сессия истекла — войдите снова');
          this.auth.logout().subscribe(() => this.router.navigate(['/login']));
          break;
        case 'cancelled':
          break;                                           // закрыли системное окно — молча
        default:
          this.notify.error('Не удалось добавить ключ. Попробуйте ещё раз');
      }
      this.cdr.detectChanges();
    });
  }

  remove(k: any) {
    this.confirm.ask(`Удалить ключ «${k.label}»?`,
        'Войти с ним больше не получится. Сам ключ останется в связке ключей устройства — его можно удалить в настройках паролей.',
        { danger: true, confirmLabel: 'Удалить' })
      .subscribe(yes => {
        if (!yes) return;
        this.busy = true;
        this.cdr.detectChanges();
        this.api.deletePasskey(k.id).subscribe({
          next: () => { this.busy = false; this.notify.success('Ключ удалён'); this.load(); },
          error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось удалить ключ'); this.load(); },
        });
      });
  }

  byId = (_: number, k: any) => k.id;
  ago(iso: string | null) { return relativeTime(iso); }
  full(iso: string | null) { return fullDateTime(iso); }
  day(iso: string | null): string {
    if (!iso) return '—';
    const t = new Date(iso);
    return isNaN(t.getTime()) ? '—' : new Intl.DateTimeFormat('ru-RU').format(t);      // 28.09.2026
  }
}
