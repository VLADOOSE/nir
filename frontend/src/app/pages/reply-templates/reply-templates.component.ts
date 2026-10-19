import { ChangeDetectorRef, Component, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';
import { errorText } from '../../shared/error-text';
import type { ReplyRole, ReplyTemplate, ReplyTemplatesState } from '../../shared/client-mail';

/** «Система → Шаблоны ответов» (спека 2026-10-19-client-mail-reply §6.4): кнопки окна письма клиенту и подпись. */
@Component({
  selector: 'app-reply-templates',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="rt-page">
      <h2>Шаблоны ответов</h2>
      <p class="subtitle">Кнопки в окне письма клиенту: нажатие ставит текст, перед отправкой его можно поправить. Рынок: <b>{{ marketLabel }}</b>.</p>
      <p class="rt-state" *ngIf="state" [class.rt-off]="!state.sending.enabled">
        {{ state.sending.enabled ? 'Отправка с ' + state.sending.mailbox + ': включена' : state.sending.reason + ' (DEPLOY.md §10)' }}</p>
      <p class="rt-note" *ngIf="loading">Загружаю…</p>
      <ng-container *ngIf="state">
        <div class="rt-card" *ngFor="let t of templates; let i = index; let last = last">
          <div class="rt-row">
            <input class="rt-title" [(ngModel)]="t.title" maxlength="60" placeholder="Название кнопки" [attr.aria-label]="'Название шаблона ' + (i + 1)" />
            <div class="rt-tools">
              <button type="button" class="btn btn-line btn-sm" [disabled]="i === 0" (click)="move(i, -1)" aria-label="Выше">↑</button>
              <button type="button" class="btn btn-line btn-sm" [disabled]="last" (click)="move(i, 1)" aria-label="Ниже">↓</button>
              <button type="button" class="btn btn-line btn-sm" (click)="templates.splice(i, 1)">Удалить</button>
            </div>
          </div>
          <textarea class="rt-body" [(ngModel)]="t.body" rows="5" maxlength="4000" [attr.aria-label]="'Текст шаблона ' + (i + 1)"></textarea>
          <label class="rt-role">Подставлять самому:
            <select [ngModel]="t.role" (ngModelChange)="setRole(t, $event)" [attr.aria-label]="'Подставлять самому — шаблон ' + (i + 1)">
              <option [ngValue]="null">—</option>
              <option [ngValue]="'FIRST_REPLY'">в первое письмо</option>
              <option [ngValue]="'OFFER'">в письмо с КП</option>
            </select>
          </label>
        </div>
        <button type="button" class="btn btn-line" (click)="add()">+ Шаблон</button>
        <h3 class="rt-sec">Подпись</h3>
        <p class="rt-note">Добавляется к каждому письму. <code>{{ employee }}</code> — ФИО отправителя из «Пользователей»; пусто — строки нет.</p>
        <textarea class="rt-body" [(ngModel)]="signature" rows="5" maxlength="1000" aria-label="Подпись"></textarea>
        <div class="rt-actions">
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .rt-page { max-width: 820px; display: flex; flex-direction: column; gap: 10px; }
    .rt-page h2 { margin: 0; }
    .rt-state { margin: 0; font-size: 13px; color: var(--success-text); }
    .rt-state.rt-off { color: var(--warn-text); }
    .rt-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .rt-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px;
               background: var(--surface); }
    .rt-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .rt-title { flex: 1; min-width: 160px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                color: var(--text); font: inherit; font-weight: 600; }
    .rt-tools { display: flex; gap: 6px; }
    .rt-body { width: 100%; box-sizing: border-box; resize: vertical; padding: 8px 10px; border: 1px solid var(--border); border-radius: 8px;
               background: var(--surface); color: var(--text); font: inherit; }
    .rt-role { font-size: 13px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .rt-sec { margin: 8px 0 0; }
    .rt-actions { display: flex; gap: 8px; }
    @media (max-width: 900px) {
      .rt-title, .rt-body { font-size: 16px; }
      .rt-tools { width: 100%; }
    }
  `],
})
export class ReplyTemplatesComponent implements OnInit {
  state: ReplyTemplatesState | null = null;
  templates: ReplyTemplate[] = [];
  signature = '';
  loading = true;
  saving = false;
  readonly employee = '{{сотрудник}}';

  constructor(private api: ApiService, private notify: NotificationService, private market: MarketService,
              private cdr: ChangeDetectorRef) {}

  get marketLabel(): string { return this.market.value === 'KZ' ? 'West-Med (KZ)' : 'Регион-Мед (РФ)'; }

  ngOnInit() {
    this.api.getReplyTemplates().subscribe({
      next: s => { this.apply(s); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Шаблоны не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  private apply(s: ReplyTemplatesState) {
    this.state = s;
    this.templates = s.templates.map(t => ({ ...t }));
    this.signature = s.signature;
  }

  /** Метка «подставлять самому» — у одного шаблона: выбор снимает её с другого (сервер иначе ответит 400). */
  setRole(t: ReplyTemplate, role: ReplyRole | null) {
    if (role) for (const x of this.templates) if (x !== t && x.role === role) x.role = null;
    t.role = role;
  }

  move(i: number, d: number) {
    const [x] = this.templates.splice(i, 1);
    this.templates.splice(i + d, 0, x);
  }

  add() { this.templates.push({ id: null, title: '', body: '', role: null }); }

  save() {
    this.saving = true;
    this.api.saveReplyTemplates({ templates: this.templates, signature: this.signature }).subscribe({
      next: s => { this.apply(s); this.saving = false; this.notify.success('Шаблоны сохранены'); this.cdr.detectChanges(); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }
}
