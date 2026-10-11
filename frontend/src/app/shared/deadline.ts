import type { Market } from '../services/market.service';

/**
 * Срок приёма заявок тендера (B2). У импортного тендера — время площадки `deadlineAt` («2026-10-09T10:25:00»,
 * местное время площадки без пояса), у ручного — только дата `deadline`: приём идёт весь этот день.
 * Время площадки показывается как есть; момент окончания считается по часам рынка, а не браузера —
 * у оператора в другом поясе «до 10:25» иначе сдвинулось бы.
 */
export interface Deadlined {
  deadline?: string | null;
  deadlineAt?: string | null;
}

/** Смещение часов рынка: KZ — Уральск (весь Казахстан с 2024-03-01), RF — Самара; перехода на летнее время нет. */
const OFFSET: Record<Market, string> = { KZ: '+05:00', RF: '+04:00' };

/** «09.10.2026 10:25», «09.10.2026» или «—». */
export function deadlineText(t: Deadlined | null | undefined): string {
  const at = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})/.exec(t?.deadlineAt ?? '');
  if (at) return `${at[3]}.${at[2]}.${at[1]} ${at[4]}:${at[5]}`;
  const d = /^(\d{4})-(\d{2})-(\d{2})/.exec(t?.deadline ?? '');
  return d ? `${d[3]}.${d[2]}.${d[1]}` : '—';
}

/** Момент окончания приёма (мс), null — срока нет. Только дата — конец этого дня. */
export function deadlineEnd(t: Deadlined | null | undefined, market: Market): number | null {
  if (t?.deadlineAt) {
    const ms = Date.parse(t.deadlineAt.slice(0, 19) + OFFSET[market]);
    if (!isNaN(ms)) return ms;
  }
  if (t?.deadline) {
    const ms = Date.parse(t.deadline.slice(0, 10) + 'T00:00:00' + OFFSET[market]);
    if (!isNaN(ms)) return ms + 24 * 60 * 60 * 1000;
  }
  return null;
}

/** Приём закончился. */
export function deadlinePassed(t: Deadlined | null | undefined, market: Market, now = Date.now()): boolean {
  const end = deadlineEnd(t, market);
  return end != null && end <= now;
}
