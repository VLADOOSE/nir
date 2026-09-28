// Файлы для dev-стабов шлюзов WhatsApp (Green-API, WAHA): PNG и XLSX собираются на лету (zlib.crc32 — Node ≥ 20.15).
import { crc32, deflateSync } from 'node:zlib';

export function png(width, height) {
  const stride = width * 3 + 1;
  const raw = Buffer.alloc(stride * height);
  for (let y = 0; y < height; y++) {
    raw[y * stride] = 0;
    for (let x = 0; x < width; x++) {
      const i = y * stride + 1 + x * 3;
      raw[i] = 40 + Math.round((180 * x) / width);
      raw[i + 1] = 120 + Math.round((100 * y) / height);
      raw[i + 2] = 200;
    }
  }
  const chunk = (type, data) => {
    const len = Buffer.alloc(4);
    len.writeUInt32BE(data.length);
    const td = Buffer.concat([Buffer.from(type, 'ascii'), data]);
    const crc = Buffer.alloc(4);
    crc.writeUInt32BE(crc32(td));
    return Buffer.concat([len, td, crc]);
  };
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(width, 0);
  ihdr.writeUInt32BE(height, 4);
  ihdr[8] = 8; // 8 бит
  ihdr[9] = 2; // RGB
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', ihdr), chunk('IDAT', deflateSync(raw)), chunk('IEND', Buffer.alloc(0)),
  ]);
}

/** ZIP без сжатия (method 0) — POI такое читает; имена в UTF-8 (флаг 0x0800). */
function zip(files) {
  const parts = [];
  const central = [];
  let offset = 0;
  for (const f of files) {
    const name = Buffer.from(f.name, 'utf8');
    const crc = crc32(f.data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0x0800, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt16LE(0, 10);
    local.writeUInt16LE(0x21, 12);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(f.data.length, 18);
    local.writeUInt32LE(f.data.length, 22);
    local.writeUInt16LE(name.length, 26);
    local.writeUInt16LE(0, 28);
    parts.push(local, name, f.data);
    const c = Buffer.alloc(46);
    c.writeUInt32LE(0x02014b50, 0);
    c.writeUInt16LE(20, 4);
    c.writeUInt16LE(20, 6);
    c.writeUInt16LE(0x0800, 8);
    c.writeUInt16LE(0, 10);
    c.writeUInt16LE(0, 12);
    c.writeUInt16LE(0x21, 14);
    c.writeUInt32LE(crc, 16);
    c.writeUInt32LE(f.data.length, 20);
    c.writeUInt32LE(f.data.length, 24);
    c.writeUInt16LE(name.length, 28);
    c.writeUInt16LE(0, 30);
    c.writeUInt16LE(0, 32);
    c.writeUInt16LE(0, 34);
    c.writeUInt16LE(0, 36);
    c.writeUInt32LE(0, 38);
    c.writeUInt32LE(offset, 42);
    central.push(c, name);
    offset += 30 + name.length + f.data.length;
  }
  const cd = Buffer.concat(central);
  const end = Buffer.alloc(22);
  end.writeUInt32LE(0x06054b50, 0);
  end.writeUInt16LE(files.length, 8);
  end.writeUInt16LE(files.length, 10);
  end.writeUInt32LE(cd.length, 12);
  end.writeUInt32LE(offset, 16);
  return Buffer.concat([...parts, cd, end]);
}

export function xlsx(rows) {
  const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  const col = (i) => String.fromCharCode(65 + i);
  const sheetRows = rows.map((r, ri) => `<row r="${ri + 1}">`
    + r.map((v, ci) => `<c r="${col(ci)}${ri + 1}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`).join('')
    + '</row>').join('');
  const x = (s) => Buffer.from('<?xml version="1.0" encoding="UTF-8" standalone="yes"?>\n' + s, 'utf8');
  const main = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main';
  const rel = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships';
  const pkg = 'http://schemas.openxmlformats.org/package/2006/relationships';
  return zip([
    { name: '[Content_Types].xml', data: x('<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
      + '<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
      + '<Default Extension="xml" ContentType="application/xml"/>'
      + '<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
      + '<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
      + '<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>'
      + '</Types>') },
    { name: '_rels/.rels', data: x(`<Relationships xmlns="${pkg}">`
      + `<Relationship Id="rId1" Type="${rel}/officeDocument" Target="xl/workbook.xml"/></Relationships>`) },
    { name: 'xl/workbook.xml', data: x(`<workbook xmlns="${main}" xmlns:r="${rel}">`
      + '<sheets><sheet name="Заявка" sheetId="1" r:id="rId1"/></sheets></workbook>') },
    { name: 'xl/_rels/workbook.xml.rels', data: x(`<Relationships xmlns="${pkg}">`
      + `<Relationship Id="rId1" Type="${rel}/worksheet" Target="worksheets/sheet1.xml"/>`
      + `<Relationship Id="rId2" Type="${rel}/styles" Target="styles.xml"/></Relationships>`) },
    { name: 'xl/styles.xml', data: x(`<styleSheet xmlns="${main}">`
      + '<fonts count="1"><font/></fonts><fills count="1"><fill/></fills><borders count="1"><border/></borders>'
      + '<cellStyleXfs count="1"><xf/></cellStyleXfs><cellXfs count="1"><xf/></cellXfs></styleSheet>') },
    { name: 'xl/worksheets/sheet1.xml', data: x(`<worksheet xmlns="${main}"><sheetData>${sheetRows}</sheetData></worksheet>`) },
  ]);
}

export const XLSX_ROWS = [
  ['№', 'Наименование', 'Производитель', 'Кол-во'],
  ['1', 'Аппарат УЗИ экспертного класса', 'Mindray', '1'],
  ['2', 'Датчик конвексный', 'Mindray', '2'],
  ['3', 'Принтер для УЗИ', 'Sony', '1'],
];
export const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
// длинная смета: проверка, что кнопки разбора не уходят под край (ревью 2026-09-28)
export const BIG_ROWS = [['№', 'Наименование', 'Производитель', 'Кол-во'],
  ...Array.from({ length: 45 }, (_, i) => [String(i + 1), `Позиция сметы ${i + 1}`, i % 2 ? 'Mindray' : 'Dräger', String(1 + (i % 3))])];

export const FILES = {
  'аппарат.png': { mime: 'image/png', data: png(320, 200) },
  'Заявка клиники.xlsx': { mime: XLSX_MIME, data: xlsx(XLSX_ROWS) },
  'Смета 45 строк.xlsx': { mime: XLSX_MIME, data: xlsx(BIG_ROWS) },
  'ТЗ.pdf': { mime: 'application/pdf', data: Buffer.from('%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n'
    + '2 0 obj <</Type/Pages/Kids[3 0 R]/Count 1>> endobj\n3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>> endobj\n'
    + 'trailer <</Root 1 0 R>>\n%%EOF\n', 'latin1') },
  'страница.html': { mime: 'text/html', data: Buffer.from('<html><body><script>alert("xss")</script>Если это исполнилось — дыра</body></html>') },
};
