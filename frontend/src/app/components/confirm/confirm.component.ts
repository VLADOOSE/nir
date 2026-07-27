import { Component, ChangeDetectorRef, HostListener } from '@angular/core';
import { NgIf } from '@angular/common';
import { ConfirmService, ConfirmEvent } from '../../services/confirm.service';

@Component({
  selector: 'app-confirm',
  standalone: true,
  imports: [NgIf],
  template: `
    <div *ngIf="current" class="confirm-overlay" (click)="onCancel()">
      <div class="confirm-modal" (click)="$event.stopPropagation()">
        <div class="confirm-message">{{ current.request.message }}</div>
        <div *ngIf="current.request.details" class="confirm-details">{{ current.request.details }}</div>
        <div class="confirm-actions">
          <button class="btn btn-cancel" (click)="onCancel()">{{ current.request.cancelLabel || 'Отмена' }}</button>
          <button class="btn" [class.btn-danger]="current.request.danger" [class.btn-primary]="!current.request.danger" (click)="onConfirm()">
            {{ current.request.confirmLabel || 'Подтвердить' }}
          </button>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* Бэкдроп остаётся затемняющей вуалью, а не поверхностью: rgba(17,24,39,.5)
       уместен в обеих темах — он гасит страницу под модалкой, а не красит её. */
    .confirm-overlay {
      position: fixed; top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(17, 24, 39, 0.5);
      display: flex; align-items: center; justify-content: center;
      z-index: 1000;
      animation: fadeIn 0.15s ease-out;
    }
    @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
    .confirm-modal {
      background: var(--surface); border-radius: 8px; box-shadow: var(--shadow);
      padding: 24px; min-width: 360px; max-width: 480px;
      animation: slideIn 0.15s ease-out;
    }
    @keyframes slideIn { from { transform: translateY(-10px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
    .confirm-message { font-size: 16px; font-weight: 600; color: var(--text); margin-bottom: 8px; }
    .confirm-details { font-size: 14px; color: var(--text-muted); margin-bottom: 20px; line-height: 1.5; }
    .confirm-actions { display: flex; gap: 8px; justify-content: flex-end; }
    /* Цвет кнопок (.btn-cancel / .btn-primary / .btn-danger + их :hover, кроме danger)
       приходит из UI-kit в styles.scss. Локально осталась ТОЛЬКО геометрия: в модалке
       подтверждения кнопка крупнее kit-дефолта (6px 14px / 13px), это осознанно. */
    .btn { padding: 8px 16px; font-size: 14px; font-weight: 500; }
    /* :hover для danger kit НЕ даёт (там у .btn-danger только заливка), поэтому правило
       живёт здесь. Затемняем к чёрному, а не к --text (как .btn-cancel:hover в kit):
       у залитой кнопки белая подпись, и в тёмной теме подмешивание светлого --text
       уронило бы её контраст вместо того, чтобы поднять. */
    .btn-danger:hover { background: color-mix(in srgb, black 15%, var(--danger)); }
  `]
})
export class ConfirmComponent {
  current: ConfirmEvent | null = null;

  constructor(private confirmService: ConfirmService, private cdr: ChangeDetectorRef) {
    this.confirmService.events$.subscribe(event => {
      this.current = event;
      this.cdr.detectChanges();
    });
  }

  onConfirm() {
    if (this.current) {
      this.current.resolve(true);
      this.current = null;
      this.cdr.detectChanges();
    }
  }

  onCancel() {
    if (this.current) {
      this.current.resolve(false);
      this.current = null;
      this.cdr.detectChanges();
    }
  }

  @HostListener('document:keydown.escape')
  onEscape() { if (this.current) this.onCancel(); }

  @HostListener('document:keydown.enter')
  onEnter() { if (this.current) this.onConfirm(); }
}
