/** «Ответ поставщика» (спека 2026-10-15 §4.1): ответ GET /api/price-requests/{id}/reply. */
export type ReplyRole = 'NUMBER' | 'NAME' | 'ARTICLE' | 'MANUFACT' | 'QUANTITY' | 'UNIT' | 'PRICE' | 'SUM' | 'VAT' | 'CURRENCY' | 'NOTE' | 'IGNORE';

export const REPLY_ROLE_OPTIONS: { v: ReplyRole; l: string }[] = [
  { v: 'NAME', l: 'Наименование' }, { v: 'ARTICLE', l: 'Артикул / модель' }, { v: 'MANUFACT', l: 'Бренд' },
  { v: 'QUANTITY', l: 'Кол-во' }, { v: 'UNIT', l: 'Ед. изм.' }, { v: 'PRICE', l: 'Цена' }, { v: 'SUM', l: 'Сумма' },
  { v: 'VAT', l: 'НДС' }, { v: 'CURRENCY', l: 'Валюта' }, { v: 'NOTE', l: 'Примечание' }, { v: 'NUMBER', l: '№' },
  { v: 'IGNORE', l: 'Игнорировать' },
];

export interface ReplyHead {
  id: number; status: string; supplierName: string; tenderId: number; tenderNumber: string | null; privateRequest: boolean;
  sentAt: string | null; responseDate: string | null; currency: string | null;
}
export interface ReplyRowRef {
  fileRow: number; text: string; quantity: number | null; unit: string | null; price: number | null; priceComputed: boolean;
  note: string | null; score: number;
  /** валюта строки файла (код KZT / RUB / USD / EUR / CNY / UE); чужая для рынка — цена не подставляется */
  currency: string | null;
}
export interface ReplyPosition {
  itemId: number; ordinal: number; name: string; brand: string | null; model: string | null; quantity: number | null;
  unit: string | null; price: number | null; auto: boolean; note: string | null; fileId: number | null; fileRow: number | null;
  row: ReplyRowRef | null; suggestion: ReplyRowRef | null; flags: string[];
}
export interface ReplySource {
  type: 'FILE' | 'EMAIL'; fileId: number | null; inboundId: number | null; name: string; kind: string | null;
  parseStatus: string | null; parseNote: string | null; sizeBytes: number | null; pageCount: number | null; uploaded: boolean;
  at: string | null; from: string | null; subject: string | null; text: string | null;
}
export interface ReplyColumn { index: number; header: string; role: ReplyRole; }
export interface ReplyTableRow {
  fileRow: number; cells: string[]; ordinal: number | null; priced: boolean; text: string; price: number | null; currency: string | null;
}

/** Подпись валюты файла рядом с числом (как MoneyText.label на сервере). */
export function currencyLabel(code: string | null): string {
  const labels: Record<string, string> = { KZT: 'тг', RUB: 'руб.', USD: '$', EUR: '€', CNY: 'юань', UE: 'у.е.' };
  return code ? labels[code] || code : '';
}
export interface ReplyTableView { source: string; headerRow: number; currency: string | null; columns: ReplyColumn[]; rows: ReplyTableRow[]; }
export interface ReplyCounts { unverified: number; noPrice: number; unassignedRows: number; }
export interface SupplierReply {
  head: ReplyHead; positions: ReplyPosition[]; sources: ReplySource[]; fileId: number | null; table: ReplyTableView | null; counts: ReplyCounts;
}

/** Куда вернуться из ответа: частная заявка или карточка тендера. */
export function backLink(h: ReplyHead): { commands: any[]; query: Record<string, any>; label: string } {
  return h.privateRequest
    ? { commands: ['/private-requests', h.tenderId], query: {}, label: h.tenderNumber || 'Заявка' }
    : { commands: ['/tenders'], query: { openId: h.tenderId }, label: 'Тендер ' + (h.tenderNumber || '') };
}

/** Подпись источника: «КП.xlsx», «Письмо 15.10 10:20». */
export function sourceLabel(s: ReplySource): string {
  if (s.type === 'FILE') return s.name;
  if (!s.at) return 'Письмо';
  const d = new Date(s.at);
  const p = (n: number) => String(n).padStart(2, '0');
  return `Письмо ${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
