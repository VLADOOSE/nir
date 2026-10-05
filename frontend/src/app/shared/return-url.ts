/** Адрес возврата после входа: только свой путь (не //чужой-хост, не /login), иначе null. */
export function safeReturnUrl(u: string | null | undefined): string | null {
  if (!u || !u.startsWith('/') || u.startsWith('//') || u.startsWith('/\\') || u.startsWith('/login')) return null;
  return u;
}
