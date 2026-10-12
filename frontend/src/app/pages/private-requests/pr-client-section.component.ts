import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { PrField, PrView, SaveState } from '../../shared/private-request';
import { errorText, retriable } from '../../shared/error-text';

interface FacilityOption { id: number; name: string; }

/**
 * Клиент заявки (спека 2026-10-12 §5.1): из «Учреждений» (поиск в поле), своим текстом («Частная клиника, Уральск» —
 * в справочник только по «Сохранить в Учреждения») или пусто. Контакт, «Куда поставка», примечание — отдельными
 * полями. Каждое поле сохраняется по выходу из него или Enter; набираемое поле ответом сервера не затирается.
 */
@Component({
  selector: 'app-pr-client-section',
  standalone: true,
  imports: [NgIf, NgFor],
  template: `
    <section class="card">
      <h3>Клиент</h3>
      <div class="client-row">
        <div class="picker" (click)="$event.stopPropagation()">
          <input type="text" [value]="clientText" [disabled]="readonly" maxlength="500" autocomplete="off"
                 placeholder="Найти в «Учреждениях» или вписать: «Частная клиника, Уральск»"
                 role="combobox" [attr.aria-expanded]="pickerOpen" aria-label="Клиент"
                 (focus)="onClientFocus()" (input)="onClientInput($event)" (keydown)="onClientKey($event)"
                 (blur)="onClientBlur()" />
          <ul class="options" *ngIf="pickerOpen && options.length" role="listbox">
            <li *ngFor="let f of options; let i = index" role="option" [class.on]="i === active"
                [attr.aria-selected]="i === active" (mousedown)="$event.preventDefault(); pick(f)">{{ f.name }}</li>
          </ul>
        </div>
        <button type="button" class="btn btn-line" *ngIf="view.customerName && !readonly" [disabled]="busy"
                (click)="saveToDirectory()">Сохранить в Учреждения</button>
      </div>
      <p class="hint" *ngIf="!view.facilityId && !view.customerName">Клиент не указан — можно оставить так.</p>

      <div class="grid3">
        <label>Контактное лицо
          <input [value]="view.contactName || ''" [disabled]="readonly" maxlength="255"
                 (blur)="commit('CONTACT_NAME', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Телефон
          <input [value]="view.contactPhone || ''" [disabled]="readonly" maxlength="50" inputmode="tel"
                 (blur)="commit('CONTACT_PHONE', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Почта
          <input type="email" [value]="view.contactEmail || ''" [disabled]="readonly" maxlength="255"
                 (blur)="commit('CONTACT_EMAIL', $event)" (keydown.enter)="blurTarget($event)" /></label>
      </div>
      <div class="grid2">
        <label>Куда поставка
          <input [value]="view.deliveryAddress || ''" [disabled]="readonly" maxlength="2000" placeholder="Город, учреждение, адрес"
                 (blur)="commit('DELIVERY_ADDRESS', $event)" (keydown.enter)="blurTarget($event)" /></label>
        <label>Примечание
          <textarea rows="2" [value]="view.note || ''" [disabled]="readonly" maxlength="10000"
                    placeholder="Текст клиента, сроки, условия" (blur)="commit('NOTE', $event)"></textarea></label>
      </div>
    </section>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    h3 { margin: 0 0 10px; font-size: 15px; }
    .client-row { display: flex; gap: 8px; align-items: flex-start; flex-wrap: wrap; }
    .picker { position: relative; flex: 1; min-width: 240px; }
    .picker input { width: 100%; box-sizing: border-box; }
    .options { position: absolute; z-index: 20; left: 0; right: 0; top: 100%; margin: 4px 0 0; padding: 4px 0; list-style: none;
               background: var(--surface); border: 1px solid var(--border); border-radius: 8px; box-shadow: var(--shadow);
               max-height: 260px; overflow-y: auto; }
    .options li { padding: 8px 12px; cursor: pointer; font-size: 14px; color: var(--text); }
    .options li.on, .options li:hover { background: var(--surface-2); }
    .hint { margin: 6px 0 0; font-size: 12px; color: var(--text-muted); }
    .grid3 { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px 12px; margin-top: 12px; }
    .grid2 { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px 12px; margin-top: 10px; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    input, textarea { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface);
                      color: var(--text); font: inherit; font-size: 14px; }
    input:disabled, textarea:disabled { background: var(--surface-2); cursor: not-allowed; }
    textarea { resize: vertical; }
    @media (max-width: 900px) {
      .grid3, .grid2 { grid-template-columns: 1fr; }
      .picker { min-width: 0; flex-basis: 100%; }
      input, textarea { font-size: 16px; min-height: 40px; }
      .client-row .btn { min-height: 40px; }
    }
  `],
})
export class PrClientSectionComponent implements OnChanges {
  @Input() view!: PrView;
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  @Output() saveState = new EventEmitter<SaveState>();

  facilities: FacilityOption[] = [];
  options: FacilityOption[] = [];
  clientText = '';
  pickerOpen = false;
  active = -1;
  busy = false;
  private clientFocused = false;
  private picked = false;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {
    this.loadFacilities();
  }

  ngOnChanges(ch: SimpleChanges) {
    // пока оператор печатает в поле клиента, ответ сохранения другого поля не должен переписать набранное
    if (ch['view'] && !this.clientFocused) this.clientText = this.label();
  }

  private label(): string {
    return this.view?.facilityName || this.view?.customerName || '';
  }

  private loadFacilities() {
    this.api.getFacilities().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => { this.facilities = (list || []).map((f: any) => ({ id: f.id, name: f.name })); this.cdr.markForCheck(); },
      error: () => { this.facilities = []; },
    });
  }

  onClientFocus() {
    this.clientFocused = true;
    this.filter();
    this.pickerOpen = !this.readonly;
  }

  onClientInput(e: Event) {
    this.clientText = (e.target as HTMLInputElement).value;
    this.picked = false;
    this.filter();
    this.pickerOpen = true;
  }

  private filter() {
    const q = this.clientText.trim().toLowerCase();
    const list = q ? this.facilities.filter(f => f.name.toLowerCase().includes(q)) : this.facilities;
    this.options = list.slice(0, 8);
    this.active = -1;
  }

  onClientKey(e: KeyboardEvent) {
    if (e.key === 'ArrowDown') {
      e.preventDefault();
      this.pickerOpen = true;
      this.active = Math.min(this.active + 1, this.options.length - 1);
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      this.active = Math.max(this.active - 1, -1);
    } else if (e.key === 'Enter') {
      e.preventDefault();
      if (this.active >= 0) this.pick(this.options[this.active]);
      (e.target as HTMLInputElement).blur();
    } else if (e.key === 'Escape') {
      this.pickerOpen = false;
    }
  }

  pick(f: FacilityOption) {
    this.picked = true;
    this.pickerOpen = false;
    this.clientText = f.name;
    if (f.id !== this.view.facilityId) this.save('FACILITY', String(f.id));
  }

  onClientBlur() {
    this.clientFocused = false;
    this.pickerOpen = false;
    if (this.picked) {
      this.picked = false;
      return;
    }
    const text = this.clientText.trim();
    if (text === this.label().trim()) return;
    if (!text) {
      this.save(this.view.facilityId ? 'FACILITY' : 'CUSTOMER_NAME', null);
      return;
    }
    // вписанное слово в слово совпало с учреждением — это оно, а не текст
    const exact = this.facilities.find(f => f.name.trim().toLowerCase() === text.toLowerCase());
    if (exact) this.save('FACILITY', String(exact.id));
    else this.save('CUSTOMER_NAME', text);
  }

  commit(field: PrField, e: Event) {
    const value = (e.target as HTMLInputElement | HTMLTextAreaElement).value.trim();
    if (value === (this.current(field) || '').trim()) return;
    this.save(field, value || null);
  }

  blurTarget(e: Event) {
    (e.target as HTMLElement).blur();
  }

  private current(field: PrField): string | null {
    switch (field) {
      case 'CONTACT_NAME': return this.view.contactName;
      case 'CONTACT_PHONE': return this.view.contactPhone;
      case 'CONTACT_EMAIL': return this.view.contactEmail;
      case 'DELIVERY_ADDRESS': return this.view.deliveryAddress;
      case 'NOTE': return this.view.note;
      case 'CUSTOMER_NAME': return this.view.customerName;
      case 'FACILITY': return this.view.facilityId == null ? null : String(this.view.facilityId);
      default: return null;
    }
  }

  private save(field: PrField, value: string | null) {
    this.saveState.emit({ kind: 'saving' });
    this.api.updatePrivateRequestField(this.view.id, field, value).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => { this.saveState.emit({ kind: 'saved' }); this.viewChange.emit(v); },
      error: e => {
        this.saveState.emit({ kind: 'error', message: errorText(e), retry: retriable(e) ? () => this.save(field, value) : undefined });
        if (field === 'FACILITY' || field === 'CUSTOMER_NAME') this.clientText = this.label();
        this.cdr.markForCheck();
      },
    });
  }

  saveToDirectory() {
    this.busy = true;
    this.api.savePrivateRequestClient(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: v => {
        this.busy = false;
        this.notify.success('Клиент сохранён в «Учреждения»');
        this.viewChange.emit(v);
        this.loadFacilities();
      },
      error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
