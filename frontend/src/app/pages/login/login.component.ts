import { Component, ChangeDetectorRef } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [NgIf, ReactiveFormsModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <div class="login-header">
          <span class="login-logo">РМ</span>
          <h1>АИС Регион-Мед</h1>
          <p>Автоматизированная информационная система учёта участия в тендерах на медицинское оборудование</p>
        </div>
        <form [formGroup]="loginForm" (ngSubmit)="onLogin()" class="login-form">
          <label>Логин<input formControlName="username" placeholder="Введите логин" autofocus /></label>
          <label>Пароль<input type="password" formControlName="password" placeholder="Введите пароль" /></label>
          <p *ngIf="error" class="error-msg">{{ error }}</p>
          <button class="btn btn-login" type="submit" [disabled]="loginForm.invalid || loading">{{ loading ? 'Вход...' : 'Войти' }}</button>
        </form>
        <p class="login-hint">Тестовые данные: admin / admin или operator / operator</p>
      </div>
    </div>
  `,
  styles: [`
    .login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: #f0f2f5; }
    .login-card { background: #fff; border-radius: 12px; box-shadow: 0 4px 24px rgba(0,0,0,0.08); padding: 40px; width: 400px; max-width: 90vw; }
    .login-header { text-align: center; margin-bottom: 32px; }
    .login-logo { display: inline-block; width: 56px; height: 56px; background: #1a56db; color: #fff; border-radius: 12px; font-size: 24px; font-weight: 700; line-height: 56px; margin-bottom: 16px; }
    .login-header h1 { font-size: 22px; color: #111827; margin: 0 0 8px; }
    .login-header p { font-size: 13px; color: #6b7280; margin: 0; line-height: 1.4; }
    .login-form label { display: block; margin-bottom: 16px; font-size: 14px; color: #374151; font-weight: 500; }
    .login-form input { display: block; width: 100%; padding: 10px 12px; margin-top: 6px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 15px; box-sizing: border-box; }
    .login-form input:focus { outline: none; border-color: #1a56db; box-shadow: 0 0 0 3px rgba(26,86,219,0.1); }
    .btn-login { width: 100%; padding: 12px; background: #1a56db; color: #fff; border: none; border-radius: 6px; font-size: 15px; font-weight: 600; cursor: pointer; margin-top: 8px; }
    .btn-login:hover { background: #1648b8; }
    .btn-login:disabled { opacity: 0.5; cursor: not-allowed; }
    .error-msg { color: #dc2626; font-size: 13px; margin: 0 0 8px; }
    .login-hint { text-align: center; font-size: 12px; color: #9ca3af; margin-top: 20px; }
  `]
})
export class LoginComponent {
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
