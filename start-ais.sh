#!/usr/bin/env bash
# Запуск АИС Регион-Мед / West-Med: backend (goszakup-токен + боевой Mail.ru SMTP/IMAP) + frontend.
# Секреты НЕ в скрипте — читаются из файлов в /tmp (создать их см. ниже). Запуск:  ./start-ais.sh
# Остановить:  ./start-ais.sh stop     |     Логи: tail -f /tmp/ais-backend.log  /tmp/ais-frontend.log

ROOT="/Users/vlad/IdeaProjects/AIS"
IMAP_PASS_FILE=/tmp/imap.pass          # пароль приложения zakup@westmed.kz (SMTP+IMAP)
GOSZAKUP_TOKEN_FILE=/tmp/goszakup.token # токен «Унифицированных сервисов» goszakup.gov.kz
cd "$ROOT" || exit 1

# --- stop ---
stop_all() {
  echo "→ Останавливаю :8080 и :4200…"
  lsof -ti :8080 2>/dev/null | xargs kill -9 2>/dev/null
  lsof -ti :4200 2>/dev/null | xargs kill -9 2>/dev/null
}
if [ "$1" = "stop" ]; then stop_all; echo "Остановлено."; exit 0; fi

# --- проверка секретов ---
MAIL_PASS=""; GZ_TOKEN=""
if [ -f "$IMAP_PASS_FILE" ]; then MAIL_PASS="$(cat "$IMAP_PASS_FILE")"; echo "✓ email-пароль zakup@ найден"; \
  else echo "⚠ нет $IMAP_PASS_FILE → почта не поднимется. Создай:"; \
       echo "    read -rs \"P?zakup@ app-password: \"; printf '%s' \"\$P\" > $IMAP_PASS_FILE; chmod 600 $IMAP_PASS_FILE; unset P"; fi
if [ -f "$GOSZAKUP_TOKEN_FILE" ]; then GZ_TOKEN="$(cat "$GOSZAKUP_TOKEN_FILE")"; echo "✓ goszakup-токен найден"; \
  else echo "⚠ нет $GOSZAKUP_TOKEN_FILE → кнопка «ТЗ» и импорт goszakup не заработают. Создай:"; \
       echo "    read -rs \"T?goszakup token: \"; printf '%s' \"\$T\" > $GOSZAKUP_TOKEN_FILE; chmod 600 $GOSZAKUP_TOKEN_FILE; unset T"; fi

stop_all; sleep 1

# --- backend (boт токен + боевой Mail.ru; приём почты авто каждые 5 мин, окно 14 дней) ---
echo "→ Backend (bootRun)…"
MAIL_HOST=smtp.mail.ru MAIL_PORT=465 MAIL_SMTP_SSL=true MAIL_SMTP_AUTH=true \
  MAIL_USERNAME=zakup@westmed.kz MAIL_PASSWORD="$MAIL_PASS" MAIL_KP_REPLY_TO=zakup@westmed.kz \
  MAIL_IMAP_ENABLED=true MAIL_IMAP_HOST=imap.mail.ru MAIL_IMAP_PORT=993 MAIL_IMAP_PROTOCOL=imaps \
  MAIL_IMAP_USERNAME=zakup@westmed.kz MAIL_IMAP_PASSWORD="$MAIL_PASS" MAIL_IMAP_MARKET=KZ \
  MAIL_IMAP_SINCE_MINUTES=20160 GOSZAKUP_TOKEN="$GZ_TOKEN" \
  nohup ./gradlew bootRun > /tmp/ais-backend.log 2>&1 &
echo "   backend лог: /tmp/ais-backend.log"

# --- frontend ---
echo "→ Frontend (npm start)…"
( cd "$ROOT/frontend" && nohup npm start > /tmp/ais-frontend.log 2>&1 & )
echo "   frontend лог: /tmp/ais-frontend.log"

# --- ждём готовности ---
printf "→ Жду backend :8080 "
for i in $(seq 1 75); do if grep -q "Started Nir2Application" /tmp/ais-backend.log 2>/dev/null; then break; fi; sleep 2; printf "."; done; echo
printf "→ Жду frontend :4200 "
for i in $(seq 1 75); do if [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost:4200 2>/dev/null)" = "200" ]; then break; fi; sleep 2; printf "."; done; echo

echo
echo "✅ Готово:"
echo "   UI:      http://localhost:4200   (admin/admin; рынок слева → West-Med (KZ) ₸)"
echo "   Backend: http://localhost:8080"
echo "   Почта:   отправка КП через zakup@; приём авто каждые 5 мин (окно 14 дней)"
echo "   Логи:    tail -f /tmp/ais-backend.log   |   tail -f /tmp/ais-frontend.log"
echo "   Стоп:    ./start-ais.sh stop"
