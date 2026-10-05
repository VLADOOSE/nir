/**
 * КП клиенту: типы, подписи и общие функции экранов (спека docs/superpowers/specs/2026-10-02-client-kp-constructor-design.md).
 * Формул здесь НЕТ: цены, НДС и маржу считает сервер (ClientOfferCalculator), экран показывает его числа.
 */
export type OfferStatus = 'DRAFT' | 'SENT' | 'ACCEPTED' | 'REJECTED';
export type ItemKind = 'ITEM' | 'SECTION' | 'INCLUDED';
export type TermsStyle = 'TABLE' | 'LIST' | 'NONE';
export type Rounding = 'NONE' | 'UNIT' | 'TEN' | 'HUNDRED';
export type Signoff = 'COMPANY' | 'DIRECTOR';
export type RegStatus = 'UNCHECKED' | 'SUGGESTED' | 'CONFIRMED' | 'NOT_REQUIRED' | 'MANUAL';

export interface OfferColumn { key: string; label?: string | null; }
export interface OfferTerm { label: string; value: string; }

/**
 * Предпросмотр документа — страницы PNG в base64 одним JSON (файл с /api — только через HttpClient, §14).
 * crowded — таблица КП тесная: кегль уменьшен ниже обычного, редактор подсказывает «Альбомная»
 * (у образца на «Реквизитах и печати» всегда false).
 */
export interface PreviewPages { pages: string[]; crowded: boolean; }

/** cost / costTotal — закупка за единицу и на количество: НДС поставщика не вычитается (решение оператора 2026-10-05). */
export interface ItemCalc {
  cost: number | null; costTotal: number | null; markupPct: number | null; priceNet: number | null;
  price: number | null; sum: number | null; vatSum: number | null; sumNet: number | null;
  profit: number | null; effectiveVatRate: number | null;
}

export interface OfferItem {
  id?: number | null;
  key: string;
  lineNo?: number;
  kind: ItemKind;
  name: string;
  model?: string | null;
  manufacturer?: string | null;
  country?: string | null;
  unit: string;
  quantity: number | null;
  /** цена закупки за единицу — как в счёте поставщика */
  purchasePrice: number | null;
  /**
   * Пометка «НДС в цене закупки» — с 2026-10-05 расчёт её не читает и экран не показывает (НДС поставщика не учитывается);
   * поля API — для совместимости: строка отправляет их такими, какими пришли, новая — «как у продажи».
   */
  purchaseVatSame: boolean;
  purchaseVatRate: number | null;
  supplierName?: string | null;
  markupPct: number | null;
  priceOverride: number | null;
  vatRate: number | null;
  registrationStatus: RegStatus;
  registrationText?: string | null;
  regNumber?: string | null;
  note?: string | null;
  calc?: ItemCalc | null;
  /** только экран: строка раскрыта / отмечена галочкой */
  _open?: boolean;
  _sel?: boolean;
}

export interface VatLine { rate: number; amount: number; }

/** cost = purchase (НДС поставщика не вычитается, поле — для совместимости); markupAvg — от закупки, по суммам с НДС. */
export interface OfferTotals {
  sum: number; vat: VatLine[]; vatTotal: number; purchase: number; cost: number; revenueNet: number;
  profit: number; markupAvg: number | null; itemCount: number; noPurchaseCount: number; unconfirmedRegistrationCount: number;
}

export interface ClientOffer {
  id: number; number: number; offerDate: string; status: OfferStatus;
  facilityId: number | null; facilityName: string | null; recipient: string | null; tenderId: number | null;
  title: string; subject: string | null; intro: string | null;
  vatEnabled: boolean; defaultMarkupPct: number; rounding: Rounding;
  columns: OfferColumn[]; detailsInName: boolean; terms: OfferTerm[]; termsStyle: TermsStyle;
  showAmountInWords: boolean; showVatBreakdown: boolean; landscape: boolean;
  signoff: Signoff; signoffContacts: boolean; withStamp: boolean; internalNote: string | null;
  version: number; currency: string; items: OfferItem[]; totals: OfferTotals; fileBaseName: string;
  createdBy: string | null; createdAt: string; updatedAt: string; sentAt: string | null;
}

export interface ColumnInfo { key: string; label: string; labelNoVat?: string; vatOnly?: boolean; }

/** Каталог колонок — подписи по умолчанию как на сервере (OfferColumnKey). */
export const COLUMN_CATALOG: ColumnInfo[] = [
  { key: 'NUM', label: '№' },
  { key: 'NAME', label: 'Наименование' },
  { key: 'MODEL', label: 'Модель / артикул' },
  { key: 'MANUFACTURER', label: 'Производитель' },
  { key: 'COUNTRY', label: 'Страна' },
  { key: 'UNIT', label: 'Ед. изм.' },
  { key: 'QTY', label: 'Кол-во' },
  { key: 'PRICE', label: 'Цена (с НДС)', labelNoVat: 'Цена' },
  { key: 'PRICE_NET', label: 'Цена без НДС', vatOnly: true },
  { key: 'VAT_RATE', label: 'Ставка НДС', vatOnly: true },
  { key: 'VAT_SUM', label: 'Сумма НДС', vatOnly: true },
  { key: 'SUM_NET', label: 'Сумма без НДС', vatOnly: true },
  { key: 'SUM', label: 'Сумма (с НДС)', labelNoVat: 'Сумма' },
  { key: 'REGISTRATION', label: 'Регистрация' },
  { key: 'NOTE', label: 'Примечание' },
];

export const OFFER_STATUS_LABELS: Record<OfferStatus, string> = {
  DRAFT: 'Черновик', SENT: 'Отправлено', ACCEPTED: 'Принято', REJECTED: 'Отклонено',
};

export const ROUNDING_OPTIONS: { v: Rounding; l: string }[] = [
  { v: 'NONE', l: 'Не округлять' }, { v: 'UNIT', l: 'До целых' }, { v: 'TEN', l: 'До 10' }, { v: 'HUNDRED', l: 'До 100' },
];

/** Быстрые условия (спека §8.2): из КП отца на ИВЛ и от 24.09. */
export const TERM_SUGGESTIONS: OfferTerm[] = [
  { label: 'Срок действия предложения', value: '10 дней' },
  { label: 'Условия поставки', value: 'DDP Заказчик' },
  { label: 'Порядок оплаты', value: '100% предоплата' },
  { label: 'Форма оплаты', value: 'безналичная' },
  { label: 'Срок поставки', value: '30 рабочих дней после поступления предоплаты' },
  { label: 'Гарантия', value: '12 месяцев с даты подписания акта установки оборудования' },
  { label: 'Обучение', value: 'Включено в стоимость' },
  { label: 'Сведения о регистрации', value: '' },
];

export const UNIT_SUGGESTIONS = ['шт', 'набор', 'упак', 'компл', 'фл', 'л', 'кг', 'м'];

export function columnInfo(key: string): ColumnInfo | undefined {
  return COLUMN_CATALOG.find(c => c.key === key);
}

export function defaultColumnLabel(key: string, vatEnabled: boolean): string {
  const c = columnInfo(key);
  if (!c) return key;
  return !vatEnabled && c.labelNoVat ? c.labelNoVat : c.label;
}

/** «5%», «12,5%», «Без НДС». */
export function vatLabel(rate: number | null | undefined): string {
  return rate == null ? 'Без НДС' : String(rate).replace('.', ',') + '%';
}

const MONEY = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const NUMBER = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 3 });

/** «9 902 248,23»; пусто для null. */
export function money(v: number | null | undefined): string {
  return v == null ? '' : MONEY.format(v);
}

/** Число в поле ввода: «126 000», дробь через запятую; пусто для null. */
export function numText(v: number | null | undefined): string {
  return v == null ? '' : NUMBER.format(v);
}

/**
 * Число из поля: пробелы между разрядами (\s ловит и неразрывный U+00A0, и узкий U+202F — их ставит Intl ru-RU),
 * запятая или точка. Пусто или мусор — null.
 */
export function parseNum(text: string | null | undefined): number | null {
  if (text == null) return null;
  const t = String(text).replace(/\s/g, '').replace(',', '.');
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

/** «2026-09-14» → «14.09.2026». */
export function dateText(iso: string | null | undefined): string {
  if (!iso) return '';
  const [y, m, d] = iso.split('-');
  return `${d}.${m}.${y}`;
}

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

/** «2026-09-23» → «23» сентября 2026 г. — в «ёлочках», как в КП отца. */
export function longDateText(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `«${d}» ${MONTHS_GEN[Number(m) - 1]} ${y} г.`;
}

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

export function newItem(kind: ItemKind, vatRate: number | null): OfferItem {
  return {
    id: null, key: uid(), kind, name: '',
    unit: 'шт', quantity: kind === 'ITEM' ? 1 : null,
    purchasePrice: null, purchaseVatSame: true, purchaseVatRate: null,
    markupPct: null, priceOverride: null, vatRate: kind === 'ITEM' ? vatRate : null,
    registrationStatus: 'UNCHECKED', registrationText: null, note: null, calc: null,
  };
}

/** Тело PUT: только то, что вводит оператор; вычисляемое и экранное не отправляем. */
export function toRequest(o: ClientOffer): any {
  return {
    version: o.version, number: o.number, offerDate: o.offerDate, facilityId: o.facilityId, recipient: o.recipient,
    title: o.title, subject: o.subject, intro: o.intro, vatEnabled: o.vatEnabled, defaultMarkupPct: o.defaultMarkupPct,
    rounding: o.rounding, columns: o.columns, detailsInName: o.detailsInName, terms: o.terms, termsStyle: o.termsStyle,
    showAmountInWords: o.showAmountInWords, showVatBreakdown: o.showVatBreakdown, landscape: o.landscape,
    signoff: o.signoff, signoffContacts: o.signoffContacts, withStamp: o.withStamp, internalNote: o.internalNote,
    items: o.items.map(it => ({
      id: it.id ?? null, key: it.key, kind: it.kind, name: it.name, model: it.model ?? null,
      manufacturer: it.manufacturer ?? null, country: it.country ?? null, unit: it.unit, quantity: it.quantity,
      purchasePrice: it.purchasePrice, purchaseVatSame: it.purchaseVatSame, purchaseVatRate: it.purchaseVatRate,
      supplierName: it.supplierName ?? null, markupPct: it.markupPct, priceOverride: it.priceOverride, vatRate: it.vatRate,
      registrationStatus: it.registrationStatus, registrationText: it.registrationText ?? null, note: it.note ?? null,
    })),
  };
}

/** Скачивание blob файлом (тот же приём, что в «Заявках»; файл с /api — только через HttpClient blob). */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
