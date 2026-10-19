/** Письма клиентам из АИС (спека 2026-10-19-client-mail-reply §4, §8). */
export type ReplyRole = 'FIRST_REPLY' | 'OFFER';

export interface ReplyTemplate { id: number | null; title: string; body: string; role: ReplyRole | null; }

export interface ComposeReply {
  messageId: number; subject: string | null; replySubject: string; sentAt: string;
  fromName: string | null; fromAddress: string; direction: 'IN' | 'OUT';
}

export interface ComposeOffer { id: number; label: string; origin: 'BUILDER' | 'FILE'; status: string; fileName: string | null; }

export interface ComposeContext {
  enabled: boolean; reason: string | null; from: string;
  chatId: number | null; chatTitle: string | null; to: string[];
  reply: ComposeReply | null; newSubject: string; firstLetter: boolean;
  templates: ReplyTemplate[]; signature: string;
  offers: ComposeOffer[]; offerId: number | null; leadId: number | null; tenderId: number | null;
}

/** Откуда открыли окно — ровно одно поле. */
export type ComposeTarget = { chatId?: number; leadId?: number; tenderId?: number; offerId?: number };

export interface OfferPick { id: number; format: 'PDF' | 'DOCX' | 'FILE'; }

export interface ClientMailSendBody {
  chatId: number | null; replyToMessageId: number | null; to: string | null; subject: string | null; text: string;
  leadId: number | null; tenderId: number | null; offers: OfferPick[]; token: string;
}

export interface ClientMailSendResult {
  recorded: boolean; chatIds: number[]; messageIds: number[]; offersSent: number[]; warning: string | null;
}

export interface ReplyTemplatesState {
  templates: ReplyTemplate[]; signature: string;
  sending: { enabled: boolean; mailbox: string; reason: string | null };
}

/** Файлы с компьютера — как предел запроса приложения (15 МБ, nginx фронта — тот же). */
export const UPLOAD_LIMIT = 15 * 1024 * 1024;
