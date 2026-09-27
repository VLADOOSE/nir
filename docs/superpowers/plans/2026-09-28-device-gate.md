# Калитка по коду устройства для ais.westmed.kz — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Закрыть `https://ais.westmed.kz` калиткой по устройству: nginx хоста пускает к АИС только браузеры, которые админ допустил в разделе «Устройства»; всем остальным — страница с кодом запроса.

**Architecture:** nginx хоста на каждый запрос делает подзапрос `auth_request` в АИС (`GET /api/gate/check`), АИС отвечает из реестра допущенных устройств в памяти (204/401). Недопущенным nginx отдаёт `302 /gate/` (страницы) или `401` с заголовком `X-AIS-Gate: device` (API — приложение само уходит на калитку). Устройства хранятся в `trusted_device` (V19): ключ из cookie `ais_device` — только хешем. Калитка — самостоятельная HTML-страница в `frontend/public/gate/`, админка — Angular-страница «Устройства».

**Tech Stack:** Java 17, Spring Boot 3.5.6 (Web, Data JPA, Security), Flyway, PostgreSQL 17 (nirdb), Angular 21 standalone, nginx 1.18 (хост прода; модуль `auth_request` есть), Docker (репетиция nginx).

**Spec:** `docs/superpowers/specs/2026-09-28-device-gate-design.md` — исполнитель читает её целиком перед началом.

## Global Constraints

- Cookie ключа: имя `ais_device`, атрибуты `Path=/; HttpOnly; Secure; SameSite=Lax; Max-Age=34560000` (400 дней).
- Ключ — 32 байта `SecureRandom` → base64url без выравнивания (43 символа); в БД — только SHA-256 hex (64 символа).
- Код запроса — 6 символов из алфавита `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, показ `XXX-XXX`, уникален среди `PENDING`.
- Запрос доступа живёт **15 минут**; ожидающих одновременно — не больше **20** (иначе 429); имя 1–60 символов после обрезки (иначе 400); `User-Agent` обрезается до 300, подпись админа — до 100 (иначе 400).
- Допущенное устройство — до отзыва; **90 дней** без визитов — `EXPIRED`.
- Статусы: `PENDING / TRUSTED / REJECTED / REVOKED / EXPIRED`; для страницы калитки плюс `NONE`.
- `/api/gate/**` — `permitAll`, ответы `Cache-Control: no-store`; `/api/devices/**` — только `ROLE_ADMIN`.
- Пометка ответа калитки для API: заголовок `X-AIS-Gate: device`.
- Таблица `trusted_device` — **общая, без рынка** (без `@Filter`, без `MarketStampingListener`).
- Миграция — `V19__trusted_device.sql` (V18 занята обращениями).
- Все `./gradlew` и обращения к БД — с `dangerouslyDisableSandbox: true` (CLAUDE.md §5). Полный гейт — `./gradlew cleanTest test` (0 падений), фронт — `cd frontend && npm run build`.
- Тесты бэкенда — `@SpringBootTest @Transactional` на nirdb, контроллеры вызываются напрямую (как в проекте); HTTP-правила безопасности — один MockMvc-тест.
- UI — UI-kit проекта (CLAUDE.md §12): токены, светлая/тёмная тема, `@media` в конце стилей, тач-таргеты, «пусто — не рисуем».
- Каждый коммит заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- `$SCRATCH` в командах — scratchpad-каталог сессии (агенту он назван в системном промпте); в git оттуда ничего не попадает.
- **Push в `main` делает оператор** (`! git push origin main`) — авторежим Claude Code не пускает агента деплоить (CLAUDE.md §5). Действия на проде (SSH) — только в задаче 9 и только с согласия оператора.

## Карта файлов

| Файл | Ответственность |
|---|---|
| `src/main/resources/db/migration/V19__trusted_device.sql` | таблица устройств |
| `src/main/java/com/vladoose/nir/entity/DeviceStatus.java`, `TrustedDevice.java` | сущность и статусы |
| `src/main/java/com/vladoose/nir/repository/TrustedDeviceRepository.java` | выборки и массовые обновления |
| `src/main/java/com/vladoose/nir/util/DeviceTokens.java` | ключ, хеш, код |
| `src/main/java/com/vladoose/nir/util/UserAgentSummary.java` | «iPhone · Safari» |
| `src/main/java/com/vladoose/nir/util/ClientIp.java` | IP клиента за двумя прокси |
| `src/main/java/com/vladoose/nir/util/GateCookie.java` | cookie ключа |
| `src/main/java/com/vladoose/nir/exception/ConflictException.java`, `TooManyRequestsException.java`, правка `GlobalExceptionHandler.java` | 409 и 429 |
| `src/main/java/com/vladoose/nir/service/DeviceRegistry.java` | допущенные в памяти + визиты |
| `src/main/java/com/vladoose/nir/service/TrustedDeviceWriter.java` | изменения БД, транзакция на метод |
| `src/main/java/com/vladoose/nir/service/DeviceGateService.java` | вся логика калитки и раздела |
| `src/main/java/com/vladoose/nir/service/DeviceGateScheduler.java` | фоновая задача раз в 5 минут |
| `src/main/java/com/vladoose/nir/dto/request/GateAccessRequest.java`, `DeviceApproveRequest.java` | тела запросов |
| `src/main/java/com/vladoose/nir/dto/response/GateStateResponse.java`, `DeviceResponse.java`, `DeviceListResponse.java` | ответы |
| `src/main/java/com/vladoose/nir/controller/GateController.java`, `DeviceController.java` | REST |
| правка `src/main/java/com/vladoose/nir/config/SecurityConfig.java` | `/api/gate/**` → `permitAll` |
| `src/test/java/com/vladoose/nir/gate/*Test.java` | тесты |
| `frontend/public/gate/index.html` | страница калитки |
| правка `frontend/src/app/interceptors/auth.interceptor.ts` | `X-AIS-Gate` → `/gate/` |
| `frontend/src/app/pages/devices/devices.component.ts` | раздел «Устройства» |
| правки `frontend/src/app/services/api.service.ts`, `app.routes.ts`, `app.config.ts`, `layout/layout.component.ts` | API, маршрут, иконка, пункт меню со счётчиком |
| правка `deploy/nginx/zz-ais.westmed.kz.conf`, новый `deploy/nginx/apply.sh` | nginx хоста и его установка с откатом |
| правки `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md`, `frontend/nginx.conf` (комментарий) | документация |

---

### Task 1: Данные — V19, сущность, репозиторий

**Files:**
- Create: `src/main/resources/db/migration/V19__trusted_device.sql`
- Create: `src/main/java/com/vladoose/nir/entity/DeviceStatus.java`
- Create: `src/main/java/com/vladoose/nir/entity/TrustedDevice.java`
- Create: `src/main/java/com/vladoose/nir/repository/TrustedDeviceRepository.java`
- Test: `src/test/java/com/vladoose/nir/gate/TrustedDevicePersistenceTest.java`

**Interfaces:**
- Produces: `DeviceStatus {PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED}`; `TrustedDevice` (Lombok `@Builder`, поля: `id, tokenHash, status, code, requesterName, label, userAgent, ip, requestedAt, decidedAt, decidedBy, lastSeenAt`); `TrustedDeviceRepository` с методами `findByTokenHash(String)`, `findByStatus(DeviceStatus)`, `findByStatusAndRequestedAtAfterOrderByRequestedAtDesc(DeviceStatus, OffsetDateTime)`, `findByStatusOrderByDecidedAtDesc(DeviceStatus)`, `findTop50ByStatusInOrderByRequestedAtDesc(Collection<DeviceStatus>)`, `countByStatusAndRequestedAtAfter(DeviceStatus, OffsetDateTime)`, `existsByCodeAndStatus(String, DeviceStatus)`, `int moveRequestedBefore(DeviceStatus from, DeviceStatus to, OffsetDateTime cutoff)`, `int markSeen(String hash, OffsetDateTime seen, DeviceStatus status)`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/gate/TrustedDevicePersistenceTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class TrustedDevicePersistenceTest {

    @Autowired TrustedDeviceRepository repo;

    private static final OffsetDateTime NOW = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);

    private static TrustedDevice device(String code, DeviceStatus status, OffsetDateTime requestedAt) {
        String hash = (UUID.randomUUID().toString() + UUID.randomUUID()).replace("-", "");   // 64 hex
        return TrustedDevice.builder().tokenHash(hash).status(status).code(code)
                .requesterName("Проверка").requestedAt(requestedAt).build();
    }

    @Test
    void savesAndFindsByTokenHash() {
        TrustedDevice d = repo.saveAndFlush(device("ABCDEF", DeviceStatus.PENDING, NOW));

        TrustedDevice found = repo.findByTokenHash(d.getTokenHash()).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(found.getRequesterName()).isEqualTo("Проверка");
        assertThat(found.getRequestedAt()).isAtSameInstantAs(NOW);
    }

    @Test
    void codeIsUniqueOnlyAmongPending() {
        repo.saveAndFlush(device("QWERTY", DeviceStatus.EXPIRED, NOW));
        repo.saveAndFlush(device("QWERTY", DeviceStatus.PENDING, NOW));     // вне PENDING код может повторяться

        assertThatThrownBy(() -> repo.saveAndFlush(device("QWERTY", DeviceStatus.PENDING, NOW)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void movesOnlyStaleRequests() {
        TrustedDevice stale = repo.saveAndFlush(device("STALE2", DeviceStatus.PENDING, NOW.minusMinutes(20)));
        TrustedDevice fresh = repo.saveAndFlush(device("FRESH2", DeviceStatus.PENDING, NOW.minusMinutes(5)));

        repo.moveRequestedBefore(DeviceStatus.PENDING, DeviceStatus.EXPIRED, NOW.minusMinutes(15));

        assertThat(repo.findById(stale.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(repo.findById(fresh.getId()).orElseThrow().getStatus()).isEqualTo(DeviceStatus.PENDING);
    }

    @Test
    void markSeenTouchesOnlyTrusted() {
        TrustedDevice trusted = repo.saveAndFlush(device("TRUST2", DeviceStatus.TRUSTED, NOW));
        TrustedDevice revoked = repo.saveAndFlush(device("REVOK2", DeviceStatus.REVOKED, NOW));

        assertThat(repo.markSeen(trusted.getTokenHash(), NOW, DeviceStatus.TRUSTED)).isEqualTo(1);
        assertThat(repo.markSeen(revoked.getTokenHash(), NOW, DeviceStatus.TRUSTED)).isZero();
        assertThat(repo.findById(trusted.getId()).orElseThrow().getLastSeenAt()).isAtSameInstantAs(NOW);
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.TrustedDevicePersistenceTest'`
Expected: FAIL — компиляция: `cannot find symbol ... TrustedDevice / DeviceStatus / TrustedDeviceRepository`.

- [ ] **Step 3: Миграция**

`src/main/resources/db/migration/V19__trusted_device.sql`:

```sql
-- Калитка по коду устройства для ais.westmed.kz (спека docs/superpowers/specs/2026-09-28-device-gate-design.md §4).
-- Общая таблица, без рынка: доступ к системе не зависит от переключателя РФ/KZ.
CREATE TABLE trusted_device (
    id              BIGSERIAL PRIMARY KEY,
    token_hash      VARCHAR(64)  NOT NULL UNIQUE,   -- SHA-256 (hex) ключа устройства; сам ключ не хранится
    status          VARCHAR(10)  NOT NULL,          -- PENDING / TRUSTED / REJECTED / REVOKED / EXPIRED
    code            VARCHAR(6)   NOT NULL,          -- код запроса без дефиса (показ — «7K4-QM2»)
    requester_name  VARCHAR(60)  NOT NULL,          -- что ввели на калитке; НЕ доверенное
    label           VARCHAR(100),                   -- подпись админа при допуске
    user_agent      VARCHAR(300),
    ip              VARCHAR(45),
    requested_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    decided_at      TIMESTAMPTZ,
    decided_by      VARCHAR(100),                   -- логин админа, принявшего последнее решение
    last_seen_at    TIMESTAMPTZ
);
CREATE INDEX idx_trusted_device_status ON trusted_device (status);
-- код уникален среди ожидающих: по нему админ находит нужный запрос
CREATE UNIQUE INDEX uq_trusted_device_pending_code ON trusted_device (code) WHERE status = 'PENDING';
```

- [ ] **Step 4: Статусы и сущность**

`src/main/java/com/vladoose/nir/entity/DeviceStatus.java`:

```java
package com.vladoose.nir.entity;

/** Статусы устройства калитки (спека device-gate §5). REJECTED, REVOKED, EXPIRED — конечные. */
public enum DeviceStatus { PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED }
```

`src/main/java/com/vladoose/nir/entity/TrustedDevice.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

/**
 * Устройство калитки ais.westmed.kz: запрос доступа и, после решения админа, допуск (спека device-gate §4–§5).
 * Общая сущность, без рынка — как {@code UserAccount}: доступ к системе не зависит от переключателя РФ/KZ.
 */
@Entity
@Table(name = "trusted_device")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TrustedDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** SHA-256 (hex) ключа из cookie; сам ключ в БД не хранится. */
    @Column(name = "token_hash", nullable = false, unique = true, length = 64)
    private String tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private DeviceStatus status;

    /** Код запроса без дефиса; показывается как «7K4-QM2». */
    @Column(nullable = false, length = 6)
    private String code;

    /** Имя, введённое на калитке. Не доверенное — решает код. */
    @Column(name = "requester_name", nullable = false, length = 60)
    private String requesterName;

    @Column(length = 100)
    private String label;

    @Column(name = "user_agent", length = 300)
    private String userAgent;

    @Column(length = 45)
    private String ip;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "decided_at")
    private OffsetDateTime decidedAt;

    @Column(name = "decided_by", length = 100)
    private String decidedBy;

    @Column(name = "last_seen_at")
    private OffsetDateTime lastSeenAt;
}
```

- [ ] **Step 5: Репозиторий**

`src/main/java/com/vladoose/nir/repository/TrustedDeviceRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface TrustedDeviceRepository extends JpaRepository<TrustedDevice, Long> {

    Optional<TrustedDevice> findByTokenHash(String tokenHash);

    List<TrustedDevice> findByStatus(DeviceStatus status);

    List<TrustedDevice> findByStatusAndRequestedAtAfterOrderByRequestedAtDesc(DeviceStatus status, OffsetDateTime after);

    List<TrustedDevice> findByStatusOrderByDecidedAtDesc(DeviceStatus status);

    List<TrustedDevice> findTop50ByStatusInOrderByRequestedAtDesc(Collection<DeviceStatus> statuses);

    long countByStatusAndRequestedAtAfter(DeviceStatus status, OffsetDateTime after);

    boolean existsByCodeAndStatus(String code, DeviceStatus status);

    /** Массово перевести запросы, поданные раньше {@code cutoff}, из {@code from} в {@code to}. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TrustedDevice d set d.status = :to where d.status = :from and d.requestedAt < :cutoff")
    int moveRequestedBefore(@Param("from") DeviceStatus from, @Param("to") DeviceStatus to,
                            @Param("cutoff") OffsetDateTime cutoff);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update TrustedDevice d set d.lastSeenAt = :seen where d.tokenHash = :hash and d.status = :status")
    int markSeen(@Param("hash") String hash, @Param("seen") OffsetDateTime seen, @Param("status") DeviceStatus status);
}
```

`clearAutomatically` обязателен: без него после массового `update` в том же контексте остаются старые объекты, и следующий `findById` вернул бы прежний статус.

- [ ] **Step 6: Тест проходит**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.TrustedDevicePersistenceTest'`
Expected: PASS, 4 теста. В логе старта — `Migrating schema "public" to version "19 - trusted device"`.

- [ ] **Step 7: Commit**

```bash
git add src/main/resources/db/migration/V19__trusted_device.sql \
        src/main/java/com/vladoose/nir/entity/DeviceStatus.java \
        src/main/java/com/vladoose/nir/entity/TrustedDevice.java \
        src/main/java/com/vladoose/nir/repository/TrustedDeviceRepository.java \
        src/test/java/com/vladoose/nir/gate/TrustedDevicePersistenceTest.java
git commit -m "feat(gate): таблица устройств калитки (V19), сущность, репозиторий

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Примитивы — ключ, код, описание устройства, IP, cookie

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/DeviceTokens.java`
- Create: `src/main/java/com/vladoose/nir/util/UserAgentSummary.java`
- Create: `src/main/java/com/vladoose/nir/util/ClientIp.java`
- Create: `src/main/java/com/vladoose/nir/util/GateCookie.java`
- Test: `src/test/java/com/vladoose/nir/gate/GatePrimitivesTest.java`

**Interfaces:**
- Produces: `DeviceTokens.newToken(): String`, `DeviceTokens.hash(String): String`, `DeviceTokens.newCode(): String`, `DeviceTokens.display(String): String`, константа `DeviceTokens.CODE_ALPHABET`; `UserAgentSummary.describe(String): String`; `ClientIp.of(HttpServletRequest): String`; `GateCookie.NAME = "ais_device"`, `GateCookie.of(String token): ResponseCookie`.

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/gate/GatePrimitivesTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.util.ClientIp;
import com.vladoose.nir.util.DeviceTokens;
import com.vladoose.nir.util.GateCookie;
import com.vladoose.nir.util.UserAgentSummary;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.*;

class GatePrimitivesTest {

    @Test
    void tokenIs43UrlSafeRandomChars() {
        String a = DeviceTokens.newToken();
        String b = DeviceTokens.newToken();
        assertThat(a).matches("[A-Za-z0-9_-]{43}");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void hashIsSha256Hex() {
        assertThat(DeviceTokens.hash("abc"))
                .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
    }

    @Test
    void codeUsesOnlyUnambiguousCharacters() {
        for (int i = 0; i < 500; i++) assertThat(DeviceTokens.newCode()).matches("[2-9A-HJKMNP-Z]{6}");
        assertThat(DeviceTokens.CODE_ALPHABET).doesNotContain("0", "O", "1", "I", "L");
    }

    @Test
    void codeIsShownInTwoHalves() {
        assertThat(DeviceTokens.display("7K4QM2")).isEqualTo("7K4-QM2");
    }

    @Test
    void userAgentGoldenSet() {
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"))
                .isEqualTo("iPhone · Safari");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) CriOS/126.0.6478.54 Mobile/15E148 Safari/604.1"))
                .isEqualTo("iPhone · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Linux; Android 14; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"))
                .isEqualTo("Android · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Linux; Android 13; M2101K6G) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 YaBrowser/24.4.1.99.00 SA/3 Mobile Safari/537.36"))
                .isEqualTo("Android · Яндекс");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"))
                .isEqualTo("Mac · Chrome");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"))
                .isEqualTo("Mac · Safari");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.68"))
                .isEqualTo("Windows · Edge");
        assertThat(UserAgentSummary.describe("Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:127.0) Gecko/20100101 Firefox/127.0"))
                .isEqualTo("Windows · Firefox");
        assertThat(UserAgentSummary.describe(null)).isEqualTo("Браузер");
        assertThat(UserAgentSummary.describe("curl/8.4.0")).isEqualTo("Браузер");
    }

    @Test
    void clientIpIsTheAddressAddedByHostNginx() {
        assertThat(ip("1.2.3.4, 5.6.7.8, 172.18.0.1", "172.18.0.5")).isEqualTo("5.6.7.8");   // первый — подделка клиента
        assertThat(ip("5.6.7.8, 172.18.0.1", "172.18.0.5")).isEqualTo("5.6.7.8");
        assertThat(ip("172.18.0.1", "172.18.0.5")).isEqualTo("172.18.0.1");
        assertThat(ip(null, "10.0.0.7")).isEqualTo("10.0.0.7");
    }

    private static String ip(String xff, String remote) {
        MockHttpServletRequest r = new MockHttpServletRequest();
        if (xff != null) r.addHeader("X-Forwarded-For", xff);
        r.setRemoteAddr(remote);
        return ClientIp.of(r);
    }

    @Test
    void cookieIsProtectedAndLongLived() {
        assertThat(GateCookie.of("abc").toString())
                .startsWith("ais_device=abc")
                .contains("Path=/", "Max-Age=34560000", "Secure", "HttpOnly", "SameSite=Lax");
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.GatePrimitivesTest'`
Expected: FAIL — компиляция: `package com.vladoose.nir.util ... cannot find symbol DeviceTokens`.

- [ ] **Step 3: DeviceTokens**

`src/main/java/com/vladoose/nir/util/DeviceTokens.java`:

```java
package com.vladoose.nir.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Ключ устройства калитки, его хеш и короткий код запроса (спека device-gate §6). */
public final class DeviceTokens {

    /** Без 0/O/1/I/L — не путаются ни на слух, ни на экране. */
    public static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    public static final int CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private DeviceTokens() {}

    /** 32 случайных байта в base64url без выравнивания — 43 символа. */
    public static String newToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** SHA-256 в hex (64 символа) — только он и хранится в БД. */
    public static String hash(String token) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }

    public static String newCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
        return sb.toString();
    }

    /** «7K4QM2» → «7K4-QM2». */
    public static String display(String code) {
        return code == null || code.length() != CODE_LENGTH ? code : code.substring(0, 3) + "-" + code.substring(3);
    }
}
```

- [ ] **Step 4: UserAgentSummary**

`src/main/java/com/vladoose/nir/util/UserAgentSummary.java`:

```java
package com.vladoose.nir.util;

/** «iPhone · Safari» из User-Agent — чтобы админ узнал устройство в списке (спека device-gate §7). */
public final class UserAgentSummary {

    private UserAgentSummary() {}

    public static String describe(String ua) {
        if (ua == null || ua.isBlank()) return "Браузер";
        String os = os(ua);
        String browser = browser(ua);
        if (os == null && browser == null) return "Браузер";
        if (os == null) return browser;
        if (browser == null) return os;
        return os + " · " + browser;
    }

    private static String os(String ua) {
        if (ua.contains("iPhone")) return "iPhone";          // раньше Mac: в строке iPhone есть «like Mac OS X»
        if (ua.contains("iPad")) return "iPad";
        if (ua.contains("Android")) return "Android";        // раньше Linux: в строке Android есть и «Linux»
        if (ua.contains("Windows")) return "Windows";
        if (ua.contains("Macintosh") || ua.contains("Mac OS X")) return "Mac";
        if (ua.contains("Linux")) return "Linux";
        return null;
    }

    private static String browser(String ua) {
        // порядок важен: Edge, Opera и Яндекс выдают себя за Chrome, а Chrome — за Safari
        if (ua.contains("Edg/") || ua.contains("EdgiOS/") || ua.contains("EdgA/")) return "Edge";
        if (ua.contains("OPR/")) return "Opera";
        if (ua.contains("YaBrowser/")) return "Яндекс";
        if (ua.contains("Firefox/") || ua.contains("FxiOS/")) return "Firefox";
        if (ua.contains("Chrome/") || ua.contains("CriOS/")) return "Chrome";
        if (ua.contains("Safari/")) return "Safari";
        return null;
    }
}
```

- [ ] **Step 5: ClientIp и GateCookie**

`src/main/java/com/vladoose/nir/util/ClientIp.java`:

```java
package com.vladoose.nir.util;

import jakarta.servlet.http.HttpServletRequest;

/**
 * IP клиента за двумя прокси (спека device-gate §7). nginx хоста ДОПИСЫВАЕТ реальный адрес к X-Forwarded-For,
 * присланному клиентом (первый адрес поэтому подделывается), фронт-контейнер дописывает свой шаг последним —
 * настоящий адрес предпоследний. IP справочный: чтобы админ узнал устройство, а не чтобы что-то решать.
 */
public final class ClientIp {

    private static final int MAX = 45;

    private ClientIp() {}

    public static String of(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        String ip;
        if (xff == null || xff.isBlank()) {
            ip = req.getRemoteAddr();
        } else {
            String[] hops = xff.split(",");
            ip = hops[hops.length >= 2 ? hops.length - 2 : 0].trim();
        }
        return ip == null || ip.length() <= MAX ? ip : ip.substring(0, MAX);
    }
}
```

`src/main/java/com/vladoose/nir/util/GateCookie.java`:

```java
package com.vladoose.nir.util;

import org.springframework.http.ResponseCookie;

import java.time.Duration;

/** Cookie ключа устройства калитки (спека device-gate §6). */
public final class GateCookie {

    public static final String NAME = "ais_device";
    /** Предел Chrome; сам срок допуска решает сервер — 90 дней без визитов. */
    public static final Duration MAX_AGE = Duration.ofDays(400);

    private GateCookie() {}

    public static ResponseCookie of(String token) {
        return ResponseCookie.from(NAME, token)
                .path("/").httpOnly(true).secure(true).sameSite("Lax").maxAge(MAX_AGE)
                .build();
    }
}
```

- [ ] **Step 6: Тест проходит**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.GatePrimitivesTest'`
Expected: PASS, 7 тестов.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/DeviceTokens.java src/main/java/com/vladoose/nir/util/UserAgentSummary.java \
        src/main/java/com/vladoose/nir/util/ClientIp.java src/main/java/com/vladoose/nir/util/GateCookie.java \
        src/test/java/com/vladoose/nir/gate/GatePrimitivesTest.java
git commit -m "feat(gate): ключ и код устройства, описание браузера, IP за прокси, cookie калитки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Логика калитки — реестр, запись в БД, сервис

**Files:**
- Create: `src/main/java/com/vladoose/nir/exception/ConflictException.java`
- Create: `src/main/java/com/vladoose/nir/exception/TooManyRequestsException.java`
- Modify: `src/main/java/com/vladoose/nir/exception/GlobalExceptionHandler.java` (два обработчика перед `@ExceptionHandler(Exception.class)`)
- Create: `src/main/java/com/vladoose/nir/dto/response/DeviceResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/DeviceListResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/DeviceRegistry.java`
- Create: `src/main/java/com/vladoose/nir/service/TrustedDeviceWriter.java`
- Create: `src/main/java/com/vladoose/nir/service/DeviceGateService.java`
- Test: `src/test/java/com/vladoose/nir/gate/DeviceGateServiceTest.java`

**Interfaces:**
- Consumes: всё из Task 1 и Task 2.
- Produces: `DeviceGateService` c вложенными `enum GateState {NONE, PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED}` и `record GateResult(GateState state, String code, OffsetDateTime expiresAt, String newToken)`; методы `boolean isTrusted(String token, OffsetDateTime now)`, `GateResult request(String token, String name, String userAgent, String ip, OffsetDateTime now)`, `GateResult status(String token, OffsetDateTime now)`, `DeviceListResponse list(String currentToken, OffsetDateTime now)`, `long pendingCount(OffsetDateTime now)`, `DeviceResponse approve(Long id, String label, String admin, OffsetDateTime now)`, `DeviceResponse reject(Long id, String admin, OffsetDateTime now)`, `DeviceResponse revoke(Long id, String admin, OffsetDateTime now)`, `void expireStale(OffsetDateTime now)`, `void flushVisits()`; константы `PENDING_TTL`, `IDLE_TTL`, `MAX_PENDING`. `DeviceRegistry(TrustedDeviceRepository)` с `load()`, `isTrusted(String hash)`. `DeviceResponse`, `DeviceListResponse` (поля — ниже).

- [ ] **Step 1: Написать падающий тест**

`src/test/java/com/vladoose/nir/gate/DeviceGateServiceTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.TooManyRequestsException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.service.DeviceGateService.GateResult;
import com.vladoose.nir.service.DeviceGateService.GateState;
import com.vladoose.nir.service.DeviceRegistry;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DeviceGateServiceTest {

    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private static final OffsetDateTime NOW = OffsetDateTime.now().truncatedTo(ChronoUnit.MICROS);
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    private TrustedDevice row(String token) {
        return repo.findByTokenHash(DeviceTokens.hash(token)).orElseThrow();
    }

    /** Ключ допущенного устройства: запрос в момент {@code at}, допуск минутой позже. */
    private String trustedAt(OffsetDateTime at) {
        GateResult r = gate.request(null, "Асель", IPHONE, "5.6.7.8", at);
        gate.approve(row(r.newToken()).getId(), null, "admin1", at.plusMinutes(1));
        return r.newToken();
    }

    @Test
    void requestIssuesKeyAndReadableCode() {
        GateResult r = gate.request(null, "  Асель ", IPHONE, "5.6.7.8", NOW);

        assertThat(r.state()).isEqualTo(GateState.PENDING);
        assertThat(r.code()).matches("[2-9A-HJKMNP-Z]{3}-[2-9A-HJKMNP-Z]{3}");
        assertThat(r.newToken()).hasSize(43);
        assertThat(r.expiresAt()).isAtSameInstantAs(NOW.plusMinutes(15));
        TrustedDevice d = row(r.newToken());
        assertThat(d.getStatus()).isEqualTo(DeviceStatus.PENDING);
        assertThat(d.getRequesterName()).isEqualTo("Асель");
        assertThat(d.getIp()).isEqualTo("5.6.7.8");
        assertThat(d.getTokenHash()).isNotEqualTo(r.newToken());     // в БД — только хеш
    }

    @Test
    void repeatedRequestFromWaitingDeviceKeepsItsCode() {
        GateResult first = gate.request(null, "Асель", null, null, NOW);
        long rows = repo.count();

        GateResult again = gate.request(first.newToken(), "Асель", null, null, NOW.plusMinutes(3));

        assertThat(again.state()).isEqualTo(GateState.PENDING);
        assertThat(again.code()).isEqualTo(first.code());
        assertThat(again.newToken()).isNull();
        assertThat(repo.count()).isEqualTo(rows);
    }

    @Test
    void requestFromTrustedDeviceIssuesNothing() {
        String token = trustedAt(NOW);

        GateResult r = gate.request(token, "Асель", null, null, NOW.plusMinutes(2));

        assertThat(r.state()).isEqualTo(GateState.TRUSTED);
        assertThat(r.newToken()).isNull();
    }

    @Test
    void requestAfterExpiryIssuesNewKey() {
        GateResult old = gate.request(null, "Асель", null, null, NOW.minusMinutes(16));

        GateResult fresh = gate.request(old.newToken(), "Асель", null, null, NOW);

        assertThat(fresh.newToken()).isNotNull().isNotEqualTo(old.newToken());
        assertThat(fresh.code()).isNotEqualTo(old.code());           // код старого ещё занят среди PENDING
    }

    @Test
    void nameIsRequiredAndBounded() {
        assertThatThrownBy(() -> gate.request(null, "   ", null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gate.request(null, null, null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> gate.request(null, "я".repeat(61), null, null, NOW)).isInstanceOf(BadRequestException.class);
        assertThat(gate.request(null, "я".repeat(60), null, null, NOW).state()).isEqualTo(GateState.PENDING);
    }

    @Test
    void waitingRequestsAreCappedAtTwenty() {
        for (long i = gate.pendingCount(NOW); i < DeviceGateService.MAX_PENDING; i++) {
            gate.request(null, "Бот " + i, null, null, NOW);
        }

        assertThatThrownBy(() -> gate.request(null, "Лишний", null, null, NOW)).isInstanceOf(TooManyRequestsException.class);
        // истёкшие запросы в лимит не входят
        assertThat(gate.request(null, "Позже", null, null, NOW.plusMinutes(16)).state()).isEqualTo(GateState.PENDING);
    }

    @Test
    void checkLetsInOnlyTrustedDevices() {
        GateResult waiting = gate.request(null, "Асель", null, null, NOW);
        assertThat(gate.isTrusted(waiting.newToken(), NOW)).isFalse();

        gate.approve(row(waiting.newToken()).getId(), null, "admin1", NOW.plusMinutes(1));
        assertThat(gate.isTrusted(waiting.newToken(), NOW.plusMinutes(2))).isTrue();   // сразу, без перезапуска

        assertThat(gate.isTrusted(null, NOW)).isFalse();
        assertThat(gate.isTrusted("", NOW)).isFalse();
        assertThat(gate.isTrusted("не-ключ", NOW)).isFalse();
        assertThat(gate.isTrusted("x".repeat(500), NOW)).isFalse();
    }

    @Test
    void approveDefaultsLabelToRequesterAndRecordsAdmin() {
        GateResult r = gate.request(null, "Асель", IPHONE, "5.6.7.8", NOW);

        DeviceResponse d = gate.approve(row(r.newToken()).getId(), "  ", "admin1", NOW.plusMinutes(1));

        assertThat(d.getStatus()).isEqualTo("TRUSTED");
        assertThat(d.getLabel()).isEqualTo("Асель");
        assertThat(d.getDecidedBy()).isEqualTo("admin1");
        assertThat(d.getDevice()).isEqualTo("iPhone · Safari");

        GateResult r2 = gate.request(null, "Марат", null, null, NOW);
        assertThat(gate.approve(row(r2.newToken()).getId(), " Ноутбук Марата ", "admin1", NOW).getLabel())
                .isEqualTo("Ноутбук Марата");
        GateResult r3 = gate.request(null, "Длинный", null, null, NOW);
        assertThatThrownBy(() -> gate.approve(row(r3.newToken()).getId(), "я".repeat(101), "admin1", NOW))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void rejectedAndRevokedDevicesStayOutside() {
        GateResult rejected = gate.request(null, "Чужой", null, null, NOW);
        gate.reject(row(rejected.newToken()).getId(), "admin1", NOW.plusMinutes(1));
        assertThat(gate.isTrusted(rejected.newToken(), NOW)).isFalse();
        assertThat(gate.status(rejected.newToken(), NOW).state()).isEqualTo(GateState.REJECTED);

        String token = trustedAt(NOW);
        gate.revoke(row(token).getId(), "admin1", NOW.plusMinutes(5));
        assertThat(gate.isTrusted(token, NOW.plusMinutes(6))).isFalse();              // сразу
        assertThat(gate.status(token, NOW).state()).isEqualTo(GateState.REVOKED);
    }

    @Test
    void decisionsOnlyFromTheRightState() {
        String token = trustedAt(NOW);
        Long trustedId = row(token).getId();
        assertThatThrownBy(() -> gate.approve(trustedId, null, "a", NOW)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> gate.reject(trustedId, "a", NOW)).isInstanceOf(ConflictException.class);

        GateResult waiting = gate.request(null, "Асель", null, null, NOW);
        assertThatThrownBy(() -> gate.revoke(row(waiting.newToken()).getId(), "a", NOW)).isInstanceOf(ConflictException.class);

        GateResult stale = gate.request(null, "Асель", null, null, NOW.minusMinutes(16));
        assertThatThrownBy(() -> gate.approve(row(stale.newToken()).getId(), null, "a", NOW)).isInstanceOf(ConflictException.class);

        assertThatThrownBy(() -> gate.approve(-1L, null, "a", NOW)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void statusFollowsTheLifecycle() {
        assertThat(gate.status(null, NOW).state()).isEqualTo(GateState.NONE);
        assertThat(gate.status("незнакомый", NOW).state()).isEqualTo(GateState.NONE);

        GateResult r = gate.request(null, "Асель", null, null, NOW);
        GateResult st = gate.status(r.newToken(), NOW.plusMinutes(1));
        assertThat(st.state()).isEqualTo(GateState.PENDING);
        assertThat(st.code()).isEqualTo(r.code());
        assertThat(gate.status(r.newToken(), NOW.plusMinutes(16)).state()).isEqualTo(GateState.EXPIRED);
    }

    @Test
    void listShowsPendingTrustedHistoryAndCurrentDevice() {
        gate.request(null, "Ждёт", null, null, NOW);
        String mine = trustedAt(NOW);
        GateResult refused = gate.request(null, "Отказ", null, null, NOW);
        gate.reject(row(refused.newToken()).getId(), "admin1", NOW);

        DeviceListResponse l = gate.list(mine, NOW.plusMinutes(2));

        assertThat(l.getPending()).extracting(DeviceResponse::getRequesterName).contains("Ждёт");
        assertThat(l.getTrusted()).filteredOn(DeviceResponse::isCurrent).singleElement()
                .satisfies(d -> assertThat(d.getLabel()).isEqualTo("Асель"));
        assertThat(l.getHistory()).extracting(DeviceResponse::getRequesterName).contains("Отказ");
        assertThat(l.getPending()).noneMatch(DeviceResponse::isCurrent);
    }

    @Test
    void visitsReachTheDatabaseInBatches() {
        String token = trustedAt(NOW);
        gate.isTrusted(token, NOW.plusMinutes(10));
        assertThat(row(token).getLastSeenAt()).isNull();                                 // пока только в памяти

        gate.flushVisits();

        assertThat(row(token).getLastSeenAt()).isAtSameInstantAs(NOW.plusMinutes(10));
    }

    @Test
    void expiryKeepsRecentlyVisitedDevices() {
        GateResult stale = gate.request(null, "Старый", null, null, NOW.minusMinutes(20));
        String idle = trustedAt(NOW.minusDays(91));
        String active = trustedAt(NOW.minusDays(91));
        gate.isTrusted(active, NOW.minusHours(1));                                      // визит — пока только в памяти

        gate.expireStale(NOW);

        assertThat(row(stale.newToken()).getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(row(idle).getStatus()).isEqualTo(DeviceStatus.EXPIRED);
        assertThat(gate.isTrusted(idle, NOW)).isFalse();
        TrustedDevice kept = row(active);
        assertThat(kept.getStatus()).isEqualTo(DeviceStatus.TRUSTED);
        assertThat(kept.getLastSeenAt()).isAtSameInstantAs(NOW.minusHours(1));          // визит записан до проверки простоя
        assertThat(gate.isTrusted(active, NOW)).isTrue();
    }

    @Test
    void registryRestoresTrustedDevicesAfterRestart() {
        String token = trustedAt(NOW);

        DeviceRegistry fresh = new DeviceRegistry(repo);                                // как после перезапуска
        fresh.load();

        assertThat(fresh.isTrusted(DeviceTokens.hash(token))).isTrue();
    }
}
```

- [ ] **Step 2: Убедиться, что тест падает**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.DeviceGateServiceTest'`
Expected: FAIL — компиляция: `cannot find symbol DeviceGateService / ConflictException / TooManyRequestsException / DeviceResponse`.

- [ ] **Step 3: Исключения 409 и 429**

`src/main/java/com/vladoose/nir/exception/ConflictException.java`:

```java
package com.vladoose.nir.exception;

/** 409: действие не подходит к текущему состоянию записи (например, допустить уже решённый запрос). */
public class ConflictException extends RuntimeException {
    public ConflictException(String message) {
        super(message);
    }
}
```

`src/main/java/com/vladoose/nir/exception/TooManyRequestsException.java`:

```java
package com.vladoose.nir.exception;

/** 429: сработал лимит (например, слишком много ожидающих запросов доступа). */
public class TooManyRequestsException extends RuntimeException {
    public TooManyRequestsException(String message) {
        super(message);
    }
}
```

В `src/main/java/com/vladoose/nir/exception/GlobalExceptionHandler.java` вставить **перед** строкой `    @ExceptionHandler(Exception.class)`:

```java
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiError> handleConflict(ConflictException ex) {
        ApiError error = ApiError.builder()
                .status(HttpStatus.CONFLICT.value())
                .message(ex.getMessage())
                .errors(null)
                .build();

        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }

    @ExceptionHandler(TooManyRequestsException.class)
    public ResponseEntity<ApiError> handleTooManyRequests(TooManyRequestsException ex) {
        ApiError error = ApiError.builder()
                .status(HttpStatus.TOO_MANY_REQUESTS.value())
                .message(ex.getMessage())
                .errors(null)
                .build();

        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(error);
    }

```

- [ ] **Step 4: DTO раздела «Устройства»**

`src/main/java/com/vladoose/nir/dto/response/DeviceResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Устройство или запрос доступа в разделе «Устройства» (спека device-gate §7). */
@Data
public class DeviceResponse {
    private Long id;
    private String status;
    private String code;               // «7K4-QM2»
    private String requesterName;
    private String label;
    private String device;             // «iPhone · Safari»
    private String ip;
    private OffsetDateTime requestedAt;
    private OffsetDateTime decidedAt;
    private String decidedBy;
    private OffsetDateTime lastSeenAt;
    private boolean current;           // ключ этого устройства пришёл в cookie запроса — «это устройство»
}
```

`src/main/java/com/vladoose/nir/dto/response/DeviceListResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Раздел «Устройства»: ожидающие запросы, допущенные, история (последние 50 решённых). */
@Data
public class DeviceListResponse {
    private List<DeviceResponse> pending = new ArrayList<>();
    private List<DeviceResponse> trusted = new ArrayList<>();
    private List<DeviceResponse> history = new ArrayList<>();
}
```

- [ ] **Step 5: Реестр в памяти**

`src/main/java/com/vladoose/nir/service/DeviceRegistry.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Допущенные устройства в памяти (спека device-gate §8): проверка калитки идёт на КАЖДЫЙ запрос к
 * ais.westmed.kz — в БД она не ходит. Бэкенд один, поэтому реестр и есть источник правды для проверки;
 * БД — для хранения и истории. Визиты копятся здесь и уходят в БД пачкой ({@link #drainVisits()}).
 */
@Component
public class DeviceRegistry {

    /** Отметка визита, ещё не записанная в БД; {@code null} — записывать нечего. */
    private record Visit(OffsetDateTime unsaved) {}

    private final ConcurrentHashMap<String, Visit> trusted = new ConcurrentHashMap<>();
    private final TrustedDeviceRepository repo;

    public DeviceRegistry(TrustedDeviceRepository repo) {
        this.repo = repo;
    }

    /** При старте — все допущенные из БД: перезапуск и деплой допуски не сбрасывают. */
    @EventListener(ApplicationReadyEvent.class)
    public void load() {
        Map<String, Visit> fresh = new HashMap<>();
        for (TrustedDevice d : repo.findByStatus(DeviceStatus.TRUSTED)) fresh.put(d.getTokenHash(), new Visit(null));
        trusted.clear();
        trusted.putAll(fresh);
    }

    public boolean isTrusted(String hash) {
        return hash != null && trusted.containsKey(hash);
    }

    public void add(String hash) {
        trusted.put(hash, new Visit(null));
    }

    public void remove(String hash) {
        trusted.remove(hash);
    }

    /** Визит — только в памяти; в БД уходит пачкой. */
    public void touch(String hash, OffsetDateTime now) {
        trusted.computeIfPresent(hash, (h, v) -> new Visit(now));
    }

    /** Забрать накопленные визиты и пометить их записанными (если за это время не было нового визита). */
    public Map<String, OffsetDateTime> drainVisits() {
        Map<String, OffsetDateTime> out = new HashMap<>();
        for (Map.Entry<String, Visit> e : trusted.entrySet()) {
            OffsetDateTime seen = e.getValue().unsaved();
            if (seen == null) continue;
            out.put(e.getKey(), seen);
            trusted.computeIfPresent(e.getKey(), (h, cur) -> seen.equals(cur.unsaved()) ? new Visit(null) : cur);
        }
        return out;
    }
}
```

- [ ] **Step 6: Запись в БД — отдельный бин**

`src/main/java/com/vladoose/nir/service/TrustedDeviceWriter.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Запись в {@code trusted_device} — каждый метод в своей транзакции. Отдельный бин, а не методы сервиса:
 * {@link DeviceGateService} трогает реестр в памяти только после возврата отсюда, то есть после коммита —
 * провалившийся коммит реестр не меняет. (Колбэк «после коммита» не подошёл бы ещё и потому, что
 * {@code @Transactional}-тесты не коммитят — тесты не увидели бы допуска.)
 */
@Service
public class TrustedDeviceWriter {

    private final TrustedDeviceRepository repo;

    public TrustedDeviceWriter(TrustedDeviceRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public TrustedDevice createPending(String tokenHash, String code, String name, String userAgent, String ip,
                                       OffsetDateTime now) {
        return repo.save(TrustedDevice.builder().tokenHash(tokenHash).status(DeviceStatus.PENDING).code(code)
                .requesterName(name).userAgent(userAgent).ip(ip).requestedAt(now).build());
    }

    /** @param staleBefore запросы, поданные не позже этого момента, считаются истёкшими */
    @Transactional
    public TrustedDevice approve(Long id, String label, String admin, OffsetDateTime now, OffsetDateTime staleBefore) {
        TrustedDevice d = pendingOrConflict(id, staleBefore);
        d.setStatus(DeviceStatus.TRUSTED);
        d.setLabel(label != null ? label : d.getRequesterName());
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public TrustedDevice reject(Long id, String admin, OffsetDateTime now, OffsetDateTime staleBefore) {
        TrustedDevice d = pendingOrConflict(id, staleBefore);
        d.setStatus(DeviceStatus.REJECTED);
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public TrustedDevice revoke(Long id, String admin, OffsetDateTime now) {
        TrustedDevice d = repo.findById(id).orElseThrow(() -> new NotFoundException("Устройство не найдено"));
        if (d.getStatus() != DeviceStatus.TRUSTED) throw new ConflictException("Устройство не допущено — отзывать нечего");
        d.setStatus(DeviceStatus.REVOKED);
        d.setDecidedAt(now);
        d.setDecidedBy(admin);
        return d;
    }

    @Transactional
    public int expirePending(OffsetDateTime staleBefore) {
        return repo.moveRequestedBefore(DeviceStatus.PENDING, DeviceStatus.EXPIRED, staleBefore);
    }

    /** @return хеши выпавших устройств — их надо убрать из реестра */
    @Transactional
    public List<String> expireIdle(OffsetDateTime idleBefore) {
        List<String> gone = new ArrayList<>();
        for (TrustedDevice d : repo.findByStatus(DeviceStatus.TRUSTED)) {
            OffsetDateTime last = latest(d.getLastSeenAt(), d.getDecidedAt());
            if (last == null || last.isBefore(idleBefore)) {
                d.setStatus(DeviceStatus.EXPIRED);
                gone.add(d.getTokenHash());
            }
        }
        return gone;
    }

    @Transactional
    public void saveVisits(Map<String, OffsetDateTime> visits) {
        visits.forEach((hash, seen) -> repo.markSeen(hash, seen, DeviceStatus.TRUSTED));
    }

    private TrustedDevice pendingOrConflict(Long id, OffsetDateTime staleBefore) {
        TrustedDevice d = repo.findById(id).orElseThrow(() -> new NotFoundException("Запрос не найден"));
        if (d.getStatus() != DeviceStatus.PENDING) throw new ConflictException("Запрос уже не ожидает решения");
        if (!d.getRequestedAt().isAfter(staleBefore)) {
            throw new ConflictException("Запрос истёк — попросите запросить доступ заново");
        }
        return d;
    }

    private static OffsetDateTime latest(OffsetDateTime a, OffsetDateTime b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.isAfter(b) ? a : b;
    }
}
```

- [ ] **Step 7: Сервис калитки**

`src/main/java/com/vladoose/nir/service/DeviceGateService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.entity.DeviceStatus;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.TooManyRequestsException;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.util.DeviceTokens;
import com.vladoose.nir.util.UserAgentSummary;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Калитка ais.westmed.kz: запрос доступа с устройства, решение админа, проверка на каждый запрос
 * (спека docs/superpowers/specs/2026-09-28-device-gate-design.md). Изменения БД — в {@link TrustedDeviceWriter}
 * (своя транзакция на метод); реестр в памяти обновляется после его возврата — то есть после коммита.
 */
@Service
public class DeviceGateService {

    public static final Duration PENDING_TTL = Duration.ofMinutes(15);
    public static final Duration IDLE_TTL = Duration.ofDays(90);
    public static final int MAX_PENDING = 20;
    public static final int NAME_MAX = 60;
    public static final int LABEL_MAX = 100;
    public static final int USER_AGENT_MAX = 300;
    /** Настоящий ключ — 43 символа; длиннее — мусор, не хешируем. */
    private static final int TOKEN_MAX = 100;

    /** Состояние устройства для страницы калитки: статусы БД плюс NONE — ключа нет или он незнаком. */
    public enum GateState { NONE, PENDING, TRUSTED, REJECTED, REVOKED, EXPIRED }

    /** {@code newToken != null} — контроллер выдаёт cookie с этим ключом. */
    public record GateResult(GateState state, String code, OffsetDateTime expiresAt, String newToken) {
        static GateResult of(GateState state) {
            return new GateResult(state, null, null, null);
        }
    }

    private final TrustedDeviceRepository repo;
    private final TrustedDeviceWriter writer;
    private final DeviceRegistry registry;

    public DeviceGateService(TrustedDeviceRepository repo, TrustedDeviceWriter writer, DeviceRegistry registry) {
        this.repo = repo;
        this.writer = writer;
        this.registry = registry;
    }

    // ---------- калитка ----------

    /** Проверка на каждый запрос к ais.westmed.kz (nginx auth_request) — только реестр в памяти, без БД. */
    public boolean isTrusted(String token, OffsetDateTime now) {
        if (!plausible(token)) return false;
        String hash = DeviceTokens.hash(token);
        if (!registry.isTrusted(hash)) return false;
        registry.touch(hash, now);
        return true;
    }

    public GateResult request(String token, String rawName, String userAgent, String ip, OffsetDateTime now) {
        String name = rawName == null ? "" : rawName.strip();
        if (name.isEmpty() || name.length() > NAME_MAX) {
            throw new BadRequestException("Укажите имя — до " + NAME_MAX + " символов");
        }
        Optional<TrustedDevice> current = find(token);
        if (current.isPresent()) {
            TrustedDevice d = current.get();
            if (d.getStatus() == DeviceStatus.TRUSTED) return GateResult.of(GateState.TRUSTED);
            if (isFreshPending(d, now)) return pending(d, null);      // перезагрузка страницы — тот же код
        }
        if (repo.countByStatusAndRequestedAtAfter(DeviceStatus.PENDING, now.minus(PENDING_TTL)) >= MAX_PENDING) {
            throw new TooManyRequestsException("Слишком много запросов доступа — попробуйте через несколько минут");
        }
        String newToken = DeviceTokens.newToken();
        TrustedDevice d = writer.createPending(DeviceTokens.hash(newToken), freeCode(), name,
                cut(userAgent, USER_AGENT_MAX), ip, now);
        return pending(d, newToken);
    }

    public GateResult status(String token, OffsetDateTime now) {
        return find(token).map(d -> switch (d.getStatus()) {
            case PENDING -> isFreshPending(d, now) ? pending(d, null) : GateResult.of(GateState.EXPIRED);
            case TRUSTED -> GateResult.of(GateState.TRUSTED);
            case REJECTED -> GateResult.of(GateState.REJECTED);
            case REVOKED -> GateResult.of(GateState.REVOKED);
            case EXPIRED -> GateResult.of(GateState.EXPIRED);
        }).orElse(GateResult.of(GateState.NONE));
    }

    // ---------- раздел «Устройства» ----------

    public DeviceListResponse list(String currentToken, OffsetDateTime now) {
        String currentHash = plausible(currentToken) ? DeviceTokens.hash(currentToken) : null;
        DeviceListResponse r = new DeviceListResponse();
        r.setPending(map(repo.findByStatusAndRequestedAtAfterOrderByRequestedAtDesc(
                DeviceStatus.PENDING, now.minus(PENDING_TTL)), currentHash));
        r.setTrusted(map(repo.findByStatusOrderByDecidedAtDesc(DeviceStatus.TRUSTED), currentHash));
        r.setHistory(map(repo.findTop50ByStatusInOrderByRequestedAtDesc(
                List.of(DeviceStatus.REJECTED, DeviceStatus.REVOKED, DeviceStatus.EXPIRED)), currentHash));
        return r;
    }

    public long pendingCount(OffsetDateTime now) {
        return repo.countByStatusAndRequestedAtAfter(DeviceStatus.PENDING, now.minus(PENDING_TTL));
    }

    public DeviceResponse approve(Long id, String rawLabel, String admin, OffsetDateTime now) {
        String label = rawLabel == null || rawLabel.isBlank() ? null : rawLabel.strip();
        if (label != null && label.length() > LABEL_MAX) {
            throw new BadRequestException("Подпись — до " + LABEL_MAX + " символов");
        }
        TrustedDevice d = writer.approve(id, label, admin, now, now.minus(PENDING_TTL));
        registry.add(d.getTokenHash());                  // writer уже закоммитил
        return toResponse(d, null);
    }

    public DeviceResponse reject(Long id, String admin, OffsetDateTime now) {
        return toResponse(writer.reject(id, admin, now, now.minus(PENDING_TTL)), null);
    }

    public DeviceResponse revoke(Long id, String admin, OffsetDateTime now) {
        TrustedDevice d = writer.revoke(id, admin, now);
        registry.remove(d.getTokenHash());               // со следующего же запроса — на калитку
        return toResponse(d, null);
    }

    // ---------- фоновая задача (спека §8) ----------

    public void expireStale(OffsetDateTime now) {
        flushVisits();          // сперва визиты: иначе недавно заходившее устройство выпало бы по старой отметке в БД
        writer.expirePending(now.minus(PENDING_TTL));
        writer.expireIdle(now.minus(IDLE_TTL)).forEach(registry::remove);
    }

    public void flushVisits() {
        Map<String, OffsetDateTime> visits = registry.drainVisits();
        if (!visits.isEmpty()) writer.saveVisits(visits);
    }

    // ---------- внутреннее ----------

    private static boolean plausible(String token) {
        return token != null && !token.isBlank() && token.length() <= TOKEN_MAX;
    }

    private Optional<TrustedDevice> find(String token) {
        return plausible(token) ? repo.findByTokenHash(DeviceTokens.hash(token)) : Optional.empty();
    }

    private String freeCode() {
        for (int i = 0; i < 10; i++) {
            String c = DeviceTokens.newCode();
            if (!repo.existsByCodeAndStatus(c, DeviceStatus.PENDING)) return c;
        }
        throw new IllegalStateException("Не удалось подобрать свободный код запроса");
    }

    private static boolean isFreshPending(TrustedDevice d, OffsetDateTime now) {
        return d.getStatus() == DeviceStatus.PENDING && d.getRequestedAt().isAfter(now.minus(PENDING_TTL));
    }

    private static GateResult pending(TrustedDevice d, String newToken) {
        return new GateResult(GateState.PENDING, DeviceTokens.display(d.getCode()),
                d.getRequestedAt().plus(PENDING_TTL), newToken);
    }

    private static List<DeviceResponse> map(List<TrustedDevice> list, String currentHash) {
        return list.stream().map(d -> toResponse(d, currentHash)).toList();
    }

    private static DeviceResponse toResponse(TrustedDevice d, String currentHash) {
        DeviceResponse r = new DeviceResponse();
        r.setId(d.getId());
        r.setStatus(d.getStatus().name());
        r.setCode(DeviceTokens.display(d.getCode()));
        r.setRequesterName(d.getRequesterName());
        r.setLabel(d.getLabel());
        r.setDevice(UserAgentSummary.describe(d.getUserAgent()));
        r.setIp(d.getIp());
        r.setRequestedAt(d.getRequestedAt());
        r.setDecidedAt(d.getDecidedAt());
        r.setDecidedBy(d.getDecidedBy());
        r.setLastSeenAt(d.getLastSeenAt());
        r.setCurrent(currentHash != null && currentHash.equals(d.getTokenHash()));
        return r;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 8: Тест проходит**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.DeviceGateServiceTest'`
Expected: PASS, 15 тестов.

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/vladoose/nir/exception/ConflictException.java \
        src/main/java/com/vladoose/nir/exception/TooManyRequestsException.java \
        src/main/java/com/vladoose/nir/exception/GlobalExceptionHandler.java \
        src/main/java/com/vladoose/nir/dto/response/DeviceResponse.java \
        src/main/java/com/vladoose/nir/dto/response/DeviceListResponse.java \
        src/main/java/com/vladoose/nir/service/DeviceRegistry.java \
        src/main/java/com/vladoose/nir/service/TrustedDeviceWriter.java \
        src/main/java/com/vladoose/nir/service/DeviceGateService.java \
        src/test/java/com/vladoose/nir/gate/DeviceGateServiceTest.java
git commit -m "feat(gate): логика калитки — запрос, допуск, отзыв, реестр в памяти, истечения

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: REST калитки и раздела, безопасность, фоновая задача

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/GateAccessRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/DeviceApproveRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/GateStateResponse.java`
- Create: `src/main/java/com/vladoose/nir/controller/GateController.java`
- Create: `src/main/java/com/vladoose/nir/controller/DeviceController.java`
- Create: `src/main/java/com/vladoose/nir/service/DeviceGateScheduler.java`
- Modify: `src/main/java/com/vladoose/nir/config/SecurityConfig.java` (строка `.requestMatchers("/api/auth/**").permitAll()`)
- Test: `src/test/java/com/vladoose/nir/gate/GateControllerTest.java`, `DeviceControllerTest.java`, `GateSecurityTest.java`

**Interfaces:**
- Consumes: `DeviceGateService` (Task 3), `GateCookie`, `ClientIp`, `DeviceTokens` (Task 2).
- Produces: `GET /api/gate/check` (204/401, `Cache-Control: no-store`), `POST /api/gate/request {name}` → `GateStateResponse {state, code, expiresAt}` + `Set-Cookie`, `GET /api/gate/status` → `GateStateResponse`; `GET /api/devices` → `DeviceListResponse`, `GET /api/devices/pending-count` → `{count}`, `POST /api/devices/{id}/approve {label?}` / `reject` / `revoke` → `DeviceResponse`. Методы контроллеров (для тестов): `GateController.check(String)`, `request(String, GateAccessRequest, String, HttpServletRequest)`, `status(String)`; `DeviceController.list(String)`, `pendingCount()`, `approve(Long, DeviceApproveRequest)`, `reject(Long)`, `revoke(Long)`.

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/vladoose/nir/gate/GateControllerTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.controller.GateController;
import com.vladoose.nir.dto.request.GateAccessRequest;
import com.vladoose.nir.dto.response.GateStateResponse;
import com.vladoose.nir.entity.TrustedDevice;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class GateControllerTest {

    @Autowired GateController controller;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private static final String EDGE = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36 Edg/126.0.2592.68";

    private ResponseEntity<GateStateResponse> ask(String token, String name) {
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.addHeader("X-Forwarded-For", "9.9.9.9, 5.6.7.8, 172.18.0.1");
        GateAccessRequest body = new GateAccessRequest();
        body.setName(name);
        return controller.request(token, body, EDGE, http);
    }

    private static String tokenFrom(ResponseEntity<?> r) {
        String c = r.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        return c.substring(c.indexOf('=') + 1, c.indexOf(';'));
    }

    @Test
    void requestHandsOutProtectedCookie() {
        ResponseEntity<GateStateResponse> r = ask(null, "Асель");

        assertThat(r.getStatusCode().value()).isEqualTo(200);
        assertThat(r.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(r.getHeaders().getFirst(HttpHeaders.SET_COOKIE))
                .startsWith("ais_device=")
                .contains("Path=/", "Max-Age=34560000", "Secure", "HttpOnly", "SameSite=Lax");
        assertThat(r.getBody().getState()).isEqualTo("PENDING");
        TrustedDevice d = repo.findByTokenHash(DeviceTokens.hash(tokenFrom(r))).orElseThrow();
        assertThat(d.getIp()).isEqualTo("5.6.7.8");       // адрес, дописанный nginx хоста, а не присланный клиентом
        assertThat(d.getUserAgent()).contains("Edg/");
    }

    @Test
    void repeatRequestDoesNotReplaceCookie() {
        String token = tokenFrom(ask(null, "Асель"));

        ResponseEntity<GateStateResponse> again = ask(token, "Асель");

        assertThat(again.getHeaders().containsKey(HttpHeaders.SET_COOKIE)).isFalse();
        assertThat(again.getBody().getState()).isEqualTo("PENDING");
    }

    @Test
    void checkAnswers204OnlyForTrustedDevice() {
        assertThat(controller.check(null).getStatusCode().value()).isEqualTo(401);
        String token = tokenFrom(ask(null, "Асель"));
        assertThat(controller.check(token).getStatusCode().value()).isEqualTo(401);

        Long id = repo.findByTokenHash(DeviceTokens.hash(token)).orElseThrow().getId();
        gate.approve(id, null, "admin1", OffsetDateTime.now());

        ResponseEntity<Void> ok = controller.check(token);
        assertThat(ok.getStatusCode().value()).isEqualTo(204);
        assertThat(ok.getHeaders().getCacheControl()).isEqualTo("no-store");
    }

    @Test
    void statusWithoutCookieIsNone() {
        assertThat(controller.status(null).getBody().getState()).isEqualTo("NONE");
    }
}
```

`src/test/java/com/vladoose/nir/gate/DeviceControllerTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.controller.DeviceController;
import com.vladoose.nir.dto.request.DeviceApproveRequest;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class DeviceControllerTest {

    @Autowired DeviceController controller;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    private Long waiting(String name) {
        DeviceGateService.GateResult r = gate.request(null, name, null, "5.6.7.8", OffsetDateTime.now());
        return repo.findByTokenHash(DeviceTokens.hash(r.newToken())).orElseThrow().getId();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorCannotSeeOrDecide() {
        Long id = waiting("Асель");
        assertThatThrownBy(() -> controller.list(null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.pendingCount()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.approve(id, null)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.revoke(id)).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminApprovesWithLabelAndSeesCount() {
        long before = controller.pendingCount().get("count");
        Long id = waiting("Асель");
        assertThat(controller.pendingCount().get("count")).isEqualTo(before + 1);

        DeviceApproveRequest body = new DeviceApproveRequest();
        body.setLabel("Телефон Асель");
        DeviceResponse d = controller.approve(id, body);

        assertThat(d.getStatus()).isEqualTo("TRUSTED");
        assertThat(d.getLabel()).isEqualTo("Телефон Асель");
        assertThat(d.getDecidedBy()).isEqualTo("admin1");
        assertThat(controller.pendingCount().get("count")).isEqualTo(before);
    }

    @Test
    @WithMockUser(username = "admin1", roles = "ADMIN")
    void adminRejectsAndRevokes() {
        assertThat(controller.reject(waiting("Чужой")).getStatus()).isEqualTo("REJECTED");

        Long id = waiting("Асель");
        controller.approve(id, null);
        assertThat(controller.revoke(id).getStatus()).isEqualTo("REVOKED");
    }
}
```

`src/test/java/com/vladoose/nir/gate/GateSecurityTest.java`:

```java
package com.vladoose.nir.gate;

import com.vladoose.nir.repository.TrustedDeviceRepository;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.DeviceTokens;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** HTTP-правила калитки: прямые вызовы контроллера фильтры Spring Security не проходят. */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class GateSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired DeviceGateService gate;
    @Autowired TrustedDeviceRepository repo;

    @Test
    void gateIsReachableWithoutLoggingIn() throws Exception {
        DeviceGateService.GateResult r = gate.request(null, "Асель", null, null, OffsetDateTime.now());
        Long id = repo.findByTokenHash(DeviceTokens.hash(r.newToken())).orElseThrow().getId();
        gate.approve(id, null, "admin1", OffsetDateTime.now());

        // 204 без входа в АИС: будь /api/gate/check закрыт, nginx получал бы 401 от Spring Security и не пускал никого
        mvc.perform(get("/api/gate/check").cookie(new Cookie("ais_device", r.newToken())))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/gate/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("NONE"));
        mvc.perform(post("/api/gate/request").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Марат\"}"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("ais_device"))
                .andExpect(cookie().httpOnly("ais_device", true))
                .andExpect(cookie().secure("ais_device", true))
                .andExpect(cookie().sameSite("ais_device", "Lax"))
                .andExpect(jsonPath("$.state").value("PENDING"));
    }

    @Test
    void blankNameIsBadRequest() throws Exception {
        mvc.perform(post("/api/gate/request").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"  \"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void devicesNeedLogin() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void devicesNeedAdmin() throws Exception {
        mvc.perform(get("/api/devices")).andExpect(status().isForbidden());
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.GateControllerTest' --tests 'com.vladoose.nir.gate.DeviceControllerTest' --tests 'com.vladoose.nir.gate.GateSecurityTest'`
Expected: FAIL — компиляция: `cannot find symbol GateController / DeviceController / GateAccessRequest`.

- [ ] **Step 3: DTO запросов и ответа калитки**

`src/main/java/com/vladoose/nir/dto/request/GateAccessRequest.java`:

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

/** «Запросить доступ» на калитке. Имя проверяет сервис (1–60 символов). */
@Data
public class GateAccessRequest {
    private String name;
}
```

`src/main/java/com/vladoose/nir/dto/request/DeviceApproveRequest.java`:

```java
package com.vladoose.nir.dto.request;

import lombok.Data;

/** «Допустить»: подпись устройства; пусто — берётся имя из запроса. */
@Data
public class DeviceApproveRequest {
    private String label;
}
```

`src/main/java/com/vladoose/nir/dto/response/GateStateResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/** Состояние устройства для страницы калитки (спека device-gate §7). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GateStateResponse {
    private String state;              // NONE | PENDING | TRUSTED | REJECTED | REVOKED | EXPIRED
    private String code;               // «7K4-QM2» — только для PENDING
    private OffsetDateTime expiresAt;  // только для PENDING
}
```

- [ ] **Step 4: Контроллеры**

`src/main/java/com/vladoose/nir/controller/GateController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.GateAccessRequest;
import com.vladoose.nir.dto.response.GateStateResponse;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.ClientIp;
import com.vladoose.nir.util.GateCookie;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;

/**
 * Калитка ais.westmed.kz (спека device-gate §7): открыта без входа в АИС — её зовёт nginx хоста
 * (подзапрос auth_request) и страница /gate/ недопущенного устройства.
 */
@RestController
@RequestMapping("/api/gate")
public class GateController {

    private final DeviceGateService gate;

    public GateController(DeviceGateService gate) {
        this.gate = gate;
    }

    /** Подзапрос nginx на каждый запрос к ais.westmed.kz: 204 — пускать, 401 — на калитку. Без БД. */
    @GetMapping("/check")
    public ResponseEntity<Void> check(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        HttpStatus s = gate.isTrusted(token, OffsetDateTime.now()) ? HttpStatus.NO_CONTENT : HttpStatus.UNAUTHORIZED;
        return ResponseEntity.status(s).cacheControl(CacheControl.noStore()).build();
    }

    @PostMapping("/request")
    public ResponseEntity<GateStateResponse> request(@CookieValue(name = GateCookie.NAME, required = false) String token,
                                                     @RequestBody(required = false) GateAccessRequest body,
                                                     @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent,
                                                     HttpServletRequest http) {
        DeviceGateService.GateResult r = gate.request(token, body == null ? null : body.getName(), userAgent,
                ClientIp.of(http), OffsetDateTime.now());
        ResponseEntity.BodyBuilder b = ResponseEntity.ok().cacheControl(CacheControl.noStore());
        if (r.newToken() != null) b.header(HttpHeaders.SET_COOKIE, GateCookie.of(r.newToken()).toString());
        return b.body(toResponse(r));
    }

    @GetMapping("/status")
    public ResponseEntity<GateStateResponse> status(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(toResponse(gate.status(token, OffsetDateTime.now())));
    }

    private static GateStateResponse toResponse(DeviceGateService.GateResult r) {
        return new GateStateResponse(r.state().name(), r.code(), r.expiresAt());
    }
}
```

`src/main/java/com/vladoose/nir/controller/DeviceController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.DeviceApproveRequest;
import com.vladoose.nir.dto.response.DeviceListResponse;
import com.vladoose.nir.dto.response.DeviceResponse;
import com.vladoose.nir.service.DeviceGateService;
import com.vladoose.nir.util.GateCookie;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.Map;

/** «Устройства» — кто может открыть ais.westmed.kz (спека device-gate §7, §11). Только ADMIN. */
@RestController
@RequestMapping("/api/devices")
@PreAuthorize("hasRole('ADMIN')")
public class DeviceController {

    private final DeviceGateService gate;

    public DeviceController(DeviceGateService gate) {
        this.gate = gate;
    }

    /** Cookie калитки в этом же запросе — чтобы пометить «это устройство». */
    @GetMapping
    public DeviceListResponse list(@CookieValue(name = GateCookie.NAME, required = false) String token) {
        return gate.list(token, OffsetDateTime.now());
    }

    @GetMapping("/pending-count")
    public Map<String, Long> pendingCount() {
        return Map.of("count", gate.pendingCount(OffsetDateTime.now()));
    }

    @PostMapping("/{id}/approve")
    public DeviceResponse approve(@PathVariable Long id, @RequestBody(required = false) DeviceApproveRequest body) {
        return gate.approve(id, body == null ? null : body.getLabel(), currentUser(), OffsetDateTime.now());
    }

    @PostMapping("/{id}/reject")
    public DeviceResponse reject(@PathVariable Long id) {
        return gate.reject(id, currentUser(), OffsetDateTime.now());
    }

    @PostMapping("/{id}/revoke")
    public DeviceResponse revoke(@PathVariable Long id) {
        return gate.revoke(id, currentUser(), OffsetDateTime.now());
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

- [ ] **Step 5: Открыть калитку в Spring Security**

В `src/main/java/com/vladoose/nir/config/SecurityConfig.java` заменить:

```java
                        .requestMatchers("/api/auth/**").permitAll()
```

на:

```java
                        .requestMatchers("/api/auth/**").permitAll()
                        // калитка ais.westmed.kz: её зовут nginx (auth_request) и недопущенное устройство — до входа в АИС
                        .requestMatchers("/api/gate/**").permitAll()
```

- [ ] **Step 6: Фоновая задача**

`src/main/java/com/vladoose/nir/service/DeviceGateScheduler.java`:

```java
package com.vladoose.nir.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Раз в 5 минут: визиты из памяти — в БД, истёкшие запросы и устройства без визитов 90 дней — в EXPIRED
 * (спека device-gate §8). Задача короткая — общий пул @Scheduled подходит. Рынок не нужен: таблица общая.
 */
@Component
public class DeviceGateScheduler {

    private static final Logger log = LoggerFactory.getLogger(DeviceGateScheduler.class);

    private final DeviceGateService gate;

    public DeviceGateScheduler(DeviceGateService gate) {
        this.gate = gate;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void tick() {
        try {
            gate.expireStale(OffsetDateTime.now());
        } catch (Exception e) {
            log.warn("калитка: фоновая задача не прошла: {}", e.toString());
        }
    }
}
```

- [ ] **Step 7: Тесты проходят**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.gate.*'`
Expected: PASS — все тесты пакета `gate` (4 + 7 + 15 + 4 + 3 + 4 = 37).

- [ ] **Step 8: Прогон мутациями — тесты должны уметь падать**

Каждую мутацию — **по одной** (CLAUDE.md §14): внести, прогнать указанный тест, убедиться в падении, откатить `git checkout -- <файл>`.

| # | Файл | Мутация | Тест, который обязан упасть |
|---|---|---|---|
| M1 | `SecurityConfig.java` | удалить строку `.requestMatchers("/api/gate/**").permitAll()` | `GateSecurityTest.gateIsReachableWithoutLoggingIn` (401 вместо 204) |
| M2 | `DeviceGateService.java` | удалить строку `registry.add(d.getTokenHash());` | `DeviceGateServiceTest.checkLetsInOnlyTrustedDevices` |
| M3 | `DeviceGateService.java` | удалить строку `registry.remove(d.getTokenHash());` в `revoke` | `DeviceGateServiceTest.rejectedAndRevokedDevicesStayOutside` |
| M4 | `DeviceGateService.java` | удалить вызов `flushVisits();` в `expireStale` | `DeviceGateServiceTest.expiryKeepsRecentlyVisitedDevices` |
| M5 | `GateController.java` | удалить строку `if (r.newToken() != null) b.header(...)` | `GateControllerTest.requestHandsOutProtectedCookie` |

Пример для M2:

```bash
perl -0pi -e 's/\n\s*registry\.add\(d\.getTokenHash\(\)\);[^\n]*//' src/main/java/com/vladoose/nir/service/DeviceGateService.java
./gradlew test --tests 'com.vladoose.nir.gate.DeviceGateServiceTest.checkLetsInOnlyTrustedDevices'   # ожидается FAIL
git checkout -- src/main/java/com/vladoose/nir/service/DeviceGateService.java
```

Если какая-то мутация НЕ роняет свой тест — тест дефектен: чинить тест, а не двигаться дальше.

- [ ] **Step 9: Полный гейт бэкенда**

Run (sandbox off): `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL, 0 падений (было 516, стало 553).

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/GateAccessRequest.java \
        src/main/java/com/vladoose/nir/dto/request/DeviceApproveRequest.java \
        src/main/java/com/vladoose/nir/dto/response/GateStateResponse.java \
        src/main/java/com/vladoose/nir/controller/GateController.java \
        src/main/java/com/vladoose/nir/controller/DeviceController.java \
        src/main/java/com/vladoose/nir/service/DeviceGateScheduler.java \
        src/main/java/com/vladoose/nir/config/SecurityConfig.java \
        src/test/java/com/vladoose/nir/gate/GateControllerTest.java \
        src/test/java/com/vladoose/nir/gate/DeviceControllerTest.java \
        src/test/java/com/vladoose/nir/gate/GateSecurityTest.java
git commit -m "feat(gate): REST калитки и раздела «Устройства», открытый /api/gate, фоновая задача

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Страница калитки и перехватчик

**Files:**
- Create: `frontend/public/gate/index.html`
- Modify: `frontend/src/app/interceptors/auth.interceptor.ts`

**Interfaces:**
- Consumes: `GET /api/gate/status`, `POST /api/gate/request` (Task 4); ответ калитки для API — `401` + `X-AIS-Gate: device` (Task 7, nginx).
- Produces: страница `/gate/` (в сборке — `dist/nir-frontend/browser/gate/index.html`).

- [ ] **Step 1: Страница калитки**

`frontend/public/gate/index.html`:

```html
<!doctype html>
<html lang="ru">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="robots" content="noindex, nofollow">
<title>Служебный вход</title>
<!-- встроенная иконка: иначе браузер сам попросит /favicon.ico, а этот запрос идёт через проверку устройства -->
<link rel="icon" href="data:,">
<script>
  // тема как в приложении: выбор пользователя из localStorage (тот же origin), иначе системная
  try { var t = localStorage.getItem('ais.theme'); if (t === 'dark' || t === 'light') document.documentElement.setAttribute('data-theme', t); } catch (e) {}
</script>
<style>
  :root { --app-bg:#f7f7f8; --surface:#ffffff; --border:#e5e7eb; --text:#111827; --text-muted:#6b7280;
          --accent:#1a56db; --accent-hover:#1e40af; --accent-contrast:#ffffff; --danger-text:#991b1b;
          --shadow:0 2px 8px rgba(0,0,0,.08); color-scheme: light; }
  @media (prefers-color-scheme: dark) {
    :root:not([data-theme="light"]) { --app-bg:#1f1e1d; --surface:#292827; --border:rgba(255,255,255,.11);
      --text:#f4f2ee; --text-muted:#a8a29a; --accent:#6b93ff; --accent-hover:#8aa9ff; --accent-contrast:#ffffff;
      --danger-text:#f87171; --shadow:0 2px 10px rgba(0,0,0,.45); color-scheme: dark; }
  }
  :root[data-theme="dark"] { --app-bg:#1f1e1d; --surface:#292827; --border:rgba(255,255,255,.11);
      --text:#f4f2ee; --text-muted:#a8a29a; --accent:#6b93ff; --accent-hover:#8aa9ff; --accent-contrast:#ffffff;
      --danger-text:#f87171; --shadow:0 2px 10px rgba(0,0,0,.45); color-scheme: dark; }
  * { box-sizing: border-box; }
  html, body { margin: 0; min-height: 100%; }
  body { background: var(--app-bg); color: var(--text); font: 15px/1.5 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
         display: flex; align-items: center; justify-content: center; min-height: 100vh; padding: 16px; }
  .box { width: 100%; max-width: 400px; background: var(--surface); border: 1px solid var(--border); border-radius: 14px;
         box-shadow: var(--shadow); padding: 28px 24px; }
  h1 { margin: 0 0 8px; font-size: 20px; }
  p { margin: 0 0 14px; }
  .muted { color: var(--text-muted); font-size: 14px; }
  label { display: block; font-size: 13px; color: var(--text-muted); margin-bottom: 6px; }
  input { width: 100%; min-height: 44px; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px;
          background: var(--surface); color: var(--text); font: inherit; font-size: 16px; }
  input:focus { outline: 2px solid var(--accent); outline-offset: 1px; }
  button { width: 100%; min-height: 44px; margin-top: 12px; border: 0; border-radius: 8px; background: var(--accent);
           color: var(--accent-contrast); font: inherit; font-weight: 600; cursor: pointer; }
  button:hover:not(:disabled) { background: var(--accent-hover); }
  button:disabled { opacity: .6; cursor: default; }
  .code { font: 700 34px/1.2 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: 3px;
          text-align: center; padding: 14px 0; margin: 6px 0 14px; border: 1px dashed var(--border); border-radius: 10px; }
  .err { color: var(--danger-text); font-size: 14px; margin: 10px 0 0; }
  .small { font-size: 13px; }
</style>
</head>
<body>
<main class="box">
  <h1>Служебный вход</h1>

  <section id="s-form">
    <p class="muted">Это устройство ещё не допущено. Представьтесь и запросите доступ — администратор подтвердит.</p>
    <form id="f" novalidate>
      <label for="name">Как вас зовут</label>
      <input id="name" maxlength="60" autocomplete="name" required>
      <button type="submit" id="btn">Запросить доступ</button>
    </form>
    <p class="err" id="err" hidden></p>
  </section>

  <section id="s-wait" hidden aria-live="polite">
    <p class="muted">Сообщите этот код администратору:</p>
    <div class="code" id="code">—</div>
    <p class="muted">Страница откроется сама, когда доступ подтвердят. Код действует 15 минут.</p>
    <p class="muted small" id="net" hidden>Нет связи — повторяю…</p>
  </section>

  <section id="s-end" hidden aria-live="polite">
    <p id="end-text"></p>
    <button type="button" id="again">Запросить снова</button>
  </section>
</main>
<script>
(function () {
  var $ = function (id) { return document.getElementById(id); };
  var timer = null;

  function show(id) { ['s-form', 's-wait', 's-end'].forEach(function (s) { $(s).hidden = s !== id; }); }
  function err(msg) { $('err').textContent = msg || ''; $('err').hidden = !msg; }
  function stop() { if (timer) { clearTimeout(timer); timer = null; } }
  function schedule() { stop(); timer = setTimeout(poll, 3000); }
  function end(text) { stop(); $('end-text').textContent = text; show('s-end'); }

  function apply(st) {
    if (st.state === 'TRUSTED') { stop(); location.replace('/'); return; }
    if (st.state === 'PENDING') { $('code').textContent = st.code; show('s-wait'); schedule(); return; }
    if (st.state === 'REJECTED') { end('Запрос отклонён.'); return; }
    if (st.state === 'EXPIRED') { end('Запрос истёк — его не подтвердили за 15 минут.'); return; }
    if (st.state === 'REVOKED') { end('Доступ с этого устройства отозван.'); return; }
    stop(); show('s-form');
  }

  function poll() {
    fetch('/api/gate/status', { cache: 'no-store', credentials: 'same-origin' })
      .then(function (r) { if (!r.ok) throw new Error(String(r.status)); return r.json(); })
      .then(function (st) { $('net').hidden = true; apply(st); })
      .catch(function () { $('net').hidden = false; schedule(); });
  }

  $('f').addEventListener('submit', function (e) {
    e.preventDefault();
    var name = $('name').value.trim();
    if (!name) { err('Введите имя'); return; }
    err('');
    $('btn').disabled = true;
    fetch('/api/gate/request', {
      method: 'POST', credentials: 'same-origin', cache: 'no-store',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name })
    })
      .then(function (r) {
        if (r.status === 429) throw new Error('Слишком много запросов — попробуйте через несколько минут.');
        if (!r.ok) {
          return r.json().catch(function () { return {}; })
            .then(function (b) { throw new Error(b.message || 'Не удалось отправить запрос. Попробуйте ещё раз.'); });
        }
        return r.json();
      })
      .then(apply)
      .catch(function (x) { err(x.message || 'Нет связи. Попробуйте ещё раз.'); })
      .then(function () { $('btn').disabled = false; });
  });

  $('again').addEventListener('click', function () { show('s-form'); $('name').focus(); });

  // при открытии: уже допущено — сразу в систему; есть ожидающий запрос — сразу его код
  fetch('/api/gate/status', { cache: 'no-store', credentials: 'same-origin' })
    .then(function (r) { return r.ok ? r.json() : { state: 'NONE' }; })
    .then(apply)
    .catch(function () { show('s-form'); });
})();
</script>
</body>
</html>
```

- [ ] **Step 2: Перехватчик — пометка калитки**

В `frontend/src/app/interceptors/auth.interceptor.ts` заменить блок `catchError`:

```ts
    catchError((err: HttpErrorResponse) => {
      if (err.status === 401 && !req.url.includes('/api/auth/')) {
        auth.logout().subscribe();
        router.navigate(['/login']);
      }
      return throwError(() => err);
    })
```

на:

```ts
    catchError((err: HttpErrorResponse) => {
      // калитка ais.westmed.kz: устройство отозвано или выпало — на страницу калитки полной навигацией.
      // logout не зовём: он тоже упёрся бы в калитку. Прочие 401 — «сессия истекла», как раньше.
      if (err.status === 401 && err.headers?.get('X-AIS-Gate') === 'device') {
        window.location.assign('/gate/');
        return throwError(() => err);
      }
      if (err.status === 401 && !req.url.includes('/api/auth/')) {
        auth.logout().subscribe();
        router.navigate(['/login']);
      }
      return throwError(() => err);
    })
```

- [ ] **Step 3: Сборка**

Run: `(cd frontend && npm run build 2>&1 | tail -5) && ls frontend/dist/nir-frontend/browser/gate/`
Expected: `Application bundle generation complete`; в `dist/…/browser/gate/` — `index.html`. Предупреждение о бюджете начального бандла было и до этой работы (1,26 МБ).

- [ ] **Step 4: Живая проверка калитки (локально, Playwright)**

Запуск: бэкенд `./gradlew bootRun` (sandbox off, фон), фронт `cd frontend && npm start` (фон). В Playwright:
1. `http://localhost:4200/gate/index.html` → форма «Служебный вход».
2. Пустое имя → «Введите имя». Имя «Проверка» → «Запросить доступ» → крупный код `XXX-XXX`.
3. Допустить через API бэкенда под админом (минуя калитку, как запасная консоль):
   ```bash
   A=$SCRATCH/admin.jar
   curl -s -c $A -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' http://localhost:8080/api/auth/login >/dev/null
   ID=$(curl -s -b $A http://localhost:8080/api/devices | python3 -c 'import json,sys; print(json.load(sys.stdin)["pending"][0]["id"])')
   curl -s -b $A -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/devices/$ID/approve
   ```
   В течение 3 секунд страница сама уходит на `/` (приложение).
4. Отозвать (`…/api/devices/$ID/revoke`), открыть `http://localhost:4200/gate/index.html` → «Доступ с этого устройства отозван.» → «Запросить снова» → форма.
5. Ширина 390 и тёмная тема (`localStorage['ais.theme']='dark'`, перезагрузка) — снимки в `.playwright-mcp/`.

(Перехват `X-AIS-Gate` проверяется на репетиции nginx — Task 7: локально калитку никто не включает.)

- [ ] **Step 5: Commit**

```bash
git add frontend/public/gate/index.html frontend/src/app/interceptors/auth.interceptor.ts
git commit -m "feat(gate): страница калитки и переход на неё по X-AIS-Gate

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Раздел «Устройства» в АИС

**Files:**
- Create: `frontend/src/app/pages/devices/devices.component.ts`
- Modify: `frontend/src/app/services/api.service.ts` (после метода `runLeadSync()`)
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/app.config.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: `/api/devices`, `/api/devices/pending-count`, `/api/devices/{id}/approve|reject|revoke` (Task 4).
- Produces: маршрут `/devices` (только ADMIN), пункт «Устройства» в «Система» со счётчиком ожидающих; `ApiService.getDevices()`, `getDevicePendingCount()`, `approveDevice(id, label)`, `rejectDevice(id)`, `revokeDevice(id)`.

- [ ] **Step 1: Методы API**

В `frontend/src/app/services/api.service.ts` сразу после метода

```ts
  runLeadSync(): Observable<any> {
    return this.http.post<any>(`${this.base}/leads/sync`, {});
  }
```

вставить:

```ts

  // === Калитка ais.westmed.kz: устройства (только ADMIN) ===
  getDevices(): Observable<any> {
    return this.http.get<any>(`${this.base}/devices`);
  }
  getDevicePendingCount(): Observable<{ count: number }> {
    return this.http.get<{ count: number }>(`${this.base}/devices/pending-count`);
  }
  approveDevice(id: number, label: string | null): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/approve`, { label });
  }
  rejectDevice(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/reject`, {});
  }
  revokeDevice(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/devices/${id}/revoke`, {});
  }
```

- [ ] **Step 2: Страница**

`frontend/src/app/pages/devices/devices.component.ts`:

```ts
import { Component, ChangeDetectorRef, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Observable } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { relativeTime, fullDateTime } from '../../shared/relative-time';

/**
 * «Устройства» — калитка ais.westmed.kz: запросы доступа, допущенные устройства, история.
 * Спека: docs/superpowers/specs/2026-09-28-device-gate-design.md §11.
 */
@Component({
  selector: 'app-devices',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="page-head">
      <div>
        <h2>Устройства</h2>
        <p class="subtitle">Кто может открыть ais.westmed.kz. Допускайте только код, который вам назвал знакомый человек.</p>
      </div>
    </div>

    <div class="error-banner" *ngIf="error">{{ error }}</div>

    <section class="block">
      <h3>Запросы доступа <span class="counter" *ngIf="data?.pending?.length">{{ data.pending.length }}</span></h3>
      <p class="empty" *ngIf="data && !data.pending.length">
        Новых запросов нет. Когда кто-то запросит доступ на ais.westmed.kz, запрос появится здесь через несколько секунд.
      </p>
      <div class="req" *ngFor="let d of data?.pending; trackBy: byId">
        <div class="req-code" [attr.aria-label]="'Код ' + d.code">{{ d.code }}</div>
        <div class="req-info">
          <div class="req-name">{{ d.requesterName }}</div>
          <div class="muted">{{ d.device }} · {{ d.ip || 'IP неизвестен' }} · <span [title]="full(d.requestedAt)">{{ ago(d.requestedAt) }}</span></div>
        </div>
        <div class="req-act">
          <input [(ngModel)]="labels[d.id]" [placeholder]="d.requesterName" maxlength="100" aria-label="Подпись устройства">
          <button type="button" class="btn btn-primary" [disabled]="busy" (click)="approve(d)">Допустить</button>
          <button type="button" class="btn btn-cancel" [disabled]="busy" (click)="reject(d)">Отклонить</button>
        </div>
      </div>
    </section>

    <section class="block">
      <h3>Допущенные</h3>
      <p class="empty" *ngIf="data && !data.trusted.length">Допущенных устройств пока нет.</p>
      <div class="dev" *ngFor="let d of data?.trusted; trackBy: byId" [class.is-current]="d.current">
        <div class="dev-main">
          <div class="dev-name">{{ d.label || d.requesterName }} <span class="badge badge-current" *ngIf="d.current">это устройство</span></div>
          <div class="muted">{{ d.device }} · допустил {{ d.decidedBy || '—' }} <span [title]="full(d.decidedAt)">{{ ago(d.decidedAt) }}</span></div>
          <div class="muted">Последний визит: {{ d.lastSeenAt ? ago(d.lastSeenAt) : 'ещё не заходило' }}</div>
        </div>
        <div class="dev-act">
          <button type="button" class="btn btn-line" *ngIf="confirmId !== d.id" [disabled]="busy" (click)="confirmId = d.id">Отозвать</button>
          <div class="confirm" *ngIf="confirmId === d.id">
            <span>{{ d.current ? 'Вы потеряете доступ с этого браузера. Вернуться — через Tailscale или SSH-туннель.' : 'Устройство сразу потеряет доступ.' }}</span>
            <button type="button" class="btn btn-danger" [disabled]="busy" (click)="revoke(d)">Отозвать</button>
            <button type="button" class="btn btn-cancel" (click)="confirmId = null">Отмена</button>
          </div>
        </div>
      </div>
    </section>

    <section class="block" *ngIf="data?.history?.length">
      <button type="button" class="toggle" (click)="showHistory = !showHistory" [attr.aria-expanded]="showHistory">
        История ({{ data.history.length }}) {{ showHistory ? '▴' : '▾' }}
      </button>
      <div class="hist" *ngIf="showHistory">
        <div class="hist-row" *ngFor="let d of data.history; trackBy: byId">
          <span class="badge" [attr.data-status]="d.status">{{ statusLabel(d.status) }}</span>
          <span>{{ d.label || d.requesterName }}</span>
          <span class="muted">{{ d.device }} · {{ ago(d.decidedAt || d.requestedAt) }}{{ d.decidedBy ? ' · ' + d.decidedBy : '' }}</span>
        </div>
      </div>
    </section>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .block { margin-top: 20px; }
    .block h3 { margin: 0 0 10px; font-size: 16px; display: flex; align-items: center; gap: 8px; }
    .muted { color: var(--text-muted); font-size: 13px; }
    .req { display: grid; grid-template-columns: auto 1fr auto; gap: 14px; align-items: center; margin-bottom: 8px; padding: 12px 14px;
           border: 1px solid var(--border); border-left: 3px solid var(--accent); border-radius: 10px;
           background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .req-code { font: 700 22px/1 ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; letter-spacing: 2px; color: var(--text); }
    .req-name { font-weight: 600; }
    .req-act { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .req-act input { width: 180px; padding: 7px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .dev { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 8px; padding: 12px 14px;
           border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .dev.is-current { border-color: var(--accent); }
    .dev-name { font-weight: 600; display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .badge-current { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .confirm { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--danger-text); }
    .toggle { background: none; border: none; color: var(--accent); cursor: pointer; font: inherit; padding: 4px 0; }
    .hist { display: flex; flex-direction: column; gap: 6px; margin-top: 8px; }
    .hist-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 14px; }
    .badge[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .badge[data-status="REVOKED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .badge[data-status="EXPIRED"] { background: color-mix(in srgb, var(--text-muted) 15%, transparent); color: var(--text-muted); }
    @media (max-width: 900px) {
      .req { grid-template-columns: 1fr; gap: 8px; }
      .req-act, .dev-act { width: 100%; }
      .req-act input { width: 100%; font-size: 16px; }
    }
  `]
})
export class DevicesComponent implements OnInit, OnDestroy {
  data: any = null;
  labels: Record<number, string> = {};
  confirmId: number | null = null;
  showHistory = false;
  busy = false;
  error = '';
  private timer: any = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.load();
    this.timer = setInterval(() => this.load(true), 5000);      // запрос появляется, пока сотрудник ждёт у калитки
  }

  ngOnDestroy() {
    clearInterval(this.timer);
  }

  load(quiet = false) {
    this.api.getDevices().subscribe({
      next: d => { this.data = d; this.error = ''; this.cdr.detectChanges(); },
      error: e => {
        if (!quiet) { this.error = e.error?.message || 'Не удалось загрузить устройства'; this.cdr.detectChanges(); }
      },
    });
  }

  approve(d: any) {
    const label = (this.labels[d.id] || '').trim();
    this.act(this.api.approveDevice(d.id, label || null), `Устройство «${label || d.requesterName}» допущено`);
  }

  reject(d: any) {
    this.act(this.api.rejectDevice(d.id), 'Запрос отклонён');
  }

  revoke(d: any) {
    this.confirmId = null;
    this.act(this.api.revokeDevice(d.id), 'Доступ отозван');
  }

  private act(obs: Observable<any>, ok: string) {
    this.busy = true;
    obs.subscribe({
      next: () => { this.busy = false; this.notify.success(ok); this.load(); },
      error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось выполнить действие'); this.load(); },
    });
  }

  byId = (_: number, d: any) => d.id;
  ago(iso: string | null) { return relativeTime(iso); }
  full(iso: string | null) { return fullDateTime(iso); }
  statusLabel(s: string): string {
    return ({ REJECTED: 'Отклонён', REVOKED: 'Отозван', EXPIRED: 'Истёк' } as Record<string, string>)[s] || s;
  }
}
```

- [ ] **Step 3: Маршрут и иконка**

`frontend/src/app/app.routes.ts` — импорт рядом с остальными страницами:

```ts
import { DevicesComponent } from './pages/devices/devices.component';
```

и строка маршрута сразу после `{ path: 'email-template', component: EmailTemplateComponent, canActivate: [adminGuard] },`:

```ts
      { path: 'devices', component: DevicesComponent, canActivate: [adminGuard] },
```

`frontend/src/app/app.config.ts` — в обоих местах (импорт из `@lucide/angular` и вызов `provideLucideIcons(...)`) дописать `LucideMonitorSmartphone` после `LucideInbox` (Edit с `replace_all` по `LucideInbox` → `LucideInbox, LucideMonitorSmartphone`; до правки проверить `grep -c LucideInbox src/app/app.config.ts` = 2).

- [ ] **Step 4: Пункт меню со счётчиком**

`frontend/src/app/layout/layout.component.ts`, шаблон — после ссылки «Шаблон письма КП»:

```html
            <a *ngIf="auth.isAdmin()" routerLink="/devices" routerLinkActive="active">
              <svg lucideIcon="monitor-smartphone" [size]="16"></svg> Устройства
              <span class="nav-count" *ngIf="pendingDevices > 0" [attr.aria-label]="pendingDevices + ' ждут допуска'">{{ pendingDevices }}</span>
            </a>
```

Класс: рядом с `newLeads = 0;` добавить поле

```ts
  pendingDevices = 0;             // запросы доступа с калитки ais.westmed.kz — счётчик в меню (только ADMIN)
```

и заменить метод `refreshLeadCount()` целиком:

```ts
  /** Заявки с сайта и запросы доступа приходят фоном — счётчики живут без перезагрузки страницы. */
  refreshCounts() {
    if (!this.auth.isLoggedIn()) return;
    this.api.getLeadCount('NEW').subscribe({
      next: r => { this.newLeads = r.count; this.cdr.detectChanges(); },
      error: () => {},
    });
    if (this.auth.isAdmin()) {
      this.api.getDevicePendingCount().subscribe({
        next: r => { this.pendingDevices = r.count; this.cdr.detectChanges(); },
        error: () => {},
      });
    }
  }
```

и в `ngOnInit()` три вызова `this.refreshLeadCount()` заменить на `this.refreshCounts()` (проверка: `grep -c refreshLeadCount src/app/layout/layout.component.ts` = 0 после правки).

- [ ] **Step 5: Сборка**

Run: `cd frontend && npm run build 2>&1 | tail -5`
Expected: `Application bundle generation complete`, без ошибок компиляции; стиль компонента в бюджете `anyComponentStyle` (24 kB).

- [ ] **Step 6: Живая проверка (локально, Playwright)**

Бэкенд и фронт запущены (как в Task 5). Подготовка — два запроса доступа прямо в бэкенд:

```bash
for n in "Асель" "Марат"; do curl -s -H 'Content-Type: application/json' -H 'User-Agent: Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1' -d "{\"name\":\"$n\"}" http://localhost:8080/api/gate/request >/dev/null; done
```

В Playwright, вход `admin/admin`, рынок KZ:
1. В меню «Система» — «Устройства» со счётчиком `2`.
2. `/devices`: две карточки запросов с крупным кодом, «iPhone · Safari», IP.
3. У «Асель» подпись «Телефон Асель» → «Допустить» → тост, карточка переехала в «Допущенные», счётчик в меню `1` (после навигации или через минуту).
4. «Марат» → «Отклонить» → «История (1)» → развернуть → бейдж «Отклонён».
5. Открыть в том же браузере `http://localhost:4200/gate/index.html`, запросить доступ под именем «Этот браузер», допустить со страницы «Устройства» → у него пометка «это устройство»; «Отозвать» → текст подтверждения про потерю доступа → «Отмена».
6. 390px и тёмная тема: карточки запросов — в одну колонку, поле подписи во всю ширину; снимки в `.playwright-mcp/`.
7. Под `operator/operator` пункта «Устройства» нет, `/devices` не открывается (adminGuard).

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/devices/devices.component.ts frontend/src/app/services/api.service.ts \
        frontend/src/app/app.routes.ts frontend/src/app/app.config.ts frontend/src/app/layout/layout.component.ts
git commit -m "feat(gate): раздел «Устройства» — запросы, допуск, отзыв, история, счётчик в меню

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: nginx хоста — калитка в конфиге, установка с откатом, репетиция на nginx 1.18

**Files:**
- Modify: `deploy/nginx/zz-ais.westmed.kz.conf` (полная замена)
- Create: `deploy/nginx/apply.sh`

**Interfaces:**
- Consumes: `/api/gate/check|status|request`, страница `/gate/` (Tasks 4–5).
- Produces: итоговый конфиг калитки; `deploy/nginx/apply.sh [файл]` — ставит конфиг на сервер с автооткатом (Task 9).

- [ ] **Step 1: Итоговый конфиг**

`deploy/nginx/zz-ais.westmed.kz.conf` — заменить целиком:

```nginx
# ais.westmed.kz → прод-АИС: фронт-контейнер на 127.0.0.1:8090 (дальше /api → бэкенд по ais-net).
# Ставится на ХОСТОВЫЙ nginx сервера рядом с westmed.kz и vital-spb.kz:
#   /etc/nginx/sites-available/zz-ais.westmed.kz  +  симлинк в sites-enabled. Установка — deploy/nginx/apply.sh,
#   порядок раскатки — DEPLOY.md §6.
#
# Имя начинается с «zz-» намеренно: default_server на сервере не задан, и «чужие» запросы (голый IP,
# неизвестный Host, TLS без SNI) nginx отдаёт ПЕРВОМУ загруженному server-блоку, а sites-enabled/*
# подключаются по алфавиту. Файл «ais.westmed.kz» загрузился бы первым и сделал бы АИС ответом по умолчанию.
#
# Калитка по коду устройства (спека docs/superpowers/specs/2026-09-28-device-gate-design.md): перед каждым
# запросом nginx спрашивает АИС, допущено ли устройство (auth_request → /api/gate/check, ответ из памяти).
# Недопущенным — только страница /gate/. Дальше, как и раньше, — логин АИС.

# 10 запросов/с на IP с запасом 100 — загрузка приложения и опросы UI укладываются с большим запасом;
# вход в АИС — 10 попыток в минуту на IP; запросы доступа на калитке — 3 в минуту на IP.
limit_req_zone $binary_remote_addr zone=ais_req:10m rate=10r/s;
limit_req_zone $binary_remote_addr zone=ais_login:10m rate=10r/m;
limit_req_zone $binary_remote_addr zone=ais_gate:1m rate=3r/m;

server {
    listen 80;
    listen [::]:80;
    server_name ais.westmed.kz;

    location /.well-known/acme-challenge/ {
        root /var/www/certbot;
    }
    location / {
        return 301 https://$host$request_uri;
    }
}

server {
    listen 443 ssl http2;
    listen [::]:443 ssl http2;
    server_name ais.westmed.kz;

    ssl_certificate     /etc/letsencrypt/live/ais.westmed.kz/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ais.westmed.kz/privkey.pem;
    ssl_protocols TLSv1.2 TLSv1.3;
    ssl_ciphers HIGH:!aNULL:!MD5;
    ssl_prefer_server_ciphers on;
    ssl_session_cache shared:AisSSL:5m;
    ssl_session_timeout 10m;

    add_header Strict-Transport-Security "max-age=31536000" always;
    add_header X-Frame-Options "SAMEORIGIN" always;
    add_header X-Content-Type-Options "nosniff" always;
    add_header Referrer-Policy "same-origin" always;
    add_header X-Robots-Tag "noindex, nofollow" always;

    limit_req zone=ais_req burst=100 nodelay;
    limit_req_status 429;

    client_max_body_size 50M;

    # калитка: по умолчанию каждая локация сперва спрашивает АИС, допущено ли устройство
    auth_request /__gate_check;

    location = /__gate_check {
        internal;
        auth_request off;                      # сама проверка проверке не подлежит
        proxy_pass http://127.0.0.1:8090/api/gate/check;
        proxy_pass_request_body off;
        proxy_set_header Content-Length "";
        proxy_set_header Host $host;
        proxy_connect_timeout 3s;
        proxy_read_timeout 5s;
    }

    # недопущенное устройство: страница → на калитку; API → 401 с пометкой,
    # по ней приложение само уходит на калитку (frontend/src/app/interceptors/auth.interceptor.ts)
    location @gate {
        if ($uri ~ ^/api/) {
            add_header X-AIS-Gate "device" always;
            return 401;
        }
        return 302 /gate/;
    }

    # Калитка и её API — без проверки устройства. Пометка «# калитка» в конце строки location нужна
    # переходному состоянию раскатки (DEPLOY.md §6): по ней в эти три локации дописывается пароль nginx.
    location /gate/ {                  # калитка
        auth_request off;
        expires -1;                    # не add_header: свой add_header отключил бы серверные (HSTS…)
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location = /api/gate/status {      # калитка
        auth_request off;
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
    location = /api/gate/request {     # калитка
        auth_request off;
        limit_req zone=ais_gate burst=2 nodelay;
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cookie_path / "/; Secure; SameSite=Lax";
    }
    # проверку зовёт только сам nginx (внутренний подзапрос выше) — снаружи её нет
    location = /api/gate/check {
        auth_request off;
        return 404;
    }

    # поисковикам — «не индексировать», без калитки (иначе робот видит только редирект)
    location = /robots.txt {
        auth_request off;
        default_type text/plain;
        return 200 "User-agent: *\nDisallow: /\n";
    }

    # error_page — в проксирующих локациях, а НЕ на уровне сервера: иначе в переходный период он
    # перехватывал бы и 401 от пароля nginx на калитке, и браузер вместо окна пароля получал бы редирект.
    # Ловит только 401, сгенерированный самим nginx (auth_request): свой 401 АИС («сессия истекла»)
    # проходит как есть — proxy_intercept_errors выключен.
    location = /api/auth/login {
        error_page 401 = @gate;
        limit_req zone=ais_login burst=5 nodelay;
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        # nginx 1.18 не знает proxy_cookie_flags — Secure/SameSite дописываются через путь cookie
        proxy_cookie_path / "/; Secure; SameSite=Lax";
    }

    location / {
        error_page 401 = @gate;
        proxy_pass http://127.0.0.1:8090;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_cookie_path / "/; Secure; SameSite=Lax";
        proxy_read_timeout 120s;   # разбор ТЗ (PDF) и подобное бывают долгими
    }
}
```

- [ ] **Step 2: Установка с откатом**

`deploy/nginx/apply.sh` (и `chmod +x deploy/nginx/apply.sh`):

```bash
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
```

- [ ] **Step 3: Репетиция — стенд на nginx 1.18 в Docker**

Предусловия: бэкенд (с кодом Tasks 1–4) на `localhost:8080`, фронт собран (`frontend/dist/nir-frontend/browser`, Task 6), Docker запущен (`docker info >/dev/null 2>&1 || open -a Docker`, дождаться).

Стенд повторяет прод в два слоя: «фронт-контейнер» (тот же `frontend/nginx.conf`, бэкенд — хост) и «хостовый nginx» (этот конфиг; меняются только адреса, TLS и IPv6):

```bash
R=$SCRATCH/rehearsal; mkdir -p $R
sed 's#ais-backend:8080#host.docker.internal:8080#' frontend/nginx.conf > $R/frontend.conf
sed -e 's#http://127.0.0.1:8090#http://ais-fe:80#g' \
    -e 's#server_name ais.westmed.kz;#server_name localhost;#' \
    -e 's#listen 443 ssl http2;#listen 8088;#' \
    -e '/listen \[::\]/d' -e '/ssl_/d' \
    deploy/nginx/zz-ais.westmed.kz.conf > $R/edge.conf
docker network create ais-rehearsal
docker run -d --name ais-fe --network ais-rehearsal \
  -v $PWD/frontend/dist/nir-frontend/browser:/usr/share/nginx/html:ro \
  -v $R/frontend.conf:/etc/nginx/conf.d/default.conf:ro nginx:1.18
docker run -d --name ais-edge --network ais-rehearsal -p 8088:8088 \
  -v $R/edge.conf:/etc/nginx/conf.d/default.conf:ro nginx:1.18
docker exec ais-edge nginx -t
```

Expected: `nginx: configuration file /etc/nginx/nginx.conf test is successful`.

- [ ] **Step 4: Репетиция — итоговое состояние (curl)**

```bash
E=http://localhost:8088; J=$R/device.jar; A=$R/admin.jar; rm -f $J $A
echo "1 страница без ключа:  $(curl -s -o /dev/null -w '%{http_code} → %{redirect_url}' $E/)"                  # 302 → http://localhost:8088/gate/
echo "2 API без ключа:       $(curl -s -D - -o /dev/null $E/api/leads/count | grep -i -E '^HTTP|^x-ais-gate' | tr -d '\r' | tr '\n' ' ')"   # 401 + x-ais-gate: device
echo "3 калитка:             $(curl -s -o /dev/null -w '%{http_code}' $E/gate/)"                                   # 200
echo "4 check снаружи:       $(curl -s -o /dev/null -w '%{http_code}' $E/api/gate/check)"                         # 404
echo "5 robots без ключа:    $(curl -s -o /dev/null -w '%{http_code}' $E/robots.txt)"                              # 200
echo "6 запрос доступа:      $(curl -s -c $J -H 'Content-Type: application/json' -d '{"name":"Репетиция"}' $E/api/gate/request)"
T=$(awk '$6=="ais_device"{print $7}' $J)   # ключ из cookie-jar — дальше явно: Secure-cookie по http curl может не слать
curl -s -c $A -H 'Content-Type: application/json' -d '{"username":"admin","password":"admin"}' http://localhost:8080/api/auth/login >/dev/null
ID=$(curl -s -b $A http://localhost:8080/api/devices | python3 -c 'import json,sys; print(json.load(sys.stdin)["pending"][0]["id"])')
curl -s -b $A -X POST -H 'Content-Type: application/json' -d '{}' http://localhost:8080/api/devices/$ID/approve >/dev/null
echo "7 допущенный — страница: $(curl -s -b "ais_device=$T" -o /dev/null -w '%{http_code}' $E/)"                               # 200
echo "8 допущенный — свой 401 АИС: $(curl -s -b "ais_device=$T" -D - -o /dev/null $E/api/leads/count | grep -i -E '^HTTP|^x-ais-gate' | tr -d '\r' | tr '\n' ' ')"   # 401 БЕЗ x-ais-gate
curl -s -b $A -X POST http://localhost:8080/api/devices/$ID/revoke >/dev/null
echo "9 после отзыва:        $(curl -s -b "ais_device=$T" -o /dev/null -w '%{http_code} → %{redirect_url}' $E/)"             # 302 → …/gate/
docker restart ais-edge >/dev/null && until curl -s -o /dev/null "$E/robots.txt"; do :; done   # обнулить лимиты
echo "10 лимит запросов:      $(for i in 1 2 3 4 5; do curl -s -o /dev/null -w '%{http_code} ' -H 'Content-Type: application/json' -d '{"name":"Лимит"}' $E/api/gate/request; done)"   # 200 200 200 429 429
```

Все 10 строк обязаны совпасть с комментариями. Расхождение — остановиться, найти причину на стенде (`docker logs ais-edge`), поправить эталонный конфиг, повторить. Каждое найденное расхождение записать для CLAUDE.md §14 (Task 8) в виде «симптом → причина → решение».

(Пятый шаг лимита может дать `200` вместо `429`, если между запросами прошло больше 20 секунд — зона `3r/m` пополняется раз в 20 с; тогда повторить быстрее.)

- [ ] **Step 5: Репетиция — в браузере (Playwright)**

Новый контекст браузера (без cookie), `http://localhost:8088/`:
1. → редирект на `/gate/`, форма калитки. Запросить доступ «Браузер репетиции» → код.
2. Допустить через API бэкенда (как в Step 4) → страница сама уходит на `/` → вход в АИС (`admin/admin`), приложение работает.
3. Отозвать устройство через API бэкенда → в открытом приложении кликнуть любой пункт меню → запрос API получает `401` с `X-AIS-Gate` → приложение само переходит на `/gate/` (перехватчик Task 5) и показывает «Доступ с этого устройства отозван».

- [ ] **Step 6: Репетиция — переходное состояние (пароль только на калитке)**

```bash
perl -pe 's{(# калитка)$}{$1\n        auth_basic "AIS"; auth_basic_user_file /etc/nginx/.htpasswd-ais;}' $R/edge.conf > $R/edge-transition.conf
printf 'westmed:%s\n' "$(openssl passwd -apr1 rehearsal-pass)" > $R/htpasswd
docker rm -f ais-edge >/dev/null
docker run -d --name ais-edge --network ais-rehearsal -p 8088:8088 \
  -v $R/edge-transition.conf:/etc/nginx/conf.d/default.conf:ro -v $R/htpasswd:/etc/nginx/.htpasswd-ais:ro nginx:1.18
docker exec ais-edge nginx -t
echo "T1 страница без ключа: $(curl -s -o /dev/null -w '%{http_code} → %{redirect_url}' $E/)"                                     # 302 → …/gate/
echo "T2 калитка без пароля: $(curl -s -D - -o /dev/null $E/gate/ | grep -i -E '^HTTP|^www-authenticate' | tr -d '\r' | tr '\n' ' ')"   # 401 + WWW-Authenticate: Basic realm="AIS" — окно пароля, НЕ редирект
echo "T3 калитка с паролем:  $(curl -s -u westmed:rehearsal-pass -o /dev/null -w '%{http_code}' $E/gate/)"                      # 200
echo "T4 неверный пароль:    $(curl -s -u westmed:wrong -o /dev/null -w '%{http_code}' $E/gate/)"                               # 401
echo "T5 запрос без пароля:  $(curl -s -o /dev/null -w '%{http_code}' -H 'Content-Type: application/json' -d '{"name":"x"}' $E/api/gate/request)"   # 401
```

- [ ] **Step 7: Разобрать стенд**

```bash
docker rm -f ais-edge ais-fe >/dev/null; docker network rm ais-rehearsal >/dev/null
```

- [ ] **Step 8: Commit**

```bash
chmod +x deploy/nginx/apply.sh
git add deploy/nginx/zz-ais.westmed.kz.conf deploy/nginx/apply.sh
git commit -m "ops(gate): калитка в nginx хоста (auth_request), установка с откатом; репетиция на nginx 1.18

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Документация и финальный гейт

**Files:**
- Modify: `CLAUDE.md` (§8 — новый блок; §14 — уроки репетиции; §15 — API; §16 — бэклог)
- Modify: `DEPLOY.md` (§6 — полностью)
- Modify: `docs/PROGRESS.md` (запись сессии)
- Modify: `frontend/nginx.conf` (комментарий в шапке)

- [ ] **Step 1: CLAUDE.md §8 — блок калитки**

В §8 после блока «**Обращения — коммерческий вход West-Med …**» (перед `## 9.`) вставить:

```markdown
- **Калитка по коду устройства для `ais.westmed.kz` (2026-09-28, ветка `feature/device-gate`).** Спека/план: `docs/superpowers/{specs,plans}/2026-09-28-device-gate*`. Шаг 2 «доступа без Tailscale»: вместо общего пароля nginx — допуск по устройству. Недопущенный браузер видит только `/gate/` («Служебный вход»: имя → код `7K4-QM2`), админ допускает в «Система → Устройства» (счётчик ожидающих в меню).
  - **nginx хоста спрашивает АИС на каждый запрос:** `auth_request /__gate_check` → `GET /api/gate/check` (`permitAll`, ответ из памяти): 204 — пускать, 401 — `@gate`: страница → `302 /gate/`, `/api/…` → `401` + `X-AIS-Gate: device`; `authInterceptor` по этому заголовку делает `location.assign('/gate/')`, без logout. **Закрыто по умолчанию:** АИС не ответила — не пускает никого.
  - ⚠️ **`error_page 401 = @gate` — в проксирующих локациях, НЕ на уровне сервера:** на уровне сервера он перехватывал бы и 401 от пароля nginx на калитке (переходный период) — браузер вместо окна пароля получал бы редирект. Свой 401 АИС («сессия истекла») `error_page` не ловит (`proxy_intercept_errors` выключен). `/__gate_check` — `auth_request off`; в `/gate/` — `expires -1`, не `add_header` (свой `add_header` отключил бы серверные HSTS/`X-Robots-Tag`).
  - **Ключ устройства** — 256 бит в cookie `ais_device` (`HttpOnly; Secure; SameSite=Lax`, 400 дней — предел Chrome), в `trusted_device` (V19, общая, без рынка) — только SHA-256. Код — метка для админа, не секрет. Запрос живёт 15 мин, ожидающих — не больше 20 (429), 3 запроса в минуту с IP (nginx); допущенное — до отзыва, 90 дней без визитов — выпадает.
  - **Реестр допущенных в памяти** (`DeviceRegistry`): проверка не ходит в БД; визиты копятся в памяти и раз в 5 мин пишутся пачкой (`DeviceGateScheduler`; визиты — ПЕРЕД истечением простоя, иначе недавно заходившее устройство выпало бы по старой отметке). Реестр обновляется ПОСЛЕ возврата из `TrustedDeviceWriter` (отдельный бин, транзакция на метод) — то есть после коммита. Колбэк «после коммита» (`TransactionSynchronization`) не подошёл: `@Transactional`-тесты не коммитят, тесты не увидели бы допуска.
  - **IP** — предпоследний адрес `X-Forwarded-For`: nginx хоста дописывает реальный адрес к присланному клиентом (первый подделывается), фронт-контейнер дописывает свой шаг последним.
  - Tailscale и SSH-туннель идут на `127.0.0.1:8090` мимо nginx хоста — калитки там нет: запасной вход админа, им же допускается первое устройство.
  - Установка конфига — `deploy/nginx/apply.sh` (при провале `nginx -t` сам возвращает прежний файл). Переходное состояние (пароль nginx только на трёх локациях калитки) получается из эталона `perl`-ом по пометке `# калитка` — DEPLOY.md §6.
```

- [ ] **Step 2: CLAUDE.md §14, §15, §16**

§14 — для каждого расхождения, найденного на репетиции (Task 7 Step 4–6), — пункт «симптом → причина → решение». Если расхождений не было — пункт:

```markdown
- **Поведение nginx проверять на живом nginx той же версии, а не по документации** (калитка, 2026-09-28): связку `auth_request` + `error_page` + именованная локация + `if` + наследование `add_header`/`error_page` отрепетировали на `nginx:1.18` в Docker двумя слоями, как на проде (`deploy/nginx` + `frontend/nginx.conf`), — curl-проверки из плана Task 7 переиспользуемы при любой правке конфига.
```

§15 — в конец перечня API перед `Записи — …` дописать:

```markdown
`/api/gate/*` (калитка, без входа: `GET /check` — только для nginx, снаружи 404; `POST /request {name}`; `GET /status`); `/api/devices` (ADMIN: GET список, `/pending-count`, POST `/{id}/approve {label?}|reject|revoke`);
```

§16 — в пункт «Обращения (коммерция West-Med)» заменить `шаг 2 — «калитка» по коду устройства вместо общего пароля nginx (вебхукам WhatsApp/АТС понадобится исключение из basic auth);` на `✔ шаг 2 — калитка по коду устройства (§8; раскатка — DEPLOY.md §6). Хвосты калитки: уведомления о запросах в Telegram; привязка устройства к учётке АИС; вебхукам WhatsApp/АТС понадобится исключение из калитки со своей проверкой подписи;`.

- [ ] **Step 3: DEPLOY.md §6 — полностью**

Заменить весь раздел `## 6. Доступ по \`https://ais.westmed.kz\` (без Tailscale)` до конца файла на:

```markdown
## 6. Доступ по `https://ais.westmed.kz` (без Tailscale)
Шаг 1 (2026-09-27): адрес, сертификат, общий пароль nginx. Шаг 2 (2026-09-28): **калитка по коду устройства** вместо общего пароля — спека `docs/superpowers/specs/2026-09-28-device-gate-design.md`. Tailscale и SSH-туннель работают параллельно и калитку обходят — это запасной вход админа.
- **DNS:** A-запись `ais` → `185.125.46.26` в панели unihost.kz (NS домена — `ns1/ns2.unihost.kz`).
- **nginx хоста:** эталон — `deploy/nginx/zz-ais.westmed.kz.conf`, ставится `deploy/nginx/apply.sh [файл]`: заливает, проверяет `nginx -t`, при провале сам возвращает прежний файл; прежний хранится рядом — `/etc/nginx/sites-available/zz-ais.westmed.kz.prev`.
  ⚠️ **Префикс `zz-` обязателен:** `default_server` на сервере не задан, и неизвестные запросы (голый IP, TLS без SNI) получает первый загруженный по алфавиту конфиг — файл `ais.…` сделал бы АИС ответом на любой запрос к IP.
  ⚠️ Битый конфиг в `sites-enabled` сорвал бы следующий reload из хука certbot, то есть продление сертификатов всех трёх сайтов, — поэтому только через `apply.sh`.
- **Калитка:** перед каждым запросом nginx спрашивает АИС (`auth_request` → `/api/gate/check`). Недопущенным: страница → `302 /gate/`, API → `401` + `X-AIS-Gate: device`. Устройства допускает и отзывает админ: АИС → Система → Устройства.
- **Первое устройство** (или все потеряны): `ssh -N -L 8090:127.0.0.1:8090 root@185.125.46.26` → `http://localhost:8090` → вход админом → «Устройства» → «Допустить» запрос с кодом, который показывает калитка.
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
```

- [ ] **Step 4: PROGRESS и комментарий фронт-контейнера**

`docs/PROGRESS.md` — после записи «Доступ без Tailscale, шаг 1 (2026-09-27)» добавить:

```markdown
- **Доступ без Tailscale, шаг 2 — калитка по коду устройства (2026-09-28, ветка `feature/device-gate`):** spec → plan → реализация. Бэкенд: V19 `trusted_device`, `DeviceGateService` + реестр в памяти + `TrustedDeviceWriter`, `/api/gate/*` (без входа) и `/api/devices/*` (ADMIN), фоновая задача раз в 5 мин. Фронт: страница `/gate/`, раздел «Устройства» со счётчиком, перехват `X-AIS-Gate`. nginx: `auth_request`, `deploy/nginx/apply.sh` с автооткатом, репетиция на `nginx:1.18` в Docker. Раскатка на прод — DEPLOY.md §6 (push — оператор).
```

`frontend/nginx.conf`, вторая строка шапки: `# на бэкенд по имени сервиса docker-сети. Наружу торчит только через 127.0.0.1:8090 → tailscale serve.` заменить на `# на бэкенд по имени сервиса docker-сети. Наружу — только через 127.0.0.1:8090: tailscale serve и nginx хоста (ais.westmed.kz, калитка).`

- [ ] **Step 5: Финальный гейт**

Run (sandbox off): `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test` и `(cd frontend && npm run build 2>&1 | tail -3)`
Expected: 0 падений (553 теста); сборка фронта без ошибок.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md DEPLOY.md docs/PROGRESS.md frontend/nginx.conf
git commit -m "docs(gate): калитка — механика, раскатка, откат, API, бэклог

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Завершение ветки**

superpowers:finishing-a-development-branch — мерж в `main` локально только по решению оператора; push делает оператор.

---

### Task 9: Раскатка на прод — ТОЛЬКО с согласия оператора

Каждый шаг затрагивает боевой сервер (`ssh root@185.125.46.26`, вход по ключу). Перед шагом 3 — подтверждение оператора в чате.

- [ ] **Step 1: Деплой кода**

Оператор: `! git push origin main`. Агент ждёт в фоне, пока на проде не появится V19:

```bash
until ssh root@185.125.46.26 'docker exec ais-ais-postgres-1 sh -c "psql -U \"\$POSTGRES_USER\" -d nirdb -tAc \"select max(version::int) from flyway_schema_history where success\""' | grep -qx 19; do sleep 20; done; echo "V19 на проде"
```

(запускать с `run_in_background: true`). Затем — логи: `ssh root@185.125.46.26 'docker logs --since 10m ais-ais-backend-1 2>&1 | grep -i -E "Successfully applied|Started Nir|ERROR" | tail'`.

- [ ] **Step 2: Запасная консоль**

`ssh -N -o ExitOnForwardFailure=yes -L 8090:127.0.0.1:8090 root@185.125.46.26` (фон) → оператор открывает `http://localhost:8090`, вход `admin` (пароль — `pbcopy < ~/.config/ais/ais-admin.pass`) → «Устройства» (пусто).

- [ ] **Step 3: Переходный конфиг и проверка всего пути**

```bash
perl -pe 's{(# калитка)$}{$1\n        auth_basic "AIS"; auth_basic_user_file /etc/nginx/.htpasswd-ais;}' deploy/nginx/zz-ais.westmed.kz.conf > $SCRATCH/zz-ais.transition.conf
deploy/nginx/apply.sh $SCRATCH/zz-ais.transition.conf
```

Проверка снаружи (bash; `R=(--resolve …)` обходит отрицательный кеш DNS Mac):

```bash
bash <<'SH'
R=(--resolve ais.westmed.kz:443:185.125.46.26); U=https://ais.westmed.kz; D="$HOME/.config/ais"
N=$(mktemp); trap 'rm -f "$N"' EXIT; umask 077
printf 'machine ais.westmed.kz login westmed password %s\n' "$(cat "$D/ais-gate.pass")" > "$N"
echo "страница без ключа → $(curl -s "${R[@]}" -o /dev/null -w '%{http_code} %{redirect_url}' $U/)"                        # 302 …/gate/
echo "калитка без пароля → $(curl -s "${R[@]}" -D - -o /dev/null $U/gate/ | grep -i -E '^HTTP|^www-auth' | tr -d '\r' | tr '\n' ' ')"   # 401 + Basic
echo "калитка с паролем → $(curl -s "${R[@]}" --netrc-file "$N" -o /dev/null -w '%{http_code}' $U/gate/)"                  # 200
echo "соседи: westmed.kz $(curl -s -o /dev/null -w '%{http_code}' https://westmed.kz/), vital-spb.kz $(curl -s -o /dev/null -w '%{http_code}' https://vital-spb.kz/)"   # 200 200
echo "голый IP без SNI → $(openssl s_client -connect 185.125.46.26:443 </dev/null 2>/dev/null | openssl x509 -noout -subject)"   # CN=vital-spb.kz
SH
```

Затем оператор: Mac и телефон → `https://ais.westmed.kz` → пароль nginx (`westmed` + `ais-gate.pass`) → калитка → имя → код → «Допустить» в запасной вкладке → вход в АИС. Проверить отзыв на одном устройстве: «Отозвать» → на нём любой клик → калитка «Доступ отозван» → запросить снова → допустить.

- [ ] **Step 4: Итоговый конфиг — общий пароль снят**

```bash
deploy/nginx/apply.sh
ssh root@185.125.46.26 'rm -f /etc/nginx/.htpasswd-ais'
```

Проверка: калитка без пароля → `200`; `POST /api/gate/request` без пароля → `200` (или `429` при частых повторах); допущенные Mac и телефон открывают АИС без пароля nginx; соседи `200`; голый IP — сертификат vital-spb.

- [ ] **Step 5: Документация статуса**

CLAUDE.md §5 (строка «Есть ПРОД»): `вход — **\`https://ais.westmed.kz\`** за паролем nginx на входе, с 2026-09-27, DEPLOY.md §6, — или по Tailscale` → `вход — **\`https://ais.westmed.kz\`** через калитку по коду устройства (с 2026-09-28, DEPLOY.md §6; допуск — «Система → Устройства»), — или по Tailscale`. PROGRESS — итог раскатки (сколько устройств допущено, что наблюдали). Память `ais-prod-deploy-oblako.md` — строка «Доступ»: калитка вместо basic auth. Commit на короткой ветке + ff-мерж; push — оператор.
