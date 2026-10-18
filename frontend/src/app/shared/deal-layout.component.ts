import { Component, Input, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';

/** Раскладка сделки (спека 2026-10-18 §3.3): окно ≥ 1200 px — две колонки, правая сворачивается; уже — вкладки. */
@Component({
  selector: 'app-deal-layout',
  standalone: true,
  imports: [NgIf],
  template: `
    <div class="dl-tabs" role="tablist">
      <button type="button" role="tab" [class.on]="tab === 'main'" [attr.aria-selected]="tab === 'main'" (click)="tab = 'main'">{{ mainLabel }}</button>
      <button type="button" role="tab" [class.on]="tab === 'side'" [attr.aria-selected]="tab === 'side'" (click)="tab = 'side'">{{ sideLabel }}</button>
    </div>
    <div class="dl" [class.collapsed]="collapsed">
      <div class="dl-main" [class.off]="tab !== 'main'"><ng-content select="[dealMain]"></ng-content></div>
      <aside class="dl-side" [class.off]="tab !== 'side'" [attr.aria-label]="sideTitle">
        <div class="dl-side-head">
          <span class="dl-side-title">{{ sideTitle }}</span>
          <button type="button" class="dl-fold" (click)="setCollapsed(true)">свернуть ›</button>
        </div>
        <ng-content select="[dealSide]"></ng-content>
      </aside>
      <button type="button" class="dl-unfold" *ngIf="collapsed" (click)="setCollapsed(false)">{{ sideLabel }} ‹</button>
    </div>
  `,
  styles: [`
    :host { display: block; }
    .dl-tabs { display: none; }
    .dl { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
    .dl.collapsed { grid-template-columns: minmax(0, 1fr) 36px; }
    .dl.collapsed .dl-side { display: none; }
    .dl-main { min-width: 0; display: flex; flex-direction: column; gap: 12px; }
    .dl-side { position: sticky; top: 12px; height: calc(100vh - 24px); display: flex; flex-direction: column; min-height: 0;
               background: var(--surface); border: 1px solid var(--border); border-radius: 10px; overflow: hidden; }
    .dl-side-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 8px 12px;
                    border-bottom: 1px solid var(--border); }
    .dl-side-title { font-weight: 600; color: var(--text); }
    .dl-fold { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 13px; padding: 4px 0; }
    .dl-unfold { position: sticky; top: 12px; writing-mode: vertical-rl; min-height: 140px; padding: 10px 6px; cursor: pointer;
                 background: var(--surface); color: var(--accent); border: 1px solid var(--border); border-radius: 10px; }
    @media (max-width: 1199px) {
      .dl-tabs { display: flex; gap: 4px; margin-bottom: 8px; border-bottom: 1px solid var(--border); }
      .dl-tabs button { flex: 1; min-height: 40px; background: none; border: none; border-bottom: 2px solid transparent;
                        color: var(--text-muted); cursor: pointer; font-size: 14px; }
      .dl-tabs button.on { color: var(--text); border-bottom-color: var(--accent); font-weight: 600; }
      .dl, .dl.collapsed { grid-template-columns: minmax(0, 1fr); }
      .dl .dl-side, .dl.collapsed .dl-side { display: flex; position: static; height: 70vh; }
      .dl-fold, .dl-unfold { display: none; }
      .off { display: none !important; }
    }
  `],
})
export class DealLayoutComponent implements OnInit {
  @Input() mainLabel = 'Сделка';
  @Input() sideLabel = 'Переписка';
  @Input() sideTitle = 'Переписка и история';
  /** Ключ «свёрнуто» в localStorage — свой у заявки и у тендера. */
  @Input({ required: true }) storageKey!: string;
  tab: 'main' | 'side' = 'main';
  collapsed = false;

  ngOnInit() {
    try { this.collapsed = localStorage.getItem(this.storageKey) === 'collapsed'; } catch { /* приватный режим — развёрнуто */ }
  }

  setCollapsed(v: boolean) {
    this.collapsed = v;
    try { localStorage.setItem(this.storageKey, v ? 'collapsed' : 'open'); } catch { /* не запомнилось — не страшно */ }
  }
}
