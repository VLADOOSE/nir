import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { MarketService } from '../services/market.service';

export const marketInterceptor: HttpInterceptorFn = (req, next) => {
  const market = inject(MarketService);
  const cloned = req.clone({ setHeaders: { 'X-Market': market.value } });
  return next(cloned);
};
