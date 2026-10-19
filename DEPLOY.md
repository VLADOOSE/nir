# Деплой АИС на oblako.kz (Docker + GitHub Actions + Tailscale)

Сервер уже держит **westmed.kz** и **vital-spb.kz** (оба в Docker) на 3.8 ГБ RAM. АИС ставится
**рядом, изолированно**: свой стек в Docker, БД внутри сети, наружу — только приватный доступ по Tailscale.
Хостовый nginx и оба сайта **не трогаются**.

```
git push main → GitHub Actions собирает 3 образа → GHCR
                                         ↓
        сервер: docker compose pull && up -d   (только тянет, не собирает)
                                         ↓
             доступ: https://<хост>.<tailnet>.ts.net  (только в tailnet)
```

Порты: фронт АИС слушает `127.0.0.1:8090` (свободен; заняты 3100/3200/8180/8280/9100/9101).
БД и бэкенд наружу/на хост **не публикуются** вообще.

---

## 0. Единоразово: подготовка сервера

### 0.1 Swap — ОБЯЗАТЕЛЬНО (сейчас 0, а RAM в обрез)
```bash
sudo fallocate -l 3G /swapfile
sudo chmod 600 /swapfile
sudo mkswap /swapfile
sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
sudo sysctl vm.swappiness=10 && echo 'vm.swappiness=10' | sudo tee /etc/sysctl.d/99-swap.conf
free -h    # проверь, что Swap: 3.0Gi
```

### 0.2 Tailscale (приватный доступ)
```bash
curl -fsSL https://tailscale.com/install.sh | sh
sudo tailscale up            # открой ссылку, залогинься в свой tailnet
# отдать фронт АИС в tailnet по HTTPS (порт 80/443 хоста НЕ занимает):
sudo tailscale serve --bg 127.0.0.1:8090
sudo tailscale serve status  # покажет https://<хост>.<tailnet>.ts.net
```
Устройства сотрудников: поставить Tailscale, войти в тот же tailnet → открывают адрес выше в браузере.
Никто вне tailnet сервер АИС не видит. (ufw/облачный firewall менять НЕ нужно — Tailscale работает исходящими.)

### 0.3 Пользователь деплоя + каталог
```bash
sudo useradd -m -s /bin/bash deploy
sudo usermod -aG docker deploy          # docker без sudo
sudo mkdir -p /srv/ais && sudo chown deploy:deploy /srv/ais
# SSH-ключ для CI (без пароля):
sudo -u deploy ssh-keygen -t ed25519 -f /home/deploy/.ssh/id_ed25519 -N ''
sudo -u deploy bash -c 'cat ~/.ssh/id_ed25519.pub >> ~/.ssh/authorized_keys'
sudo cat /home/deploy/.ssh/id_ed25519    # ← ПРИВАТНЫЙ ключ, положишь в GitHub secret DEPLOY_SSH_KEY
```

### 0.4 Логин сервера в GHCR (тянуть приватные образы)
Создай на GitHub PAT (classic) со `read:packages` → на сервере:
```bash
sudo -u deploy bash -c 'echo <PAT> | docker login ghcr.io -u VLADOOSE --password-stdin'
```

### 0.5 .env на сервере
```bash
# скопируй .env.example из репо в /srv/ais/.env и заполни реальными секретами
sudo -u deploy nano /srv/ais/.env
```
🔴 Настоящие: пароль БД, пароль приложения mail.ru, токен goszakup. В git НЕ коммитить.

---

## 1. Единоразово: секреты GitHub
Repo → Settings → Secrets and variables → Actions → New secret:
- `DEPLOY_HOST` — IP/домен сервера
- `DEPLOY_USER` — `deploy`
- `DEPLOY_SSH_KEY` — приватный ключ из шага 0.3 (целиком, с `-----BEGIN...`)

`GITHUB_TOKEN` — встроенный, ничего не надо.

---

## 2. Первый деплой
Вариант А (через CI): просто `git push` в `main` — workflow соберёт образы и задеплоит.
Вариант Б (руками, первый раз проверить): на сервере
```bash
cd /srv/ais
# положи сюда docker-compose.yml (CI кладёт сам; вручную — scp из репо)
docker compose pull && docker compose up -d
docker compose logs -f ais-backend     # дождись "Started Nir2Application"
```
При первом старте Flyway накатит `V1..V12`, поднимет `pg_trgm`, Java-инициализатор зальёт реестр
НЦЭЛС (~14k) из JSON внутри образа, `random_page_cost=1.1` уже вшит в образ БД.

Проверка:
```bash
docker compose ps            # все healthy/up
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8090   # 200
```
Затем открой `https://<хост>.<tailnet>.ts.net` с устройства в tailnet → логинься.

---

## 3. Дальнейшая доработка (CI/CD)
Пишешь код локально → `./gradlew test` (гейт) → `git push main` → через ~3–5 мин прод сам обновился.
Откат: в `docker-compose.yml` замени `:latest` на `:<нужный git-sha>` и `docker compose up -d`
(CI пушит и SHA-теги).

---

## 4. Эксплуатация
- **Резервные копии базы — с 2026-10-15** (до этого автокопий не было, только ручные перед чистками). Скрипты — `deploy/backup/`:
  - **Сервер:** `ais-db-backup.sh` → `/usr/local/sbin/ais-db-backup.sh` (root, 700), задание `ais-db-backup.cron` → `/etc/cron.d/ais-db-backup` (644): раз в сутки в 21:30 UTC (02:30 по Алматы) `pg_dump` → `/srv/ais/backup/daily/nirdb-ГГГГ-ММ-ДД.sql.gz` (600, папка 700), хранятся 14 суток; копия, не прошедшая проверку (`gzip -t` и штатный конец pg_dump), хорошие не вытесняет и старые не удаляет. Журнал — `/var/log/ais-db-backup.log`. Ручные копии перед чистками — по-прежнему `/srv/ais/backup/*.sql.gz`.
  - **Mac оператора:** `mac-pull.sh` → `~/Library/Scripts/ais-backup-pull.sh`, задание launchd `kz.westmed.ais-backup.plist` (`__HOME__` → `$HOME`) → `~/Library/LaunchAgents/`, `launchctl bootstrap gui/$(id -u) <plist>`: раз в сутки в 12:00 (спал — после пробуждения; выключен — до следующего дня) забирает свежую копию в `~/Backups/AIS` (не iCloud — в базе персональные данные), хранит 8 последних; сервер недоступен, копий нет или последняя старше 2 суток — уведомление macOS. Журнал — `~/Backups/AIS/pull.log`; запустить сейчас — `launchctl kickstart -k gui/$(id -u)/kz.westmed.ais-backup`. Ключ SSH без пароля — иначе фоновое задание не войдёт.
  - **Проверено 2026-10-15:** копия (8,9 МБ) восстановлена во временную базу `nirdb_restore_check` без ошибок, 14 ключевых таблиц совпали с рабочей построчно (тендеры, лоты, реестр, обращения, переписка с файлами, КП, запросы цен, пользователи, 29 миграций); отказ `pg_dump` — код 1 и ни одного файла.
  - **Восстановление:** остановить бэкенд (`docker compose stop ais-backend`), затем `docker compose exec -T ais-postgres sh -c 'dropdb -U "$POSTGRES_USER" nirdb && createdb -U "$POSTGRES_USER" nirdb'` и `zcat <копия>.sql.gz | docker compose exec -T ais-postgres sh -c 'psql -q -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d nirdb'` → `docker compose start ais-backend` (Flyway увидит те же миграции). Сначала — во временную базу, как в проверке выше, если копия под сомнением.
- **Диск** (2026-10-15 был 83 %, стал 48 %): занимают его в основном кэш сборки Docker (сайты westmed и medsite1 собираются на сервере) и журнал systemd. Чистить только так: `docker builder prune -f --filter until=72h` (кэш сборки старше 3 суток — образы, контейнеры и тома не трогает; свежий кэш сайтов остаётся) и `journalctl --vacuum-time=14d`. Журнал ограничен 500 МБ (`/etc/systemd/journald.conf.d/ais-size.conf`; по умолчанию было 10 % диска — 3,8 ГБ). ⚠️ Не `docker image prune -a` и не `docker system prune -a`: образ `certbot/certbot` «не используется» контейнерами, но medsite1 запускает его раз в неделю (`/root/medsite1/renew-ssl.sh`) — снесло бы продление сертификата; тома — данные баз, minio и сессия WhatsApp.
  ⚠️ Контейнеры на сервере называются с префиксом compose-проекта — `ais-ais-postgres-1`, `ais-ais-backend-1`, — поэтому голый `docker exec ais-postgres …` падает «No such container». Через `docker compose` (из `/srv/ais`) работают имена сервисов.
- **Логи:** `docker compose logs -f ais-backend`
- **Рестарт:** `docker compose restart ais-backend`
- **Память:** после первого разбора ТЗ глянь `free -h` / `docker stats`. Если бэкенд упирается в
  `mem_limit 1300m` и падает на тяжёлых ТЗ — подними swap до 4–6 ГБ или VPS до 8 ГБ (тогда `-Xmx2g`).

---

## 5. 🔴 Хардинг перед боевым использованием
- ~~Сменить `admin/admin` и `operator/operator`~~ ✔ **2026-09-27** — штатным `PUT /api/users/{id}`; новые пароли у оператора в `~/.config/ais/ais-admin.pass` / `ais-operator.pass` (см. §6).
- **Перевыпустить** пароли приложений mail.ru и токен goszakup (светились в переписке).
- Демо-сид Flyway `V2` — при желании почистить новой миграцией с очисткой демо-строк, не правкой V2 (следующая свободная на 2026-10-11 — V26: V24 заняла почта zakup@, V25 — пакет 3 импорта; если V26 уже займёт сид волны 2 КП — следующая за ней).
- Позже: увести SSH за Tailscale и закрыть :22 в ufw (сейчас 22 публичен) — тогда доступ к серверу
  тоже только по VPN.

---

## 6. Доступ по `https://ais.westmed.kz` (без Tailscale)
Шаг 1 (2026-09-27): адрес, сертификат, общий пароль nginx. Шаг 2 (2026-09-28): **калитка по коду устройства** вместо общего пароля — спека `docs/superpowers/specs/2026-09-28-device-gate-design.md`. Tailscale и SSH-туннель работают параллельно и калитку обходят — это запасной вход админа.
- **DNS:** A-запись `ais` → `185.125.46.26` в панели unihost.kz (NS домена — `ns1/ns2.unihost.kz`).
- **nginx хоста:** эталон — `deploy/nginx/zz-ais.westmed.kz.conf`, ставится `deploy/nginx/apply.sh [файл]`: заливает, проверяет `nginx -t`, при провале сам возвращает прежний файл; прежний хранится рядом — `/etc/nginx/sites-available/zz-ais.westmed.kz.prev`.
  ⚠️ **Префикс `zz-` обязателен:** `default_server` на сервере не задан, и неизвестные запросы (голый IP, TLS без SNI) получает первый загруженный по алфавиту конфиг — файл `ais.…` сделал бы АИС ответом на любой запрос к IP.
  ⚠️ Битый конфиг в `sites-enabled` сорвал бы следующий reload из хука certbot, то есть продление сертификатов всех трёх сайтов, — поэтому только через `apply.sh`.
- **Калитка:** перед каждым запросом nginx спрашивает АИС (`auth_request` → `/api/gate/check`). Недопущенным: страница → `302 /gate/`, API → `401` + `X-AIS-Gate: device`. Устройства допускает и отзывает админ: АИС → Система → Устройства.
- **«АИС выдаёт 500»** — почти всегда калитка: nginx хоста не получил от АИС 204/401 на проверку и отвечает 500. Смотреть `tail /var/log/nginx/error.log` (`auth request unexpected status: 502`), затем лог фронта `cd /srv/ais && docker compose logs --since 10m ais-frontend | grep upstream` — какой адрес бэкенда он зовёт — и `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8090/api/gate/check` (401 — норма). 2026-10-14: nginx фронта держал прежний IP пересозданного бэкенда, исправлено (`resolver` в `frontend/nginx.conf`); на старом образе фронта лечит `docker compose exec ais-frontend nginx -s reload`.
- **Первое устройство** (или все потеряны): `ssh -N -L 8090:127.0.0.1:8090 root@185.125.46.26` → `http://localhost:8090` → вход админом → «Устройства» → «Допустить» запрос с кодом, который показывает калитка.
- **Вход по ключу (passkeys, 2026-09-28):** Face ID / Touch ID вместо пароля АИС — только на `https://ais.westmed.kz`: ключ привязан к домену (`passkeys.rp-id` в `application-prod.yaml`, `.env` не нужен). На Tailscale и туннеле — вход паролем. Добавить ключ: войти паролем → «Мой профиль» → «Добавить вход по Face ID / Touch ID». Потерян телефон: «Мой профиль» с другого устройства → удалить его ключ, плюс «Устройства» → отозвать телефон. Сессия — 12 ч; деплой сбрасывает сессии (одно касание Face ID). Встроенное удаление ключа Spring наружу не выходит: `frontend/nginx.conf` пропускает к бэкенду только 4 пути passkeys и только POST. Откат — revert мерж-коммита и push: вход паролем не затронут, таблицы V20 остаются пустым грузом, ключи на устройствах удалить в настройках паролей. Личная учётка оператора — `vlad` (ADMIN), пароль — `~/.config/ais/ais-vlad.pass` на Mac оператора (600; в буфер — `pbcopy < ~/.config/ais/ais-vlad.pass`, через общий буфер Apple доступен и на iPhone); общий `admin` — запасной вход по паролю. Посмотреть ключи прода: `ssh root@185.125.46.26 'docker exec ais-ais-postgres-1 sh -c "psql -U \"\$POSTGRES_USER\" -d nirdb -c \"select e.name, c.label, c.created, c.last_used from user_credentials c join user_entities e on e.id = c.user_entity_user_id\""'`.
- **Раскатка калитки поверх шага 1** (порядок — спека §13):
  1. деплой кода (push `main` делает оператор) — nginx ещё с общим паролем, калитка лежит без дела;
  2. запасная вкладка через туннель (см. «Первое устройство»);
  3. переходный конфиг — пароль nginx только на трёх локациях калитки:
     `perl -pe 's{(# калитка)$}{$1\n        auth_basic "AIS"; auth_basic_user_file /etc/nginx/.htpasswd-ais;}' deploy/nginx/zz-ais.westmed.kz.conf > /tmp/zz-ais.transition.conf && deploy/nginx/apply.sh /tmp/zz-ais.transition.conf`
     → с Mac и с телефона: пароль → калитка → запрос → допуск из запасной вкладки → вход в АИС;
  4. итоговый конфиг: `deploy/nginx/apply.sh`, затем `ssh root@185.125.46.26 'rm -f /etc/nginx/.htpasswd-ais'`.
- **Откат** на прежний конфиг: `ssh root@185.125.46.26 'cd /etc/nginx/sites-available && cp -p zz-ais.westmed.kz.prev zz-ais.westmed.kz && nginx -t && systemctl reload nginx'`. Вернуть общий пароль после шага 4 — заново создать `/etc/nginx/.htpasswd-ais` (команда ниже) и поставить конфиг шага 1 из git: `git show 83ffbd0:deploy/nginx/zz-ais.westmed.kz.conf > /tmp/step1.conf && deploy/nginx/apply.sh /tmp/step1.conf`.
- **Сертификат:** Let's Encrypt через webroot `/var/www/certbot`, как у соседних сайтов; продлевает `certbot.timer`. Хук `/etc/letsencrypt/renewal-hooks/deploy/reload-nginx.sh` перечитывает сертификаты после продления (до 2026-09-27 его не было — продлённый сертификат nginx не видел до перезапуска, у всех трёх сайтов).
- **Пароль nginx** (нужен только в переходный период шага 3): пользователь `westmed`, хеш в `/etc/nginx/.htpasswd-ais` (`640 root:www-data`); задать —
  `ssh root@185.125.46.26 'H=$(openssl passwd -6 -stdin); printf "westmed:%s\n" "$H" > /etc/nginx/.htpasswd-ais; chown root:www-data /etc/nginx/.htpasswd-ais; chmod 640 /etc/nginx/.htpasswd-ais' < ~/.config/ais/ais-gate.pass`.
- **Пароли** — на Mac оператора в `~/.config/ais/` (права 600): `ais-admin.pass`, `ais-operator.pass` — логины АИС; `ais-gate.pass` — пароль nginx переходного периода. В буфер, не показывая на экране: `pbcopy < ~/.config/ais/ais-admin.pass`.
- **Ограничения:** 10 запросов/с на IP (запас 100), вход в АИС — 10 попыток в минуту на IP, запросы доступа на калитке — 3 в минуту на IP; `robots.txt` запрещает индексацию, плюс `X-Robots-Tag: noindex`; cookie сессии получает `Secure; SameSite=Lax` на уровне nginx (nginx 1.18 не знает `proxy_cookie_flags` — через `proxy_cookie_path`).
- **Проверка после любой правки конфига** — curl-набор из плана калитки (`docs/superpowers/plans/2026-09-28-device-gate.md`, Task 7) на стенде `nginx:1.18`, прежде чем ставить на сервер.

---

## 7. Чаты WhatsApp (свой шлюз WAHA; Green-API — запасной)
Зеркало переписки рабочего номера West-Med: спеки `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md` (модель чатов, правила обращений) и `docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md` (шлюз); механика — CLAUDE.md §8. WAHA — контейнер `ais-waha` в том же compose, только во внутренней сети: вебхук идёт `ais-waha → ais-backend:8080` мимо nginx, **наружу ничего не открывается** (в nginx фронта путь вебхука закрыт `404`, калитка не меняется). Код приезжает обычным деплоем (V22); WAHA запускается только профилем `whatsapp`.
- ✔ **Включено 2026-10-14:** ключи WAHA созданы на сервере (`openssl rand`, в чат не попадали), `COMPOSE_PROFILES=whatsapp`, копия `.env` до правки — `.env.bak-2026-10-14-1503-whatsapp`; привязан номер оператора (…4418); живая проверка (задача 13) пройдена там же — CLAUDE.md §8 «Живая проверка на проде». Условие ниже выполнено: `WahaLiveFixturesTest` зелёный, размер есть у документа, фото и видео в вебхуке и у фото в догонке.
- ⚠️ **Условие включения — задача 13 плана WAHA выполнена** (живая проверка с тестовым телефоном, фикстуры живых событий): защита «файл больше `WHATSAPP_MAX_FILE_MB` не качать» опирается на размер файла из события (`_data.Message.<вид>.fileLength`), а форма событий GOWS с живыми ещё не сверена. Если размер лежит не там, АИС попросит WAHA скачать файл целиком в память — видео до 2 ГБ при потолке 700 МБ убьёт контейнер, а с ним повторы вебхуков, которые WAHA держит в памяти. Проверка: `WahaLiveFixturesTest` зелёный (размер есть у документа, фото и видео — и в вебхуке, и в сообщении из догонки).
- ⚠️ **До адресов WhatsApp из DNS провайдера с сервера нет соединения** (2026-10-14: `web.whatsapp.com` / `mmg.whatsapp.net` → 31.13.72.52, `g.whatsapp.net` → 31.13.72.48 — 0 из 6; адреса из DNS Cloudflare 57.144.x / 157.240.x — 6 из 6). Поэтому у `ais-waha` в compose `dns: [1.1.1.1, 1.0.0.1]`. **Серверы файлов DNS не спасает:** их имя выдаёт сам WhatsApp (нашей сессии — стокгольмский `media-arn2-1.cdn.whatsapp.net` → 31.13.72.52 у всех DNS; `media-hel3-1` тоже недоступен), фото и PDF приходили за 4 и 7 минут, очередь стояла. Узлы Франкфурта отдают те же файлы (ссылка `media-arn2-1` через `curl --resolve …:57.144.223.32` → 200, размер совпал) — у `ais-waha` `extra_hosts` на 57.144.223.32 и 57.144.249.32. **`web.whatsapp.com` закреплён тоже** (2026-10-15): DNS Cloudflare порой отдаёт и для него недоступные 31.13.72.52 / 157.240.205.60 — переподключение сессии ждало по минуте (`dial tcp …:443: i/o timeout` в логе WAHA). Устойчиво отвечает только сеть 57.144.x (157.240.0.60 Франкфурта — уже 2 из 5); проверка узла — `curl --resolve web.whatsapp.com:443:<адрес> https://web.whatsapp.com/ws/chat` → 400 с верным сертификатом. Признак, что пора перепроверить: в чате «файл не скачался», в логе WAHA `Failed to download media … media-…cdn.whatsapp.net` — взять из лога хост, `dig +short <хост>` и `nc -z -w 3 <адрес> 443`; нужен новый хост или адрес — строка в `extra_hosts`. Трассы — `mtr -T -P 443 -n -r <адрес>` (`traceroute` на сервере нет). Образ WAHA — 3,2 ГБ на диске.
- **Перед включением:** `free -h` и `docker stats --no-stream` (WAHA ≈200 МБ, потолок 700 МБ); `df -h` — файлы чатов хранятся в БД (до `WHATSAPP_MAX_FILE_MB`, по умолчанию 25 МБ на файл); `uname -m` — должно быть `x86_64`: тег `gows-2026.9.1` собран только под amd64 (у того же релиза есть `gows-arm-2026.9.1` для arm64 — на нём WAHA проверялась локально на Mac).
- **Включение** (агент — только после явного «да» оператора; копия `.env` до правки): в `/srv/ais/.env` — `WHATSAPP_ENABLED=true`, `WHATSAPP_PROVIDER=waha`, `WHATSAPP_WAHA_API_KEY` и `WHATSAPP_WAHA_HMAC_KEY` (каждый — `openssl rand -hex 32` прямо на сервере, значения в чат не печатать), `COMPOSE_PROFILES=whatsapp` → `cd /srv/ais && docker compose up -d` (поднимет `ais-waha` и пересоздаст бэкенд с новым `.env`; `restart` новый `.env` не перечитывает).
- **Привязка:** АИС сама создаёт сессию `westmed`; оператор → «Система → WhatsApp» → QR **рабочим** телефоном (WhatsApp → Настройки → Связанные устройства → Привязка устройства). Код живёт ~2,5 мин (первый 60 с, дальше по 20 с), потом сессия «упала» — «Перезапустить» даёт новый.
- В логе WAHA при старте печатается «Generated credentials» для панели и Swagger — обе выключены (`WAHA_DASHBOARD_ENABLED=false`, `WHATSAPP_SWAGGER_ENABLED=false`), эти пароли ни к чему не пускают.
- **Приёмочный тест** (главное — issue #2241 WAHA: свои сообщения в чужом чате): с личного телефона — на рабочий; с рабочего — ответы 2–3 разным людям, в т.ч. не из контактов, → легли в правильные чаты; фото, PDF, Excel; **видео больше 25 МБ** → в чате «файл не сохранён: больше 25 МБ» И это сработала именно проверка размера, а не обрыв потока: в логе бэкенда строка «файл больше предела (N МБ при пределе 25 МБ) — не скачиваем» (`docker compose logs --since 10m ais-backend | grep 'больше предела'`), в логе WAHA запрос скачивания не появился (`docker compose logs --since 10m ais-waha | grep -c 'downloadMedia=true'` — не вырос), у `ais-waha` в `docker stats --no-stream` нет всплеска памяти. «Не сохранён» без строки в логе — защиты нет: WAHA скачала файл целиком, а АИС лишь обрезала поток; правка, удаление; звонок → строка «📞 Входящий звонок — принят»; **push на рабочем телефоне по-прежнему приходят** (`WAHA_PRESENCE_AUTO_ONLINE=False`). То же — после каждого обновления образа.
- ⚠️ **Автоответы WhatsApp Business** на рабочем номере до приёмочного теста не включать: если WAHA пришлёт их как отправленные с телефона, каждое новое обращение сразу уйдёт «В работу».
- **Красная строка на «Чатах» — что делать:** «номер не подключён» — привязать в «Система → WhatsApp»; «сессия упала» / «сессия остановлена» — там же «Перезапустить»; «сессия упала» СРАЗУ после перезапуска, а в логе WAHA `failed to dial whatsapp web websocket … no such host` — контейнер не достаёт до WhatsApp: `docker compose exec ais-waha getent hosts web.whatsapp.com` (пусто — DNS не отдаёт адрес, так было в локальной сети разработки; адрес есть — с сервера `nc -z -w 3 <адрес> 443 && echo ok`: нет соединения — DNS отдаёт недостижимый адрес, так у провайдера с 31.13.72.x, поэтому у сервиса `dns: [1.1.1.1, 1.0.0.1]`; если и Cloudflare начнёт отдавать недостижимые — подобрать DNS, чей ответ проходит `nc`); «нужно подтверждение на рабочем телефоне» — подтвердить в WhatsApp на телефоне; «WHATSAPP_PROVIDER: неизвестный провайдер» — опечатка в `.env` (`waha` или `greenapi`); «WAHA недоступен … принятые сообщения разбираются, но файлы к ним могут не скачаться» — `docker compose ps ais-waha`, `docker compose logs --since 10m ais-waha` (файл получает три попытки ≈1 мин, дальше сообщение пишется без него — файл останется в телефоне); «WAHA присылает события с чужой подписью» (держится сутки или до первого события с верной подписью) — `WHATSAPP_WAHA_HMAC_KEY` в `.env` разошёлся с запущенной WAHA → `docker compose up -d` (пересоздаст обе; до этого правки, удаления и звонки не доходят, сообщения подбирает только догонка); «WAHA прислала событие больше 4 МБ» — `docker compose logs ais-backend | grep 'тело больше'`; если это настоящее событие WAHA, а не мусор из сети docker, — поднять `WahaWebhookController.MAX_BODY_BYTES` (код) и задеплоить; «не заданы ключи шлюза WAHA» — ключи в `.env`; «WAHA отклонил ключ API» — ключ в `.env` разошёлся с запущенным контейнером → `docker compose up -d`; «база данных недоступна» — чинить БД: WAHA повторяет вебхуки до ~4,5 ч (в своей памяти; её рестарт их теряет — пропущенное подберёт догонка); «приём остановлен: за сутки не записались 3 сообщения» — поломка кода, `docker compose logs ais-backend`; «не удалось догнать сообщения…» — повтор сам через 10 мин.
- **Очередь:** `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "select status, count(*) from whatsapp_inbox group by 1"'` (через `sh -c`, как бэкап в §4: `$POSTGRES_USER` есть только внутри контейнера) — `PENDING` не должен копиться, у `DROPPED` причина в `last_error`.
- **Откат:** `WHATSAPP_ENABLED=false` → `docker compose up -d`; устройство WAHA удалить в телефоне («Связанные устройства»). Убрать WAHA совсем — ещё строку `COMPOSE_PROFILES=whatsapp` из `.env` и `docker compose stop ais-waha`. История остаётся и читается на «Чатах».
- **Обновление WAHA** — только вручную: журнал изменений → новый тег в `docker-compose.yml` → деплой → приёмочный тест.
- ⚠️ Том `ais-waha-sessions` = доступ к рабочему WhatsApp: не выкладывать, в бэкапы — только осознанно.
- **Запасной провайдер Green-API:** `WHATSAPP_PROVIDER=greenapi` + `WHATSAPP_API_URL` / `WHATSAPP_ID_INSTANCE` / `WHATSAPP_API_TOKEN` (инстанс Business, адрес вебхука в кабинете — ПУСТОЙ; один инстанс — один потребитель очереди) → `docker compose up -d`; затем `docker compose stop ais-waha` и отвязать устройство WAHA в телефоне. Пока провайдер не `waha`, события WAHA АИС принимает, но в очередь не пишет — иначе при возврате на WAHA проиграла бы давние звонки и правки. Дублей при переключении нет: id сообщения WhatsApp у обоих шлюзов один.
- **Номер на сайте westmed.kz** (отдельный репозиторий `~/IdeaProjects/westmed`, отдельный деплой): три места — `frontend/src/components/shared/WhatsAppButton.tsx` (`PHONE`), `frontend/src/components/shared/Footer.tsx`, `frontend/src/app/[locale]/(storefront)/contacts/page.tsx`.

---

## 8. Почта zakup@ и Telegram
Каждое письмо во «Входящих» zakup@westmed.kz — уведомлением в тему «Почта zakup@» группы «Заявки» (бот сайта @West_Med_bot): ответ поставщика на запрос КП — с тендером, поставщиком и распознанной ценой или отказом; возврат «не доставлено», «доставка задерживается», автоответ, прочее. Спека — `docs/superpowers/specs/2026-10-05-zakup-mail-telegram-design.md` (§10 — раскатка, в конце — «Решения при реализации»), механика — CLAUDE.md §8 «Почта zakup@ → Telegram» и §9. Код приезжает обычным деплоем (V24: курсор ящика `mail_cursor` и очередь уведомлений в строках `inbound_email`); пока в `.env` нет ключей ниже, поведение прода не меняется — приём (`MAIL_IMAP_ENABLED`) и Telegram (`TELEGRAM_ENABLED`) выключены. Ящик АИС только читает (IMAP EXAMINE + `BODY.PEEK`): отметки «прочитано» у людей не трогает.
**Статус: включено 2026-10-06** — приём с 08:11 UTC, тема 52 (копия `.env` до правки — `.env.bak-2026-10-06-0811`; шаг 2 пропущен — приём на пароле отправки КП, вход по IMAP проверен), Bot API закреплён за рабочим адресом (пункт «Маршрут до `api.telegram.org`» ниже), приёмка пройдена 08:37 UTC. Токен бота перевыпущен 2026-10-07 (прежний лежал в тесте сайта и печатался в логе medsite1) — пункт «Токен бота общий…» ниже.
- **Копии писем в «Отправленные» (с 2026-10-14, `SentCopy`):** каждое письмо, которое АИС отправила по SMTP (запросы цен поставщикам, «↻ Переслать»), кладётся копией в «Отправленные» zakup@ — Mail.ru сам кладёт туда только письма своей веб-почты. Работает без новых ключей: `MAIL_USERNAME` = `MAIL_IMAP_USERNAME` = zakup@westmed.kz и задан `MAIL_IMAP_PASSWORD` (сверено 2026-10-14). Проверка после деплоя: `docker compose logs ais-backend | grep 'Копии писем АИС'` → «Копии писем АИС — в «Отправленные» zakup@westmed.kz»; запрос цен письмом → письмо в «Отправленных» веб-почты. Сбой копии письмо не трогает (в логе — `Копия письма в «Отправленные» не сохранена: <класс>`). Выключить — `MAIL_SENT_COPY_ENABLED=false` → `docker compose up -d` (пустым ключ не оставлять — старт упадёт).
- **Файлы ответов поставщиков (V30, с ветки `feature/supplier-reply-files`):** приём zakup@ читает вложения писем и хранит файлы ответов на запросы цен; Excel и Word разбираются, цены встают с 💡 (CLAUDE.md §8 «Ответ поставщика»). Новых ключей не нужно; предел файла — `MAIL_IMAP_MAX_FILE_MB` (по умолчанию 15, не больше 25; пусто или не число — 15). В `.env` его не вписывать, если умолчание подходит. Предел загрузки файла на экране «Ответ поставщика» и в остальных загрузках — 15 МБ (`spring.servlet.multipart`, было 10) = `client_max_body_size 15m` фронта. Проверка после деплоя: ответ с личной почты на тестовый запрос цен с Excel во вложении → в теме «Почта zakup@» строка «💡 Цены из файла «…»: подставлено N из M — сверьте», ссылка ведёт на экран «Ответ поставщика». ⚠️ **Откат кода раньше V30 — сначала SQL:** V30 положила в словарь заголовков (`header_synonym.field`) роли, которых прежний бэкенд не знает (`@Enumerated(STRING)` → `No enum constant` на чтении словаря) — после отката встал бы любой разбор Excel клиники (импорт частной заявки, «Импортировать» во «Входящих», «Разобрать в позиции» в чатах и обращениях). Перед откатом: `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "delete from header_synonym where field in ('"'"'NUMBER'"'"','"'"'ARTICLE'"'"','"'"'UNIT'"'"','"'"'PRICE'"'"','"'"'SUM'"'"','"'"'VAT'"'"','"'"'CURRENCY'"'"','"'"'NOTE'"'"')"'` (при возврате на новую версию роли КП поставщика снова определятся по заголовкам, выученное — заново). Остальное V30 прежнему бэкенду не мешает: новая таблица и колонки ему не видны; пометка «💡 …» перенесена миграцией из текста примечания в признак `response_auto` — прежний бэкенд её не покажет (цены и примечания на месте). Flyway прежней версии пропускает применённую V30 (будущие миграции по умолчанию игнорируются).
- **Курс для файла ответа в чужой валюте (V31, с ветки `feature/supplier-reply-rate`, 2026-10-16):** у файла ответа поставщика — курс пересчёта в валюту запроса (`supplier_reply_file.rate`, `rate_currency`, `rate_set_at`), вписывается на экране «Ответ поставщика» (CLAUDE.md §8 «Ответ поставщика»). Новых ключей не нужно. Проверка после деплоя: ответ на тестовый запрос цен с Excel в рублях («Цена, руб.») → в Telegram «Цены в файле «…» — в руб., не подставлены — впишите курс на экране сверки» → на экране «1 руб. = [6] тг → Пересчитать» → цены с 💡 в тенге, тост «По курсу 6 подставили N цен — сверьте»; второе письмо с исправленным КП в рублях к тому же запросу — в Telegram «подставлено N из M по курсу 6». Откат кода раньше V31 — без SQL: новые колонки прежний бэкенд не читает (`ddl-auto: none`), пересчитанные цены остаются ценами ответа.
- ⚠️ **Откат кода раньше `fix/supplier-reply-examples` (2026-10-18; разбор КП: валюта из текста вне таблицы) — сначала SQL:** у файлов ответа с «валютой не ясна» (в тексте документа разные валюты или суммы в тысячах) в разобранной таблице псевдокод `MIXED` (`supplier_reply_file.parsed->>'currency'`). Прежний код покажет «Цены в файле — в MIXED» с полем курса и примет курс — цены пересчитаются из неизвестной валюты. Найти такие файлы: `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "select id, price_request_id, file_name from supplier_reply_file where parsed->>'"'"'currency'"'"' = '"'"'MIXED'"'"'"'`; перед откатом — выбрать им валюту на экране сверки («Укажите ▾» над таблицей файла) или убрать валюту: `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "update supplier_reply_file set parsed = jsonb_set(parsed, '"'"'{currency}'"'"', '"'"'null'"'"'::jsonb) where parsed->>'"'"'currency'"'"' = '"'"'MIXED'"'"'"'` — прежний код будет считать такие файлы в валюте запроса, как до этой ветки (цены по ним сам не подставляет — только «Взять цены из этого файла» и смена ролей колонок). Остальное прежнему коду не мешает: валюта, найденная в тексте, — обычный код (RUB, KZT…), новые поля разобранной таблицы (`currencyFrom` — откуда валюта, кто её выбрал) он пропускает.
- ⚠️ **Откат кода раньше 2026-10-14 — сначала SQL:** запросы «Передам сам» хранятся с `letter_status = 'MANUAL'`, а прежний бэкенд такого значения не знает (`@Enumerated(STRING)` → 500 в таблице цен заявки, «Создать КП клиенту», запросах КП тендера). Перед откатом: `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "update price_request set letter_status = null where letter_status = '"'"'MANUAL'"'"'"'` — ячейка останется «ждём ответа».
- ⚠️ **Откат кода раньше V33 (готовое КП, ветка `feature/ready-offer-file`, 2026-10-19) — сначала SQL:** КП из файла (`client_offer.origin = 'FILE'`) прежний бэкенд показал бы как КП конструктора: без номера («КП № null»; каждое автосохранение — 400 «номер обязателен»), PDF и Word собрал бы из умолчаний «Реквизитов» с ценами файла, отправленное не дал бы удалить. Перед откатом (сперва события смены статуса этих КП — иначе в ленте останутся строки со ссылкой на удалённое КП; файлы КП уходят каскадом, заявки и позиции остаются): `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "delete from deal_event where code = '"'"'OFFER_STATUS'"'"' and ref_id in (select id from client_offer where origin = '"'"'FILE'"'"'); delete from client_offer where origin = '"'"'FILE'"'"'"'`. Остальное V33 прежнему коду не мешает: у КП конструктора номер есть всегда, таблица `client_offer_file` и колонки `origin` / `offer_hint_dismissed` ему не видны, Flyway пропускает «будущую» миграцию. Проверка после деплоя: в заявке с перепиской на info@ ответить клиенту с КП во вложении из веб-почты → в «Дальше» заявки «Похоже, КП ушло клиенту ДД.ММ письмом — «…»» → «Занести КП» → редактор: «из файла» и строка сверки с «ИТОГО» файла.
- **Исполнение сделки (V34, ветка `feature/deal-execution`, 2026-10-19):** раздел «Исполнение сделки» на странице частной заявки после «Клиент согласился» — договор или счёт, оплата клиента, поставщики (заказ, платежи в их валюте, отгрузка), поставка, закрывающие документы, файлы к каждому месту (CLAUDE.md §8 «Исполнение сделки»). Новых ключей не нужно. Проверка после деплоя: заявка с принятым КП → в строке этапов десять шагов, «Дальше: Клиент согласился на КП № N — договор или счёт», в списке заявок чип «Договор»; вписать № договора → индикатор «Сохранено», «Дальше» — предоплата. Откат кода раньше V34 — без SQL: прежний бэкенд таблиц исполнения не читает (`ddl-auto: none`), данные остаются и вернутся с новой версией; Flyway пропускает «будущую» миграцию. Поставщика, который есть в исполнении какой-нибудь сделки (`deal_supplier`), не удалить и новым, и прежним кодом — «Операция невозможна: данные используются…» (внешний ключ), как и поставщика с запросами цен.
- **Включение** (агент — только после явного «да» оператора на SSH; секреты на экран и в чат не выводить):
  1. **Оператор:** в группе «Заявки» — новая тема «Почта zakup@» → ссылка на неё (`https://t.me/c/4352219740/<id>`): `<id>` — это `TELEGRAM_MAIL_THREAD_ID`, только цифры (не число — каждая отправка падает «TELEGRAM_MAIL_THREAD_ID должен быть числом»; без темы сообщения уходили бы в «Общую»). **Сделано 2026-10-06:** тема «Почта zakup@» — `https://t.me/c/4352219740/52`, то есть `TELEGRAM_MAIL_THREAD_ID=52`.
  2. **Оператор, рекомендуется:** новый пароль приложения zakup@ (Mail.ru → Безопасность → «Пароли для внешних приложений», доступ IMAP + SMTP; прежний светился в переписке) → `~/.config/ais/zakup-mailru.pass` на Mac (600), в чат не вставлять. Он идёт в ОБА ключа: `MAIL_IMAP_PASSWORD` (приём) и `MAIL_PASSWORD` (отправка КП). Старый пароль в Mail.ru удалять только после шага 5 — иначе отправка КП встанет. Без нового пароля приём берёт пароль отправки КП — шаг 4, «Шаг 2 пропущен».
  3. **Оператор:** `! git push origin main` — код с ВЫКЛЮЧЕННЫМ приёмом (V24 накатывается). Включение — отдельным шагом: старый код ни разу не должен открыть zakup@ (он помечал письма прочитанными), а откат остаётся чисто настройкой. Агент проверяет, что бэкенд поднялся.
  4. **`.env`** — на сервере, `cd /srv/ais`: копия, затем ключи (`set_env` меняет строку или дописывает новую; первая строка заодно добавляет перевод строки в конец файла, если его нет — иначе дописанное приклеилось бы к последней строке):
     ```bash
     cp -p .env .env.bak-$(date +%F) && umask 077 && { [ -z "$(tail -c1 .env)" ] || echo >> .env; }
     set_env() { if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi; }
     set_env MAIL_IMAP_ENABLED true; set_env MAIL_IMAP_HOST imap.mail.ru; set_env MAIL_IMAP_PORT 993; set_env MAIL_IMAP_PROTOCOL imaps
     set_env MAIL_IMAP_USERNAME zakup@westmed.kz; set_env MAIL_IMAP_MARKET KZ; set_env MAIL_IMAP_SINCE_MINUTES 60
     set_env MAIL_IMAP_POLL_MS 60000; set_env MAIL_IMAP_CLIENT_REQUESTS false
     set_env TELEGRAM_ENABLED true; set_env TELEGRAM_CHAT_ID -1004352219740
     set_env TELEGRAM_MAIL_THREAD_ID 52    # тема «Почта zakup@» (шаг 1)
     ```
     Токен бота — из настроек сайта (`app_settings.telegram_bot_token` в БД контейнера `westmed-postgres`) прямо в `.env`, значение на экран не попадает:
     ```bash
     cd /srv/ais && umask 077 && echo "select trim(value) from app_settings where key = 'telegram_bot_token'" \
       | docker exec -i westmed-postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At' \
       | { T=$(cat); if [ -n "$T" ]; then grep -v '^TELEGRAM_BOT_TOKEN=' .env > .env.new; printf 'TELEGRAM_BOT_TOKEN=%s\n' "$T" >> .env.new; cat .env.new > .env; rm -f .env.new; else echo 'токена в БД сайта нет — .env не тронут'; fi; }
     ```
     Новый пароль приложения (шаг 2) — с Mac, тем же приёмом, в оба ключа:
     ```bash
     ssh root@185.125.46.26 'cd /srv/ais && umask 077 && V=$(cat) && if [ -n "$V" ]; then for k in MAIL_IMAP_PASSWORD MAIL_PASSWORD; do grep -v "^$k=" .env > .env.new; printf "%s=%s\n" "$k" "$V" >> .env.new; cat .env.new > .env; done; rm -f .env.new; else echo "файл пароля пуст — .env не тронут"; fi' < ~/.config/ais/zakup-mailru.pass
     ```
     **Шаг 2 пропущен** — приёму нужен тот же пароль, что у отправки КП: приём на проде не включался ни разу, и в `MAIL_IMAP_PASSWORD` может стоять заглушка из `.env.example`. Скопировать без вывода на экран (`sed` срезает комментарий после значения, если строка пришла из `.env.example`):
     ```bash
     cd /srv/ais && umask 077 && V=$(grep '^MAIL_PASSWORD=' .env | tail -1 | cut -d= -f2- | sed -E 's/[[:space:]]+#.*$//') \
       && if [ -n "$V" ]; then grep -v '^MAIL_IMAP_PASSWORD=' .env > .env.new; printf 'MAIL_IMAP_PASSWORD=%s\n' "$V" >> .env.new; cat .env.new > .env; rm -f .env.new; else echo 'MAIL_PASSWORD пуст — .env не тронут'; fi; unset V
     ```
     Пароль приложения, выданный без доступа по IMAP, приём не пустит (тост «Ошибка подключения к почте: … AUTHENTICATIONFAILED …») — тогда всё-таки шаг 2.
     **Проверка без секретов:** каждый ключ ровно один раз, секреты — звёздочками, `TELEGRAM_API_URL` нет (пустое значение умолчанием не заменяется — ни одно уведомление не уйдёт); пароли и токен заданы — не пусто и не заглушка `CHANGE_ME…` (звёздочки первой команды заглушку прячут); приём и отправка — один пароль:
     ```bash
     cd /srv/ais && grep -E '^(MAIL_IMAP_|MAIL_PASSWORD=|TELEGRAM_)' .env | sed -E 's/^((MAIL_IMAP_PASSWORD|MAIL_PASSWORD|TELEGRAM_BOT_TOKEN)=).+/\1***/'
     val() { grep "^$1=" .env | tail -1 | cut -d= -f2- | sed -E 's/[[:space:]]+#.*$//'; }
     for k in MAIL_IMAP_PASSWORD MAIL_PASSWORD TELEGRAM_BOT_TOKEN; do case "$(val $k)" in ''|CHANGE_ME*) echo "$k: НЕ задан";; *) echo "$k: задан";; esac; done
     [ -n "$(val MAIL_PASSWORD)" ] && [ "$(val MAIL_IMAP_PASSWORD)" = "$(val MAIL_PASSWORD)" ] && echo 'приём и отправка — один пароль' || echo 'пароли приёма и отправки РАЗНЫЕ или пусты — проверить'
     ```
  5. `docker compose up -d --force-recreate ais-backend` — `.env` перечитывается только при пересоздании контейнера (`restart` его не видит); сессии сбрасываются (одно касание Face ID). Через минуту `docker compose logs --since 5m ais-backend | grep -E 'не настроен|Ошибка приёма почты|не отправлено'` — пусто (иначе: «Telegram включён … но не настроен» — нет токена или чата; остальное — раздел «Тост» ниже).

  Первый проход (через 20 с после старта) — «первый запуск»: письма за последние 60 минут (`MAIL_IMAP_SINCE_MINUTES`) придут в тему, более старые — нет; курсор встаёт на последнее письмо ящика, дальше — всё новое по порядку, в том числе пришедшее, пока бэкенд лежал.
- **Приёмка:**
  - письмо с личного адреса на zakup@ → за ~1 мин в теме «Почта zakup@» сообщение «✉️ Письмо на zakup@westmed.kz · …» (без звука);
  - в веб-почте Mail.ru письмо осталось непрочитанным;
  - во «Входящих» АИС (рынок KZ) оно есть — «Прочее»; «Проверить почту» — зелёный тост («Новых писем: 0» или сводка с «Telegram: отправлено N, ждут M»);
  - ссылка «Открыть в АИС» из Telegram: встроенный браузер Telegram — новое устройство для калитки (как иконка АИС на экране «Домой» iPhone — CLAUDE.md §8 «Калитка по коду устройства») → запрос → «Допустить» в «Система → Устройства» → после допуска открыть ссылку из Telegram ещё раз (первый переход через калитку её теряет) → вход → «Входящие» на KZ;
  - по желанию — запрос КП из АИС поставщику с личным адресом оператора и ответ на него с ценой («Цена 100 000 тг») → «📩 Ответ поставщика …» со звуком, «💡 Цена распознана: … — проверьте», запрос — «Ответ получен»;
  - по желанию — то, что локально не проверить (настоящий возврат Mail.ru: формат его отчёта о доставке и тема вложенного письма по его IMAP; **сама эта проверка ещё не опробована**): из веб-почты zakup@ — письмо на заведомо несуществующий ящик ВНЕШНЕГО домена (например, `ais-bounce-test-<случайные цифры>@gmail.com`; несуществующий адрес своего домена Mail.ru может отклонить ещё при отправке, без отчёта о доставке) с темой «[КП-<id существующего запроса>] проверка возврата» → «⚠️ Письмо не доставлено · <поставщик запроса> (<адрес>)» с причиной от сервера; статус запроса не меняется.
- **Очередь уведомлений** (`notify_status`: `PENDING` — ждёт, `SENT`, `FAILED` — не ушло за сутки; пусто — Telegram был выключен, своё письмо или строка до V24): `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "select notify_status, count(*) from inbound_email group by 1"'` (через `sh -c`, как бэкап в §4: `$POSTGRES_USER` есть только внутри контейнера). Последние ошибки и курсор ящика:
  ```bash
  docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb' <<'SQL'
  select id, notify_status, notify_attempts, notify_error from inbound_email where notify_status in ('PENDING','FAILED') order by id desc limit 10;
  select mailbox, uid_validity, last_uid, updated_at from mail_cursor;
  SQL
  ```
  **Предел отправок — не больше 10 сообщений за минуту и не чаще одного в 1,1 с:** бот и группа общие с сайтом, а сайт при отказе Telegram (в том числе 429 «слишком часто») своё уведомление о заявке выбрасывает без повтора — половина минутного лимита группы остаётся ему. Поэтому после простоя уведомления растягиваются — в среднем около 5 в минуту, а не 10: проход идёт раз в минуту, и к следующему проходу отправки прошлого ещё в окне 60 с, так что проходы чередуются «10 — почти ничего». 300 писем — около часа (`PENDING` убывает; в логе через проход INFO «предел 10 сообщений за 60 с выбран»). Первый же сбой Telegram останавливает пачку до следующего прохода; сбои подряд — пауза 1, 2, 4, 8, 16, 30 минут (дальше по 30; удачная отправка её обнуляет), на 429 — до `retry_after`; за сутки не ушло — `FAILED` + WARN (письмо остаётся во «Входящих» и в Mail.ru). Отдельного экрана очереди нет — состояние видно в тосте «Проверить почту» («ждут N — <последняя ошибка>»), в логе и здесь.
- **Тост «Проверить почту» — что делать.** Красный: «Ошибка подключения к почте: …» (например, `AUTHENTICATIONFAILED`) — пароль приложения или выключен доступ по IMAP в Mail.ru; «… — проход остановлен, повтор следующим проходом» с причиной «связь с почтой оборвалась» или «база данных недоступна (…)» — повтор сам; «письмо UID N не записалось (…)» — поломка кода на этом письме, ящик стоит на нём: `docker compose logs ais-backend | grep 'UID N'` (пропустить его — следующий пункт). Зелёный, но «Telegram: … ждут N — <ошибка>»: «HTTP 400 — Bad Request: message thread not found» — не тот `TELEGRAM_MAIL_THREAD_ID`; «Bad Request: chat not found» или «HTTP 403 — Forbidden: …» — не тот чат или бота убрали из группы; «HTTP 401 — Unauthorized» — токен перевыпущен на сайте → заново токен (шаг 4) и шаг 5; «адрес API или токен в настройках некорректны» — пустой `TELEGRAM_API_URL=` в `.env` или пробел внутри токена; «Telegram недоступен …» / «не ответил за 15 с» — сеть, уйдёт само (не уходит часами — маршрут до Telegram, пункт «Маршрут до `api.telegram.org`» ниже).
- **Пропустить застрявшее письмо** — ящик стоит на одном письме каждый проход: тост «письмо UID N не записалось (…)» или проход раз за разом обрывается на одном и том же письме (тост «связь с почтой оборвалась», в логе — `docker compose logs --since 30m ais-backend | grep 'оборвалась на письме UID'`, номер один и тот же). Курсор ставится на это письмо — следующий проход начнёт с UID N+1 (`N` — номер из тоста или лога):
  ```bash
  cd /srv/ais && docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb' <<'SQL'
  update mail_cursor set last_uid = N, updated_at = now() where mailbox = 'zakup@westmed.kz';
  SQL
  ```
  Письмо остаётся в Mail.ru — прочитать его там: в АИС и в Telegram его не будет. Бэкенд не останавливать: в пределах одного `uid_validity` курсор назад не ходит, проход, идущий в эту минуту, номер не откатит.
- **Откат настройкой:** `TELEGRAM_ENABLED=false` (приём работает, новые уведомления не ставятся; стоящие в очереди ждут — включили обратно в течение суток — уйдут) или `MAIL_IMAP_ENABLED=false` (как было: ни приёма, ни отправки очереди) → `docker compose up -d --force-recreate ais-backend`.
- ⚠️ **Повторное включение после долгой паузы** (приём был выключен днями): первые проходы обработают ВСЁ, что пришло после курсора, — сотни писем (по 100 за проход), сотни уведомлений (в среднем около 5 в минуту — предел отправок выше), а старые ответы поставщиков применятся к запросам КП (цены, «Ответ получен», отказы). Если догонка не нужна — ПЕРЕД включением удалить строку курсора: будет «первый запуск» с окном 60 минут (`MAIL_IMAP_SINCE_MINUTES`), а письма, уже записанные раньше, отсеет дедуп по Message-ID:
  ```bash
  cd /srv/ais && docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb' <<'SQL'
  delete from mail_cursor where mailbox = 'zakup@westmed.kz';
  SQL
  ```
- ⚠️ **Кодом «просто откатить» нельзя:** старый бэкенд не прочитает строки `inbound_email` с типами `BOUNCE` / `AUTO_REPLY` / `DELAYED` (`@Enumerated(STRING)` → «Входящие» отвечают 500), а новый, пока приём включён, пишет такие строки каждую минуту. Только в этом порядке:
  1. **Остановить приём настройкой:** `MAIL_IMAP_ENABLED=false` в `.env` **и** `docker compose up -d --force-recreate ais-backend` — без пересоздания работающий бэкенд `.env` не перечитает и продолжит приём (заодно старый код потом не откроет ящик — он помечал письма прочитанными).
  2. **Перевести новые виды в «Прочее»:**
     ```bash
     docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb' <<'SQL'
     update inbound_email set type = 'UNMATCHED' where type in ('BOUNCE','AUTO_REPLY','DELAYED');
     SQL
     ```
  3. **Откатить код** (§3). Есть сомнение, что между шагами что-то успело записаться, — повторить UPDATE шага 2 сразу после подъёма старого образа: старый код новых видов не пишет никогда, второй раз всё будет чисто.

  Колонки и таблица V24 старому коду не мешают: Flyway пропускает неизвестную ему «будущую» миграцию (как V20 при откате passkeys).
- ⚠️ **Токен бота общий для трёх систем — АИС, сайт westmed и medsite1 (vital-spb.kz):** Revoke в @BotFather сразу отключает старый токен у всех, и до замены уведомления не ходят (у АИС — 401). Заменять скриптом `deploy/rotate-telegram-bot-token.sh` (порядок — в его шапке): токен — в `~/.config/ais/westmed-bot.token` на Mac (при сохранении из буфера сперва вставить команду в строку ввода, потом скопировать токен — иначе в буфере окажется сама команда), скрипт получает его со стандартного входа, проверяет в Telegram (getMe — @West_Med_bot, getChat — группа «Заявки») и только потом пишет в `app_settings` сайта, `notification_setting` medsite (оба читают токен из базы при каждой отправке) и `.env` АИС (копия `.env.bak-…-token`, бэкенд пересоздаётся); везде сверяет отпечаток. Последний перевыпуск — 2026-10-07. Пароль zakup@ тоже в двух ключах — `MAIL_IMAP_PASSWORD` и `MAIL_PASSWORD`.
- ⚠️ **Маршрут до `api.telegram.org` (2026-10-06):** DNS отдаёт 149.154.166.110, а с сервера до него нет маршрута (трасса обрывается внутри сети провайдера, `curl https://api.telegram.org/` висит до таймаута; в тосте и логе — «Telegram недоступен при отправке сообщения: HttpConnectTimeoutException»). Бэкенду АИС адрес закреплён в `docker-compose.yml`: `extra_hosts: api.telegram.org:149.154.167.220` — второй адрес того же Bot API, сертификат тот же (проверяется по имени). **С 2026-10-07 тот же адрес закреплён у сайта westmed (`/opt/westmed/docker-compose.prod.yml`) и у medsite1 — сайта vital-spb.kz, который пересылает в «Заявки» письма info@westmed.kz и info@vital-spb.kz (`/root/medsite1/docker-compose.prod.yml` — серверная версия, она намеренно отличается от репозитория); до этого их уведомления не уходили с 2026-09-25.** Убирать закрепление — у всех троих. Проверка с сервера: `curl -sS -m 8 -o /dev/null -w '%{http_code}\n' https://api.telegram.org/` (302 — маршрут починили, закрепление убрать) и то же с `--resolve api.telegram.org:443:149.154.167.220` (302 — запасной адрес жив).
- ⚠️ **Группа «Заявки» общая с vital-spb.kz:** тема отдельная, но переписку с поставщиками видят все участники группы.
- ⚠️ **Первый переход по ссылке через калитку ссылку теряет** (после допуска — главная); допущенный и вошедший браузер открывает ссылку сразу и на нужном рынке (`?market=` в ссылке).
- ⚠️ **Письма, которые правила Mail.ru перекладывают из «Входящих»** в другие папки, АИС не видит: читается только INBOX (папку «Спам» тоже не читает).
- `MAIL_IMAP_MARKET` на ходу не менять: строки «Входящих», дедуп по Message-ID и очередь привязаны к рынку ящика — старые строки выпадут из дедупа, а `PENDING` прежнего рынка не уйдут никогда.

## 9. Автозапуск импорта тендеров и уведомления о новых тендерах в Telegram
Импорт сам стартует в рабочие часы по Уральску: goszakup — ежечасно в :10, 8:10–19:10, пн–сб (прогон на проде ≈ 1 мин); СК-Фармация — в 8:40, 12:40, 16:40, пн–сб (≈ 4 мин, ≈ 900 запросов к порталу; её тендеры живут 3–15 дней). О новых действующих профильных тендерах ЗКО — одно тихое (без звука) сообщение за прогон в тему «Тендеры» группы «Заявки» (тот же бот, что у почты zakup@, §8). Механика — CLAUDE.md §8 «Автозапуск импорта и уведомления о новых тендерах». **Выключено по умолчанию.**
**Статус: включено 2026-10-07 08:17 UTC** — push `09e6a1bb`, тема «Тендеры» — 55 (`https://t.me/c/4352219740/55`), копия `.env` до правки — `.env.bak-2026-10-07-0816`; лог старта — оба расписания и «регионы [западно-казахстанская область], тема 55».
- **Включение** (агент — только после явного «да» оператора на SSH):
  1. **Оператор:** в группе «Заявки» — новая тема «Тендеры» → ссылка на неё `https://t.me/c/4352219740/<id>`: `<id>` — это `TELEGRAM_TENDERS_THREAD_ID` (только цифры; пусто — сообщения уйдут в «Общую»).
  2. **Оператор:** `! git push origin main` — код уходит с выключенным автозапуском и уведомлениями.
  3. **`.env`** — на сервере, `cd /srv/ais`: копия и ключи тем же `set_env`, что в §8 (шаг 4):
     ```bash
     cp -p .env .env.bak-$(date +%F) && umask 077 && { [ -z "$(tail -c1 .env)" ] || echo >> .env; }
     set_env() { if grep -q "^$1=" .env; then sed -i "s|^$1=.*|$1=$2|" .env; else printf '%s=%s\n' "$1" "$2" >> .env; fi; }
     set_env TENDERS_AUTO_IMPORT_ENABLED true
     set_env TENDERS_NOTIFY_ENABLED true
     set_env TELEGRAM_TENDERS_THREAD_ID <id>    # тема «Тендеры» (шаг 1)
     ```
     Остальное не задавать — умолчания в коде, пустое значение тоже значит «по умолчанию»: регионы `TENDERS_NOTIFY_REGIONS` — «Западно-Казахстанская область» (через запятую; `*` — все регионы); расписания `TENDERS_AUTO_IMPORT_GOSZAKUP_CRON` / `TENDERS_AUTO_IMPORT_SK_CRON` — cron Spring из 6 полей, `-` выключает площадку; пояс `TENDERS_AUTO_IMPORT_ZONE` — `Asia/Oral`. Опечатка в расписании выключает автозапуск этой площадки (ERROR в логе), старт бэкенда не роняет; неразборчивый пояс — Уральск. `TELEGRAM_ENABLED` / `TELEGRAM_BOT_TOKEN` / `TELEGRAM_CHAT_ID` уже стоят с §8.
  4. `docker compose up -d --force-recreate ais-backend`.
  5. **Приёмка:** при старте в логе `Автоимпорт goszakup: «0 10 8-19 * * MON-SAT», пояс Asia/Oral`, то же для СК-Фармации, и `Уведомления о новых тендерах: регионы [западно-казахстанская область], тема <id>` (WARN «…Telegram не настроен…» вместо неё — проверить `TELEGRAM_*`): `docker compose logs ais-backend | grep -E "Автоимпорт|Уведомления о новых"`. В ближайшие :10 рабочего часа — `Автоимпорт тендеров: запуск goszakup`; если площадка опубликовала новые профильные тендеры ЗКО — сообщение в теме «Тендеры» и в логе `уведомление о новых тендерах отправлено (N)`. Новых нет — сообщений нет, это норма. Не ждать часа: «Обновить тендеры» на `/tenders` — уведомление уходит и после ручного прогона.
- **Что приходит:** только тендеры, СОЗДАННЫЕ прогоном (уже известные АИС — нет), со статусом ACTIVE, приём по которым ещё не закончился (с 2026-10-11 — по времени площадки: «до 10:25» в 14:10 уже не новый; время — и в тексте, «приём до 13.10.2026 18:00») и регионом из `TENDERS_NOTIFY_REGIONS`. У СК-Фармации регион — организатора («г. Астана», республиканская закупка), поэтому при фильтре ЗКО её тендеры не приходят — нужны и они: `TENDERS_NOTIFY_REGIONS=Западно-Казахстанская область,г. Астана`. Одно сообщение на прогон (помещается ≈ 8 тендеров, остальные — строкой «…и ещё N» со ссылкой на раздел).
- **Сбой Telegram или базы при сборке:** ошибка в итоге прогона («уведомление в Telegram о новых тендерах не ушло (повтор — со следующим прогоном): …» / «… не собрано …»; причину сбоя самого импорта не затирает) — в тосте и строке «Обновлено…» на `/tenders` (у СК-Фармации строки «Обновлено…» нет — тост и лог), WARN в логе; неушедшие тендеры добавляются к уведомлению следующего прогона, если ещё действуют (список — в памяти: рестарт бэкенда его теряет, тендеры при этом в АИС есть). Причины отказа Telegram — как у почты (§8, «Тост «Проверить почту»»): «message thread not found» — не тот `TELEGRAM_TENDERS_THREAD_ID`.
- **Оповещение о сбое импорта (с 2026-10-11):** тем же `TENDERS_NOTIFY_ENABLED` и в ту же тему — если площадка упала целиком (ошибки есть, а не записан ни один тендер) **2 прогона подряд**, одно тихое сообщение «⚠️ Импорт goszakup не работает: 2 прогона подряд (с …) — ни один тендер не записан» с причиной; когда заработает — «✅ Импорт … снова работает». Новых переменных нет. Частичные сбои (несколько больниц) не оповещаются — они в итоге прогона. Повод — 10.10.2026, 9 часов недоступности goszakup с прода без единого сигнала. Механика — CLAUDE.md §8.
- **Нагрузка на площадки:** 12 прогонов goszakup и 3 прогона СК-Фармации в рабочий день (раньше площадки опрашивались только по кнопке). После включения — смотреть отказы и 429 в итоге прогонов; при жалобах площадки — реже расписание (`TENDERS_AUTO_IMPORT_*_CRON`).
- **Откат:** `TENDERS_AUTO_IMPORT_ENABLED=false` и/или `TENDERS_NOTIFY_ENABLED=false` (выключает и уведомления о новых тендерах, и оповещения о сбоях) + `docker compose up -d --force-recreate ais-backend`.
- Прежние ключи `GOSZAKUP_IMPORT_ENABLED`, `GOSZAKUP_POLL_MS`, `SKPHARMACY_IMPORT_ENABLED`, `SKPHARMACY_POLL_MS` (тики раз в 6 ч) больше не читаются — их заменил автозапуск выше.

## 10. Письма клиентов на info@ → «Обращения»
Письмо клиента на info@westmed.kz становится обращением «Почта», переписка (письма клиента и наши ответы из «Отправленных») — в карточке обращения и на «Чатах»; служебное, рассылки и возвраты — во «Входящих» (фильтр «info@»). Спека — `docs/superpowers/specs/2026-10-13-info-mail-leads-design.md`, механика — CLAUDE.md §8 «Письма клиентов на info@». Код приезжает обычным деплоем (V29); пока в `.env` нет ключей ниже, приём выключен (`MAIL_CLIENTS_ENABLED=false`). Ящик АИС только читает (IMAP EXAMINE + `BODY.PEEK`): письма у людей остаются непрочитанными. Уведомления о письмах info@ в Telegram по-прежнему шлёт medsite — АИС их не шлёт.
**Статус:** код в проде с 2026-10-13 13:33 UTC (push `9081e2ce`), **приём включён 2026-10-13 13:39 UTC** (`deploy/set-info-mail.sh`: вход по IMAP прошёл, у «Отправленных» Mail.ru есть признак `\Sent`, копия `.env` до правки — `.env.bak-2026-10-13-1338-info`, пароль приложения — новый). **Приёмка пройдена на живых письмах:** первый проход (13:39:49 UTC) завёл 2 обращения «Почта», оператор закрыл их «Не клиент» (переписки отмечены); 14.10 ответ директора из веб-почты info@ на заявку сайта №4 АИС нашла в «Отправленных» за минуту — обращение «Новое» → «В работе», статус «В работе» ушёл на westmed.kz, через 5 минут ответ клиентки лёг в ту же переписку. Курсоры 14.10: «Входящие» UID 153, «Отправленные» UID 150; ошибок в логе приёма нет.
- **Включение** (агент — только после явного «да» оператора на SSH; секреты на экран и в чат не выводить):
  1. **Оператор:** новый пароль приложения info@ (Mail.ru → Безопасность → «Пароли для внешних приложений», доступ IMAP; прежний светился в переписке) → `~/.config/ais/info.pass` (600): вставить в строку ввода `(umask 077; pbpaste | tr -d '[:space:]' > ~/.config/ais/info.pass)`, ПОТОМ скопировать пароль, и только тогда Enter.
  2. **Оператор:** `! git push origin main` — код с ВЫКЛЮЧЕННЫМ приёмом (V29). Агент проверяет, что бэкенд поднялся.
  3. **Оператор:** `! scp -q deploy/set-info-mail.sh root@185.125.46.26:/root/ && ssh root@185.125.46.26 'bash /root/set-info-mail.sh; rm -f /root/set-info-mail.sh' < ~/.config/ais/info.pass` — вход по IMAP, копия `.env.bak-…-info`, ключи `MAIL_CLIENTS_*`, пересоздание бэкенда.
  4. Первый проход — через ~30 с после старта: письма клиентов за 7 дней (`MAIL_CLIENTS_SINCE_DAYS`) станут обращениями, на которые мы уже ответили из почты — сразу «В работе». Отработанные закрыть «Ответили клиенту без заявки», шум — «Не клиент». ⚠️ Письма склеиваются и с открытыми заявками с сайта того же адреса: наш ответ из «Отправленных» за эти 7 дней переведёт такую заявку «В работу» и запишет статус «В работе» на westmed.kz.
- **Приёмка:** (1) письмо с личной почты на info@ → обращение «Новое» с каналом «Почта» за минуту, письмо в почте осталось непрочитанным; (2) ответ из веб-почты info@ → «В работе», ответ виден в ленте карточки; (3) тестовое обращение закрыть «Не клиент» и снять отметку с переписки на «Чатах».
- **Проверка с сервера:**
  ```bash
  cd /srv/ais && docker compose logs --since 10m ais-backend | grep 'Приём писем клиентов' | tail -5
  docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -At' <<'SQL'
  select folder, uid_validity, last_uid, updated_at from mail_cursor where mailbox = 'info@westmed.kz';
  select status, count(*) from lead where channel = 'EMAIL' group by 1;
  SQL
  ```
- **Откат настройкой:** `MAIL_CLIENTS_ENABLED=false` → `docker compose up -d --force-recreate ais-backend`. Обращения и переписки остаются.
- ⚠️ **Повторное включение после паузы:** первые проходы догоняют всё, что пришло после курсора (по 100 писем за проход в каждой папке). Догонка не нужна — перед включением `delete from mail_cursor where mailbox = 'info@westmed.kz';` (первый запуск с окном 7 дней; записанное раньше отсеет повтор по Message-ID).
- ⚠️ **Кодом «просто откатить» нельзя:** старый бэкенд не прочитает сообщения вида `EMAIL` и причину `NOT_CLIENT` (`@Enumerated(STRING)` → 500 на «Чатах» и в карточке), а у письма бывает несколько файлов (старый код ждёт один — 500 в переписке). Порядок: (1) `MAIL_CLIENTS_ENABLED=false` + пересоздать бэкенд; (2) SQL:
  ```sql
  update lead set close_reason = 'OTHER' where close_reason = 'NOT_CLIENT';
  delete from chat where channel = 'EMAIL';   -- переписки info@: сообщения и файлы уходят каскадом, у обращений chat_id → NULL
  ```
  (3) откатить код (§3). Колонки V29 старому коду не мешают: курсор zakup@ — одна строка ящика, Flyway пропускает «будущую» миграцию.
- ⚠️ `MAIL_CLIENTS_MARKET` на ходу не менять; `MAIL_CLIENTS_USERNAME` ≠ `MAIL_IMAP_USERNAME` (иначе приём info@ выключается с ERROR при старте). Пароль с `$` или пробелом в `.env` без кавычек не пишется — у паролей приложений Mail.ru их нет.
- ⚠️ Письма, которые правила Mail.ru уносят из «Входящих» (и папку «Спам»), АИС не видит. Служебные адреса вне `MAIL_CLIENTS_SERVICE_DOMAINS` заведут обращение — «Не клиент» один раз на адрес. Письмо через сервис форм со служебного адреса (клиент — в Reply-To) уйдёт во «Входящие», а не в обращение.
