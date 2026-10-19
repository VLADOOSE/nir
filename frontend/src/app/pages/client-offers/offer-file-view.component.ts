import { ChangeDetectorRef, Component, DestroyRef, Input, OnChanges, OnDestroy, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { saveBlob } from '../../shared/client-offer';
import type { OfferFileMeta, OfferFileTable } from '../../shared/client-offer';
import { responseFile } from '../../shared/share';
import { errorText } from '../../shared/error-text';

/** Показываются первые 30 страниц PDF — как у ответов поставщиков (ReplyPdfPages.MAX_PAGES). */
const MAX_PAGES = 30;

/**
 * Документ готового КП вместо предпросмотра конструктора (спека 2026-10-19 §3.3): таблицы файла как в файле, страницы
 * PDF по одной (blob через HttpClient — §14), картинка, «Скачать файл».
 */
@Component({
  selector: 'app-offer-file-view',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="ofv-head">
      <span class="ofv-name">{{ meta.name }}</span>
      <button type="button" class="btn btn-line btn-sm" (click)="download()">Скачать файл</button>
    </div>
    <p class="hint" *ngIf="loading">Загружаю документ…</p>
    <p class="hint warn" *ngIf="loadError">{{ loadError }}</p>
    <div class="ofv-tbl" *ngFor="let t of tables">
      <div class="t-cap">{{ t.source }}</div>
      <div class="t-scroll">
        <table>
          <thead><tr><th *ngFor="let c of t.columns">{{ c.header || '—' }}</th></tr></thead>
          <tbody><tr *ngFor="let r of t.rows"><td *ngFor="let cell of r.cells">{{ cell }}</td></tr></tbody>
        </table>
      </div>
    </div>
    <div class="ofv-pages" *ngIf="meta.kind === 'PDF'">
      <img *ngFor="let u of pageUrls; let i = index" [src]="u" [alt]="'Страница ' + (i + 1)" (click)="zoom = u" />
      <p class="hint" *ngIf="pagesLoading">Загружаю страницы…</p>
      <p class="hint" *ngIf="(meta.pages || 0) > 30">Показаны первые 30 страниц — остальное в файле.</p>
    </div>
    <img class="ofv-pic" *ngIf="meta.kind === 'IMAGE' && imageUrl" [src]="imageUrl" alt="КП картинкой" (click)="zoom = imageUrl" />
    <p class="hint" *ngIf="!loading && !loadError && (meta.kind === 'WORD' || meta.kind === 'EXCEL') && !tables.length">
      Таблицы с ценами в файле нет — откройте его кнопкой «Скачать файл».</p>
    <div class="ofv-zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Крупно"><img [src]="zoom" alt="Крупно" /></div>
  `,
  styles: [`
    :host { display: flex; flex-direction: column; gap: 10px; min-width: 0; }
    .ofv-head { display: flex; align-items: baseline; gap: 10px; flex-wrap: wrap; }
    .ofv-name { font-weight: 600; overflow-wrap: anywhere; flex: 1; min-width: 0; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .hint.warn { color: var(--warn-text); }
    .ofv-tbl { border: 1px solid var(--border); border-radius: 8px; overflow: hidden; }
    .t-cap { font-size: 12px; color: var(--text-muted); padding: 6px 10px; background: var(--surface-2); }
    .t-scroll { overflow: auto; max-height: 70vh; }
    table { border-collapse: collapse; font-size: 12px; min-width: 100%; }
    th, td { border-bottom: 1px solid var(--border); padding: 5px 8px; text-align: left; vertical-align: top; }
    th { background: var(--surface-2); position: sticky; top: 0; font-weight: 600; }
    .ofv-pages { display: flex; flex-direction: column; gap: 8px; }
    .ofv-pages img, .ofv-pic { width: 100%; border: 1px solid var(--border); border-radius: 6px; cursor: zoom-in; background: #fff; }
    .ofv-zoom { position: fixed; inset: 0; background: rgba(17, 24, 39, .8); z-index: 1200; display: flex; align-items: flex-start;
                justify-content: center; overflow: auto; padding: 16px; cursor: zoom-out; }
    .ofv-zoom img { max-width: min(1100px, 100%); background: #fff; }
    @media (max-width: 900px) { .ofv-head .btn { min-height: 40px; } }
  `],
})
export class OfferFileViewComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) offerId!: number;
  @Input({ required: true }) meta!: OfferFileMeta;

  tables: OfferFileTable[] = [];
  pageUrls: string[] = [];
  imageUrl: string | null = null;
  zoom: string | null = null;
  loading = false;
  pagesLoading = false;
  loadError = '';
  private urls: string[] = [];
  private sub?: Subscription;
  private shown: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  ngOnChanges(ch: SimpleChanges) {
    if (this.offerId === this.shown) return;   // ответ автосохранения меняет meta-объект, документ тот же
    this.shown = this.offerId;
    this.reset();
    if (this.meta.kind === 'PDF') this.loadPages(Math.min(this.meta.pages || 0, MAX_PAGES));
    else if (this.meta.kind === 'IMAGE') this.loadImage();
    else if (this.meta.kind === 'WORD' || this.meta.kind === 'EXCEL') this.loadTables();
  }

  ngOnDestroy() { this.reset(); }

  private loadTables() {
    const id = this.offerId;
    this.loading = true;
    this.sub = this.api.getClientOfferFileView(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { if (id !== this.offerId) return; this.loading = false; this.tables = v.tables; this.cdr.markForCheck(); },
      error: e => { this.loading = false; this.loadError = 'Документ не загрузился: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  private loadPages(count: number) {
    if (!count) return;
    const id = this.offerId;
    this.pagesLoading = true;
    const next = (n: number) => {
      if (n > count || id !== this.offerId) { this.pagesLoading = false; this.cdr.markForCheck(); return; }
      this.sub = this.api.clientOfferFilePage(id, n).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: b => {
          if (id !== this.offerId) return;
          const u = URL.createObjectURL(b);
          this.urls.push(u);
          this.pageUrls = [...this.pageUrls, u];
          this.cdr.markForCheck();
          next(n + 1);
        },
        error: () => { this.pagesLoading = false; this.cdr.markForCheck(); },
      });
    };
    next(1);
  }

  private loadImage() {
    const id = this.offerId;
    this.sub = this.api.downloadClientOfferFile(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        if (!r.body || id !== this.offerId) return;
        const u = URL.createObjectURL(r.body);
        this.urls.push(u);
        this.imageUrl = u;
        this.cdr.markForCheck();
      },
      error: e => { this.loadError = 'Картинка не загрузилась: ' + errorText(e); this.cdr.markForCheck(); },
    });
  }

  download() {
    this.api.downloadClientOfferFile(this.offerId).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const f = responseFile(r, this.meta.name); if (f) saveBlob(f, f.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  private reset() {
    this.sub?.unsubscribe();
    for (const u of this.urls) URL.revokeObjectURL(u);
    this.urls = [];
    this.pageUrls = [];
    this.imageUrl = null;
    this.zoom = null;
    this.tables = [];
    this.loading = false;
    this.pagesLoading = false;
    this.loadError = '';
  }
}
