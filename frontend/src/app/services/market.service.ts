import { Injectable, signal } from '@angular/core';
import { BehaviorSubject } from 'rxjs';

export type Market = 'RF' | 'KZ';

interface MarketMeta { code: Market; symbol: string; companyLabel: string; logo: string; }

const MARKETS: Record<Market, MarketMeta> = {
  RF: { code: 'RF', symbol: '₽', companyLabel: 'АИС Регион-Мед', logo: 'РМ' },
  KZ: { code: 'KZ', symbol: '₸', companyLabel: 'АИС West-Med',  logo: 'WM' },
};

@Injectable({ providedIn: 'root' })
export class MarketService {
  private readonly KEY = 'ais.market';
  private current: Market = this.read();
  private subject = new BehaviorSubject<Market>(this.current);

  market = signal<Market>(this.current);
  market$ = this.subject.asObservable();

  private read(): Market {
    const v = localStorage.getItem(this.KEY);
    return v === 'KZ' ? 'KZ' : 'RF';
  }

  get value(): Market { return this.current; }
  symbol(): string { return MARKETS[this.current].symbol; }
  companyLabel(): string { return MARKETS[this.current].companyLabel; }
  logo(): string { return MARKETS[this.current].logo; }
  meta(m: Market): MarketMeta { return MARKETS[m]; }

  // Название/хост площадки госзакупок активного рынка (РФ — ЕИС, KZ — Госзакуп РК)
  portalLabel(platform?: string): string {
    if (platform === 'SK_PHARMACY') return 'СК-Фармация';
    if (platform === 'GOSZAKUP') return 'Госзакуп';
    return this.current === 'KZ' ? 'Госзакуп' : 'ЕИС';
  }
  portalHost(platform?: string): string {
    if (platform === 'SK_PHARMACY') return 'fms.ecc.kz';
    if (platform === 'GOSZAKUP') return 'goszakup.gov.kz';
    return this.current === 'KZ' ? 'goszakup.gov.kz' : 'zakupki.gov.ru';
  }

  /** Ссылка на тендер на площадке по его каналу; null → фолбэк по рынку. */
  portalLink(tenderNumber: string, platform?: string): string {
    const q = encodeURIComponent(tenderNumber || '');
    // id объявления — часть номера до дефиса ("363780-1" → "363780"); та же схема у goszakup и fms.ecc.kz
    const m = /^(\d+)-\d+$/.exec(tenderNumber || '');
    if (platform === 'SK_PHARMACY') {
      return m ? `https://fms.ecc.kz/ru/announce/index/${m[1]}?tab=lots`
               : `https://fms.ecc.kz/ru/searchanno`;
    }
    if (platform === 'GOSZAKUP') {
      return m ? `https://goszakup.gov.kz/ru/announce/index/${m[1]}`
               : `https://goszakup.gov.kz/ru/search/lots?filter[name]=${q}`;
    }
    if (this.current !== 'KZ') {
      return `https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=${q}`;
    }
    return m ? `https://goszakup.gov.kz/ru/announce/index/${m[1]}`
             : `https://goszakup.gov.kz/ru/search/lots?filter[name]=${q}`;
  }

  setMarket(m: Market) {
    if (m === this.current) return;
    this.current = m;
    localStorage.setItem(this.KEY, m);
    this.market.set(m);
    this.subject.next(m);
  }
}
