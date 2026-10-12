# Частная заявка — этап 3: КП клиенту из заявки — план

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** «Создать КП клиенту» в частной заявке собирает черновик КП из её позиций — с РУ, производителем, ставкой НДС и ценой закупки из ответов поставщиков; в редакторе КП — «из заявки ЧЗ-…», «Поставщик ▾» в строке и плашка «Пришли новые цены — Подставить»; в заявке — раздел «КП клиенту» и этап по КП.

**Architecture:** Новый сервис `ClientOfferRequestService` (пакет `service`) — создание КП из заявки, ответы поставщиков на позицию строки, новые цены. Правило цены закупки — чистая функция `PurchaseOffers` (`service/offer`), текст регистрации — `util/RegistrationText` (его же возьмёт часть 3 волны 2). Ссылка строки КП на ответ (`price_request_item_id`) пишется существующим PUT КП с гардом в `ClientOfferService`. Миграций нет: колонки связей есть с V23.

**Tech Stack:** Spring Boot 3.5.6 / Java 17 / Hibernate 6 / PostgreSQL 17 (nirdb); Angular 21 standalone, ленивые страницы.

**Spec:** `docs/superpowers/specs/2026-10-12-private-request-workspace-design.md` — §8 (главное), §5.1 («КП по заявке» в шапке и разделе), §5.4 правило 2 (этап по КП), §5.6 (кнопка «КП клиенту» на телефоне), §9 (три строки этапа 3), §10, §11, §12, §13 п. 3.

## Global Constraints

- Без миграций (спека §13: «колонки связей есть в V23»).
- Запись — только `ADMIN`, чтение — любой вошедший.
- Гард рынка по id: КП — `ClientOfferService.get` (чужой рынок — 404), заявка — `PrivateRequestService.findById` (только `PRIVATE_REQUEST` и текущий рынок, иначе 404).
- КП — снимок: цены в нём сами не меняются; «Подставить» — только по нажатию. Позиции, добавленные в заявку после создания КП, в него не попадают.
- Расчёт КП — волна 1 без изменений (`ClientOfferCalculator` не трогать); наценка — общая наценка КП из умолчаний «Реквизитов».
- Тире в тексте, который документ ставит сам, — `DocFormat.DASH` (длинного «—» в документах нет, решение оператора 2026-10-12).
- Тесты — `@SpringBootTest @Transactional` на nirdb; MockMvc — `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())` (новых особых контекстов не заводить, CLAUDE.md §14); полный прогон — `./gradlew cleanTest test`.
- Фронт: новое — только в ленивых чанках, типы — `import type`, промисы — `.then`, файлы с `/api` — только `HttpClient` blob; гейт — `npm --prefix frontend run build`.
- Коммиты кончаются строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`; все делегируемые агенты — Opus.

## Review Focus

1. **Строку ответа удалили** (удалён запрос цен) после того, как строка КП на неё сослалась: автосохранение КП не падает — ссылка молча снимается. Тест — задача 3 `unknownResponseLineDropsLinkSilently`.
2. **Поставщик поменял цену по уже выбранному ответу** — плашка предлагает её; сознательно выбранный не самый дешёвый поставщик с неизменной ценой и строка с ручной закупкой не трогаются. Тест — задача 4 `bannerCountsEmptyAndChangedOnly_deliberateChoiceUntouched`.
3. **Позицию удалили из заявки после создания КП** (`tender_lot_id` → пусто по `ON DELETE SET NULL`): строка КП остаётся, плашка её не считает, «Поставщик ▾» пуст, PUT проходит. Тест — задача 4 `positionDeletedAfterKpKeepsRowAndIsIgnored`.
4. **Заявка без клиента:** «Кому» в КП пусто, а не «Клиент не указан» (это уйдёт в печать); только контакт — контакт; телефон «Кому» не становится. Тест — задача 2 `recipientNeverPrintsPlaceholder`.
5. **Новая строка со ссылкой на ответ** (копия строки, API): у новой строки нет позиции — 400; поэтому фронт снимает ссылку у копии, иначе каждое автосохранение получало бы 400. Тест — задача 3 `newRowWithLinkIs400`, фронт — задача 7 (`duplicateRow`).

## Решения при планировании (уточнения спеки)

Решения ниже приняты при планировании; при ревью сверять код с ними, а не с текстом спеки.

1. **«Кому»** = учреждение → название клиента → контактное лицо; нет ни того ни другого — пусто. Телефон и «Клиент не указан» в «Кому» не ставятся: это печатный документ (§5.1 описывает показ на экране).
2. **Кандидаты в закупку** — по каждому поставщику его **последняя** строка ответа с ценой по позиции (как ячейка таблицы цен заявки), среди запросов не `REJECTED` / `CLOSED` (ревью ветки: в плане было и `DECLINED` — но цена у отказавшего запроса — это цена, вписанная после отказа, таблица цен ставит ей ★; исключение убрано, спека §8 уточнена). Иначе старая цена поставщика, которую он потом поменял новым ответом, могла бы стать «лучшей». Тот же набор — в «Поставщик ▾». Сначала самая дешёвая среди `ACCEPTED`, иначе самая дешёвая; равенство — более ранний запрос (меньший id).
3. **Текст регистрации** — «№ {номер} от {ДД.ММ.ГГГГ} г., бессрочно» / «№ … от … г., до {ДД.ММ.ГГГГ} г.» (через запятую); даты регистрации нет — «№ {номер}» и срок; записи нет в реестре — только «№ {номер}», производитель — бренд позиции.
4. **Регистрация строки:** `CONFIRMED` → `CONFIRMED` (производитель и страна — из реестра, с усечением до 500 / 200 символов), ставка `vatRegistered`; `NOT_REQUIRED` → `NOT_REQUIRED`, «Не подлежит регистрации», `vatNotRegistrable`; `SUGGESTED` / `NOT_FOUND` / пусто → `UNCHECKED` («не проверено»), производитель — бренд, `vatDefault`. `SUGGESTED` в КП не ставим: подсказка не печатается, а подтвердить её в КП нечем.
5. **Ссылка строки на ответ в PUT:** та же, что сохранена, — не перепроверяется (снимок: запрос потом закрыли — ссылка живёт); пусто — снимается вместе с `distributor_id`; новая — проверяется: строка ответа заявки этого КП, позиции этой строки и с ценой, иначе 400; несуществующий id — ссылка молча снимается (строку удалили вместе с запросом — Review Focus 1). У новой строки (без id) позиции нет — ссылка на любой ответ даёт 400.
6. **Ручная правка закупки снимает ссылку на фронте** (`setPurchase`, только если значение изменилось); сервер ссылку из цены не выводит: иначе любое автосохранение после новой цены поставщика снимало бы ссылку, и плашка её не увидела бы.
7. **`distributor_id`** — только из ответа (сервер); без ссылки — пусто. **`tender_lot_id`** ставится при создании и с клиента не меняется; у новых строк — пусто. Оба поля в ответе — только для чтения.
8. **Плашка** считается при открытии КП и после каждого удачного сохранения (одним GET); «Подставить» — через `flushThen` редактора → POST → редактор берёт КП из ответа целиком. Версия в POST не нужна: «Подставить» поднимает версию, вторая вкладка получит 409.
9. **Этап заявки по КП** (по лучшему статусу): `KP_ACCEPTED` «КП принято» (`success`), `KP_SENT` «КП отправлено» (`accent`), `KP_DRAFT` «КП — черновик» (`neutral`), все отклонены — `KP_REJECTED` «КП отклонено» (`danger`). Закрытая заявка остаётся «Закрыта: …».
10. **«Создать КП клиенту»** разрешено и у закрытой заявки, и повторно (несколько КП по заявке); у заявки без позиций кнопки нет (сервер не запрещает — пустое КП безвредно).
11. **Номер заявки в ответах КП** (`tenderNumber`) — для «из заявки ЧЗ-…» в шапке редактора и в журнале; журнал берёт номера одним запросом.
12. **Сумма позиции из ответа больше предела** — 400 при создании (`checkAmounts`, как в PUT), а не ошибка базы.

## Карта файлов

**Бэкенд — создать:**
- `util/RegistrationText.java`
- `service/offer/PurchaseOffer.java`, `service/offer/PurchaseOffers.java`
- `service/ClientOfferRequestService.java`
- `dto/response/SupplierOfferResponse.java`, `dto/response/NewPricesResponse.java`, `dto/response/PrivateRequestOfferRef.java`
- тесты: `util/RegistrationTextTest`, `clientoffer/PurchaseOffersTest`, `clientoffer/ClientOfferFromRequestTest`, `clientoffer/ClientOfferResponseLinkTest`, `clientoffer/ClientOfferNewPricesTest`, `privaterequest/PrivateRequestStage3Test`

**Бэкенд — изменить:**
- `repository/PriceRequestItemRepository.java` (+ `findPricedByTenderId`), `repository/ClientOfferRepository.java` (+ `findByTenderIdOrderByIdDesc`, `findStatusesByTenderIds`), `repository/TenderRepository.java` (+ `findNumbersByIds`)
- `service/ClientOfferService.java` (`checkAmounts` — package-private; гард ссылки в `checkItems` / `applyItem`; + `PriceRequestItemRepository`)
- `service/ClientOfferMapper.java` (+ `tenderNumber`, поля ссылки, `toListItems`)
- `controller/ClientOfferController.java` (+ 4 пути)
- `dto/request/ClientOfferItemDto.java` (+ `priceRequestItemId`, только для чтения — `tenderLotId`, `distributorId`)
- `dto/response/ClientOfferResponse.java`, `ClientOfferListItemResponse.java` (+ `tenderNumber`), `PrivateRequestViewResponse.java` (+ `offers`)
- `service/privaterequest/PrivateRequestViewBuilder.java`, `PrivateRequestStageCalculator.java`

**Фронт — создать:** `pages/private-requests/pr-offers.component.ts`

**Фронт — изменить:** `shared/client-offer.ts`, `shared/private-request.ts`, `services/api.service.ts`, `pages/private-requests/private-request-page.component.ts`, `pages/client-offers/client-offers.component.ts`, `pages/client-offers/client-offer-editor.component.ts`, `pages/client-offers/client-offer-items.component.ts`

**Документация:** `CLAUDE.md` (§8, §12, §13, §15, §16), `docs/PROGRESS.md`, память `last-task-pointer.md`.

---

### Task 1: Правило цены закупки и текст регистрации

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/RegistrationText.java`
- Create: `src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java`
- Create: `src/main/java/com/vladoose/nir/service/offer/PurchaseOffers.java`
- Test: `src/test/java/com/vladoose/nir/util/RegistrationTextTest.java`, `src/test/java/com/vladoose/nir/clientoffer/PurchaseOffersTest.java`

**Interfaces:**
- Produces: `RegistrationText.of(String regNumber, MedRegistry record) → String` (пустой номер — `null`); `record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId, String supplierName, BigDecimal price, String status, LocalDate respondedOn)` с `static PurchaseOffer of(PriceRequestItem)`; `PurchaseOffers.EXCLUDED`, `PurchaseOffers.current(Collection<PurchaseOffer>) → List<PurchaseOffer>` (без исключённых статусов, последняя строка каждого поставщика, по цене, затем по id запроса), `PurchaseOffers.best(List<PurchaseOffer>) → Optional<PurchaseOffer>`.

- [ ] **Step 1: Write the failing tests**

`src/test/java/com/vladoose/nir/util/RegistrationTextTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.entity.MedRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** Текст регистрации строки КП из записи реестра (спека 2026-10-12 §8, решение плана 3). */
class RegistrationTextTest {

    private static MedRegistry rec(LocalDate reg, LocalDate exp, Boolean unlimited) {
        MedRegistry m = new MedRegistry();
        m.setRegDate(reg);
        m.setExpirationDate(exp);
        m.setUnlimited(unlimited);
        return m;
    }

    @Test
    void unlimited() {
        assertThat(RegistrationText.of("РК-МИ (МТ)-0№023037", rec(LocalDate.of(2021, 10, 28), null, true)))
                .isEqualTo("№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., бессрочно");
    }

    @Test
    void untilDate() {
        assertThat(RegistrationText.of("РК-МИ (МТ)-0№023037",
                rec(LocalDate.of(2021, 10, 28), LocalDate.of(2026, 10, 28), false)))
                .isEqualTo("№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., до 28.10.2026 г.");
    }

    @Test
    void withoutDatesOrRecord_numberOnly() {
        assertThat(RegistrationText.of("РК МИ (ИМН)-0 №027522", rec(null, null, null))).isEqualTo("№ РК МИ (ИМН)-0 №027522");
        assertThat(RegistrationText.of(" РК-МИ (МТ)-0№1 ", null)).isEqualTo("№ РК-МИ (МТ)-0№1");
    }

    @Test
    void blankNumberGivesNothing() {
        assertThat(RegistrationText.of("  ", rec(LocalDate.of(2021, 1, 1), null, true))).isNull();
        assertThat(RegistrationText.of(null, null)).isNull();
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/PurchaseOffersTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Цена закупки по умолчанию (спека 2026-10-12 §8, решение плана 2). */
class PurchaseOffersTest {

    private static PurchaseOffer o(long item, long request, long distributor, String price, String status) {
        return new PurchaseOffer(item, request, 7L, distributor, "Поставщик " + distributor, new BigDecimal(price), status, null);
    }

    @Test
    void acceptedBeatsCheaperResponded() {
        assertThat(PurchaseOffers.best(PurchaseOffers.current(List.of(
                o(1, 10, 1, "100", "RESPONDED"), o(2, 11, 2, "120", "ACCEPTED"))))).get()
                .extracting(PurchaseOffer::priceRequestItemId).isEqualTo(2L);
    }

    @Test
    void declinedRejectedClosedAreNotCandidates() {
        assertThat(PurchaseOffers.current(List.of(o(1, 10, 1, "50", "DECLINED"), o(2, 11, 2, "60", "REJECTED"),
                o(3, 12, 3, "70", "CLOSED"), o(4, 13, 4, "80", "SENT"))))
                .extracting(PurchaseOffer::priceRequestItemId).containsExactly(4L);
    }

    @Test
    void latestPricedLinePerSupplier_olderPriceIsNotOffered() {
        assertThat(PurchaseOffers.current(List.of(o(1, 10, 1, "90", "RESPONDED"), o(2, 15, 1, "130", "RESPONDED"))))
                .extracting(PurchaseOffer::priceRequestItemId).containsExactly(2L);
    }

    @Test
    void tieGoesToEarlierRequest_listIsCheapestFirst() {
        List<PurchaseOffer> current = PurchaseOffers.current(List.of(
                o(5, 20, 1, "100", "RESPONDED"), o(6, 18, 2, "100", "RESPONDED"), o(7, 19, 3, "99", "RESPONDED")));
        assertThat(current).extracting(PurchaseOffer::priceRequestItemId).containsExactly(7L, 6L, 5L);
        assertThat(PurchaseOffers.best(current.subList(1, 3))).get().extracting(PurchaseOffer::priceRequestItemId).isEqualTo(6L);
    }

    @Test
    void nothingGivesNothing() {
        assertThat(PurchaseOffers.best(List.of())).isEmpty();
    }
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.util.RegistrationTextTest' --tests 'com.vladoose.nir.clientoffer.PurchaseOffersTest'`
Expected: FAIL — компиляция: `cannot find symbol RegistrationText` / `PurchaseOffer`.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/vladoose/nir/util/RegistrationText.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.entity.MedRegistry;

/**
 * Текст регистрации строки КП из записи реестра (спека 2026-10-12 §8): «№ РК-МИ (МТ)-0№023037 от 28.10.2021 г., бессрочно»
 * или «…, до 28.10.2026 г.». Без даты регистрации — «№ …» и срок; записи нет — только номер. Общий помощник: им же
 * воспользуется часть 3 волны 2 (реестр в редакторе КП).
 */
public final class RegistrationText {

    private RegistrationText() {}

    public static String of(String regNumber, MedRegistry record) {
        if (regNumber == null || regNumber.isBlank()) return null;
        StringBuilder sb = new StringBuilder("№ ").append(regNumber.trim());
        if (record == null) return sb.toString();
        if (record.getRegDate() != null) sb.append(" от ").append(DocFormat.date(record.getRegDate())).append(" г.");
        if (Boolean.TRUE.equals(record.getUnlimited())) {
            sb.append(", бессрочно");
        } else if (record.getExpirationDate() != null) {
            sb.append(", до ").append(DocFormat.date(record.getExpirationDate())).append(" г.");
        }
        return sb.toString();
    }
}
```

`src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java`:

```java
package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.PriceRequest;
import com.vladoose.nir.entity.PriceRequestItem;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Строка ответа поставщика с ценой — кандидат в закупку строки КП (спека 2026-10-12 §8). */
public record PurchaseOffer(long priceRequestItemId, long priceRequestId, long lotId, long distributorId,
                            String supplierName, BigDecimal price, String status, LocalDate respondedOn) {

    public static PurchaseOffer of(PriceRequestItem i) {
        PriceRequest pr = i.getPriceRequest();
        return new PurchaseOffer(i.getId(), pr.getId(), i.getTenderLot().getId(), pr.getDistributor().getId(),
                pr.getDistributor().getName(), i.getResponsePrice(), pr.getStatus(), pr.getResponseDate());
    }
}
```

`src/main/java/com/vladoose/nir/service/offer/PurchaseOffers.java`:

```java
package com.vladoose.nir.service.offer;

import java.util.*;

/**
 * Цена закупки по умолчанию (спека 2026-10-12 §8, решение плана 2). Кандидаты — строки ответов с ценой у запросов не
 * DECLINED / REJECTED / CLOSED; у каждого поставщика — только последняя (как ячейка таблицы цен заявки), иначе цена,
 * которую он потом поменял новым ответом, могла бы стать «лучшей». Лучшая — самая дешёвая среди принятых (ACCEPTED),
 * иначе самая дешёвая; равенство — более ранний запрос.
 */
public final class PurchaseOffers {

    public static final Set<String> EXCLUDED = Set.of("DECLINED", "REJECTED", "CLOSED");
    private static final Comparator<PurchaseOffer> CHEAPEST =
            Comparator.comparing(PurchaseOffer::price).thenComparingLong(PurchaseOffer::priceRequestId);

    private PurchaseOffers() {}

    /** Кандидаты позиции: без исключённых статусов, по последней строке каждого поставщика, от дешёвой. */
    public static List<PurchaseOffer> current(Collection<PurchaseOffer> lines) {
        Map<Long, PurchaseOffer> latest = new HashMap<>();
        for (PurchaseOffer o : lines) {
            if (o.price() == null || EXCLUDED.contains(o.status())) continue;
            latest.merge(o.distributorId(), o, (a, b) -> a.priceRequestId() >= b.priceRequestId() ? a : b);
        }
        return latest.values().stream().sorted(CHEAPEST).toList();
    }

    public static Optional<PurchaseOffer> best(List<PurchaseOffer> current) {
        Optional<PurchaseOffer> accepted = current.stream().filter(o -> "ACCEPTED".equals(o.status())).min(CHEAPEST);
        return accepted.isPresent() ? accepted : current.stream().min(CHEAPEST);
    }
}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.util.RegistrationTextTest' --tests 'com.vladoose.nir.clientoffer.PurchaseOffersTest'`
Expected: PASS, 9 тестов.

- [ ] **Step 5: Mutation (по одной, копией файла — CLAUDE.md §14)**

`cp src/main/java/com/vladoose/nir/service/offer/PurchaseOffers.java /tmp/po.bak` → убрать `"DECLINED", ` из `EXCLUDED` → прогон из шага 4: Expected FAIL `declinedRejectedClosedAreNotCandidates` → `cp /tmp/po.bak …` → `diff -q` → `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/util/RegistrationText.java src/main/java/com/vladoose/nir/service/offer/PurchaseOffer.java src/main/java/com/vladoose/nir/service/offer/PurchaseOffers.java src/test/java/com/vladoose/nir/util/RegistrationTextTest.java src/test/java/com/vladoose/nir/clientoffer/PurchaseOffersTest.java
git commit -m "feat(client-offer): правило цены закупки из ответов и текст регистрации из реестра

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 2: «Создать КП клиенту» — сервис и путь

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferService.java` (`checkAmounts` → package-private `static`)
- Create: `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java`
- Modify: `src/main/java/com/vladoose/nir/controller/ClientOfferController.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferFromRequestTest.java`

**Interfaces:**
- Consumes: `PurchaseOffer.of`, `PurchaseOffers.current/best`, `RegistrationText.of` (задача 1); `ClientOfferService.create(String author)`, `calculate(ClientOffer)`, `NOT_REQUIRED_TEXT`; `PrivateRequestService.findById(Long)`.
- Produces: `PriceRequestItemRepository.findPricedByTenderId(Long) → List<PriceRequestItem>`; `ClientOfferRequestService.createFromPrivateRequest(Long tenderId, String author) → ClientOffer`; package-private `ClientOfferRequestService.offersByLot(Long tenderId) → Map<Long, List<PurchaseOffer>>` и `static String recipient(Tender)`; `POST /api/client-offers/from-private-request/{tenderId}` (ADMIN) → `ClientOfferResponse`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferFromRequestTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** КП клиенту из частной заявки (спека 2026-10-12 §8; решения плана 1–4, 12). */
@SpringBootTest
@Transactional
class ClientOfferFromRequestTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired CompanyProfileRepository profiles;
    @Autowired DistributorRepository distributors;
    @Autowired FacilityRepository facilities;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired TenderLotRepository lots;
    @Autowired TenderRepository tenders;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        // реквизиты в nirdb правит оператор: ставки и умолчания — явно, в транзакции теста (откатывается)
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setVatDefault(new BigDecimal("16"));
        kz.setVatRegistered(new BigDecimal("5"));
        kz.setVatNotRegistrable(null);
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request(PrivateRequestLineInput... lines) {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(lines), LinesMode.APPEND);
        return t;
    }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> l = new ArrayList<>(t.getLots());
        l.sort(Comparator.comparing(TenderLot::getLotNumber));
        return l;
    }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name(name + " " + System.nanoTime()).email("d@x.kz").build());
    }

    /** Ответ поставщика по позиции: запрос со статусом и строка с ценой (как в PrivateRequestPricesTest). */
    private PriceRequestItem answer(Tender t, Distributor d, TenderLot lot, String status, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status(status)
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(price == null ? null : new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    @Test
    void rowsFromPositionsInOrder_clientAndCheapestAnswer() {
        Facility f = facilities.save(Facility.builder().name("ZZ КП Клиника " + System.nanoTime()).build());
        Tender t = request(input("ZZ КП Монитор", "Mindray", 2, "компл", null, null),
                input("ZZ КП Кушетка", null, null, null, null, null));
        service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(f.getId()));
        List<TenderLot> l = sorted(t);
        Distributor a = dist("ZZ КП Поставщик А"), b = dist("ZZ КП Поставщик Б");
        answer(t, a, l.get(0), "RESPONDED", "120000");
        PriceRequestItem cheaper = answer(t, b, l.get(0), "RESPONDED", "110000.5");
        em.flush();

        ClientOffer o = requestOffers.createFromPrivateRequest(t.getId(), "test");

        assertThat(o.getTenderId()).isEqualTo(t.getId());
        assertThat(o.getFacility().getId()).isEqualTo(f.getId());
        assertThat(o.getRecipient()).isEqualTo(f.getName());
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.DRAFT);
        assertThat(o.getItems()).hasSize(2);
        ClientOfferItem first = o.getItems().get(0), second = o.getItems().get(1);
        assertThat(first.getName()).isEqualTo("ZZ КП Монитор");
        assertThat(first.getQuantity()).isEqualByComparingTo("2");
        assertThat(first.getUnit()).isEqualTo("компл");
        assertThat(first.getTenderLotId()).isEqualTo(l.get(0).getId());
        assertThat(first.getPurchasePrice()).isEqualByComparingTo("110000.50");
        assertThat(first.getPriceRequestItemId()).isEqualTo(cheaper.getId());
        assertThat(first.getDistributorId()).isEqualTo(b.getId());
        assertThat(first.getSupplierName()).isEqualTo(b.getName());
        assertThat(second.getQuantity()).isEqualByComparingTo("1");   // кол-ва нет — 1
        assertThat(second.getUnit()).isEqualTo("шт");                    // ед. изм. нет — «шт»
        assertThat(second.getPurchasePrice()).isNull();                  // ответов нет — закупка пустая
        assertThat(second.getPriceRequestItemId()).isNull();
        assertThat(o.getItemCount()).isEqualTo(2);
        assertThat(o.getTotalAmount()).isNotNull();
    }

    @Test
    void registrationManufacturerCountryAndVatByLabel() {
        String reg = jdbc.queryForObject("select reg_number from med_registry where reg_date is not null and producer is not null"
                + " and country is not null order by reg_number limit 1", String.class);
        Tender t = request(input("ZZ КП Подтверждено", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Не подлежит", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Подсказка", "ZZBRAND", 1, null, null, null));
        List<TenderLot> l = sorted(t);
        l.get(0).setRegStatus(LineRegStatus.CONFIRMED);
        l.get(0).setRegNumber(reg);
        l.get(1).setRegStatus(LineRegStatus.NOT_REQUIRED);
        l.get(2).setRegStatus(LineRegStatus.SUGGESTED);
        l.get(2).setRegNumber(reg);
        em.flush();
        MedRegistry rec = jdbc.queryForObject("select reg_number, producer, country from med_registry where reg_number = ?",
                (rs, n) -> { MedRegistry m = new MedRegistry(); m.setProducer(rs.getString(2)); m.setCountry(rs.getString(3)); return m; }, reg);

        List<ClientOfferItem> items = requestOffers.createFromPrivateRequest(t.getId(), "test").getItems();

        assertThat(items.get(0).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(items.get(0).getRegNumber()).isEqualTo(reg);
        assertThat(items.get(0).getRegistrationText()).startsWith("№ " + reg + " от ");
        assertThat(items.get(0).getManufacturer()).isEqualTo(rec.getProducer().length() > 500 ? rec.getProducer().substring(0, 500) : rec.getProducer());
        assertThat(items.get(0).getCountry()).isNotBlank();
        assertThat(items.get(0).getVatRate()).isEqualByComparingTo("5");
        assertThat(items.get(1).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.NOT_REQUIRED);
        assertThat(items.get(1).getRegistrationText()).isEqualTo("Не подлежит регистрации");
        assertThat(items.get(1).getVatRate()).isNull();
        assertThat(items.get(2).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.UNCHECKED);
        assertThat(items.get(2).getRegNumber()).isNull();
        assertThat(items.get(2).getManufacturer()).isEqualTo("ZZBRAND");
        assertThat(items.get(2).getVatRate()).isEqualByComparingTo("16");
    }

    /** Отказ, отклонённый и закрытый запрос — не кандидаты; принятый сильнее более дешёвого ответа. */
    @Test
    void priceRule_acceptedFirst_declinedRejectedClosedIgnored() {
        Tender t = request(input("ZZ КП Правило", null, 1, null, null, null));
        TenderLot lot = sorted(t).get(0);
        answer(t, dist("ZZ КП Отказ"), lot, "DECLINED", "10");
        answer(t, dist("ZZ КП Отклонён"), lot, "REJECTED", "11");
        answer(t, dist("ZZ КП Закрыт"), lot, "CLOSED", "12");
        answer(t, dist("ZZ КП Дешёвый"), lot, "RESPONDED", "500");
        Distributor accepted = dist("ZZ КП Принятый");
        answer(t, accepted, lot, "ACCEPTED", "700");
        em.flush();

        ClientOfferItem it = requestOffers.createFromPrivateRequest(t.getId(), "test").getItems().get(0);

        assertThat(it.getPurchasePrice()).isEqualByComparingTo("700");
        assertThat(it.getDistributorId()).isEqualTo(accepted.getId());
    }

    /** «Кому» — печатный текст: «Клиент не указан» и телефон в него не попадают (Review Focus 4, решение плана 1). */
    @Test
    void recipientNeverPrintsPlaceholder() {
        Tender none = request(input("ZZ КП Без клиента", null, 1, null, null, null));
        assertThat(requestOffers.createFromPrivateRequest(none.getId(), "test").getRecipient()).isNull();

        Tender phone = request(input("ZZ КП Телефон", null, 1, null, null, null));
        service.updateField(phone.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 000 00 00");
        assertThat(requestOffers.createFromPrivateRequest(phone.getId(), "test").getRecipient()).isNull();

        Tender contact = request(input("ZZ КП Контакт", null, 1, null, null, null));
        service.updateField(contact.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Айгуль Сапарова");
        assertThat(requestOffers.createFromPrivateRequest(contact.getId(), "test").getRecipient()).isEqualTo("ZZ Айгуль Сапарова");

        Tender named = request(input("ZZ КП Текстом", null, 1, null, null, null));
        service.updateField(named.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Частная клиника");
        service.updateField(named.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт");
        assertThat(requestOffers.createFromPrivateRequest(named.getId(), "test").getRecipient()).isEqualTo("ZZ Частная клиника");
    }

    @Test
    void hugeSumIs400NotServerError() {
        Tender t = request(input("ZZ КП Огромная", null, 1000, null, null, null));
        answer(t, dist("ZZ КП Дорогой"), sorted(t).get(0), "RESPONDED", "9999999999999");
        em.flush();
        assertThatThrownBy(() -> requestOffers.createFromPrivateRequest(t.getId(), "test"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Сумма позиции");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void endpoint_createsFromRequest_otherMarketAndTendersAre404() throws Exception {
        Tender t = request(input("ZZ КП Путь", null, 1, null, null, null));
        JsonNode r = om.readTree(mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.get("tenderId").asLong()).isEqualTo(t.getId());
        assertThat(r.get("items").get(0).get("tenderLotId").asLong()).isEqualTo(sorted(t).get(0).getId());

        mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "RF"))
                .andExpect(status().isNotFound());
        Tender publicTender = tenders.save(Tender.builder().tenderNumber("ZZ-KP-PUB").status("ACTIVE").source(Source.PUBLIC_TENDER).build());
        mvc.perform(post("/api/client-offers/from-private-request/" + publicTender.getId()).header("X-Market", "KZ"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void operatorCannotCreate() throws Exception {
        Tender t = request(input("ZZ КП Оператор", null, 1, null, null, null));
        mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }
}
```

> Сверено при планировании: `PrivateRequestField.FACILITY` / `CUSTOMER_NAME` / `CONTACT_NAME` / `CONTACT_PHONE`, `dto.request.PrivateRequestLineInput`, `Facility.builder()`. Проверить при исполнении: сеттеры `CompanyProfile` (`setVatDefault/Registered/NotRegistrable`) и обязательные поля `Facility` (NOT NULL кроме имени) — расхождение: правка теста и строка `Ruling:` в леджере.

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferFromRequestTest'`
Expected: FAIL — компиляция: `cannot find symbol ClientOfferRequestService`.

- [ ] **Step 3: Write the implementation**

В `PriceRequestItemRepository` — после `findByTenderId`:

```java
    /** Строки ответов с ценой по заявке — с запросом, поставщиком и позицией (КП из заявки, спека 2026-10-12 §8). */
    @Query("SELECT i FROM PriceRequestItem i JOIN FETCH i.priceRequest pr JOIN FETCH pr.distributor JOIN FETCH i.tenderLot " +
           "WHERE pr.tender.id = :tenderId AND i.responsePrice IS NOT NULL")
    List<PriceRequestItem> findPricedByTenderId(@Param("tenderId") Long tenderId);
```

В `ClientOfferService`: `private static void checkAmounts(ClientOffer o, OfferCalculation calc)` → `static void checkAmounts(ClientOffer o, OfferCalculation calc)` (package-private — его зовёт и `ClientOfferRequestService`).

`src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.MedRegistryRepository;
import com.vladoose.nir.repository.PriceRequestItemRepository;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.PurchaseOffer;
import com.vladoose.nir.service.offer.PurchaseOffers;
import com.vladoose.nir.util.RegistrationText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * КП клиенту из частной заявки (спека 2026-10-12 §8): черновик — как «+ Новое КП» (номер из счётчика, умолчания
 * «Реквизитов»), плюс клиент, «Кому», ссылка на заявку и строки из её позиций с РУ, ставкой и ценой закупки из ответов
 * поставщиков. КП — снимок: позиции и цены, пришедшие позже, в него сами не попадают.
 */
@Service
public class ClientOfferRequestService {

    private static final Comparator<TenderLot> BY_LINE = Comparator
            .comparing((TenderLot l) -> l.getLotNumber() == null ? Integer.MAX_VALUE : l.getLotNumber())
            .thenComparing(l -> l.getId() == null ? Long.MAX_VALUE : l.getId());

    private final ClientOfferService offers;
    private final ClientOfferRepository repository;
    private final PrivateRequestService privateRequests;
    private final CompanyProfileService profiles;
    private final PriceRequestItemRepository priceItems;
    private final MedRegistryRepository registry;

    public ClientOfferRequestService(ClientOfferService offers, ClientOfferRepository repository,
                                     PrivateRequestService privateRequests, CompanyProfileService profiles,
                                     PriceRequestItemRepository priceItems, MedRegistryRepository registry) {
        this.offers = offers;
        this.repository = repository;
        this.privateRequests = privateRequests;
        this.profiles = profiles;
        this.priceItems = priceItems;
        this.registry = registry;
    }

    @Transactional
    public ClientOffer createFromPrivateRequest(Long tenderId, String author) {
        Tender t = privateRequests.findById(tenderId);   // гард: только частная заявка своего рынка, иначе 404
        ClientOffer o = offers.create(author);
        CompanyProfile p = profiles.forMarket(o.getMarket());
        o.setFacility(t.getFacility());
        o.setRecipient(recipient(t));
        o.setTenderId(t.getId());
        Map<Long, List<PurchaseOffer>> byLot = offersByLot(t.getId());
        Map<String, MedRegistry> records = records(t.getLots());
        int lineNo = 0;
        for (TenderLot lot : t.getLots().stream().sorted(BY_LINE).toList()) {
            ClientOfferItem it = item(o, ++lineNo, lot, p, lot.getRegNumber() == null ? null : records.get(lot.getRegNumber().trim()));
            PurchaseOffers.best(byLot.getOrDefault(lot.getId(), List.of())).ifPresent(best -> link(it, best));
            o.getItems().add(it);
        }
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);   // 400 до сохранения, а не ошибка базы (решение плана 12)
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        repository.flush();
        return o;
    }

    /** Кандидаты в закупку по позициям заявки (решение плана 2). */
    Map<Long, List<PurchaseOffer>> offersByLot(Long tenderId) {
        Map<Long, List<PurchaseOffer>> raw = new HashMap<>();
        for (PriceRequestItem i : priceItems.findPricedByTenderId(tenderId)) {
            raw.computeIfAbsent(i.getTenderLot().getId(), k -> new ArrayList<>()).add(PurchaseOffer.of(i));
        }
        Map<Long, List<PurchaseOffer>> out = new HashMap<>();
        raw.forEach((lot, lines) -> out.put(lot, PurchaseOffers.current(lines)));
        return out;
    }

    /** «Кому» — печатный текст: учреждение → название клиента → контакт; телефон и «Клиент не указан» — нет (решение плана 1). */
    static String recipient(Tender t) {
        if (t.getFacility() != null && !blank(t.getFacility().getName())) return t.getFacility().getName().trim();
        if (!blank(t.getCustomerName())) return t.getCustomerName().trim();
        if (!blank(t.getContactName())) return t.getContactName().trim();
        return null;
    }

    private Map<String, MedRegistry> records(Collection<TenderLot> lots) {
        Set<String> numbers = new HashSet<>();
        for (TenderLot l : lots) {
            if (l.getRegStatus() == LineRegStatus.CONFIRMED && !blank(l.getRegNumber())) numbers.add(l.getRegNumber().trim());
        }
        Map<String, MedRegistry> out = new HashMap<>();
        if (!numbers.isEmpty()) for (MedRegistry m : registry.findByRegNumberIn(numbers)) out.put(m.getRegNumber(), m);
        return out;
    }

    /** Строка КП из позиции: регистрация, производитель и ставка — по метке РУ (решение плана 4). */
    static ClientOfferItem item(ClientOffer o, int lineNo, TenderLot lot, CompanyProfile p, MedRegistry rec) {
        Integer qty = lot.getQuantity();
        ClientOfferItem it = ClientOfferItem.builder()
                .offer(o).lineNo(lineNo).kind(ClientOfferItemKind.ITEM)
                .name(lot.getEquipName() == null ? "" : lot.getEquipName().trim())
                .unit(blank(lot.getUnit()) ? "шт" : lot.getUnit().trim())
                .quantity(BigDecimal.valueOf(qty == null || qty <= 0 ? 1 : qty).setScale(3))
                .manufacturer(blank(lot.getManufact()) ? null : lot.getManufact().trim())
                .tenderLotId(lot.getId())
                .build();
        if (lot.getRegStatus() == LineRegStatus.CONFIRMED && !blank(lot.getRegNumber())) {
            it.setRegistrationStatus(OfferRegistrationStatus.CONFIRMED);
            it.setRegNumber(lot.getRegNumber().trim());
            it.setRegistrationText(RegistrationText.of(lot.getRegNumber(), rec));
            if (rec != null && !blank(rec.getProducer())) it.setManufacturer(cut(rec.getProducer(), 500));
            if (rec != null && !blank(rec.getCountry())) it.setCountry(cut(rec.getCountry(), 200));
            it.setVatRate(p.getVatRegistered());
        } else if (lot.getRegStatus() == LineRegStatus.NOT_REQUIRED) {
            it.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
            it.setRegistrationText(ClientOfferService.NOT_REQUIRED_TEXT);
            it.setVatRate(p.getVatNotRegistrable());
        } else {
            it.setVatRate(p.getVatDefault());
        }
        return it;
    }

    static void link(ClientOfferItem it, PurchaseOffer offer) {
        it.setPurchasePrice(offer.price().setScale(2, RoundingMode.HALF_UP));
        it.setSupplierName(cut(offer.supplierName(), 255));
        it.setPriceRequestItemId(offer.priceRequestItemId());
        it.setDistributorId(offer.distributorId());
    }

    static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() > max ? t.substring(0, max) : t;
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
```

В `ClientOfferController` — поле `private final ClientOfferRequestService requestOffers;` (в конструктор третьим параметром) и путь:

```java
    /** КП клиенту из частной заявки (спека 2026-10-12 §8): черновик с позициями, РУ и ценами ответов. */
    @PostMapping("/from-private-request/{tenderId}")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse fromPrivateRequest(@PathVariable Long tenderId) {
        return full(requestOffers.createFromPrivateRequest(tenderId, currentUser()));
    }
```

`ClientOfferItemDto` и маппер получают `tenderLotId` в задаче 3 — до неё ассерт `items[0].tenderLotId` в `endpoint_…` упадёт: **в этой задаче добавить в `ClientOfferItemDto` только чтение**:

```java
    /** Позиция заявки, из которой строка (ставит сервер при создании КП из заявки); с клиента не меняется. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long tenderLotId;
```

и в `ClientOfferMapper.toItemDto`: `d.setTenderLotId(it.getTenderLotId());`.

- [ ] **Step 4: Run the test to verify it passes**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferFromRequestTest' --tests 'com.vladoose.nir.clientoffer.ClientOfferApiTest'`
Expected: PASS.

- [ ] **Step 5: Mutations (по одной)**

1. `recipient`: вернуть «Клиент не указан» последней строкой (`return "Клиент не указан";`) → Expected FAIL `recipientNeverPrintsPlaceholder`.
2. `item`: `it.setVatRate(p.getVatRegistered())` → `p.getVatDefault()` → Expected FAIL `registrationManufacturerCountryAndVatByLabel`.
После каждой — откат копией, `diff -q`; после серии — `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/PriceRequestItemRepository.java src/main/java/com/vladoose/nir/service/ClientOfferService.java src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java src/main/java/com/vladoose/nir/controller/ClientOfferController.java src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java src/main/java/com/vladoose/nir/service/ClientOfferMapper.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferFromRequestTest.java
git commit -m "feat(client-offer): КП клиенту из частной заявки — позиции, РУ, ставки, цена закупки из ответов

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 3: Ссылка строки на ответ поставщика — гард PUT и «Поставщик ▾»

**Files:**
- Modify: `src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java` (+ `priceRequestItemId`, только чтение — `distributorId`)
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferService.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java` (+ `offers`)
- Create: `src/main/java/com/vladoose/nir/dto/response/SupplierOfferResponse.java`
- Modify: `src/main/java/com/vladoose/nir/controller/ClientOfferController.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferResponseLinkTest.java`

**Interfaces:**
- Consumes: `ClientOfferRequestService.offersByLot` (задача 2), `PurchaseOffer` (задача 1).
- Produces: в JSON строки КП — `priceRequestItemId` (чтение и запись), `tenderLotId`, `distributorId` (только чтение); `ClientOfferRequestService.offers(Long offerId, Long itemId) → List<SupplierOfferResponse>`; `GET /api/client-offers/{id}/items/{itemId}/offers`; `record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn, boolean accepted, boolean cheapest, boolean selected)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferResponseLinkTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Строка КП ↔ ответ поставщика (спека 2026-10-12 §8, решения плана 5–7): гард PUT и «Поставщик ▾». */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class ClientOfferResponseLinkTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired ClientOfferRepository offers;
    MockMvc mvc;

    Tender t;
    TenderLot lotA, lotB;
    Distributor a, b;
    PriceRequestItem aOnA, bOnA, aOnB;
    ClientOffer kp;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Ссылка А", null, 1, null, null, null),
                input("ZZ Ссылка Б", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> l = new ArrayList<>(t.getLots());
        l.sort(Comparator.comparing(TenderLot::getLotNumber));
        lotA = l.get(0);
        lotB = l.get(1);
        a = distributors.save(Distributor.builder().name("ZZ Ссылка Поставщик А " + System.nanoTime()).build());
        b = distributors.save(Distributor.builder().name("ZZ Ссылка Поставщик Б " + System.nanoTime()).build());
        aOnA = answer(a, lotA, "RESPONDED", "100");
        bOnA = answer(b, lotA, "RESPONDED", "150");
        aOnB = answer(a, lotB, "RESPONDED", "40");
        em.flush();
        kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private PriceRequestItem answer(Distributor d, TenderLot lot, String status, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(lot.getTender()).distributor(d).status(status)
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private JsonNode getOffer() throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions putOffer(ObjectNode body) throws Exception {
        return mvc.perform(put("/api/client-offers/" + kp.getId()).header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    /** Тело PUT = ответ GET с правкой ссылки строки row (как в ClientOfferApiTest). */
    private ObjectNode withLink(int row, Long priceRequestItemId) throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ObjectNode item = (ObjectNode) body.get("items").get(row);
        if (priceRequestItemId == null) item.putNull("priceRequestItemId"); else item.put("priceRequestItemId", priceRequestItemId);
        return body;
    }

    private ClientOfferItem stored(int row) {
        em.flush();
        em.clear();
        return offers.findById(kp.getId()).orElseThrow().getItems().get(row);
    }

    @Test
    void createdRowCarriesLinkReadOnlyFields() throws Exception {
        JsonNode row = getOffer().get("items").get(0);
        assertThat(row.get("priceRequestItemId").asLong()).isEqualTo(aOnA.getId());   // дешевле всех — А, 100
        assertThat(row.get("distributorId").asLong()).isEqualTo(a.getId());
        assertThat(row.get("tenderLotId").asLong()).isEqualTo(lotA.getId());
    }

    @Test
    void linkToAnotherAnswerOfThisPosition_isSaved_distributorFromAnswer() throws Exception {
        putOffer(withLink(0, bOnA.getId())).andExpect(status().isOk());
        ClientOfferItem it = stored(0);
        assertThat(it.getPriceRequestItemId()).isEqualTo(bOnA.getId());
        assertThat(it.getDistributorId()).isEqualTo(b.getId());
    }

    @Test
    void linkToAnswerOfAnotherRequestIs400() throws Exception {
        Tender other = service.createEmpty();
        service.addLines(other.getId(), List.of(input("ZZ Ссылка Чужая", null, 1, null, null, null)), LinesMode.APPEND);
        PriceRequestItem foreign = answer(a, other.getLots().get(0), "RESPONDED", "1");
        em.flush();
        putOffer(withLink(0, foreign.getId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не относится к этой позиции")));
        assertThat(stored(0).getPriceRequestItemId()).isEqualTo(aOnA.getId());
    }

    @Test
    void linkToAnswerOfAnotherPositionIs400() throws Exception {
        putOffer(withLink(0, aOnB.getId())).andExpect(status().isBadRequest());
    }

    /** Снимок: запрос, на ответ которого ссылается строка, потом закрыли — та же ссылка живёт (решение плана 5). */
    @Test
    void unchangedLinkSurvivesClosedRequest() throws Exception {
        aOnA.getPriceRequest().setStatus("CLOSED");
        em.flush();
        putOffer(withLink(0, aOnA.getId())).andExpect(status().isOk());
        assertThat(stored(0).getPriceRequestItemId()).isEqualTo(aOnA.getId());
    }

    @Test
    void nullClearsLinkAndDistributor_supplierTextStays() throws Exception {
        putOffer(withLink(0, null)).andExpect(status().isOk());
        ClientOfferItem it = stored(0);
        assertThat(it.getPriceRequestItemId()).isNull();
        assertThat(it.getDistributorId()).isNull();
        assertThat(it.getSupplierName()).isEqualTo(a.getName());
    }

    /** Строку ответа удалили вместе с запросом — автосохранение не падает, ссылка снимается (Review Focus 1). */
    @Test
    void unknownResponseLineDropsLinkSilently() throws Exception {
        putOffer(withLink(1, Long.MAX_VALUE)).andExpect(status().isOk());
        assertThat(stored(1).getPriceRequestItemId()).isNull();
    }

    /** У новой строки позиции нет — ссылка на любой ответ даёт 400 (Review Focus 5). */
    @Test
    void newRowWithLinkIs400() throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ObjectNode copy = ((ObjectNode) body.get("items").get(0)).deepCopy();
        copy.remove("id");
        copy.put("key", "zz-new-row");
        ((ArrayNode) body.get("items")).add(copy);
        putOffer(body).andExpect(status().isBadRequest());
    }

    @Test
    void offersList_latestPricedPerSupplier_cheapestAndSelected() throws Exception {
        long itemId = getOffer().get("items").get(0).get("id").asLong();
        JsonNode list = om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId() + "/items/" + itemId + "/offers")
                .header("X-Market", "KZ")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("priceRequestItemId").asLong()).isEqualTo(aOnA.getId());
        assertThat(list.get(0).get("cheapest").asBoolean()).isTrue();
        assertThat(list.get(0).get("selected").asBoolean()).isTrue();
        assertThat(list.get(1).get("priceRequestItemId").asLong()).isEqualTo(bOnA.getId());
        assertThat(list.get(1).get("cheapest").asBoolean()).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferResponseLinkTest'`
Expected: FAIL — `createdRowCarriesLinkReadOnlyFields` (в JSON нет `priceRequestItemId` / `distributorId`), `linkToAnotherAnswerOfThisPosition…` (PUT ссылку не меняет), путь `…/offers` — 404/405.

- [ ] **Step 3: Write the implementation**

`ClientOfferItemDto` — рядом с `tenderLotId` (задача 2):

```java
    /**
     * Строка ответа поставщика, из которой взята закупка (спека 2026-10-12 §8). Пишется: только ответ заявки этого КП по
     * позиции этой строки и с ценой, иначе 400; пусто — ссылки нет; ручная правка закупки на экране её снимает.
     */
    private Long priceRequestItemId;
    /** Поставщик ответа — выводит сервер из priceRequestItemId; с клиента не принимается. */
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private Long distributorId;
```

`ClientOfferMapper.toItemDto` — добавить: `d.setPriceRequestItemId(it.getPriceRequestItemId()); d.setDistributorId(it.getDistributorId());`

`ClientOfferService`:
- конструктор: новый параметр `PriceRequestItemRepository priceRequestItems` (поле `private final PriceRequestItemRepository priceRequestItems;`);
- запись проверенной ссылки и результат проверки строк:

```java
    /** Ссылка строки на ответ после проверки (решение плана 5): keep — та, что сохранена; иначе — новые id ответа и поставщика. */
    private record Link(boolean keep, Long priceRequestItemId, Long distributorId) {
        static final Link NONE = new Link(false, null, null);
        static final Link KEEP = new Link(true, null, null);
    }

    private record Checked(List<ClientOfferItem> stored, List<Link> links) {}
```

- `checkItems` становится нестатическим и возвращает `Checked`:

```java
    private Checked checkItems(ClientOffer o, List<ClientOfferItemDto> dtos, CompanyProfile profile) {
        Map<Long, ClientOfferItem> existing = new HashMap<>();
        for (ClientOfferItem it : o.getItems()) existing.put(it.getId(), it);
        List<ClientOfferItem> stored = new ArrayList<>(dtos.size());
        List<Link> links = new ArrayList<>(dtos.size());
        int itemNo = 0;
        for (int i = 0; i < dtos.size(); i++) {
            ClientOfferItemDto dto = dtos.get(i);
            if (dto == null) throw new BadRequestException("Пустая строка в списке строк КП (строка " + (i + 1) + ")");
            ClientOfferItem it = null;
            if (dto.getId() != null) {
                it = existing.remove(dto.getId());
                if (it == null) throw new BadRequestException("Строка не найдена или повторяется: id=" + dto.getId());
            }
            if (dto.getKind() == null) throw new BadRequestException("Не указан вид строки (строка " + (i + 1) + ")");
            Link link = Link.NONE;
            if (dto.getKind() == ClientOfferItemKind.ITEM) {
                String row = "№ " + (++itemNo) + quotedName(dto.getName());
                checkItem(dto, it, profile, row);
                link = checkLink(o, dto, it, row);
            }
            stored.add(it);
            links.add(link);
        }
        return new Checked(stored, links);
    }

    /**
     * Ссылка строки на ответ поставщика (решение плана 5). Та же, что сохранена, — не перепроверяется: КП — снимок, запрос
     * потом закрыли — ссылка живёт. Новая — только ответ заявки этого КП, по позиции этой строки и с ценой, иначе 400.
     * Строки ответа нет (удалена вместе с запросом) — ссылка молча снимается: автосохранение не должно падать.
     */
    private Link checkLink(ClientOffer o, ClientOfferItemDto dto, ClientOfferItem stored, String row) {
        Long id = dto.getPriceRequestItemId();
        if (id == null) return Link.NONE;
        if (stored != null && id.equals(stored.getPriceRequestItemId())) return Link.KEEP;
        PriceRequestItem answer = priceRequestItems.findById(id).orElse(null);
        if (answer == null) return Link.NONE;
        Long lotId = stored == null ? null : stored.getTenderLotId();
        PriceRequest pr = answer.getPriceRequest();
        boolean ours = o.getTenderId() != null && lotId != null && answer.getResponsePrice() != null
                && pr.getTender() != null && o.getTenderId().equals(pr.getTender().getId())
                && answer.getTenderLot() != null && lotId.equals(answer.getTenderLot().getId());
        if (!ours) throw new BadRequestException("Ответ поставщика не относится к этой позиции заявки — позиция " + row);
        return new Link(false, id, pr.getDistributor().getId());
    }
```

- в `update(...)`: `List<ClientOfferItem> stored = checkItems(o, r.getItems(), profile);` → `Checked checked = checkItems(o, r.getItems(), profile);`, `syncItems(o, r.getItems(), stored);` → `syncItems(o, r.getItems(), checked);`
- `syncItems(ClientOffer o, List<ClientOfferItemDto> dtos, Checked checked)`: `ClientOfferItem it = checked.stored().get(i);` и `applyItem(it, dtos.get(i), checked.links().get(i));`
- `applyItem(ClientOfferItem it, ClientOfferItemDto dto, Link link)`: в ветке не-ITEM — `it.setPriceRequestItemId(null); it.setDistributorId(null);` перед `return`; в конце ветки ITEM:

```java
        if (!link.keep()) {
            it.setPriceRequestItemId(link.priceRequestItemId());
            it.setDistributorId(link.distributorId());
        }
```

- javadoc `applyItem`: «Связи волны 2 (поставщик, лот, строка ответа, каталог) не трогаются» → «Ссылка на ответ и поставщик — из проверки (checkLink); позиция заявки и позиция каталога с клиента не меняются».

`src/main/java/com/vladoose/nir/dto/response/SupplierOfferResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Ответ поставщика с ценой на позицию строки КП — пункт «Поставщик ▾» (спека 2026-10-12 §8). */
public record SupplierOfferResponse(Long priceRequestItemId, String supplierName, BigDecimal price, LocalDate respondedOn,
                                    boolean accepted, boolean cheapest, boolean selected) {}
```

`ClientOfferRequestService.offers` (импорты `SupplierOfferResponse`, `NotFoundException`, `java.util.Objects`):

```java
    /** «Поставщик ▾» (спека §8): ответы с ценой на позицию строки — по последнему от поставщика, от дешёвого. */
    @Transactional(readOnly = true)
    public List<SupplierOfferResponse> offers(Long offerId, Long itemId) {
        ClientOffer o = offers.get(offerId);
        ClientOfferItem it = o.getItems().stream().filter(i -> itemId.equals(i.getId())).findFirst()
                .orElseThrow(() -> new NotFoundException("Строка КП не найдена: id=" + itemId));
        if (o.getTenderId() == null || it.getTenderLotId() == null) return List.of();
        List<PurchaseOffer> current = offersByLot(o.getTenderId()).getOrDefault(it.getTenderLotId(), List.of());
        if (current.isEmpty()) return List.of();
        BigDecimal min = current.get(0).price();
        return current.stream().map(c -> new SupplierOfferResponse(c.priceRequestItemId(), c.supplierName(), c.price(),
                c.respondedOn(), "ACCEPTED".equals(c.status()), c.price().compareTo(min) == 0,
                Objects.equals(c.priceRequestItemId(), it.getPriceRequestItemId()))).toList();
    }
```

`ClientOfferController`:

```java
    /** «Поставщик ▾» в строке КП: ответы поставщиков с ценой на позицию заявки этой строки. */
    @GetMapping("/{id}/items/{itemId}/offers")
    public List<SupplierOfferResponse> itemOffers(@PathVariable Long id, @PathVariable Long itemId) {
        return requestOffers.offers(id, itemId);
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS — новый класс и весь пакет `clientoffer` (PUT волны 1 не сломан).

- [ ] **Step 5: Mutations (по одной)**

1. `checkLink`: убрать условие `o.getTenderId().equals(pr.getTender().getId())` → Expected FAIL `linkToAnswerOfAnotherRequestIs400`.
2. `checkLink`: `if (answer == null) return Link.NONE;` → `throw new BadRequestException("Ответ не найден")` → Expected FAIL `unknownResponseLineDropsLinkSilently`.
Откат копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java src/main/java/com/vladoose/nir/service/ClientOfferMapper.java src/main/java/com/vladoose/nir/service/ClientOfferService.java src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java src/main/java/com/vladoose/nir/dto/response/SupplierOfferResponse.java src/main/java/com/vladoose/nir/controller/ClientOfferController.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferResponseLinkTest.java
git commit -m "feat(client-offer): строка КП ↔ ответ поставщика — гард PUT и «Поставщик ▾»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 4: Плашка «Пришли новые цены — Подставить»

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/response/NewPricesResponse.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java` (+ `newPrices`, `applyNewPrices`)
- Modify: `src/main/java/com/vladoose/nir/controller/ClientOfferController.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferNewPricesTest.java`

**Interfaces:**
- Consumes: `offersByLot`, `link` (задача 2), `PurchaseOffers.best` (задача 1).
- Produces: `GET /api/client-offers/{id}/new-prices` → `NewPricesResponse(List<Line> lines)`, `Line(Long itemId, int lineNo, String name, BigDecimal purchasePrice, BigDecimal newPrice, String supplierName)`; `POST /api/client-offers/{id}/apply-new-prices` (ADMIN) → `ClientOfferResponse`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferNewPricesTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.NewPricesResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Плашка новых цен (спека 2026-10-12 §8; решение плана 8). */
@SpringBootTest
@Transactional
class ClientOfferNewPricesTest {

    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired ClientOfferRepository offers;

    Tender t;
    List<TenderLot> lots;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Новые 1", null, 1, null, null, null),
                input("ZZ Новые 2", null, 1, null, null, null), input("ZZ Новые 3", null, 1, null, null, null)),
                LinesMode.APPEND);
        lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name(name + " " + System.nanoTime()).build());
    }

    private PriceRequestItem answer(Distributor d, TenderLot lot, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status("RESPONDED")
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private List<String> names(NewPricesResponse r) {
        return r.lines().stream().map(NewPricesResponse.Line::name).toList();
    }

    @Test
    void bannerCountsEmptyAndChangedOnly_deliberateChoiceUntouched() {
        Distributor a = dist("ZZ Новые А"), b = dist("ZZ Новые Б"), c = dist("ZZ Новые В");
        PriceRequestItem aOn1 = answer(a, lots.get(0), "100");
        PriceRequestItem aOn3 = answer(a, lots.get(2), "300");
        em.flush();
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        // строка 3 — закупка вписана руками, без ссылки (ручная правка на экране снимает ссылку)
        ClientOfferItem third = kp.getItems().get(2);
        third.setPurchasePrice(new BigDecimal("250.00"));
        third.setPriceRequestItemId(null);
        third.setDistributorId(null);
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();

        answer(b, lots.get(1), "50");          // строка 2 без закупки — теперь есть цена
        answer(c, lots.get(0), "80");          // по строке 1 дешевле — но выбранный ответ не менялся: не трогаем
        aOn3.setResponsePrice(new BigDecimal("310"));   // строка 3 ручная — не трогаем
        em.flush();
        assertThat(names(requestOffers.newPrices(kp.getId()))).containsExactly("ZZ Новые 2");

        aOn1.setResponsePrice(new BigDecimal("110"));   // цена выбранного ответа изменилась
        em.flush();
        NewPricesResponse banner = requestOffers.newPrices(kp.getId());
        assertThat(names(banner)).containsExactly("ZZ Новые 1", "ZZ Новые 2");
        assertThat(banner.lines().get(0).newPrice()).isEqualByComparingTo("110");

        int version = kp.getVersion();
        ClientOffer applied = requestOffers.applyNewPrices(kp.getId());
        em.flush();
        assertThat(applied.getItems().get(0).getPurchasePrice()).isEqualByComparingTo("110");
        assertThat(applied.getItems().get(0).getPriceRequestItemId()).isEqualTo(aOn1.getId());
        assertThat(applied.getItems().get(1).getPurchasePrice()).isEqualByComparingTo("50");
        assertThat(applied.getItems().get(1).getDistributorId()).isEqualTo(b.getId());
        assertThat(applied.getItems().get(2).getPurchasePrice()).isEqualByComparingTo("250");
        assertThat(applied.getVersion()).isGreaterThan(version);
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();
    }

    @Test
    void onlyDraftFromRequest() {
        Distributor a = dist("ZZ Новые Черновик");
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        answer(a, lots.get(0), "100");
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).hasSize(1);
        kp.setStatus(ClientOfferStatus.SENT);
        em.flush();
        assertThat(requestOffers.newPrices(kp.getId()).lines()).isEmpty();
        assertThatThrownBy(() -> requestOffers.applyNewPrices(kp.getId())).isInstanceOf(BadRequestException.class);
    }

    /** Позицию удалили из заявки после КП: строка остаётся, плашка её не считает (Review Focus 3). */
    @Test
    void positionDeletedAfterKpKeepsRowAndIsIgnored() {
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        service.deleteLine(t.getId(), lots.get(1).getId());
        em.flush();
        em.clear();   // ON DELETE SET NULL делает база — перечитать КП
        answer(dist("ZZ Новые После"), em.find(TenderLot.class, lots.get(0).getId()), "70");
        em.flush();
        ClientOffer back = offers.findById(kp.getId()).orElseThrow();
        assertThat(back.getItems()).hasSize(3);
        assertThat(back.getItems().get(1).getTenderLotId()).isNull();
        assertThat(back.getItems().get(1).getName()).isEqualTo("ZZ Новые 2");
        assertThat(names(requestOffers.newPrices(kp.getId()))).containsExactly("ZZ Новые 1");
    }
}
```

> Сверено при планировании: `PrivateRequestService.deleteLine(Long id, Long lotId)`.

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferNewPricesTest'`
Expected: FAIL — компиляция: нет `NewPricesResponse`, `newPrices`, `applyNewPrices`.

- [ ] **Step 3: Write the implementation**

`src/main/java/com/vladoose/nir/dto/response/NewPricesResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.util.List;

/** Плашка «Пришли новые цены по N позициям» (спека 2026-10-12 §8): строки черновика, которым есть что подставить. */
public record NewPricesResponse(List<Line> lines) {
    public record Line(Long itemId, int lineNo, String name, BigDecimal purchasePrice, BigDecimal newPrice,
                       String supplierName) {}
}
```

`ClientOfferRequestService` (импорты `NewPricesResponse`, `ClientOfferStatus`, `BadRequestException`, `java.time.OffsetDateTime`, `java.util.stream.Collectors`):

```java
    /** Что подставить строке: цена и ответ, из которого она. */
    private record Update(ClientOfferItem item, PurchaseOffer offer, BigDecimal price) {}

    /**
     * Строки черновика из заявки, которым есть что подставить (спека §8): пустая закупка, а для позиции теперь есть цена по
     * правилу умолчания, — или цена выбранного ответа изменилась. Сознательно выбранный не самый дешёвый ответ с прежней
     * ценой и ручная закупка (без ссылки) не трогаются; строка без позиции (позицию удалили) — тоже.
     */
    private List<Update> updates(ClientOffer o) {
        if (o.getStatus() != ClientOfferStatus.DRAFT || o.getTenderId() == null) return List.of();
        Map<Long, List<PurchaseOffer>> byLot = offersByLot(o.getTenderId());
        Map<Long, PurchaseOffer> byAnswer = new HashMap<>();
        byLot.values().forEach(list -> list.forEach(c -> byAnswer.put(c.priceRequestItemId(), c)));
        Set<Long> linked = o.getItems().stream().map(ClientOfferItem::getPriceRequestItemId).filter(Objects::nonNull)
                .filter(id -> !byAnswer.containsKey(id)).collect(Collectors.toSet());
        for (PriceRequestItem i : priceItems.findAllById(linked)) {
            if (i.getResponsePrice() != null && i.getTenderLot() != null) byAnswer.put(i.getId(), PurchaseOffer.of(i));
        }
        List<Update> out = new ArrayList<>();
        for (ClientOfferItem it : o.getItems()) {
            if (it.getKind() != ClientOfferItemKind.ITEM) continue;
            if (it.getPurchasePrice() == null) {
                if (it.getTenderLotId() == null) continue;
                PurchaseOffers.best(byLot.getOrDefault(it.getTenderLotId(), List.of()))
                        .ifPresent(best -> out.add(new Update(it, best, scaled(best.price()))));
            } else if (it.getPriceRequestItemId() != null) {
                PurchaseOffer answer = byAnswer.get(it.getPriceRequestItemId());
                if (answer != null && scaled(answer.price()).compareTo(it.getPurchasePrice()) != 0) {
                    out.add(new Update(it, answer, scaled(answer.price())));
                }
            }
        }
        return out;
    }

    @Transactional(readOnly = true)
    public NewPricesResponse newPrices(Long offerId) {
        ClientOffer o = offers.get(offerId);
        return new NewPricesResponse(updates(o).stream().map(u -> new NewPricesResponse.Line(u.item().getId(),
                u.item().getLineNo(), u.item().getName(), u.item().getPurchasePrice(), u.price(),
                u.offer().supplierName())).toList());
    }

    /** «Подставить»: цены из плашки — в строки черновика; версия растёт, вторая вкладка получит 409 (решение плана 8). */
    @Transactional
    public ClientOffer applyNewPrices(Long offerId) {
        ClientOffer o = offers.get(offerId);
        if (o.getStatus() != ClientOfferStatus.DRAFT) throw new BadRequestException("Новые цены подставляются только в черновик");
        if (o.getTenderId() == null) throw new BadRequestException("КП не из заявки — подставлять нечего");
        for (Update u : updates(o)) link(u.item(), u.offer());
        OfferCalculation calc = offers.calculate(o);
        ClientOfferService.checkAmounts(o, calc);
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        o.setUpdatedAt(OffsetDateTime.now());
        repository.flush();
        return o;
    }

    private static BigDecimal scaled(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
```

> `link(it, offer)` ставит цену, поставщика, ссылку и `distributor_id` — для изменённой цены выбранного ответа это тот же ответ и тот же поставщик.

`ClientOfferController`:

```java
    /** Плашка «Пришли новые цены» — только у черновика из заявки; иначе пусто. */
    @GetMapping("/{id}/new-prices")
    public NewPricesResponse newPrices(@PathVariable Long id) {
        return requestOffers.newPrices(id);
    }

    @PostMapping("/{id}/apply-new-prices")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse applyNewPrices(@PathVariable Long id) {
        return full(requestOffers.applyNewPrices(id));
    }
```

- [ ] **Step 4: Run the tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 5: Mutation**

В `updates`: ветку «цена выбранного ответа изменилась» заменить на «есть ответ дешевле» (`PurchaseOffers.best(byLot…)` с ценой ниже закупки) → Expected FAIL `bannerCountsEmptyAndChangedOnly_deliberateChoiceUntouched` (строка 1 попадёт в плашку при ответе В за 80). Откат копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/dto/response/NewPricesResponse.java src/main/java/com/vladoose/nir/service/ClientOfferRequestService.java src/main/java/com/vladoose/nir/controller/ClientOfferController.java src/test/java/com/vladoose/nir/clientoffer/ClientOfferNewPricesTest.java
git commit -m "feat(client-offer): плашка новых цен и «Подставить» для черновика из заявки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 5: Номер заявки в КП, раздел «КП клиенту» в заявке, этап по КП

**Files:**
- Modify: `src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java`, `TenderRepository.java`
- Modify: `src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java`, `ClientOfferListItemResponse.java`, `PrivateRequestViewResponse.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/PrivateRequestOfferRef.java`
- Modify: `src/main/java/com/vladoose/nir/service/ClientOfferMapper.java`, `controller/ClientOfferController.java`
- Modify: `src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java`, `PrivateRequestStageCalculator.java`
- Test: `src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage3Test.java`

**Interfaces:**
- Consumes: `ClientOfferRequestService.createFromPrivateRequest` (задача 2).
- Produces: `tenderNumber` в `ClientOfferResponse` и `ClientOfferListItemResponse`; `PrivateRequestViewResponse.offers: List<PrivateRequestOfferRef>`; `record PrivateRequestOfferRef(Long id, Integer number, LocalDate offerDate, String status, int itemCount, BigDecimal totalAmount, String currency)`; коды этапа `KP_ACCEPTED` / `KP_SENT` / `KP_DRAFT` / `KP_REJECTED`; `ClientOfferMapper.toListItems(List<ClientOffer>)`.

- [ ] **Step 1: Write the failing test**

`src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage3Test.java`:

```java
package com.vladoose.nir.privaterequest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestViewResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestStageCalculator;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Заявка ↔ КП клиенту (спека 2026-10-12 §5.1, §5.4 правило 2, §8): этап, раздел «КП клиенту», номер заявки в КП. */
@SpringBootTest
@Transactional
class PrivateRequestStage3Test {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired PrivateRequestStageCalculator stages;
    @Autowired PrivateRequestViewBuilder views;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Этап КП", null, 1, null, null, null)), LinesMode.APPEND);
        return t;
    }

    @Test
    void stageFollowsBestKpStatus_closedRequestStaysClosed() {
        Tender t = request();
        ClientOffer first = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_DRAFT");
        assertThat(stages.of(t).text()).isEqualTo("КП — черновик");

        first.setStatus(ClientOfferStatus.SENT);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_SENT");

        ClientOffer second = requestOffers.createFromPrivateRequest(t.getId(), "test");
        second.setStatus(ClientOfferStatus.ACCEPTED);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_ACCEPTED");

        first.setStatus(ClientOfferStatus.REJECTED);
        second.setStatus(ClientOfferStatus.REJECTED);
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("KP_REJECTED");
        assertThat(stages.of(t).text()).isEqualTo("КП отклонено");

        t.setStatus("WON");
        em.flush();
        assertThat(stages.of(t).code()).isEqualTo("WON");
    }

    @Test
    void viewListsOffersOfTheRequest_newestFirst() {
        Tender t = request();
        ClientOffer older = requestOffers.createFromPrivateRequest(t.getId(), "test");
        ClientOffer newer = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();

        PrivateRequestViewResponse v = views.build(t);

        assertThat(v.getOffers()).extracting(r -> r.id()).containsExactly(newer.getId(), older.getId());
        assertThat(v.getOffers().get(0).status()).isEqualTo("DRAFT");
        assertThat(v.getOffers().get(0).itemCount()).isEqualTo(1);
        assertThat(v.getOffers().get(0).currency()).isEqualTo("KZT");
    }

    @Test
    @WithMockUser(roles = "USER")
    void offerAndJournalCarryRequestNumber() throws Exception {
        Tender t = request();
        ClientOffer kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();

        JsonNode one = om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(one.get("tenderNumber").asText()).isEqualTo(t.getTenderNumber());

        JsonNode list = om.readTree(mvc.perform(get("/api/client-offers").param("q", String.valueOf(kp.getNumber()))
                .header("X-Market", "KZ")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        JsonNode row = null;
        for (JsonNode r : list) if (r.get("id").asLong() == kp.getId()) row = r;
        assertThat(row).isNotNull();
        assertThat(row.get("tenderNumber").asText()).isEqualTo(t.getTenderNumber());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.PrivateRequestStage3Test'`
Expected: FAIL — компиляция: нет `getOffers()`; этап — «Новая» вместо `KP_DRAFT`.

- [ ] **Step 3: Write the implementation**

`ClientOfferRepository`:

```java
    /** КП по заявке — раздел «КП клиенту» на её странице, новые сверху. */
    List<ClientOffer> findByTenderIdOrderByIdDesc(Long tenderId);

    /** Статусы КП по заявкам — этап заявки (спека §5.4 правило 2): заявка → статус. */
    @Query("select o.tenderId, o.status from ClientOffer o where o.tenderId in :ids")
    List<Object[]> findStatusesByTenderIds(@Param("ids") Collection<Long> ids);
```

`TenderRepository`:

```java
    /** Номера заявок для журнала КП («из заявки ЧЗ-…») — одним запросом. */
    @Query("select t.id, t.tenderNumber from Tender t where t.id in :ids")
    List<Object[]> findNumbersByIds(@Param("ids") Collection<Long> ids);
```

`ClientOfferResponse` и `ClientOfferListItemResponse` — поле `private String tenderNumber;` (после `tenderId`).

`ClientOfferMapper` — зависимость и номера:

```java
    private final TenderRepository tenders;

    public ClientOfferMapper(TenderRepository tenders) {
        this.tenders = tenders;
    }
```

в `toResponse`: `r.setTenderNumber(o.getTenderId() == null ? null : tenders.findById(o.getTenderId()).map(Tender::getTenderNumber).orElse(null));`

```java
    /** Журнал: номера заявок — одним запросом на всю страницу (без N+1). */
    public List<ClientOfferListItemResponse> toListItems(List<ClientOffer> list) {
        Set<Long> ids = list.stream().map(ClientOffer::getTenderId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, String> numbers = new HashMap<>();
        if (!ids.isEmpty()) for (Object[] row : tenders.findNumbersByIds(ids)) numbers.put((Long) row[0], (String) row[1]);
        return list.stream().map(o -> {
            ClientOfferListItemResponse r = toListItem(o);
            r.setTenderNumber(o.getTenderId() == null ? null : numbers.get(o.getTenderId()));
            return r;
        }).toList();
    }
```

> Если тесты где-то зовут `new ClientOfferMapper()` (`grep -rn "new ClientOfferMapper(" src`) — передать `null`-репозиторий нельзя: в таких тестах брать бин из контекста; запись `Ruling:`.

`ClientOfferController.list`: `return mapper.toListItems(service.list(parseStatuses(status), q));`

`src/main/java/com/vladoose/nir/dto/response/PrivateRequestOfferRef.java`:

```java
package com.vladoose.nir.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;

/** КП клиенту по заявке — строка раздела «КП клиенту» на странице заявки (спека 2026-10-12 §8). */
public record PrivateRequestOfferRef(Long id, Integer number, LocalDate offerDate, String status, int itemCount,
                                     BigDecimal totalAmount, String currency) {}
```

`PrivateRequestViewResponse`: `private List<PrivateRequestOfferRef> offers;`

`PrivateRequestViewBuilder` — зависимость `ClientOfferRepository clientOffers` (в конструктор) и в `build` после `lead`:

```java
        r.setOffers(clientOffers.findByTenderIdOrderByIdDesc(t.getId()).stream()
                .map(o -> new PrivateRequestOfferRef(o.getId(), o.getNumber(), o.getOfferDate(), o.getStatus().name(),
                        o.getItemCount(), o.getTotalAmount(), o.getMarket().currencyCode()))
                .toList());
```

`PrivateRequestStageCalculator` — зависимость `ClientOfferRepository clientOffers` (в конструктор), в `of(List<Tender>)` перед циклом:

```java
        Map<Long, Set<ClientOfferStatus>> kp = new HashMap<>();   // заявка → статусы её КП
        for (Object[] row : clientOffers.findStatusesByTenderIds(ids)) {
            kp.computeIfAbsent((Long) row[0], k -> EnumSet.noneOf(ClientOfferStatus.class)).add((ClientOfferStatus) row[1]);
        }
```

вызов `summary(...)` — последним аргументом `kp.getOrDefault(t.getId(), Set.of())`; `summary` получает параметр `Set<ClientOfferStatus> offers` и после трёх «Закрыта: …»:

```java
        if (!offers.isEmpty()) {   // правило 2 (спека §5.4): по лучшему статусу КП
            if (offers.contains(ClientOfferStatus.ACCEPTED)) return new Summary("KP_ACCEPTED", "КП принято", lines);
            if (offers.contains(ClientOfferStatus.SENT)) return new Summary("KP_SENT", "КП отправлено", lines);
            if (offers.contains(ClientOfferStatus.DRAFT)) return new Summary("KP_DRAFT", "КП — черновик", lines);
            return new Summary("KP_REJECTED", "КП отклонено", lines);
        }
```

javadoc класса: «закрыта → (КП клиенту — этап 3) → …» → «закрыта → КП клиенту → …»; «Три запроса на весь список» → «Четыре запроса…».

- [ ] **Step 4: Run the tests to verify they pass**

Run (sandbox off): `./gradlew test --tests 'com.vladoose.nir.privaterequest.*' --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 5: Mutation**

`summary`: поменять местами проверки `ACCEPTED` и `SENT` → Expected FAIL `stageFollowsBestKpStatus_closedRequestStaysClosed`. Откат копией, `diff -q`, `./gradlew compileJava`.

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java src/main/java/com/vladoose/nir/repository/TenderRepository.java src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java src/main/java/com/vladoose/nir/dto/response/ClientOfferListItemResponse.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestViewResponse.java src/main/java/com/vladoose/nir/dto/response/PrivateRequestOfferRef.java src/main/java/com/vladoose/nir/service/ClientOfferMapper.java src/main/java/com/vladoose/nir/controller/ClientOfferController.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestViewBuilder.java src/main/java/com/vladoose/nir/service/privaterequest/PrivateRequestStageCalculator.java src/test/java/com/vladoose/nir/privaterequest/PrivateRequestStage3Test.java
git commit -m "feat(private-request): этап по КП клиенту, раздел КП в заявке, номер заявки в КП

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 6: Фронт — типы, API, раздел «КП клиенту» в заявке, журнал

**Files:**
- Modify: `frontend/src/app/shared/client-offer.ts`, `frontend/src/app/shared/private-request.ts`, `frontend/src/app/services/api.service.ts`
- Create: `frontend/src/app/pages/private-requests/pr-offers.component.ts`
- Modify: `frontend/src/app/pages/private-requests/private-request-page.component.ts`, `frontend/src/app/pages/client-offers/client-offers.component.ts`

**Interfaces:**
- Consumes: пути и поля задач 2–5.
- Produces: `ApiService.createClientOfferFromRequest(requestId)`, `getClientOfferItemOffers(offerId, itemId)`, `getClientOfferNewPrices(offerId)`, `applyClientOfferNewPrices(offerId)`; типы `SupplierOffer`, `NewPriceLine`, `PrOfferRef`; поля `ClientOffer.tenderNumber`, `OfferItem.priceRequestItemId/tenderLotId/distributorId`.

- [ ] **Step 1: Types and API**

`shared/client-offer.ts`:
- в `ClientOffer` после `tenderId: number | null;` — `tenderNumber: string | null;`
- в `OfferItem` после `supplierName?`:

```ts
  /** строка ответа поставщика, из которой закупка («Поставщик ▾»); ручная правка закупки её снимает (спека §8) */
  priceRequestItemId?: number | null;
  /** позиция заявки и поставщик ответа — ставит сервер, только для чтения */
  tenderLotId?: number | null;
  distributorId?: number | null;
```

- в `toRequest` у строки после `supplierName: …,` — `priceRequestItemId: it.priceRequestItemId ?? null,`
- новые типы:

```ts
/** Ответ поставщика с ценой на позицию строки — пункт «Поставщик ▾» (спека 2026-10-12 §8). */
export interface SupplierOffer {
  priceRequestItemId: number; supplierName: string; price: number; respondedOn: string | null;
  accepted: boolean; cheapest: boolean; selected: boolean;
}

/** Строка плашки «Пришли новые цены». */
export interface NewPriceLine {
  itemId: number; lineNo: number; name: string; purchasePrice: number | null; newPrice: number; supplierName: string | null;
}
```

`shared/private-request.ts`:

```ts
/** КП клиенту по заявке — раздел «КП клиенту» (спека §8). */
export interface PrOfferRef {
  id: number; number: number; offerDate: string; status: string; itemCount: number; totalAmount: number | null; currency: string;
}
```

в `PrView` после `lead: LeadRef | null;` — `offers: PrOfferRef[];`; в `stageTone` до `default`:

```ts
    case 'KP_ACCEPTED': return 'success';
    case 'KP_SENT': return 'accent';
    case 'KP_REJECTED': return 'danger';
```

`services/api.service.ts`: импорт `import type { NewPriceLine, PreviewPages, SupplierOffer } from '../shared/client-offer';` (вместо одиночного `PreviewPages`) и после `downloadClientOfferDocx`:

```ts
  /** КП клиенту из частной заявки (спека 2026-10-12 §8). */
  createClientOfferFromRequest(requestId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/from-private-request/${requestId}`, {});
  }
  getClientOfferItemOffers(offerId: number, itemId: number): Observable<SupplierOffer[]> {
    return this.http.get<SupplierOffer[]>(`${this.base}/client-offers/${offerId}/items/${itemId}/offers`);
  }
  getClientOfferNewPrices(offerId: number): Observable<{ lines: NewPriceLine[] }> {
    return this.http.get<{ lines: NewPriceLine[] }>(`${this.base}/client-offers/${offerId}/new-prices`);
  }
  applyClientOfferNewPrices(offerId: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${offerId}/apply-new-prices`, {});
  }
```

- [ ] **Step 2: Раздел «КП клиенту»**

`pages/private-requests/pr-offers.component.ts`:

```ts
import { ChangeDetectorRef, Component, DestroyRef, Input } from '@angular/core';
import { takeUntilDestroyed } from '@angular/core/rxjs-interop';
import { NgFor, NgIf } from '@angular/common';
import { Router, RouterLink } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import type { PrView } from '../../shared/private-request';
import { OFFER_STATUS_LABELS, dateText, money } from '../../shared/client-offer';
import type { OfferStatus } from '../../shared/client-offer';
import { errorText } from '../../shared/error-text';

/**
 * «КП клиенту» на странице заявки (спека 2026-10-12 §8): КП по заявке — номер, дата, статус, позиции, сумма — и
 * «Создать КП клиенту»: черновик из позиций, РУ и цен ответов, сразу открывается редактор.
 */
@Component({
  selector: 'app-pr-offers',
  standalone: true,
  imports: [NgIf, NgFor, RouterLink],
  template: `
    <section class="pr-offers">
      <div class="head">
        <h3>КП клиенту</h3>
        <button type="button" class="btn btn-primary" *ngIf="!readonly && view.lines.length" [disabled]="busy" (click)="create()">
          {{ busy ? 'Создаю…' : 'Создать КП клиенту' }}</button>
      </div>
      <p class="empty" *ngIf="!view.offers.length">
        КП по заявке ещё нет{{ view.lines.length ? '' : ' — сначала добавьте позиции' }}.</p>
      <a class="offer" *ngFor="let o of view.offers" [routerLink]="['/client-offers', o.id]">
        <span class="num">№ {{ o.number }} от {{ dateText(o.offerDate) }}</span>
        <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
        <span class="meta">{{ o.itemCount }} поз. · {{ money(o.totalAmount) }} {{ o.currency === 'RUB' ? '₽' : '₸' }}</span>
      </a>
    </section>
  `,
  styles: [`
    .pr-offers { display: flex; flex-direction: column; gap: 8px; margin-top: 20px; }
    .head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
    h3 { margin: 0; }
    .empty { margin: 0; }
    .offer { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 10px 12px; border: 1px solid var(--border);
             border-radius: 10px; color: var(--text); text-decoration: none; background: var(--surface); }
    .offer:hover { border-color: var(--accent); }
    .num { font-weight: 600; }
    .meta { margin-left: auto; color: var(--text-muted); }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    @media (max-width: 900px) {
      .meta { margin-left: 0; width: 100%; }
      .offer { min-height: 40px; }
    }
  `],
})
export class PrOffersComponent {
  @Input({ required: true }) view!: PrView;
  @Input() readonly = false;
  busy = false;
  dateText = dateText;
  money = money;

  constructor(private api: ApiService, private router: Router, private notify: NotificationService,
              private cdr: ChangeDetectorRef, private destroyRef: DestroyRef) {}

  statusLabel(s: string): string {
    return OFFER_STATUS_LABELS[s as OfferStatus] ?? s;
  }

  create() {
    if (this.busy) return;
    this.busy = true;
    this.api.createClientOfferFromRequest(this.view.id).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: o => { this.router.navigate(['/client-offers', o.id]); },
      error: e => { this.busy = false; this.notify.error('КП не создано: ' + errorText(e)); this.cdr.markForCheck(); },
    });
  }
}
```

> Проверить при исполнении: путь `NotificationService` (`../../services/notification.service`) и форму `OFFER_STATUS_LABELS`; символ валюты — как в журнале (`MarketService.symbol()`), если так проще: тогда `@Input() symbol`.

`private-request-page.component.ts`:
- импорт и `imports: [..., PrOffersComponent]`, `@ViewChild('offersPart') offersPart?: PrOffersComponent;`
- после `<app-pr-supply …>`: `<app-pr-offers #offersPart [view]="v" [readonly]="!auth.isAdmin()"></app-pr-offers>`
- в шапке после блока «Из обращения» (решение спеки §5.1 — «КП по заявке» в шапке):

```html
      <div class="from-lead" *ngIf="v.offers.length">
        КП клиенту: <a [routerLink]="['/client-offers', v.offers[0].id]">№ {{ v.offers[0].number }} · {{ offerStatus(v.offers[0].status) }}</a>
      </div>
```

с методом `offerStatus(s: string) { return OFFER_STATUS_LABELS[s as OfferStatus] ?? s; }` (импорт из `shared/client-offer`);
- липкая панель на телефоне (спека §5.6) — рядом с «Запросить цены»:

```html
      <div class="pr-bar" *ngIf="auth.isAdmin() && v.stage === 'PRICED'">
        <button type="button" class="btn btn-primary" [disabled]="offersPart?.busy" (click)="offersPart?.create()">КП клиенту</button>
      </div>
```

`client-offers.component.ts` (журнал): строка клиента:

```html
        <div class="client">{{ o.clientName || 'Клиент не указан' }}<span class="from-pr" *ngIf="o.tenderNumber"> · из заявки {{ o.tenderNumber }}</span></div>
```

стиль `.from-pr { color: var(--text-muted); font-weight: 400; }`.

- [ ] **Step 3: Build**

Run: `npm --prefix frontend run build`
Expected: `Output location: …/frontend/dist/nir-frontend`, без ошибок; начальный бандл — не больше прежнего (≈1,34 МБ).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/shared/client-offer.ts frontend/src/app/shared/private-request.ts frontend/src/app/services/api.service.ts frontend/src/app/pages/private-requests/pr-offers.component.ts frontend/src/app/pages/private-requests/private-request-page.component.ts frontend/src/app/pages/client-offers/client-offers.component.ts
git commit -m "feat(private-request): раздел «КП клиенту» на странице заявки, «из заявки» в журнале КП

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 7: Фронт — редактор КП: «из заявки», «Поставщик ▾», плашка новых цен

**Files:**
- Modify: `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`
- Modify: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`

**Interfaces:**
- Consumes: API задачи 6.

- [ ] **Step 1: Editor**

`client-offer-editor.component.ts`:
- шапка, после `<span class="st" …>`:

```html
          <a class="from-pr" *ngIf="o.tenderId" [routerLink]="['/private-requests', o.tenderId]">из заявки {{ o.tenderNumber || ('№ ' + o.tenderId) }}</a>
```

  стиль `.from-pr { font-size: 13px; color: var(--accent); }`;
- поле `newPrices: NewPriceLine[] = [];` (импорт `NewPriceLine` из `shared/client-offer`);
- плашка — первой в `<section class="pane-edit" …>`:

```html
          <div class="new-prices" *ngIf="auth.isAdmin() && o.status === 'DRAFT' && newPrices.length && !conflict" role="status">
            <span>Пришли новые цены по {{ newPrices.length }} {{ newPrices.length === 1 ? 'позиции' : 'позициям' }}</span>
            <button type="button" class="btn btn-line" [disabled]="busy || conflict" (click)="applyNewPrices()">Подставить</button>
          </div>
```

  стиль (тинт области по kit):

```css
    .new-prices { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; padding: 10px 12px; margin-bottom: 12px;
                  border: 1px solid var(--warn); border-radius: 10px; background: color-mix(in srgb, var(--warn) 8%, var(--surface)); }
```

- методы:

```ts
  /** Плашка новых цен — при открытии и после сохранения; сбой — без плашки (КП работает и без неё). */
  private checkNewPrices() {
    const o = this.offer;
    if (!o || o.status !== 'DRAFT' || !o.tenderId || !this.auth.isAdmin()) { this.newPrices = []; return; }
    const gen = this.gen;
    this.api.getClientOfferNewPrices(o.id).subscribe({
      next: r => { if (gen === this.gen) { this.newPrices = r.lines; this.render(); } },
      error: () => { /* подсказка, без неё КП работает */ },
    });
  }

  /** «Подставить»: сперва дописать свои правки, затем POST — и КП из ответа целиком (решение плана 8). */
  applyNewPrices() {
    const o = this.offer;
    if (!o || this.conflict) return;
    this.flushThen(() => {
      if (this.busy) return;
      this.busy = true;
      const gen = this.gen;
      this.api.applyClientOfferNewPrices(o.id).subscribe({
        next: (r: ClientOffer) => {
          if (gen !== this.gen) return;
          this.busy = false;
          r.items.forEach((it: OfferItem) => { if (!it.key) it.key = 'i' + it.id; });
          this.offer = r;
          this.newPrices = [];
          this.previewTick++;
          this.notify.success('Цены подставлены');
          this.render();
        },
        error: e => {
          if (gen !== this.gen) return;
          this.busy = false;
          this.notify.error('Цены не подставлены: ' + errorText(e));
          this.render();
        },
      });
    });
  }
```

- вызовы: в `load` в `next` после `this.render();` — `this.checkNewPrices();`; в `save` в `next` в ветке `else { this.dirty = false; this.previewTick++; this.runAfterSave(); }` — `this.checkNewPrices();` перед `this.runAfterSave();`
- `merge`: в цикле по строкам — `if (s) { it.id = s.id; it.lineNo = s.lineNo; it.calc = s.calc; it.tenderLotId = s.tenderLotId; it.distributorId = s.distributorId; }` (поля сервера; ссылку `priceRequestItemId` держит экран — решение плана 6).

- [ ] **Step 2: Items — «Поставщик ▾», ручная правка, копия**

`client-offer-items.component.ts`:
- зависимость `ApiService` в конструкторе; импорт `SupplierOffer` из `shared/client-offer`;
- состояние:

```ts
  /** «Поставщик ▾»: у какой строки открыт список, ответы, загрузка (спека §8). */
  offersFor: string | null = null;
  offersList: SupplierOffer[] = [];
  offersLoading = false;
  offersError = '';
```

- в `details` вместо `<label class="wide">Поставщик …</label>`:

```html
          <div class="wide supplier">
            <label>Поставщик <input [(ngModel)]="it.supplierName" (ngModelChange)="emit()" [disabled]="readonly" maxlength="255" placeholder="только для вас" /></label>
            <button type="button" class="btn btn-line pick" *ngIf="canPick(it)" [attr.aria-expanded]="offersFor === it.key"
                    (click)="toggleOffers(it); $event.stopPropagation()">Поставщик ▾</button>
            <div class="offers" *ngIf="offersFor === it.key" (click)="$event.stopPropagation()">
              <div class="muted" *ngIf="offersLoading">Загружаю ответы…</div>
              <div class="err" *ngIf="offersError">{{ offersError }}</div>
              <div class="muted" *ngIf="!offersLoading && !offersError && !offersList.length">Ответов с ценой по этой позиции нет</div>
              <button type="button" class="offer" *ngFor="let s of offersList" [class.on]="s.priceRequestItemId === it.priceRequestItemId"
                      (click)="pickOffer(it, s)">
                <span class="ok" aria-hidden="true">{{ s.priceRequestItemId === it.priceRequestItemId ? '✓' : '' }}</span>
                <span class="nm">{{ s.supplierName }}</span>
                <span class="pr">{{ money(s.price) }}</span>
                <span class="wh" *ngIf="s.respondedOn">ответил {{ dayMonth(s.respondedOn) }}</span>
                <span class="tag" *ngIf="s.cheapest">дешевле всех</span>
                <span class="tag" *ngIf="s.accepted">принято</span>
              </button>
            </div>
          </div>
```

  стили:

```css
    .supplier { display: flex; flex-wrap: wrap; align-items: flex-end; gap: 8px; position: relative; }
    .supplier label { flex: 1 1 220px; }
    .offers { flex-basis: 100%; display: flex; flex-direction: column; gap: 4px; padding: 8px; border: 1px solid var(--border);
              border-radius: 10px; background: var(--surface); box-shadow: var(--shadow); }
    .offer { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; min-height: 40px; padding: 6px 8px; border: 0;
             border-radius: 8px; background: transparent; color: var(--text); text-align: left; cursor: pointer; }
    .offer:hover, .offer.on { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); }
    .offer .ok { width: 1em; color: var(--accent); }
    .offer .nm { flex: 1 1 160px; }
    .offer .pr { font-weight: 600; white-space: nowrap; }
    .offer .wh { color: var(--text-muted); font-size: 12px; }
    .offer .tag { font-size: 11px; padding: 1px 8px; border-radius: 999px; background: color-mix(in srgb, var(--success) 15%, transparent);
                  color: var(--success-text); }
    .offers .err { color: var(--danger-text); }
```

- методы:

```ts
  /** «Поставщик ▾» — у сохранённой строки из позиции заявки (решение плана 7: у новой строки позиции нет). */
  canPick(it: OfferItem): boolean {
    return !this.readonly && !!this.offer.tenderId && !!it.tenderLotId && !!it.id;
  }

  toggleOffers(it: OfferItem) {
    if (this.offersFor === it.key) { this.offersFor = null; return; }
    this.offersFor = it.key;
    this.offersList = [];
    this.offersError = '';
    this.offersLoading = true;
    this.api.getClientOfferItemOffers(this.offer.id, it.id!).pipe(takeUntilDestroyed(this.destroyRef)).subscribe({
      next: list => { if (this.offersFor === it.key) { this.offersList = list; this.offersLoading = false; this.cdr.markForCheck(); } },
      error: e => { if (this.offersFor === it.key) { this.offersError = errorText(e); this.offersLoading = false; this.cdr.markForCheck(); } },
    });
  }

  /** Выбор ответа: цена, поставщик и ссылка на ответ → автосохранение (сервер выведет поставщика сам). */
  pickOffer(it: OfferItem, s: SupplierOffer) {
    it.purchasePrice = s.price;
    it.supplierName = s.supplierName;
    it.priceRequestItemId = s.priceRequestItemId;
    this.offersFor = null;
    this.emit();
  }

  /** «12.10» из «2026-10-12». */
  dayMonth(iso: string): string {
    return iso.slice(8, 10) + '.' + iso.slice(5, 7);
  }
```

  (`errorText` — импорт из `../../shared/error-text`, `takeUntilDestroyed` — если ещё не импортирован);
- `closeMenu()` (document click / Escape): добавить `this.offersFor = null;`
- `setPurchase`: перед `it.purchasePrice = v;` — `if (v !== it.purchasePrice) it.priceRequestItemId = null;   // ручная правка снимает ссылку на ответ (решение плана 6)`
- `duplicateRow`: после строк про регистрацию — `copy.priceRequestItemId = null; copy.tenderLotId = null; copy.distributorId = null;   // у новой строки позиции нет: ссылка дала бы 400 на каждое автосохранение`

- [ ] **Step 3: Build**

Run: `npm --prefix frontend run build`
Expected: `Output location: …`, без ошибок и без превышения бюджетов стилей (24 kB на компонент).

- [ ] **Step 4: Commit**

```bash
git add frontend/src/app/pages/client-offers/client-offer-editor.component.ts frontend/src/app/pages/client-offers/client-offer-items.component.ts
git commit -m "feat(client-offer): редактор — «из заявки», «Поставщик ▾», плашка «Подставить новые цены»

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

---

### Task 8: Живая проверка, документация, полный прогон

**Files:**
- Modify: `CLAUDE.md` (§8 — блок «Частная заявка — этап 3» после этапа 2; §12 — фронт этапа 3; §13 — число тестов; §15 — пути; §16 — что не делалось), `docs/PROGRESS.md` («▶ Последняя задача»)
- Память: `~/.claude/projects/-Users-vlad-IdeaProjects-AIS/memory/last-task-pointer.md`, `MEMORY.md`

- [ ] **Step 1: Стек и данные** (sandbox off)

Почта — `./gradlew devMail` (фон). Бэкенд — с почтой devMail для отправки и приёма ответов (как этап 2, CLAUDE.md §5 «Почта zakup@ и Telegram — локально»): `MAIL_IMAP_ENABLED=true … MAIL_HOST=127.0.0.1 MAIL_PORT=3025 … JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun`. Фронт — `npm --prefix frontend start`. Записать до проверки: `select max(id) from tender; select max(id) from client_offer; select max(id) from price_request; select max(id) from inbound_email;`

- [ ] **Step 2: Сценарий (Playwright, 1280 и 390, обе темы)**

1. Новая заявка KZ, две позиции (одна с брендом из V28, одна без), РУ одной — «Подтвердить».
2. «Запросить цены» двум поставщикам; ответ с ценой — `python3 scripts/dev-mail.py reply <prId>` по одному из них; «Проверить почту».
3. «Создать КП клиенту» → редактор: «из заявки ЧЗ-…»; строка 1 — закупка из ответа, поставщик, регистрация «№ … от … г., …», ставка «подтверждённого»; строка 2 — без закупки (жёлтая).
4. Раскрыть строку 1 → «Поставщик ▾» — список с «дешевле всех» и ✓; выбрать другого → автосохранение; ручная правка закупки → ✓ снимается.
5. Ответ по строке 2 (`dev-mail.py reply` по второму запросу) → вернуться в КП → плашка «Пришли новые цены по 1 позиции» → «Подставить» → цена встала, плашка ушла.
6. PDF и Word выгружаются; в журнале — «из заявки ЧЗ-…»; в заявке — раздел «КП клиенту», чип этапа «КП — черновик», ссылка в шапке.
7. Оператор (operator/operator): редактор — только просмотр, «Поставщик ▾» нет; в заявке нет «Создать КП клиенту».
8. 390 px: на этапе «Есть цены» внизу липкая «КП клиенту».

- [ ] **Step 3: Уборка** — СНАЧАЛА остановить бэкенд (`lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill`), потом SQL: удалить созданные КП, запросы цен и строки «Входящих» этой проверки, курсор `mail_cursor` ящика zakup@, заявку; потом devMail (3143) и фронт (4200).

- [ ] **Step 4: Документация** — CLAUDE.md §8 (блок этапа 3: что сделано, решения плана, проверено), §12, §13, §15 (`/api/client-offers/from-private-request/{tenderId}`, `/{id}/items/{itemId}/offers`, `/{id}/new-prices`, `/{id}/apply-new-prices`, `tenderNumber`, `offers` в виде заявки), §16; PROGRESS «▶ Последняя задача»; память.

- [ ] **Step 5: Полный прогон**

Run (sandbox off): `./gradlew cleanTest test > .superpowers/sdd/2026-10-12-private-request-workspace-stage3/fullgate.log 2>&1`, затем подсчёт по `build/test-results/test/*.xml`.
Expected: 0 failures, 0 errors; skipped — 1 (калибровка).

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/PROGRESS.md
git commit -m "docs: частная заявка, этап 3 — КП клиенту из заявки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>"
```

- [ ] **Step 7: Финал** — ревью всей ветки (Opus) → одна волна исправлений (тест, красный до правки) → полный прогон → мерж в `main` fast-forward. Push — оператор (`! git push origin main`).
