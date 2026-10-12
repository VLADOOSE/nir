import { ChangeDetectorRef, Component, DestroyRef, Input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import type { PrView } from '../../shared/private-request';
import { OFFER_STATUS_LABELS, dateText, money } from '../../shared/client-offer';
import type { OfferStatus } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

/**
 * «КП клиенту» на странице заявки (спека 2026-10-12 §8): КП по заявке — номер, дата, статус, позиции, сумма — и
 * «Создать КП клиенту»: черновик из позиций, РУ и цен ответов, сразу открывается редактор.
 */
@Component({
  selector: 'app-pr-offers',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <section class="pr-offers">
      <div class="head">
        <h3>КП клиенту</h3>
        <button type="button" class="btn btn-primary" *ngIf="!readonly && view.lines.length" [disabled]="busy" (click)="create()">
          {{ busy ? 'Создаю…' : 'Создать КП клиенту' }}</button>
      </div>
      <p class="empty" *ngIf="!view.offers.length">
        КП по заявке ещё нет{{ view.lines.length ? '' : ' — сначала добавьте позиции' }}.</p>
      <a class="offer" *ngFor="let o of view.offers" [routerLink]="['/client-offers', o.id]">
        <span class="num">№ {{ o.number }} от {{ dateText(o.offerDate) }}</span>
        <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
        <span class="meta">{{ o.itemCount }} поз. · {{ money(o.totalAmount) }} {{ o.currency === 'RUB' ? '₽' : '₸' }}</span>
      </a>
    </section>
  `,
  styles: [`
    .pr-offers { display: flex; flex-direction: column; gap: 8px; margin-top: 20px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    h3 { margin: 0; }
    .empty { margin: 0; }
    .offer { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 10px 12px; border: 1px solid var(--border);
             border-radius: 10px; color: var(--text); text-decoration: none; background: var(--surface); }
    .offer:hover { border-color: var(--accent); }
    .num { font-weight: 600; }
    .meta { margin-left: auto; color: var(--text-muted); }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    @media (max-width: 900px) {
      .meta { margin-left: 0; width: 100%; }
      .offer { min-height: 40px; }
    }
  `],
})
export class PrOffersComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  busy = false;
  dateText = dateText;
  money = money;

  constructor(private api: ApiService, private router: Router, private notify: NotificationService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  statusLabel(s: string): string {
    return OFFER_STATUS_LABELS[s as OfferStatus] ?? s;
  }

  create() {
    if (this.busy) return;
    this.busy = true;
    this.api.createClientOfferFromRequest(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: o => { this.router.navigate(['/client-offers', o.id]); },
      error: e => { this.busy = false; this.notify.error('КП не создано: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
