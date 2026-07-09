# Редактор шаблона письма КП — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Сохраняемый на рынок (KZ/RF) шаблон письма КП, редактируемый из UI, с плейсхолдерами; заодно письмо перестаёт раскрывать конкретный тендер (убраны номер тендера и ссылка на объявление).

**Architecture:** Новая таблица `email_template` (строка на рынок). `EmailTemplateRenderer` подставляет `{{плейсхолдеры}}`. `KpEmailComposer` грузит шаблон рынка из БД, при отсутствии/пустоте — зашитый дефолт (`DEFAULT_SUBJECT`/`DEFAULT_BODY` с плейсхолдерами); `announceLink` и номер тендера удаляются. API GET/PUT/`default` + страница «Шаблон письма КП» в группе «Система». Активный рынок — из `MarketContext` (заголовок `X-Market`, его уже шлёт фронт).

**Tech Stack:** Java 17, Spring Boot 3.5.6, Spring Data JPA, Flyway, PostgreSQL 17, Angular 21, Lombok, GreenMail (косвенно через существующие тесты отправки).

## Global Constraints

- **Спек:** `docs/superpowers/specs/2026-07-09-kp-email-template-editor-design.md` (источник истины).
- **Нераскрытие тендера (решение оператора 2026-07-09):** письмо КП НЕ содержит номер тендера/заявки и ссылку на объявление. Плейсхолдеров `{{предмет}}`/`{{ссылка}}` НЕТ. `KpEmailComposer.announceLink` удаляется.
- **Токен темы:** `[КП-<id>]` формируется ТОЛЬКО сервером (`KpToken.subjectToken(pr.getId())`), в шаблон не входит, приклеивается в `compose` перед человеческой темой. Матч ответа (`KpToken.parse`) не ломать.
- **Плейсхолдеры (полный набор):** `{{приветствие}}`, `{{компания}}`, `{{позиции}}`, `{{дедлайн}}`, `{{реестр}}`. Неизвестная метка остаётся как есть.
- **Фолбэк на дефолт:** нет строки в БД ИЛИ поле пустое → зашитый дефолт-константа. Дефолт рыночно-нейтрален (различия рынков — через `{{компания}}`/`{{реестр}}`).
- **Flyway:** новая миграция V10 (только `CREATE TABLE`, БЕЗ сид-текста — фолбэк-константа единственный источник дефолта, избегаем дублирования текста в SQL). V1–V9 не трогаем.
- **Многорыночность (§6 CLAUDE.md):** `email_template` — НЕ `MarketScoped` (нет `@Filter`/листенера), читаем/пишем по явному `MarketContext.get()`. НЕ переобъявлять `@FilterDef`.
- **БД:** nirdb UTF-8; `./gradlew`/psql — с `dangerouslyDisableSandbox: true`; kill lingering `lsof -ti :8080 | xargs kill -9`.
- **Тест-гейт (§13 CLAUDE.md):** зелёный = падают ТОЛЬКО 2 пред-существующих `ApplyAutoFillServiceTest`. Фронт — `npm run build` (бюджет `anyComponentStyle` 16 kB). git/gradlew из корня репо.
- **Коммиты:** ветка `feat/kp-email-template-editor` (уже создана); каждый заканчивать `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- **Субагенты — наследуют модель сессии (§2 CLAUDE.md), `model` не переопределять.**

## File Structure

**Создаём:**
- `src/main/java/com/vladoose/nir/entity/EmailTemplate.java`
- `src/main/java/com/vladoose/nir/repository/EmailTemplateRepository.java`
- `src/main/java/com/vladoose/nir/service/EmailTemplateRenderer.java`
- `src/main/java/com/vladoose/nir/service/EmailTemplateService.java`
- `src/main/java/com/vladoose/nir/controller/EmailTemplateController.java`
- `src/main/java/com/vladoose/nir/dto/request/EmailTemplateRequest.java`
- `src/main/java/com/vladoose/nir/dto/response/EmailTemplateResponse.java`
- `src/main/resources/db/migration/V10__email_template.sql`
- `src/test/java/com/vladoose/nir/email/EmailTemplateRendererTest.java`
- `src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java`
- `frontend/src/app/pages/email-template/email-template.component.ts`

**Меняем:**
- `src/main/java/com/vladoose/nir/service/KpEmailComposer.java` — на шаблоны + анти-лик.
- `src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java` — обновить упавшие ассерты.
- `frontend/src/app/services/api.service.ts` — методы шаблона.
- `frontend/src/app/app.routes.ts` — роут `email-template`.
- `frontend/src/app/layout/layout.component.ts` — ссылка в группе «Система».
- `CLAUDE.md` — §9/§15/§16 (финальная задача).

---

### Task 1: Сущность + репозиторий + миграция V10

**Files:**
- Create: `src/main/java/com/vladoose/nir/entity/EmailTemplate.java`
- Create: `src/main/java/com/vladoose/nir/repository/EmailTemplateRepository.java`
- Create: `src/main/resources/db/migration/V10__email_template.sql`
- Test: `src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java` (только repo-часть в этой задаче)

**Interfaces:**
- Produces:
  - `EmailTemplate { Long getId(); Market getMarket(); String getSubjectTemplate(); String getBodyTemplate(); OffsetDateTime getUpdatedAt(); }` + Lombok setters/`@Builder`.
  - `EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> { Optional<EmailTemplate> findByMarket(Market market); }`
  - Таблица `email_template(market UK, subject_template, body_template, updated_at)`.

- [ ] **Step 1: Сущность `EmailTemplate`**

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "email_template")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class EmailTemplate {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 2)
    private Market market;

    @Column(name = "subject_template", columnDefinition = "TEXT", nullable = false)
    private String subjectTemplate;

    @Column(name = "body_template", columnDefinition = "TEXT", nullable = false)
    private String bodyTemplate;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
```

- [ ] **Step 2: Репозиторий**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface EmailTemplateRepository extends JpaRepository<EmailTemplate, Long> {
    Optional<EmailTemplate> findByMarket(Market market);
}
```

- [ ] **Step 3: Миграция V10** (только таблица, без сид-текста)

```sql
-- V10: шаблон письма КП, редактируемый из UI, по одной строке на рынок.
-- Дефолтный текст живёт в коде (KpEmailComposer.DEFAULT_SUBJECT/DEFAULT_BODY) — единственный
-- источник; при отсутствии строки композер берёт дефолт. Поэтому таблицу НЕ сеем (нет дублирования
-- текста в SQL и дрейфа). Строка появляется, когда оператор сохранит шаблон.
CREATE TABLE email_template (
  id               BIGSERIAL PRIMARY KEY,
  market           VARCHAR(2) NOT NULL UNIQUE,
  subject_template TEXT NOT NULL,
  body_template    TEXT NOT NULL,
  updated_at       TIMESTAMPTZ
);
```

- [ ] **Step 4: Failing-тест репозитория** (создать файл, только save/find на этой задаче)

```java
package com.vladoose.nir.email;

import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class EmailTemplateEndpointTest {

    @Autowired EmailTemplateRepository repository;

    @Test
    void savesAndFindsByMarket() {
        repository.save(EmailTemplate.builder()
                .market(Market.KZ).subjectTemplate("S").bodyTemplate("B").build());
        assertThat(repository.findByMarket(Market.KZ)).isPresent()
                .get().extracting(EmailTemplate::getBodyTemplate).isEqualTo("B");
        assertThat(repository.findByMarket(Market.RF)).isEmpty();
    }
}
```

- [ ] **Step 5: Применить миграцию + прогнать**

Применить V10: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` в фоне (`run_in_background:true` + `dangerouslyDisableSandbox:true`), дождаться "now at version v10" / "Started Nir2Application", затем `lsof -ti :8080 | xargs kill -9`.
Затем: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateEndpointTest'` (sandbox off).
Expected: PASS (1 тест). Проверка: `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "\d email_template"` показывает таблицу.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/entity/EmailTemplate.java src/main/java/com/vladoose/nir/repository/EmailTemplateRepository.java src/main/resources/db/migration/V10__email_template.sql src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java && git commit -m "feat(kp-template): сущность EmailTemplate + репозиторий + таблица V10

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: `EmailTemplateRenderer`

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/EmailTemplateRenderer.java`
- Test: `src/test/java/com/vladoose/nir/email/EmailTemplateRendererTest.java`

**Interfaces:**
- Produces: `@Component EmailTemplateRenderer` с `String render(String template, Map<String,String> vars)` — заменяет `{{key}}` на значение; отсутствующие ключи не трогает; `null`-шаблон → `""`, `null`-значение → `""`.

- [ ] **Step 1: Failing-тест рендерера**

```java
package com.vladoose.nir.email;

import com.vladoose.nir.service.EmailTemplateRenderer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class EmailTemplateRendererTest {

    private final EmailTemplateRenderer renderer = new EmailTemplateRenderer();

    @Test
    void substitutesKnownKeys() {
        String out = renderer.render("Привет, {{имя}}! {{город}}",
                Map.of("имя", "Иван", "город", "Алматы"));
        assertThat(out).isEqualTo("Привет, Иван! Алматы");
    }

    @Test
    void leavesUnknownPlaceholderAsIs() {
        String out = renderer.render("A {{известно}} B {{неизвестно}}",
                Map.of("известно", "X"));
        assertThat(out).isEqualTo("A X B {{неизвестно}}");
    }

    @Test
    void multilineValuePreserved() {
        String out = renderer.render("Список:\n{{позиции}}конец",
                Map.of("позиции", "— a\n— b\n"));
        assertThat(out).isEqualTo("Список:\n— a\n— b\nконец");
    }

    @Test
    void nullTemplateIsEmpty() {
        assertThat(renderer.render(null, Map.of())).isEqualTo("");
    }
}
```

- [ ] **Step 2: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateRendererTest'` (sandbox off).
Expected: FAIL — класса `EmailTemplateRenderer` нет.

- [ ] **Step 3: Реализовать рендерер**

```java
package com.vladoose.nir.service;

import org.springframework.stereotype.Component;

import java.util.Map;

/** Подстановка {{плейсхолдеров}} в шаблон письма. Неизвестные метки остаются как есть. */
@Component
public class EmailTemplateRenderer {

    public String render(String template, Map<String, String> vars) {
        if (template == null) return "";
        String out = template;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            String value = e.getValue() == null ? "" : e.getValue();
            out = out.replace("{{" + e.getKey() + "}}", value);
        }
        return out;
    }
}
```

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateRendererTest'` (sandbox off).
Expected: PASS (4).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/EmailTemplateRenderer.java src/test/java/com/vladoose/nir/email/EmailTemplateRendererTest.java && git commit -m "feat(kp-template): EmailTemplateRenderer — подстановка {{плейсхолдеров}}

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `KpEmailComposer` на шаблоны + анти-лик (убрать тендер/ссылку)

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/KpEmailComposer.java` (полная переработка)
- Modify: `src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java` (обновить упавшие ассерты)

**Interfaces:**
- Consumes: `EmailTemplateRepository.findByMarket` (Task 1), `EmailTemplateRenderer.render` (Task 2), `KpToken.subjectToken`, `Market.companyShortName()`.
- Produces:
  - `KpEmailComposer(EmailTemplateRepository, EmailTemplateRenderer)` — конструкторная инъекция (был без конструктора).
  - `Composed compose(PriceRequest pr)` / `Composed composeForPreview(PriceRequest draft)` — контракт неизменен, но текст теперь из шаблона рынка (фолбэк — дефолт-константа).
  - Публичные константы `static final String DEFAULT_SUBJECT`, `DEFAULT_BODY` (для Task 4 «default»-эндпоинта и «Сбросить»).
  - Письмо БЕЗ номера тендера и ссылки на объявление.

- [ ] **Step 1: Обновить `KpEmailComposerTest` (упавшие ассерты) — сначала правим тест под новый контракт**

Открыть `src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java`. Он сейчас конструирует `new KpEmailComposer()` — заменить на `@SpringBootTest` с `@Autowired KpEmailComposer composer` (композер теперь требует бины). Обновить ассерты, которые ломает анти-лик:
- было `assertThat(msg.subject()).contains("[КП-42]").contains("по тендеру № 17276387-1");`
  → стало `assertThat(msg.subject()).contains("[КП-42]").contains("Запрос коммерческого предложения");`
- было `.contains("Приём заявок до 15.07.2026")` → стало `.contains("Просим ответить до 15.07.2026")`
- УДАЛИТЬ строку `.contains("https://goszakup.gov.kz/ru/announce/index/17276387\n")`
- ДОБАВИТЬ анти-лик: `assertThat(msg.body()).doesNotContain("goszakup").doesNotContain("тендер");` и `assertThat(msg.subject()).doesNotContain("17276387");`
- Ассерты, которые ОСТАЮТСЯ (не трогать): приветствие, `ТОО «West-Med»`/`ООО «РЕГИОН-МЕД»`, строки лотов, `Требования (из ТЗ)`, `НЦЭЛС РК`/`Росздравнадзора`, обрезка ТЗ (`полное ТЗ — по ссылке на объявление` — это про trimSpec, НЕ про announceLink, оставить).

> Точную конверсию `new KpEmailComposer()` → autowired смотреть по факту (несколько методов). Если тест был unit (без Spring) — сделать `@SpringBootTest` (как sibling `email/KpEmailComposerTest`). Прогнать позже (Step 4), сейчас правки только фиксируют ожидаемый контракт.

- [ ] **Step 2: Прогнать — падает (старый композер даёт тендер/ссылку)**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.service.KpEmailComposerTest'` (sandbox off).
Expected: FAIL — тело/тема ещё содержат тендер и ссылку (либо не компилится из-за конструктора).

- [ ] **Step 3: Переписать `KpEmailComposer`** (полная новая версия файла)

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.EmailTemplateRepository;
import com.vladoose.nir.util.KpToken;
import org.springframework.stereotype.Component;

import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Построение темы/тела письма КП из редактируемого шаблона рынка (email_template) с фолбэком на
 * зашитый дефолт. Плейсхолдеры: {{приветствие}} {{компания}} {{позиции}} {{дедлайн}} {{реестр}}.
 * Письмо НЕ раскрывает конкретный тендер/заявку (нет номера и ссылки на объявление) — решение
 * оператора 2026-07-09. Токен [КП-id] в теме — серверный, в шаблон не входит.
 */
@Component
public class KpEmailComposer {

    static final int SPEC_LIMIT = 1200;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public static final String DEFAULT_SUBJECT = "Запрос коммерческого предложения";
    public static final String DEFAULT_BODY =
            "{{приветствие}}\n\n" +
            "{{компания}} просит предоставить коммерческое предложение по следующим позициям:\n\n" +
            "{{позиции}}\n" +
            "{{дедлайн}}Просим указать: цену за единицу, № регистрационного удостоверения ({{реестр}}) " +
            "на предлагаемую модель, сроки поставки, условия оплаты, гарантию.\n\n" +
            "С уважением,\n{{компания}}\n\n" +
            "Ответ на этот запрос просим направить ответным письмом (Reply) — он поступит в наш отдел закупок.";

    private final EmailTemplateRepository templateRepository;
    private final EmailTemplateRenderer renderer;

    public KpEmailComposer(EmailTemplateRepository templateRepository, EmailTemplateRenderer renderer) {
        this.templateRepository = templateRepository;
        this.renderer = renderer;
    }

    public record Composed(String subject, String body) {}

    public Composed compose(PriceRequest pr) {
        Tmpl t = loadTemplate(pr);
        Map<String, String> vars = vars(pr);
        String subject = KpToken.subjectToken(pr.getId()) + " " + renderer.render(t.subject(), vars);
        return new Composed(subject, renderer.render(t.body(), vars));
    }

    /** Черновой предпросмотр (id ещё нет) — тема без токена. */
    public Composed composeForPreview(PriceRequest draft) {
        Tmpl t = loadTemplate(draft);
        Map<String, String> vars = vars(draft);
        return new Composed(renderer.render(t.subject(), vars), renderer.render(t.body(), vars));
    }

    private record Tmpl(String subject, String body) {}

    private Tmpl loadTemplate(PriceRequest pr) {
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        String subj = DEFAULT_SUBJECT, body = DEFAULT_BODY;
        Optional<EmailTemplate> opt = templateRepository.findByMarket(market);
        if (opt.isPresent()) {
            EmailTemplate et = opt.get();
            if (et.getSubjectTemplate() != null && !et.getSubjectTemplate().isBlank()) subj = et.getSubjectTemplate();
            if (et.getBodyTemplate() != null && !et.getBodyTemplate().isBlank()) body = et.getBodyTemplate();
        }
        return new Tmpl(subj, body);
    }

    private Map<String, String> vars(PriceRequest pr) {
        Tender tender = pr.getTender();
        Market market = pr.getMarket() != null ? pr.getMarket() : Market.RF;
        Distributor d = pr.getDistributor();
        Map<String, String> v = new HashMap<>();
        String contact = (safe(d.getLastName()) + " " + safe(d.getFirstName())).trim();
        v.put("приветствие", contact.isBlank() ? "Здравствуйте!" : "Уважаемый(ая) " + contact + "!");
        v.put("компания", market.companyShortName());
        v.put("реестр", market == Market.KZ ? "НЦЭЛС РК" : "Росздравнадзора");
        v.put("позиции", buildPositions(pr));
        v.put("дедлайн", tender.getDeadline() != null
                ? "Просим ответить до " + DATE.format(tender.getDeadline()) + ".\n\n" : "");
        return v;
    }

    private String buildPositions(PriceRequest pr) {
        StringBuilder sb = new StringBuilder();
        for (PriceRequestItem it : pr.getItems()) {
            TenderLot lot = it.getTenderLot();
            String qty = it.getRequestedQuantity() != null ? it.getRequestedQuantity() + " шт." : "кол-во уточняется";
            sb.append("— Лот ").append(lot.getLotNumber() != null ? lot.getLotNumber() : "—").append(": ");
            MedEquipment eq = it.getMedEquipment();
            if (eq != null) {
                sb.append(eq.getName()).append(" (").append(eq.getManufact()).append(")");
                if (eq.getRegistration() != null) {
                    sb.append(", РУ № ").append(eq.getRegistration().getRegNumber());
                }
                sb.append(" — ").append(qty).append("\n");
            } else {
                sb.append(lot.getEquipName());
                if (lot.getManufact() != null && !lot.getManufact().isBlank()) {
                    sb.append(" (").append(lot.getManufact()).append(")");
                }
                sb.append(" — ").append(qty).append("\n");
                if (lot.getRequiredSpec() != null && !lot.getRequiredSpec().isBlank()) {
                    sb.append("  Требования (из ТЗ): ").append(trimSpec(lot.getRequiredSpec())).append("\n");
                }
            }
        }
        return sb.toString();
    }

    private String trimSpec(String spec) {
        String s = spec.strip();
        if (s.length() <= SPEC_LIMIT) return s;
        return s.substring(0, SPEC_LIMIT) + "… (полное ТЗ — по ссылке на объявление)";
    }

    private String safe(String s) { return s == null ? "" : s; }
}
```

> Удаляются: `humanSubject`, `ctx`/`Ctx`, `buildBody`, `announceLink`, импорты `URLEncoder`/`StandardCharsets`/`Source`. `Source` больше не нужен (различие тендер/заявка убрано из письма) — убрать импорт, если не используется.

- [ ] **Step 4: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.KpEmailComposerTest' --tests 'com.vladoose.nir.email.KpEmailComposerTest'` (sandbox off).
Expected: PASS (оба класса — service-версия обновлена, email-версия из ч.2 не пинит тендер/ссылку → зелёная на дефолте).
Проверка на дубли/съеденное (§14): `grep -c "public Composed compose" src/main/java/com/vladoose/nir/service/KpEmailComposer.java` (ждём 2: `compose` + `composeForPreview`) + `./gradlew compileJava`.

- [ ] **Step 5: Регресс отправки (композер зовётся из dispatch)**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.service.PriceRequestSendServiceTest' --tests 'com.vladoose.nir.email.KpRoundTripTest'` (sandbox off).
Expected: PASS — токен в теме на месте (default subject + серверный токен), override-тест зелёный.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/KpEmailComposer.java src/test/java/com/vladoose/nir/service/KpEmailComposerTest.java && git commit -m "feat(kp-template): письмо из шаблона рынка + фолбэк-дефолт; убраны номер тендера и ссылка на объявление (анти-лик)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Сервис + контроллер шаблона (GET/PUT/default)

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/EmailTemplateRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/EmailTemplateResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/EmailTemplateService.java`
- Create: `src/main/java/com/vladoose/nir/controller/EmailTemplateController.java`
- Test: `src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java` (дописать API-тесты)

**Interfaces:**
- Consumes: `EmailTemplateRepository` (Task 1), `KpEmailComposer.DEFAULT_SUBJECT/DEFAULT_BODY` (Task 3), `MarketContext.get()`.
- Produces:
  - `EmailTemplateResponse { String market; String subject; String body; OffsetDateTime updatedAt; List<String> warnings; }`
  - `EmailTemplateRequest { String subject; String body; }` (рынок — из `MarketContext`).
  - `EmailTemplateService`: `current()` (сохранённый или дефолт), `save(subject, body)` (upsert + `warnings`), `defaults()`.
  - `GET /api/email-template` → `EmailTemplateResponse` (текущий рынок); `PUT /api/email-template` (ADMIN) → `EmailTemplateResponse` c `warnings`; `GET /api/email-template/default` → `{subject, body}` дефолта.

> **Отклонение от спека (обосновано):** активный рынок берём из `MarketContext.get()` (заголовок `X-Market`, его уже шлёт фронтовый `marketInterceptor`), а не из query `?market=`. Единообразно с остальным приложением, меньше дублирования.

- [ ] **Step 1: DTO**

`EmailTemplateResponse.java`:
```java
package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateResponse {
    private String market;
    private String subject;
    private String body;
    private OffsetDateTime updatedAt;
    private List<String> warnings;
}
```
`EmailTemplateRequest.java`:
```java
package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class EmailTemplateRequest {
    @NotBlank(message = "Тема не может быть пустой")
    private String subject;
    @NotBlank(message = "Текст письма не может быть пустым")
    private String body;
}
```

- [ ] **Step 2: Failing-тесты API** (дописать в существующий `EmailTemplateEndpointTest`)

Добавить методы (класс уже `@SpringBootTest @Transactional`; autowire контроллер + `MarketContext`):
```java
    @Autowired com.vladoose.nir.controller.EmailTemplateController controller;

    @org.junit.jupiter.api.AfterEach
    void clearMarket() { com.vladoose.nir.context.MarketContext.clear(); }

    @Test
    void getReturnsDefaultWhenNoRow() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var resp = controller.get();
        assertThat(resp.getSubject()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_SUBJECT);
        assertThat(resp.getBody()).contains("{{позиции}}");
        assertThat(resp.getMarket()).isEqualTo("KZ");
    }

    @Test
    void putUpsertsAndGetReturnsSaved() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var req = new com.vladoose.nir.dto.request.EmailTemplateRequest();
        req.setSubject("Моя тема"); req.setBody("Тело {{позиции}} конец");
        controller.put(req);
        assertThat(controller.get().getSubject()).isEqualTo("Моя тема");
        // upsert — второй PUT не плодит строк
        req.setSubject("Тема 2"); controller.put(req);
        assertThat(repository.findByMarket(Market.KZ)).isPresent();
        assertThat(controller.get().getSubject()).isEqualTo("Тема 2");
    }

    @Test
    void putWarnsWhenBodyLacksPositions() {
        com.vladoose.nir.context.MarketContext.set(Market.KZ);
        var req = new com.vladoose.nir.dto.request.EmailTemplateRequest();
        req.setSubject("t"); req.setBody("нет плейсхолдера позиций");
        var resp = controller.put(req);
        assertThat(resp.getWarnings()).contains("no-positions");
    }

    @Test
    void defaultEndpointReturnsCodeDefault() {
        com.vladoose.nir.context.MarketContext.set(Market.RF);
        var resp = controller.getDefault();
        assertThat(resp.getSubject()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_SUBJECT);
        assertThat(resp.getBody()).isEqualTo(com.vladoose.nir.service.KpEmailComposer.DEFAULT_BODY);
    }
```

- [ ] **Step 3: Прогнать — падает**

Run: `cd /Users/vlad/IdeaProjects/AIS && lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateEndpointTest'` (sandbox off).
Expected: FAIL — контроллера/сервиса нет.

- [ ] **Step 4: Сервис**

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.entity.EmailTemplate;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.EmailTemplateRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Чтение/сохранение шаблона письма КП для активного рынка (MarketContext). Дефолт — из KpEmailComposer. */
@Service
public class EmailTemplateService {

    private final EmailTemplateRepository repository;

    public EmailTemplateService(EmailTemplateRepository repository) {
        this.repository = repository;
    }

    /** Текущий сохранённый шаблон рынка, либо дефолт, если строки ещё нет. */
    public EmailTemplateResponse current() {
        Market market = MarketContext.get();
        return repository.findByMarket(market)
                .map(t -> new EmailTemplateResponse(market.name(), t.getSubjectTemplate(),
                        t.getBodyTemplate(), t.getUpdatedAt(), List.of()))
                .orElseGet(() -> new EmailTemplateResponse(market.name(),
                        KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of()));
    }

    /** Зашитый дефолт (для «Сбросить»). */
    public EmailTemplateResponse defaults() {
        Market market = MarketContext.get();
        return new EmailTemplateResponse(market.name(),
                KpEmailComposer.DEFAULT_SUBJECT, KpEmailComposer.DEFAULT_BODY, null, List.of());
    }

    @Transactional
    public EmailTemplateResponse save(String subject, String body) {
        Market market = MarketContext.get();
        EmailTemplate t = repository.findByMarket(market).orElseGet(EmailTemplate::new);
        t.setMarket(market);
        t.setSubjectTemplate(subject);
        t.setBodyTemplate(body);
        t.setUpdatedAt(OffsetDateTime.now());
        repository.save(t);
        List<String> warnings = new ArrayList<>();
        if (body == null || !body.contains("{{позиции}}")) warnings.add("no-positions");
        return new EmailTemplateResponse(market.name(), subject, body, t.getUpdatedAt(), warnings);
    }
}
```

- [ ] **Step 5: Контроллер**

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.EmailTemplateRequest;
import com.vladoose.nir.dto.response.EmailTemplateResponse;
import com.vladoose.nir.service.EmailTemplateService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/email-template")
public class EmailTemplateController {

    private final EmailTemplateService service;

    public EmailTemplateController(EmailTemplateService service) {
        this.service = service;
    }

    /** Текущий шаблон активного рынка (или дефолт). */
    @GetMapping
    public EmailTemplateResponse get() {
        return service.current();
    }

    /** Зашитый дефолт активного рынка (для «Сбросить»). */
    @GetMapping("/default")
    public EmailTemplateResponse getDefault() {
        return service.defaults();
    }

    /** Сохранить шаблон активного рынка (upsert). */
    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public EmailTemplateResponse put(@Valid @RequestBody EmailTemplateRequest req) {
        return service.save(req.getSubject(), req.getBody());
    }
}
```

- [ ] **Step 6: Прогнать — зелёный**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.email.EmailTemplateEndpointTest'` (sandbox off).
Expected: PASS (repo-тест + 4 API-теста).

- [ ] **Step 7: Регресс всей сборки**

Run: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test` (sandbox off).
Expected: падают ТОЛЬКО 2 `ApplyAutoFillServiceTest`.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/dto/request/EmailTemplateRequest.java src/main/java/com/vladoose/nir/dto/response/EmailTemplateResponse.java src/main/java/com/vladoose/nir/service/EmailTemplateService.java src/main/java/com/vladoose/nir/controller/EmailTemplateController.java src/test/java/com/vladoose/nir/email/EmailTemplateEndpointTest.java && git commit -m "feat(kp-template): API GET/PUT/default шаблона письма (рынок из MarketContext, warnings без {{позиции}})

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Фронт — страница «Шаблон письма КП»

**Files:**
- Create: `frontend/src/app/pages/email-template/email-template.component.ts`
- Modify: `frontend/src/app/services/api.service.ts`
- Modify: `frontend/src/app/app.routes.ts`
- Modify: `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: `GET/PUT /api/email-template`, `GET /api/email-template/default` (Task 4). `X-Market` уже вешает `marketInterceptor`.
- Produces: страница с темой+телом, легендой плейсхолдеров, «Сохранить»/«Сбросить».

- [ ] **Step 1: `ApiService` — методы**

Добавить (рядом с прочими доменными методами):
```ts
  getEmailTemplate(): Observable<any> {
    return this.http.get<any>(`${this.base}/email-template`);
  }
  getEmailTemplateDefault(): Observable<any> {
    return this.http.get<any>(`${this.base}/email-template/default`);
  }
  saveEmailTemplate(body: { subject: string; body: string }): Observable<any> {
    return this.http.put<any>(`${this.base}/email-template`, body);
  }
```

- [ ] **Step 2: Компонент страницы**

`frontend/src/app/pages/email-template/email-template.component.ts`:
```ts
import { Component, OnInit, ChangeDetectorRef } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { MarketService } from '../../services/market.service';

@Component({
  selector: 'app-email-template',
  standalone: true,
  imports: [CommonModule, FormsModule],
  template: `
    <div class="et-page">
      <h2>Шаблон письма КП</h2>
      <p class="et-market">Рынок: <b>{{ marketLabel }}</b> — редактируется шаблон активного рынка.</p>

      <label class="et-lbl">Тема письма</label>
      <input class="et-input" [(ngModel)]="subject" placeholder="Запрос коммерческого предложения" />

      <label class="et-lbl">Текст письма</label>
      <textarea class="et-body" rows="18" [(ngModel)]="body"
                (focus)="lastField = 'body'" (click)="lastField = 'body'"></textarea>

      <div class="et-vars">
        <span class="et-vars-title">Плейсхолдеры (клик — вставить):</span>
        <button type="button" class="et-chip" *ngFor="let p of placeholders"
                (click)="insert(p.key)" [title]="p.desc">{{ p.key }}</button>
      </div>
      <p class="et-note">Метка [КП-№] и подстановка позиций/дат — автоматические. Письмо намеренно не указывает номер тендера.</p>

      <div class="et-actions">
        <button class="btn btn-save" [disabled]="saving" (click)="save()">{{ saving ? 'Сохранение…' : 'Сохранить' }}</button>
        <button class="btn btn-line" (click)="reset()">Сбросить</button>
      </div>
    </div>
  `,
  styles: [`
    .et-page { max-width: 820px; }
    .et-market { color: #6b7280; font-size: 13px; margin: 4px 0 16px; }
    .et-lbl { display: block; font-size: 13px; color: #374151; margin: 12px 0 4px; font-weight: 600; }
    .et-input { width: 100%; padding: 8px 10px; border: 1px solid #d1d5db; border-radius: 6px; }
    .et-body { width: 100%; padding: 10px; border: 1px solid #d1d5db; border-radius: 6px; font: inherit; resize: vertical; }
    .et-vars { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 12px 0; }
    .et-vars-title { font-size: 12px; color: #6b7280; margin-right: 4px; }
    .et-chip { background: #eef2ff; color: #3730a3; border: none; border-radius: 999px; padding: 3px 10px; font-size: 12px; cursor: pointer; }
    .et-chip:hover { background: #e0e7ff; }
    .et-note { color: #6b7280; font-size: 12px; }
    .et-actions { display: flex; gap: 10px; margin-top: 16px; }
    .btn { padding: 8px 16px; border-radius: 6px; border: none; cursor: pointer; font-size: 14px; }
    .btn-save { background: #4f46e5; color: #fff; }
    .btn-line { background: #fff; border: 1px solid #d1d5db; color: #374151; }
  `],
})
export class EmailTemplateComponent implements OnInit {
  subject = '';
  body = '';
  saving = false;
  lastField: 'subject' | 'body' = 'body';
  placeholders = [
    { key: '{{приветствие}}', desc: 'Уважаемый(ая) ФИО! или Здравствуйте!' },
    { key: '{{компания}}', desc: 'Название вашей компании' },
    { key: '{{позиции}}', desc: 'Список оборудования (обязательно)' },
    { key: '{{дедлайн}}', desc: 'Просим ответить до даты' },
    { key: '{{реестр}}', desc: 'НЦЭЛС РК / Росздравнадзора' },
  ];

  constructor(private api: ApiService, private notify: NotificationService,
              private market: MarketService, private cdr: ChangeDetectorRef) {}

  get marketLabel() { return this.market.companyLabel(); }  // MarketService: companyLabel()/value/symbol()

  ngOnInit() {
    this.api.getEmailTemplate().subscribe({
      next: (t) => { this.subject = t.subject || ''; this.body = t.body || ''; this.cdr.detectChanges(); },
      error: (e) => this.notify.error('Не удалось загрузить шаблон: ' + (e.error?.message || e.message)),
    });
  }

  insert(key: string) {
    if (this.lastField === 'subject') this.subject = (this.subject || '') + key;
    else this.body = (this.body || '') + key;
    this.cdr.detectChanges();
  }

  save() {
    this.saving = true;
    this.api.saveEmailTemplate({ subject: this.subject, body: this.body }).subscribe({
      next: (r) => {
        this.saving = false;
        if ((r.warnings || []).includes('no-positions'))
          this.notify.error('Сохранено, но в тексте нет {{позиции}} — список оборудования не попадёт в письмо');
        else this.notify.success('Шаблон сохранён');
        this.cdr.detectChanges();
      },
      error: (e) => { this.saving = false; this.notify.error('Ошибка сохранения: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  reset() {
    this.api.getEmailTemplateDefault().subscribe({
      next: (t) => {
        this.subject = t.subject || ''; this.body = t.body || '';
        this.notify.success('Загружен стандартный шаблон — нажмите «Сохранить», чтобы применить');
        this.cdr.detectChanges();
      },
      error: (e) => this.notify.error('Ошибка: ' + (e.error?.message || e.message)),
    });
  }
}
```

> `MarketService` API (проверено): `companyLabel()` (полное название компании рынка), `value` (getter `Market`), `market` (signal), `symbol()`. `marketLabel` использует `companyLabel()`. Метода `current()` НЕТ — не использовать.

- [ ] **Step 3: Роут**

В `frontend/src/app/app.routes.ts`: импорт вверху `import { EmailTemplateComponent } from './pages/email-template/email-template.component';` и в дети `LayoutComponent` (рядом с `users`) добавить:
```ts
      { path: 'email-template', component: EmailTemplateComponent, canActivate: [adminGuard] },
```

- [ ] **Step 4: Ссылка в сайдбаре (группа «Система»)**

В `frontend/src/app/layout/layout.component.ts`, блок `<span class="nav-group-title">Система</span>`, после ссылки «Пользователи» добавить:
```html
            <a *ngIf="auth.isAdmin()" routerLink="/email-template" routerLinkActive="active">
              <svg lucideIcon="mail" [size]="16"></svg> Шаблон письма КП
            </a>
```
> Layout использует `LucideDynamicIcon` (динамические иконки по строковому имени) — `lucideIcon="mail"` валиден (`mail` — реальная lucide-иконка), доп. регистрация не нужна.

- [ ] **Step 5: Сборка фронта**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`.
Expected: BUILD SUCCESS (новый компонент — свой бюджет стилей, не трогает 16 kB бюджет tenders).

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/email-template/email-template.component.ts frontend/src/app/services/api.service.ts frontend/src/app/app.routes.ts frontend/src/app/layout/layout.component.ts && git commit -m "feat(kp-template): страница «Шаблон письма КП» (тема/тело/плейсхолдеры/Сбросить)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: Живая проверка (Playwright) + CLAUDE.md

**Files:**
- Modify: `CLAUDE.md` (§9/§15/§16)

- [ ] **Step 1: Поднять стек**

Backend: `cd /Users/vlad/IdeaProjects/AIS && ./gradlew bootRun` (sandbox off, фон). Frontend: `cd frontend && npm start` (фон). MailHog: убедиться `curl -s -o /dev/null -w "%{http_code}" http://localhost:8025` = 200 (иначе `docker start nir2-mailhog`). Дождаться :8080/:4200.

- [ ] **Step 2: Playwright — сценарий**

Navigate `http://localhost:4200`, логин admin/admin, `localStorage.setItem('ais.market','KZ')`. Открыть **«Система → Шаблон письма КП»** (или `/email-template`). Убедиться: поля заполнены дефолтом (тема «Запрос коммерческого предложения», тело с `{{позиции}}`). Вписать в тело узнаваемую фразу (напр. «ТЕСТ-ШАБЛОН-2026»), нажать «Сохранить» → тост. Перейти на импортный KZ-тендер (`?openId=412`), «КП» на лоте → выбрать поставщиков → «Отправить запросы» → в диалоге превью тело содержит «ТЕСТ-ШАБЛОН-2026» и НЕ содержит номер тендера/ссылку → «Отправить». Открыть `http://localhost:8025` (MailHog) — письмо с новым текстом, в теме `[КП-<id>]`, без goszakup-ссылки. Вернуться в редактор → «Сбросить» → поля вернулись к дефолту. Снять скриншот `kp-template-editor.png`.

- [ ] **Step 3: Обновить CLAUDE.md**

§9: добавить, что тело/тема КП строятся из редактируемого шаблона рынка (`email_template`, V10) через `EmailTemplateRenderer` с плейсхолдерами `{{приветствие}}/{{компания}}/{{позиции}}/{{дедлайн}}/{{реестр}}`, фолбэк на `KpEmailComposer.DEFAULT_*`; **письмо больше НЕ раскрывает тендер** (убраны номер и ссылка на объявление, `announceLink` удалён). §15: `/api/email-template` (GET/PUT/`default`), рынок из `X-Market`/`MarketContext`. §16: отметить редактор шаблона сделанным; follow-up — `{{обращение}}`, HTML-шаблон, опц. раскрытие тендера.

- [ ] **Step 4: Commit + финиш**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md kp-template-editor.png && git commit -m "docs: CLAUDE.md — редактор шаблона письма КП + нераскрытие тендера

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```
Затем — whole-branch review (superpowers:requesting-code-review) и finishing-a-development-branch (merge --ff-only в main, удалить ветку).

---

## Self-Review (выполнено при написании)

- **Покрытие спека:** §4 сущность/таблица → Task 1 (сид опущен — обосновано в Global Constraints/Task 1); §5 плейсхолдеры + анти-лик → Task 3; §6 рендер+фолбэк → Task 2 (renderer) + Task 3 (композер); §7 API → Task 4 (`?market=`→`MarketContext`, обосновано); §8 фронт → Task 5; §9 тесты → Task 1/2/3/4 + Task 6 (Playwright); §10 миграция → Task 1 (V10 без сида).
- **Отклонения от спека (обоснованы):** (1) V10 без сид-текста — фолбэк-константа единственный источник дефолта, нет дублирования SQL↔Java и дрейфа; поведение для пользователя идентично (GET отдаёт дефолт при пустой строке). (2) Рынок из `MarketContext`/`X-Market`, не query `?market=` — единообразно с приложением.
- **Плейсхолдеры-заглушки:** нет; каждый шаг содержит конкретный код/команды.
- **Согласованность типов:** `EmailTemplate`(market/subjectTemplate/bodyTemplate/updatedAt) ↔ репозиторий `findByMarket` ↔ сервис ↔ `KpEmailComposer.loadTemplate`; `DEFAULT_SUBJECT`/`DEFAULT_BODY` (public, Task 3) используются в Task 4 сервисе; `EmailTemplateResponse{market,subject,body,updatedAt,warnings}` ↔ фронт (`t.subject/t.body/r.warnings`); `render(String,Map)` (Task 2) ↔ вызовы в композере (Task 3); контроллер `get()/getDefault()/put()` ↔ тест Task 4 ↔ фронт-методы Task 5.
