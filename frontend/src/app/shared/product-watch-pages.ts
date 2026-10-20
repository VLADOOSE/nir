/**
 * Помощники ленивых страниц «нашей продукции» (спека 2026-10-19-product-watch): отдельно от shared/product-watch.ts,
 * который импортирует не ленивая страница «Тендеры», — иначе их код лежал бы в начальном бандле.
 */
import type { TenderSearchHit } from './product-watch';

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

/** Номера позиций диапазонами: [1,2,3,5] → «1–3, 5». */
export function positionsText(xs: number[]): string {
  const s = [...xs].sort((a, b) => a - b);
  const out: string[] = [];
  for (let i = 0; i < s.length; i++) {
    let j = i;
    while (j + 1 < s.length && s[j + 1] === s[j] + 1) j++;
    out.push(j > i ? `${s[i]}–${s[j]}` : `${s[i]}`);
    i = j;
  }
  return out.join(', ');
}

/** «приём до 20.10 09:00», «не состоялся 16.10», «план: ноябрь 2026»… (дата — срок объявления, как его пишет площадка). */
export function stateText(h: TenderSearchHit): string {
  const d = h.deadlineAt && h.deadlineAt.length >= 10 ? `${h.deadlineAt.slice(8, 10)}.${h.deadlineAt.slice(5, 7)}` : '';
  const t = h.deadlineAt && h.deadlineAt.length >= 16 ? ' ' + h.deadlineAt.slice(11, 16) : '';
  switch (h.state) {
    case 'OPEN': return `приём до ${d}${t}`.trim();
    case 'FAILED': return `не состоялся ${d}`.trim();
    case 'CLOSED': return `приём закрыт ${d}`.trim();
    case 'DONE': return `состоялся ${d}`.trim();
    case 'CANCELLED': return 'отменён';
    default: return 'план: ' + monthText(h.planYear, h.planMonth);
  }
}
