/** Исполнение сделки (спека 2026-10-19-deal-execution): раздел страницы частной заявки после «Клиент согласился». */
export type ContractKind = 'CONTRACT' | 'INVOICE' | 'NONE';
export type ExecField = 'CONTRACT_KIND' | 'CONTRACT_NUMBER' | 'CONTRACT_DATE' | 'CONTRACT_NOTE' | 'PREPAY_PCT' | 'AMOUNT' | 'FROM_STOCK';
export type ExecMark = 'DELIVERY' | 'DOCS';
export type SupplierField = 'AMOUNT' | 'CURRENCY' | 'EXCLUDED';
export type SupplierMark = 'ORDER' | 'SHIP';
export type ExecStage = 'CONTRACT' | 'CLIENT_PAYMENT' | 'SUPPLIER' | 'DELIVERY' | 'DOCS';

export interface ExecPayment { id: number; amount: number; paidOn: string; note: string | null; createdBy: string | null; }
export interface ExecSupplier {
  id: number | null; distributorId: number; name: string; fromOffer: boolean; manual: boolean; excluded: boolean;
  currency: string; amount: number | null; amountFromOffer: boolean; offerAmount: number | null;
  orderedOn: string | null; orderNote: string | null; paid: number; payments: ExecPayment[];
  shippedOn: string | null; shipNote: string | null;
}
export interface ExecFile {
  id: number; stage: ExecStage; paymentId: number | null; distributorId: number | null; name: string; size: number;
  uploadedBy: string | null; uploadedAt: string;
}
export interface DealExecution {
  visible: boolean; currency: string; amount: number | null; amountFromOffer: boolean; offerAmount: number | null;
  offerId: number | null; offerLabel: string | null;
  contractKind: ContractKind | null; contractNumber: string | null; contractDate: string | null; contractNote: string | null;
  prepayPct: number; prepayAmount: number | null; clientPaid: number; clientPayments: ExecPayment[];
  fromStock: boolean; suppliers: ExecSupplier[]; unassigned: { count: number; purchase: number | null } | null;
  deliveredOn: string | null; deliveryNote: string | null; docsOn: string | null; docsNote: string | null;
  files: ExecFile[];
}
export interface ExecFileCandidate {
  attachmentId: number; fileName: string; sizeBytes: number | null; sentAt: string; channel: string | null;
  subject: string | null; direction: 'IN' | 'OUT' | null;
}
/** Куда прикладывается файл: место, платёж, поставщик; title — в заголовок окна. */
export interface ExecFileTarget { stage: ExecStage; paymentId?: number | null; distributorId?: number | null; title: string; }
/** Форма «дата [+ сумма] + заметка». */
export interface ExecEntry { date: string; amount: number | null; note: string | null; }

export const EXEC_CURRENCIES: { v: string; l: string }[] = [
  { v: 'KZT', l: 'тг' }, { v: 'RUB', l: 'руб.' }, { v: 'USD', l: '$' }, { v: 'EUR', l: '€' }, { v: 'CNY', l: 'юань' },
];

export function execCurrency(code: string | null | undefined): string {
  return EXEC_CURRENCIES.find(c => c.v === code)?.l ?? (code || '');
}

const WHOLE = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 });
const CENTS = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** «3 070 455 тг», «1 535 227,50 тг»: копейки — только ненулевые (как ExecutionTexts на сервере). */
export function execMoney(v: number | null | undefined, currency?: string | null): string {
  if (v == null) return '';
  const s = Number.isInteger(v) ? WHOLE.format(v) : CENTS.format(v);
  return currency ? s + ' ' + execCurrency(currency) : s;
}

/** Файлы одного места: блок, платёж, поставщик. */
export function filesAt(e: DealExecution, stage: ExecStage, paymentId: number | null = null,
                        distributorId: number | null = null): ExecFile[] {
  return e.files.filter(f => f.stage === stage && (f.paymentId ?? null) === paymentId && (f.distributorId ?? null) === distributorId);
}
