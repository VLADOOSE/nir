import { Injectable } from '@angular/core';
import { Observable, catchError, map, shareReplay, throwError } from 'rxjs';
import { ApiService } from './api.service';

export interface VatHints { registered: string; standard: string; }

/** Пока профиль не пришёл — без чисел: устаревшая ставка хуже, чем никакой (зашитое число уже расходилось со ставкой рынка). */
export const DEFAULT_VAT_HINTS: VatHints = { registered: 'льготная\u00A0ставка\u00A0НДС', standard: 'облагается\u00A0НДС' };

/**
 * Реквизиты рынка: ставки НДС и умолчания КП (спека §7). Кешируется до сохранения на «Реквизитах и печати»;
 * смена рынка перезагружает страницу — кеш уходит вместе с ней.
 */
@Injectable({ providedIn: 'root' })
export class CompanyProfileService {
  private cache$: Observable<any> | null = null;

  constructor(private api: ApiService) {}

  profile$(): Observable<any> {
    if (!this.cache$) {
      this.cache$ = this.api.getCompanyProfile().pipe(
        catchError(e => { this.cache$ = null; return throwError(() => e); }),
        shareReplay(1));
    }
    return this.cache$;
  }

  invalidate() {
    this.cache$ = null;
  }

  /**
   * «льготная ставка НДС 5%» / «НДС 16%» — из настроек рынка, а не зашитым числом. Пробелы внутри — неразрывные: фраза
   * не рвётся посередине (было «Зарегистрировано без» / «НДС», одинокое «5%» на телефоне).
   */
  vatHints$(): Observable<VatHints> {
    return this.profile$().pipe(map(p => ({
      registered: hint(p.vatRegistered, 'льготная\u00A0ставка\u00A0НДС\u00A0'),
      standard: hint(p.vatNotRegistrable, 'НДС\u00A0'),
    })));
  }
}

function hint(rate: number | null | undefined, prefix: string): string {
  return rate == null ? 'без\u00A0НДС' : prefix + String(rate).replace('.', ',') + '%';
}
