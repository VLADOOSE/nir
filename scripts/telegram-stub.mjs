// Заглушка Telegram Bot API для живой проверки уведомлений о почте (слушает только 127.0.0.1:7709).
// sendMessage → {ok:true} (тело — не объект JSON → 400, заглушка не падает); GET /__messages — что пришло;
// POST /__fail/<код> — следующий ответ с этим кодом (429 — с retry_after 30). Токен из пути НЕ печатается.
import http from 'node:http';

const messages = [];
let failNext = null;
let nextId = 1;

/** Тело sendMessage — объект JSON; иначе null: ответ 400, а не исключение, которое уронило бы заглушку. */
function parseJson(body) {
  try {
    const m = JSON.parse(body || '{}');
    return m !== null && typeof m === 'object' ? m : null;
  } catch {
    return null;
  }
}

http.createServer((req, res) => {
  let body = '';
  req.on('data', c => { body += c; });
  req.on('end', () => {
    const path = req.url.replace(/\/bot[^/]+\//, '/bot***/');
    if (req.method === 'GET' && req.url === '/__messages') {
      res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8' });
      return res.end(JSON.stringify(messages, null, 2));
    }
    const fail = req.url.match(/^\/__fail\/(\d{3})$/);
    if (req.method === 'POST' && fail) {
      failNext = Number(fail[1]);
      res.writeHead(200);
      return res.end('ok');
    }
    if (req.method === 'POST' && /\/bot[^/]+\/sendMessage$/.test(req.url)) {
      if (failNext) {
        const code = failNext;
        failNext = null;
        const payload = { ok: false, error_code: code, description: 'stub failure ' + code };
        if (code === 429) payload.parameters = { retry_after: 30 };
        res.writeHead(code, { 'Content-Type': 'application/json' });
        console.log(new Date().toISOString(), path, '→', code);
        return res.end(JSON.stringify(payload));
      }
      const m = parseJson(body);
      if (!m) {
        res.writeHead(400, { 'Content-Type': 'application/json' });
        console.log(new Date().toISOString(), path, '→ 400 (тело — не объект JSON)');
        return res.end(JSON.stringify({ ok: false, error_code: 400, description: 'stub: bad json' }));
      }
      messages.push({ at: new Date().toISOString(), chat_id: m.chat_id, thread: m.message_thread_id,
        silent: !!m.disable_notification, text: m.text });
      console.log(new Date().toISOString(), path, 'thread', m.message_thread_id, m.disable_notification ? '(тихо)' : '(звук)');
      console.log(m.text + '\n');
      res.writeHead(200, { 'Content-Type': 'application/json' });
      return res.end(JSON.stringify({ ok: true, result: { message_id: nextId++ } }));
    }
    res.writeHead(404);
    res.end();
  });
}).listen(7709, '127.0.0.1', () => console.log('Telegram stub: http://127.0.0.1:7709 (GET /__messages)'));
