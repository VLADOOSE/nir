# Эвристический парс цены из ответа поставщика (ч.3b) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Извлекать цену (и по возможности срок поставки) из вольного текста ответа поставщика на КП и автозаполнять `PriceRequestItem.responsePrice` для одно-лотовых КП.

**Architecture:** Чистый статический экстрактор `util/SupplierReplyPriceParser` (без I/O, паттерн `SpecConstraintExtractor`/`LotQueryTokenizer`) + тонкий хук в `MailReceiveService.matchSupplierResponse` (срабатывает только для КП с ровно 1 позицией и пустой ценой) + фолбэк `collect()` на `text/html`.

**Tech Stack:** Java 17, Spring Boot 3.5.6, JUnit 5, AssertJ, GreenMail (IMAP-тест), jakarta.mail.

## Global Constraints

- Пакет чистых экстракторов — `com.vladoose.nir.util`, класс `public final` + `private` конструктор + `public static` методы (как `SpecConstraintExtractor`). Зовётся статически, НЕ Spring-бин.
- Парсер НЕ бросает исключений — всегда `Optional`; при любой неоднозначности/сбое/отсутствии якоря → `Optional.empty()` (не гадать).
- Хук: писать цену только если у КП ровно 1 `PriceRequestItem` И `responsePrice == null` (не затирать ручной ввод). `RESPONDED` ставится всегда (как сейчас).
- Рынок для парсера — `pr.getMarket()` (не `mailboxMarket`). KZ → `₸/тенге/тг/тнг/KZT`; RF → `₽/руб/RUB`.
- Схема БД и фронт НЕ меняются. Коммит-трейлер: `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.
- Тесты гонять с `dangerouslyDisableSandbox: true`, перед прогоном `lsof -ti :8080 | xargs kill -9`.

---

### Task 1: `SupplierReplyPriceParser` — чистый экстрактор цены + срока

**Files:**
- Create: `src/main/java/com/vladoose/nir/util/SupplierReplyPriceParser.java`
- Test: `src/test/java/com/vladoose/nir/util/SupplierReplyPriceParserTest.java`

**Interfaces:**
- Consumes: `com.vladoose.nir.entity.Market` (enum `RF`/`KZ`).
- Produces: `public static Optional<ParsedPrice> parse(String rawBody, Market market)`;
  `public record ParsedPrice(BigDecimal price, String term, String matchedSnippet)`.

- [ ] **Step 1: Написать падающий тест (golden-корпус)**

Create `src/test/java/com/vladoose/nir/util/SupplierReplyPriceParserTest.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.entity.Market;
import com.vladoose.nir.util.SupplierReplyPriceParser.ParsedPrice;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SupplierReplyPriceParserTest {

    private BigDecimal price(String body, Market m) {
        Optional<ParsedPrice> r = SupplierReplyPriceParser.parse(body, m);
        assertThat(r).as("ожидалась цена в: %s", body).isPresent();
        return r.get().price();
    }

    @Test
    void realReply_priceWithCurrency_andStripsOurQuotedOriginal() {
        String body = "Цена 3 200 000 ₸, срок 3 недели\r\n\r\n"
                + "On Fri, Jul 10, 2026 at 1:42 PM <zakup@westmed.kz> wrote:\r\n"
                + "> Здравствуйте!\r\n"
                + "> — Лот —: ... РУ № РК МИ (МТ)-0№030788 — 1 шт.\r\n"
                + "> Просим ответить до 13.07.2026.\r\n";
        Optional<ParsedPrice> r = SupplierReplyPriceParser.parse(body, Market.KZ);
        assertThat(r).isPresent();
        assertThat(r.get().price()).isEqualByComparingTo("3200000");
        assertThat(r.get().term()).contains("3").containsIgnoringCase("недел");
    }

    @Test
    void decline_noPrice_returnsEmpty() {
        String body = "Здравствуйте! Спасибо за обращение. "
                + "Запрашиваемые вами изделия мы не поставляем.\r\n"
                + "Моб/WhatsApp: +7 700 025-88-50";
        assertThat(SupplierReplyPriceParser.parse(body, Market.KZ)).isEmpty();
    }

    @Test
    void counterQuestion_noPrice_returnsEmpty() {
        String body = "Добрый день. 1. Какого объёма нужен холодильник? "
                + "2. Со стеклянной или металической дверью?";
        assertThat(SupplierReplyPriceParser.parse(body, Market.KZ)).isEmpty();
    }

    @Test
    void variousFormats() {
        assertThat(price("Наша цена 3200000 тенге", Market.KZ)).isEqualByComparingTo("3200000");
        assertThat(price("Стоимость: 3 200 000,00 тг", Market.KZ)).isEqualByComparingTo("3200000.00");
        assertThat(price("цена 3200000", Market.KZ)).isEqualByComparingTo("3200000");
        assertThat(price("Предлагаем 2 950 000 ₸", Market.KZ)).isEqualByComparingTo("2950000");
    }

    @Test
    void ignoresPhoneNumbers_whenNoPrice() {
        assertThat(SupplierReplyPriceParser.parse("Звоните: +7 700 025-88-50", Market.KZ)).isEmpty();
    }

    @Test
    void picksPrice_notPhone_whenBothPresent() {
        String body = "Цена 3 200 000 ₸. Тел: +7 700 025-88-50";
        assertThat(price(body, Market.KZ)).isEqualByComparingTo("3200000");
    }

    @Test
    void htmlBody_stripsTags_findsPrice() {
        String body = "<div>Цена <b>3 200 000</b>&nbsp;₸</div>";
        assertThat(price(body, Market.KZ)).isEqualByComparingTo("3200000");
    }

    @Test
    void rfMarket_rubles() {
        assertThat(price("цена 450 000 руб", Market.RF)).isEqualByComparingTo("450000");
    }

    @Test
    void bareNumberWithoutAnchor_returnsEmpty() {
        // число есть, но нет ни валюты, ни слова-якоря → не гадаем
        assertThat(SupplierReplyPriceParser.parse("Отгрузим 3200000 штук со склада", Market.KZ)).isEmpty();
    }

    @Test
    void nullOrBlank_returnsEmpty() {
        assertThat(SupplierReplyPriceParser.parse(null, Market.KZ)).isEmpty();
        assertThat(SupplierReplyPriceParser.parse("   ", Market.KZ)).isEmpty();
    }
}
```

- [ ] **Step 2: Запустить тест — убедиться, что падает (класса нет)**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.util.SupplierReplyPriceParserTest"`
(Bash с `dangerouslyDisableSandbox: true`.)
Expected: FAIL — компиляция не проходит, `SupplierReplyPriceParser` / `ParsedPrice` не существуют.

- [ ] **Step 3: Реализовать парсер**

Create `src/main/java/com/vladoose/nir/util/SupplierReplyPriceParser.java`:

```java
package com.vladoose.nir.util;

import com.vladoose.nir.entity.Market;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Эвристический парс ответа поставщика на КП: извлекает цену (и по возможности срок поставки)
 * из вольного текста письма. Чистая функция без I/O — юнит-тестируется на корпусе.
 * Не бросает: при любой неоднозначности/сбое/отсутствии якоря → Optional.empty() (не гадает).
 */
public final class SupplierReplyPriceParser {

    private SupplierReplyPriceParser() {}

    public record ParsedPrice(BigDecimal price, String term, String matchedSnippet) {}

    /** Начало цитаты нашего же письма — всё от маркера и ниже отбрасываем. */
    private static final Pattern QUOTE_BOUNDARY = Pattern.compile(
            "(?im)^[ \\t]*(>|On .+wrote:|From:[ \\t]|Sent:[ \\t]|Отправлено:|От кого:|-{3,}[ ]*Original)");

    private static final Pattern HTML_TAG = Pattern.compile("(?s)<[^>]+>");

    /** Телефон (KZ/RU) — вырезаем до поиска цены, чтобы его цифры не стали кандидатом. */
    private static final Pattern PHONE = Pattern.compile(
            "(?:\\+7|8)[ \\-]?\\(?\\d{3}\\)?[ \\-]?\\d{3}[ \\-]?\\d{2}[ \\-]?\\d{2}");

    private static final Pattern DATE = Pattern.compile(
            "\\b\\d{1,2}[.\\-/]\\d{1,2}[.\\-/]\\d{2,4}\\b");

    /** Число-цена: сгруппированное тысячами ИЛИ ≥4 цифр подряд, с опц. десятичной частью. */
    private static final Pattern MONEY = Pattern.compile(
            "\\d{1,3}(?:[ \\u00A0.]\\d{3})+(?:,\\d{1,2})?|\\d{4,}(?:[.,]\\d{1,2})?");

    private static final Pattern PRICE_KEYWORD = Pattern.compile(
            "(?i)(цена|стоимост|сумма|за единиц|итого|составля|прайс|price)");

    private static final Pattern TERM = Pattern.compile(
            "(?i)срок[^.\\n\\r]{0,40}?(\\d{1,3})\\s*(рабочих\\s*)?(дн|недел|мес)\\w*");

    public static Optional<ParsedPrice> parse(String rawBody, Market market) {
        try {
            if (rawBody == null || rawBody.isBlank()) return Optional.empty();
            String text = stripQuote(stripHtml(rawBody));
            if (text.isBlank()) return Optional.empty();

            String term = extractTerm(text);
            // Скраб телефонов/дат — их цифры не должны стать кандидатами цены.
            String scrubbed = DATE.matcher(PHONE.matcher(text).replaceAll(" ")).replaceAll(" ");
            String[] currency = currencyAnchors(market);

            Matcher m = MONEY.matcher(scrubbed);
            BigDecimal bestVal = null;
            String bestRaw = null;
            int bestScore = 0;
            while (m.find()) {
                BigDecimal val = toBigDecimal(m.group());
                if (val == null) continue;
                int score = anchorScore(scrubbed, m.start(), m.end(), currency);
                boolean better = score > bestScore
                        || (score == bestScore && bestVal != null && val.compareTo(bestVal) > 0);
                if (better) { bestScore = score; bestVal = val; bestRaw = m.group(); }
            }
            if (bestScore == 0 || bestVal == null) return Optional.empty(); // нет якоря → не гадаем
            return Optional.of(new ParsedPrice(bestVal, term, snippetAround(text, bestRaw)));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private static String stripHtml(String s) {
        if (s.indexOf('<') < 0) return s;
        String noTags = HTML_TAG.matcher(s).replaceAll(" ");
        return noTags.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">").replaceAll("&#\\d+;", " ");
    }

    private static String stripQuote(String s) {
        Matcher m = QUOTE_BOUNDARY.matcher(s);
        return m.find() ? s.substring(0, m.start()) : s;
    }

    private static String[] currencyAnchors(Market market) {
        return market == Market.RF
                ? new String[]{"₽", "руб", "rub"}
                : new String[]{"₸", "тенге", "тнг", "тг", "kzt"};
    }

    /** Якорь: валюта справа (≤6 симв.) или слово-цена слева (≤24 симв.) = сильный (2);
     *  валюта чуть дальше (≤12) = слабый (1); иначе 0. */
    private static int anchorScore(String text, int start, int end, String[] currency) {
        String after = text.substring(end, Math.min(text.length(), end + 6)).toLowerCase();
        for (String c : currency) if (after.contains(c)) return 2;
        String before = text.substring(Math.max(0, start - 24), start).toLowerCase();
        if (PRICE_KEYWORD.matcher(before).find()) return 2;
        String farAfter = text.substring(end, Math.min(text.length(), end + 12)).toLowerCase();
        for (String c : currency) if (farAfter.contains(c)) return 1;
        return 0;
    }

    /** "3 200 000" → 3200000; "3 200 000,00" → 3200000.00; "3.200.000" → 3200000. */
    private static BigDecimal toBigDecimal(String raw) {
        String s = raw.replace(" ", "").replace(" ", "");
        if (s.contains(",")) {
            s = s.replace(".", "").replace(",", ".");        // запятая — десятичная, точки — тысячи
        } else if (s.matches("\\d{1,3}(\\.\\d{3})+")) {
            s = s.replace(".", "");                           // точки — разделители тысяч
        } // иначе одиночная точка = десятичная — оставляем как есть
        try { return new BigDecimal(s); } catch (NumberFormatException e) { return null; }
    }

    private static String extractTerm(String text) {
        Matcher m = TERM.matcher(text);
        return m.find() ? m.group().trim() : null;
    }

    private static String snippetAround(String text, String raw) {
        int i = text.indexOf(raw);
        if (i < 0) return raw;
        int from = Math.max(0, i - 20), to = Math.min(text.length(), i + raw.length() + 12);
        return text.substring(from, to).trim().replaceAll("\\s+", " ");
    }
}
```

- [ ] **Step 4: Запустить тест — убедиться, что проходит**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.util.SupplierReplyPriceParserTest"`
Expected: PASS (10 тестов зелёные).

- [ ] **Step 5: Коммит**

```bash
git add src/main/java/com/vladoose/nir/util/SupplierReplyPriceParser.java \
        src/test/java/com/vladoose/nir/util/SupplierReplyPriceParserTest.java
git commit -m "feat(response-price): SupplierReplyPriceParser — эвристический парс цены+срока

Отрезает цитату нашего письма, снимает HTML, скрабит телефоны/даты, ищет число по
якорям валюты/слов (KZ ₸/тенге, RF ₽/руб); нет якоря → empty (не гадает). Golden-тесты
на реальном корпусе (КП-508, отказ adamant, вопрос medstore, форматы, HTML, RF).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Хук в `MailReceiveService` + фолбэк `collect()` на `text/html`

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/MailReceiveService.java`
- Test: `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java` (добавить метод)

**Interfaces:**
- Consumes: `SupplierReplyPriceParser.parse(String, Market)` из Task 1;
  `PriceRequest.getItems() : List<PriceRequestItem>`, `PriceRequest.getMarket() : Market`;
  `PriceRequestItem.getResponsePrice()/setResponsePrice(BigDecimal)/setResponseNote(String)`.
- Produces: побочный эффект — у одно-лотового КП после `poll()` проставлена `responsePrice`.

- [ ] **Step 1: Написать падающий интеграционный тест**

Добавить метод в `src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java`
(перед закрывающей `}` класса; переиспользует поля/`message()` из файла):

```java
    @Test
    void poll_singleLotKp_autoFillsResponsePrice() throws Exception {
        MarketContext.set(Market.KZ);
        Facility fac = facilityRepository.save(Facility.builder().name("ZZPRICE Клиника").build());
        Distributor dist = distributorRepository.save(
                Distributor.builder().name("ZZPRICE Дистр").email("p@x.kz").build());
        Tender tender = Tender.builder()
                .tenderNumber("ZZPRICE-T1").facility(fac).status("NEW")
                .source(Source.PRIVATE_REQUEST).build();
        TenderLot lot = TenderLot.builder().tender(tender).equipName("Аппарат").quantity(1).build();
        tender.getLots().add(lot);
        tender = tenderRepository.save(tender);                 // cascade сохраняет лот
        TenderLot savedLot = tender.getLots().get(0);

        PriceRequest pr = PriceRequest.builder()
                .tender(tender).distributor(dist).status("SENT").build();
        pr.getItems().add(PriceRequestItem.builder()
                .priceRequest(pr).tenderLot(savedLot).requestedQuantity(1).build());
        pr = priceRequestRepository.save(pr);                   // cascade сохраняет item
        Long prId = pr.getId();

        GreenMailUser user = greenMail.setUser("zakup@westmed.kz", "zakup@westmed.kz", "secret");
        user.deliver(message("supplier@x.kz",
                "Re: КП " + KpToken.subjectToken(prId), "Цена 3 200 000 ₸, срок 3 недели", null, null));

        MarketContext.set(Market.KZ);
        mailReceiveService.poll();

        PriceRequest reloaded = priceRequestRepository.findById(prId).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo("RESPONDED");
        PriceRequestItem item = reloaded.getItems().get(0);
        assertThat(item.getResponsePrice()).isEqualByComparingTo("3200000");
        assertThat(item.getResponseNote()).containsIgnoringCase("распознан");
    }
```

- [ ] **Step 2: Запустить тест — убедиться, что падает**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.mail.MailReceiveServiceIntegrationTest.poll_singleLotKp_autoFillsResponsePrice"`
Expected: FAIL — `item.getResponsePrice()` == null (хук ещё не добавлен).

- [ ] **Step 3: Добавить импорт парсера**

В `MailReceiveService.java` после строки `import com.vladoose.nir.util.KpToken;` добавить:

```java
import com.vladoose.nir.util.SupplierReplyPriceParser;
```

- [ ] **Step 4: Встроить хук в `matchSupplierResponse`**

Заменить блок `if ("CREATED".equals(st) || "SENT".equals(st)) { ... }` (внутри `matchSupplierResponse`) на:

```java
        if ("CREATED".equals(st) || "SENT".equals(st)) {
            pr.setStatus("RESPONDED");
            pr.setResponseDate(LocalDate.now());
            pr.setNote(trunc(body, 4000));
            // ч.3b: авто-парс цены для одно-лотового КП (не затираем ручной ввод)
            if (pr.getItems().size() == 1) {
                PriceRequestItem item = pr.getItems().get(0);
                if (item.getResponsePrice() == null) {
                    SupplierReplyPriceParser.parse(body, pr.getMarket()).ifPresent(pp -> {
                        item.setResponsePrice(pp.price());
                        item.setResponseNote("💡 Цена распознана автоматически, проверьте."
                                + (pp.term() != null ? " Срок: " + pp.term() + "." : ""));
                    });
                }
            }
            priceRequestRepository.save(pr);   // cascade ALL сохранит правку item
        }
```

- [ ] **Step 5: Запустить новый тест — убедиться, что проходит**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.mail.MailReceiveServiceIntegrationTest.poll_singleLotKp_autoFillsResponsePrice"`
Expected: PASS.

- [ ] **Step 6: Фолбэк `collect()` на `text/html` (для HTML-only писем)**

В `MailReceiveService.java`:

(a) В классе `Extracted` добавить поле рядом с `text`:

```java
    private static class Extracted {
        final StringBuilder text = new StringBuilder();
        final StringBuilder html = new StringBuilder();
        byte[] attachment;
        String attachmentName;
    }
```

(b) В `collect(...)` в ветке `else if (part.isMimeType("text/plain"))` добавить парную ветку html
(после блока text/plain, перед закрытием метода):

```java
        } else if (part.isMimeType("text/plain")) {
            Object c = part.getContent();
            if (c != null) ex.text.append(c);
        } else if (part.isMimeType("text/html")) {
            Object c = part.getContent();
            if (c != null) ex.html.append(c);
        }
```

(c) В `handle(...)` заменить использование `text` на фолбэк. После `StringBuilder text = ex.text;`
(строка ~121) добавить и далее использовать `bodyText`:

```java
        String bodyText = ex.text.length() > 0 ? ex.text.toString() : ex.html.toString();
```

Заменить `matchSupplierResponse(kp.get(), text.toString())` → `matchSupplierResponse(kp.get(), bodyText)`
и `.excerpt(trunc(text.toString(), 2000))` → `.excerpt(trunc(bodyText, 2000))`.

- [ ] **Step 7: Прогнать почтовые тесты целиком — регресса нет**

Run: `lsof -ti :8080 | xargs kill -9 2>/dev/null; ./gradlew test --tests "com.vladoose.nir.mail.*"`
Expected: PASS (оба прежних теста + новый). Прежний `poll_matchesSupplierResponse_andQueuesClientExcel`
использует PR без items → хук пропускается (size 0), регресса нет.

- [ ] **Step 8: Коммит**

```bash
git add src/main/java/com/vladoose/nir/service/MailReceiveService.java \
        src/test/java/com/vladoose/nir/mail/MailReceiveServiceIntegrationTest.java
git commit -m "feat(response-price): хук авто-парса цены в matchSupplierResponse + html-фолбэк collect()

Одно-лотовый КП с пустой ценой → SupplierReplyPriceParser заполняет responsePrice +
пометку в responseNote. collect() берёт text/html, если нет text/plain (HTML-only письма).
GreenMail-тест: ответ «Цена 3 200 000 ₸» → responsePrice=3200000.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Одно-лотовые only → хук `items.size()==1` ✓ (Task 2 Step 4).
- Цена → `responsePrice`, срок → note ✓ (Task 1 record + Task 2 note).
- Отрезать цитату ✓ (`stripQuote`), HTML→текст ✓ (`stripHtml` + collect-фолбэк), якоря валюты/слов ✓,
  отсечь телефоны/даты ✓ (скраб), нет якоря → empty ✓.
- Fill-if-empty, не затирать ✓ (`responsePrice == null`). RESPONDED всегда ✓.
- Тесты на реальном корпусе ✓ (Task 1) + интеграция GreenMail ✓ (Task 2).
- YAGNI: много-лотовый/вложения/модель-РУ/LLM — не трогаем ✓.

**2. Placeholder scan:** нет TBD/«handle edge cases» — весь код приведён. ✓

**3. Type consistency:** `parse(String, Market) → Optional<ParsedPrice>`; `ParsedPrice(price, term, matchedSnippet)`;
`getItems()/getMarket()/getResponsePrice()/setResponsePrice/setResponseNote` — совпадают между Task 1 и Task 2. ✓

**Примечание о деве от спеки:** спека упоминала пакет `service` и «инъекцию парсера» — уточнено на
`util` + статический вызов (паттерн существующих экстракторов, проще, без правки конструктора
`MailReceiveService`).
