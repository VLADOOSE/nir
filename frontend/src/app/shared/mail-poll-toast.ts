/**
 * Тост по ответу POST /api/inbound/poll — «Проверить почту» во «Входящих» и «Проверить ответы» в карточке тендера.
 * Чистая функция: тост шлёт вызывающий. reload — перечитать список: проход дошёл до конца или ещё идёт в фоне.
 */
export function mailPollToast(r: any): { message: string; type: 'success' | 'error' | 'info'; reload: boolean } {
  if (r?.enabled === false) return { message: r.message || 'Приём почты выключен', type: 'error', reload: false };
  if (r?.pending) return { message: r.message || 'Проверка почты ещё идёт', type: 'info', reload: true };
  if (r?.ok === false) return { message: r.message || 'Проверка почты не удалась', type: 'error', reload: false };
  return { message: r?.message || 'Почта проверена', type: 'success', reload: true };
}
