#!/usr/bin/env python3
"""Запись характеристик товаров на westmed.kz из таблицы docs/research/*.json (поля siteId, add, fix).

    python3 scripts/westmed-attributes.py [таблица.json]            — только показать, что изменится
    python3 scripts/westmed-attributes.py [таблица.json] --write    — внести

⚠️ Только с «да» оператора: даже без --write скрипт ходит в админ-API сайта учёткой ais@ (пароль — из
~/.config/ais/westmed-prod.pass, не печатается). Исправление применяется, только если на сайте сейчас то же значение,
что в таблице («site»); иначе — пропуск с пометкой (товар правили руками). Новые характеристики — в конец, если такого
названия ещё нет: повторный запуск ничего не добавит. Контракт сайта — как в задаче 15 плана 2026-10-20-product-spec-fit:
PATCH заменяет весь список характеристик товара. Без таблицы — 2026-10-22-aksion-ema-monitor-attributes.json."""
import json, os, sys, urllib.error, urllib.request

BASE = 'https://westmed.kz'
WRITE = '--write' in sys.argv
ARGS = [a for a in sys.argv[1:] if a != '--write']
SRC = ARGS[0] if ARGS else os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'docs', 'research',
                                        '2026-10-22-aksion-ema-monitor-attributes.json')
PASSWORD = open(os.path.expanduser('~/.config/ais/westmed-prod.pass'), encoding='utf-8').read().strip()   # не печатать


def call(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    data = None if body is None else json.dumps(body, ensure_ascii=False).encode('utf-8')
    with urllib.request.urlopen(urllib.request.Request(BASE + path, data=data, method=method, headers=headers), timeout=30) as r:
        raw = r.read()
        return json.loads(raw) if raw else None


items = json.load(open(SRC, encoding='utf-8'))
# Пределы сайта (westmed V2__catalog.sql: product_attributes.attribute_name VARCHAR(100), attribute_value VARCHAR(500)):
# длиннее — сайт отвечает 500, и запись обрывается посреди списка. Проверяем всю таблицу до входа.
NAME_MAX, VALUE_MAX = 100, 500
long = [f"{it['name'][:40]} / {a['name'][:60]}: название {len(a['name'])}, значение {len(a['value'])}"
        for it in items for a in it.get('add', []) + [f['to'] for f in it.get('fix', []) if f.get('to')]
        if len(a['name']) > NAME_MAX or len(a['value']) > VALUE_MAX]
if long:
    sys.exit(f'длиннее пределов сайта (название ≤ {NAME_MAX}, значение ≤ {VALUE_MAX}) — сократите в таблице:\n  ' + '\n  '.join(long))
try:
    token = call('POST', '/api/auth/login', {'email': 'ais@westmed.kz', 'password': PASSWORD})['accessToken']
except urllib.error.HTTPError as e:
    # у сайта не больше 5 входов за 5 минут с одного адреса — каждый запуск скрипта входит один раз
    sys.exit('сайт ограничил вход (не больше 5 за 5 минут с адреса) — повторите через 5 минут' if e.code == 429
             else f'вход на сайт не удался: HTTP {e.code}')
total_add = total_fix = skipped = done = 0
for it in items:
    card = call('GET', f"/api/admin/products/{it['siteId']}", token=token)
    have = [{'name': a['attributeName'], 'value': a['attributeValue']}
            for a in sorted(card.get('attributes') or [], key=lambda a: a.get('sortOrder') or 0)]
    fixes = {f['name']: f for f in it.get('fix', [])}
    after, nfix = [], 0
    for a in have:
        f = fixes.get(a['name'])
        if f is None:
            after.append(a); continue
        if a['value'] != f['site']:
            if not f.get('remove') and a['name'] == f['to']['name'] and a['value'] == f['to']['value']:
                done += 1; after.append(a); continue   # повторный запуск: уже исправлено
            print(f"   ! {card['name'][:50]} / {a['name']}: на сайте «{a['value'][:60]}» — не как в таблице, не трогаем")
            skipped += 1; after.append(a); continue
        nfix += 1
        if not f.get('remove'):
            after.append({'name': f['to']['name'], 'value': f['to']['value']})
    names = {a['name'].strip().lower() for a in after}
    add = [a for a in it.get('add', []) if a['name'].strip().lower() not in names]
    print(f"{card['name'][:70]}: было {len(have)}, исправить {nfix}, добавить {len(add)}")
    total_add += len(add); total_fix += nfix
    if WRITE and (add or nfix):
        try:
            res = call('PATCH', f"/api/admin/products/{it['siteId']}", {'attributes': after + add}, token=token)
        except urllib.error.HTTPError as e:
            sys.exit(f"сайт не принял «{card['name'][:50]}»: HTTP {e.code} — остановка; записанное раньше осталось, "
                     "повторный запуск продолжит с этого товара")
        if len(res.get('attributes') or []) != len(after) + len(add):
            sys.exit(f"после записи «{card['name'][:50]}» число характеристик не сошлось — остановка")
print(('внесено' if WRITE else 'будет внесено') + f': добавить {total_add}, исправить {total_fix}; уже исправлено {done}; '
      f'пропущено (на сайте другое) {skipped}')
