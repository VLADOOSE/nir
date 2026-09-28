import { Component, Input } from '@angular/core';
import { NgFor } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { IMPORT_FIELD_OPTIONS, ImportPreview } from './import-lines';

/** Грид разбора Excel: заголовки файла + выбор роли колонки. Правит column.field на месте (как раньше в экранах). */
@Component({
  selector: 'app-import-grid',
  standalone: true,
  imports: [NgFor, FormsModule],
  template: `
    <div class="grid-wrap">
      <table class="import-grid">
        <thead>
          <tr>
            <th *ngFor="let c of preview.columns">
              <div class="ih">{{ c.header || '—' }}</div>
              <select [(ngModel)]="c.field" [ngModelOptions]="{standalone: true}"
                      [attr.aria-label]="'Роль колонки ' + (c.header || c.index + 1)">
                <option *ngFor="let o of options" [ngValue]="o.v">{{ o.l }}</option>
              </select>
            </th>
          </tr>
        </thead>
        <tbody>
          <tr *ngFor="let row of preview.rows">
            <td *ngFor="let cell of row">{{ cell }}</td>
          </tr>
        </tbody>
      </table>
    </div>
  `,
  styles: [`
    .grid-wrap { overflow-x: auto; border: 1px solid var(--border); border-radius: 8px; }
    .import-grid { border-collapse: collapse; width: 100%; font-size: 13px; }
    /* Цвет текста НЕ приглушённый из kit: в шапке стоят заголовки ИЗ ФАЙЛА пользователя — данные, которые он
       сверяет с разметкой колонок, а не служебная подпись. Фон — как у kit-овского th (styles.scss).
       text-align задан здесь: раньше его давал экранный «thead th» «Частных заявок», а во «Входящих» шапка
       стояла по центру — экраны расходились; теперь одна раскладка (слева, как ячейки). */
    .import-grid th { background: var(--surface-2); color: var(--text); padding: 8px; border: 1px solid var(--border); vertical-align: top; text-align: left; }
    .import-grid th .ih { font-weight: 600; margin-bottom: 4px; }
    .import-grid th select { width: 100%; padding: 4px; border: 1px solid var(--border); border-radius: 4px; font-size: 12px; background: var(--surface); color: var(--text); }
    .import-grid td { padding: 6px 8px; border: 1px solid var(--border); white-space: nowrap; }
  `],
})
export class ImportGridComponent {
  @Input({ required: true }) preview!: ImportPreview;
  readonly options = IMPORT_FIELD_OPTIONS;
}
