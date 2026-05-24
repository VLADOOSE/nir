import { Component } from '@angular/core';
import { NgFor, NgIf, NgClass } from '@angular/common';
import { NotificationService, Notification } from '../../services/notification.service';

@Component({
  selector: 'app-notifications',
  standalone: true,
  imports: [NgFor, NgIf, NgClass],
  template: `
    <div class="toast-container">
      <div *ngFor="let n of notifications" class="toast" [ngClass]="'toast-' + n.type" (click)="dismiss(n.id)">
        <span class="toast-icon" *ngIf="n.type === 'success'">&#10003;</span>
        <span class="toast-icon" *ngIf="n.type === 'error'">&#10007;</span>
        <span class="toast-icon" *ngIf="n.type === 'info'">i</span>
        <span class="toast-msg">{{ n.message }}</span>
      </div>
    </div>
  `,
  styles: [`
    .toast-container { position: fixed; top: 16px; right: 16px; z-index: 9999; display: flex; flex-direction: column; gap: 8px; max-width: 400px; }
    .toast { display: flex; align-items: center; gap: 10px; padding: 12px 16px; border-radius: 8px; color: #fff; font-size: 14px; cursor: pointer; box-shadow: 0 4px 12px rgba(0,0,0,0.15); animation: slideIn 0.3s ease; }
    .toast-success { background: #059669; }
    .toast-error { background: #dc2626; }
    .toast-info { background: #1a56db; }
    .toast-icon { font-weight: 700; font-size: 16px; }
    .toast-msg { flex: 1; }
    @keyframes slideIn { from { transform: translateX(100%); opacity: 0; } to { transform: translateX(0); opacity: 1; } }
  `]
})
export class NotificationComponent {
  notifications: Notification[] = [];

  constructor(private ns: NotificationService) {
    ns.notifications$.subscribe(n => this.notifications.push(n));
    ns.dismiss$.subscribe(id => this.notifications = this.notifications.filter(n => n.id !== id));
  }

  dismiss(id: number) {
    this.notifications = this.notifications.filter(n => n.id !== id);
  }
}
