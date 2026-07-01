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
  portalLabel(): string { return this.current === 'KZ' ? 'Госзакуп' : 'ЕИС'; }
  portalHost(): string { return this.current === 'KZ' ? 'goszakup.gov.kz' : 'zakupki.gov.ru'; }

  /** Ссылка на тендер на площадке активного рынка. */
  portalLink(tenderNumber: string): string {
    const q = encodeURIComponent(tenderNumber || '');
    if (this.current !== 'KZ') {
      return `https://zakupki.gov.ru/epz/order/extendedsearch/results.html?searchString=${q}`;
    }
    // Импортированный с goszakup номер "17276387-1" → страница объявления по id (часть до дефиса);
    // ручные номера (KZ-2026-0001) на портале не существуют — оставляем поиск по лотам
    const m = /^(\d+)-\d+$/.exec(tenderNumber || '');
    return m
      ? `https://goszakup.gov.kz/ru/announce/index/${m[1]}`
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
