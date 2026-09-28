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
import { xlsx, XLSX_ROWS, FILES } from './stub-files.mjs';
import { writeFileSync } from 'node:fs';

const PORT = Number(process.env.STUB_PORT || 7707);
const TOKEN = process.env.STUB_TOKEN || 'dev-token';
const BASE = `http://localhost:${PORT}`;
const WID = '77000000001@c.us';

if (process.argv[2] === '--write-xlsx') {
  writeFileSync(process.argv[3], xlsx(XLSX_ROWS));
  console.log('записан', process.argv[3]);
  process.exit(0);
}

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
