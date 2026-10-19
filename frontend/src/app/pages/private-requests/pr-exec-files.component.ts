import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, Output } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import type { PrView } from '../../shared/private-request';
import type { ExecFile } from '../../shared/deal-execution';
import { errorText } from '../../shared/error-text';
import { responseFile } from '../../shared/share';
import { saveBlob } from '../../shared/client-offer';

/** Файлы одного места раздела «Исполнение»: имя — скачать (blob, §14), × — удалить (админ). */
@Component({
  selector: 'app-pr-exec-files',
  standalone: true,
  imports: [NgFor, NgIf],
  template: `
    <span class="xf" *ngIf="files.length">
      <span class="xf-item" *ngFor="let f of files">
        <button type="button" class="xf-name" (click)="download(f)" [title]="f.name">📎 {{ f.name }}</button>
        <button type="button" class="xf-del" *ngIf="!readonly" [disabled]="busy" (click)="remove(f)"
                [attr.aria-label]="'Удалить файл ' + f.name">×</button>
      </span>
    </span>
  `,
  styles: [`
    .xf { display: inline-flex; flex-wrap: wrap; gap: 4px 8px; }
    .xf-item { display: inline-flex; align-items: center; gap: 2px; max-width: 100%; }
    .xf-name { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; font-size: 13px;
               max-width: 260px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
    .xf-name:hover { text-decoration: underline; }
    .xf-del { background: none; border: none; padding: 0 4px; color: var(--text-muted); cursor: pointer; font-size: 16px; line-height: 1; }
    .xf-del:hover { color: var(--danger-text); }
    @media (max-width: 900px) {
      .xf-name { min-height: 40px; max-width: 220px; }
      .xf-del { min-width: 40px; min-height: 40px; }
    }
  `],
})
export class PrExecFilesComponent {
  @Input({ required: true }) requestId!: number;
  @Input() files: ExecFile[] = [];
  @Input() readonly = false;
  @Output() viewChange = new EventEmitter<PrView>();
  busy = false;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  download(f: ExecFile) {
    this.api.downloadExecutionFile(this.requestId, f.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { const file = responseFile(r, f.name); if (file) saveBlob(file, file.name); },
      error: e => this.notify.error('Файл не скачался: ' + errorText(e)),
    });
  }

  remove(f: ExecFile) {
    this.confirm.ask(`Удалить файл «${f.name}»?`, undefined, { danger: true, confirmLabel: 'Удалить' })
      .pipe(takeUntilDestroyed(this.destroyRef)).subscribe(ok => {
        if (!ok) return;
        this.busy = true;
        this.api.deleteExecutionFile(this.requestId, f.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
          next: v => { this.busy = false; this.viewChange.emit(v); },
          error: e => { this.busy = false; this.notify.error(errorText(e)); this.cdr.markForCheck(); },
        });
      });
  }
}
