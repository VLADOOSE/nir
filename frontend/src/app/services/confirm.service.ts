import { Injectable } from '@angular/core';
import { Subject, Observable } from 'rxjs';

export interface ConfirmRequest {
  message: string;
  details?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

export interface ConfirmEvent {
  request: ConfirmRequest;
  resolve: (result: boolean) => void;
}

@Injectable({ providedIn: 'root' })
export class ConfirmService {
  private subject = new Subject<ConfirmEvent>();
  events$ = this.subject.asObservable();

  ask(message: string, details?: string, options?: Partial<ConfirmRequest>): Observable<boolean> {
    return new Observable<boolean>(observer => {
      this.subject.next({
        request: { message, details, danger: false, ...options },
        resolve: (result) => {
          observer.next(result);
          observer.complete();
        }
      });
    });
  }
}
