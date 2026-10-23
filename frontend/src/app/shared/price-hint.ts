/** Подсказка «📒 по прайсу» (спека 2026-10-23 §6): строки текущих прайсов поставщиков — лучшая первой. */
export interface PriceHintRow {
  rowId: number; priceListId: number; distributorId: number; supplierName: string; manufacturer: boolean;
  section: string | null; name: string; model: string | null; article: string | null; listDate: string | null;
  validTo: string | null; expired: boolean; currency: string; price: number; ourRate: number | null;
  converted: number | null; today: number | null; todayDate: string | null; terms: string | null;
  regText: string | null; regNumber: string | null; regStatus: 'ACTIVE' | 'EXPIRED' | 'NOT_FOUND' | 'NONE' | null; regExpires: string | null;
}
export interface PriceHint { text: string | null; rows: PriceHintRow[]; }
export interface PriceHistoryEntry {
  kind: 'LIST' | 'REPLY'; date: string | null; supplierName: string; currency: string; price: number; pct: number | null;
  priceListId: number | null; priceRequestId: number | null;
}

const NUM = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 2 });
const SHORT: Record<string, string> = { KZT: 'тг', RUB: 'руб.', USD: '$', EUR: '€', CNY: 'юань' };

export function hintMoney(v: number | null | undefined, currency: string | null | undefined): string {
  return v == null ? '' : NUM.format(v) + ' ' + (currency ? SHORT[currency] ?? currency : '');
}

/** «📒 Аксион · прайс 01.09: 150 000 руб. → 900 000 тг · ещё 2»; строк нет — null. */
export function hintLine(h: PriceHint | null | undefined, marketCurrency: string): string | null {
  const r = h?.rows?.[0];
  if (!r) return null;
  const date = r.listDate ? ` · прайс ${r.listDate.slice(8, 10)}.${r.listDate.slice(5, 7)}` : '';
  const conv = r.currency === marketCurrency ? '' : r.converted != null ? ` → ${hintMoney(r.converted, marketCurrency)}` : ' — впишите наш курс';
  const more = h!.rows.length > 1 ? ` · ещё ${h!.rows.length - 1}` : '';
  return `📒 ${r.supplierName}${date}: ${hintMoney(r.price, r.currency)}${conv}${r.expired ? ' (прайс истёк)' : ''}${more}`;
}
