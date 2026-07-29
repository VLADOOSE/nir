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
    .et-market { color: var(--text-muted); font-size: 13px; margin: 4px 0 16px; }
    .et-lbl { display: block; font-size: 13px; color: var(--text); margin: 12px 0 4px; font-weight: 600; }
    .et-input { width: 100%; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); }
    .et-body { width: 100%; padding: 10px; border: 1px solid var(--border); border-radius: 6px; font: inherit; resize: vertical; background: var(--surface); color: var(--text); }
    .et-vars { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 12px 0; }
    .et-vars-title { font-size: 12px; color: var(--text-muted); margin-right: 4px; }
    /* Чип-плейсхолдер — тинт-формула чипа на --accent (текстового варианта у
       --accent нет, надпись — сам --accent). Индиго #eef2ff/#3730a3 был просто
       вторым синим диалектом, отдельного смысла не нёс. */
    .et-chip { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border: none; border-radius: 999px; padding: 3px 10px; font-size: 12px; cursor: pointer; }
    /* Ховер усилен до 25%: буквальный перевод #e0e7ff дал бы тот же 15%-тинт,
       и чип перестал бы отзываться на наведение. */
    .et-chip:hover { background: color-mix(in srgb, var(--accent) 25%, transparent); }
    .et-note { color: var(--text-muted); font-size: 12px; }
    .et-actions { display: flex; gap: 10px; margin-top: 16px; }
    /* .btn / .btn-save / .btn-line удалены — их даёт kit. Обе кнопки шаблона
       несут базовый класс btn, поэтому геометрию повторять не нужно; заодно ушли
       два случайных расхождения: своя геометрия .btn (8px 16px / 6px / 14px) и
       индиго #4f46e5 у «Сохранить» вместо бренд-синего. */
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
