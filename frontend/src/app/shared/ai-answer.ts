import { shortProduct } from './product-watch';

export type AiVerdict = 'GO' | 'NO' | 'UNSURE' | 'ANALOG';
export type AiOutcome = 'PINNED' | 'SAME_MODEL' | 'KEPT_MANUAL' | 'ADVICE' | 'ANALOG' | 'NOT_OURS' | 'UNSURE' | 'CLOSED'
  | 'LOT_NOT_FOUND' | 'PRODUCT_NOT_CANDIDATE' | 'PRODUCT_REMOVED';
export interface AiLotResult { lotId: number; tenderId: number | null; tenderNumber: string | null; lotName: string | null;
  customer: string | null; verdict: AiVerdict; outcome: AiOutcome; model: string | null; text: string; }
export interface AiLetterPlan { tenderId: number; tenderNumber: string | null; customer: string | null; distributorId: number;
  distributorName: string; manufacturer: boolean; lotIds: number[]; lotNames: string[]; }
export interface AiAnswerResult { lots: AiLotResult[]; unparsed: string[]; letters: AiLetterPlan[]; notes: string[]; }

/**
 * Строка вывода ИИ у лота (спека 2026-10-21-tender-telegram-bot §10 п. 1): «ИИ: идти — ДКИ-Н-12 · почему · vlad, Telegram,
 * 21.10», «аналог: CUSA Clarity, Integra, РУ …» (наш не подходит, можно чужой моделью), «не наше: …», «не ясно: …»; значок 🤖
 * ставит шаблон.
 */
export function aiLotLine(l: { aiVerdict?: AiVerdict | null; aiProductName?: string | null; aiNote?: string | null;
                               aiBy?: string | null; aiAt?: string | null }): string {
  const day = l.aiAt ? new Date(l.aiAt).toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit', timeZone: 'Asia/Oral' }) : null;
  const who = [l.aiBy, day].filter(Boolean).join(', ');
  const why = l.aiNote || '';
  if (l.aiVerdict === 'GO') {
    return ['ИИ: идти' + (l.aiProductName ? ' — ' + shortProduct(l.aiProductName) : ''), why, who].filter(s => !!s).join(' · ');
  }
  const head = l.aiVerdict === 'NO' ? 'не наше' : l.aiVerdict === 'ANALOG' ? 'аналог' : 'не ясно';
  return (why ? head + ': ' + why : head) + (who ? ' · ' + who : '');
}
