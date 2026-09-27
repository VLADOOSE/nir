export const LEAD_STATUS_LABELS: Record<string, string> = {
  NEW: 'Новое', IN_WORK: 'В работе', CONVERTED: 'Заявка создана', CLOSED: 'Закрыто',
};

export const LEAD_CHANNEL_LABELS: Record<string, string> = {
  SITE: 'Сайт', PHONE: 'Звонок', WHATSAPP: 'WhatsApp', EMAIL: 'Почта', OTHER: 'Другое',
};

export const LEAD_CLOSE_REASONS = [
  { v: 'ANSWERED', l: 'Ответили клиенту без заявки' },
  { v: 'SPAM', l: 'Спам' },
  { v: 'DUPLICATE', l: 'Дубль' },
  { v: 'NOT_OUR_PROFILE', l: 'Не наш профиль' },
  { v: 'CLIENT_DECLINED', l: 'Клиент отказался' },
  { v: 'OTHER', l: 'Другое' },
];

/** У заявок с сайта показываем сам сайт («westmed.kz», позже «vital-spb.kz»), у остальных — канал. */
export function leadChannelLabel(channel: string, source?: string | null): string {
  if (channel === 'SITE' && source) return source;
  return LEAD_CHANNEL_LABELS[channel] || channel;
}
