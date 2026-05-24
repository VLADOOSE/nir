import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';

export interface Notification {
  message: string;
  type: 'success' | 'error' | 'info';
  id: number;
}

@Injectable({ providedIn: 'root' })
export class NotificationService {
  private counter = 0;
  notifications$ = new Subject<Notification>();
  dismiss$ = new Subject<number>();

  show(message: string, type: 'success' | 'error' | 'info' = 'info') {
    const id = ++this.counter;
    this.notifications$.next({ message, type, id });
    setTimeout(() => this.dismiss$.next(id), 4000);
  }

  success(message: string) { this.show(message, 'success'); }
  error(message: string) { this.show(message, 'error'); }
  info(message: string) { this.show(message, 'info'); }
}
