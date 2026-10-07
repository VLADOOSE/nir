#!/usr/bin/env bash
# Перевыпуск токена бота @West_Med_bot — он общий для трёх систем на 185.125.46.26: АИС (.env), сайт westmed
# (app_settings) и medsite1 / vital-spb.kz (notification_setting). Revoke в @BotFather отключает старый токен сразу
# у всех, поэтому сразу после него (DEPLOY.md §8, «Токен бота общий…»):
#   на Mac: вставить в строку ввода `(umask 077; pbpaste | tr -d '[:space:]' > ~/.config/ais/westmed-bot.token)`,
#           ПОТОМ скопировать токен в Telegram и только тогда Enter (иначе в буфере окажется сама команда);
#   scp -q deploy/rotate-telegram-bot-token.sh root@185.125.46.26:/root/ \
#     && ssh root@185.125.46.26 'bash /root/rotate-telegram-bot-token.sh; rm -f /root/rotate-telegram-bot-token.sh' \
#        < ~/.config/ais/westmed-bot.token
# Токен приходит со стандартного входа и не попадает ни на экран, ни в argv. Сначала проверка в самом Telegram
# (getMe — @West_Med_bot, getChat — группа «Заявки»), потом запись: сайт и medsite читают токен из базы при каждой
# отправке (перезапуск не нужен), АИС — из .env при старте (копия .env.bak-…-token, бэкенд пересоздаётся — сессии
# сбрасываются). В каждом месте сверяется отпечаток SHA-256. Применено впервые 2026-10-07.
T=$(cat | tr -d '[:space:]')
printf %s "$T" | grep -Eq '^[0-9]{6,12}:[A-Za-z0-9_-]{30,}$' || { echo "это не токен бота — ничего не меняю"; exit 1; }
H=$(printf %s "$T" | sha256sum | cut -c1-12)
same() { [ "$(printf %s "$1" | tr -d '[:space:]' | sha256sum | cut -c1-12)" = "$H" ]; }
# Bot API — через рабочий адрес (DEPLOY.md §8); адрес с токеном идёт в curl конфигом со стандартного входа
tg() { printf 'url = "https://api.telegram.org/bot%s/%s"\n' "$T" "$1" \
         | curl -sS -m 15 --resolve api.telegram.org:443:149.154.167.220 -K - 2>&1; }

ME=$(tg getMe)
printf %s "$ME" | grep -q '"ok":true' && printf %s "$ME" | grep -q '"username":"West_Med_bot"' \
  || { echo "getMe: токен не принят или это не @West_Med_bot — ничего не меняю: $(printf %s "$ME" | cut -c1-160)"; exit 1; }
CH=$(tg 'getChat?chat_id=-1004352219740')
printf %s "$CH" | grep -q '"ok":true' \
  || { echo "getChat: бот не видит группу «Заявки» — ничего не меняю: $(printf %s "$CH" | cut -c1-160)"; exit 1; }
echo "Telegram: токен принят, это @West_Med_bot, группа «Заявки» видна"

echo "=== 1/3 сайт westmed"
docker exec -i westmed-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<SQL
update app_settings set value = '$T' where key = 'telegram_bot_token';
SQL
V=$(docker exec -i westmed-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL'
select value from app_settings where key = 'telegram_bot_token';
SQL
)
same "$V" && echo "westmed: в базе новый токен" || echo "westmed: в базе НЕ новый токен — проверить"

echo "=== 2/3 medsite1"
docker exec -i medsite1-db-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -v ON_ERROR_STOP=1' <<SQL
update notification_setting set value = '$T', updated_at = now() where channel = 'telegram' and setting_key = 'botToken';
SQL
V=$(docker exec -i medsite1-db-1 sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' <<'SQL'
select value from notification_setting where channel = 'telegram' and setting_key = 'botToken';
SQL
)
same "$V" && echo "medsite1: в базе новый токен" || echo "medsite1: в базе НЕ новый токен — проверить"

echo "=== 3/3 АИС"
cd /srv/ais || exit 1
B=.env.bak-$(date +%F-%H%M)-token
cp -p .env "$B" && echo "копия .env — $B"
umask 077
grep -v '^TELEGRAM_BOT_TOKEN=' .env > .env.new && printf 'TELEGRAM_BOT_TOKEN=%s\n' "$T" >> .env.new \
  && cat .env.new > .env && rm -f .env.new
[ "$(grep -c '^TELEGRAM_BOT_TOKEN=' .env)" = 1 ] && same "$(grep '^TELEGRAM_BOT_TOKEN=' .env | cut -d= -f2-)" \
  && echo "АИС: в .env новый токен" || { echo "АИС: .env не обновился — бэкенд не трогаю"; exit 1; }
docker compose up -d --force-recreate ais-backend </dev/null 2>&1 | grep -v -i 'variable is not set' | tail -2
for i in $(seq 1 60); do
  docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -q 'Started Nir2Application' && break
  sleep 3
done
docker compose logs --since 3m ais-backend </dev/null 2>/dev/null | grep -E 'Started Nir2Application|не настроен|Уведомления о новых' \
  | sed -E 's/^ais-backend-1 *\| //' | cut -c1-200
same "$(docker exec ais-ais-backend-1 printenv TELEGRAM_BOT_TOKEN </dev/null)" \
  && echo "АИС: бэкенд запущен с новым токеном" || echo "АИС: у бэкенда НЕ новый токен — проверить"
unset T V ME CH
