import { ChangeDetectorRef, Component, EventEmitter, HostListener, Input, OnChanges, OnDestroy, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';

/**
 * Предпросмотр КП — страницы того же PDF, что получит клиент (PNG base64 одним ответом через HttpClient, §14).
 * Старые страницы остаются на экране, пока не придут новые; тап по странице — крупно, Esc — закрыть.
 * После каждого предпросмотра сообщает, тесная ли таблица (кегль уменьшен) — редактор подсказывает «Альбомная».
 */
@Component({
  selector: 'app-client-offer-preview',
  standalone: true,
  imports: [NgFor, NgIf],
  template: `
    <div class="pv-state" *ngIf="loading && !pages.length">Собираю предпросмотр…</div>
    <div class="error-banner" *ngIf="error">{{ error }} <button type="button" class="btn btn-line" (click)="load()">Повторить</button></div>
    <div class="pv-pages" [class.stale]="loading && pages.length">
      <button type="button" class="pv-page" *ngFor="let p of pages; let i = index" (click)="zoom = p" [attr.aria-label]="'Страница ' + (i + 1) + ' крупно'">
        <img [src]="p" [alt]="'Страница ' + (i + 1)" />
      </button>
    </div>
    <div class="pv-zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Страница крупно">
      <img [src]="zoom" alt="Страница крупно" />
    </div>
  `,
  styles: [`
    :host { display: block; }
    .pv-state { color: var(--text-muted); font-size: 13px; padding: 24px 0; text-align: center; }
    .pv-pages { display: flex; flex-direction: column; gap: 12px; transition: opacity .2s; }
    .pv-pages.stale { opacity: .55; }
    .pv-page { display: block; padding: 0; border: 1px solid var(--border); background: var(--surface); box-shadow: var(--shadow); cursor: zoom-in; }
    .pv-page img { display: block; width: 100%; height: auto; }
    /* вуаль модалки — самое частое из значений приложения (§16: не заводить пятое) */
    .pv-zoom { position: fixed; inset: 0; z-index: 60; overflow: auto; background: rgba(17, 24, 39, .5); display: flex; justify-content: center; align-items: flex-start;
               padding: calc(16px + env(safe-area-inset-top, 0px)) 16px calc(16px + env(safe-area-inset-bottom, 0px)); cursor: zoom-out; }
    .pv-zoom img { width: 100%; max-width: 1100px; height: auto; box-shadow: var(--shadow-lg); }
  `],
})
export class ClientOfferPreviewComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) offerId!: number;
  @Input() tick = 0;
  @Input() active = true;
  /** crowded из последнего пришедшего предпросмотра: таблица тесная, кегль уменьшен. */
  @Output() crowded = new EventEmitter<boolean>();

  pages: string[] = [];
  loading = false;
  error = '';
  zoom: string | null = null;
  private loadedKey = '';
  private timer: any = null;
  private sub: Subscription | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    // скрытый предпросмотр не собирается: отложенная загрузка отменяется, а при показе — догоняет последнюю версию
    if (!this.active) { clearTimeout(this.timer); return; }
    if (this.key() === this.loadedKey) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.load(), 250);
  }

  ngOnDestroy() {
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
  }

  @HostListener('document:keydown.escape')
  closeZoom() {
    if (this.zoom) { this.zoom = null; this.cdr.detectChanges(); }
  }

  load() {
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
    const key = this.key();
    this.loading = true;
    this.error = '';
    this.cdr.detectChanges();
    this.sub = this.api.getClientOfferPreview(this.offerId).subscribe({
      next: r => {
        this.pages = r.pages.map(p => 'data:image/png;base64,' + p);
        this.loading = false;
        this.loadedKey = key;
        this.crowded.emit(!!r.crowded);
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.error = 'Предпросмотр не удался'; this.cdr.detectChanges(); },
    });
  }

  private key(): string {
    return this.offerId + ':' + this.tick;
  }
}
