/** Статусы заявки на участие — одни подписи на «Заявках на участие» и в карточке тендера. */
export const APPLY_STATUS_LABELS: Record<string, string> = {
  DRAFT: 'Черновик', SUBMITTED: 'Подана', UNDER_REVIEW: 'На рассмотрении', WON: 'Выиграна', LOST: 'Проиграна',
  CANCELLED: 'Отменена', REJECTED: 'Отклонена',
};

export function applyStatusLabel(s: string): string {
  return APPLY_STATUS_LABELS[s] || s;
}
