#!/bin/bash
# Поставить конфиг nginx хоста для ais.westmed.kz с автоматическим откатом.
#   deploy/nginx/apply.sh [файл]   — по умолчанию эталон deploy/nginx/zz-ais.westmed.kz.conf
# Битый конфиг в sites-enabled сорвал бы следующий reload — в том числе из хука certbot при продлении
# сертификатов соседних сайтов, поэтому при провале `nginx -t` прежний файл возвращается на место.
# Прежний конфиг остаётся рядом — /etc/nginx/sites-available/zz-ais.westmed.kz.prev (для ручного отката).
set -euo pipefail
SRV=${AIS_SERVER:-root@185.125.46.26}
CONF=${1:-"$(cd "$(dirname "$0")" && pwd)/zz-ais.westmed.kz.conf"}

scp -q "$CONF" "$SRV:/tmp/zz-ais.westmed.kz.new"
ssh "$SRV" 'bash -s' <<'EOF'
set -euo pipefail
A=/etc/nginx/sites-available/zz-ais.westmed.kz
E=/etc/nginx/sites-enabled/zz-ais.westmed.kz
NEW=/tmp/zz-ais.westmed.kz.new
if [ -e "$A" ]; then cp -p "$A" "$A.prev"; fi
install -m 644 "$NEW" "$A"
rm -f "$NEW"
ln -sf "$A" "$E"
if nginx -t -q; then
  systemctl reload nginx
  echo "nginx: конфиг установлен и перечитан (прежний — $A.prev)"
else
  if [ -e "$A.prev" ]; then mv "$A.prev" "$A"; else rm -f "$E" "$A"; fi
  echo "nginx -t не прошёл — откатил:"
  nginx -t 2>&1 | sed 's/^/  /'
  exit 1
fi
EOF
