#!/bin/bash
# Ежесуточная копия базы АИС (nirdb) на сервере — DEPLOY.md §4 «Резервные копии».
# Ставится: scp → /usr/local/sbin/ais-db-backup.sh (root, 700) + ais-db-backup.cron → /etc/cron.d/ais-db-backup.
# Хранит 14 суточных копий в /srv/ais/backup/daily (600, только root); Mac оператора забирает свежую (mac-pull.sh).
# Копия, которая не проверилась (gzip битый, нет штатного конца pg_dump), не заменяет хорошие и не удаляет старые.
set -euo pipefail

DIR=/srv/ais/backup/daily
KEEP_DAYS=14

mkdir -p "$DIR"
chmod 700 "$DIR"
f="$DIR/nirdb-$(date -u +%Y-%m-%d).sql.gz"
tmp="$f.part"
trap 'rm -f "$tmp"' EXIT

cd /srv/ais
docker compose exec -T ais-postgres sh -c 'pg_dump -U "$POSTGRES_USER" nirdb' </dev/null | gzip > "$tmp"
gzip -t "$tmp"
[ "$(zcat "$tmp" | tail -n 5 | grep -c 'PostgreSQL database dump complete')" -ge 1 ]
chmod 600 "$tmp"
mv "$tmp" "$f"
find "$DIR" -name 'nirdb-*.sql.gz' -mtime +"$KEEP_DAYS" -delete
echo "$(date -u +%FT%TZ) ok $(du -h "$f" | cut -f1) $f"
