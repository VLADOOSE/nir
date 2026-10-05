import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { AuthService } from '../services/auth.service';
import { safeReturnUrl } from '../shared/return-url';

export const authGuard: CanActivateFn = (_route, state) => {
  const auth = inject(AuthService);
  const router = inject(Router);
  if (auth.isLoggedIn()) return true;
  // ссылка из уведомления (?openId=…&market=…) после входа должна открыть ту же страницу, а не главную
  const back = safeReturnUrl(state.url);
  return router.createUrlTree(['/login'], back ? { queryParams: { returnUrl: back } } : {});
};
