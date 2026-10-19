/** Единая страница сделки (спека 2026-10-18): этапы и «Дальше», лента «Переписка и история». */
export type StepState = 'DONE' | 'CURRENT' | 'TODO' | 'SKIPPED';
export interface DealStep { key: string; state: StepState; label: string; detail: string | null; }
export interface DealNext { text: string; action: string | null; refId: number | null; hintId?: number | null; }
export interface DealProgress { steps: DealStep[]; next: DealNext | null; }
export interface DealTimelineItem {
  at: string; dateOnly: boolean; kind: string; text: string | null; author: string | null;
  linkType: string | null; linkId: number | null; message: any | null;
}
export interface DealTimeline { items: DealTimelineItem[]; hasMore: boolean; }
export type DealKind = 'private-request' | 'tender';
export interface DealAction { action: string; refId: number | null; hintId?: number | null; decision?: 'ACCEPTED' | 'REJECTED'; }

export const ACTION_LABELS: Record<string, string> = {
  ADD_LINES: 'К позициям', REQUEST_PRICES: 'Запросить цены…', OPEN_PRICES: 'Таблица цен', CREATE_OFFER: 'Создать КП клиенту',
  OPEN_OFFER: 'Открыть КП', CLOSE_WON: 'Закрыть: купили у нас', TO_LOTS: 'К лотам', OPEN_REQUESTS: 'Запросы КП',
  OPEN_APPLY: 'Открыть заявку', ATTACH_OFFER_FILE: 'Занести КП', MARK_OFFER_SENT: 'Отметить отправленным',
};

export const KIND_ICONS: Record<string, string> = {
  LEAD: '📥', LEAD_NOTE: '📝', LEAD_CALL: '📞', LEAD_STATUS: '↻', CREATED: '＋', TENDER_FOUND: '🔎', RFQ_SENT: '✉',
  LETTER_FAILED: '⚠', BOUNCED: '⚠', DELAYED: '⏳', REPLY: '📩', DECLINED: '⛔', REPLY_FILE: '📎', OFFER_CREATED: '📄',
  OFFER_SENT: '📄', NOTE: '📝', CALL: '📞', STAGE: '✓', APPLY_CREATED: '📋', CONTRACT: '✍', DELIVERED: '🚚', PAID: '💳',
};

/** Куда ведёт строка ленты. */
export function timelineLink(i: DealTimelineItem): { path: any[]; query?: Record<string, any> } | null {
  if (i.linkId == null) return null;
  switch (i.linkType) {
    case 'PRICE_REQUEST': return { path: ['/price-requests', i.linkId] };
    case 'OFFER': return { path: ['/client-offers', i.linkId] };
    case 'CHAT': return { path: ['/chats'], query: { chatId: i.linkId } };
    case 'LEAD': return { path: ['/leads'], query: { openId: i.linkId } };
    case 'APPLY': return { path: ['/applies'], query: { openId: i.linkId } };
    default: return null;
  }
}
