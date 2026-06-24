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

  setMarket(m: Market) {
    if (m === this.current) return;
    this.current = m;
    localStorage.setItem(this.KEY, m);
    this.market.set(m);
    this.subject.next(m);
  }
}
