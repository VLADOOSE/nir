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
- **Бэкап БД** (там живые заявки/письма) — cron:
  ```bash
  0 3 * * * cd /srv/ais && docker compose exec -T ais-postgres sh -c 'pg_dump -U "$POSTGRES_USER" nirdb' | gzip > /srv/ais/backup/nirdb-$(date +\%F).sql.gz
  ```
  ⚠️ Контейнеры на сервере называются с префиксом compose-проекта — `ais-ais-postgres-1`, `ais-ais-backend-1`, — поэтому голый `docker exec ais-postgres …` падает «No such container». Через `docker compose` (из `/srv/ais`) работают имена сервисов.
- **Логи:** `docker compose logs -f ais-backend`
- **Рестарт:** `docker compose restart ais-backend`
- **Память:** после первого разбора ТЗ глянь `free -h` / `docker stats`. Если бэкенд упирается в
  `mem_limit 1300m` и падает на тяжёлых ТЗ — подними swap до 4–6 ГБ или VPS до 8 ГБ (тогда `-Xmx2g`).

---

## 5. 🔴 Хардинг перед боевым использованием
- ~~Сменить `admin/admin` и `operator/operator`~~ ✔ **2026-09-27** — штатным `PUT /api/users/{id}`; новые пароли у оператора в `~/.config/ais/ais-admin.pass` / `ais-operator.pass` (см. §6).
- **Перевыпустить** пароли приложений mail.ru и токен goszakup (светились в переписке).
- Демо-сид Flyway `V2` — при желании почистить (или добавить `V13` с очисткой демо-строк).
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

## 7. Чаты WhatsApp (Green-API)
Зеркало переписки рабочего номера West-Med (2026-09-28, спека `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md`; механика — CLAUDE.md §8). АИС сама опрашивает очередь Green-API — **наружу ничего не открывается**, калитка и nginx не меняются. Код приезжает обычным деплоем (V21), приём включается отдельно — через `.env`.
- **Подготовка (оператор):** инстанс Green-API на тарифе **Business** (на бесплатном Developer видно только 3 чата); привязать рабочий номер QR-кодом (WhatsApp → «Связанные устройства»); в настройках инстанса включить уведомления о входящих, об отправленных с телефона, о правках и удалениях; **поле адреса вебхука оставить ПУСТЫМ** — иначе сообщения уйдут туда, а не в очередь (строка состояния в АИС об этом предупредит). `apiUrl`, `idInstance`, токен — в `~/.config/ais/` на Mac (600), в чат не писать.
- ⚠️ **Один инстанс — один потребитель очереди:** прод и локальная разработка — на РАЗНЫХ инстансах (для разработки — свой Developer), иначе они разбирают сообщения друг у друга.
- **Перед включением:** `df -h` на сервере — файлы чатов (до `WHATSAPP_MAX_FILE_MB`, по умолчанию 25 МБ на файл) хранятся в БД; файлы групп не скачиваются.
- **Включение** (агент — только после явного «да» оператора; копия `.env` до правки): дописать в `/srv/ais/.env` `WHATSAPP_ENABLED=true`, `WHATSAPP_API_URL`, `WHATSAPP_ID_INSTANCE`, `WHATSAPP_API_TOKEN` (опц. `WHATSAPP_MAX_FILE_MB`, `WHATSAPP_MARKET` — по умолчанию `KZ`) → `cd /srv/ais && docker compose up -d ais-backend` (`restart` новый `.env` НЕ перечитывает — нужен `up -d`, он пересоздаёт контейнер).
- **Проверка:** АИС → «Чаты»: строка «WhatsApp +7 … · подключён» с верным номером; сообщение с личного телефона → чат и обращение «Новое»; ответ с рабочего телефона → в АИС справа, обращение «В работе». Лог: `docker compose logs --since 10m ais-backend | grep -i whatsapp` (адресов запросов в логе нет — токен в них).
- **Откат:** `WHATSAPP_ENABLED=false` → `docker compose up -d ais-backend`. Приём останавливается, таблицы V21 и история остаются и читаются на «Чатах». Непрочитанное Green-API хранит сутки.
- **Номер на сайте westmed.kz** (отдельный репозиторий `~/IdeaProjects/westmed`, отдельный деплой): три места — `frontend/src/components/shared/WhatsAppButton.tsx` (`PHONE`), `frontend/src/components/shared/Footer.tsx`, `frontend/src/app/[locale]/(storefront)/contacts/page.tsx`.
