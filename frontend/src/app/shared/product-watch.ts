/** «Наша продукция в тендерах» (спека 2026-10-19-product-watch): типы и помощники экранов. */
export type MatchLevel = 'TYPE' | 'NAMED' | 'COPIED';

export interface ProductGroup {
  id: number | null; name: string; searchWords: string[]; requireWords: string[]; excludeWords: string[];
  brandWords: string[]; active: boolean; brandGroup: boolean;
}

/** Итог прогона (ImportSummary сервера) — то, что показывает страница. */
export interface ImportSummaryLite {
  enabled: boolean; fetched: number; matched: number; created: number; updated: number; skipped: number; errors: number;
  message: string | null; lastError: string | null;
}

export interface WatchStatus {
  enabled: boolean; running: boolean; current: string | null;
  lastSearchAt: string | null; lastSearch: ImportSummaryLite | null;
  lastPlansAt: string | null; lastPlans: ImportSummaryLite | null;
  lastCatalogAt: string | null; lastCatalog: string | null;
}

export interface ProductGroupsState {
  kz: boolean; groups: ProductGroup[];
  catalog: { count: number; byBrand: Record<string, number>; syncedAt: string | null; stale: boolean } | null;
  watch: WatchStatus | null;
}

export interface ProbeLot {
  numberAnno: string; lotName: string; announcement: string; customer: string; deadlineAt: string | null; passed: boolean; reason: string;
}
export interface ProbeResult { found: number; passed: number; lots: ProbeLot[]; }
/** Сводка «наш товар» тендера в списке (спека §12.2). */
export interface OurProduct { level: MatchLevel; label: string; groups: string[]; }

/** Отметка «наш товар» лота в карточке тендера (спека §12.3): ссылки — на товары сайта. */
export interface LotProductMatch {
  groupName: string; word: string; level: MatchLevel; evidence: string | null; productName: string | null; productUrl: string | null;
  similar: { name: string; brand: string | null; url: string | null }[];
}

export const OUR_LABELS: Record<string, string> = { ANY: 'Любой наш вид', EXACT: '★ Точно наше' };

/** Проходит ли тендер фильтр «Наш товар»: '' — все; 'ANY' — любая отметка; 'EXACT' — ★; 'g:<группа>' — эта группа. */
export function ourMatches(our: OurProduct | null | undefined, filter: string): boolean {
  if (!filter) return true;
  if (!our) return false;
  if (filter === 'ANY') return true;
  if (filter === 'EXACT') return our.level !== 'TYPE';
  return filter.startsWith('g:') && our.groups.includes(filter.slice(2));
}

/** Пункт плана закупок на странице «Планы закупок» (спека §10.2). */
export interface PlanPoint {
  id: number; extId: number; year: number; month: number | null; monthPassed: boolean; customerName: string | null;
  region: string | null; name: string; description: string | null; quantity: number | null; unit: string | null;
  unitPrice: number | null; amount: number | null; tradeMethod: string | null; groups: string[]; level: MatchLevel;
  firstSeenAt: string; goneStatus: string | null;
}

/** «Найти тендеры» по стране в частной заявке (спека §11). */
export type SearchState = 'OPEN' | 'FAILED' | 'CLOSED' | 'DONE' | 'CANCELLED' | 'PLANNED';
export interface TenderSearchHit {
  kind: 'TENDER' | 'PLAN'; numberAnno: string | null; lotNumber: string | null; lotName: string; announcement: string | null;
  customer: string | null; region: string | null; quantity: number | null; unitPrice: number | null; amount: number | null;
  deadlineAt: string | null; state: SearchState; tradeMethod: string | null; score: number; positions: number[];
  specPositions: number[]; tenderId: number | null; planYear: number | null; planMonth: number | null;
}
export interface TenderSearchStatus {
  running: boolean; progress: number; total: number; startedAt: string | null; waiting: string | null; error: string | null;
  hits: TenderSearchHit[];
}
export interface AnnouncementResult {
  status: string; supplierName: string | null; supplierBin: string | null; contractSum: number | null; signDate: string | null;
}
