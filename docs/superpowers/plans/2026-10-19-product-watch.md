# Наша продукция в тендерах — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **В этом проекте — superpowers:executing-plans, всё в основной сессии, без субагентов** (CLAUDE.md §2, решение оператора 2026-10-18).

**Goal:** АИС сама находит по всей стране (goszakup + лоты СК-Фармации) тендеры и пункты планов закупок с нашими видами изделий, заводит тендеры в «Тендеры» с отметкой «🏷 наш вид» / «★ точно наше» (бренд или модель названы, ТЗ списано с нашего товара), сообщает в тему «Тендеры» (★ — со звуком), показывает «Планы закупок» и по кнопке в частной заявке ищет тендеры и планы под позиции клиента.

**Architecture:** Правила сопоставления — чистые функции в `service/productwatch` (текст → группа `TYPE`, бренд или модель `NAMED`, фразы каталога в ТЗ `COPIED`, похожие товары) над снимком в памяти (`ProductCatalog`: группы из `product_group`, товары из `site_product`). Поиск по стране — новый `GoszakupSearchClient` (v3 GraphQL: лоты по слову, объявления по `commonName`, объявление по номеру, планы, договоры), реализованный тем же `GoszakupHttpClient`; заведение — прежний `GoszakupTenderWriter`; отметки пишет `ProductMatchService` в своей транзакции; ТЗ — прежний `TechSpecService` через общий с фоновой очередью `TechSpecLotParser`. Прогоны (поиск, планы, каталог, пересчёт) — один исполнитель `product-watch` (`ProductWatchScheduler`, cron в коде). Уведомления — `ProductWatchNotifier` + `ProductWatchComposer`; импорт ЗКО добавляет строку «наш товар» в «🏥 Новый тендер». «Найти тендеры» — фоновая задача на заявку (`TenderSearchService`, исполнитель `tender-search`). Фронт — две ленивые страницы («Наша продукция», «Планы закупок»), чипы и фильтр в «Тендерах», отметки у лотов, панель в частной заявке.

**Tech Stack:** Java 17, Spring Boot 3.5.6 (Data JPA, Hibernate 6 JSONB, method security, scheduling), Flyway, PostgreSQL 17, JDK `HttpServer` в тестах; Angular 21 (standalone, инлайн-шаблоны).

**Spec:** `docs/superpowers/specs/2026-10-19-product-watch-design.md` (§19 — решения при планировании, дописываются задачей 13).

## Global Constraints

- Миграция — **V36** (`V36__product_watch.sql`); старые миграции не трогать.
- Только рынок **KZ**: все фоновые прогоны ставят `MarketContext.set(Market.KZ)` в своём потоке и `clear()` в `finally` (CLAUDE.md §6); пути чтения на РФ отдают пусто, пути записи на РФ — 400 «Только для Казахстана».
- Запись — `@PreAuthorize("hasRole('ADMIN')")`; чтение — любой вошедший.
- Сеть — вне транзакции; запись — отдельным бином с `@Transactional` (не self-invoke).
- Запросы к goszakup из фоновых прогонов — с паузой **300 мс**; `401 / 403 / 429` (после штатных повторов `UpstreamRetry`) — прогон останавливается, ошибка в итоге.
- Публичную карточку товара сайта `/api/v1/products/{slug}` **не вызывать** (накручивает просмотры); каталог — админ-API (`/api/admin/products`, `/api/admin/products/{id}`) учёткой `leads.westmed.*`, без неё — публичный список `/api/v1/products?size=500`.
- Уровни отметок: `TYPE` < `NAMED` < `COPIED`; «★» — `NAMED` и `COPIED`.
- Пределы группы: название 1–120; слов поиска 1–10, слово ≤ 40 без пробелов; уточнений, исключений, брендов — ≤ 30 по ≤ 40; групп на рынок ≤ 60.
- Telegram: тема «Тендеры» (`TELEGRAM_TENDERS_THREAD_ID`), сообщение ≤ 3500 символов, поля — `TelegramText.oneLine`; сообщение с ★ — со звуком, иначе тихо.
- Настройки (все с умолчаниями в Java, yaml — пустые плейсхолдеры, как у `TenderAutoImportScheduler`): `PRODUCT_WATCH_ENABLED` (false), `PRODUCT_WATCH_CRON` (`0 30 8-19 * * MON-SAT`), `PRODUCT_WATCH_PLANS_CRON` (`0 30 7 * * MON-SAT`), `PRODUCT_WATCH_CATALOG_CRON` (`0 0 6 * * *`), `PRODUCT_WATCH_ZONE` (`Asia/Oral`), `PRODUCT_WATCH_SPEC_PER_RUN` (10). Пустая строка — умолчание, «-» — выключено, опечатка — выключено с ERROR, старт не роняет.
- Фронт: новые страницы — только ленивые (`loadComponent`); `ApiService` берёт типы через `import type` из `shared/product-watch.ts`; токены UI-kit, обе темы, цели 40 px при ≤ 900 px; классы с приставкой (`pg-`, `pp-`, `ts-`, `pw-`), не совпадают с классами UI-kit (`styles.scss`).
- `./gradlew` — с `dangerouslyDisableSandbox: true`; перед полным прогоном глушить `bootRun` (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`) и `devMail`; полный прогон — `./gradlew cleanTest test`.
- Ветка `feature/product-watch`; каждый коммит кончается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`.
- Мутация — откат копией файла (`cp файл /tmp/pw-mut && … && cp /tmp/pw-mut файл && diff -q`), не `git checkout --`; серию мутаций заканчивать `./gradlew compileJava`.
- Токен goszakup общий с продом: живые запросы при разработке — малыми порциями (одна-две группы), не полным прогоном.

## Review Focus

1. **Одно объявление нашлось по нескольким словам и группам** (лот «Отсасыватель» — по «отсасыватель», объявление «Аппарат для аспирации дыма» — по «аспирации») — заводится один раз, отметки по каждой группе, одно сообщение → тест `sameAnnouncementSeveralWords_importedOnce` (задача 4).
2. **Регистр, «ё» и латинские двойники** в тексте лота («ОБЛУЧАТЕЛЬ», «Облучатeль» с латинской e, «Электрокардиограф ЭК12Т-01») — отметка ставится так же → тест `caseYoAndLatinLookalikes` (задача 3).
3. **Срок приёма прошёл между поиском и отправкой** (найден в 08:30, приём до 08:45, сообщение — в 08:50) — тендер не сообщается → тест `deadlinePassedBeforeSend_notNotified` (задача 8).
4. **Кривой ввод на странице групп** (пустые слова поиска, слово с пробелом, повтор слова, пустое название) — 400 с понятным текстом, ничего не сохранено → тест `invalidGroups_400_nothingSaved` (задача 9).
5. **403 посреди прогона** — прогон останавливается, уже заведённые тендеры остаются, ошибка в итоге, повторных запросов нет → тест `forbiddenMidRun_stopsKeepsWritten` (задача 4).

## Карта файлов

Сервер (`src/main/java/com/vladoose/nir/…`):

| Файл | Что |
|---|---|
| `resources/db/migration/V36__product_watch.sql` | таблицы `product_group`, `site_product`, `tender_lot_product_match`, `procurement_plan_point`, `procurement_plan_match`; колонки тендера и лота; сид 24 групп |
| `entity/ProductGroup.java`, `SiteProduct.java`, `TenderLotProductMatch.java`, `ProcurementPlanPoint.java`, `ProcurementPlanMatch.java`, `ProductMatchLevel.java`, `ProductMatchSource.java`, `SimilarProductRef.java`, `SiteProductAttribute.java` | сущности и перечисления |
| изменяются `entity/Tender.java`, `entity/TenderLot.java` | `foundByProductSearch`, `productNotifiedLevel/At`; `techSpecAttempts` |
| `repository/ProductGroupRepository.java`, `SiteProductRepository.java`, `TenderLotProductMatchRepository.java`, `ProcurementPlanPointRepository.java`, `ProcurementPlanMatchRepository.java` | |
| `integration/westmed/WestmedCatalogClient.java`, `dto/WestmedCatalogItem.java` | каталог сайта (админ-API или публичный список) |
| `integration/goszakup/GoszakupSearchClient.java`, `dto/FoundLot.java`, `dto/FoundAnnouncement.java`, `dto/FoundPlanPoint.java`, `dto/ContractInfo.java` | v3-поиск по стране |
| изменяются `GoszakupHttpClient.java`, `GoszakupCallException.java`, `WestmedHttpClient.java` | реализации, код ответа в исключении |
| `integration/CronSchedules.java` | общая регистрация cron (из `TenderAutoImportScheduler`) |
| `service/productwatch/ProductText.java` | нормализация, слова, стемы |
| `service/productwatch/GroupRule.java`, `BrandModels.java`, `CatalogPhrases.java`, `SimilarProducts.java`, `ProductMatcher.java`, `CatalogSnapshot.java`, `LotMatch.java`, `LotInput.java` | правила (чистые функции) |
| `service/productwatch/ProductCatalog.java` | снимок в памяти, `refresh()` |
| `service/productwatch/SiteCatalogSync.java` | загрузка каталога |
| `service/productwatch/ProductMatchService.java` | отметки тендера (транзакция) |
| `service/productwatch/ProductWatchService.java` | поиск по стране |
| `service/productwatch/ProductSpecChecker.java` | ТЗ найденных лотов |
| `service/productwatch/ProductRematchService.java` | пересчёт |
| `service/productwatch/ProductWatchScheduler.java` | исполнитель, cron, статус |
| `service/productwatch/ProductWatchNotifier.java`, `ProductWatchComposer.java` | Telegram |
| `service/productwatch/ProductGroupService.java` | страница групп, «Проверить» |
| `service/productwatch/ProductMatchQuery.java` | сводки для «Тендеров» и уведомлений |
| `service/productwatch/ProcurementPlanService.java`, `ProcurementPlanWriter.java`, `ProcurementPlanDigest.java`, `ProcurementPlanQuery.java` | планы: сбор, запись, сводка, страница |
| `service/productwatch/TenderSearchService.java`, `AnnouncementService.java` | «Найти тендеры», «Завести в АИС», «Итоги» |
| `service/TechSpecLotParser.java` | разбор ТЗ одного лота с исходом (из `TechSpecBackfillScheduler.processOne`) |
| `controller/ProductWatchController.java`, `ProcurementPlanController.java`, `TenderSearchController.java` | API |
| `dto/response/ProductGroupsResponse.java`, `ProductGroupDto.java`, `ProbeResponse.java`, `OurProductSummary.java`, `LotProductMatchDto.java`, `PlanPointDto.java`, `TenderSearchStatus.java`, `TenderSearchHit.java`, `AnnouncementResult.java`; `dto/request/ProductGroupsRequest.java` | |
| изменяются `dto/response/TenderResponse.java`, `TenderLotResponse.java`, `controller/TenderController.java`, `integration/goszakup/ImportSummary.java`, `GoszakupImportScheduler.java`, `GoszakupTenderWriter.java`, `skpharmacy/SkPharmacyImportScheduler.java`, `integration/TenderAutoImportScheduler.java`, `integration/goszakup/RegionResolver.java` (падежи Астаны), `repository/TenderRepository.java`, `service/TechSpecBackfillScheduler.java`, `service/TechSpecStatusWriter.java`, `service/tendernotify/NewTenderNotifier.java`, `NewTenderComposer.java`, `integration/telegram/TelegramClient.java`, `resources/application.yaml`, `.env.example`, `DEPLOY.md` | |

Фронт (`frontend/src/app/…`):

| Файл | Что |
|---|---|
| `shared/product-watch.ts` | типы и подписи |
| `pages/product-groups/product-groups.component.ts` | «Наша продукция» |
| `pages/procurement-plans/procurement-plans.component.ts` | «Планы закупок» |
| `pages/private-requests/pr-tender-search.component.ts` | «Найти тендеры по стране» |
| изменяются `services/api.service.ts`, `app.routes.ts`, `app.config.ts`, `layout/layout.component.ts`, `pages/tenders/tenders.component.ts`, `tenders-filters.component.ts`, `tender-lots.component.ts`, `pages/private-requests/private-request-page.component.ts` | |

Тесты (`src/test/java/com/vladoose/nir/productwatch/…`, ресурсы `src/test/resources/product-watch/…`).

---

### Task 1: Данные — миграция V36, сущности, репозитории, сид 24 групп

**Files:**
- Create: `src/main/resources/db/migration/V36__product_watch.sql`
- Create: `src/main/java/com/vladoose/nir/entity/ProductMatchLevel.java`, `ProductMatchSource.java`, `ProductGroup.java`, `SiteProduct.java`, `SiteProductAttribute.java`, `SimilarProductRef.java`, `TenderLotProductMatch.java`, `ProcurementPlanPoint.java`, `ProcurementPlanMatch.java`
- Modify: `src/main/java/com/vladoose/nir/entity/Tender.java` (три поля), `src/main/java/com/vladoose/nir/entity/TenderLot.java` (одно поле)
- Create: `src/main/java/com/vladoose/nir/repository/ProductGroupRepository.java`, `SiteProductRepository.java`, `TenderLotProductMatchRepository.java`, `ProcurementPlanPointRepository.java`, `ProcurementPlanMatchRepository.java`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductWatchSchemaTest.java`

**Interfaces:**
- Produces: перечисления `ProductMatchLevel { TYPE, NAMED, COPIED; boolean exact(); static ProductMatchLevel max(ProductMatchLevel a, ProductMatchLevel b) }`, `ProductMatchSource { SEARCH, IMPORT, REMATCH }`; сущности с полями ниже; репозитории:
  - `ProductGroupRepository.findByMarketOrderByPositionAscIdAsc(Market)`, `findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market)`;
  - `SiteProductRepository.findByMarketAndRemovedAtIsNull(Market)`, `findByMarket(Market)`, `Optional<SiteProduct> findBySiteId(UUID)`;
  - `TenderLotProductMatchRepository.findByTenderId(Long)`, `findByTenderIdIn(Collection<Long>)`, `findByLotId(Long)`, `existsByTenderId(Long)`;
  - `ProcurementPlanPointRepository.findByExtId(Long)`, `findByGoneAtIsNull()`;
  - `ProcurementPlanMatchRepository.findByPlanPointIdIn(Collection<Long>)`.

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V36 (спека 2026-10-19-product-watch §4): сид групп, JSONB туда и обратно, каскады. */
@SpringBootTest
@Transactional
class ProductWatchSchemaTest {

    @Autowired ProductGroupRepository groups;
    @Autowired SiteProductRepository products;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProcurementPlanPointRepository planPoints;
    @Autowired ProcurementPlanMatchRepository planMatches;
    @Autowired TenderRepository tenders;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Сид V36 — пока группы KZ не правили (updated_at пуст у всех); правили — только инварианты. */
    @Test
    void seed_24kzGroups_brandGroupFirst() {
        List<ProductGroup> kz = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ);
        assertThat(kz).allSatisfy(g -> assertThat(g.getSearchWords()).isNotEmpty());
        if (kz.stream().anyMatch(g -> g.getUpdatedAt() != null)) return;
        assertThat(kz).hasSize(24);
        assertThat(kz.get(0).isBrandGroup()).isTrue();
        assertThat(kz.get(0).getSearchWords()).contains("фотек", "аксион", "эмалед", "эхвч");
        assertThat(kz).filteredOn(ProductGroup::isBrandGroup).hasSize(1);
        ProductGroup lamps = kz.stream().filter(g -> g.getName().startsWith("Светильники")).findFirst().orElseThrow();
        assertThat(lamps.getSearchWords()).containsExactly("светильник");
        assertThat(lamps.getRequireWords()).contains("операцион", "хирург");
        assertThat(lamps.getExcludeWords()).contains("уличн", "прожектор");
        assertThat(lamps.getBrandWords()).contains("эмалед");
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.RF)).isEmpty();
    }

    @Test
    void jsonbRoundTripAndCascades() {
        ProductGroup g = groups.save(ProductGroup.builder().market(Market.KZ).name("Тест-группа")
                .searchWords(List.of("тестслово")).position(99).build());
        SiteProduct p = products.save(SiteProduct.builder().market(Market.KZ).siteId(UUID.randomUUID())
                .name("Тестовый аппарат ТА-100").brand("Фотек")
                .attributes(List.of(new SiteProductAttribute("Мощность", "100 Вт")))
                .syncedAt(OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("PW-TEST-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        TenderLot lot = new TenderLot(); lot.setTender(t); lot.setEquipName("Тестовый аппарат");
        t.getLots().add(lot);
        tenders.save(t);
        em.flush();
        matches.save(TenderLotProductMatch.builder().tenderId(t.getId()).lotId(lot.getId()).groupId(g.getId())
                .word("тестслово").level(ProductMatchLevel.COPIED).evidence("совпало 3 фразы").productId(p.getId())
                .similar(List.of(new SimilarProductRef(p.getId(), p.getName(), "Фотек", "ta-100", 0.8)))
                .source(ProductMatchSource.SEARCH).build());
        em.flush(); em.clear();

        TenderLotProductMatch m = matches.findByTenderId(t.getId()).get(0);
        assertThat(m.getSimilar()).extracting(SimilarProductRef::slug).containsExactly("ta-100");
        assertThat(products.findBySiteId(p.getSiteId()).orElseThrow().getAttributes())
                .extracting(SiteProductAttribute::value).containsExactly("100 Вт");
        assertThat(ProductMatchLevel.max(ProductMatchLevel.TYPE, ProductMatchLevel.COPIED)).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(ProductMatchLevel.NAMED.exact()).isTrue();
        assertThat(ProductMatchLevel.TYPE.exact()).isFalse();

        tenders.deleteById(t.getId());                       // каскад тендер → лот → отметка
        em.flush(); em.clear();
        assertThat(matches.findByTenderId(t.getId())).isEmpty();

        ProcurementPlanPoint pp = planPoints.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(-1L).year(2026)
                .name("Электрокардиограф").level(ProductMatchLevel.TYPE)
                .firstSeenAt(OffsetDateTime.now()).lastSeenAt(OffsetDateTime.now()).build());
        planMatches.save(ProcurementPlanMatch.builder().planPointId(pp.getId()).groupId(g.getId()).word("тестслово").build());
        em.flush(); em.clear();
        groups.deleteById(g.getId());                        // каскад группа → отметки плана
        em.flush(); em.clear();
        assertThat(planMatches.findByPlanPointIdIn(List.of(pp.getId()))).isEmpty();
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests '*ProductWatchSchemaTest*'` (sandbox off)
Expected: FAIL компиляции — нет `ProductGroup`, `ProductGroupRepository` и др.

- [ ] **Step 3: Write the migration**

`src/main/resources/db/migration/V36__product_watch.sql`:

```sql
-- Наша продукция в тендерах (спека 2026-10-19-product-watch §4): группы видов изделий, каталог сайта, отметки на лотах,
-- признаки тендера и лота, пункты планов закупок. Группы и каталог — не рыночные сущности с явным рынком (как
-- reply_template), пункты планов — рыночная (market + @Filter), отметки — через тендер.
CREATE TABLE product_group (
    id            BIGSERIAL    PRIMARY KEY,
    market        VARCHAR(2)   NOT NULL,
    name          VARCHAR(120) NOT NULL,
    search_words  JSONB        NOT NULL DEFAULT '[]',
    require_words JSONB        NOT NULL DEFAULT '[]',
    exclude_words JSONB        NOT NULL DEFAULT '[]',
    brand_words   JSONB        NOT NULL DEFAULT '[]',
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    -- «Наши бренды и модели»: слова поиска — бренды и модели, находки сразу NAMED (спека §5.3)
    brand_group   BOOLEAN      NOT NULL DEFAULT FALSE,
    position      INT          NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ,
    updated_by    VARCHAR(100)
);
CREATE INDEX idx_product_group_market ON product_group (market, position);

CREATE TABLE site_product (
    id                BIGSERIAL    PRIMARY KEY,
    market            VARCHAR(2)   NOT NULL,
    site_id           UUID         NOT NULL UNIQUE,
    name              VARCHAR(500) NOT NULL,
    sku               VARCHAR(50),
    slug              VARCHAR(500),
    brand             VARCHAR(200),
    category          VARCHAR(200),
    short_description TEXT,
    description       TEXT,
    attributes        JSONB        NOT NULL DEFAULT '[]',
    registration_cert VARCHAR(200),
    site_updated_at   TIMESTAMPTZ,
    synced_at         TIMESTAMPTZ  NOT NULL,
    removed_at        TIMESTAMPTZ
);
CREATE INDEX idx_site_product_market ON site_product (market) WHERE removed_at IS NULL;

CREATE TABLE tender_lot_product_match (
    id              BIGSERIAL     PRIMARY KEY,
    tender_id       BIGINT        NOT NULL REFERENCES tender (id) ON DELETE CASCADE,
    lot_id          BIGINT        NOT NULL REFERENCES tender_lot (id) ON DELETE CASCADE,
    group_id        BIGINT        NOT NULL REFERENCES product_group (id) ON DELETE CASCADE,
    word            VARCHAR(100),
    level           VARCHAR(10)   NOT NULL,
    evidence        VARCHAR(1000),
    product_id      BIGINT        REFERENCES site_product (id) ON DELETE SET NULL,
    similar         JSONB         NOT NULL DEFAULT '[]',
    spec_checked_at TIMESTAMPTZ,
    source          VARCHAR(10)   NOT NULL,
    matched_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_lot_product_match UNIQUE (lot_id, group_id)
);
CREATE INDEX idx_lot_product_match_tender ON tender_lot_product_match (tender_id);

ALTER TABLE tender ADD COLUMN found_by_product_search BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE tender ADD COLUMN product_notified_level VARCHAR(10);
ALTER TABLE tender ADD COLUMN product_notified_at TIMESTAMPTZ;
ALTER TABLE tender_lot ADD COLUMN tech_spec_attempts SMALLINT NOT NULL DEFAULT 0;

CREATE TABLE procurement_plan_point (
    id             BIGSERIAL     PRIMARY KEY,
    market         VARCHAR(2)    NOT NULL,
    ext_id         BIGINT        NOT NULL UNIQUE,
    year           INT           NOT NULL,
    month          SMALLINT,
    customer_bin   VARCHAR(20),
    customer_name  VARCHAR(500),
    region         VARCHAR(100),
    delivery_place TEXT,
    name           VARCHAR(500)  NOT NULL,
    description    TEXT,
    quantity       NUMERIC(15,3),
    unit           VARCHAR(100),
    unit_price     NUMERIC(15,2),
    amount         NUMERIC(15,2),
    trade_method   VARCHAR(200),
    status_id      INT,
    status_name    VARCHAR(100),
    level          VARCHAR(10)   NOT NULL,
    first_seen_at  TIMESTAMPTZ   NOT NULL,
    last_seen_at   TIMESTAMPTZ   NOT NULL,
    gone_at        TIMESTAMPTZ,
    gone_status    VARCHAR(100),
    digested_at    TIMESTAMPTZ
);
CREATE INDEX idx_plan_point_open ON procurement_plan_point (market, year, month) WHERE gone_at IS NULL;

CREATE TABLE procurement_plan_match (
    id            BIGSERIAL    PRIMARY KEY,
    plan_point_id BIGINT       NOT NULL REFERENCES procurement_plan_point (id) ON DELETE CASCADE,
    group_id      BIGINT       NOT NULL REFERENCES product_group (id) ON DELETE CASCADE,
    word          VARCHAR(100),
    CONSTRAINT ux_plan_match UNIQUE (plan_point_id, group_id)
);

-- Сид: 24 группы рынка KZ по каталогу westmed.kz на 2026-10-19 (спека §4.1). Слова поиска — в формах площадки
-- (поиск площадки — одно целое слово, без морфологии), уточнения и исключения — стемы (начало слова).
INSERT INTO product_group (market, name, search_words, require_words, exclude_words, brand_words, brand_group, position) VALUES
('KZ', 'Наши бренды и модели (части и принадлежности)',
 '["фотек","fotek","аксион","эмалед","эхвч","эхвча","митар","актитон","actiton"]', '[]', '[]', '[]', TRUE, 1),
('KZ', 'Электрокардиографы (Аксион, НПП Монитор)', '["электрокардиограф"]', '[]', '[]',
 '["аксион","эк1т","эк3тц","эк12т","эк3т","крп-01","нпп монитор"]', FALSE, 2),
('KZ', 'Дефибрилляторы (Аксион)', '["дефибриллятор"]', '[]', '[]', '["аксион","дки-н","да-н"]', FALSE, 3),
('KZ', 'Аппараты ИВЛ (Аксион)', '["вентиляции"]', '["легк"]', '["приточн","вытяжн"]', '["а-ивл"]', FALSE, 4),
('KZ', 'Облучатели и рециркуляторы бактерицидные, УФ (ЭМА)', '["облучатель","рециркулятор"]',
 '["бактерицид","кварц","ультрафиолет","рециркул","обеззараж"]', '[]',
 '["рбм","рбпе","рбмт","обн","обп","обпе","оуфну","эма"]', FALSE, 5),
('KZ', 'Облучатели фототерапевтические (Аксион)', '["облучатель","фототерапии"]', '["фототерап","новорожд","неонат"]', '[]',
 '["офтн","аксион"]', FALSE, 6),
('KZ', 'Обогрев новорождённых (Аксион)', '["обогрева"]', '["новорожд","неонат"]', '[]', '["уон","аксион"]', FALSE, 7),
('KZ', 'Светильники операционные и смотровые (ЭМАЛЕД)', '["светильник"]',
 '["операцион","хирург","бестенев","смотров","медицин","процедур"]', '["уличн","прожектор","офисн","аварийн","взрывозащ"]',
 '["эмалед"]', FALSE, 8),
('KZ', 'Электрохирургия (Фотек)', '["электрохирургический","электрокоагулятор","коагулятор"]', '[]', '[]',
 '["фотек","эхвч","эхвча","onyx","термошов"]', FALSE, 9),
('KZ', 'Отсасыватели и аспирация дыма (Аксион, Фотек)', '["отсасыватель","аспиратор","аспирации"]', '[]', '["катетер","назальн"]',
 '["асд","фотек","омх","омп","ог-10","аксион"]', FALSE, 10),
('KZ', 'Ультразвуковая хирургия (Фотек ACTITON)', '["ультразвуковой"]', '["хирург","костн","остеотом","скальпел"]', '[]',
 '["actiton","актитон","фотек"]', FALSE, 11),
('KZ', 'Инсуффляторы (Фотек)', '["инсуффлятор"]', '[]', '[]', '["фотек"]', FALSE, 12),
('KZ', 'Нейтральные электроды и принадлежности ЭХВЧ (Фотек)', '["электрод"]',
 '["нейтральн","возвратн","пассивн","электрохирург"]', '["сварочн","экг","кардиограф","сравнения","искров"]',
 '["фотек","valleylab"]', FALSE, 13),
('KZ', 'Биполярные инструменты (Фотек)', '["пинцет","ножницы"]', '["биполяр"]', '[]', '["фотек","cleantips"]', FALSE, 14),
('KZ', 'Стойки аппаратные и инфузионные (Фотек)', '["стойка"]', '["инфузион","аппарат","медицин","эндоскоп"]',
 '["ресепшн","серверн","телекоммуникац","стеллаж"]', '["сп1-01","сп2-01","фотек"]', FALSE, 15),
('KZ', 'Стерилизаторы озоновые (Орион)', '["стерилизатор"]', '["озон"]', '[]',
 '["орион","ск-16","ск-36","ск-40","ск-85","ск-140","ск-250"]', FALSE, 16),
('KZ', 'Озонотерапия (Орион)', '["озонатор","озонотерапии"]', '[]', '[]', '["орион","от-15"]', FALSE, 17),
('KZ', 'Индикаторы стерилизации (Орион)', '["индикатор"]', '["стерилиз","стерильн"]', '[]', '["озовин","дезтест"]', FALSE, 18),
('KZ', 'Магнитотерапия (ЭМА «Полюс»)', '["магнитотерапии"]', '[]', '[]', '["полюс"]', FALSE, 19),
('KZ', 'Электрофорез (ЭМА «Поток»)', '["электрофореза"]', '["аппарат","физиотерап","гальван"]', '["гель","капилляр","реагент","белк"]',
 '["поток-1"]', FALSE, 20),
('KZ', 'Спирографы (НПП Монитор)', '["спирограф","спирометр"]', '[]', '[]', '["смп-02","нпп монитор"]', FALSE, 21),
('KZ', 'Холтер и кардиорегистраторы (НПП Монитор)', '["холтер","кардиорегистратор","мониторирования"]',
 '["экг","суточн","холтер","кардиорегистр"]', '[]', '["крн-01","крб-02","нпп монитор"]', FALSE, 22),
('KZ', 'Мониторы пациента (НПП Монитор)', '["монитор"]', '["пациент","прикроват","реанимац","витальн"]',
 '["компьютер","жидкокристал","диагональ","офисн"]', '["митар","нпп монитор"]', FALSE, 23),
('KZ', 'Дозаторы шприцевые (Аксион)', '["дозатор"]', '["шприц","инфузион"]',
 '["лаборатор","пипет","мыл","антисептик","локтев","бумаж"]', '["д01","аксион"]', FALSE, 24);
```

- [ ] **Step 4: Write enums and entities**

`entity/ProductMatchLevel.java`:

```java
package com.vladoose.nir.entity;

/** Уровень отметки «наш товар» (спека 2026-10-19-product-watch §4.3): порядок значим — TYPE < NAMED < COPIED. */
public enum ProductMatchLevel {
    /** Наш вид изделия. */
    TYPE,
    /** Назван наш бренд или модель. */
    NAMED,
    /** ТЗ списано с нашего товара. */
    COPIED;

    /** «★ точно наше». */
    public boolean exact() { return this != TYPE; }

    public static ProductMatchLevel max(ProductMatchLevel a, ProductMatchLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
```

`entity/ProductMatchSource.java`:

```java
package com.vladoose.nir.entity;

/** Кто поставил отметку: поиск по стране, импорт ЗКО / СК-Фармации, пересчёт (молча, спека §8–§9). */
public enum ProductMatchSource { SEARCH, IMPORT, REMATCH }
```

`entity/SiteProductAttribute.java` и `entity/SimilarProductRef.java` (элементы JSONB):

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Характеристика товара сайта (JSONB `site_product.attributes`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SiteProductAttribute(String name, String value) {}
```

```java
package com.vladoose.nir.entity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Похожий наш товар (JSONB `tender_lot_product_match.similar`). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SimilarProductRef(long productId, String name, String brand, String slug, double score) {}
```

`entity/ProductGroup.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Группа «нашей продукции» (V36, спека 2026-10-19-product-watch §4.1). Не рыночная сущность с явным рынком (как
 * reply_template): читается и пишется по MarketContext, без @Filter.
 */
@Entity
@Table(name = "product_group")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false, length = 120)
    private String name;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "search_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> searchWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "require_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> requireWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exclude_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> excludeWords = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "brand_words", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<String> brandWords = new ArrayList<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean active = true;

    @Column(name = "brand_group", nullable = false)
    private boolean brandGroup;

    @Column(nullable = false)
    private int position;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @Column(name = "updated_by", length = 100)
    private String updatedBy;
}
```

`entity/SiteProduct.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Товар каталога westmed.kz (V36, спека §4.2): правится только на сайте, АИС раз в сутки забирает копию. */
@Entity
@Table(name = "site_product")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class SiteProduct {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(name = "site_id", nullable = false, unique = true)
    private UUID siteId;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(length = 50)
    private String sku;

    @Column(length = 500)
    private String slug;

    @Column(length = 200)
    private String brand;

    @Column(length = 200)
    private String category;

    @Column(name = "short_description", columnDefinition = "TEXT")
    private String shortDescription;

    @Column(columnDefinition = "TEXT")
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<SiteProductAttribute> attributes = new ArrayList<>();

    @Column(name = "registration_cert", length = 200)
    private String registrationCert;

    @Column(name = "site_updated_at")
    private OffsetDateTime siteUpdatedAt;

    @Column(name = "synced_at", nullable = false)
    private OffsetDateTime syncedAt;

    @Column(name = "removed_at")
    private OffsetDateTime removedAt;
}
```

`entity/TenderLotProductMatch.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Отметка «наш товар» на лоте (V36, спека §4.3): строка на пару «лот × группа». Не рыночная — через тендер
 * (читать только после гарда рынка тендера), каскадом от тендера, лота и группы.
 */
@Entity
@Table(name = "tender_lot_product_match")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TenderLotProductMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tender_id", nullable = false)
    private Long tenderId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(length = 100)
    private String word;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchLevel level;

    @Column(length = 1000)
    private String evidence;

    @Column(name = "product_id")
    private Long productId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<SimilarProductRef> similar = new ArrayList<>();

    @Column(name = "spec_checked_at")
    private OffsetDateTime specCheckedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchSource source;

    @Column(name = "matched_at", nullable = false)
    @Builder.Default
    private OffsetDateTime matchedAt = OffsetDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private OffsetDateTime updatedAt = OffsetDateTime.now();
}
```

`entity/ProcurementPlanPoint.java` (рыночная — `@Filter` + листенер, `@FilterDef` НЕ объявлять — он один на `Tender`, CLAUDE.md §6):

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** Пункт плана закупок goszakup с нашим видом изделия (V36, спека §4.6, §10). */
@Entity
@Table(name = "procurement_plan_point")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcurementPlanPoint implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(name = "ext_id", nullable = false, unique = true)
    private Long extId;

    @Column(nullable = false)
    private int year;

    private Integer month;

    @Column(name = "customer_bin", length = 20)
    private String customerBin;

    @Column(name = "customer_name", length = 500)
    private String customerName;

    @Column(length = 100)
    private String region;

    @Column(name = "delivery_place", columnDefinition = "TEXT")
    private String deliveryPlace;

    @Column(nullable = false, length = 500)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(precision = 15, scale = 3)
    private BigDecimal quantity;

    @Column(length = 100)
    private String unit;

    @Column(name = "unit_price", precision = 15, scale = 2)
    private BigDecimal unitPrice;

    @Column(precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "trade_method", length = 200)
    private String tradeMethod;

    @Column(name = "status_id")
    private Integer statusId;

    @Column(name = "status_name", length = 100)
    private String statusName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ProductMatchLevel level;

    @Column(name = "first_seen_at", nullable = false)
    private OffsetDateTime firstSeenAt;

    @Column(name = "last_seen_at", nullable = false)
    private OffsetDateTime lastSeenAt;

    @Column(name = "gone_at")
    private OffsetDateTime goneAt;

    @Column(name = "gone_status", length = 100)
    private String goneStatus;

    @Column(name = "digested_at")
    private OffsetDateTime digestedAt;
}
```

`entity/ProcurementPlanMatch.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

/** Группа пункта плана (V36): строка на пару «пункт × группа», каскадом от пункта и группы. */
@Entity
@Table(name = "procurement_plan_match")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProcurementPlanMatch {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "plan_point_id", nullable = false)
    private Long planPointId;

    @Column(name = "group_id", nullable = false)
    private Long groupId;

    @Column(length = 100)
    private String word;
}
```

Изменения `entity/Tender.java` — после поля `customerBin`:

```java
    /** Тендер заведён поиском нашей продукции по стране (спека 2026-10-19-product-watch §7.3): обновляется им же. */
    @Column(name = "found_by_product_search", nullable = false)
    @Builder.Default
    private boolean foundByProductSearch = false;

    /** Наибольший уровень «наш товар», уже сообщённый в Telegram (спека §9); пусто — не сообщали. */
    @Enumerated(EnumType.STRING)
    @Column(name = "product_notified_level", length = 10)
    private ProductMatchLevel productNotifiedLevel;

    @Column(name = "product_notified_at")
    private OffsetDateTime productNotifiedAt;
```

Изменение `entity/TenderLot.java` — после `techSpecAttemptedAt`:

```java
    /** Сколько раз поиск нашей продукции пытался разобрать ТЗ лота (спека 2026-10-19-product-watch §7.4): не больше 3. */
    @Column(name = "tech_spec_attempts", nullable = false)
    @Builder.Default
    private int techSpecAttempts = 0;
```

- [ ] **Step 5: Write repositories**

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductGroupRepository extends JpaRepository<ProductGroup, Long> {
    List<ProductGroup> findByMarketOrderByPositionAscIdAsc(Market market);
    List<ProductGroup> findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market market);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SiteProductRepository extends JpaRepository<SiteProduct, Long> {
    List<SiteProduct> findByMarketAndRemovedAtIsNull(Market market);
    List<SiteProduct> findByMarket(Market market);
    Optional<SiteProduct> findBySiteId(UUID siteId);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.TenderLotProductMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface TenderLotProductMatchRepository extends JpaRepository<TenderLotProductMatch, Long> {
    List<TenderLotProductMatch> findByTenderId(Long tenderId);
    List<TenderLotProductMatch> findByTenderIdIn(Collection<Long> tenderIds);
    List<TenderLotProductMatch> findByLotId(Long lotId);
    boolean existsByTenderId(Long tenderId);
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProcurementPlanPoint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Рыночная сущность: аспект включает фильтр рынка (CLAUDE.md §6). */
public interface ProcurementPlanPointRepository extends JpaRepository<ProcurementPlanPoint, Long> {
    Optional<ProcurementPlanPoint> findByExtId(Long extId);
    List<ProcurementPlanPoint> findByGoneAtIsNull();
}
```

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ProcurementPlanMatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ProcurementPlanMatchRepository extends JpaRepository<ProcurementPlanMatch, Long> {
    List<ProcurementPlanMatch> findByPlanPointIdIn(Collection<Long> planPointIds);
    void deleteByPlanPointId(Long planPointId);
}
```

- [ ] **Step 6: Run test to verify it passes**

Run: `./gradlew test --tests '*ProductWatchSchemaTest*'` (sandbox off)
Expected: PASS (2 теста); в логе Flyway «Migrating schema "public" to version "36 - product watch"».

- [ ] **Step 7: Mutation check**

Миграцию после применения к nirdb не мутировать (контрольная сумма Flyway). Мутации кода: (а) `ProductMatchLevel.max` всегда возвращает `a` → `jsonbRoundTripAndCascades` красный (`max(TYPE, COPIED)`); (б) у `TenderLotProductMatch.similar` убрать `@JdbcTypeCode(SqlTypes.JSON)` → контекст или тест JSONB красный. Вернуть копией файла, `./gradlew compileJava`.

- [ ] **Step 8: Commit**

```bash
git add src/main/resources/db/migration/V36__product_watch.sql src/main/java/com/vladoose/nir/entity/ src/main/java/com/vladoose/nir/repository/ src/test/java/com/vladoose/nir/productwatch/ProductWatchSchemaTest.java
git commit -m "feat(product-watch): V36 — группы нашей продукции с сидом, каталог сайта, отметки, планы закупок

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: Каталог сайта — клиент, загрузка, снимок товаров

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/westmed/WestmedCatalogClient.java`, `src/main/java/com/vladoose/nir/integration/westmed/dto/WestmedCatalogItem.java`
- Modify: `src/main/java/com/vladoose/nir/integration/westmed/WestmedHttpClient.java` (реализует `WestmedCatalogClient`)
- Create: `src/main/java/com/vladoose/nir/service/productwatch/SiteCatalogSync.java`
- Test: `src/test/java/com/vladoose/nir/integration/westmed/WestmedCatalogClientTest.java`, `src/test/java/com/vladoose/nir/productwatch/SiteCatalogSyncTest.java`, `src/test/java/com/vladoose/nir/productwatch/FakeWestmedCatalogClient.java`

**Interfaces:**
- Consumes: `SiteProduct`, `SiteProductRepository` (задача 1).
- Produces:
  - `WestmedCatalogItem(UUID id, String name, String sku, String slug, String brand, String category, String shortDescription, String description, List<SiteProductAttribute> attributes, String registrationCert, Instant updatedAt)`;
  - `WestmedCatalogClient { boolean adminConfigured(); List<WestmedCatalogItem> adminCatalog(); List<WestmedCatalogItem> publicCatalog(); }` — админ-список (страницы по 100, только `ACTIVE`, без `deletedAt`) с карточками `/api/admin/products/{id}`; публичный — `/api/v1/products?size=500&page=…` (без описаний и характеристик);
  - `SiteCatalogSync.sync()` → `SyncResult(int count, int added, int updated, int removed, boolean full)`; бросает `UpstreamException` (502) при недоступном сайте; после записи зовёт `ProductCatalog.refresh()` (задача 3) — до задачи 3 вызов добавляется отдельным шагом задачи 3.

- [ ] **Step 1: Write the failing client test**

`WestmedCatalogClientTest` — стаб на `HttpServer` (как `GoszakupHttpClientTest`): вход `/api/auth/login` → `{"accessToken":"t"}`; `/api/admin/products?page=0&size=100&status=ACTIVE` → страница из двух товаров, `last:false`; `page=1` → один товар, `last:true`; `/api/admin/products/{id}` → карточка с `description`, `attributes`, `registrationCert`, `updatedAt`; `/api/v1/products?size=500&page=0` → три товара. Счётчик запросов к пути `/api/v1/products/` + slug — должен остаться 0.

```java
package com.vladoose.nir.integration.westmed;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Каталог сайта для поиска нашей продукции (спека 2026-10-19-product-watch §6): админ-API и публичный список. */
class WestmedCatalogClientTest {

    HttpServer server;
    final AtomicInteger publicCards = new AtomicInteger();
    final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());

    static final String A = "11111111-1111-1111-1111-111111111111";
    static final String B = "22222222-2222-2222-2222-222222222222";
    static final String C = "33333333-3333-3333-3333-333333333333";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/auth/login", ex -> reply(ex, 200, "{\"accessToken\":\"t\"}"));
        server.createContext("/api/admin/products", ex -> {
            String path = ex.getRequestURI().getPath();
            String q = ex.getRequestURI().getQuery() == null ? "" : ex.getRequestURI().getQuery();
            if (path.equals("/api/admin/products")) {
                if (q.contains("page=0")) reply(ex, 200, page(false, item(A, "Блок аспирации для аппарата АСД-\\\"ФОТЕК\\\""), item(B, "Электрокардиограф ЭК12Т-01")));
                else reply(ex, 200, page(true, item(C, "Удалённый товар", "\"deletedAt\":\"2026-10-01T00:00:00Z\"")));
                return;
            }
            String id = path.substring("/api/admin/products/".length());
            reply(ex, 200, "{\"id\":\"" + id + "\",\"name\":\"Карточка " + id.substring(0, 1) + "\",\"slug\":\"s-" + id.substring(0, 1)
                    + "\",\"sku\":\"sku\",\"brandName\":\"Фотек\",\"categoryName\":\"Хирургическое оборудование\","
                    + "\"description\":\"Комплект: блок аспирации, пневмопедаль, держатель шланга аспирационного напольный\","
                    + "\"shortDescription\":\"кратко\",\"registrationCert\":\"РК-МТ-7№010807\",\"updatedAt\":\"2026-10-10T05:00:00Z\","
                    + "\"attributes\":[{\"attributeName\":\"Производительность\",\"attributeValue\":\"300 л/мин\",\"sortOrder\":1}]}");
        });
        server.createContext("/api/v1/products", ex -> {
            if (!ex.getRequestURI().getPath().equals("/api/v1/products")) { publicCards.incrementAndGet(); reply(ex, 404, "{}"); return; }
            reply(ex, 200, "{\"content\":[{\"id\":\"" + A + "\",\"name\":\"Блок аспирации\",\"slug\":\"blok\",\"sku\":\"fotek-1\","
                    + "\"shortDescription\":\"кратко\",\"brandName\":\"Фотек\",\"categoryName\":\"Хирургическое оборудование\"}],"
                    + "\"last\":true,\"totalPages\":1,\"number\":0}");
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private static String item(String id, String name, String... extra) {
        return "{\"id\":\"" + id + "\",\"name\":\"" + name + "\",\"slug\":\"x\",\"sku\":\"s\",\"categoryName\":\"Кат\","
                + "\"brandName\":\"Фотек\",\"status\":\"ACTIVE\"" + (extra.length > 0 ? "," + extra[0] : "") + "}";
    }

    private static String page(boolean last, String... items) {
        return "{\"content\":[" + String.join(",", items) + "],\"last\":" + last + ",\"number\":" + (last ? 1 : 0) + "}";
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String body) throws java.io.IOException {
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(code, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    private WestmedHttpClient client(String user, String pass) {
        return new WestmedHttpClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), user, pass, Duration.ofSeconds(5));
    }

    @Test
    void adminCatalog_allPages_cardsWithDescriptionAttributes_skipsDeleted_neverPublicCard() {
        List<WestmedCatalogItem> items = client("ais@westmed.kz", "secret").adminCatalog();
        assertThat(items).extracting(WestmedCatalogItem::id).extracting(Object::toString).containsExactly(A, B);
        WestmedCatalogItem a = items.get(0);
        assertThat(a.description()).contains("держатель шланга аспирационного напольный");
        assertThat(a.attributes()).extracting(x -> x.name() + "=" + x.value()).containsExactly("Производительность=300 л/мин");
        assertThat(a.registrationCert()).isEqualTo("РК-МТ-7№010807");
        assertThat(a.updatedAt()).isNotNull();
        assertThat(publicCards.get()).isZero();
    }

    @Test
    void publicCatalog_listOnly() {
        WestmedHttpClient c = client("", "");
        assertThat(c.adminConfigured()).isFalse();
        List<WestmedCatalogItem> items = c.publicCatalog();
        assertThat(items).extracting(WestmedCatalogItem::name).containsExactly("Блок аспирации");
        assertThat(items.get(0).description()).isNull();
        assertThat(publicCards.get()).isZero();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*WestmedCatalogClientTest*'`
Expected: FAIL компиляции — нет `WestmedCatalogItem`, `adminCatalog`.

- [ ] **Step 3: Implement the client**

`integration/westmed/dto/WestmedCatalogItem.java`:

```java
package com.vladoose.nir.integration.westmed.dto;

import com.vladoose.nir.entity.SiteProductAttribute;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Товар каталога сайта: из админ-API — с описанием, характеристиками и РУ; из публичного списка — без них. */
public record WestmedCatalogItem(UUID id, String name, String sku, String slug, String brand, String category,
                                 String shortDescription, String description, List<SiteProductAttribute> attributes,
                                 String registrationCert, Instant updatedAt) {}
```

`integration/westmed/WestmedCatalogClient.java`:

```java
package com.vladoose.nir.integration.westmed;

import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;

import java.util.List;

/** Каталог westmed.kz (спека 2026-10-19-product-watch §6). Публичную карточку /api/v1/products/{slug} не зовём — просмотры. */
public interface WestmedCatalogClient {

    /** Задана учётка админ-API сайта (leads.westmed.username / password). */
    boolean adminConfigured();

    /** Активные товары с карточками (описание, характеристики, РУ): список по 100 + карточка каждого. */
    List<WestmedCatalogItem> adminCatalog();

    /** Публичный список (название, бренд, раздел, краткое описание) — для разработки без пароля сайта. */
    List<WestmedCatalogItem> publicCatalog();
}
```

В `WestmedHttpClient`: `implements WestmedClient, WestmedCatalogClient`; добавить (рядом с `PRODUCT_PAGE`) приватные записи разбора и методы:

```java
    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdminListItem(UUID id, String name, String slug, String sku, String categoryName, String brandName,
                         String status, Instant deletedAt) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdminAttribute(String attributeName, String attributeValue, Integer sortOrder) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AdminCard(UUID id, String name, String slug, String sku, String description, String shortDescription,
                     String registrationCert, String categoryName, String brandName, Instant updatedAt,
                     Instant deletedAt, List<AdminAttribute> attributes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PublicItem(UUID id, String name, String slug, String sku, String shortDescription, String categoryName,
                      String brandName) {}

    private static final TypeReference<WestmedPage<AdminListItem>> ADMIN_PAGE = new TypeReference<>() {};
    private static final TypeReference<AdminCard> ADMIN_CARD = new TypeReference<>() {};
    private static final TypeReference<WestmedPage<PublicItem>> PUBLIC_PAGE = new TypeReference<>() {};
    /** Предохранитель обхода страниц (100 × 20 = 2000 товаров — с запасом над живыми 196). */
    static final int CATALOG_MAX_PAGES = 20;

    @Override
    public boolean adminConfigured() { return isConfigured(); }

    @Override
    public List<WestmedCatalogItem> adminCatalog() {
        List<WestmedCatalogItem> out = new ArrayList<>();
        for (int page = 0; page < CATALOG_MAX_PAGES; page++) {
            WestmedPage<AdminListItem> p = admin("GET", "/api/admin/products?page=" + page + "&size=100&status=ACTIVE", null, ADMIN_PAGE);
            for (AdminListItem i : p.contentOrEmpty()) {
                if (i.deletedAt() != null || i.id() == null) continue;
                AdminCard c = admin("GET", "/api/admin/products/" + i.id(), null, ADMIN_CARD);
                List<SiteProductAttribute> attrs = c.attributes() == null ? List.of() : c.attributes().stream()
                        .sorted(Comparator.comparing(a -> a.sortOrder() == null ? 0 : a.sortOrder()))
                        .map(a -> new SiteProductAttribute(a.attributeName(), a.attributeValue())).toList();
                out.add(new WestmedCatalogItem(i.id(), c.name(), c.sku(), c.slug(), c.brandName(), c.categoryName(),
                        c.shortDescription(), c.description(), attrs, c.registrationCert(), c.updatedAt()));
            }
            if (p.isLast()) break;
        }
        return out;
    }

    @Override
    public List<WestmedCatalogItem> publicCatalog() {
        List<WestmedCatalogItem> out = new ArrayList<>();
        for (int page = 0; page < CATALOG_MAX_PAGES; page++) {
            HttpResponse<String> r = send("GET", "/api/v1/products?size=500&page=" + page, null, null);
            if (r.statusCode() != 200) throw new WestmedApiException(r.statusCode(), "каталог сайта: HTTP " + r.statusCode());
            WestmedPage<PublicItem> p = parse(r.body(), PUBLIC_PAGE);
            for (PublicItem i : p.contentOrEmpty()) {
                if (i.id() == null) continue;
                out.add(new WestmedCatalogItem(i.id(), i.name(), i.sku(), i.slug(), i.brandName(), i.categoryName(),
                        i.shortDescription(), null, List.of(), null, null));
            }
            if (p.isLast()) break;
        }
        return out;
    }
```

(импорты: `com.fasterxml.jackson.annotation.JsonIgnoreProperties`, `com.vladoose.nir.entity.SiteProductAttribute`, `com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem`, `java.time.Instant`, `java.util.ArrayList`, `java.util.Comparator`, `java.util.UUID`.) `ObjectMapper` бина Spring уже знает `Instant` (JavaTimeModule) — тест передаёт свой с модулем.

- [ ] **Step 4: Run the client test**

Run: `./gradlew test --tests '*WestmedCatalogClientTest*'`
Expected: PASS (2).

- [ ] **Step 5: Write the failing sync test**

`FakeWestmedCatalogClient` (тест): поля `boolean admin; List<WestmedCatalogItem> items; RuntimeException fail; int adminCalls, publicCalls;` — методы отдают `items` или бросают `fail`.

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.integration.westmed.WestmedCatalogClient;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;

import java.util.ArrayList;
import java.util.List;

/** Управляемый фейк каталога сайта: без сети. */
public class FakeWestmedCatalogClient implements WestmedCatalogClient {
    public boolean admin = true;
    public List<WestmedCatalogItem> items = new ArrayList<>();
    public RuntimeException fail;
    public int adminCalls, publicCalls;

    @Override public boolean adminConfigured() { return admin; }
    @Override public List<WestmedCatalogItem> adminCatalog() { adminCalls++; if (fail != null) throw fail; return items; }
    @Override public List<WestmedCatalogItem> publicCatalog() { publicCalls++; if (fail != null) throw fail; return items; }
}
```

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.entity.SiteProductAttribute;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedApiException;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.service.productwatch.SiteCatalogSync;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Загрузка каталога сайта (спека §6): новые — вставка, изменённые — обновление, пропавшие — removed_at. */
@SpringBootTest
@Transactional
class SiteCatalogSyncTest {

    @Autowired SiteProductRepository products;
    @Autowired EntityManager em;
    @Autowired com.vladoose.nir.service.productwatch.ProductCatalog catalog;

    FakeWestmedCatalogClient fake;
    SiteCatalogSync sync;

    static final UUID A = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001");
    static final UUID B = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        products.deleteAllInBatch();          // своя картина в откатываемой транзакции (CLAUDE.md §14)
        em.flush();
        fake = new FakeWestmedCatalogClient();
        sync = new SiteCatalogSync(fake, products, catalog);
    }

    @AfterEach void clear() { MarketContext.clear(); catalog.invalidate(); }

    private static WestmedCatalogItem item(UUID id, String name, String description) {
        return new WestmedCatalogItem(id, name, "sku", "slug-" + id.toString().substring(35), "Фотек", "Хирургия",
                "кратко", description, List.of(new SiteProductAttribute("Мощность", "350 Вт")), "РК-МТ", Instant.parse("2026-10-10T05:00:00Z"));
    }

    @Test
    void addThenUpdateThenRemove() {
        fake.items = List.of(item(A, "Блок аспирации", "состав"), item(B, "ЭХВЧ-350-03", null));
        SiteCatalogSync.SyncResult r1 = sync.sync();
        assertThat(r1.added()).isEqualTo(2);
        assertThat(r1.full()).isTrue();
        assertThat(products.findByMarketAndRemovedAtIsNull(Market.KZ)).hasSize(2);

        fake.items = List.of(item(A, "Блок аспирации для АСД", "новый состав"));
        SiteCatalogSync.SyncResult r2 = sync.sync();
        assertThat(r2.updated()).isEqualTo(1);
        assertThat(r2.removed()).isEqualTo(1);
        SiteProduct a = products.findBySiteId(A).orElseThrow();
        assertThat(a.getName()).isEqualTo("Блок аспирации для АСД");
        assertThat(a.getDescription()).isEqualTo("новый состав");
        assertThat(products.findBySiteId(B).orElseThrow().getRemovedAt()).isNotNull();

        fake.items = List.of(item(A, "Блок аспирации для АСД", "новый состав"), item(B, "ЭХВЧ-350-03", null));
        sync.sync();
        assertThat(products.findBySiteId(B).orElseThrow().getRemovedAt()).as("вернулся на сайт").isNull();
    }

    @Test
    void withoutAdmin_publicList() {
        fake.admin = false;
        fake.items = List.of(item(A, "Блок аспирации", null));
        SiteCatalogSync.SyncResult r = sync.sync();
        assertThat(r.full()).isFalse();
        assertThat(fake.publicCalls).isEqualTo(1);
        assertThat(fake.adminCalls).isZero();
    }

    @Test
    void siteDown_502_catalogKept() {
        fake.items = List.of(item(A, "Блок аспирации", null));
        sync.sync();
        fake.fail = new WestmedApiException(0, "сайт недоступен: ConnectException");
        assertThatThrownBy(() -> sync.sync()).isInstanceOf(UpstreamException.class).hasMessageContaining("Каталог сайта не загрузился");
        assertThat(products.findByMarketAndRemovedAtIsNull(Market.KZ)).hasSize(1);
    }

    @Test
    void emptyAnswer_doesNotRemoveEverything() {
        fake.items = List.of(item(A, "Блок аспирации", null));
        sync.sync();
        fake.items = List.of();
        assertThatThrownBy(() -> sync.sync()).isInstanceOf(UpstreamException.class).hasMessageContaining("пустой");
        assertThat(products.findBySiteId(A).orElseThrow().getRemovedAt()).isNull();
    }
}
```

- [ ] **Step 6: Run to verify it fails**

Run: `./gradlew test --tests '*SiteCatalogSyncTest*'`
Expected: FAIL компиляции — нет `SiteCatalogSync`, `ProductCatalog`.

- [ ] **Step 7: Implement `SiteCatalogSync` (и заготовку `ProductCatalog`)**

`ProductCatalog` полностью пишется в задаче 3; здесь — минимальный бин с методом `refresh()` (пустое тело `public void refresh() {}` и javadoc «снимок групп и товаров — задача 3»), чтобы сервис загрузки уже звал его.

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.westmed.WestmedCatalogClient;
import com.vladoose.nir.integration.westmed.dto.WestmedCatalogItem;
import com.vladoose.nir.repository.SiteProductRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

/**
 * Каталог westmed.kz → site_product (спека 2026-10-19-product-watch §6). Сеть — вне транзакции, запись — одной
 * транзакцией; пустой ответ сайта — сбой, а не «все товары пропали» (иначе разовая заглушка сайта сняла бы каталог).
 */
@Service
public class SiteCatalogSync {

    private static final Logger log = LoggerFactory.getLogger(SiteCatalogSync.class);

    public record SyncResult(int count, int added, int updated, int removed, boolean full) {}

    private final WestmedCatalogClient client;
    private final SiteProductRepository products;
    private final ProductCatalog catalog;
    private TransactionTemplate tx;

    public SiteCatalogSync(WestmedCatalogClient client, SiteProductRepository products, ProductCatalog catalog) {
        this.client = client;
        this.products = products;
        this.catalog = catalog;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setTransactionManager(PlatformTransactionManager tm) { this.tx = new TransactionTemplate(tm); }

    public SyncResult sync() {
        boolean full = client.adminConfigured();
        List<WestmedCatalogItem> items;
        try {
            items = full ? client.adminCatalog() : client.publicCatalog();
        } catch (RuntimeException e) {
            log.warn("Каталог сайта не загрузился: {}", ErrorText.of(e));
            throw new UpstreamException("Каталог сайта не загрузился: " + ErrorText.of(e), e);
        }
        if (items.isEmpty()) throw new UpstreamException("Каталог сайта не загрузился: сайт отдал пустой список", null);
        SyncResult r = tx == null ? write(items, full) : tx.execute(s -> write(items, full));
        catalog.refresh();
        log.info("Каталог сайта: {} товаров (новых {}, изменено {}, пропало {}){}", r.count(), r.added(), r.updated(),
                r.removed(), full ? "" : " — публичный список, без описаний");
        return r;
    }

    private SyncResult write(List<WestmedCatalogItem> items, boolean full) {
        Market market = MarketContext.get();
        OffsetDateTime now = OffsetDateTime.now();
        Map<UUID, SiteProduct> known = new HashMap<>();
        for (SiteProduct p : products.findByMarket(market)) known.put(p.getSiteId(), p);
        Set<UUID> seen = new HashSet<>();
        int added = 0, updated = 0, removed = 0;
        for (WestmedCatalogItem i : items) {
            if (!seen.add(i.id())) continue;
            SiteProduct p = known.get(i.id());
            boolean isNew = p == null;
            if (isNew) p = SiteProduct.builder().market(market).siteId(i.id()).build();
            boolean changed = isNew || !Objects.equals(p.getName(), i.name()) || p.getRemovedAt() != null
                    || (full && !Objects.equals(p.getDescription(), i.description()));
            p.setName(trunc(i.name(), 500));
            p.setSku(trunc(i.sku(), 50));
            p.setSlug(trunc(i.slug(), 500));
            p.setBrand(trunc(i.brand(), 200));
            p.setCategory(trunc(i.category(), 200));
            p.setShortDescription(i.shortDescription());
            if (full) {                                  // публичный список описаний не несёт — прежние не затираем
                p.setDescription(i.description());
                p.setAttributes(i.attributes() == null ? new ArrayList<>() : new ArrayList<>(i.attributes()));
                p.setRegistrationCert(trunc(i.registrationCert(), 200));
                p.setSiteUpdatedAt(i.updatedAt() == null ? null : i.updatedAt().atOffset(ZoneOffset.UTC));
            }
            p.setRemovedAt(null);
            p.setSyncedAt(now);
            products.save(p);
            if (isNew) added++; else if (changed) updated++;
        }
        for (SiteProduct p : known.values()) {
            if (!seen.contains(p.getSiteId()) && p.getRemovedAt() == null) {
                p.setRemovedAt(now);
                products.save(p);
                removed++;
            }
        }
        return new SyncResult(seen.size(), added, updated, removed, full);
    }

    private static String trunc(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
```

(`UpstreamException(String, Throwable)` есть — сверено с классом.)

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests '*SiteCatalogSyncTest*' --tests '*WestmedCatalogClientTest*'`
Expected: PASS (6).

- [ ] **Step 9: Mutation check**

(а) в `write` убрать проверку `p.getRemovedAt() == null` у пропавших — тест не краснеет (счётчик), поэтому мутация другая: убрать `p.setRemovedAt(null)` у встреченных → `addThenUpdateThenRemove` красный («вернулся на сайт»). (б) убрать `if (items.isEmpty()) throw …` → `emptyAnswer_doesNotRemoveEverything` красный. Вернуть копией, `./gradlew compileJava`.

- [ ] **Step 10: Commit**

```bash
git add src/main/java/com/vladoose/nir/integration/westmed/ src/main/java/com/vladoose/nir/service/productwatch/SiteCatalogSync.java src/main/java/com/vladoose/nir/service/productwatch/ProductCatalog.java src/test/java/com/vladoose/nir/integration/westmed/WestmedCatalogClientTest.java src/test/java/com/vladoose/nir/productwatch/
git commit -m "feat(product-watch): каталог сайта — админ-API с описаниями или публичный список, загрузка в site_product

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 3: Правила сопоставления — текст, группа, бренд и модель, фразы ТЗ, похожие товары; снимок каталога

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductText.java`, `BrandModels.java`, `GroupRule.java`, `CatalogPhrases.java`, `SimilarProducts.java`, `CatalogProduct.java`, `CatalogSnapshot.java`, `LotInput.java`, `LotMatch.java`, `ProductMatcher.java`
- Modify: `src/main/java/com/vladoose/nir/service/productwatch/ProductCatalog.java` (заготовка задачи 2 → снимок)
- Create: `src/main/resources/product-watch/generic-phrases.txt` (общие фразы ТЗ)
- Create fixtures: `src/test/resources/product-watch/site-catalog.json` (публичный список сайта, 196 товаров), `src/test/resources/product-watch/specs/17779323-1.pdf` (ТЗ Астаны)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductTextTest.java`, `BrandModelsTest.java`, `GroupRuleTest.java`, `ProductMatcherTest.java`, `ProductFixtures.java`

**Interfaces:**
- Consumes: `ProductGroup`, `SiteProduct`, `SimilarProductRef`, `ProductMatchLevel` (задача 1); `MedicalGoodsVocabulary.matchesAny(String, List<String>)` (есть); `PdfTextExtractor.extract(byte[])`, `TechSpecExtractor.russianSection(String)` (есть, `util`).
- Produces:
  - `ProductText.normalize(String)`, `words(String)`, `significant(String)`, `prefix(String)`, `searchStem(String)`, `prefixes(String)`;
  - `BrandModels.compile(String) → Pattern`, `modelCodes(String) → List<String>`, `globalCode(String) → boolean`, `record Code(String label, Pattern pattern, Long productId)`, `static Hit first(List<Code>, String normalized) → Hit(String label, Long productId)` или `null`;
  - `record GroupRule(long id, String name, boolean brandGroup, List<String> searchWords, List<String> stems, List<String> require, List<String> exclude, List<BrandModels.Code> codes)`, `GroupRule.of(ProductGroup)`, `String typeWord(String lotText, String announcement, boolean singleLot)`;
  - `record CatalogProduct(long id, String name, String brand, String slug, String category, String shortDescription, String description)`;
  - `CatalogPhrases.build(List<CatalogProduct>) → Map<Long, List<Phrase>>`, `record Phrase(long productId, int segment, String text, List<String> prefixes)`, `CatalogPhrases.GENERIC` (из `src/main/resources/product-watch/generic-phrases.txt`), `index(List<String>)`, `CatalogPhrases.copied(List<CatalogProduct> family, Map<Long, List<Phrase>>, List<String> specPrefixes, List<String> groupStems) → Copy(long productId, int count, String evidence)` или `null`; константы `COPY_MIN = 3`, `BY_FAMILY = true`;
  - `SimilarProducts.idf(List<CatalogProduct>) → Map<String, Double>`, `top(List<CatalogProduct>, Set<String> lotPrefixes, Map<String, Double> idf) → List<SimilarProductRef>` (≤ 3, оценка ≥ 0,35);
  - `CatalogSnapshot.build(List<ProductGroup>, List<SiteProduct>)`; поля `groups()`, `products()`, `groupProducts(long groupId)`, `globalCodes()`, `phrases()`, `idf()`;
  - `record LotInput(long lotId, String name, String description, String spec)` (`spec` — разобранное ТЗ или `null`);
  - `record LotMatch(long lotId, long groupId, String word, ProductMatchLevel level, String evidence, Long productId, List<SimilarProductRef> similar, boolean specUsed)`;
  - `ProductMatcher.match(CatalogSnapshot, String announcementName, List<LotInput>) → List<LotMatch>`;
  - `ProductCatalog.get() → CatalogSnapshot` (лениво), `refresh()`, `invalidate()`.

- [ ] **Step 1: Prepare fixtures**

Каталог — публичный список сайта (данные открытые; карточку товара не звать):

```bash
python3 - <<'EOF'
import json, urllib.request
d = json.load(urllib.request.urlopen("https://westmed.kz/api/v1/products?page=0&size=500&lang=ru", timeout=30))
out = [{"id": p["id"], "name": p["name"], "slug": p.get("slug"), "brand": p.get("brandName"),
        "category": p.get("categoryName"), "shortDescription": p.get("shortDescription")} for p in d["content"]]
json.dump(out, open("src/test/resources/product-watch/site-catalog.json", "w"), ensure_ascii=False, indent=1)
print(len(out))
EOF
```
(sandbox off; ожидается 196). ТЗ Астаны — PDF лота 88111123-ЗЦП2 (тендер 17779323-1): `GoszakupClient.fetchLotTechSpec("17779323-1", "Отсасыватель")` + `downloadFile` живым токеном — или копия `techspec_17779323.pdf` из scratchpad сессии 2026-10-19 → `src/test/resources/product-watch/specs/17779323-1.pdf` (29 533 байт, данные госзакупок публичные).

`ProductFixtures` (тест) — общие помощники:

```java
package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.TechSpecExtractor;

import java.io.InputStream;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;

/** Снимок каталога сайта и ТЗ из src/test/resources/product-watch — для правил без базы. */
final class ProductFixtures {

    private ProductFixtures() {}

    record Item(String id, String name, String slug, String brand, String category, String shortDescription) {}

    static List<SiteProduct> catalog() {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/site-catalog.json")) {
            List<Item> items = new ObjectMapper().readValue(in, new TypeReference<>() {});
            AtomicLong id = new AtomicLong(1000);
            List<SiteProduct> out = new ArrayList<>();
            for (Item i : items) {
                out.add(SiteProduct.builder().id(id.incrementAndGet()).market(Market.KZ).siteId(UUID.fromString(i.id()))
                        .name(i.name()).slug(i.slug()).brand(i.brand()).category(i.category())
                        .shortDescription(i.shortDescription()).syncedAt(OffsetDateTime.now()).build());
            }
            return out;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Русская часть ТЗ — тем же кодом, что разбор «ТЗ» в работе. */
    static String spec(String file) {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/specs/" + file)) {
            return TechSpecExtractor.russianSection(PdfTextExtractor.extract(in.readAllBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static ProductGroup group(long id, String name, List<String> words, List<String> require, List<String> exclude, List<String> brands) {
        return ProductGroup.builder().id(id).market(Market.KZ).name(name).searchWords(words).requireWords(require)
                .excludeWords(exclude).brandWords(brands).active(true).position((int) id).build();
    }

    /** Группы как в сиде V36 (часть): бренды, ЭКГ, облучатели, светильники, отсасыватели, электроды, дозаторы, мониторы. */
    static List<ProductGroup> groups() {
        ProductGroup brands = group(1, "Наши бренды и модели", List.of("фотек", "fotek", "аксион", "эмалед", "эхвч", "эхвча", "митар", "актитон", "actiton"),
                List.of(), List.of(), List.of());
        brands.setBrandGroup(true);
        return List.of(brands,
                group(2, "Электрокардиографы", List.of("электрокардиограф"), List.of(), List.of(), List.of("аксион", "эк1т", "эк3тц", "эк12т", "эк3т", "крп-01", "нпп монитор")),
                group(5, "Облучатели и рециркуляторы", List.of("облучатель", "рециркулятор"), List.of("бактерицид", "кварц", "ультрафиолет", "рециркул", "обеззараж"), List.of(),
                        List.of("рбм", "рбпе", "рбмт", "обн", "обп", "обпе", "оуфну", "эма")),
                group(8, "Светильники", List.of("светильник"), List.of("операцион", "хирург", "бестенев", "смотров", "медицин", "процедур"),
                        List.of("уличн", "прожектор", "офисн", "аварийн", "взрывозащ"), List.of("эмалед")),
                group(10, "Отсасыватели и аспирация дыма", List.of("отсасыватель", "аспиратор", "аспирации"), List.of(), List.of("катетер", "назальн"),
                        List.of("асд", "фотек", "омх", "омп", "ог-10", "аксион")),
                group(13, "Нейтральные электроды", List.of("электрод"), List.of("нейтральн", "возвратн", "пассивн", "электрохирург"),
                        List.of("сварочн", "экг", "кардиограф", "сравнения", "искров"), List.of("фотек", "valleylab")),
                group(23, "Мониторы пациента", List.of("монитор"), List.of("пациент", "прикроват", "реанимац", "витальн"),
                        List.of("компьютер", "жидкокристал", "диагональ", "офисн"), List.of("митар", "нпп монитор")),
                group(24, "Дозаторы шприцевые", List.of("дозатор"), List.of("шприц", "инфузион"),
                        List.of("лаборатор", "пипет", "мыл", "антисептик", "локтев", "бумаж"), List.of("д01", "аксион")));
    }
}
```

- [ ] **Step 2: Write the failing unit tests**

`ProductTextTest`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTextTest {

    @Test
    void normalize_caseYoLookalikesOnlyInsideCyrillicWords() {
        assertThat(ProductText.normalize("ОБЛУЧАТЕЛЬ Облучатeль")).isEqualTo("облучатель облучатель");   // латинская e
        assertThat(ProductText.normalize("Ёмкость")).isEqualTo("емкость");
        assertThat(ProductText.normalize("ACTITON SpO2")).isEqualTo("actiton spo2");                     // чистая латиница — как есть
        assertThat(ProductText.normalize("ЭХBЧ-350")).isEqualTo("эхвч-350");                               // латинская B внутри кириллицы
    }

    @Test
    void searchStem_dropsVowelTailKeepsFive() {
        assertThat(ProductText.searchStem("облучатель")).isEqualTo("облучател");
        assertThat(ProductText.searchStem("аспирации")).isEqualTo("аспирац");
        assertThat(ProductText.searchStem("электрокардиограф")).isEqualTo("электрокардиограф");
        assertThat(ProductText.searchStem("стойка")).isEqualTo("стойк");
        assertThat(ProductText.searchStem("холтер")).isEqualTo("холтер");
    }

    @Test
    void prefixesAndSignificant() {
        assertThat(ProductText.prefixes("Держатель шланга аспирационного")).containsExactly("держа", "шланг", "аспир");
        assertThat(ProductText.significant("для")).isFalse();
        assertThat(ProductText.significant("150")).isFalse();
        assertThat(ProductText.significant("блок")).isTrue();
    }
}
```

`BrandModelsTest`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.service.productwatch.BrandModels;
import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BrandModelsTest {

    private static boolean hit(String word, String text) {
        Pattern p = BrandModels.compile(word);
        return p.matcher(ProductText.normalize(text)).find();
    }

    @Test
    void codesIgnoreSeparatorsAndExecution() {
        assertThat(hit("ЭХВЧ-350", "аппарат ЭХВЧ 350-03 «ФОТЕК»")).isTrue();
        assertThat(hit("ЭХВЧ-350-03", "эхвч35003")).isTrue();
        assertThat(hit("эк12т", "Электрокардиограф ЭК12Т-01-«Р-Д»")).isTrue();
        assertThat(hit("эк12т", "ЭК12ТЦ")).as("другая модель").isFalse();
        assertThat(hit("ск-16", "СК-160")).isFalse();
        assertThat(hit("дки-н", "Дефибриллятор ДКИ-Н-11")).isTrue();
    }

    @Test
    void shortWordsWholeLongWordsStem() {
        assertThat(hit("обн", "обновление ПО")).isFalse();
        assertThat(hit("обн", "Облучатель ОБН 150")).isTrue();
        assertThat(hit("аксион", "запчасти к дефибриллятору «Аксиона»")).isTrue();
        assertThat(hit("нпп монитор", "производства НПП «Монитор»")).isTrue();
        assertThat(hit("фотек", "FOTEK")).isFalse();
        assertThat(hit("fotek", "аппарат FOTEK")).isTrue();
    }

    @Test
    void modelCodesFromCatalogNames_globalOnlyDistinct() {
        assertThat(BrandModels.modelCodes("Дефибриллятор-монитор ДКИ-Н-11 «Аксион» (ЭКГ, SpO2, НИАД)"))
                .contains("ДКИ-Н-11", "SpO2");
        assertThat(BrandModels.modelCodes("ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-\"ФОТЕК\"")).contains("ЭХВЧ-350-03");
        assertThat(BrandModels.modelCodes("Рециркулятор бактерицидный настенный РБм 2Х15")).isEmpty();
        assertThat(BrandModels.globalCode("ДКИ-Н-11")).isTrue();
        assertThat(BrandModels.globalCode("SpO2")).isFalse();
        assertThat(BrandModels.globalCode("СК-16")).isFalse();
        assertThat(BrandModels.globalCode("ОБПЕ-450")).isTrue();
    }
}
```

`GroupRuleTest`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.service.productwatch.GroupRule;
import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Группа на лоте (спека §5.2): слово, уточнения, исключения, название объявления. */
class GroupRuleTest {

    private static GroupRule rule(long id) {
        return GroupRule.of(ProductFixtures.groups().stream().filter(g -> g.getId() == id).findFirst().orElseThrow());
    }

    private static String type(long groupId, String lot, String announcement, boolean single) {
        return rule(groupId).typeWord(ProductText.normalize(lot), ProductText.normalize(announcement), single);
    }

    @Test
    void lamps_requireAndExclude() {
        assertThat(type(8, "Светильник операционный потолочный", "", true)).isEqualTo("светильник");
        assertThat(type(8, "Светильник уличный светодиодный медицинского учреждения", "", true)).isNull();
        assertThat(type(8, "Светильник светодиодный", "Ремонт кабинета", true)).as("нет уточнения").isNull();
    }

    @Test
    void electrodes_weldingAndEcgOut_neutralIn() {
        assertThat(type(13, "Электрод сварочный", "", true)).isNull();
        assertThat(type(13, "Электрод нейтральный (возвратный) одноразовый", "", true)).isEqualTo("электрод");
        assertThat(type(13, "Электроды для ЭКГ одноразовые", "", true)).isNull();
    }

    @Test
    void dosersAndMonitors() {
        assertThat(type(24, "Дозатор лабораторный пипеточный", "", true)).isNull();
        assertThat(type(24, "Дозатор шприцевой", "", true)).isEqualTo("дозатор");
        assertThat(type(23, "Монитор компьютерный, диагональ 24", "", true)).isNull();
        assertThat(type(23, "Монитор пациента прикроватный", "", true)).isEqualTo("монитор");
    }

    @Test
    void announcementNameCountsOnlyForSingleLot() {
        assertThat(type(10, "Отсасыватель", "Аппарат для аспирации дыма", true)).isEqualTo("отсасыватель");
        assertThat(type(10, "Насадка", "Аппарат для аспирации дыма", true)).as("одно-лотовое: слово в объявлении").isEqualTo("аспирации");
        assertThat(type(10, "Насадка", "Аппарат для аспирации дыма и прочее", false)).as("много лотов").isNull();
    }

    @Test
    void caseYoAndLatinLookalikes() {   // Review Focus #2
        assertThat(type(5, "ОБЛУЧАТЕЛЬ бактерицидный", "", true)).isEqualTo("облучатель");
        assertThat(type(5, "Облучатeль бактерицидный", "", true)).as("латинская e").isEqualTo("облучатель");
        assertThat(type(2, "ЭЛЕКТРОКАРДИОГРАФ 12-канальный", "", true)).isEqualTo("электрокардиограф");
    }
}
```

`ProductMatcherTest`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сопоставление лота с нашей продукцией (спека §5): TYPE / NAMED / COPIED, похожие товары. */
class ProductMatcherTest {

    static CatalogSnapshot snap;
    static String astana;

    @BeforeAll
    static void load() {
        snap = CatalogSnapshot.build(ProductFixtures.groups(), ProductFixtures.catalog());
        astana = ProductFixtures.spec("17779323-1.pdf");
    }

    private static List<LotMatch> match(String announcement, LotInput... lots) {
        return ProductMatcher.match(snap, announcement, List.of(lots));
    }

    @Test
    void astana_copiedFromFotekAsd() {
        List<LotMatch> m = match("Аппарат для аспирации дыма",
                new LotInput(1, "Отсасыватель", "для аспирации жидкого содержимого", astana));
        LotMatch suction = m.stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(suction.level()).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(suction.evidence()).contains("блок аспирации").contains("держатель шланга аспирационного напольный");
        String product = snap.products().stream().filter(p -> p.id() == suction.productId()).findFirst().orElseThrow().name();
        assertThat(product).startsWith("Аппарат для аспирации дыма АСД");
        assertThat(suction.specUsed()).isTrue();
    }

    @Test
    void astanaWithoutSpec_typeOnly_withSimilar() {
        LotMatch m = match("Аппарат для аспирации дыма", new LotInput(1, "Отсасыватель", "для аспирации жидкого содержимого", null))
                .stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.word()).isEqualTo("отсасыватель");
        assertThat(m.similar()).extracting(SimilarProductRef::name).anyMatch(n -> n.startsWith("Аппарат для аспирации дыма"));
    }

    @Test
    void genericSuctionSpec_notCopied() {
        LotMatch m = match("Медицинские изделия", new LotInput(1, "Отсасыватель", "хирургический",
                "Отсасыватель медицинский электрический. Производительность не менее 20 л/мин. Банка 2 л. Шнур питания. "
                        + "Руководство по эксплуатации. Гарантия 12 месяцев."), new LotInput(2, "Бинт", "марлевый", null))
                .stream().filter(x -> x.groupId() == 10).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void brandInLot_namedByBrandGroup() {
        List<LotMatch> m = match("Приобретение запасных частей", new LotInput(1, "Комплектующие для аппарата Фотек",
                "согласно технической спецификации", null));
        assertThat(m).extracting(LotMatch::groupId, LotMatch::level).containsExactly(org.assertj.core.groups.Tuple.tuple(1L, ProductMatchLevel.NAMED));
        assertThat(m.get(0).evidence()).isEqualTo("в названии лота: фотек");
    }

    @Test
    void modelCodeInSpec_named() {
        LotMatch m = match("Медицинская техника", new LotInput(1, "Электрокардиограф", "12-канальный",
                "Электрокардиограф ЭК12Т-01 или эквивалент, 12 отведений")).stream().filter(x -> x.groupId() == 2).findFirst().orElseThrow();
        assertThat(m.level()).isEqualTo(ProductMatchLevel.NAMED);
        assertThat(m.evidence()).isEqualTo("в ТЗ: эк12т-01");
        assertThat(m.productId()).isNotNull();
    }

    @Test
    void orionOutsideItsGroup_notNamed() {
        assertThat(match("Реагенты", new LotInput(1, "Набор реагентов Orion Diagnostica", "орион", null))).isEmpty();
    }
}
```

- [ ] **Step 3: Run to verify they fail**

Run: `./gradlew test --tests '*ProductTextTest*' --tests '*BrandModelsTest*' --tests '*GroupRuleTest*' --tests '*ProductMatcherTest*'`
Expected: FAIL компиляции — нет классов правил.

- [ ] **Step 4: Implement `ProductText`**

```java
package com.vladoose.nir.service.productwatch;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Нормализация текста для правил «нашей продукции» (спека 2026-10-19-product-watch §5.1). */
public final class ProductText {

    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}]+");
    private static final Map<Character, Character> LOOKALIKE = Map.ofEntries(
            Map.entry('a', 'а'), Map.entry('c', 'с'), Map.entry('e', 'е'), Map.entry('o', 'о'), Map.entry('p', 'р'),
            Map.entry('x', 'х'), Map.entry('y', 'у'), Map.entry('k', 'к'), Map.entry('m', 'м'), Map.entry('t', 'т'),
            Map.entry('h', 'н'), Map.entry('b', 'в'));
    /** Служебные слова фраз и ключевых слов (спека §5.4). */
    static final Set<String> STOP = Set.of("для", "или", "при", "под", "над", "без", "более", "менее", "шт", "мм", "см", "кг",
            "мл", "вт", "что", "как", "это", "его", "все", "всех", "также", "тип", "типа", "and", "the", "for", "with");

    private ProductText() {}

    /** Нижний регистр, «ё» → «е», латинские двойники внутри слов с кириллицей → кириллица; разделители — как были. */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) return "";
        String low = s.toLowerCase(Locale.ROOT).replace('ё', 'е');
        Matcher m = TOKEN.matcher(low);
        StringBuilder out = new StringBuilder(low.length());
        int last = 0;
        while (m.find()) {
            out.append(low, last, m.start());
            String tok = m.group();
            out.append(hasCyrillic(tok) ? delatin(tok) : tok);
            last = m.end();
        }
        return out.append(low.substring(last)).toString();
    }

    /** Слова (буквы и цифры подряд) уже нормализованного текста. */
    public static List<String> words(String normalized) {
        List<String> out = new ArrayList<>();
        Matcher m = TOKEN.matcher(normalized == null ? "" : normalized);
        while (m.find()) out.add(m.group());
        return out;
    }

    /** Начала слов произвольного текста (нормализация + {@link #prefix}). */
    public static List<String> prefixes(String text) {
        return words(normalize(text)).stream().map(ProductText::prefix).toList();
    }

    /** Значимое слово: не короче 3 символов, не служебное, не одни цифры. */
    public static boolean significant(String w) {
        return w.length() >= 3 && !STOP.contains(w) && !w.chars().allMatch(Character::isDigit);
    }

    /** Начало слова для сравнения с учётом окончаний: первые 5 символов (короче — целиком). */
    public static String prefix(String w) {
        return w.length() <= 5 ? w : w.substring(0, 5);
    }

    /** Локальный стем слова поиска площадки: без конечных гласных, «ь», «й»; не короче 5 символов. */
    public static String searchStem(String word) {
        String n = normalize(word).strip();
        String s = n.replaceAll("[аеиоуыэюяьй]+$", "");
        return s.length() >= 5 ? s : n;
    }

    static boolean hasCyrillic(String t) {
        for (int i = 0; i < t.length(); i++) if (Character.UnicodeBlock.of(t.charAt(i)) == Character.UnicodeBlock.CYRILLIC) return true;
        return false;
    }

    static int letters(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isLetter(t.charAt(i))) n++;
        return n;
    }

    static int digits(String t) {
        int n = 0;
        for (int i = 0; i < t.length(); i++) if (Character.isDigit(t.charAt(i))) n++;
        return n;
    }

    private static String delatin(String tok) {
        StringBuilder b = new StringBuilder(tok.length());
        for (int i = 0; i < tok.length(); i++) b.append(LOOKALIKE.getOrDefault(tok.charAt(i), tok.charAt(i)));
        return b.toString();
    }
}
```

- [ ] **Step 5: Implement `BrandModels`**

```java
package com.vladoose.nir.service.productwatch;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Бренды и коды моделей (спека §5.3): разделители («-», пробел, точка, кавычки) и переходы буква↔цифра не значимы —
 * «ЭХВЧ 350» = «ЭХВЧ-350-03»; у кода с цифрами и у короткого слова (≤ 3 букв) — граница слова в конце («СК-16» не
 * совпадает с «СК-160», «обн» — с «обновлением»), длинное слово — стем («аксион» ловит «Аксиона»).
 */
public final class BrandModels {

    public record Code(String label, Pattern pattern, Long productId) {}

    public record Hit(String label, Long productId) {}

    private static final String SEP = "[\\s\\-./«»\"“”'–—]*";
    private static final Pattern PART = Pattern.compile("\\p{L}+|\\p{N}+");
    /** Бренды без двусмысленности — общий список у любого лота («ЭМА», «Орион», «Монитор» — только в своей группе). */
    public static final List<String> GLOBAL_BRANDS = List.of("фотек", "fotek", "аксион", "axion", "эмалед", "нпп монитор");

    private BrandModels() {}

    public static Pattern compile(String word) {
        String n = ProductText.normalize(word).strip();
        List<String> parts = new ArrayList<>();
        Matcher m = PART.matcher(n);
        while (m.find()) parts.add(m.group());
        StringBuilder q = new StringBuilder("(?<![\\p{L}\\p{N}])");
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) q.append(SEP);
            q.append(Pattern.quote(parts.get(i)));
        }
        if (ProductText.digits(n) > 0 || ProductText.letters(n) <= 3) q.append("(?![\\p{L}\\p{N}])");
        return Pattern.compile(q.toString(), Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** Коды моделей из названия товара: слово с ≥ 2 буквами и ≥ 1 цифрой («ДКИ-Н-11», «ЭХВЧ-350-03»). */
    public static List<String> modelCodes(String productName) {
        List<String> out = new ArrayList<>();
        if (productName == null) return out;
        for (String raw : productName.split("[\\s«»\"“”()\\[\\],;]+")) {
            String t = raw.replaceAll("^[\\-–—./]+|[\\-–—./]+$", "");
            if (!t.isEmpty() && ProductText.letters(t) >= 2 && ProductText.digits(t) >= 1) out.add(t);
        }
        return out;
    }

    /** Код — в общий список (у любого лота): ≥ 2 цифр и не короче 5 знаков без разделителей. */
    public static boolean globalCode(String code) {
        String squashed = ProductText.normalize(code).replaceAll("[^\\p{L}\\p{N}]", "");
        return ProductText.digits(squashed) >= 2 && squashed.length() >= 5;
    }

    /** Первое совпадение среди кодов: метка — как в тексте. */
    public static Hit first(List<Code> codes, String normalizedText) {
        if (normalizedText == null || normalizedText.isEmpty()) return null;
        for (Code c : codes) {
            Matcher m = c.pattern().matcher(normalizedText);
            if (m.find()) return new Hit(m.group(), c.productId());
        }
        return null;
    }
}
```

- [ ] **Step 6: Implement `GroupRule`, `CatalogProduct`, `LotInput`, `LotMatch`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.util.ArrayList;
import java.util.List;

/**
 * Скомпилированная группа (спека §5.2). Слово поиска — стемом ({@link ProductText#searchStem}) в тексте лота; у
 * объявления с одним лотом — ещё в его названии. Уточнения — в тексте лота (+ название объявления, если лот один);
 * исключения — только в тексте лота: общее название «Закуп медицинских изделий» не должно ни пропускать, ни резать
 * отдельные лоты многолотового объявления.
 */
public record GroupRule(long id, String name, boolean brandGroup, List<String> searchWords, List<String> stems,
                        List<String> require, List<String> exclude, List<BrandModels.Code> codes) {

    public static GroupRule of(ProductGroup g) {
        List<String> words = g.getSearchWords() == null ? List.of() : List.copyOf(g.getSearchWords());
        List<String> stems = words.stream().map(ProductText::searchStem).toList();
        List<BrandModels.Code> codes = new ArrayList<>();
        List<String> brandWords = g.isBrandGroup() ? words : (g.getBrandWords() == null ? List.of() : g.getBrandWords());
        for (String w : brandWords) codes.add(new BrandModels.Code(w, BrandModels.compile(w), null));
        return new GroupRule(g.getId(), g.getName(), g.isBrandGroup(), words, stems,
                norm(g.getRequireWords()), norm(g.getExcludeWords()), List.copyOf(codes));
    }

    /** Слово поиска, по которому лот прошёл группу, или null. Тексты — уже нормализованы. */
    public String typeWord(String lotText, String announcement, boolean singleLot) {
        String hit = firstStem(lotText);
        if (hit == null && singleLot) hit = firstStem(announcement);
        if (hit == null) return null;
        String scope = singleLot ? lotText + " | " + announcement : lotText;
        if (!require.isEmpty() && !MedicalGoodsVocabulary.matchesAny(scope, require)) return null;
        if (!exclude.isEmpty() && MedicalGoodsVocabulary.matchesAny(lotText, exclude)) return null;
        return hit;
    }

    private String firstStem(String text) {
        if (text == null || text.isEmpty()) return null;
        for (int i = 0; i < stems.size(); i++) {
            if (MedicalGoodsVocabulary.matchesAny(text, List.of(stems.get(i)))) return searchWords.get(i);
        }
        return null;
    }

    private static List<String> norm(List<String> xs) {
        return xs == null ? List.of() : xs.stream().map(ProductText::normalize).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }
}
```

```java
package com.vladoose.nir.service.productwatch;

/** Товар каталога в снимке правил (без JPA). */
public record CatalogProduct(long id, String name, String brand, String slug, String category, String shortDescription,
                             String description) {}
```

```java
package com.vladoose.nir.service.productwatch;

/** Лот для правил: текст площадки и разобранное ТЗ (null — ТЗ ещё нет). */
public record LotInput(long lotId, String name, String description, String spec) {}
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;

import java.util.List;

/** Отметка лота по группе (результат правил, до записи). */
public record LotMatch(long lotId, long groupId, String word, ProductMatchLevel level, String evidence, Long productId,
                       List<SimilarProductRef> similar, boolean specUsed) {}
```

- [ ] **Step 7: Implement `CatalogPhrases` и список общих фраз**

`src/main/resources/product-watch/generic-phrases.txt` — фразы, которые есть в любом ТЗ своего вида изделия и поэтому «списанностью» не считаются (по фразе на строку, `#` — комментарий; задача 6 дополняет список по набору настоящих ТЗ):

```
# Общие фразы ТЗ — не засчитываются как «ТЗ списано с нашего товара» (спека 2026-10-19-product-watch §5.4).
# Дополняется калибровкой (задача 6 плана): фраза каталога, найденная в ТЗ другого производителя, — общая.
шнур питания
сетевой кабель
кабель питания
блок питания
руководство по эксплуатации
паспорт изделия
гарантийный талон
инструкция по применению
сумка для переноски
комплект поставки
техническое обслуживание
индивидуальная упаковка
блок управления
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Collectors;

/**
 * «ТЗ списано с нашего товара» (спека §5.4). Отрезки названий и описаний товаров (между «.», «,», «;», скобками,
 * « – ») → окна по 2–4 значимых слова подряд, в окне — хотя бы одно редкое для каталога слово, окно не из списка общих
 * фраз. Отрезок засчитывается один раз, если в ТЗ есть любое его окно: начала слов по порядку, между соседними — не
 * больше одного другого слова. Оценка семейства (товары одного бренда в группе) — число разных найденных отрезков.
 * Порог и способ подсчёта — калибровка на настоящих ТЗ (задача 6 плана).
 */
public final class CatalogPhrases {

    /** Не меньше стольких разных отрезков — «списано» (калибровка). */
    public static final int COPY_MIN = 3;
    /** Считать по семейству (товары одного бренда в группе), а не по одному товару (калибровка). */
    public static final boolean BY_FAMILY = true;
    /** Слово «редкое», если встречается не больше чем у этой доли товаров каталога. */
    static final double RARE_SHARE = 0.05;
    /** Описание товара — первые столько символов (длинные описания без пользы раздувают окна). */
    static final int DESCRIPTION_MAX = 3000;

    /** Окно отрезка: товар, номер отрезка у товара, текст отрезка (для экрана), начала слов окна. */
    public record Phrase(long productId, int segment, String text, List<String> prefixes) {}

    public record Copy(long productId, int count, String evidence) {}

    static final Set<String> GENERIC = loadGeneric();

    private CatalogPhrases() {}

    public static Map<Long, List<Phrase>> build(List<CatalogProduct> products) {
        Map<Long, List<List<String>>> segments = new HashMap<>();
        Map<String, Integer> df = new HashMap<>();
        for (CatalogProduct p : products) {
            List<List<String>> segs = new ArrayList<>();
            String description = p.description() == null ? null
                    : p.description().length() > DESCRIPTION_MAX ? p.description().substring(0, DESCRIPTION_MAX) : p.description();
            for (String src : new String[] { p.name(), description }) {
                if (src == null) continue;
                for (String seg : ProductText.normalize(src).split("[.,;:()\\[\\]\\n\\r•·|!?]|\\s[–—-]\\s")) {
                    List<String> sig = ProductText.words(seg).stream().filter(ProductText::significant).toList();
                    if (sig.size() >= 2) segs.add(sig);
                }
            }
            segments.put(p.id(), segs);
            Set<String> seen = new HashSet<>();
            for (List<String> s : segs) for (String w : s) if (seen.add(ProductText.prefix(w))) df.merge(ProductText.prefix(w), 1, Integer::sum);
        }
        int rareMax = Math.max(1, (int) Math.floor(products.size() * RARE_SHARE));
        Map<Long, List<Phrase>> out = new HashMap<>();
        for (CatalogProduct p : products) {
            List<Phrase> phrases = new ArrayList<>();
            List<List<String>> segs = segments.get(p.id());
            for (int si = 0; si < segs.size(); si++) {
                List<String> sig = segs.get(si);
                String text = String.join(" ", sig);
                Set<String> keys = new HashSet<>();
                for (int len = 2; len <= Math.min(4, sig.size()); len++) {
                    for (int i = 0; i + len <= sig.size(); i++) {
                        List<String> prefixes = sig.subList(i, i + len).stream().map(ProductText::prefix).toList();
                        String k = String.join(" ", prefixes);
                        if (GENERIC.contains(k) || !keys.add(k)) continue;
                        if (prefixes.stream().anyMatch(x -> df.getOrDefault(x, 0) <= rareMax)) phrases.add(new Phrase(p.id(), si, text, prefixes));
                    }
                }
            }
            out.put(p.id(), List.copyOf(phrases));
        }
        return out;
    }

    /** Позиции начал слов ТЗ — чтобы окно проверялось только от мест, где стоит его первое слово. */
    public static Map<String, List<Integer>> index(List<String> specPrefixes) {
        Map<String, List<Integer>> idx = new HashMap<>();
        for (int i = 0; i < specPrefixes.size(); i++) idx.computeIfAbsent(specPrefixes.get(i), k -> new ArrayList<>()).add(i);
        return idx;
    }

    /** Окно есть в ТЗ: начала слов по порядку, между соседними — не больше одного другого слова. */
    static boolean occurs(List<String> p, List<String> spec, Map<String, List<Integer>> idx) {
        outer:
        for (int start : idx.getOrDefault(p.get(0), List.of())) {
            int pos = start;
            for (int k = 1; k < p.size(); k++) {
                if (pos + 1 < spec.size() && spec.get(pos + 1).equals(p.get(k))) pos += 1;
                else if (pos + 2 < spec.size() && spec.get(pos + 2).equals(p.get(k))) pos += 2;
                else continue outer;
            }
            return true;
        }
        return false;
    }

    /**
     * Совпало ли ТЗ с товарами группы. Лучшее семейство — больше разных найденных отрезков; товар отметки — в этом
     * семействе больше отрезков, затем в названии есть стем группы, затем длиннее название (набор длиннее своих частей).
     * null — порог не набран.
     */
    public static Copy copied(List<CatalogProduct> groupProducts, Map<Long, List<Phrase>> phrases, List<String> specPrefixes,
                              List<String> groupStems) {
        Map<String, List<Integer>> idx = index(specPrefixes);
        Map<Long, LinkedHashSet<String>> found = new HashMap<>();   // товар → тексты найденных отрезков
        for (CatalogProduct p : groupProducts) {
            LinkedHashSet<String> segs = new LinkedHashSet<>();
            for (Phrase x : phrases.getOrDefault(p.id(), List.of())) {
                if (!segs.contains(x.text()) && occurs(x.prefixes(), specPrefixes, idx)) segs.add(x.text());
            }
            if (!segs.isEmpty()) found.put(p.id(), segs);
        }
        if (found.isEmpty()) return null;
        Map<String, List<CatalogProduct>> families = groupProducts.stream().filter(p -> found.containsKey(p.id()))
                .collect(Collectors.groupingBy(p -> p.brand() == null ? "" : p.brand().toLowerCase(Locale.ROOT)));
        Copy best = null;
        for (List<CatalogProduct> fam : families.values()) {
            LinkedHashSet<String> texts = new LinkedHashSet<>();
            fam.stream().sorted(Comparator.comparingInt((CatalogProduct p) -> -found.get(p.id()).size()))
                    .forEach(p -> texts.addAll(found.get(p.id())));
            CatalogProduct top = fam.stream().max(Comparator.comparingInt((CatalogProduct p) -> found.get(p.id()).size())
                    .thenComparing(p -> hasStem(p.name(), groupStems))
                    .thenComparingInt(p -> p.name().length())).orElseThrow();
            int count = BY_FAMILY ? texts.size() : found.get(top.id()).size();
            if (count < COPY_MIN) continue;
            if (best == null || count > best.count()) {
                String list = texts.stream().limit(4).collect(Collectors.joining("; "));
                best = new Copy(top.id(), count, "совпало " + count + " " + phrasesWord(count) + ": " + list);
            }
        }
        return best;
    }

    private static boolean hasStem(String name, List<String> stems) {
        String n = ProductText.normalize(name);
        return stems.stream().anyMatch(s -> MedicalGoodsVocabulary.matchesAny(n, List.of(s)));
    }

    static String phrasesWord(int n) {
        int d = n % 10, h = n % 100;
        if (d == 1 && h != 11) return "фраза";
        if (d >= 2 && d <= 4 && (h < 12 || h > 14)) return "фразы";
        return "фраз";
    }

    static String key(String text) {
        return ProductText.words(ProductText.normalize(text)).stream().filter(ProductText::significant)
                .map(ProductText::prefix).collect(Collectors.joining(" "));
    }

    private static Set<String> loadGeneric() {
        try (InputStream in = CatalogPhrases.class.getResourceAsStream("/product-watch/generic-phrases.txt")) {
            if (in == null) return Set.of();
            Set<String> out = new HashSet<>();
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            for (String line; (line = r.readLine()) != null; ) {
                String t = line.strip();
                if (!t.isEmpty() && !t.startsWith("#")) out.add(key(t));
            }
            return Set.copyOf(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("product-watch/generic-phrases.txt не читается", e);
        }
    }
}
```

- [ ] **Step 8: Implement `SimilarProducts`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.SimilarProductRef;

import java.util.*;

/**
 * Похожие наши товары (спека §5.5): ключевые слова товара — значимые слова начала названия (до первого кода, кавычки,
 * скобки или тире), пусто — краткого описания; оценка — доля веса ключевых слов, найденных в тексте лота (вес —
 * редкость слова в каталоге). Ниже {@link #MIN} не показывается, не больше {@link #LIMIT}.
 */
public final class SimilarProducts {

    static final double MIN = 0.35;
    static final int LIMIT = 3;

    private SimilarProducts() {}

    static List<String> keyWords(CatalogProduct p) {
        String head = ProductText.normalize(p.name()).split("[«\"“(]|\\s[–—-]\\s|\\.\\s")[0];
        List<String> out = new ArrayList<>();
        for (String w : ProductText.words(head)) {
            if (ProductText.digits(w) > 0) break;
            if (ProductText.significant(w)) out.add(ProductText.prefix(w));
        }
        if (out.isEmpty() && p.shortDescription() != null) {
            ProductText.words(ProductText.normalize(p.shortDescription())).stream().filter(ProductText::significant)
                    .limit(8).map(ProductText::prefix).forEach(out::add);
        }
        return out;
    }

    public static Map<String, Double> idf(List<CatalogProduct> products) {
        Map<String, Integer> df = new HashMap<>();
        for (CatalogProduct p : products) for (String k : new HashSet<>(keyWords(p))) df.merge(k, 1, Integer::sum);
        Map<String, Double> out = new HashMap<>();
        int n = Math.max(1, products.size());
        df.forEach((k, v) -> out.put(k, Math.log(1.0 + (double) n / v)));
        return out;
    }

    public static List<SimilarProductRef> top(List<CatalogProduct> candidates, Set<String> lotPrefixes, Map<String, Double> idf) {
        record Scored(CatalogProduct p, double score) {}
        List<Scored> scored = new ArrayList<>();
        for (CatalogProduct p : candidates) {
            List<String> key = keyWords(p);
            if (key.isEmpty()) continue;
            double num = 0, den = 0;
            for (String k : new LinkedHashSet<>(key)) {
                double w = idf.getOrDefault(k, 1.0);
                den += w;
                if (lotPrefixes.contains(k)) num += w;
            }
            double s = den == 0 ? 0 : num / den;
            if (s >= MIN) scored.add(new Scored(p, s));
        }
        scored.sort(Comparator.comparingDouble((Scored x) -> -x.score()).thenComparingInt(x -> x.p().name().length()));
        List<SimilarProductRef> out = new ArrayList<>();
        Set<String> brands = new HashSet<>();
        for (Scored x : scored) {                            // сначала разные бренды — подсказка, что предлагать
            if (out.size() == LIMIT) break;
            if (brands.add(String.valueOf(x.p().brand()))) out.add(ref(x.p(), x.score()));
        }
        for (Scored x : scored) {
            if (out.size() == LIMIT) break;
            if (out.stream().noneMatch(r -> r.productId() == x.p().id())) out.add(ref(x.p(), x.score()));
        }
        return out;
    }

    private static SimilarProductRef ref(CatalogProduct p, double s) {
        return new SimilarProductRef(p.id(), p.name(), p.brand(), p.slug(), Math.round(s * 1000) / 1000.0);
    }
}
```

- [ ] **Step 9: Implement `CatalogSnapshot`, `ProductMatcher`, `ProductCatalog`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.entity.SiteProduct;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;

import java.util.*;

/** Снимок групп и каталога для правил (спека §5): строится при загрузке каталога и сохранении групп. */
public final class CatalogSnapshot {

    private final List<GroupRule> groups;
    private final List<CatalogProduct> products;
    private final Map<Long, List<CatalogProduct>> groupProducts;
    private final List<BrandModels.Code> globalCodes;
    private final Map<Long, List<BrandModels.Code>> groupCodes;
    private final Map<Long, List<CatalogPhrases.Phrase>> phrases;
    private final Map<String, Double> idf;

    private CatalogSnapshot(List<GroupRule> groups, List<CatalogProduct> products, Map<Long, List<CatalogProduct>> groupProducts,
                            List<BrandModels.Code> globalCodes, Map<Long, List<BrandModels.Code>> groupCodes,
                            Map<Long, List<CatalogPhrases.Phrase>> phrases, Map<String, Double> idf) {
        this.groups = groups; this.products = products; this.groupProducts = groupProducts; this.globalCodes = globalCodes;
        this.groupCodes = groupCodes; this.phrases = phrases; this.idf = idf;
    }

    public static CatalogSnapshot build(List<ProductGroup> groups, List<SiteProduct> siteProducts) {
        List<GroupRule> rules = groups.stream().filter(ProductGroup::isActive).map(GroupRule::of).toList();
        List<CatalogProduct> products = siteProducts.stream().filter(p -> p.getRemovedAt() == null)
                .map(p -> new CatalogProduct(p.getId(), p.getName(), p.getBrand(), p.getSlug(), p.getCategory(),
                        p.getShortDescription(), p.getDescription())).toList();
        List<BrandModels.Code> global = new ArrayList<>();
        for (String b : BrandModels.GLOBAL_BRANDS) global.add(new BrandModels.Code(b, BrandModels.compile(b), null));
        Map<Long, List<BrandModels.Code>> productCodes = new HashMap<>();
        for (CatalogProduct p : products) {
            List<BrandModels.Code> codes = new ArrayList<>();
            for (String c : BrandModels.modelCodes(p.name())) {
                BrandModels.Code code = new BrandModels.Code(c, BrandModels.compile(c), p.id());
                codes.add(code);
                if (BrandModels.globalCode(c)) global.add(code);
            }
            productCodes.put(p.id(), codes);
        }
        Map<Long, List<CatalogProduct>> byGroup = new HashMap<>();
        Map<Long, List<BrandModels.Code>> groupCodes = new HashMap<>();
        for (GroupRule r : rules) {
            List<CatalogProduct> mine = new ArrayList<>();
            for (CatalogProduct p : products) {
                String text = ProductText.normalize(String.join(" ", Objects.toString(p.name(), ""),
                        Objects.toString(p.shortDescription(), ""), Objects.toString(p.category(), "")));
                boolean byWord = MedicalGoodsVocabulary.matchesAny(text, r.stems());
                boolean byBrand = BrandModels.first(r.codes(), ProductText.normalize(p.name())) != null;
                if (r.brandGroup() || byWord || byBrand) mine.add(p);
            }
            byGroup.put(r.id(), List.copyOf(mine));
            // коды товаров группы — раньше слов группы: у кода есть товар («в ТЗ: эк12т-01» → ЭК12Т-01), у слова — нет
            List<BrandModels.Code> codes = new ArrayList<>();
            for (CatalogProduct p : mine) codes.addAll(productCodes.getOrDefault(p.id(), List.of()));
            codes.addAll(r.codes());
            groupCodes.put(r.id(), List.copyOf(codes));
        }
        return new CatalogSnapshot(rules, products, byGroup, List.copyOf(global), groupCodes,
                CatalogPhrases.build(products), SimilarProducts.idf(products));
    }

    public List<GroupRule> groups() { return groups; }
    public List<CatalogProduct> products() { return products; }
    public List<CatalogProduct> groupProducts(long groupId) { return groupProducts.getOrDefault(groupId, List.of()); }
    public List<BrandModels.Code> globalCodes() { return globalCodes; }
    public List<BrandModels.Code> groupCodes(long groupId) { return groupCodes.getOrDefault(groupId, List.of()); }
    public Map<Long, List<CatalogPhrases.Phrase>> phrases() { return phrases; }
    public Map<String, Double> idf() { return idf; }
}
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;

import java.util.*;

/**
 * Лоты объявления → отметки «наш товар» (спека §5). Чистая функция над снимком: без базы и сети.
 * <ul>
 *   <li>группа «Наши бренды и модели» — сразу NAMED, если её слово (или общий бренд / код модели) есть в названии
 *       лота (у одно-лотового объявления — и в названии объявления) или в ТЗ;</li>
 *   <li>остальные — TYPE по {@link GroupRule#typeWord}; бренд или модель группы / общий список → NAMED; фразы каталога
 *       в ТЗ → COPIED (ТЗ есть только у разобранных лотов).</li>
 * </ul>
 */
public final class ProductMatcher {

    private ProductMatcher() {}

    public static List<LotMatch> match(CatalogSnapshot s, String announcementName, List<LotInput> lots) {
        List<LotMatch> out = new ArrayList<>();
        String ann = ProductText.normalize(announcementName);
        boolean single = lots.size() == 1;
        for (LotInput lot : lots) {
            String lotText = ProductText.normalize(String.join(" ", Objects.toString(lot.name(), ""), Objects.toString(lot.description(), "")));
            String spec = lot.spec() == null || lot.spec().isBlank() ? null : ProductText.normalize(lot.spec());
            String nameScope = single ? lotText + " | " + ann : lotText;
            for (GroupRule r : s.groups()) {
                if (r.brandGroup()) {
                    List<BrandModels.Code> codes = new ArrayList<>(r.codes());
                    codes.addAll(s.globalCodes());
                    String[] ev = named(codes, nameScope, spec);
                    if (ev != null) {
                        BrandModels.Hit h = BrandModels.first(codes, ev[1].equals("name") ? nameScope : spec);
                        out.add(new LotMatch(lot.lotId(), r.id(), h.label(), ProductMatchLevel.NAMED, ev[0], h.productId(),
                                List.of(), spec != null));
                    }
                    continue;
                }
                String word = r.typeWord(lotText, ann, single);
                if (word == null) continue;
                ProductMatchLevel level = ProductMatchLevel.TYPE;
                String evidence = null;
                Long productId = null;
                List<BrandModels.Code> codes = new ArrayList<>(s.groupCodes(r.id()));
                codes.addAll(s.globalCodes());
                String[] ev = named(codes, nameScope, spec);
                if (ev != null) {
                    BrandModels.Hit h = BrandModels.first(codes, ev[1].equals("name") ? nameScope : spec);
                    level = ProductMatchLevel.NAMED;
                    evidence = ev[0];
                    productId = h.productId();
                }
                if (spec != null) {
                    CatalogPhrases.Copy c = CatalogPhrases.copied(s.groupProducts(r.id()), s.phrases(),
                            ProductText.words(spec).stream().map(ProductText::prefix).toList(), r.stems());
                    if (c != null) {
                        evidence = c.evidence() + (evidence == null ? "" : "; " + evidence);
                        level = ProductMatchLevel.COPIED;
                        productId = c.productId();
                    }
                }
                Set<String> lotPrefixes = new HashSet<>(ProductText.words(nameScope).stream().map(ProductText::prefix).toList());
                if (spec != null) ProductText.words(spec.length() > 3000 ? spec.substring(0, 3000) : spec)
                        .forEach(w -> lotPrefixes.add(ProductText.prefix(w)));
                List<SimilarProductRef> similar = SimilarProducts.top(s.groupProducts(r.id()), lotPrefixes, s.idf());
                out.add(new LotMatch(lot.lotId(), r.id(), word, level, evidence, productId, similar, spec != null));
            }
        }
        return out;
    }

    /** {текст для экрана, где: "name" / "spec"} первого бренда или кода, или null. */
    private static String[] named(List<BrandModels.Code> codes, String nameScope, String spec) {
        BrandModels.Hit h = BrandModels.first(codes, nameScope);
        if (h != null) return new String[] { "в названии лота: " + h.label(), "name" };
        if (spec != null) {
            h = BrandModels.first(codes, spec);
            if (h != null) return new String[] { "в ТЗ: " + h.label(), "spec" };
        }
        return null;
    }
}
```

`ProductCatalog` (замена заготовки):

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Снимок групп и каталога в памяти (рынок KZ — блок только для Казахстана). Обновляется после загрузки каталога и
 * сохранения групп; первый вызов строит лениво. Чтение репозиториев — своей короткой транзакцией (фоновые потоки).
 */
@Component
public class ProductCatalog {

    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final TransactionTemplate readTx;
    private volatile CatalogSnapshot snapshot;

    public ProductCatalog(ProductGroupRepository groups, SiteProductRepository products, PlatformTransactionManager tm) {
        this.groups = groups;
        this.products = products;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
    }

    public CatalogSnapshot get() {
        CatalogSnapshot s = snapshot;
        if (s == null) {
            refresh();
            s = snapshot;
        }
        return s;
    }

    public synchronized void refresh() {
        snapshot = readTx.execute(st -> CatalogSnapshot.build(
                groups.findByMarketAndActiveTrueOrderByPositionAscIdAsc(Market.KZ),
                products.findByMarketAndRemovedAtIsNull(Market.KZ)));
    }

    /** Сбросить снимок — следующий {@link #get()} построит заново (тесты: снимок не должен пережить откат транзакции). */
    public void invalidate() {
        snapshot = null;
    }
}
```

`SiteCatalogSyncTest` создаёт `SiteCatalogSync` с настоящим бином `ProductCatalog` — после этой задачи вызов `refresh()` реальный.

- [ ] **Step 10: Run tests**

Run: `./gradlew test --tests '*ProductTextTest*' --tests '*BrandModelsTest*' --tests '*GroupRuleTest*' --tests '*ProductMatcherTest*' --tests '*SiteCatalogSyncTest*'`
Expected: PASS. Если `astana_copiedFromFotekAsd` падает на числе фраз — распечатать (временно, `System.out`) найденные фразы ТЗ и каталожные фразы группы 10 и разобраться, какая не совпала (окно, префикс, общая фраза); правило менять осознанно, решение — в леджер `.superpowers/sdd/2026-10-19-product-watch/progress.md`.

- [ ] **Step 11: Mutation check**

По одной, откат копией:
1. `GroupRule.typeWord`: убрать проверку исключений → `lamps_requireAndExclude`, `electrodes_…` красные.
2. `typeWord`: название объявления учитывать и у многолотовых (`if (hit == null) hit = firstStem(announcement)`) → `announcementNameCountsOnlyForSingleLot` красный.
3. `ProductText.normalize`: без `delatin` → `caseYoAndLatinLookalikes` красный.
4. `BrandModels.compile`: без конечной границы у кода с цифрами → `codesIgnoreSeparatorsAndExecution` («СК-160») красный.
5. `CatalogPhrases.copied`: `BY_FAMILY = false` → `astana_copiedFromFotekAsd` красный (если по товару — 1 фраза на товар; если зелёный — записать в леджер: описания каталога уже несут состав, и это вход калибровки задачи 6).
6. `CatalogSnapshot.build`: общие бренды с «орион» (`GLOBAL_BRANDS` + «орион») → `orionOutsideItsGroup_notNamed` красный.
Затем `./gradlew compileJava`.

- [ ] **Step 12: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/productwatch/ src/test/java/com/vladoose/nir/productwatch/ src/test/resources/product-watch/
git commit -m "feat(product-watch): правила — группа, бренд и модель, фразы ТЗ из каталога, похожие товары; снимок каталога

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 4: Поиск по стране — клиент v3, заведение тендеров, отметки

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupSearchClient.java`, `dto/FoundAnnouncement.java`, `dto/FoundLot.java`, `dto/FoundPlanPoint.java`, `dto/ContractInfo.java`, `dto/SearchPage.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupHttpClient.java` (реализует `GoszakupSearchClient`), `GoszakupCallException.java` (код ответа), `GoszakupTenderWriter.java` (`upsertFound`, `updateStatus`, `foundBySearch`)
- Create: `src/main/java/com/vladoose/nir/service/productwatch/CatalogSource.java`, `ProductMatchService.java`, `ProductWatchService.java`
- Modify: `ProductCatalog.java` (`implements CatalogSource`), `repository/TenderRepository.java` (`findByFoundByProductSearchTrueAndStatus`)
- Test: `src/test/java/com/vladoose/nir/integration/goszakup/GoszakupSearchClientTest.java`, `src/test/java/com/vladoose/nir/productwatch/FakeGoszakupSearchClient.java`, `ProductWatchServiceTest.java`, `ProductMatchServiceTest.java`

**Interfaces:**
- Consumes: `ProductMatcher`, `CatalogSnapshot`, `LotInput`, `LotMatch` (задача 3); `TenderLotProductMatch` и репозиторий (задача 1); `GoszakupClient.fetchLots/fetchSubject` (есть); `UpstreamRetry` (есть).
- Produces:
  - `GoszakupCallException(String message, int status, boolean retryable, Throwable cause)`, `int status()` (0 — не HTTP-код);
  - `record SearchPage<T>(List<T> items, Long nextAfter)`;
  - `record FoundAnnouncement(Long id, String numberAnno, String nameRu, String orgBin, String orgNameRu, BigDecimal totalSum, String publishDate, String endDate, Integer refBuyStatusId, Integer refTradeMethodsId, String tradeMethodName, Integer systemId)` + `TrdBuyDto toTrdBuy()`;
  - `record FoundLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount, Integer refLotStatusId, String lastUpdateDate, String customerBin, String customerNameRu, String trdBuyNumberAnno, FoundAnnouncement announcement)`;
  - `record FoundPlanPoint(Long id, Integer year, Integer month, String monthName, String subjectBiin, String subjectNameRu, String nameRu, String descRu, String extraDescRu, BigDecimal count, String unit, BigDecimal price, BigDecimal amount, String tradeMethod, Integer statusId, String statusName, String deliveryPlace)`;
  - `record ContractInfo(String supplierBiin, BigDecimal contractSum, BigDecimal contractSumWnds, String signDate, Integer statusId)`;
  - `GoszakupSearchClient { boolean isConfigured(); SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate updatedFrom, LocalDate updatedTo, Long after); SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate publishedFrom, LocalDate publishedTo, Long after); FoundAnnouncement announcement(String numberAnno); List<FoundLot> lotsOfAnnouncement(String numberAnno); SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after); List<FoundPlanPoint> plansById(List<Long> ids); List<ContractInfo> contracts(String numberAnno); }` (`null` в списке статусов / дат — фильтр не ставится);
  - `GoszakupTenderWriter.upsertFound(TrdBuyDto, SubjectDto, List<LotDto>, String tradeMethod) → Result`, `updateStatus(String numberAnno, Integer refBuyStatusId, String endDate) → boolean`, `Boolean foundBySearch(String numberAnno)` (`null` — тендера нет);
  - `interface CatalogSource { CatalogSnapshot get(); }`;
  - `ProductMatchService.evaluate(long tenderId, ProductMatchSource) → Result(long tenderId, ProductMatchLevel level, int lots, boolean hadMarks)` (`level` — `null`, если отметок нет), `markSpecChecked(long lotId)`, `markNotified(long tenderId, ProductMatchLevel level)`;
  - `ProductWatchService.run(ImportSummary sum)`; константы `OPEN_LOT = [190, 210, 220, 230, 240]`, `OPEN_BUY = [210, 220, 230, 240, 245]`, `WINDOW_DAYS = 60`, `MAX_PAGES = 5`, `PAGE = 200`, `PAUSE_MS = 300`; `static boolean stopping(RuntimeException)` — 401 / 403 / 429.

- [ ] **Step 1: Write the failing client test**

`GoszakupSearchClientTest` — стаб `HttpServer` на `/v3/graphql`: запоминает тело запроса, отвечает заготовкой по признаку в теле (`nameDescriptionRu` / `commonName` / `numberAnno` / `Plans` / `Contract`), на тело с `"forbidden"` — 403.

```java
package com.vladoose.nir.integration.goszakup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.vladoose.nir.integration.goszakup.dto.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Поиск по стране на v3 (спека 2026-10-19-product-watch §3, §7.2): формы запросов и ответов, сняты живьём 2026-10-19. */
class GoszakupSearchClientTest {

    HttpServer server;
    final List<String> bodies = new ArrayList<>();

    static final String LOTS = """
        {"data":{"Lots":[{"id":43584637,"lotNumber":"88111123-ОИ3","nameRu":"Отсасыватель","descriptionRu":"для аспирации жидкого содержимого",
          "count":1,"amount":3714285.71,"refLotStatusId":220,"lastUpdateDate":"2026-10-16 19:32:08","customerBin":"080740007819",
          "customerNameRu":"ГКП на ПХВ \\"Городская многопрофильная больница № 2\\" акимата города Астана","trdBuyNumberAnno":"17802320-1",
          "TrdBuy":{"id":17802320,"numberAnno":"17802320-1","nameRu":"Аппарат для аспирации дыма","orgBin":"080740007819",
            "orgNameRu":"ГКП на ПХВ \\"Городская многопрофильная больница № 2\\" акимата города Астана","totalSum":3714285.71,
            "publishDate":"2026-10-16 19:32:08","endDate":"2026-10-20 09:00:08","refBuyStatusId":220,"refTradeMethodsId":6,"systemId":3,
            "RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}}]},
         "extensions":{"pageInfo":{"hasNextPage":true,"lastId":43584637}}}""";
    static final String BUYS = """
        {"data":{"TrdBuy":[{"id":17802320,"numberAnno":"17802320-1","nameRu":"Аппарат для аспирации дыма","orgBin":"080740007819",
          "totalSum":3714285.71,"publishDate":"2026-10-16 19:32:08","endDate":"2026-10-20 09:00:08","refBuyStatusId":220,
          "refTradeMethodsId":6,"systemId":3,"RefTradeMethods":{"nameRu":"Из одного источника по несостоявшимся закупкам"}}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":17802320}}}""";
    static final String PLANS = """
        {"data":{"Plans":[{"id":88053701,"plnPointYear":2026,"refMonthsId":9,"subjectBiin":"990240006109",
          "subjectNameRu":"ГКП \\"Айтекебийская районная больница\\"","nameRu":"Электрокардиограф","descRu":"3-6 канальный",
          "extraDescRu":"встроенный термопринтер","count":4,"price":1480000,"amount":5920000,"refPlnPointStatusId":2,
          "RefUnits":{"nameRu":"Штука"},"RefTradeMethods":{"nameRu":"Электронный магазин"},"RefMonths":{"nameRu":"Сентябрь"},
          "RefPlnPointStatus":{"nameRu":"Утвержден"},
          "PlansKato":[{"fullDeliveryPlaceNameRu":"Актюбинская область, Айтекебийский район, с.Темирбека Жургенева"}]}]},
         "extensions":{"pageInfo":{"hasNextPage":false,"lastId":88053701}}}""";
    static final String CONTRACTS = """
        {"data":{"Contract":[{"supplierBiin":"123456789012","contractSum":3650000,"contractSumWnds":3832500,"signDate":"2026-09-12 10:00:00","refContractStatusId":250}]}}""";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v3/graphql", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            bodies.add(body);
            String reply = body.contains("forbidden") ? null
                    : body.contains("Contract(") ? CONTRACTS
                    : body.contains("Plans(") ? PLANS
                    : body.contains("Lots(") ? LOTS : BUYS;
            int code = reply == null ? 403 : 200;
            byte[] b = (reply == null ? "{}" : reply).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(code, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach void stop() { server.stop(0); }

    private GoszakupSearchClient client() {
        return new GoszakupHttpClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort() + "/v2", "tok", 50);
    }

    @Test
    void searchLots_filtersAndNestedAnnouncement() {
        SearchPage<FoundLot> p = client().searchLots("отсасыватель", List.of(190, 210, 220, 230, 240),
                LocalDate.of(2026, 8, 21), LocalDate.of(2026, 10, 21), null);
        String body = bodies.get(0);
        assertThat(body).contains("nameDescriptionRu").contains("отсасыватель").contains("refLotStatusId").contains("lastUpdateDate")
                .contains("2026-08-21").contains("2026-10-21");
        assertThat(p.nextAfter()).isEqualTo(43584637L);
        FoundLot l = p.items().get(0);
        assertThat(l.nameRu()).isEqualTo("Отсасыватель");
        assertThat(l.announcement().nameRu()).isEqualTo("Аппарат для аспирации дыма");
        assertThat(l.announcement().tradeMethodName()).isEqualTo("Из одного источника по несостоявшимся закупкам");
        assertThat(l.announcement().toTrdBuy().getEndDate()).isEqualTo("2026-10-20 09:00:08");
    }

    @Test
    void searchAnnouncements_commonNameNotNameRu() {
        SearchPage<FoundAnnouncement> p = client().searchAnnouncements("аспирации", List.of(210, 220), null, null, null);
        assertThat(bodies.get(0)).contains("commonName").doesNotContain("\"nameRu\":\"аспирации\"").doesNotContain("publishDate:$");
        assertThat(p.items()).extracting(FoundAnnouncement::numberAnno).containsExactly("17802320-1");
        assertThat(p.nextAfter()).isNull();
    }

    @Test
    void plansAndContracts() {
        FoundPlanPoint pp = client().searchPlans("электрокардиограф", List.of(2026, 2027), List.of(2), null).items().get(0);
        assertThat(pp.month()).isEqualTo(9);
        assertThat(pp.unit()).isEqualTo("Штука");
        assertThat(pp.deliveryPlace()).startsWith("Актюбинская область");
        assertThat(pp.statusName()).isEqualTo("Утвержден");
        assertThat(client().contracts("17578819-1")).extracting(ContractInfo::supplierBiin).containsExactly("123456789012");
    }

    @Test
    void forbidden_statusInException() {
        assertThatThrownBy(() -> client().searchLots("forbidden", null, null, null, null))
                .isInstanceOf(GoszakupCallException.class)
                .satisfies(e -> assertThat(((GoszakupCallException) e).status()).isEqualTo(403));
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*GoszakupSearchClientTest*'`
Expected: FAIL компиляции — нет `GoszakupSearchClient`, `FoundLot`, `status()`.

- [ ] **Step 3: Implement DTOs, exception status and client**

`GoszakupCallException` — второй конструктор и поле:

```java
    private final int status;

    public GoszakupCallException(String message, boolean retryable, Throwable cause) {
        this(message, 0, retryable, cause);
    }

    /** status — HTTP-код ответа площадки (0 — не HTTP: обрыв, разбор, неполный ответ). */
    public GoszakupCallException(String message, int status, boolean retryable, Throwable cause) {
        super(message, cause);
        this.retryable = retryable;
        this.status = status;
    }

    public int status() { return status; }
```

В `GoszakupHttpClient.raw(...)` — код в исключение: `throw new GoszakupCallException("goszakup API " + code + " на " + pathOf(url), code, UpstreamRetry.retryableStatus(code), null);`.

DTO (`integration/goszakup/dto`):

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.util.List;

/** Страница v3: элементы и курсор следующей страницы (null — последняя). */
public record SearchPage<T>(List<T> items, Long nextAfter) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Объявление из v3-поиска (спека 2026-10-19-product-watch §7.2). */
public record FoundAnnouncement(Long id, String numberAnno, String nameRu, String orgBin, String orgNameRu, BigDecimal totalSum,
                                String publishDate, String endDate, Integer refBuyStatusId, Integer refTradeMethodsId,
                                String tradeMethodName, Integer systemId) {

    /** Для прежнего писателя импорта ({@link com.vladoose.nir.integration.goszakup.GoszakupTenderWriter}). */
    public TrdBuyDto toTrdBuy() {
        TrdBuyDto d = new TrdBuyDto();
        d.setId(id);
        d.setNumberAnno(numberAnno);
        d.setNameRu(nameRu);
        d.setOrgBin(orgBin);
        d.setTotalSum(totalSum);
        d.setPublishDate(publishDate);
        d.setEndDate(endDate);
        d.setRefBuyStatusId(refBuyStatusId);
        d.setSystemId(systemId);
        return d;
    }
}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Лот из v3-поиска с вложенным объявлением. */
public record FoundLot(Long id, String lotNumber, String nameRu, String descriptionRu, BigDecimal count, BigDecimal amount,
                       Integer refLotStatusId, String lastUpdateDate, String customerBin, String customerNameRu,
                       String trdBuyNumberAnno, FoundAnnouncement announcement) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Пункт плана закупок из v3 (спека §10). */
public record FoundPlanPoint(Long id, Integer year, Integer month, String monthName, String subjectBiin, String subjectNameRu,
                             String nameRu, String descRu, String extraDescRu, BigDecimal count, String unit, BigDecimal price,
                             BigDecimal amount, String tradeMethod, Integer statusId, String statusName, String deliveryPlace) {}
```

```java
package com.vladoose.nir.integration.goszakup.dto;

import java.math.BigDecimal;

/** Договор объявления (итоги, спека §11.2). */
public record ContractInfo(String supplierBiin, BigDecimal contractSum, BigDecimal contractSumWnds, String signDate, Integer statusId) {}
```

```java
package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.List;

/**
 * Поиск по стране на v3 GraphQL goszakup (спека 2026-10-19-product-watch §3): лоты по одному слову, объявления по слову
 * в названии (commonName — nameRu ищет только точное название), объявление по номеру, планы, договоры. null в фильтре —
 * фильтр не ставится. Реализует {@link GoszakupHttpClient} тем же токеном.
 */
public interface GoszakupSearchClient {
    boolean isConfigured();
    SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate updatedFrom, LocalDate updatedTo, Long after);
    SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate publishedFrom, LocalDate publishedTo, Long after);
    FoundAnnouncement announcement(String numberAnno);
    List<FoundLot> lotsOfAnnouncement(String numberAnno);
    SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after);
    List<FoundPlanPoint> plansById(List<Long> ids);
    List<ContractInfo> contracts(String numberAnno);
}
```

`GoszakupHttpClient` — `implements GoszakupClient, GoszakupSearchClient`; общий запрос v3 с фильтром из непустых полей и разбор:

```java
    private static final String BUY_FIELDS = "id numberAnno nameRu orgBin orgNameRu totalSum publishDate endDate refBuyStatusId "
            + "refTradeMethodsId systemId RefTradeMethods{ nameRu }";
    private static final String LOT_FIELDS = "id lotNumber nameRu descriptionRu count amount refLotStatusId lastUpdateDate "
            + "customerBin customerNameRu trdBuyNumberAnno TrdBuy{ " + BUY_FIELDS + " }";
    private static final String PLAN_FIELDS = "id plnPointYear refMonthsId subjectBiin subjectNameRu nameRu descRu extraDescRu count "
            + "price amount refPlnPointStatusId RefUnits{ nameRu } RefTradeMethods{ nameRu } RefMonths{ nameRu } "
            + "RefPlnPointStatus{ nameRu } PlansKato{ fullDeliveryPlaceNameRu }";
    private static final int SEARCH_PAGE = 200;

    /** Поле фильтра v3: имя, тип GraphQL и значение (null — поле не ставится). */
    private record F(String name, String type, Object value) {}

    /** Запрос `root(filter:{…}, limit, after){ fields }` → корневой узел ответа (data.root) и курсор. */
    private JsonNode v3(String root, String fields, List<F> filters, int limit, Long after, Long[] next) {
        StringBuilder decl = new StringBuilder("$l:Int,$a:Int");
        StringBuilder flt = new StringBuilder();
        ObjectNode vars = objectMapper.createObjectNode();
        int i = 0;
        for (F f : filters) {
            if (f.value() == null) continue;
            String v = "v" + (i++);
            decl.append(",$").append(v).append(':').append(f.type());
            if (flt.length() > 0) flt.append(',');
            flt.append(f.name()).append(":$").append(v);
            vars.set(v, objectMapper.valueToTree(f.value()));
        }
        vars.put("l", limit);
        if (after != null) vars.put("a", after);
        String query = "query(" + decl + "){ " + root + "(filter:{" + flt + "}, limit:$l, after:$a){ " + fields + " } }";
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("query", query);
            body.set("variables", vars);
            JsonNode r = objectMapper.readTree(rawPost(graphqlUrl(), objectMapper.writeValueAsBytes(body)));
            if (r.path("errors").size() > 0) throw new GoszakupCallException("goszakup v3 GraphQL: " + r.get("errors"), false, null);
            JsonNode items = r.path("data").path(root);
            JsonNode pi = r.path("extensions").path("pageInfo");
            if (next != null) next[0] = items.size() > 0 && pi.path("hasNextPage").asBoolean(false) ? pi.path("lastId").asLong() : null;
            return items;
        } catch (java.io.IOException e) {
            throw new GoszakupCallException("goszakup v3: разбор JSON: " + e.getMessage(), false, e);
        }
    }

    private static String dates(LocalDate from, LocalDate to) { return null; }   // не используется — даты передаются списком

    private static List<String> range(LocalDate from, LocalDate to) {
        return from == null || to == null ? null : List.of(from.toString(), to.toString());
    }

    private static String text(JsonNode n, String f) { JsonNode x = n.path(f); return x.isMissingNode() || x.isNull() ? null : x.asText(); }
    private static Integer integer(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.asInt() : null; }
    private static Long longer(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.asLong() : null; }
    private static java.math.BigDecimal dec(JsonNode n, String f) { JsonNode x = n.path(f); return x.isNumber() ? x.decimalValue() : null; }

    private static FoundAnnouncement announcementOf(JsonNode b) {
        if (b == null || b.isMissingNode() || b.isNull()) return null;
        return new FoundAnnouncement(longer(b, "id"), text(b, "numberAnno"), text(b, "nameRu"), text(b, "orgBin"),
                text(b, "orgNameRu"), dec(b, "totalSum"), text(b, "publishDate"), text(b, "endDate"),
                integer(b, "refBuyStatusId"), integer(b, "refTradeMethodsId"), text(b.path("RefTradeMethods"), "nameRu"),
                integer(b, "systemId"));
    }

    private static FoundLot lotOf(JsonNode l) {
        return new FoundLot(longer(l, "id"), text(l, "lotNumber"), text(l, "nameRu"), text(l, "descriptionRu"), dec(l, "count"),
                dec(l, "amount"), integer(l, "refLotStatusId"), text(l, "lastUpdateDate"), text(l, "customerBin"),
                text(l, "customerNameRu"), text(l, "trdBuyNumberAnno"), announcementOf(l.path("TrdBuy")));
    }

    private static FoundPlanPoint planOf(JsonNode p) {
        JsonNode kato = p.path("PlansKato");
        String place = kato.isArray() && kato.size() > 0 ? text(kato.get(0), "fullDeliveryPlaceNameRu") : null;
        return new FoundPlanPoint(longer(p, "id"), integer(p, "plnPointYear"), integer(p, "refMonthsId"),
                text(p.path("RefMonths"), "nameRu"), text(p, "subjectBiin"), text(p, "subjectNameRu"), text(p, "nameRu"),
                text(p, "descRu"), text(p, "extraDescRu"), dec(p, "count"), text(p.path("RefUnits"), "nameRu"), dec(p, "price"),
                dec(p, "amount"), text(p.path("RefTradeMethods"), "nameRu"), integer(p, "refPlnPointStatusId"),
                text(p.path("RefPlnPointStatus"), "nameRu"), place);
    }

    private static <T> List<T> map(JsonNode items, java.util.function.Function<JsonNode, T> f) {
        List<T> out = new ArrayList<>();
        for (JsonNode n : items) out.add(f.apply(n));
        return out;
    }

    @Override
    public SearchPage<FoundLot> searchLots(String word, List<Integer> lotStatuses, LocalDate from, LocalDate to, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("Lots", LOT_FIELDS, List.of(new F("nameDescriptionRu", "String", word),
                new F("refLotStatusId", "[Int]", lotStatuses), new F("lastUpdateDate", "[String]", range(from, to))),
                SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::lotOf), next[0]);
    }

    @Override
    public SearchPage<FoundAnnouncement> searchAnnouncements(String word, List<Integer> buyStatuses, LocalDate from, LocalDate to, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("TrdBuy", BUY_FIELDS, List.of(new F("commonName", "String", word),
                new F("refBuyStatusId", "[Int]", buyStatuses), new F("publishDate", "[String]", range(from, to))),
                SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::announcementOf), next[0]);
    }

    @Override
    public FoundAnnouncement announcement(String numberAnno) {
        JsonNode items = v3("TrdBuy", BUY_FIELDS, List.of(new F("numberAnno", "String", numberAnno)), 1, null, null);
        return items.size() == 0 ? null : announcementOf(items.get(0));
    }

    @Override
    public List<FoundLot> lotsOfAnnouncement(String numberAnno) {
        return map(v3("Lots", LOT_FIELDS, List.of(new F("trdBuyNumberAnno", "String", numberAnno)), 100, null, null),
                GoszakupHttpClient::lotOf);
    }

    @Override
    public SearchPage<FoundPlanPoint> searchPlans(String word, List<Integer> years, List<Integer> statuses, Long after) {
        Long[] next = new Long[1];
        JsonNode items = v3("Plans", PLAN_FIELDS, List.of(new F("nameDescriptionRu", "String", word),
                new F("plnPointYear", "[Int]", years), new F("refPlnPointStatusId", "[Int]", statuses)), SEARCH_PAGE, after, next);
        return new SearchPage<>(map(items, GoszakupHttpClient::planOf), next[0]);
    }

    @Override
    public List<FoundPlanPoint> plansById(List<Long> ids) {
        if (ids == null || ids.isEmpty()) return List.of();
        List<Integer> asInt = ids.stream().map(Long::intValue).toList();   // id в фильтре Plans — [Int]
        return map(v3("Plans", PLAN_FIELDS, List.of(new F("id", "[Int]", asInt)), Math.min(200, ids.size()), null, null),
                GoszakupHttpClient::planOf);
    }

    @Override
    public List<ContractInfo> contracts(String numberAnno) {
        return map(v3("Contract", "supplierBiin contractSum contractSumWnds signDate refContractStatusId",
                List.of(new F("trdBuyNumberAnno", "String", numberAnno)), 20, null, null),
                c -> new ContractInfo(text(c, "supplierBiin"), dec(c, "contractSum"), dec(c, "contractSumWnds"),
                        text(c, "signDate"), integer(c, "refContractStatusId")));
    }
```

(удалить заглушку `dates(...)` — она не нужна; `range` даёт список дат.) Импорты: `java.time.LocalDate`, `com.vladoose.nir.integration.goszakup.dto.*`.

- [ ] **Step 4: Run the client test**

Run: `./gradlew test --tests '*GoszakupSearchClientTest*' --tests '*GoszakupHttpClientTest*'`
Expected: PASS (прежние тесты клиента — тоже).

- [ ] **Step 5: Write the failing service tests**

`FakeGoszakupSearchClient`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.integration.goszakup.GoszakupCallException;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.*;

import java.time.LocalDate;
import java.util.*;

/** Управляемый фейк v3-поиска: ответы по слову, объявления по номеру, отказ 403 с N-го вызова. */
public class FakeGoszakupSearchClient implements GoszakupSearchClient {
    public boolean configured = true;
    public final Map<String, List<FoundLot>> lotsByWord = new HashMap<>();
    public final Map<String, List<FoundAnnouncement>> buysByWord = new HashMap<>();
    public final Map<String, FoundAnnouncement> byNumber = new HashMap<>();
    public final Map<String, List<FoundLot>> lotsByAnno = new HashMap<>();
    public final Map<String, List<FoundPlanPoint>> plansByWord = new HashMap<>();
    public final Map<Long, FoundPlanPoint> plansById = new HashMap<>();
    public final Map<String, List<ContractInfo>> contracts = new HashMap<>();
    public final List<String> calls = new ArrayList<>();
    /** С какого по счёту вызова отвечать 403 (0 — никогда). */
    public int forbidFromCall = 0;

    private void call(String what) {
        calls.add(what);
        if (forbidFromCall > 0 && calls.size() >= forbidFromCall) {
            throw new GoszakupCallException("goszakup API 403 на /v3/graphql", 403, false, null);
        }
    }

    @Override public boolean isConfigured() { return configured; }
    @Override public SearchPage<FoundLot> searchLots(String w, List<Integer> s, LocalDate f, LocalDate t, Long a) {
        call("lots:" + w); return new SearchPage<>(lotsByWord.getOrDefault(w, List.of()), null);
    }
    @Override public SearchPage<FoundAnnouncement> searchAnnouncements(String w, List<Integer> s, LocalDate f, LocalDate t, Long a) {
        call("buys:" + w); return new SearchPage<>(buysByWord.getOrDefault(w, List.of()), null);
    }
    @Override public FoundAnnouncement announcement(String n) { call("anno:" + n); return byNumber.get(n); }
    @Override public List<FoundLot> lotsOfAnnouncement(String n) { call("annoLots:" + n); return lotsByAnno.getOrDefault(n, List.of()); }
    @Override public SearchPage<FoundPlanPoint> searchPlans(String w, List<Integer> y, List<Integer> s, Long a) {
        call("plans:" + w); return new SearchPage<>(plansByWord.getOrDefault(w, List.of()), null);
    }
    @Override public List<FoundPlanPoint> plansById(List<Long> ids) {
        call("plansById"); return ids.stream().map(plansById::get).filter(Objects::nonNull).toList();
    }
    @Override public List<ContractInfo> contracts(String n) { call("contracts:" + n); return contracts.getOrDefault(n, List.of()); }
}
```

`ProductWatchServiceTest` (база, откат; свои группы и каталог в транзакции теста — тест не зависит от правок оператора):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.SourceRemovedLots;
import com.vladoose.nir.integration.goszakup.*;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Поиск по стране (спека §7): кандидаты, заведение, отметки, остановка на 403, обновление пропавших. */
@SpringBootTest
@Transactional
class ProductWatchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired RegionResolver regionResolver;
    @Autowired SourceRemovedLots removedLots;
    @Autowired EntityManager em;

    /** 2026-10-19 10:00 по Уральску. */
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);
    FakeGoszakupSearchClient search;
    FakeGoszakupClient client;
    ProductWatchService service;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        em.flush();
        snap = CatalogSnapshot.build(groups, products);
        search = new FakeGoszakupSearchClient();
        client = new FakeGoszakupClient();
        GoszakupTenderWriter writer = new GoszakupTenderWriter(tenders, regionResolver, removedLots, CLOCK);
        ProductMatchService matchService = new ProductMatchService(tenders, matches, () -> snap);
        service = new ProductWatchService(search, client, writer, matchService, () -> snap, tenders, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static FoundAnnouncement astana(String endDate) {
        return new FoundAnnouncement(17802320L, "17802320-1", "Аппарат для аспирации дыма", "080740007819",
                "ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астана", new BigDecimal("3714285.71"),
                "2026-10-16 19:32:08", endDate, 220, 6, "Из одного источника по несостоявшимся закупкам", 3);
    }

    static FoundLot lot(FoundAnnouncement a, String name, String descr) {
        return new FoundLot(1L, "88111123-ОИ3", name, descr, BigDecimal.ONE, new BigDecimal("3714285.71"), 220,
                "2026-10-16 19:32:08", a.orgBin(), a.orgNameRu(), a.numberAnno(), a);
    }

    private void astanaOnPlatform() {
        FoundAnnouncement a = astana("2026-10-20 09:00:08");
        search.lotsByWord.put("отсасыватель", List.of(lot(a, "Отсасыватель", "для аспирации жидкого содержимого")));
        LotDto l = new LotDto();
        l.setLotNumber("88111123-ОИ3"); l.setNameRu("Отсасыватель"); l.setDescriptionRu("для аспирации жидкого содержимого");
        l.setCount(BigDecimal.ONE); l.setAmount(new BigDecimal("3714285.71"));
        client.lotsByAnno.put("17802320-1", List.of(l));
        SubjectDto s = new SubjectDto();
        s.setNameRu("ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астана");
        SubjectAddressDto addr = new SubjectAddressDto();
        addr.setAddress("г. Астана, ул. Ш. Айманова 23");
        s.setAddress(List.of(addr));
        client.subjectsByBin.put("080740007819", s);
    }

    @Test
    void newAnnouncement_importedWithMarksRegionMethod() {
        astanaOnPlatform();
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        Tender t = tenders.findBySourceExtId("17802320-1").orElseThrow();
        assertThat(t.isFoundByProductSearch()).isTrue();
        assertThat(t.getRegion()).isEqualTo("г. Астана");
        assertThat(t.getPurchaseType()).isEqualTo("Из одного источника по несостоявшимся");   // 50 символов колонки
        assertThat(t.getDeadlineAt()).isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0, 8));
        List<TenderLotProductMatch> m = matches.findByTenderId(t.getId());
        assertThat(m).hasSize(1);
        assertThat(m.get(0).getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.get(0).getSource()).isEqualTo(ProductMatchSource.SEARCH);
        assertThat(sum.getCreated()).isEqualTo(1);
        assertThat(sum.getErrors()).isZero();
    }

    @Test
    void sameAnnouncementSeveralWords_importedOnce() {   // Review Focus #1
        astanaOnPlatform();
        search.buysByWord.put("аспирации", List.of(astana("2026-10-20 09:00:08")));
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(sum.getCreated()).isEqualTo(1);
        assertThat(client.lotsCalls.get("17802320-1")).isEqualTo(1);
        assertThat(tenders.findBySourceExtId("17802320-1")).isPresent();
    }

    @Test
    void noOurLot_notImported() {
        FoundAnnouncement a = astana("2026-10-20 09:00:08");
        search.lotsByWord.put("монитор", List.of(lot(a, "Монитор компьютерный", "диагональ 24")));
        LotDto l = new LotDto();
        l.setLotNumber("1"); l.setNameRu("Монитор компьютерный"); l.setDescriptionRu("диагональ 24");
        client.lotsByAnno.put("17802320-1", List.of(l));
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(tenders.findBySourceExtId("17802320-1")).isEmpty();
        assertThat(sum.getSkipped()).isEqualTo(1);
    }

    @Test
    void deadlinePassed_notCandidate() {
        FoundAnnouncement a = astana("2026-10-19 09:00:00");   // час назад по Уральску
        search.lotsByWord.put("отсасыватель", List.of(lot(a, "Отсасыватель", "")));
        service.run(new ImportSummary());
        assertThat(client.lotsCalls).isEmpty();
    }

    @Test
    void forbiddenMidRun_stopsKeepsWritten() {   // Review Focus #5
        astanaOnPlatform();
        FoundAnnouncement b = new FoundAnnouncement(1L, "17000001-1", "Облучатель бактерицидный", "111", "Школа", BigDecimal.TEN,
                "2026-10-18 10:00:00", "2026-10-21 10:00:00", 220, 2, "Запрос ценовых предложений", 3);
        search.buysByWord.put("облучатель", List.of(b));
        // объявление Астаны обрабатывается первым (срок раньше), второе — 403 на лотах
        client.forbiddenLots.add("17000001-1");
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(tenders.findBySourceExtId("17802320-1")).isPresent();
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).contains("403");
    }

    @Test
    void vanishedFoundTender_refreshedByNumber() {
        astanaOnPlatform();
        service.run(new ImportSummary());
        search.lotsByWord.clear();
        search.byNumber.put("17802320-1", new FoundAnnouncement(17802320L, "17802320-1", "Аппарат для аспирации дыма", "080740007819",
                null, null, null, "2026-10-20 09:00:08", 430, 6, null, 3));
        service.run(new ImportSummary());
        assertThat(tenders.findBySourceExtId("17802320-1").orElseThrow().getStatus()).isEqualTo("CANCELLED");
    }

    @Test
    void notConfigured_disabled() {
        search.configured = false;
        ImportSummary sum = new ImportSummary();
        service.run(sum);
        assertThat(sum.isEnabled()).isFalse();
    }
}
```

В `FakeGoszakupClient` добавить: `public final java.util.Set<String> forbiddenLots = new java.util.HashSet<>();` и в `fetchLots` после `failTransient(...)`: `if (forbiddenLots.contains(numberAnno)) throw new GoszakupCallException("goszakup API 403 на /v2/lots", 403, false, null);`.

`ProductMatchServiceTest` — отметки: создание, обновление уровня при ТЗ (`tech_spec_status = OK`, `required_spec` = текст ТЗ Астаны → `COPIED`), удаление лишних (лот перестал проходить группу после правки названия), `hadMarks`, `markNotified`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductMatchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired EntityManager em;
    ProductMatchService service;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        service = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender(String announcement, String lotName, String descr) {
        Tender t = new Tender();
        t.setTenderNumber("PW-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ); t.setDescription(announcement);
        t.setPlatform(TenderPlatform.GOSZAKUP); t.setSourceExtId("PW-1");
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName); l.setRequiredSpec(descr);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    @Test
    void createUpgradeWithSpecAndRemoveStale() {
        Tender t = tender("Аппарат для аспирации дыма", "Отсасыватель", "для аспирации жидкого содержимого");
        ProductMatchService.Result r1 = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r1.level()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(r1.hadMarks()).isFalse();

        TenderLot lot = t.getLots().get(0);
        lot.setTechSpecStatus(TechSpecStatus.OK);
        lot.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
        em.flush();
        ProductMatchService.Result r2 = service.evaluate(t.getId(), ProductMatchSource.SEARCH);
        assertThat(r2.level()).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(r2.hadMarks()).isTrue();
        TenderLotProductMatch m = matches.findByTenderId(t.getId()).get(0);
        assertThat(m.getSpecCheckedAt()).isNotNull();
        assertThat(m.getSource()).as("источник — первой отметки").isEqualTo(ProductMatchSource.SEARCH);

        lot.setEquipName("Бинт марлевый"); lot.setTechSpecStatus(null); lot.setRequiredSpec(null);
        t.setDescription("Перевязочные материалы");
        em.flush();
        assertThat(service.evaluate(t.getId(), ProductMatchSource.REMATCH).level()).isNull();
        assertThat(matches.findByTenderId(t.getId())).isEmpty();
    }

    @Test
    void markNotified() {
        Tender t = tender("Аппарат для аспирации дыма", "Отсасыватель", "");
        service.markNotified(t.getId(), ProductMatchLevel.TYPE);
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }
}
```

- [ ] **Step 6: Run to verify they fail**

Run: `./gradlew test --tests '*ProductWatchServiceTest*' --tests '*ProductMatchServiceTest*'`
Expected: FAIL компиляции — нет `ProductWatchService`, `ProductMatchService`, `CatalogSource`, `upsertFound`.

- [ ] **Step 7: Implement writer additions**

В `GoszakupTenderWriter`:

```java
    /**
     * Тендер, найденный поиском нашей продукции по стране (спека 2026-10-19-product-watch §7.3): тот же upsert, что у
     * импорта ЗКО (регион — по заказчику), плюс признак «найден поиском» и способ закупки площадки (колонка — 50 символов).
     */
    @Transactional
    public Result upsertFound(TrdBuyDto d, SubjectDto subj, List<LotDto> lots, String tradeMethod) {
        Result r = upsertOne(d, subj, lots, null);
        Tender t = tenderRepository.findBySourceExtId(d.getNumberAnno()).orElseThrow();
        t.setFoundByProductSearch(true);
        if (tradeMethod != null && !tradeMethod.isBlank()) t.setPurchaseType(trunc(tradeMethod, 50));
        return r;
    }

    /** Статус и срок заведённого поиском тендера, пропавшего из выдачи (отменён, приостановлен, закрыт): без лотов. */
    @Transactional
    public boolean updateStatus(String numberAnno, Integer refBuyStatusId, String endDate) {
        Tender t = tenderRepository.findBySourceExtId(numberAnno).orElse(null);
        if (t == null) return false;
        LocalDateTime at = ImportDeadline.parse(endDate);
        if (at != null) {
            t.setDeadline(at.toLocalDate());
            t.setDeadlineAt(at);
        }
        String status = mapStatus(refBuyStatusId);
        if ("ACTIVE".equals(status)
                && ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone()))) {
            status = "COMPLETED";
        }
        t.setStatus(status);
        return true;
    }

    /** null — тендера нет; иначе — заведён ли он поиском по стране. */
    @Transactional(readOnly = true)
    public Boolean foundBySearch(String numberAnno) {
        return tenderRepository.findBySourceExtId(numberAnno).map(Tender::isFoundByProductSearch).orElse(null);
    }
```

В `TenderRepository`: `List<Tender> findByFoundByProductSearchTrueAndStatus(String status);`.

- [ ] **Step 8: Implement `CatalogSource`, `ProductMatchService`**

```java
package com.vladoose.nir.service.productwatch;

/** Откуда правилам брать снимок: в работе — {@link ProductCatalog}, в тестах — лямбда со своим снимком. */
@FunctionalInterface
public interface CatalogSource {
    CatalogSnapshot get();
}
```

`ProductCatalog` — `implements CatalogSource` (метод `get()` уже есть).

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/**
 * Отметки «наш товар» одного тендера (спека 2026-10-19-product-watch §4.3, §5.6): правила по тому, что лежит в базе
 * (название объявления, лоты; разобранное ТЗ — у лотов со статусом OK), результат — заменой набора отметок тендера.
 * Источник строки — первой отметки; ТЗ сверено — когда отметка посчитана по ТЗ.
 */
@Service
public class ProductMatchService {

    public record Result(long tenderId, ProductMatchLevel level, int lots, boolean hadMarks) {}

    private final TenderRepository tenders;
    private final TenderLotProductMatchRepository matches;
    private final CatalogSource catalog;

    public ProductMatchService(TenderRepository tenders, TenderLotProductMatchRepository matches, CatalogSource catalog) {
        this.tenders = tenders;
        this.matches = matches;
        this.catalog = catalog;
    }

    @Transactional
    public Result evaluate(long tenderId, ProductMatchSource source) {
        Tender t = tenders.findById(tenderId).orElse(null);
        if (t == null) return new Result(tenderId, null, 0, false);
        List<LotInput> inputs = new ArrayList<>();
        for (TenderLot l : t.getLots()) {
            if (l.getSourceRemovedAt() != null || l.getId() == null) continue;
            boolean spec = l.getTechSpecStatus() == TechSpecStatus.OK;
            inputs.add(new LotInput(l.getId(), l.getEquipName(), spec ? null : l.getRequiredSpec(), spec ? l.getRequiredSpec() : null));
        }
        List<LotMatch> result = ProductMatcher.match(catalog.get(), t.getDescription(), inputs);
        List<TenderLotProductMatch> existing = matches.findByTenderId(tenderId);
        boolean hadMarks = !existing.isEmpty();
        Map<String, TenderLotProductMatch> byKey = new HashMap<>();
        for (TenderLotProductMatch m : existing) byKey.put(m.getLotId() + "/" + m.getGroupId(), m);
        OffsetDateTime now = OffsetDateTime.now();
        Set<String> keep = new HashSet<>();
        Set<Long> lots = new HashSet<>();
        ProductMatchLevel max = null;
        for (LotMatch m : result) {
            String key = m.lotId() + "/" + m.groupId();
            keep.add(key);
            lots.add(m.lotId());
            TenderLotProductMatch row = byKey.get(key);
            if (row == null) {
                row = TenderLotProductMatch.builder().tenderId(tenderId).lotId(m.lotId()).groupId(m.groupId())
                        .source(source).matchedAt(now).build();
            }
            row.setWord(trunc(m.word(), 100));
            row.setLevel(m.level());
            row.setEvidence(trunc(m.evidence(), 1000));
            row.setProductId(m.productId());
            row.setSimilar(new ArrayList<>(m.similar()));
            if (m.specUsed() && row.getSpecCheckedAt() == null) row.setSpecCheckedAt(now);
            row.setUpdatedAt(now);
            matches.save(row);
            max = ProductMatchLevel.max(max, m.level());
        }
        for (TenderLotProductMatch m : existing) {
            if (!keep.contains(m.getLotId() + "/" + m.getGroupId())) matches.delete(m);
        }
        return new Result(tenderId, max, lots.size(), hadMarks);
    }

    /** ТЗ лота недоступно насовсем (нет файла, не читается, 3 неудачи) — сверка закончена, отметка остаётся по названию. */
    @Transactional
    public void markSpecChecked(long lotId) {
        OffsetDateTime now = OffsetDateTime.now();
        for (TenderLotProductMatch m : matches.findByLotId(lotId)) {
            if (m.getSpecCheckedAt() == null) {
                m.setSpecCheckedAt(now);
                matches.save(m);
            }
        }
    }

    /** Сообщено в Telegram (или пересчёт молча, спека §8) — уровень не понижается. */
    @Transactional
    public void markNotified(long tenderId, ProductMatchLevel level) {
        tenders.findById(tenderId).ifPresent(t -> {
            t.setProductNotifiedLevel(ProductMatchLevel.max(t.getProductNotifiedLevel(), level));
            t.setProductNotifiedAt(OffsetDateTime.now());
        });
    }

    private static String trunc(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
```

- [ ] **Step 9: Implement `ProductWatchService`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.*;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Поиск нашей продукции по стране (спека 2026-10-19-product-watch §7): слова включённых групп → лоты и объявления с
 * идущим приёмом за 60 дней → у себя правила (§5) → новое заводится тем же писателем, что импорт ЗКО, заведённое
 * поиском — обновляется, чужое (ЗКО, СК-Фармация) — только отметки. Сеть — вне транзакции (§6 CLAUDE.md); пауза
 * 300 мс между запросами; 401 / 403 / 429 — прогон останавливается.
 */
@Service
public class ProductWatchService {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchService.class);

    public static final List<Integer> OPEN_LOT = List.of(190, 210, 220, 230, 240);
    public static final List<Integer> OPEN_BUY = List.of(210, 220, 230, 240, 245);
    static final int WINDOW_DAYS = 60;
    static final int MAX_PAGES = 5;
    static final long PAUSE_MS = 300;

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final GoszakupTenderWriter writer;
    private final ProductMatchService matches;
    private final CatalogSource catalog;
    private final TenderRepository tenders;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProductWatchService(GoszakupSearchClient search, GoszakupClient client, GoszakupTenderWriter writer,
                               ProductMatchService matches, ProductCatalog catalog, TenderRepository tenders) {
        this(search, client, writer, matches, catalog, tenders, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public ProductWatchService(GoszakupSearchClient search, GoszakupClient client, GoszakupTenderWriter writer,
                               ProductMatchService matches, CatalogSource catalog, TenderRepository tenders, Clock clock,
                               UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.writer = writer; this.matches = matches;
        this.catalog = catalog; this.tenders = tenders; this.clock = clock; this.sleeper = sleeper;
    }

    /** Стоп-коды: повтор не поможет, а долбёжка жжёт общий с продом токен. */
    public static boolean stopping(RuntimeException e) {
        return e instanceof GoszakupCallException g && (g.status() == 401 || g.status() == 403 || g.status() == 429);
    }

    public void run(ImportSummary sum) {
        if (!search.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return;
        }
        CatalogSnapshot snap = catalog.get();
        List<String> words = snap.groups().stream().flatMap(g -> g.searchWords().stream())
                .map(w -> w.strip().toLowerCase(Locale.ROOT)).filter(w -> !w.isEmpty()).distinct().toList();
        if (words.isEmpty()) {
            sum.setMessage("Нет включённых групп нашей продукции");
            return;
        }
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        LocalDate today = now.toLocalDate();
        Map<String, FoundAnnouncement> candidates = new LinkedHashMap<>();
        List<String> notes = new ArrayList<>();
        try {
            for (String w : words) {
                collectLots(w, today, now, candidates, sum, notes);
                collectAnnouncements(w, today, now, candidates, sum, notes);
            }
        } catch (RuntimeException e) {
            if (!stopping(e)) throw e;
            sum.addError("поиск остановлен: " + ErrorText.of(e));
            finish(sum, notes);
            return;
        }
        Map<String, Optional<SubjectDto>> subjects = new HashMap<>();
        List<FoundAnnouncement> ordered = candidates.values().stream()
                .sorted(Comparator.comparing((FoundAnnouncement a) -> ImportDeadline.parse(a.endDate()),
                        Comparator.nullsLast(Comparator.naturalOrder()))).toList();
        for (FoundAnnouncement a : ordered) {
            try {
                processCandidate(a, snap, sum, subjects);
            } catch (RuntimeException e) {
                sum.addError("объявление " + a.numberAnno() + ": " + ErrorText.of(e));
                log.warn("Поиск нашей продукции: объявление {}: {}", a.numberAnno(), ErrorText.of(e));
                if (stopping(e)) {
                    finish(sum, notes);
                    return;
                }
            }
        }
        refreshVanished(candidates.keySet(), sum);
        finish(sum, notes);
    }

    private void collectLots(String w, LocalDate today, LocalDateTime now, Map<String, FoundAnnouncement> out, ImportSummary sum,
                             List<String> notes) {
        Long after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            pause();
            Long a0 = after;
            SearchPage<FoundLot> p = UpstreamRetry.call(sleeper, () -> search.searchLots(w, OPEN_LOT,
                    today.minusDays(WINDOW_DAYS), today.plusDays(1), a0));
            sum.setFetched(sum.getFetched() + p.items().size());
            for (FoundLot l : p.items()) if (open(l.announcement(), now)) out.putIfAbsent(l.announcement().numberAnno(), l.announcement());
            after = p.nextAfter();
            if (after == null) return;
        }
        notes.add("«" + w + "»: больше " + MAX_PAGES + " страниц");
        log.warn("Поиск нашей продукции: по слову «{}» больше {} страниц — хвост не просмотрен", w, MAX_PAGES);
    }

    private void collectAnnouncements(String w, LocalDate today, LocalDateTime now, Map<String, FoundAnnouncement> out,
                                      ImportSummary sum, List<String> notes) {
        Long after = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            pause();
            Long a0 = after;
            SearchPage<FoundAnnouncement> p = UpstreamRetry.call(sleeper, () -> search.searchAnnouncements(w, OPEN_BUY,
                    today.minusDays(WINDOW_DAYS), today.plusDays(1), a0));
            sum.setFetched(sum.getFetched() + p.items().size());
            for (FoundAnnouncement a : p.items()) if (open(a, now)) out.putIfAbsent(a.numberAnno(), a);
            after = p.nextAfter();
            if (after == null) return;
        }
        notes.add("объявления «" + w + "»: больше " + MAX_PAGES + " страниц");
    }

    /** Приём идёт: срок с временем площадки позже «сейчас», модуль госзакупа — текущий. */
    static boolean open(FoundAnnouncement a, LocalDateTime now) {
        if (a == null || a.numberAnno() == null) return false;
        if (a.systemId() != null && a.systemId() != 3) return false;
        LocalDateTime at = ImportDeadline.parse(a.endDate());
        return at != null && at.isAfter(now);
    }

    private void processCandidate(FoundAnnouncement a, CatalogSnapshot snap, ImportSummary sum, Map<String, Optional<SubjectDto>> subjects) {
        pause();
        List<LotDto> lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(a.numberAnno()));
        List<LotInput> inputs = lots.stream().map(l -> new LotInput(0L, l.getNameRu(), l.getDescriptionRu(), null)).toList();
        if (lots.isEmpty() || ProductMatcher.match(snap, a.nameRu(), inputs).isEmpty()) {
            sum.setSkipped(sum.getSkipped() + 1);
            return;
        }
        sum.setMatched(sum.getMatched() + 1);
        Boolean found = writer.foundBySearch(a.numberAnno());
        if (found == null) {
            SubjectDto subj = subjectOf(a.orgBin(), subjects);
            if (writer.upsertFound(a.toTrdBuy(), subj, lots, a.tradeMethodName()) == GoszakupTenderWriter.Result.CREATED) {
                sum.addCreated(a.numberAnno());
            } else {
                sum.setUpdated(sum.getUpdated() + 1);
            }
        } else if (found) {
            writer.upsertFound(a.toTrdBuy(), null, lots, a.tradeMethodName());
            sum.setUpdated(sum.getUpdated() + 1);
        }
        Tender t = tenders.findBySourceExtId(a.numberAnno()).orElseThrow();
        matches.evaluate(t.getId(), ProductMatchSource.SEARCH);
        sum.getTouchedExtIds().add(a.numberAnno());
    }

    /** Заведённые поиском тендеры с идущим приёмом, которых нет в выдаче, — статус по номеру. */
    private void refreshVanished(Set<String> seen, ImportSummary sum) {
        for (Tender t : tenders.findByFoundByProductSearchTrueAndStatus("ACTIVE")) {
            if (seen.contains(t.getSourceExtId())) continue;
            try {
                pause();
                FoundAnnouncement a = UpstreamRetry.call(sleeper, () -> search.announcement(t.getSourceExtId()));
                if (a != null && writer.updateStatus(t.getSourceExtId(), a.refBuyStatusId(), a.endDate())) {
                    sum.setUpdated(sum.getUpdated() + 1);
                }
            } catch (RuntimeException e) {
                sum.addError("обновление " + t.getSourceExtId() + ": " + ErrorText.of(e));
                if (stopping(e)) return;
            }
        }
    }

    private SubjectDto subjectOf(String bin, Map<String, Optional<SubjectDto>> cache) {
        if (bin == null || bin.isBlank()) return null;
        return cache.computeIfAbsent(bin, b -> {
            try {
                pause();
                return Optional.ofNullable(UpstreamRetry.call(sleeper, () -> client.fetchSubject(b)));
            } catch (RuntimeException e) {
                if (stopping(e)) throw e;
                log.warn("Поиск нашей продукции: subject {} недоступен — тендер без заказчика: {}", b, ErrorText.of(e));
                return Optional.empty();
            }
        }).orElse(null);
    }

    private void finish(ImportSummary sum, List<String> notes) {
        sum.setMessage(String.format("Найдено %d, наших %d, новых %d, обновлено %d, ошибок %d%s", sum.getFetched(),
                sum.getMatched(), sum.getCreated(), sum.getUpdated(), sum.getErrors(),
                notes.isEmpty() ? "" : " · " + String.join("; ", notes)));
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("поиск прерван");
        }
    }
}
```

`ImportSummary` — список затронутых номеров (задача 7 использует его и у импорта ЗКО и СК-Фармации):

```java
    /** Номера объявлений, созданных или обновлённых прогоном (отметки «наш товар», спека 2026-10-19-product-watch §8). */
    @JsonIgnore
    private final Set<String> touchedExtIds = new LinkedHashSet<>();
```

и в `addCreated` — `touchedExtIds.add(sourceExtId);`.

- [ ] **Step 10: Run tests**

Run: `./gradlew test --tests '*ProductWatchServiceTest*' --tests '*ProductMatchServiceTest*' --tests '*GoszakupImportServiceTest*' --tests '*GoszakupSearchClientTest*'`
Expected: PASS.

- [ ] **Step 11: Mutation check**

1. `ProductWatchService.open`: не проверять срок → `deadlinePassed_notCandidate` красный.
2. `collectLots`: `out.put` вместо `putIfAbsent` и без дедупа по номеру в `processCandidate` (обрабатывать каждый кандидат по каждому слову: хранить список вместо карты) → `sameAnnouncementSeveralWords_importedOnce` красный.
3. `run`: при `stopping(e)` в цикле кандидатов — `continue` вместо `return` → `forbiddenMidRun_stopsKeepsWritten` (ожидается одна ошибка; станет больше или пойдут лишние вызовы) — красный по `getErrors() == 1` только при втором стоп-объявлении; добавить в тест третье объявление со сроком позже — и проверить, что `client.lotsCalls` его не содержит.
4. `upsertFound` без `setFoundByProductSearch(true)` → `newAnnouncement_…` красный.
5. `ProductMatchService.evaluate` без удаления лишних → `createUpgradeWithSpecAndRemoveStale` красный.
Откат копией, `./gradlew compileJava`.

- [ ] **Step 12: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration/goszakup src/main/java/com/vladoose/nir/service/productwatch src/main/java/com/vladoose/nir/repository/TenderRepository.java src/test/java/com/vladoose/nir/integration/goszakup src/test/java/com/vladoose/nir/productwatch
git commit -m "feat(product-watch): поиск по стране — v3 лоты и объявления по слову, заведение тендеров, отметки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: ТЗ найденных лотов — общий разбор одного лота, сверка по ТЗ

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/LotSpecParser.java`, `src/main/java/com/vladoose/nir/service/TechSpecLotParser.java`, `src/main/java/com/vladoose/nir/service/productwatch/ProductSpecChecker.java`
- Modify: `src/main/java/com/vladoose/nir/service/TechSpecBackfillScheduler.java` (`processOne` → `TechSpecLotParser`), `TechSpecStatusWriter.java` (`incrementAttempts`), `repository/TenderLotProductMatchRepository.java` (`findLotsForSpecCheck`)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductSpecCheckerTest.java`; прежний `TechSpecBackfillSchedulerTest` — без правок, должен остаться зелёным

**Interfaces:**
- Consumes: `ProductMatchService.evaluate/markSpecChecked` (задача 4); `TechSpecService.parse(Long)` (есть).
- Produces:
  - `@FunctionalInterface LotSpecParser { TechSpecLotParser.Result parse(Long lotId); }`; `TechSpecLotParser implements LotSpecParser`, `record Result(TechSpecStatus status, boolean outage)`;
  - `TechSpecStatusWriter.incrementAttempts(Long lotId)`;
  - `TenderLotProductMatchRepository.findLotsForSpecCheck(boolean goszakup, int limit) → List<Long>` (лоты с отметкой без сверки ТЗ, тендер ACTIVE, статус ТЗ не `NO_FILE`/`UNREADABLE`, попыток < 3; сначала ближайший срок; без токена goszakup — только СК-Фармация);
  - `ProductSpecChecker.run(int limit, ImportSummary sum) → int` (сверено лотов); константы `MAX_ATTEMPTS = 3`, `OUTAGE_STOP = 3`.

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ТЗ найденных лотов (спека §7.4): отметка попытки до разбора, сверка по ТЗ, терминальные исходы, остановка на аварии. */
@SpringBootTest
@Transactional
class ProductSpecCheckerTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired TechSpecStatusWriter statusWriter;
    @Autowired EntityManager em;
    ProductMatchService matchService;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private TenderLot suctionLot() {
        Tender t = new Tender();
        t.setTenderNumber("PW-SPEC"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ); t.setDescription("Аппарат для аспирации дыма");
        t.setPlatform(TenderPlatform.GOSZAKUP); t.setSourceExtId("PW-SPEC");
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель"); l.setRequiredSpec("для аспирации жидкого содержимого");
        l.setTechSpecStatus(TechSpecStatus.PENDING);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return l;
    }

    private ProductSpecChecker checker(LotSpecParser parser) {
        return new ProductSpecChecker(matches, lots, statusWriter, parser, matchService, () -> true, ms -> { });
    }

    @Test
    void parsedSpec_upgradesToCopied_attemptMarkedBeforeParse() {
        TenderLot l = suctionLot();
        List<Integer> attemptsSeen = new ArrayList<>();
        int done = checker(lotId -> {
            em.flush(); em.clear();
            TenderLot fresh = lots.findById(lotId).orElseThrow();
            attemptsSeen.add(fresh.getTechSpecAttempts());
            fresh.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
            fresh.setTechSpecStatus(TechSpecStatus.OK);
            lots.save(fresh);
            em.flush();
            return new TechSpecLotParser.Result(TechSpecStatus.OK, false);
        }).run(10, new ImportSummary());
        assertThat(done).isEqualTo(1);
        assertThat(attemptsSeen).containsExactly(1);
        TenderLotProductMatch m = matches.findByLotId(l.getId()).get(0);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.COPIED);
        assertThat(m.getSpecCheckedAt()).isNotNull();
    }

    @Test
    void noFile_checkedKeepsType() {
        TenderLot l = suctionLot();
        checker(id -> { statusWriter.markResult(id, TechSpecStatus.NO_FILE); return new TechSpecLotParser.Result(TechSpecStatus.NO_FILE, false); })
                .run(10, new ImportSummary());
        TenderLotProductMatch m = matches.findByLotId(l.getId()).get(0);
        assertThat(m.getLevel()).isEqualTo(ProductMatchLevel.TYPE);
        assertThat(m.getSpecCheckedAt()).isNotNull();
    }

    @Test
    void errorThreeTimes_thenChecked() {
        TenderLot l = suctionLot();
        ProductSpecChecker c = checker(id -> new TechSpecLotParser.Result(TechSpecStatus.ERROR, false));
        c.run(10, new ImportSummary());
        c.run(10, new ImportSummary());
        assertThat(matches.findByLotId(l.getId()).get(0).getSpecCheckedAt()).isNull();
        c.run(10, new ImportSummary());
        assertThat(matches.findByLotId(l.getId()).get(0).getSpecCheckedAt()).isNotNull();
        assertThat(lots.findById(l.getId()).orElseThrow().getTechSpecAttempts()).isEqualTo(3);
        assertThat(c.run(10, new ImportSummary())).as("больше не берётся").isZero();
    }

    @Test
    void alreadyParsed_noDownload() {
        TenderLot l = suctionLot();
        l.setTechSpecStatus(TechSpecStatus.OK);
        l.setRequiredSpec(ProductFixtures.spec("17779323-1.pdf"));
        lots.save(l);
        em.flush();
        int[] calls = {0};
        checker(id -> { calls[0]++; return new TechSpecLotParser.Result(TechSpecStatus.OK, false); }).run(10, new ImportSummary());
        assertThat(calls[0]).isZero();
        assertThat(matches.findByLotId(l.getId()).get(0).getLevel()).isEqualTo(ProductMatchLevel.COPIED);
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ProductSpecCheckerTest*'`
Expected: FAIL компиляции — нет `TechSpecLotParser`, `LotSpecParser`, `ProductSpecChecker`.

- [ ] **Step 3: Extract `TechSpecLotParser`**

Перенести тело `TechSpecBackfillScheduler.processOne` без изменений в новый бин; `processOne` остаётся тонкой обёрткой `return parser.parse(lotId).outage();`; конструктор очереди — `(TenderLotRepository, TechSpecLotParser, TechSpecStatusWriter, GoszakupClient)` (`TechSpecService` ей больше не нужен).

```java
package com.vladoose.nir.service;

/** Разбор ТЗ одного лота: в работе — {@link TechSpecLotParser}, в тестах — лямбда. */
@FunctionalInterface
public interface LotSpecParser {
    TechSpecLotParser.Result parse(Long lotId);
}
```

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.exception.UpstreamException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Разбор ТЗ одного лота с записью исхода — общий для фоновой очереди и поиска нашей продукции (спека
 * 2026-10-19-product-watch §7.4). Перенесено из TechSpecBackfillScheduler.processOne без изменений: отметка ДО разбора
 * (exit 137 не ловится никаким catch), исход записывается всегда, Error отдаётся наружу после записи.
 */
@Component
public class TechSpecLotParser implements LotSpecParser {

    private static final Logger log = LoggerFactory.getLogger(TechSpecLotParser.class);

    /** outage — лот не разобрался именно из-за недоступности площадки (для предохранителя). */
    public record Result(TechSpecStatus status, boolean outage) {}

    private final TechSpecService techSpecService;
    private final TechSpecStatusWriter writer;

    public TechSpecLotParser(TechSpecService techSpecService, TechSpecStatusWriter writer) {
        this.techSpecService = techSpecService;
        this.writer = writer;
    }

    @Override
    public Result parse(Long lotId) {
        writer.markResult(lotId, TechSpecStatus.ERROR);
        TechSpecStatus status;
        boolean outage = false;
        Error fatal = null;
        try {
            techSpecService.parse(lotId);
            status = TechSpecStatus.OK;
        } catch (NotFoundException e) {
            status = TechSpecStatus.NO_FILE;
        } catch (UnprocessableException e) {
            status = TechSpecStatus.UNREADABLE;
        } catch (BadRequestException e) {
            status = TechSpecStatus.NO_FILE;
        } catch (UpstreamException e) {
            status = TechSpecStatus.ERROR;
            outage = true;
        } catch (RuntimeException e) {
            log.warn("Разбор ТЗ лота {} упал неожиданно: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
        } catch (Error e) {
            log.error("Разбор ТЗ лота {} упал фатально: {}", lotId, e.toString());
            status = TechSpecStatus.ERROR;
            fatal = e;
        }
        try {
            writer.markResult(lotId, status);
        } catch (Throwable writeFailure) {
            if (fatal == null) throw writeFailure;
            fatal.addSuppressed(writeFailure);
        }
        if (fatal != null) throw fatal;
        return new Result(status, outage);
    }
}
```

(Комментарии из прежнего `processOne` — про OOM-киллер, `Error`, запись исхода — переносятся вместе с кодом дословно.)

`TechSpecStatusWriter`:

```java
    /** Попытка поиска нашей продукции разобрать ТЗ лота (до разбора — как отметка очереди, спека §7.4). */
    @Transactional
    public void incrementAttempts(Long lotId) {
        lotRepository.findById(lotId).ifPresent(lot -> {
            lot.setTechSpecAttempts(lot.getTechSpecAttempts() + 1);
            lotRepository.save(lot);
        });
    }
```

- [ ] **Step 4: Implement repository query and `ProductSpecChecker`**

`TenderLotProductMatchRepository`:

```java
    /**
     * Лоты с отметкой «наш товар», чьё ТЗ ещё не сверено (спека 2026-10-19-product-watch §7.4): тендер ACTIVE, статус ТЗ не
     * терминальный, попыток меньше 3; сначала ближайший срок. Без токена goszakup — только лоты СК-Фармации.
     */
    @Query(value = """
            SELECT DISTINCT ON (l.id) l.id
            FROM tender_lot_product_match m
            JOIN tender t ON t.id = m.tender_id
            JOIN tender_lot l ON l.id = m.lot_id
            WHERE m.spec_checked_at IS NULL AND t.status = 'ACTIVE' AND t.market = 'KZ'
              AND (t.platform = 'SK_PHARMACY' OR (:goszakup AND (t.platform = 'GOSZAKUP' OR t.platform IS NULL)))
              AND (l.tech_spec_status IS NULL OR l.tech_spec_status IN ('PENDING', 'ERROR', 'OK'))
              AND l.tech_spec_attempts < 3
            ORDER BY l.id, t.deadline_at NULLS LAST
            """, nativeQuery = true)
    List<Long> findLotsForSpecCheckUnordered(@Param("goszakup") boolean goszakup);
```

(Порядок «сначала ближайший срок» — в Java: выбрать с тендерами и отсортировать; `DISTINCT ON` требует `ORDER BY l.id` первым.) В `ProductSpecChecker` — `findLotsForSpecCheckUnordered`, затем сортировка по сроку тендера через `lots.findAllById` и ограничение `limit`.

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.function.BooleanSupplier;

/**
 * ТЗ найденных лотов (спека 2026-10-19-product-watch §7.4): не больше limit за прогон, сначала ближайший срок.
 * Попытка отмечается ДО разбора (урок фоновой очереди: SIGKILL не ловится никаким catch); разобрано — пересчёт отметок
 * тендера по ТЗ; нет файла / не читается / 3 неудачи — сверка закончена, отметка остаётся по названию. Три аварии
 * площадки подряд — пачка обрывается (как у фоновой очереди).
 */
@Component
public class ProductSpecChecker {

    private static final Logger log = LoggerFactory.getLogger(ProductSpecChecker.class);
    static final int MAX_ATTEMPTS = 3;
    static final int OUTAGE_STOP = 3;
    static final long PAUSE_MS = 300;

    private final TenderLotProductMatchRepository matches;
    private final TenderLotRepository lots;
    private final TechSpecStatusWriter statusWriter;
    private final LotSpecParser parser;
    private final ProductMatchService matchService;
    private final BooleanSupplier goszakupConfigured;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              TechSpecLotParser parser, ProductMatchService matchService, GoszakupClient goszakup) {
        this(matches, lots, statusWriter, parser, matchService, goszakup::isConfigured, UpstreamRetry.REAL);
    }

    public ProductSpecChecker(TenderLotProductMatchRepository matches, TenderLotRepository lots, TechSpecStatusWriter statusWriter,
                              LotSpecParser parser, ProductMatchService matchService, BooleanSupplier goszakupConfigured,
                              UpstreamRetry.Sleeper sleeper) {
        this.matches = matches; this.lots = lots; this.statusWriter = statusWriter; this.parser = parser;
        this.matchService = matchService; this.goszakupConfigured = goszakupConfigured; this.sleeper = sleeper;
    }

    public int run(int limit, ImportSummary sum) {
        List<TenderLot> queue = lots.findAllById(matches.findLotsForSpecCheckUnordered(goszakupConfigured.getAsBoolean())).stream()
                .sorted(Comparator.comparing((TenderLot l) -> l.getTender().getDeadlineAt(), Comparator.nullsLast(Comparator.<LocalDateTime>naturalOrder())))
                .limit(limit).toList();
        int done = 0, outages = 0;
        for (TenderLot lot : queue) {
            long lotId = lot.getId(), tenderId = lot.getTender().getId();
            if (lot.getTechSpecStatus() == TechSpecStatus.OK) {      // ТЗ уже разобрано кнопкой или очередью — без скачивания
                matchService.evaluate(tenderId, ProductMatchSource.SEARCH);
                done++;
                continue;
            }
            pause();
            statusWriter.incrementAttempts(lotId);
            TechSpecLotParser.Result r = parser.parse(lotId);
            if (r.status() == TechSpecStatus.OK) {
                matchService.evaluate(tenderId, ProductMatchSource.SEARCH);
                done++;
            } else if (r.status() == TechSpecStatus.NO_FILE || r.status() == TechSpecStatus.UNREADABLE
                    || lots.findById(lotId).map(TenderLot::getTechSpecAttempts).orElse(MAX_ATTEMPTS) >= MAX_ATTEMPTS) {
                matchService.markSpecChecked(lotId);
                done++;
            }
            outages = r.outage() ? outages + 1 : 0;
            if (outages >= OUTAGE_STOP) {
                sum.addSideError("ТЗ: площадка недоступна — " + OUTAGE_STOP + " лота подряд, разбор отложен");
                log.warn("Поиск нашей продукции: ТЗ — {} отказа площадки подряд, пачка оборвана", OUTAGE_STOP);
                break;
            }
        }
        return done;
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
```

- [ ] **Step 5: Run tests**

Run: `./gradlew test --tests '*ProductSpecCheckerTest*' --tests '*TechSpecBackfillSchedulerTest*'`
Expected: PASS (очередь — без изменений поведения).

- [ ] **Step 6: Mutation check**

1. `ProductSpecChecker.run`: `incrementAttempts` после `parse` → `parsedSpec_…_attemptMarkedBeforeParse` красный (`attemptsSeen` = 0).
2. Без ветки «уже разобрано» (всегда `parser.parse`) → `alreadyParsed_noDownload` красный.
3. `MAX_ATTEMPTS` проверка `> 3` вместо `>= 3` → `errorThreeTimes_thenChecked` красный.
4. `TechSpecLotParser.parse` без первого `markResult(ERROR)` → прежний тест очереди про отметку до разбора красный (найти его по `kill`/`markResult` в `TechSpecBackfillSchedulerTest`; нет такого — добавить тест в `ProductSpecCheckerTest`: лямбда-парсер бросает `OutOfMemoryError`-подобный `Error` → у лота `ERROR` и попытка засчитана).
Откат, `./gradlew compileJava`.

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/vladoose/nir/service/ src/main/java/com/vladoose/nir/repository/TenderLotProductMatchRepository.java src/test/java/com/vladoose/nir/productwatch/ProductSpecCheckerTest.java
git commit -m "feat(product-watch): ТЗ найденных лотов — общий разбор одного лота с очередью, сверка с каталогом по ТЗ

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 6: Калибровка сверки ТЗ — набор настоящих ТЗ, разметка, гейт

**Files:**
- Create: `src/test/java/com/vladoose/nir/productwatch/ProductSpecCorpusTool.java` (сбор набора, запускается только с `PRODUCT_SPEC_CORPUS=1`)
- Create fixtures: `src/test/resources/product-watch/specs/*.pdf` (30–40 ТЗ), `src/test/resources/product-watch/golden.tsv` (разметка)
- Modify: `src/main/resources/product-watch/generic-phrases.txt` (общие фразы по набору), `CatalogPhrases.COPY_MIN` / `BY_FAMILY` (если калибровка требует)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductSpecMatchQualityTest.java`

**Interfaces:**
- Consumes: `GoszakupHttpClient` (поиск лотов, файлы ТЗ, скачивание), `ProductMatcher`, `CatalogSnapshot`, `ProductFixtures` (задачи 3–4).
- Produces: гейт с отсечками: ложных `COPIED` = 0, ложных `NAMED` = 0, оба объявления Астаны (17779323-1, 17802320-1) — `COPIED` «Аппарат для аспирации дыма АСД…», размеченных `COPIED` найдено не меньше `COPIED_RECALL_MIN` (число — по итогу калибровки, храповик); снимок числа строк набора и каталога — в шапке `golden.tsv`.

- [ ] **Step 1: Write the corpus tool**

Инструмент — тест, который по умолчанию пропускается (`@EnabledIfEnvironmentVariable(named = "PRODUCT_SPEC_CORPUS", matches = "1")`): токен — из `GOSZAKUP_TOKEN`; по каждой группе сида (кроме «Наших брендов») — `searchLots(слово, null, сегодня − 90 дн, завтра, null)`, до 3 лотов на группу с файлом «Техническая спецификация» (`fetchLotTechSpec(numberAnno, lotName)` → `downloadFile`), разные заказчики; для «Наших брендов» — до 5 лотов по словам «фотек», «аксион», «эхвч» без окна дат (лоты 2023–2025, ТЗ могли удалить — пропуск при 404). Сохраняет `specs/<номер объявления>_<номер лота>.pdf` (символы, кроме букв, цифр и «-», — «_») и строку в `specs/corpus.tsv`: файл, группа, слово, название лота, описание лота, название объявления, заказчик. Пауза 1 с между запросами, всего ≤ 300 запросов, 403 — стоп. Набор кладётся в репозиторий: ТЗ госзакупок — публичные документы.

```java
package com.vladoose.nir.productwatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.integration.goszakup.GoszakupHttpClient;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/** Сбор набора настоящих ТЗ для калибровки (задача 6 плана). Не тест: запуск — PRODUCT_SPEC_CORPUS=1 GOSZAKUP_TOKEN=… */
@EnabledIfEnvironmentVariable(named = "PRODUCT_SPEC_CORPUS", matches = "1")
class ProductSpecCorpusTool {

    record Want(String group, String word, int max, boolean anyDate) {}

    static final List<Want> WANTS = List.of(
            new Want("Электрокардиографы", "электрокардиограф", 3, false), new Want("Дефибрилляторы", "дефибриллятор", 3, false),
            new Want("Облучатели и рециркуляторы", "облучатель", 3, false), new Want("Облучатели и рециркуляторы", "рециркулятор", 2, false),
            new Want("Светильники", "светильник", 3, false), new Want("Электрохирургия", "электрохирургический", 2, false),
            new Want("Электрохирургия", "электрокоагулятор", 2, false), new Want("Отсасыватели и аспирация дыма", "отсасыватель", 3, false),
            new Want("Отсасыватели и аспирация дыма", "аспиратор", 2, false), new Want("Стерилизаторы озоновые", "стерилизатор", 2, false),
            new Want("Дозаторы шприцевые", "дозатор", 2, false), new Want("Мониторы пациента", "монитор", 2, false),
            new Want("Нейтральные электроды", "электрод", 2, false), new Want("Спирографы", "спирометр", 1, false),
            new Want("Наши бренды и модели", "фотек", 3, true), new Want("Наши бренды и модели", "эхвч", 2, true),
            new Want("Наши бренды и модели", "аксион", 2, true));

    @Test
    void collect() throws Exception {
        GoszakupHttpClient gz = new GoszakupHttpClient(new ObjectMapper(), "https://ows.goszakup.gov.kz/v2", System.getenv("GOSZAKUP_TOKEN"), 50);
        Path dir = Paths.get("src/test/resources/product-watch/specs");
        Files.createDirectories(dir);
        Path tsv = dir.resolve("corpus.tsv");
        List<String> rows = new ArrayList<>(Files.exists(tsv) ? Files.readAllLines(tsv) : List.of("file\tgroup\tword\tlot\tdescription\tannouncement\tcustomer"));
        Set<String> customers = new HashSet<>();
        LocalDate today = LocalDate.now();
        for (Want w : WANTS) {
            int got = 0;
            Thread.sleep(1000);
            List<FoundLot> lots = gz.searchLots(w.word(), null, w.anyDate() ? null : today.minusDays(90), w.anyDate() ? null : today.plusDays(1), null).items();
            for (FoundLot l : lots) {
                if (got >= w.max() || l.announcement() == null || !customers.add(String.valueOf(l.customerBin()) + w.word())) continue;
                Thread.sleep(1000);
                LotTechSpecRef ref = gz.fetchLotTechSpec(l.trdBuyNumberAnno(), l.nameRu());
                if (ref == null || ref.filePath() == null) continue;
                Thread.sleep(1000);
                byte[] pdf = gz.downloadFile(ref.filePath());
                if (pdf == null) continue;
                String file = (l.trdBuyNumberAnno() + "_" + l.lotNumber()).replaceAll("[^\\p{L}\\p{N}-]", "_") + ".pdf";
                Files.write(dir.resolve(file), pdf);
                rows.add(String.join("\t", file, w.group(), w.word(), clean(l.nameRu()), clean(l.descriptionRu()),
                        clean(l.announcement().nameRu()), clean(l.customerNameRu())));
                got++;
            }
        }
        Files.write(tsv, rows);
    }

    private static String clean(String s) { return s == null ? "" : s.replaceAll("[\\t\\r\\n]+", " ").strip(); }
}
```

- [ ] **Step 2: Collect the corpus (живые запросы)**

Run (sandbox off): `PRODUCT_SPEC_CORPUS=1 GOSZAKUP_TOKEN="$(cat ~/.config/ais/goszakup.token)" ./gradlew test --tests '*ProductSpecCorpusTool*'`
Expected: в `specs/` — 30–40 PDF и `corpus.tsv`; добавить строку Астаны вручную (`17779323-1.pdf` уже лежит; второе объявление — `17802320-1_88111123-ОИ3.pdf` скачать тем же инструментом с WANTS из одного элемента или руками). Если какой-то группы не набралось (нет открытых лотов) — расширить окно до 180 дней для неё; нет совсем — записать в леджер.

- [ ] **Step 3: Label `golden.tsv` вручную**

Для каждого файла — прочитать русскую часть ТЗ (распечатать: временный тест `System.out.println(ProductFixtures.spec(file))` или `jshell` с `PdfTextExtractor`) и карточки наших товаров группы (`site-catalog.json`; с описаниями — если есть снимок админ-API, шаг 4). Метка — по смыслу ТЗ, **не по ответу правил** (CLAUDE.md §14: разметка набора — самостоятельно, иначе гейт меряет сам себя):
- `COPIED` — ТЗ воспроизводит состав / формулировки конкретного нашего товара (как Астана: позиции комплекта ФОТЕК слово в слово);
- `NAMED` — в ТЗ или лоте назван наш бренд или модель;
- `TYPE` — наш вид изделия, ТЗ написано своими словами или под другого производителя;
- `NONE` — лот не наш вид (ложное срабатывание группы — тоже входит в гейт).

Формат (`#` — шапка с инструкцией и снимком):

```
# Набор сверки ТЗ «наша продукция» (задача 6 плана 2026-10-19-product-watch). Метки — по смыслу ТЗ, не по выдаче правил.
# Снимок: каталог site-catalog.json — 196 товаров (2026-10-19), набор — N ТЗ. Каталог обновили — прогнать отчёт,
# пересмотреть строки с расхождениями (перекалибровка, а не регрессия); не «чинить» понижением отсечек.
# file	expected	product_prefix	group	lot	announcement
17779323-1.pdf	COPIED	Аппарат для аспирации дыма АСД	Отсасыватели и аспирация дыма	Отсасыватель	Аппарат для аспирации дыма
```

- [ ] **Step 4: (по согласию оператора) Снимок каталога с описаниями**

Если оператор разрешил прочитать каталог админ-API сайта учёткой ais@ (только чтение; пароль — `~/.config/ais/westmed-prod.pass`, не печатать): `WestmedHttpClient.adminCatalog()` → `src/test/resources/product-watch/site-catalog-full.json` (те же поля + `description`, `attributes`). В работе фразы берутся и из описаний — калибровка на снимке без описаний недооценит ложные совпадения. Нет согласия — калибровка только на публичном снимке, в леджере и спеке §19 — оговорка «пересчитать гейт после первой загрузки каталога на проде».

- [ ] **Step 5: Write the gate test (report + cut-offs)**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сверки ТЗ (спека 2026-10-19-product-watch §15.2): ложных ★ — 0, Астана — COPIED, размеченные COPIED — не меньше
 * {@link #COPIED_RECALL_MIN}. Строки REPORT — по каждому ТЗ: ожидание, итог, найденные фразы.
 */
class ProductSpecMatchQualityTest {

    /** Храповик: число найденных размеченных COPIED на наборе; вниз не опускать (перекалибровка — по шапке TSV). */
    static final int COPIED_RECALL_MIN = 1;

    record Row(String file, String expected, String productPrefix, String group, String lot, String announcement) {}

    static List<Row> golden() throws Exception {
        List<Row> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                ProductSpecMatchQualityTest.class.getResourceAsStream("/product-watch/golden.tsv"), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null; ) {
                if (line.isBlank() || line.startsWith("#")) continue;
                String[] c = line.split("\t", -1);
                out.add(new Row(c[0], c[1], c[2], c[3], c[4], c[5]));
            }
        }
        return out;
    }

    @Test
    void gate() throws Exception {
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.groups(), ProductFixtures.catalog());
        int falseExact = 0, copiedFound = 0, copiedLabeled = 0;
        List<String> astana = new ArrayList<>();
        for (Row row : golden()) {
            String spec = ProductFixtures.spec(row.file());
            List<LotMatch> m = ProductMatcher.match(snap, row.announcement(), List.of(new LotInput(1, row.lot(), "", spec)));
            ProductMatchLevel got = m.stream().map(LotMatch::level).reduce(null, ProductMatchLevel::max);
            String product = m.stream().filter(x -> x.productId() != null).map(x -> snap.products().stream()
                    .filter(p -> p.id() == x.productId()).findFirst().map(CatalogProduct::name).orElse("?")).findFirst().orElse("");
            String evidence = m.stream().map(LotMatch::evidence).filter(Objects::nonNull).findFirst().orElse("");
            System.out.println("REPORT\t" + row.file() + "\texpected=" + row.expected() + "\tgot=" + got + "\t" + product + "\t" + evidence);
            boolean exactGot = got != null && got.exact();
            boolean exactExpected = row.expected().equals("COPIED") || row.expected().equals("NAMED");
            if (exactGot && !exactExpected) falseExact++;
            if (row.expected().equals("COPIED")) {
                copiedLabeled++;
                if (got == ProductMatchLevel.COPIED && product.startsWith(row.productPrefix())) copiedFound++;
            }
            if (row.file().startsWith("17779323-1") || row.file().startsWith("17802320-1")) astana.add(got + " " + product);
        }
        System.out.println("REPORT\tИТОГ\tложных ★=" + falseExact + "\tCOPIED найдено " + copiedFound + " из " + copiedLabeled);
        assertThat(falseExact).as("ложные ★").isZero();
        assertThat(astana).isNotEmpty().allSatisfy(x -> assertThat(x).startsWith("COPIED Аппарат для аспирации дыма АСД"));
        assertThat(copiedFound).as("найдено размеченных COPIED").isGreaterThanOrEqualTo(COPIED_RECALL_MIN);
    }
}
```

- [ ] **Step 6: Run the gate, calibrate**

Run: `./gradlew cleanTest test --tests '*ProductSpecMatchQualityTest*' -i | grep REPORT`
Цикл, пока ложных ★ не 0 при наибольшем числе найденных `COPIED`:
1. ложный `COPIED` → какие фразы совпали (`evidence`) → общая для вида изделия (есть в ТЗ другого производителя) — в `generic-phrases.txt` с комментарием «из <файл>»;
2. ложный `NAMED` → какой бренд / код (`evidence`) → код из общего списка без пользы («СП2-01» в чужом ТЗ) — ужесточить `BrandModels.globalCode` (тест в `BrandModelsTest` на этот код); слово группы, которое мешает, — править `ProductFixtures.groups()` и сид **новой миграцией V37** (`UPDATE product_group … WHERE market = 'KZ' AND name = '…' AND updated_at IS NULL`, до деплоя), V36 после применения к nirdb не трогать (контрольная сумма Flyway);
3. пропущен размеченный `COPIED` → почему (фраза разбита отрезками, окно, редкость) → правило менять, только если ложных ★ не прибавилось;
4. `COPY_MIN` / `BY_FAMILY` — перебрать 2…4 × {товар, семейство} совместно, выбрать точку «ложных ★ = 0, найдено COPIED — максимум».
Итог — в `COPIED_RECALL_MIN` (найдено на наборе), в шапку `golden.tsv` (N, дата) и в леджер с таблицей «настройка → ложных ★ / найдено COPIED».

- [ ] **Step 7: Mutation check**

`CatalogPhrases.GENERIC` — пустое множество (не читать файл) → гейт красный (ложные ★), если калибровка добавляла общие фразы; `COPY_MIN = 1` → гейт красный (ложные ★). Нет красного — гейт не различает: дополнить набор отрицательными ТЗ того же вида (электрохирургия других брендов) и повторить. Откат, `./gradlew compileJava`.

- [ ] **Step 8: Commit**

```bash
git add src/test/java/com/vladoose/nir/productwatch/ProductSpecCorpusTool.java src/test/java/com/vladoose/nir/productwatch/ProductSpecMatchQualityTest.java src/test/resources/product-watch/ src/main/resources/product-watch/generic-phrases.txt src/main/java/com/vladoose/nir/service/productwatch/
git commit -m "test(product-watch): гейт сверки ТЗ на наборе настоящих ТЗ — ложных ★ 0, Астана COPIED; общие фразы по калибровке

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 7: Прогоны — расписание и исполнитель, отметки у импорта ЗКО и СК-Фармации, тихий пересчёт

**Files:**
- Create: `src/main/java/com/vladoose/nir/integration/CronSchedules.java`
- Modify: `src/main/java/com/vladoose/nir/integration/TenderAutoImportScheduler.java` (регистрация — через `CronSchedules`)
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductWatchScheduler.java`, `ProductImportMarks.java`, `ProductRematchService.java`
- Modify: `src/main/java/com/vladoose/nir/integration/goszakup/GoszakupImportService.java`, `skpharmacy/SkPharmacyImportService.java` (обновлённые — в `touchedExtIds`), `GoszakupImportScheduler.java`, `skpharmacy/SkPharmacyImportScheduler.java` (отметки в конце прогона), `service/tendernotify/ImportOutageNotifier.java` (заголовок оповещения), `repository/TenderRepository.java` (`findOpenIds`), `src/main/resources/application.yaml`, `.env.example`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductWatchSchedulerTest.java`, `ProductRematchServiceTest.java` (и отметки импорта); прежние `TenderAutoImportSchedulerTest`, `ImportOutageNotifierTest`, `GoszakupImportSchedulerTest` — зелёные

**Interfaces:**
- Consumes: `ProductWatchService.run`, `ProductSpecChecker.run`, `ProductMatchService.evaluate/markNotified`, `SiteCatalogSync.sync`, `ProductCatalog.refresh` (задачи 2–5).
- Produces:
  - `CronSchedules.register(ScheduledTaskRegistrar, String label, String cron, String byDefault, ZoneId, Runnable, Logger) → boolean`, `CronSchedules.zone(String, ZoneId byDefault, Logger) → ZoneId`;
  - `ProductWatchScheduler.Status(boolean enabled, boolean running, String current, Instant lastSearchAt, ImportSummary lastSearch, Instant lastPlansAt, ImportSummary lastPlans, Instant lastCatalogAt, String lastCatalog)`; методы `status()`, `startSearch()`, `startCatalog()`, `startRematch()`, `boolean enabled()`; задача 11 добавит `startPlans()`, задача 8 — уведомление в конце поиска;
  - `ProductImportMarks.afterImport(ImportSummary)`;
  - `ProductRematchService.rematchOpen() → int` (тендеров с отметками);
  - `ImportOutageNotifier.afterRun(String key, String title, ImportSummary)` (прежний `afterRun(platform, sum)` = `afterRun(platform, "Импорт " + platform, sum)`);
  - `TenderRepository.findOpenIds(Market market) → List<Long>` (`ACTIVE`, `PUBLIC_TENDER`).

- [ ] **Step 1: Write the failing tests**

`ProductWatchSchedulerTest` (без контекста Spring, моки Mockito):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class ProductWatchSchedulerTest {

    ProductWatchService search = mock(ProductWatchService.class);
    ProductSpecChecker specs = mock(ProductSpecChecker.class);
    ProductRematchService rematch = mock(ProductRematchService.class);
    SiteCatalogSync catalog = mock(SiteCatalogSync.class);
    ImportOutageNotifier outage = mock(ImportOutageNotifier.class);

    private ProductWatchScheduler scheduler(boolean enabled, String cron, String plans, String catalogCron) {
        return new ProductWatchScheduler(search, specs, rematch, catalog, outage, enabled, cron, plans, catalogCron, "", "10");
    }

    @Test
    void registration_defaultsDashTypo() {
        ScheduledTaskRegistrar r = new ScheduledTaskRegistrar();
        scheduler(true, "", "-", "0 0 6 * *").configureTasks(r);   // поиск — умолчание, планы — выкл, каталог — опечатка
        assertThat(r.getCronTaskList()).hasSize(1);
        ScheduledTaskRegistrar off = new ScheduledTaskRegistrar();
        scheduler(false, "", "", "").configureTasks(off);
        assertThat(off.getCronTaskList()).isEmpty();
    }

    @Test
    void searchRunsInOwnThreadUnderKz_thenSpecs_thenOutage() throws Exception {
        AtomicReference<Market> seen = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        doAnswer(inv -> { seen.set(MarketContext.get()); return null; }).when(search).run(any());
        doAnswer(inv -> { done.countDown(); return null; }).when(outage).afterRun(any(), any(), any());
        ProductWatchScheduler s = scheduler(true, "", "", "");
        s.startSearch();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen.get()).isEqualTo(Market.KZ);
        verify(specs).run(eq(10), any(ImportSummary.class));
        verify(outage).afterRun(eq("product-watch"), eq("Поиск нашей продукции"), any());
    }

    @Test
    void disabled_noRun() {
        ProductWatchScheduler s = scheduler(false, "", "", "");
        assertThat(s.startSearch().enabled()).isFalse();
        verifyNoInteractions(search);
    }

    @Test
    void secondStartWhileQueued_notDuplicated() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(inv -> { release.await(5, TimeUnit.SECONDS); return null; }).when(search).run(any());
        ProductWatchScheduler s = scheduler(true, "", "", "");
        s.startSearch();
        s.startSearch();
        release.countDown();
        Thread.sleep(300);
        verify(search, times(1)).run(any());
    }

}
```

Отметки импорта и пересчёт — `ProductRematchServiceTest` (база, откат, свои группы и каталог — как в задаче 4):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Отметки у импорта ЗКО / СК-Фармации и тихий пересчёт (спека §8). */
@SpringBootTest
@Transactional
class ProductRematchServiceTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired EntityManager em;
    ProductMatchService matchService;
    CatalogSnapshot snap;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender tender(String ext, String lotName) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп медицинских изделий"); t.setPlatform(TenderPlatform.GOSZAKUP);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName);
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        return t;
    }

    @Test
    void importMarks_touchedOnly_sourceImport() {
        Tender ours = tender("PW-IMP-1", "Облучатель бактерицидный настенный");
        tender("PW-IMP-2", "Облучатель бактерицидный передвижной");     // не затронут прогоном
        ImportSummary sum = new ImportSummary();
        sum.getTouchedExtIds().add("PW-IMP-1");
        new ProductImportMarks(matchService, tenders, true).afterImport(sum);
        assertThat(matches.findByTenderId(ours.getId())).extracting(TenderLotProductMatch::getSource).containsExactly(ProductMatchSource.IMPORT);
        assertThat(matches.findByTenderId(tenders.findBySourceExtId("PW-IMP-2").orElseThrow().getId())).isEmpty();
        ImportSummary off = new ImportSummary();
        off.getTouchedExtIds().add("PW-IMP-2");
        new ProductImportMarks(matchService, tenders, false).afterImport(off);
        assertThat(matches.findByTenderId(tenders.findBySourceExtId("PW-IMP-2").orElseThrow().getId())).isEmpty();
    }

    @Test
    void rematch_silentForNew_keepsPendingForEarlierMarks() {
        Tender fresh = tender("PW-RM-1", "Дефибриллятор");                   // группы «Дефибрилляторы» в фикстуре нет → без отметки
        Tender fresh2 = tender("PW-RM-2", "Рециркулятор бактерицидный");
        Tender pending = tender("PW-RM-3", "Облучатель бактерицидный");
        matchService.evaluate(pending.getId(), ProductMatchSource.SEARCH);  // отметили поиском, сообщение ещё не ушло
        int n = new ProductRematchService(tenders, matchService, () -> { }).rematchOpen();
        em.flush(); em.clear();
        assertThat(n).isGreaterThanOrEqualTo(2);
        assertThat(tenders.findById(fresh2.getId()).orElseThrow().getProductNotifiedLevel()).as("молча").isEqualTo(ProductMatchLevel.TYPE);
        assertThat(tenders.findById(pending.getId()).orElseThrow().getProductNotifiedLevel()).as("ждёт сообщения").isNull();
        assertThat(tenders.findById(fresh.getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }
}
```

(Оба сценария — в одном классе `ProductRematchServiceTest`; отдельного `ProductImportMarksTest` нет.)

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests '*ProductWatchSchedulerTest*' --tests '*ProductRematchServiceTest*'`
Expected: FAIL компиляции.

- [ ] **Step 3: Extract `CronSchedules`, refactor `TenderAutoImportScheduler`**

```java
package com.vladoose.nir.integration;

import org.slf4j.Logger;
import org.springframework.scheduling.config.CronTask;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.scheduling.support.CronTrigger;

import java.time.DateTimeException;
import java.time.ZoneId;

/**
 * Расписания в коде, а не {@code @Scheduled(cron = "${…}")} (CLAUDE.md §8 «Автозапуск импорта»): пустая переменная —
 * умолчание, «-» — выключено, опечатка — выключено с ERROR в логе; старт бэкенда это не роняет.
 */
public final class CronSchedules {

    private CronSchedules() {}

    /** true — задача зарегистрирована. */
    public static boolean register(ScheduledTaskRegistrar registrar, String label, String cron, String byDefault, ZoneId zone,
                                   Runnable tick, Logger log) {
        String expr = cron == null || cron.isBlank() ? byDefault : cron.trim();
        if ("-".equals(expr)) {
            log.info("{}: выключено расписанием «-»", label);
            return false;
        }
        if (!CronExpression.isValidExpression(expr)) {
            log.error("{}: расписание «{}» не разобрано (cron Spring из 6 полей) — выключено", label, expr);
            return false;
        }
        registrar.addCronTask(new CronTask(tick, new CronTrigger(expr, zone)));
        log.info("{}: «{}», пояс {}", label, expr, zone);
        return true;
    }

    public static ZoneId zone(String s, ZoneId byDefault, Logger log) {
        if (s == null || s.isBlank()) return byDefault;
        try {
            return ZoneId.of(s.trim());
        } catch (DateTimeException e) {
            log.error("Пояс «{}» не разобран — беру {}", s, byDefault);
            return byDefault;
        }
    }
}
```

`TenderAutoImportScheduler.configureTasks`: `ZoneId z = CronSchedules.zone(zone, DEFAULT_ZONE, log); CronSchedules.register(registrar, "Автоимпорт goszakup", goszakupCron, DEFAULT_GOSZAKUP_CRON, z, this::tickGoszakup, log); …` — прежние приватные `register`/`zone` удалить; тексты логов те же по смыслу. Прогнать `TenderAutoImportSchedulerTest` — если тест сверяет текст лога, поправить ожидание на новый (лог — «Автоимпорт goszakup: «…», пояс …»).

- [ ] **Step 4: `ImportOutageNotifier` — заголовок**

```java
    public synchronized void afterRun(String platform, ImportSummary sum) {
        afterRun(platform, "Импорт " + platform, sum);
    }

    /** key — счёт аварии (площадка или «product-watch»), title — «Импорт goszakup» / «Поиск нашей продукции». */
    public synchronized void afterRun(String key, String title, ImportSummary sum) {
        … прежнее тело, где states.computeIfAbsent(key, …), а в down/up — title вместо "Импорт " + platform …
    }
```

`down(title, …)`: «⚠️ » + title + « не работает: N прогона подряд (с …) — ни один тендер не записан»; `up(title, …)`: «✅ » + title + « снова работает: …». `ImportOutageNotifierTest` — без правок (тексты импорта не меняются).

- [ ] **Step 5: Implement `ProductImportMarks`, `ProductRematchService`, touched ext ids at imports**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Отметки «наш товар» у тендеров, созданных и обновлённых импортом ЗКО и СК-Фармации (спека §8): в конце прогона,
 * в его потоке и под рынком KZ, без запросов к площадке. ТЗ таких лотов сверит ближайший поиск по стране.
 */
@Component
public class ProductImportMarks {

    private static final Logger log = LoggerFactory.getLogger(ProductImportMarks.class);

    private final ProductMatchService matches;
    private final TenderRepository tenders;
    private final boolean enabled;

    public ProductImportMarks(ProductMatchService matches, TenderRepository tenders,
                              @Value("${product-watch.enabled:false}") boolean enabled) {
        this.matches = matches;
        this.tenders = tenders;
        this.enabled = enabled;
    }

    public void afterImport(ImportSummary sum) {
        if (!enabled) return;
        for (String ext : sum.getTouchedExtIds()) {
            try {
                tenders.findBySourceExtId(ext).ifPresent(t -> matches.evaluate(t.getId(), ProductMatchSource.IMPORT));
            } catch (RuntimeException e) {
                sum.addSideError("отметки «наш товар» " + ext + ": " + ErrorText.of(e));
                log.warn("Отметки «наш товар» у {}: {}", ext, ErrorText.of(e));
            }
        }
    }
}
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchSource;
import com.vladoose.nir.repository.TenderRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * Пересчёт отметок по тендерам KZ с идущим приёмом (спека §8): после сохранения групп и при старте с включённым блоком.
 * Молча: тендеру, у которого отметок до пересчёта не было, уровень сразу считается сообщённым; тендер с отметкой
 * поиска или импорта, чьё сообщение ещё не ушло, пересчёт не «гасит».
 */
@Service
public class ProductRematchService {

    private final TenderRepository tenders;
    private final ProductMatchService matches;
    private final Runnable refreshCatalog;

    @Autowired
    public ProductRematchService(TenderRepository tenders, ProductMatchService matches, ProductCatalog catalog) {
        this(tenders, matches, catalog::refresh);
    }

    public ProductRematchService(TenderRepository tenders, ProductMatchService matches, Runnable refreshCatalog) {
        this.tenders = tenders;
        this.matches = matches;
        this.refreshCatalog = refreshCatalog;
    }

    public int rematchOpen() {
        refreshCatalog.run();
        int marked = 0;
        for (Long id : tenders.findOpenIds(Market.KZ)) {
            ProductMatchService.Result r = matches.evaluate(id, ProductMatchSource.REMATCH);
            if (r.level() == null) continue;
            marked++;
            if (!r.hadMarks()) matches.markNotified(id, r.level());
        }
        return marked;
    }
}
```

`TenderRepository`:

```java
    @Query("select t.id from Tender t where t.market = :market and t.status = 'ACTIVE' and t.source = com.vladoose.nir.entity.Source.PUBLIC_TENDER")
    List<Long> findOpenIds(@Param("market") Market market);
```

`GoszakupImportService.importOne`: `else { sum.setUpdated(sum.getUpdated() + 1); sum.getTouchedExtIds().add(d.getNumberAnno()); }`; то же в `SkPharmacyImportService` (ветка «обновлено»).

- [ ] **Step 6: Implement `ProductWatchScheduler`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.CronSchedules;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.service.tendernotify.ImportOutageNotifier;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneId;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Прогоны «нашей продукции» (спека 2026-10-19-product-watch §7, §10, §6): один исполнитель `product-watch` — поиск,
 * планы, каталог и пересчёт идут по очереди и не нагружают токен одновременно; рынок KZ — в потоке прогона (§6 CLAUDE.md).
 * Расписания — в коде ({@link CronSchedules}); выключатель PRODUCT_WATCH_ENABLED (по умолчанию выключено).
 */
@Component
public class ProductWatchScheduler implements SchedulingConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchScheduler.class);

    static final String DEFAULT_SEARCH_CRON = "0 30 8-19 * * MON-SAT";
    static final String DEFAULT_PLANS_CRON = "0 30 7 * * MON-SAT";
    static final String DEFAULT_CATALOG_CRON = "0 0 6 * * *";
    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Oral");
    static final int DEFAULT_SPEC_PER_RUN = 10;

    public record Status(boolean enabled, boolean running, String current, Instant lastSearchAt, ImportSummary lastSearch,
                         Instant lastPlansAt, ImportSummary lastPlans, Instant lastCatalogAt, String lastCatalog) {}

    private final ProductWatchService search;
    private final ProductSpecChecker specs;
    private final ProductRematchService rematch;
    private final SiteCatalogSync catalog;
    private final ImportOutageNotifier outage;
    private final boolean enabled;
    private final String searchCron, plansCron, catalogCron, zone;
    private final int specPerRun;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "product-watch");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean searchQueued = new AtomicBoolean(false);
    private final AtomicBoolean catalogQueued = new AtomicBoolean(false);
    private final AtomicBoolean rematchQueued = new AtomicBoolean(false);
    private volatile String current;
    private volatile Instant lastSearchAt, lastPlansAt, lastCatalogAt;
    private volatile ImportSummary lastSearch, lastPlans;
    private volatile String lastCatalog;

    @Autowired
    public ProductWatchScheduler(ProductWatchService search, ProductSpecChecker specs, ProductRematchService rematch,
                                 SiteCatalogSync catalog, ImportOutageNotifier outage,
                                 @Value("${product-watch.enabled:false}") boolean enabled,
                                 @Value("${product-watch.cron:}") String searchCron,
                                 @Value("${product-watch.plans-cron:}") String plansCron,
                                 @Value("${product-watch.catalog-cron:}") String catalogCron,
                                 @Value("${product-watch.zone:}") String zone,
                                 @Value("${product-watch.spec-per-run:}") String specPerRun) {
        this.search = search; this.specs = specs; this.rematch = rematch; this.catalog = catalog; this.outage = outage;
        this.enabled = enabled; this.searchCron = searchCron; this.plansCron = plansCron; this.catalogCron = catalogCron;
        this.zone = zone; this.specPerRun = parseInt(specPerRun, DEFAULT_SPEC_PER_RUN);
    }

    private static int parseInt(String s, int byDefault) {
        try {
            return s == null || s.isBlank() ? byDefault : Math.max(0, Integer.parseInt(s.trim()));
        } catch (NumberFormatException e) {
            return byDefault;
        }
    }

    public boolean enabled() { return enabled; }

    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        if (!enabled) return;
        ZoneId z = CronSchedules.zone(zone, DEFAULT_ZONE, log);
        CronSchedules.register(registrar, "Поиск нашей продукции", searchCron, DEFAULT_SEARCH_CRON, z, this::startSearch, log);
        CronSchedules.register(registrar, "Каталог сайта", catalogCron, DEFAULT_CATALOG_CRON, z, this::startCatalog, log);
        // планы закупок — задача 11: CronSchedules.register(registrar, "Планы закупок", plansCron, DEFAULT_PLANS_CRON, z, this::startPlans, log);
    }

    /** При старте с включённым блоком — молчаливый пересчёт (каталог пуст — сперва загрузка). */
    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!enabled) return;
        startCatalog();
        startRematch();
    }

    public Status status() {
        return new Status(enabled, current != null, current, lastSearchAt, lastSearch, lastPlansAt, lastPlans, lastCatalogAt, lastCatalog);
    }

    public Status startSearch() {
        if (!enabled || !searchQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastSearch = sum;
        executor.execute(() -> runTask("поиск по стране", () -> {
            try {
                search.run(sum);
                specs.run(specPerRun, sum);
            } finally {
                lastSearchAt = Instant.now();
                searchQueued.set(false);
                report(sum);
            }
        }));
        return status();
    }

    public Status startCatalog() {
        if (!enabled || !catalogQueued.compareAndSet(false, true)) return status();
        executor.execute(() -> runTask("каталог сайта", () -> {
            try {
                SiteCatalogSync.SyncResult r = catalog.sync();
                lastCatalog = "товаров " + r.count() + " (новых " + r.added() + ", изменено " + r.updated() + ", пропало " + r.removed() + ")"
                        + (r.full() ? "" : " — без описаний (публичный список)");
            } catch (RuntimeException e) {
                lastCatalog = "не загрузился: " + ErrorText.of(e);
            } finally {
                lastCatalogAt = Instant.now();
                catalogQueued.set(false);
            }
        }));
        return status();
    }

    public Status startRematch() {
        if (!enabled || !rematchQueued.compareAndSet(false, true)) return status();
        executor.execute(() -> runTask("пересчёт отметок", () -> {
            try {
                int n = rematch.rematchOpen();
                log.info("Наша продукция: пересчёт — тендеров с отметками {}", n);
            } finally {
                rematchQueued.set(false);
            }
        }));
        return status();
    }

    /** Рынок — в потоке прогона; Error не тонет в Future (execute, а не submit). */
    private void runTask(String name, Runnable body) {
        MarketContext.set(Market.KZ);
        current = name;
        try {
            body.run();
        } catch (Throwable e) {
            log.error("Наша продукция: {} — прерван: {}", name, ErrorText.of(e));
        } finally {
            current = null;
            MarketContext.clear();
        }
    }

    private void report(ImportSummary sum) {
        try {
            outage.afterRun("product-watch", "Поиск нашей продукции", sum);
        } catch (Throwable e) {
            log.warn("Наша продукция: оповещение о сбое упало: {}", ErrorText.of(e));
        }
    }
}
```

`application.yaml` (раздел верхнего уровня, рядом с `tenders:`):

```yaml
# Наша продукция в тендерах (спека 2026-10-19-product-watch): выключено по умолчанию; пустые cron — умолчания в Java
product-watch:
  enabled: ${PRODUCT_WATCH_ENABLED:false}
  cron: ${PRODUCT_WATCH_CRON:}
  plans-cron: ${PRODUCT_WATCH_PLANS_CRON:}
  catalog-cron: ${PRODUCT_WATCH_CATALOG_CRON:}
  zone: ${PRODUCT_WATCH_ZONE:}
  spec-per-run: ${PRODUCT_WATCH_SPEC_PER_RUN:}
```

`.env.example` — блок с этими переменными и комментарием «PRODUCT_WATCH_ENABLED пустым не оставлять (Invalid boolean value [])».

Импорт ЗКО и СК-Фармации — `ProductImportMarks` в конце прогона, ДО уведомления о новых тендерах (задача 8 добавит строку «наш товар» в него): в `GoszakupImportScheduler` и `SkPharmacyImportScheduler` новый параметр конструктора `ProductImportMarks marks` и в `finally` первым — `markProducts(sum)` (обёртка с `try/catch Throwable` → `log.warn`, как `notifyNewTenders`).

- [ ] **Step 7: Run tests**

Run: `./gradlew test --tests '*ProductWatchSchedulerTest*' --tests '*ProductRematchServiceTest*' --tests '*TenderAutoImportSchedulerTest*' --tests '*ImportOutageNotifierTest*' --tests '*GoszakupImportSchedulerTest*' --tests '*SkPharmacyImportSchedulerTest*'`
Expected: PASS.

- [ ] **Step 8: Mutation check**

1. `ProductRematchService.rematchOpen`: `markNotified` всегда (без `!r.hadMarks()`) → `rematch_…_keepsPendingForEarlierMarks` красный.
2. `ProductImportMarks.afterImport`: без проверки `enabled` → тест красный (вторая половина).
3. `ProductWatchScheduler.runTask`: без `MarketContext.set(KZ)` → `searchRunsInOwnThreadUnderKz…` красный (`seen` = RF).
4. `startSearch` без `searchQueued` → `secondStartWhileQueued_notDuplicated` красный.
Откат, `./gradlew compileJava`.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/com/vladoose/nir/integration src/main/java/com/vladoose/nir/service src/main/java/com/vladoose/nir/repository/TenderRepository.java src/main/resources/application.yaml .env.example src/test/java/com/vladoose/nir/productwatch
git commit -m "feat(product-watch): прогоны в своём исполнителе по расписанию, отметки у импорта ЗКО и СК-Фармации, тихий пересчёт

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Уведомления — «Наш товар в тендерах», строка в «Новом тендере», звук у ★

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductMatchQuery.java`, `ProductWatchComposer.java`, `ProductWatchNotifier.java`
- Modify: `src/main/java/com/vladoose/nir/integration/telegram/TelegramClient.java` (`sendTenders(text, silent)`), `service/tendernotify/NewTenderNotifier.java`, `NewTenderComposer.java` (строка «наш товар», звук), `repository/TenderLotProductMatchRepository.java` (`findTendersToNotify`), `ProductWatchScheduler.java` (уведомление в конце поиска), `GoszakupImportScheduler.java`, `SkPharmacyImportScheduler.java` (уведомление нашей продукции после «Нового тендера»)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductWatchComposerTest.java`, `ProductWatchNotifierTest.java`; дополнить `NewTenderNotifierTest`, `NewTenderComposerTest`

**Interfaces:**
- Consumes: отметки (задачи 1, 4), `ProductMatchService.markNotified` (задача 4).
- Produces:
  - `ProductMatchQuery.summaries(Collection<Long> tenderIds) → Map<Long, Summary>`; `record LotLine(long lotId, String lotName, Integer qty, ProductMatchLevel level, String groupName, String word, String evidence, String productName, String productSlug, List<SimilarProductRef> similar)`; `record Summary(long tenderId, ProductMatchLevel level, String label, List<String> groups, List<LotLine> lots)`; `static String telegramLine(Summary)`;
  - `ProductWatchComposer.compose(List<Card>, String publicUrl) → Message(String text, boolean sound)` (null — нечего); `record Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer, String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level, boolean upgrade, List<ProductMatchQuery.LotLine> lots)`;
  - `ProductWatchNotifier.afterRun(ImportSummary)`;
  - `TelegramClient.sendTenders(String text, boolean silent)`;
  - `TenderLotProductMatchRepository.findTendersToNotify() → List<Long>`.

- [ ] **Step 1: Write the failing tests**

`ProductWatchComposerTest` (чистая функция):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.entity.SimilarProductRef;
import com.vladoose.nir.service.productwatch.ProductMatchQuery;
import com.vladoose.nir.service.productwatch.ProductWatchComposer;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProductWatchComposerTest {

    static ProductMatchQuery.LotLine line(String name, ProductMatchLevel level, String evidence, String product, List<String> similar) {
        List<SimilarProductRef> sim = similar.stream().map(s -> new SimilarProductRef(1, s, "Аксион", "s", 0.9)).toList();
        return new ProductMatchQuery.LotLine(1, name, 1, level, "Отсасыватели", "отсасыватель", evidence, product, "asd", sim);
    }

    static ProductWatchComposer.Card astana(ProductMatchLevel level, boolean upgrade) {
        return new ProductWatchComposer.Card(42, "17802320-1", "goszakup", "Из одного источника по несостоявшимся",
                "Аппарат для аспирации дыма", "ГКП на ПХВ «Городская многопрофильная больница № 2»", "г. Астана",
                new BigDecimal("3714285.71"), LocalDate.of(2026, 10, 20), LocalDateTime.of(2026, 10, 20, 9, 0),
                level, upgrade, List.of(line("Отсасыватель", level, level == ProductMatchLevel.COPIED
                        ? "совпало 5 фраз: блок аспирации; держатель шланга аспирационного напольный" : null,
                        "Аппарат для аспирации дыма АСД-\"ФОТЕК\"", List.of("ОМХ-5/80–01 «Аксион»"))));
    }

    @Test
    void exactFirstWithSound_typeAfter_links() {
        ProductWatchComposer.Card type = new ProductWatchComposer.Card(43, "17578819-1", "goszakup", null,
                "Государственная закупка изделий медицинского назначения", "РГП «Казаэронавигация»", "г. Астана",
                new BigDecimal("30800000"), LocalDate.of(2026, 10, 20), LocalDateTime.of(2026, 10, 20, 9, 0),
                ProductMatchLevel.TYPE, false, List.of(line("Электрокардиограф", ProductMatchLevel.TYPE, null, null, List.of("ЭК12Т-01"))));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(List.of(type, astana(ProductMatchLevel.COPIED, false)), "https://ais.westmed.kz");
        assertThat(m.sound()).isTrue();
        assertThat(m.text()).startsWith("🏷 Наш товар в тендерах: 2");
        assertThat(m.text().indexOf("★ Точно наше")).isLessThan(m.text().indexOf("🏷 Наш вид изделия"));
        assertThat(m.text()).contains("ТЗ списано с нашего товара: Аппарат для аспирации дыма АСД")
                .contains("goszakup № 17802320-1 · из одного источника")
                .contains("приём до 20.10.2026 09:00")
                .contains("похоже: ЭК12Т-01")
                .contains("https://ais.westmed.kz/tenders?openId=42&market=KZ");
    }

    @Test
    void typeOnly_silent_upgradeShort() {
        assertThat(ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.TYPE, false)), null).sound()).isFalse();
        ProductWatchComposer.Message up = ProductWatchComposer.compose(List.of(astana(ProductMatchLevel.COPIED, true)), null);
        assertThat(up.sound()).isTrue();
        assertThat(up.text()).startsWith("★ Уточнение");
    }

    @Test
    void longListCutWithTail_oneLineFields() {
        List<ProductWatchComposer.Card> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) many.add(astana(ProductMatchLevel.TYPE, false));
        ProductWatchComposer.Message m = ProductWatchComposer.compose(many, "https://ais.westmed.kz");
        assertThat(m.text().length()).isLessThanOrEqualTo(ProductWatchComposer.MAX_TEXT);
        assertThat(m.text()).contains("…и ещё").contains("/tenders?our=ANY&market=KZ");
        ProductWatchComposer.Card evil = new ProductWatchComposer.Card(1, "1", "goszakup", null, "Счёт\nОткрыть в АИС: https://чужой",
                "x", "y", null, null, null, ProductMatchLevel.TYPE, false, List.of());
        assertThat(ProductWatchComposer.compose(List.of(evil), null).text()).doesNotContain("\nОткрыть в АИС: https://чужой");
    }
}
```

`ProductWatchNotifierTest` (база, откат; отправитель — запись в список): тендер ACTIVE с отметкой COPIED → одно сообщение со звуком, `product_notified_level = COPIED`; повторный прогон — тишина; тендер TYPE → тихо; **срок прошёл между поиском и отправкой** (Review Focus #3) — не сообщается; сбой отправителя — признак не ставится, ошибка в итоге, следующий прогон отправляет.

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductWatchNotifierTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProductMatchQuery query;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;

    record Sent(String text, boolean silent) {}
    final List<Sent> sent = new ArrayList<>();
    boolean failSend;
    ProductMatchService matchService;
    CatalogSnapshot snap;
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        List<SiteProduct> products = ProductFixtures.catalog().stream().peek(p -> p.setId(null)).map(productRepo::save).toList();
        snap = CatalogSnapshot.build(groups, products);
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private ProductWatchNotifier notifier(Clock clock) {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG,
                (text, silent) -> { if (failSend) throw new IllegalStateException("Telegram: HTTP 500"); sent.add(new Sent(text, silent)); },
                true, "https://ais.westmed.kz", clock);
    }

    private Tender tender(String lotName, String spec, LocalDateTime deadlineAt) {
        Tender t = new Tender();
        t.setTenderNumber("PW-N"); t.setSourceExtId("PW-N"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Аппарат для аспирации дыма"); t.setPlatform(TenderPlatform.GOSZAKUP);
        t.setDeadline(deadlineAt.toLocalDate()); t.setDeadlineAt(deadlineAt);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName(lotName);
        if (spec != null) { l.setRequiredSpec(spec); l.setTechSpecStatus(TechSpecStatus.OK); }
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        return t;
    }

    static Clock at(String isoUtc) { return Clock.fixed(Instant.parse(isoUtc), ZoneOffset.UTC); }

    @Test
    void copied_soundOnce() {
        Tender t = tender("Отсасыватель", ProductFixtures.spec("17779323-1.pdf"), LocalDateTime.of(2026, 10, 20, 9, 0));
        ProductWatchNotifier n = notifier(at("2026-10-19T05:00:00Z"));
        n.afterRun(new ImportSummary());
        n.afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isFalse();
        em.flush(); em.clear();
        assertThat(tenders.findById(t.getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.COPIED);
    }

    @Test
    void typeOnly_silent() {
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        notifier(at("2026-10-19T05:00:00Z")).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(sent.get(0).silent()).isTrue();
    }

    @Test
    void deadlinePassedBeforeSend_notNotified() {   // Review Focus #3
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 19, 8, 45));
        notifier(at("2026-10-19T03:50:00Z")).afterRun(new ImportSummary());   // 08:50 по Уральску
        assertThat(sent).isEmpty();
    }

    @Test
    void sendFails_retryNextRun() {
        tender("Отсасыватель", null, LocalDateTime.of(2026, 10, 20, 9, 0));
        failSend = true;
        ImportSummary sum = new ImportSummary();
        notifier(at("2026-10-19T05:00:00Z")).afterRun(sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        failSend = false;
        notifier(at("2026-10-19T05:30:00Z")).afterRun(new ImportSummary());
        assertThat(sent).hasSize(1);
    }
}
```

(Конструктор `TelegramSettings` — 6 аргументов `(enabled, apiUrl, botToken, chatId, mailThreadId, tendersThreadId)`, сверено с классом.)

В `NewTenderComposerTest` — карта с полем `ourProduct = "★ ТЗ списано с нашего товара: …"` печатает строку после лотов; в `NewTenderNotifierTest` — тендер ЗКО с отметкой COPIED: сообщение «🏥 Новый тендер» со звуком, строка «★ …», после отправки `product_notified_level = COPIED`; затем `ProductWatchNotifier.afterRun` — тишина.

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests '*ProductWatchComposerTest*' --tests '*ProductWatchNotifierTest*'`
Expected: FAIL компиляции.

- [ ] **Step 3: Implement `TelegramClient.sendTenders(text, silent)`**

```java
    /** Сообщение в тему тендеров; silent — без звука (так всё в теме, кроме «★ точно наше», спека 2026-10-19-product-watch §9). */
    public long sendTenders(String text, boolean silent) {
        return send(settings.tendersThreadId(), "TELEGRAM_TENDERS_THREAD_ID", text, silent);
    }

    public long sendTenders(String text) {
        return sendTenders(text, true);
    }
```

- [ ] **Step 4: Implement `ProductMatchQuery`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** Сводки отметок «наш товар» для списка «Тендеры», карточки и уведомлений (спека §9, §12). Без N+1: одним заходом. */
@Service
public class ProductMatchQuery {

    public record LotLine(long lotId, String lotName, Integer qty, ProductMatchLevel level, String groupName, String word,
                          String evidence, String productName, String productSlug, List<SimilarProductRef> similar) {}

    public record Summary(long tenderId, ProductMatchLevel level, String label, List<String> groups, List<LotLine> lots) {}

    private final TenderLotProductMatchRepository matches;
    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final TenderLotRepository lots;

    public ProductMatchQuery(TenderLotProductMatchRepository matches, ProductGroupRepository groups, SiteProductRepository products,
                             TenderLotRepository lots) {
        this.matches = matches; this.groups = groups; this.products = products; this.lots = lots;
    }

    @Transactional(readOnly = true)
    public Map<Long, Summary> summaries(Collection<Long> tenderIds) {
        if (tenderIds.isEmpty()) return Map.of();
        List<TenderLotProductMatch> rows = matches.findByTenderIdIn(tenderIds);
        if (rows.isEmpty()) return Map.of();
        Map<Long, String> groupNames = groups.findAllById(rows.stream().map(TenderLotProductMatch::getGroupId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(ProductGroup::getId, ProductGroup::getName));
        Set<Long> productIds = rows.stream().map(TenderLotProductMatch::getProductId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, SiteProduct> prods = products.findAllById(productIds).stream().collect(Collectors.toMap(SiteProduct::getId, p -> p));
        Map<Long, TenderLot> lotById = lots.findAllById(rows.stream().map(TenderLotProductMatch::getLotId).collect(Collectors.toSet()))
                .stream().collect(Collectors.toMap(TenderLot::getId, l -> l));
        Map<Long, List<TenderLotProductMatch>> byTender = rows.stream().collect(Collectors.groupingBy(TenderLotProductMatch::getTenderId));
        Map<Long, Summary> out = new HashMap<>();
        byTender.forEach((tid, list) -> {
            List<LotLine> lines = list.stream()
                    .sorted(Comparator.comparing((TenderLotProductMatch m) -> -m.getLevel().ordinal()).thenComparing(TenderLotProductMatch::getLotId))
                    .map(m -> {
                        TenderLot l = lotById.get(m.getLotId());
                        SiteProduct p = m.getProductId() == null ? null : prods.get(m.getProductId());
                        return new LotLine(m.getLotId(), l == null ? null : l.getEquipName(), l == null ? null : l.getQuantity(), m.getLevel(),
                                groupNames.get(m.getGroupId()), m.getWord(), m.getEvidence(), p == null ? null : p.getName(),
                                p == null ? null : p.getSlug(), m.getSimilar());
                    }).toList();
            ProductMatchLevel level = lines.stream().map(LotLine::level).reduce(null, ProductMatchLevel::max);
            List<String> gs = lines.stream().map(LotLine::groupName).filter(Objects::nonNull).distinct().toList();
            out.put(tid, new Summary(tid, level, label(level, lines), gs, lines));
        });
        return out;
    }

    /** Подпись чипа: «★ наш товар: Фотек» / «★ наш товар: АСД-ФОТЕК» / «🏷 наш вид: отсасыватель». */
    static String label(ProductMatchLevel level, List<LotLine> lines) {
        LotLine top = lines.get(0);
        if (level != null && level.exact()) {
            String what = top.productName() != null ? shortName(top.productName())
                    : top.evidence() != null && top.evidence().contains(": ") ? top.evidence().substring(top.evidence().indexOf(": ") + 2) : top.word();
            return "★ наш товар: " + what;
        }
        return "🏷 наш вид: " + top.word();
    }

    /** Строка для «🏥 Новый тендер» (спека §9). */
    public static String telegramLine(Summary s) {
        LotLine top = s.lots().get(0);
        return switch (s.level()) {
            case COPIED -> "★ ТЗ списано с нашего товара: " + shortName(Objects.toString(top.productName(), top.word()));
            case NAMED -> "★ Назван наш товар: " + (top.evidence() == null ? top.word() : top.evidence());
            case TYPE -> "🏷 Наш вид: " + top.word() + similarTail(top);
        };
    }

    static String similarTail(LotLine l) {
        if (l.similar() == null || l.similar().isEmpty()) return "";
        return " (похоже: " + l.similar().stream().limit(2).map(x -> shortName(x.name())).collect(Collectors.joining(", ")) + ")";
    }

    /** Название товара без хвоста «. Набор с …» — для строки и чипа. */
    public static String shortName(String name) {
        if (name == null) return "";
        int dot = name.indexOf(". ");
        String s = dot > 0 ? name.substring(0, dot) : name;
        return s.length() <= 80 ? s : s.substring(0, 79) + "…";
    }
}
```

- [ ] **Step 5: Implement `ProductWatchComposer`**

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.util.DocFormat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Текст «Наш товар в тендерах» (спека 2026-10-19-product-watch §9): одно сообщение на прогон, сверху «★ Точно наше»,
 * ниже «🏷 Наш вид изделия»; уточнение 🏷 → ★ — короткое «★ Уточнение». Со звуком, если есть ★. Лоты — только наши.
 * Поля — одной строкой ({@link TelegramText#oneLine}): их пишет площадка.
 */
public final class ProductWatchComposer {

    public static final int MAX_TEXT = 3500;
    private static final int SHOWN_LOTS = 3;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public record Card(long tenderId, String number, String platformLabel, String tradeMethod, String subject, String customer,
                       String region, BigDecimal total, LocalDate deadline, LocalDateTime deadlineAt, ProductMatchLevel level,
                       boolean upgrade, List<ProductMatchQuery.LotLine> lots) {}

    public record Message(String text, boolean sound) {}

    private ProductWatchComposer() {}

    public static Message compose(List<Card> cards, String publicUrl) {
        if (cards.isEmpty()) return null;
        String base = publicUrl == null || publicUrl.isBlank() ? null : publicUrl.trim().replaceAll("/+$", "");
        List<Card> exact = cards.stream().filter(c -> !c.upgrade() && c.level().exact()).toList();
        List<Card> upgrades = cards.stream().filter(Card::upgrade).toList();
        List<Card> type = cards.stream().filter(c -> !c.upgrade() && !c.level().exact()).toList();
        boolean sound = cards.stream().anyMatch(c -> c.level().exact());
        int fresh = exact.size() + type.size();
        StringBuilder out = new StringBuilder(fresh == 0
                ? "★ Уточнение: ТЗ под наш товар" + (upgrades.size() > 1 ? " — " + upgrades.size() : "")
                : "🏷 Наш товар в тендерах: " + fresh);
        String tail = tail(base);
        int shown = section(out, fresh == 0 ? null : "★ Точно наше", exact, base, tail)
                + section(out, fresh == 0 ? null : "★ Уточнение", upgrades, base, tail)
                + section(out, "🏷 Наш вид изделия", type, base, tail);
        if (shown < cards.size()) out.append("\n\n…и ещё ").append(cards.size() - shown).append(tail);
        return new Message(out.toString(), sound);
    }

    /** Раздел: заголовок (null — без заголовка: сообщение из одних уточнений) и карточки, пока помещаются с хвостом. */
    private static int section(StringBuilder out, String head, List<Card> cards, String base, String tail) {
        if (cards.isEmpty()) return 0;
        String h = head == null ? "" : "\n\n" + head;
        if (out.length() + h.length() + tail.length() + 20 > MAX_TEXT) return 0;
        out.append(h);
        int n = 0;
        for (Card c : cards) {
            String b = "\n" + block(c, base);
            if (out.length() + b.length() + tail.length() + 20 > MAX_TEXT) break;
            out.append(b);
            n++;
        }
        return n;
    }

    private static String tail(String base) {
        return " — фильтр «Наш товар» в АИС" + (base == null ? "" : ": " + base + "/tenders?our=ANY&market=KZ");
    }

    private static String block(Card c, String base) {
        StringBuilder b = new StringBuilder("▸ ").append(blank(TelegramText.oneLine(c.subject(), 200), "Без названия"));
        String who = join(TelegramText.oneLine(c.customer(), 150), TelegramText.oneLine(c.region(), 60));
        if (!who.isEmpty()) b.append('\n').append(who);
        List<String> parts = new ArrayList<>();
        for (ProductMatchQuery.LotLine l : c.lots().subList(0, Math.min(SHOWN_LOTS, c.lots().size()))) {
            String lot = blank(TelegramText.oneLine(l.lotName(), 80), "лот") + (l.qty() == null ? "" : " — " + l.qty() + " шт.");
            String what = switch (l.level()) {
                case COPIED -> "ТЗ списано с нашего товара: " + TelegramText.oneLine(ProductMatchQuery.shortName(l.productName()), 100)
                        + countTail(l.evidence());
                case NAMED -> "назван наш товар: " + TelegramText.oneLine(evidenceTail(l.evidence()), 100);
                case TYPE -> l.similar() == null || l.similar().isEmpty() ? "" : "похоже: " + l.similar().stream().limit(2)
                        .map(x -> TelegramText.oneLine(ProductMatchQuery.shortName(x.name()), 60)
                                + (x.brand() == null ? "" : " (" + TelegramText.oneLine(x.brand(), 30) + ")"))
                        .collect(Collectors.joining(", "));
            };
            parts.add(what.isEmpty() ? lot : lot + " · " + what);
        }
        if (!parts.isEmpty()) b.append('\n').append(String.join("\n", parts));
        if (c.lots().size() > SHOWN_LOTS) b.append("\nи ещё ").append(c.lots().size() - SHOWN_LOTS).append(" наших лотов");
        String money = c.total() == null ? "" : "Сумма: " + DocFormat.money(c.total()) + " ₸";
        String deadline = c.deadlineAt() != null ? c.deadlineAt().format(DATE_TIME) : c.deadline() != null ? c.deadline().format(DATE) : null;
        String until = deadline == null ? "" : (money.isEmpty() ? "Приём до " : "приём до ") + deadline;
        String sum = join(money, until);
        if (!sum.isEmpty()) b.append('\n').append(sum);
        b.append('\n').append(TelegramText.oneLine(c.platformLabel(), 40)).append(" № ").append(blank(TelegramText.oneLine(c.number(), 60), "—"));
        String method = method(c.tradeMethod());
        if (method != null) b.append(" · ").append(method);
        if (base != null) b.append("\nОткрыть в АИС: ").append(base).append("/tenders?openId=").append(c.tenderId()).append("&market=KZ");
        return b.toString();
    }

    /** Способ — если не обычный ЗЦП: «из одного источника», «конкурс», «аукцион», «электронный магазин». */
    static String method(String m) {
        if (m == null || m.isBlank()) return null;
        String low = m.toLowerCase(Locale.ROOT);
        if (low.contains("одного источника")) return "из одного источника";
        if (low.contains("конкурс")) return "конкурс";
        if (low.contains("аукцион")) return "аукцион";
        if (low.contains("магазин")) return "электронный магазин";
        return null;
    }

    private static String countTail(String evidence) {
        if (evidence == null) return "";
        int i = evidence.indexOf("совпало ");
        if (i < 0) return "";
        int colon = evidence.indexOf(':', i);
        return " (" + evidence.substring(i, colon < 0 ? evidence.length() : colon) + ")";
    }

    private static String evidenceTail(String evidence) {
        if (evidence == null) return "";
        int i = evidence.indexOf(": ");
        return i < 0 ? evidence : evidence.substring(i + 2);
    }

    private static String join(String a, String b) {
        if (a == null || a.isEmpty()) return b == null ? "" : b;
        return b == null || b.isEmpty() ? a : a + " · " + b;
    }

    private static String blank(String s, String fallback) { return s == null || s.isBlank() ? fallback : s; }
}
```

- [ ] **Step 6: Implement `ProductWatchNotifier` and the repository query**

`TenderLotProductMatchRepository`:

```java
    /** Тендеры KZ с идущим приёмом, у которых уровень отметки выше уже сообщённого (спека §9). */
    @Query(value = """
            SELECT m.tender_id FROM tender_lot_product_match m JOIN tender t ON t.id = m.tender_id
            WHERE t.market = 'KZ' AND t.status = 'ACTIVE'
            GROUP BY m.tender_id, t.product_notified_level
            HAVING MAX(CASE m.level WHEN 'COPIED' THEN 3 WHEN 'NAMED' THEN 2 ELSE 1 END)
                 > COALESCE(CASE t.product_notified_level WHEN 'COPIED' THEN 3 WHEN 'NAMED' THEN 2 WHEN 'TYPE' THEN 1 END, 0)
            """, nativeQuery = true)
    List<Long> findTendersToNotify();
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.*;

/**
 * «Наш товар в тендерах» в Telegram (спека 2026-10-19-product-watch §9): зовётся в конце поиска по стране и импортов
 * ЗКО / СК-Фармации (после «🏥 Новый тендер»). Кого — тендеры с отметкой выше сообщённой, приём ещё идёт. Чтение —
 * короткой транзакцией, отправка — вне её; не ушло — признак не ставится, следующий прогон повторит.
 */
@Component
public class ProductWatchNotifier {

    private static final Logger log = LoggerFactory.getLogger(ProductWatchNotifier.class);

    /** Отправка готового текста; в работе — тема тендеров Bot API. */
    public interface Sender {
        void send(String text, boolean silent);
    }

    private final TenderLotProductMatchRepository matches;
    private final TenderRepository tenders;
    private final ProductMatchQuery query;
    private final ProductMatchService matchService;
    private final TransactionTemplate readTx;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final String publicUrl;
    private final Clock clock;

    @Autowired
    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                TelegramClient client, @Value("${product-watch.enabled:false}") boolean enabled,
                                @Value("${ais.public-url:}") String publicUrl) {
        this(matches, tenders, query, matchService, tm, telegram, client::sendTenders, enabled, publicUrl, Clock.systemUTC());
    }

    public ProductWatchNotifier(TenderLotProductMatchRepository matches, TenderRepository tenders, ProductMatchQuery query,
                                ProductMatchService matchService, PlatformTransactionManager tm, TelegramSettings telegram,
                                Sender sender, boolean enabled, String publicUrl, Clock clock) {
        this.matches = matches; this.tenders = tenders; this.query = query; this.matchService = matchService;
        this.readTx = new TransactionTemplate(tm);
        this.readTx.setReadOnly(true);
        this.telegram = telegram; this.sender = sender; this.enabled = enabled; this.publicUrl = publicUrl; this.clock = clock;
    }

    public synchronized void afterRun(ImportSummary sum) {
        if (!enabled || !telegram.isConfigured()) return;
        List<ProductWatchComposer.Card> cards;
        try {
            cards = readTx.execute(st -> prepare());
        } catch (RuntimeException e) {
            sum.addSideError("уведомление «наш товар» не собрано: " + ErrorText.of(e));
            return;
        }
        if (cards == null || cards.isEmpty()) return;
        ProductWatchComposer.Message m = ProductWatchComposer.compose(cards, publicUrl);
        try {
            sender.send(m.text(), !m.sound());
        } catch (RuntimeException e) {
            log.warn("Telegram: «наш товар» не ушло ({}), повтор — со следующим прогоном: {}", cards.size(), ErrorText.of(e));
            sum.addSideError("уведомление «наш товар» не ушло (повтор — со следующим прогоном): " + ErrorText.of(e));
            return;
        }
        for (ProductWatchComposer.Card c : cards) matchService.markNotified(c.tenderId(), c.level());
        log.info("Telegram: «наш товар» отправлено ({}){}", cards.size(), m.sound() ? " со звуком" : "");
    }

    private List<ProductWatchComposer.Card> prepare() {
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        List<Long> ids = matches.findTendersToNotify();
        Map<Long, ProductMatchQuery.Summary> sums = query.summaries(ids);
        List<ProductWatchComposer.Card> out = new ArrayList<>();
        for (Tender t : tenders.findAllById(ids)) {
            if (!"ACTIVE".equals(t.getStatus()) || ImportDeadline.passed(t.getDeadline(), t.getDeadlineAt(), now)) continue;
            ProductMatchQuery.Summary s = sums.get(t.getId());
            if (s == null || s.level() == null) continue;
            boolean upgrade = t.getProductNotifiedLevel() != null;
            List<ProductMatchQuery.LotLine> lots = upgrade ? s.lots().stream().filter(l -> l.level().exact()).toList() : s.lots();
            out.add(new ProductWatchComposer.Card(t.getId(), t.getTenderNumber(),
                    t.getPlatform() == TenderPlatform.SK_PHARMACY ? "СК-Фармация" : "goszakup", t.getPurchaseType(),
                    t.getDescription(), t.getCustomerName(), t.getRegion(), t.getTotalCost(), t.getDeadline(), t.getDeadlineAt(),
                    s.level(), upgrade, lots));
        }
        out.sort(Comparator.comparing((ProductWatchComposer.Card c) -> -c.level().ordinal())
                .thenComparing(c -> c.deadlineAt() == null ? LocalDateTime.MAX : c.deadlineAt()));
        return out;
    }
}
```

- [ ] **Step 7: Wire into runs and «🏥 Новый тендер»**

`ProductWatchScheduler` — новый параметр конструктора `ProductWatchNotifier notifier` (сразу после `ImportOutageNotifier outage`); в `startSearch` после `specs.run(...)` — `notifier.afterRun(sum)` (в `try`, сбой → `log.warn`). В тесте планировщика добавить мок `ProductWatchNotifier` и `verify(notifier).afterRun(any())`.

`GoszakupImportScheduler` / `SkPharmacyImportScheduler` — параметр `ProductWatchNotifier productNotifier`; в `finally`: `markProducts(sum)` → `notifyNewTenders(sum)` → `notifyProducts(sum)` (обёртка с `try/catch Throwable`) → `reportOutage(sum)`.

`NewTenderNotifier`:
- `Sender` — `void send(String text, boolean silent)`; боевой — `client::sendTenders`;
- конструктор получает `ProductMatchQuery query` и `ProductMatchService matchService` (тестовый конструктор — тоже);
- в `prepare` — `Map<Long, ProductMatchQuery.Summary> ours = query.summaries(id тендеров карточек)`; в `Card` — новое поле `String ourProduct` = `ProductMatchQuery.telegramLine(s)` и `ProductMatchLevel ourLevel`;
- отправка — `sender.send(text, !anyExact)`; после успешной отправки — `matchService.markNotified(id, level)` у карточек с `ourLevel != null`.

`NewTenderComposer.Card` — поля `String ourProduct` в конце записи; в `block` после строки «Лоты (…)»: `if (c.ourProduct() != null) b.append('\n').append(TelegramText.oneLine(c.ourProduct(), 300));`. Прежние вызовы `new Card(...)` в тестах — добавить `null`.

- [ ] **Step 8: Run tests**

Run: `./gradlew test --tests '*ProductWatchComposerTest*' --tests '*ProductWatchNotifierTest*' --tests '*NewTenderNotifierTest*' --tests '*NewTenderComposerTest*' --tests '*ProductWatchSchedulerTest*' --tests '*GoszakupImportSchedulerTest*'`
Expected: PASS.

- [ ] **Step 9: Mutation check**

1. `ProductWatchNotifier.prepare`: без проверки срока → `deadlinePassedBeforeSend_notNotified` красный.
2. `afterRun`: `markNotified` до отправки → `sendFails_retryNextRun` красный.
3. `ProductWatchComposer.compose`: `sound` всегда false → `exactFirstWithSound…` красный.
4. `NewTenderNotifier`: без `markNotified` у карточек с отметкой → тест «ЗКО + наш товар — второе сообщение не уходит» красный.
5. `findTendersToNotify`: `>=` вместо `>` → `copied_soundOnce` красный (повтор).
Откат, `./gradlew compileJava`.

- [ ] **Step 10: Commit**

```bash
git add -A src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir
git commit -m "feat(product-watch): уведомления «Наш товар в тендерах» — ★ со звуком, строка в «Новом тендере» без повтора

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 9: Страница «Наша продукция» — группы, «Проверить», каталог, поиск по стране

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/ProductGroupDto.java`, `ProductGroupsResponse.java`, `ProbeResponse.java`; `dto/request/ProductGroupsRequest.java`
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProductGroupService.java`, `src/main/java/com/vladoose/nir/controller/ProductWatchController.java`
- Modify: `GroupRule.java` (`rejectReason`)
- Create (front): `frontend/src/app/shared/product-watch.ts`, `frontend/src/app/pages/product-groups/product-groups.component.ts`
- Modify (front): `services/api.service.ts`, `app.routes.ts`, `app.config.ts` (иконка `tag`), `layout/layout.component.ts` (пункт в группе «Тендеры», только KZ)
- Test: `src/test/java/com/vladoose/nir/productwatch/ProductGroupServiceTest.java`

**Interfaces:**
- Consumes: `ProductGroupRepository`, `SiteProductRepository` (задача 1), `GroupRule`, `ProductCatalog` (задача 3), `GoszakupSearchClient`, `ProductWatchService.OPEN_LOT` (задача 4), `ProductWatchScheduler` (задача 7).
- Produces:
  - `record ProductGroupDto(Long id, String name, List<String> searchWords, List<String> requireWords, List<String> excludeWords, List<String> brandWords, boolean active, boolean brandGroup)`;
  - `record ProductGroupsRequest(List<ProductGroupDto> groups)`;
  - `record ProductGroupsResponse(boolean kz, List<ProductGroupDto> groups, Catalog catalog, ProductWatchScheduler.Status watch)` + `record Catalog(int count, Map<String, Integer> byBrand, OffsetDateTime syncedAt, boolean stale)`;
  - `record ProbeResponse(int found, int passed, List<Lot> lots)` + `record Lot(String numberAnno, String lotName, String announcement, String customer, String deadlineAt, boolean passed, String reason)`;
  - `GroupRule.rejectReason(String lotText, String announcement, boolean singleLot) → String` (null — прошёл);
  - `ProductGroupService.get()`, `save(ProductGroupsRequest, String author)`, `probe(ProductGroupDto)`;
  - пути: `GET /api/product-groups`, `PUT /api/product-groups` (ADMIN), `POST /api/product-groups/probe` (ADMIN), `POST /api/product-watch/run` (ADMIN), `GET /api/product-watch/status`, `POST /api/site-catalog/sync` (ADMIN);
  - фронт: `ApiService.getProductGroups()`, `saveProductGroups(groups)`, `probeProductGroup(group)`, `runProductWatch()`, `syncSiteCatalog()`, `getProductWatchStatus()`; типы `ProductGroup`, `ProductGroupsState`, `ProbeResult`, `WatchStatus`.

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.*;

import static org.assertj.core.api.Assertions.*;

/** Страница «Наша продукция» (спека §12.1): весь список одним PUT, проверка до записи, «Проверить». */
@SpringBootTest
@Transactional
class ProductGroupServiceTest {

    @Autowired ProductGroupRepository groups;
    @Autowired SiteProductRepository products;
    @Autowired ProductCatalog catalog;
    @Autowired EntityManager em;
    FakeGoszakupSearchClient search;
    ProductGroupService service;
    final List<String> rematches = new ArrayList<>();

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groups.deleteAllInBatch();
        em.flush();
        search = new FakeGoszakupSearchClient();
        service = new ProductGroupService(groups, products, catalog, search, () -> rematches.add("x"), () -> null, CLOCK);
    }

    @AfterEach void clear() { MarketContext.clear(); catalog.invalidate(); }

    static ProductGroupDto dto(Long id, String name, List<String> words) {
        return new ProductGroupDto(id, name, words, List.of(), List.of(), List.of(), true, false);
    }

    @Test
    void save_insertUpdateDeleteOrder_thenRematchQueued() {
        ProductGroupsResponse r1 = service.save(new ProductGroupsRequest(List.of(dto(null, "Электрокардиографы", List.of("электрокардиограф")),
                dto(null, "Облучатели", List.of("облучатель", "рециркулятор")))), "vlad");
        assertThat(r1.groups()).extracting(ProductGroupDto::name).containsExactly("Электрокардиографы", "Облучатели");
        Long ecg = r1.groups().get(0).id();
        ProductGroupsResponse r2 = service.save(new ProductGroupsRequest(List.of(dto(null, "Дозаторы", List.of("дозатор")),
                dto(ecg, "ЭКГ", List.of("электрокардиограф")))), "vlad");
        assertThat(r2.groups()).extracting(ProductGroupDto::name).containsExactly("Дозаторы", "ЭКГ");
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.KZ)).extracting(ProductGroup::getUpdatedBy).containsOnly("vlad");
        assertThat(rematches).hasSize(2);
    }

    @Test
    void invalidGroups_400_nothingSaved() {   // Review Focus #4
        service.save(new ProductGroupsRequest(List.of(dto(null, "ЭКГ", List.of("электрокардиограф")))), "vlad");
        for (ProductGroupDto bad : List.of(dto(null, "Пустая", List.of()), dto(null, "Пробел", List.of("монитор пациента")),
                dto(null, "Повтор", List.of("дозатор", "Дозатор")), dto(null, " ", List.of("дозатор")))) {
            assertThatThrownBy(() -> service.save(new ProductGroupsRequest(List.of(bad)), "vlad"))
                    .isInstanceOf(BadRequestException.class).hasMessageContaining("группа № 1");
        }
        assertThat(groups.findByMarketOrderByPositionAscIdAsc(Market.KZ)).extracting(ProductGroup::getName).containsExactly("ЭКГ");
    }

    @Test
    void rf_readEmpty_write400() {
        MarketContext.set(Market.RF);
        assertThat(service.get().kz()).isFalse();
        assertThatThrownBy(() -> service.save(new ProductGroupsRequest(List.of()), "vlad")).hasMessageContaining("Только для Казахстана");
    }

    @Test
    void probe_reasons() {
        FoundAnnouncement a = new FoundAnnouncement(1L, "1-1", "Закуп", "1", "Больница", BigDecimal.TEN, "2026-10-18 10:00:00",
                "2026-10-21 10:00:00", 220, 2, null, 3);
        FoundAnnouncement old = new FoundAnnouncement(2L, "2-1", "Закуп", "1", "Больница", BigDecimal.TEN, "2026-10-01 10:00:00",
                "2026-10-05 10:00:00", 220, 2, null, 3);
        search.lotsByWord.put("электрод", List.of(
                new FoundLot(1L, "1", "Электрод нейтральный возвратный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "1-1", a),
                new FoundLot(2L, "2", "Электрод сварочный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "1-1", a),
                new FoundLot(3L, "3", "Электрод нейтральный", "", BigDecimal.ONE, BigDecimal.TEN, 220, null, "1", "Больница", "2-1", old)));
        ProbeResponse r = service.probe(new ProductGroupDto(null, "Электроды", List.of("электрод"), List.of("нейтральн"),
                List.of("сварочн"), List.of(), true, false));
        assertThat(r.found()).isEqualTo(2);
        assertThat(r.passed()).isEqualTo(1);
        assertThat(r.lots()).extracting(ProbeResponse.Lot::reason).containsExactly("прошёл — по слову «электрод»", "исключение «сварочн»");
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*ProductGroupServiceTest*'`
Expected: FAIL компиляции.

- [ ] **Step 3: `GroupRule.rejectReason`**

```java
    /** Почему лот не прошёл группу (для «Проверить», спека §12.1); null — прошёл. Тексты — нормализованные. */
    public String rejectReason(String lotText, String announcement, boolean singleLot) {
        if (typeWord(lotText, announcement, singleLot) != null) return null;
        String hit = firstStem(lotText);
        if (hit == null && singleLot) hit = firstStem(announcement);
        if (hit == null) return "нет слова поиска в лоте";
        String scope = singleLot ? lotText + " | " + announcement : lotText;
        if (!require.isEmpty() && !MedicalGoodsVocabulary.matchesAny(scope, require)) return "нет уточнения (" + String.join(", ", require) + ")";
        for (String e : exclude) if (MedicalGoodsVocabulary.matchesAny(lotText, List.of(e))) return "исключение «" + e + "»";
        return "не прошёл";
    }
```

- [ ] **Step 4: DTOs, service, controller**

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Группа «нашей продукции» на странице (спека §12.1). */
public record ProductGroupDto(Long id, String name, List<String> searchWords, List<String> requireWords, List<String> excludeWords,
                              List<String> brandWords, boolean active, boolean brandGroup) {}
```

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.dto.response.ProductGroupDto;

import java.util.List;

/** Весь список групп рынка по порядку. */
public record ProductGroupsRequest(List<ProductGroupDto> groups) {}
```

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.service.productwatch.ProductWatchScheduler;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

public record ProductGroupsResponse(boolean kz, List<ProductGroupDto> groups, Catalog catalog, ProductWatchScheduler.Status watch) {
    /** Каталог сайта: число товаров, по брендам, когда обновлён, устарел ли (старше двух суток). */
    public record Catalog(int count, Map<String, Integer> byBrand, OffsetDateTime syncedAt, boolean stale) {}
}
```

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** «Проверить» группу: сколько нашла площадка с идущим приёмом, сколько прошло, причины. */
public record ProbeResponse(int found, int passed, List<Lot> lots) {
    public record Lot(String numberAnno, String lotName, String announcement, String customer, String deadlineAt, boolean passed, String reason) {}
}
```

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.repository.ProductGroupRepository;
import com.vladoose.nir.repository.SiteProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Страница «Наша продукция» (спека 2026-10-19-product-watch §12.1): весь список групп одним запросом — сначала проверка
 * всего ввода, потом запись; после сохранения — снимок правил и пересчёт отметок в фоне (исполнитель product-watch).
 * «Проверить» — что площадка находит по словам группы прямо сейчас и что прошло уточнения и исключения.
 */
@Service
public class ProductGroupService {

    static final int NAME_MAX = 120, WORDS_MAX = 10, WORD_MAX = 40, LIST_MAX = 30, GROUPS_MAX = 60, PROBE_SHOWN = 50;

    private final ProductGroupRepository groups;
    private final SiteProductRepository products;
    private final ProductCatalog catalog;
    private final GoszakupSearchClient search;
    private final Runnable rematch;
    private final Supplier<ProductWatchScheduler.Status> watch;
    private final Clock clock;

    @Autowired
    public ProductGroupService(ProductGroupRepository groups, SiteProductRepository products, ProductCatalog catalog,
                               GoszakupSearchClient search, ProductWatchScheduler scheduler) {
        this(groups, products, catalog, search, scheduler::startRematch, scheduler::status, Clock.systemUTC());
    }

    public ProductGroupService(ProductGroupRepository groups, SiteProductRepository products, ProductCatalog catalog,
                               GoszakupSearchClient search, Runnable rematch, Supplier<ProductWatchScheduler.Status> watch, Clock clock) {
        this.groups = groups; this.products = products; this.catalog = catalog; this.search = search;
        this.rematch = rematch; this.watch = watch; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public ProductGroupsResponse get() {
        if (MarketContext.get() != Market.KZ) return new ProductGroupsResponse(false, List.of(), null, watch.get());
        List<ProductGroupDto> list = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ).stream().map(ProductGroupService::dto).toList();
        List<SiteProduct> live = products.findByMarketAndRemovedAtIsNull(Market.KZ);
        Map<String, Integer> byBrand = new TreeMap<>();
        for (SiteProduct p : live) byBrand.merge(p.getBrand() == null ? "—" : p.getBrand(), 1, Integer::sum);
        OffsetDateTime synced = live.stream().map(SiteProduct::getSyncedAt).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        boolean stale = synced == null || synced.isBefore(OffsetDateTime.now(clock).minusDays(2));
        return new ProductGroupsResponse(true, list, new ProductGroupsResponse.Catalog(live.size(), byBrand, synced, stale), watch.get());
    }

    @Transactional
    public ProductGroupsResponse save(ProductGroupsRequest req, String author) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        List<ProductGroupDto> items = req == null || req.groups() == null ? List.of() : req.groups();
        Map<Long, ProductGroup> byId = groups.findByMarketOrderByPositionAscIdAsc(Market.KZ).stream()
                .collect(Collectors.toMap(ProductGroup::getId, g -> g));
        check(items, byId);
        Set<Long> keep = items.stream().map(ProductGroupDto::id).filter(Objects::nonNull).collect(Collectors.toSet());
        for (ProductGroup g : byId.values()) if (!keep.contains(g.getId())) groups.delete(g);
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (int i = 0; i < items.size(); i++) {
            ProductGroupDto d = items.get(i);
            ProductGroup g = d.id() == null ? ProductGroup.builder().market(Market.KZ).build() : byId.get(d.id());
            g.setName(d.name().strip());
            g.setSearchWords(clean(d.searchWords()));
            g.setRequireWords(clean(d.requireWords()));
            g.setExcludeWords(clean(d.excludeWords()));
            g.setBrandWords(clean(d.brandWords()));
            g.setActive(d.active());
            g.setPosition(i + 1);
            g.setUpdatedAt(now);
            g.setUpdatedBy(author);
            groups.save(g);
        }
        groups.flush();
        catalog.refresh();
        rematch.run();
        return get();
    }

    /** Весь ввод — до первой записи; текст — с номером группы. */
    private static void check(List<ProductGroupDto> items, Map<Long, ProductGroup> byId) {
        if (items.size() > GROUPS_MAX) throw new BadRequestException("Групп не больше " + GROUPS_MAX);
        for (int i = 0; i < items.size(); i++) {
            ProductGroupDto d = items.get(i);
            String where = "группа № " + (i + 1);
            String name = d.name() == null ? "" : d.name().strip();
            if (name.isEmpty()) throw new BadRequestException("Название пустое — " + where);
            if (name.length() > NAME_MAX) throw new BadRequestException("Название длиннее " + NAME_MAX + " символов — " + where);
            if (d.id() != null && !byId.containsKey(d.id())) throw new BadRequestException("Группа не найдена — " + where);
            List<String> words = clean(d.searchWords());
            if (words.isEmpty()) throw new BadRequestException("Нет слов поиска — " + where + " «" + name + "»");
            if (words.size() > WORDS_MAX) throw new BadRequestException("Слов поиска не больше " + WORDS_MAX + " — " + where);
            Set<String> seen = new HashSet<>();
            for (String w : words) {
                if (w.contains(" ")) throw new BadRequestException("Слово поиска — одно слово без пробелов: «" + w + "» — " + where);
                if (w.length() > WORD_MAX) throw new BadRequestException("Слово длиннее " + WORD_MAX + " символов — " + where);
                if (!seen.add(w.toLowerCase(Locale.ROOT))) throw new BadRequestException("Слово «" + w + "» повторяется — " + where);
            }
            for (List<String> list : List.of(clean(d.requireWords()), clean(d.excludeWords()), clean(d.brandWords()))) {
                if (list.size() > LIST_MAX) throw new BadRequestException("Слов в списке не больше " + LIST_MAX + " — " + where);
                for (String w : list) if (w.length() > WORD_MAX) throw new BadRequestException("Слово длиннее " + WORD_MAX + " символов — " + where);
            }
        }
    }

    /** «Проверить» (спека §12.1): лоты с идущим приёмом по словам группы (одна страница на слово) и причины. */
    public ProbeResponse probe(ProductGroupDto d) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (!search.isConfigured()) throw new BadRequestException("Токен goszakup не настроен — проверять нечем");
        check(List.of(d), Map.of());
        ProductGroup g = ProductGroup.builder().id(0L).market(Market.KZ).name(d.name()).searchWords(clean(d.searchWords()))
                .requireWords(clean(d.requireWords())).excludeWords(clean(d.excludeWords())).brandWords(clean(d.brandWords()))
                .active(true).brandGroup(d.brandGroup()).build();
        GroupRule rule = GroupRule.of(g);
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), Market.KZ.zone());
        LocalDate today = now.toLocalDate();
        Map<Long, FoundLot> lots = new LinkedHashMap<>();
        for (String w : rule.searchWords()) {
            for (FoundLot l : search.searchLots(w, ProductWatchService.OPEN_LOT, today.minusDays(60), today.plusDays(1), null).items()) {
                LocalDateTime at = l.announcement() == null ? null : ImportDeadline.parse(l.announcement().endDate());
                if (at != null && at.isAfter(now) && l.id() != null) lots.putIfAbsent(l.id(), l);
            }
        }
        List<ProbeResponse.Lot> out = new ArrayList<>();
        int passed = 0;
        for (FoundLot l : lots.values()) {
            String lotText = ProductText.normalize(String.join(" ", Objects.toString(l.nameRu(), ""), Objects.toString(l.descriptionRu(), "")));
            String ann = ProductText.normalize(l.announcement().nameRu());
            String why = rule.brandGroup() ? null : rule.rejectReason(lotText, ann, false);
            if (why == null) passed++;
            String word = rule.brandGroup() ? "" : rule.typeWord(lotText, ann, false);
            out.add(new ProbeResponse.Lot(l.trdBuyNumberAnno(), l.nameRu(), l.announcement().nameRu(), l.customerNameRu(),
                    l.announcement().endDate(), why == null, why == null ? "прошёл — по слову «" + word + "»" : why));
        }
        out.sort(Comparator.comparing((ProbeResponse.Lot x) -> !x.passed()));
        return new ProbeResponse(lots.size(), passed, out.stream().limit(PROBE_SHOWN).toList());
    }

    private static List<String> clean(List<String> xs) {
        return xs == null ? new ArrayList<>() : xs.stream().filter(Objects::nonNull).map(String::strip).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private static ProductGroupDto dto(ProductGroup g) {
        return new ProductGroupDto(g.getId(), g.getName(), g.getSearchWords(), g.getRequireWords(), g.getExcludeWords(),
                g.getBrandWords(), g.isActive(), g.isBrandGroup());
    }
}
```

Сохранение не меняет `brandGroup` (признак сида; новые группы — обычные). Тест `probe_reasons` ждёт «прошёл — по слову «электрод»» для лота с объявлением «Закуп» — для мульти-лотового режима (`single = false`) это верно.

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.service.productwatch.ProductGroupService;
import com.vladoose.nir.service.productwatch.ProductWatchScheduler;
import com.vladoose.nir.util.CurrentUser;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** «Наша продукция» (спека 2026-10-19-product-watch §12.1, §13): группы, «Проверить», поиск по стране, каталог сайта. */
@RestController
public class ProductWatchController {

    private final ProductGroupService groups;
    private final ProductWatchScheduler scheduler;

    public ProductWatchController(ProductGroupService groups, ProductWatchScheduler scheduler) {
        this.groups = groups;
        this.scheduler = scheduler;
    }

    @GetMapping("/api/product-groups")
    public ProductGroupsResponse get() { return groups.get(); }

    @PutMapping("/api/product-groups")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductGroupsResponse save(@RequestBody ProductGroupsRequest req) { return groups.save(req, CurrentUser.name()); }

    @PostMapping("/api/product-groups/probe")
    @PreAuthorize("hasRole('ADMIN')")
    public ProbeResponse probe(@RequestBody ProductGroupDto group) { return groups.probe(group); }

    @PostMapping("/api/product-watch/run")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status run() { return scheduler.startSearch(); }

    @GetMapping("/api/product-watch/status")
    public ProductWatchScheduler.Status status() { return scheduler.status(); }

    @PostMapping("/api/site-catalog/sync")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status syncCatalog() { return scheduler.startCatalog(); }
}
```

Кнопки «Искать сейчас» и «Обновить» каталог работают только при включённом блоке (`PRODUCT_WATCH_ENABLED`) — иначе статус `enabled: false` и страница пишет «выключено».

- [ ] **Step 5: Run backend tests**

Run: `./gradlew test --tests '*ProductGroupServiceTest*'`
Expected: PASS.

- [ ] **Step 6: Frontend — types and API**

`frontend/src/app/shared/product-watch.ts`:

```ts
/** «Наша продукция в тендерах» (спека 2026-10-19-product-watch): типы и подписи экрана. */
export type MatchLevel = 'TYPE' | 'NAMED' | 'COPIED';

export interface ProductGroup {
  id: number | null; name: string; searchWords: string[]; requireWords: string[]; excludeWords: string[];
  brandWords: string[]; active: boolean; brandGroup: boolean;
}

export interface ImportSummaryLite {
  enabled: boolean; fetched: number; matched: number; created: number; updated: number; errors: number;
  message: string | null; lastError: string | null;
}

export interface WatchStatus {
  enabled: boolean; running: boolean; current: string | null;
  lastSearchAt: string | null; lastSearch: ImportSummaryLite | null;
  lastPlansAt: string | null; lastPlans: ImportSummaryLite | null;
  lastCatalogAt: string | null; lastCatalog: string | null;
}

export interface ProductGroupsState {
  kz: boolean; groups: ProductGroup[];
  catalog: { count: number; byBrand: Record<string, number>; syncedAt: string | null; stale: boolean } | null;
  watch: WatchStatus;
}

export interface ProbeLot { numberAnno: string; lotName: string; announcement: string; customer: string; deadlineAt: string; passed: boolean; reason: string; }
export interface ProbeResult { found: number; passed: number; lots: ProbeLot[]; }

/** Слова через запятую ↔ список (страница правит списки строкой). */
export function wordsText(xs: string[] | null | undefined): string { return (xs || []).join(', '); }
export function wordsList(s: string): string[] { return (s || '').split(',').map(x => x.trim()).filter(x => !!x); }
```

`ApiService` (импорт `import type { ProductGroup, ProductGroupsState, ProbeResult, WatchStatus } from '../shared/product-watch';`):

```ts
  // === Наша продукция в тендерах (спека 2026-10-19-product-watch) ===
  getProductGroups(): Observable<ProductGroupsState> { return this.http.get<ProductGroupsState>(`${this.base}/product-groups`); }
  saveProductGroups(groups: ProductGroup[]): Observable<ProductGroupsState> {
    return this.http.put<ProductGroupsState>(`${this.base}/product-groups`, { groups });
  }
  probeProductGroup(group: ProductGroup): Observable<ProbeResult> { return this.http.post<ProbeResult>(`${this.base}/product-groups/probe`, group); }
  runProductWatch(): Observable<WatchStatus> { return this.http.post<WatchStatus>(`${this.base}/product-watch/run`, {}); }
  getProductWatchStatus(): Observable<WatchStatus> { return this.http.get<WatchStatus>(`${this.base}/product-watch/status`); }
  syncSiteCatalog(): Observable<WatchStatus> { return this.http.post<WatchStatus>(`${this.base}/site-catalog/sync`, {}); }
```

- [ ] **Step 7: Frontend — page**

`frontend/src/app/pages/product-groups/product-groups.component.ts`:

```ts
import { ChangeDetectorRef, Component, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf, DatePipe } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { ProbeResult, ProductGroup, ProductGroupsState, WatchStatus, wordsList, wordsText } from '../../shared/product-watch';

interface Row { g: ProductGroup; words: string; require: string; exclude: string; brands: string; probe?: ProbeResult | null; probing?: boolean; }

/** «Наша продукция» (спека 2026-10-19-product-watch §12.1): группы видов изделий, «Проверить», поиск по стране, каталог. */
@Component({
  selector: 'app-product-groups',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, DatePipe],
  template: `
    <div class="pg-page">
      <h2>Наша продукция</h2>
      <p class="subtitle">Что АИС ищет в тендерах по всей стране: виды изделий из нашего каталога, их слова поиска, уточнения и исключения.
        Найденное — в «Тендерах» с отметкой «🏷 наш вид» или «★ точно наше» и в теме «Тендеры» в Telegram.</p>
      <p class="pg-note" *ngIf="loading">Загружаю…</p>
      <p class="pg-note" *ngIf="state && !state.kz">Только для Казахстана — переключите рынок на West-Med (KZ).</p>
      <ng-container *ngIf="state?.kz">
        <div class="pg-status" [class.pg-off]="!state!.watch.enabled">
          <span *ngIf="!state!.watch.enabled">Поиск по стране выключен (PRODUCT_WATCH_ENABLED, DEPLOY.md).</span>
          <span *ngIf="state!.watch.enabled">Поиск по стране: включён<ng-container *ngIf="state!.watch.lastSearchAt"> · {{ state!.watch.lastSearchAt | date:'dd.MM HH:mm' }} —
            {{ state!.watch.lastSearch?.message }}</ng-container><ng-container *ngIf="state!.watch.current"> · идёт: {{ state!.watch.current }}</ng-container></span>
          <button type="button" class="btn btn-line btn-sm" *ngIf="admin && state!.watch.enabled" [disabled]="!!state!.watch.current" (click)="runNow()">Искать сейчас</button>
        </div>
        <div class="pg-card" *ngFor="let r of rows; let i = index; let last = last" [class.pg-inactive]="!r.g.active">
          <div class="pg-row">
            <input class="pg-name" [(ngModel)]="r.g.name" maxlength="120" [disabled]="!admin" [attr.aria-label]="'Название группы ' + (i + 1)" />
            <label class="pg-toggle"><input type="checkbox" [(ngModel)]="r.g.active" [disabled]="!admin" /> включена</label>
            <div class="pg-tools" *ngIf="admin">
              <button type="button" class="btn btn-line btn-sm" [disabled]="i === 0" (click)="move(i, -1)" aria-label="Выше">↑</button>
              <button type="button" class="btn btn-line btn-sm" [disabled]="last" (click)="move(i, 1)" aria-label="Ниже">↓</button>
              <button type="button" class="btn btn-line btn-sm" (click)="probe(r)" [disabled]="r.probing">{{ r.probing ? 'Проверяю…' : 'Проверить' }}</button>
              <button type="button" class="btn btn-line btn-sm" (click)="remove(i)">Удалить</button>
            </div>
          </div>
          <p class="pg-hint" *ngIf="r.g.brandGroup">Слова поиска — наши бренды и модели: находки сразу «★ назван наш товар».</p>
          <label class="pg-field">Слова поиска <small>— по одному слову, как пишет площадка, через запятую</small>
            <input [(ngModel)]="r.words" [disabled]="!admin" placeholder="электрокардиограф" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Уточнения <small>— хотя бы одно должно быть в лоте (начало слова)</small>
            <input [(ngModel)]="r.require" [disabled]="!admin" placeholder="операцион, хирург" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Исключения <small>— если есть в лоте, лот не наш</small>
            <input [(ngModel)]="r.exclude" [disabled]="!admin" placeholder="уличн, прожектор" /></label>
          <label class="pg-field" *ngIf="!r.g.brandGroup">Наши бренды и модели группы <small>— для «★ назван наш товар»</small>
            <input [(ngModel)]="r.brands" [disabled]="!admin" placeholder="аксион, дки-н" /></label>
          <div class="pg-probe" *ngIf="r.probe">
            <p class="pg-note">Площадка нашла с идущим приёмом: {{ r.probe.found }}, прошло: {{ r.probe.passed }}.</p>
            <div class="pg-plot" *ngFor="let l of r.probe.lots" [class.pg-pass]="l.passed">
              <span class="pg-lot">{{ l.lotName }}</span>
              <span class="pg-meta">{{ l.customer }} · № {{ l.numberAnno }} · до {{ l.deadlineAt?.slice(0, 16) }}</span>
              <span class="pg-why">{{ l.reason }}</span>
            </div>
          </div>
        </div>
        <div class="pg-actions" *ngIf="admin">
          <button type="button" class="btn btn-line" (click)="add()">+ Группа</button>
          <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
        </div>
        <p class="pg-catalog" *ngIf="state!.catalog" [class.pg-stale]="state!.catalog!.stale">
          Каталог сайта: {{ state!.catalog!.count }} товаров ({{ brandsText() }}),
          {{ state!.catalog!.syncedAt ? 'обновлён ' + (state!.catalog!.syncedAt | date:'dd.MM HH:mm') : 'ещё не загружен' }}
          <button type="button" class="btn btn-line btn-sm" *ngIf="admin && state!.watch.enabled" (click)="syncCatalog()">Обновить</button>
          <ng-container *ngIf="state!.watch.lastCatalog"> · {{ state!.watch.lastCatalog }}</ng-container>
        </p>
      </ng-container>
    </div>
  `,
  styles: [`
    .pg-page { max-width: 900px; display: flex; flex-direction: column; gap: 10px; }
    .pg-page h2 { margin: 0; }
    .pg-note, .pg-hint { margin: 0; font-size: 13px; color: var(--text-muted); }
    .pg-status { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--success-text); }
    .pg-status.pg-off { color: var(--warn-text); }
    .pg-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 8px; background: var(--surface); }
    .pg-card.pg-inactive { opacity: 0.6; }
    .pg-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .pg-name { flex: 1; min-width: 180px; padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-weight: 600; }
    .pg-toggle { font-size: 13px; color: var(--text-muted); display: flex; gap: 6px; align-items: center; }
    .pg-tools { display: flex; gap: 6px; flex-wrap: wrap; }
    .pg-field { display: flex; flex-direction: column; gap: 4px; font-size: 13px; color: var(--text); }
    .pg-field small { color: var(--text-muted); font-weight: 400; }
    .pg-field input { padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; }
    .pg-probe { border-top: 1px dashed var(--border); padding-top: 8px; display: flex; flex-direction: column; gap: 6px; }
    .pg-plot { display: grid; grid-template-columns: 1fr auto; gap: 2px 10px; font-size: 13px; padding: 6px 8px; border-radius: 6px;
               background: color-mix(in srgb, var(--danger) 8%, var(--surface)); }
    .pg-plot.pg-pass { background: color-mix(in srgb, var(--success) 8%, var(--surface)); }
    .pg-lot { font-weight: 600; color: var(--text); }
    .pg-meta { grid-column: 1; color: var(--text-muted); }
    .pg-why { grid-column: 2; grid-row: 1 / span 2; align-self: center; color: var(--text); }
    .pg-actions { display: flex; gap: 8px; }
    .pg-catalog { margin: 4px 0 0; font-size: 13px; color: var(--text-muted); display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
    .pg-catalog.pg-stale { color: var(--warn-text); }
    @media (max-width: 900px) {
      .pg-name, .pg-field input { font-size: 16px; }
      .pg-tools { width: 100%; }
      .pg-plot { grid-template-columns: 1fr; }
      .pg-why { grid-column: 1; grid-row: auto; }
    }
  `],
})
export class ProductGroupsComponent implements OnInit, OnDestroy {
  state: ProductGroupsState | null = null;
  rows: Row[] = [];
  loading = true;
  saving = false;
  private poll: ReturnType<typeof setInterval> | null = null;

  constructor(private api: ApiService, private auth: AuthService, private confirm: ConfirmService,
              private notify: NotificationService, private cdr: ChangeDetectorRef) {}

  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    this.load();
    this.poll = setInterval(() => this.refreshStatus(), 5000);
  }

  ngOnDestroy() { if (this.poll) clearInterval(this.poll); }

  private load() {
    this.api.getProductGroups().subscribe({
      next: s => { this.apply(s); this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Группы не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  private apply(s: ProductGroupsState) {
    this.state = s;
    this.rows = s.groups.map(g => ({ g: { ...g }, words: wordsText(g.searchWords), require: wordsText(g.requireWords),
      exclude: wordsText(g.excludeWords), brands: wordsText(g.brandWords) }));
  }

  /** Статус поиска и каталога — без перезагрузки правок групп. */
  private refreshStatus() {
    if (!this.state?.kz) return;
    this.api.getProductWatchStatus().subscribe({ next: w => { if (this.state) { this.state.watch = w; this.cdr.detectChanges(); } }, error: () => {} });
  }

  brandsText(): string {
    const b = this.state?.catalog?.byBrand || {};
    return Object.keys(b).map(k => `${k} ${b[k]}`).join(', ');
  }

  private toGroup(r: Row): ProductGroup {
    return { ...r.g, searchWords: wordsList(r.words), requireWords: wordsList(r.require), excludeWords: wordsList(r.exclude), brandWords: wordsList(r.brands) };
  }

  move(i: number, d: number) { const [x] = this.rows.splice(i, 1); this.rows.splice(i + d, 0, x); }

  add() {
    this.rows.push({ g: { id: null, name: '', searchWords: [], requireWords: [], excludeWords: [], brandWords: [], active: true, brandGroup: false },
      words: '', require: '', exclude: '', brands: '' });
  }

  remove(i: number) {
    this.confirm.ask(`Удалить группу «${this.rows[i].g.name || 'без названия'}»?`, 'Отметки этой группы на тендерах пропадут после сохранения.',
      { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => { if (ok) { this.rows.splice(i, 1); this.cdr.detectChanges(); } });
  }

  probe(r: Row) {
    r.probing = true; r.probe = null;
    this.api.probeProductGroup(this.toGroup(r)).subscribe({
      next: p => { r.probe = p; r.probing = false; this.cdr.detectChanges(); },
      error: e => { r.probing = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  save() {
    this.saving = true;
    this.api.saveProductGroups(this.rows.map(r => this.toGroup(r))).subscribe({
      next: s => { this.apply(s); this.saving = false; this.notify.success('Сохранено. Отметки на открытых тендерах пересчитываются.'); this.cdr.detectChanges(); },
      error: e => { this.saving = false; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  runNow() {
    this.api.runProductWatch().subscribe({
      next: w => { if (this.state) this.state.watch = w; this.notify.success('Поиск по стране запущен'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  syncCatalog() {
    this.api.syncSiteCatalog().subscribe({
      next: w => { if (this.state) this.state.watch = w; this.notify.success('Каталог сайта обновляется'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }
}
```

`app.routes.ts` — рядом с другими ленивыми маршрутами: `{ path: 'product-groups', loadComponent: () => import('./pages/product-groups/product-groups.component').then(m => m.ProductGroupsComponent) },`.

`layout.component.ts` — в группе «Тендеры» после «Поиска тендеров» (задача 11 вставит «Планы закупок» перед ним):

```html
            <a *ngIf="market.value === 'KZ'" routerLink="/product-groups" routerLinkActive="active">
              <svg lucideIcon="tag" [size]="16"></svg> Наша продукция
            </a>
```

`app.config.ts` — `LucideTag` в обоих местах (импорт из `@lucide/angular` и список `provideLucideIcons`).

- [ ] **Step 8: Build and check the page in the browser**

Run: `cd frontend && npm run build` → без ошибок, начальный бандл не вырос больше чем на 1 кБ (страница ленивая).
Живьём (бэкенд локально с `PRODUCT_WATCH_ENABLED=true GOSZAKUP_TOKEN=…`, Playwright, рынок KZ, 1280 и 390, обе темы): 24 группы; «Проверить» у «Светильники» — список с причинами; правка слова с пробелом → тост 400 «Слово поиска — одно слово…», ничего не сохранено; «Сохранить» → тост; оператор — поля только для чтения, без кнопок; РФ — «Только для Казахстана».

- [ ] **Step 9: Mutation check**

`ProductGroupService.check`: без проверки пробела → `invalidGroups_400_nothingSaved` красный; `save` без `rematch.run()` → `save_…_thenRematchQueued` красный. Откат, `./gradlew compileJava`.

- [ ] **Step 10: Commit**

```bash
git add -A src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/productwatch frontend/src/app
git commit -m "feat(product-watch): страница «Наша продукция» — группы, «Проверить», поиск по стране, каталог сайта

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---
### Task 10: «Тендеры» — чипы и фильтр «Наш товар», отметки у лотов, пометка «найден поиском»

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/OurProductSummary.java`, `LotProductMatchDto.java`
- Modify: `dto/response/TenderResponse.java` (`foundByProductSearch`, `ourProduct`), `TenderLotResponse.java` (`productMatches`), `mapper/TenderMapper.java`, `mapper/TenderLotMapper.java` (ignore новых полей), `controller/TenderController.java` (подстановка сводок одним заходом), `service/productwatch/ProductMatchQuery.java` (DTO для API), `integration/westmed/WestmedLeadMapper.java` (`public static productUrl(String siteUrl, String slug)`)
- Modify (front): `shared/product-watch.ts`, `pages/tenders/tenders.component.ts`, `tenders-filters.component.ts`, `tender-lots.component.ts`
- Test: `src/test/java/com/vladoose/nir/productwatch/TenderOurProductApiTest.java`

**Interfaces:**
- Consumes: `ProductMatchQuery.summaries` (задача 8).
- Produces:
  - `record OurProductSummary(String level, String label, List<String> groups)`;
  - `record LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl, List<Similar> similar)` + `record Similar(String name, String brand, String url)`;
  - `ProductMatchQuery.ourProduct(Collection<Long>) → Map<Long, OurProductSummary>`, `lotMatches(long tenderId) → Map<Long, List<LotProductMatchDto>>`;
  - фронт: `TendersFilters.our: '' | 'ANY' | 'EXACT' | string` (`'g:<название группы>'`), параметр адреса `?our=ANY|EXACT`.

- [ ] **Step 1: Write the failing test**

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderController;
import com.vladoose.nir.dto.response.*;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Отметки «наш товар» в API «Тендеров» (спека §12.2–12.3): сводка в списке, отметки лота со ссылкой на товар сайта. */
@SpringBootTest
@Transactional
@WithMockUser(roles = "USER")
class TenderOurProductApiTest {

    @Autowired TenderController controller;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void listSummaryAndLotMatches() {
        ProductGroup g = groupRepo.save(ProductGroup.builder().market(Market.KZ).name("Отсасыватели и аспирация дыма")
                .searchWords(List.of("отсасыватель")).position(99).build());
        SiteProduct p = productRepo.save(SiteProduct.builder().market(Market.KZ).siteId(java.util.UUID.randomUUID())
                .name("Аппарат для аспирации дыма АСД-\"ФОТЕК\". Набор").slug("asd-fotek").brand("Фотек")
                .syncedAt(java.time.OffsetDateTime.now()).build());
        Tender t = new Tender();
        t.setTenderNumber("PW-API"); t.setSourceExtId("PW-API"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setFoundByProductSearch(true);
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель");
        t.getLots().add(l);
        tenders.save(t);
        em.flush();
        matches.save(TenderLotProductMatch.builder().tenderId(t.getId()).lotId(l.getId()).groupId(g.getId()).word("отсасыватель")
                .level(ProductMatchLevel.COPIED).evidence("совпало 5 фраз: блок аспирации").productId(p.getId())
                .source(ProductMatchSource.SEARCH).build());
        em.flush(); em.clear();

        TenderResponse r = controller.findAll().stream().filter(x -> x.getId().equals(t.getId())).findFirst().orElseThrow();
        assertThat(r.isFoundByProductSearch()).isTrue();
        assertThat(r.getOurProduct().level()).isEqualTo("COPIED");
        assertThat(r.getOurProduct().label()).isEqualTo("★ наш товар: Аппарат для аспирации дыма АСД-\"ФОТЕК\"");
        TenderLotResponse lot = controller.getLots(t.getId()).get(0);
        assertThat(lot.getProductMatches()).hasSize(1);
        assertThat(lot.getProductMatches().get(0).productUrl()).endsWith("/product/asd-fotek");
        assertThat(controller.findById(t.getId()).getOurProduct()).isNotNull();
    }
}
```

- [ ] **Step 2: Run to verify it fails**

Run: `./gradlew test --tests '*TenderOurProductApiTest*'`
Expected: FAIL компиляции — нет `getOurProduct`, `getProductMatches`.

- [ ] **Step 3: Implement DTOs, query, controller**

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Сводка «наш товар» тендера для списка: уровень, подпись чипа, группы (фильтр). */
public record OurProductSummary(String level, String label, List<String> groups) {}
```

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Отметка «наш товар» лота для карточки тендера (спека §12.3). */
public record LotProductMatchDto(String groupName, String word, String level, String evidence, String productName, String productUrl,
                                 List<Similar> similar) {
    public record Similar(String name, String brand, String url) {}
}
```

`TenderResponse`: `private boolean foundByProductSearch; private OurProductSummary ourProduct;`. `TenderLotResponse`: `private List<LotProductMatchDto> productMatches = new ArrayList<>();`. `TenderMapper.toResponse` — `@Mapping(target = "ourProduct", ignore = true)`; `TenderLotMapper.toResponse` — `@Mapping(target = "productMatches", ignore = true)`.

`WestmedLeadMapper`: `public static String productUrl(String siteUrl, String slug) { return siteUrl + "/product/" + slug; }` и прежний приватный метод зовёт его.

`ProductMatchQuery` — `@Value("${leads.westmed.site-url:https://westmed.kz}") String siteUrl` в конструкторе (обрезать «/» в конце) и методы:

```java
    @Transactional(readOnly = true)
    public Map<Long, OurProductSummary> ourProduct(Collection<Long> tenderIds) {
        Map<Long, OurProductSummary> out = new HashMap<>();
        summaries(tenderIds).forEach((id, s) -> out.put(id, new OurProductSummary(s.level().name(), s.label(), s.groups())));
        return out;
    }

    @Transactional(readOnly = true)
    public Map<Long, List<LotProductMatchDto>> lotMatches(long tenderId) {
        Summary s = summaries(List.of(tenderId)).get(tenderId);
        if (s == null) return Map.of();
        Map<Long, List<LotProductMatchDto>> out = new HashMap<>();
        for (LotLine l : s.lots()) {
            List<LotProductMatchDto.Similar> sim = l.similar() == null ? List.of() : l.similar().stream()
                    .map(x -> new LotProductMatchDto.Similar(shortName(x.name()), x.brand(), x.slug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, x.slug())))
                    .toList();
            out.computeIfAbsent(l.lotId(), k -> new ArrayList<>()).add(new LotProductMatchDto(l.groupName(), l.word(), l.level().name(),
                    l.evidence(), l.productName() == null ? null : shortName(l.productName()),
                    l.productSlug() == null ? null : WestmedLeadMapper.productUrl(siteUrl, l.productSlug()), sim));
        }
        return out;
    }
```

`TenderController` — зависимость `ProductMatchQuery productQuery`; помощник и правки путей:

```java
    /** Сводки «наш товар» — одним заходом на весь список (без N+1). */
    private List<TenderResponse> withOurProduct(List<TenderResponse> list) {
        Map<Long, OurProductSummary> ours = productQuery.ourProduct(list.stream().map(TenderResponse::getId).toList());
        for (TenderResponse r : list) r.setOurProduct(ours.get(r.getId()));
        return list;
    }
```

`findAll` → `return withOurProduct(mapper.toResponseList(service.findAll()));`; `search` — так же; `findById` → `withOurProduct(List.of(mapper.toResponse(…))).get(0)`; `getLots`:

```java
        List<TenderLotResponse> lots = tenderLotMapper.toResponseList(tenderLotService.findByTenderId(id));
        Map<Long, List<LotProductMatchDto>> m = productQuery.lotMatches(id);
        for (TenderLotResponse l : lots) l.setProductMatches(m.getOrDefault(l.getId(), List.of()));
        return lots;
```

- [ ] **Step 4: Run test**

Run: `./gradlew test --tests '*TenderOurProductApiTest*' --tests '*TenderMarketGuardApiTest*'`
Expected: PASS.

- [ ] **Step 5: Frontend — list chip, filter, URL param**

`shared/product-watch.ts`:

```ts
export interface OurProduct { level: MatchLevel; label: string; groups: string[]; }
export interface LotProductMatch {
  groupName: string; word: string; level: MatchLevel; evidence: string | null; productName: string | null; productUrl: string | null;
  similar: { name: string; brand: string | null; url: string | null }[];
}
export const OUR_LABELS: Record<string, string> = { ANY: 'Любой наш вид', EXACT: '★ Точно наше' };
/** Проходит ли тендер фильтр «Наш товар» ('' — все; 'ANY'; 'EXACT'; 'g:<группа>'). */
export function ourMatches(our: OurProduct | null | undefined, filter: string): boolean {
  if (!filter) return true;
  if (!our) return false;
  if (filter === 'ANY') return true;
  if (filter === 'EXACT') return our.level !== 'TYPE';
  return filter.startsWith('g:') && our.groups.includes(filter.slice(2));
}
```

`tenders-filters.component.ts`: в `TendersFilters` — `our: string`; входы `@Input() ourGroups: string[] = []`; на десктопе после «Регион» (только KZ):

```html
      <select class="f-sel" *ngIf="isKz" [(ngModel)]="filters.our" (change)="emit()" title="Наш товар">
        <option value="">Наш товар: все</option><option value="EXACT">★ Точно наше</option><option value="ANY">Любой наш вид</option>
        <option *ngFor="let g of ourGroups" [value]="'g:' + g">{{ g }}</option>
      </select>
```

в шторке — такой же `<label *ngIf="isKz">Наш товар<select …>`; в `activeChips` — `if (f.our) out.push({ label: f.our.startsWith('g:') ? f.our.slice(2) : (OUR_LABELS[f.our] || f.our), key: 'our' });`.

`tenders.component.ts`:
- поле `filterOur = ''`; в `filtersState` / `onFiltersChange` / `resetTendersFilter` — `our`;
- `ourGroups` — `get ourGroups(): string[]` = уникальные группы из `tenders[].ourProduct?.groups` (отсортировать);
- в `applyTendersFilter` — `if (!ourMatches(t.ourProduct, this.filterOur)) return false;`;
- подписка на адрес (там же, где `openId`): `const our = p.get('our'); if (our && our !== this.filterOur) { this.filterOur = our; this.applyTendersFilter(); }`;
- в шаблон карточки списка — после `stageChip`:

```html
            <span *ngIf="t.ourProduct" class="pw-chip" [class.pw-exact]="t.ourProduct.level !== 'TYPE'" [title]="t.ourProduct.groups.join(', ')">{{ t.ourProduct.label }}</span>
```

- в карточке тендера (инфо-сетка) — первым пунктом при `selectedTender.foundByProductSearch`: `<div class="info-item info-wide"><span class="pw-found">Найден поиском по нашей продукции</span></div>`;
- стили (в компоненте, рядом с `.purchase-type`): `.pw-chip { font-size: 12px; padding: 2px 8px; border-radius: 10px; color: var(--accent); background: color-mix(in srgb, var(--accent) 15%, transparent); white-space: nowrap; max-width: 320px; overflow: hidden; text-overflow: ellipsis; }` `.pw-chip.pw-exact { color: var(--success-text); background: color-mix(in srgb, var(--success) 15%, transparent); font-weight: 600; }` `.pw-found { font-size: 13px; color: var(--success-text); }` — на телефоне `.pw-chip { max-width: 100%; }`.

- [ ] **Step 6: Frontend — lot marks**

`tender-lots.component.ts` — после блока `lot-flags` «снят с площадки»:

```html
          <div class="lot-ours" *ngFor="let m of l.productMatches || []">
            <span class="stage-chip" [class.stage-success]="m.level !== 'TYPE'" [class.stage-accent]="m.level === 'TYPE'">
              {{ m.level === 'TYPE' ? '🏷 ' + m.groupName : '★ ' + (m.level === 'COPIED' ? 'ТЗ списано с нашего' : 'назван наш товар') }}</span>
            <span class="lot-ours-text">
              <ng-container *ngIf="m.productUrl"><a [href]="m.productUrl" target="_blank" rel="noopener" (click)="$event.stopPropagation()">{{ m.productName }} ↗</a></ng-container>
              <ng-container *ngIf="!m.productUrl && m.productName">{{ m.productName }}</ng-container>
              <ng-container *ngIf="m.evidence"> — {{ m.evidence }}</ng-container>
              <ng-container *ngIf="m.level === 'TYPE' && (l.techSpecStatus === 'NO_FILE' || l.techSpecStatus === 'UNREADABLE')"> · ТЗ недоступно</ng-container>
              <ng-container *ngIf="m.level === 'TYPE' && m.similar.length"> похоже:
                <ng-container *ngFor="let s of m.similar; let last = last"><a *ngIf="s.url" [href]="s.url" target="_blank" rel="noopener" (click)="$event.stopPropagation()">{{ s.name }}</a><span *ngIf="!s.url">{{ s.name }}</span><ng-container *ngIf="!last">, </ng-container></ng-container>
              </ng-container>
            </span>
          </div>
```

Стили: `.lot-ours { display: flex; gap: 6px; align-items: baseline; flex-wrap: wrap; margin-top: 4px; font-size: 12px; }` `.lot-ours-text { color: var(--text-muted); min-width: 0; overflow-wrap: anywhere; }` `.lot-ours a { color: var(--accent); }` (классы `stage-success` / `stage-accent` — уже есть в компоненте, строки 189–194).

- [ ] **Step 7: Build and check in the browser**

Run: `cd frontend && npm run build`
Живьём (тендеры с отметками — из прогона поиска задачи 13 или созданные SQL в локальной nirdb и убранные после): чип в списке, фильтр «★ Точно наше» / группа, `/tenders?our=ANY` открывается с фильтром, карточка — «Найден поиском…», у лота — «★ ТЗ списано…» со ссылкой на сайт и «похоже: …»; 1280 / 390, обе темы; РФ — фильтра нет.

- [ ] **Step 8: Mutation check**

`TenderController.findAll` без `withOurProduct` → тест красный; `lotMatches` без `productUrl` → тест красный. Откат, `./gradlew compileJava`.

- [ ] **Step 9: Commit**

```bash
git add -A src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/productwatch frontend/src/app
git commit -m "feat(product-watch): «Тендеры» — чипы и фильтр «Наш товар», отметки у лотов со ссылкой на сайт

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 11: Планы закупок — сбор, «объявлен / снят», сводка раз в день, страница

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/ProcurementPlanService.java`, `ProcurementPlanWriter.java`, `ProcurementPlanDigest.java`, `ProcurementPlanQuery.java`, `src/main/java/com/vladoose/nir/controller/ProcurementPlanController.java`, `src/main/java/com/vladoose/nir/dto/response/PlanPointDto.java`
- Modify: `ProductWatchScheduler.java` (`startPlans`, cron, общий `report`), `repository/ProcurementPlanPointRepository.java` (`findByDigestedAtIsNullAndGoneAtIsNull`), `ProductWatchSchedulerTest.java`
- Create (front): `frontend/src/app/pages/procurement-plans/procurement-plans.component.ts`; Modify: `api.service.ts`, `app.routes.ts`, `app.config.ts` (`calendar-clock`), `layout.component.ts`, `shared/product-watch.ts`
- Test: `src/test/java/com/vladoose/nir/productwatch/ProcurementPlanServiceTest.java`, `ProcurementPlanDigestTest.java`, `ProcurementPlanQueryTest.java`

**Interfaces:**
- Consumes: `GoszakupSearchClient.searchPlans/plansById` (задача 4), `CatalogSnapshot`/`GroupRule`/`BrandModels` (задача 3), `RegionResolver` (есть), `TelegramClient.sendTenders(text, silent)` (задача 8).
- Produces:
  - `ProcurementPlanService.collect(ImportSummary) → boolean firstRun`;
  - `ProcurementPlanWriter.upsert(List<Collected>, OffsetDateTime runStart) → int[] {created, updated}`, `openNotSeenSince(OffsetDateTime) → List<Long> extIds`, `markGone(Map<Long, String> extIdToStatus)`, `isEmpty()`; `record Collected(FoundPlanPoint point, String region, ProductMatchLevel level, Map<Long, String> groupWords)`;
  - `ProcurementPlanDigest.flush(boolean firstRun, ImportSummary)`;
  - `record PlanPointDto(Long id, Long extId, int year, Integer month, boolean monthPassed, String customerName, String region, String name, String description, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount, String tradeMethod, List<String> groups, String level, OffsetDateTime firstSeenAt, String goneStatus)`;
  - пути: `GET /api/procurement-plans?group=&region=&soon=&fresh=&all=&q=&sort=&limit=`, `POST /api/procurement-plans/collect` (ADMIN).

- [ ] **Step 1: Write the failing tests**

`ProcurementPlanServiceTest` (база, откат; фейк поиска; свои группы):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Планы закупок (спека §10): только «Утверждён», услуги — мимо, регион по месту поставки, «объявлен / снят». */
@SpringBootTest
@Transactional
class ProcurementPlanServiceTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired ProcurementPlanMatchRepository planMatches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired ProcurementPlanWriter writer;
    @Autowired RegionResolver regions;
    @Autowired EntityManager em;
    FakeGoszakupSearchClient search;
    ProcurementPlanService service;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> gs = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        CatalogSnapshot snap = CatalogSnapshot.build(gs, List.of());
        search = new FakeGoszakupSearchClient();
        service = new ProcurementPlanService(search, writer, () -> snap, regions, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    static FoundPlanPoint plan(long id, String name, String desc, String unit, String place) {
        return new FoundPlanPoint(id, 2026, 11, "Ноябрь", "990240006109", "ГКП «Айтекебийская районная больница»", name, desc, "",
                new BigDecimal("4"), unit, new BigDecimal("1480000"), new BigDecimal("5920000"), "Электронный магазин", 2, "Утвержден", place);
    }

    @Test
    void collect_onlyOurs_noServices_region_firstRun() {
        search.plansByWord.put("рециркулятор", List.of(
                plan(1, "Рециркулятор", "бактерицидный на 2 лампы", "Штука", "Актюбинская область, Айтекебийский район"),
                plan(2, "Рециркулятор", "ремонт", "Одна услуга", "г.Алматы")));
        search.plansByWord.put("светильник", List.of(plan(3, "Светильник", "уличный светодиодный", "Штука", "г.Алматы")));
        ImportSummary sum = new ImportSummary();
        assertThat(service.collect(sum)).as("первый сбор").isTrue();
        List<ProcurementPlanPoint> all = points.findAll();
        assertThat(all).extracting(ProcurementPlanPoint::getExtId).containsExactly(1L);
        assertThat(all.get(0).getRegion()).isEqualTo("Актюбинская область");
        assertThat(all.get(0).getMonth()).isEqualTo(11);
        assertThat(planMatches.findByPlanPointIdIn(List.of(all.get(0).getId()))).hasSize(1);
        assertThat(service.collect(new ImportSummary())).as("второй — не первый").isFalse();
    }

    @Test
    void vanished_markedGoneWithStatus() {
        search.plansByWord.put("рециркулятор", List.of(plan(1, "Рециркулятор", "бактерицидный", "Штука", "г.Алматы")));
        service.collect(new ImportSummary());
        search.plansByWord.clear();
        search.plansById.put(1L, new FoundPlanPoint(1L, 2026, 11, null, null, null, "Рециркулятор", null, null, null, null, null, null,
                null, 5, "Опубликован", null));
        service.collect(new ImportSummary());
        ProcurementPlanPoint p = points.findByExtId(1L).orElseThrow();
        assertThat(p.getGoneAt()).isNotNull();
        assertThat(p.getGoneStatus()).isEqualTo("Опубликован");
    }
}
```

`ProcurementPlanDigestTest` (база, откат; отправитель — список в памяти):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.service.productwatch.ProcurementPlanDigest;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Сводка планов раз в день (спека §10.3): первый сбор — молча, дальше — одно тихое сообщение о новом, ★ сверху. */
@SpringBootTest
@Transactional
class ProcurementPlanDigestTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired PlatformTransactionManager tm;
    final List<String> sent = new ArrayList<>();
    final List<Boolean> silent = new ArrayList<>();
    ProcurementPlanDigest digest;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T02:30:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        TelegramSettings tg = new TelegramSettings(true, "https://api.telegram.org", "1:x", "-1001", "");
        digest = new ProcurementPlanDigest(points, tm, tg, (text, quiet) -> { sent.add(text); silent.add(quiet); }, true,
                "https://ais.westmed.kz", CLOCK);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProcurementPlanPoint point(long extId, String name, ProductMatchLevel level, String amount) {
        OffsetDateTime t = OffsetDateTime.now(CLOCK);
        return points.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(extId).year(2026).month(11).name(name)
                .customerName("ГКП «Айтекебийская районная больница»").region("Актюбинская область").quantity(new BigDecimal("4"))
                .unit("Штука").amount(new BigDecimal(amount)).level(level).firstSeenAt(t).lastSeenAt(t).build());
    }

    @Test
    void firstRunSilent_thenOneQuietMessageExactFirst() {
        point(1, "Рециркулятор", ProductMatchLevel.TYPE, "5920000");
        digest.flush(true, new ImportSummary());
        assertThat(sent).isEmpty();
        assertThat(points.findByExtId(1L).orElseThrow().getDigestedAt()).isNotNull();

        point(2, "Облучатель-рециркулятор", ProductMatchLevel.TYPE, "9000000");
        point(3, "Электрокардиограф ЭК12Т-01-«Р-Д»", ProductMatchLevel.NAMED, "1200000");
        digest.flush(false, new ImportSummary());
        assertThat(sent).hasSize(1);
        assertThat(silent).containsExactly(true);
        String text = sent.get(0);
        assertThat(text).startsWith("🗓 Новое в планах закупок: 2");
        assertThat(text.indexOf("★ Электрокардиограф")).isLessThan(text.indexOf("Облучатель"));
        assertThat(text).contains("ноябрь 2026").contains("Актюбинская область")
                .contains("https://ais.westmed.kz/procurement-plans?fresh=1&market=KZ");

        digest.flush(false, new ImportSummary());
        assertThat(sent).as("повтор — тишина").hasSize(1);
    }

    @Test
    void sendFails_notDigested_retryNextTime() {
        point(5, "Рециркулятор", ProductMatchLevel.TYPE, "100");
        ProcurementPlanDigest failing = new ProcurementPlanDigest(points, tm,
                new TelegramSettings(true, "https://api.telegram.org", "1:x", "-1001", ""),
                (text, quiet) -> { throw new IllegalStateException("Telegram: HTTP 500"); }, true, "", CLOCK);
        ImportSummary sum = new ImportSummary();
        failing.flush(false, sum);
        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(points.findByExtId(5L).orElseThrow().getDigestedAt()).isNull();
    }

    @Test
    void manyPoints_tenLinesAndRest() {
        for (int i = 0; i < 14; i++) point(100 + i, "Рециркулятор " + i, ProductMatchLevel.TYPE, String.valueOf(1000 + i));
        digest.flush(false, new ImportSummary());
        assertThat(sent.get(0).split("\n▸ ", -1)).hasSize(11);
        assertThat(sent.get(0)).contains("…и ещё 4");
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests '*ProcurementPlan*'`
Expected: FAIL компиляции.

- [ ] **Step 3: Implement writer, service, digest**

`ProcurementPlanPointRepository` — ещё:

```java
    List<ProcurementPlanPoint> findByDigestedAtIsNullAndGoneAtIsNull();
```

`service/productwatch/ProcurementPlanWriter.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.repository.ProcurementPlanMatchRepository;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

/** Запись пунктов планов (спека §10.1): отдельный бин — транзакция на вызов, сеть сюда не заходит (§6 CLAUDE.md). */
@Component
public class ProcurementPlanWriter {

    /** Пункт плана с нашими группами: регион — по месту поставки, группы → слово, по которому пункт прошёл. */
    public record Collected(FoundPlanPoint point, String region, ProductMatchLevel level, Map<Long, String> groupWords) {}

    private final ProcurementPlanPointRepository points;
    private final ProcurementPlanMatchRepository matches;

    public ProcurementPlanWriter(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches) {
        this.points = points; this.matches = matches;
    }

    @Transactional(readOnly = true)
    public boolean isEmpty() { return points.count() == 0; }

    /** @return {создано, обновлено} */
    @Transactional
    public int[] upsert(List<Collected> found, OffsetDateTime runStart) {
        int created = 0, updated = 0;
        for (Collected c : found) {
            FoundPlanPoint f = c.point();
            Optional<ProcurementPlanPoint> existing = points.findByExtId(f.id());
            ProcurementPlanPoint p = existing.orElseGet(() -> ProcurementPlanPoint.builder().market(Market.KZ).extId(f.id())
                    .firstSeenAt(runStart).build());
            if (existing.isPresent()) updated++; else created++;
            p.setYear(f.year() == null ? runStart.getYear() : f.year());
            p.setMonth(f.month());
            p.setCustomerBin(cut(f.subjectBiin(), 20));
            p.setCustomerName(cut(f.subjectNameRu(), 500));
            p.setRegion(c.region());
            p.setDeliveryPlace(f.deliveryPlace());
            p.setName(Objects.requireNonNullElse(cut(f.nameRu(), 500), "—"));
            String desc = String.join(". ", Arrays.asList(blank(f.descRu()), blank(f.extraDescRu())).stream().filter(Objects::nonNull).toList());
            p.setDescription(desc.isEmpty() ? null : desc);
            p.setQuantity(f.count());
            p.setUnit(cut(f.unit(), 100));
            p.setUnitPrice(f.price());
            p.setAmount(f.amount());
            p.setTradeMethod(cut(f.tradeMethod(), 200));
            p.setStatusId(f.statusId());
            p.setStatusName(cut(f.statusName(), 100));
            p.setLevel(c.level());
            p.setLastSeenAt(runStart);
            p.setGoneAt(null);
            p.setGoneStatus(null);
            p = points.save(p);
            matches.deleteByPlanPointId(p.getId());
            for (Map.Entry<Long, String> g : c.groupWords().entrySet()) {
                matches.save(ProcurementPlanMatch.builder().planPointId(p.getId()).groupId(g.getKey()).word(cut(g.getValue(), 100)).build());
            }
        }
        return new int[] {created, updated};
    }

    /** Открытые пункты, которые этот сбор не встретил, — их статус проверяется по id. */
    @Transactional(readOnly = true)
    public List<Long> openNotSeenSince(OffsetDateTime runStart) {
        return points.findByGoneAtIsNull().stream().filter(p -> p.getLastSeenAt().isBefore(runStart))
                .map(ProcurementPlanPoint::getExtId).toList();
    }

    /** extId → статус площадки («Опубликован», «снят»…): пункт уходит из списка по умолчанию. */
    @Transactional
    public void markGone(Map<Long, String> gone) {
        OffsetDateTime now = OffsetDateTime.now();
        for (Map.Entry<Long, String> e : gone.entrySet()) {
            points.findByExtId(e.getKey()).ifPresent(p -> { p.setGoneAt(now); p.setGoneStatus(cut(e.getValue(), 100)); });
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.strip(); }

    private static String cut(String s, int max) { return s == null ? null : s.length() <= max ? s : s.substring(0, max); }
}
```

`service/productwatch/ProcurementPlanService.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.MedicalGoodsVocabulary;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.util.ErrorText;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.*;
import java.util.*;

/**
 * Планы закупок с нашими видами изделий (спека 2026-10-19-product-watch §10): по словам включённых групп — пункты
 * «Утверждён» на этот и следующий год; услуги отсекаются; правила групп — как у лота; регион — по месту поставки.
 * Пункт, который перестал встречаться, — статус по id: «объявлен / снят».
 */
@Service
public class ProcurementPlanService {

    static final List<Integer> APPROVED = List.of(2);
    static final int MAX_PAGES = 5;
    static final long PAUSE_MS = 300;

    private final GoszakupSearchClient search;
    private final ProcurementPlanWriter writer;
    private final CatalogSource catalog;
    private final RegionResolver regions;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public ProcurementPlanService(GoszakupSearchClient search, ProcurementPlanWriter writer, ProductCatalog catalog, RegionResolver regions) {
        this(search, writer, catalog, regions, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public ProcurementPlanService(GoszakupSearchClient search, ProcurementPlanWriter writer, CatalogSource catalog, RegionResolver regions,
                                  Clock clock, UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.writer = writer; this.catalog = catalog; this.regions = regions; this.clock = clock; this.sleeper = sleeper;
    }

    /** @return true — первый сбор (пунктов ещё не было): сводка в этот раз молчит. */
    public boolean collect(ImportSummary sum) {
        boolean first = writer.isEmpty();
        if (!search.isConfigured()) {
            sum.setEnabled(false);
            sum.setMessage("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
            return first;
        }
        OffsetDateTime runStart = OffsetDateTime.now(clock);
        int year = LocalDate.now(clock.withZone(Market.KZ.zone())).getYear();
        CatalogSnapshot snap = catalog.get();
        Map<Long, ProcurementPlanWriter.Collected> found = new LinkedHashMap<>();
        try {
            for (String w : snap.groups().stream().flatMap(g -> g.searchWords().stream()).map(x -> x.strip().toLowerCase(Locale.ROOT)).distinct().toList()) {
                Long after = null;
                for (int page = 0; page < MAX_PAGES; page++) {
                    pause();
                    Long a0 = after;
                    SearchPage<FoundPlanPoint> p = UpstreamRetry.call(sleeper, () -> search.searchPlans(w, List.of(year, year + 1), APPROVED, a0));
                    sum.setFetched(sum.getFetched() + p.items().size());
                    for (FoundPlanPoint pp : p.items()) collected(pp, snap).ifPresent(c -> found.merge(pp.id(), c, ProcurementPlanService::union));
                    after = p.nextAfter();
                    if (after == null) break;
                }
            }
        } catch (RuntimeException e) {
            sum.addError("планы: " + ErrorText.of(e));
            if (ProductWatchService.stopping(e)) {
                sum.setMessage("Планы: сбор остановлен — " + ErrorText.of(e));
                return first;
            }
        }
        int[] cu = writer.upsert(new ArrayList<>(found.values()), runStart);
        sum.setMatched(found.size());
        sum.setCreated(cu[0]);
        sum.setUpdated(cu[1]);
        goneCheck(runStart, sum);
        sum.setMessage(String.format("Планы: найдено %d, наших %d, новых %d, ошибок %d", sum.getFetched(), found.size(), cu[0], sum.getErrors()));
        return first;
    }

    Optional<ProcurementPlanWriter.Collected> collected(FoundPlanPoint p, CatalogSnapshot snap) {
        if (p.id() == null || p.nameRu() == null) return Optional.empty();
        String unit = Objects.toString(p.unit(), "").toLowerCase(Locale.ROOT);
        if (unit.contains("услуг") || MedicalGoodsVocabulary.hasServiceStrong(p.nameRu())) return Optional.empty();
        String text = ProductText.normalize(String.join(" ", p.nameRu(), Objects.toString(p.descRu(), ""), Objects.toString(p.extraDescRu(), "")));
        Map<Long, String> groups = new LinkedHashMap<>();
        ProductMatchLevel level = null;
        for (GroupRule r : snap.groups()) {
            if (r.brandGroup()) {
                BrandModels.Hit h = BrandModels.first(r.codes(), text);
                if (h != null) { groups.put(r.id(), h.label()); level = ProductMatchLevel.NAMED; }
                continue;
            }
            String word = r.typeWord(text, "", false);
            if (word == null) continue;
            groups.put(r.id(), word);
            boolean named = BrandModels.first(snap.groupCodes(r.id()), text) != null || BrandModels.first(snap.globalCodes(), text) != null;
            level = ProductMatchLevel.max(level, named ? ProductMatchLevel.NAMED : ProductMatchLevel.TYPE);
        }
        if (groups.isEmpty()) return Optional.empty();
        String region = regions.resolve(p.deliveryPlace(), p.subjectNameRu());
        return Optional.of(new ProcurementPlanWriter.Collected(p, region, level, groups));
    }

    private static ProcurementPlanWriter.Collected union(ProcurementPlanWriter.Collected a, ProcurementPlanWriter.Collected b) {
        Map<Long, String> g = new LinkedHashMap<>(a.groupWords());
        b.groupWords().forEach(g::putIfAbsent);
        return new ProcurementPlanWriter.Collected(a.point(), a.region(), ProductMatchLevel.max(a.level(), b.level()), g);
    }

    private void goneCheck(OffsetDateTime runStart, ImportSummary sum) {
        List<Long> stale = writer.openNotSeenSince(runStart);
        Map<Long, String> gone = new HashMap<>();
        for (int i = 0; i < stale.size(); i += 200) {
            List<Long> batch = stale.subList(i, Math.min(stale.size(), i + 200));
            try {
                pause();
                Map<Long, FoundPlanPoint> byId = new HashMap<>();
                for (FoundPlanPoint p : UpstreamRetry.call(sleeper, () -> search.plansById(batch))) byId.put(p.id(), p);
                for (Long id : batch) {
                    FoundPlanPoint p = byId.get(id);
                    if (p == null) gone.put(id, "снят");
                    else if (p.statusId() == null || !APPROVED.contains(p.statusId())) gone.put(id, Objects.toString(p.statusName(), "изменён"));
                }
            } catch (RuntimeException e) {
                sum.addError("планы, статусы: " + ErrorText.of(e));
                if (ProductWatchService.stopping(e)) break;
            }
        }
        if (!gone.isEmpty()) writer.markGone(gone);
    }

    private void pause() {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("сбор планов прерван");
        }
    }
}
```

(В `collected` правило группы зовётся с пустым названием объявления — у плана его нет; уточнения проверяются по тексту пункта.)

`service/productwatch/ProcurementPlanDigest.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.ProcurementPlanPoint;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramClient;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.integration.telegram.TelegramText;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.util.DocFormat;
import com.vladoose.nir.util.ErrorText;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Сводка «🗓 Новое в планах закупок» раз в день (спека 2026-10-19-product-watch §10.3): тихо, в тему «Тендеры»;
 * первый сбор — молча (иначе в тему высыпался бы весь план страны). Не ушло — пункты не помечаются, следующий сбор повторит.
 */
@Component
public class ProcurementPlanDigest {

    private static final Logger log = LoggerFactory.getLogger(ProcurementPlanDigest.class);

    static final int MAX_LINES = 10;
    static final int LIMIT = 3500;
    static final String[] MONTHS = {"январь", "февраль", "март", "апрель", "май", "июнь", "июль", "август", "сентябрь",
            "октябрь", "ноябрь", "декабрь"};

    public interface Sender {
        void send(String text, boolean silent);
    }

    private final ProcurementPlanPointRepository points;
    private final TransactionTemplate tx;
    private final TelegramSettings telegram;
    private final Sender sender;
    private final boolean enabled;
    private final String publicUrl;
    private final Clock clock;

    @Autowired
    public ProcurementPlanDigest(ProcurementPlanPointRepository points, PlatformTransactionManager tm, TelegramSettings telegram,
                                 TelegramClient client, @Value("${product-watch.enabled:false}") boolean enabled,
                                 @Value("${ais.public-url:}") String publicUrl) {
        this(points, tm, telegram, client::sendTenders, enabled, publicUrl, Clock.systemUTC());
    }

    public ProcurementPlanDigest(ProcurementPlanPointRepository points, PlatformTransactionManager tm, TelegramSettings telegram,
                                 Sender sender, boolean enabled, String publicUrl, Clock clock) {
        this.points = points; this.tx = new TransactionTemplate(tm); this.telegram = telegram; this.sender = sender;
        this.enabled = enabled; this.publicUrl = publicUrl == null ? "" : publicUrl.replaceAll("/+$", ""); this.clock = clock;
    }

    public synchronized void flush(boolean firstRun, ImportSummary sum) {
        if (!enabled) return;
        List<ProcurementPlanPoint> fresh;
        try {
            fresh = tx.execute(st -> points.findByDigestedAtIsNullAndGoneAtIsNull());
        } catch (RuntimeException e) {
            sum.addSideError("сводка планов не собрана: " + ErrorText.of(e));
            return;
        }
        if (fresh == null || fresh.isEmpty()) return;
        List<Long> ids = fresh.stream().map(ProcurementPlanPoint::getId).toList();
        if (firstRun || !telegram.isConfigured()) {   // первый сбор и выключенный Telegram — без сообщения, без накопления
            markDigested(ids);
            return;
        }
        List<ProcurementPlanPoint> sorted = fresh.stream()
                .sorted(Comparator.comparing((ProcurementPlanPoint p) -> p.getLevel() == ProductMatchLevel.TYPE ? 1 : 0)
                        .thenComparing(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount(), Comparator.reverseOrder()))
                .toList();
        try {
            sender.send(compose(sorted, publicUrl), true);
        } catch (RuntimeException e) {
            log.warn("Telegram: сводка планов ({}) не ушла, повтор — со следующим сбором: {}", ids.size(), ErrorText.of(e));
            sum.addSideError("сводка планов не ушла (повтор — со следующим сбором): " + ErrorText.of(e));
            return;
        }
        markDigested(ids);
        log.info("Telegram: сводка планов отправлена ({})", ids.size());
    }

    private void markDigested(List<Long> ids) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        tx.executeWithoutResult(st -> points.findAllById(ids).forEach(p -> p.setDigestedAt(now)));
    }

    static String compose(List<ProcurementPlanPoint> ps, String base) {
        StringBuilder b = new StringBuilder("🗓 Новое в планах закупок: ").append(ps.size());
        int shown = 0;
        for (ProcurementPlanPoint p : ps) {
            if (shown >= MAX_LINES) break;
            String block = block(p);
            if (b.length() + block.length() + 200 > LIMIT) break;
            b.append("\n\n").append(block);
            shown++;
        }
        int rest = ps.size() - shown;
        String link = base.isEmpty() ? "раздел «Планы закупок» в АИС" : "«Планы закупок» в АИС: " + base + "/procurement-plans?fresh=1&market=KZ";
        b.append("\n\n").append(rest > 0 ? "…и ещё " + rest + " — " + link : link);
        return b.toString();
    }

    static String block(ProcurementPlanPoint p) {
        List<String> parts = new ArrayList<>();
        if (p.getQuantity() != null) {
            String unit = p.getUnit() == null ? "" : " " + TelegramText.oneLine(p.getUnit().toLowerCase(Locale.ROOT), 30);
            parts.add(p.getQuantity().stripTrailingZeros().toPlainString() + unit);
        }
        if (p.getAmount() != null) parts.add(DocFormat.money(p.getAmount()) + " ₸");
        String when = p.getMonth() != null && p.getMonth() >= 1 && p.getMonth() <= 12 ? MONTHS[p.getMonth() - 1] + " " + p.getYear() : String.valueOf(p.getYear());
        String who = String.join(" · ", Arrays.asList(p.getCustomerName(), p.getRegion()).stream().filter(Objects::nonNull).toList());
        return "▸ " + (p.getLevel() == ProductMatchLevel.TYPE ? "" : "★ ") + TelegramText.oneLine(p.getName(), 200)
                + (parts.isEmpty() ? "" : " — " + String.join(", ", parts)) + " · " + when
                + (who.isEmpty() ? "" : "\n  " + TelegramText.oneLine(who, 300));
    }
}
```

`ProductWatchScheduler` — новые параметры конструктора `ProcurementPlanService plans, ProcurementPlanDigest digest` (сразу после `ProductWatchNotifier notifier`), флаг `private final AtomicBoolean plansQueued = new AtomicBoolean(false);`, регистрация в `configureTasks` (вместо комментария):

```java
        CronSchedules.register(registrar, "Планы закупок", plansCron, DEFAULT_PLANS_CRON, z, this::startPlans, log);
```

`startPlans` и общий `report` (прежний `report(ImportSummary)` заменяется этим — поиск зовёт `report("product-watch", "Поиск нашей продукции", sum)`):

```java
    public Status startPlans() {
        if (!enabled || !plansQueued.compareAndSet(false, true)) return status();
        ImportSummary sum = new ImportSummary();
        lastPlans = sum;
        executor.execute(() -> runTask("планы закупок", () -> {
            try {
                boolean first = plans.collect(sum);
                digest.flush(first, sum);
            } finally {
                lastPlansAt = Instant.now();
                plansQueued.set(false);
                report("product-plans", "Сбор планов закупок", sum);
            }
        }));
        return status();
    }

    private void report(String key, String title, ImportSummary sum) {
        try {
            outage.afterRun(key, title, sum);
        } catch (Throwable e) {
            log.warn("Наша продукция: оповещение о сбое упало: {}", ErrorText.of(e));
        }
    }
```

В `ProductWatchSchedulerTest` — моки `ProcurementPlanService planService = mock(ProcurementPlanService.class);`, `ProcurementPlanDigest planDigest = mock(ProcurementPlanDigest.class);` (имя `plans` занято параметром-расписанием помощника), помощник — `new ProductWatchScheduler(search, specs, rematch, catalog, outage, notifier, planService, planDigest, enabled, cron, plans, catalogCron, "", "10")`, импорт `org.mockito.InOrder`, и тест:

```java
    @Test
    void plansRunCollectThenDigest_inOrder() {
        when(planService.collect(any())).thenReturn(true);
        ProductWatchScheduler s = scheduler(true, "-", "-", "-");
        s.startPlans();
        verify(outage, timeout(3000)).afterRun(eq("product-plans"), eq("Сбор планов закупок"), any());
        InOrder o = inOrder(planService, planDigest, outage);
        o.verify(planService).collect(any());
        o.verify(planDigest).flush(eq(true), any());
        o.verify(outage).afterRun(eq("product-plans"), eq("Сбор планов закупок"), any());
    }
```

- [ ] **Step 4: Query, controller and DTO**

Тест выборки страницы — `ProcurementPlanQueryTest`:

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Страница «Планы закупок» (спека §10.2): по умолчанию — открытые; фильтры группы, региона, «скоро», «новые»; «месяц прошёл». */
@SpringBootTest
@Transactional
class ProcurementPlanQueryTest {

    @Autowired ProcurementPlanPointRepository points;
    @Autowired ProcurementPlanMatchRepository matches;
    @Autowired ProductGroupRepository groups;
    ProcurementPlanQuery query;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        points.deleteAllInBatch();
        query = new ProcurementPlanQuery(points, matches, groups, CLOCK);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProcurementPlanPoint point(long extId, int year, int month, String region, int seenDaysAgo, String gone) {
        OffsetDateTime seen = OffsetDateTime.now(CLOCK).minusDays(seenDaysAgo);
        return points.save(ProcurementPlanPoint.builder().market(Market.KZ).extId(extId).year(year).month(month).region(region)
                .name("Рециркулятор " + extId).amount(new BigDecimal(1000 * extId)).level(ProductMatchLevel.TYPE)
                .firstSeenAt(seen).lastSeenAt(seen).goneAt(gone == null ? null : seen).goneStatus(gone).build());
    }

    List<Long> ids(Filter f) { return query.list(f).stream().map(PlanPointDto::extId).toList(); }

    static Filter f(Long group, String region, boolean soon, boolean fresh, boolean all) {
        return new Filter(group, region, soon, fresh, all, null, "month", 300);
    }

    @Test
    void filters_soonFreshGroupRegionAll_monthPassed() {
        ProductGroup g = groups.save(ProductGroup.builder().market(Market.KZ).name("Рециркуляторы PW").searchWords(List.of("рециркулятор"))
                .position(99).build());
        point(1, 2026, 9, "г. Алматы", 30, null);
        ProcurementPlanPoint nov = point(2, 2026, 11, "Актюбинская область", 1, null);
        point(3, 2027, 3, "г. Алматы", 1, null);
        point(4, 2026, 12, "г. Алматы", 1, "Опубликован");
        matches.save(ProcurementPlanMatch.builder().planPointId(nov.getId()).groupId(g.getId()).word("рециркулятор").build());

        assertThat(ids(f(null, null, false, false, false))).containsExactly(1L, 2L, 3L);
        assertThat(ids(f(null, null, true, false, false))).containsExactly(2L);          // октябрь — декабрь 2026
        assertThat(ids(f(null, null, false, true, false))).containsExactly(2L, 3L);      // за 7 дней
        assertThat(ids(f(g.getId(), null, false, false, false))).containsExactly(2L);
        assertThat(ids(f(null, "г. Алматы", false, false, true))).containsExactly(1L, 4L, 3L);
        assertThat(query.list(f(null, null, false, false, false)).get(0).monthPassed()).isTrue();
        assertThat(query.list(f(g.getId(), null, false, false, false)).get(0).groups()).containsExactly("Рециркуляторы PW");
    }

    @Test
    void rfMarket_empty() {
        point(1, 2026, 11, "г. Алматы", 1, null);
        MarketContext.set(Market.RF);
        assertThat(query.list(f(null, null, false, false, false))).isEmpty();
    }
}
```

DTO:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Пункт плана на странице «Планы закупок» (спека §10.2). */
public record PlanPointDto(Long id, Long extId, int year, Integer month, boolean monthPassed, String customerName, String region,
                           String name, String description, BigDecimal quantity, String unit, BigDecimal unitPrice, BigDecimal amount,
                           String tradeMethod, List<String> groups, String level, OffsetDateTime firstSeenAt, String goneStatus) {}
```

`service/productwatch/ProcurementPlanQuery.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.ProcurementPlanMatch;
import com.vladoose.nir.entity.ProcurementPlanPoint;
import com.vladoose.nir.repository.ProcurementPlanMatchRepository;
import com.vladoose.nir.repository.ProcurementPlanPointRepository;
import com.vladoose.nir.repository.ProductGroupRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.*;
import java.util.stream.Stream;

/** Выборка страницы «Планы закупок» (спека §10.2). Пунктов — сотни: фильтры в памяти, рынок — фильтром Hibernate. */
@Service
public class ProcurementPlanQuery {

    public record Filter(Long group, String region, boolean soon, boolean fresh, boolean all, String q, String sort, int limit) {}

    private final ProcurementPlanPointRepository points;
    private final ProcurementPlanMatchRepository matches;
    private final ProductGroupRepository groups;
    private final Clock clock;

    @Autowired
    public ProcurementPlanQuery(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches, ProductGroupRepository groups) {
        this(points, matches, groups, Clock.systemUTC());
    }

    public ProcurementPlanQuery(ProcurementPlanPointRepository points, ProcurementPlanMatchRepository matches, ProductGroupRepository groups,
                                Clock clock) {
        this.points = points; this.matches = matches; this.groups = groups; this.clock = clock;
    }

    @Transactional(readOnly = true)
    public List<PlanPointDto> list(Filter f) {
        if (MarketContext.get() != Market.KZ) return List.of();
        List<ProcurementPlanPoint> ps = f.all() ? points.findAll() : points.findByGoneAtIsNull();
        Map<Long, List<Long>> groupIds = new HashMap<>();
        for (ProcurementPlanMatch m : matches.findByPlanPointIdIn(ps.stream().map(ProcurementPlanPoint::getId).toList())) {
            groupIds.computeIfAbsent(m.getPlanPointId(), k -> new ArrayList<>()).add(m.getGroupId());
        }
        Map<Long, String> names = new HashMap<>();
        groups.findAllById(groupIds.values().stream().flatMap(List::stream).distinct().toList()).forEach(g -> names.put(g.getId(), g.getName()));
        YearMonth now = YearMonth.now(clock.withZone(Market.KZ.zone()));
        OffsetDateTime freshEdge = OffsetDateTime.now(clock).minusDays(7);
        String q = f.q() == null ? "" : f.q().strip().toLowerCase(Locale.ROOT);
        Comparator<ProcurementPlanPoint> order = switch (f.sort() == null ? "month" : f.sort()) {
            case "amount" -> Comparator.comparing(ProcurementPlanPoint::getAmount, Comparator.nullsLast(Comparator.reverseOrder()));
            case "new" -> Comparator.comparing(ProcurementPlanPoint::getFirstSeenAt, Comparator.reverseOrder());
            default -> Comparator.comparing(ProcurementPlanPoint::getYear)
                    .thenComparing(ProcurementPlanPoint::getMonth, Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(ProcurementPlanPoint::getAmount, Comparator.nullsLast(Comparator.reverseOrder()));
        };
        return ps.stream()
                .filter(p -> f.group() == null || groupIds.getOrDefault(p.getId(), List.of()).contains(f.group()))
                .filter(p -> f.region() == null || f.region().isBlank() || f.region().equals(p.getRegion()))
                .filter(p -> !f.soon() || soon(p, now))
                .filter(p -> !f.fresh() || p.getFirstSeenAt().isAfter(freshEdge))
                .filter(p -> q.isEmpty() || Stream.of(p.getName(), p.getDescription(), p.getCustomerName())
                        .anyMatch(x -> x != null && x.toLowerCase(Locale.ROOT).contains(q)))
                .sorted(order)
                .limit(Math.max(1, Math.min(500, f.limit())))
                .map(p -> dto(p, groupIds.getOrDefault(p.getId(), List.of()).stream().map(names::get).filter(Objects::nonNull).toList(), now))
                .toList();
    }

    /** Месяц закупки — в ближайшие 3 месяца, включая текущий. */
    static boolean soon(ProcurementPlanPoint p, YearMonth now) {
        if (p.getMonth() == null) return false;
        YearMonth ym = YearMonth.of(p.getYear(), p.getMonth());
        return !ym.isBefore(now) && !ym.isAfter(now.plusMonths(2));
    }

    private static PlanPointDto dto(ProcurementPlanPoint p, List<String> groupNames, YearMonth now) {
        boolean passed = p.getMonth() != null && YearMonth.of(p.getYear(), p.getMonth()).isBefore(now);
        return new PlanPointDto(p.getId(), p.getExtId(), p.getYear(), p.getMonth(), passed, p.getCustomerName(), p.getRegion(),
                p.getName(), p.getDescription(), p.getQuantity(), p.getUnit(), p.getUnitPrice(), p.getAmount(), p.getTradeMethod(),
                groupNames, p.getLevel().name(), p.getFirstSeenAt(), p.getGoneStatus());
    }
}
```

`controller/ProcurementPlanController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.PlanPointDto;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.productwatch.ProcurementPlanQuery;
import com.vladoose.nir.service.productwatch.ProductWatchScheduler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** «Планы закупок» (спека §10.2, §13): список с фильтрами; «Собрать сейчас» — ADMIN. */
@RestController
@RequestMapping("/api/procurement-plans")
public class ProcurementPlanController {

    private final ProcurementPlanQuery query;
    private final ProductWatchScheduler scheduler;

    public ProcurementPlanController(ProcurementPlanQuery query, ProductWatchScheduler scheduler) {
        this.query = query; this.scheduler = scheduler;
    }

    @GetMapping
    public List<PlanPointDto> list(@RequestParam(required = false) Long group, @RequestParam(required = false) String region,
                                   @RequestParam(defaultValue = "false") boolean soon, @RequestParam(defaultValue = "false") boolean fresh,
                                   @RequestParam(defaultValue = "false") boolean all, @RequestParam(required = false) String q,
                                   @RequestParam(defaultValue = "month") String sort, @RequestParam(defaultValue = "300") int limit) {
        return query.list(new ProcurementPlanQuery.Filter(group, region, soon, fresh, all, q, sort, limit));
    }

    @PostMapping("/collect")
    @PreAuthorize("hasRole('ADMIN')")
    public ProductWatchScheduler.Status collect() {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        return scheduler.startPlans();
    }
}
```

- [ ] **Step 5: Frontend page**

`shared/product-watch.ts` — дописать:

```ts
export interface PlanPoint {
  id: number; extId: number; year: number; month: number | null; monthPassed: boolean; customerName: string | null;
  region: string | null; name: string; description: string | null; quantity: number | null; unit: string | null;
  unitPrice: number | null; amount: number | null; tradeMethod: string | null; groups: string[]; level: MatchLevel;
  firstSeenAt: string; goneStatus: string | null;
}
export const MONTHS = ['январь', 'февраль', 'март', 'апрель', 'май', 'июнь', 'июль', 'август', 'сентябрь', 'октябрь', 'ноябрь', 'декабрь'];
/** «ноябрь 2026»; месяц не указан — только год. */
export function monthText(year: number | null, month: number | null): string {
  return month && month >= 1 && month <= 12 ? `${MONTHS[month - 1]} ${year ?? ''}`.trim() : `${year ?? ''}`;
}
```

`ApiService` (импорт типа `PlanPoint`):

```ts
  getProcurementPlans(params: Record<string, string>): Observable<PlanPoint[]> {
    return this.http.get<PlanPoint[]>(`${this.base}/procurement-plans`, { params });
  }
  collectProcurementPlans(): Observable<WatchStatus> { return this.http.post<WatchStatus>(`${this.base}/procurement-plans/collect`, {}); }
```

`frontend/src/app/pages/procurement-plans/procurement-plans.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, OnInit } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { DatePipe, NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { MarketService } from '../../services/market.service';
import { NotificationService } from '../../services/notification.service';
import { errorText } from '../../shared/error-text';
import { KZ_REGIONS } from '../../shared/kz-regions';
import { money } from '../../shared/client-offer';
import { monthText } from '../../shared/product-watch';
import type { PlanPoint, ProductGroup, WatchStatus } from '../../shared/product-watch';

/** «Планы закупок» (спека 2026-10-19-product-watch §10.2): пункты планов с нашими видами, ещё не объявленные. */
@Component({
  selector: 'app-procurement-plans',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, DatePipe],
  template: `
    <div class="pp-page">
      <h2>Планы закупок</h2>
      <p class="subtitle">Пункты планов закупок больниц с нашими видами изделий, ещё не объявленные (статус «Утверждён»):
        за месяцы до тендера можно подготовить КП, регистрацию и поставщика.</p>
      <p class="pp-note" *ngIf="!kz">Только для Казахстана — переключите рынок на West-Med (KZ).</p>
      <ng-container *ngIf="kz">
        <div class="pp-status" *ngIf="watch">
          <span *ngIf="!watch.enabled">Сбор планов выключен (PRODUCT_WATCH_ENABLED, DEPLOY.md).</span>
          <span *ngIf="watch.enabled">Собрано: {{ watch.lastPlansAt ? (watch.lastPlansAt | date:'dd.MM HH:mm') : 'ещё не собирали' }}<ng-container
            *ngIf="watch.lastPlans?.message"> — {{ watch.lastPlans?.message }}</ng-container></span>
          <button type="button" class="btn btn-line btn-sm" *ngIf="admin && watch.enabled" [disabled]="watch.running" (click)="collect()">
            {{ watch.running ? 'Идёт…' : 'Собрать сейчас' }}</button>
        </div>
        <div class="pp-filters">
          <select [(ngModel)]="group" (change)="load()" aria-label="Группа">
            <option value="">Все группы</option>
            <option *ngFor="let g of groups" [value]="'' + g.id">{{ g.name }}</option>
          </select>
          <select [(ngModel)]="region" (change)="load()" aria-label="Регион">
            <option value="">Все регионы</option>
            <option *ngFor="let r of regions" [value]="r">{{ r }}</option>
          </select>
          <select [(ngModel)]="sort" (change)="load()" aria-label="Порядок">
            <option value="month">По месяцу закупки</option><option value="amount">Дороже сверху</option><option value="new">Новые сверху</option>
          </select>
          <input type="search" [(ngModel)]="q" (keyup.enter)="load()" (search)="load()" placeholder="Название, заказчик…" aria-label="Поиск" />
          <label><input type="checkbox" [(ngModel)]="soon" (change)="load()" /> ближайшие 3 месяца</label>
          <label><input type="checkbox" [(ngModel)]="fresh" (change)="load()" /> новые за 7 дней</label>
          <label><input type="checkbox" [(ngModel)]="all" (change)="load()" /> с объявленными и снятыми</label>
        </div>
        <p class="pp-note" *ngIf="loading">Загружаю…</p>
        <p class="pp-note" *ngIf="!loading && !points.length">Ничего не найдено.</p>
        <div class="pp-card" *ngFor="let p of points" [class.pp-gone]="p.goneStatus">
          <div class="pp-top">
            <span class="pp-month" [class.pp-passed]="p.monthPassed">{{ monthText(p.year, p.month) }}<ng-container
              *ngIf="p.monthPassed && !p.goneStatus"> · месяц прошёл</ng-container></span>
            <span class="pp-star" *ngIf="p.level !== 'TYPE'" title="Назван наш товар">★</span>
            <span class="pp-gone-st" *ngIf="p.goneStatus">{{ p.goneStatus === 'снят' ? 'снят из плана' : 'объявлен: ' + p.goneStatus }}</span>
          </div>
          <div class="pp-cust">{{ p.customerName }}<ng-container *ngIf="p.region"> · {{ p.region }}</ng-container></div>
          <div class="pp-name">{{ p.name }}</div>
          <div class="pp-desc" *ngIf="p.description" [class.pp-open]="opened.has(p.id)" (click)="toggle(p.id)">{{ p.description }}</div>
          <div class="pp-sum">
            <span *ngIf="p.quantity != null">{{ qty(p.quantity) }} {{ p.unit }}</span>
            <span *ngIf="p.unitPrice != null"> × {{ money(p.unitPrice) }}</span>
            <span *ngIf="p.amount != null"> = <b>{{ money(p.amount) }} ₸</b></span>
            <span class="pp-method" *ngIf="p.tradeMethod"> · {{ p.tradeMethod }}</span>
          </div>
          <div class="pp-groups">{{ p.groups.join(' · ') }} · в плане с {{ p.firstSeenAt | date:'dd.MM.yyyy' }}</div>
        </div>
      </ng-container>
    </div>
  `,
  styles: [`
    .pp-page { max-width: 1000px; display: flex; flex-direction: column; gap: 10px; }
    .pp-page h2 { margin: 0; }
    .pp-note { margin: 0; font-size: 13px; color: var(--text-muted); }
    .pp-status { display: flex; gap: 10px; align-items: center; flex-wrap: wrap; font-size: 13px; color: var(--text-muted); }
    .pp-filters { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; }
    .pp-filters select, .pp-filters input[type=search] { padding: 6px 9px; border: 1px solid var(--border); border-radius: 6px;
      background: var(--surface); color: var(--text); font: inherit; }
    .pp-filters label { font-size: 13px; color: var(--text); display: flex; gap: 6px; align-items: center; }
    .pp-card { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; background: var(--surface);
               display: flex; flex-direction: column; gap: 4px; }
    .pp-card.pp-gone { opacity: 0.6; }
    .pp-top { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .pp-month { font-weight: 600; color: var(--text); }
    .pp-month.pp-passed { color: var(--warn-text); }
    .pp-star { color: var(--success-text); font-weight: 700; }
    .pp-gone-st { color: var(--text-muted); }
    .pp-cust { font-size: 13px; color: var(--text-muted); }
    .pp-name { font-weight: 600; color: var(--text); overflow-wrap: anywhere; }
    .pp-desc { font-size: 13px; color: var(--text); cursor: pointer; display: -webkit-box; -webkit-line-clamp: 2;
               -webkit-box-orient: vertical; overflow: hidden; overflow-wrap: anywhere; }
    .pp-desc.pp-open { display: block; }
    .pp-sum { font-size: 13px; color: var(--text); }
    .pp-method, .pp-groups { color: var(--text-muted); }
    .pp-groups { font-size: 12px; }
    @media (max-width: 900px) {
      .pp-filters select, .pp-filters input[type=search] { font-size: 16px; flex: 1 1 100%; }
    }
  `],
})
export class ProcurementPlansComponent implements OnInit {
  readonly regions = KZ_REGIONS;
  readonly money = money;
  readonly monthText = monthText;
  points: PlanPoint[] = [];
  groups: ProductGroup[] = [];
  watch: WatchStatus | null = null;
  group = ''; region = ''; sort = 'month'; q = ''; soon = false; fresh = false; all = false;
  loading = false;
  opened = new Set<number>();
  private req: Subscription | null = null;

  constructor(private api: ApiService, private auth: AuthService, private market: MarketService, private notify: NotificationService,
              private route: ActivatedRoute, private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  get kz(): boolean { return this.market.value === 'KZ'; }
  get admin(): boolean { return this.auth.isAdmin(); }

  ngOnInit() {
    if (!this.kz) return;
    this.fresh = this.route.snapshot.queryParamMap.get('fresh') === '1';   // ссылка из сводки в Telegram
    this.api.getProductGroups().pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { this.groups = s.groups.filter(g => !g.brandGroup); this.watch = s.watch; this.cdr.detectChanges(); },
      error: () => {},
    });
    this.load();
  }

  load() {
    const params: Record<string, string> = { sort: this.sort, limit: '500' };
    if (this.group) params['group'] = this.group;
    if (this.region) params['region'] = this.region;
    if (this.q.trim()) params['q'] = this.q.trim();
    if (this.soon) params['soon'] = 'true';
    if (this.fresh) params['fresh'] = 'true';
    if (this.all) params['all'] = 'true';
    this.req?.unsubscribe();   // ответ на прежние фильтры не перетирает новый
    this.loading = true;
    this.req = this.api.getProcurementPlans(params).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: ps => { this.points = ps; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Планы не загрузились: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  collect() {
    this.api.collectProcurementPlans().subscribe({
      next: w => { this.watch = w; this.notify.success('Сбор планов запущен — займёт несколько минут'); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  toggle(id: number) { if (this.opened.has(id)) this.opened.delete(id); else this.opened.add(id); }

  qty(n: number): string { return Number.isInteger(n) ? String(n) : String(n).replace('.', ','); }
}
```

`app.routes.ts` (ленивый, как остальные страницы раздела): `{ path: 'procurement-plans', loadComponent: () => import('./pages/procurement-plans/procurement-plans.component').then(m => m.ProcurementPlansComponent) }`. `layout.component.ts` — в группе «Тендеры», перед «Нашей продукцией»:

```html
            <a *ngIf="market.value === 'KZ'" routerLink="/procurement-plans" routerLinkActive="active">
              <svg lucideIcon="calendar-clock" [size]="16"></svg> Планы закупок
            </a>
```

`app.config.ts` — `LucideCalendarClock` в обоих местах.

- [ ] **Step 6: Run tests, build, browser**

Run: `./gradlew test --tests '*ProcurementPlan*' --tests '*ProductWatchSchedulerTest*'` и `cd frontend && npm run build`.
Живьём — сбор по 2–3 группам (локально, `PRODUCT_WATCH_ENABLED=true`, «Собрать сейчас»; группы на время проверки сузить на странице «Наша продукция» и вернуть), страница: фильтры, «месяц прошёл», 1280 / 390, обе темы, оператор; сводка — на заглушке Bot API (`scripts/telegram-stub.mjs`): первый сбор — тишина, второй с новым пунктом — одно сообщение.

- [ ] **Step 7: Mutation check**

1. `collected` без отсечки услуг → `collect_onlyOurs_noServices_region_firstRun` красный.
2. `collect` без `goneCheck` → `vanished_markedGoneWithStatus` красный.
3. `ProcurementPlanDigest.flush` без ветки «первый сбор — молча» → `firstRunSilent_thenOneQuietMessageExactFirst` красный.
4. `flush`: `markDigested` до отправки → `sendFails_notDigested_retryNextTime` красный.
5. `ProcurementPlanQuery.soon` без верхней границы → `filters_soonFreshGroupRegionAll_monthPassed` красный.
6. `ProductWatchScheduler.startPlans`: `digest.flush` раньше `collect` → `plansRunCollectThenDigest_inOrder` красный.
Откат копией файла, `./gradlew compileJava`.

- [ ] **Step 8: Commit**

```bash
git add -A src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/productwatch frontend/src/app
git commit -m "feat(product-watch): планы закупок — сбор «Утверждён» по нашим видам, «объявлен / снят», сводка раз в день, страница

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 12: «Найти тендеры» в частной заявке — поиск по стране, итоги, «Завести в АИС», «В историю сделки»

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/productwatch/TenderSearchService.java`, `AnnouncementService.java`, `src/main/java/com/vladoose/nir/controller/TenderSearchController.java`, `src/main/java/com/vladoose/nir/dto/response/TenderSearchStatus.java`, `TenderSearchHit.java`, `AnnouncementResult.java`
- Modify: `ProductWatchService.java` (`importAnnouncement(String)` — заведение одного объявления без проверки «наш лот»), `repository/TenderRepository.java` (`findBySourceExtIdIn`, `findParsedLotSpec`), `integration/goszakup/RegionResolver.java` (падежи Астаны), `ProductFixtures.java` (`pdf`), `RegionResolverTest.java`
- Create (front): `frontend/src/app/pages/private-requests/pr-tender-search.component.ts`; Modify: `private-request-page.component.ts`, `api.service.ts`, `shared/product-watch.ts`
- Test: `src/test/java/com/vladoose/nir/productwatch/TenderSearchServiceTest.java`, `AnnouncementServiceTest.java`

**Interfaces:**
- Consumes: `GoszakupSearchClient` (задача 4), `GoszakupClient.fetchLotTechSpec/downloadFile/fetchSubject` (есть), `PdfTextExtractor`, `TechSpecExtractor` (есть), `PrivateRequestService.findById` (гард рынка и вида — есть), `ProductWatchService` (задача 4), `CatalogPhrases.occurs/index` (задача 3), `RegionResolver`.
- Produces:
  - `record TenderSearchHit(String kind, String numberAnno, String lotNumber, String lotName, String announcement, String customer, String region, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount, String deadlineAt, String state, String tradeMethod, double score, List<Integer> positions, List<Integer> specPositions, Long tenderId, Integer planYear, Integer planMonth)` (`kind` — `TENDER` / `PLAN`; `state` — `OPEN` / `FAILED` / `CLOSED` / `DONE` / `CANCELLED` / `PLANNED`; дата состояния на экране — `deadlineAt`);
  - `record TenderSearchStatus(boolean running, int progress, int total, Instant startedAt, String waiting, String error, List<TenderSearchHit> hits)`;
  - `record AnnouncementResult(String status, String supplierName, String supplierBin, BigDecimal contractSum, String signDate)`;
  - `TenderSearchService.start(long requestId) → TenderSearchStatus`, `status(long requestId)`; `ProductWatchService.importAnnouncement(String numberAnno) → long tenderId`; `AnnouncementService.result(String numberAnno)`;
  - пути: `POST/GET /api/private-requests/{id}/tender-search`, `POST /api/goszakup/announcements/{numberAnno}/import` (ADMIN), `GET /api/goszakup/announcements/{numberAnno}/result`;
  - фронт: `ApiService.startTenderSearch(id)`, `getTenderSearch(id)`, `importAnnouncement(anno)`, `announcementResult(anno)`.

- [ ] **Step 1: Write the failing tests**

`ProductFixtures` (задача 3) — помощник байтов ТЗ:

```java
    /** Байты PDF ТЗ из ресурсов (для фейка клиента goszakup). */
    static byte[] pdf(String file) {
        try (InputStream in = ProductFixtures.class.getResourceAsStream("/product-watch/specs/" + file)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
```

`TenderSearchServiceTest` (база, откат; фейк поиска — задача 4, фейк клиента — существующий `FakeGoszakupClient` с `techSpecByKey` / `filesByUrl`; позиции — список клиента «Horeca_1» на набор АСД-Г5.1; часы — 19.10.2026 10:00 по Уральску):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.TenderSearchHit;
import com.vladoose.nir.dto.response.TenderSearchStatus;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.goszakup.FakeGoszakupClient;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.FoundAnnouncement;
import com.vladoose.nir.integration.goszakup.dto.FoundLot;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Найти тендеры» (спека §11): слова позиций → лоты, объявления и планы по стране → похожесть → сверка с ТЗ. */
@SpringBootTest
@Transactional
class TenderSearchServiceTest {

    @Autowired PrivateRequestService requests;
    @Autowired TenderRepository tenders;
    @Autowired RegionResolver regions;
    FakeGoszakupSearchClient search;
    FakeGoszakupClient client;
    TenderSearchService service;

    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC);
    static final List<String> HORECA = List.of(
            "Аппарат для аспирации дыма АСД-ФОТЕК, комплект для амбулаторной гинекологии с высокочастотным активатором",
            "Блок аспирации", "Активатор высокочастотный", "Фильтр сменный", "Шланг аспирационный исполнение 1",
            "Наконечник аспирационный", "Держатель шланга аспирационного напольный", "Фильтр одноразовый");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.groups(), List.of());
        search = new FakeGoszakupSearchClient();
        client = new FakeGoszakupClient();
        service = new TenderSearchService(search, client, requests, () -> snap, tenders, regions, CLOCK, ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    long request(List<String> names) {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), names.stream().map(n -> PrivateRequestService.input(n, null, 1, null, null, null)).toList(),
                LinesMode.APPEND);
        return t.getId();
    }

    static FoundAnnouncement buy(String anno, String name, String org, String end, int method, String methodName) {
        return new FoundAnnouncement((long) anno.hashCode(), anno, name, "990240006109", org, new BigDecimal("3714285.71"),
                "2026-10-01 10:00:00", end, 220, method, methodName, 3);
    }

    static FoundLot lot(FoundAnnouncement a, int status, String name, String desc) {
        return new FoundLot((long) (a.numberAnno() + name).hashCode(), "88111123-" + status, name, desc, BigDecimal.ONE,
                new BigDecimal("3714285.71"), status, "2026-10-16 09:00:00", a.orgBin(), a.orgNameRu(), a.numberAnno(), a);
    }

    static final String ASTANA = "ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астаны";

    @Test
    void horecaPositions_astanaFirst_bySpec() {
        long id = request(HORECA);
        FoundAnnouncement zcp2 = buy("17779323-1", "Аппарат для аспирации дыма", ASTANA, "2026-10-16 09:00:00", 3, "Запрос ценовых предложений");
        FoundAnnouncement oi3 = buy("17802320-1", "Аппарат для аспирации дыма", ASTANA, "2026-10-20 09:00:00", 6,
                "Из одного источника по несостоявшимся закупкам");
        FoundAnnouncement other = buy("17790001-1", "Хирургическое оборудование", "КГП «Больница»", "2026-10-25 10:00:00", 3,
                "Запрос ценовых предложений");
        search.lotsByWord.put("аспирации", List.of(
                lot(zcp2, 370, "Отсасыватель", "для аспирации жидкого содержимого"),
                lot(oi3, 220, "Отсасыватель", "для аспирации жидкого содержимого"),
                lot(other, 220, "Аспиратор хирургический", "электрический")));
        byte[] ts = ProductFixtures.pdf("17779323-1.pdf");          // ТЗ ОИ3 — то же, что у ЗЦП2
        client.techSpecByKey.put("17779323-1|Отсасыватель", new LotTechSpecRef("f1", "ts.pdf", false));
        client.techSpecByKey.put("17802320-1|Отсасыватель", new LotTechSpecRef("f2", "ts.pdf", false));
        client.filesByUrl.put("f1", ts);
        client.filesByUrl.put("f2", ts);

        TenderSearchStatus s = service.runNow(id);

        assertThat(s.error()).isNull();
        assertThat(s.hits()).extracting(TenderSearchHit::numberAnno).startsWith("17802320-1", "17779323-1");
        TenderSearchHit first = s.hits().get(0);
        assertThat(first.state()).isEqualTo("OPEN");
        assertThat(first.specPositions()).contains(2, 5, 6, 7).doesNotContain(8);   // «Фильтр одноразовый» в ТЗ — «однократного»
        assertThat(first.region()).isEqualTo("г. Астана");
        assertThat(s.hits().get(1).state()).isEqualTo("FAILED");
        assertThat(search.calls).contains("lots:аспирации", "lots:отсасыватель");
    }

    @Test
    void genericOnlyPositions_nothingToSearch_noRequests() {
        long id = request(List.of("Аппарат медицинский", "Набор"));
        TenderSearchStatus s = service.runNow(id);
        assertThat(search.calls).isEmpty();
        assertThat(s.error()).isEqualTo("Не по чему искать: в позициях одни общие слова");
    }

    @Test
    void forbidden_stopsWithReason() {
        long id = request(List.of("Блок аспирации"));
        search.forbidFromCall = 1;
        TenderSearchStatus s = service.runNow(id);
        assertThat(s.error()).startsWith("Поиск остановлен: goszakup API 403");
        assertThat(search.calls).hasSize(1);
    }

    @Test
    void otherMarketRequest_404() {
        MarketContext.set(Market.RF);
        long rf = requests.createEmpty().getId();
        MarketContext.set(Market.KZ);
        assertThatThrownBy(() -> service.start(rf)).isInstanceOf(NotFoundException.class);
    }
}
```

`AnnouncementServiceTest` («Итоги» и «Завести в АИС»; фейки — как выше; `ProductWatchService` собирается тестом, как в задаче 4):

```java
package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.FakeGoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class AnnouncementServiceTest {

    @Autowired GoszakupTenderWriter writer;
    @Autowired ProductMatchService matchService;
    @Autowired TenderRepository tenders;
    FakeGoszakupSearchClient search;
    FakeGoszakupClient client;
    AnnouncementService announcements;
    ProductWatchService watch;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        search = new FakeGoszakupSearchClient();
        client = new FakeGoszakupClient();
        announcements = new AnnouncementService(search, client, ms -> { });
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.groups(), List.of());
        watch = new ProductWatchService(search, client, writer, matchService, () -> snap, tenders,
                Clock.fixed(Instant.parse("2026-10-19T05:00:00Z"), ZoneOffset.UTC), ms -> { });
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void result_contractWithSupplierName() {
        search.contracts.put("17700001-1", List.of(new ContractInfo("150540012345", new BigDecimal("3260000"),
                new BigDecimal("3650000"), "2026-09-12 15:20:00", 2)));
        SubjectDto s = new SubjectDto();
        s.setBin("150540012345");
        s.setNameRu("ТОО «Медтехника-КЗ»");
        client.subjectsByBin.put("150540012345", s);
        AnnouncementResult r = announcements.result("17700001-1");
        assertThat(r.supplierName()).isEqualTo("ТОО «Медтехника-КЗ»");
        assertThat(r.contractSum()).isEqualByComparingTo("3650000");
        assertThat(r.signDate()).isEqualTo("2026-09-12");
        assertThat(r.status()).isEqualTo("договор есть");
    }

    @Test
    void result_noContractYet() {
        assertThat(announcements.result("17700002-1").status()).isEqualTo("договор ещё не опубликован");
    }

    @Test
    void importAnnouncement_withoutOurLot_createdOnce() {
        FoundAnnouncement a = new FoundAnnouncement(1L, "17700003-1", "Приобретение мебели", "990240006109", "КГП «Больница»",
                new BigDecimal("500000"), "2026-10-10 10:00:00", "2026-10-25 10:00:00", 220, 3, "Запрос ценовых предложений", 3);
        search.byNumber.put("17700003-1", a);
        LotDto l = new LotDto();
        l.setLotNumber("1");
        l.setNameRu("Стол письменный");
        l.setCount(BigDecimal.ONE);
        l.setAmount(new BigDecimal("500000"));
        client.lotsByAnno.put("17700003-1", List.of(l));
        long id = watch.importAnnouncement("17700003-1");
        Tender t = tenders.findById(id).orElseThrow();
        assertThat(t.isFoundByProductSearch()).isTrue();
        assertThat(t.getLots()).hasSize(1);
        assertThat(watch.importAnnouncement("17700003-1")).isEqualTo(id);
        assertThat(client.lotsCalls.get("17700003-1")).isEqualTo(1);
    }
}
```

- [ ] **Step 2: Run to verify they fail**

Run: `./gradlew test --tests '*TenderSearchServiceTest*' --tests '*AnnouncementServiceTest*'`
Expected: FAIL компиляции.

- [ ] **Step 3: Implement**

DTO:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.util.List;

/**
 * Строка «Найти тендеры» (спека §11.2). kind — TENDER / PLAN; state — OPEN «приём до …», FAILED «не состоялся»,
 * CLOSED «приём закрыт», DONE «состоялся», CANCELLED «отменён», PLANNED «план: месяц год».
 */
public record TenderSearchHit(String kind, String numberAnno, String lotNumber, String lotName, String announcement,
                              String customer, String region, BigDecimal quantity, BigDecimal unitPrice, BigDecimal amount,
                              String deadlineAt, String state, String tradeMethod, double score, List<Integer> positions,
                              List<Integer> specPositions, Long tenderId, Integer planYear, Integer planMonth) {}
```

```java
package com.vladoose.nir.dto.response;

import java.time.Instant;
import java.util.List;

/** Состояние поиска заявки: идёт ли, прогресс по словам, ожидание очереди, ошибка, результат. */
public record TenderSearchStatus(boolean running, int progress, int total, Instant startedAt, String waiting, String error,
                                 List<TenderSearchHit> hits) {}
```

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;

/** «Итоги ›» объявления: договор (поставщик, сумма с НДС, дата подписи) или «договор ещё не опубликован». */
public record AnnouncementResult(String status, String supplierName, String supplierBin, BigDecimal contractSum, String signDate) {}
```

`RegionResolver` — падежи Астаны (полное имя заказчика — «…акимата города Астаны»; «астана» в нём не встречается):

```java
        PATTERNS.put("астаны", "г. Астана");
        PATTERNS.put("астане", "г. Астана");
```

(после `PATTERNS.put("астана", "г. Астана");`) и тест в `RegionResolverTest`:

```java
    @Test void astanaDeclined() {
        assertThat(r.resolve("ГКП на ПХВ «Городская многопрофильная больница № 2» акимата города Астаны")).isEqualTo("г. Астана");
    }
```

`TenderRepository`:

```java
    List<Tender> findBySourceExtIdIn(Collection<String> sourceExtIds);

    /** Разобранное ТЗ лота, если тендер уже в АИС (по номеру объявления и номеру лота площадки). */
    @Query("select l.requiredSpec from TenderLot l where l.tender.sourceExtId = :anno and l.sourceLotCode = :code"
            + " and l.techSpecStatus = com.vladoose.nir.entity.TechSpecStatus.OK")
    List<String> findParsedLotSpec(@Param("anno") String anno, @Param("code") String code);
```

`ProductWatchService.importAnnouncement`:

```java
    /** «Завести в АИС» из «Найти тендеры» (спека §11.2): объявление по номеру — без проверки «наш лот»; уже есть — его id. */
    public long importAnnouncement(String numberAnno) {
        Optional<Tender> existing = tenders.findBySourceExtId(numberAnno);
        if (existing.isPresent()) return existing.get().getId();
        FoundAnnouncement a = UpstreamRetry.call(sleeper, () -> search.announcement(numberAnno));
        if (a == null) throw new com.vladoose.nir.exception.NotFoundException("Объявление " + numberAnno + " на goszakup не найдено");
        List<LotDto> lots = UpstreamRetry.call(sleeper, () -> client.fetchLots(numberAnno));
        SubjectDto subj = a.orgBin() == null ? null : UpstreamRetry.call(sleeper, () -> client.fetchSubject(a.orgBin()));
        writer.upsertFound(a.toTrdBuy(), subj, lots, a.tradeMethodName());
        long id = tenders.findBySourceExtId(numberAnno).orElseThrow().getId();
        matches.evaluate(id, ProductMatchSource.SEARCH);
        return id;
    }
```

`service/productwatch/AnnouncementService.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.dto.ContractInfo;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.integration.http.UpstreamRetry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

/** «Итоги ›» в «Найти тендеры» (спека §11.2): договор объявления и название поставщика по БИН. */
@Service
public class AnnouncementService {

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final UpstreamRetry.Sleeper sleeper;

    @Autowired
    public AnnouncementService(GoszakupSearchClient search, GoszakupClient client) {
        this(search, client, UpstreamRetry.REAL);
    }

    public AnnouncementService(GoszakupSearchClient search, GoszakupClient client, UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.sleeper = sleeper;
    }

    public AnnouncementResult result(String numberAnno) {
        List<ContractInfo> cs = UpstreamRetry.call(sleeper, () -> search.contracts(numberAnno));
        if (cs.isEmpty()) return new AnnouncementResult("договор ещё не опубликован", null, null, null, null);
        ContractInfo c = cs.get(0);
        String name = null;
        if (c.supplierBiin() != null) {
            try {
                SubjectDto s = UpstreamRetry.call(sleeper, () -> client.fetchSubject(c.supplierBiin()));
                if (s != null) name = s.getNameRu();
            } catch (RuntimeException e) {
                if (ProductWatchService.stopping(e)) throw e;   // название — необязательно, стоп-коды — нет
            }
        }
        BigDecimal sum = c.contractSumWnds() != null ? c.contractSumWnds() : c.contractSum();
        String date = c.signDate() == null || c.signDate().length() < 10 ? c.signDate() : c.signDate().substring(0, 10);
        return new AnnouncementResult("договор есть", name, c.supplierBiin(), sum, date);
    }
}
```

`service/productwatch/TenderSearchService.java`:

```java
package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.TenderSearchHit;
import com.vladoose.nir.dto.response.TenderSearchStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.integration.ImportDeadline;
import com.vladoose.nir.integration.goszakup.GoszakupClient;
import com.vladoose.nir.integration.goszakup.GoszakupSearchClient;
import com.vladoose.nir.integration.goszakup.RegionResolver;
import com.vladoose.nir.integration.goszakup.dto.*;
import com.vladoose.nir.integration.http.UpstreamRetry;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.util.ErrorText;
import com.vladoose.nir.util.PdfTextExtractor;
import com.vladoose.nir.util.TechSpecExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * «Найти тендеры» в частной заявке (спека 2026-10-19-product-watch §11): слова позиций → лоты, объявления и планы
 * по всей стране за 90 дней → похожесть по словам → у лучших — сверка с ТЗ. Задача на заявку в своём однопоточном
 * исполнителе (токен общий с продом — параллельно не ходим); результат — в памяти 1 ч, в базу не пишется.
 */
@Service
public class TenderSearchService {

    private static final Logger log = LoggerFactory.getLogger(TenderSearchService.class);

    static final int MAX_POSITIONS = 10;
    static final int MAX_WORDS = 6;
    static final int WINDOW_DAYS = 90;
    static final int PAGE_FULL = 200;
    static final int MAX_ANNOUNCEMENTS = 3;
    static final int SPEC_TOP = 10;
    static final int MAX_HITS = 15;
    static final double MIN_SIMILARITY = 0.35;
    static final long PAUSE_MS = 300;
    static final Duration KEEP = Duration.ofHours(1);
    /** Общие слова названий: по ним искать бессмысленно (спека §11.1 п. 1). */
    static final Set<String> GENERIC_WORDS = Set.of("аппарат", "аппарата", "аппараты", "система", "системы", "набор", "набора",
            "комплект", "комплекта", "медицинский", "медицинская", "медицинское", "медицинские", "изделие", "изделия",
            "прибор", "прибора", "устройство", "оборудование", "исполнение");
    private static final List<String> STATE_ORDER = List.of("OPEN", "PLANNED", "FAILED", "CLOSED", "DONE", "CANCELLED");

    record Position(int lineNo, String name, String brand) {}

    record Input(long requestId, List<Position> positions) {}

    /** Поиск заявки: пишет только поток задачи, читает HTTP-поток опроса. */
    static final class Job {
        final Input input;
        final Instant createdAt;
        volatile boolean started;
        volatile boolean done;
        volatile int progress;
        volatile int total;
        volatile String error;
        volatile List<TenderSearchHit> hits = List.of();

        Job(Input input, Instant createdAt) { this.input = input; this.createdAt = createdAt; }

        TenderSearchStatus status() {
            String waiting = !started && !done ? "ждёт: идёт поиск по другой заявке" : null;
            return new TenderSearchStatus(!done, progress, total, createdAt, waiting, error, hits);
        }
    }

    /** Кандидат до выдачи. */
    static final class Cand {
        String kind, anno, state;
        FoundLot head;
        FoundPlanPoint plan;
        double score;
        TreeSet<Integer> positions = new TreeSet<>();
        List<Integer> specPositions = List.of();
    }

    private final GoszakupSearchClient search;
    private final GoszakupClient client;
    private final PrivateRequestService requests;
    private final CatalogSource catalog;
    private final TenderRepository tenders;
    private final RegionResolver regions;
    private final Clock clock;
    private final UpstreamRetry.Sleeper sleeper;
    private final Map<Long, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "tender-search");
        t.setDaemon(true);
        return t;
    });

    @Autowired
    public TenderSearchService(GoszakupSearchClient search, GoszakupClient client, PrivateRequestService requests,
                               ProductCatalog catalog, TenderRepository tenders, RegionResolver regions) {
        this(search, client, requests, catalog, tenders, regions, Clock.systemUTC(), UpstreamRetry.REAL);
    }

    public TenderSearchService(GoszakupSearchClient search, GoszakupClient client, PrivateRequestService requests,
                               CatalogSource catalog, TenderRepository tenders, RegionResolver regions, Clock clock,
                               UpstreamRetry.Sleeper sleeper) {
        this.search = search; this.client = client; this.requests = requests; this.catalog = catalog;
        this.tenders = tenders; this.regions = regions; this.clock = clock; this.sleeper = sleeper;
    }

    /** «Найти тендеры по стране»: идёт поиск этой заявки — его статус, иначе новая задача. */
    public TenderSearchStatus start(long requestId) {
        Input in = input(requestId);
        purge();
        Job old = jobs.get(requestId);
        if (old != null && !old.done) return old.status();
        Job job = new Job(in, clock.instant());
        jobs.put(requestId, job);
        executor.execute(() -> {
            job.started = true;
            MarketContext.set(Market.KZ);   // §6: фоновый поток, рынок — явно
            try {
                run(job);
            } catch (RuntimeException e) {
                log.warn("Найти тендеры: поиск заявки {} упал: {}", in.requestId(), e.toString());
                job.error = "Поиск прерван: " + ErrorText.of(e);
            } catch (Error e) {
                job.error = "Поиск прерван: " + ErrorText.of(e);
                throw e;
            } finally {
                job.done = true;
                MarketContext.clear();
            }
        });
        return job.status();
    }

    public TenderSearchStatus status(long requestId) {
        input(requestId);   // гард рынка и вида заявки
        Job j = jobs.get(requestId);
        return j == null ? new TenderSearchStatus(false, 0, 0, null, null, null, List.of()) : j.status();
    }

    /** Синхронный прогон — тот же код, что у задачи исполнителя (тесты). */
    public TenderSearchStatus runNow(long requestId) {
        Job job = new Job(input(requestId), clock.instant());
        job.started = true;
        try {
            run(job);
        } finally {
            job.done = true;
        }
        return job.status();
    }

    Input input(long requestId) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Поиск тендеров по стране — только для Казахстана");
        if (!search.isConfigured()) throw new BadRequestException("Токен goszakup не настроен (GOSZAKUP_TOKEN)");
        Tender t = requests.findById(requestId);
        List<Position> ps = t.getLots().stream()
                .filter(l -> l.getEquipName() != null && !l.getEquipName().isBlank())
                .sorted(Comparator.comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber()))
                .limit(MAX_POSITIONS)
                .map(l -> new Position(l.getLotNumber() == null ? 0 : l.getLotNumber(), l.getEquipName(), l.getManufact()))
                .toList();
        if (ps.isEmpty()) throw new BadRequestException("В заявке нет позиций");
        return new Input(requestId, ps);
    }

    private void purge() {
        Instant edge = clock.instant().minus(KEEP);
        jobs.values().removeIf(j -> j.done && j.createdAt.isBefore(edge));
    }

    void run(Job job) {
        CatalogSnapshot snap = catalog.get();
        List<Position> ps = job.input.positions();
        LinkedHashSet<String> words = new LinkedHashSet<>();
        for (Position p : ps) {
            for (String w : wordsOf(p, snap)) if (words.size() < MAX_WORDS) words.add(w);
        }
        if (words.isEmpty()) {
            job.error = "Не по чему искать: в позициях одни общие слова";
            return;
        }
        job.total = words.size();
        ZonedDateTime kz = ZonedDateTime.now(clock.withZone(Market.KZ.zone()));
        Map<Long, FoundLot> lots = new LinkedHashMap<>();
        Map<Long, FoundPlanPoint> plans = new LinkedHashMap<>();
        try {
            for (String w : words) {
                collect(w, kz.toLocalDate(), lots, plans);
                job.progress++;
            }
            job.hits = rank(ps, lots.values(), plans.values(), kz.toLocalDateTime());
        } catch (RuntimeException e) {
            job.error = (ProductWatchService.stopping(e) ? "Поиск остановлен: " : "Поиск прерван: ") + ErrorText.of(e);
        }
    }

    /** Слова позиции: по которому она прошла группу и первое слово группы; без группы — два длинных не общих. */
    static List<String> wordsOf(Position p, CatalogSnapshot snap) {
        String text = ProductText.normalize(p.name() + " " + Objects.toString(p.brand(), ""));
        for (GroupRule r : snap.groups()) {
            if (r.brandGroup()) continue;   // брендов в названиях лотов почти нет — по ним ищет поиск по стране
            String w = r.typeWord(text, "", false);
            if (w == null) continue;
            List<String> out = new ArrayList<>(List.of(ProductText.normalize(w).strip()));
            if (!r.searchWords().isEmpty()) {
                String first = ProductText.normalize(r.searchWords().get(0)).strip();
                if (!first.equals(out.get(0))) out.add(first);
            }
            return out;
        }
        return ProductText.words(text).stream()
                .filter(x -> x.length() > 4 && !GENERIC_WORDS.contains(x) && x.chars().anyMatch(Character::isLetter))
                .distinct().sorted(Comparator.comparingInt(String::length).reversed()).limit(2).toList();
    }

    private void collect(String w, LocalDate today, Map<Long, FoundLot> lots, Map<Long, FoundPlanPoint> plans) {
        LocalDate from = today.minusDays(WINDOW_DAYS), to = today.plusDays(1);
        SearchPage<FoundLot> p = call(() -> search.searchLots(w, null, from, to, null));
        p.items().forEach(l -> lots.putIfAbsent(l.id(), l));
        if (p.nextAfter() != null && p.items().size() >= PAGE_FULL) {
            Long after = p.nextAfter();
            call(() -> search.searchLots(w, null, from, to, after)).items().forEach(l -> lots.putIfAbsent(l.id(), l));
        }
        Set<String> covered = lots.values().stream().map(FoundLot::trdBuyNumberAnno).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        int n = 0;
        for (FoundAnnouncement a : call(() -> search.searchAnnouncements(w, null, from, to, null)).items()) {
            if (n >= MAX_ANNOUNCEMENTS) break;
            if (a.numberAnno() == null || covered.contains(a.numberAnno())) continue;
            n++;
            for (FoundLot l : call(() -> search.lotsOfAnnouncement(a.numberAnno()))) {
                lots.putIfAbsent(l.id(), l.announcement() != null ? l : withAnnouncement(l, a));
            }
        }
        int year = today.getYear();
        call(() -> search.searchPlans(w, List.of(year, year + 1), ProcurementPlanService.APPROVED, null)).items()
                .forEach(pp -> plans.putIfAbsent(pp.id(), pp));
    }

    static FoundLot withAnnouncement(FoundLot l, FoundAnnouncement a) {
        return new FoundLot(l.id(), l.lotNumber(), l.nameRu(), l.descriptionRu(), l.count(), l.amount(), l.refLotStatusId(),
                l.lastUpdateDate(), l.customerBin(), l.customerNameRu(), a.numberAnno(), a);
    }

    List<TenderSearchHit> rank(List<Position> ps, Collection<FoundLot> lots, Collection<FoundPlanPoint> plans, LocalDateTime now) {
        Map<String, List<FoundLot>> byAnno = new LinkedHashMap<>();
        for (FoundLot l : lots) {
            if (l.trdBuyNumberAnno() != null) byAnno.computeIfAbsent(l.trdBuyNumberAnno(), k -> new ArrayList<>()).add(l);
        }
        List<Cand> tenderCands = new ArrayList<>();
        for (Map.Entry<String, List<FoundLot>> e : byAnno.entrySet()) {
            Cand c = new Cand();
            c.kind = "TENDER";
            c.anno = e.getKey();
            for (FoundLot l : e.getValue()) {
                String ann = l.announcement() == null ? "" : Objects.toString(l.announcement().nameRu(), "");
                Set<String> text = prefixSet(String.join(" ", Objects.toString(l.nameRu(), ""), Objects.toString(l.descriptionRu(), ""), ann));
                for (Position p : ps) {
                    double s = similarity(p, text);
                    if (s < MIN_SIMILARITY) continue;
                    c.positions.add(p.lineNo());
                    if (s > c.score) { c.score = s; c.head = l; }
                }
            }
            if (c.head == null) continue;
            FoundAnnouncement a = c.head.announcement();
            c.state = state(c.head.refLotStatusId(), a == null ? null : a.endDate(), now);
            tenderCands.add(c);
        }
        tenderCands.sort(Comparator.comparingDouble((Cand c) -> c.score).reversed());
        for (Cand c : tenderCands.subList(0, Math.min(SPEC_TOP, tenderCands.size()))) {
            String spec = spec(c.anno, c.head);
            if (spec != null) c.specPositions = specPositions(ps, spec);
        }
        List<Cand> all = new ArrayList<>(tenderCands);
        for (FoundPlanPoint pp : plans) {
            Cand c = new Cand();
            c.kind = "PLAN";
            c.plan = pp;
            c.state = "PLANNED";
            Set<String> text = prefixSet(String.join(" ", Objects.toString(pp.nameRu(), ""), Objects.toString(pp.descRu(), ""),
                    Objects.toString(pp.extraDescRu(), "")));
            for (Position p : ps) {
                double s = similarity(p, text);
                if (s < MIN_SIMILARITY) continue;
                c.positions.add(p.lineNo());
                c.score = Math.max(c.score, s);
            }
            if (!c.positions.isEmpty()) all.add(c);
        }
        all.sort(Comparator.comparingInt((Cand c) -> c.specPositions.size()).reversed()
                .thenComparing(Comparator.comparingDouble((Cand c) -> c.score).reversed())
                .thenComparingInt(c -> STATE_ORDER.indexOf(c.state)));
        List<Cand> top = all.subList(0, Math.min(MAX_HITS, all.size()));
        Map<String, Long> inAis = tenders.findBySourceExtIdIn(top.stream().filter(c -> c.anno != null).map(c -> c.anno).toList())
                .stream().collect(Collectors.toMap(Tender::getSourceExtId, Tender::getId, (x, y) -> x));
        return top.stream().map(c -> hit(c, inAis.get(c.anno))).toList();
    }

    private TenderSearchHit hit(Cand c, Long tenderId) {
        List<Integer> pos = List.copyOf(c.positions);
        double score = Math.round(c.score * 100) / 100.0;
        if (c.plan != null) {
            FoundPlanPoint p = c.plan;
            return new TenderSearchHit("PLAN", null, null, p.nameRu(), p.descRu(), p.subjectNameRu(),
                    regions.resolve(p.deliveryPlace(), p.subjectNameRu()), p.count(), p.price(), p.amount(), null, "PLANNED",
                    p.tradeMethod(), score, pos, List.of(), null, p.year(), p.month());
        }
        FoundLot l = c.head;
        FoundAnnouncement a = l.announcement();
        String customer = a != null && a.orgNameRu() != null ? a.orgNameRu() : l.customerNameRu();
        BigDecimal unit = l.amount() != null && l.count() != null && l.count().signum() > 0
                ? l.amount().divide(l.count(), 2, RoundingMode.HALF_UP) : null;
        return new TenderSearchHit("TENDER", c.anno, l.lotNumber(), l.nameRu(), a == null ? null : a.nameRu(), customer,
                regions.resolve(customer), l.count(), unit, l.amount(), a == null ? null : a.endDate(), c.state,
                a == null ? null : a.tradeMethodName(), score, pos, List.copyOf(c.specPositions), tenderId, null, null);
    }

    /** Состояние по статусу лота и сроку объявления (спека §11.1 п. 3). */
    static String state(Integer lotStatus, String endDate, LocalDateTime now) {
        LocalDateTime end = ImportDeadline.parse(endDate);
        if (lotStatus != null && ProductWatchService.OPEN_LOT.contains(lotStatus) && (end == null || end.isAfter(now))) return "OPEN";
        if (Objects.equals(lotStatus, 370)) return "FAILED";
        if (Objects.equals(lotStatus, 360)) return "DONE";
        if (Objects.equals(lotStatus, 410) || Objects.equals(lotStatus, 430)) return "CANCELLED";
        return "CLOSED";
    }

    static Set<String> prefixSet(String text) {
        return ProductText.words(ProductText.normalize(text)).stream().filter(ProductText::significant).map(ProductText::prefix)
                .collect(Collectors.toSet());
    }

    /** Доля начал значимых слов позиции (название + бренд), найденных в тексте кандидата. */
    static double similarity(Position p, Set<String> candidate) {
        List<String> words = prefixSet(p.name() + " " + Objects.toString(p.brand(), "")).stream().toList();
        if (words.isEmpty()) return 0;
        return (double) words.stream().filter(candidate::contains).count() / words.size();
    }

    /** Позиции, у которых окно из 3 или 2 значимых слов названия встречается в ТЗ (как фразы §5.4). */
    static List<Integer> specPositions(List<Position> ps, String spec) {
        List<String> sp = ProductText.prefixes(spec);
        Map<String, List<Integer>> idx = CatalogPhrases.index(sp);
        List<Integer> out = new ArrayList<>();
        for (Position p : ps) {
            List<String> w = ProductText.words(ProductText.normalize(p.name())).stream()
                    .filter(ProductText::significant).map(ProductText::prefix).toList();
            boolean found = false;
            for (int n = Math.min(3, w.size()); n >= 2 && !found; n--) {
                for (int i = 0; i + n <= w.size() && !found; i++) found = CatalogPhrases.occurs(w.subList(i, i + n), sp, idx);
            }
            if (found) out.add(p.lineNo());
        }
        return out;
    }

    /** ТЗ лота: разобранное в АИС — из базы; иначе PDF с площадки (без записи). Не вышло — null. */
    private String spec(String anno, FoundLot head) {
        try {
            if (head.lotNumber() != null) {
                List<String> parsed = tenders.findParsedLotSpec(anno, head.lotNumber());
                if (!parsed.isEmpty() && parsed.get(0) != null && !parsed.get(0).isBlank()) return parsed.get(0);
            }
            LotTechSpecRef ref = call(() -> client.fetchLotTechSpec(anno, head.nameRu()));
            if (ref == null || ref.filePath() == null) return null;
            byte[] pdf = call(() -> client.downloadFile(ref.filePath()));
            return TechSpecExtractor.russianSection(PdfTextExtractor.extract(pdf));
        } catch (RuntimeException e) {
            if (ProductWatchService.stopping(e)) throw e;
            log.debug("Найти тендеры: ТЗ {} не получено: {}", anno, e.toString());
            return null;
        }
    }

    private <T> T call(Supplier<T> s) {
        try {
            sleeper.sleep(PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("поиск прерван");
        }
        return UpstreamRetry.call(sleeper, s);
    }
}
```

`controller/TenderSearchController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.AnnouncementResult;
import com.vladoose.nir.dto.response.TenderSearchStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.service.productwatch.AnnouncementService;
import com.vladoose.nir.service.productwatch.ProductWatchService;
import com.vladoose.nir.service.productwatch.TenderSearchService;
import com.vladoose.nir.util.ErrorText;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/** «Найти тендеры» (спека §11, §13): поиск по заявке, «Итоги ›», «Завести в АИС». */
@RestController
public class TenderSearchController {

    private static final Pattern ANNO = Pattern.compile("^\\d{5,12}-\\d{1,3}$");

    private final TenderSearchService service;
    private final AnnouncementService announcements;
    private final ProductWatchService watch;

    public TenderSearchController(TenderSearchService service, AnnouncementService announcements, ProductWatchService watch) {
        this.service = service; this.announcements = announcements; this.watch = watch;
    }

    @PostMapping("/api/private-requests/{id}/tender-search")
    public TenderSearchStatus start(@PathVariable Long id) { return service.start(id); }

    @GetMapping("/api/private-requests/{id}/tender-search")
    public TenderSearchStatus status(@PathVariable Long id) { return service.status(id); }

    @PostMapping("/api/goszakup/announcements/{numberAnno}/import")
    @PreAuthorize("hasRole('ADMIN')")
    public Map<String, Long> importAnnouncement(@PathVariable String numberAnno) {
        check(numberAnno);
        return Map.of("tenderId", upstream(() -> watch.importAnnouncement(numberAnno)));
    }

    @GetMapping("/api/goszakup/announcements/{numberAnno}/result")
    public AnnouncementResult result(@PathVariable String numberAnno) {
        check(numberAnno);
        return upstream(() -> announcements.result(numberAnno));
    }

    private static void check(String numberAnno) {
        if (MarketContext.get() != Market.KZ) throw new BadRequestException("Только для Казахстана");
        if (numberAnno == null || !ANNO.matcher(numberAnno).matches()) throw new BadRequestException("Неверный номер объявления");
    }

    /** Отказ площадки — 502 со своим текстом (без адреса и токена), а не 500. */
    private static <T> T upstream(Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException e) {   // GoszakupCallException — его наследник
            throw new UpstreamException("goszakup не ответил: " + ErrorText.of(e));
        }
    }
}
```

- [ ] **Step 4: Frontend panel**

`shared/product-watch.ts` — дописать:

```ts
export type SearchState = 'OPEN' | 'FAILED' | 'CLOSED' | 'DONE' | 'CANCELLED' | 'PLANNED';
export interface TenderSearchHit {
  kind: 'TENDER' | 'PLAN'; numberAnno: string | null; lotNumber: string | null; lotName: string; announcement: string | null;
  customer: string | null; region: string | null; quantity: number | null; unitPrice: number | null; amount: number | null;
  deadlineAt: string | null; state: SearchState; tradeMethod: string | null; score: number; positions: number[];
  specPositions: number[]; tenderId: number | null; planYear: number | null; planMonth: number | null;
}
export interface TenderSearchStatus {
  running: boolean; progress: number; total: number; startedAt: string | null; waiting: string | null; error: string | null;
  hits: TenderSearchHit[];
}
export interface AnnouncementResult {
  status: string; supplierName: string | null; supplierBin: string | null; contractSum: number | null; signDate: string | null;
}

/** Номера позиций диапазонами: [1,2,3,5] → «1–3, 5». */
export function positionsText(xs: number[]): string {
  const s = [...xs].sort((a, b) => a - b);
  const out: string[] = [];
  for (let i = 0; i < s.length; i++) {
    let j = i;
    while (j + 1 < s.length && s[j + 1] === s[j] + 1) j++;
    out.push(j > i ? `${s[i]}–${s[j]}` : `${s[i]}`);
    i = j;
  }
  return out.join(', ');
}

/** «приём до 20.10 09:00», «не состоялся 16.10», «план: ноябрь 2026»… (дата — срок объявления, как его пишет площадка). */
export function stateText(h: TenderSearchHit): string {
  const d = h.deadlineAt && h.deadlineAt.length >= 10 ? `${h.deadlineAt.slice(8, 10)}.${h.deadlineAt.slice(5, 7)}` : '';
  const t = h.deadlineAt && h.deadlineAt.length >= 16 ? ' ' + h.deadlineAt.slice(11, 16) : '';
  switch (h.state) {
    case 'OPEN': return `приём до ${d}${t}`.trim();
    case 'FAILED': return `не состоялся ${d}`.trim();
    case 'CLOSED': return `приём закрыт ${d}`.trim();
    case 'DONE': return `состоялся ${d}`.trim();
    case 'CANCELLED': return 'отменён';
    default: return 'план: ' + monthText(h.planYear, h.planMonth);
  }
}
```

`ApiService` (импорт типов `TenderSearchStatus`, `AnnouncementResult`):

```ts
  startTenderSearch(id: number): Observable<TenderSearchStatus> {
    return this.http.post<TenderSearchStatus>(`${this.base}/private-requests/${id}/tender-search`, {});
  }
  getTenderSearch(id: number): Observable<TenderSearchStatus> {
    return this.http.get<TenderSearchStatus>(`${this.base}/private-requests/${id}/tender-search`);
  }
  importAnnouncement(anno: string): Observable<{ tenderId: number }> {
    return this.http.post<{ tenderId: number }>(`${this.base}/goszakup/announcements/${encodeURIComponent(anno)}/import`, {});
  }
  announcementResult(anno: string): Observable<AnnouncementResult> {
    return this.http.get<AnnouncementResult>(`${this.base}/goszakup/announcements/${encodeURIComponent(anno)}/result`);
  }
```

`frontend/src/app/pages/private-requests/pr-tender-search.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, EventEmitter, Input, OnChanges, OnDestroy, Output, SimpleChanges } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import type { PrView } from '../../shared/private-request';
import { money } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';
import { positionsText, stateText } from '../../shared/product-watch';
import type { AnnouncementResult, TenderSearchHit, TenderSearchStatus } from '../../shared/product-watch';

/**
 * «Тендеры по стране» на странице заявки (спека 2026-10-19-product-watch §11): тендеры и планы закупок по всей стране
 * с позициями заявки — кто ещё покупает такое, и не перекупщик ли клиент под тендер. Только KZ; искать могут все.
 */
@Component({
  selector: 'app-pr-tender-search',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <section class="card">
      <div class="sec-head">
        <h3>Тендеры по стране</h3>
        <button type="button" class="btn btn-line" *ngIf="view.lines.length" [disabled]="running" (click)="start()">
          {{ running ? 'Ищу…' : (status ? 'Искать заново' : 'Найти тендеры по стране') }}</button>
      </div>
      <p class="hint" *ngIf="!status && !running">Тендеры за 90 дней и планы закупок на этот и следующий год с позициями
        этой заявки — по всей стране. Поиск занимает до минуты.</p>
      <p class="hint" *ngIf="running">{{ status?.waiting || ('Ищу… слово ' + (status?.progress || 0) + ' из ' + (status?.total || '…')) }}</p>
      <p class="ts-error" *ngIf="status?.error">{{ status?.error }}</p>
      <p class="hint" *ngIf="status && !running && !status.error && !status.hits.length">Ничего похожего по стране за 90 дней и в планах.</p>
      <div class="ts-hit" *ngFor="let h of status?.hits || []">
        <div class="ts-top">
          <span class="ts-state" [attr.data-state]="h.state">{{ stateText(h) }}</span>
          <span class="ts-match" *ngIf="h.specPositions.length">совпали позиции {{ positionsText(h.specPositions) }} (ТЗ)</span>
          <span class="ts-weak" *ngIf="!h.specPositions.length">похоже на {{ h.positions.length > 1 ? 'позиции' : 'позицию' }} {{ positionsText(h.positions) }}</span>
        </div>
        <div class="ts-cust">{{ h.customer }}<ng-container *ngIf="h.region"> · {{ h.region }}</ng-container></div>
        <div class="ts-lot">{{ h.lotName }}<ng-container *ngIf="h.quantity != null"> — {{ qty(h.quantity) }} шт.</ng-container><ng-container
          *ngIf="h.unitPrice != null"> · {{ money(h.unitPrice) }} ₸ за ед.</ng-container><ng-container
          *ngIf="h.unitPrice == null && h.amount != null"> · {{ money(h.amount) }} ₸</ng-container></div>
        <div class="ts-method" *ngIf="h.tradeMethod">{{ h.tradeMethod }}</div>
        <div class="ts-acts" *ngIf="h.kind === 'TENDER' && h.numberAnno as anno">
          <a class="xref" [href]="portal(anno)" target="_blank" rel="noopener">goszakup № {{ anno }} ↗</a>
          <button type="button" class="btn btn-line btn-sm" *ngIf="h.state === 'DONE' || h.state === 'CLOSED'"
                  [disabled]="loadingResult.has(anno)" (click)="result(anno)">Итоги ›</button>
          <a class="btn btn-line btn-sm" *ngIf="h.tenderId" [routerLink]="['/tenders']" [queryParams]="{ openId: h.tenderId }">Открыть в АИС</a>
          <button type="button" class="btn btn-line btn-sm" *ngIf="!h.tenderId && !readonly" [disabled]="importing === anno"
                  (click)="importIt(h, anno)">{{ importing === anno ? 'Завожу…' : 'Завести в АИС' }}</button>
          <button type="button" class="btn btn-line btn-sm" *ngIf="!readonly" [disabled]="notedAnno.has(anno)"
                  (click)="note(h, anno)">{{ notedAnno.has(anno) ? 'В истории ✓' : 'В историю сделки' }}</button>
        </div>
        <div class="ts-result" *ngIf="h.numberAnno && results[h.numberAnno] as r">{{ resultText(r) }}</div>
        <div class="ts-acts" *ngIf="h.kind === 'PLAN'"><a class="xref" routerLink="/procurement-plans">Планы закупок ›</a></div>
      </div>
    </section>
  `,
  styles: [`
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px;
            display: flex; flex-direction: column; gap: 8px; }
    .sec-head { display: flex; justify-content: space-between; align-items: center; gap: 8px; flex-wrap: wrap; }
    h3 { margin: 0; font-size: 15px; }
    .hint { margin: 0; font-size: 12px; color: var(--text-muted); }
    .ts-error { margin: 0; font-size: 13px; color: var(--danger-text); }
    .ts-hit { border: 1px solid var(--border); border-radius: 10px; padding: 10px 12px; display: flex; flex-direction: column; gap: 4px; }
    .ts-top { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; font-size: 13px; }
    .ts-state { font-weight: 600; padding: 2px 8px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .ts-state[data-state="OPEN"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .ts-state[data-state="FAILED"] { background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .ts-state[data-state="PLANNED"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .ts-match { color: var(--success-text); font-weight: 600; }
    .ts-weak, .ts-cust, .ts-method { color: var(--text-muted); }
    .ts-cust, .ts-method { font-size: 13px; }
    .ts-lot { color: var(--text); overflow-wrap: anywhere; }
    .ts-acts { display: flex; gap: 8px; flex-wrap: wrap; align-items: center; font-size: 13px; }
    .ts-result { font-size: 13px; color: var(--text); }
    @media (max-width: 900px) { .ts-acts .btn { min-height: 40px; } }
  `],
})
export class PrTenderSearchComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  /** Заметка ушла в историю — странице перечитать ленту. */
  @Output() changed = new EventEmitter<void>();

  readonly money = money;
  readonly stateText = stateText;
  readonly positionsText = positionsText;
  status: TenderSearchStatus | null = null;
  results: Record<string, AnnouncementResult> = {};
  loadingResult = new Set<string>();
  notedAnno = new Set<string>();
  importing: string | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private forId: number | null = null;

  constructor(private api: ApiService, private notify: NotificationService, private cdr: ChangeDetectorRef,
              private destroyRef: DestroyRef) {}

  get running(): boolean { return !!this.status?.running; }

  ngOnChanges(ch: SimpleChanges) {
    if (!ch['view'] || this.view.id === this.forId) return;
    this.forId = this.view.id;   // другая заявка: прежний результат не показываем
    this.stop();
    this.status = null; this.results = {}; this.notedAnno.clear(); this.importing = null;
    const id = this.view.id;
    this.api.getTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { if (this.forId !== id) return; this.status = s.startedAt ? s : null; this.schedule(); this.cdr.detectChanges(); },
      error: () => {},   // токена нет или чужой рынок — кнопка покажет причину при нажатии
    });
  }

  ngOnDestroy() { this.stop(); }

  start() {
    const id = this.view.id;
    this.api.startTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: s => { if (this.forId !== id) return; this.status = s; this.schedule(); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }

  private schedule() {
    this.stop();
    if (!this.status?.running) return;
    const id = this.view.id;
    this.timer = setTimeout(() => {
      this.api.getTenderSearch(id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
        next: s => { if (this.forId !== id) return; this.status = s; this.schedule(); this.cdr.detectChanges(); },
        error: () => this.schedule(),
      });
    }, 2000);
  }

  private stop() { if (this.timer) { clearTimeout(this.timer); this.timer = null; } }

  portal(anno: string): string { return `https://goszakup.gov.kz/ru/announce/index/${anno.split('-')[0]}`; }

  qty(n: number): string { return Number.isInteger(n) ? String(n) : String(n).replace('.', ','); }

  result(anno: string) {
    this.loadingResult.add(anno);
    this.api.announcementResult(anno).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.loadingResult.delete(anno); this.results[anno] = r; this.cdr.detectChanges(); },
      error: e => { this.loadingResult.delete(anno); this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  resultText(r: AnnouncementResult): string {
    if (r.status !== 'договор есть') return r.status;
    const who = r.supplierName || (r.supplierBin ? 'БИН ' + r.supplierBin : 'поставщик не указан');
    const sum = r.contractSum != null ? `, договор ${money(r.contractSum)} ₸` : ', договор';
    const date = r.signDate ? ` от ${r.signDate.slice(8, 10)}.${r.signDate.slice(5, 7)}.${r.signDate.slice(0, 4)}` : '';
    return `победитель: ${who}${sum}${date}`;
  }

  importIt(h: TenderSearchHit, anno: string) {
    this.importing = anno;
    this.api.importAnnouncement(anno).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: r => { this.importing = null; h.tenderId = r.tenderId; this.notify.success(`Тендер ${anno} заведён в АИС`); this.cdr.detectChanges(); },
      error: e => { this.importing = null; this.notify.error(errorText(e)); this.cdr.detectChanges(); },
    });
  }

  note(h: TenderSearchHit, anno: string) {
    const who = [h.customer, h.region].filter(x => !!x).join(', ');
    const text = `Запрос клиента совпадает с тендером ${anno}${who ? ' (' + who + ')' : ''} — ${stateText(h)}`;
    this.api.addDealEvent('private-request', this.view.id, 'NOTE', text).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: () => { this.notedAnno.add(anno); this.notify.success('Записано в историю сделки'); this.changed.emit(); this.cdr.detectChanges(); },
      error: e => this.notify.error(errorText(e)),
    });
  }
}
```

`private-request-page.component.ts`: импорт `PrTenderSearchComponent` в `imports`, разметка — сразу после `linesAnchor`:

```html
          <app-pr-tender-search *ngIf="isKz" [view]="v" [readonly]="!auth.isAdmin()" (changed)="timeline?.refresh()"></app-pr-tender-search>
```

- [ ] **Step 5: Run tests, build, browser**

Run: `./gradlew test --tests '*TenderSearchServiceTest*' --tests '*AnnouncementServiceTest*' --tests '*RegionResolverTest*'` и `cd frontend && npm run build`.
Живьём — заявка KZ с позициями набора АСД-Г5.1 (как у Horeca_1) → «Найти тендеры по стране» → Астана сверху (если ещё в окне 90 дней), «Итоги ›», «В историю сделки» → строка в ленте; оператор — без «Завести» и «В историю»; 1280 / 390, обе темы. Тестовую заявку — удалить.

- [ ] **Step 6: Mutation check**

1. `rank`: сортировка без `specPositions` → `horecaPositions_astanaFirst_bySpec` красный (вторым встаёт «Аспиратор хирургический»).
2. `spec()` всегда `null` → тот же тест красный (`specPositions` пуст).
3. `wordsOf` без слова, по которому позиция прошла группу (только первое слово группы), → тот же тест красный (`lots:аспирации` не звался).
4. `wordsOf` без `GENERIC_WORDS` → `genericOnlyPositions_nothingToSearch_noRequests` красный.
5. `run`: стоп-код как «Поиск прерван» → `forbidden_stopsWithReason` красный.
6. `AnnouncementService.result`: `contractSum` вместо `contractSumWnds` → `result_contractWithSupplierName` красный.
7. `importAnnouncement` без проверки «уже в АИС» → `importAnnouncement_withoutOurLot_createdOnce` красный.
8. `RegionResolver` без «астаны» → `astanaDeclined` красный.
Откат копией файла, `./gradlew compileJava`.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/productwatch frontend/src/app
git commit -m "feat(product-watch): «Найти тендеры» в частной заявке — по стране, итоги, «Завести в АИС», «В историю сделки»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 13: Живая проверка, документация, ревью ветки, полный прогон

**Files:**
- Modify: `docs/superpowers/specs/2026-10-19-product-watch-design.md` (§19 «Решения при планировании», §20 «Решения при реализации»), `CLAUDE.md` (§5 «локально», §7 модель V36, §8 блок, §12 фронт, §13 прогон, §15 API, §16 бэклог), `docs/PROGRESS.md` («▶ Последняя задача»), `DEPLOY.md` (новый раздел «Наша продукция в тендерах»), память `last-task-pointer`
- Ledger: `.superpowers/sdd/2026-10-19-product-watch/progress.md`

- [ ] **Step 1: Full live check (локально, малыми порциями токена)**

Стек: `JAVA_TOOL_OPTIONS=-Xmx1g PRODUCT_WATCH_ENABLED=true GOSZAKUP_TOKEN="$(cat ~/.config/ais/goszakup.token)" TELEGRAM_ENABLED=true TELEGRAM_API_URL=http://127.0.0.1:7709 TELEGRAM_BOT_TOKEN=dev:token TELEGRAM_CHAT_ID=-1001 TELEGRAM_TENDERS_THREAD_ID=55 TENDERS_NOTIFY_ENABLED=true PRODUCT_WATCH_CRON=- PRODUCT_WATCH_PLANS_CRON=- ./gradlew bootRun` (sandbox off) + `node scripts/telegram-stub.mjs` + `cd frontend && npm start`. Записать до проверки: `select max(id) from tender; select count(*) from tender_lot_product_match; select count(*) from procurement_plan_point;`.
1. «Наша продукция»: 24 группы; на время проверки выключить все группы, кроме «Отсасыватели и аспирация дыма», «Электрокардиографы», «Наши бренды и модели» (токен!); «Обновить» каталог → «196 товаров, без описаний (публичный список)»;
2. «Искать сейчас» → в логе итог; новые тендеры (если есть открытые) — в «Тендерах» с чипом, регион, «Найден поиском…»; отметки у лотов; ТЗ сверены (`spec_checked_at`); `curl -s 127.0.0.1:7709/__messages` — одно сообщение, `silent` false при ★;
3. повторный «Искать сейчас» — сообщений нет; 
4. фильтр «★ Точно наше», `/tenders?our=ANY`;
5. «Планы закупок» → «Собрать сейчас» (две группы) → страница, фильтры; сводка — первый раз тишина;
6. частная заявка KZ с позициями АСД-Г5.1 → «Найти тендеры по стране» → Астана (17779323-1 / 17802320-1) сверху с «совпали позиции … (ТЗ)», «Итоги ›»;
7. 1280 / 390, обе темы, оператор (только просмотр, 0 запросов записи), РФ — «Только для Казахстана».
Уборка (только dev): остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), затем `delete from tender where id > <max> and found_by_product_search; delete from procurement_plan_point; update product_group set active = true where market = 'KZ';` (и вернуть поправленное на странице), удалить тестовую заявку; заглушку и `ng serve` — по LISTEN-портам.

- [ ] **Step 2: Branch review (самому, CLAUDE.md §2–3)**

`git diff main...feature/product-watch` целиком свежим взглядом: гард рынка у всех путей (`findById` = `em.find` — фильтр не режет, §14), сеть вне транзакций, `MarketContext` в потоках, пределы ввода, токен не в логах, тексты — по-русски, классы фронта не совпадают с UI-kit (`grep` по `styles.scss`), начальный бандл не вырос (> 1,40 МБ — разобраться). Найденное — правка с тестом, красным до неё.

- [ ] **Step 3: Full test run**

Run: `lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill; lsof -ti tcp:3143 -sTCP:LISTEN | xargs kill; ./gradlew cleanTest test` (sandbox off)
Expected: 0 падений (1 skipped — калибровочный отчёт реестра); число тестов и классов — в CLAUDE.md §13.

- [ ] **Step 4: Documentation**

- спека §19 — решения при планировании (из «Решений» ниже) и §20 — при реализации (по леджеру);
- CLAUDE.md: §5 — «Наша продукция — локально» (команда бэкенда из шага 1, уборка); §7 — V36; §8 — новый блок «Наша продукция в тендерах» (как устроено, гейт, проверено); §12 — фронт; §13 — прогон; §15 — пути; §16 — вне рамок и мелочи ревью; следующая свободная миграция — V37;
- DEPLOY.md — раздел: включение (`PRODUCT_WATCH_ENABLED=true` в `/srv/ais/.env`, перезапуск бэкенда), проверка (лог «Поиск нашей продукции: …», страница «Наша продукция», первый прогон молча размечает), нагрузка на токен, выключение и откат (без SQL);
- PROGRESS «▶ Последняя задача» и память `last-task-pointer` — что сделано, что в проде (после push оператора), что ждёт.

- [ ] **Step 5: Merge**

```bash
git checkout main && git merge --ff-only feature/product-watch && git branch -d feature/product-watch
```

Push — оператор (`! git push origin main`, CLAUDE.md §5); после деплоя — по согласию оператора: V36 на проде, лог без ошибок; включение — по DEPLOY.md.

---

## Решения при планировании

1. **Отдельный интерфейс поиска** `GoszakupSearchClient` (реализует тот же `GoszakupHttpClient`): прежний `GoszakupClient` и его тестовый фейк не меняются.
2. **Код ответа в `GoszakupCallException`** (`status()`): стоп-коды 401 / 403 / 429 различаются по числу, а не по тексту.
3. **`brand_group` — колонка**, а не название группы: признак «находки сразу NAMED» не зависит от правки названия на странице.
4. **Уточнения у многолотового объявления проверяются только по тексту лота** (спека §5.2 п. 2 говорила «лот + название объявления»): общее название «Приобретение медицинских изделий» иначе пропускало бы любой «Светильник светодиодный» через уточнение «медицин».
5. **Фразы ТЗ — окна по 2–4 значимых слова внутри отрезка, отрезок засчитывается один раз**; список общих фраз — файлом `product-watch/generic-phrases.txt`, его дополняет калибровка (задача 6): общие названия принадлежностей ФОТЕК («педаль двухклавишная», «нейтральный электрод», «блок управления») иначе давали бы «★ списано» любому ТЗ электрохирургического аппарата.
6. **Коды моделей в общем списке** — только с ≥ 2 цифрами и ≥ 5 знаками без разделителей («ДКИ-Н-11» — да, «СК-16», «SpO2» — нет); короткие коды — только в своей группе.
7. **Каталог — карточка `/api/admin/products/{id}` у каждого товара при каждой загрузке**: в админ-списке нет `updatedAt` (спека §6 предполагала «только у изменённых»); 196 запросов раз в сутки к своему сайту.
8. **Пересчёт после сохранения групп — в фоне** (исполнитель `product-watch`), а не в запросе: поиск по стране пишет отметки в том же исполнителе, параллельная запись упёрлась бы в уникальность «лот × группа»; тост — «отметки пересчитываются».
9. **Отметки у импорта ЗКО и СК-Фармации — по `touchedExtIds`** (созданные и обновлённые прогоном), ТЗ их лотов сверяет ближайший поиск по стране.
10. **«Найти тендеры» — отдельным разделом под позициями** (`app-pr-tender-search`), а не кнопкой в шапке раздела позиций: у результатов свой объём и опрос.
11. **ТЗ для «Найти тендеры» не пишется в базу** — только у заведённых в АИС; без заведения разбор каждый раз заново (10 ТЗ на нажатие).
12. **Снимок каталога в тестах** — публичный список (без описаний); с описаниями — только по согласию оператора (задача 6, шаг 4).
13. **Слова «Найти тендеры»** — слово, по которому позиция прошла группу, и первое слово группы (спека §11.1 п. 1 — «слова поиска этой группы»: у «Отсасыватели и аспирация дыма» их три, позиция «Блок аспирации» прошла по третьему); всего не больше 6 слов, лоты найденных объявлений — у 3 объявлений на слово, которых ещё нет среди лотов: так нажатие укладывается в 30–60 запросов (§11.1 п. 6). Прогресс — «слово 2 из 6», а не «позиция 3 из 8»: запросы идут по словам.
14. **Состояние «приём закрыт ДД.ММ»** (`CLOSED`) — лот после срока в статусе рассмотрения (не 360 / 370 / 410 / 430): спека §11.1 п. 3 его не называла, а «состоялся» про такой лот было бы неправдой; «Итоги ›» — у «состоялся» и «приём закрыт».
15. **Падежи Астаны в `RegionResolver`** — полное имя заказчика «…акимата города Астаны» не содержит «астана», и регион Астаны не определялся ни в «Найти тендеры», ни в планах.
16. **Сводка планов — по признаку `digested_at`, а не «новые за сутки»**: не ушедшая сводка повторяется со следующим сбором, выключенный Telegram ничего не копит.
17. **Выборка «Планов закупок» — отдельный сервис `ProcurementPlanQuery`** (контроллер → сервис, как во всём проекте), фильтры — в памяти: пунктов сотни.
