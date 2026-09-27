/** «только что» / «12 мин назад» / «3 ч назад»; старше суток — полная дата. Для лент и списков. */
export function relativeTime(iso: string | null | undefined, now: Date = new Date()): string {
  if (!iso) return '—';
  const t = new Date(iso);
  if (isNaN(t.getTime())) return '—';
  const min = Math.floor((now.getTime() - t.getTime()) / 60000);
  if (min < 1) return 'только что';
  if (min < 60) return `${min} мин назад`;
  const h = Math.floor(min / 60);
  if (h < 24) return `${h} ч назад`;
  return fullDateTime(iso);
}

export function fullDateTime(iso: string | null | undefined): string {
  if (!iso) return '';
  const t = new Date(iso);
  if (isNaN(t.getTime())) return '';
  return new Intl.DateTimeFormat('ru-RU', {
    day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit',
  }).format(t);
}
