/** Выбор «Приложить запрос на бланке (Word)» помнит браузер — общий для окна заявки и панели КП тендера (спека §6.2). */
const DOC_KEY = 'ais.rfq.withDocument';

export function readWithDocument(): boolean {
  try {
    return localStorage.getItem(DOC_KEY) === '1';
  } catch {
    return false;
  }
}

export function storeWithDocument(on: boolean) {
  try {
    localStorage.setItem(DOC_KEY, on ? '1' : '0');
  } catch {
    /* приватный режим или запрет хранилища — выбор просто не запомнится */
  }
}

/** Сегодня по часам браузера, YYYY-MM-DD (toISOString — UTC: до 05:00 по Уральску дал бы вчерашнее число). */
export function todayIso(): string {
  const d = new Date();
  d.setMinutes(d.getMinutes() - d.getTimezoneOffset());
  return d.toISOString().slice(0, 10);
}
