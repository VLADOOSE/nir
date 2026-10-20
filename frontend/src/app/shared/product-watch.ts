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

/** Слова через запятую ↔ список (страница правит списки строкой). */
export function wordsText(xs: string[] | null | undefined): string { return (xs || []).join(', '); }
export function wordsList(s: string): string[] { return (s || '').split(',').map(x => x.trim()).filter(x => !!x); }

/**
 * «20.10 09:30» по часам браузера — без DatePipe: его код в ленивой странице оптимизатор чанков переносил в начальный
 * бандл (+9 кБ, замер 2026-10-20).
 */
export function whenText(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getDate())}.${p(d.getMonth() + 1)} ${p(d.getHours())}:${p(d.getMinutes())}`;
}
