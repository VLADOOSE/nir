import { HttpResponse } from '@angular/common/http';
import { dispositionFileName } from './private-request';

/** Окно «Поделиться» с файлом (iPhone, Android, Safari на Mac): есть ли оно в этом браузере. */
export function canShareFiles(): boolean {
  try {
    const nav = navigator as any;
    return !!nav.share && !!nav.canShare && nav.canShare({ files: [new File(['x'], 'x.pdf', { type: 'application/pdf' })] });
  } catch {
    return false;
  }
}

/**
 * Файл — в окно «Поделиться». Браузер пускает туда только из нажатия, поэтому файл готовят заранее, а сюда приходят
 * прямо из клика. Отмена в окне — не ошибка; иначе — текст для тоста (.then, не нативный await — CLAUDE.md §14).
 */
export function openShareSheet(file: File, onError: (text: string) => void): void {
  (navigator as any).share({ files: [file], title: file.name }).then(() => {}, (e: any) => {
    if (e?.name === 'AbortError') return;
    onError(e?.name === 'NotAllowedError'
      ? 'Браузер не дал поделиться — нажмите ещё раз или скачайте файл'
      : 'Не удалось поделиться — скачайте файл');
  });
}

/** Ответ-blob → File с именем из Content-Disposition. */
export function responseFile(r: HttpResponse<Blob>, fallback: string): File | null {
  if (!r.body) return null;
  const name = dispositionFileName(r.headers.get('Content-Disposition'), fallback);
  return new File([r.body], name, { type: r.body.type || 'application/octet-stream' });
}
