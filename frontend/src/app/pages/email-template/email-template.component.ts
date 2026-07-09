import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-email-template',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="et-page">
      <h2>Шаблон письма КП</h2>
      <p class="et-market">Рынок: <b>{{ marketLabel }}</b> — редактируется шаблон активного рынка.</p>

      <label class="et-lbl">Тема письма</label>
      <input class="et-input" [(ngModel)]="subject" placeholder="Запрос коммерческого предложения" />

      <label class="et-lbl">Текст письма</label>
      <textarea class="et-body" rows="18" [(ngModel)]="body"
                (focus)="lastField = 'body'" (click)="lastField = 'body'"></textarea>

      <div class="et-vars">
        <span class="et-vars-title">Плейсхолдеры (клик — вставить):</span>
        <button type="button" class="et-chip" *ngFor="let p of placeholders"
                (click)="insert(p.key)" [title]="p.desc">{{ p.key }}</button>
      </div>
      <p class="et-note">Метка [КП-№] и подстановка позиций/дат — автоматические. Письмо намеренно не указывает номер тендера.</p>

      <div class="et-actions">
        <button class="btn btn-save" [disabled]="saving" (click)="save()">{{ saving ? 'Сохранение…' : 'Сохранить' }}</button>
        <button class="btn btn-line" (click)="reset()">Сбросить</button>
      </div>
    </div>
  `,
  styles: [`
    .et-page { max-width: 820px; }
    .et-market { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .et-lbl { display: block; font-size: 13px; color: #374151; margin: 12px 0 4px; font-weight: 600; }
    .et-input { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .et-body { width: 100%; padding: 10px; border: 1px solid #d1d5db; border-radius: 6px; font: inherit; resize: vertical; }
    .et-vars { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 12px 0; }
    .et-vars-title { font-size: 12px; color: #6b7280; margin-right: 4px; }
    .et-chip { background: #eef2ff; color: #3730a3; border: none; border-radius: 999px; padding: 3px 10px; font-size: 12px; cursor: pointer; }
    .et-chip:hover { background: #e0e7ff; }
    .et-note { color: #6b7280; font-size: 12px; }
    .et-actions { display: flex; gap: 10px; margin-top: 16px; }
    .btn { padding: 8px 16px; border-radius: 6px; border: none; cursor: pointer; font-size: 14px; }
    .btn-save { background: #4f46e5; color: #fff; }
    .btn-line { background: #fff; border: 1px solid #d1d5db; color: #374151; }
  `],
})
export class EmailTemplateComponent implements OnInit {
  subject = '';
  body = '';
  saving = false;
  lastField: 'subject' | 'body' = 'body';
  placeholders = [
    { key: '{{приветствие}}', desc: 'Уважаемый(ая) ФИО! или Здравствуйте!' },
    { key: '{{компания}}', desc: 'Название вашей компании' },
    { key: '{{позиции}}', desc: 'Список оборудования (обязательно)' },
    { key: '{{дедлайн}}', desc: 'Просим ответить до даты' },
    { key: '{{реестр}}', desc: 'НЦЭЛС РК / Росздравнадзора' },
  ];

  constructor(private api: ApiService, private notify: NotificationService,
              private market: MarketService, private cdr: ChangeDetectorRef) {}

  get marketLabel() { return this.market.companyLabel(); }  // MarketService: companyLabel()/value/symbol()

  ngOnInit() {
    this.api.getEmailTemplate().subscribe({
      next: (t) => { this.subject = t.subject || ''; this.body = t.body || ''; this.cdr.detectChanges(); },
      error: (e) => this.notify.error('Не удалось загрузить шаблон: ' + (e.error?.message || e.message)),
    });
  }

  insert(key: string) {
    if (this.lastField === 'subject') this.subject = (this.subject || '') + key;
    else this.body = (this.body || '') + key;
    this.cdr.detectChanges();
  }

  save() {
    this.saving = true;
    this.api.saveEmailTemplate({ subject: this.subject, body: this.body }).subscribe({
      next: (r) => {
        this.saving = false;
        if ((r.warnings || []).includes('no-positions'))
          this.notify.error('Сохранено, но в тексте нет {{позиции}} — список оборудования не попадёт в письмо');
        else this.notify.success('Шаблон сохранён');
        this.cdr.detectChanges();
      },
      error: (e) => { this.saving = false; this.notify.error('Ошибка сохранения: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  reset() {
    this.api.getEmailTemplateDefault().subscribe({
      next: (t) => {
        this.subject = t.subject || ''; this.body = t.body || '';
        this.notify.success('Загружен стандартный шаблон — нажмите «Сохранить», чтобы применить');
        this.cdr.detectChanges();
      },
      error: (e) => this.notify.error('Ошибка: ' + (e.error?.message || e.message)),
    });
  }
}
