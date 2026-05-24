import { Component, ChangeDetectorRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl } from '@angular/forms';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';

@Component({
  selector: 'app-mail',
  standalone: true,
  imports: [NgFor, NgIf, ReactiveFormsModule, RouterLink],
  template: `
    <h2>Почта</h2>
    <p class="subtitle">Встроенный почтовый клиент для работы с дистрибьюторами</p>

    <div *ngIf="!emailConfigured" class="mail-warning">
      Почта не настроена. Перейдите в <a routerLink="/settings">Настройки</a> для конфигурации SMTP.
    </div>

    <ng-container *ngIf="emailConfigured">
      <div class="mail-toolbar">
        <button class="btn btn-compose" (click)="showCompose = true">Написать</button>
        <button class="btn btn-refresh" (click)="loadInbox()">Обновить</button>
      </div>

      <div *ngIf="showCompose" class="compose-form">
        <h3>Новое письмо</h3>
        <form [formGroup]="composeForm" (ngSubmit)="onSend()">
          <label>Кому<input formControlName="to" placeholder="email@example.com" /></label>
          <label>Тема<input formControlName="subject" /></label>
          <label>Текст<textarea formControlName="body" rows="8"></textarea></label>
          <div class="form-actions">
            <button class="btn btn-save" type="submit">Отправить</button>
            <button class="btn btn-cancel" type="button" (click)="showCompose = false">Отмена</button>
          </div>
          <p *ngIf="sendMessage" class="send-msg" [class.error]="sendError">{{ sendMessage }}</p>
        </form>
      </div>

      <div *ngIf="loading" class="empty">Загрузка...</div>
      <div *ngIf="!loading && inbox.length === 0 && !selectedMail" class="empty">Входящих писем нет</div>

      <div class="inbox-list" *ngIf="!loading && inbox.length > 0 && !selectedMail">
        <div class="mail-item" *ngFor="let m of inbox" (click)="selectedMail = m">
          <div class="mail-from">{{ m.from }}</div>
          <div class="mail-subject">{{ m.subject }}</div>
          <div class="mail-date">{{ m.date }}</div>
        </div>
      </div>

      <div *ngIf="selectedMail" class="mail-detail">
        <button class="btn btn-back" (click)="selectedMail = null">&#8592; Назад</button>
        <h3>{{ selectedMail.subject }}</h3>
        <p class="mail-meta">От: {{ selectedMail.from }} | {{ selectedMail.date }}</p>
        <pre class="mail-body-text">{{ selectedMail.preview }}</pre>
        <button class="btn btn-compose" (click)="replyTo(selectedMail)">Ответить</button>
      </div>
    </ng-container>
  `,
  styles: [`
    h2 { margin: 0; font-size: 20px; color: #111827; }
    h3 { margin: 0 0 12px; font-size: 16px; color: #111827; }
    .subtitle { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .mail-warning { background: #fef3c7; color: #92400e; padding: 16px; border-radius: 8px; margin-bottom: 16px; font-size: 14px; }
    .mail-warning a { color: #1a56db; text-decoration: underline; }
    .mail-toolbar { display: flex; gap: 8px; margin-bottom: 16px; }
    .btn { padding: 8px 18px; border: none; border-radius: 4px; cursor: pointer; font-size: 13px; }
    .btn-compose { background: #1a56db; color: #fff; }
    .btn-refresh { background: #e5e7eb; color: #374151; }
    .btn-save { background: #1a56db; color: #fff; }
    .btn-cancel { background: #e5e7eb; color: #374151; }
    .btn-back { background: #6b7280; color: #fff; margin-bottom: 12px; }
    .compose-form { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; margin-bottom: 16px; max-width: 600px; }
    .compose-form label { display: block; margin-bottom: 12px; font-size: 14px; color: #374151; font-weight: 500; }
    .compose-form input, .compose-form textarea { display: block; width: 100%; padding: 8px; margin-top: 4px; border: 1px solid #d1d5db; border-radius: 4px; font-size: 14px; font-family: inherit; box-sizing: border-box; }
    .form-actions { display: flex; gap: 8px; margin-top: 12px; }
    .send-msg { margin-top: 8px; font-size: 13px; color: #065f46; }
    .send-msg.error { color: #dc2626; }
    .empty { color: #9ca3af; font-size: 14px; padding: 32px 0; text-align: center; }
    .inbox-list { border: 1px solid #e5e7eb; border-radius: 8px; overflow: hidden; }
    .mail-item { display: grid; grid-template-columns: 200px 1fr 150px; padding: 12px 16px; border-bottom: 1px solid #f3f4f6; cursor: pointer; font-size: 14px; }
    .mail-item:hover { background: #f9fafb; }
    .mail-item:last-child { border-bottom: none; }
    .mail-from { font-weight: 500; color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mail-subject { color: #374151; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .mail-date { color: #9ca3af; font-size: 12px; text-align: right; }
    .mail-detail { background: #f9fafb; border: 1px solid #e5e7eb; border-radius: 8px; padding: 20px; }
    .mail-meta { font-size: 13px; color: #6b7280; margin-bottom: 16px; }
    .mail-body-text { background: #fff; border: 1px solid #d1d5db; border-radius: 4px; padding: 16px; font-size: 13px; line-height: 1.6; white-space: pre-wrap; font-family: inherit; margin-bottom: 12px; }
  `]
})
export class MailComponent {
  emailConfigured = false;
  inbox: any[] = [];
  loading = false;
  showCompose = false;
  selectedMail: any = null;
  sendMessage = '';
  sendError = false;
  composeForm = new FormGroup({
    to: new FormControl(''),
    subject: new FormControl(''),
    body: new FormControl('')
  });

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {
    this.api.getEmailStatus().subscribe((s: any) => {
      this.emailConfigured = s.configured;
      if (s.configured) this.loadInbox();
      this.cdr.detectChanges();
    });
  }

  loadInbox() {
    this.loading = true;
    this.api.getInbox(20).subscribe({
      next: data => { this.inbox = data; this.loading = false; this.cdr.detectChanges(); },
      error: () => { this.loading = false; this.cdr.detectChanges(); }
    });
  }

  onSend() {
    const v = this.composeForm.value;
    if (!v.to || !v.subject) { this.sendMessage = 'Заполните получателя и тему'; this.sendError = true; this.cdr.detectChanges(); return; }
    this.api.sendEmail(v.to!, v.subject!, v.body || '').subscribe({
      next: (res: any) => {
        this.sendMessage = res.status === 'OK' ? 'Письмо отправлено' : 'Ошибка: ' + res.message;
        this.sendError = res.status !== 'OK';
        if (res.status === 'OK') { this.showCompose = false; this.composeForm.reset(); }
        this.cdr.detectChanges();
      },
      error: () => { this.sendMessage = 'Ошибка отправки'; this.sendError = true; this.cdr.detectChanges(); }
    });
  }

  replyTo(m: any) {
    this.selectedMail = null;
    this.showCompose = true;
    this.composeForm.patchValue({
      to: m.from,
      subject: 'Re: ' + m.subject,
      body: '\n\n--- Исходное письмо ---\n' + m.preview
    });
  }
}
