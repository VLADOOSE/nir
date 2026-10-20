import type { FitCandidateView } from './product-watch';

/** «Модель в работе» лота (спека 2026-10-20-lot-model-in-work). */
export type ModelSource = 'SITE' | 'REGISTRY' | 'CATALOG';
export interface OurModelHit {
  productId: number; name: string; brand: string | null; sku: string | null; regNumber: string | null;
  regStatus: string; regExpires: string | null; url: string | null;
}
export interface RegistryModelHit {
  regNumber: string; name: string; producer: string | null; country: string | null; expires: string | null;
  unlimited: boolean; expired: boolean;
}
export interface LotModelSearch { ours: OurModelHit[]; registry: RegistryModelHit[]; }
export interface LotModelInfo {
  equipmentId: number; name: string; manufact: string | null; source: ModelSource; siteProductId: number | null;
  url: string | null; regNumber: string | null; regStatus: string; regExpires: string | null; regUnlimited: boolean;
}
export interface LotModelFit {
  model: LotModelInfo | null; note: string | null; setBy: string | null; setAt: string | null;
  card: 'OK' | 'MISSING' | 'NONE'; error: string | null; fit: FitCandidateView | null;
}

export const MODEL_SOURCE: Record<ModelSource, string> = { SITE: 'наш товар', REGISTRY: 'реестр', CATALOG: 'каталог' };

/** «2026-02-01» → «01.02.2026» — строкой, без new Date (пояс браузера сдвинул бы день). */
export function isoDate(s: string | null | undefined): string {
  return s ? s.slice(0, 10).split('-').reverse().join('.') : '';
}

/** «РУ ✓», «РУ истекло 01.02.2026», «РУ не найдено», «РУ не указано», «РУ не проверено». */
export function regShort(status: string | null | undefined, expires: string | null | undefined): string {
  if (status === 'ACTIVE') return 'РУ ✓';
  if (status === 'EXPIRED') return 'РУ истекло' + (expires ? ' ' + isoDate(expires) : '');
  if (status === 'NOT_FOUND') return 'РУ не найдено';
  if (status === 'NOT_SPECIFIED') return 'РУ не указано';
  return 'РУ не проверено';
}
