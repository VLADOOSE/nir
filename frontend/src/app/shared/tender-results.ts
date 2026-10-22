/** Итоги тендеров (спека 2026-10-22-tender-results-market-prices §11): типы ответов и помощники экрана. */
export type LotOutcome = 'PENDING' | 'DONE' | 'FAILED' | 'CANCELLED';

export interface LotResultSummary {
  outcome: LotOutcome; text: string; failReason: string | null; bidsCount: number | null; winnerName: string | null;
  winnerPrice: number | null; ourPlace: number | null; ourRejected: boolean | null; contract: boolean; protocolStatus: string | null;
  resultsAt: string | null;
}

export interface TenderResultSummary { chip: string; call: boolean; done: boolean; ours: boolean; }

export interface LotResultBid {
  place: number | null; name: string | null; bin: string | null; unitPrice: number | null; total: number | null;
  submittedAt: string | null; rejected: boolean; rejectReason: string | null; winner: boolean; second: boolean; ours: boolean;
}

export interface LotResultContract {
  number: string; signedAt: string | null; unitPrice: number | null; sum: number | null; supplierBin: string | null;
  supplierName: string | null;
}

export interface LotResultAttempt {
  anno: string; lotNumber: string | null; method: string | null; methodName: string; status: string | null; statusId: number | null;
  publishedAt: string | null; resultsAt: string | null; supplierBin: string | null; supplierName: string | null; tenderId: number | null;
  own: boolean;
}

export interface LotResultView {
  lotId: number; outcome: LotOutcome | null; text: string; lotStatus: string | null; failReason: string | null; failText: string | null;
  resultsAt: string | null; protocolUrl: string | null; protocolName: string | null; protocolStatus: string | null;
  protocolNote: string | null; bidsCount: number | null; rejectedCount: number | null; bids: LotResultBid[];
  contract: LotResultContract | null; attempts: LotResultAttempt[]; nextTenderId: number | null; nextTenderNumber: string | null;
  checkedAt: string | null; finalAt: string | null;
}

export interface CustomerContacts { bin: string | null; name: string | null; phone: string | null; email: string | null; }

export interface CustomerHistoryRow {
  signedAt: string | null; name: string | null; quantity: number | null; unit: string | null; unitPrice: number | null;
  supplierName: string | null; region: string | null; sameCategory: boolean; anno: string | null;
}

export interface CustomerHistory { bin: string | null; rows: CustomerHistoryRow[]; fetchedAt: string | null; error: string | null; }

export interface ResultsCheck { done: boolean; ok: boolean; message: string | null; }

/** Состоялся — зелёный, не состоялся — красный, прочее — приглушённый. */
export function resultTone(outcome: string | null | undefined): 'success' | 'danger' | 'muted' {
  return outcome === 'DONE' ? 'success' : outcome === 'FAILED' ? 'danger' : 'muted';
}

/** Фильтр «Итоги» списка: '' — все. */
export const RESULT_LABELS: Record<string, string> = { CALL: 'Не состоялся — звонить', DONE: 'Состоялся', OURS: 'Мы участвовали' };

export function resultMatches(r: TenderResultSummary | null | undefined, filter: string): boolean {
  if (!filter) return true;
  if (!r) return false;
  return filter === 'CALL' ? r.call : filter === 'DONE' ? r.done : filter === 'OURS' ? r.ours : true;
}

/** Время площадки без пояса строкой: «2026-10-14» → «14.10.2026», «2026-10-14T12:54:49» → «14.10.2026 12:54». */
export function plainDate(iso: string | null | undefined): string {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2}))?/.exec(iso || '');
  if (!m) return '';
  return `${m[3]}.${m[2]}.${m[1]}` + (m[4] ? ` ${m[4]}:${m[5]}` : '');
}

/** Момент времени (с поясом) — по Уральску. */
export function instantText(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '' : d.toLocaleString('ru-RU', { timeZone: 'Asia/Oral', day: '2-digit', month: '2-digit',
    year: 'numeric', hour: '2-digit', minute: '2-digit' });
}

/** Объявление на портале goszakup по номеру «17758520-1». */
export function announceUrl(anno: string): string {
  return 'https://goszakup.gov.kz/ru/announce/index/' + encodeURIComponent((anno || '').split('-')[0]);
}
