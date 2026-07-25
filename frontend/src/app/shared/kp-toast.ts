/** Свести результаты POST /api/price-requests/send в текст тоста. Чистая функция: тост шлёт вызывающий. */
export function kpToastFromResults(results: any[]): { message: string; isError: boolean } {
  const list = results || [];
  const sent = list.filter((r: any) => r.emailSent).length;
  const noEmail = list.filter((r: any) => r.reason === 'NO_EMAIL').map((r: any) => r.distributorName);
  const failed = list.filter((r: any) => r.reason === 'SEND_FAILED').map((r: any) => r.distributorName);
  let message = `Создано запросов: ${list.length}, писем отправлено: ${sent}`;
  if (noEmail.length) message += `; без email: ${noEmail.join(', ')}`;
  if (failed.length) message += `; ошибка отправки: ${failed.join(', ')}`;
  return { message, isError: noEmail.length > 0 || failed.length > 0 };
}
