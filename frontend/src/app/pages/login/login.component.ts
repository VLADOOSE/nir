import { Component, ChangeDetectorRef } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { APP_NAME, APP_TAGLINE } from '../../services/market.service';

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
          <label>Логин<input formControlName="username" placeholder="Введите логин" autofocus /></label>
          <label>Пароль<input type="password" formControlName="password" placeholder="Введите пароль" /></label>
          <p *ngIf="error" class="error-msg">{{ error }}</p>
          <button class="btn btn-login" type="submit" [disabled]="loginForm.invalid || loading">{{ loading ? 'Вход...' : 'Войти' }}</button>
        </form>
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
  `]
})
export class LoginComponent {
  readonly appName = APP_NAME;
  readonly appTagline = APP_TAGLINE;
  loginForm = new FormGroup({
    username: new FormControl('', Validators.required),
    password: new FormControl('', Validators.required)
  });
  error = '';
  loading = false;

  constructor(private auth: AuthService, private router: Router, private cdr: ChangeDetectorRef) {
    if (this.auth.isLoggedIn()) {
      this.router.navigate(['/dashboard']);
    }
  }

  onLogin() {
    const { username, password } = this.loginForm.value;
    if (!username || !password) return;
    this.error = '';
    this.loading = true;
    this.auth.login(username, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.loading = false;
        this.error = err.error?.message || 'Неверный логин или пароль';
      }
    });
  }
}
