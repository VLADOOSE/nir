/**
 * Разбор Excel (D1): колонки файла, разметка оператора и сборка строк — один код на все экраны импорта
 * («Входящие», «Частные заявки», файлы чатов). Раньше логика была скопирована в каждый экран.
 */
export interface ImportColumn { index: number; header: string; field: string | null; }
export interface ImportPreview { columns: ImportColumn[]; rows: string[][]; }
export interface ImportLine { name: string; manufact: string | null; quantity: number; }
export interface ImportMapping { header: string; field: string; }

export const IMPORT_FIELD_OPTIONS = [
  { v: 'NAME', l: 'Наименование' },
  { v: 'MANUFACT', l: 'Бренд' },
  { v: 'QUANTITY', l: 'Кол-во' },
  { v: 'IGNORE', l: 'Игнорировать' },
];

/** Строки по разметке колонок; error — что сказать оператору, если собрать нечего. */
export function buildImportLines(preview: ImportPreview | null):
    { lines: ImportLine[]; mappings: ImportMapping[]; error: string | null } {
  const cols = preview?.columns || [];
  const nameCol = cols.find(c => c.field === 'NAME');
  if (!nameCol) return { lines: [], mappings: [], error: 'Отметьте колонку с наименованием' };
  const manuCol = cols.find(c => c.field === 'MANUFACT');
  const qtyCol = cols.find(c => c.field === 'QUANTITY');
  const lines = (preview?.rows || [])
    .map(row => ({
      name: row[nameCol.index],
      manufact: manuCol ? row[manuCol.index] : null,
      quantity: qtyCol ? (parseInt(row[qtyCol.index], 10) || 1) : 1,
    }))
    .filter(l => l.name && String(l.name).trim());
  if (!lines.length) return { lines: [], mappings: [], error: 'Нет строк с наименованием' };
  const mappings = cols
    .filter(c => c.field && c.field !== 'IGNORE')
    .map(c => ({ header: c.header, field: c.field as string }));
  return { lines, mappings, error: null };
}
