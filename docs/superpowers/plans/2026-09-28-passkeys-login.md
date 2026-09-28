# Вход по Face ID / Touch ID (passkeys) — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Вход в АИС по ключу (passkey) — Face ID на iPhone, Touch ID на Mac — вместо длинного пароля; пароль остаётся запасным входом.

**Architecture:** Встроенные passkeys Spring Security 6.5.5 (`http.webAuthn`) с JDBC-хранилищем ключей в таблицах Spring (V20), привязанных к `user_account` каскадом по логину. Регистрация и вход — встроенные эндпоинты Spring (`/webauthn/*`, `/login/webauthn`); фронт шлёт тела ровно в формате эталонного клиента Spring. Свои ключи — наш `/api/passkeys` с проверкой владельца. Сессия — 12 ч, id меняется при каждом входе.

**Tech Stack:** Java 17, Spring Boot 3.5.6 / Spring Security 6.5.5 (WebAuthn), `webauthn4j-core` 0.29.5.RELEASE, Flyway, PostgreSQL 17 (nirdb), Angular 21 standalone, nginx 1.27 (фронт-контейнер), Playwright + виртуальный аутентификатор Chrome (CDP).

**Spec:** `docs/superpowers/specs/2026-09-28-passkeys-login-design.md` — исполнитель читает её целиком перед началом. Факты о Spring 6.5.5 в спеке (§1.1, §5.1) сверены по байткоду (`javap`). Если живое поведение расходится с планом — остановиться и выяснить причину, а не подгонять тест под результат.

## Global Constraints

- Зависимость — ровно `com.webauthn4j:webauthn4j-core:0.29.5.RELEASE`: с этой версией собран Spring Security 6.5.5. Версию 0.29.7 из примера документации Spring не брать.
- `rpName` — «АИС Медзакупки». Настройки `passkeys.rp-id` / `passkeys.allowed-origins`:
  - разработка (`application.yaml`) — `localhost` / `http://localhost:4200`;
  - прод (`application-prod.yaml`) — `ais.westmed.kz` / `https://ais.westmed.kz`.

  Читаются через `@Value`: `@ConfigurationProperties` в проекте не используется.
- Таблицы `user_entities` и `user_credentials` — схема Spring без изменений колонок плюс FK, UNIQUE и индекс из спеки §4. Миграция — `V20__passkeys.sql` (V19 занята калиткой). Таблицы общие, без рынка.
- Сессия: `server.servlet.session.timeout: 12h`, `server.servlet.session.cookie.max-age: 12h`. Id сессии меняется при каждом успешном входе — и паролем, и ключом.
- Правила доступа:
  - `DELETE /webauthn/**` → `denyAll`, первым правилом;
  - `/webauthn/register` и `/webauthn/register/**` → `authenticated`;
  - `disableDefaultRegistrationPage(true)`;
  - nginx фронт-контейнера пропускает к бэкенду только `/login/webauthn`, `/webauthn/authenticate/options`, `/webauthn/register/options`, `/webauthn/register` и только методом POST.
- Свои ключи — только через `/api/passkeys`:
  - нет такого ключа, чужой или битый id → одинаково 404 (`NotFoundException`);
  - `lastUsedAt` = `null`, пока `last_used` равен `created` (так Spring сохраняет новый ключ);
  - ключи сортируются от старых к новым.
- Принципал после входа по ключу — `PublicKeyCredentialUserEntity`, а не `UserDetails`. Пользователя брать только через `Authentication.getName()`.
- Тексты интерфейса — дословно:
  - кнопка входа — «Войти с Face ID / Touch ID»; пока ждём — «Ждём Face ID / Touch ID…»;
  - кнопка в профиле — «Добавить вход по Face ID / Touch ID» (пока ждём — «Ждём Face ID / Touch ID…»);
  - отмена или нет ключа (вход) — «Не получилось войти по ключу. Если ключа на этом устройстве ещё нет — войдите паролем и добавьте его в «Мой профиль».»;
  - отказ сервера (вход) — «Ключ не принят. Попробуйте ещё раз; если не получится — войдите паролем и добавьте ключ заново в «Мой профиль», а старый удалите в настройках паролей устройства.»;
  - прочий сбой входа — «Не удалось войти по ключу. Попробуйте ещё раз или войдите паролем.»;
  - ключ уже есть — «На этом устройстве ключ для вашей учётки уже есть»;
  - сессия истекла — «Сессия истекла — войдите снова»;
  - ключ добавлен — «Ключ добавлен. В следующий раз нажмите «Войти с Face ID / Touch ID»»;
  - прочий сбой добавления — «Не удалось добавить ключ. Попробуйте ещё раз»;
  - подтверждение удаления — «Удалить ключ «<подпись>»?» и под ним «Войти с ним больше не получится. Сам ключ останется в связке ключей устройства — его можно удалить в настройках паролей.»;
  - ключ удалён — «Ключ удалён»;
  - ключей нет — «Ключей пока нет. Добавьте — и входите без пароля.»;
  - хост не совпал с rpId — «Добавить ключ можно на https://<rpId>.»;
  - браузер без WebAuthn — «Этот браузер не поддерживает вход по ключу.».
- Тесты бэкенда:
  - `@SpringBootTest @Transactional` на nirdb;
  - MockMvc собирается только так: `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build()`. Без `@AutoConfigureMockMvc`, `@MockitoBean` и `@TestPropertySource`: каждый особый контекст — ещё 10 соединений к nirdb (CLAUDE.md §14);
  - пакет тестов — `com.vladoose.nir.passkeys`;
  - JSON с кириллицей читать через `getContentAsString(StandardCharsets.UTF_8)`.
- Все `./gradlew` и обращения к БД — с `dangerouslyDisableSandbox: true` (CLAUDE.md §5). Гейт бэкенда — `./gradlew cleanTest test` (0 падений), фронта — `cd frontend && npm run build`.
- UI — UI-kit проекта (CLAUDE.md §12): токены, светлая и тёмная тема, `@media` в конце стилей, тач-таргеты. Иконки на экране входа — инлайн-SVG.
- Секреты (`~/.config/ais/*.pass`) не печатать: только `$(cat …)`, stdin, `pbcopy <` или чтение внутри скрипта.
- Каждый коммит заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Push в `main` делает оператор (`! git push origin main`). Действия на проде — только в Task 8 и только с согласия оператора.
- `$SCRATCH` в командах — scratchpad сессии. Файлы для Docker класть в `~/.cache/ais-rehearsal`: colima монтирует только домашний каталог (CLAUDE.md §14).

## Карта файлов

| Файл | Ответственность |
|---|---|
| правка `build.gradle` | зависимость `webauthn4j-core` |
| `src/main/resources/db/migration/V20__passkeys.sql` | таблицы Spring + привязка к учётке |
| `src/main/java/com/vladoose/nir/config/PasskeyConfig.java` | JDBC-хранилища ключей Spring |
| правка `src/main/java/com/vladoose/nir/config/SecurityConfig.java` | `http.webAuthn`, правила доступа, фильтр смены id |
| `src/main/java/com/vladoose/nir/security/SessionIdRotationFilter.java` | смена id сессии перед входом по ключу |
| `src/main/java/com/vladoose/nir/service/PasskeyService.java` | свои ключи: список, удаление с проверкой владельца |
| `src/main/java/com/vladoose/nir/controller/PasskeyController.java` | `/api/passkeys` |
| правка `src/main/java/com/vladoose/nir/controller/AuthController.java` | смена id при входе паролем; `/api/auth/passkey-config` |
| `src/main/java/com/vladoose/nir/dto/response/PasskeyResponse.java`, `PasskeyListResponse.java`, `PasskeyConfigResponse.java` | ответы |
| правки `src/main/resources/application.yaml`, `application-prod.yaml` | rpId и origin по средам, сессия 12 ч |
| `src/test/java/com/vladoose/nir/passkeys/*` | фикстуры, эмулятор ключа, тесты |
| `frontend/src/app/services/passkey.service.ts` | церемонии WebAuthn в формате Spring |
| правка `frontend/src/app/services/api.service.ts` | `getPasskeys`, `deletePasskey`, `getPasskeyConfig` |
| `frontend/src/app/pages/profile/profile.component.ts` | «Мой профиль» |
| правки `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts` | маршрут, пункт меню, имя в шапке → профиль |
| правка `frontend/src/app/pages/login/login.component.ts` | кнопка входа по ключу, `autocomplete` |
| правка `frontend/src/app/interceptors/auth.interceptor.ts` | 401 от `/login/webauthn` — не «сессия истекла» |
| правки `frontend/nginx.conf`, `frontend/proxy.conf.json` | пути passkeys → бэкенд |
| правки `CLAUDE.md`, `DEPLOY.md`, `docs/PROGRESS.md` | документация |

---

### Task 1: Хранилище ключей, включение passkeys, правила доступа, сессия 12 ч

**Files:**
- Modify: `build.gradle`
- Create: `src/main/resources/db/migration/V20__passkeys.sql`
- Create: `src/main/java/com/vladoose/nir/config/PasskeyConfig.java`
- Modify: `src/main/java/com/vladoose/nir/config/SecurityConfig.java`
- Modify: `src/main/resources/application.yaml`, `src/main/resources/application-prod.yaml`
- Test: `src/test/java/com/vladoose/nir/passkeys/PasskeyFixtures.java`, `PasskeySetupTest.java`, `PasskeyAccountLinkTest.java`

**Interfaces:**
- Produces:
  - бины `PublicKeyCredentialUserEntityRepository` и `UserCredentialRepository` (JDBC, пакет `org.springframework.security.web.webauthn.management`);
  - настройки `passkeys.rp-id` и `passkeys.allowed-origins`;
  - тестовые помощники (package-private, пакет `com.vladoose.nir.passkeys`):
    - `PasskeyFixtures.user(UserAccountRepository users, String username) → UserAccount`;
    - `PasskeyFixtures.owner(PublicKeyCredentialUserEntityRepository entities, String username) → PublicKeyCredentialUserEntity`;
    - `PasskeyFixtures.key(UserCredentialRepository credentials, PublicKeyCredentialUserEntity owner, String label, Instant created, Instant lastUsed) → Bytes` (id ключа).

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/vladoose/nir/passkeys/PasskeyFixtures.java`:

```java
package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.ImmutableCredentialRecord;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCose;
import org.springframework.security.web.webauthn.api.ImmutablePublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialType;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

import java.time.Instant;
import java.util.Set;

/**
 * Учётки и ключи прямо в таблицах — для тестов, которым не нужна церемония WebAuthn
 * (саму церемонию проверяет PasskeyLoginFlowTest на эмуляторе ключа).
 */
final class PasskeyFixtures {

    private PasskeyFixtures() {}

    /** Учётка АИС: запись пользователя WebAuthn ссылается на её логин внешним ключом (V20). Пароль тесту не нужен. */
    static UserAccount user(UserAccountRepository users, String username) {
        return users.saveAndFlush(UserAccount.builder()
                .username(username).fullName(username).role("ROLE_USER").passwordHash("-").build());
    }

    static PublicKeyCredentialUserEntity owner(PublicKeyCredentialUserEntityRepository entities, String username) {
        PublicKeyCredentialUserEntity e = ImmutablePublicKeyCredentialUserEntity.builder()
                .name(username).id(Bytes.random()).displayName(username).build();
        entities.save(e);
        return e;
    }

    /** Ключ владельца. created == lastUsed — так Spring сохраняет только что зарегистрированный ключ. */
    static Bytes key(UserCredentialRepository credentials, PublicKeyCredentialUserEntity owner,
                     String label, Instant created, Instant lastUsed) {
        Bytes id = Bytes.random();
        credentials.save(ImmutableCredentialRecord.builder()
                .credentialType(PublicKeyCredentialType.PUBLIC_KEY)
                .credentialId(id)
                .userEntityUserId(owner.getId())
                .publicKey(new ImmutablePublicKeyCose(new byte[] {1, 2, 3}))
                .signatureCount(0)
                .uvInitialized(true)
                .transports(Set.of())
                .backupEligible(false)
                .backupState(false)
                .created(created)
                .lastUsed(lastUsed)
                .label(label)
                .build());
        return id;
    }
}
```

`src/test/java/com/vladoose/nir/passkeys/PasskeySetupTest.java`:

```java
package com.vladoose.nir.passkeys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.ServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Включение passkeys и правила доступа к встроенным эндпоинтам Spring (спека passkeys-login §5.1–5.3).
 * MockMvc — из общего контекста: @AutoConfigureMockMvc поднял бы лишний контекст со своим пулом (CLAUDE.md §14).
 */
@SpringBootTest
@Transactional
class PasskeySetupTest {

    static final String USER = "pk-setup";
    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;
    @Autowired ServerProperties server;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        PasskeyFixtures.user(users, USER);
    }

    @Test
    void loggedInUserGetsRegistrationOptionsForThisDomain() throws Exception {
        String json = mvc.perform(post("/webauthn/register/options").with(user(USER)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode o = new ObjectMapper().readTree(json);

        assertThat(o.at("/rp/id").asText()).isEqualTo("localhost");
        assertThat(o.at("/rp/name").asText()).isEqualTo("АИС Медзакупки");
        assertThat(o.at("/user/name").asText()).isEqualTo(USER);
        assertThat(o.at("/authenticatorSelection/residentKey").asText()).isEqualTo("required");
        assertThat(o.at("/challenge").asText()).isNotBlank();
        assertThat(entities.findByUsername(USER))
                .as("запись пользователя WebAuthn создаётся при первых параметрах регистрации").isNotNull();
    }

    @Test
    void registrationNeedsLogin() throws Exception {
        // параметры: фильтр Spring стоит до проверки прав и сам отвечает анониму 400, а не 401 (спека §1.1 п. 7)
        mvc.perform(post("/webauthn/register/options")).andExpect(status().isBadRequest());
        // сама регистрация стоит после проверки прав — её закрывает правило authenticated()
        mvc.perform(post("/webauthn/register").contentType(APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginOptionsAreOpenToAnonymous() throws Exception {
        mvc.perform(post("/webauthn/authenticate/options"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"))
                .andExpect(jsonPath("$.challenge").isNotEmpty());
    }

    @Test
    void builtInDeleteIsClosedForEveryone() throws Exception {
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, USER);
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        mvc.perform(delete("/webauthn/register/" + key.toBase64UrlString()))
                .andExpect(status().isUnauthorized());                       // аноним получает точку входа
        mvc.perform(delete("/webauthn/register/" + key.toBase64UrlString()).with(user(USER)))
                .andExpect(status().isForbidden());                          // даже владельцу — только через /api/passkeys
        assertThat(credentials.findByCredentialId(key)).as("ключ на месте").isNotNull();
    }

    @Test
    void defaultRegistrationPageIsOff() throws Exception {
        mvc.perform(get("/webauthn/register").with(user(USER)))
                .andExpect(status().is(not(200)))
                .andExpect(content().string(not(containsString("<html"))));
    }

    @Test
    void sessionLastsAWorkingDay() {
        assertThat(server.getServlet().getSession().getTimeout()).isEqualTo(Duration.ofHours(12));
        assertThat(server.getServlet().getSession().getCookie().getMaxAge()).isEqualTo(Duration.ofHours(12));
    }
}
```

`src/test/java/com/vladoose/nir/passkeys/PasskeyAccountLinkTest.java`:

```java
package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Ключи привязаны к учётке каскадом по логину (V20, спека passkeys-login §4). Spring находит владельца ключа
 * по ЛОГИНУ, а логин в «Пользователях» редактируется: без каскада ключи удалённой учётки достались бы
 * новой учётке с тем же логином.
 */
@SpringBootTest
@Transactional
class PasskeyAccountLinkTest {

    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");

    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;

    @Test
    void renamedAccountKeepsItsKeys() {
        UserAccount account = PasskeyFixtures.user(users, "pk-link-old");
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, "pk-link-old");
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        account.setUsername("pk-link-new");
        users.saveAndFlush(account);

        assertThat(entities.findByUsername("pk-link-old")).isNull();
        assertThat(entities.findByUsername("pk-link-new").getId()).isEqualTo(owner.getId());
        assertThat(credentials.findByCredentialId(key)).isNotNull();
    }

    @Test
    void deletedAccountTakesItsKeysAndNewAccountWithSameLoginGetsNone() {
        UserAccount account = PasskeyFixtures.user(users, "pk-link-gone");
        PublicKeyCredentialUserEntity owner = PasskeyFixtures.owner(entities, "pk-link-gone");
        Bytes key = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);

        users.delete(account);
        users.flush();
        assertThat(credentials.findByCredentialId(key)).isNull();
        assertThat(entities.findById(owner.getId())).isNull();

        PasskeyFixtures.user(users, "pk-link-gone");
        assertThat(entities.findByUsername("pk-link-gone")).as("новая учётка чужих ключей не получает").isNull();
    }

    @Test
    void webAuthnUserNeedsExistingAccount() {
        assertThatThrownBy(() -> PasskeyFixtures.owner(entities, "pk-link-nobody"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void oneWebAuthnUserPerLogin() {
        PasskeyFixtures.user(users, "pk-link-twice");
        PasskeyFixtures.owner(entities, "pk-link-twice");
        assertThatThrownBy(() -> PasskeyFixtures.owner(entities, "pk-link-twice"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.*'`
Expected: ERROR у всех тестов — `No qualifying bean of type 'org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository'`.

- [ ] **Step 3: Зависимость**

В `build.gradle` сразу после строки `implementation 'org.springframework.boot:spring-boot-starter-security'`:

```groovy
    // вход по Face ID / Touch ID (passkeys): WebAuthn в Spring Security 6.5.5 требует webauthn4j — ровно
    // та версия, с которой собран Spring 6.5.5 (gradle/libs.versions.toml тега 6.5.5), спека passkeys-login §1.1
    implementation 'com.webauthn4j:webauthn4j-core:0.29.5.RELEASE'
```

- [ ] **Step 4: Миграция**

`src/main/resources/db/migration/V20__passkeys.sql`:

```sql
-- Вход по Face ID / Touch ID — passkeys (спека docs/superpowers/specs/2026-09-28-passkeys-login-design.md §4).
-- Хранилище ключей — схема Spring Security 6.5.5 без изменений колонок (user-entities-schema.sql и
-- user-credentials-schema-postgres.sql из spring-security-web): её читают и пишут
-- JdbcPublicKeyCredentialUserEntityRepository и JdbcUserCredentialRepository. Таблицы общие, без рынка.
CREATE TABLE user_entities (
    id           VARCHAR(1000) NOT NULL,   -- случайный id пользователя WebAuthn (base64url), не user_account.id
    name         VARCHAR(100)  NOT NULL,   -- логин = user_account.username
    display_name VARCHAR(200),
    PRIMARY KEY (id)
);

CREATE TABLE user_credentials (
    credential_id                VARCHAR(1000) NOT NULL,   -- id ключа (base64url)
    user_entity_user_id          VARCHAR(1000) NOT NULL,
    public_key                   BYTEA         NOT NULL,   -- ОТКРЫТЫЙ ключ; закрытый не покидает устройство
    signature_count              BIGINT,
    uv_initialized               BOOLEAN,
    backup_eligible              BOOLEAN       NOT NULL,
    authenticator_transports     VARCHAR(1000),
    public_key_credential_type   VARCHAR(100),
    backup_state                 BOOLEAN       NOT NULL,
    attestation_object           BYTEA,
    attestation_client_data_json BYTEA,
    created                      TIMESTAMP,
    last_used                    TIMESTAMP,
    label                        VARCHAR(1000) NOT NULL,   -- подпись («iPhone · Safari»)
    PRIMARY KEY (credential_id)
);

-- Наше дополнение: ключи намертво привязаны к учётке. Spring ищет владельца ключа по ЛОГИНУ, а логин
-- в «Пользователях» редактируется: переименование переносит ключи, удаление учётки удаляет их, и новая
-- учётка со старым логином чужих ключей не получает.
ALTER TABLE user_entities ADD CONSTRAINT uq_user_entities_name UNIQUE (name);
ALTER TABLE user_entities ADD CONSTRAINT fk_user_entities_account
    FOREIGN KEY (name) REFERENCES user_account (username) ON UPDATE CASCADE ON DELETE CASCADE;
ALTER TABLE user_credentials ADD CONSTRAINT fk_user_credentials_entity
    FOREIGN KEY (user_entity_user_id) REFERENCES user_entities (id) ON DELETE CASCADE;
CREATE INDEX idx_user_credentials_entity ON user_credentials (user_entity_user_id);
```

- [ ] **Step 5: Хранилища ключей**

`src/main/java/com/vladoose/nir/config/PasskeyConfig.java`:

```java
package com.vladoose.nir.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.security.web.webauthn.management.JdbcPublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.JdbcUserCredentialRepository;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;

/**
 * Хранилища ключей входа (passkeys) — таблицы Spring из V20. http.webAuthn() находит эти бины сам;
 * без них Spring держит ключи в памяти и теряет их при каждом рестарте (спека passkeys-login §5.1).
 */
@Configuration
public class PasskeyConfig {

    @Bean
    public PublicKeyCredentialUserEntityRepository passkeyUserEntities(JdbcOperations jdbc) {
        return new JdbcPublicKeyCredentialUserEntityRepository(jdbc);
    }

    @Bean
    public UserCredentialRepository passkeyCredentials(JdbcOperations jdbc) {
        return new JdbcUserCredentialRepository(jdbc);
    }
}
```

- [ ] **Step 6: Spring Security — passkeys и правила**

Заменить весь `src/main/java/com/vladoose/nir/config/SecurityConfig.java` на:

```java
package com.vladoose.nir.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Value("${passkeys.rp-id}") String passkeyRpId,
                                           @Value("${passkeys.allowed-origins}") String[] passkeyOrigins) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED))
                .authorizeHttpRequests(auth -> auth
                        // вход по ключу (passkeys): встроенное удаление ключа в Spring 6.5.5 не проверяет ни владельца,
                        // ни даже вход, а его фильтр стоит после этой проверки прав — закрыто для всех; свои ключи
                        // удаляются через /api/passkeys/{id} (спека passkeys-login §5.2)
                        .requestMatchers(HttpMethod.DELETE, "/webauthn/**").denyAll()
                        .requestMatchers("/webauthn/register", "/webauthn/register/**").authenticated()
                        .requestMatchers("/api/auth/**").permitAll()
                        // калитка ais.westmed.kz: её зовут nginx (auth_request) и недопущенное устройство — до входа в АИС
                        .requestMatchers("/api/gate/**").permitAll()
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().permitAll()
                )
                // встроенные эндпоинты passkeys: POST /webauthn/register/options, /webauthn/register,
                // /webauthn/authenticate/options, /login/webauthn; ключ навсегда привязан к домену rpId
                .webAuthn(w -> w
                        .rpName("АИС Медзакупки")
                        .rpId(passkeyRpId)
                        .allowedOrigins(passkeyOrigins)
                        .disableDefaultRegistrationPage(true))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                )
                .formLogin(form -> form.disable())
                .httpBasic(basic -> basic.disable())
                .logout(logout -> logout.disable());

        return http.build();
    }
}
```

- [ ] **Step 7: Настройки — домен ключей и сессия**

`src/main/resources/application.yaml`: блок

```yaml
server:
  port: 8080
```

заменить на

```yaml
server:
  port: 8080
  servlet:
    session:
      # «рабочий день» (спека passkeys-login §5.3): вход живёт до 12 ч с момента входа и переживает закрытие
      # браузера; было — 30 мин бездействия (умолчание Spring Boot) и cookie до закрытия браузера
      timeout: 12h
      cookie:
        max-age: 12h

# Вход по Face ID / Touch ID — passkeys (спека docs/superpowers/specs/2026-09-28-passkeys-login-design.md).
# Ключ навсегда привязан к домену rp-id: здесь — разработка (ng serve на :4200), прод — application-prod.yaml.
passkeys:
  rp-id: localhost
  allowed-origins: http://localhost:4200
```

В конец `src/main/resources/application-prod.yaml` дописать:

```yaml

# Вход по Face ID / Touch ID: ключи привязаны к домену прода (спека passkeys-login §5.1).
# На Tailscale и SSH-туннеле кнопки входа по ключу нет — там вход паролем.
passkeys:
  rp-id: ais.westmed.kz
  allowed-origins: https://ais.westmed.kz
```

- [ ] **Step 8: Тесты проходят**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.*'`
Expected: PASS, 10 тестов (6 в `PasskeySetupTest`, 4 в `PasskeyAccountLinkTest`). В логе — `Successfully applied 1 migration … v20` при первом прогоне.

(`defaultRegistrationPageIsOff` печатает в лог стек `NoResourceFoundException` — так общий обработчик ведёт себя на любом неизвестном пути; это ожидаемо.)

- [ ] **Step 9: Прогон мутациями — тесты должны уметь падать**

По одной мутации, после каждой — тот же прогон, затем откат (CLAUDE.md §14):

| # | Мутация | Должен покраснеть |
|---|---|---|
| M1 | убрать строку `.requestMatchers(HttpMethod.DELETE, "/webauthn/**").denyAll()` | `builtInDeleteIsClosedForEveryone` (владелец получает 204 вместо 403) |
| M2 | `.disableDefaultRegistrationPage(false)` | `defaultRegistrationPageIsOff` |
| M3 | первой строкой в `renamedAccountKeepsItsKeys` и `deletedAccountTakesItsKeys…` — `jdbc.execute("ALTER TABLE user_entities DROP CONSTRAINT fk_user_entities_account");` (плюс поле `@Autowired JdbcTemplate jdbc;`; DDL в Postgres транзакционный — откатится вместе с тестом) | оба этих теста |

Если мутация НЕ краснит свой тест — тест вакуумный: исправить тест, а не мутацию.

- [ ] **Step 10: Commit**

```bash
git add build.gradle src/main/resources/db/migration/V20__passkeys.sql \
  src/main/java/com/vladoose/nir/config/PasskeyConfig.java src/main/java/com/vladoose/nir/config/SecurityConfig.java \
  src/main/resources/application.yaml src/main/resources/application-prod.yaml \
  src/test/java/com/vladoose/nir/passkeys/PasskeyFixtures.java \
  src/test/java/com/vladoose/nir/passkeys/PasskeySetupTest.java \
  src/test/java/com/vladoose/nir/passkeys/PasskeyAccountLinkTest.java
git commit -m "feat(passkeys): хранилище ключей (V20), включение WebAuthn, правила доступа, сессия 12 ч

Встроенное удаление ключа Spring 6.5.5 закрыто для всех (не проверяет владельца и вход).
Ключи привязаны к учётке каскадом по логину.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Полный цикл на эмуляторе ключа

Задача доказывает, что конфигурация Task 1 работает целиком: регистрация → вход одним ключом → сессия → `/api/auth/me`. Продуктового кода нет; если цикл не сходится — чинится конфигурация Task 1, а не тест.

**Files:**
- Create: `src/test/java/com/vladoose/nir/passkeys/VirtualPasskey.java`
- Test: `src/test/java/com/vladoose/nir/passkeys/PasskeyLoginFlowTest.java`

**Interfaces:**
- Consumes: всё из Task 1.
- Produces (для Task 3):
  - `VirtualPasskey` (package-private): конструктор `VirtualPasskey()`, `String credentialId()` (base64url), `String registrationBody(String optionsJson, String label, String origin)`, `String assertionBody(String optionsJson, String origin)`, `VirtualPasskey impostor()`; константы `ORIGIN = "http://localhost:4200"`, `RP_ID = "localhost"`;
  - в `PasskeyLoginFlowTest` — помощники `MockHttpSession loginWithPassword()`, `VirtualPasskey registerKey(MockHttpSession, String label)`, `String loginOptions(MockHttpSession)`, `ResultActions loginWithKey(MockHttpSession, String body)` и константа `PASSWORD_JSON`.

- [ ] **Step 1: Эмулятор ключа**

`src/test/java/com/vladoose/nir/passkeys/VirtualPasskey.java`:

```java
package com.vladoose.nir.passkeys;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webauthn4j.converter.AttestationObjectConverter;
import com.webauthn4j.converter.AuthenticatorDataConverter;
import com.webauthn4j.converter.util.ObjectConverter;
import com.webauthn4j.data.attestation.AttestationObject;
import com.webauthn4j.data.attestation.authenticator.AAGUID;
import com.webauthn4j.data.attestation.authenticator.AttestedCredentialData;
import com.webauthn4j.data.attestation.authenticator.AuthenticatorData;
import com.webauthn4j.data.attestation.authenticator.EC2COSEKey;
import com.webauthn4j.data.attestation.statement.COSEAlgorithmIdentifier;
import com.webauthn4j.data.attestation.statement.NoneAttestationStatement;
import com.webauthn4j.data.extension.authenticator.AuthenticationExtensionAuthenticatorOutput;
import com.webauthn4j.data.extension.authenticator.RegistrationExtensionAuthenticatorOutput;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Эмулятор ключа входа (passkey) для тестов: ES256 и аттестация «none» — как у Face ID / Touch ID.
 * Тела запросов — ровно в формате эталонного клиента Spring (spring-security-webauthn.js) и нашего фронта
 * (services/passkey.service.ts), включая поле credType при входе. Спека passkeys-login §6, §11.
 * Собран на webauthn4j-core и JDK: webauthn4j-test не берём — он тянет BouncyCastle и ещё три модуля.
 */
final class VirtualPasskey {

    static final String ORIGIN = "http://localhost:4200";   // passkeys.allowed-origins в application.yaml
    static final String RP_ID = "localhost";                 // passkeys.rp-id

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final ObjectConverter CBOR = new ObjectConverter();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final KeyPair keys;
    private final byte[] credentialId = new byte[16];
    private byte[] userHandle;          // user.id из параметров регистрации: устройство хранит его вместе с ключом
    private long signCount;

    VirtualPasskey() throws GeneralSecurityException {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec("secp256r1"));
        this.keys = g.generateKeyPair();
        RANDOM.nextBytes(credentialId);
    }

    String credentialId() {
        return b64(credentialId);
    }

    /** Ответ POST /webauthn/register/options → тело POST /webauthn/register. */
    String registrationBody(String optionsJson, String label, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        userHandle = Base64.getUrlDecoder().decode(options.at("/user/id").asText());
        byte[] clientData = clientData("webauthn.create", options.at("/challenge").asText(), origin);

        AttestedCredentialData attested = new AttestedCredentialData(AAGUID.ZERO, credentialId,
                EC2COSEKey.create((ECPublicKey) keys.getPublic(), COSEAlgorithmIdentifier.ES256));
        AuthenticatorData<RegistrationExtensionAuthenticatorOutput> authData = new AuthenticatorData<>(
                rpIdHash(), (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV | AuthenticatorData.BIT_AT),
                signCount, attested);
        byte[] attestationObject = new AttestationObjectConverter(CBOR)
                .convertToBytes(new AttestationObject(authData, new NoneAttestationStatement()));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("attestationObject", b64(attestationObject));
        response.put("clientDataJSON", b64(clientData));
        response.put("transports", List.of("internal"));
        Map<String, Object> credential = new LinkedHashMap<>();
        credential.put("id", b64(credentialId));
        credential.put("rawId", b64(credentialId));
        credential.put("response", response);
        credential.put("type", "public-key");
        credential.put("clientExtensionResults", Map.of());
        credential.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(Map.of("publicKey", Map.of("credential", credential, "label", label)));
    }

    /** Ответ POST /webauthn/authenticate/options → тело POST /login/webauthn. */
    String assertionBody(String optionsJson, String origin) throws Exception {
        JsonNode options = JSON.readTree(optionsJson);
        byte[] clientData = clientData("webauthn.get", options.at("/challenge").asText(), origin);
        signCount++;
        byte[] authData = new AuthenticatorDataConverter(CBOR).convert(
                new AuthenticatorData<AuthenticationExtensionAuthenticatorOutput>(
                        rpIdHash(), (byte) (AuthenticatorData.BIT_UP | AuthenticatorData.BIT_UV), signCount));

        Signature s = Signature.getInstance("SHA256withECDSA");         // ES256: подпись в DER, как у WebAuthn
        s.initSign(keys.getPrivate());
        s.update(ByteBuffer.allocate(authData.length + 32).put(authData).put(sha256(clientData)).array());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("authenticatorData", b64(authData));
        response.put("clientDataJSON", b64(clientData));
        response.put("signature", b64(s.sign()));
        response.put("userHandle", b64(userHandle));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", b64(credentialId));
        body.put("rawId", b64(credentialId));
        body.put("response", response);
        body.put("credType", "public-key");        // так шлёт эталонный клиент Spring — повторяем как есть
        body.put("clientExtensionResults", Map.of());
        body.put("authenticatorAttachment", "platform");
        return JSON.writeValueAsString(body);
    }

    /** Чужой ключ, выдающий себя за этот: тот же id и userHandle, но своя пара ключей — подпись не сойдётся. */
    VirtualPasskey impostor() throws GeneralSecurityException {
        VirtualPasskey other = new VirtualPasskey();
        System.arraycopy(credentialId, 0, other.credentialId, 0, credentialId.length);
        other.userHandle = userHandle;
        other.signCount = signCount;
        return other;
    }

    private static byte[] clientData(String type, String challenge, String origin) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("challenge", challenge);
        m.put("origin", origin);
        m.put("crossOrigin", false);
        return JSON.writeValueAsBytes(m);
    }

    private static byte[] rpIdHash() throws GeneralSecurityException {
        return sha256(RP_ID.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] sha256(byte[] data) throws GeneralSecurityException {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String b64(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }
}
```

- [ ] **Step 2: Тест полного цикла и отказов**

`src/test/java/com/vladoose/nir/passkeys/PasskeyLoginFlowTest.java`:

```java
package com.vladoose.nir.passkeys;

import com.vladoose.nir.entity.UserAccount;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Полный цикл passkeys на эмуляторе ключа: регистрация в сессии, открытой паролем, → вход одним ключом
 * в новой сессии → /api/auth/me (спека passkeys-login §11). Плюс отказы: чужая подпись, удалённый ключ,
 * чужой origin, повтор того же ответа.
 */
@SpringBootTest
@Transactional
class PasskeyLoginFlowTest {

    static final String USER = "pk-flow";
    static final String PASSWORD = "flow-secret-pass";
    static final String PASSWORD_JSON = "{\"username\":\"" + USER + "\",\"password\":\"" + PASSWORD + "\"}";

    @Autowired WebApplicationContext wac;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired UserCredentialRepository credentials;

    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        users.saveAndFlush(UserAccount.builder().username(USER).fullName("Проверка Ключей")
                .role("ROLE_ADMIN").passwordHash(encoder.encode(PASSWORD)).build());
    }

    @Test
    void registerWithPasswordSessionThenLogInWithKeyAlone() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");

        MockHttpSession fresh = new MockHttpSession();          // другой браузер: ни пароля, ни сессии
        loginWithKey(fresh, key.assertionBody(loginOptions(fresh), VirtualPasskey.ORIGIN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.authenticated").value(true));
        mvc.perform(get("/api/auth/me").session(fresh))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value(USER))
                .andExpect(jsonPath("$.role").value("ROLE_ADMIN"));

        CredentialRecord stored = credentials.findByCredentialId(Bytes.fromBase64(key.credentialId()));
        assertThat(stored.getLabel()).isEqualTo("iPhone · Safari");
        assertThat(stored.getLastUsed()).as("вход ключом отмечен").isAfter(stored.getCreated());
    }

    @Test
    void foreignSignatureIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.impostor().assertionBody(loginOptions(s), VirtualPasskey.ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deletedKeyIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        credentials.delete(Bytes.fromBase64(key.credentialId()));
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.assertionBody(loginOptions(s), VirtualPasskey.ORIGIN))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void foreignOriginIsRejected() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        loginWithKey(s, key.assertionBody(loginOptions(s), "https://evil.example"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void assertionCannotBeReplayed() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession s = new MockHttpSession();
        String body = key.assertionBody(loginOptions(s), VirtualPasskey.ORIGIN);
        loginWithKey(s, body).andExpect(status().isOk());
        loginWithKey(s, body).andExpect(status().isUnauthorized());   // вызов одноразовый: параметры уже сняты с сессии
    }

    // --- помощники (ими пользуется и Task 3) ---

    MockHttpSession loginWithPassword() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON).content(PASSWORD_JSON))
                .andExpect(status().isOk());
        return session;
    }

    VirtualPasskey registerKey(MockHttpSession session, String label) throws Exception {
        String options = mvc.perform(post("/webauthn/register/options").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        VirtualPasskey key = new VirtualPasskey();
        mvc.perform(post("/webauthn/register").session(session).contentType(APPLICATION_JSON)
                        .content(key.registrationBody(options, label, VirtualPasskey.ORIGIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
        return key;
    }

    String loginOptions(MockHttpSession session) throws Exception {
        return mvc.perform(post("/webauthn/authenticate/options").session(session))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    ResultActions loginWithKey(MockHttpSession session, String body) throws Exception {
        return mvc.perform(post("/login/webauthn").session(session).contentType(APPLICATION_JSON).content(body));
    }
}
```

- [ ] **Step 3: Прогон**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.PasskeyLoginFlowTest'`
Expected: PASS, 5 тестов.

Если регистрация не проходит или вход даёт 401 там, где ждём 200: при регистрации 400 означает нечитаемое тело или отсутствие параметров в сессии, а ошибку ПРОВЕРКИ ключа фильтр Spring не превращает в статус — она вылетает из `perform()` исключением (в живом Tomcat это 500). При входе любая ошибка — 401. Причину Spring пишет только на уровне DEBUG. Временно добавить в `application.yaml` `logging.level.org.springframework.security.web.webauthn: DEBUG` и повторить (`@TestPropertySource` нельзя — лишний контекст). Типичные причины: origin в `clientDataJSON` не совпал с `passkeys.allowed-origins`; хеш rpId не совпал с `passkeys.rp-id`; не тот base64 (нужен base64url без `=`). Правку логирования откатить.

- [ ] **Step 4: Прогон мутацией**

Временно `passkeys.allowed-origins: https://evil.example` в `application.yaml` → `registerWithPasswordSessionThenLogInWithKeyAlone` краснеет на регистрации (исключение проверки origin из фильтра Spring). Откатить.

- [ ] **Step 5: Commit**

```bash
git add src/test/java/com/vladoose/nir/passkeys/VirtualPasskey.java \
  src/test/java/com/vladoose/nir/passkeys/PasskeyLoginFlowTest.java
git commit -m "test(passkeys): полный цикл на эмуляторе ключа — регистрация, вход, отказы

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Смена id сессии при входе

**Files:**
- Create: `src/main/java/com/vladoose/nir/security/SessionIdRotationFilter.java`
- Modify: `src/main/java/com/vladoose/nir/config/SecurityConfig.java`
- Modify: `src/main/java/com/vladoose/nir/controller/AuthController.java` (метод `login`)
- Test: `src/test/java/com/vladoose/nir/passkeys/PasskeyLoginFlowTest.java` (два теста)

**Interfaces:**
- Consumes: помощники `PasskeyLoginFlowTest` из Task 2.
- Produces: `SessionIdRotationFilter extends OncePerRequestFilter` (без Spring-аннотаций — создаётся через `new` в `SecurityConfig`).

- [ ] **Step 1: Написать падающие тесты**

В `PasskeyLoginFlowTest` перед строкой `// --- помощники (ими пользуется и Task 3) ---` вставить:

```java
    @Test
    void passwordLoginChangesSessionId() throws Exception {
        MockHttpSession session = new MockHttpSession();
        String before = session.getId();
        mvc.perform(post("/api/auth/login").session(session).contentType(APPLICATION_JSON).content(PASSWORD_JSON))
                .andExpect(status().isOk());
        assertThat(session.getId()).as("id сессии после входа паролем").isNotEqualTo(before);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }

    @Test
    void keyLoginChangesSessionId() throws Exception {
        VirtualPasskey key = registerKey(loginWithPassword(), "iPhone · Safari");
        MockHttpSession session = new MockHttpSession();
        String options = loginOptions(session);                 // сессия открыта ещё до входа — её и подменяют
        String before = session.getId();
        loginWithKey(session, key.assertionBody(options, VirtualPasskey.ORIGIN)).andExpect(status().isOk());
        assertThat(session.getId()).as("id сессии после входа ключом").isNotEqualTo(before);
        mvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());
    }
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.PasskeyLoginFlowTest'`
Expected: FAIL в `passwordLoginChangesSessionId` и `keyLoginChangesSessionId` — `Expecting actual not to be equal to …` (id прежний).

- [ ] **Step 3: Фильтр**

`src/main/java/com/vladoose/nir/security/SessionIdRotationFilter.java`:

```java
package com.vladoose.nir.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Меняет id сессии перед входом по ключу (POST /login/webauthn): встроенный фильтр Spring 6.5.5 его не меняет.
 * Сессия, открытая до входа, могла быть подсунута — например, cookie JSESSIONID на весь домен с другого сайта
 * *.westmed.kz; после входа такой id ничего не стоит. Содержимое сессии (в том числе параметры входа по ключу)
 * сохраняется, cookie выдаётся заново. Спека passkeys-login §5.3.
 * Не бин: @Component зарегистрировал бы его ещё и общим сервлет-фильтром. Создаётся в SecurityConfig.
 */
public class SessionIdRotationFilter extends OncePerRequestFilter {

    // тот же матчер, что у самого фильтра входа по ключу в Spring
    private static final RequestMatcher KEY_LOGIN =
            PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/login/webauthn");

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !KEY_LOGIN.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getSession(false) != null) {
            request.changeSessionId();
        }
        chain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Поставить фильтр в цепочку**

В `SecurityConfig`:
- импорты: `import com.vladoose.nir.security.SessionIdRotationFilter;` и `import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;`;
- после блока `.webAuthn(w -> w … .disableDefaultRegistrationPage(true))` вставить:

```java
                // смена id сессии перед входом по ключу; место фильтра формы входа (она выключена) — раньше
                // фильтра Spring для /login/webauthn, который стоит перед BasicAuthenticationFilter
                .addFilterBefore(new SessionIdRotationFilter(), UsernamePasswordAuthenticationFilter.class)
```

- [ ] **Step 5: Смена id при входе паролем**

В `AuthController.login` сразу после строки `Authentication auth = authenticationManager.authenticate(token);` вставить:

```java

            // сессия, открытая до входа, могла быть подсунута — после входа её id ничего не стоит (спека passkeys-login §5.3)
            if (request.getSession(false) != null) {
                request.changeSessionId();
            }
```

- [ ] **Step 6: Тесты проходят**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.*'`
Expected: PASS, 17 тестов.

- [ ] **Step 7: Прогон мутациями**

| # | Мутация | Должен покраснеть |
|---|---|---|
| M4 | закомментировать `request.changeSessionId();` в фильтре | `keyLoginChangesSessionId` |
| M5 | закомментировать `request.changeSessionId();` в `AuthController.login` | `passwordLoginChangesSessionId` |
| M6 | поставить фильтр `addFilterAfter(…, BasicAuthenticationFilter.class)` — ПОСЛЕ фильтра Spring | `keyLoginChangesSessionId` (успешный вход по ключу цепочку дальше не продолжает — фильтр не срабатывает) |

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/vladoose/nir/security/SessionIdRotationFilter.java \
  src/main/java/com/vladoose/nir/config/SecurityConfig.java \
  src/main/java/com/vladoose/nir/controller/AuthController.java \
  src/test/java/com/vladoose/nir/passkeys/PasskeyLoginFlowTest.java
git commit -m "feat(passkeys): смена id сессии при каждом входе — паролем и ключом

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Свои ключи и домен ключей — REST

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/PasskeyResponse.java`, `PasskeyListResponse.java`, `PasskeyConfigResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/PasskeyService.java`
- Create: `src/main/java/com/vladoose/nir/controller/PasskeyController.java`
- Modify: `src/main/java/com/vladoose/nir/controller/AuthController.java` (конструктор, новый эндпоинт)
- Test: `src/test/java/com/vladoose/nir/passkeys/PasskeyControllerTest.java`

**Interfaces:**
- Consumes: `PasskeyFixtures` (Task 1); `UserAgentSummary.describe(String) → String` (калитка, `util/UserAgentSummary.java`); `NotFoundException(String)` → 404 (`GlobalExceptionHandler`).
- Produces (для фронта, Task 5–6):
  - `GET /api/passkeys` → `PasskeyListResponse {keys: PasskeyResponse[], suggestedLabel: string}`, где `PasskeyResponse {id: string, label: string, createdAt: ISO, lastUsedAt: ISO|null}`;
  - `DELETE /api/passkeys/{id}` → 204 или 404;
  - `GET /api/auth/passkey-config` → `PasskeyConfigResponse {rpId: string}`;
  - `PasskeyService.list(String username) → List<PasskeyResponse>`, `PasskeyService.delete(String username, String credentialId)`;
  - `PasskeyController.list(String userAgent) → PasskeyListResponse`, `PasskeyController.delete(String id)`, `AuthController.passkeyConfig() → PasskeyConfigResponse`.

- [ ] **Step 1: Написать падающие тесты**

`src/test/java/com/vladoose/nir/passkeys/PasskeyControllerTest.java`:

```java
package com.vladoose.nir.passkeys;

import com.vladoose.nir.controller.AuthController;
import com.vladoose.nir.controller.PasskeyController;
import com.vladoose.nir.dto.response.PasskeyListResponse;
import com.vladoose.nir.dto.response.PasskeyResponse;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.UserAccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** «Мой профиль»: только свои ключи (спека passkeys-login §5.4, §6). Контроллеры — напрямую, как в проекте. */
@SpringBootTest
@Transactional
class PasskeyControllerTest {

    static final Instant T = Instant.parse("2026-09-01T10:00:00Z");
    static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1";

    @Autowired PasskeyController controller;
    @Autowired AuthController auth;
    @Autowired UserAccountRepository users;
    @Autowired PublicKeyCredentialUserEntityRepository entities;
    @Autowired UserCredentialRepository credentials;
    @Autowired WebApplicationContext wac;

    PublicKeyCredentialUserEntity owner;
    PublicKeyCredentialUserEntity other;

    @BeforeEach
    void setUp() {
        PasskeyFixtures.user(users, "pk-owner");
        PasskeyFixtures.user(users, "pk-other");
        owner = PasskeyFixtures.owner(entities, "pk-owner");
        other = PasskeyFixtures.owner(entities, "pk-other");
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void listsOnlyOwnKeysOldestFirstWithSuggestedLabel() {
        PasskeyFixtures.key(credentials, owner, "Mac · Chrome", T.plusSeconds(60), T.plusSeconds(60));
        PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);
        PasskeyFixtures.key(credentials, other, "Чужой", T, T);

        PasskeyListResponse r = controller.list(IPHONE);

        assertThat(r.getKeys()).extracting(PasskeyResponse::getLabel).containsExactly("iPhone · Safari", "Mac · Chrome");
        assertThat(r.getSuggestedLabel()).isEqualTo("iPhone · Safari");
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void keyThatNeverLoggedInHasNoLastUse() {
        PasskeyFixtures.key(credentials, owner, "новый", T, T);                      // так Spring сохраняет новый ключ
        PasskeyFixtures.key(credentials, owner, "рабочий", T, T.plusSeconds(3600));

        List<PasskeyResponse> keys = controller.list(null).getKeys();

        assertThat(keys).filteredOn(k -> k.getLabel().equals("новый")).singleElement()
                .extracting(PasskeyResponse::getLastUsedAt).isNull();
        assertThat(keys).filteredOn(k -> k.getLabel().equals("рабочий")).singleElement()
                .extracting(PasskeyResponse::getLastUsedAt).isEqualTo(T.plusSeconds(3600));
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void deletesOwnKey() {
        Bytes mine = PasskeyFixtures.key(credentials, owner, "iPhone · Safari", T, T);
        controller.delete(mine.toBase64UrlString());
        assertThat(credentials.findByCredentialId(mine)).isNull();
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void foreignKeyLooksMissingAndStays() {
        Bytes theirs = PasskeyFixtures.key(credentials, other, "Чужой", T, T);
        assertThatThrownBy(() -> controller.delete(theirs.toBase64UrlString())).isInstanceOf(NotFoundException.class);
        assertThat(credentials.findByCredentialId(theirs)).as("чужой ключ на месте").isNotNull();
    }

    @Test
    @WithMockUser(username = "pk-owner")
    void unknownOrGarbageIdIsNotFound() {
        assertThatThrownBy(() -> controller.delete(Bytes.random().toBase64UrlString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.delete("не base64!")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void passkeyConfigNamesTheDomain() {
        assertThat(auth.passkeyConfig().getRpId()).isEqualTo("localhost");
    }

    @Test
    void httpRules() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        mvc.perform(get("/api/passkeys")).andExpect(status().isUnauthorized());
        mvc.perform(delete("/api/passkeys/abc")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/passkey-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rpId").value("localhost"));
    }
}
```

- [ ] **Step 2: Убедиться, что тесты падают**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.PasskeyControllerTest'`
Expected: FAIL компиляции — `cannot find symbol: class PasskeyController` (и `PasskeyListResponse`, `PasskeyResponse`, `passkeyConfig`).

- [ ] **Step 3: DTO**

`src/main/java/com/vladoose/nir/dto/response/PasskeyResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/** Ключ входа (passkey) в «Моём профиле» (спека passkeys-login §6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyResponse {
    private String id;               // id ключа, base64url — для удаления
    private String label;            // «iPhone · Safari»
    private Instant createdAt;
    private Instant lastUsedAt;      // null — ключом ещё не входили
}
```

`src/main/java/com/vladoose/nir/dto/response/PasskeyListResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/** Свои ключи и подпись нового ключа по умолчанию — из User-Agent этого же запроса. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyListResponse {
    private List<PasskeyResponse> keys = new ArrayList<>();
    private String suggestedLabel;
}
```

`src/main/java/com/vladoose/nir/dto/response/PasskeyConfigResponse.java`:

```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Домен ключей входа: фронт показывает кнопку «Войти с Face ID» только на нём (спека passkeys-login §6). */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class PasskeyConfigResponse {
    private String rpId;
}
```

- [ ] **Step 4: Сервис**

`src/main/java/com/vladoose/nir/service/PasskeyService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.response.PasskeyResponse;
import com.vladoose.nir.exception.NotFoundException;
import org.springframework.security.web.webauthn.api.Bytes;
import org.springframework.security.web.webauthn.api.CredentialRecord;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialUserEntity;
import org.springframework.security.web.webauthn.management.PublicKeyCredentialUserEntityRepository;
import org.springframework.security.web.webauthn.management.UserCredentialRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/**
 * Ключи входа (passkeys) пользователя — посмотреть и удалить свои (спека passkeys-login §5.4). Регистрацию и вход
 * ведёт Spring Security (http.webAuthn); здесь — то, чего у него нет: встроенное удаление Spring не проверяет
 * владельца и закрыто (SecurityConfig).
 */
@Service
public class PasskeyService {

    private final PublicKeyCredentialUserEntityRepository userEntities;
    private final UserCredentialRepository credentials;

    public PasskeyService(PublicKeyCredentialUserEntityRepository userEntities, UserCredentialRepository credentials) {
        this.userEntities = userEntities;
        this.credentials = credentials;
    }

    public List<PasskeyResponse> list(String username) {
        PublicKeyCredentialUserEntity owner = userEntities.findByUsername(username);
        if (owner == null) return List.of();
        return credentials.findByUserId(owner.getId()).stream()
                .sorted(Comparator.comparing(CredentialRecord::getCreated, Comparator.nullsLast(Comparator.naturalOrder())))
                .map(PasskeyService::toResponse)
                .toList();
    }

    /**
     * Удаляет свой ключ. Нет такого, чужой или битый id — одинаково «не найден»: по ответу нельзя узнать,
     * существует ли чужой ключ.
     */
    @Transactional
    public void delete(String username, String credentialId) {
        CredentialRecord key = find(credentialId);
        PublicKeyCredentialUserEntity owner = userEntities.findByUsername(username);
        if (key == null || owner == null || !owner.getId().equals(key.getUserEntityUserId())) {
            throw new NotFoundException("Ключ не найден");
        }
        credentials.delete(key.getCredentialId());
    }

    private CredentialRecord find(String credentialId) {
        try {
            return credentials.findByCredentialId(Bytes.fromBase64(credentialId));
        } catch (IllegalArgumentException notBase64Url) {
            return null;
        }
    }

    private static PasskeyResponse toResponse(CredentialRecord c) {
        // Spring при регистрации ставит last_used = created: такой ключ ещё не входил
        Instant lastUsed = c.getLastUsed() == null || c.getLastUsed().equals(c.getCreated()) ? null : c.getLastUsed();
        return new PasskeyResponse(c.getCredentialId().toBase64UrlString(), c.getLabel(), c.getCreated(), lastUsed);
    }
}
```

- [ ] **Step 5: Контроллер**

`src/main/java/com/vladoose/nir/controller/PasskeyController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.response.PasskeyListResponse;
import com.vladoose.nir.service.PasskeyService;
import com.vladoose.nir.util.UserAgentSummary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/** «Мой профиль»: свои ключи входа — любому вошедшему, только свои (спека passkeys-login §6). */
@RestController
@RequestMapping("/api/passkeys")
public class PasskeyController {

    private final PasskeyService passkeys;

    public PasskeyController(PasskeyService passkeys) {
        this.passkeys = passkeys;
    }

    @GetMapping
    public PasskeyListResponse list(@RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        return new PasskeyListResponse(passkeys.list(currentUser()), UserAgentSummary.describe(userAgent));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id) {
        passkeys.delete(currentUser(), id);
    }

    // после входа по ключу принципал — PublicKeyCredentialUserEntity, а не UserDetails: только getName()
    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

- [ ] **Step 6: Домен ключей в `AuthController`**

- импорты: `import com.vladoose.nir.dto.response.PasskeyConfigResponse;` и `import org.springframework.beans.factory.annotation.Value;`;
- поле и конструктор заменить на:

```java
    private final AuthenticationManager authenticationManager;
    private final UserAccountRepository userRepository;
    private final String passkeyRpId;
    private final SecurityContextRepository contextRepository = new HttpSessionSecurityContextRepository();

    public AuthController(AuthenticationManager authenticationManager, UserAccountRepository userRepository,
                          @Value("${passkeys.rp-id}") String passkeyRpId) {
        this.authenticationManager = authenticationManager;
        this.userRepository = userRepository;
        this.passkeyRpId = passkeyRpId;
    }
```

- после метода `me()` добавить:

```java

    /** Домен ключей входа: кнопка «Войти с Face ID» показывается только на нём (спека passkeys-login §6). */
    @GetMapping("/passkey-config")
    public PasskeyConfigResponse passkeyConfig() {
        return new PasskeyConfigResponse(passkeyRpId);
    }
```

- [ ] **Step 7: Тесты проходят**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.passkeys.*'`
Expected: PASS, 24 теста.

- [ ] **Step 8: Прогон мутацией**

M7: в `PasskeyService.delete` убрать из условия `|| !owner.getId().equals(key.getUserEntityUserId())` → краснеет `foreignKeyLooksMissingAndStays`. Откатить.

- [ ] **Step 9: Полный гейт бэкенда**

Run (sandbox off): `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test`
Expected: 0 падений; тестов — 553 + 24 = 577.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/PasskeyResponse.java \
  src/main/java/com/vladoose/nir/dto/response/PasskeyListResponse.java \
  src/main/java/com/vladoose/nir/dto/response/PasskeyConfigResponse.java \
  src/main/java/com/vladoose/nir/service/PasskeyService.java \
  src/main/java/com/vladoose/nir/controller/PasskeyController.java \
  src/main/java/com/vladoose/nir/controller/AuthController.java \
  src/test/java/com/vladoose/nir/passkeys/PasskeyControllerTest.java
git commit -m "feat(passkeys): свои ключи (/api/passkeys, удаление только своих) и домен ключей для фронта

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Фронт — сервис ключей, пути к бэкенду, «Мой профиль»

**Files:**
- Modify: `frontend/src/app/services/api.service.ts`
- Create: `frontend/src/app/services/passkey.service.ts`
- Create: `frontend/src/app/pages/profile/profile.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts`
- Modify: `frontend/proxy.conf.json`, `frontend/nginx.conf`

**Interfaces:**
- Consumes: REST из Task 4; встроенные эндпоинты Spring (Task 1); `AuthService.getUser() → AuthUser | null`, `AuthService.logout()`; `ConfirmService.ask(message, details, options) → Observable<boolean>`; `NotificationService.success/info/error(string)`; `relativeTime`, `fullDateTime` из `shared/relative-time`.
- Produces (для Task 6):
  - `PasskeyService.usableHere(rpId: string | null | undefined): boolean`;
  - `PasskeyService.login(): Promise<PasskeyOutcome>` и `PasskeyService.register(label: string): Promise<PasskeyOutcome>`;
  - `type PasskeyOutcome = 'ok' | 'cancelled' | 'exists' | 'rejected' | 'session' | 'failed'`;
  - `ApiService.getPasskeyConfig(): Observable<{ rpId: string }>`.

- [ ] **Step 1: Методы API**

В `frontend/src/app/services/api.service.ts` после метода `revokeDevice(...)` (перед `// === Шаблон письма КП ===`) вставить:

```ts

  // === Вход по ключу (passkeys): свои ключи — «Мой профиль»; домен ключей — для кнопки входа ===
  getPasskeys(): Observable<{ keys: any[]; suggestedLabel: string }> {
    return this.http.get<{ keys: any[]; suggestedLabel: string }>(`${this.base}/passkeys`);
  }
  deletePasskey(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/passkeys/${encodeURIComponent(id)}`);
  }
  getPasskeyConfig(): Observable<{ rpId: string }> {
    return this.http.get<{ rpId: string }>(`${this.base}/auth/passkey-config`);
  }
```

- [ ] **Step 2: Сервис ключей**

`frontend/src/app/services/passkey.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { HttpClient, HttpErrorResponse } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';

/** Исход церемонии — экран сам решает, что показать (спека passkeys-login §7.1, §8). */
export type PasskeyOutcome = 'ok' | 'cancelled' | 'exists' | 'rejected' | 'session' | 'failed';

/**
 * Вход и добавление ключа по Face ID / Touch ID (WebAuthn). Эндпоинты — встроенные в Spring Security 6.5.5,
 * тела — ровно как у эталонного клиента Spring (spring-security-webauthn.js из spring-security-web), включая
 * credType при входе. Тот же формат шлёт эмулятор ключа в тестах бэкенда (VirtualPasskey).
 * Запросы — через HttpClient: работают общие интерсепторы, в том числе уход на калитку по X-AIS-Gate.
 * Спека: docs/superpowers/specs/2026-09-28-passkeys-login-design.md §6–7.
 */
@Injectable({ providedIn: 'root' })
export class PasskeyService {
  constructor(private http: HttpClient) {}

  /** Ключ сработает только на своём домене и в браузере с WebAuthn — на Tailscale, туннеле и в старых браузерах нет. */
  usableHere(rpId: string | null | undefined): boolean {
    return !!rpId && typeof window.PublicKeyCredential === 'function' && location.hostname === rpId;
  }

  async login(): Promise<PasskeyOutcome> {
    let options: any;
    try {
      options = await firstValueFrom(this.http.post<any>('/webauthn/authenticate/options', null));
    } catch {
      return 'failed';
    }
    let cred: PublicKeyCredential | null;
    try {
      cred = (await navigator.credentials.get({
        publicKey: {
          ...options,
          challenge: fromB64url(options.challenge),
          allowCredentials: (options.allowCredentials || []).map((c: any) => ({ ...c, id: fromB64url(c.id) })),
        },
      })) as PublicKeyCredential | null;
    } catch (e) {
      return ceremonyError(e);
    }
    if (!cred) return 'cancelled';
    const r = cred.response as AuthenticatorAssertionResponse;
    const body = {
      id: cred.id,
      rawId: toB64url(cred.rawId),
      response: {
        authenticatorData: toB64url(r.authenticatorData),
        clientDataJSON: toB64url(r.clientDataJSON),
        signature: toB64url(r.signature),
        userHandle: r.userHandle ? toB64url(r.userHandle) : undefined,
      },
      credType: cred.type,                                    // так шлёт эталонный клиент Spring
      clientExtensionResults: cred.getClientExtensionResults(),
      authenticatorAttachment: cred.authenticatorAttachment,
    };
    try {
      const res = await firstValueFrom(this.http.post<any>('/login/webauthn', body));
      return res?.authenticated ? 'ok' : 'rejected';
    } catch (e) {
      return (e as HttpErrorResponse).status === 401 ? 'rejected' : 'failed';
    }
  }

  async register(label: string): Promise<PasskeyOutcome> {
    let options: any;
    try {
      options = await firstValueFrom(this.http.post<any>('/webauthn/register/options', null));
    } catch (e) {
      // без входа Spring 6.5.5 отвечает здесь 400, а не 401: фильтр стоит до проверки прав (спека §1.1 п. 7)
      const status = (e as HttpErrorResponse).status;
      return status === 400 || status === 401 ? 'session' : 'failed';
    }
    let cred: PublicKeyCredential | null;
    try {
      cred = (await navigator.credentials.create({
        publicKey: {
          ...options,
          user: { ...options.user, id: fromB64url(options.user.id) },
          challenge: fromB64url(options.challenge),
          excludeCredentials: (options.excludeCredentials || []).map((c: any) => ({ ...c, id: fromB64url(c.id) })),
        },
      })) as PublicKeyCredential | null;
    } catch (e) {
      return ceremonyError(e);
    }
    if (!cred) return 'cancelled';
    const r = cred.response as AuthenticatorAttestationResponse;
    const body = {
      publicKey: {
        credential: {
          id: cred.id,
          rawId: toB64url(cred.rawId),
          response: {
            attestationObject: toB64url(r.attestationObject),
            clientDataJSON: toB64url(r.clientDataJSON),
            transports: typeof r.getTransports === 'function' ? r.getTransports() : [],
          },
          type: cred.type,
          clientExtensionResults: cred.getClientExtensionResults(),
          authenticatorAttachment: cred.authenticatorAttachment,
        },
        label,
      },
    };
    try {
      const res = await firstValueFrom(this.http.post<any>('/webauthn/register', body));
      return res?.success ? 'ok' : 'failed';
    } catch (e) {
      return (e as HttpErrorResponse).status === 401 ? 'session' : 'failed';
    }
  }
}

/** Отмену и «ключа на устройстве нет» браузер не различает — оба NotAllowedError (спека §8). */
function ceremonyError(e: unknown): PasskeyOutcome {
  const name = (e as DOMException)?.name;
  if (name === 'InvalidStateError') return 'exists';                                 // ключ этой учётки на устройстве уже есть
  if (name === 'NotAllowedError' || name === 'AbortError') return 'cancelled';
  return 'failed';
}

function toB64url(buf: ArrayBuffer): string {
  const bytes = new Uint8Array(buf);
  let bin = '';
  for (let i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  return btoa(bin).replace(/=/g, '').replace(/\+/g, '-').replace(/\//g, '_');
}

function fromB64url(s: string): ArrayBuffer {
  const bin = atob(s.replace(/-/g, '+').replace(/_/g, '/'));        // atob принимает и без «=» — как у Spring
  const out = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) out[i] = bin.charCodeAt(i);
  return out.buffer;
}
```

- [ ] **Step 3: Страница «Мой профиль»**

`frontend/src/app/pages/profile/profile.component.ts`:

```ts
import { Component, ChangeDetectorRef, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService, AuthUser } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { PasskeyService } from '../../services/passkey.service';
import { fullDateTime, relativeTime } from '../../shared/relative-time';

/**
 * «Мой профиль» — учётная запись и вход по Face ID / Touch ID (passkeys): свои ключи, добавление, удаление.
 * Доступен всем вошедшим. Спека: docs/superpowers/specs/2026-09-28-passkeys-login-design.md §7.3.
 */
@Component({
  selector: 'app-profile',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <h2>Мой профиль</h2>

    <section class="block card" *ngIf="user">
      <div class="kv"><span class="k">ФИО</span><span>{{ user.fullName || '—' }}</span></div>
      <div class="kv"><span class="k">Логин</span><span>{{ user.username }}</span></div>
      <div class="kv"><span class="k">Роль</span><span>{{ user.role === 'ROLE_ADMIN' ? 'Администратор' : 'Оператор' }}</span></div>
    </section>

    <section class="block">
      <h3>Вход по Face ID / Touch ID</h3>
      <p class="subtitle">Ключ хранится на устройстве, Face ID и Touch ID его не покидают. Пароль продолжает работать.</p>

      <div class="error-banner" *ngIf="error">{{ error }}</div>

      <p class="empty" *ngIf="keys && !keys.length">Ключей пока нет. Добавьте — и входите без пароля.</p>
      <div class="key" *ngFor="let k of keys; trackBy: byId">
        <div class="key-main">
          <div class="key-name">{{ k.label }}</div>
          <div class="muted">
            добавлен <span [title]="full(k.createdAt)">{{ day(k.createdAt) }}</span> ·
            <ng-container *ngIf="k.lastUsedAt; else unused">последний вход: <span [title]="full(k.lastUsedAt)">{{ ago(k.lastUsedAt) }}</span></ng-container>
            <ng-template #unused>ещё не использовался</ng-template>
          </div>
        </div>
        <button type="button" class="btn btn-line" [disabled]="busy" (click)="remove(k)">Удалить</button>
      </div>

      <div class="add" *ngIf="usable">
        <input [(ngModel)]="label" maxlength="60" aria-label="Подпись ключа" placeholder="Подпись, например «iPhone · Safari»">
        <button type="button" class="btn btn-primary" [disabled]="busy || !label.trim()" (click)="add()">
          {{ busy ? 'Ждём Face ID / Touch ID…' : 'Добавить вход по Face ID / Touch ID' }}
        </button>
      </div>
      <p class="muted hint" *ngIf="!usable && !supported">Этот браузер не поддерживает вход по ключу.</p>
      <p class="muted hint" *ngIf="!usable && supported && rpId">Добавить ключ можно на https://{{ rpId }}.</p>
    </section>
  `,
  styles: [`
    h2 { margin: 0; }
    .block { margin-top: 20px; max-width: 720px; }
    .block h3 { margin: 0 0 4px; font-size: 16px; }
    .card { padding: 12px 16px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .kv { display: flex; gap: 12px; padding: 4px 0; font-size: 14px; }
    .kv .k { flex: none; width: 70px; color: var(--text-muted); }
    .muted { color: var(--text-muted); font-size: 13px; }
    .key { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap;
           margin-top: 8px; padding: 12px 14px; border: 1px solid var(--border); border-radius: 10px; background: var(--surface); }
    .key-name { font-weight: 600; }
    .add { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; margin-top: 14px; }
    .add input { flex: 1 1 220px; max-width: 320px; padding: 8px 10px; border: 1px solid var(--border); border-radius: 6px;
                 background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .hint { margin-top: 14px; }
    @media (max-width: 900px) {
      .add { flex-direction: column; align-items: stretch; }
      .add input { max-width: none; font-size: 16px; }
      .key .btn { width: 100%; }
    }
  `]
})
export class ProfileComponent implements OnInit {
  user: AuthUser | null = null;
  keys: any[] | null = null;
  label = '';
  rpId: string | null = null;
  readonly supported = typeof window.PublicKeyCredential === 'function';
  usable = false;
  busy = false;
  error = '';

  constructor(private auth: AuthService, private api: ApiService, private passkeys: PasskeyService,
              private confirm: ConfirmService, private notify: NotificationService,
              private router: Router, private cdr: ChangeDetectorRef) {}

  ngOnInit() {
    this.user = this.auth.getUser();
    this.api.getPasskeyConfig().subscribe({
      next: c => { this.rpId = c.rpId; this.usable = this.passkeys.usableHere(c.rpId); this.cdr.detectChanges(); },
    });
    this.load(true);
  }

  load(resetLabel = false) {
    this.api.getPasskeys().subscribe({
      next: r => {
        this.keys = r.keys;
        if (resetLabel || !this.label.trim()) this.label = r.suggestedLabel;
        this.error = '';
        this.cdr.detectChanges();
      },
      error: e => { this.error = e.error?.message || 'Не удалось загрузить ключи'; this.cdr.detectChanges(); },
    });
  }

  async add() {
    const label = this.label.trim();
    if (!label || this.busy) return;
    this.busy = true;
    this.cdr.detectChanges();
    const outcome = await this.passkeys.register(label);
    this.busy = false;
    switch (outcome) {
      case 'ok':
        this.notify.success('Ключ добавлен. В следующий раз нажмите «Войти с Face ID / Touch ID»');
        this.load(true);
        break;
      case 'exists':
        this.notify.info('На этом устройстве ключ для вашей учётки уже есть');
        break;
      case 'session':
        this.notify.error('Сессия истекла — войдите снова');
        this.auth.logout().subscribe(() => this.router.navigate(['/login']));
        break;
      case 'cancelled':
        break;                                             // закрыли системное окно — молча
      default:
        this.notify.error('Не удалось добавить ключ. Попробуйте ещё раз');
    }
    this.cdr.detectChanges();
  }

  remove(k: any) {
    this.confirm.ask(`Удалить ключ «${k.label}»?`,
        'Войти с ним больше не получится. Сам ключ останется в связке ключей устройства — его можно удалить в настройках паролей.',
        { danger: true, confirmLabel: 'Удалить' })
      .subscribe(yes => {
        if (!yes) return;
        this.busy = true;
        this.cdr.detectChanges();
        this.api.deletePasskey(k.id).subscribe({
          next: () => { this.busy = false; this.notify.success('Ключ удалён'); this.load(); },
          error: e => { this.busy = false; this.notify.error(e.error?.message || 'Не удалось удалить ключ'); this.load(); },
        });
      });
  }

  byId = (_: number, k: any) => k.id;
  ago(iso: string | null) { return relativeTime(iso); }
  full(iso: string | null) { return fullDateTime(iso); }
  day(iso: string | null): string {
    if (!iso) return '—';
    const t = new Date(iso);
    return isNaN(t.getTime()) ? '—' : new Intl.DateTimeFormat('ru-RU').format(t);      // 28.09.2026
  }
}
```

- [ ] **Step 4: Маршрут**

В `frontend/src/app/app.routes.ts`:
- после `import { DevicesComponent } from './pages/devices/devices.component';` добавить `import { ProfileComponent } from './pages/profile/profile.component';`;
- перед строкой `{ path: 'about', component: AboutComponent },` добавить `{ path: 'profile', component: ProfileComponent },` (всем вошедшим — без `adminGuard`).

- [ ] **Step 5: Пункт меню и имя в шапке**

В `frontend/src/app/layout/layout.component.ts`:
- в шапке `<span class="user-name">{{ user.fullName || user.username }}</span>` заменить на `<a class="user-name" routerLink="/profile" title="Мой профиль">{{ user.fullName || user.username }}</a>`;
- в группе «Система» перед ссылкой «О системе» (`<a routerLink="/about" routerLinkActive="active">`) вставить:

```html
            <a routerLink="/profile" routerLinkActive="active">
              <svg lucideIcon="user" [size]="16"></svg> Мой профиль
            </a>
```

- в стилях строку `.user-name { font-weight: 500; }` заменить на:

```css
    .user-name { font-weight: 500; color: inherit; text-decoration: none; }
    .user-name:hover { text-decoration: underline; }
```

(Иконка `user` уже зарегистрирована в `app.config.ts` — ею живёт шапка.)

- [ ] **Step 6: Прокси разработки**

`frontend/proxy.conf.json` заменить на:

```json
{
  "/api": {
    "target": "http://localhost:8080",
    "secure": false
  },
  "/webauthn": {
    "target": "http://localhost:8080",
    "secure": false
  },
  "/login/webauthn": {
    "target": "http://localhost:8080",
    "secure": false
  }
}
```

- [ ] **Step 7: nginx фронт-контейнера**

В `frontend/nginx.conf`:
- две первые строки шапки заменить на:

```nginx
# nginx ВНУТРИ контейнера ais-frontend (не хостовый!). Отдаёт Angular-статику и проксирует /api и пути входа по ключу
# на бэкенд по имени сервиса docker-сети. Наружу — только через 127.0.0.1:8090: tailscale serve и nginx хоста (ais.westmed.kz, калитка).
```

- между блоком `location /api/ { … }` и комментарием `# SPA-fallback…` вставить:

```nginx
    # вход по ключу (passkeys): встроенные эндпоинты Spring Security живут вне /api. Только эти четыре пути
    # и только POST — встроенное удаление ключа (DELETE /webauthn/register/{id}) не проверяет ни владельца,
    # ни вход, наружу его не выпускаем (спека passkeys-login §5.2)
    location ~ ^/(login/webauthn|webauthn/authenticate/options|webauthn/register/options|webauthn/register)$ {
        limit_except POST { deny all; }
        proxy_pass http://ais-backend:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

```

- [ ] **Step 8: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -5`
Expected: сборка без ошибок. Допустимо только прежнее предупреждение о бюджете начального бандла.

- [ ] **Step 9: Репетиция nginx фронт-контейнера**

Предусловия: бэкенд с кодом Tasks 1–4 на `localhost:8080` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew bootRun` в фоне, sandbox off; дождаться `curl -s localhost:8080/api/auth/passkey-config`). Docker: `docker info >/dev/null 2>&1`, иначе `colima start --cpu 1 --memory 1` и `export DOCKER_CONTEXT=colima` (CLAUDE.md §14).

```bash
bash <<'SH'
cd /Users/vlad/IdeaProjects/AIS
R="$HOME/.cache/ais-rehearsal"; mkdir -p "$R"
sed 's#ais-backend:8080#host.docker.internal:8080#' frontend/nginx.conf > "$R/frontend-pk.conf"
docker rm -f ais-fe-pk >/dev/null 2>&1
docker run -d --name ais-fe-pk -p 8089:80 \
  -v "$PWD/frontend/dist/nir-frontend/browser:/usr/share/nginx/html:ro" \
  -v "$R/frontend-pk.conf:/etc/nginx/conf.d/default.conf:ro" nginx:1.27-alpine >/dev/null
sleep 2; docker exec ais-fe-pk nginx -t 2>&1 | tail -1
F=http://localhost:8089
echo "1 параметры входа:        $(curl -s -X POST $F/webauthn/authenticate/options | grep -o '"rpId":"[^"]*"')"     # "rpId":"localhost"
echo "2 параметры регистрации:  $(curl -s -o /dev/null -w '%{http_code}' -X POST $F/webauthn/register/options)"       # 400 — ответ Spring
echo "3 вход по ключу без тела: $(curl -s -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' -d '{}' $F/login/webauthn)"   # 401 — ответ Spring
echo "4 GET регистрации:        $(curl -s -o /dev/null -w '%{http_code}' $F/webauthn/register)"                      # 403 — только POST
echo "5 DELETE ключа:           $(curl -s -o /dev/null -w '%{http_code}' -X DELETE $F/webauthn/register/abc)"          # 405 — до бэкенда не доходит
echo "6 страница входа (SPA):   $(curl -s -o /dev/null -w '%{http_code}' $F/login)"                                   # 200
echo "7 домен ключей:           $(curl -s $F/api/auth/passkey-config)"                                                # {"rpId":"localhost"}
docker rm -f ais-fe-pk >/dev/null
SH
```

Expected: `nginx: configuration file /etc/nginx/nginx.conf test is successful` и все семь строк совпадают с комментариями. Если строки 1 и 7 пустые, а на 2–3 пришёл 502, контейнер не видит хост: пересоздать с `--add-host host.docker.internal:host-gateway`. Иначе расхождение — это ошибка конфига: остановиться, найти причину (`docker logs ais-fe-pk`), поправить `frontend/nginx.conf` и повторить.

- [ ] **Step 10: Живая проверка — добавление и удаление ключа (Playwright)**

Бэкенд из Step 9 работает; dev-сервер перезапустить, потому что прокси читается только при старте: `lsof -ti tcp:4200 -sTCP:LISTEN | xargs kill 2>/dev/null; cd frontend && npm start` (в фоне), дождаться `curl -s -o /dev/null -w '%{http_code}' localhost:4200`.

Playwright `browser_run_code_unsafe`. Одна функция держит CDP-сессию и виртуальный аутентификатор Chrome на весь сценарий:

```js
async (page) => {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  const { authenticatorId } = await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
    isUserVerified: true, automaticPresenceSimulation: true } });
  const out = {};
  const addBtn = () => page.getByRole('button', { name: 'Добавить вход по Face ID / Touch ID' });
  await page.goto('http://localhost:4200/login');
  await page.locator('input[formcontrolname=username]').fill('admin');
  await page.locator('input[formcontrolname=password]').fill('admin');
  await page.locator('button[type=submit]').click();
  await page.waitForURL('**/dashboard');
  await page.goto('http://localhost:4200/profile');
  await page.locator('.add input').waitFor();
  out.suggested = await page.locator('.add input').inputValue();                    // «Mac · Chrome»
  out.empty = await page.getByText('Ключей пока нет').count();                         // 1
  await addBtn().click();
  await page.getByText('Ключ добавлен').waitFor({ timeout: 15000 });
  out.onDevice = (await cdp.send('WebAuthn.getCredentials', { authenticatorId })).credentials.length;   // 1
  out.row = await page.locator('.key').first().innerText();                         // «Mac · Chrome … ещё не использовался»
  try {                                                                              // тот же ключ второй раз → «уже есть»
    await addBtn().click();
    await page.getByText('ключ для вашей учётки уже есть').waitFor({ timeout: 15000 });
    out.exists = 'ok';
  } catch (e) { out.exists = 'не дождались: ' + e.message.slice(0, 80); }
  await page.locator('.key .btn').first().click();
  await page.locator('.confirm-modal .btn-danger').click();
  await page.getByText('Ключ удалён').waitFor();
  out.afterDelete = await page.locator('.key').count();                             // 0
  return out;
}
```

Expected:
- `suggested` — «Mac · Chrome»;
- `empty` 1;
- `onDevice` 1;
- в `row` есть «ещё не использовался»;
- `exists` 'ok';
- `afterDelete` 0.

Если `exists` не дождался, виртуальный аутентификатор не отдал `InvalidStateError`, а ветка «уже есть» проверится на iPhone в Task 8. Записать это в отчёт и не чинить. Затем `browser_take_screenshot` страницы профиля. Закрыть браузер (`browser_close`) — аутентификатор уходит вместе с ним.

- [ ] **Step 11: Commit**

```bash
git add frontend/src/app/services/api.service.ts frontend/src/app/services/passkey.service.ts \
  frontend/src/app/pages/profile/profile.component.ts frontend/src/app/app.routes.ts \
  frontend/src/app/layout/layout.component.ts frontend/proxy.conf.json frontend/nginx.conf
git commit -m "feat(passkeys): «Мой профиль» — ключи Face ID / Touch ID; пути passkeys в nginx фронта и прокси

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Фронт — вход по ключу

**Files:**
- Modify: `frontend/src/app/pages/login/login.component.ts` (целиком)
- Modify: `frontend/src/app/interceptors/auth.interceptor.ts`

**Interfaces:**
- Consumes: `PasskeyService.usableHere`, `PasskeyService.login()`, `ApiService.getPasskeyConfig()` (Task 5); `AuthService.loadCurrentUser(): Observable<AuthUser | null>`.

- [ ] **Step 1: Интерсептор**

В `frontend/src/app/interceptors/auth.interceptor.ts` строку

```ts
      if (err.status === 401 && !req.url.includes('/api/auth/')) {
```

заменить на

```ts
      // /login/webauthn: 401 — «ключ не принят», а не «сессия истекла»; страница входа сама скажет, что случилось
      if (err.status === 401 && !req.url.includes('/api/auth/') && !req.url.includes('/login/webauthn')) {
```

- [ ] **Step 2: Страница входа**

`frontend/src/app/pages/login/login.component.ts` заменить целиком:

```ts
import { Component, ChangeDetectorRef, OnInit } from '@angular/core';
import { NgIf } from '@angular/common';
import { ReactiveFormsModule, FormGroup, FormControl, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../services/auth.service';
import { ApiService } from '../../services/api.service';
import { PasskeyService } from '../../services/passkey.service';
import { APP_NAME, APP_TAGLINE } from '../../services/market.service';

@Component({
  selector: 'app-login',
  standalone: true,
  imports: [NgIf, ReactiveFormsModule],
  template: `
    <div class="login-page">
      <div class="login-card">
        <div class="login-header">
          <span class="login-logo">
            <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="3" stroke-linecap="round"><path d="M12 5v14M5 12h14"/></svg>
          </span>
          <h1>{{ appName }}</h1>
          <p>{{ appTagline }}</p>
        </div>
        <form [formGroup]="loginForm" (ngSubmit)="onLogin()" class="login-form">
          <label>Логин<input formControlName="username" name="username" autocomplete="username" placeholder="Введите логин" autofocus /></label>
          <label>Пароль<input type="password" formControlName="password" name="password" autocomplete="current-password" placeholder="Введите пароль" /></label>
          <p *ngIf="error" class="error-msg">{{ error }}</p>
          <button class="btn btn-login" type="submit" [disabled]="loginForm.invalid || loading">{{ loading ? 'Вход...' : 'Войти' }}</button>
        </form>
        <!-- вход по ключу (passkeys): только там, где ключ может сработать — на своём домене и в браузере с WebAuthn -->
        <div *ngIf="passkeyUsable" class="passkey">
          <div class="or">или</div>
          <button class="btn btn-line btn-passkey" type="button" (click)="onPasskey()" [disabled]="passkeyBusy || loading">
            <svg viewBox="0 0 24 24" width="18" height="18" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="M3 7V5a2 2 0 0 1 2-2h2M17 3h2a2 2 0 0 1 2 2v2M21 17v2a2 2 0 0 1-2 2h-2M7 21H5a2 2 0 0 1-2-2v-2"/><path d="M8 14s1.5 2 4 2 4-2 4-2"/><path d="M9 9h.01M15 9h.01"/></svg>
            {{ passkeyBusy ? 'Ждём Face ID / Touch ID…' : 'Войти с Face ID / Touch ID' }}
          </button>
          <p *ngIf="passkeyError" class="error-msg passkey-msg">{{ passkeyError }}</p>
          <p *ngIf="passkeyHint" class="hint-msg passkey-msg">{{ passkeyHint }}</p>
        </div>
      </div>
    </div>
  `,
  styles: [`
    /* Экран живёт ВНЕ LayoutComponent, поэтому фон страницы задаёт он сам. */
    .login-page { display: flex; justify-content: center; align-items: center; min-height: 100vh; background: var(--app-bg); }
    /* Тень карточки — var(--shadow-lg), общий токен «приподнятой панели» (им же
       живут модалки applies и bulk-price). Прежняя rgba(0,0,0,.08) на тёмном фоне
       не видна вовсе, а свою геометрию тени этот экран не заслуживает. */
    .login-card { background: var(--surface); border-radius: 12px; box-shadow: var(--shadow-lg); padding: 40px; width: 400px; max-width: 90vw; }
    .login-header { text-align: center; margin-bottom: 32px; }
    /* Медкрест внутри — инлайн-SVG со stroke="currentColor", цвет ему даёт эта
       строка (CLAUDE.md §14: path захардкожен намеренно, lucide приходил пустым). */
    .login-logo { display: inline-flex; align-items: center; justify-content: center; width: 56px; height: 56px; background: var(--accent); color: var(--accent-contrast); border-radius: 14px; margin-bottom: 16px; }
    /* h1 в kit нет (там только h2, h3) — цвет остаётся локальным. */
    .login-header h1 { font-size: 22px; color: var(--text); margin: 0 0 8px; }
    .login-header p { font-size: 13px; color: var(--text-muted); margin: 0; line-height: 1.4; }
    .login-form label { display: block; margin-bottom: 16px; font-size: 14px; color: var(--text); font-weight: 500; }
    .login-form input { display: block; width: 100%; padding: 10px 12px; margin-top: 6px; border: 1px solid var(--border); border-radius: 6px; font-size: 15px; box-sizing: border-box; background: var(--surface); color: var(--text); }
    .login-form input:focus { outline: none; border-color: var(--accent); box-shadow: 0 0 0 3px color-mix(in srgb, var(--accent) 10%, transparent); }
    /* Кнопка входа осознанно крупнее базы kit: во всю ширину карточки — это
       раскладка экрана, а не разъехавшийся примитив. Цвет, ховер и disabled
       берутся из kit (.btn + .btn-login), поэтому здесь только геометрия. */
    .btn-login { width: 100%; padding: 12px; border-radius: 6px; font-size: 15px; font-weight: 600; margin-top: 8px; }
    .error-msg { color: var(--danger-text); font-size: 13px; margin: 0 0 8px; }
    /* .login-hint шаблоном сейчас не используется — оставлен как был, переведён вместе с остальным. */
    .login-hint { text-align: center; font-size: 12px; color: var(--text-muted); margin-top: 20px; }
    /* Вход по ключу: та же геометрия, что у «Войти»; цвет — контурная кнопка kit (.btn + .btn-line). */
    .passkey { margin-top: 18px; }
    .or { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; color: var(--text-muted); font-size: 12px; }
    .or::before, .or::after { content: ''; flex: 1; height: 1px; background: var(--border); }
    .btn-passkey { width: 100%; padding: 12px; border-radius: 6px; font-size: 15px; font-weight: 600; display: flex; align-items: center; justify-content: center; gap: 8px; }
    .hint-msg { color: var(--text-muted); font-size: 13px; }
    .passkey-msg { margin: 10px 0 0; line-height: 1.4; }
  `]
})
export class LoginComponent implements OnInit {
  readonly appName = APP_NAME;
  readonly appTagline = APP_TAGLINE;
  loginForm = new FormGroup({
    username: new FormControl('', Validators.required),
    password: new FormControl('', Validators.required)
  });
  error = '';
  loading = false;
  passkeyUsable = false;
  passkeyBusy = false;
  passkeyError = '';
  passkeyHint = '';

  constructor(private auth: AuthService, private api: ApiService, private passkeys: PasskeyService,
              private router: Router, private cdr: ChangeDetectorRef) {
    if (this.auth.isLoggedIn()) {
      this.router.navigate(['/dashboard']);
    }
  }

  ngOnInit() {
    // кнопка ключа — только на домене ключей (не на Tailscale/туннеле) и в браузере с WebAuthn
    this.api.getPasskeyConfig().subscribe({
      next: c => { this.passkeyUsable = this.passkeys.usableHere(c.rpId); this.cdr.detectChanges(); },
      error: () => {},                                   // нет ответа — просто без кнопки: пароль работает всегда
    });
  }

  onLogin() {
    const { username, password } = this.loginForm.value;
    if (!username || !password) return;
    this.error = '';
    this.loading = true;
    this.auth.login(username, password).subscribe({
      next: () => {
        this.loading = false;
        this.router.navigate(['/dashboard']);
      },
      error: (err) => {
        this.loading = false;
        this.error = err.error?.message || 'Неверный логин или пароль';
      }
    });
  }

  async onPasskey() {
    this.error = '';
    this.passkeyError = '';
    this.passkeyHint = '';
    this.passkeyBusy = true;
    this.cdr.detectChanges();
    const outcome = await this.passkeys.login();
    if (outcome === 'ok') {
      this.auth.loadCurrentUser().subscribe(user => {
        this.passkeyBusy = false;
        if (user) {
          this.router.navigate(['/dashboard']);
        } else {
          this.passkeyError = 'Не удалось войти по ключу. Попробуйте ещё раз или войдите паролем.';
          this.cdr.detectChanges();
        }
      });
      return;
    }
    this.passkeyBusy = false;
    if (outcome === 'cancelled') {
      // отмену и «ключа на устройстве нет» браузер не различает — подсказка одна и мягкая (спека §8)
      this.passkeyHint = 'Не получилось войти по ключу. Если ключа на этом устройстве ещё нет — войдите паролем и добавьте его в «Мой профиль».';
    } else if (outcome === 'rejected') {
      this.passkeyError = 'Ключ не принят. Попробуйте ещё раз; если не получится — войдите паролем и добавьте ключ заново в «Мой профиль», а старый удалите в настройках паролей устройства.';
    } else {
      this.passkeyError = 'Не удалось войти по ключу. Попробуйте ещё раз или войдите паролем.';
    }
    this.cdr.detectChanges();
  }
}
```

- [ ] **Step 3: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build 2>&1 | tail -5`
Expected: без ошибок.

- [ ] **Step 4: Живая проверка — вход по ключу (Playwright, 1280)**

Бэкенд и dev-сервер из Task 5 работают. Если dev-сервер показывает оверлей ошибки — не верить ему, а перезагрузить страницу (CLAUDE.md §14). Одна функция `browser_run_code_unsafe`:

```js
async (page) => {
  const cdp = await page.context().newCDPSession(page);
  await cdp.send('WebAuthn.enable');
  await cdp.send('WebAuthn.addVirtualAuthenticator', { options: {
    protocol: 'ctap2', transport: 'internal', hasResidentKey: true, hasUserVerification: true,
    isUserVerified: true, automaticPresenceSimulation: true } });
  const out = {};
  const keyBtn = () => page.getByRole('button', { name: 'Войти с Face ID / Touch ID' });
  await page.goto('http://localhost:4200/login');
  await keyBtn().waitFor();
  out.buttonWithoutKey = await keyBtn().count();                                   // 1 — кнопка есть и до ключа
  await page.locator('input[formcontrolname=username]').fill('admin');
  await page.locator('input[formcontrolname=password]').fill('admin');
  await page.locator('button[type=submit]').click();
  await page.waitForURL('**/dashboard');
  await page.goto('http://localhost:4200/profile');
  await page.getByRole('button', { name: 'Добавить вход по Face ID / Touch ID' }).click();
  await page.getByText('Ключ добавлен').waitFor({ timeout: 15000 });
  await page.locator('.btn-logout').click();                                       // выход → вход одним ключом
  await page.waitForURL('**/login');
  await keyBtn().click();
  await page.waitForURL('**/dashboard', { timeout: 15000 });
  out.keyLogin = page.url();
  await page.goto('http://localhost:4200/profile');
  await page.locator('.key').first().waitFor();
  out.row = await page.locator('.key').first().innerText();                         // … последний вход: только что
  await page.locator('.key .btn').first().click();                                  // удалить ключ в АИС…
  await page.locator('.confirm-modal .btn-danger').click();
  await page.getByText('Ключ удалён').waitFor();
  await page.locator('.btn-logout').click();
  await page.waitForURL('**/login');
  await keyBtn().click();                                                          // …а на устройстве он остался
  await page.getByText('Ключ не принят').waitFor({ timeout: 15000 });
  out.rejectedStaysOnLogin = page.url();
  await page.goto('http://127.0.0.1:4200/login');                                  // другой хост — ключ не сработает
  await page.locator('input[formcontrolname=username]').waitFor();
  await page.waitForTimeout(1500);                                                  // ответ /api/auth/passkey-config
  out.buttonOn127 = await keyBtn().count();                                         // 0
  return out;
}
```

Expected:
- `buttonWithoutKey` 1;
- `keyLogin` оканчивается на `/dashboard`;
- в `row` есть «последний вход: только что»;
- `rejectedStaysOnLogin` оканчивается на `/login` — интерсептор не увёл на выход;
- `buttonOn127` 0.

- [ ] **Step 5: Живая проверка — телефон и тёмная тема**

Без виртуального аутентификатора — смотрим вёрстку:
1. `browser_navigate` → `http://localhost:4200/login`, затем `browser_resize` 390×844. Проверить `window.innerWidth === 390`: навигация сбрасывает вьюпорт на 1280 (CLAUDE.md §12).
2. `browser_evaluate`: `localStorage.setItem('ais.theme','dark'); location.reload()`, затем снова убедиться, что ширина 390.
3. `browser_take_screenshot` страницы входа.
4. `browser_evaluate` — `document.querySelector('.btn-passkey').getBoundingClientRect().height`. Ожидается ≥ 40.
5. Войти `admin/admin` → `http://localhost:4200/profile` (снова проверить ширину 390) → `browser_take_screenshot`. Поле подписи и кнопка идут во всю ширину, горизонтальной прокрутки нет: `document.documentElement.scrollWidth <= 390`.
6. Светлая тема на 1280 — скриншоты входа и профиля.
7. Вернуть тему: `localStorage.removeItem('ais.theme')`, затем `browser_close`.

- [ ] **Step 6: Уборка локальных данных**

Ключи из живых проверок уже удалены, осталась запись пользователя WebAuthn для `admin` (sandbox off):

```bash
PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "delete from user_entities where name = 'admin'"
```

- [ ] **Step 7: Commit**

```bash
git add frontend/src/app/pages/login/login.component.ts frontend/src/app/interceptors/auth.interceptor.ts
git commit -m "feat(passkeys): кнопка «Войти с Face ID / Touch ID» на странице входа; autocomplete для паролей

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Проверка сессии, документация, финальный гейт

**Files:**
- Modify: `CLAUDE.md` (§5, §8, §13, §14, §15, §16)
- Modify: `DEPLOY.md` (§6)
- Modify: `docs/PROGRESS.md`

- [ ] **Step 1: Сессия на живом бэкенде — смена id, срок cookie, старый id**

Бэкенд из Task 5 на `localhost:8080`:

```bash
bash <<'SH'
B=http://localhost:8080; J=$(mktemp); trap 'rm -f "$J"' EXIT
curl -s -c "$J" -b "$J" -o /dev/null -X POST $B/webauthn/authenticate/options        # сессия до входа (аноним)
OLD=$(awk '$6=="JSESSIONID"{print $7}' "$J")
H=$(curl -s -c "$J" -b "$J" -D - -o /dev/null -H 'Content-Type: application/json' \
      -d '{"username":"admin","password":"admin"}' $B/api/auth/login)
NEW=$(awk '$6=="JSESSIONID"{print $7}' "$J")
echo "id сменён:  $([ -n "$OLD" ] && [ "$OLD" != "$NEW" ] && echo да || echo НЕТ)"                  # да
echo "срок cookie: $(printf '%s' "$H" | grep -i '^set-cookie: JSESSIONID' | grep -o -i 'max-age=[0-9]*')"   # Max-Age=43200
echo "старый id:  $(curl -s -o /dev/null -w '%{http_code}' -b "JSESSIONID=$OLD" $B/api/auth/me)"     # 401
echo "новый id:   $(curl -s -o /dev/null -w '%{http_code}' -b "JSESSIONID=$NEW" $B/api/auth/me)"     # 200
SH
```

Expected: `да`, `Max-Age=43200`, `401`, `200`. После — остановить `bootRun` и dev-сервер: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:4200 -sTCP:LISTEN | xargs kill`.

- [ ] **Step 2: CLAUDE.md §8 — блок passkeys**

В §8 после блока «**Калитка по коду устройства для `ais.westmed.kz` …**» (перед `## 9.`) вставить:

```markdown
- **Вход по Face ID / Touch ID — passkeys (2026-09-28, ветка `feature/passkeys`).** Спека/план: `docs/superpowers/{specs,plans}/2026-09-28-passkeys-login*`. Вместо длинного пароля на `ais.westmed.kz` (калитка остаётся первым барьером): «Мой профиль» → «Добавить вход по Face ID / Touch ID», дальше на странице входа — «Войти с Face ID / Touch ID». Пароль работает всегда; у оператора личная учётка `vlad`, общий `admin` — запасной вход.
  - **Встроенные passkeys Spring Security 6.5.5** (`http.webAuthn`) + `webauthn4j-core` **0.29.5** — ровно та версия, с которой собран Spring 6.5.5 (в примере документации Spring — 0.29.7, не она). Эндпоинты Spring живут вне `/api`: `POST /webauthn/register/options`, `/webauthn/register`, `/webauthn/authenticate/options`, `/login/webauthn`. Формат тел — как у эталонного клиента Spring (`spring-security-webauthn.js` в `spring-security-web.jar`), включая `credType` при входе; тот же формат шлёт эмулятор ключа в тестах. Домен ключей — `passkeys.rp-id` / `passkeys.allowed-origins` (`localhost` / `http://localhost:4200`; прод — `application-prod.yaml`, `ais.westmed.kz`). Ключ навсегда привязан к домену, поэтому на Tailscale и туннеле кнопки нет: фронт сравнивает `GET /api/auth/passkey-config` → `{rpId}` с `location.hostname`.
  - **Хранилище — таблицы Spring** (V20: `user_entities`, `user_credentials`) + внешние ключи: `user_entities.name` → `user_account.username` `ON UPDATE CASCADE ON DELETE CASCADE`, ключи → запись пользователя `ON DELETE CASCADE`, `UNIQUE(name)`. ⚠️ Spring ищет владельца ключа по ЛОГИНУ, а логин в «Пользователях» редактируется: без каскада ключи удалённой учётки достались бы новой учётке с тем же логином.
  - ⚠️ **Находки в Spring 6.5.5 (сверено `javap`, документация о них молчит):**
    1. встроенный `DELETE /webauthn/register/{id}` удаляет ключ по id БЕЗ проверки владельца и даже входа, а его фильтр стоит после проверки прав: при `anyRequest().permitAll()` удалять чужие ключи мог бы аноним. Закрыто трижды: `denyAll`; nginx фронта пускает только 4 точных пути и только POST; свои ключи удаляются через `DELETE /api/passkeys/{id}` с проверкой владельца (нет ключа, чужой, битый id — одинаково 404);
    2. параметры регистрации без входа — **400**, не 401: фильтр стоит до проверки прав и отвечает сам. Фронт трактует это как «сессия истекла»;
    3. вход по ключу id сессии не меняет → `SessionIdRotationFilter` перед фильтром входа (на месте выключенной формы входа) и `changeSessionId()` во входе паролем;
    4. новый ключ получает `last_used = created` (`ImmutableCredentialRecord.builder()`) → «ещё не использовался» определяется равенством;
    5. принципал после входа по ключу — `PublicKeyCredentialUserEntity`, не `UserDetails`: пользователя брать только через `getName()`.
  - **Сессия — «рабочий день»:** `server.servlet.session.timeout` и `cookie.max-age` = 12h (было 30 мин бездействия). Деплой сбрасывает сессии.
  - **Проверено:**
    - полный цикл «регистрация → вход → сессия» на эмуляторе ключа (`VirtualPasskey`: webauthn4j-core + JDK, ES256, аттестация none; `webauthn4j-test` не взят — тянет BouncyCastle);
    - отказы: чужая подпись, удалённый ключ, чужой origin, повтор;
    - правила доступа, каскад, смена id;
    - мутации M1–M7;
    - репетиция nginx фронта на `nginx:1.27-alpine`;
    - вживую — виртуальный аутентификатор Chrome через CDP (`WebAuthn.addVirtualAuthenticator`) в Playwright;
    - curl: старый id сессии после входа → 401, `Max-Age=43200`.
```

- [ ] **Step 3: CLAUDE.md §5, §13, §14, §15, §16**

§5 — в строке «**Логин в UI:** …» после `(или \`operator\` / \`operator\`).` дописать: `Вход по ключу (Face ID / Touch ID): «Мой профиль» → «Добавить вход…» — работает на \`localhost:4200\` (rpId \`localhost\`) и на проде \`ais.westmed.kz\`; на \`127.0.0.1\` и Tailscale кнопки нет.`

§13 — в строке `На 2026-09-28 — **553 теста, 0 падений, 0 skipped**.` число заменить на фактическое из финального гейта (Step 6).

§14 — дописать пункты:

```markdown
- **Встроенное во фреймворк — не значит безопасное в нашей конфигурации** (passkeys, 2026-09-28): `DELETE /webauthn/register/{id}` в Spring Security 6.5.5 удаляет ключ без проверки владельца и входа, а его фильтр стоит ПОСЛЕ `AuthorizationFilter`: при нашем `anyRequest().permitAll()` удалять чужие ключи мог бы аноним. Нашлось только чтением байткода (`javap -c -p` по классам из gradle-кеша), в документации об этом ни слова. Перед включением готового модуля безопасности — пройти его фильтры: кто стоит до и после проверки прав, что проверяет сам, что отвечает (так же нашлись 400 вместо 401 и несменённый id сессии).
- **Контракт с фронтом снимать с эталонного клиента фреймворка:** эталонный клиент лежит прямо в jar — `unzip -p <spring-security-web-6.5.5.jar из ~/.gradle/caches> org/springframework/security/spring-security-webauthn.js`. Тела WebAuthn (base64url без `=`, `credType` при входе) повторены с него дословно. Эмулятор ключа в тестах шлёт те же тела, поэтому фронт и тесты проверяют один и тот же контракт.
```

Плюс по пункту на каждое расхождение, найденное живыми проверками Tasks 5–7, в виде «симптом → причина → решение».

§15 — перед `Записи — …` дописать: `` `/api/passkeys` (вошедший: GET свои ключи `{keys, suggestedLabel}`; DELETE `/{id}` — только свой, иначе 404); `/api/auth/passkey-config` (без входа: `{rpId}`); встроенные эндпоинты Spring (вне `/api`, только POST): `/webauthn/register/options`, `/webauthn/register`, `/webauthn/authenticate/options`, `/login/webauthn`; ``

§16 — в пункт «Обращения (коммерция West-Med)…» после `✔ шаг 2 — калитка по коду устройства (§8; раскатка — DEPLOY.md §6).` дописать: `✔ вход по Face ID / Touch ID (passkeys, §8). Хвосты passkeys: подсказка ключа в поле логина (conditional UI) и автопоказ Face ID при открытии входа; сброс ключей сотрудника из «Пользователей»; переименование ключей и смена своего пароля в профиле; обязательный ключ / \`userVerification: required\`; сессии, переживающие деплой (spring-session-jdbc); Signal API (устройство само забывает удалённые ключи); вход ключом на хосте Tailscale (Related Origin Requests); журнал входов.`

- [ ] **Step 4: DEPLOY.md §6**

После пункта «**Первое устройство** …» вставить:

```markdown
- **Вход по ключу (passkeys, 2026-09-28):** Face ID / Touch ID вместо пароля АИС — только на `https://ais.westmed.kz`: ключ привязан к домену (`passkeys.rp-id` в `application-prod.yaml`, `.env` не нужен). На Tailscale и туннеле — вход паролем. Добавить ключ: войти паролем → «Мой профиль» → «Добавить вход по Face ID / Touch ID». Потерян телефон: «Мой профиль» с другого устройства → удалить его ключ, плюс «Устройства» → отозвать телефон. Сессия — 12 ч; деплой сбрасывает сессии (одно касание Face ID). Встроенное удаление ключа Spring наружу не выходит: `frontend/nginx.conf` пропускает к бэкенду только 4 пути passkeys и только POST. Откат — revert мерж-коммита и push: вход паролем не затронут, таблицы V20 остаются пустым грузом, ключи на устройствах удалить в настройках паролей.
```

- [ ] **Step 5: PROGRESS**

`docs/PROGRESS.md`:
- строку `- ➡️ **Дальше — вход по passkeys (Face ID / Touch ID)** …` заменить на:

```markdown
- **Вход по Face ID / Touch ID — passkeys (2026-09-28, ветка `feature/passkeys`):** spec → plan → реализация, 8 задач. Встроенные passkeys Spring Security 6.5.5 + `webauthn4j-core` 0.29.5, V20 (`user_entities`/`user_credentials` с каскадом по логину), «Мой профиль» (ключи: добавить/удалить), кнопка «Войти с Face ID / Touch ID», сессия 12 ч и смена id при каждом входе. Сверка байткода Spring нашла дыру: встроенное удаление ключа не проверяет ни владельца, ни вход — закрыто трижды (`denyAll`, nginx фронта, свой `/api/passkeys` с проверкой владельца). Проверено: <N> тестов, мутации M1–M7, репетиция nginx фронта, вживую — виртуальный аутентификатор Chrome (CDP). Раскатка — DEPLOY.md §6.
```

(`<N>` — фактическое число из финального гейта.)
- в строке «**Последнее обновление:** …» заменить `**Следующий блок — вход по passkeys (Face ID / Touch ID)**, решение оператора 2026-09-28.` на `**Вход по passkeys (Face ID / Touch ID)** — реализован (ветка \`feature/passkeys\`), раскатка — по решению оператора.`

- [ ] **Step 6: Финальный гейт**

Run (sandbox off): `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test` и `(cd frontend && npm run build 2>&1 | tail -3)`
Expected: 0 падений (около 577 тестов — точное число вписать в CLAUDE.md §13 и PROGRESS); сборка фронта без ошибок.

- [ ] **Step 7: Commit**

```bash
git add CLAUDE.md DEPLOY.md docs/PROGRESS.md
git commit -m "docs(passkeys): вход по Face ID / Touch ID — механика, находки в Spring 6.5.5, раскатка, API, бэклог

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 8: Завершение ветки**

superpowers:finishing-a-development-branch — мерж в `main` локально только по решению оператора; push делает оператор.

---

### Task 8: Раскатка на прод — ТОЛЬКО с согласия оператора

Каждый шаг с сервером (`ssh root@185.125.46.26`, вход по ключу) — после подтверждения оператора в чате.

- [ ] **Step 1: Деплой кода**

Оператор: `! git push origin main`. Агент ждёт в фоне (`run_in_background: true`), пока на проде не появится V20:

```bash
until ssh root@185.125.46.26 'docker exec ais-ais-postgres-1 sh -c "psql -U \"\$POSTGRES_USER\" -d nirdb -tAc \"select max(version::int) from flyway_schema_history where success\""' | grep -qx 20; do sleep 20; done; echo "V20 на проде"
```

Затем логи: `ssh root@185.125.46.26 'docker logs --since 10m ais-ais-backend-1 2>&1 | grep -i -E "Successfully applied|Started Nir|ERROR" | tail'`.

- [ ] **Step 2: Проверка через SSH-туннель**

Туннель `8090 → 127.0.0.1:8090` (если не поднят: `ssh -N -o ExitOnForwardFailure=yes -L 8090:127.0.0.1:8090 root@185.125.46.26` в фоне):

```bash
bash <<'SH'
F=http://localhost:8090
echo "домен ключей:           $(curl -s $F/api/auth/passkey-config)"                                         # {"rpId":"ais.westmed.kz"}
echo "параметры входа:        $(curl -s -X POST $F/webauthn/authenticate/options | grep -o '"rpId":"[^"]*"')"  # "rpId":"ais.westmed.kz"
echo "регистрация без входа:  $(curl -s -o /dev/null -w '%{http_code}' -X POST $F/webauthn/register/options)"  # 400
echo "встроенное удаление:    $(curl -s -o /dev/null -w '%{http_code}' -X DELETE $F/webauthn/register/abc)"   # 405
SH
```

- [ ] **Step 3: Личная учётка `vlad`**

С согласия оператора. Пароль генерируется локально и нигде не печатается:

```bash
( umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(18))' > ~/.config/ais/ais-vlad.pass )
python3 - <<'PY'
import http.cookiejar, json, os, urllib.error, urllib.request
base = 'http://localhost:8090'
secret = lambda name: open(os.path.expanduser(f'~/.config/ais/{name}')).read().strip()
jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar))
def call(method, path, body):
    req = urllib.request.Request(base + path, method=method, data=json.dumps(body).encode(),
                                 headers={'Content-Type': 'application/json'})
    try:
        with opener.open(req) as r:
            return r.status
    except urllib.error.HTTPError as e:
        return e.code
print('вход admin:', call('POST', '/api/auth/login', {'username': 'admin', 'password': secret('ais-admin.pass')}))
print('учётка vlad:', call('POST', '/api/users', {'username': 'vlad', 'fullName': 'Владислав Ширяев',
                                                  'role': 'ROLE_ADMIN', 'password': secret('ais-vlad.pass')}))
jar.clear()
print('вход vlad:', call('POST', '/api/auth/login', {'username': 'vlad', 'password': secret('ais-vlad.pass')}))
PY
```

Expected: `200`, `200`, `200`.

- [ ] **Step 4: Ключи на устройствах оператора**

Оператор:
1. **iPhone:**
   1. на Mac `pbcopy < ~/.config/ais/ais-vlad.pass` — через общий буфер Apple пароль доступен и на iPhone;
   2. `https://ais.westmed.kz` → вход `vlad` → меню «Мой профиль» → «Добавить вход по Face ID / Touch ID» → Face ID → ключ «iPhone · Safari» в списке;
   3. «Выйти» → «Войти с Face ID / Touch ID» → Face ID → внутри;
   4. один раз нажать «Отмена» на окне Face ID → под кнопкой мягкая подсказка — проверка ветки отмены на настоящем устройстве;
   5. ещё раз «Добавить вход…» на том же iPhone → «На этом устройстве ключ для вашей учётки уже есть».
2. **Mac (Chrome):** «Войти с Face ID / Touch ID». Если Chrome предложил ключ из iCloud или вход телефоном по QR-коду — войти так. Иначе войти `vlad` паролем → «Мой профиль» → «Добавить…» → Touch ID → выйти → войти ключом.
3. **Запасной вход:** общий `admin` по паролю входит как прежде.

- [ ] **Step 5: Проверка в базе прода (только чтение)**

```bash
ssh root@185.125.46.26 'docker exec ais-ais-postgres-1 sh -c "psql -U \"\$POSTGRES_USER\" -d nirdb -c \"select e.name, c.label, c.created, c.last_used from user_credentials c join user_entities e on e.id = c.user_entity_user_id order by c.created\""'
```

Expected: ключи `vlad` с подписями устройств; у ключей, которыми входили, `last_used` позже `created`.

- [ ] **Step 6: Документация статуса и память**

- CLAUDE.md §8, блок passkeys — в конец дописать `**В проде с <дата>** (push \`<hash>\`): учётка \`vlad\` (ADMIN), ключи — iPhone (Face ID) и Mac (<как вошёл Mac>).`
- CLAUDE.md §5, строка «Есть ПРОД» — после `(с 2026-09-28, DEPLOY.md §6; допуск — «Система → Устройства»)` дописать `, вход — Face ID / Touch ID (passkeys) или пароль`.
- DEPLOY.md §6, пункт passkeys — дописать: `Личная учётка оператора — \`vlad\` (ADMIN), пароль — \`~/.config/ais/ais-vlad.pass\` на Mac оператора (600); общий \`admin\` — запасной вход по паролю.`
- PROGRESS — строка «Раскатка passkeys (<дата>)»: что проверено на устройствах.
- Память `ais-prod-deploy-oblako.md` — строка «Доступ»: вход по Face ID / Touch ID (учётка `vlad`, пароль — `~/.config/ais/ais-vlad.pass`), общий `admin` — запасной. Обновить строку-указатель в `MEMORY.md`.
- Commit: `docs(passkeys): вход по ключу в проде — статус раскатки` (с трейлером `Co-Authored-By`). Push — оператор, вместе со следующим деплоем или сразу.
