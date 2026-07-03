import { Component, Input, Output, EventEmitter, OnChanges, SimpleChanges, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgIf, NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketMoneyPipe } from '../../pipes/market-money.pipe';

@Component({
  selector: 'app-private-request-card',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, MarketMoneyPipe],
  template: `
    <div *ngIf="requestId !== null" class="overlay" (click)="onClose()">
      <aside class="sidebar" (click)="$event.stopPropagation()">
        <header class="head">
          <div class="title-block">
            <h2 class="title">Заявка {{ request?.number || '' }}</h2>
            <div class="subtitle">
              <span>{{ request?.client?.name || '—' }}</span>
              <span class="dot" *ngIf="request?.status">·</span>
              <span class="type-pill" *ngIf="request?.status">{{ request?.status }}</span>
            </div>
          </div>
          <button class="close-btn" type="button" (click)="onClose()" aria-label="Закрыть">&times;</button>
        </header>

        <div class="body">
          <div *ngIf="loading" class="loading">Загрузка…</div>

          <!-- Строки заявки + реестр-статус -->
          <section class="section" *ngIf="!loading">
            <div class="section-head">
              <h3 class="section-title">Строки заявки</h3>
              <button class="btn-line" type="button" *ngIf="!editMode" (click)="startEdit()">✎ Редактировать</button>
            </div>
            <table *ngIf="!editMode && lines.length">
              <thead>
                <tr>
                  <th class="w-40"><input type="checkbox" [checked]="allSelected()" (change)="toggleAll($any($event.target).checked)" /></th>
                  <th>Наименование/модель</th>
                  <th class="w-140">Бренд</th>
                  <th class="w-60">Кол-во</th>
                  <th>Реестр НЦЭЛС</th>
                </tr>
              </thead>
              <tbody>
                <tr *ngFor="let l of lines">
                  <td class="w-40"><input type="checkbox" [(ngModel)]="l._selected" /></td>
                  <td class="name-cell">
                    {{ l.name }}
                    <div class="requested-at" *ngIf="requestedDistributorsFor(l.lotId).length">Запрошен у: {{ requestedDistributorsFor(l.lotId).join(', ') }}</div>
                  </td>
                  <td>{{ l.manufact || '—' }}</td>
                  <td>{{ l.quantity ?? '—' }}</td>
                  <td>
                    <div class="reg-block" *ngIf="l.registrationStatus === 'REGISTERED'; else notFound">
                      <span class="badge b-REGISTERED">Зарегистрировано</span>
                      <span class="vat">НДС-льгота</span>
                      <div class="reg-meta" *ngIf="l.topCandidate as c">
                        № РУ {{ c.regNumber }}<span *ngIf="c.producer"> · {{ c.producer }}</span><span *ngIf="c.country"> · {{ c.country }}</span>
                      </div>
                    </div>
                    <ng-template #notFound>
                      <span class="badge b-NOT_FOUND">Не найдено в реестре</span>
                    </ng-template>
                  </td>
                </tr>
              </tbody>
            </table>
            <div class="empty" *ngIf="!editMode && !lines.length">В заявке нет строк</div>

            <!-- режим редактирования строк -->
            <div *ngIf="editMode" class="edit-block">
              <table class="edit-grid">
                <thead>
                  <tr><th>Наименование/модель</th><th class="w-160">Бренд</th><th class="w-80">Кол-во</th><th class="w-40"></th></tr>
                </thead>
                <tbody>
                  <tr *ngFor="let l of editLines; let i = index">
                    <td><input [(ngModel)]="l.name" [ngModelOptions]="{standalone:true}" placeholder="наименование/модель" /></td>
                    <td><input [(ngModel)]="l.manufact" [ngModelOptions]="{standalone:true}" placeholder="бренд" /></td>
                    <td><input type="number" min="1" [(ngModel)]="l.quantity" [ngModelOptions]="{standalone:true}" class="qty" /></td>
                    <td><button type="button" class="x-row" (click)="removeEditLine(i)" title="удалить строку">×</button></td>
                  </tr>
                </tbody>
              </table>
              <button type="button" class="btn-line add-line" (click)="addEditLine()">+ строка</button>
              <div class="edit-err" *ngIf="editError">{{ editError }}</div>
              <div class="edit-actions">
                <button class="btn-primary" type="button" [disabled]="saving" (click)="saveEdit()">Сохранить</button>
                <button class="btn-line" type="button" (click)="cancelEdit()">Отмена</button>
              </div>
            </div>
          </section>

          <!-- Подобрать поставщиков (по брендам) -->
          <section class="section" *ngIf="!loading && sourcing">
            <h3 class="section-title">Подобрать поставщиков</h3>
            <div class="empty" *ngIf="!sourcing.groups?.length">Нет поставщиков с подходящими брендами. Добавьте бренды в карточках поставщиков или запросите вручную ниже.</div>
            <div class="src-group" *ngFor="let g of sourcing.groups">
              <div class="src-head">
                <span class="src-dist">{{ g.distributor?.name }}</span>
                <button class="btn-primary" type="button" (click)="requestGroup(g)" [disabled]="sendingGroupId === g.distributor?.id">
                  Запросить КП ({{ g.lines?.length || 0 }})
                </button>
              </div>
              <ul class="src-lines">
                <li *ngFor="let l of g.lines">{{ l.name }} <span class="src-brand">· {{ l.manufact }}</span> × {{ l.quantity }}</li>
              </ul>
            </div>
            <div class="src-unmatched" *ngIf="sourcing.unmatchedLines?.length">
              <div class="src-unmatched-title">Без поставщика ({{ sourcing.unmatchedLines.length }}):</div>
              <ul class="src-lines">
                <li *ngFor="let l of sourcing.unmatchedLines">{{ l.name }} <span class="src-brand">· {{ l.manufact || 'бренд не указан' }}</span></li>
              </ul>
            </div>
          </section>

          <!-- Запросить КП -->
          <section class="section" *ngIf="!loading">
            <h3 class="section-title">Запросить КП</h3>
            <div class="ask-row">
              <select [(ngModel)]="selectedDistributorId" class="dist-select">
                <option [ngValue]="null" disabled>— выберите поставщика —</option>
                <option *ngFor="let d of distributors" [ngValue]="d.id">{{ d.name }}</option>
              </select>
              <button class="btn-primary" type="button" (click)="requestPrice()" [disabled]="!selectedDistributorId || !selectedCount() || sending">
                Запросить КП по выбранным ({{ selectedCount() }})
              </button>
            </div>
            <div class="hint">Отметьте строки и поставщика, затем запросите КП. Можно несколько раундов разным поставщикам.</div>
          </section>

          <!-- Существующие КП / ответы -->
          <section class="section" *ngIf="!loading">
            <h3 class="section-title">Существующие КП</h3>
            <div class="empty" *ngIf="!priceRequests.length">КП по этой заявке ещё не запрашивались</div>
            <div class="pr-card" *ngFor="let pr of priceRequests">
              <div class="pr-head">
                <span class="pr-dist">{{ pr.distributor?.name || '—' }}</span>
                <span class="badge" [class]="'b-status'">{{ pr.status }}</span>
              </div>
              <table class="pr-items">
                <thead>
                  <tr><th>Позиция</th><th class="w-60">Кол-во</th><th class="w-160">Цена ответа</th><th>Примечание</th></tr>
                </thead>
                <tbody>
                  <tr *ngFor="let it of pr.items">
                    <td>{{ it.tenderLot?.equipName || it.medEquipment?.name || '—' }}</td>
                    <td>{{ it.requestedQuantity ?? '—' }}</td>
                    <td>
                      <input type="number" min="0" class="price-input" [(ngModel)]="it._editPrice" placeholder="0" />
                      <span class="cur-price" *ngIf="it.responsePrice != null">тек.: {{ it.responsePrice | money }}</span>
                    </td>
                    <td><input class="note-input" [(ngModel)]="it._editNote" placeholder="—" /></td>
                  </tr>
                </tbody>
              </table>
              <div class="pr-actions">
                <button class="btn-primary" type="button" (click)="saveResponses(pr)">Сохранить ответы</button>
              </div>
            </div>
          </section>
        </div>
      </aside>
    </div>
  `,
  styles: [`
    .overlay { position: fixed; inset: 0; background: rgba(17, 24, 39, 0.5); z-index: 1000; animation: fadeIn 0.15s ease-out; }
    @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
    .sidebar { position: fixed; top: 0; right: 0; bottom: 0; width: 720px; max-width: 100vw; background: #fff; box-shadow: -8px 0 30px rgba(0,0,0,0.18); display: flex; flex-direction: column; animation: slideIn 0.2s ease-out; }
    @keyframes slideIn { from { transform: translateX(20px); opacity: 0; } to { transform: translateX(0); opacity: 1; } }

    .head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; padding: 24px 32px 16px; border-bottom: 1px solid #e5e7eb; flex-shrink: 0; }
    .title-block { min-width: 0; flex: 1; }
    .title { margin: 0; font-size: 20px; line-height: 1.3; font-weight: 600; color: #111827; word-break: break-word; }
    .subtitle { margin-top: 6px; display: flex; align-items: center; gap: 6px; flex-wrap: wrap; font-size: 13px; color: #6b7280; }
    .dot { color: #d1d5db; }
    .type-pill { display: inline-block; padding: 2px 10px; background: #eef2ff; color: #3730a3; border-radius: 999px; font-size: 12px; font-weight: 500; }
    .close-btn { background: transparent; border: none; cursor: pointer; font-size: 28px; line-height: 1; color: #9ca3af; padding: 0 4px; border-radius: 4px; transition: color 0.15s, background 0.15s; }
    .close-btn:hover { color: #ef4444; background: #fef2f2; }

    .body { flex: 1; overflow-y: auto; padding: 24px 32px 32px; }
    .section { margin-bottom: 28px; }
    .section:last-child { margin-bottom: 0; }
    .section-title { margin: 0 0 12px; font-size: 13px; font-weight: 600; color: #6b7280; text-transform: uppercase; letter-spacing: 0.04em; }
    .section-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
    .section-head .section-title { margin: 0; }
    .btn-line { background: #fff; border: 1px solid #d1d5db; border-radius: 6px; padding: 5px 12px; cursor: pointer; font-size: 12px; color: #374151; }
    .edit-grid { width: 100%; border-collapse: collapse; }
    .edit-grid th { text-align: left; font-size: 11px; color: #6b7280; padding: 4px 6px; text-transform: uppercase; }
    .edit-grid td { padding: 4px 6px; }
    .edit-grid input { width: 100%; padding: 6px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; box-sizing: border-box; }
    .edit-grid input.qty { width: 64px; }
    .x-row { background: none; border: none; color: #ef4444; font-size: 18px; cursor: pointer; line-height: 1; }
    .add-line { margin-top: 6px; border-style: dashed; }
    .edit-err { color: #b91c1c; font-size: 13px; margin: 8px 0; }
    .edit-actions { display: flex; gap: 8px; margin-top: 12px; }
    .w-160 { width: 160px; } .w-80 { width: 80px; }

    table { width: 100%; border-collapse: collapse; font-size: 13px; }
    th, td { text-align: left; padding: 8px 12px; border-bottom: 1px solid #f3f4f6; vertical-align: top; }
    th { background: #f9fafb; color: #6b7280; font-weight: 600; font-size: 12px; text-transform: uppercase; letter-spacing: 0.04em; }
    .name-cell { font-weight: 500; color: #111827; }
    .w-40 { width: 40px; text-align: center; } .w-60 { width: 60px; } .w-140 { width: 140px; } .w-160 { width: 160px; }
    .requested-at { font-size: 12px; color: #6b7280; margin-top: 4px; }

    .badge { display: inline-block; padding: 2px 9px; border-radius: 10px; font-size: 12px; font-weight: 600; }
    .b-REGISTERED { background: #d1fae5; color: #065f46; }
    .b-NOT_FOUND { background: #e5e7eb; color: #374151; }
    .b-status { background: #dbeafe; color: #1e40af; }
    .vat { margin-left: 8px; font-size: 11px; color: #065f46; font-weight: 600; }
    .reg-meta { font-size: 12px; color: #6b7280; margin-top: 4px; }

    .ask-row { display: flex; gap: 10px; align-items: center; }
    .dist-select { padding: 7px 10px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; min-width: 300px; }
    .hint { font-size: 12px; color: #9ca3af; margin-top: 6px; }

    .pr-card { border: 1px solid #e5e7eb; border-radius: 8px; padding: 12px 14px; margin-bottom: 14px; background: #fff; }
    .pr-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 8px; }
    .pr-dist { font-weight: 600; color: #111827; font-size: 14px; }
    .pr-items th, .pr-items td { padding: 6px 8px; }
    .price-input { width: 110px; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .note-input { width: 100%; padding: 5px 8px; border: 1px solid #d1d5db; border-radius: 6px; font-size: 13px; }
    .cur-price { display: block; font-size: 11px; color: #9ca3af; margin-top: 2px; }
    .pr-actions { margin-top: 10px; display: flex; justify-content: flex-end; }

    .btn-primary { background: #1a56db; color: #fff; border: none; padding: 8px 14px; border-radius: 8px; cursor: pointer; font-size: 13px; }
    .btn-primary:disabled { background: #93c5fd; cursor: not-allowed; }

    .src-group { border: 1px solid #e5e7eb; border-radius: 8px; padding: 10px 12px; margin-bottom: 10px; }
    .src-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; }
    .src-dist { font-weight: 600; color: #111827; font-size: 14px; }
    .src-lines { margin: 0; padding-left: 18px; font-size: 13px; color: #374151; }
    .src-brand { color: #6b7280; }
    .src-unmatched { margin-top: 10px; padding: 10px 12px; background: #f9fafb; border: 1px dashed #e5e7eb; border-radius: 8px; }
    .src-unmatched-title { font-size: 12px; color: #92400e; font-weight: 600; margin-bottom: 4px; }

    .empty { color: #9ca3af; font-size: 13px; padding: 16px; background: #f9fafb; border: 1px dashed #e5e7eb; border-radius: 6px; text-align: center; }
    .loading { color: #6b7280; font-size: 13px; text-align: center; padding: 12px; }

    @media (max-width: 768px) {
      .sidebar { width: 100vw; }
      .head { padding: 16px 20px 12px; }
      .body { padding: 16px 20px 24px; }
      .dist-select { min-width: 0; flex: 1; }
    }
  `]
})
export class PrivateRequestCardComponent implements OnChanges {
  @Input() requestId: number | null = null;
  @Output() close = new EventEmitter<void>();

  request: any = null;
  lines: any[] = [];
  priceRequests: any[] = [];
  distributors: any[] = [];
  selectedDistributorId: number | null = null;
  loading = false;
  sending = false;
  sourcing: any = null;
  sendingGroupId: number | null = null;
  editMode = false;
  editLines: any[] = [];
  editError = '';
  saving = false;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef, private notify: NotificationService) {}

  startEdit() {
    this.editLines = this.lines.map((l: any) => ({
      lotId: l.lotId, name: l.name, manufact: l.manufact || '', quantity: l.quantity ?? 1,
    }));
    this.editError = '';
    this.editMode = true;
    this.cdr.detectChanges();
  }

  addEditLine() { this.editLines.push({ lotId: null, name: '', manufact: '', quantity: 1 }); }
  removeEditLine(i: number) { this.editLines.splice(i, 1); }
  cancelEdit() { this.editMode = false; this.editError = ''; }

  saveEdit() {
    if (this.requestId == null) return;
    const lines = this.editLines
      .filter((l: any) => l.name && String(l.name).trim())
      .map((l: any) => ({
        lotId: l.lotId ?? null,
        name: String(l.name).trim(),
        manufact: l.manufact && String(l.manufact).trim() ? String(l.manufact).trim() : null,
        quantity: parseInt(l.quantity, 10) || 1,
      }));
    if (!lines.length) { this.editError = 'Нужна хотя бы одна строка с наименованием'; return; }
    this.saving = true;
    this.api.update('private-requests', this.requestId, { lines }).subscribe({
      next: () => {
        this.saving = false;
        this.editMode = false;
        this.notify.success('Заявка обновлена');
        this.loadAll(this.requestId as number);
      },
      error: (e: any) => {
        this.saving = false;
        this.editError = e.error?.message || 'Ошибка сохранения';
        this.cdr.detectChanges();
      },
    });
  }

  ngOnChanges(changes: SimpleChanges) {
    if (changes['requestId']) {
      const cur = changes['requestId'].currentValue;
      const prev = changes['requestId'].previousValue;
      if (cur != null && cur !== prev) {
        this.loadAll(cur);
      }
      if (cur == null) {
        this.request = null;
        this.lines = [];
        this.priceRequests = [];
        this.sourcing = null;
      }
    }
  }

  loadAll(id: number) {
    this.loading = true;
    this.request = null;
    this.lines = [];
    this.priceRequests = [];
    this.selectedDistributorId = null;
    this.sourcing = null;
    this.editMode = false;
    this.cdr.detectChanges();

    this.api.getPrivateRequest(id).subscribe({
      next: (data) => {
        this.request = data || null;
        this.lines = ((data && data.lines) || []).map((l: any) => ({ ...l, _selected: false }));
        this.loading = false;
        this.cdr.detectChanges();
      },
      error: (e) => {
        this.loading = false;
        this.notify.error('Ошибка загрузки заявки: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });

    this.loadPriceRequests(id);

    this.api.getPrivateRequestSourcing(id).subscribe({
      next: (s) => { this.sourcing = s || null; this.cdr.detectChanges(); },
      error: () => { this.sourcing = null; }
    });

    if (!this.distributors.length) {
      this.api.getDistributors().subscribe({
        next: (d) => { this.distributors = d || []; this.cdr.detectChanges(); },
        error: () => {}
      });
    }
  }

  loadPriceRequests(id: number) {
    this.api.getPriceRequestsByTender(id).subscribe({
      next: (prs) => {
        this.priceRequests = prs || [];
        for (const pr of this.priceRequests) {
          for (const it of (pr.items || [])) {
            it._editPrice = it.responsePrice;
            it._editNote = it.responseNote;
          }
        }
        this.cdr.detectChanges();
      },
      error: () => { this.priceRequests = []; this.cdr.detectChanges(); }
    });
  }

  selectedCount(): number {
    return this.lines.filter(l => l._selected).length;
  }

  allSelected(): boolean {
    return this.lines.length > 0 && this.lines.every(l => l._selected);
  }

  toggleAll(checked: boolean) {
    for (const l of this.lines) l._selected = checked;
  }

  requestedDistributorsFor(lotId: any): string[] {
    if (lotId == null) return [];
    const names: string[] = [];
    for (const pr of this.priceRequests) {
      const hasLot = (pr.items || []).some((it: any) => it.tenderLot?.id === lotId);
      if (hasLot && pr.distributor?.name && !names.includes(pr.distributor.name)) {
        names.push(pr.distributor.name);
      }
    }
    return names;
  }

  requestPrice() {
    if (this.requestId == null || !this.selectedDistributorId || !this.selectedCount()) return;
    this.sending = true;
    this.api.sendPriceRequests({
      tenderId: this.requestId,
      distributorIds: [this.selectedDistributorId],
      items: this.lines.filter(l => l._selected).map(l => ({
        tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity ?? 1
      }))
    }).subscribe({
      next: (results) => {
        this.sending = false;
        for (const l of this.lines) l._selected = false;
        this.selectedDistributorId = null;
        this.kpToast(results);
        this.loadPriceRequests(this.requestId as number);
      },
      error: (e) => {
        this.sending = false;
        this.notify.error('Ошибка запроса КП: ' + (e.error?.message || e.message));
        this.cdr.detectChanges();
      }
    });
  }

  requestGroup(group: any) {
    if (this.requestId == null || !group?.distributor?.id || !group.lines?.length) return;
    this.sendingGroupId = group.distributor.id;
    this.api.sendPriceRequests({
      tenderId: this.requestId,
      distributorIds: [group.distributor.id],
      items: group.lines.map((l: any) => ({
        tenderLotId: l.lotId, medEquipmentId: null, requestedQuantity: l.quantity ?? 1
      }))
    }).subscribe({
      next: (results) => {
        this.sendingGroupId = null;
        this.kpToast(results);
        this.loadPriceRequests(this.requestId as number);
        this.api.getPrivateRequestSourcing(this.requestId as number).subscribe({ next: s => { this.sourcing = s; this.cdr.detectChanges(); } });
      },
      error: (e) => { this.sendingGroupId = null; this.notify.error('Ошибка: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }

  private kpToast(results: any[]) {
    const r = (results || [])[0];
    if (!r) { this.notify.success('КП запрошено'); return; }
    if (r.emailSent) this.notify.success(`КП отправлено: «${r.distributorName}»`);
    else if (r.reason === 'NO_EMAIL') this.notify.error(`КП создано, но у «${r.distributorName}» нет email — письмо не ушло`);
    else this.notify.error(`КП создано, но письмо «${r.distributorName}» не отправлено (ошибка SMTP)`);
  }

  saveResponses(pr: any) {
    const updates = (pr.items || []).map((it: any) => ({
      itemId: it.id,
      responsePrice: it._editPrice ?? it.responsePrice,
      responseNote: it._editNote ?? it.responseNote
    }));
    const bad = updates.find((u: any) => u.responsePrice != null && Number(u.responsePrice) < 0);
    if (bad) { this.notify.error('Цена не может быть отрицательной'); return; }
    this.api.updatePriceRequestResponses(pr.id, updates).subscribe({
      next: () => {
        this.notify.success('Ответы сохранены');
        this.loadPriceRequests(this.requestId as number);
      },
      error: (e) => { this.notify.error('Ошибка сохранения: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); }
    });
  }

  onClose() {
    this.close.emit();
  }

  @HostListener('document:keydown.escape')
  onEscape() {
    if (this.requestId !== null) this.onClose();
  }
}
