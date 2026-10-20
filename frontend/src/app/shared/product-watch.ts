/** «Наша продукция в тендерах» (спека 2026-10-19-product-watch): типы и помощники экранов. */
export type MatchLevel = 'TYPE' | 'FITS' | 'NAMED' | 'COPIED';
export type FitVerdict = 'FITS' | 'FITS_NO_RU' | 'PARTIAL' | 'NOT_FIT' | 'NO_REQUIREMENTS';
export type FitMark = 'OK' | 'FAIL' | 'DIFF' | 'UNKNOWN';
export interface FitRowView { mark: FitMark; text: string; ours: string | null; source: string | null; }
export interface FitCandidateView {
  productId: number; name: string; url: string | null; verdict: FitVerdict; met: number; failed: number; total: number;
  unknown: number; reason: string | null; reasonShort: string | null; regStatus: string; regNumber: string | null; rows: FitRowView[];
}

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
/** Товар сайта с РУ — вкладка «Наша продукция → Товары» (спека 2026-10-20-product-spec-fit §8.2). */
export interface ProductCatalogItem {
  id: number; name: string; brand: string | null; url: string | null; regNumber: string | null; regStatus: string;
  regExpires: string | null; regUnlimited: boolean; attributesCount: number;
}
export interface ProductCatalogState { kz: boolean; checkedAt: string | null; items: ProductCatalogItem[]; }

/** Сводка «наш товар» тендера в списке (спека §12.2). */
export interface OurProduct { level: MatchLevel; label: string; groups: string[]; }

/** Отметка «наш товар» лота в карточке тендера (спеки §12.3 / 2026-10-20-product-spec-fit §8.1): ссылки — на товары сайта. */
export interface LotProductMatch {
  groupName: string; word: string; level: MatchLevel; evidence: string | null; productName: string | null; productUrl: string | null;
  similar: { name: string; brand: string | null; url: string | null }[];
  fit: FitVerdict | null; foreignBrand: string | null; candidates: FitCandidateView[];
}

export const OUR_LABELS: Record<string, string> = { ANY: 'Любой наш вид', EXACT: '★ Подходит или точно наше' };

/** Текст РУ для «подходит по ТЗ, но …» (как RegStatus.noRuText сервера). */
export const NO_RU: Record<string, string> = {
  EXPIRED: 'РУ истекло', NOT_FOUND: 'РУ не найдено в реестре', NOT_SPECIFIED: 'РУ не указано на сайте', UNKNOWN: 'РУ не проверено',
};

/** Название товара без хвоста «. Набор …» и не длиннее 60. */
export function shortProduct(name: string | null | undefined): string {
  if (!name) return '';
  const dot = name.indexOf('. ');
  const s = dot > 0 ? name.slice(0, dot) : name;
  return s.length <= 60 ? s : s.slice(0, 59) + '…';
}

/** Чип отметки лота (спека 2026-10-20-product-spec-fit §8.1): ★ — как раньше, иначе вердикт сверки, нет сверки — «🏷 группа». */
export function fitChip(m: LotProductMatch): { text: string; tone: 'success' | 'warn' | 'muted' | 'accent' } {
  if (m.level === 'COPIED') return { text: '★ ТЗ списано с нашего', tone: 'success' };
  if (m.level === 'NAMED') return { text: '★ назван наш товар', tone: 'success' };
  const b = m.candidates?.[0];
  switch (m.fit) {
    case 'FITS': return { text: `✓ подходит: ${shortProduct(b?.name)} · ${b?.met} из ${b?.total}`, tone: 'success' };
    case 'FITS_NO_RU': return { text: `подходит по ТЗ, ${NO_RU[b?.regStatus ?? 'UNKNOWN'] ?? 'РУ не проверено'}`, tone: 'warn' };
    case 'PARTIAL': return { text: '◐ похоже — проверьте', tone: 'warn' };
    case 'NOT_FIT': return { text: `✗ не подходит: ${b?.reasonShort ?? 'см. сверку'}`, tone: 'muted' };
    default: return { text: '🏷 ' + m.groupName, tone: 'accent' };
  }
}

/** Проходит ли тендер фильтр «Наш товар»: '' — все; 'ANY' — любая отметка; 'EXACT' — ★ или «✓ подходит»; 'g:<группа>' — эта группа. */
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
