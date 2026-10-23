/** Курсы валют (спека 2026-10-23 §4): наш курс — для закупки в валюте рынка и наценки; курс сегодня — НБ РК. */
export interface RateToday { rate: number; date: string; stale: boolean; }
export interface RateRow {
  currency: string; ourRate: number | null; updatedBy: string | null; updatedAt: string | null;
  today: RateToday | null; marginPct: number | null;
}
export interface RatesView { market: string; marketCurrency: string; todayEnabled: boolean; rates: RateRow[]; }

const SHORT: Record<string, string> = { KZT: 'тг', RUB: 'руб.', USD: '$', EUR: '€', CNY: 'юань' };

/** «руб.», «тг», «$» — рядом с числом (как ExecutionTexts.currency на сервере). */
export function curShort(code: string | null | undefined): string {
  return code ? SHORT[code] ?? code : '';
}

const RATE = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 6, useGrouping: false });

/** Курс без группировки, до 6 знаков: «5,48», «6». */
export function rateText(v: number | null | undefined): string {
  return v == null ? '' : RATE.format(v);
}

/** Валюты переключателя цены: валюта рынка, затем валюты с нашим курсом (спека §10). */
export function priceCurrencies(market: string, ours: Record<string, number> | null | undefined): string[] {
  return [market, ...Object.keys(ours ?? {}).filter(c => c !== market)];
}

/** Наш курс по валютам из ответа /api/currency-rates. */
export function ourRatesOf(v: RatesView | null): Record<string, number> {
  const out: Record<string, number> = {};
  for (const r of v?.rates ?? []) if (r.ourRate != null) out[r.currency] = r.ourRate;
  return out;
}

/** «23.10» из «2026-10-23». */
export function dayMonth(iso: string | null | undefined): string {
  return iso ? iso.slice(8, 10) + '.' + iso.slice(5, 7) : '';
}
