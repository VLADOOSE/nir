/**
 * Текст ошибки сервера (ApiError.message — его несут все ответы бэкенда, и 5xx тоже). Нет ответа или 5xx без него —
 * прокси перед лежащим бэкендом: «нет связи с сервером».
 */
export function errorText(e: any): string {
  if (e?.error?.message) return e.error.message;
  return !e?.status || e.status >= 500 ? 'нет связи с сервером' : `ошибка ${e.status}`;
}
