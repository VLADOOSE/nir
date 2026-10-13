import type { Observable } from 'rxjs';
import { dateText } from './private-request';
import type { RegAction, RegistryRecord } from './private-request';

/** Статус регистрации для метки: частная заявка (RegStatus) и строка КП (ещё UNCHECKED и MANUAL). */
export type ChipStatus = 'CONFIRMED' | 'SUGGESTED' | 'NOT_FOUND' | 'NOT_REQUIRED' | 'MANUAL' | 'UNCHECKED' | null | undefined;

/** Что метка делает со строкой: заявка — запросом, КП — правкой строки и автосохранением (спека 2026-10-13 §2.2). */
export interface RegistryChipActions {
  /** Кандидаты при «Найти в реестре» — до своего запроса. */
  candidates(): Observable<RegistryRecord[]>;
  /** Подтвердить (record — выбранная запись; без неё — текущая подсказка), «не подлежит», вернуть проверку. */
  act(action: RegAction, record?: RegistryRecord): Observable<unknown>;
}

/** РУ истекло: срок записи раньше сегодняшней даты рынка; бессрочное не истекает. */
export function regExpired(r: RegistryRecord | null | undefined, today: string): boolean {
  return !!r && !r.unlimited && !!r.expirationDate && r.expirationDate.slice(0, 10) < today;
}

/**
 * Подпись метки (спека 2026-10-13 §2.1; решение плана 4 — один словарь для заявки и КП). «РУ не найдено», а не «нет в
 * реестре»: автоматическая проверка не находит подсказку и у зарегистрированного изделия — без производителя, с
 * производителем латиницей при кириллице в реестре (ревью ветки, I1). Красная — только «РУ истекло»: это факт.
 */
export function chipLabel(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): string {
  switch (s) {
    case 'CONFIRMED': return regExpired(r, today) ? 'РУ истекло ' + dateText(r!.expirationDate) : 'РУ ✓';
    case 'SUGGESTED': return 'РУ?';
    case 'NOT_FOUND': return 'РУ не найдено';
    case 'NOT_REQUIRED': return 'не подлежит';
    case 'MANUAL': return 'РУ вручную';
    default: return 'не проверено';
  }
}

/** data-status метки: истёкшее подтверждённое — отдельный тон. */
export function chipTone(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): string {
  if (s === 'CONFIRMED' && regExpired(r, today)) return 'EXPIRED';
  return s ?? 'UNCHECKED';
}

/** Позицию нельзя продать больнице без проверки: не проверено, «РУ?», «РУ не найдено», РУ истекло (сводка, §2.1). */
export function unregistered(s: ChipStatus, r: RegistryRecord | null | undefined, today: string): boolean {
  return s == null || s === 'UNCHECKED' || s === 'SUGGESTED' || s === 'NOT_FOUND' || (s === 'CONFIRMED' && regExpired(r, today));
}
