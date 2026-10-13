#!/usr/bin/env bash
# Включение приёма писем клиентов info@westmed.kz → «Обращения» (DEPLOY.md §10). Запуск — с Mac, командой оператора:
#   scp -q deploy/set-info-mail.sh root@185.125.46.26:/root/ \
#     && ssh root@185.125.46.26 'bash /root/set-info-mail.sh; rm -f /root/set-info-mail.sh' < ~/.config/ais/info.pass
# Пароль приложения приходит со стандартного входа и не попадает ни на экран, ни в argv. Сначала вход по IMAP (curl,
# учётные данные — конфигом со стандартного входа) и признак папки «Отправленные»; не вошёл — .env не трогается.
# Потом копия .env.bak-…-info, ключи MAIL_CLIENTS_*, пересоздание бэкенда и строки лога о приёме.
set -u
BOX=info@westmed.kz
P=$(cat | tr -d '\r\n')
[ -n "$P" ] || { echo "пароль пуст — ничего не меняю"; exit 1; }
E=$(printf %s "$P" | sed 's/[\\"]/\\&/g')
L=$(printf 'url = "imaps://imap.mail.ru:993/"\nuser = "%s:%s"\n' "$BOX" "$E" | curl -sS -m 25 -K - 2>&1)
printf %s "$L" | grep -q 'INBOX' \
  || { echo "вход по IMAP не прошёл — ничего не меняю: $(printf %s "$L" | head -c 160)"; exit 1; }
echo "IMAP: вход в $BOX прошёл"
printf %s "$L" | grep -q '\\Sent' && echo "IMAP: «Отправленные» с признаком \\Sent есть" \
  || echo "IMAP: признака \\Sent нет — АИС поищет папку по имени («Отправленные» / Sent)"

cd /srv/ais || exit 1
B=.env.bak-$(date +%F-%H%M)-info
cp -p .env "$B" && echo "копия .env — $B"
umask 077
{ [ -z "$(tail -c1 .env)" ] || echo >> .env; }
set_env() { grep -v "^$1=" .env > .env.new; printf '%s=%s\n' "$1" "$2" >> .env.new; cat .env.new > .env; rm -f .env.new; }
set_env MAIL_CLIENTS_ENABLED true
set_env MAIL_CLIENTS_HOST imap.mail.ru
set_env MAIL_CLIENTS_PORT 993
set_env MAIL_CLIENTS_PROTOCOL imaps
set_env MAIL_CLIENTS_USERNAME "$BOX"
set_env MAIL_CLIENTS_MARKET KZ
set_env MAIL_CLIENTS_PASSWORD "$P"
H=$(printf %s "$P" | sha256sum | cut -c1-12)
[ "$(grep -c '^MAIL_CLIENTS_PASSWORD=' .env)" = 1 ] \
  && [ "$(grep '^MAIL_CLIENTS_PASSWORD=' .env | cut -d= -f2- | tr -d '\n' | sha256sum | cut -c1-12)" = "$H" ] \
  && echo ".env: ключи MAIL_CLIENTS_* записаны" || { echo ".env не обновился — бэкенд не трогаю"; exit 1; }
docker compose up -d --force-recreate ais-backend </dev/null 2>&1 | grep -v -i 'variable is not set' | tail -2
for i in $(seq 1 60); do
  docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -q 'Started Nir2Application' && break
  sleep 3
done
docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -E 'Started Nir2Application|Приём писем клиентов' \
  | sed -E 's/^ais-backend-1 *\| //' | cut -c1-200
[ "$(docker exec ais-ais-backend-1 printenv MAIL_CLIENTS_ENABLED </dev/null)" = true ] \
  && echo "АИС: бэкенд запущен с приёмом info@" || echo "АИС: у бэкенда приём info@ НЕ включён — проверить"
unset P E L
