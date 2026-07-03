import {
  Component,
  EventEmitter,
  Input,
  Output,
  OnChanges,
  SimpleChanges,
  ChangeDetectorRef
} from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';

@Component({
  selector: 'app-bulk-price-modal',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule],
  template: `
    <div class="modal-overlay" *ngIf="tenderId != null" (click)="onOverlayClick($event)">
      <div class="modal-window" (click)="$event.stopPropagation()">
        <div class="modal-header">
          <h2>Запрос КП по тендеру</h2>
          <button class="btn-close" type="button" (click)="onClose()" aria-label="Закрыть">&times;</button>
        </div>

        <div class="modal-body">
          <div *ngIf="loading" class="loading">
            <div class="spinner"></div>
            <span>Загрузка...</span>
          </div>

          <ng-container *ngIf="!loading && preview">
            <!-- Лоты без подходящего оборудования -->
            <div class="alert alert-danger" *ngIf="preview.lotsWithoutMatch?.length > 0">
              <h3>Лоты без подходящего оборудования</h3>
              <ul>
                <li *ngFor="let lot of preview.lotsWithoutMatch">
                  Лот &#8470; {{ lot.lotNumber }} &mdash; {{ lot.equipName }}
                  <span class="muted" *ngIf="lot.quantity"> &times; {{ lot.quantity }}</span>
                </li>
              </ul>
              <p class="alert-hint">Добавьте подходящее оборудование в каталог или ослабьте требования лота.</p>
            </div>

            <!-- Лоты без дистрибьюторов -->
            <div class="alert alert-danger" *ngIf="preview.lotsWithoutDistributor?.length > 0">
              <h3>Лоты без дистрибьюторов</h3>
              <ul>
                <li *ngFor="let lot of preview.lotsWithoutDistributor">
                  Лот &#8470; {{ lot.lotNumber }} &mdash; {{ lot.equipName }}
                  <span class="muted" *ngIf="lot.quantity"> &times; {{ lot.quantity }}</span>
                </li>
              </ul>
              <p class="alert-hint">Настройте специализацию дистрибьюторов или добавьте нового с этим типом.</p>
            </div>

            <!-- Группы по дистрибьюторам -->
            <div *ngIf="preview.groups?.length === 0 && !preview.lotsWithoutMatch?.length && !preview.lotsWithoutDistributor?.length"
                 class="empty">
              Нет данных для отображения.
            </div>

            <div class="group-card" *ngFor="let group of preview.groups">
              <div class="group-header">
                <div class="group-title">
                  <strong>{{ group.distributor?.name }}</strong>
                  <span class="contact" *ngIf="group.distributor?.email"> &middot; {{ group.distributor.email }}</span>
                  <span class="contact" *ngIf="group.distributor?.phone"> &middot; {{ group.distributor.phone }}</span>
                </div>
                <div class="group-types" *ngIf="getDistributorTypes(group.distributor) as types">
                  <span class="type-badge" *ngFor="let t of types">{{ t }}</span>
                </div>
              </div>

              <div class="group-items">
                <div class="item-row" *ngFor="let item of group.items">
                  <label class="item-label">
                    <input type="checkbox"
                           [checked]="!!selected[key(group.distributor?.id, item.lot?.id, item.equipment?.id)]"
                           (change)="toggle(group.distributor?.id, item.lot?.id, item.equipment?.id, $event)" />
                    <span class="item-text">
                      <span class="lot-info">
                        Лот &#8470; {{ item.lot?.lotNumber }}
                        ({{ item.lot?.equipName }} &times; {{ item.lot?.quantity }})
                      </span>
                      <span class="equip-info">
                        {{ item.equipment?.name }}
                        <span class="muted" *ngIf="item.equipment?.manufact">({{ item.equipment.manufact }})</span>
                      </span>
                    </span>
                  </label>
                </div>
              </div>

              <div class="group-actions">
                <button class="btn btn-send"
                        *ngIf="!sentGroupIds.has(group.distributor?.id)"
                        [disabled]="sendingGroupIds.has(group.distributor?.id)"
                        (click)="onSendGroup(group)">
                  {{ sendingGroupIds.has(group.distributor?.id) ? 'Отправка...' : 'Отправить КП' }}
                </button>
                <span class="sent-mark" *ngIf="sentGroupIds.has(group.distributor?.id)">
                  &#10003; Отправлено
                </span>
              </div>
            </div>
          </ng-container>

          <div *ngIf="!loading && !preview && loadError" class="alert alert-danger">
            <h3>Ошибка загрузки</h3>
            <p>{{ loadError }}</p>
          </div>
        </div>

        <div class="modal-footer">
          <button class="btn btn-cancel" type="button" (click)="onClose()">Закрыть</button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .modal-overlay {
      position: fixed; inset: 0; background: rgba(15, 23, 42, 0.55);
      display: flex; align-items: center; justify-content: center;
      z-index: 2000; padding: 16px;
    }
    .modal-window {
      background: #fff; width: 1000px; max-width: 95vw; max-height: 90vh;
      border-radius: 10px; display: flex; flex-direction: column;
      box-shadow: 0 20px 60px rgba(0, 0, 0, 0.35);
      overflow: hidden;
    }
    .modal-header {
      display: flex; align-items: center; justify-content: space-between;
      padding: 18px 24px; border-bottom: 1px solid #e5e7eb;
    }
    .modal-header h2 { margin: 0; font-size: 18px; color: #111827; }
    .btn-close {
      background: transparent; border: none; font-size: 28px; line-height: 1;
      color: #6b7280; cursor: pointer; padding: 0 4px;
    }
    .btn-close:hover { color: #ef4444; }

    .modal-body { padding: 20px 24px; overflow-y: auto; flex: 1; }

    .modal-footer {
      padding: 12px 24px; border-top: 1px solid #e5e7eb;
      display: flex; justify-content: flex-end; gap: 8px;
    }

    .loading {
      display: flex; align-items: center; justify-content: center; gap: 12px;
      padding: 60px 0; color: #6b7280; font-size: 14px;
    }
    .spinner {
      width: 28px; height: 28px; border: 3px solid #e5e7eb;
      border-top-color: #1a56db; border-radius: 50%;
      animation: spin 0.8s linear infinite;
    }
    @keyframes spin { to { transform: rotate(360deg); } }

    .empty { text-align: center; color: #9ca3af; padding: 40px 0; font-size: 14px; }

    .alert {
      border-radius: 8px; padding: 14px 18px; margin-bottom: 16px;
      border: 1px solid transparent;
    }
    .alert-danger {
      background: #fef2f2; border-color: #fecaca; color: #991b1b;
    }
    .alert h3 { margin: 0 0 8px; font-size: 15px; color: #ef4444; }
    .alert ul { margin: 0 0 8px; padding-left: 20px; font-size: 14px; }
    .alert li { margin-bottom: 4px; }
    .alert-hint { margin: 6px 0 0; font-size: 13px; color: #7f1d1d; font-style: italic; }
    .muted { color: #6b7280; font-weight: normal; }

    .group-card {
      border: 1px solid #e5e7eb; border-radius: 8px; padding: 16px 18px;
      margin-bottom: 14px; background: #fafbfc;
    }
    .group-header {
      display: flex; flex-direction: column; align-items: flex-start;
      gap: 8px; margin-bottom: 12px;
    }
    .group-title { font-size: 15px; color: #111827; line-height: 1.4; }
    .group-title strong { color: #1a56db; }
    .contact { color: #6b7280; font-size: 13px; }
    .group-types { display: flex; gap: 6px; flex-wrap: wrap; }
    .type-badge {
      background: #e0e7ff; color: #3730a3; padding: 2px 10px;
      border-radius: 10px; font-size: 12px; font-weight: 500;
    }

    .group-items { background: #fff; border-radius: 6px; padding: 4px 0; }
    .item-row {
      display: flex; align-items: center; justify-content: space-between;
      padding: 10px 12px; border-bottom: 1px solid #f3f4f6; gap: 12px;
    }
    .item-row:last-child { border-bottom: none; }
    .item-row.warn { background: #fffbeb; }
    .item-label {
      display: flex; align-items: center; gap: 10px; cursor: pointer; flex: 1;
      font-size: 14px; color: #111827; margin: 0;
    }
    .item-label input[type="checkbox"] { cursor: pointer; width: 16px; height: 16px; }
    .item-text { display: flex; flex-direction: column; gap: 2px; }
    .lot-info { font-weight: 500; color: #1a56db; font-size: 13px; }
    .equip-info { font-size: 14px; color: #374151; }
    .item-price {
      display: flex; flex-direction: column; align-items: flex-end; gap: 2px;
      white-space: nowrap;
    }
    .price-value { font-weight: 600; color: #111827; font-size: 14px; }
    .warn-icon { color: #ef4444; font-size: 12px; }

    .group-actions {
      display: flex; align-items: center; justify-content: flex-end;
      gap: 12px; margin-top: 12px;
    }
    .sent-mark { color: #059669; font-weight: 600; font-size: 14px; }

    .btn {
      padding: 8px 18px; border: none; border-radius: 5px; cursor: pointer;
      font-size: 13px; font-weight: 500;
    }
    .btn:disabled { opacity: 0.5; cursor: not-allowed; }
    .btn-send { background: #1a56db; color: #fff; }
    .btn-send:hover:not(:disabled) { background: #1e429f; }
    .btn-cancel { background: #e5e7eb; color: #374151; }
    .btn-cancel:hover { background: #d1d5db; }
  `]
})
export class BulkPriceModalComponent implements OnChanges {
  @Input() tenderId: number | null = null;
  @Output() close = new EventEmitter<void>();

  preview: any = null;
  loading = false;
  loadError: string | null = null;

  selected: Record<string, boolean> = {};
  sentGroupIds = new Set<number>();
  sendingGroupIds = new Set<number>();

  constructor(
    private api: ApiService,
    private notify: NotificationService,
    private cdr: ChangeDetectorRef
  ) {}

  ngOnChanges(changes: SimpleChanges): void {
    if (changes['tenderId']) {
      const id = changes['tenderId'].currentValue;
      if (id != null) {
        this.resetState();
        this.loadPreview(id);
      } else {
        this.resetState();
      }
    }
  }

  private resetState(): void {
    this.preview = null;
    this.loading = false;
    this.loadError = null;
    this.selected = {};
    this.sentGroupIds = new Set<number>();
    this.sendingGroupIds = new Set<number>();
  }

  private loadPreview(tenderId: number): void {
    this.loading = true;
    this.loadError = null;
    this.cdr.detectChanges();

    this.api.bulkPricePreview(tenderId).subscribe({
      next: (data: any) => {
        this.preview = data || { groups: [], lotsWithoutMatch: [], lotsWithoutDistributor: [] };
        this.preselectItems();
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: (err: any) => {
        this.loading = false;
        this.loadError = err?.error?.message || err?.message || 'Не удалось загрузить данные';
        this.cdr.detectChanges();
      }
    });
  }

  private preselectItems(): void {
    this.selected = {};
    const groups = this.preview?.groups || [];
    for (const group of groups) {
      const distId = group?.distributor?.id;
      if (distId == null) continue;
      const items = group?.items || [];
      for (const item of items) {
        const k = this.key(distId, item?.lot?.id, item?.equipment?.id);
        this.selected[k] = true;
      }
    }
  }

  key(distId: any, lotId: any, eqId: any): string {
    return `${distId}|${lotId}|${eqId}`;
  }

  toggle(distId: any, lotId: any, eqId: any, event: Event): void {
    const checked = (event.target as HTMLInputElement).checked;
    const k = this.key(distId, lotId, eqId);
    if (checked) {
      this.selected[k] = true;
    } else {
      delete this.selected[k];
    }
  }

  getDistributorTypes(distributor: any): string[] | null {
    if (!distributor) return null;
    const candidates = [
      distributor.types,
      distributor.equipmentTypes,
      distributor.specializations,
      distributor.equipTypes
    ];
    for (const c of candidates) {
      if (Array.isArray(c) && c.length > 0) {
        return c
          .map((t: any) => (typeof t === 'string' ? t : t?.name || t?.title || ''))
          .filter((s: string) => !!s);
      }
    }
    return null;
  }

  formatPrice(n: any): string {
    if (n == null || n === '') return '0';
    return Number(n).toLocaleString('ru-RU');
  }

  onSendGroup(group: any): void {
    if (!this.tenderId) return;
    const distId = group?.distributor?.id;
    if (distId == null) {
      this.notify.error('Не указан дистрибьютор');
      return;
    }
    if (this.sentGroupIds.has(distId)) return;

    const items: any[] = [];
    for (const item of (group?.items || [])) {
      const k = this.key(distId, item?.lot?.id, item?.equipment?.id);
      if (this.selected[k]) {
        items.push({
          tenderLotId: item?.lot?.id,
          medEquipmentId: item?.equipment?.id,
          requestedQuantity: item?.lot?.quantity ?? 1
        });
      }
    }

    if (items.length === 0) {
      this.notify.error('Не выбрано ни одной позиции');
      return;
    }

    this.sendingGroupIds.add(distId);
    this.cdr.detectChanges();

    this.api.sendPriceRequests({ tenderId: this.tenderId, distributorIds: [distId], items }).subscribe({
      next: (results: any[]) => {
        this.sendingGroupIds.delete(distId);
        this.sentGroupIds.add(distId);
        const r = (results || [])[0];
        if (r && !r.emailSent) {
          this.notify.error(`КП создано (${items.length} поз.), но письмо «${group?.distributor?.name || ''}» не ушло${r.reason === 'NO_EMAIL' ? ' — нет email' : ' (ошибка SMTP)'}`);
        } else {
          this.notify.success(`КП отправлено: ${group?.distributor?.name || 'дистрибьютор'} (${items.length} поз.)`);
        }
        this.cdr.detectChanges();
      },
      error: (err: any) => {
        this.sendingGroupIds.delete(distId);
        const msg = err?.error?.message || err?.message || 'Не удалось отправить КП';
        this.notify.error('Ошибка отправки: ' + msg);
        this.cdr.detectChanges();
      }
    });
  }

  onOverlayClick(_event: Event): void {
    this.onClose();
  }

  onClose(): void {
    this.close.emit();
  }
}
