/**
 * Частная заявка — типы, подписи и форматы страницы (спека docs/superpowers/specs/2026-10-12-private-request-workspace-design.md).
 * Импортируют ленивые страницы; ApiService — только `import type` (начальный бандл у предела, CLAUDE.md §12).
 */

export type PrStatus = 'NEW' | 'WON' | 'LOST' | 'CANCELLED';
export type RegStatus = 'SUGGESTED' | 'CONFIRMED' | 'NOT_FOUND' | 'NOT_REQUIRED' | null;
export type PrField = 'FACILITY' | 'CUSTOMER_NAME' | 'CONTACT_NAME' | 'CONTACT_PHONE' | 'CONTACT_EMAIL' | 'DELIVERY_ADDRESS' | 'NOTE';
export type PrLineField = 'NAME' | 'BRAND' | 'QUANTITY' | 'UNIT' | 'SPEC';
export type LinesMode = 'APPEND' | 'REPLACE';
export type RegAction = 'CONFIRM' | 'NOT_REQUIRED' | 'RESET';

export interface RegistryRecord {
  regNumber: string;
  name: string;
  producer: string | null;
  country: string | null;
  regDate: string | null;
  expirationDate: string | null;
  unlimited: boolean | null;
  score?: number | null;
}

export interface PrLine {
  lotId: number;
  lineNo: number;
  name: string;
  brand: string | null;
  quantity: number | null;
  unit: string | null;
  spec: string | null;
  productUrl: string | null;
  regStatus: RegStatus;
  registry: RegistryRecord | null;
  requested: boolean;
}

export interface LeadRef { id: number; subject: string; source: string; status: string; receivedAt: string; }

export interface PrView {
  id: number;
  number: string;
  status: PrStatus;
  stage: string;
  stageText: string;
  facilityId: number | null;
  facilityName: string | null;
  customerName: string | null;
  clientLabel: string;
  contactName: string | null;
  contactPhone: string | null;
  contactEmail: string | null;
  deliveryAddress: string | null;
  note: string | null;
  createdAt: string | null;
  lead: LeadRef | null;
  lines: PrLine[];
}

export interface PrListItem {
  id: number;
  number: string;
  status: PrStatus;
  stage: string;
  stageText: string;
  clientLabel: string;
  lineCount: number;
  createdAt: string | null;
  leadId: number | null;
}

export interface LineInput {
  name: string;
  brand?: string | null;
  quantity?: number | null;
  unit?: string | null;
  spec?: string | null;
  productUrl?: string | null;
}

export interface ParsedLine { name: string; quantity: number; unit: string | null; }
export interface SiteProduct { name: string; brand: string | null; sku: string | null; url: string | null; }

export interface SourcingLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  reason: 'MANUFACTURER' | 'BRAND' | 'TYPE';
  label: string;
  preselected: boolean;
  requestedAt: string | null;
}
export interface SourcingSupplier {
  distributorId: number;
  name: string;
  email: string | null;
  hasEmail: boolean;
  preselected: boolean;
  manufacturer: boolean;
  trusted: boolean;
  lines: SourcingLine[];
}
export interface Sourcing { suppliers: SourcingSupplier[]; unmatched: { lotId: number; name: string; brand: string | null }[]; }

export interface SendResult {
  priceRequestId: number;
  distributorId: number;
  distributorName: string;
  emailSent: boolean;
  reason: string | null;
}

export type CellState = 'PRICE' | 'DECLINED' | 'BOUNCED' | 'NOT_SENT' | 'WAITING' | 'NONE';
export interface PriceCell {
  distributorId: number;
  state: CellState;
  price: number | null;
  note: string | null;
  priceRequestId: number | null;
  itemId: number | null;
  best: boolean;
  /** Письмо ушло на адрес, вписанный в окне запроса, а не на почту карточки: «Переслать» отправит туда же. */
  emailOverride: boolean;
}
export interface PriceLine {
  lotId: number;
  name: string;
  quantity: number | null;
  unit: string | null;
  cells: PriceCell[];
  bestPrice: number | null;
  bestDistributorName: string | null;
}
export interface PriceSupplier { distributorId: number; name: string; firstSentAt: string | null; clientDisclosed: boolean; }
export interface Prices { suppliers: PriceSupplier[]; lines: PriceLine[]; }

/**
 * Сохранение по выходу из поля: разделы сообщают странице, та показывает «Сохраняю… / Сохранено · 14:05 / Не сохранено —
 * повторить» (спека §5.2). `retry` — только у идемпотентных правок поля (PATCH); добавление позиций не повторяется
 * (ответ мог потеряться после записи — повтор задвоил бы позиции).
 * `key` — какое поле сохраняется ('head:CONTACT_EMAIL', 'line:12:NAME'): ошибка поля держится, пока не сохранится ЭТО поле, —
 * удачное сохранение другого её не снимает (иначе «Сохранено» при значении, которого на сервере нет). Без `key`
 * (добавление позиций) ошибка живёт до следующего сохранения. `seq` — номер попытки (`nextSaveSeq`): ответ на прежнюю
 * попытку поля, пришедший после новой, состояние не меняет — «Повторить» отправил бы старое значение поверх нового.
 */
export interface SaveState { kind: 'saving' | 'saved' | 'error'; seq: number; key?: string; message?: string; retry?: () => void; }

let saveSeq = 0;
export function nextSaveSeq(): number {
  return ++saveSeq;
}

/** Тон чипа этапа — data-tone для глобального .pr-stage (styles.scss). */
export function stageTone(stage: string | null | undefined): string {
  switch (stage) {
    case 'REQUESTED': return 'accent';
    case 'PRICED': return 'warn';
    case 'WON': return 'success';
    case 'LOST': return 'danger';
    case 'CANCELLED':
    case 'EMPTY': return 'muted';
    default: return 'neutral';
  }
}

export const CLOSE_OUTCOMES: { v: PrStatus; l: string }[] = [
  { v: 'WON', l: 'купили у нас' },
  { v: 'LOST', l: 'не купили' },
  { v: 'CANCELLED', l: 'отменена' },
];

export const UNIT_SUGGESTIONS = ['шт', 'набор', 'упак', 'компл', 'фл', 'л', 'кг', 'м', 'пара'];

/** Метка РУ позиции (спека §5.3). «РУ не найдено», а не «нет в реестре»: у позиции без бренда подсказки нет, а изделие в реестре есть. */
export function regLabel(s: RegStatus): string {
  switch (s) {
    case 'CONFIRMED': return 'РУ ✓';
    case 'SUGGESTED': return 'РУ?';
    case 'NOT_FOUND': return 'РУ не найдено';
    case 'NOT_REQUIRED': return 'не подлежит';
    default: return 'РУ —';
  }
}

/** «бессрочно» / «до 07.04.2031» / пусто. */
export function regValidity(r: RegistryRecord | null | undefined): string {
  if (!r) return '';
  if (r.unlimited) return 'бессрочно';
  return r.expirationDate ? 'до ' + dateText(r.expirationDate) : '';
}

/** «2026-10-12» или ISO-время → «12.10.2026»; пусто для null. Дата без времени не уезжает на день в чужом поясе. */
export function dateText(iso: string | null | undefined): string {
  if (!iso) return '';
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(iso);
  if (m) return `${m[3]}.${m[2]}.${m[1]}`;
  const d = new Date(iso);
  return isNaN(d.getTime()) ? '' : d.toLocaleDateString('ru-RU');
}

/** «14:05» — для индикатора сохранения. */
export function timeText(d: Date = new Date()): string {
  return d.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' });
}

/** plural(5, 'поставщик', 'поставщика', 'поставщиков') → «поставщиков». */
export function plural(n: number, one: string, few: string, many: string): string {
  const m10 = n % 10;
  const m100 = n % 100;
  if (m10 === 1 && m100 !== 11) return one;
  if (m10 >= 2 && m10 <= 4 && (m100 < 12 || m100 > 14)) return few;
  return many;
}

/** Письмо или документ для одного поставщика до отправки: из справочника или ещё не заведённый. */
export interface RfqPreviewBody {
  distributorId?: number;
  newSupplierName?: string;
  lotIds: number[];
  replyBy: string | null;
  discloseClient: boolean;
  withDocument: boolean;
  withStamp: boolean;
}

export interface RfqSendSupplier {
  distributorId: number | null;
  newSupplier: { name: string; rememberBrands: boolean } | null;
  /** Почта из окна: пустая — запрос без письма. */
  email: string;
  discloseClient: boolean;
  lotIds: number[];
}

export interface RfqSendBody { replyBy: string | null; withDocument: boolean; withStamp: boolean; suppliers: RfqSendSupplier[]; }

/** Те же адреса без учёта регистра и разделителей — как EmailAddresses.same на сервере. */
export function sameEmails(a: string | null, b: string | null): boolean {
  const norm = (s: string | null) => (s || '').split(/[,;\s]+/).map(x => x.trim().toLowerCase()).filter(Boolean).join(',');
  return norm(a) === norm(b);
}

/** Имя файла из Content-Disposition (RFC 5987: filename*=UTF-8''…), иначе запасное. */
export function dispositionFileName(header: string | null, fallback: string): string {
  const m = /filename\*=UTF-8''([^;]+)/i.exec(header || '');
  if (m) {
    try {
      return decodeURIComponent(m[1]);
    } catch {
      /* битое имя — запасное */
    }
  }
  return fallback;
}
