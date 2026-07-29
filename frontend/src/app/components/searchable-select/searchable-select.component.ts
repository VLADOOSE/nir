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
    .ss-selected { display: flex; justify-content: space-between; align-items: center; padding: 8px 12px; border: 1px solid var(--border); border-radius: 4px; cursor: pointer; background: var(--surface); color: var(--text); font-size: 14px; min-height: 38px; }
    /* Ховер-рамка. Буквальный перевод в var(--border) был бы no-op — база уже
       --border; а голый var(--text-muted) в тёмной теме светит ярче, чем рамка
       сфокусированного поля. Смесь 60/40 даёт ровно прежний #9ca3af в светлой
       теме и спокойный, но заметный шаг в тёмной. */
    .ss-selected:hover { border-color: color-mix(in srgb, var(--text-muted) 60%, var(--border)); }
    .ss-placeholder { color: var(--text-muted); }
    .ss-value { color: var(--text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .ss-arrow { font-size: 10px; color: var(--text-muted); margin-left: 8px; }
    .ss-dropdown { position: absolute; top: 100%; left: 0; right: 0; background: var(--surface); border: 1px solid var(--border); border-radius: 0 0 6px 6px; box-shadow: var(--shadow); z-index: 50; margin-top: -1px; }
    /* Поле поиска намеренно сливается с фоном списка (как и было: белое на белом),
       граница между ними — только нижняя рамка. */
    .ss-search { width: 100%; padding: 8px 12px; border: none; border-bottom: 1px solid var(--border); font-size: 14px; outline: none; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .ss-options { max-height: 250px; overflow-y: auto; }
    .ss-option { padding: 8px 12px; cursor: pointer; font-size: 14px; display: flex; flex-direction: column; }
    .ss-option:hover { background: var(--surface-2); }
    /* Выбранный пункт — подсветка ОБЛАСТИ: смешивается с --surface непрозрачно,
       а НЕ заменяется на --surface-2 (иначе совпал бы с ховером соседей и
       перестал читаться как выбор). Правило идёт после :hover — на равной
       специфичности выигрывает по порядку, менять местами нельзя. */
    .ss-option.active { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); color: var(--accent); }
    .ss-option-main { color: var(--text); }
    .ss-option-sub { font-size: 12px; color: var(--text-muted); margin-top: 2px; }
    .ss-group-label { padding: 6px 12px; font-size: 11px; color: var(--text-muted); text-transform: uppercase; background: var(--surface-2); font-weight: 600; }
    /* .ss-empty — не kit-овский .empty (другой класс, своя геометрия внутри
       выпадающего списка), поэтому остаётся локальным. */
    .ss-empty { padding: 12px; text-align: center; color: var(--text-muted); font-size: 13px; }
    .open .ss-selected { border-color: var(--accent); border-radius: 4px 4px 0 0; }
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
