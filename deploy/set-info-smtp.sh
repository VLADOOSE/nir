#!/usr/bin/env bash
# Включение отправки писем клиентам с info@westmed.kz из АИС (DEPLOY.md §10, спека 2026-10-19-client-mail-reply §10).
# Запуск — с Mac, командой оператора:
#   scp -q deploy/set-info-smtp.sh root@185.125.46.26:/root/ \
#     && ssh root@185.125.46.26 'bash /root/set-info-smtp.sh; rm -f /root/set-info-smtp.sh'
# Адрес и пароль info@ — из /srv/ais/.env (MAIL_CLIENTS_USERNAME / MAIL_CLIENTS_PASSWORD, их записал set-info-mail.sh):
# на экран и в argv не попадают. Сначала вход на smtps://smtp.mail.ru:465 (curl, учётные данные — конфигом со стандартного
# входа); не вошёл — .env не трогается. Потом копия .env.bak-…-smtp, ключи MAIL_CLIENTS_SMTP_*, пересоздание бэкенда и
# строка лога «Отправка писем клиентам».
set -u
cd /srv/ais || exit 1
get() { grep "^$1=" .env | tail -1 | cut -d= -f2-; }
BOX=$(get MAIL_CLIENTS_USERNAME)
P=$(get MAIL_CLIENTS_PASSWORD)
[ -n "$BOX" ] && [ -n "$P" ] || { echo "в .env нет MAIL_CLIENTS_USERNAME / MAIL_CLIENTS_PASSWORD — ничего не меняю"; exit 1; }
EB=$(printf %s "$BOX" | sed 's/[\\"]/\\&/g')
EP=$(printf %s "$P" | sed 's/[\\"]/\\&/g')
# NOOP после входа: без команды curl шлёт HELP, Mail.ru отвечает 500 — и успешный вход выглядел бы отказом (curl 8);
# неверный пароль — curl 67 «Login denied» (проверено с Mac 2026-10-19)
L=$(printf 'url = "smtps://smtp.mail.ru:465"\nuser = "%s:%s"\nrequest = "NOOP"\n' "$EB" "$EP" | curl -sS -m 25 -K - 2>&1)
RC=$?
[ "$RC" = 0 ] || { echo "вход на SMTP не прошёл (curl $RC) — ничего не меняю: $(printf %s "$L" | head -c 160)"; exit 1; }
echo "SMTP: вход в $BOX прошёл"
B=.env.bak-$(date +%F-%H%M)-smtp
cp -p .env "$B" && echo "копия .env — $B"
umask 077
{ [ -z "$(tail -c1 .env)" ] || echo >> .env; }
set_env() { grep -v "^$1=" .env > .env.new; printf '%s=%s\n' "$1" "$2" >> .env.new; cat .env.new > .env; rm -f .env.new; }
set_env MAIL_CLIENTS_SMTP_HOST smtp.mail.ru
set_env MAIL_CLIENTS_SMTP_PORT 465
set_env MAIL_CLIENTS_SMTP_SSL true
echo ".env: ключи MAIL_CLIENTS_SMTP_* записаны"
docker compose up -d --force-recreate ais-backend </dev/null 2>&1 | grep -v -i 'variable is not set' | tail -2
for i in $(seq 1 60); do
  docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -q 'Started Nir2Application' && break
  sleep 3
done
docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -E 'Started Nir2Application|Отправка писем клиентам' \
  | sed -E 's/^ais-backend-1 *\| //' | cut -c1-200
unset P EP L
