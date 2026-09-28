#!/usr/bin/env node
// Стаб Green-API для живых проверок «Чатов» без WhatsApp
// (спека docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md §12). Только для разработки.
//
// Запуск:    node scripts/greenapi-stub.mjs                        (порт 7707; другой — STUB_PORT=…)
// Бэкенд:    WHATSAPP_ENABLED=true WHATSAPP_API_URL=http://localhost:7707 WHATSAPP_ID_INSTANCE=1101 \
//            WHATSAPP_API_TOKEN=dev-token WHATSAPP_INITIAL_DELAY_MS=3000 ./gradlew bootRun
// Сценарий:  curl -s -X POST localhost:7707/__scenario/basic      — корзина сайта, Excel, фото, ответ с телефона,
//                                                                     PDF, HTML, правка, группа, удаление
//            curl -s -X POST localhost:7707/__scenario/excel      — два Excel подряд, один на 45 строк
// Своё:     curl -s localhost:7707/__enqueue -H 'Content-Type: application/json' -d @notification.json
// Состояние: curl -s -X POST localhost:7707/__state/notAuthorized  (authorized / blocked / sleepMode / suspended …)
// Настройки: curl -s localhost:7707/__settings -d '{"outgoingMessageWebhook":"no"}'
// Очередь:   curl -s localhost:7707/__queue
// Excel:     node scripts/greenapi-stub.mjs --write-xlsx /путь/файл.xlsx   — записать тестовый .xlsx и выйти
//
// Токен проверяется как у настоящего сервиса (STUB_TOKEN, по умолчанию dev-token): чужой — 401.
// Очередь отдаёт ГОЛОВУ, пока её не удалят, — как настоящая.

import http from 'node:http';
import { crc32, deflateSync } from 'node:zlib';
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.STUB_PORT || 7707);
const TOKEN = process.env.STUB_TOKEN || 'dev-token';
const BASE = `http://localhost:${PORT}`;
const WID = '77000000001@c.us';

// ---------- файлы: PNG и XLSX собираются на лету (zlib.crc32 — Node ≥ 20.15) ----------

function png(width, height) {
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

function xlsx(rows) {
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

const XLSX_ROWS = [
  ['№', 'Наименование', 'Производитель', 'Кол-во'],
  ['1', 'Аппарат УЗИ экспертного класса', 'Mindray', '1'],
  ['2', 'Датчик конвексный', 'Mindray', '2'],
  ['3', 'Принтер для УЗИ', 'Sony', '1'],
];

if (process.argv[2] === '--write-xlsx') {
  writeFileSync(process.argv[3], xlsx(XLSX_ROWS));
  console.log('записан', process.argv[3]);
  process.exit(0);
}

const XLSX_MIME = 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet';
// длинная смета: проверка, что кнопки разбора не уходят под край (ревью 2026-09-28)
const BIG_ROWS = [['№', 'Наименование', 'Производитель', 'Кол-во'],
  ...Array.from({ length: 45 }, (_, i) => [String(i + 1), `Позиция сметы ${i + 1}`, i % 2 ? 'Mindray' : 'Dräger', String(1 + (i % 3))])];

const FILES = {
  'аппарат.png': { mime: 'image/png', data: png(320, 200) },
  'Заявка клиники.xlsx': { mime: XLSX_MIME, data: xlsx(XLSX_ROWS) },
  'Смета 45 строк.xlsx': { mime: XLSX_MIME, data: xlsx(BIG_ROWS) },
  'ТЗ.pdf': { mime: 'application/pdf', data: Buffer.from('%PDF-1.4\n1 0 obj <</Type/Catalog/Pages 2 0 R>> endobj\n'
    + '2 0 obj <</Type/Pages/Kids[3 0 R]/Count 1>> endobj\n3 0 obj <</Type/Page/Parent 2 0 R/MediaBox[0 0 200 100]>> endobj\n'
    + 'trailer <</Root 1 0 R>>\n%%EOF\n', 'latin1') },
  'страница.html': { mime: 'text/html', data: Buffer.from('<html><body><script>alert("xss")</script>Если это исполнилось — дыра</body></html>') },
};

// ---------- уведомления в форме настоящего Green-API ----------

let seq = 0;
const now = () => Math.floor(Date.now() / 1000);
const idm = () => 'STUB' + Date.now().toString(16).toUpperCase() + (++seq);
const instance = () => ({ idInstance: 1101, wid: WID, typeInstance: 'whatsapp' });
const envelope = (type, chatId, sender, chatName, senderName, senderContactName, messageData) => ({
  typeWebhook: type, instanceData: instance(), timestamp: now(), idMessage: idm(),
  senderData: { chatId, sender, chatName, senderName, senderContactName }, messageData,
});
const incoming = (chatId, name, md) => envelope('incomingMessageReceived', chatId, chatId, name, name, name, md);
const outgoing = (chatId, name, md) => envelope('outgoingMessageReceived', chatId, WID, name, 'West-Med', '', md);
const inGroup = (groupId, groupName, authorId, author, md) =>
  envelope('incomingMessageReceived', groupId, authorId, groupName, author, author, md);
const text = (t) => ({ typeMessage: 'textMessage', textMessageData: { textMessage: t } });
const file = (typeMessage, name, caption) => ({
  typeMessage,
  fileMessageData: { downloadUrl: `${BASE}/files/${encodeURIComponent(name)}`, caption, fileName: name,
    jpegThumbnail: '', mimeType: FILES[name].mime },
});
const edited = (stanzaId, t) => ({ typeMessage: 'editedMessage', editedMessageData: { textMessage: t, stanzaId } });
const deleted = (stanzaId) => ({ typeMessage: 'deletedMessage', deletedMessageData: { stanzaId } });

let receipt = 0;
const queue = [];
let state = 'authorized';
const settings = {
  wid: WID, webhookUrl: '', webhookUrlToken: '', incomingWebhook: 'yes', outgoingMessageWebhook: 'yes',
  outgoingWebhook: 'yes', stateWebhook: 'yes', editedMessageWebhook: 'yes', deletedMessageWebhook: 'yes',
};
const enqueue = (body) => { queue.push({ receiptId: ++receipt, body }); return receipt; };

function basicScenario() {
  const aigerim = '77011234567@c.us';
  const erlan = '77029876543@c.us';
  const group = '120363000000000001@g.us';
  const a = 'Айгерим (тест)';
  const q = [
    incoming(aigerim, a, text('Здравствуйте! Интересует следующее оборудование:\n\n1. Облучатель ОБН-150 (x2)\n'
      + '2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение.')),
    incoming(aigerim, a, file('documentMessage', 'Заявка клиники.xlsx', 'Полный список во вложении')),
    incoming(aigerim, a, file('imageMessage', 'аппарат.png', 'Вот такой стоит сейчас')),
    outgoing(aigerim, a, text('Добрый день! Подготовим КП сегодня.')),
    incoming(aigerim, a, file('documentMessage', 'ТЗ.pdf', '')),
    incoming(aigerim, a, file('documentMessage', 'страница.html', 'проверка: должно скачиваться, а не открываться')),
  ];
  const uzi = incoming(erlan, 'Ерлан (тест)', text('Нужен аппарат УЗИ, какие есть?'));
  q.push(uzi, incoming(erlan, 'Ерлан (тест)', edited(uzi.idMessage, 'Нужен аппарат УЗИ экспертного класса')));
  const g = inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', text('Кто завтра едет в Уральск?'));
  q.push(g,
    inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', file('imageMessage', 'аппарат.png', 'фото из группы')),
    inGroup(group, 'Коллеги West-Med (тест)', '77025556677@c.us', 'Данияр', deleted(g.idMessage)));
  q.forEach(enqueue);
  return q.length;
}

/** Два Excel подряд у одной клиентки: длинная смета (45 строк) и короткая заявка — разбор в позиции. */
function excelScenario() {
  const saule = '77051112233@c.us';
  const s = 'Сауле (тест)';
  const q = [
    incoming(saule, s, text('Добрый день! Нужна смета на оснащение кабинета.')),
    incoming(saule, s, file('documentMessage', 'Смета 45 строк.xlsx', 'Смета целиком')),
    incoming(saule, s, file('documentMessage', 'Заявка клиники.xlsx', 'И отдельно срочное')),
  ];
  q.forEach(enqueue);
  return q.length;
}

// ---------- сервер ----------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE);
  const send = (status, payload, type = 'application/json') => {
    const b = Buffer.isBuffer(payload) || typeof payload === 'string' ? payload : JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': type });
    res.end(b);
  };
  const readBody = async () => { let s = ''; for await (const c of req) s += c; return s; };

  if (req.method === 'POST' && url.pathname === '/__enqueue') return send(200, { receiptId: enqueue(JSON.parse(await readBody())) });
  if (req.method === 'POST' && url.pathname === '/__scenario/basic') return send(200, { enqueued: basicScenario() });
  if (req.method === 'POST' && url.pathname === '/__scenario/excel') return send(200, { enqueued: excelScenario() });
  if (req.method === 'POST' && url.pathname.startsWith('/__state/')) {
    state = decodeURIComponent(url.pathname.slice('/__state/'.length));
    enqueue({ typeWebhook: 'stateInstanceChanged', instanceData: instance(), timestamp: now(), stateInstance: state });
    return send(200, { state });
  }
  if (req.method === 'POST' && url.pathname === '/__settings') {
    Object.assign(settings, JSON.parse((await readBody()) || '{}'));
    return send(200, settings);
  }
  if (req.method === 'GET' && url.pathname === '/__queue') return send(200, queue);
  if (req.method === 'GET' && url.pathname.startsWith('/files/')) {
    const f = FILES[decodeURIComponent(url.pathname.slice('/files/'.length))];
    return f ? send(200, f.data, f.mime) : send(404, '');
  }

  const m = url.pathname.match(/^\/waInstance(\d+)\/([A-Za-z]+)\/([^/]+)(?:\/(\d+))?$/);
  if (!m) return send(404, '');
  if (m[3] !== TOKEN) return send(401, '');
  const method = m[2];
  if (method === 'receiveNotification') {
    const until = Date.now() + Math.min(Number(url.searchParams.get('receiveTimeout') || 5), 60) * 1000;
    while (!queue.length && Date.now() < until) await new Promise((r) => setTimeout(r, 200));
    return send(200, queue.length ? queue[0] : null);
  }
  if (method === 'deleteNotification' && req.method === 'DELETE') {
    const i = queue.findIndex((x) => x.receiptId === Number(m[4]));
    if (i >= 0) queue.splice(i, 1);
    return send(200, { result: i >= 0 });
  }
  if (method === 'getStateInstance') return send(200, { stateInstance: state });
  if (method === 'getSettings') return send(200, settings);
  return send(404, '');
});

server.listen(PORT, '127.0.0.1', () => console.log(`Стаб Green-API: ${BASE} (idInstance любой, токен ${TOKEN === 'dev-token' ? 'dev-token' : 'из STUB_TOKEN'})`));
