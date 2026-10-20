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

export const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];

/** «ноябрь 2026»; месяц не указан — только год. */
export function monthText(year: number | null, month: number | null): string {
  return month && month >= 1 && month <= 12 ? `${MONTHS[month - 1]} ${year ?? ''}`.trim() : `${year ?? ''}`;
}

/** «19.10.2026» по часам браузера (без DatePipe — см. whenText). */
export function dayText(iso: string | null | undefined): string {
  if (!iso) return '';
  const d = new Date(iso);
  if (isNaN(d.getTime())) return '';
  const p = (n: number) => String(n).padStart(2, '0');
  return `${p(d.getDate())}.${p(d.getMonth() + 1)}.${d.getFullYear()}`;
}

/** «5 920 000,00» — деньги плана (валюта — тенге, подпись рядом). */
export function planMoney(n: number | null | undefined): string {
  if (n == null) return '';
  return new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(n);
}

/** Единица плана коротко — как в сводке Telegram: «Штука» → «шт.», «Комплект» → «компл.». */
export function unitShort(u: string | null | undefined): string {
  const s = (u || '').trim().toLowerCase();
  const map: Record<string, string> = { 'штука': 'шт.', 'штука условная': 'усл. шт.', 'комплект': 'компл.', 'упаковка': 'уп.', 'рулон': 'рул.' };
  return map[s] ?? s;
}
