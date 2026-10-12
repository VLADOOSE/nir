import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, HostListener, Input, OnDestroy, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../services/api.service';
import { SiteProduct } from './private-request';

/**
 * «Найти на westmed.kz или вписать своё…» (спека 2026-10-12 §4): от 2 символов, через 300 мс — до 10 товаров сайта;
 * выбор — позиция с брендом и ссылкой, Enter без выбора — позиция как написано. Сайт не ответил — подсказка, текст
 * добавляется всё равно. siteEnabled=false (РФ) — только свой текст.
 */
@Component({
  selector: 'app-site-product-search',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <div class="sps" (click)="$event.stopPropagation()">
      <input type="text" [value]="text" maxlength="255" autocomplete="off"
             [placeholder]="siteEnabled ? 'Найти на westmed.kz или вписать своё…' : 'Вписать позицию…'"
             role="combobox" [attr.aria-expanded]="open" aria-label="Добавить позицию"
             (input)="onInput($event)" (keydown)="onKey($event)" (focus)="open = !!text.trim()" />
      <ul class="drop" *ngIf="open && text.trim()" role="listbox">
        <li class="hint" *ngIf="loading">Ищу на сайте…</li>
        <li class="hint warn" *ngIf="error">{{ error }}</li>
        <li *ngFor="let p of results; let i = index" role="option" [class.on]="i === active" [attr.aria-selected]="i === active"
            (mousedown)="$event.preventDefault(); pick(p)">
          <span class="nm">{{ p.name }}</span>
          <span class="meta">{{ p.brand || 'бренд не указан' }}<ng-container *ngIf="p.sku"> · {{ p.sku }}</ng-container></span>
        </li>
        <li class="own" role="option" [class.on]="active === results.length" [attr.aria-selected]="active === results.length"
            (mousedown)="$event.preventDefault(); addOwn()">＋ Добавить «{{ text.trim() }}» как есть</li>
      </ul>
    </div>
  `,
  styles: [`
    .sps { position: relative; margin-top: 10px; }
    input { width: 100%; box-sizing: border-box; padding: 9px 12px; border: 1px dashed var(--border); border-radius: 8px;
            background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    input:focus { border-style: solid; border-color: var(--accent); outline: none; }
    .drop { position: absolute; z-index: 25; left: 0; right: 0; top: 100%; margin: 4px 0 0; padding: 4px 0; list-style: none;
            background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
            max-height: 320px; overflow-y: auto; }
    .drop li { padding: 8px 12px; cursor: pointer; display: flex; flex-direction: column; gap: 2px; }
    .drop li.on, .drop li:hover { background: var(--surface-2); }
    .drop li.hint { cursor: default; font-size: 12px; color: var(--text-muted); }
    .drop li.hint.warn { color: var(--warn-text); }
    .drop li.hint:hover { background: none; }
    .nm { font-size: 14px; color: var(--text); overflow-wrap: anywhere; }
    .meta { font-size: 12px; color: var(--text-muted); }
    .own { font-size: 13px; color: var(--accent); border-top: 1px solid var(--border); }
    @media (max-width: 900px) {
      input { font-size: 16px; min-height: 44px; }
      .drop li { min-height: 40px; justify-content: center; }
    }
  `],
})
export class SiteProductSearchComponent implements OnDestroy {
  @Input() siteEnabled = true;
  @Output() pickProduct = new EventEmitter<SiteProduct>();
  @Output() addText = new EventEmitter<string>();

  text = '';
  results: SiteProduct[] = [];
  open = false;
  loading = false;
  error = '';
  active = -1;
  private timer: any = null;
  private sub?: Subscription;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  ngOnDestroy() {
    clearTimeout(this.timer);
  }

  @HostListener('document:click')
  closeDrop() {
    this.open = false;
  }

  onInput(e: Event) {
    this.text = (e.target as HTMLInputElement).value;
    this.active = -1;
    this.error = '';
    this.open = !!this.text.trim();
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
    this.results = [];
    const q = this.text.trim();
    if (!this.siteEnabled || q.length < 2) {
      this.loading = false;
      return;
    }
    this.loading = true;
    this.timer = setTimeout(() => this.search(q), 300);
  }

  private search(q: string) {
    this.sub = this.api.searchSiteProducts(q).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => {
        if (q !== this.text.trim()) return;   // ответ на устаревший запрос
        this.results = r || [];
        this.loading = false;
        this.cdr.markForCheck();
      },
      error: e => {
        this.loading = false;
        this.results = [];
        this.error = e?.error?.message || 'Сайт не отвечает — добавьте своим текстом';
        this.cdr.markForCheck();
      },
    });
  }

  onKey(e: KeyboardEvent) {
    const own = this.results.length;   // последний вариант — «как есть»
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.open = !!this.text.trim();
      this.active = Math.min(this.active + 1, own);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.active = Math.max(this.active - 1, -1);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (this.active >= 0 && this.active < own) this.pick(this.results[this.active]);
      else this.addOwn();
    } else if (e.key === 'Escape') {
      this.open = false;
    }
  }

  pick(p: SiteProduct) {
    this.pickProduct.emit(p);
    this.reset();
  }

  addOwn() {
    const t = this.text.trim();
    if (!t) return;
    this.addText.emit(t);
    this.reset();
  }

  private reset() {
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
    this.text = '';
    this.results = [];
    this.open = false;
    this.active = -1;
    this.loading = false;
    this.error = '';
    this.cdr.markForCheck();
  }
}
