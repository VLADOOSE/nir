// Заглушка Telegram Bot API для живой проверки (слушает только 127.0.0.1:7709): уведомления, кнопки бота, файлы, нажатия.
// GET /__messages — что пришло; GET /__files/<n> — файл n как есть; POST /__press {from, message_id, data} — нажатие кнопки;
// POST /__reply {from, reply_to, text} — ответ человека на сообщение бота; POST /__fail/<код>[?retry_after=N] — следующий
// ответ (кроме getUpdates) с этим кодом. Токен из пути НЕ печатается.
import http from 'node:http';

const BOT_ID = 7000000001;
const CHAT = -1001;
const messages = [];   // всё, что прислал бэкенд (с кнопками, файлами, отметкой «удалено»)
const files = [];      // байты файлов: /__files/<index>
const updates = [];    // очередь getUpdates
const waiters = [];    // висящие long-poll
let nextMessageId = 1;
let nextUpdateId = 1;
let fail = null;

const json = (res, code, obj) => { res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8' }); res.end(JSON.stringify(obj)); };
const ok = (res, result) => json(res, 200, { ok: true, result });
const parseJson = (buf) => { try { const m = JSON.parse(buf.toString('utf8') || '{}'); return m && typeof m === 'object' ? m : null; } catch { return null; } };

function multipart(buf, boundary) {
  const fields = {};
  let file = null;
  const sep = Buffer.from('--' + boundary);
  let pos = buf.indexOf(sep);
  while (pos !== -1) {
    const start = pos + sep.length;
    if (buf.subarray(start, start + 2).toString() === '--') break;
    const next = buf.indexOf(sep, start);
    if (next === -1) break;
    const part = buf.subarray(start + 2, next - 2);
    const headEnd = part.indexOf('\r\n\r\n');
    const head = part.subarray(0, headEnd).toString('utf8');
    const data = part.subarray(headEnd + 4);
    const name = /name="([^"]+)"/.exec(head)?.[1];
    const filename = /filename="([^"]*)"/.exec(head)?.[1];
    if (filename !== undefined) file = { name: filename, type: /Content-Type: ([^\r\n]+)/i.exec(head)?.[1] || 'application/octet-stream', bytes: data };
    else if (name) fields[name] = data.toString('utf8');
    pos = next;
  }
  return { fields, file };
}

function buttons(markup) {
  const m = typeof markup === 'string' ? parseJson(Buffer.from(markup)) : markup;
  return (m?.inline_keyboard || []).map(row => row.map(b => b.text + ' → ' + (b.callback_data ?? b.url ?? ('copy: ' + b.copy_text?.text))));
}

function push(update) {
  updates.push({ update_id: nextUpdateId++, ...update });
  while (waiters.length) waiters.shift()();
}

function getUpdates(req, res) {
  const offset = Number(req.offset ?? 0);
  const timeout = Math.min(Number(req.timeout ?? 0), 25);
  if (offset === -1) return ok(res, updates.slice(-1));
  while (updates.length && updates[0].update_id < offset) updates.shift();   // подтверждённые
  if (updates.length || timeout === 0) return ok(res, updates.slice());
  const timer = setTimeout(() => { const i = waiters.indexOf(wake); if (i >= 0) waiters.splice(i, 1); ok(res, []); }, timeout * 1000);
  const wake = () => { clearTimeout(timer); ok(res, updates.filter(u => u.update_id >= offset)); };
  waiters.push(wake);
}

function bot(method, body, contentType, res) {
  if (method === 'getUpdates') return getUpdates(parseJson(body) || {}, res);
  if (fail) {
    const { code, retryAfter } = fail;
    fail = null;
    const payload = { ok: false, error_code: code, description: 'stub failure ' + code };
    if (code === 429) payload.parameters = { retry_after: retryAfter };
    console.log(new Date().toISOString(), method, '→', code);
    return json(res, code, payload);
  }
  if (method === 'getMe') return ok(res, { id: BOT_ID, is_bot: true, username: 'stub_bot' });
  const boundary = /boundary=(.+)$/.exec(contentType || '')?.[1];
  const m = boundary ? multipart(body, boundary) : { fields: parseJson(body), file: null };
  if (!m.fields) return json(res, 400, { ok: false, error_code: 400, description: 'stub: bad json' });
  const f = m.fields;
  const at = new Date().toISOString();
  if (method === 'deleteMessage') {
    const msg = messages.find(x => x.id === Number(f.message_id));
    if (msg) msg.deleted = at;
    console.log(at, 'deleteMessage', f.message_id);
    return ok(res, true);
  }
  if (method === 'editMessageReplyMarkup' || method === 'editMessageText') {
    const msg = messages.find(x => x.id === Number(f.message_id));
    if (msg) { msg.buttons = buttons(f.reply_markup); if (f.text) msg.text = f.text; msg.edited = at; }
    console.log(at, method, f.message_id);
    return ok(res, true);
  }
  if (method === 'answerCallbackQuery') {
    messages.push({ at, method, text: f.text || '', alert: !!f.show_alert });
    console.log(at, 'answerCallbackQuery', f.show_alert ? '(окно)' : '', f.text || '');
    return ok(res, true);
  }
  const id = nextMessageId++;
  const replyTo = (typeof f.reply_parameters === 'string' ? parseJson(Buffer.from(f.reply_parameters)) : f.reply_parameters)?.message_id;
  const rec = { id, at, method, chat_id: f.chat_id, thread: f.message_thread_id, silent: String(f.disable_notification) === 'true',
    text: f.text ?? f.caption, buttons: buttons(f.reply_markup), replyTo, forceReply: !!f.reply_markup?.force_reply };
  if (m.file) { files.push(m.file); rec.file = { name: m.file.name, type: m.file.type, size: m.file.bytes.length, index: files.length - 1 }; }
  messages.push(rec);
  console.log(at, method, '#' + id, 'thread', rec.thread, rec.silent ? '(тихо)' : '(звук)', rec.file ? 'файл ' + rec.file.name : '');
  console.log((rec.text || '') + '\n' + rec.buttons.map(r => '  [' + r.join(' | ') + ']').join('\n') + '\n');
  return ok(res, { message_id: id });
}

http.createServer((req, res) => {
  const chunks = [];
  req.on('data', c => chunks.push(c));
  req.on('end', () => {
    const body = Buffer.concat(chunks);
    const url = new URL(req.url, 'http://127.0.0.1');
    if (req.method === 'GET' && url.pathname === '/__messages') return json(res, 200, messages);
    const file = url.pathname.match(/^\/__files\/(\d+)$/);
    if (req.method === 'GET' && file && files[Number(file[1])]) {
      const f = files[Number(file[1])];
      res.writeHead(200, { 'Content-Type': f.type });
      return res.end(f.bytes);
    }
    const failMatch = url.pathname.match(/^\/__fail\/(\d{3})$/);
    if (req.method === 'POST' && failMatch) {
      fail = { code: Number(failMatch[1]), retryAfter: Number(url.searchParams.get('retry_after') || 30) };
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/__press') {
      const p = parseJson(body) || {};
      push({ callback_query: { id: 'cb' + nextUpdateId, from: { id: Number(p.from) }, message: { message_id: Number(p.message_id), chat: { id: CHAT } }, data: p.data } });
      return json(res, 200, { ok: true });
    }
    if (req.method === 'POST' && url.pathname === '/__reply') {
      const p = parseJson(body) || {};
      push({ message: { message_id: nextMessageId++, chat: { id: CHAT }, from: { id: Number(p.from) }, text: p.text,
        reply_to_message: { message_id: Number(p.reply_to), from: { id: BOT_ID } } } });
      return json(res, 200, { ok: true });
    }
    const call = url.pathname.match(/^\/bot[^/]+\/(\w+)$/);
    if (req.method === 'POST' && call) return bot(call[1], body, req.headers['content-type'], res);
    res.writeHead(404);
    res.end();
  });
}).listen(7709, '127.0.0.1', () => console.log('Telegram stub: http://127.0.0.1:7709 (GET /__messages, POST /__press, /__reply)'));
