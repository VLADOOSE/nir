#!/usr/bin/env node
// Стаб WAHA для живых проверок «Чатов», карточки обращения и «Система → WhatsApp» без WhatsApp
// (спека docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md §12). Только для разработки.
//
// Запуск:    node scripts/waha-stub.mjs                                       (порт 7708; другой — STUB_PORT=…)
// Бэкенд:    WHATSAPP_ENABLED=true WHATSAPP_PROVIDER=waha WHATSAPP_WAHA_URL=http://localhost:7708 \
//            WHATSAPP_WAHA_API_KEY=dev-key WHATSAPP_WAHA_HMAC_KEY=dev-hmac WHATSAPP_INITIAL_DELAY_MS=3000 ./gradlew bootRun
// Привязка:  сессию создаёт сама АИС → «Система → WhatsApp» показывает QR; «отсканировать» телефоном:
//            curl -s -X POST localhost:7708/__link
// Сценарий:  curl -s -X POST localhost:7708/__scenario/basic    — корзина сайта, Excel, фото, ответ с телефона, PDF, HTML,
//                                                                  правка, группа (фото, удаление), звонки, файл 40 МБ, скрытый номер
//            curl -s -X POST localhost:7708/__scenario/offline  — два сообщения БЕЗ вебхука: их найдёт догонка
//                                                                  (перезапуск бэкенда, ≤10 мин или POST /__status/WORKING)
// Статус:    curl -s -X POST localhost:7708/__status/FAILED     (STOPPED / STARTING / SCAN_QR_CODE / WORKING / PASSKEY_REQUIRED)
// Своё:      curl -s localhost:7708/__send -H 'Content-Type: application/json' -d '{"event":"message.any","payload":{…}}'
// Хранилище: curl -s localhost:7708/__messages
//
// Ключ API (STUB_KEY, по умолчанию dev-key) проверяется, как у настоящей WAHA: чужой — 401. Вебхуки подписываются
// HMAC-SHA512 ключом STUB_HMAC (dev-hmac) и уходят на STUB_HOOK (http://localhost:8080/api/whatsapp/waha/webhook).

import http from 'node:http';
import { createHmac, randomUUID } from 'node:crypto';
import { png, FILES } from './stub-files.mjs';

const PORT = Number(process.env.STUB_PORT || 7708);
const KEY = process.env.STUB_KEY || 'dev-key';
const HMAC = process.env.STUB_HMAC || 'dev-hmac';
const HOOK = process.env.STUB_HOOK || 'http://localhost:8080/api/whatsapp/waha/webhook';
const BASE = `http://localhost:${PORT}`;
const ME = { id: '77000000001@c.us', pushName: 'West-Med (стаб)' };

// ---------- сессия ----------

let session = null;          // { name, status }
let linked = false;
const sessionView = () => session && { name: session.name, status: session.status, me: linked ? ME : null, engine: { engine: 'GOWS' } };

// ---------- справочники «телефона» ----------

const CONTACTS = {
  '77011234567@c.us': { name: 'Айгерим (тест)', pushname: 'Aigerim' },
  '77029876543@c.us': { name: 'Ерлан (тест)', pushname: 'Erlan' },
  '77051112233@c.us': { name: null, pushname: 'Сауле' },
  '77019998877@c.us': { name: 'Марат (тест)', pushname: 'Marat' },
};
const GROUPS = { '120363000000000001@g.us': 'Коллеги West-Med (тест)' };
const LIDS = { '123456789012345@lid': '77071234567@c.us' };

// ---------- сообщения «телефона»: всё, что пришло, — для файлов и догонки ----------

const store = new Map();     // id сообщения WAHA → payload message.any
const fileOf = new Map();    // id сообщения → имя файла в FILES
let seq = 0;
const now = () => Math.floor(Date.now() / 1000);
const rawId = () => 'STUB' + Date.now().toString(16).toUpperCase() + (++seq);
const serverJid = (jid) => (jid.endsWith('@c.us') ? jid.replace('@c.us', '@s.whatsapp.net') : jid);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function message({ chatId, fromMe = false, participant = null, pushName = null, body = null, content = {}, media = null, source }) {
  const raw = rawId();
  const id = `${fromMe}_${chatId}_${raw}` + (participant ? `_${participant}` : '');
  const p = {
    id, timestamp: now(), from: fromMe ? ME.id : chatId, fromMe, to: fromMe ? chatId : ME.id,
    ...(participant ? { participant } : {}),
    ...(source ? { source } : {}),
    body, hasMedia: !!media, media: media ? { url: null, mimetype: media.mimetype, filename: media.filename } : null,
    ack: fromMe ? 1 : 0,
    _data: {
      Info: {
        Chat: serverJid(chatId), Sender: serverJid(fromMe ? ME.id : participant || chatId), IsFromMe: fromMe,
        IsGroup: chatId.endsWith('@g.us'), ID: raw, ...(pushName ? { PushName: pushName } : {}),
      },
      Message: content,
    },
  };
  store.set(id, p);
  return p;
}

const text = (t) => ({ conversation: t });
const incoming = (chatId, pushName, t) => message({ chatId, pushName, body: t, content: text(t) });
const phoneReply = (chatId, t) => message({ chatId, fromMe: true, source: 'app', body: t, content: text(t) });

/** Файл из FILES: размер — в содержимом GOWS (fileLength), байты — по media.url после downloadMedia=true. */
function withFile({ chatId, pushName, participant = null, key, name, caption = null }) {
  const f = FILES[name];
  const p = message({
    chatId, pushName, participant, body: caption,
    content: { [key]: { mimetype: f.mime, fileName: name, fileLength: f.data.length, ...(caption ? { caption } : {}) } },
    media: { mimetype: f.mime, filename: key === 'imageMessage' ? null : name },
  });
  fileOf.set(p.id, name);
  return p;
}

const rawOf = (p) => p._data.Info.ID;

function edit(original, newText) {
  const chatId = original.fromMe ? original.to : original.from;
  return {
    id: `${original.fromMe}_${chatId}_${rawId()}`, timestamp: now(), from: original.from, fromMe: original.fromMe, to: original.to,
    body: newText, editedMessageId: rawOf(original), _data: { Info: { ...original._data.Info, ID: rawId() } },
  };
}

function revoke(original, chatId) {
  return {
    before: null, revokedMessageId: rawOf(original),
    after: { id: `${original.fromMe}_${chatId}_${rawId()}`, timestamp: now(), from: original.from, fromMe: original.fromMe, body: null, _data: {} },
  };
}

const call = (id, from, isVideo) => ({ id, from, timestamp: now(), isVideo, isGroup: false, _data: { CallID: id, From: serverJid(from) } });

// ---------- вебхук: подпись HMAC-SHA512 сырого тела, как у WAHA ----------

async function hook(event, payload) {
  const body = JSON.stringify({
    id: 'evt_' + randomUUID().replaceAll('-', ''), timestamp: Date.now(), event, session: session?.name || 'westmed',
    metadata: { market: 'KZ' }, me: linked ? ME : null, payload, engine: 'GOWS', environment: { version: 'stub' },
  });
  try {
    const r = await fetch(HOOK, {
      method: 'POST', body,
      headers: {
        'Content-Type': 'application/json', 'X-Webhook-Request-Id': 'req_' + randomUUID(), 'X-Webhook-Timestamp': String(Date.now()),
        'X-Webhook-Hmac': createHmac('sha512', HMAC).update(body).digest('hex'), 'X-Webhook-Hmac-Algorithm': 'sha512',
      },
    });
    console.log(`вебхук ${event} → ${r.status}`);
    return r.status;
  } catch (e) {
    console.log(`вебхук ${event} → не доставлен (${e.cause?.code || e.message})`);
    return 0;
  }
}

async function basicScenario() {
  const aigerim = '77011234567@c.us';
  const erlan = '77029876543@c.us';
  const saule = '77051112233@c.us';
  const group = '120363000000000001@g.us';
  // без привязки у событий нет me, а номер сессии АИС ещё не знает — сообщения ушли бы в попытки и пропуск
  if (!linked) return ['сначала «привяжите» номер: curl -X POST localhost:7708/__link'];
  const out = [];
  const go = async (event, payload) => { out.push(`${event}: ${await hook(event, payload)}`); await sleep(200); };

  await go('message.any', incoming(aigerim, 'Aigerim', 'Здравствуйте! Интересует следующее оборудование:\n\n'
    + '1. Облучатель ОБН-150 (x2)\n2. Рециркулятор СН-111-130\n\nПрошу подготовить коммерческое предложение.'));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'Заявка клиники.xlsx', caption: 'Полный список во вложении' }));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'imageMessage', name: 'аппарат.png', caption: 'Вот такой стоит сейчас' }));
  await go('message.any', phoneReply(aigerim, 'Добрый день! Подготовим КП сегодня.'));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'ТЗ.pdf' }));
  await go('message.any', withFile({ chatId: aigerim, pushName: 'Aigerim', key: 'documentMessage', name: 'страница.html',
    caption: 'проверка: должно скачиваться, а не открываться' }));
  const uzi = incoming(erlan, 'Erlan', 'Нужен аппарат УЗИ, какие есть?');
  await go('message.any', uzi);
  await go('message.edited', edit(uzi, 'Нужен аппарат УЗИ экспертного класса'));
  const g = message({ chatId: group, participant: '77025556677@c.us', pushName: 'Данияр', body: 'Кто завтра едет в Уральск?',
    content: text('Кто завтра едет в Уральск?') });
  await go('message.any', g);
  await go('message.any', withFile({ chatId: group, participant: '77025556677@c.us', pushName: 'Данияр', key: 'imageMessage',
    name: 'аппарат.png', caption: 'фото из группы' }));
  await go('message.revoked', revoke(g, group));
  const voice = 'CALL' + rawId();
  await go('call.received', call(voice, saule, false));
  await sleep(1500);
  await go('call.accepted', call(voice, saule, false));
  const video = 'CALL' + rawId();
  await go('call.received', call(video, aigerim, true));
  await sleep(1500);
  await go('call.rejected', call(video, aigerim, true));
  const big = message({ chatId: erlan, pushName: 'Erlan', body: 'каталог целиком',
    content: { documentMessage: { mimetype: 'application/pdf', fileName: 'Каталог 40 МБ.pdf', fileLength: 40 * 1024 * 1024 } },
    media: { mimetype: 'application/pdf', filename: 'Каталог 40 МБ.pdf' } });
  await go('message.any', big);
  const hidden = message({ chatId: '123456789012345@lid', pushName: 'Скрытый номер (тест)', body: 'Добрый день, по поводу стерилизатора',
    content: text('Добрый день, по поводу стерилизатора') });
  hidden._data.Info.SenderAlt = '77071234567@s.whatsapp.net';
  await go('message.any', hidden);
  return out;
}

/** Пришло, пока АИС лежала: только на «телефоне», без вебхука — найдёт догонка. */
function offlineScenario() {
  incoming('77019998877@c.us', 'Marat', 'Добрый день! Пишу, пока АИС была выключена.');
  incoming('77019998877@c.us', 'Marat', 'Нужен дефибриллятор, есть в наличии?');
  return 2;
}

// ---------- сервер ----------

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, BASE);
  const path = url.pathname;
  const reply = (status, payload, type = 'application/json') => {
    const b = Buffer.isBuffer(payload) ? payload : JSON.stringify(payload);
    res.writeHead(status, { 'Content-Type': type });
    res.end(b);
  };
  const readBody = async () => { let s = ''; for await (const c of req) s += c; return s; };
  let m;

  // служебные ручки стаба — без ключа
  if (req.method === 'POST' && path === '/__link') {
    linked = true;
    if (session) session.status = 'WORKING';
    await hook('session.status', { name: session?.name || 'westmed', status: 'WORKING' });
    return reply(200, sessionView());
  }
  if (req.method === 'POST' && path.startsWith('/__status/')) {
    const s = decodeURIComponent(path.slice('/__status/'.length));
    if (session) session.status = s;
    await hook('session.status', { name: session?.name || 'westmed', status: s });
    return reply(200, sessionView());
  }
  if (req.method === 'POST' && path === '/__scenario/basic') return reply(200, { sent: await basicScenario() });
  if (req.method === 'POST' && path === '/__scenario/offline') return reply(200, { stored: offlineScenario() });
  if (req.method === 'POST' && path === '/__send') {
    const e = JSON.parse((await readBody()) || '{}');
    return reply(200, { status: await hook(e.event, e.payload) });
  }
  if (req.method === 'GET' && path === '/__messages') return reply(200, [...store.values()]);
  if (path === '/ping') return reply(200, { message: 'pong' });

  // API WAHA — только с ключом
  if (!path.startsWith('/api/')) return reply(404, {});
  if (req.headers['x-api-key'] !== KEY) return reply(401, { message: 'Unauthorized' });

  if (req.method === 'GET' && (m = path.match(/^\/api\/sessions\/([^/]+)$/))) {
    return session ? reply(200, sessionView()) : reply(404, { message: 'Session not found' });
  }
  if (req.method === 'POST' && path === '/api/sessions') {
    const b = JSON.parse((await readBody()) || '{}');
    if (session) return reply(422, { message: 'Session already exists' });
    session = { name: b.name, status: linked ? 'WORKING' : 'SCAN_QR_CODE' };
    console.log('сессия создана, config:', JSON.stringify(b.config));
    await hook('session.status', { name: session.name, status: session.status });
    return reply(201, sessionView());
  }
  if (req.method === 'POST' && (m = path.match(/^\/api\/sessions\/([^/]+)\/(start|restart|logout)$/))) {
    if (!session) return reply(404, {});
    if (m[2] === 'logout') linked = false;
    session.status = linked ? 'WORKING' : 'SCAN_QR_CODE';
    await hook('session.status', { name: session.name, status: session.status });
    return reply(201, sessionView());
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/auth\/qr$/))) {
    if (!session || session.status !== 'SCAN_QR_CODE') return reply(422, { message: 'Session status is not SCAN_QR_CODE' });
    return reply(200, png(240, 240), 'image/png');        // не QR — сканировать нечего, проверяется показ и обновление
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/chats\/all\/messages$/))) {
    const from = Number(url.searchParams.get('filter.timestamp.gte') || 0);
    const limit = Number(url.searchParams.get('limit') || 100);
    const offset = Number(url.searchParams.get('offset') || 0);
    const list = [...store.values()].filter((x) => x.timestamp >= from).sort((a, b) => a.timestamp - b.timestamp);
    return reply(200, list.slice(offset, offset + limit));
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/chats\/([^/]+)\/messages\/([^/]+)$/))) {
    const id = decodeURIComponent(m[3]);
    const p = store.get(id);
    if (!p) return reply(404, { message: 'Message not found' });
    const copy = structuredClone(p);
    if (url.searchParams.get('downloadMedia') === 'true' && fileOf.has(id)) {
      copy.media = { ...copy.media, url: `${BASE}/api/files/${m[1]}/${encodeURIComponent(id)}` };
    }
    return reply(200, copy);
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/files\/([^/]+)\/(.+)$/))) {
    const f = FILES[fileOf.get(decodeURIComponent(m[2]))];
    return f ? reply(200, f.data, f.mime) : reply(404, {});
  }
  if (req.method === 'GET' && path === '/api/contacts') {
    const id = url.searchParams.get('contactId');
    const c = CONTACTS[id];
    return c ? reply(200, { id, number: id.split('@')[0], ...c }) : reply(404, {});
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/groups\/([^/]+)$/))) {
    const id = decodeURIComponent(m[2]);
    return GROUPS[id] ? reply(200, { id, subject: GROUPS[id] }) : reply(404, {});
  }
  if (req.method === 'GET' && (m = path.match(/^\/api\/([^/]+)\/lids\/([^/]+)$/))) {
    const lid = decodeURIComponent(m[2]);
    return LIDS[lid] ? reply(200, { lid, pn: LIDS[lid] }) : reply(404, {});
  }
  return reply(404, {});
});

server.listen(PORT, '127.0.0.1', () => console.log(`Стаб WAHA: ${BASE} (ключ API ${KEY === 'dev-key' ? 'dev-key' : 'из STUB_KEY'}, вебхуки → ${HOOK})`));
