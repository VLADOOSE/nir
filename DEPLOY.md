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

## 7. Чаты WhatsApp (свой шлюз WAHA; Green-API — запасной)
Зеркало переписки рабочего номера West-Med: спеки `docs/superpowers/specs/2026-09-28-whatsapp-chats-green-api-design.md` (модель чатов, правила обращений) и `docs/superpowers/specs/2026-09-28-whatsapp-waha-design.md` (шлюз); механика — CLAUDE.md §8. WAHA — контейнер `ais-waha` в том же compose, только во внутренней сети: вебхук идёт `ais-waha → ais-backend:8080` мимо nginx, **наружу ничего не открывается** (в nginx фронта путь вебхука закрыт `404`, калитка не меняется). Код приезжает обычным деплоем (V22); WAHA запускается только профилем `whatsapp`.
- ⚠️ **Условие включения — задача 13 плана WAHA выполнена** (живая проверка с тестовым телефоном, фикстуры живых событий): защита «файл больше `WHATSAPP_MAX_FILE_MB` не качать» опирается на размер файла из события (`_data.Message.<вид>.fileLength`), а форма событий GOWS с живыми ещё не сверена. Если размер лежит не там, АИС попросит WAHA скачать файл целиком в память — видео до 2 ГБ при потолке 700 МБ убьёт контейнер, а с ним повторы вебхуков, которые WAHA держит в памяти. Проверка: `WahaLiveFixturesTest` зелёный (размер есть у документа, фото и видео — и в вебхуке, и в сообщении из догонки).
- **Перед включением:** `free -h` и `docker stats --no-stream` (WAHA ≈200 МБ, потолок 700 МБ); `df -h` — файлы чатов хранятся в БД (до `WHATSAPP_MAX_FILE_MB`, по умолчанию 25 МБ на файл); `uname -m` — должно быть `x86_64`: тег `gows-2026.9.1` собран только под amd64 (у того же релиза есть `gows-arm-2026.9.1` для arm64 — на нём WAHA проверялась локально на Mac).
- **Включение** (агент — только после явного «да» оператора; копия `.env` до правки): в `/srv/ais/.env` — `WHATSAPP_ENABLED=true`, `WHATSAPP_PROVIDER=waha`, `WHATSAPP_WAHA_API_KEY` и `WHATSAPP_WAHA_HMAC_KEY` (каждый — `openssl rand -hex 32` прямо на сервере, значения в чат не печатать), `COMPOSE_PROFILES=whatsapp` → `cd /srv/ais && docker compose up -d` (поднимет `ais-waha` и пересоздаст бэкенд с новым `.env`; `restart` новый `.env` не перечитывает).
- **Привязка:** АИС сама создаёт сессию `westmed`; оператор → «Система → WhatsApp» → QR **рабочим** телефоном (WhatsApp → Настройки → Связанные устройства → Привязка устройства). Код живёт ~2,5 мин (первый 60 с, дальше по 20 с), потом сессия «упала» — «Перезапустить» даёт новый.
- В логе WAHA при старте печатается «Generated credentials» для панели и Swagger — обе выключены (`WAHA_DASHBOARD_ENABLED=false`, `WHATSAPP_SWAGGER_ENABLED=false`), эти пароли ни к чему не пускают.
- **Приёмочный тест** (главное — issue #2241 WAHA: свои сообщения в чужом чате): с личного телефона — на рабочий; с рабочего — ответы 2–3 разным людям, в т.ч. не из контактов, → легли в правильные чаты; фото, PDF, Excel; **видео больше 25 МБ** → в чате «файл не сохранён: больше 25 МБ» И это сработала именно проверка размера, а не обрыв потока: в логе бэкенда строка «файл больше предела (N МБ при пределе 25 МБ) — не скачиваем» (`docker compose logs --since 10m ais-backend | grep 'больше предела'`), в логе WAHA запрос скачивания не появился (`docker compose logs --since 10m ais-waha | grep -c 'downloadMedia=true'` — не вырос), у `ais-waha` в `docker stats --no-stream` нет всплеска памяти. «Не сохранён» без строки в логе — защиты нет: WAHA скачала файл целиком, а АИС лишь обрезала поток; правка, удаление; звонок → строка «📞 Входящий звонок — принят»; **push на рабочем телефоне по-прежнему приходят** (`WAHA_PRESENCE_AUTO_ONLINE=False`). То же — после каждого обновления образа.
- ⚠️ **Автоответы WhatsApp Business** на рабочем номере до приёмочного теста не включать: если WAHA пришлёт их как отправленные с телефона, каждое новое обращение сразу уйдёт «В работу».
- **Красная строка на «Чатах» — что делать:** «номер не подключён» — привязать в «Система → WhatsApp»; «сессия упала» / «сессия остановлена» — там же «Перезапустить»; «сессия упала» СРАЗУ после перезапуска, а в логе WAHA `failed to dial whatsapp web websocket … no such host` — контейнер не достаёт до WhatsApp: `docker compose exec ais-waha getent hosts web.whatsapp.com` (пусто — DNS сервера не отдаёт адрес; так было в локальной сети разработки → у сервиса `dns: [8.8.8.8]`); «нужно подтверждение на рабочем телефоне» — подтвердить в WhatsApp на телефоне; «WHATSAPP_PROVIDER: неизвестный провайдер» — опечатка в `.env` (`waha` или `greenapi`); «WAHA недоступен … принятые сообщения разбираются, но файлы к ним могут не скачаться» — `docker compose ps ais-waha`, `docker compose logs --since 10m ais-waha` (файл получает три попытки ≈1 мин, дальше сообщение пишется без него — файл останется в телефоне); «WAHA присылает события с чужой подписью» (держится сутки или до первого события с верной подписью) — `WHATSAPP_WAHA_HMAC_KEY` в `.env` разошёлся с запущенной WAHA → `docker compose up -d` (пересоздаст обе; до этого правки, удаления и звонки не доходят, сообщения подбирает только догонка); «WAHA прислала событие больше 4 МБ» — `docker compose logs ais-backend | grep 'тело больше'`; если это настоящее событие WAHA, а не мусор из сети docker, — поднять `WahaWebhookController.MAX_BODY_BYTES` (код) и задеплоить; «не заданы ключи шлюза WAHA» — ключи в `.env`; «WAHA отклонил ключ API» — ключ в `.env` разошёлся с запущенным контейнером → `docker compose up -d`; «база данных недоступна» — чинить БД: WAHA повторяет вебхуки до ~4,5 ч (в своей памяти; её рестарт их теряет — пропущенное подберёт догонка); «приём остановлен: за сутки не записались 3 сообщения» — поломка кода, `docker compose logs ais-backend`; «не удалось догнать сообщения…» — повтор сам через 10 мин.
- **Очередь:** `docker compose exec -T ais-postgres sh -c 'psql -U "$POSTGRES_USER" -d nirdb -c "select status, count(*) from whatsapp_inbox group by 1"'` (через `sh -c`, как бэкап в §4: `$POSTGRES_USER` есть только внутри контейнера) — `PENDING` не должен копиться, у `DROPPED` причина в `last_error`.
- **Откат:** `WHATSAPP_ENABLED=false` → `docker compose up -d`; устройство WAHA удалить в телефоне («Связанные устройства»). Убрать WAHA совсем — ещё строку `COMPOSE_PROFILES=whatsapp` из `.env` и `docker compose stop ais-waha`. История остаётся и читается на «Чатах».
- **Обновление WAHA** — только вручную: журнал изменений → новый тег в `docker-compose.yml` → деплой → приёмочный тест.
- ⚠️ Том `ais-waha-sessions` = доступ к рабочему WhatsApp: не выкладывать, в бэкапы — только осознанно.
- **Запасной провайдер Green-API:** `WHATSAPP_PROVIDER=greenapi` + `WHATSAPP_API_URL` / `WHATSAPP_ID_INSTANCE` / `WHATSAPP_API_TOKEN` (инстанс Business, адрес вебхука в кабинете — ПУСТОЙ; один инстанс — один потребитель очереди) → `docker compose up -d`; затем `docker compose stop ais-waha` и отвязать устройство WAHA в телефоне. Пока провайдер не `waha`, события WAHA АИС принимает, но в очередь не пишет — иначе при возврате на WAHA проиграла бы давние звонки и правки. Дублей при переключении нет: id сообщения WhatsApp у обоих шлюзов один.
- **Номер на сайте westmed.kz** (отдельный репозиторий `~/IdeaProjects/westmed`, отдельный деплой): три места — `frontend/src/components/shared/WhatsAppButton.tsx` (`PHONE`), `frontend/src/components/shared/Footer.tsx`, `frontend/src/app/[locale]/(storefront)/contacts/page.tsx`.
