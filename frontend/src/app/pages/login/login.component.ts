import { Component, ChangeDetectorRef, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { ActivatedRoute, Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ApiService } from '../../services/api.service';
import { PasskeyService } from '../../services/passkey.service';
import { APP_NAME, APP_TAGLINE } from '../../services/market.service';
import { safeReturnUrl } from '../../shared/return-url';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [NgIf, ReactiveFormsModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <div class="login-header">
          <span class="login-logo">
            <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
          </span>
          <h1>{{ appName }}</h1>
          <p>{{ appTagline }}</p>
        </div>
        <form [formGroup]="loginForm" (ngSubmit)="onLogin()" class="login-form">
          <label>Логин<input formControlName="username" name="username" autocomplete="username" placeholder="Введите логин" autofocus /></label>
          <label>Пароль<input type="password" formControlName="password" name="password" autocomplete="current-password" placeholder="Введите пароль" /></label>
          <p *ngIf="error" class="error-msg">{{ error }}</p>
          <button class="btn btn-login" type="submit" [disabled]="loginForm.invalid || loading">{{ loading ? 'Вход...' : 'Войти' }}</button>
        </form>
        <!-- вход по ключу (passkeys): только там, где ключ может сработать — на своём домене и в браузере с WebAuthn -->
        <div *ngIf="passkeyUsable" class="passkey">
          <div class="or">или</div>
          <button class="btn btn-line btn-passkey" type="button" (click)="onPasskey()" [disabled]="passkeyBusy || loading">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><path d="M9 9h.01M15 9h.01"/></svg>
            {{ passkeyBusy ? 'Ждём Face ID / Touch ID…' : 'Войти с Face ID / Touch ID' }}
          </button>
          <p *ngIf="passkeyError" class="error-msg passkey-msg">{{ passkeyError }}</p>
          <p *ngIf="passkeyHint" class="hint-msg passkey-msg">{{ passkeyHint }}</p>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* Экран живёт ВНЕ LayoutComponent, поэтому фон страницы задаёт он сам. */
    .login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: var(--app-bg); }
    /* Тень карточки — var(--shadow-lg), общий токен «приподнятой панели» (им же
       живут модалки applies и bulk-price). Прежняя rgba(0,0,0,.08) на тёмном фоне
       не видна вовсе, а свою геометрию тени этот экран не заслуживает. */
    .login-card { background: var(--surface); border-radius: 12px; box-shadow: var(--shadow-lg); padding: 40px; width: 400px; max-width: 90vw; }
    .login-header { text-align: center; margin-bottom: 32px; }
    /* Медкрест внутри — инлайн-SVG со stroke="currentColor", цвет ему даёт эта
       строка (CLAUDE.md §14: path захардкожен намеренно, lucide приходил пустым). */
    .login-logo { display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; background: var(--accent); color: var(--accent-contrast); border-radius: 14px; margin-bottom: 16px; }
    /* h1 в kit нет (там только h2, h3) — цвет остаётся локальным. */
    .login-header h1 { font-size: 22px; color: var(--text); margin: 0 0 8px; }
    .login-header p { font-size: 13px; color: var(--text-muted); margin: 0; line-height: 1.4; }
    .login-form label { display: block; margin-bottom: 16px; font-size: 14px; color: var(--text); font-weight: 500; }
    .login-form input { display: block; width: 100%; padding: 10px 12px; margin-top: 6px; border: 1px solid var(--border); border-radius: 6px; font-size: 15px; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .login-form input:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 10%, transparent); }
    /* Кнопка входа осознанно крупнее базы kit: во всю ширину карточки — это
       раскладка экрана, а не разъехавшийся примитив. Цвет, ховер и disabled
       берутся из kit (.btn + .btn-login), поэтому здесь только геометрия. */
    .btn-login { width: 100%; padding: 12px; border-radius: 6px; font-size: 15px; font-weight: 600; margin-top: 8px; }
    .error-msg { color: var(--danger-text); font-size: 13px; margin: 0 0 8px; }
    /* .login-hint шаблоном сейчас не используется — оставлен как был, переведён вместе с остальным. */
    .login-hint { text-align: center; font-size: 12px; color: var(--text-muted); margin-top: 20px; }
    /* Вход по ключу: та же геометрия, что у «Войти»; цвет — контурная кнопка kit (.btn + .btn-line). */
    .passkey { margin-top: 18px; }
    .or { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; color: var(--text-muted); font-size: 12px; }
    .or::before, .or::after { content: ''; flex: 1; height: 1px; background: var(--border); }
    .btn-passkey { width: 100%; padding: 12px; border-radius: 6px; font-size: 15px; font-weight: 600; display: flex; align-items: center; justify-content: center; gap: 8px; }
    .hint-msg { color: var(--text-muted); font-size: 13px; }
    .passkey-msg { margin: 10px 0 0; line-height: 1.4; }
    /* Телефон: паддинг 40px съедал ширину, и «Войти с Face ID / Touch ID» ломалось на «Touch / ID». */
    @media (max-width: 480px) {
      .login-card { padding: 28px 20px; }
    }
  `]
})
export class LoginComponent implements OnInit {
  readonly appName = APP_NAME;
  readonly appTagline = APP_TAGLINE;
  loginForm = new FormGroup({
    username: new FormControl('', Validators.required),
    password: new FormControl('', Validators.required)
  });
  error = '';
  loading = false;
  passkeyUsable = false;
  passkeyBusy = false;
  passkeyError = '';
  passkeyHint = '';

  constructor(private auth: AuthService, private api: ApiService, private passkeys: PasskeyService,
              private router: Router, private route: ActivatedRoute, private cdr: ChangeDetectorRef) {
    if (this.auth.isLoggedIn()) {
      this.router.navigateByUrl(this.target());
    }
  }

  /** Куда после входа: страница из ссылки (returnUrl), иначе главная. */
  private target(): string {
    return safeReturnUrl(this.route.snapshot.queryParamMap.get('returnUrl')) ?? '/dashboard';
  }

  ngOnInit() {
    // кнопка ключа — только на домене ключей (не на Tailscale/туннеле) и в браузере с WebAuthn
    this.api.getPasskeyConfig().subscribe({
      next: c => { this.passkeyUsable = this.passkeys.usableHere(c.rpId); this.cdr.detectChanges(); },
      error: () => {},                                   // нет ответа — просто без кнопки: пароль работает всегда
    });
  }

  onLogin() {
    const { username, password } = this.loginForm.value;
    if (!username || !password) return;
    this.error = '';
    this.loading = true;
    this.auth.login(username, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigateByUrl(this.target());
      },
      error: (err) => {
        this.loading = false;
        this.error = err.error?.message || 'Неверный логин или пароль';
      }
    });
  }

  onPasskey() {
    this.error = '';
    this.passkeyError = '';
    this.passkeyHint = '';
    this.passkeyBusy = true;
    this.cdr.detectChanges();
    // .then, а не await: после нативного await навигация на дашборд шла бы мимо зоны Angular (см. PasskeyService)
    this.passkeys.login().then(outcome => {
      if (outcome === 'ok') {
        this.auth.loadCurrentUser().subscribe(user => {
          this.passkeyBusy = false;
          if (user) {
            this.router.navigateByUrl(this.target());
          } else {
            this.passkeyError = 'Не удалось войти по ключу. Попробуйте ещё раз или войдите паролем.';
            this.cdr.detectChanges();
          }
        });
        return;
      }
      this.passkeyBusy = false;
      if (outcome === 'cancelled') {
        // отмену и «ключа на устройстве нет» браузер не различает — подсказка одна и мягкая (спека §8)
        this.passkeyHint = 'Не получилось войти по ключу. Если ключа на этом устройстве ещё нет — войдите паролем и добавьте его в «Мой профиль».';
      } else if (outcome === 'rejected') {
        this.passkeyError = 'Ключ не принят. Попробуйте ещё раз; если не получится — войдите паролем и добавьте ключ заново в «Мой профиль», а старый удалите в настройках паролей устройства.';
      } else {
        this.passkeyError = 'Не удалось войти по ключу. Попробуйте ещё раз или войдите паролем.';
      }
      this.cdr.detectChanges();
    });
  }
}
