#!/bin/bash
# Забрать свежую копию базы АИС с сервера на Mac оператора — DEPLOY.md §4 «Резервные копии».
# Ставится в ~/Library/Scripts/ais-backup-pull.sh; запускает launchd раз в сутки (kz.westmed.ais-backup.plist), а если
# Mac в это время спал — после пробуждения. Хранит 8 последних копий в ~/Backups/AIS (не iCloud: в базе персональные
# данные). Сервер недоступен, копий нет или последняя старше 2 суток (сломался cron на сервере) — уведомление macOS.
set -euo pipefail

HOST=root@185.125.46.26
REMOTE=/srv/ais/backup/daily
DIR="$HOME/Backups/AIS"
KEEP=8

mkdir -p "$DIR"
chmod 700 "$DIR"
notify() { osascript -e "display notification \"$1\" with title \"АИС: резервная копия\"" >/dev/null 2>&1 || true; }

latest=$(ssh -o BatchMode=yes -o ConnectTimeout=20 "$HOST" "ls -1t $REMOTE/nirdb-*.sql.gz 2>/dev/null | head -1") \
    || { notify "Сервер недоступен — копия не забрана"; exit 1; }
[ -n "$latest" ] || { notify "На сервере нет копий базы"; exit 1; }
name=$(basename "$latest")

stamp=${name#nirdb-}
stamp=${stamp%.sql.gz}
age_days=$(( ( $(date +%s) - $(date -j -f %Y-%m-%d "$stamp" +%s) ) / 86400 ))
[ "$age_days" -le 2 ] || notify "Последняя копия на сервере — ${age_days} сут. назад: проверьте cron (DEPLOY.md §4)"

if [ ! -f "$DIR/$name" ]; then
    scp -q -o BatchMode=yes -o ConnectTimeout=20 "$HOST:$latest" "$DIR/$name.part"
    gzip -t "$DIR/$name.part"
    chmod 600 "$DIR/$name.part"
    mv "$DIR/$name.part" "$DIR/$name"
fi
ls -1t "$DIR"/nirdb-*.sql.gz | tail -n +$((KEEP + 1)) | while read -r old; do rm -f "$old"; done
echo "$(date +%FT%T) ok $(du -h "$DIR/$name" | cut -f1) $DIR/$name"
