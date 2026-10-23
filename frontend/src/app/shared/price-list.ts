/** Прайсы поставщиков (спека 2026-10-23 §5): ответы /api/price-lists. */
export interface PriceListColumn { index: number; header: string; }
export type RowRegStatus = 'ACTIVE' | 'EXPIRED' | 'NOT_FOUND' | 'NONE' | null;
export interface PriceListRow {
  id: number; lineNo: number; section: string | null; name: string; model: string | null; article: string | null;
  prices: (number | null)[]; ourPrice: number | null; converted: number | null; today: number | null;
  regText: string | null; regNumber: string | null; regStatus: RowRegStatus; regExpires: string | null; noReg: boolean;
  siteProductId: number | null; siteProductName: string | null; siteProductUrl: string | null;
  medEquipmentId: number | null; medEquipmentName: string | null;
  prevPrice: number | null; prevDate: string | null; prevPct: number | null; fileRow: number | null;
}
export interface PriceListView {
  id: number; status: 'DRAFT' | 'ACTIVE'; distributorId: number; distributorName: string; fileName: string; kind: string;
  parseStatus: string; parseNote: string | null; listDate: string | null; validTo: string | null; expired: boolean;
  currency: string | null; retail: boolean; columns: PriceListColumn[]; ourColumn: number | null; discountPct: number;
  terms: string | null; marketCurrency: string; ourRate: number | null; todayRate: number | null; todayDate: string | null;
  createdBy: string | null; createdAt: string; activatedBy: string | null; activatedAt: string | null; rows: PriceListRow[];
}
export interface PriceListListItem {
  id: number; distributorId: number; distributorName: string; status: 'DRAFT' | 'ACTIVE'; listDate: string | null;
  validTo: string | null; expired: boolean; currency: string | null; fileName: string; kind: string; parseStatus: string;
  createdBy: string | null; createdAt: string; itemCount: number;
}
export interface PriceLinkCandidates {
  products: { productId: number; name: string; brand: string | null; url: string | null }[];
  equipment: { id: number; name: string; manufact: string | null }[];
}
export type PriceListField = 'DISTRIBUTOR' | 'LIST_DATE' | 'VALID_TO' | 'CURRENCY' | 'OUR_COLUMN' | 'DISCOUNT_PCT' | 'TERMS';
export type PriceRowField = 'SECTION' | 'NAME' | 'MODEL' | 'ARTICLE' | 'REG_TEXT' | 'SITE_PRODUCT' | 'EQUIPMENT' | `PRICE:${number}`;

/** Статус прайса для чипа: черновик / действующий / истёк. */
export function listState(p: { status: string; expired: boolean }): { text: string; tone: 'muted' | 'ok' | 'warn' } {
  if (p.status === 'DRAFT') return { text: 'черновик', tone: 'muted' };
  return p.expired ? { text: 'истёк', tone: 'warn' } : { text: 'действует', tone: 'ok' };
}

/** «01.09.2026»; пусто — «». */
export function dmy(iso: string | null | undefined): string {
  return iso ? `${iso.slice(8, 10)}.${iso.slice(5, 7)}.${iso.slice(0, 4)}` : '';
}
