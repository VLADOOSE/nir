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
  0 3 * * * docker exec ais-postgres pg_dump -U nir nirdb | gzip > /srv/ais/backup/nirdb-$(date +\%F).sql.gz
  ```
- **Логи:** `docker compose logs -f ais-backend`
- **Рестарт:** `docker compose restart ais-backend`
- **Память:** после первого разбора ТЗ глянь `free -h` / `docker stats`. Если бэкенд упирается в
  `mem_limit 1300m` и падает на тяжёлых ТЗ — подними swap до 4–6 ГБ или VPS до 8 ГБ (тогда `-Xmx2g`).

---

## 5. 🔴 Хардинг перед боевым использованием
- **Сменить `admin/admin` и `operator/operator`** (дефолт — дыра №1).
- **Перевыпустить** пароли приложений mail.ru и токен goszakup (светились в переписке).
- Демо-сид Flyway `V2` — при желании почистить (или добавить `V13` с очисткой демо-строк).
- Позже: увести SSH за Tailscale и закрыть :22 в ufw (сейчас 22 публичен) — тогда доступ к серверу
  тоже только по VPN.
