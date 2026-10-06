/**
 * Тост по итогу прогона импорта тендеров — `lastSummary` из `/api/tenders/import-kz/status` и `/import-sk/status`.
 * Чистая функция: тост шлёт вызывающий. Красный — импорт выключен или были ошибки и ничего не записалось
 * (площадка недоступна, сменилась вёрстка, прогон прерван, все объявления упали); синий — записал, но с ошибками;
 * зелёный — без ошибок. До 2026-10-06 любой исход был зелёным, и импорт СК-Фармации, падавший на сертификате
 * портала, стоял незаметно.
 */
export function importToast(label: string, s: any): { message: string; type: 'success' | 'error' | 'info' } | null {
  if (!s) return null;
  if (s.enabled === false) return { message: s.message || `${label}: импорт выключен`, type: 'error' };
  const summary = s.message ? `${label}: ${s.message}` : label;
  if (!s.errors) return { message: summary, type: 'success' };
  if (!s.fetched) {   // площадка не отдала ничего — счётчики пустые, важна только причина
    const count = s.errors > 1 ? ` (ошибок ${s.errors})` : '';
    return { message: `${label}: не удалось${count}${s.lastError ? ' — ' + s.lastError : ''}`, type: 'error' };
  }
  const withReason = s.lastError ? `${summary} · последняя ошибка: ${s.lastError}` : summary;
  const nothingSaved = !s.created && !s.updated;
  return { message: withReason, type: nothingSaved ? 'error' : 'info' };
}
