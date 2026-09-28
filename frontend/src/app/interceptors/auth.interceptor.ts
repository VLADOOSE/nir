import { HttpInterceptorFn, HttpErrorResponse } from '@angular/common/http';
import { inject } from '@angular/core';
import { Router } from '@angular/router';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../services/auth.service';

export const authInterceptor: HttpInterceptorFn = (req, next) => {
  const router = inject(Router);
  const auth = inject(AuthService);

  const cloned = req.clone({ withCredentials: true });

  return next(cloned).pipe(
    catchError((err: HttpErrorResponse) => {
      // калитка ais.westmed.kz: устройство отозвано или выпало — на страницу калитки полной навигацией.
      // logout не зовём: он тоже упёрся бы в калитку. Прочие 401 — «сессия истекла», как раньше.
      if (err.status === 401 && err.headers?.get('X-AIS-Gate') === 'device') {
        window.location.assign('/gate/');
        return throwError(() => err);
      }
      // /login/webauthn: 401 — «ключ не принят», а не «сессия истекла»; страница входа сама скажет, что случилось
      if (err.status === 401 && !req.url.includes('/api/auth/') && !req.url.includes('/login/webauthn')) {
        auth.logout().subscribe();
        router.navigate(['/login']);
      }
      return throwError(() => err);
    })
  );
};
