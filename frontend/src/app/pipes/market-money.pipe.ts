import { Pipe, PipeTransform } from '@angular/core';
import { MarketService } from '../services/market.service';

/** Форматирует число как сумму в валюте активного рынка: 1234567 -> "1 234 567 ₸". */
@Pipe({ name: 'money', standalone: true, pure: false })
export class MarketMoneyPipe implements PipeTransform {
  constructor(private market: MarketService) {}
  transform(value: number | null | undefined, digits: number = 0): string {
    if (value == null) return '—';
    const n = Number(value).toLocaleString('ru-RU', { minimumFractionDigits: digits, maximumFractionDigits: digits });
    return `${n} ${this.market.symbol()}`;
  }
}
