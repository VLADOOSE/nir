#!/bin/bash
# Запуск бэкенда со всеми секретами, которые обычно нужны локально.
#
# Зачем скрипт: секреты раньше держали в /tmp, а macOS её периодически чистит —
# токен goszakup пропадал, и «ТЗ»/импорт молча переставали работать. Теперь они
# лежат в ~/.config/ais (переживает перезагрузку, права 700).
#
# Куча: при разборе ТЗ работает PDFBox, и на дефолтной куче бэкенд ловит
# OOM (exit 137) на долгом аптайме — отсюда -Xmx2g (CLAUDE.md §5).
#
# Использование:  ./scripts/run-backend.sh
# Токен положить:  ~/.config/ais/goszakup.token  (одна строка, без кавычек)

set -u
cd "$(dirname "$0")/.." || exit 1

CFG="$HOME/.config/ais"
export JAVA_TOOL_OPTIONS="-Xmx2g"

add_secret() {  # $1 = имя переменной, $2 = файл, $3 = зачем нужен
  if [ -f "$2" ]; then
    export "$1=$(tr -d '\r\n' < "$2")"
    echo "  ✓ $1 — из $2"
  else
    echo "  ✗ $1 — нет файла $2 ($3)"
  fi
}

echo "Секреты:"
add_secret GOSZAKUP_TOKEN "$CFG/goszakup.token" "нужен кнопке «ТЗ» и импорту goszakup"
add_secret MAIL_PASSWORD  "$CFG/zakup.pass"     "нужен боевой отправке КП с zakup@westmed.kz"

# Живой приём почты по умолчанию ВЫКЛЮЧЕН — включается явно:
#   MAIL_IMAP_ENABLED=true ./scripts/run-backend.sh
if [ "${MAIL_IMAP_ENABLED:-}" = "true" ] && [ -f "$CFG/info.pass" ]; then
  export MAIL_IMAP_HOST=imap.mail.ru MAIL_IMAP_PORT=993 MAIL_IMAP_PROTOCOL=imaps \
         MAIL_IMAP_USERNAME=info@westmed.kz MAIL_IMAP_MARKET=KZ MAIL_IMAP_SINCE_MINUTES=1440
  export MAIL_IMAP_PASSWORD="$(tr -d '\r\n' < "$CFG/info.pass")"
  echo "  ✓ приём почты info@westmed.kz включён"
fi

echo
exec ./gradlew bootRun
