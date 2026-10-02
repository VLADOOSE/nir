import { relativeTime } from './relative-time';

/** Ответ GET /api/chats/status (спеки whatsapp-chats §7, whatsapp-waha §7). */
export interface WhatsappStatus {
  enabled: boolean;
  configured: boolean;
  /** Шлюз: waha / greenapi; опечатка в WHATSAPP_PROVIDER приходит как есть (configured=false, lastError объясняет). */
  provider: string | null;
  state: string | null;
  number: string | null;
  lastMessageAt: string | null;
  warnings: string[] | null;
  lastError: string | null;
  /** Сколько сообщений за сутки пропущено как «ядовитые». */
  droppedCount: number;
  /** Рынок, в который пишет зеркало (KZ/RF). */
  market: string | null;
}

export interface StatusLine { text: string; error: boolean; }

const WARNING_TEXT: Record<string, string> = {
  WEBHOOK_URL_SET: 'в инстансе указан адрес вебхука — сообщения уходят туда, а не в АИС. Очистите поле в кабинете Green-API',
  INCOMING_OFF: 'не включены уведомления о входящих',
  OUTGOING_PHONE_OFF: 'не включены уведомления об ответах с телефона — ваши ответы не попадут в АИС',
  QUOTA_EXCEEDED: 'исчерпан лимит бесплатного тарифа — сообщения из новых чатов не приходят',
  CATCH_UP_FAILED: 'не удалось догнать сообщения, пришедшие без АИС, — повтор через 10 мин',
  WEBHOOK_REJECTED: 'WAHA присылает события с чужой подписью — WHATSAPP_WAHA_HMAC_KEY в .env разошёлся с WAHA (docker compose up -d); правки, удаления и звонки не доходят',
  WEBHOOK_TOO_LARGE: 'WAHA прислала событие больше 4 МБ — АИС его не приняла: сообщение, правка или звонок могли не дойти (подробности в логе сервера)',
};

/** Подписи — как в селекторе рынка слева вверху. */
const MARKET_LABEL: Record<string, string> = { KZ: 'West-Med (KZ)', RF: 'Регион-Мед (РФ)' };

function droppedText(n: number): string {
  return n > 1
    ? `не удалось принять сообщений: ${n} — посмотрите их в телефоне`
    : 'одно сообщение не удалось принять — посмотрите его в телефоне';
}

const STATE_TEXT: Record<string, StatusLine> = {
  // Green-API
  notAuthorized: { text: 'WhatsApp: номер не подключён — отсканируйте QR-код в кабинете Green-API', error: true },
  blocked: { text: 'WhatsApp: номер заблокирован WhatsApp', error: true },
  suspended: { text: 'WhatsApp: временные ограничения WhatsApp на номере', error: true },
  sleepMode: { text: 'WhatsApp: телефон выключен — сообщения придут, когда он появится в сети', error: true },
  starting: { text: 'WhatsApp: инстанс запускается', error: false },
  // WAHA (спека whatsapp-waha §7)
  SCAN_QR_CODE: { text: 'WhatsApp: номер не подключён — привяжите в «Система → WhatsApp»', error: true },
  FAILED: { text: 'WhatsApp: сессия WhatsApp упала — перезапустите в «Система → WhatsApp»', error: true },
  STOPPED: { text: 'WhatsApp: сессия остановлена — запустите в «Система → WhatsApp»', error: true },
  STARTING: { text: 'WhatsApp: подключается…', error: false },
  PASSKEY_REQUIRED: { text: 'WhatsApp: нужно подтверждение на рабочем телефоне', error: true },
  PASSKEY_CONFIRMATION_REQUIRED: { text: 'WhatsApp: нужно подтверждение на рабочем телефоне', error: true },
};

/** «Подключён»: у Green-API — authorized, у WAHA — WORKING. */
const CONNECTED = ['authorized', 'WORKING'];

function notConfigured(provider: string | null): string {
  return provider === 'greenapi' ? 'не заданы учётные данные Green-API' : 'не заданы ключи шлюза WAHA';
}

/** Одна строка: выключено → не настроено → ошибка → состояние → предупреждения → «подключён». */
export function whatsappStatusLine(s: WhatsappStatus | null): StatusLine | null {
  if (!s) return null;
  if (!s.enabled) return { text: 'WhatsApp: приём выключен', error: false };
  if (!s.configured) return { text: 'WhatsApp: ' + (s.lastError || notConfigured(s.provider)), error: true };
  if (s.lastError) return { text: 'WhatsApp: ' + s.lastError, error: true };
  if (!s.state) return { text: 'WhatsApp: подключение проверяется…', error: false };
  if (!CONNECTED.includes(s.state)) {
    return STATE_TEXT[s.state] || { text: 'WhatsApp: состояние шлюза — ' + s.state, error: true };
  }
  const warnings = (s.warnings || []).map(w => w === 'MESSAGE_DROPPED' ? droppedText(s.droppedCount) : WARNING_TEXT[w] || w);
  if (warnings.length) return { text: 'WhatsApp: ' + warnings.join('; '), error: true };
  const last = s.lastMessageAt ? ' · последнее сообщение ' + relativeTime(s.lastMessageAt) : '';
  return { text: 'WhatsApp' + (s.number ? ' ' + formatPhone(s.number) : '') + ' · подключён' + last, error: false };
}

/**
 * Чаты живут на рынке зеркала; на другом рынке список пуст, хотя строка говорит «подключён». Ловушка «не видно
 * данных»: новый браузер и иконка iPhone открываются на РФ (CLAUDE.md §14).
 */
export function marketHint(s: WhatsappStatus | null, current: string): string | null {
  if (!s?.enabled || !s.market || s.market === current) return null;
  return `Чаты WhatsApp ведутся на рынке ${MARKET_LABEL[s.market] || s.market}, а сейчас выбран `
    + `${MARKET_LABEL[current] || current} — переключите рынок слева вверху.`;
}

/** «77000000001» / «+77000000001» → «+7 700 000 00 01». */
export function formatPhone(raw: string): string {
  const d = (raw || '').replace(/\D/g, '');
  if (d.length === 11) return `+${d[0]} ${d.slice(1, 4)} ${d.slice(4, 7)} ${d.slice(7, 9)} ${d.slice(9)}`;
  return d ? '+' + d : '';
}
