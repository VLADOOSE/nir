import { Component, ChangeDetectorRef } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-settings',
  standalone: true,
  imports: [NgIf, ReactiveFormsModule],
  template: `
    <h2>Настройки</h2>
    <p class="subtitle">Конфигурация системы</p>

    <div class="settings-section">
      <h3>Настройки эл. почты (SMTP)</h3>
      <p class="settings-desc">Для отправки запросов КП дистрибьюторам напрямую из системы</p>

      <div class="mail-status" [class.configured]="emailConfigured" [class.not-configured]="!emailConfigured">
        <span *ngIf="emailConfigured">Почта настроена</span>
        <span *ngIf="!emailConfigured">Почта не настроена</span>
      </div>

      <form [formGroup]="mailForm" (ngSubmit)="saveMailSettings()" class="settings-form">
        <label>SMTP сервер<input formControlName="host" placeholder="smtp.mail.ru" /></label>
        <div class="dims-row">
          <label>Порт<input type="number" formControlName="port" placeholder="465" /></label>
          <label>Протокол
            <select formControlName="protocol">
              <option value="smtps">SMTPS (SSL)</option>
              <option value="smtp">SMTP</option>
            </select>
          </label>
        </div>
        <label>Адрес эл. почты (логин)<input formControlName="username" placeholder="user@mail.ru" /></label>
        <label>Пароль приложения<input type="password" formControlName="password" placeholder="Пароль" /></label>
        <div class="form-actions">
          <button class="btn btn-save" type="submit">Сохранить</button>
          <button class="btn btn-test" type="button" (click)="testEmail()">Тестовое письмо</button>
        </div>
        <p *ngIf="saveMessage" class="save-message" [class.error]="saveError">{{ saveMessage }}</p>
      </form>

      <div class="settings-hint">
        <p><strong>Как получить пароль приложения:</strong></p>
        <p>Mail.ru: Настройки &rarr; Все настройки &rarr; Безопасность &rarr; Пароли приложений &rarr; Создать</p>
        <p>Gmail: Аккаунт &rarr; Безопасность &rarr; Двухэтапная аутентификация &rarr; Пароли приложений</p>
        <p>Yandex: Настройки &rarr; Безопасность &rarr; Пароли приложений</p>
        <p style="margin-top: 8px;"><strong>Применение:</strong> Задайте переменные окружения <code>MAIL_USERNAME</code> и <code>MAIL_PASSWORD</code> перед запуском сервера.</p>
      </div>
    </div>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .settings-section { background: #fff; border: 1px solid #e5e7eb; border-radius: 8px; padding: 24px; max-width: 600px; }
    .settings-section h3 { margin: 0 0 4px; font-size: 16px; }
    .settings-desc { font-size: 13px; color: #6b7280; margin: 0 0 16px; }
    .mail-status { padding: 8px 16px; border-radius: 6px; font-size: 14px; margin-bottom: 16px; }
    .mail-status.configured { background: #d1fae5; color: #065f46; }
    .mail-status.not-configured { background: #fef3c7; color: #92400e; }
    .settings-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .settings-form input, .settings-form select { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; box-sizing: border-box; }
    .dims-row { display: flex; gap: 12px; }
    .dims-row label { flex: 1; }
    .form-actions { display: flex; gap: 8px; margin-top: 16px; }
    .btn { padding: 8px 18px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-test { background: #10b981; color: #fff; }
    .save-message { margin-top: 12px; font-size: 13px; color: #065f46; }
    .save-message.error { color: #dc2626; }
    .settings-hint { margin-top: 20px; padding: 16px; background: #f9fafb; border-radius: 6px; font-size: 13px; color: #6b7280; line-height: 1.6; }
    .settings-hint p { margin: 2px 0; }
    code { background: #e5e7eb; padding: 1px 4px; border-radius: 3px; font-size: 12px; }
  `]
})
export class SettingsComponent {
  emailConfigured = false;
  saveMessage = '';
  saveError = false;

  mailForm = new FormGroup({
    host: new FormControl('smtp.mail.ru'),
    port: new FormControl(465),
    protocol: new FormControl('smtps'),
    username: new FormControl(''),
    password: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {
    this.api.getEmailStatus().subscribe((s: any) => {
      this.emailConfigured = s.configured;
      this.cdr.detectChanges();
    });
  }

  saveMailSettings() {
    this.saveMessage = 'Настройки почты применяются через переменные окружения при запуске сервера. Задайте MAIL_USERNAME и MAIL_PASSWORD перед запуском ./gradlew bootRun';
    this.saveError = false;
    this.cdr.detectChanges();
  }

  testEmail() {
    const email = this.mailForm.value.username;
    if (!email) { this.saveMessage = 'Укажите адрес эл. почты'; this.saveError = true; this.cdr.detectChanges(); return; }
    this.api.sendEmail(email, 'Тестовое письмо АИС Регион-Мед', 'Если вы видите это письмо, настройка почты выполнена успешно.').subscribe({
      next: (res: any) => {
        this.saveMessage = res.status === 'OK' ? 'Тестовое письмо отправлено на ' + email : 'Ошибка: ' + res.message;
        this.saveError = res.status !== 'OK';
        this.cdr.detectChanges();
      },
      error: () => { this.saveMessage = 'Ошибка подключения к серверу'; this.saveError = true; this.cdr.detectChanges(); }
    });
  }
}
