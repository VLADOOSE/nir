import { Component, Input, Output, EventEmitter, HostListener, ElementRef } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';

@Component({
  selector: 'app-searchable-select',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="ss-container" [class.open]="isOpen">
      <div class="ss-selected" (click)="toggle()">
        <span *ngIf="!selectedItem" class="ss-placeholder">{{ placeholder }}</span>
        <span *ngIf="selectedItem" class="ss-value">{{ getLabel(selectedItem) }}</span>
        <span class="ss-arrow">&#x25BC;</span>
      </div>
      <div class="ss-dropdown" *ngIf="isOpen">
        <input type="text" class="ss-search" [(ngModel)]="searchText" (input)="filterItems()" placeholder="Введите для поиска..." />
        <div class="ss-options">
          <div class="ss-option" *ngIf="!required" (click)="selectItem(null)">{{ placeholder }}</div>
          <div *ngIf="groupLabel && filteredItems.length > 0" class="ss-group-label">{{ groupLabel }}</div>
          <div class="ss-option" *ngFor="let item of filteredItems" (click)="selectItem(item)" [class.active]="selectedItem && item[valueField] === selectedItem[valueField]">
            <span class="ss-option-main">{{ getLabel(item) }}</span>
            <span *ngIf="getSubLabel(item)" class="ss-option-sub">{{ getSubLabel(item) }}</span>
          </div>
          <div *ngIf="filteredItems.length === 0" class="ss-empty">Ничего не найдено</div>
        </div>
      </div>
    </div>
  `,
  styles: [`
    .ss-container { position: relative; width: 100%; margin-top: 4px; }
    .ss-selected { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; border: 1px solid #d1d5db; border-radius: 4px; cursor: pointer; background: #fff; font-size: 14px; min-height: 38px; }
    .ss-selected:hover { border-color: #9ca3af; }
    .ss-placeholder { color: #9ca3af; }
    .ss-value { color: #111827; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ss-arrow { font-size: 10px; color: #9ca3af; margin-left: 8px; }
    .ss-dropdown { position: absolute; top: 100%; left: 0; right: 0; background: #fff; border: 1px solid #d1d5db; border-radius: 0 0 6px 6px; box-shadow: 0 4px 12px rgba(0,0,0,0.1); z-index: 50; margin-top: -1px; }
    .ss-search { width: 100%; padding: 8px 12px; border: none; border-bottom: 1px solid #e5e7eb; font-size: 14px; outline: none; box-sizing: border-box; }
    .ss-options { max-height: 250px; overflow-y: auto; }
    .ss-option { padding: 8px 12px; cursor: pointer; font-size: 14px; display: flex; flex-direction: column; }
    .ss-option:hover { background: #f3f4f6; }
    .ss-option.active { background: #eff6ff; color: #1a56db; }
    .ss-option-main { color: #111827; }
    .ss-option-sub { font-size: 12px; color: #6b7280; margin-top: 2px; }
    .ss-group-label { padding: 6px 12px; font-size: 11px; color: #9ca3af; text-transform: uppercase; background: #f9fafb; font-weight: 600; }
    .ss-empty { padding: 12px; text-align: center; color: #9ca3af; font-size: 13px; }
    .open .ss-selected { border-color: #1a56db; border-radius: 4px 4px 0 0; }
  `]
})
export class SearchableSelectComponent {
  @Input() items: any[] = [];
  @Input() valueField = 'id';
  @Input() labelField = 'name';
  @Input() subLabelFields: string[] = [];
  @Input() searchFields: string[] = [];
  @Input() placeholder = '— выберите —';
  @Input() groupLabel = '';
  @Input() required = false;
  @Input() value: any = null;
  @Output() valueChange = new EventEmitter<any>();

  isOpen = false;
  searchText = '';
  filteredItems: any[] = [];
  selectedItem: any = null;

  constructor(private el: ElementRef) {}

  ngOnInit() {
    this.filteredItems = [...this.items];
    this.syncSelected();
  }

  ngOnChanges() {
    this.filteredItems = [...this.items];
    this.syncSelected();
  }

  private syncSelected() {
    if (this.value != null) {
      this.selectedItem = this.items.find(i => i[this.valueField] === this.value) || null;
    } else {
      this.selectedItem = null;
    }
  }

  toggle() {
    this.isOpen = !this.isOpen;
    if (this.isOpen) { this.searchText = ''; this.filterItems(); }
  }

  filterItems() {
    const q = this.searchText.toLowerCase();
    const fields = this.searchFields.length > 0 ? this.searchFields : [this.labelField];
    this.filteredItems = this.items.filter(item =>
      fields.some(f => (item[f] || '').toString().toLowerCase().includes(q))
    );
  }

  selectItem(item: any) {
    this.selectedItem = item;
    this.isOpen = false;
    this.valueChange.emit(item ? item[this.valueField] : null);
  }

  getLabel(item: any): string { return item ? item[this.labelField] || '' : ''; }

  getSubLabel(item: any): string {
    if (!item || this.subLabelFields.length === 0) return '';
    return this.subLabelFields.map(f => item[f] || '').filter(Boolean).join(' — ');
  }

  @HostListener('document:click', ['$event'])
  onDocumentClick(event: Event) {
    if (!this.el.nativeElement.contains(event.target)) { this.isOpen = false; }
  }
}
