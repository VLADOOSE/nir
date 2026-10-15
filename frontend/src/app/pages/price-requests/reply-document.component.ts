import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { saveBlob } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { errorText } from '../../shared/error-text';
import { REPLY_ROLE_OPTIONS, sourceLabel } from '../../shared/supplier-reply';
import type { ReplyColumn, ReplySource, SupplierReply } from '../../shared/supplier-reply';

/** Документ ответа (спека §4.1): источники, таблица файла с ролями колонок, страницы PDF, картинка, текст письма. */
@Component({
  selector: 'app-reply-document',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="src">
      <button type="button" *ngFor="let s of view.sources" class="chip" [class.on]="isOn(s)" (click)="pick(s)">{{ label(s) }}</button>
      <label class="btn btn-line up" *ngIf="admin">{{ uploading ? 'Загружаю…' : 'Загрузить файл' }}
        <input type="file" hidden accept=".xlsx,.xls,.docx,.doc,.pdf,.jpg,.jpeg,.png,.webp" [disabled]="uploading" (change)="upload($event)" />
      </label>
    </div>
    <p class="hint" *ngIf="!view.sources.length">Файлов и писем от поставщика нет. Загрузите КП, если оно пришло в WhatsApp или письмом без метки [КП-…].</p>

    <ng-container *ngIf="current as s">
      <div class="meta" *ngIf="s.type === 'FILE'">
        <span class="name">{{ s.name }}</span>
        <span class="note" *ngIf="s.parseNote">{{ s.parseNote }}</span>
        <button type="button" class="link" (click)="download(s)">Скачать</button>
        <button type="button" class="link" *ngIf="admin && s.parseStatus === 'PARSED'" [disabled]="busy" (click)="apply(s)">Взять цены из этого файла</button>
        <ng-container *ngIf="admin && s.uploaded">
          <button type="button" class="link danger" *ngIf="!confirmDelete" (click)="confirmDelete = true">Удалить</button>
          <span *ngIf="confirmDelete" class="confirm">Удалить файл и несверенные цены из него?
            <button type="button" class="link danger" [disabled]="busy" (click)="remove(s)">Да</button>
            <button type="button" class="link" (click)="confirmDelete = false">Нет</button></span>
        </ng-container>
      </div>

      <div class="tbl" *ngIf="s.type === 'FILE' && s.fileId === view.fileId && view.table as t">
        <div class="t-cap">{{ t.source }} · шапка — строка {{ t.headerRow }}</div>
        <div class="t-scroll">
          <table>
            <thead><tr><th>Стр.</th>
              <th *ngFor="let c of t.columns">
                <div class="h">{{ c.header || '—' }}</div>
                <!-- выбранное — через [selected] у варианта: [value] у select ставится раньше, чем *ngFor нарисует
                     варианты, и все колонки показывали бы первый вариант («Наименование») — найдено живой проверкой -->
                <select *ngIf="admin; else roleText" (change)="setRole(c, $event)" [disabled]="busy"
                        [attr.aria-label]="'Роль колонки ' + (c.header || c.index + 1)">
                  <option *ngFor="let o of roles" [value]="o.v" [selected]="o.v === c.role">{{ o.l }}</option>
                </select>
                <ng-template #roleText><div class="role">{{ roleLabel(c.role) }}</div></ng-template>
              </th></tr></thead>
            <tbody>
              <tr *ngFor="let r of t.rows" [class.linked]="r.ordinal != null">
                <td class="rn">{{ r.fileRow }}<span class="to" *ngIf="r.ordinal != null">→ поз. {{ r.ordinal }}</span></td>
                <td *ngFor="let cell of r.cells">{{ cell }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </div>

      <div class="pages" *ngIf="s.type === 'FILE' && s.kind === 'PDF'">
        <img *ngFor="let u of pageUrls; let i = index" [src]="u" [alt]="'Страница ' + (i + 1)" (click)="zoom = u" />
        <p class="hint" *ngIf="pagesLoading">Загружаю страницы…</p>
        <p class="hint" *ngIf="(s.pageCount || 0) > 30">Показаны первые 30 страниц — остальное в файле.</p>
      </div>
      <img class="pic" *ngIf="s.type === 'FILE' && s.kind === 'IMAGE' && imageUrl" [src]="imageUrl" alt="Картинка из ответа" (click)="zoom = imageUrl" />
      <div class="mail" *ngIf="s.type === 'EMAIL'">
        <div class="m-head">{{ s.from }} · {{ s.subject }}</div>
        <pre>{{ s.text || 'Текста нет' }}</pre>
      </div>
    </ng-container>
    <div class="zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Крупно"><img [src]="zoom" alt="Крупно" /></div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .src { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; max-width: 100%; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .up { cursor: pointer; }
    .meta { display: flex; flex-wrap: wrap; gap: 6px 14px; align-items: baseline; font-size: 13px; }
    .name { font-weight: 600; overflow-wrap: anywhere; }
    .note { color: var(--warn-text); }
    .confirm { color: var(--text); }
    .link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font-size: 13px; }
    .link.danger { color: var(--danger-text); }
    .link:disabled { color: var(--text-muted); cursor: default; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .tbl { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .t-cap { font-size: 12px; color: var(--text-muted); padding: 6px 10px; background: var(--surface-2); }
    .t-scroll { overflow: auto; max-height: 70vh; }
    table { border-collapse: collapse; font-size: 12px; min-width: 100%; }
    th, td { border-bottom: 1px solid var(--border); padding: 5px 8px; text-align: left; vertical-align: top; }
    th { background: var(--surface-2); position: sticky; top: 0; }
    th .h { font-weight: 600; margin-bottom: 4px; }
    th select { width: 100%; min-width: 7.5em; font-size: 12px; padding: 3px; border: 1px solid var(--border); border-radius: 4px; background: var(--surface); color: var(--text); }
    .role { font-size: 11px; color: var(--text-muted); }
    tr.linked td { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .rn { white-space: nowrap; color: var(--text-muted); }
    .to { display: block; color: var(--success-text); font-weight: 600; }
    .pages { display: flex; flex-direction: column; gap: 8px; }
    .pages img, .pic { width: 100%; border: 1px solid var(--border); border-radius: 6px; cursor: zoom-in; background: #fff; }
    .mail .m-head { font-size: 12px; color: var(--text-muted); margin-bottom: 4px; overflow-wrap: anywhere; }
    .mail pre { white-space: pre-wrap; font: inherit; font-size: 13px; margin: 0; padding: 10px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
    .zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, .8); z-index: 1200; display: flex; align-items: flex-start; justify-content: center; overflow: auto; padding: 16px; cursor: zoom-out; }
    .zoom img { max-width: min(1100px, 100%); background: #fff; }
    @media (max-width: 900px) { .chip, .link, .up { min-height: 40px; } th select { font-size: 16px; } }
  `],
})
export class ReplyDocumentComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) view!: SupplierReply;
  @Input({ required: true }) requestId!: number;
  @Input() admin = false;
  @Output() changed = new EventEmitter<SupplierReply>();
  @Output() selectFile = new EventEmitter<number>();

  readonly roles = REPLY_ROLE_OPTIONS;
  current: ReplySource | null = null;
  pageUrls: string[] = [];
  pagesLoading = false;
  imageUrl: string | null = null;
  zoom: string | null = null;
  busy = false;
  uploading = false;
  confirmDelete = false;
  private urls: string[] = [];
  private loadSub?: Subscription;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view']) return;
    const prev = this.current;
    let next: ReplySource | null = null;
    if (prev?.type === 'EMAIL') next = this.view.sources.find(s => s.type === 'EMAIL' && s.inboundId === prev.inboundId) || null;
    if (!next && this.view.fileId != null) next = this.view.sources.find(s => s.type === 'FILE' && s.fileId === this.view.fileId) || null;
    if (!next) next = this.view.sources[0] || null;
    this.show(next);
  }

  ngOnDestroy() { this.revoke(); }

  label(s: ReplySource) { return sourceLabel(s); }
  isOn(s: ReplySource) { return !!this.current && this.current.type === s.type && this.current.fileId === s.fileId && this.current.inboundId === s.inboundId; }
  roleLabel(r: string) { return this.roles.find(o => o.v === r)?.l || r; }

  pick(s: ReplySource) {
    // таблица и подсказки позиций считаются по выбранному файлу — разобранный файл перечитывает экран
    if (s.type === 'FILE' && s.parseStatus === 'PARSED' && s.fileId !== this.view.fileId) { this.selectFile.emit(s.fileId!); return; }
    this.show(s);
  }

  private show(s: ReplySource | null) {
    const same = !!s && !!this.current && s.type === this.current.type && s.fileId === this.current.fileId && s.inboundId === this.current.inboundId;
    this.current = s;
    this.confirmDelete = false;
    if (same) return;
    this.revoke();
    if (!s || s.type !== 'FILE' || s.fileId == null) return;
    if (s.kind === 'PDF') this.loadPages(s.fileId, Math.min(s.pageCount || 0, 30));
    else if (s.kind === 'IMAGE') this.loadImage(s.fileId);
  }

  private loadPages(fileId: number, count: number) {
    if (!count) return;
    this.pagesLoading = true;
    const next = (n: number) => {
      if (n > count || this.current?.fileId !== fileId) { this.pagesLoading = false; this.cdr.markForCheck(); return; }
      this.loadSub = this.api.supplierReplyPage(this.requestId, fileId, n).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: b => { if (this.current?.fileId !== fileId) return; const u = URL.createObjectURL(b); this.urls.push(u); this.pageUrls = [...this.pageUrls, u]; this.cdr.markForCheck(); next(n + 1); },
        error: () => { this.pagesLoading = false; this.cdr.markForCheck(); },
      });
    };
    next(1);
  }

  private loadImage(fileId: number) {
    this.loadSub = this.api.downloadSupplierReplyFile(this.requestId, fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { if (!r.body || this.current?.fileId !== fileId) return; const u = URL.createObjectURL(r.body); this.urls.push(u); this.imageUrl = u; this.cdr.markForCheck(); },
    });
  }

  private revoke() {
    this.loadSub?.unsubscribe();
    for (const u of this.urls) URL.revokeObjectURL(u);
    this.urls = [];
    this.pageUrls = [];
    this.imageUrl = null;
    this.zoom = null;
    this.pagesLoading = false;
  }

  download(s: ReplySource) {
    if (s.fileId == null) return;
    this.api.downloadSupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const f = responseFile(r, s.name); if (f) saveBlob(f, f.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  upload(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const f = input.files?.[0];
    input.value = '';
    if (!f) return;
    // предел — как у сервера и nginx фронта (15 МБ, GlobalExceptionHandler.UPLOAD_LIMIT_MB)
    if (f.size > 15 * 1024 * 1024) { this.notify.error('Файл больше 15 МБ — уменьшите его и загрузите снова'); return; }
    this.uploading = true;
    this.api.uploadSupplierReplyFile(this.requestId, f).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.uploading = false; this.notify.success('Файл загружен'); this.changed.emit(v); },
      error: e => {
        this.uploading = false;
        // больше предела nginx отвечает 413 своей страницей, без нашего текста
        this.notify.error(e?.status === 413 ? 'Файл больше 15 МБ — уменьшите его и загрузите снова' : 'Файл не загрузился: ' + errorText(e));
        this.cdr.markForCheck();
      },
    });
  }

  apply(s: ReplySource) {
    if (s.fileId == null) return;
    this.busy = true;
    this.api.applySupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }

  setRole(c: ReplyColumn, ev: Event) {
    const role = (ev.target as HTMLSelectElement).value;
    if (this.view.fileId == null || !this.view.table) return;
    const roles = this.view.table.columns.map(x => ({ index: x.index, role: x.index === c.index ? role : x.role }));
    this.busy = true;
    this.api.setSupplierReplyColumns(this.requestId, this.view.fileId, roles).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error('Роль не сохранилась: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }

  remove(s: ReplySource) {
    if (s.fileId == null) return;
    this.busy = true;
    this.api.deleteSupplierReplyFile(this.requestId, s.fileId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.busy = false; this.confirmDelete = false; this.changed.emit(v); },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
