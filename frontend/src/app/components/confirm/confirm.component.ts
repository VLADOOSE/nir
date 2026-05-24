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
    .confirm-overlay {
      position: fixed; top: 0; left: 0; right: 0; bottom: 0;
      background: rgba(17, 24, 39, 0.5);
      display: flex; align-items: center; justify-content: center;
      z-index: 1000;
      animation: fadeIn 0.15s ease-out;
    }
    @keyframes fadeIn { from { opacity: 0; } to { opacity: 1; } }
    .confirm-modal {
      background: #fff; border-radius: 8px; box-shadow: 0 20px 50px rgba(0,0,0,0.25);
      padding: 24px; min-width: 360px; max-width: 480px;
      animation: slideIn 0.15s ease-out;
    }
    @keyframes slideIn { from { transform: translateY(-10px); opacity: 0; } to { transform: translateY(0); opacity: 1; } }
    .confirm-message { font-size: 16px; font-weight: 600; color: #111827; margin-bottom: 8px; }
    .confirm-details { font-size: 14px; color: #6b7280; margin-bottom: 20px; line-height: 1.5; }
    .confirm-actions { display: flex; gap: 8px; justify-content: flex-end; }
    .btn { padding: 8px 16px; border: none; border-radius: 4px; cursor: pointer; font-size: 14px; font-weight: 500; }
    .btn-cancel { background: #e5e7eb; color: #374151; }
    .btn-cancel:hover { background: #d1d5db; }
    .btn-primary { background: #1a56db; color: #fff; }
    .btn-primary:hover { background: #1e40af; }
    .btn-danger { background: #dc2626; color: #fff; }
    .btn-danger:hover { background: #b91c1c; }
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
