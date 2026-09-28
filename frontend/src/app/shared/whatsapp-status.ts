import { relativeTime } from './relative-time';

/** Ответ GET /api/chats/status (спека whatsapp-chats §7). */
export interface WhatsappStatus {
  enabled: boolean;
  configured: boolean;
  state: string | null;
  number: string | null;
  lastMessageAt: string | null;
  warnings: string[] | null;
  lastError: string | null;
}

export interface StatusLine { text: string; error: boolean; }

const WARNING_TEXT: Record<string, string> = {
  WEBHOOK_URL_SET: 'в инстансе указан адрес вебхука — сообщения уходят туда, а не в АИС. Очистите поле в кабинете Green-API',
  INCOMING_OFF: 'не включены уведомления о входящих',
  OUTGOING_PHONE_OFF: 'не включены уведомления об ответах с телефона — ваши ответы не попадут в АИС',
  QUOTA_EXCEEDED: 'исчерпан лимит бесплатного тарифа — сообщения из новых чатов не приходят',
  MESSAGE_DROPPED: 'одно сообщение не удалось принять — посмотрите его в телефоне',
};

const STATE_TEXT: Record<string, StatusLine> = {
  notAuthorized: { text: 'WhatsApp: номер не подключён — отсканируйте QR-код в кабинете Green-API', error: true },
  blocked: { text: 'WhatsApp: номер заблокирован WhatsApp', error: true },
  suspended: { text: 'WhatsApp: временные ограничения WhatsApp на номере', error: true },
  sleepMode: { text: 'WhatsApp: телефон выключен — сообщения придут, когда он появится в сети', error: true },
  starting: { text: 'WhatsApp: инстанс запускается', error: false },
};

/** Одна строка: выключено → нет ключей → ошибка → состояние → предупреждения → «подключён». */
export function whatsappStatusLine(s: WhatsappStatus | null): StatusLine | null {
  if (!s) return null;
  if (!s.enabled) return { text: 'WhatsApp: приём выключен', error: false };
  if (!s.configured) return { text: 'WhatsApp: не заданы учётные данные Green-API', error: true };
  if (s.lastError) return { text: 'WhatsApp: ' + s.lastError, error: true };
  if (!s.state) return { text: 'WhatsApp: подключение проверяется…', error: false };
  if (s.state !== 'authorized') {
    return STATE_TEXT[s.state] || { text: 'WhatsApp: состояние инстанса — ' + s.state, error: true };
  }
  const warnings = (s.warnings || []).map(w => WARNING_TEXT[w] || w);
  if (warnings.length) return { text: 'WhatsApp: ' + warnings.join('; '), error: true };
  const last = s.lastMessageAt ? ' · последнее сообщение ' + relativeTime(s.lastMessageAt) : '';
  return { text: 'WhatsApp' + (s.number ? ' ' + formatPhone(s.number) : '') + ' · подключён' + last, error: false };
}

/** «77000000001» / «+77000000001» → «+7 700 000 00 01». */
export function formatPhone(raw: string): string {
  const d = (raw || '').replace(/\D/g, '');
  if (d.length === 11) return `+${d[0]} ${d.slice(1, 4)} ${d.slice(4, 7)} ${d.slice(7, 9)} ${d.slice(9)}`;
  return d ? '+' + d : '';
}
