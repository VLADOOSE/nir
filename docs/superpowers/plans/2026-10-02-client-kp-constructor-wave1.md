# КП клиенту — конструктор коммерческих предложений, волна 1 — план реализации

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Отец (директор West-Med / Регион-Мед) собирает КП клиенту в АИС, а не в Word: позиции, количество, наценка с учётом НДС, ставка НДС у каждой строки, выбор и переименование колонок, условия, предпросмотр страницами, выгрузка в PDF и Word, галочка «Подпись и печать»; реквизиты, печать и подпись — на странице «Система → Реквизиты и печать»; журнал КП с копированием. Волна 2 (позиции из частной заявки, Excel поставщика, реестр и каталог) — отдельным планом после этой.

**Architecture:** Одна модель документа на сервере. `ClientOfferCalculator` считает цены и итоги (единственное место формул), `KpDocumentBuilder` собирает неизменяемую `KpDocument` (уже отформатированные строки), из неё два рендерера: HTML-шаблон Thymeleaf → openhtmltopdf (PDFBox 3) → PDF → PDFBox → PNG-страницы предпросмотра; Apache POI XWPF → DOCX. Реквизиты рынка — строка `company_profile` (как `email_template`), КП — рыночная сущность `client_offer` со строками `client_offer_item` (миграция V23). Фронт: ленивые страницы «КП клиентам» (журнал + редактор с автосохранением и предпросмотром) и «Реквизиты и печать».

**Tech Stack:** Java 17, Spring Boot 3.5.6, Hibernate 6.6 (`@JdbcTypeCode(SqlTypes.JSON)` → `jsonb`), Flyway (V23), Thymeleaf (уже подключён), openhtmltopdf 1.1.87 (`io.github.openhtmltopdf`, на PDFBox 3.0.7), Apache POI 5.2.5 XWPF (`poi-ooxml-lite` — нужные классы проверены), jsoup 1.18.1 (HTML → W3C DOM), шрифты Liberation (SIL OFL); JUnit 5 + AssertJ + MockMvc на nirdb; Angular 21.2 standalone + `@angular/cdk` 21.2 (drag-drop).

**Spec:** `docs/superpowers/specs/2026-10-02-client-kp-constructor-design.md` — читать вместе с планом. Этот план — **волна 1** (§14 спеки): §4–§8, §9.1, §10 (строки «волна 1»), §11–§13, §7.4 «заодно».

## Проверено до написания плана (пробные запуски 2026-10-02)

- **openhtmltopdf 1.1.87 + Liberation Serif:** кириллица, все казахские буквы (ӘәҒғҚқҢңӨөҰұҮүҺһІі), «№», «« »» и тире извлекаются из PDF; шапка таблицы (`thead` + `-fs-table-paginate: paginate`) повторяется на каждой странице; длинные коды моделей без пробелов переносятся внутри ячейки (`word-wrap: break-word`); печать `position: absolute` ложится поверх строки подписи; `useExternalResourceAccessControl(... RUN_BEFORE_RESOLVING_URI)` с разрешением только `data:` — **0 обращений** к локальному серверу-ловушке. 4 страницы PDF — 0,7 с на холодной JVM, PNG 4 страниц при 110 dpi — 0,46 с.
- **Транзитивная зависимость:** openhtmltopdf-pdfbox тянет `de.rototor.pdfbox:graphics2d:3.0.1` и PDFBox 3.0.7 — Gradle подтянет сам; PDFBox проекта поднимается с 3.0.5 до 3.0.7.
- **Liberation Serif 2.1.5:** знаков ₸ (U+20B8) и ₽ (U+20BD) **нет**, и в нынешнем `/fonts/DejaVuSans.ttf` (на деле Arial) их тоже нет → валюта в документах — словами («тг», «тенге», «руб.»), как в КП отца.
- **POI XWPF:** повтор шапки (`setRepeatHeader`), объединение (`gridSpan`) и плавающая картинка (`wp:anchor` + `wrapNone`, собранный из `CTAnchor.Factory.parse`) записываются и читаются обратно. Ширина таблицы — **только** явная: `tblW` в DXA + `tblLayout fixed` + `gridCol` + `tcW` у каждой ячейки (`setWidth("100%")` не работает). Все нужные схемные классы (`CTAnchor`, `CTPageSz`, `STPageOrientation`, `CTTblLayoutType`, `CTTcPr`, `CTVerticalJc`, …) есть в `poi-ooxml-lite-5.2.5`.
- ⚠️ **Quick Look на Mac плавающие картинки .docx НЕ рисует** (и ширины ячеек игнорирует) — печать в .docx глазами проверяется только в самом Word. Автоматически открыть Word не удалось (AppleScript −609) → в живой проверке файл открывает оператор.
- **Начальный бандл фронта 1,36 МБ при пределе ошибки 1,5 МБ** (`angular.json`) → новые страницы подключаются лениво (`loadComponent`), `@angular/cdk` импортируется только ими.

## Global Constraints

- Java **17**: `instanceof`-шаблоны и `switch` со стрелками — можно; **pattern matching в `switch` — нельзя**.
- Схема — **только новой миграцией `V23__client_offers.sql`** (все три таблицы + сид двух рынков); V1–V22 не трогать (CLAUDE.md §10). После первого локального запуска V23 не править: если исправление всё же нужно до мержа — `DROP TABLE client_offer_item, client_offer, company_profile; DELETE FROM flyway_schema_history WHERE version = '23';` в nirdb и перезапуск.
- `company_profile` — НЕ рыночная (как `email_template`): строка на рынок, читается по `MarketContext.get()`.
- `client_offer` — рыночная: `@Filter(name = "marketFilter", condition = "market = :market")` + `@EntityListeners(MarketStampingListener.class)` + `MarketScoped`; `@FilterDef` — только на `Tender`. По id (`findById` = `em.find`, фильтр обходит) — **явный гард рынка** → чужой рынок = 404.
- Строки КП — **только через коллекцию** `offer.getItems()` (`cascade = ALL, orphanRemoval = true`), никогда `repository.delete` строки (CLAUDE.md §7).
- Запись — `@PreAuthorize("hasRole('ADMIN')")`; чтение — любой вошедший; картинки логотипа/печати/подписи отдаются **только ADMIN**.
- Формулы цен, НДС и маржи — **только** в `ClientOfferCalculator` (сервер). Фронт показывает посчитанное сервером, своих формул не держит.
- Шаблон документа — **только `th:text`** (никакого `th:utext`); openhtmltopdf грузит **только `data:`**; шрифты — из classpath `/fonts/kp/`.
- Текст документа КП: валюта словами (`тг` / `тенге` / `руб.`), не знаками ₸/₽. Шрифт PDF — Liberation Serif, шрифт Word — Times New Roman. В CSS шаблона документа цвета печати (`#000`) допустимы — это бумага, не интерфейс.
- Интерфейс: только токены темы (ни одного нового хекса), кнопки — kit `.btn` + роль, `@media` — последним блоком `styles`, мобильный ≤900px, тач-таргеты 40px, `cdr.detectChanges()` после async, **без нативного `await`** в экранах (`.subscribe`/`.then`), файлы с `/api` — только `HttpClient` blob (PNG предпросмотра приходят base64 в JSON — это можно), `confirm()` не используем (`ConfirmService`).
- Новые страницы — **`loadComponent`** (ленивые чанки); у каждого компонента свой бюджет стилей (предупреждение 16 кБ, ошибка 24 кБ).
- Тесты бэкенда — `@SpringBootTest @Transactional` на nirdb либо чистый JUnit для классов без Spring; MockMvc — **только** `MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity())`, **не** `@AutoConfigureMockMvc` / `@TestPropertySource` / `@MockitoBean` (каждый особый контекст — ещё 10 соединений к nirdb, §14). `./gradlew …` и `psql` — **с `dangerouslyDisableSandbox: true`**. Гейт — `./gradlew cleanTest test` (0 падений) и `cd frontend && npm run build`.
- Временные файлы — в scratchpad сессии (в командах плана — `/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/68d890e6-6182-4985-9671-4ab089fe89eb/scratchpad`; в другой сессии подставить её scratchpad из системного промпта), не в `/tmp` и не в репозиторий.
- Коммиты — на ветке `feature/client-kp-constructor`, каждый заканчивается строкой `Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>`. `git push origin main` делает оператор. Git и gradlew — из корня (`cd /Users/vlad/IdeaProjects/AIS && …`).
- Волна 2 не делается: колонки `client_offer_item.distributor_id / tender_lot_id / price_request_item_id / med_equipment_id` в V23 есть, но `PUT` волны 1 их не принимает и не меняет; статусы регистрации `SUGGESTED` / `CONFIRMED` клиент создать не может.

## Карта файлов

Пути Java — относительно `src/main/java/com/vladoose/nir/` (тесты — `src/test/java/com/vladoose/nir/clientoffer/`).

| Файл | Задача | Ответственность |
|---|---|---|
| `build.gradle`, `resources/fonts/kp/LiberationSerif-*.ttf`, `resources/fonts/LICENSE-LiberationFonts.txt`, `service/document/KpFonts.java`, `KpPdfRenderer.java`, `KpPreviewRenderer.java` | 1 | HTML → PDF без внешних ресурсов, PDF → PNG |
| `db/migration/V23__client_offers.sql`, `entity/CompanyProfile.java`, `ClientOffer.java`, `ClientOfferItem.java`, `OfferColumn.java`, `OfferTerm.java`, `ClientOfferStatus.java`, `ClientOfferItemKind.java`, `OfferRounding.java`, `TermsStyle.java`, `OfferSignoff.java`, `OfferRegistrationStatus.java`, `repository/CompanyProfileRepository.java`, `ClientOfferRepository.java` | 2 | схема, сид реквизитов, сущности, журнал |
| `service/offer/OfferColumnKey.java`, `ColumnAlign.java`, `OfferSettingsValidator.java`, `service/ImageProcessor.java`, `CompanyImageKind.java`, `CompanyProfileService.java`, `dto/request/CompanyProfileRequest.java`, `dto/response/CompanyProfileResponse.java`, `controller/CompanyProfileController.java` | 3 | реквизиты, картинки, следующий номер |
| `service/offer/ClientOfferCalculator.java`, `ItemCalc.java`, `VatLine.java`, `OfferTotals.java`, `OfferCalculation.java`, `util/AmountInWords.java`, `util/DocFormat.java` | 4 | расчёт, пропись, формат чисел |
| `service/CompanyLines.java`, `service/document/KpDocument.java`, `KpDocumentBuilder.java` | 5 | модель документа |
| `resources/templates/kp/offer.html`, `service/document/KpHtmlRenderer.java` | 6 | вёрстка PDF |
| `service/document/KpDocxRenderer.java` | 7 | Word |
| `dto/request/ClientOfferUpdateRequest.java`, `ClientOfferItemDto.java`, `ClientOfferStatusRequest.java`, `dto/response/ClientOfferResponse.java`, `ClientOfferListItemResponse.java`, `PreviewPagesResponse.java`, `service/ClientOfferService.java`, `ClientOfferMapper.java`, `controller/ClientOfferController.java`, `controller/CompanyProfileController.java` (образец), `exception/GlobalExceptionHandler.java` | 8 | API КП |
| `service/CompanyInfoProvider.java`, `PdfCompanyHeader.java`, `ExcelCompanyHeader.java`, `JasperReportService.java`, `ProfitabilityExcelService.java`, `CompanyInfo.java` (удаляется), `resources/fonts/` | 9 | «заодно»: реквизиты рынка в старых PDF/Excel, шрифт, валюта |
| `frontend/package.json`, `src/styles.scss`, `src/app/app.config.ts`, `app.routes.ts`, `layout/layout.component.ts`, `services/api.service.ts`, `services/company-profile.service.ts`, `shared/client-offer.ts`, `shared/offer-terms-editor.component.ts`, `shared/offer-columns-editor.component.ts`, `pages/company-profile/company-profile.component.ts` | 10 | основа фронта, «Реквизиты и печать» |
| `pages/client-offers/client-offers.component.ts` | 11 | журнал |
| `pages/client-offers/client-offer-items.component.ts` | 12 | строки КП |
| `pages/client-offers/client-offer-editor.component.ts`, `client-offer-preview.component.ts` | 13 | редактор, автосохранение, предпросмотр, выгрузка |
| `components/equipment-detail-modal/…`, `pages/registry-reconciliation/…`, `pages/private-requests/private-request-card.component.ts` | 14 | подписи НДС из настроек рынка |
| `CLAUDE.md`, `docs/PROGRESS.md` | 15 | документация, полный гейт, живая проверка |

---

### Task 1: HTML → PDF без внешних ресурсов и PNG-страницы

**Files:**
- Modify: `build.gradle` (зависимости)
- Create: `src/main/resources/fonts/kp/LiberationSerif-Regular.ttf`, `-Bold.ttf`, `-Italic.ttf`, `-BoldItalic.ttf`, `src/main/resources/fonts/LICENSE-LiberationFonts.txt`
- Create: `src/main/java/com/vladoose/nir/service/document/KpFonts.java`, `KpPdfRenderer.java`, `KpPreviewRenderer.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/KpTestSupport.java`, `KpPdfRendererTest.java`, `KpPreviewRendererTest.java`

**Interfaces:**
- Produces: `KpFonts` (`public static final String FAMILY = "Liberation Serif"`, `void register(PdfRendererBuilder)`), `KpPdfRenderer` (`public byte[] render(String html)`), `KpPreviewRenderer` (`public static final float DPI = 110f`, `public List<byte[]> pages(byte[] pdf, int maxPages)`); тестовый `KpTestSupport` (`text` и `pageTexts` — с нормализацией пробелов, `imageCount`, `firstPageSize`, `circlePng`, `signaturePng`, `circleOnWhiteJpeg`, `png`, `TrapServer`) — им пользуются задачи 3, 6, 7, 8, 9.

- [ ] **Step 1: Зависимости и шрифты**

В `build.gradle` строку `implementation 'org.apache.pdfbox:pdfbox:3.0.5'` заменить двумя:

```groovy
    implementation 'org.apache.pdfbox:pdfbox:3.0.7'
    // КП клиенту: HTML → PDF (спека client-kp-constructor §6.4); ветка 1.1.x собрана на PDFBox 3.0.7
    implementation 'io.github.openhtmltopdf:openhtmltopdf-pdfbox:1.1.87'
```

Шрифты (архив релиза Liberation 2.1.5, сумма сверена 2026-10-02):

```bash
SP=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/68d890e6-6182-4985-9671-4ab089fe89eb/scratchpad
curl -sL -o "$SP/liberation.tgz" https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz
echo "7191c669bf38899f73a2094ed00f7b800553364f90e2637010a69c0e268f25d0  $SP/liberation.tgz" | shasum -a 256 -c
tar xzf "$SP/liberation.tgz" -C "$SP"
cd /Users/vlad/IdeaProjects/AIS && mkdir -p src/main/resources/fonts/kp
for f in Regular Bold Italic BoldItalic; do cp "$SP/liberation-fonts-ttf-2.1.5/LiberationSerif-$f.ttf" src/main/resources/fonts/kp/; done
cp "$SP/liberation-fonts-ttf-2.1.5/LICENSE" src/main/resources/fonts/LICENSE-LiberationFonts.txt
ls -la src/main/resources/fonts/kp
```

Expected: `shasum` печатает `OK`; в `fonts/kp` четыре `.ttf` по ~370–395 КБ.

- [ ] **Step 2: Тестовые помощники**

`src/test/java/com/vladoose/nir/clientoffer/KpTestSupport.java`:

```java
package com.vladoose.nir.clientoffer;

import com.sun.net.httpserver.HttpServer;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Проверки документов КП: текст и картинки PDF, «ловушка» внешних запросов, тестовые картинки. */
final class KpTestSupport {

    private KpTestSupport() {}

    /**
     * Весь текст PDF одной строкой: любые пробельные — один пробел. В ячейках длинный текст переносится, и PDF отдаёт
     * его с переводом строки («Цена за ед.,\nтг»); неразрывные пробелы между разрядами — обычные («126 000,00»).
     */
    static String text(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return normalize(new PDFTextStripper().getText(d));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<String> pageTexts(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            List<String> pages = new ArrayList<>();
            PDFTextStripper stripper = new PDFTextStripper();
            for (int i = 1; i <= d.getNumberOfPages(); i++) {
                stripper.setStartPage(i);
                stripper.setEndPage(i);
                pages.add(normalize(stripper.getText(d)));
            }
            return pages;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String normalize(String text) {
        return text.replace(' ', ' ').replaceAll("\\s+", " ");
    }

    /** Сколько растровых картинок нарисовано на страницах (с вложенными формами). */
    static int imageCount(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            int n = 0;
            for (PDPage page : d.getPages()) n += images(page.getResources());
            return n;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int images(PDResources resources) throws IOException {
        if (resources == null) return 0;
        int n = 0;
        for (COSName name : resources.getXObjectNames()) {
            PDXObject x = resources.getXObject(name);
            if (x instanceof PDImageXObject) n++;
            else if (x instanceof PDFormXObject form) n += images(form.getResources());
        }
        return n;
    }

    static PDRectangle firstPageSize(byte[] pdf) {
        try (PDDocument d = Loader.loadPDF(pdf)) {
            return d.getPage(0).getMediaBox();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** PNG 200×200: синий круг на прозрачном фоне — «печать». */
    static byte[] circlePng() {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(30, 60, 200));
        g.setStroke(new BasicStroke(8));
        g.drawOval(8, 8, 184, 184);
        g.dispose();
        return png(img);
    }

    /**
     * PNG 300×80: росчерк — «подпись». Картинка ДРУГАЯ, чем у печати: и openhtmltopdf (кеш по URI), и POI
     * (повторное использование одинаковых данных) склеивают одинаковые картинки в одну — счёт «печать + подпись»
     * на одинаковых байтах дал бы 1 вместо 2.
     */
    static byte[] signaturePng() {
        BufferedImage img = new BufferedImage(300, 80, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 30, 120));
        g.setStroke(new BasicStroke(4));
        g.drawLine(10, 60, 120, 15);
        g.drawLine(120, 15, 180, 65);
        g.drawLine(180, 65, 290, 20);
        g.dispose();
        return png(img);
    }

    /** JPEG: закрашенный синий круг на белом листе — как скан печати. */
    static byte[] circleOnWhiteJpeg(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, size, size);
        g.setColor(new Color(30, 60, 200));
        g.fillOval(size / 4, size / 4, size / 2, size / 2);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "jpeg", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    static byte[] png(BufferedImage img) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** Локальный HTTP-сервер, считающий обращения: документ не должен ходить никуда. */
    static final class TrapServer implements AutoCloseable {
        private final HttpServer server;
        private final AtomicInteger hits = new AtomicInteger();

        TrapServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                hits.incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
                exchange.close();
            });
            server.start();
        }

        String url(String path) {
            return "http://127.0.0.1:" + server.getAddress().getPort() + path;
        }

        int hits() {
            return hits.get();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
```

- [ ] **Step 3: Падающие тесты рендереров**

`src/test/java/com/vladoose/nir/clientoffer/KpPdfRendererTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.document.KpFonts;
import com.vladoose.nir.service.document.KpPdfRenderer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Рендерер PDF: шрифт с казахскими буквами, повтор шапки таблицы, и главное — никуда не ходит (спека §6.4). */
class KpPdfRendererTest {

    private final KpPdfRenderer renderer = new KpPdfRenderer(new KpFonts());

    @Test
    void rendersCyrillicKazakhAndNumeroSign() {
        byte[] pdf = renderer.render(html("<p>Жауапкершілігі шектеулі серіктестігі — ӘәҒғҚқҢңӨөҰұҮүҺһІі № «West-Med»</p>"));
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(KpTestSupport.text(pdf)).contains("ӘәҒғҚқҢңӨөҰұҮүҺһІі", "№", "«West-Med»", "Жауапкершілігі");
    }

    /** Мутация «снять useExternalResourceAccessControl» или «разрешить всё» роняет этот тест. */
    @Test
    void neverFetchesExternalResources() throws Exception {
        try (KpTestSupport.TrapServer trap = new KpTestSupport.TrapServer()) {
            byte[] pdf = renderer.render(html(
                    "<p>картинка: <img src=\"" + trap.url("/x.png") + "\"/></p>"
                    + "<link rel=\"stylesheet\" href=\"" + trap.url("/x.css") + "\"/>"
                    + "<p style=\"background-image: url('" + trap.url("/bg.png") + "')\">фон</p>"));
            assertThat(KpTestSupport.text(pdf)).contains("картинка", "фон");
            assertThat(trap.hits()).isZero();
        }
    }

    @Test
    void embedsDataUriImage() {
        String png = "data:image/png;base64," + Base64.getEncoder().encodeToString(KpTestSupport.circlePng());
        byte[] pdf = renderer.render(html("<img src=\"" + png + "\" style=\"width:30mm\"/>"));
        assertThat(KpTestSupport.imageCount(pdf)).isEqualTo(1);
    }

    @Test
    void repeatsTableHeaderOnEveryPage() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 80; i++) rows.append("<tr><td>").append(i).append("</td><td>Позиция ").append(i).append("</td></tr>");
        byte[] pdf = renderer.render(html("<table style=\"width:100%; -fs-table-paginate: paginate\">"
                + "<thead><tr><th>№</th><th>Наименование</th></tr></thead><tbody>" + rows + "</tbody></table>"));
        List<String> pages = KpTestSupport.pageTexts(pdf);
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages).allSatisfy(p -> assertThat(p).contains("Наименование"));
    }

    static String html(String body) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><style>"
                + "body { font-family: 'Liberation Serif'; font-size: 11pt; }"
                + "thead { display: table-header-group; } td, th { border: 0.5pt solid #000; }"
                + "</style></head><body>" + body + "</body></html>";
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/KpPreviewRendererTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.service.document.KpFonts;
import com.vladoose.nir.service.document.KpPdfRenderer;
import com.vladoose.nir.service.document.KpPreviewRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KpPreviewRendererTest {

    private final KpPreviewRenderer preview = new KpPreviewRenderer();

    private byte[] twoPagePdf() {
        StringBuilder rows = new StringBuilder();
        for (int i = 1; i <= 80; i++) rows.append("<tr><td>").append(i).append("</td></tr>");
        return new KpPdfRenderer(new KpFonts()).render(KpPdfRendererTest.html("<table>" + rows + "</table>"));
    }

    @Test
    void rendersEveryPageToPng() throws Exception {
        List<byte[]> pages = preview.pages(twoPagePdf(), 10);
        assertThat(pages).hasSizeGreaterThan(1);
        BufferedImage first = ImageIO.read(new ByteArrayInputStream(pages.get(0)));
        // A4 = 210 мм при 110 dpi ≈ 909 px
        assertThat(first.getWidth()).isBetween(890, 930);
    }

    @Test
    void limitsPageCount() {
        assertThat(preview.pages(twoPagePdf(), 1)).hasSize(1);
    }
}
```

- [ ] **Step 4: Убедиться, что тесты падают**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.KpPdfRendererTest' --tests 'com.vladoose.nir.clientoffer.KpPreviewRendererTest'`
Expected: FAIL — компиляция тестов: `cannot find symbol … KpPdfRenderer`.

- [ ] **Step 5: Реализация**

`src/main/java/com/vladoose/nir/service/document/KpFonts.java`:

```java
package com.vladoose.nir.service.document;

import com.openhtmltopdf.outputdevice.helper.BaseRendererBuilder;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * Шрифты PDF КП: Liberation Serif (SIL OFL, лицензия — resources/fonts/LICENSE-LiberationFonts.txt).
 * Метрика совпадает с Times New Roman, поэтому строки в PDF и в Word (где Times New Roman) переносятся одинаково.
 * Знаков ₸ и ₽ в шрифте нет — валюта в документах пишется словами («тг», «тенге», «руб.»).
 */
@Component
public class KpFonts {

    public static final String FAMILY = "Liberation Serif";

    private final byte[] regular = load("LiberationSerif-Regular.ttf");
    private final byte[] bold = load("LiberationSerif-Bold.ttf");
    private final byte[] italic = load("LiberationSerif-Italic.ttf");
    private final byte[] boldItalic = load("LiberationSerif-BoldItalic.ttf");

    public void register(PdfRendererBuilder builder) {
        builder.useFont(() -> new ByteArrayInputStream(regular), FAMILY, 400, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(bold), FAMILY, 700, BaseRendererBuilder.FontStyle.NORMAL, true);
        builder.useFont(() -> new ByteArrayInputStream(italic), FAMILY, 400, BaseRendererBuilder.FontStyle.ITALIC, true);
        builder.useFont(() -> new ByteArrayInputStream(boldItalic), FAMILY, 700, BaseRendererBuilder.FontStyle.ITALIC, true);
    }

    private static byte[] load(String file) {
        try (InputStream in = KpFonts.class.getResourceAsStream("/fonts/kp/" + file)) {
            if (in == null) throw new IllegalStateException("Нет шрифта в classpath: /fonts/kp/" + file);
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
```

`src/main/java/com/vladoose/nir/service/document/KpPdfRenderer.java`:

```java
package com.vladoose.nir.service.document;

import com.openhtmltopdf.outputdevice.helper.ExternalResourceControlPriority;
import com.openhtmltopdf.outputdevice.helper.ExternalResourceType;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import com.openhtmltopdf.util.XRLog;
import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.logging.Level;

/**
 * HTML → PDF (openhtmltopdf на PDFBox 3). Наружу рендерер не ходит: разрешены только data:-картинки, которые
 * вставили мы сами (спека client-kp-constructor §6.4) — ни сети, ни file://, даже если в текст КП когда-нибудь
 * просочится разметка. HTML разбирает jsoup (HTML5, сущности вроде &nbsp;) → W3C DOM.
 */
@Component
public class KpPdfRenderer {

    static {
        // по INFO-строке на каждую сборку в лог не нужно — оставляем предупреждения
        for (String logger : XRLog.listRegisteredLoggers()) XRLog.setLevel(logger, Level.WARNING);
    }

    private final KpFonts fonts;

    public KpPdfRenderer(KpFonts fonts) {
        this.fonts = fonts;
    }

    public byte[] render(String html) {
        org.w3c.dom.Document dom = new W3CDom().fromJsoup(Jsoup.parse(html));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PdfRendererBuilder builder = new PdfRendererBuilder();
        builder.useFastMode();
        fonts.register(builder);
        builder.useExternalResourceAccessControl(KpPdfRenderer::allowed, ExternalResourceControlPriority.RUN_BEFORE_RESOLVING_URI);
        builder.withW3cDocument(dom, "/");
        builder.toStream(out);
        try {
            builder.run();
        } catch (IOException e) {
            throw new UncheckedIOException("PDF не собран", e);
        }
        return out.toByteArray();
    }

    static boolean allowed(String uri, ExternalResourceType type) {
        return uri != null && uri.startsWith("data:");
    }
}
```

`src/main/java/com/vladoose/nir/service/document/KpPreviewRenderer.java`:

```java
package com.vladoose.nir.service.document;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;

/** Страницы PDF → PNG для предпросмотра: оператор видит ровно то, что получит клиент (спека §8.3). */
@Component
public class KpPreviewRenderer {

    /** 110 dpi: страница A4 ≈ 909×1286 px, 150–250 КБ — читаемо и на телефоне, и на 1280. */
    public static final float DPI = 110f;

    public List<byte[]> pages(byte[] pdf, int maxPages) {
        try (PDDocument document = Loader.loadPDF(pdf)) {
            PDFRenderer renderer = new PDFRenderer(document);
            int count = Math.min(document.getNumberOfPages(), maxPages);
            List<byte[]> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                BufferedImage image = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                ByteArrayOutputStream png = new ByteArrayOutputStream();
                ImageIO.write(image, "png", png);
                result.add(png.toByteArray());
            }
            return result;
        } catch (IOException e) {
            throw new UncheckedIOException("Предпросмотр не собран", e);
        }
    }
}
```

- [ ] **Step 6: Тесты зелёные; разбор ТЗ на PDFBox 3.0.7 не сломан**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*' --tests '*TechSpec*' --tests '*PdfText*'`
Expected: PASS (все). Если `--tests '*PdfText*'` не нашёл тестов — это нормально (Gradle сообщит «No tests found» только при полном отсутствии совпадений по всем фильтрам).

- [ ] **Step 7: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add build.gradle src/main/resources/fonts src/main/java/com/vladoose/nir/service/document src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): HTML → PDF без внешних ресурсов (openhtmltopdf + Liberation Serif) и PNG-страницы предпросмотра

PDFBox 3.0.5 → 3.0.7 (на нём собран openhtmltopdf 1.1.87). Рендерер грузит только data:-картинки — тест
с локальным сервером-ловушкой; шапка таблицы повторяется на страницах; казахские буквы и «№» в шрифте есть.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 2: Схема V23, сид реквизитов, сущности и журнал

**Files:**
- Create: `src/main/resources/db/migration/V23__client_offers.sql`
- Create: `src/main/java/com/vladoose/nir/entity/CompanyProfile.java`, `ClientOffer.java`, `ClientOfferItem.java`, `OfferColumn.java`, `OfferTerm.java`, `ClientOfferStatus.java`, `ClientOfferItemKind.java`, `OfferRounding.java`, `TermsStyle.java`, `OfferSignoff.java`, `OfferRegistrationStatus.java`
- Create: `src/main/java/com/vladoose/nir/repository/CompanyProfileRepository.java`, `ClientOfferRepository.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferTestData.java`, `ClientOfferPersistenceTest.java`

**Interfaces:**
- Produces: сущности с Lombok-геттерами/сеттерами/билдерами (поля — как в DDL ниже, camelCase); `ClientOffer.getItems()` — `List<ClientOfferItem>` с `@OrderBy("lineNo ASC")`; `ClientOffer.getTableColumns()` — `List<OfferColumn>` (JSON-колонка `table_columns`; в спеке — «columns», переименовано, чтобы не спорить с ключевым словом SQL); `OfferColumn(String key, String label)`, `OfferTerm(String label, String value)` — `@Data @NoArgsConstructor @AllArgsConstructor`; `CompanyProfileRepository.findByMarket(Market)`; `ClientOfferRepository.findJournal(Collection<ClientOfferStatus>, Pageable)`, `searchJournal(Collection<ClientOfferStatus>, String like, Integer number, Pageable)`; тестовый `ClientOfferTestData.newOffer(int number)`, `item(ClientOffer, int lineNo, String name, String purchase, String vat)`.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferTestData.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Готовые КП и строки для тестов (без БД — рынок ставит листенер при сохранении или тест явно). */
final class ClientOfferTestData {

    private ClientOfferTestData() {}

    static ClientOffer newOffer(int number) {
        return ClientOffer.builder()
                .number(number)
                .offerDate(LocalDate.of(2026, 9, 14))
                .status(ClientOfferStatus.DRAFT)
                .title("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ")
                .vatEnabled(true)
                .defaultMarkupPct(new BigDecimal("20"))
                .rounding(OfferRounding.NONE)
                .tableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null), new OfferColumn("NAME", null),
                        new OfferColumn("SUM", null))))
                .detailsInName(true)
                .terms(new ArrayList<>(List.of(new OfferTerm("Порядок оплаты", "100% предоплата"))))
                .termsStyle(TermsStyle.LIST)
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .build();
    }

    /** Позиция: количество 1, закупка «как у продажи». vat == null — без НДС. */
    static ClientOfferItem item(ClientOffer offer, int lineNo, String name, String purchase, String vat) {
        return ClientOfferItem.builder()
                .offer(offer)
                .lineNo(lineNo)
                .name(name)
                .quantity(BigDecimal.ONE)
                .purchasePrice(purchase == null ? null : new BigDecimal(purchase))
                .vatRate(vat == null ? null : new BigDecimal(vat))
                .build();
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferPersistenceTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.CompanyProfileRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V23: сид реквизитов обоих рынков, JSON-колонки, строки через коллекцию, журнал по рынку (§6 CLAUDE.md). */
@SpringBootTest
@Transactional
class ClientOfferPersistenceTest {

    @Autowired CompanyProfileRepository profiles;
    @Autowired ClientOfferRepository offers;
    @Autowired EntityManager em;

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    @Test
    void seedHasWestMedAndRegionMedProfiles() {
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        assertThat(kz.getShortName()).isEqualTo("ТОО «West-Med»");
        assertThat(kz.getHeaderLeft()).isEqualTo("Жауапкершілігі\nшектеулі серіктестігі");
        assertThat(kz.getIdsLine()).isEqualTo("РНН 271 800 059 535 БИН 121 040 000 303");
        assertThat(kz.getBankName()).isEqualTo("АО \"Alatau City Bank\"");
        assertThat(kz.getBik()).isEqualTo("TSESKZKA");
        assertThat(kz.getVatRates()).containsExactly(new BigDecimal("5"), new BigDecimal("16"), null);
        assertThat(kz.getVatDefault()).isEqualByComparingTo("5");
        assertThat(kz.getVatRegistered()).isEqualByComparingTo("5");
        assertThat(kz.getVatNotRegistrable()).isEqualByComparingTo("16");
        assertThat(kz.getDefaultColumns()).extracting(OfferColumn::getKey)
                .containsExactly("NUM", "NAME", "UNIT", "QTY", "PRICE", "VAT_RATE", "SUM", "REGISTRATION");
        assertThat(kz.getDefaultColumns().get(4).getLabel()).isEqualTo("Цена за ед., тг");
        assertThat(kz.getDefaultTerms()).hasSize(5);
        assertThat(kz.getDefaultTermsStyle()).isEqualTo(TermsStyle.LIST);
        assertThat(kz.getStampSizeMm()).isEqualTo(40);
        assertThat(kz.getNextNumber()).isPositive();
        assertThat(kz.getStampPng()).isNull();

        CompanyProfile rf = profiles.findByMarket(Market.RF).orElseThrow();
        assertThat(rf.getShortName()).isEqualTo("ООО «РЕГИОН-МЕД»");
        assertThat(rf.getIdsLine()).isEqualTo("ИНН 6318000846 КПП 631801001 ОГРН 1146318039218");
        assertThat(rf.getVatRates()).containsExactly(null, new BigDecimal("10"), new BigDecimal("22"));
        assertThat(rf.getVatRegistered()).isNull();
    }

    @Test
    void savesOfferWithItemsJsonAndStampsMarket() {
        MarketContext.set(Market.KZ);
        ClientOffer offer = ClientOfferTestData.newOffer(7);
        offer.getItems().add(ClientOfferTestData.item(offer, 1, "Пульсоксиметр", "105000", "5"));
        offer.getItems().add(ClientOfferTestData.item(offer, 2, "Гигрометр", "13515", "16"));
        offers.saveAndFlush(offer);
        em.clear();

        ClientOffer back = offers.findById(offer.getId()).orElseThrow();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
        assertThat(back.getTableColumns()).extracting(OfferColumn::getKey).containsExactly("NUM", "NAME", "SUM");
        assertThat(back.getTerms()).extracting(OfferTerm::getValue).containsExactly("100% предоплата");
        assertThat(back.getItems()).extracting(ClientOfferItem::getName).containsExactly("Пульсоксиметр", "Гигрометр");
        ClientOfferItem first = back.getItems().get(0);
        assertThat(first.getKind()).isEqualTo(ClientOfferItemKind.ITEM);
        assertThat(first.isPurchaseVatSame()).isTrue();
        assertThat(first.getUnit()).isEqualTo("шт");
        assertThat(first.getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.UNCHECKED);
        assertThat(back.getVersion()).isZero();
        assertThat(back.getCreatedAt()).isNotNull();
    }

    @Test
    void journalIsScopedToMarket() {
        MarketContext.set(Market.KZ);
        ClientOffer kz = offers.saveAndFlush(ClientOfferTestData.newOffer(901));
        MarketContext.set(Market.RF);
        ClientOffer rf = offers.saveAndFlush(ClientOfferTestData.newOffer(902));

        List<ClientOffer> rfJournal = offers.findJournal(EnumSet.allOf(ClientOfferStatus.class), PageRequest.of(0, 300));
        assertThat(rfJournal).extracting(ClientOffer::getId).contains(rf.getId()).doesNotContain(kz.getId());
    }

    @Test
    void searchFindsByItemNameAndNumber() {
        MarketContext.set(Market.KZ);
        ClientOffer offer = ClientOfferTestData.newOffer(4431);
        offer.getItems().add(ClientOfferTestData.item(offer, 1, "Аппарат ИВЛ А-ИВЛ-Э-03", "2721000", "5"));
        offers.saveAndFlush(offer);

        var statuses = EnumSet.allOf(ClientOfferStatus.class);
        assertThat(offers.searchJournal(statuses, "%ивл%", null, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).contains(offer.getId());
        assertThat(offers.searchJournal(statuses, "%нет-такого%", 4431, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).contains(offer.getId());
        assertThat(offers.searchJournal(statuses, "%нет-такого%", null, PageRequest.of(0, 300)))
                .extracting(ClientOffer::getId).doesNotContain(offer.getId());
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferPersistenceTest'`
Expected: FAIL — компиляция: `cannot find symbol … ClientOffer`.

- [ ] **Step 2: Миграция V23**

`src/main/resources/db/migration/V23__client_offers.sql`:

```sql
-- V23: КП клиенту (спека docs/superpowers/specs/2026-10-02-client-kp-constructor-design.md §4).
-- company_profile — реквизиты и настройки КП, строка на рынок (как email_template, НЕ рыночная сущность).
-- client_offer / client_offer_item — КП и его строки (рыночная сущность; строки — через коллекцию, §7 CLAUDE.md).
CREATE TABLE company_profile (
    id                   BIGSERIAL PRIMARY KEY,
    market               VARCHAR(2)   NOT NULL UNIQUE,
    short_name           VARCHAR(255) NOT NULL,
    full_name            VARCHAR(500),
    header_left          TEXT,
    header_right         TEXT,
    brand_text           VARCHAR(100),
    ids_line             VARCHAR(500),
    bin_inn              VARCHAR(20),
    address              TEXT,
    accounts             TEXT,
    bank_name            VARCHAR(255),
    bik                  VARCHAR(20),
    phone                VARCHAR(100),
    email                VARCHAR(255),
    director_title       VARCHAR(100),
    director_name        VARCHAR(255),
    signoff_contacts     VARCHAR(500),
    logo_png             BYTEA,
    stamp_png            BYTEA,
    signature_png        BYTEA,
    images_updated_at    TIMESTAMPTZ,
    stamp_size_mm        INTEGER      NOT NULL DEFAULT 40,
    vat_rates            JSONB        NOT NULL,                -- [5, 16, null]; null = «Без НДС»
    vat_default          NUMERIC(5,2),                         -- NULL = без НДС
    vat_registered       NUMERIC(5,2),
    vat_not_registrable  NUMERIC(5,2),
    default_markup_pct   NUMERIC(7,2) NOT NULL DEFAULT 20,
    default_columns      JSONB        NOT NULL,                -- [{"key":"NUM","label":"№"}, …]
    default_terms        JSONB        NOT NULL,                -- [{"label":"…","value":"…"}, …]
    default_terms_style  VARCHAR(10)  NOT NULL DEFAULT 'LIST',
    default_intro        TEXT,
    next_number          INTEGER      NOT NULL DEFAULT 1,
    updated_at           TIMESTAMPTZ
);

CREATE TABLE client_offer (
    id                    BIGSERIAL PRIMARY KEY,
    market                VARCHAR(2)   NOT NULL,
    number                INTEGER      NOT NULL,                -- «исх. №»; уникальность не навязываем (спека §4.2)
    offer_date            DATE         NOT NULL,
    status                VARCHAR(20)  NOT NULL DEFAULT 'DRAFT', -- DRAFT / SENT / ACCEPTED / REJECTED
    facility_id           BIGINT REFERENCES facility (id) ON DELETE SET NULL,
    recipient             TEXT,
    tender_id             BIGINT REFERENCES tender (id) ON DELETE SET NULL,  -- частная заявка-источник (волна 2)
    title                 VARCHAR(200) NOT NULL,
    subject               TEXT,
    intro                 TEXT,
    vat_enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    default_markup_pct    NUMERIC(7,2) NOT NULL DEFAULT 0,
    rounding              VARCHAR(10)  NOT NULL DEFAULT 'NONE',  -- NONE / UNIT / TEN / HUNDRED
    table_columns         JSONB        NOT NULL,
    details_in_name       BOOLEAN      NOT NULL DEFAULT TRUE,
    terms                 JSONB        NOT NULL DEFAULT '[]',
    terms_style           VARCHAR(10)  NOT NULL DEFAULT 'LIST',  -- TABLE / LIST / NONE
    show_amount_in_words  BOOLEAN      NOT NULL DEFAULT TRUE,
    show_vat_breakdown    BOOLEAN      NOT NULL DEFAULT TRUE,
    landscape             BOOLEAN      NOT NULL DEFAULT FALSE,
    signoff               VARCHAR(10)  NOT NULL DEFAULT 'DIRECTOR', -- COMPANY / DIRECTOR
    signoff_contacts      BOOLEAN      NOT NULL DEFAULT FALSE,
    with_stamp            BOOLEAN      NOT NULL DEFAULT FALSE,
    total_amount          NUMERIC(15,2),                         -- пересчитывается при сохранении — для журнала без N+1
    item_count            INTEGER      NOT NULL DEFAULT 0,
    internal_note         TEXT,
    version               INTEGER      NOT NULL DEFAULT 0,       -- @Version: защита от затирания из второй вкладки
    created_by            VARCHAR(100),
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    sent_at               TIMESTAMPTZ
);
CREATE INDEX idx_client_offer_market_number ON client_offer (market, number);
CREATE INDEX idx_client_offer_tender ON client_offer (tender_id);
CREATE INDEX idx_client_offer_facility ON client_offer (facility_id);

CREATE TABLE client_offer_item (
    id                     BIGSERIAL PRIMARY KEY,
    offer_id               BIGINT        NOT NULL REFERENCES client_offer (id) ON DELETE CASCADE,
    line_no                INTEGER       NOT NULL,
    kind                   VARCHAR(10)   NOT NULL DEFAULT 'ITEM',  -- ITEM / SECTION / INCLUDED
    name                   TEXT          NOT NULL,
    model                  VARCHAR(255),
    manufacturer           VARCHAR(500),
    country                VARCHAR(200),
    unit                   VARCHAR(30)   NOT NULL DEFAULT 'шт',
    quantity               NUMERIC(12,3),
    purchase_price         NUMERIC(15,2),
    purchase_vat_same      BOOLEAN       NOT NULL DEFAULT TRUE,
    purchase_vat_rate      NUMERIC(5,2),                            -- при purchase_vat_same = false; NULL = без НДС
    supplier_name          VARCHAR(255),
    distributor_id         BIGINT REFERENCES distributor (id) ON DELETE SET NULL,
    tender_lot_id          BIGINT REFERENCES tender_lot (id) ON DELETE SET NULL,
    price_request_item_id  BIGINT REFERENCES price_request_item (id) ON DELETE SET NULL,
    med_equipment_id       BIGINT REFERENCES med_equipment (id) ON DELETE SET NULL,
    markup_pct             NUMERIC(7,2),                            -- NULL = общая наценка КП
    price_override         NUMERIC(15,2),                           -- цена клиенту, вбитая руками
    vat_rate               NUMERIC(5,2),                            -- NULL = без НДС
    registration_status    VARCHAR(20)   NOT NULL DEFAULT 'UNCHECKED',
    registration_text      TEXT,
    reg_number             VARCHAR(100),
    suggestion_score       NUMERIC(5,3),
    note                   TEXT
);
CREATE INDEX idx_client_offer_item_offer ON client_offer_item (offer_id, line_no);

-- Стартовые реквизиты. West-Med — из КП отца (24.09.2026, банк переименован в Alatau City Bank — подтверждено
-- оператором 2026-10-02), Регион-Мед — из прежних констант CompanyInfo. Казахский текст — с правильной «і» (U+0456):
-- в бланке отца стоит латинская «i», обход старых шрифтов. next_number = 1 — свой номер отец ставит перед первым КП.
INSERT INTO company_profile (market, short_name, full_name, header_left, header_right, brand_text, ids_line, bin_inn,
                             address, accounts, bank_name, bik, phone, email, director_title, director_name,
                             signoff_contacts, vat_rates, vat_default, vat_registered, vat_not_registrable,
                             default_markup_pct, default_columns, default_terms, default_terms_style, default_intro,
                             next_number, updated_at)
VALUES ('KZ', 'ТОО «West-Med»', 'Товарищество с ограниченной ответственностью «West-Med»',
        E'Жауапкершілігі\nшектеулі серіктестігі', E'Товарищество\nс ограниченной ответственностью',
        '"West-Med"', 'РНН 271 800 059 535 БИН 121 040 000 303', '121040000303',
        E'Республика Казахстан, 090000, Западно-Казахстанская область,\nгород Уральск, ул.Мухита 121-21',
        'KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)',
        'АО "Alatau City Bank"', 'TSESKZKA', '87770752770', 'west-med@mail.ru',
        'Директор', 'Ширяев Илья Викторович', 'моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru',
        '[5, 16, null]', 5, 5, 16, 20,
        '[{"key":"NUM","label":"№"},{"key":"NAME","label":"Наименование"},{"key":"UNIT","label":"Ед. изм."},{"key":"QTY","label":"Кол-во"},{"key":"PRICE","label":"Цена за ед., тг"},{"key":"VAT_RATE","label":"НДС"},{"key":"SUM","label":"Общая сумма, тг"},{"key":"REGISTRATION","label":"Регистрация в РК"}]',
        '[{"label":"","value":"Цены действительны в течение 10 дней"},{"label":"","value":"Транспортные услуги включены в общую стоимость товара"},{"label":"Порядок оплаты","value":"100% предоплата"},{"label":"Форма оплаты","value":"безналичная"},{"label":"Срок поставки всего товара","value":"30 рабочих дней после поступления предоплаты"}]',
        'LIST', 'ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:', 1, now()),
       ('RF', 'ООО «РЕГИОН-МЕД»', 'Общество с ограниченной ответственностью «РЕГИОН-МЕД»', NULL, NULL,
        'РЕГИОН-МЕД', 'ИНН 6318000846 КПП 631801001 ОГРН 1146318039218', '6318000846',
        E'Российская Федерация, 443066, Самарская область,\nг. Самара, ул. Дыбенко, д. 120, кв. 148',
        'р/с 40702810623000120018, к/с 30101810300000000847',
        'Поволжский филиал АО «РАЙФФАЙЗЕНБАНК» (г. Нижний Новгород)', '042202847', '+7 (846) 201-55-15',
        'region-med@mail.ru', 'Директор', 'Ширяев Илья Викторович',
        'моб.: +7 927 755-50-70, e-mail: region-med@mail.ru',
        '[null, 10, 22]', 22, NULL, 22, 20,
        '[{"key":"NUM"},{"key":"NAME"},{"key":"QTY"},{"key":"UNIT"},{"key":"PRICE"},{"key":"VAT_RATE"},{"key":"SUM"}]',
        '[{"label":"","value":"Цены действительны в течение 10 дней"},{"label":"Порядок оплаты","value":"100% предоплата"},{"label":"Форма оплаты","value":"безналичная"}]',
        'LIST', 'ООО «РЕГИОН-МЕД» предлагает поставку медицинской продукции по следующим ценам:', 1, now());
```

- [ ] **Step 3: Сущности, перечисления, репозитории**

`src/main/java/com/vladoose/nir/entity/OfferColumn.java`:

```java
package com.vladoose.nir.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Колонка таблицы КП: ключ из service.offer.OfferColumnKey + своя подпись (null — подпись по умолчанию). JSON. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferColumn {
    private String key;
    private String label;
}
```

`src/main/java/com/vladoose/nir/entity/OfferTerm.java`:

```java
package com.vladoose.nir.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Условие КП «название — значение»; пустое название — в списке печатается только значение. JSON. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class OfferTerm {
    private String label;
    private String value;
}
```

Перечисления (каждое — в своём файле пакета `com.vladoose.nir.entity`):

```java
package com.vladoose.nir.entity;

public enum ClientOfferStatus { DRAFT, SENT, ACCEPTED, REJECTED }
```

```java
package com.vladoose.nir.entity;

/** ITEM — позиция; SECTION — заголовок раздела на всю ширину; INCLUDED — «включено в стоимость» (гарантия, обучение). */
public enum ClientOfferItemKind { ITEM, SECTION, INCLUDED }
```

```java
package com.vladoose.nir.entity;

/** Округление цены клиенту за единицу: до 0,01 / до целых / до 10 / до 100. */
public enum OfferRounding { NONE, UNIT, TEN, HUNDRED }
```

```java
package com.vladoose.nir.entity;

/** Условия: таблицей над позициями, списком под итогом или не печатать. */
public enum TermsStyle { TABLE, LIST, NONE }
```

```java
package com.vladoose.nir.entity;

/** Подпись: «С уважением, ТОО «…»» или «С уважением, Директор ТОО «…» ____ Фамилия И. О.». */
public enum OfferSignoff { COMPANY, DIRECTOR }
```

```java
package com.vladoose.nir.entity;

/**
 * Регистрация строки КП. Печатается только CONFIRMED / NOT_REQUIRED / MANUAL; SUGGESTED — подсказка реестра,
 * не печатается. CONFIRMED и SUGGESTED ставит только сервер (волна 2); имя не RegistrationStatus — тот занят каталогом.
 */
public enum OfferRegistrationStatus { UNCHECKED, SUGGESTED, CONFIRMED, NOT_REQUIRED, MANUAL }
```

`src/main/java/com/vladoose/nir/entity/CompanyProfile.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Реквизиты компании рынка и умолчания КП (спека client-kp-constructor §4.1). Строка на рынок, как EmailTemplate:
 * НЕ рыночная сущность (без @Filter), читается по MarketContext.get(). Картинки — уже обработанный PNG.
 */
@Entity
@Table(name = "company_profile")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CompanyProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, unique = true, length = 2)
    private Market market;

    @Column(name = "short_name", nullable = false)
    private String shortName;

    @Column(name = "full_name", length = 500)
    private String fullName;

    @Column(name = "header_left", columnDefinition = "TEXT")
    private String headerLeft;

    @Column(name = "header_right", columnDefinition = "TEXT")
    private String headerRight;

    @Column(name = "brand_text", length = 100)
    private String brandText;

    @Column(name = "ids_line", length = 500)
    private String idsLine;

    @Column(name = "bin_inn", length = 20)
    private String binInn;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(columnDefinition = "TEXT")
    private String accounts;

    @Column(name = "bank_name")
    private String bankName;

    @Column(length = 20)
    private String bik;

    @Column(length = 100)
    private String phone;

    private String email;

    @Column(name = "director_title", length = 100)
    private String directorTitle;

    @Column(name = "director_name")
    private String directorName;

    @Column(name = "signoff_contacts", length = 500)
    private String signoffContacts;

    @Column(name = "logo_png")
    private byte[] logoPng;

    @Column(name = "stamp_png")
    private byte[] stampPng;

    @Column(name = "signature_png")
    private byte[] signaturePng;

    @Column(name = "images_updated_at")
    private OffsetDateTime imagesUpdatedAt;

    @Column(name = "stamp_size_mm", nullable = false)
    @Builder.Default
    private int stampSizeMm = 40;

    /** Ставки рынка; null-элемент = «Без НДС». */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "vat_rates", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<BigDecimal> vatRates = new ArrayList<>();

    /** Ставка новой строки; null = без НДС. */
    @Column(name = "vat_default", precision = 5, scale = 2)
    private BigDecimal vatDefault;

    @Column(name = "vat_registered", precision = 5, scale = 2)
    private BigDecimal vatRegistered;

    @Column(name = "vat_not_registrable", precision = 5, scale = 2)
    private BigDecimal vatNotRegistrable;

    @Column(name = "default_markup_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal defaultMarkupPct;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_columns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferColumn> defaultColumns = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "default_terms", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferTerm> defaultTerms = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "default_terms_style", nullable = false, length = 10)
    @Builder.Default
    private TermsStyle defaultTermsStyle = TermsStyle.LIST;

    @Column(name = "default_intro", columnDefinition = "TEXT")
    private String defaultIntro;

    @Column(name = "next_number", nullable = false)
    @Builder.Default
    private int nextNumber = 1;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
```

`src/main/java/com/vladoose/nir/entity/ClientOffer.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * КП клиенту (спека client-kp-constructor §4.2). Рыночная сущность (§6 CLAUDE.md): @Filter + листенер штампа;
 * @FilterDef объявлен ОДИН раз — на Tender. Строки — дети с cascade=ALL/orphanRemoval: менять ТОЛЬКО через items.
 * Числа расчёта не хранятся (считает ClientOfferCalculator); totalAmount/itemCount — копия для журнала.
 */
@Entity
@Table(name = "client_offer")
@Filter(name = "marketFilter", condition = "market = :market")
@EntityListeners(MarketStampingListener.class)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOffer implements MarketScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 2)
    private Market market;

    @Column(nullable = false)
    private Integer number;

    @Column(name = "offer_date", nullable = false)
    private LocalDate offerDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private ClientOfferStatus status = ClientOfferStatus.DRAFT;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "facility_id")
    private Facility facility;

    @Column(columnDefinition = "TEXT")
    private String recipient;

    /** Частная заявка-источник (волна 2) — id без связи, чтобы не тянуть тендер с лотами в каждый КП. */
    @Column(name = "tender_id")
    private Long tenderId;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(columnDefinition = "TEXT")
    private String intro;

    @Column(name = "vat_enabled", nullable = false)
    private boolean vatEnabled;

    @Column(name = "default_markup_pct", nullable = false, precision = 7, scale = 2)
    private BigDecimal defaultMarkupPct;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private OfferRounding rounding = OfferRounding.NONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "table_columns", nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferColumn> tableColumns = new ArrayList<>();

    @Column(name = "details_in_name", nullable = false)
    private boolean detailsInName;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    @Builder.Default
    private List<OfferTerm> terms = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Column(name = "terms_style", nullable = false, length = 10)
    @Builder.Default
    private TermsStyle termsStyle = TermsStyle.LIST;

    @Column(name = "show_amount_in_words", nullable = false)
    private boolean showAmountInWords;

    @Column(name = "show_vat_breakdown", nullable = false)
    private boolean showVatBreakdown;

    @Column(nullable = false)
    private boolean landscape;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private OfferSignoff signoff = OfferSignoff.DIRECTOR;

    @Column(name = "signoff_contacts", nullable = false)
    private boolean signoffContacts;

    @Column(name = "with_stamp", nullable = false)
    private boolean withStamp;

    @Column(name = "total_amount", precision = 15, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "internal_note", columnDefinition = "TEXT")
    private String internalNote;

    @Version
    @Column(nullable = false)
    private int version;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @Column(name = "sent_at")
    private OffsetDateTime sentAt;

    @OneToMany(mappedBy = "offer", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("lineNo ASC")
    @Builder.Default
    private List<ClientOfferItem> items = new ArrayList<>();

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
```

`src/main/java/com/vladoose/nir/entity/ClientOfferItem.java`:

```java
package com.vladoose.nir.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

/** Строка КП (спека §4.3). Числа расчёта не хранятся — только ввод оператора. Связи волны 2 — голые id. */
@Entity
@Table(name = "client_offer_item")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ClientOfferItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "offer_id", nullable = false)
    private ClientOffer offer;

    /** Порядковый номер строки (не «position»: это функция HQL, разбор @OrderBy по ней ненадёжен). */
    @Column(name = "line_no", nullable = false)
    private int lineNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    @Builder.Default
    private ClientOfferItemKind kind = ClientOfferItemKind.ITEM;

    @Column(nullable = false, columnDefinition = "TEXT")
    @Builder.Default
    private String name = "";

    @Column(length = 255)
    private String model;

    @Column(length = 500)
    private String manufacturer;

    @Column(length = 200)
    private String country;

    @Column(nullable = false, length = 30)
    @Builder.Default
    private String unit = "шт";

    @Column(precision = 12, scale = 3)
    private BigDecimal quantity;

    @Column(name = "purchase_price", precision = 15, scale = 2)
    private BigDecimal purchasePrice;

    @Column(name = "purchase_vat_same", nullable = false)
    @Builder.Default
    private boolean purchaseVatSame = true;

    @Column(name = "purchase_vat_rate", precision = 5, scale = 2)
    private BigDecimal purchaseVatRate;

    @Column(name = "supplier_name")
    private String supplierName;

    @Column(name = "distributor_id")
    private Long distributorId;

    @Column(name = "tender_lot_id")
    private Long tenderLotId;

    @Column(name = "price_request_item_id")
    private Long priceRequestItemId;

    @Column(name = "med_equipment_id")
    private Long medEquipmentId;

    @Column(name = "markup_pct", precision = 7, scale = 2)
    private BigDecimal markupPct;

    @Column(name = "price_override", precision = 15, scale = 2)
    private BigDecimal priceOverride;

    @Column(name = "vat_rate", precision = 5, scale = 2)
    private BigDecimal vatRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "registration_status", nullable = false, length = 20)
    @Builder.Default
    private OfferRegistrationStatus registrationStatus = OfferRegistrationStatus.UNCHECKED;

    @Column(name = "registration_text", columnDefinition = "TEXT")
    private String registrationText;

    @Column(name = "reg_number", length = 100)
    private String regNumber;

    @Column(name = "suggestion_score", precision = 5, scale = 3)
    private BigDecimal suggestionScore;

    @Column(columnDefinition = "TEXT")
    private String note;
}
```

`src/main/java/com/vladoose/nir/repository/CompanyProfileRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface CompanyProfileRepository extends JpaRepository<CompanyProfile, Long> {
    Optional<CompanyProfile> findByMarket(Market market);
}
```

`src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java`:

```java
package com.vladoose.nir.repository;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/** Выборки — HQL, поэтому рыночный фильтр аспекта их режет (§6 CLAUDE.md). findById фильтр обходит — гард в сервисе. */
public interface ClientOfferRepository extends JpaRepository<ClientOffer, Long> {

    @Query("select o from ClientOffer o left join fetch o.facility "
            + "where o.status in :statuses order by o.offerDate desc, o.id desc")
    List<ClientOffer> findJournal(@Param("statuses") Collection<ClientOfferStatus> statuses, Pageable pageable);

    /** like — уже в нижнем регистре, с экранированными % и _ и обёрнутый в %; number — точный «исх. №» или null. */
    @Query("""
            select distinct o from ClientOffer o left join fetch o.facility f left join o.items i
            where o.status in :statuses
              and (lower(coalesce(o.recipient, '')) like :like escape '\\'
                or lower(coalesce(f.name, '')) like :like escape '\\'
                or lower(coalesce(o.subject, '')) like :like escape '\\'
                or lower(i.name) like :like escape '\\'
                or o.number = :number)
            order by o.offerDate desc, o.id desc""")
    List<ClientOffer> searchJournal(@Param("statuses") Collection<ClientOfferStatus> statuses,
                                    @Param("like") String like, @Param("number") Integer number, Pageable pageable);
}
```

- [ ] **Step 4: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferPersistenceTest'`
Expected: PASS (4 теста). Flyway при старте контекста применит V23 к nirdb — в логе `Migrating schema "public" to version "23 - client offers"`.

Проверка сида глазами (sandbox off): `PGPASSWORD=admin /Library/PostgreSQL/17/bin/psql -U postgres -d nirdb -c "select market, short_name, vat_rates, next_number from company_profile order by market"`
Expected: две строки — `KZ | ТОО «West-Med» | [5, 16, null] | 1` и `RF | ООО «РЕГИОН-МЕД» | [null, 10, 22] | 1`.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/db/migration/V23__client_offers.sql src/main/java/com/vladoose/nir/entity src/main/java/com/vladoose/nir/repository/CompanyProfileRepository.java src/main/java/com/vladoose/nir/repository/ClientOfferRepository.java src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): V23 — реквизиты рынка (сид West-Med и Регион-Мед), КП клиенту и его строки

company_profile — строка на рынок (как email_template); client_offer — рыночная сущность с @Version и
JSON-колонками (колонки таблицы, условия); строки — через коллекцию. Журнал и поиск — HQL под фильтром рынка.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 3: Реквизиты и печать — API, обработка картинок, следующий номер

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/offer/OfferColumnKey.java`, `ColumnAlign.java`, `OfferSettingsValidator.java`
- Create: `src/main/java/com/vladoose/nir/util/DocFormat.java`
- Create: `src/main/java/com/vladoose/nir/service/ImageProcessor.java`, `CompanyImageKind.java`, `CompanyProfileService.java`
- Create: `src/main/java/com/vladoose/nir/dto/request/CompanyProfileRequest.java`, `dto/response/CompanyProfileResponse.java`, `controller/CompanyProfileController.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ImageProcessorTest.java`, `CompanyProfileApiTest.java`

**Interfaces:**
- Consumes: Task 2 (`CompanyProfile`, `CompanyProfileRepository`, `OfferColumn`, `OfferTerm`, `TermsStyle`), Task 1 (`KpTestSupport`).
- Produces:
  - `OfferColumnKey` — `NUM, NAME, MODEL, MANUFACTURER, COUNTRY, UNIT, QTY, PRICE, PRICE_NET, VAT_RATE, VAT_SUM, SUM_NET, SUM, REGISTRATION, NOTE`; методы `String defaultLabel(boolean vatEnabled)`, `int weight()`, `ColumnAlign align()`, `boolean vatOnly()`, `static OfferColumnKey parse(String key)` (неизвестный → `BadRequestException`); `ColumnAlign { LEFT, CENTER, RIGHT }`.
  - `OfferSettingsValidator` — `static void columns(List<OfferColumn>)`, `static void terms(List<OfferTerm>)`, `static void vatRates(List<BigDecimal>)`, `static boolean containsRate(List<BigDecimal>, BigDecimal)`.
  - `ImageProcessor.process(byte[] input, boolean removeBackground)` → PNG.
  - `CompanyImageKind { LOGO("logo"), STAMP("stamp"), SIGNATURE("signature") }` + `static CompanyImageKind fromPath(String)` (неизвестное → `NotFoundException`).
  - `CompanyProfileService`: `CompanyProfile current()`, `CompanyProfile forMarket(Market)`, `CompanyProfile update(CompanyProfileRequest)`, `CompanyProfile putImage(CompanyImageKind, byte[], boolean)`, `CompanyProfile deleteImage(CompanyImageKind)`, `byte[] image(CompanyImageKind)`, `int allocateNumber(Market)`.
  - REST `/api/company-profile` (GET, PUT, `POST|GET|DELETE /images/{kind}`) — `CompanyProfileResponse` (все поля профиля без байтов + `market`, `currency`, `hasLogo`, `hasStamp`, `hasSignature`, `imagesUpdatedAt`, `updatedAt`).

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientoffer/ImageProcessorTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.ImageProcessor;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Скан печати → чистый PNG (спека §7.2): фон убирается, чернила остаются, размеры проверяются до декодирования. */
class ImageProcessorTest {

    private final ImageProcessor processor = new ImageProcessor();

    private static BufferedImage decode(byte[] png) throws IOException {
        return ImageIO.read(new ByteArrayInputStream(png));
    }

    private static int alpha(BufferedImage img, int x, int y) {
        return (img.getRGB(x, y) >>> 24) & 0xFF;
    }

    @Test
    void removesWhiteBackgroundKeepsInkAndTrims() throws Exception {
        BufferedImage out = decode(processor.process(KpTestSupport.circleOnWhiteJpeg(400), true));
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        // круг диаметром 200 — поля обрезаны
        assertThat(out.getWidth()).isBetween(190, 212);
        assertThat(out.getHeight()).isBetween(190, 212);
        assertThat(alpha(out, 0, 0)).isZero();                       // угол квадрата вокруг круга — бывшая бумага
        int center = out.getRGB(out.getWidth() / 2, out.getHeight() / 2);
        assertThat((center >>> 24) & 0xFF).isEqualTo(255);            // чернила непрозрачны
        assertThat(center & 0xFF).isGreaterThan((center >> 16) & 0xFF); // и остались синими
    }

    @Test
    void withoutRemovalKeepsWhitePaper() throws Exception {
        BufferedImage out = decode(processor.process(KpTestSupport.circleOnWhiteJpeg(400), false));
        assertThat(out.getWidth()).isEqualTo(400);
        assertThat(alpha(out, 0, 0)).isEqualTo(255);
    }

    @Test
    void keepsExistingTransparency() throws Exception {
        BufferedImage src = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = src.createGraphics();
        g.setColor(new Color(235, 235, 235));   // светло-серый, почти как бумага — но прозрачность уже есть
        g.fillRect(25, 25, 50, 50);
        g.dispose();
        BufferedImage out = decode(processor.process(KpTestSupport.png(src), true));
        assertThat(out.getWidth()).isEqualTo(50);
        assertThat(alpha(out, 25, 25)).isEqualTo(255);
    }

    @Test
    void scalesDownLongSide() throws Exception {
        BufferedImage src = new BufferedImage(3000, 1500, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.DARK_GRAY);
        g.fillRect(0, 0, 3000, 1500);
        g.dispose();
        BufferedImage out = decode(processor.process(KpTestSupport.png(src), false));
        assertThat(out.getWidth()).isEqualTo(1200);
        assertThat(out.getHeight()).isEqualTo(600);
    }

    @Test
    void rejectsHugeDimensionsBeforeDecoding() {
        byte[] wide = KpTestSupport.png(new BufferedImage(6000, 10, BufferedImage.TYPE_INT_RGB));
        assertThatThrownBy(() -> processor.process(wide, false)).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("5000");
    }

    @Test
    void rejectsNonImagesAndGif() throws Exception {
        assertThatThrownBy(() -> processor.process("не картинка".getBytes(StandardCharsets.UTF_8), true))
                .isInstanceOf(BadRequestException.class);
        ByteArrayOutputStream gif = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "gif", gif);
        assertThatThrownBy(() -> processor.process(gif.toByteArray(), true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("PNG или JPEG");
    }

    @Test
    void rejectsEmptyAndTooBig() {
        assertThatThrownBy(() -> processor.process(new byte[0], true)).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> processor.process(new byte[ImageProcessor.MAX_BYTES + 1], true))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("5 МБ");
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/CompanyProfileApiTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.service.CompanyProfileService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;
import com.vladoose.nir.context.MarketContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** «Система → Реквизиты и печать»: права, рынок, проверка ставок, картинки (спека §7, §10, §11). */
@SpringBootTest
@Transactional
class CompanyProfileApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired CompanyProfileService service;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    private String profileJson() throws Exception {
        return mvc.perform(get("/api/company-profile").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorReadsProfileOfHisMarketButCannotChangeIt() throws Exception {
        mvc.perform(get("/api/company-profile").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.market").value("KZ"))
                .andExpect(jsonPath("$.currency").value("KZT"))
                .andExpect(jsonPath("$.shortName").value("ТОО «West-Med»"))
                .andExpect(jsonPath("$.vatRates.length()").value(3))
                .andExpect(jsonPath("$.vatRates[0]").value(5))
                .andExpect(jsonPath("$.vatRates[2]").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.hasStamp").value(false))
                .andExpect(jsonPath("$.stampPng").doesNotExist());
        mvc.perform(get("/api/company-profile").header("X-Market", "RF"))
                .andExpect(jsonPath("$.shortName").value("ООО «РЕГИОН-МЕД»"));
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(profileJson()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUpdatesFieldsAndRatesAreChecked() throws Exception {
        ObjectNode body = (ObjectNode) om.readTree(profileJson());
        body.put("phone", "8 777 000 00 00");
        body.put("nextNumber", 444);
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.phone").value("8 777 000 00 00"))
                .andExpect(jsonPath("$.nextNumber").value(444));

        body.put("vatDefault", 12);   // 12% нет в списке [5, 16, без НДС]
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("12%")));

        body.put("vatDefault", 5);
        body.putArray("defaultColumns").addObject().put("key", "SUM");   // без «Наименования»
        mvc.perform(put("/api/company-profile").header("X-Market", "KZ")
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void adminUploadsStampSeesItAndDeletes() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "stamp.jpg", "image/jpeg",
                KpTestSupport.circleOnWhiteJpeg(400));
        mvc.perform(multipart("/api/company-profile/images/stamp").file(file).param("removeBackground", "true")
                        .header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasStamp").value(true))
                .andExpect(jsonPath("$.hasLogo").value(false));
        byte[] png = mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_PNG))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(png).startsWith((byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G');
        // печать West-Med не видна из Регион-Мед
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "RF")).andExpect(status().isNotFound());

        mvc.perform(delete("/api/company-profile/images/stamp").header("X-Market", "KZ"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hasStamp").value(false));
        mvc.perform(get("/api/company-profile/images/stamp").header("X-Market", "KZ")).andExpect(status().isNotFound());
        mvc.perform(get("/api/company-profile/images/nope").header("X-Market", "KZ")).andExpect(status().isNotFound());
    }

    @Test
    void allocateNumberIsSequentialPerMarket() {
        int a = service.allocateNumber(Market.KZ);
        int b = service.allocateNumber(Market.KZ);
        int rf = service.allocateNumber(Market.RF);
        assertThat(b).isEqualTo(a + 1);
        assertThat(rf).isPositive();
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ImageProcessorTest' --tests 'com.vladoose.nir.clientoffer.CompanyProfileApiTest'`
Expected: FAIL — компиляция: `cannot find symbol … ImageProcessor`.

- [ ] **Step 2: Ключи колонок и проверки настроек**

`src/main/java/com/vladoose/nir/service/offer/ColumnAlign.java`:

```java
package com.vladoose.nir.service.offer;

public enum ColumnAlign { LEFT, CENTER, RIGHT }
```

`src/main/java/com/vladoose/nir/service/offer/OfferColumnKey.java`:

```java
package com.vladoose.nir.service.offer;

import com.vladoose.nir.exception.BadRequestException;

/**
 * Колонки таблицы КП (спека §6.2): подпись по умолчанию (с НДС / без НДС), относительная ширина (у NAME — остаток),
 * выравнивание, «только при НДС» — такие колонки при выключенном НДС не печатаются.
 */
public enum OfferColumnKey {
    NUM("№", null, 5, ColumnAlign.CENTER, false),
    NAME("Наименование", null, 0, ColumnAlign.LEFT, false),
    MODEL("Модель / артикул", null, 13, ColumnAlign.LEFT, false),
    MANUFACTURER("Производитель", null, 15, ColumnAlign.LEFT, false),
    COUNTRY("Страна", null, 9, ColumnAlign.LEFT, false),
    UNIT("Ед. изм.", null, 7, ColumnAlign.CENTER, false),
    QTY("Кол-во", null, 7, ColumnAlign.CENTER, false),
    PRICE("Цена (с НДС)", "Цена", 12, ColumnAlign.RIGHT, false),
    PRICE_NET("Цена без НДС", null, 12, ColumnAlign.RIGHT, true),
    VAT_RATE("Ставка НДС", null, 8, ColumnAlign.CENTER, true),
    VAT_SUM("Сумма НДС", null, 11, ColumnAlign.RIGHT, true),
    SUM_NET("Сумма без НДС", null, 13, ColumnAlign.RIGHT, true),
    SUM("Сумма (с НДС)", "Сумма", 13, ColumnAlign.RIGHT, false),
    REGISTRATION("Регистрация", null, 17, ColumnAlign.LEFT, false),
    NOTE("Примечание", null, 13, ColumnAlign.LEFT, false);

    private final String label;
    private final String labelNoVat;
    private final int weight;
    private final ColumnAlign align;
    private final boolean vatOnly;

    OfferColumnKey(String label, String labelNoVat, int weight, ColumnAlign align, boolean vatOnly) {
        this.label = label;
        this.labelNoVat = labelNoVat;
        this.weight = weight;
        this.align = align;
        this.vatOnly = vatOnly;
    }

    public String defaultLabel(boolean vatEnabled) {
        return !vatEnabled && labelNoVat != null ? labelNoVat : label;
    }

    public int weight() { return weight; }
    public ColumnAlign align() { return align; }
    public boolean vatOnly() { return vatOnly; }

    public static OfferColumnKey parse(String key) {
        if (key != null) {
            for (OfferColumnKey k : values()) if (k.name().equals(key)) return k;
        }
        throw new BadRequestException("Неизвестная колонка: " + key);
    }
}
```

`src/main/java/com/vladoose/nir/service/offer/OfferSettingsValidator.java`:

```java
package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.util.DocFormat;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Проверки колонок, условий и ставок — одни и те же для реквизитов рынка и для самого КП. */
public final class OfferSettingsValidator {

    private OfferSettingsValidator() {}

    public static void columns(List<OfferColumn> columns) {
        if (columns == null || columns.isEmpty()) throw new BadRequestException("Колонка «Наименование» обязательна");
        Set<OfferColumnKey> seen = new HashSet<>();
        for (OfferColumn c : columns) {
            OfferColumnKey key = OfferColumnKey.parse(c.getKey());
            if (!seen.add(key)) throw new BadRequestException("Колонка повторяется: " + key.defaultLabel(true));
            if (c.getLabel() != null && c.getLabel().length() > 120) {
                throw new BadRequestException("Подпись колонки длиннее 120 символов");
            }
        }
        if (!seen.contains(OfferColumnKey.NAME)) throw new BadRequestException("Колонка «Наименование» обязательна");
    }

    /** Пустое значение допустимо (оператор ещё печатает) — в документ такое условие не попадёт. */
    public static void terms(List<OfferTerm> terms) {
        if (terms == null) throw new BadRequestException("Список условий не передан");
        if (terms.size() > 30) throw new BadRequestException("Условий больше 30");
        for (OfferTerm t : terms) {
            if ((t.getLabel() != null && t.getLabel().length() > 300)
                    || (t.getValue() != null && t.getValue().length() > 2000)) {
                throw new BadRequestException("Условие слишком длинное");
            }
        }
    }

    public static void vatRates(List<BigDecimal> rates) {
        if (rates == null || rates.isEmpty()) throw new BadRequestException("Нужна хотя бы одна ставка НДС");
        if (rates.size() > 10) throw new BadRequestException("Ставок НДС больше 10");
        List<BigDecimal> seen = new ArrayList<>();
        for (BigDecimal r : rates) {
            if (r != null && (r.signum() < 0 || r.compareTo(BigDecimal.valueOf(100)) > 0)) {
                throw new BadRequestException("Ставка НДС должна быть от 0 до 100%");
            }
            if (containsRate(seen, r)) throw new BadRequestException("Ставка повторяется: " + DocFormat.rate(r));
            seen.add(r);
        }
    }

    /** null в списке = «Без НДС»; сравнение по значению (5 == 5.00). */
    public static boolean containsRate(List<BigDecimal> rates, BigDecimal rate) {
        if (rates == null) return false;
        for (BigDecimal r : rates) {
            if (r == null ? rate == null : rate != null && r.compareTo(rate) == 0) return true;
        }
        return false;
    }
}
```

`OfferSettingsValidator` и `CompanyProfileService` печатают ставки через `DocFormat` — создать его сейчас (тест к нему — в Task 4).

`src/main/java/com/vladoose/nir/util/DocFormat.java`:

```java
package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Числа и даты документов КП: «9 902 248,23» (неразрывный пробел между разрядами), «2,5», «5%», «14.09.2026».
 * DecimalFormat не потокобезопасен — создаётся на вызов.
 */
public final class DocFormat {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private DocFormat() {}

    private static DecimalFormat format(String pattern) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        symbols.setGroupingSeparator(' ');
        symbols.setDecimalSeparator(',');
        DecimalFormat f = new DecimalFormat(pattern, symbols);
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f;
    }

    public static String money(BigDecimal v) {
        return v == null ? "" : format("#,##0.00").format(v);
    }

    public static String qty(BigDecimal v) {
        return v == null ? "" : format("#,##0.###").format(v);
    }

    /** null — «Без НДС». */
    public static String rate(BigDecimal v) {
        return v == null ? "Без НДС" : format("0.##").format(v) + "%";
    }

    public static String date(LocalDate d) {
        return d == null ? "" : d.format(DATE);
    }

    /** Сокращение валюты в тексте документа: шрифт PDF не содержит знаков ₸ и ₽. */
    public static String currencyShort(String currencyCode) {
        return "RUB".equals(currencyCode) ? "руб." : "тг";
    }
}
```

- [ ] **Step 3: Обработка картинок**

`src/main/java/com/vladoose/nir/service/ImageProcessor.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.exception.BadRequestException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;

/**
 * Скан печати, подписи или логотип → чистый PNG (спека client-kp-constructor §7.2). Размеры проверяются по
 * заголовку ДО декодирования (защита от «бомб»); фон = медиана цвета краёв (белая, желтоватая или серая бумага),
 * всё близкое к нему становится прозрачным с мягким краем, чернила сохраняют цвет; пустые поля обрезаются;
 * длинная сторона ≤ 1200 px; на выходе всегда перекодированный PNG — метаданные и EXIF обработку не переживают.
 */
@Component
public class ImageProcessor {

    public static final int MAX_BYTES = 5 * 1024 * 1024;
    public static final int MAX_SIDE_IN = 5000;
    public static final int MAX_SIDE_OUT = 1200;
    private static final Set<String> FORMATS = Set.of("png", "jpeg");
    /** Отличие от цвета бумаги (макс. по каналам): ≤ NEAR — прозрачно, ≥ FAR — непрозрачно, между — край. */
    static final int NEAR = 28;
    static final int FAR = 80;

    public byte[] process(byte[] input, boolean removeBackground) {
        if (input == null || input.length == 0) throw new BadRequestException("Файл пустой");
        if (input.length > MAX_BYTES) throw new BadRequestException("Картинка больше 5 МБ");
        BufferedImage image = toArgb(read(input));
        if (removeBackground && !hasTransparency(image)) removeBackground(image);
        return png(scaleDown(trim(image)));
    }

    private static BufferedImage read(byte[] input) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = in == null ? Collections.emptyIterator() : ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new BadRequestException("Нужна картинка PNG или JPEG");
            ImageReader reader = readers.next();
            try {
                if (!FORMATS.contains(reader.getFormatName().toLowerCase(Locale.ROOT))) {
                    throw new BadRequestException("Нужна картинка PNG или JPEG");
                }
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w <= 0 || h <= 0 || w > MAX_SIDE_IN || h > MAX_SIDE_IN) {
                    throw new BadRequestException("Картинка больше " + MAX_SIDE_IN + " px по стороне");
                }
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException e) {
            throw new BadRequestException("Картинку не удалось прочитать");
        }
    }

    private static BufferedImage toArgb(BufferedImage src) {
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return out;
    }

    /** Есть заметная прозрачность (>1% пикселей) — готовый PNG, фон не трогаем. */
    private static boolean hasTransparency(BufferedImage img) {
        long transparent = 0;
        long total = (long) img.getWidth() * img.getHeight();
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) < 250) transparent++;
            }
        }
        return transparent * 100 > total;
    }

    private static void removeBackground(BufferedImage img) {
        int[] bg = borderMedian(img);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int dist = Math.max(Math.abs(r - bg[0]), Math.max(Math.abs(g - bg[1]), Math.abs(b - bg[2])));
                int alpha = dist <= NEAR ? 0 : dist >= FAR ? 255 : (dist - NEAR) * 255 / (FAR - NEAR);
                int oldAlpha = (argb >>> 24) & 0xFF;
                img.setRGB(x, y, (Math.min(alpha, oldAlpha) << 24) | (argb & 0x00FFFFFF));
            }
        }
    }

    /** Медиана цвета по краям картинки — цвет бумаги. */
    private static int[] borderMedian(BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int n = 2 * w + 2 * Math.max(0, h - 2);
        int[][] ch = new int[3][n];
        int i = 0;
        for (int x = 0; x < w; x++) {
            i = put(ch, i, img.getRGB(x, 0));
            if (h > 1) i = put(ch, i, img.getRGB(x, h - 1));
        }
        for (int y = 1; y < h - 1; y++) {
            i = put(ch, i, img.getRGB(0, y));
            if (w > 1) i = put(ch, i, img.getRGB(w - 1, y));
        }
        int[] median = new int[3];
        for (int c = 0; c < 3; c++) {
            int[] values = Arrays.copyOf(ch[c], i);
            Arrays.sort(values);
            median[c] = values[values.length / 2];
        }
        return median;
    }

    private static int put(int[][] ch, int i, int argb) {
        ch[0][i] = (argb >> 16) & 0xFF;
        ch[1][i] = (argb >> 8) & 0xFF;
        ch[2][i] = argb & 0xFF;
        return i + 1;
    }

    private static BufferedImage trim(BufferedImage img) {
        int minX = img.getWidth(), minY = img.getHeight(), maxX = -1, maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 16) {
                    if (x < minX) minX = x;
                    if (x > maxX) maxX = x;
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
        }
        if (maxX < 0) throw new BadRequestException("На картинке ничего не осталось — загрузите без удаления фона");
        BufferedImage out = new BufferedImage(maxX - minX + 1, maxY - minY + 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.drawImage(img, -minX, -minY, null);
        g.dispose();
        return out;
    }

    private static BufferedImage scaleDown(BufferedImage img) {
        int longSide = Math.max(img.getWidth(), img.getHeight());
        if (longSide <= MAX_SIDE_OUT) return img;
        double k = (double) MAX_SIDE_OUT / longSide;
        int w = Math.max(1, (int) Math.round(img.getWidth() * k));
        int h = Math.max(1, (int) Math.round(img.getHeight() * k));
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PNG не записан", e);
        }
    }
}
```

`src/main/java/com/vladoose/nir/service/CompanyImageKind.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.exception.NotFoundException;

/** Картинки реквизитов: логотип бланка, печать, подпись директора. path — сегмент URL. */
public enum CompanyImageKind {
    LOGO("logo"), STAMP("stamp"), SIGNATURE("signature");

    private final String path;

    CompanyImageKind(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }

    public static CompanyImageKind fromPath(String path) {
        for (CompanyImageKind k : values()) if (k.path.equals(path)) return k;
        throw new NotFoundException("Нет такой картинки: " + path);
    }
}
```

- [ ] **Step 4: Сервис, DTO, контроллер**

`src/main/java/com/vladoose/nir/dto/request/CompanyProfileRequest.java`:

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Реквизиты и умолчания КП рынка (страница «Реквизиты и печать»). Картинки — отдельными запросами. */
@Data
public class CompanyProfileRequest {
    @NotBlank(message = "Краткое название обязательно") @Size(max = 255) private String shortName;
    @Size(max = 500) private String fullName;
    @Size(max = 1000) private String headerLeft;
    @Size(max = 1000) private String headerRight;
    @Size(max = 100) private String brandText;
    @Size(max = 500) private String idsLine;
    @Size(max = 20) private String binInn;
    @Size(max = 1000) private String address;
    @Size(max = 1000) private String accounts;
    @Size(max = 255) private String bankName;
    @Size(max = 20) private String bik;
    @Size(max = 100) private String phone;
    @Size(max = 255) private String email;
    @Size(max = 100) private String directorTitle;
    @Size(max = 255) private String directorName;
    @Size(max = 500) private String signoffContacts;
    @Min(value = 20, message = "Печать — от 20 мм") @Max(value = 60, message = "Печать — до 60 мм") private int stampSizeMm = 40;
    @NotNull private List<BigDecimal> vatRates;
    private BigDecimal vatDefault;
    private BigDecimal vatRegistered;
    private BigDecimal vatNotRegistrable;
    @NotNull @DecimalMin("-100") @DecimalMax("1000") private BigDecimal defaultMarkupPct;
    @NotNull private List<OfferColumn> defaultColumns;
    @NotNull private List<OfferTerm> defaultTerms;
    @NotNull private TermsStyle defaultTermsStyle;
    @Size(max = 2000) private String defaultIntro;
    @Min(value = 1, message = "Номер — от 1") private int nextNumber = 1;
}
```

`src/main/java/com/vladoose/nir/dto/response/CompanyProfileResponse.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Профиль рынка без байтов картинок: только «есть ли» и когда менялись. */
@Data
public class CompanyProfileResponse {
    private String market;
    private String currency;
    private String shortName;
    private String fullName;
    private String headerLeft;
    private String headerRight;
    private String brandText;
    private String idsLine;
    private String binInn;
    private String address;
    private String accounts;
    private String bankName;
    private String bik;
    private String phone;
    private String email;
    private String directorTitle;
    private String directorName;
    private String signoffContacts;
    private int stampSizeMm;
    private List<BigDecimal> vatRates;
    private BigDecimal vatDefault;
    private BigDecimal vatRegistered;
    private BigDecimal vatNotRegistrable;
    private BigDecimal defaultMarkupPct;
    private List<OfferColumn> defaultColumns;
    private List<OfferTerm> defaultTerms;
    private TermsStyle defaultTermsStyle;
    private String defaultIntro;
    private int nextNumber;
    private boolean hasLogo;
    private boolean hasStamp;
    private boolean hasSignature;
    private OffsetDateTime imagesUpdatedAt;
    private OffsetDateTime updatedAt;

    public static CompanyProfileResponse of(CompanyProfile p) {
        CompanyProfileResponse r = new CompanyProfileResponse();
        r.market = p.getMarket().name();
        r.currency = p.getMarket().currencyCode();
        r.shortName = p.getShortName();
        r.fullName = p.getFullName();
        r.headerLeft = p.getHeaderLeft();
        r.headerRight = p.getHeaderRight();
        r.brandText = p.getBrandText();
        r.idsLine = p.getIdsLine();
        r.binInn = p.getBinInn();
        r.address = p.getAddress();
        r.accounts = p.getAccounts();
        r.bankName = p.getBankName();
        r.bik = p.getBik();
        r.phone = p.getPhone();
        r.email = p.getEmail();
        r.directorTitle = p.getDirectorTitle();
        r.directorName = p.getDirectorName();
        r.signoffContacts = p.getSignoffContacts();
        r.stampSizeMm = p.getStampSizeMm();
        r.vatRates = p.getVatRates();
        r.vatDefault = p.getVatDefault();
        r.vatRegistered = p.getVatRegistered();
        r.vatNotRegistrable = p.getVatNotRegistrable();
        r.defaultMarkupPct = p.getDefaultMarkupPct();
        r.defaultColumns = p.getDefaultColumns();
        r.defaultTerms = p.getDefaultTerms();
        r.defaultTermsStyle = p.getDefaultTermsStyle();
        r.defaultIntro = p.getDefaultIntro();
        r.nextNumber = p.getNextNumber();
        r.hasLogo = p.getLogoPng() != null;
        r.hasStamp = p.getStampPng() != null;
        r.hasSignature = p.getSignaturePng() != null;
        r.imagesUpdatedAt = p.getImagesUpdatedAt();
        r.updatedAt = p.getUpdatedAt();
        return r;
    }
}
```

`src/main/java/com/vladoose/nir/service/CompanyProfileService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CompanyProfileRequest;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.util.DocFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Реквизиты и настройки КП рынка (спека §7). Строка на рынок — по MarketContext, как EmailTemplateService. */
@Service
public class CompanyProfileService {

    private final CompanyProfileRepository repository;
    private final ImageProcessor images;
    private final JdbcTemplate jdbc;

    public CompanyProfileService(CompanyProfileRepository repository, ImageProcessor images, JdbcTemplate jdbc) {
        this.repository = repository;
        this.images = images;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CompanyProfile current() {
        return forMarket(MarketContext.get());
    }

    @Transactional(readOnly = true)
    public CompanyProfile forMarket(Market market) {
        return repository.findByMarket(market)
                .orElseThrow(() -> new NotFoundException("Реквизиты рынка " + market + " не найдены"));
    }

    @Transactional
    public CompanyProfile update(CompanyProfileRequest r) {
        OfferSettingsValidator.vatRates(r.getVatRates());
        requireRate(r.getVatRates(), r.getVatDefault(), "новой строки");
        requireRate(r.getVatRates(), r.getVatRegistered(), "для подтверждённого РУ");
        requireRate(r.getVatRates(), r.getVatNotRegistrable(), "для «не подлежит регистрации»");
        OfferSettingsValidator.columns(r.getDefaultColumns());
        OfferSettingsValidator.terms(r.getDefaultTerms());

        CompanyProfile p = current();
        p.setShortName(r.getShortName().trim());
        p.setFullName(trim(r.getFullName()));
        p.setHeaderLeft(trim(r.getHeaderLeft()));
        p.setHeaderRight(trim(r.getHeaderRight()));
        p.setBrandText(trim(r.getBrandText()));
        p.setIdsLine(trim(r.getIdsLine()));
        p.setBinInn(trim(r.getBinInn()));
        p.setAddress(trim(r.getAddress()));
        p.setAccounts(trim(r.getAccounts()));
        p.setBankName(trim(r.getBankName()));
        p.setBik(trim(r.getBik()));
        p.setPhone(trim(r.getPhone()));
        p.setEmail(trim(r.getEmail()));
        p.setDirectorTitle(trim(r.getDirectorTitle()));
        p.setDirectorName(trim(r.getDirectorName()));
        p.setSignoffContacts(trim(r.getSignoffContacts()));
        p.setStampSizeMm(r.getStampSizeMm());
        p.setVatRates(new ArrayList<>(r.getVatRates()));
        p.setVatDefault(r.getVatDefault());
        p.setVatRegistered(r.getVatRegistered());
        p.setVatNotRegistrable(r.getVatNotRegistrable());
        p.setDefaultMarkupPct(r.getDefaultMarkupPct());
        p.setDefaultColumns(new ArrayList<>(r.getDefaultColumns()));
        p.setDefaultTerms(new ArrayList<>(r.getDefaultTerms()));
        p.setDefaultTermsStyle(r.getDefaultTermsStyle());
        p.setDefaultIntro(trim(r.getDefaultIntro()));
        p.setNextNumber(r.getNextNumber());
        p.setUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public CompanyProfile putImage(CompanyImageKind kind, byte[] bytes, boolean removeBackground) {
        byte[] png = images.process(bytes, removeBackground);
        CompanyProfile p = current();
        switch (kind) {
            case LOGO -> p.setLogoPng(png);
            case STAMP -> p.setStampPng(png);
            case SIGNATURE -> p.setSignaturePng(png);
        }
        p.setImagesUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public CompanyProfile deleteImage(CompanyImageKind kind) {
        CompanyProfile p = current();
        switch (kind) {
            case LOGO -> p.setLogoPng(null);
            case STAMP -> p.setStampPng(null);
            case SIGNATURE -> p.setSignaturePng(null);
        }
        p.setImagesUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional(readOnly = true)
    public byte[] image(CompanyImageKind kind) {
        CompanyProfile p = current();
        byte[] png = switch (kind) {
            case LOGO -> p.getLogoPng();
            case STAMP -> p.getStampPng();
            case SIGNATURE -> p.getSignaturePng();
        };
        if (png == null) throw new NotFoundException("Картинка не загружена");
        return png;
    }

    /**
     * Следующий «исх. №» рынка — атомарно (UPDATE … RETURNING), без гонки двух вкладок. Мимо JPA: сущность профиля
     * в этой транзакции не меняется, поэтому её устаревший nextNumber никто не запишет обратно.
     */
    @Transactional
    public int allocateNumber(Market market) {
        Integer number = jdbc.queryForObject(
                "UPDATE company_profile SET next_number = next_number + 1 WHERE market = ? RETURNING next_number - 1",
                Integer.class, market.name());
        if (number == null) throw new NotFoundException("Реквизиты рынка " + market + " не найдены");
        return number;
    }

    private static void requireRate(List<BigDecimal> rates, BigDecimal rate, String what) {
        if (!OfferSettingsValidator.containsRate(rates, rate)) {
            throw new BadRequestException("Ставки " + what + " «" + DocFormat.rate(rate) + "» нет в списке ставок");
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
```

`src/main/java/com/vladoose/nir/controller/CompanyProfileController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.CompanyProfileRequest;
import com.vladoose.nir.dto.response.CompanyProfileResponse;
import com.vladoose.nir.service.CompanyImageKind;
import com.vladoose.nir.service.CompanyProfileService;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/** «Система → Реквизиты и печать» (спека §7, §10). Чтение профиля — любому вошедшему, остальное — ADMIN. */
@RestController
@RequestMapping("/api/company-profile")
public class CompanyProfileController {

    private final CompanyProfileService service;

    public CompanyProfileController(CompanyProfileService service) {
        this.service = service;
    }

    @GetMapping
    public CompanyProfileResponse get() {
        return CompanyProfileResponse.of(service.current());
    }

    @PutMapping
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse put(@Valid @RequestBody CompanyProfileRequest req) {
        return CompanyProfileResponse.of(service.update(req));
    }

    @PostMapping(value = "/images/{kind}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse upload(@PathVariable String kind, @RequestParam("file") MultipartFile file,
                                         @RequestParam(defaultValue = "true") boolean removeBackground) throws IOException {
        return CompanyProfileResponse.of(service.putImage(CompanyImageKind.fromPath(kind), file.getBytes(), removeBackground));
    }

    /** Печать и подпись — только администратору; в документы вставляются на сервере. */
    @GetMapping("/images/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<byte[]> image(@PathVariable String kind) {
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(service.image(CompanyImageKind.fromPath(kind)));
    }

    @DeleteMapping("/images/{kind}")
    @PreAuthorize("hasRole('ADMIN')")
    public CompanyProfileResponse deleteImage(@PathVariable String kind) {
        return CompanyProfileResponse.of(service.deleteImage(CompanyImageKind.fromPath(kind)));
    }
}
```

- [ ] **Step 5: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service src/main/java/com/vladoose/nir/util src/main/java/com/vladoose/nir/dto src/main/java/com/vladoose/nir/controller/CompanyProfileController.java src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): «Реквизиты и печать» — API профиля рынка, обработка сканов печати/подписи/логотипа, «исх. №»

Фон скана убирается по цвету бумаги с мягким краем, поля обрезаются, на выходе всегда перекодированный PNG;
размеры проверяются до декодирования. Ставки «по умолчанию» обязаны быть в списке ставок рынка. Картинки
печати и подписи отдаются только администратору; номер выделяется атомарно (UPDATE … RETURNING).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 4: Расчёт цен, НДС и маржи; сумма прописью; формат чисел

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java`, `ItemCalc.java`, `VatLine.java`, `OfferTotals.java`, `OfferCalculation.java`
- Create: `src/main/java/com/vladoose/nir/util/AmountInWords.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferCalculatorTest.java`, `AmountInWordsTest.java`, `DocFormatTest.java`

**Interfaces:**
- Consumes: Task 2 (`ClientOffer`, `ClientOfferItem`, перечисления), Task 3 (`DocFormat` уже создан), `ClientOfferTestData`.
- Produces:
  - `ClientOfferCalculator.calculate(ClientOffer)` → `OfferCalculation(List<ItemCalc> items, OfferTotals totals)`; `items` — **в том же порядке и той же длины**, что `offer.getItems()`; у строк `SECTION`/`INCLUDED` — `ItemCalc.NONE`.
  - `ItemCalc(BigDecimal cost, BigDecimal costTotal, BigDecimal markupPct, BigDecimal priceNet, BigDecimal price, BigDecimal sum, BigDecimal vatSum, BigDecimal sumNet, BigDecimal profit, BigDecimal effectiveVatRate)` — record; `effectiveVatRate == null` — строка без НДС.
  - `VatLine(BigDecimal rate, BigDecimal amount)`; `OfferTotals(BigDecimal sum, List<VatLine> vat, BigDecimal vatTotal, BigDecimal purchase, BigDecimal cost, BigDecimal revenueNet, BigDecimal profit, BigDecimal markupAvg, int itemCount, int noPurchaseCount, int unconfirmedRegistrationCount)`.
  - `AmountInWords.of(BigDecimal amount, String currencyCode)` — `"KZT"` / `"RUB"`.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferCalculatorTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferTotals;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Контрольные примеры спеки §5.5 + итоги, разбивка НДС и маржа. */
class ClientOfferCalculatorTest {

    private final ClientOfferCalculator calculator = new ClientOfferCalculator();

    private static ClientOfferItem add(ClientOffer o, String purchase, String vat) {
        ClientOfferItem it = ClientOfferTestData.item(o, o.getItems().size() + 1, "Позиция " + (o.getItems().size() + 1), purchase, vat);
        o.getItems().add(it);
        return it;
    }

    private ItemCalc first(ClientOffer o) {
        return calculator.calculate(o).items().get(0);
    }

    @Test
    void markupCountsOnPriceWithoutVat() {                     // §5.5 №1
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("100000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("20");
        assertThat(c.priceNet()).isEqualByComparingTo("120000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("6000.00");
        assertThat(c.sumNet()).isEqualByComparingTo("120000.00");
        assertThat(c.profit()).isEqualByComparingTo("20000.00");
        assertThat(c.effectiveVatRate()).isEqualByComparingTo("5");
    }

    @Test
    void supplierWithoutVat() {                                 // №2
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem it = add(o, "100000", "5");
        it.setPurchaseVatSame(false);
        it.setPurchaseVatRate(null);
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("100000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.profit()).isEqualByComparingTo("20000.00");
    }

    @Test
    void offerWithoutVatKeepsInputVatInCost() {                 // №3
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        add(o, "105000", "5");
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("0");
        assertThat(c.profit()).isEqualByComparingTo("21000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void lineWithoutVatInVatOfferAlsoKeepsInputVat() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", null);                                  // строка «без НДС» в КП с НДС
        ItemCalc c = first(o);
        assertThat(c.cost()).isEqualByComparingTo("105000.00");
        assertThat(c.price()).isEqualByComparingTo("126000.00");
        assertThat(c.effectiveVatRate()).isNull();
    }

    @Test
    void manualPriceDerivesMarkup() {                           // №4
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5").setPriceOverride(new BigDecimal("130000"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("130000.00");
        assertThat(c.markupPct()).isEqualByComparingTo("23.81");
        assertThat(c.vatSum()).isEqualByComparingTo("6190.48");
        assertThat(c.profit()).isEqualByComparingTo("23809.52");
    }

    @Test
    void manualPriceIgnoresOfferMarkupAndRounding() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.HUNDRED);
        o.setDefaultMarkupPct(new BigDecimal("50"));
        add(o, "100", "5").setPriceOverride(new BigDecimal("1234.56"));
        assertThat(first(o).price()).isEqualByComparingTo("1234.56");
    }

    @Test
    void ownMarkupBeatsOfferMarkupAndRoundsToTens() {           // №5
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(OfferRounding.TEN);
        add(o, "12345", "16").setMarkupPct(new BigDecimal("15"));
        ItemCalc c = first(o);
        assertThat(c.price()).isEqualByComparingTo("14200.00");  // 14 196,75 → до 10
        assertThat(c.vatSum()).isEqualByComparingTo("1958.62");
        assertThat(c.markupPct()).isEqualByComparingTo("15");
    }

    @Test
    void roundingModes() {
        // закупка 1000 с НДС 5%, наценка 17,36% → 1173,60
        assertThat(priceWith(OfferRounding.NONE)).isEqualByComparingTo("1173.60");
        assertThat(priceWith(OfferRounding.UNIT)).isEqualByComparingTo("1174.00");
        assertThat(priceWith(OfferRounding.TEN)).isEqualByComparingTo("1170.00");
        assertThat(priceWith(OfferRounding.HUNDRED)).isEqualByComparingTo("1200.00");
    }

    private BigDecimal priceWith(OfferRounding rounding) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setRounding(rounding);
        o.setDefaultMarkupPct(new BigDecimal("17.36"));
        add(o, "1000", "5");
        return first(o).price();
    }

    @Test
    void quantityMultipliesSums() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5").setQuantity(new BigDecimal("3"));
        ItemCalc c = first(o);
        assertThat(c.sum()).isEqualByComparingTo("378000.00");
        assertThat(c.vatSum()).isEqualByComparingTo("18000.00");
        assertThat(c.costTotal()).isEqualByComparingTo("300000.00");
        assertThat(c.profit()).isEqualByComparingTo("60000.00");
    }

    @Test
    void vitaLineTotal() {                                      // №6 — КП отца от 14.09.2026
        ClientOffer o = ClientOfferTestData.newOffer(443);
        String[] prices = {"7650000.00", "1950000.00", "15045.02", "15045.02", "9144.58", "10552.51", "6858.43", "6321.41",
                "9979.20", "7809.22", "91808.64", "14304.10", "15462.05", "10728.10", "70631.23", "14411.52", "4147.20"};
        for (String p : prices) add(o, null, "5").setPriceOverride(new BigDecimal(p));
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.sum()).isEqualByComparingTo("9902248.23");
        assertThat(t.vat()).hasSize(1);
        assertThat(t.vat().get(0).rate()).isEqualByComparingTo("5");
        assertThat(t.vat().get(0).amount()).isEqualByComparingTo("471535.63");
        assertThat(t.itemCount()).isEqualTo(17);
        assertThat(t.noPurchaseCount()).isEqualTo(17);
    }

    @Test
    void mixedVatBreakdown() {                                  // №7 — строки КП от 24.09.2026
        ClientOffer o = ClientOfferTestData.newOffer(1);
        line(o, "105600.00", 3, "5");
        line(o, "13515.00", 4, "16");
        line(o, "21150.00", 2, "5");
        line(o, "83725.00", 4, "16");
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.sum()).isEqualByComparingTo("748060.00");
        assertThat(t.vat()).extracting(v -> v.rate().stripTrailingZeros().toPlainString()).containsExactly("5", "16");
        assertThat(t.vat().get(0).amount()).isEqualByComparingTo("17100.00");
        assertThat(t.vat().get(1).amount()).isEqualByComparingTo("53649.65");
        assertThat(t.vatTotal()).isEqualByComparingTo("70749.65");
    }

    private static void line(ClientOffer o, String price, int qty, String vat) {
        ClientOfferItem it = add(o, null, vat);
        it.setPriceOverride(new BigDecimal(price));
        it.setQuantity(BigDecimal.valueOf(qty));
    }

    @Test
    void sectionAndIncludedRowsAreNotCalculated() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem section = add(o, null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        add(o, "105000", "5");
        ClientOfferItem included = add(o, null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        OfferCalculation calc = calculator.calculate(o);
        assertThat(calc.items()).hasSize(3);
        assertThat(calc.items().get(0)).isSameAs(ItemCalc.NONE);
        assertThat(calc.items().get(2)).isSameAs(ItemCalc.NONE);
        assertThat(calc.totals().itemCount()).isEqualTo(1);
        assertThat(calc.totals().sum()).isEqualByComparingTo("126000.00");
    }

    @Test
    void rowWithoutPurchaseAndPriceHasNoSum() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, null, "5");
        OfferCalculation calc = calculator.calculate(o);
        assertThat(calc.items().get(0).price()).isNull();
        assertThat(calc.items().get(0).sum()).isNull();
        assertThat(calc.totals().sum()).isEqualByComparingTo("0");
        assertThat(calc.totals().noPurchaseCount()).isEqualTo(1);
    }

    @Test
    void marginTotals() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "105000", "5");                                  // прибыль 20 000 на себестоимость 100 000
        add(o, "116000", "16").setMarkupPct(new BigDecimal("10")); // себестоимость 100 000, прибыль 10 000
        OfferTotals t = calculator.calculate(o).totals();
        assertThat(t.purchase()).isEqualByComparingTo("221000.00");
        assertThat(t.cost()).isEqualByComparingTo("200000.00");
        assertThat(t.revenueNet()).isEqualByComparingTo("230000.00");
        assertThat(t.profit()).isEqualByComparingTo("30000.00");
        assertThat(t.markupAvg()).isEqualByComparingTo("15.00");
    }

    @Test
    void countsUnconfirmedRegistration() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        add(o, "1", "5");                                        // UNCHECKED
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.MANUAL);
        add(o, "1", "5").setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
        assertThat(calculator.calculate(o).totals().unconfirmedRegistrationCount()).isEqualTo(2);
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/AmountInWordsTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.util.AmountInWords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0          | KZT | Ноль тенге 00 тиын",
            "1          | RUB | Один рубль 00 копеек",
            "2          | RUB | Два рубля 00 копеек",
            "5          | RUB | Пять рублей 00 копеек",
            "11         | RUB | Одиннадцать рублей 00 копеек",
            "14         | RUB | Четырнадцать рублей 00 копеек",
            "21         | RUB | Двадцать один рубль 00 копеек",
            "101.01     | RUB | Сто один рубль 01 копейка",
            "112.14     | RUB | Сто двенадцать рублей 14 копеек",
            "0.02       | RUB | Ноль рублей 02 копейки",
            "1000       | RUB | Одна тысяча рублей 00 копеек",
            "1001.22    | RUB | Одна тысяча один рубль 22 копейки",
            "2000       | KZT | Две тысячи тенге 00 тиын",
            "5000       | KZT | Пять тысяч тенге 00 тиын",
            "21000      | RUB | Двадцать одна тысяча рублей 00 копеек",
            "1000000    | KZT | Один миллион тенге 00 тиын",
            "2721000    | KZT | Два миллиона семьсот двадцать одна тысяча тенге 00 тиын",
            "748060     | KZT | Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
            "9902248.23 | KZT | Девять миллионов девятьсот две тысячи двести сорок восемь тенге 23 тиын",
    })
    void spellsAmount(String amount, String currency, String expected) {
        assertThat(AmountInWords.of(new BigDecimal(amount), currency)).isEqualTo(expected);
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/DocFormatTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.util.DocFormat;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

class DocFormatTest {

    private static final String NB = " ";

    @Test
    void formatsMoneyQuantityRateAndDate() {
        assertThat(DocFormat.money(new BigDecimal("9902248.23"))).isEqualTo("9" + NB + "902" + NB + "248,23");
        assertThat(DocFormat.money(BigDecimal.ZERO)).isEqualTo("0,00");
        assertThat(DocFormat.money(null)).isEmpty();
        assertThat(DocFormat.qty(new BigDecimal("1.000"))).isEqualTo("1");
        assertThat(DocFormat.qty(new BigDecimal("2.5"))).isEqualTo("2,5");
        assertThat(DocFormat.qty(new BigDecimal("1000"))).isEqualTo("1" + NB + "000");
        assertThat(DocFormat.rate(new BigDecimal("5.00"))).isEqualTo("5%");
        assertThat(DocFormat.rate(new BigDecimal("12.5"))).isEqualTo("12,5%");
        assertThat(DocFormat.rate(null)).isEqualTo("Без НДС");
        assertThat(DocFormat.date(LocalDate.of(2026, 9, 14))).isEqualTo("14.09.2026");
        assertThat(DocFormat.currencyShort("KZT")).isEqualTo("тг");
        assertThat(DocFormat.currencyShort("RUB")).isEqualTo("руб.");
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferCalculatorTest' --tests 'com.vladoose.nir.clientoffer.AmountInWordsTest' --tests 'com.vladoose.nir.clientoffer.DocFormatTest'`
Expected: FAIL — компиляция: `cannot find symbol … ClientOfferCalculator` / `AmountInWords` (`DocFormatTest` компилируется — класс создан в Task 3).

- [ ] **Step 2: Реализация расчёта**

`src/main/java/com/vladoose/nir/service/offer/ItemCalc.java`:

```java
package com.vladoose.nir.service.offer;

import java.math.BigDecimal;

/**
 * Расчёт строки КП за единицу и на количество (спека §5.1). cost — себестоимость за ед. (без входного НДС, если
 * строка с НДС), costTotal — на количество; price — цена клиенту за ед. (с НДС, если строка с НДС); суммы — на
 * количество; effectiveVatRate — ставка с учётом общего переключателя НДС (null — без НДС). У SECTION/INCLUDED — NONE.
 */
public record ItemCalc(BigDecimal cost, BigDecimal costTotal, BigDecimal markupPct, BigDecimal priceNet, BigDecimal price,
                       BigDecimal sum, BigDecimal vatSum, BigDecimal sumNet, BigDecimal profit, BigDecimal effectiveVatRate) {

    public static final ItemCalc NONE = new ItemCalc(null, null, null, null, null, null, null, null, null, null);
}
```

`src/main/java/com/vladoose/nir/service/offer/VatLine.java`:

```java
package com.vladoose.nir.service.offer;

import java.math.BigDecimal;

/** «в т.ч. НДС 5% — сумма». */
public record VatLine(BigDecimal rate, BigDecimal amount) {}
```

`src/main/java/com/vladoose/nir/service/offer/OfferTotals.java`:

```java
package com.vladoose.nir.service.offer;

import java.math.BigDecimal;
import java.util.List;

/**
 * Итоги КП (§5.3) и маржа (§5.4, в документ не попадает). markupAvg — прибыль / себестоимость по строкам, где
 * себестоимость известна; null, если таких строк нет.
 */
public record OfferTotals(BigDecimal sum, List<VatLine> vat, BigDecimal vatTotal, BigDecimal purchase, BigDecimal cost,
                          BigDecimal revenueNet, BigDecimal profit, BigDecimal markupAvg,
                          int itemCount, int noPurchaseCount, int unconfirmedRegistrationCount) {}
```

`src/main/java/com/vladoose/nir/service/offer/OfferCalculation.java`:

```java
package com.vladoose.nir.service.offer;

import java.util.List;

/** items — в том же порядке и той же длины, что offer.getItems(). */
public record OfferCalculation(List<ItemCalc> items, OfferTotals totals) {}
```

`src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java`:

```java
package com.vladoose.nir.service.offer;

import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferItem;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.entity.OfferRounding;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Единственное место формул КП (спека client-kp-constructor §5). Наценка — «с учётом НДС» (решение оператора
 * 2026-10-02): себестоимость = закупка без входного НДС, если строку продаём с НДС; если без НДС — входной НДС
 * к зачёту не идёт и остаётся в себестоимости. Цена = себестоимость × (1 + наценка) × (1 + наш НДС) → округление.
 * Ручная цена фиксируется, наценка выводится обратно. НДС выделяется из суммы («в т.ч.»), суммы — HALF_UP до 0,01.
 */
@Component
public class ClientOfferCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final BigDecimal TEN = BigDecimal.TEN;
    private static final int WORK_SCALE = 10;

    public OfferCalculation calculate(ClientOffer offer) {
        List<ItemCalc> items = new ArrayList<>(offer.getItems().size());
        Map<BigDecimal, BigDecimal> vatByRate = new TreeMap<>();
        BigDecimal sum = zero(), vatTotal = zero(), purchase = zero(), cost = zero(), revenueNet = zero(), profit = zero();
        BigDecimal costWithProfit = zero(), netWithProfit = zero();
        int itemCount = 0, noPurchase = 0, unconfirmed = 0;

        for (ClientOfferItem item : offer.getItems()) {
            if (item.getKind() != ClientOfferItemKind.ITEM) {
                items.add(ItemCalc.NONE);
                continue;
            }
            itemCount++;
            OfferRegistrationStatus reg = item.getRegistrationStatus();
            if (reg == OfferRegistrationStatus.UNCHECKED || reg == OfferRegistrationStatus.SUGGESTED) unconfirmed++;
            if (item.getPurchasePrice() == null) noPurchase++;
            else purchase = purchase.add(item.getPurchasePrice().multiply(qty(item)));

            ItemCalc c = calculateItem(offer, item);
            items.add(c);
            if (c.sum() != null) {
                sum = sum.add(c.sum());
                vatTotal = vatTotal.add(c.vatSum());
                revenueNet = revenueNet.add(c.sumNet());
                if (c.effectiveVatRate() != null) vatByRate.merge(c.effectiveVatRate(), c.vatSum(), BigDecimal::add);
            }
            if (c.costTotal() != null) cost = cost.add(c.costTotal());
            if (c.profit() != null) {
                profit = profit.add(c.profit());
                costWithProfit = costWithProfit.add(c.costTotal());
                netWithProfit = netWithProfit.add(c.sumNet());
            }
        }

        List<VatLine> vat = new ArrayList<>();
        vatByRate.forEach((rate, amount) -> vat.add(new VatLine(rate, amount)));
        BigDecimal markupAvg = costWithProfit.signum() > 0
                ? netWithProfit.subtract(costWithProfit).multiply(HUNDRED).divide(costWithProfit, 2, RoundingMode.HALF_UP)
                : null;
        OfferTotals totals = new OfferTotals(sum, vat, vatTotal, purchase.setScale(2, RoundingMode.HALF_UP), cost,
                revenueNet, profit, markupAvg, itemCount, noPurchase, unconfirmed);
        return new OfferCalculation(items, totals);
    }

    ItemCalc calculateItem(ClientOffer offer, ClientOfferItem item) {
        BigDecimal q = qty(item);
        BigDecimal v = offer.isVatEnabled() ? item.getVatRate() : null;
        boolean taxable = v != null;
        BigDecimal p = item.isPurchaseVatSame() ? v : item.getPurchaseVatRate();

        BigDecimal costRaw = null;
        if (item.getPurchasePrice() != null) {
            costRaw = taxable && p != null
                    ? item.getPurchasePrice().divide(factor(p), WORK_SCALE, RoundingMode.HALF_UP)
                    : item.getPurchasePrice();
        }

        BigDecimal price;
        BigDecimal markup;
        if (item.getPriceOverride() != null) {
            price = item.getPriceOverride().setScale(2, RoundingMode.HALF_UP);
            BigDecimal net = taxable ? price.divide(factor(v), WORK_SCALE, RoundingMode.HALF_UP) : price;
            markup = costRaw != null && costRaw.signum() > 0
                    ? net.divide(costRaw, WORK_SCALE, RoundingMode.HALF_UP).subtract(BigDecimal.ONE)
                            .multiply(HUNDRED).setScale(2, RoundingMode.HALF_UP)
                    : null;
        } else if (costRaw != null) {
            BigDecimal m = item.getMarkupPct() != null ? item.getMarkupPct() : offer.getDefaultMarkupPct();
            BigDecimal net = costRaw.multiply(factor(m));
            price = round(taxable ? net.multiply(factor(v)) : net, offer.getRounding());
            markup = m.setScale(2, RoundingMode.HALF_UP);
        } else {
            return new ItemCalc(null, null, item.getMarkupPct(), null, null, null, null, null, null, v);
        }

        BigDecimal sum = price.multiply(q).setScale(2, RoundingMode.HALF_UP);
        BigDecimal vatSum = taxable
                ? sum.multiply(v).divide(HUNDRED.add(v), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO.setScale(2);
        BigDecimal sumNet = sum.subtract(vatSum);
        BigDecimal priceNet = taxable ? price.divide(factor(v), 2, RoundingMode.HALF_UP) : price;
        BigDecimal costTotal = costRaw == null ? null : costRaw.multiply(q).setScale(2, RoundingMode.HALF_UP);
        BigDecimal profit = costTotal == null ? null : sumNet.subtract(costTotal);
        BigDecimal cost = costRaw == null ? null : costRaw.setScale(2, RoundingMode.HALF_UP);
        return new ItemCalc(cost, costTotal, markup, priceNet, price, sum, vatSum, sumNet, profit, v);
    }

    static BigDecimal round(BigDecimal value, OfferRounding rounding) {
        return switch (rounding == null ? OfferRounding.NONE : rounding) {
            case UNIT -> value.setScale(0, RoundingMode.HALF_UP).setScale(2);
            case TEN -> value.divide(TEN, 0, RoundingMode.HALF_UP).multiply(TEN).setScale(2);
            case HUNDRED -> value.divide(HUNDRED, 0, RoundingMode.HALF_UP).multiply(HUNDRED).setScale(2);
            case NONE -> value.setScale(2, RoundingMode.HALF_UP);
        };
    }

    private static BigDecimal factor(BigDecimal percent) {
        return BigDecimal.ONE.add(percent.divide(HUNDRED, WORK_SCALE, RoundingMode.HALF_UP));
    }

    private static BigDecimal qty(ClientOfferItem item) {
        return item.getQuantity() == null ? BigDecimal.ZERO : item.getQuantity();
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(2);
    }
}
```

- [ ] **Step 3: Сумма прописью** (`DocFormat` уже есть — Task 3)

`src/main/java/com/vladoose/nir/util/AmountInWords.java`:

```java
package com.vladoose.nir.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * Сумма прописью для КП (спека §5.3): «Девять миллионов девятьсот две тысячи двести сорок восемь тенге 23 тиын».
 * Целая часть словами (род: тысяча — ж., миллион — м., валюта — м.), копейки/тиыны цифрами. Тенге и тиын не
 * склоняются; рубль и копейка — по правилу 1 / 2–4 / 5–20 (11–14 — всегда «многие»).
 */
public final class AmountInWords {

    private static final String[] ONES_M = {"", "один", "два", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] ONES_F = {"", "одна", "две", "три", "четыре", "пять", "шесть", "семь", "восемь", "девять"};
    private static final String[] TEENS = {"десять", "одиннадцать", "двенадцать", "тринадцать", "четырнадцать",
            "пятнадцать", "шестнадцать", "семнадцать", "восемнадцать", "девятнадцать"};
    private static final String[] TENS = {"", "", "двадцать", "тридцать", "сорок", "пятьдесят", "шестьдесят",
            "семьдесят", "восемьдесят", "девяносто"};
    private static final String[] HUNDREDS = {"", "сто", "двести", "триста", "четыреста", "пятьсот", "шестьсот",
            "семьсот", "восемьсот", "девятьсот"};
    private static final String[][] GROUPS = {
            null,
            {"тысяча", "тысячи", "тысяч"},
            {"миллион", "миллиона", "миллионов"},
            {"миллиард", "миллиарда", "миллиардов"},
            {"триллион", "триллиона", "триллионов"}};
    private static final boolean[] GROUP_FEMININE = {false, true, false, false, false};

    private record Currency(String[] major, String[] minor) {}

    private static final Currency KZT = new Currency(new String[]{"тенге", "тенге", "тенге"}, new String[]{"тиын", "тиын", "тиын"});
    private static final Currency RUB = new Currency(new String[]{"рубль", "рубля", "рублей"}, new String[]{"копейка", "копейки", "копеек"});

    private AmountInWords() {}

    public static String of(BigDecimal amount, String currencyCode) {
        Currency currency = "RUB".equals(currencyCode) ? RUB : KZT;
        BigDecimal value = amount.setScale(2, RoundingMode.HALF_UP);
        boolean negative = value.signum() < 0;
        value = value.abs();
        long whole = value.longValue();
        int minor = value.remainder(BigDecimal.ONE).movePointRight(2).intValue();
        String words = whole == 0 ? "ноль" : spell(whole);
        String text = (negative ? "минус " : "") + words + " " + currency.major()[form(whole)]
                + " " + String.format("%02d", minor) + " " + currency.minor()[form(minor)];
        return Character.toUpperCase(text.charAt(0)) + text.substring(1);
    }

    private static String spell(long number) {
        List<String> words = new ArrayList<>();
        int group = 0;
        long n = number;
        while (n > 0) {
            if (group >= GROUPS.length) throw new IllegalArgumentException("Слишком большая сумма: " + number);
            int triad = (int) (n % 1000);
            if (triad != 0) {
                List<String> part = triad(triad, GROUP_FEMININE[group]);
                if (group > 0) part.add(GROUPS[group][form(triad)]);
                words.addAll(0, part);
            }
            n /= 1000;
            group++;
        }
        return String.join(" ", words);
    }

    private static List<String> triad(int n, boolean feminine) {
        List<String> w = new ArrayList<>();
        int h = n / 100, t = (n / 10) % 10, o = n % 10;
        if (h > 0) w.add(HUNDREDS[h]);
        if (t == 1) {
            w.add(TEENS[o]);
        } else {
            if (t > 1) w.add(TENS[t]);
            if (o > 0) w.add(feminine ? ONES_F[o] : ONES_M[o]);
        }
        return w;
    }

    /** 0 — «один рубль», 1 — «два рубля», 2 — «пять рублей». */
    static int form(long n) {
        long m100 = n % 100, m10 = n % 10;
        if (m100 >= 11 && m100 <= 14) return 2;
        if (m10 == 1) return 0;
        if (m10 >= 2 && m10 <= 4) return 1;
        return 2;
    }
}
```

- [ ] **Step 4: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 5: Мутации (по одной, откат — копией файла, CLAUDE.md §14)**

```bash
cd /Users/vlad/IdeaProjects/AIS && F=src/main/java/com/vladoose/nir/service/offer/ClientOfferCalculator.java && cp "$F" "$F.bak"
# 1) себестоимость всегда «без входного НДС» — даже когда продаём без НДС
sed -i '' 's/costRaw = taxable \&\& p != null/costRaw = p != null/' "$F"
./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferCalculatorTest' -q; echo "exit=$?"
cp "$F.bak" "$F" && diff -q "$F.bak" "$F" && rm "$F.bak"
```

Expected: `exit=1`, падают `offerWithoutVatKeepsInputVatInCost`, `lineWithoutVatInVatOfferAlsoKeepsInputVat`. После отката — `diff` молчит. Затем полный прогон пакета снова PASS.

- [ ] **Step 6: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/offer src/main/java/com/vladoose/nir/util/AmountInWords.java src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): расчёт КП — наценка с учётом НДС, ручная цена, округление, разбивка НДС, маржа; сумма прописью

Контрольные примеры спеки §5.5 — тестами: 105 000 + 20% = 126 000 и в двух других режимах; итог ВитаЛайн
9 902 248,23 и НДС 471 535,63; смешанные 5%/16% из КП от 24.09. Мутация «себестоимость без входного НДС
всегда» ловится своими тестами.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 5: Модель документа

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/CompanyLines.java`
- Create: `src/main/java/com/vladoose/nir/service/document/KpDocument.java`, `KpDocumentBuilder.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/KpFixtures.java`, `KpDocumentBuilderTest.java`

**Interfaces:**
- Consumes: Task 2 (сущности), Task 3 (`OfferColumnKey`, `ColumnAlign`, `DocFormat`), Task 4 (`OfferCalculation`, `ItemCalc`, `VatLine`, `AmountInWords`).
- Produces:
  - `CompanyLines.of(CompanyProfile)` → строки реквизитов под логотипом; `CompanyLines.split(String)` → непустые строки текста (используются в Task 9).
  - `KpDocument` (record) с вложенными `Letterhead(List<String> left, List<String> right, byte[] logoPng, String brandText, List<String> lines)`, `Term(String label, List<String> valueLines)`, `Column(String key, String label, ColumnAlign align, int percent)`, `RowKind { ITEM, SECTION, INCLUDED }`, `Row(RowKind kind, List<List<String>> cells, int spanFrom, List<String> spanLines)`, `Signoff(boolean director, String titleLine, String nameLine, String contacts, byte[] signaturePng, byte[] stampPng, int stampSizeMm)`; поля `KpDocument`: `landscape, letterhead, numberLine, recipientLines, title, subject, intro, termsTable, columns, rows, totalLines, amountInWords, termsList, signoff`.
  - `KpDocumentBuilder.build(ClientOffer, CompanyProfile, OfferCalculation)` → `KpDocument`.
  - Тестовый `KpFixtures`: `profileKz()`, `offer2409()` (КП № 443 от 14.09.2026, колонки рынка KZ, 4 строки из КП от 24.09, условия списком), `document(ClientOffer, CompanyProfile)`.

**Правила строки (`Row`):** `cells` — ячейки колонок `[0, spanFrom)`, затем, если `spanFrom < columns.size()`, одна объединённая ячейка `spanLines` до конца строки. `ITEM`: все колонки, `spanFrom = columns.size()`. `SECTION`: `cells` пусто, `spanFrom = 0`, текст раздела во всю ширину. `INCLUDED`: ячейки до колонки `NAME` включительно (наименование), затем «Включено в стоимость» (или `note`) во всю оставшуюся ширину; если `NAME` последняя — текст второй строкой в ней же.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientoffer/KpFixtures.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.document.KpDocumentBuilder;
import com.vladoose.nir.service.offer.ClientOfferCalculator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Реквизиты West-Med и КП «как от 24.09» без БД — для тестов модели документа и рендереров. */
final class KpFixtures {

    private KpFixtures() {}

    static CompanyProfile profileKz() {
        return CompanyProfile.builder()
                .market(Market.KZ)
                .shortName("ТОО «West-Med»")
                .fullName("Товарищество с ограниченной ответственностью «West-Med»")
                .headerLeft("Жауапкершілігі\nшектеулі серіктестігі")
                .headerRight("Товарищество\nс ограниченной ответственностью")
                .brandText("\"West-Med\"")
                .idsLine("РНН 271 800 059 535 БИН 121 040 000 303")
                .binInn("121040000303")
                .address("Республика Казахстан, 090000, Западно-Казахстанская область,\nгород Уральск, ул.Мухита 121-21")
                .accounts("KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)")
                .bankName("АО \"Alatau City Bank\"")
                .bik("TSESKZKA")
                .phone("87770752770")
                .email("west-med@mail.ru")
                .directorTitle("Директор")
                .directorName("Ширяев Илья Викторович")
                .signoffContacts("моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru")
                .vatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)))
                .vatDefault(new BigDecimal("5"))
                .vatRegistered(new BigDecimal("5"))
                .vatNotRegistrable(new BigDecimal("16"))
                .defaultMarkupPct(new BigDecimal("20"))
                .stampSizeMm(40)
                .build();
    }

    static List<OfferColumn> kzColumns() {
        return new ArrayList<>(List.of(
                new OfferColumn("NUM", "№"), new OfferColumn("NAME", "Наименование"), new OfferColumn("UNIT", "Ед. изм."),
                new OfferColumn("QTY", "Кол-во"), new OfferColumn("PRICE", "Цена за ед., тг"), new OfferColumn("VAT_RATE", "НДС"),
                new OfferColumn("SUM", "Общая сумма, тг"), new OfferColumn("REGISTRATION", "Регистрация в РК")));
    }

    /** КП № 443 от 14.09.2026: 4 строки из КП отца от 24.09 (цены вбиты руками), условия — списком. */
    static ClientOffer offer2409() {
        ClientOffer o = ClientOfferTestData.newOffer(443);
        o.setMarket(Market.KZ);
        o.setTableColumns(kzColumns());
        o.setIntro("ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:");
        o.setTerms(new ArrayList<>(List.of(
                new OfferTerm("", "Цены действительны в течение 10 дней"),
                new OfferTerm("", "Транспортные услуги включены в общую стоимость товара"),
                new OfferTerm("Порядок оплаты", "100% предоплата"),
                new OfferTerm("Форма оплаты", "безналичная"),
                new OfferTerm("Срок поставки всего товара", "30 рабочих дней после поступления предоплаты"))));
        line(o, "Пульсоксиметр QMP – PO70 взрослый", 3, "105600.00", "5",
                OfferRegistrationStatus.MANUAL, "№ РК-МИ (МТ)-0№023037 от 28.10.2021 г.");
        line(o, "Гигрометр психрометрический ВИТ-2", 4, "13515.00", "16",
                OfferRegistrationStatus.NOT_REQUIRED, "Не подлежит регистрации");
        line(o, "Мешок для ИВЛ типа «Амбу» Beebrix, 1600 мл", 2, "21150.00", "5",
                OfferRegistrationStatus.MANUAL, "№ РК МИ (ИМН)-0 №027522 бессрочно");
        line(o, "Термоконтейнер для холодовой цепи ТМ-4", 4, "83725.00", "16",
                OfferRegistrationStatus.NOT_REQUIRED, "Не подлежит регистрации");
        return o;
    }

    static ClientOfferItem line(ClientOffer o, String name, int qty, String price, String vat,
                                OfferRegistrationStatus reg, String regText) {
        ClientOfferItem it = ClientOfferTestData.item(o, o.getItems().size() + 1, name, null, vat);
        it.setQuantity(BigDecimal.valueOf(qty));
        it.setPriceOverride(new BigDecimal(price));
        it.setRegistrationStatus(reg);
        it.setRegistrationText(regText);
        o.getItems().add(it);
        return it;
    }

    static KpDocument document(ClientOffer offer, CompanyProfile profile) {
        return new KpDocumentBuilder().build(offer, profile, new ClientOfferCalculator().calculate(offer));
    }
}
```

`src/test/java/com/vladoose/nir/clientoffer/KpDocumentBuilderTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocument;
import com.vladoose.nir.service.offer.ColumnAlign;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Модель документа (спека §6): колонки, строки трёх видов, итоги, условия, бланк, подпись. */
class KpDocumentBuilderTest {

    private static String nb(String s) {
        return s.replace(' ', ' ');
    }

    private static List<String> labels(KpDocument d) {
        return d.columns().stream().map(KpDocument.Column::label).toList();
    }

    @Test
    void columnsKeepCustomLabelsAndFillDefaults() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null),
                new OfferColumn("NAME", "Товары (работы, услуги)"), new OfferColumn("PRICE", null),
                new OfferColumn("VAT_RATE", " "), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(labels(d)).containsExactly("№", "Товары (работы, услуги)", "Цена (с НДС)", "Ставка НДС", "Сумма (с НДС)");
        assertThat(d.columns().stream().mapToInt(KpDocument.Column::percent).sum()).isEqualTo(100);
        assertThat(d.columns().get(1).percent()).isGreaterThan(40);
        assertThat(d.columns().get(2).align()).isEqualTo(ColumnAlign.RIGHT);
    }

    @Test
    void vatOffHidesVatColumnsAndSaysWithoutVat() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setVatEnabled(false);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("PRICE", null),
                new OfferColumn("VAT_RATE", null), new OfferColumn("VAT_SUM", null), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(labels(d)).containsExactly("Наименование", "Цена", "Сумма");
        assertThat(d.totalLines()).contains("Без НДС");
    }

    @Test
    void unknownColumnIsSkippedAndNameIsAlwaysPresent() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("FOO", "x"), new OfferColumn("SUM", null))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.columns()).extracting(KpDocument.Column::key).containsExactly("NAME", "SUM");
    }

    @Test
    void nameGetsModelAndProducerWhenTheirColumnsAreHidden() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        ClientOfferItem it = ClientOfferTestData.item(o, 1, "Скальпель офтальмологический", "1000", "5");
        it.setModel("MSL24");
        it.setManufacturer("Mani");
        it.setCountry("Вьетнам");
        o.getItems().add(it);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        int name = 1; // NUM, NAME, SUM
        assertThat(d.rows().get(0).cells().get(name))
                .containsExactly("Скальпель офтальмологический MSL24", "Производитель: Mani, Вьетнам");

        o.getTableColumns().add(new OfferColumn("MODEL", null));
        o.getTableColumns().add(new OfferColumn("COUNTRY", null));
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.rows().get(0).cells().get(name)).containsExactly("Скальпель офтальмологический", "Производитель: Mani");

        o.setDetailsInName(false);
        d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.rows().get(0).cells().get(name)).containsExactly("Скальпель офтальмологический");
    }

    @Test
    void numbersOnlyItemsAndBuildsSectionAndIncludedSpans() {
        ClientOffer o = ClientOfferTestData.newOffer(1);   // колонки NUM, NAME, SUM
        o.getItems().add(ClientOfferTestData.item(o, 1, "Аппарат ИВЛ", "2721000", "5"));
        ClientOfferItem section = ClientOfferTestData.item(o, 2, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(section);
        o.getItems().add(ClientOfferTestData.item(o, 3, "Увлажнитель", "1000", "5"));
        ClientOfferItem included = ClientOfferTestData.item(o, 4, "Гарантийное сервисное обслуживание 37 месяцев", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        o.getItems().add(included);

        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows.get(0).cells().get(0)).containsExactly("1");
        assertThat(rows.get(1).kind()).isEqualTo(KpDocument.RowKind.SECTION);
        assertThat(rows.get(1).spanFrom()).isZero();
        assertThat(rows.get(1).spanLines()).containsExactly("Основные комплектующие:");
        assertThat(rows.get(2).cells().get(0)).containsExactly("2");
        KpDocument.Row inc = rows.get(3);
        assertThat(inc.cells()).hasSize(2);                     // NUM (пусто) + NAME
        assertThat(inc.cells().get(0)).isEmpty();
        assertThat(inc.cells().get(1)).containsExactly("Гарантийное сервисное обслуживание 37 месяцев");
        assertThat(inc.spanFrom()).isEqualTo(2);
        assertThat(inc.spanLines()).containsExactly("Включено в стоимость");
    }

    @Test
    void includedWhenNameIsLastColumnGoesIntoNameCell() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NUM", null), new OfferColumn("SUM", null),
                new OfferColumn("NAME", null))));
        ClientOfferItem included = ClientOfferTestData.item(o, 1, "Обучение персонала", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        included.setNote("Включено в стоимость медицинской техники");
        o.getItems().add(included);
        KpDocument.Row row = KpFixtures.document(o, KpFixtures.profileKz()).rows().get(0);
        assertThat(row.spanFrom()).isEqualTo(3);
        assertThat(row.cells().get(2)).containsExactly("Обучение персонала", "Включено в стоимость медицинской техники");
    }

    @Test
    void registrationIsPrintedOnlyWhenConfirmedNotRequiredOrManual() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTableColumns(new ArrayList<>(List.of(new OfferColumn("NAME", null), new OfferColumn("REGISTRATION", null))));
        ClientOfferItem manual = ClientOfferTestData.item(o, 1, "А", "1", "5");
        manual.setRegistrationStatus(OfferRegistrationStatus.MANUAL);
        manual.setRegistrationText("№ РК-МИ (МТ)-0№023037");
        ClientOfferItem suggested = ClientOfferTestData.item(o, 2, "Б", "1", "5");
        suggested.setRegistrationStatus(OfferRegistrationStatus.SUGGESTED);
        suggested.setRegistrationText("подсказка реестра — не печатать");
        ClientOfferItem notRequired = ClientOfferTestData.item(o, 3, "В", "1", "16");
        notRequired.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
        notRequired.setRegistrationText("Не подлежит регистрации");
        o.getItems().addAll(List.of(manual, suggested, notRequired));
        List<KpDocument.Row> rows = KpFixtures.document(o, KpFixtures.profileKz()).rows();
        assertThat(rows.get(0).cells().get(1)).containsExactly("№ РК-МИ (МТ)-0№023037");
        assertThat(rows.get(1).cells().get(1)).isEmpty();
        assertThat(rows.get(2).cells().get(1)).containsExactly("Не подлежит регистрации");
    }

    @Test
    void totalsVatBreakdownAndWordsFromTheSeptemberOffer() {
        KpDocument d = KpFixtures.document(KpFixtures.offer2409(), KpFixtures.profileKz());
        assertThat(d.totalLines().stream().map(KpDocumentBuilderTest::nb)).containsExactly(
                "Итого: 748 060,00 тг", "в т.ч. НДС 5%: 17 100,00 тг", "в т.ч. НДС 16%: 53 649,65 тг");
        assertThat(d.amountInWords()).isEqualTo("Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын");
        List<String> first = d.rows().get(0).cells().stream().map(c -> nb(String.join("|", c))).toList();
        assertThat(first).containsExactly("1", "Пульсоксиметр QMP – PO70 взрослый", "шт", "3", "105 600,00", "5%",
                "316 800,00", "№ РК-МИ (МТ)-0№023037 от 28.10.2021 г.");
    }

    @Test
    void wordsAndBreakdownCanBeSwitchedOff() {
        ClientOffer o = KpFixtures.offer2409();
        o.setShowAmountInWords(false);
        o.setShowVatBreakdown(false);
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.amountInWords()).isNull();
        assertThat(d.totalLines()).hasSize(1);
    }

    @Test
    void termsListIsNumberedWithSemicolonsAndSkipsEmpty() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("", "Цены действительны в течение 10 дней."),
                new OfferTerm("Порядок оплаты", "100% предоплата;"), new OfferTerm("Форма оплаты", " "))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.termsList()).containsExactly("1. Цены действительны в течение 10 дней;", "2. Порядок оплаты: 100% предоплата.");
        assertThat(d.termsTable()).isEmpty();
    }

    @Test
    void termsTableStyle() {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setTermsStyle(TermsStyle.TABLE);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Условия поставки", "DDP Заказчик"),
                new OfferTerm("Гарантия", "37 месяцев с даты подписания\nакта установки оборудования"))));
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.termsList()).isEmpty();
        assertThat(d.termsTable()).hasSize(2);
        assertThat(d.termsTable().get(1).valueLines()).containsExactly("37 месяцев с даты подписания", "акта установки оборудования");
    }

    @Test
    void letterheadNumberLineAndRecipient() {
        ClientOffer o = ClientOfferTestData.newOffer(443);
        o.setRecipient("Главному врачу\nГКП на ПХВ «Областная больница»\n");
        KpDocument d = KpFixtures.document(o, KpFixtures.profileKz());
        assertThat(d.letterhead().left()).containsExactly("Жауапкершілігі", "шектеулі серіктестігі");
        assertThat(d.letterhead().right()).containsExactly("Товарищество", "с ограниченной ответственностью");
        assertThat(d.letterhead().lines()).containsExactly(
                "РНН 271 800 059 535 БИН 121 040 000 303",
                "Республика Казахстан, 090000, Западно-Казахстанская область,",
                "город Уральск, ул.Мухита 121-21",
                "KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)",
                "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA",
                "Тел. 87770752770, электронный адрес: west-med@mail.ru");
        assertThat(d.letterhead().brandText()).isEqualTo("\"West-Med\"");
        assertThat(d.numberLine()).isEqualTo("Исх. № 443 от 14.09.2026 г.");
        assertThat(d.recipientLines()).containsExactly("Главному врачу", "ГКП на ПХВ «Областная больница»");
        assertThat(d.title()).isEqualTo("КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ");
    }

    @Test
    void signoffStampOnlyWhenRequestedAndSignatureOnlyForDirector() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(new byte[]{1});
        p.setSignaturePng(new byte[]{2});
        ClientOffer o = ClientOfferTestData.newOffer(1);

        KpDocument.Signoff s = KpFixtures.document(o, p).signoff();
        assertThat(s.director()).isTrue();
        assertThat(s.titleLine()).isEqualTo("Директор ТОО «West-Med»");
        assertThat(s.nameLine()).isEqualTo("Ширяев И. В.");
        assertThat(s.stampPng()).isNull();
        assertThat(s.signaturePng()).isNull();
        assertThat(s.contacts()).isNull();

        o.setWithStamp(true);
        o.setSignoffContacts(true);
        s = KpFixtures.document(o, p).signoff();
        assertThat(s.stampPng()).containsExactly(1);
        assertThat(s.signaturePng()).containsExactly(2);
        assertThat(s.contacts()).isEqualTo("моб: 87770752770 (Казахстан), e-mail: west-med@mail.ru");
        assertThat(s.stampSizeMm()).isEqualTo(40);

        o.setSignoff(OfferSignoff.COMPANY);
        s = KpFixtures.document(o, p).signoff();
        assertThat(s.titleLine()).isEqualTo("ТОО «West-Med»");
        assertThat(s.nameLine()).isNull();
        assertThat(s.signaturePng()).isNull();
        assertThat(s.stampPng()).containsExactly(1);
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.KpDocumentBuilderTest'`
Expected: FAIL — компиляция: `cannot find symbol … KpDocument`.

- [ ] **Step 2: Строки реквизитов**

`src/main/java/com/vladoose/nir/service/CompanyLines.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.CompanyProfile;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Строки реквизитов под логотипом бланка (спека §6.3) — одни и те же в КП, PDF заявки и Excel рентабельности:
 * идентификаторы; адрес; счета; «Банк: … БИК: …»; «Тел. …, электронный адрес: …». Пустое не печатается.
 */
public final class CompanyLines {

    private CompanyLines() {}

    public static List<String> of(CompanyProfile p) {
        List<String> lines = new ArrayList<>();
        add(lines, p.getIdsLine());
        lines.addAll(split(p.getAddress()));
        lines.addAll(split(p.getAccounts()));
        add(lines, join(" ", prefixed("Банк: ", p.getBankName()), prefixed("БИК: ", p.getBik())));
        add(lines, join(", ", prefixed("Тел. ", p.getPhone()), prefixed("электронный адрес: ", p.getEmail())));
        return lines;
    }

    /** Непустые строки текста без пробелов по краям. */
    public static List<String> split(String text) {
        if (text == null) return List.of();
        List<String> out = new ArrayList<>();
        for (String line : text.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    private static void add(List<String> lines, String s) {
        if (s != null && !s.isBlank()) lines.add(s.trim());
    }

    private static String prefixed(String prefix, String value) {
        return value == null || value.isBlank() ? null : prefix + value.trim();
    }

    private static String join(String separator, String... parts) {
        String joined = Arrays.stream(parts).filter(Objects::nonNull).collect(Collectors.joining(separator));
        return joined.isEmpty() ? null : joined;
    }
}
```

- [ ] **Step 3: Модель и сборщик**

`src/main/java/com/vladoose/nir/service/document/KpDocument.java`:

```java
package com.vladoose.nir.service.document;

import com.vladoose.nir.service.offer.ColumnAlign;

import java.util.List;

/**
 * Неизменяемая модель документа КП (спека §6.4): всё уже посчитано и отформатировано — рендереры PDF и Word
 * ничего не считают и не форматируют сами, поэтому документы не расходятся по содержанию.
 */
public record KpDocument(
        boolean landscape,
        Letterhead letterhead,
        String numberLine,
        List<String> recipientLines,
        String title,
        String subject,
        String intro,
        List<Term> termsTable,
        List<Column> columns,
        List<Row> rows,
        List<String> totalLines,
        String amountInWords,
        List<String> termsList,
        Signoff signoff) {

    public record Letterhead(List<String> left, List<String> right, byte[] logoPng, String brandText, List<String> lines) {}

    public record Term(String label, List<String> valueLines) {}

    /** percent — доля ширины таблицы, сумма по колонкам = 100. */
    public record Column(String key, String label, ColumnAlign align, int percent) {}

    public enum RowKind { ITEM, SECTION, INCLUDED }

    /** cells — ячейки колонок [0, spanFrom); если spanFrom < колонок — затем одна объединённая ячейка spanLines. */
    public record Row(RowKind kind, List<List<String>> cells, int spanFrom, List<String> spanLines) {}

    public record Signoff(boolean director, String titleLine, String nameLine, String contacts,
                          byte[] signaturePng, byte[] stampPng, int stampSizeMm) {}
}
```

`src/main/java/com/vladoose/nir/service/document/KpDocumentBuilder.java`:

```java
package com.vladoose.nir.service.document;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.CompanyLines;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferColumnKey;
import com.vladoose.nir.service.offer.VatLine;
import com.vladoose.nir.util.AmountInWords;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** КП + реквизиты рынка + расчёт → KpDocument (спека §6.1–§6.3). */
@Component
public class KpDocumentBuilder {

    static final String DEFAULT_TITLE = "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ";
    static final String INCLUDED_DEFAULT = "Включено в стоимость";
    private static final Set<OfferRegistrationStatus> PRINTED_REGISTRATION =
            Set.of(OfferRegistrationStatus.CONFIRMED, OfferRegistrationStatus.NOT_REQUIRED, OfferRegistrationStatus.MANUAL);

    public KpDocument build(ClientOffer offer, CompanyProfile profile, OfferCalculation calc) {
        String currency = profile.getMarket().currencyCode();
        List<KpDocument.Column> columns = columns(offer.getTableColumns(), offer.isVatEnabled());
        return new KpDocument(
                offer.isLandscape(),
                new KpDocument.Letterhead(CompanyLines.split(profile.getHeaderLeft()), CompanyLines.split(profile.getHeaderRight()),
                        profile.getLogoPng(), blankToNull(profile.getBrandText()), CompanyLines.of(profile)),
                "Исх. № " + offer.getNumber() + " от " + DocFormat.date(offer.getOfferDate()) + " г.",
                CompanyLines.split(offer.getRecipient()),
                blankToNull(offer.getTitle()) == null ? DEFAULT_TITLE : offer.getTitle().trim(),
                blankToNull(offer.getSubject()),
                blankToNull(offer.getIntro()),
                offer.getTermsStyle() == TermsStyle.TABLE ? termsTable(offer.getTerms()) : List.of(),
                columns,
                rows(offer, calc, columns),
                totalLines(offer, calc, currency),
                offer.isShowAmountInWords() ? "Сумма прописью: " + AmountInWords.of(calc.totals().sum(), currency) : null,
                offer.getTermsStyle() == TermsStyle.LIST ? termsList(offer.getTerms()) : List.of(),
                signoff(offer, profile));
    }

    static List<KpDocument.Column> columns(List<OfferColumn> config, boolean vat) {
        List<OfferColumnKey> keys = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        for (OfferColumn c : config == null ? List.<OfferColumn>of() : config) {
            OfferColumnKey key;
            try {
                key = OfferColumnKey.parse(c.getKey());
            } catch (BadRequestException e) {
                continue;   // неизвестный ключ (старые данные) — не печатаем
            }
            if (keys.contains(key) || (!vat && key.vatOnly())) continue;
            keys.add(key);
            labels.add(blankToNull(c.getLabel()) == null ? key.defaultLabel(vat) : c.getLabel().trim());
        }
        if (!keys.contains(OfferColumnKey.NAME)) {
            keys.add(0, OfferColumnKey.NAME);
            labels.add(0, OfferColumnKey.NAME.defaultLabel(vat));
        }
        int othersRaw = keys.stream().filter(k -> k != OfferColumnKey.NAME).mapToInt(OfferColumnKey::weight).sum();
        double scale = othersRaw > 75 ? 75.0 / othersRaw : 1.0;   // наименованию — не меньше четверти ширины
        int[] percent = new int[keys.size()];
        int others = 0;
        for (int i = 0; i < keys.size(); i++) {
            if (keys.get(i) == OfferColumnKey.NAME) continue;
            percent[i] = (int) Math.round(keys.get(i).weight() * scale);
            others += percent[i];
        }
        List<KpDocument.Column> result = new ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            OfferColumnKey k = keys.get(i);
            int p = k == OfferColumnKey.NAME ? 100 - others : percent[i];
            result.add(new KpDocument.Column(k.name(), labels.get(i), k.align(), p));
        }
        return result;
    }

    private static List<KpDocument.Row> rows(ClientOffer offer, OfferCalculation calc, List<KpDocument.Column> columns) {
        Set<String> shown = new HashSet<>();
        columns.forEach(c -> shown.add(c.key()));
        List<KpDocument.Row> rows = new ArrayList<>();
        int number = 0;
        for (int i = 0; i < offer.getItems().size(); i++) {
            ClientOfferItem it = offer.getItems().get(i);
            switch (it.getKind()) {
                case SECTION -> rows.add(new KpDocument.Row(KpDocument.RowKind.SECTION, List.of(), 0, lines(it.getName())));
                case INCLUDED -> rows.add(included(it, columns));
                case ITEM -> {
                    number++;
                    List<List<String>> cells = new ArrayList<>();
                    for (KpDocument.Column c : columns) {
                        cells.add(cell(OfferColumnKey.parse(c.key()), it, calc.items().get(i), number, shown, offer.isDetailsInName()));
                    }
                    rows.add(new KpDocument.Row(KpDocument.RowKind.ITEM, cells, columns.size(), List.of()));
                }
            }
        }
        return rows;
    }

    private static KpDocument.Row included(ClientOfferItem it, List<KpDocument.Column> columns) {
        List<String> note = lines(blankToNull(it.getNote()) == null ? INCLUDED_DEFAULT : it.getNote());
        int nameIdx = -1;
        for (int i = 0; i < columns.size(); i++) if (columns.get(i).key().equals(OfferColumnKey.NAME.name())) nameIdx = i;
        List<List<String>> cells = new ArrayList<>();
        for (int i = 0; i < nameIdx; i++) cells.add(List.of());
        List<String> name = new ArrayList<>(lines(it.getName()));
        if (nameIdx == columns.size() - 1) {   // наименование — последняя колонка: «включено» второй строкой в ней же
            name.addAll(note);
            cells.add(name);
            return new KpDocument.Row(KpDocument.RowKind.INCLUDED, cells, columns.size(), List.of());
        }
        cells.add(name);
        return new KpDocument.Row(KpDocument.RowKind.INCLUDED, cells, nameIdx + 1, note);
    }

    private static List<String> cell(OfferColumnKey key, ClientOfferItem it, ItemCalc c, int number,
                                     Set<String> shown, boolean details) {
        return switch (key) {
            case NUM -> List.of(String.valueOf(number));
            case NAME -> nameLines(it, shown, details);
            case MODEL -> lines(it.getModel());
            case MANUFACTURER -> lines(it.getManufacturer());
            case COUNTRY -> lines(it.getCountry());
            case UNIT -> lines(it.getUnit());
            case QTY -> List.of(DocFormat.qty(it.getQuantity()));
            case PRICE -> money(c.price());
            case PRICE_NET -> money(c.priceNet());
            case VAT_RATE -> List.of(DocFormat.rate(c.effectiveVatRate()));
            case VAT_SUM -> money(c.vatSum());
            case SUM_NET -> money(c.sumNet());
            case SUM -> money(c.sum());
            case REGISTRATION -> PRINTED_REGISTRATION.contains(it.getRegistrationStatus()) ? lines(it.getRegistrationText()) : List.of();
            case NOTE -> lines(it.getNote());
        };
    }

    /** Модель — через пробел после наименования, производитель и страна — второй строкой, если у них нет своих колонок. */
    private static List<String> nameLines(ClientOfferItem it, Set<String> shown, boolean details) {
        String name = it.getName() == null ? "" : it.getName().trim();
        if (details && !shown.contains(OfferColumnKey.MODEL.name()) && blankToNull(it.getModel()) != null) {
            name = name + " " + it.getModel().trim();
        }
        List<String> out = new ArrayList<>(lines(name));
        if (details) {
            boolean producer = !shown.contains(OfferColumnKey.MANUFACTURER.name()) && blankToNull(it.getManufacturer()) != null;
            boolean country = !shown.contains(OfferColumnKey.COUNTRY.name()) && blankToNull(it.getCountry()) != null;
            if (producer) out.add("Производитель: " + it.getManufacturer().trim() + (country ? ", " + it.getCountry().trim() : ""));
            else if (country) out.add("Страна: " + it.getCountry().trim());
        }
        return out;
    }

    private static List<String> totalLines(ClientOffer offer, OfferCalculation calc, String currency) {
        String cur = DocFormat.currencyShort(currency);
        List<String> lines = new ArrayList<>();
        lines.add("Итого: " + DocFormat.money(calc.totals().sum()) + " " + cur);
        if (!offer.isVatEnabled()) {
            lines.add("Без НДС");
        } else if (offer.isShowVatBreakdown()) {
            for (VatLine v : calc.totals().vat()) {
                lines.add("в т.ч. НДС " + DocFormat.rate(v.rate()) + ": " + DocFormat.money(v.amount()) + " " + cur);
            }
        }
        return lines;
    }

    private static List<KpDocument.Term> termsTable(List<OfferTerm> terms) {
        List<KpDocument.Term> out = new ArrayList<>();
        for (OfferTerm t : terms) {
            if (blankToNull(t.getValue()) == null) continue;
            out.add(new KpDocument.Term(t.getLabel() == null ? "" : t.getLabel().trim(), lines(t.getValue())));
        }
        return out;
    }

    /** «1. Цены действительны в течение 10 дней;» … последний — с точкой, как в КП отца от 24.09. */
    private static List<String> termsList(List<OfferTerm> terms) {
        List<OfferTerm> filled = terms.stream().filter(t -> blankToNull(t.getValue()) != null).toList();
        List<String> out = new ArrayList<>();
        for (int i = 0; i < filled.size(); i++) {
            OfferTerm t = filled.get(i);
            String value = String.join(" ", lines(t.getValue())).replaceAll("[;.]+$", "");
            String text = blankToNull(t.getLabel()) == null ? value : t.getLabel().trim() + ": " + value;
            out.add((i + 1) + ". " + text + (i < filled.size() - 1 ? ";" : "."));
        }
        return out;
    }

    private static KpDocument.Signoff signoff(ClientOffer offer, CompanyProfile profile) {
        boolean director = offer.getSignoff() == OfferSignoff.DIRECTOR;
        String shortName = profile.getShortName();
        String title = director
                ? (blankToNull(profile.getDirectorTitle()) == null ? shortName : profile.getDirectorTitle().trim() + " " + shortName)
                : shortName;
        return new KpDocument.Signoff(
                director,
                title,
                director ? surnameWithInitials(profile.getDirectorName()) : null,
                offer.isSignoffContacts() ? blankToNull(profile.getSignoffContacts()) : null,
                offer.isWithStamp() && director ? profile.getSignaturePng() : null,
                offer.isWithStamp() ? profile.getStampPng() : null,
                profile.getStampSizeMm());
    }

    /** «Ширяев Илья Викторович» → «Ширяев И. В.». */
    static String surnameWithInitials(String fullName) {
        if (blankToNull(fullName) == null) return null;
        String[] parts = fullName.trim().split("\\s+");
        StringBuilder sb = new StringBuilder(parts[0]);
        for (int i = 1; i < parts.length && i <= 2; i++) sb.append(' ').append(parts[i].charAt(0)).append('.');
        return sb.toString();
    }

    private static List<String> money(BigDecimal value) {
        return List.of(value == null ? "—" : DocFormat.money(value));
    }

    private static List<String> lines(String text) {
        return CompanyLines.split(text);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
```

- [ ] **Step 4: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/CompanyLines.java src/main/java/com/vladoose/nir/service/document src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): модель документа КП — бланк, колонки с подписями, строки трёх видов, итоги, условия, подпись

Одна неизменяемая KpDocument на оба рендерера (PDF и Word): всё уже отформатировано. Регистрация печатается
только подтверждённая/«не подлежит»/вбитая руками; печать и подпись — только с галочкой.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 6: Вёрстка документа — Thymeleaf-шаблон и PDF целиком

**Files:**
- Create: `src/main/resources/templates/kp/offer.html`
- Create: `src/main/java/com/vladoose/nir/service/document/KpHtmlRenderer.java`
- Modify: `src/test/java/com/vladoose/nir/clientoffer/KpFixtures.java` (движок шаблонов и `pdf(...)` для тестов)
- Test: `src/test/java/com/vladoose/nir/clientoffer/KpPdfDocumentTest.java`

**Interfaces:**
- Consumes: Task 1 (`KpPdfRenderer`, `KpFonts`, `KpTestSupport`), Task 5 (`KpDocument`, `KpFixtures`).
- Produces: `KpHtmlRenderer(ITemplateEngine engine)`, `public String render(KpDocument doc)`; константы `STAMP_LEFT_MM = 22`, `STAMP_TOP_MM = -4` (сдвиг печати от начала блока подписи — подбирается на живой проверке, Task 15); тестовые `KpFixtures.templateEngine()` и `KpFixtures.pdf(ClientOffer, CompanyProfile)`.

**Почему так:** доступ к компонентам record в шаблоне — **вызовами методов** (`doc.columns()`, `c.label()`): так SpEL работает независимо от того, умеет ли он свойства record. CSS — только 2.1 (у openhtmltopdf нет flex/grid): таблицы, `float`, абсолютное позиционирование печати относительно блока подписи (`position: relative`). Правило `@page` (размер, ориентация, поля) приходит из Java отдельным `<style>` — текст константы, экранирование `th:text` его не портит.

- [ ] **Step 1: Падающий тест**

В `KpFixtures.java` добавить импорты и два метода:

```java
import com.vladoose.nir.service.document.KpFonts;
import com.vladoose.nir.service.document.KpHtmlRenderer;
import com.vladoose.nir.service.document.KpPdfRenderer;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;
```

```java
    /** Тот же шаблон из classpath, что и в приложении, — без Spring-контекста. */
    static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    static byte[] pdf(ClientOffer offer, CompanyProfile profile) {
        String html = new KpHtmlRenderer(templateEngine()).render(document(offer, profile));
        return new KpPdfRenderer(new KpFonts()).render(html);
    }
```

`src/test/java/com/vladoose/nir/clientoffer/KpPdfDocumentTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** PDF КП целиком (спека §6): бланк, таблица, итоги, пропись, условия, подпись; экранирование; печать; ориентация. */
class KpPdfDocumentTest {

    @Test
    void fullDocumentLooksLikeTheSeptemberOffer() {
        String text = KpTestSupport.text(KpFixtures.pdf(KpFixtures.offer2409(), KpFixtures.profileKz()));
        assertThat(text).contains(
                "Жауапкершілігі", "Товарищество", "\"West-Med\"",
                "РНН 271 800 059 535 БИН 121 040 000 303",
                "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA",
                "Тел. 87770752770, электронный адрес: west-med@mail.ru",
                "Исх. № 443 от 14.09.2026 г.", "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ",
                "ТОО «West-Med» предлагает поставку медицинской продукции по следующим ценам:",
                "Цена за ед., тг", "Регистрация в РК",
                "Пульсоксиметр QMP – PO70 взрослый", "105 600,00", "316 800,00", "Не подлежит регистрации",
                "Итого: 748 060,00 тг", "в т.ч. НДС 5%: 17 100,00 тг", "в т.ч. НДС 16%: 53 649,65 тг",
                "Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
                "1. Цены действительны в течение 10 дней;",
                "5. Срок поставки всего товара: 30 рабочих дней после поступления предоплаты.",
                "С уважением,", "Директор ТОО «West-Med»", "Ширяев И. В.");
    }

    /** Текст оператора — только текст: разметка не исполняется и никуда не ходит (спека §6.4, §11). */
    @Test
    void operatorTextIsEscapedAndNothingIsFetched() throws Exception {
        try (KpTestSupport.TrapServer trap = new KpTestSupport.TrapServer()) {
            ClientOffer o = ClientOfferTestData.newOffer(1);
            o.getItems().add(ClientOfferTestData.item(o, 1,
                    "<b>Жирный</b> & <img src=\"" + trap.url("/evil.png") + "\"/>", "1000", "5"));
            o.setSubject("<script>alert(1)</script>");
            String text = KpTestSupport.text(KpFixtures.pdf(o, KpFixtures.profileKz()));
            assertThat(text).contains("<b>Жирный</b> &", "<script>alert(1)</script>");
            assertThat(trap.hits()).isZero();
        }
    }

    @Test
    void stampAndSignatureOnlyWithCheckbox() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        ClientOffer o = KpFixtures.offer2409();
        assertThat(KpTestSupport.imageCount(KpFixtures.pdf(o, p))).isZero();
        o.setWithStamp(true);
        assertThat(KpTestSupport.imageCount(KpFixtures.pdf(o, p))).isEqualTo(2);
    }

    @Test
    void logoReplacesBrandText() {
        CompanyProfile p = KpFixtures.profileKz();
        p.setLogoPng(KpTestSupport.circlePng());
        byte[] pdf = KpFixtures.pdf(KpFixtures.offer2409(), p);
        assertThat(KpTestSupport.imageCount(pdf)).isEqualTo(1);
        assertThat(KpTestSupport.text(pdf)).doesNotContain("\"West-Med\"");
    }

    @Test
    void letterheadOnlyOnFirstPageAndHeaderRepeats() {
        ClientOffer o = KpFixtures.offer2409();
        for (int i = 0; i < 70; i++) {
            KpFixtures.line(o, "Позиция длинного КП № " + i, 1, "1000.00", "5", OfferRegistrationStatus.UNCHECKED, null);
        }
        List<String> pages = KpTestSupport.pageTexts(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(pages.size()).isGreaterThan(1);
        assertThat(pages.get(0)).contains("РНН 271 800 059 535");
        assertThat(pages.get(1)).doesNotContain("РНН 271 800 059 535").contains("Цена за ед., тг");
    }

    @Test
    void landscapePageIsWide() {
        ClientOffer o = KpFixtures.offer2409();
        o.setLandscape(true);
        PDRectangle size = KpTestSupport.firstPageSize(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(size.getWidth()).isGreaterThan(size.getHeight());
    }

    @Test
    void termsAsTableAndRenamedColumn() {
        ClientOffer o = KpFixtures.offer2409();
        o.setTermsStyle(TermsStyle.TABLE);
        o.setTerms(new ArrayList<>(List.of(new OfferTerm("Условия поставки", "DDP Заказчик"))));
        o.getTableColumns().get(1).setLabel("Наименование медицинской техники (по регистрационному удостоверению)");
        String text = KpTestSupport.text(KpFixtures.pdf(o, KpFixtures.profileKz()));
        assertThat(text).contains("Условия поставки", "DDP Заказчик", "по регистрационному удостоверению")
                .doesNotContain("1. Условия поставки");
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.KpPdfDocumentTest'`
Expected: FAIL — компиляция: `cannot find symbol … KpHtmlRenderer`.

- [ ] **Step 2: Шаблон**

`src/main/resources/templates/kp/offer.html`:

```html
<!DOCTYPE html>
<html xmlns:th="http://www.thymeleaf.org" lang="ru">
<head>
  <meta charset="utf-8"/>
  <title th:text="${doc.numberLine()}">КП</title>
  <style th:text="${pageCss}">@page { size: A4; }</style>
  <style>
    /* Документ КП (спека client-kp-constructor §6). Только CSS 2.1 — openhtmltopdf не знает flex/grid.
       Цвета — печатные (#000): это бумага, а не интерфейс АИС. */
    body { font-family: 'Liberation Serif'; font-size: 11pt; color: #000; }
    table { border-collapse: collapse; }
    .lh-top { width: 100%; }
    .lh-top td { font-weight: bold; font-size: 11pt; vertical-align: top; padding: 0; }
    .lh-right { text-align: right; }
    .lh-logo { text-align: center; margin: 1mm 0; }
    .lh-logo img { max-width: 100%; max-height: 20mm; }
    .lh-brand { text-align: center; font-size: 30pt; font-weight: bold; margin: 1mm 0; }
    .lh-lines { text-align: center; font-weight: bold; font-size: 12pt; line-height: 1.25; }
    .lh-rule { border-bottom: 2.5pt solid #000; margin: 2mm 0 4mm 0; }
    .meta { width: 100%; margin-bottom: 3mm; }
    .meta td { vertical-align: top; padding: 0; }
    .meta-recipient { text-align: right; width: 50%; }
    .title { text-align: center; font-weight: bold; font-size: 13pt; margin: 2mm 0 1mm 0; }
    .subject { text-align: center; font-weight: bold; font-size: 12pt; margin-bottom: 2mm; }
    .intro { margin: 2mm 0; }
    .terms-table { width: 100%; margin: 2mm 0 4mm 0; }
    .terms-table td { border: 0.5pt solid #000; padding: 1.2mm 1.5mm; vertical-align: top; font-size: 10.5pt; }
    .terms-table .term-label { font-weight: bold; width: 45%; }
    .items { width: 100%; table-layout: fixed; -fs-table-paginate: paginate; margin-top: 2mm; }
    .items thead { display: table-header-group; }
    .items th, .items td { border: 0.5pt solid #000; padding: 1.2mm 1.5mm; font-size: 10pt; vertical-align: middle; word-wrap: break-word; }
    .items th { font-weight: bold; text-align: center; }
    .items tr { page-break-inside: avoid; }
    .a-left { text-align: left; }
    .a-center { text-align: center; }
    .a-right { text-align: right; }
    .row-section td { font-weight: bold; }
    .span-cell { text-align: center; }
    .row-section .span-cell { text-align: left; }
    .totals { margin-top: 3mm; text-align: right; }
    .totals .total-main { font-weight: bold; font-size: 12pt; }
    .words { margin-top: 2mm; }
    .terms-list { margin-top: 4mm; }
    .terms-list div { margin-bottom: 1mm; }
    .signoff { margin-top: 10mm; position: relative; page-break-inside: avoid; }
    .sign-table { width: 100%; margin-top: 1mm; }
    .sign-table td { vertical-align: bottom; padding: 0; }
    .sign-line { width: 45mm; height: 15mm; border-bottom: 0.7pt solid #000; text-align: center; }
    .sign-line img { max-height: 15mm; max-width: 44mm; }
    .sign-name { padding-left: 3mm; }
    .contacts { margin-top: 2mm; font-size: 10pt; }
    .stamp { position: absolute; }
  </style>
</head>
<body>
  <table class="lh-top" th:if="${!doc.letterhead().left().isEmpty() or !doc.letterhead().right().isEmpty()}">
    <tr>
      <td><th:block th:each="l, s : ${doc.letterhead().left()}"><span th:text="${l}">слева</span><br th:unless="${s.last}"/></th:block></td>
      <td class="lh-right"><th:block th:each="l, s : ${doc.letterhead().right()}"><span th:text="${l}">справа</span><br th:unless="${s.last}"/></th:block></td>
    </tr>
  </table>
  <div class="lh-logo" th:if="${logo != null}"><img th:src="${logo}" alt=""/></div>
  <div class="lh-brand" th:if="${logo == null and doc.letterhead().brandText() != null}" th:text="${doc.letterhead().brandText()}">Бренд</div>
  <div class="lh-lines" th:if="${!doc.letterhead().lines().isEmpty()}"><th:block th:each="l, s : ${doc.letterhead().lines()}"><span th:text="${l}">строка</span><br th:unless="${s.last}"/></th:block></div>
  <div class="lh-rule"></div>

  <table class="meta">
    <tr>
      <td th:text="${doc.numberLine()}">Исх. №</td>
      <td class="meta-recipient"><th:block th:each="l, s : ${doc.recipientLines()}"><span th:text="${l}">кому</span><br th:unless="${s.last}"/></th:block></td>
    </tr>
  </table>

  <div class="title" th:text="${doc.title()}">КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ</div>
  <div class="subject" th:if="${doc.subject() != null}" th:text="${doc.subject()}">предмет</div>
  <p class="intro" th:if="${doc.intro() != null}" th:text="${doc.intro()}">вводная</p>

  <table class="terms-table" th:if="${!doc.termsTable().isEmpty()}">
    <tr th:each="t : ${doc.termsTable()}">
      <td class="term-label" th:text="${t.label()}">условие</td>
      <td><th:block th:each="l, s : ${t.valueLines()}"><span th:text="${l}">значение</span><br th:unless="${s.last}"/></th:block></td>
    </tr>
  </table>

  <table class="items">
    <colgroup><col th:each="c : ${doc.columns()}" th:style="${'width: ' + c.percent() + '%'}"/></colgroup>
    <thead>
      <tr><th th:each="c : ${doc.columns()}" th:text="${c.label()}">колонка</th></tr>
    </thead>
    <tbody>
      <tr th:each="r : ${doc.rows()}" th:class="${r.kind().name() == 'SECTION' ? 'row-section' : (r.kind().name() == 'INCLUDED' ? 'row-included' : 'row-item')}">
        <td th:each="cell, cs : ${r.cells()}" th:class="${'a-' + #strings.toLowerCase(doc.columns()[cs.index].align().name())}"><th:block th:each="l, s : ${cell}"><span th:text="${l}">ячейка</span><br th:unless="${s.last}"/></th:block></td>
        <td class="span-cell" th:if="${r.spanFrom() < doc.columns().size()}" th:colspan="${doc.columns().size() - r.spanFrom()}"><th:block th:each="l, s : ${r.spanLines()}"><span th:text="${l}">объединено</span><br th:unless="${s.last}"/></th:block></td>
      </tr>
    </tbody>
  </table>

  <div class="totals">
    <div th:each="t, s : ${doc.totalLines()}" th:class="${s.first ? 'total-main' : 'total-line'}" th:text="${t}">Итого</div>
  </div>
  <div class="words" th:if="${doc.amountInWords() != null}" th:text="${doc.amountInWords()}">прописью</div>

  <div class="terms-list" th:if="${!doc.termsList().isEmpty()}">
    <div th:each="t : ${doc.termsList()}" th:text="${t}">1. условие</div>
  </div>

  <div class="signoff">
    <div>С уважением,</div>
    <table class="sign-table" th:if="${doc.signoff().director()}">
      <tr>
        <td th:text="${doc.signoff().titleLine()}">Директор</td>
        <td class="sign-line"><img th:if="${signature != null}" th:src="${signature}" alt=""/></td>
        <td class="sign-name" th:text="${doc.signoff().nameLine()}">Фамилия И. О.</td>
      </tr>
    </table>
    <div th:unless="${doc.signoff().director()}" th:text="${doc.signoff().titleLine()}">ТОО</div>
    <div class="contacts" th:if="${doc.signoff().contacts() != null}" th:text="${doc.signoff().contacts()}">контакты</div>
    <img class="stamp" th:if="${stamp != null}" th:src="${stamp}" th:style="${stampStyle}" alt=""/>
  </div>
</body>
</html>
```

- [ ] **Step 3: Рендерер HTML**

`src/main/java/com/vladoose/nir/service/document/KpHtmlRenderer.java`:

```java
package com.vladoose.nir.service.document;

import org.springframework.stereotype.Component;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Base64;
import java.util.Locale;

/**
 * KpDocument → HTML по шаблону templates/kp/offer.html. Весь текст — через th:text (экранируется); картинки —
 * data:-URI (единственное, что разрешено грузить рендереру PDF). Положение печати — от начала блока подписи.
 */
@Component
public class KpHtmlRenderer {

    /** Сдвиг печати от левого верхнего угла блока «С уважением…», мм: центр печати ложится на должность и линию. */
    static final int STAMP_LEFT_MM = 22;
    static final int STAMP_TOP_MM = -4;

    private final ITemplateEngine engine;

    public KpHtmlRenderer(ITemplateEngine engine) {
        this.engine = engine;
    }

    public String render(KpDocument doc) {
        Context ctx = new Context(Locale.forLanguageTag("ru"));
        ctx.setVariable("doc", doc);
        ctx.setVariable("pageCss", pageCss(doc.landscape()));
        ctx.setVariable("logo", dataUri(doc.letterhead().logoPng()));
        ctx.setVariable("signature", dataUri(doc.signoff().signaturePng()));
        ctx.setVariable("stamp", dataUri(doc.signoff().stampPng()));
        ctx.setVariable("stampStyle", "width: " + doc.signoff().stampSizeMm() + "mm; left: " + STAMP_LEFT_MM
                + "mm; top: " + STAMP_TOP_MM + "mm;");
        return engine.process("kp/offer", ctx);
    }

    static String pageCss(boolean landscape) {
        return landscape
                ? "@page { size: A4 landscape; margin: 12mm 15mm 12mm 15mm; }"
                : "@page { size: A4; margin: 15mm 12mm 15mm 20mm; }";
    }

    static String dataUri(byte[] png) {
        return png == null || png.length == 0 ? null : "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
    }
}
```

- [ ] **Step 4: Тесты зелёные + посмотреть глазами**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

Глазами (тест «по запросу», в репозиторий не коммитится): в scratchpad сохранить PDF и PNG первой страницы через `jshell` или временный тест `@Disabled`-с-выводом; проще — дождаться Task 8 и открыть `/api/client-offers/{id}/pdf`. На этом шаге достаточно зелёных тестов.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/resources/templates/kp/offer.html src/main/java/com/vladoose/nir/service/document/KpHtmlRenderer.java src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): вёрстка КП — бланк West-Med, таблица со сквозной шапкой, итоги, пропись, условия, подпись и печать

Thymeleaf-шаблон на CSS 2.1; всё — th:text, картинки — data:. Тесты читают PDF обратно: содержимое как в КП
отца от 24.09, экранирование и ноль внешних запросов, печать только с галочкой, бланк только на 1-й странице.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 7: Word (.docx) из той же модели

**Files:**
- Create: `src/main/java/com/vladoose/nir/service/document/KpDocxRenderer.java`
- Test: `src/test/java/com/vladoose/nir/clientoffer/KpDocxRendererTest.java`

**Interfaces:**
- Consumes: Task 5 (`KpDocument`, `KpFixtures`), Task 1 (`KpTestSupport.circlePng`).
- Produces: `KpDocxRenderer.render(KpDocument)` → `byte[]` (.docx).

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/clientoffer/KpDocxRendererTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.document.KpDocxRenderer;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Word читается обратно через POI: колонки, сквозная шапка, явные ширины, объединения, печать (спека §6.4). */
class KpDocxRendererTest {

    private final KpDocxRenderer renderer = new KpDocxRenderer();

    private byte[] docx(ClientOffer o, CompanyProfile p) {
        return renderer.render(KpFixtures.document(o, p));
    }

    private static XWPFDocument open(byte[] docx) throws IOException {
        return new XWPFDocument(new ByteArrayInputStream(docx));
    }

    private static XWPFTable itemsTable(XWPFDocument d) {
        return d.getTables().stream()
                .filter(t -> t.getRow(0).getCell(0).getText().equals("№"))
                .findFirst().orElseThrow();
    }

    private static int anchors(XWPFDocument d) {
        int n = 0;
        for (XWPFParagraph p : d.getParagraphs()) {
            for (XWPFRun r : p.getRuns()) {
                for (CTDrawing drawing : r.getCTR().getDrawingList()) n += drawing.sizeOfAnchorArray();
            }
        }
        return n;
    }

    @Test
    void itemsTableHasColumnsInOrderRepeatingHeaderAndFixedWidths() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()))) {
            XWPFTable t = itemsTable(d);
            List<String> header = t.getRow(0).getTableCells().stream().map(XWPFTableCell::getText).toList();
            assertThat(header).containsExactly("№", "Наименование", "Ед. изм.", "Кол-во", "Цена за ед., тг", "НДС",
                    "Общая сумма, тг", "Регистрация в РК");
            assertThat(t.getRow(0).isRepeatHeader()).isTrue();
            CTTblPr pr = t.getCTTbl().getTblPr();
            assertThat(pr.getTblW().getType()).isEqualTo(STTblWidth.DXA);
            assertThat(pr.getTblLayout().getType()).isEqualTo(STTblLayoutType.FIXED);
            long sum = 0;
            for (XWPFTableCell c : t.getRow(1).getTableCells()) sum += ((BigInteger) c.getCTTc().getTcPr().getTcW().getW()).longValue();
            assertThat(sum).isEqualTo(((BigInteger) pr.getTblW().getW()).longValue());
            assertThat(t.getRow(1).getCell(1).getText()).isEqualTo("Пульсоксиметр QMP – PO70 взрослый");
            assertThat(t.getRow(1).getCell(4).getText().replace(' ', ' ')).isEqualTo("105 600,00");
        }
    }

    @Test
    void sectionAndIncludedRowsAreMerged() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        ClientOfferItem section = ClientOfferTestData.item(o, 0, "Основные комплектующие:", null, null);
        section.setKind(ClientOfferItemKind.SECTION);
        o.getItems().add(0, section);
        ClientOfferItem included = ClientOfferTestData.item(o, 99, "Гарантийное сервисное обслуживание 37 месяцев", null, null);
        included.setKind(ClientOfferItemKind.INCLUDED);
        o.getItems().add(included);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            XWPFTable t = itemsTable(d);
            XWPFTableRow sectionRow = t.getRow(1);
            assertThat(sectionRow.getTableCells()).hasSize(1);
            assertThat(sectionRow.getCell(0).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(8);
            assertThat(sectionRow.getCell(0).getText()).isEqualTo("Основные комплектующие:");
            XWPFTableRow last = t.getRow(t.getNumberOfRows() - 1);
            assertThat(last.getTableCells()).hasSize(3);          // №, наименование, «включено» на 6 колонок
            assertThat(last.getCell(2).getCTTc().getTcPr().getGridSpan().getVal().intValue()).isEqualTo(6);
            assertThat(last.getCell(2).getText()).isEqualTo("Включено в стоимость");
        }
    }

    @Test
    void letterheadTotalsWordsTermsAndSignoffArePresent() throws Exception {
        try (XWPFDocument d = open(docx(KpFixtures.offer2409(), KpFixtures.profileKz()));
             XWPFWordExtractor extractor = new XWPFWordExtractor(d)) {
            String text = extractor.getText().replace(' ', ' ');
            assertThat(text).contains("Жауапкершілігі", "РНН 271 800 059 535 БИН 121 040 000 303",
                    "Исх. № 443 от 14.09.2026 г.", "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ",
                    "Итого: 748 060,00 тг", "в т.ч. НДС 16%: 53 649,65 тг",
                    "Сумма прописью: Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
                    "1. Цены действительны в течение 10 дней;", "Директор ТОО «West-Med»", "Ширяев И. В.");
        }
    }

    @Test
    void stampIsFloatingPictureOnlyWithCheckbox() throws Exception {
        CompanyProfile p = KpFixtures.profileKz();
        p.setStampPng(KpTestSupport.circlePng());
        p.setSignaturePng(KpTestSupport.signaturePng());
        ClientOffer o = KpFixtures.offer2409();
        try (XWPFDocument d = open(docx(o, p))) {
            assertThat(anchors(d)).isZero();
            assertThat(d.getAllPictures()).isEmpty();
        }
        o.setWithStamp(true);
        try (XWPFDocument d = open(docx(o, p))) {
            assertThat(anchors(d)).isEqualTo(1);                 // печать «перед текстом»
            assertThat(d.getAllPictures()).hasSize(2);           // + подпись над линией
        }
    }

    @Test
    void landscapeSwapsPageSizeAndFontIsTimesNewRoman() throws Exception {
        ClientOffer o = KpFixtures.offer2409();
        o.setLandscape(true);
        try (XWPFDocument d = open(docx(o, KpFixtures.profileKz()))) {
            CTPageSz size = d.getDocument().getBody().getSectPr().getPgSz();
            assertThat(((BigInteger) size.getW()).intValue()).isGreaterThan(((BigInteger) size.getH()).intValue());
            assertThat(size.getOrient()).isEqualTo(STPageOrientation.LANDSCAPE);
            XWPFRun firstRun = d.getParagraphs().stream().flatMap(p -> p.getRuns().stream()).findFirst().orElseThrow();
            assertThat(firstRun.getFontFamily()).isEqualTo("Times New Roman");
        }
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.KpDocxRendererTest'`
Expected: FAIL — компиляция: `cannot find symbol … KpDocxRenderer`.

- [ ] **Step 2: Реализация**

`src/main/java/com/vladoose/nir/service/document/KpDocxRenderer.java`:

```java
package com.vladoose.nir.service.document;

import com.vladoose.nir.service.offer.ColumnAlign;
import org.apache.poi.openxml4j.exceptions.InvalidFormatException;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlException;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTAnchor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.*;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.util.List;

/**
 * KpDocument → Word (.docx) через Apache POI XWPF (спека §6.4): те же блоки и колонки, что у PDF. Ширины — только
 * явные: tblW в DXA + fixed + gridCol + tcW у каждой ячейки («100%» POI не работает — проверено 2026-10-02).
 * Печать — плавающая картинка «перед текстом» (wp:anchor + wrapNone), её можно сдвинуть в Word; подпись — в ячейке
 * над линией. Шрифт Times New Roman. ⚠ Quick Look на Mac плавающие картинки не рисует — смотреть только в Word.
 */
@Component
public class KpDocxRenderer {

    private static final String FONT = "Times New Roman";
    private static final int A4_SHORT = 11906;   // твипы
    private static final int A4_LONG = 16838;
    private static final int MARGIN_TOP = 850, MARGIN_BOTTOM = 850, MARGIN_LEFT = 1134, MARGIN_RIGHT = 680; // 15/15/20/12 мм
    private static final int LANDSCAPE_MARGIN = 850;   // 15 мм
    private static final long EMU_PER_MM = 36000L;
    private static final double TWIPS_PER_MM = 56.6929;
    /** Сдвиг печати от абзаца «С уважением,», мм — как в PDF (KpHtmlRenderer). */
    private static final int STAMP_X_MM = 22, STAMP_Y_MM = -2;

    public byte[] render(KpDocument doc) {
        try (XWPFDocument d = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            int width = page(d, doc.landscape());
            letterhead(d, doc.letterhead(), width);
            meta(d, doc, width);
            paragraph(d, ParagraphAlignment.CENTER, true, 13, doc.title()).setSpacingBefore(120);
            if (doc.subject() != null) paragraph(d, ParagraphAlignment.CENTER, true, 12, doc.subject());
            if (doc.intro() != null) paragraph(d, ParagraphAlignment.LEFT, false, 11, doc.intro()).setSpacingBefore(120);
            if (!doc.termsTable().isEmpty()) termsTable(d, doc.termsTable(), width);
            itemsTable(d, doc, width);
            for (int i = 0; i < doc.totalLines().size(); i++) {
                XWPFParagraph p = paragraph(d, ParagraphAlignment.RIGHT, i == 0, i == 0 ? 12 : 11, doc.totalLines().get(i));
                if (i == 0) p.setSpacingBefore(160);
            }
            if (doc.amountInWords() != null) paragraph(d, ParagraphAlignment.LEFT, false, 11, doc.amountInWords()).setSpacingBefore(120);
            for (int i = 0; i < doc.termsList().size(); i++) {
                XWPFParagraph p = paragraph(d, ParagraphAlignment.LEFT, false, 11, doc.termsList().get(i));
                if (i == 0) p.setSpacingBefore(200);
            }
            signoff(d, doc.signoff(), width);
            d.write(out);
            return out.toByteArray();
        } catch (IOException | InvalidFormatException | XmlException e) {
            throw new IllegalStateException("Word не собран", e);
        }
    }

    /** Размер страницы и поля; возвращает ширину набора в твипах. */
    private static int page(XWPFDocument d, boolean landscape) {
        CTBody body = d.getDocument().getBody();
        CTSectPr sect = body.isSetSectPr() ? body.getSectPr() : body.addNewSectPr();
        CTPageSz size = sect.isSetPgSz() ? sect.getPgSz() : sect.addNewPgSz();
        CTPageMar margin = sect.isSetPgMar() ? sect.getPgMar() : sect.addNewPgMar();
        if (landscape) {
            size.setW(BigInteger.valueOf(A4_LONG));
            size.setH(BigInteger.valueOf(A4_SHORT));
            size.setOrient(STPageOrientation.LANDSCAPE);
            margin.setTop(BigInteger.valueOf(LANDSCAPE_MARGIN));
            margin.setBottom(BigInteger.valueOf(LANDSCAPE_MARGIN));
            margin.setLeft(BigInteger.valueOf(LANDSCAPE_MARGIN));
            margin.setRight(BigInteger.valueOf(LANDSCAPE_MARGIN));
            return A4_LONG - 2 * LANDSCAPE_MARGIN;
        }
        size.setW(BigInteger.valueOf(A4_SHORT));
        size.setH(BigInteger.valueOf(A4_LONG));
        margin.setTop(BigInteger.valueOf(MARGIN_TOP));
        margin.setBottom(BigInteger.valueOf(MARGIN_BOTTOM));
        margin.setLeft(BigInteger.valueOf(MARGIN_LEFT));
        margin.setRight(BigInteger.valueOf(MARGIN_RIGHT));
        return A4_SHORT - MARGIN_LEFT - MARGIN_RIGHT;
    }

    private static void letterhead(XWPFDocument d, KpDocument.Letterhead lh, int width) throws IOException, InvalidFormatException {
        if (!lh.left().isEmpty() || !lh.right().isEmpty()) {
            int[] w = {width / 2, width - width / 2};
            XWPFTable t = d.createTable(1, 2);
            layout(t, w);
            borders(t, XWPFTable.XWPFBorderType.NONE);
            cellLines(t.getRow(0).getCell(0), lh.left(), ParagraphAlignment.LEFT, true, 11);
            cellLines(t.getRow(0).getCell(1), lh.right(), ParagraphAlignment.RIGHT, true, 11);
            cellWidths(t.getRow(0), w);
        }
        if (lh.logoPng() != null) {
            XWPFParagraph p = d.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.setSpacingAfter(0);
            long maxWidth = Math.round(width / TWIPS_PER_MM * EMU_PER_MM);
            long[] size = scaledSize(lh.logoPng(), 18 * EMU_PER_MM, maxWidth);
            p.createRun().addPicture(new ByteArrayInputStream(lh.logoPng()), Document.PICTURE_TYPE_PNG, "logo.png",
                    (int) size[0], (int) size[1]);
        } else if (lh.brandText() != null) {
            paragraph(d, ParagraphAlignment.CENTER, true, 28, lh.brandText());
        }
        if (!lh.lines().isEmpty()) {
            XWPFParagraph p = d.createParagraph();
            p.setAlignment(ParagraphAlignment.CENTER);
            p.setSpacingAfter(0);
            lines(p, lh.lines(), true, 12);
        }
        XWPFParagraph rule = d.createParagraph();
        rule.setBorderBottom(Borders.THICK);
        rule.setSpacingAfter(120);
    }

    private static void meta(XWPFDocument d, KpDocument doc, int width) {
        int[] w = {width / 2, width - width / 2};
        XWPFTable t = d.createTable(1, 2);
        layout(t, w);
        borders(t, XWPFTable.XWPFBorderType.NONE);
        cellLines(t.getRow(0).getCell(0), List.of(doc.numberLine()), ParagraphAlignment.LEFT, false, 11);
        cellLines(t.getRow(0).getCell(1), doc.recipientLines(), ParagraphAlignment.RIGHT, false, 11);
        cellWidths(t.getRow(0), w);
    }

    private static void termsTable(XWPFDocument d, List<KpDocument.Term> terms, int width) {
        int[] w = {(int) (width * 0.45), width - (int) (width * 0.45)};
        XWPFTable t = d.createTable(terms.size(), 2);
        layout(t, w);
        borders(t, XWPFTable.XWPFBorderType.SINGLE);
        for (int i = 0; i < terms.size(); i++) {
            XWPFTableRow row = t.getRow(i);
            cellLines(row.getCell(0), List.of(terms.get(i).label()), ParagraphAlignment.LEFT, true, 11);
            cellLines(row.getCell(1), terms.get(i).valueLines(), ParagraphAlignment.LEFT, false, 11);
            cellWidths(row, w);
        }
        d.createParagraph().setSpacingAfter(0);
    }

    private static void itemsTable(XWPFDocument d, KpDocument doc, int width) {
        List<KpDocument.Column> cols = doc.columns();
        int n = cols.size();
        int[] w = new int[n];
        int used = 0;
        for (int i = 0; i < n; i++) {
            w[i] = i == n - 1 ? width - used : (int) Math.round(width * cols.get(i).percent() / 100.0);
            used += w[i];
        }
        XWPFTable t = d.createTable(1, n);
        layout(t, w);
        borders(t, XWPFTable.XWPFBorderType.SINGLE);
        XWPFTableRow header = t.getRow(0);
        header.setRepeatHeader(true);
        for (int i = 0; i < n; i++) {
            cellLines(header.getCell(i), List.of(cols.get(i).label()), ParagraphAlignment.CENTER, true, 10);
            header.getCell(i).setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
        }
        cellWidths(header, w);
        for (KpDocument.Row r : doc.rows()) {
            XWPFTableRow row = t.createRow();
            boolean section = r.kind() == KpDocument.RowKind.SECTION;
            for (int i = 0; i < r.cells().size(); i++) {
                cellLines(row.getCell(i), r.cells().get(i), align(cols.get(i).align()), section, 10);
                row.getCell(i).setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            }
            if (r.spanFrom() < n) {
                for (int i = n - 1; i > r.spanFrom(); i--) row.removeCell(i);
                XWPFTableCell span = row.getCell(r.spanFrom());
                CTTcPr pr = span.getCTTc().isSetTcPr() ? span.getCTTc().getTcPr() : span.getCTTc().addNewTcPr();
                (pr.isSetGridSpan() ? pr.getGridSpan() : pr.addNewGridSpan()).setVal(BigInteger.valueOf(n - r.spanFrom()));
                cellLines(span, r.spanLines(), section ? ParagraphAlignment.LEFT : ParagraphAlignment.CENTER, section, 10);
                span.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.CENTER);
            }
            cellWidths(row, w);
        }
    }

    private static void signoff(XWPFDocument d, KpDocument.Signoff s, int width)
            throws IOException, InvalidFormatException, XmlException {
        XWPFParagraph regards = d.createParagraph();
        regards.setSpacingBefore(480);
        regards.setSpacingAfter(0);
        run(regards, false, 11).setText("С уважением,");
        if (s.stampPng() != null) floating(regards, s.stampPng(), s.stampSizeMm());
        if (s.director()) {
            int[] w = {(int) (width * 0.5), (int) (width * 0.25), width - (int) (width * 0.5) - (int) (width * 0.25)};
            XWPFTable t = d.createTable(1, 3);
            layout(t, w);
            borders(t, XWPFTable.XWPFBorderType.NONE);
            XWPFTableRow row = t.getRow(0);
            cellLines(row.getCell(0), List.of(s.titleLine()), ParagraphAlignment.LEFT, false, 11);
            XWPFTableCell line = row.getCell(1);
            CTTcPr pr = line.getCTTc().isSetTcPr() ? line.getCTTc().getTcPr() : line.getCTTc().addNewTcPr();
            CTTcBorders tcBorders = pr.isSetTcBorders() ? pr.getTcBorders() : pr.addNewTcBorders();
            CTBorder bottom = tcBorders.addNewBottom();
            bottom.setVal(STBorder.SINGLE);
            bottom.setSz(BigInteger.valueOf(6));
            bottom.setColor("000000");
            XWPFParagraph lp = line.getParagraphs().get(0);
            lp.setAlignment(ParagraphAlignment.CENTER);
            if (s.signaturePng() != null) {
                long[] size = scaledSize(s.signaturePng(), 15 * EMU_PER_MM, 44 * EMU_PER_MM);
                lp.createRun().addPicture(new ByteArrayInputStream(s.signaturePng()), Document.PICTURE_TYPE_PNG,
                        "signature.png", (int) size[0], (int) size[1]);
            }
            cellLines(row.getCell(2), s.nameLine() == null ? List.of() : List.of(s.nameLine()), ParagraphAlignment.LEFT, false, 11);
            for (XWPFTableCell c : row.getTableCells()) c.setVerticalAlignment(XWPFTableCell.XWPFVertAlign.BOTTOM);
            cellWidths(row, w);
        } else {
            paragraph(d, ParagraphAlignment.LEFT, false, 11, s.titleLine());
        }
        if (s.contacts() != null) paragraph(d, ParagraphAlignment.LEFT, false, 10, s.contacts()).setSpacingBefore(120);
    }

    /** Картинка «перед текстом» (wp:anchor, wrapNone) со сдвигом от абзаца — так ложится печать. */
    private static void floating(XWPFParagraph p, byte[] png, int sizeMm) throws IOException, InvalidFormatException, XmlException {
        long[] size = scaledSize(png, sizeMm * EMU_PER_MM, sizeMm * EMU_PER_MM);
        XWPFRun r = p.createRun();
        r.addPicture(new ByteArrayInputStream(png), Document.PICTURE_TYPE_PNG, "stamp.png", (int) size[0], (int) size[1]);
        CTDrawing drawing = r.getCTR().getDrawingArray(0);
        CTGraphicalObject graphic = drawing.getInlineArray(0).getGraphic();
        String xml = "<wp:anchor xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\""
                + " distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\" simplePos=\"0\" relativeHeight=\"251659264\""
                + " behindDoc=\"0\" locked=\"0\" layoutInCell=\"1\" allowOverlap=\"1\">"
                + "<wp:simplePos x=\"0\" y=\"0\"/>"
                + "<wp:positionH relativeFrom=\"column\"><wp:posOffset>" + STAMP_X_MM * EMU_PER_MM + "</wp:posOffset></wp:positionH>"
                + "<wp:positionV relativeFrom=\"paragraph\"><wp:posOffset>" + STAMP_Y_MM * EMU_PER_MM + "</wp:posOffset></wp:positionV>"
                + "<wp:extent cx=\"" + size[0] + "\" cy=\"" + size[1] + "\"/>"
                + "<wp:effectExtent l=\"0\" t=\"0\" r=\"0\" b=\"0\"/><wp:wrapNone/>"
                + "<wp:docPr id=\"9001\" name=\"Печать\"/><wp:cNvGraphicFramePr/></wp:anchor>";
        CTAnchor anchor = CTAnchor.Factory.parse(xml);
        anchor.setGraphic(graphic);
        drawing.removeInline(0);
        drawing.setAnchorArray(new CTAnchor[]{anchor});
    }

    /** {ширина, высота} в EMU: вписать в высоту, не шире maxWidth, пропорции картинки. */
    private static long[] scaledSize(byte[] png, long height, long maxWidth) throws IOException {
        BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
        long h = height;
        long w = img == null || img.getHeight() == 0 ? height : Math.round(height * (double) img.getWidth() / img.getHeight());
        if (w > maxWidth) {
            h = Math.round(h * (double) maxWidth / w);
            w = maxWidth;
        }
        return new long[]{w, h};
    }

    private static void layout(XWPFTable t, int[] widths) {
        CTTbl tbl = t.getCTTbl();
        CTTblPr pr = tbl.getTblPr() != null ? tbl.getTblPr() : tbl.addNewTblPr();
        CTTblWidth w = pr.isSetTblW() ? pr.getTblW() : pr.addNewTblW();
        int sum = 0;
        for (int x : widths) sum += x;
        w.setType(STTblWidth.DXA);
        w.setW(BigInteger.valueOf(sum));
        (pr.isSetTblLayout() ? pr.getTblLayout() : pr.addNewTblLayout()).setType(STTblLayoutType.FIXED);
        CTTblGrid grid = tbl.getTblGrid() != null ? tbl.getTblGrid() : tbl.addNewTblGrid();
        while (grid.sizeOfGridColArray() > 0) grid.removeGridCol(0);
        for (int x : widths) grid.addNewGridCol().setW(BigInteger.valueOf(x));
    }

    /** tcW у каждой ячейки с учётом объединения — без него Word ширины не соблюдает. */
    private static void cellWidths(XWPFTableRow row, int[] widths) {
        int col = 0;
        for (XWPFTableCell cell : row.getTableCells()) {
            CTTcPr pr = cell.getCTTc().isSetTcPr() ? cell.getCTTc().getTcPr() : cell.getCTTc().addNewTcPr();
            int span = pr.isSetGridSpan() ? pr.getGridSpan().getVal().intValue() : 1;
            int w = 0;
            for (int k = col; k < col + span && k < widths.length; k++) w += widths[k];
            CTTblWidth tcW = pr.isSetTcW() ? pr.getTcW() : pr.addNewTcW();
            tcW.setType(STTblWidth.DXA);
            tcW.setW(BigInteger.valueOf(w));
            col += span;
        }
    }

    private static void borders(XWPFTable t, XWPFTable.XWPFBorderType type) {
        int size = type == XWPFTable.XWPFBorderType.NONE ? 0 : 4;
        String color = type == XWPFTable.XWPFBorderType.NONE ? "auto" : "000000";
        t.setTopBorder(type, size, 0, color);
        t.setBottomBorder(type, size, 0, color);
        t.setLeftBorder(type, size, 0, color);
        t.setRightBorder(type, size, 0, color);
        t.setInsideHBorder(type, size, 0, color);
        t.setInsideVBorder(type, size, 0, color);
    }

    private static void cellLines(XWPFTableCell cell, List<String> lines, ParagraphAlignment align, boolean bold, int size) {
        XWPFParagraph p = cell.getParagraphs().get(0);
        p.setAlignment(align);
        p.setSpacingBefore(0);
        p.setSpacingAfter(0);
        lines(p, lines, bold, size);
    }

    private static void lines(XWPFParagraph p, List<String> lines, boolean bold, int size) {
        if (lines.isEmpty()) return;
        XWPFRun r = run(p, bold, size);
        for (int i = 0; i < lines.size(); i++) {
            r.setText(lines.get(i));
            if (i < lines.size() - 1) r.addBreak();
        }
    }

    private static XWPFParagraph paragraph(XWPFDocument d, ParagraphAlignment align, boolean bold, int size, String text) {
        XWPFParagraph p = d.createParagraph();
        p.setAlignment(align);
        p.setSpacingAfter(0);
        run(p, bold, size).setText(text);
        return p;
    }

    private static XWPFRun run(XWPFParagraph p, boolean bold, int size) {
        XWPFRun r = p.createRun();
        r.setFontFamily(FONT);
        r.setFontSize(size);
        r.setBold(bold);
        return r;
    }

    private static ParagraphAlignment align(ColumnAlign a) {
        return switch (a) {
            case LEFT -> ParagraphAlignment.LEFT;
            case CENTER -> ParagraphAlignment.CENTER;
            case RIGHT -> ParagraphAlignment.RIGHT;
        };
    }
}
```

- [ ] **Step 3: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS. Если компилятор не найдёт схемный класс (`ClassNotFoundException … openxmlformats …`) — значит, его нет в `poi-ooxml-lite`: добавить `implementation 'org.apache.poi:poi-ooxml-full:5.2.5'` и сообщить в отчёте задачи (для использованных классов наличие проверено 2026-10-02, поэтому такого быть не должно).

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir/service/document/KpDocxRenderer.java src/test/java/com/vladoose/nir/clientoffer/KpDocxRendererTest.java && git commit -q -F - <<'EOF'
feat(kp): Word (.docx) из той же модели — сквозная шапка, явные ширины, объединения, печать поверх текста

Ширины — только явные (tblW DXA + fixed + gridCol + tcW): «100%» POI не работает. Печать — плавающая картинка
(wp:anchor, wrapNone), её можно сдвинуть в Word. Тесты читают файл обратно через POI.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 8: API конструктора — журнал, автосохранение, статусы, копия, предпросмотр, выгрузка

**Files:**
- Create: `src/main/java/com/vladoose/nir/dto/request/ClientOfferUpdateRequest.java`, `ClientOfferItemDto.java`, `ClientOfferStatusRequest.java`
- Create: `src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java`, `ClientOfferListItemResponse.java`, `PreviewPagesResponse.java`
- Create: `src/main/java/com/vladoose/nir/service/ClientOfferService.java`, `ClientOfferMapper.java`, `controller/ClientOfferController.java`
- Modify: `src/main/java/com/vladoose/nir/entity/ClientOfferItem.java` (поле `@Transient clientKey`), `controller/CompanyProfileController.java` (образец), `exception/GlobalExceptionHandler.java` (409 на гонку версий)
- Test: `src/test/java/com/vladoose/nir/clientoffer/ClientOfferApiTest.java`

**Interfaces:**
- Consumes: Tasks 2–7.
- Produces (REST, всё под `/api`, рынок из `X-Market`):
  - `GET /client-offers?status=ALL|DRAFT,SENT…&q=` → `ClientOfferListItemResponse[]` (`id, number, offerDate, status, clientName, itemCount, totalAmount, currency, tenderId, updatedAt`);
  - `POST /client-offers` (ADMIN) → `ClientOfferResponse`; `GET /client-offers/{id}`; `PUT /client-offers/{id}` (ADMIN, `ClientOfferUpdateRequest`, 409 при чужой версии); `DELETE /client-offers/{id}` (ADMIN, только `DRAFT`, иначе 400; 204); `POST /client-offers/{id}/duplicate` (ADMIN); `POST /client-offers/{id}/status {status}` (ADMIN);
  - `GET /client-offers/{id}/preview` → `{pages: [base64 PNG]}`; `GET /client-offers/{id}/pdf`, `/docx` — файл (`attachment; filename*=UTF-8''…`, `nosniff`, `no-store`);
  - `GET /company-profile/sample-preview` (ADMIN) → `{pages: [одна страница]}`.
  - `ClientOfferResponse`: `id, number, offerDate, status, facilityId, facilityName, recipient, tenderId, title, subject, intro, vatEnabled, defaultMarkupPct, rounding, columns, detailsInName, terms, termsStyle, showAmountInWords, showVatBreakdown, landscape, signoff, signoffContacts, withStamp, internalNote, version, currency, items, totals, fileBaseName, createdBy, createdAt, updatedAt, sentAt`; строка `ClientOfferItemDto`: `id, key, lineNo, kind, name, model, manufacturer, country, unit, quantity, purchasePrice, purchaseVatSame, purchaseVatRate, supplierName, markupPct, priceOverride, vatRate, registrationStatus, registrationText, regNumber, note, calc` (`calc` — `ItemCalc`, только в ответе). `key` — ключ строки на клиенте: сервер возвращает его как есть (у строк без ключа — `"i" + id`), фронт по нему сводит ответ автосохранения со своими строками.

- [ ] **Step 1: Падающие тесты**

`src/test/java/com/vladoose/nir/clientoffer/ClientOfferApiTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.FacilityRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** API «КП клиентам» (спека §10–§12): права, рынок, автосохранение с версией, строки, файлы. */
@SpringBootTest
@Transactional
class ClientOfferApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired FacilityRepository facilities;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) {
        return b.header("X-Market", "KZ");
    }

    private JsonNode json(MockHttpServletRequestBuilder b) throws Exception {
        return om.readTree(mvc.perform(b).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode create() throws Exception {
        return json(kz(post("/api/client-offers")));
    }

    /** Тело PUT = ответ GET с правками: лишние поля (id, totals, calc…) сервер игнорирует. */
    private ObjectNode bodyOf(JsonNode offer) {
        return offer.deepCopy();
    }

    private ObjectNode item(String key, String name, int qty, Integer purchase, Integer vat) {
        ObjectNode it = om.createObjectNode();
        it.put("key", key);
        it.put("kind", "ITEM");
        it.put("name", name);
        it.put("unit", "шт");
        it.put("quantity", qty);
        if (purchase != null) it.put("purchasePrice", purchase); else it.putNull("purchasePrice");
        it.put("purchaseVatSame", true);
        if (vat != null) it.put("vatRate", vat); else it.putNull("vatRate");
        it.put("registrationStatus", "UNCHECKED");
        return it;
    }

    private JsonNode save(long id, ObjectNode body) throws Exception {
        return json(kz(put("/api/client-offers/" + id)).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void operatorSeesJournalButCannotWrite() throws Exception {
        mvc.perform(kz(get("/api/client-offers"))).andExpect(status().isOk());
        mvc.perform(kz(post("/api/client-offers"))).andExpect(status().isForbidden());
        mvc.perform(kz(get("/api/company-profile/sample-preview"))).andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void createTakesMarketDefaultsAndNextNumber() throws Exception {
        int next = jdbc.queryForObject("select next_number from company_profile where market = 'KZ'", Integer.class);
        JsonNode o = create();
        assertThat(o.get("number").asInt()).isEqualTo(next);
        assertThat(o.get("status").asText()).isEqualTo("DRAFT");
        assertThat(o.get("version").asInt()).isZero();
        assertThat(o.get("currency").asText()).isEqualTo("KZT");
        assertThat(o.get("columns")).hasSize(8);
        assertThat(o.get("terms")).hasSize(5);
        assertThat(o.get("intro").asText()).contains("West-Med");
        assertThat(o.get("defaultMarkupPct").asInt()).isEqualTo(20);
        assertThat(o.get("fileBaseName").asText()).startsWith("КП № " + next + " от ");
        assertThat(create().get("number").asInt()).isEqualTo(next + 1);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void putCalculatesItemsEchoesKeysAndBumpsVersion() throws Exception {
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        ArrayNode items = body.putArray("items");
        items.add(item("k1", "Пульсоксиметр", 3, 105000, 5));
        JsonNode r = save(o.get("id").asLong(), body);
        JsonNode first = r.get("items").get(0);
        assertThat(first.get("id").isNumber()).isTrue();
        assertThat(first.get("key").asText()).isEqualTo("k1");
        assertThat(first.get("lineNo").asInt()).isEqualTo(1);
        assertThat(first.get("calc").get("price").decimalValue()).isEqualByComparingTo("126000.00");
        assertThat(r.get("totals").get("sum").decimalValue()).isEqualByComparingTo("378000.00");
        assertThat(r.get("totals").get("profit").decimalValue()).isEqualByComparingTo("60000.00");
        assertThat(r.get("version").asInt()).isEqualTo(1);
        // журнал видит итог без загрузки строк
        mvc.perform(kz(get("/api/client-offers")).param("q", "пульсоксиметр"))
                .andExpect(jsonPath("$[0].id").value(o.get("id").asLong()))
                .andExpect(jsonPath("$[0].totalAmount").value(378000.0))
                .andExpect(jsonPath("$[0].itemCount").value(1));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void staleVersionIsConflict() throws Exception {
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        save(o.get("id").asLong(), body);                          // версия 0 → 1
        mvc.perform(kz(put("/api/client-offers/" + o.get("id").asLong()))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))   // снова с версией 0
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("другой вкладке")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void validationOfRatesQuantityAndRegistration() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("k1", "А", 1, 100, 12));
        mvc.perform(kz(put("/api/client-offers/" + id)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("12%")));

        body.putArray("items").add(item("k1", "А", 0, 100, 5));
        mvc.perform(kz(put("/api/client-offers/" + id)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Количество")));

        ObjectNode confirmed = item("k1", "А", 1, 100, 5);
        confirmed.put("registrationStatus", "CONFIRMED");
        body.putArray("items").add(confirmed);
        mvc.perform(kz(put("/api/client-offers/" + id)).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("реестр")));

        ObjectNode notRequired = item("k1", "Гигрометр", 1, 100, 16);
        notRequired.put("registrationStatus", "NOT_REQUIRED");
        ObjectNode manual = item("k2", "Пульсоксиметр", 1, 100, 5);
        manual.put("registrationText", "  № РК-МИ (МТ)-0№023037  ");
        body.putArray("items").add(notRequired).add(manual);
        JsonNode r = save(id, body);
        assertThat(r.get("items").get(0).get("registrationText").asText()).isEqualTo("Не подлежит регистрации");
        assertThat(r.get("items").get(1).get("registrationStatus").asText()).isEqualTo("MANUAL");
        assertThat(r.get("items").get(1).get("registrationText").asText()).isEqualTo("№ РК-МИ (МТ)-0№023037");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void removesMissingRowsAndKeepsNewOrder() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("a", "Первая", 1, 100, 5)).add(item("b", "Вторая", 1, 100, 5)).add(item("c", "Третья", 1, 100, 5));
        JsonNode r = save(id, body);
        ObjectNode third = (ObjectNode) r.get("items").get(2).deepCopy();
        ObjectNode firstRow = (ObjectNode) r.get("items").get(0).deepCopy();
        ObjectNode next = bodyOf(r);
        next.putArray("items").add(third).add(firstRow);
        JsonNode r2 = save(id, next);
        assertThat(r2.get("items")).hasSize(2);
        assertThat(r2.get("items").get(0).get("name").asText()).isEqualTo("Третья");
        assertThat(r2.get("items").get(0).get("lineNo").asInt()).isEqualTo(1);
        assertThat(r2.get("items").get(1).get("name").asText()).isEqualTo("Первая");
        assertThat(r2.get("items").get(1).get("id").asLong()).isEqualTo(firstRow.get("id").asLong());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void clientOfAnotherMarketIsRejected() throws Exception {
        MarketContext.set(Market.RF);
        Facility rf = facilities.save(Facility.builder().name("Клиника РФ " + UUID.randomUUID()).build());
        MarketContext.clear();
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        body.put("facilityId", rf.getId());
        mvc.perform(kz(put("/api/client-offers/" + o.get("id").asLong())).contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value(containsString("Клиент не найден")));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void offerOfAnotherMarketIsNotFound() throws Exception {
        long id = create().get("id").asLong();
        mvc.perform(get("/api/client-offers/" + id).header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers/" + id + "/pdf").header("X-Market", "RF")).andExpect(status().isNotFound());
        mvc.perform(get("/api/client-offers").header("X-Market", "RF"))
                .andExpect(jsonPath("$[?(@.id == " + id + ")]").isEmpty());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void onlyDraftCanBeDeletedAndSentSetsSentAt() throws Exception {
        long id = create().get("id").asLong();
        JsonNode sent = json(kz(post("/api/client-offers/" + id + "/status")).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"SENT\"}"));
        assertThat(sent.get("status").asText()).isEqualTo("SENT");
        assertThat(sent.get("sentAt").isNull()).isFalse();
        assertThat(sent.get("version").asInt()).isEqualTo(1);
        mvc.perform(kz(delete("/api/client-offers/" + id))).andExpect(status().isBadRequest());

        long draft = create().get("id").asLong();
        mvc.perform(kz(delete("/api/client-offers/" + draft))).andExpect(status().isNoContent());
        mvc.perform(kz(get("/api/client-offers/" + draft))).andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void duplicateCopiesRowsWithNewNumber() throws Exception {
        JsonNode o = create();
        ObjectNode body = bodyOf(o);
        body.put("recipient", "ГКП «Областная больница»");
        body.putArray("items").add(item("a", "Первая", 2, 100, 5)).add(item("b", "Вторая", 1, 200, 16));
        save(o.get("id").asLong(), body);
        JsonNode copy = json(kz(post("/api/client-offers/" + o.get("id").asLong() + "/duplicate")));
        assertThat(copy.get("id").asLong()).isNotEqualTo(o.get("id").asLong());
        assertThat(copy.get("number").asInt()).isEqualTo(o.get("number").asInt() + 1);
        assertThat(copy.get("status").asText()).isEqualTo("DRAFT");
        assertThat(copy.get("recipient").asText()).isEqualTo("ГКП «Областная больница»");
        assertThat(copy.get("items")).hasSize(2);
        assertThat(copy.get("items").get(1).get("name").asText()).isEqualTo("Вторая");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void previewPdfAndDocx() throws Exception {
        JsonNode o = create();
        long id = o.get("id").asLong();
        ObjectNode body = bodyOf(o);
        body.putArray("items").add(item("a", "Пульсоксиметр", 1, 105000, 5));
        save(id, body);

        JsonNode preview = json(kz(get("/api/client-offers/" + id + "/preview")));
        byte[] png = Base64.getDecoder().decode(preview.get("pages").get(0).asText());
        assertThat(ImageIO.read(new ByteArrayInputStream(png)).getWidth()).isBetween(890, 930);

        byte[] pdf = mvc.perform(kz(get("/api/client-offers/" + id + "/pdf")))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Disposition", containsString("filename*=UTF-8''")))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(KpTestSupport.text(pdf)).contains("Пульсоксиметр", "Итого: 126 000,00 тг");

        byte[] docx = mvc.perform(kz(get("/api/client-offers/" + id + "/docx")))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document"))
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(new String(docx, 0, 2)).isEqualTo("PK");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void journalSearchByNumberAndStatusFilter() throws Exception {
        JsonNode o = create();
        int number = o.get("number").asInt();
        mvc.perform(kz(get("/api/client-offers")).param("q", String.valueOf(number)))
                .andExpect(jsonPath("$[0].id").value(o.get("id").asLong()));
        mvc.perform(kz(get("/api/client-offers")).param("status", "SENT"))
                .andExpect(jsonPath("$[?(@.id == " + o.get("id").asLong() + ")]").isEmpty());
        mvc.perform(kz(get("/api/client-offers")).param("status", "НЕТ"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void samplePreviewForAdmin() throws Exception {
        JsonNode r = json(kz(get("/api/company-profile/sample-preview")));
        assertThat(r.get("pages")).hasSize(1);
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferApiTest'`
Expected: FAIL — 404 на `/api/client-offers` (контроллера нет) или компиляция, если тест ссылается на ещё не созданные классы (он не ссылается — упадут сами запросы).

- [ ] **Step 2: Ключ строки в сущности и 409 на гонку версий**

В `ClientOfferItem.java` добавить импорт `jakarta.persistence.Transient` (уже покрыт `jakarta.persistence.*`) и поле после `note`:

```java
    /** Ключ строки на клиенте (автосохранение сводит ответ со своими строками по нему). Не хранится. */
    @Transient
    private String clientKey;
```

В `GlobalExceptionHandler.java` перед обработчиком `Exception.class` добавить:

```java
    /** Две записи одного КП одновременно (две вкладки) — @Version отбил вторую: это не 500, а «обновите». */
    @ExceptionHandler(org.springframework.orm.ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleOptimisticLock(org.springframework.orm.ObjectOptimisticLockingFailureException ex) {
        ApiError error = ApiError.builder()
                .status(HttpStatus.CONFLICT.value())
                .message("Данные изменились в другой вкладке — обновите страницу")
                .errors(null)
                .build();
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error);
    }
```

- [ ] **Step 3: DTO**

`src/main/java/com/vladoose/nir/dto/request/ClientOfferItemDto.java`:

```java
package com.vladoose.nir.dto.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.vladoose.nir.entity.ClientOfferItemKind;
import com.vladoose.nir.entity.OfferRegistrationStatus;
import com.vladoose.nir.service.offer.ItemCalc;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;

/** Строка КП: в запросе — ввод оператора, в ответе — плюс calc. Пустое наименование допустимо (оператор печатает). */
@Data
public class ClientOfferItemDto {
    private Long id;
    @Size(max = 64) private String key;
    private Integer lineNo;
    private ClientOfferItemKind kind;
    @Size(max = 4000) private String name;
    @Size(max = 255) private String model;
    @Size(max = 500) private String manufacturer;
    @Size(max = 200) private String country;
    @Size(max = 30) private String unit;
    private BigDecimal quantity;
    private BigDecimal purchasePrice;
    private Boolean purchaseVatSame;
    private BigDecimal purchaseVatRate;
    @Size(max = 255) private String supplierName;
    private BigDecimal markupPct;
    private BigDecimal priceOverride;
    private BigDecimal vatRate;
    private OfferRegistrationStatus registrationStatus;
    @Size(max = 1000) private String registrationText;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private String regNumber;
    @Size(max = 4000) private String note;
    @JsonProperty(access = JsonProperty.Access.READ_ONLY)
    private ItemCalc calc;
}
```

`src/main/java/com/vladoose/nir/dto/request/ClientOfferUpdateRequest.java`:

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferRounding;
import com.vladoose.nir.entity.OfferSignoff;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Автосохранение КП целиком (спека §8.3): шапка, настройки и строки. version — защита от второй вкладки. */
@Data
public class ClientOfferUpdateRequest {
    @NotNull private Integer version;
    @NotNull @Min(value = 1, message = "Номер — от 1") private Integer number;
    @NotNull(message = "Дата КП обязательна") private LocalDate offerDate;
    private Long facilityId;
    @Size(max = 2000) private String recipient;
    @NotBlank(message = "Заголовок не может быть пустым") @Size(max = 200) private String title;
    @Size(max = 1000) private String subject;
    @Size(max = 4000) private String intro;
    private boolean vatEnabled = true;
    @NotNull @DecimalMin(value = "-100", message = "Наценка — от −100%") @DecimalMax(value = "1000", message = "Наценка — до 1000%")
    private BigDecimal defaultMarkupPct;
    @NotNull private OfferRounding rounding;
    @NotNull private List<OfferColumn> columns;
    private boolean detailsInName = true;
    @NotNull private List<OfferTerm> terms;
    @NotNull private TermsStyle termsStyle;
    private boolean showAmountInWords = true;
    private boolean showVatBreakdown = true;
    private boolean landscape;
    @NotNull private OfferSignoff signoff;
    private boolean signoffContacts;
    private boolean withStamp;
    @Size(max = 4000) private String internalNote;
    @NotNull @Size(max = 500, message = "Строк больше 500") @Valid private List<ClientOfferItemDto> items;
}
```

`src/main/java/com/vladoose/nir/dto/request/ClientOfferStatusRequest.java`:

```java
package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.ClientOfferStatus;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ClientOfferStatusRequest {
    @NotNull private ClientOfferStatus status;
}
```

`src/main/java/com/vladoose/nir/dto/response/ClientOfferResponse.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.OfferTotals;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ClientOfferResponse {
    private Long id;
    private Integer number;
    private LocalDate offerDate;
    private ClientOfferStatus status;
    private Long facilityId;
    private String facilityName;
    private String recipient;
    private Long tenderId;
    private String title;
    private String subject;
    private String intro;
    private boolean vatEnabled;
    private BigDecimal defaultMarkupPct;
    private OfferRounding rounding;
    private List<OfferColumn> columns;
    private boolean detailsInName;
    private List<OfferTerm> terms;
    private TermsStyle termsStyle;
    private boolean showAmountInWords;
    private boolean showVatBreakdown;
    private boolean landscape;
    private OfferSignoff signoff;
    private boolean signoffContacts;
    private boolean withStamp;
    private String internalNote;
    private int version;
    private String currency;
    private List<ClientOfferItemDto> items;
    private OfferTotals totals;
    private String fileBaseName;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime sentAt;
}
```

`src/main/java/com/vladoose/nir/dto/response/ClientOfferListItemResponse.java`:

```java
package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.ClientOfferStatus;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

@Data
public class ClientOfferListItemResponse {
    private Long id;
    private Integer number;
    private LocalDate offerDate;
    private ClientOfferStatus status;
    private String clientName;
    private int itemCount;
    private BigDecimal totalAmount;
    private String currency;
    private Long tenderId;
    private OffsetDateTime updatedAt;
}
```

`src/main/java/com/vladoose/nir/dto/response/PreviewPagesResponse.java`:

```java
package com.vladoose.nir.dto.response;

import java.util.List;

/** Страницы предпросмотра PNG в base64 — одним ответом через HttpClient (X-Market, §14). */
public record PreviewPagesResponse(List<String> pages) {}
```

- [ ] **Step 4: Сервис и маппер**

`src/main/java/com/vladoose/nir/service/ClientOfferMapper.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.dto.response.ClientOfferListItemResponse;
import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferItem;
import com.vladoose.nir.service.offer.ItemCalc;
import com.vladoose.nir.service.offer.OfferCalculation;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ClientOfferMapper {

    public ClientOfferResponse toResponse(ClientOffer o, OfferCalculation calc, String fileBaseName) {
        ClientOfferResponse r = new ClientOfferResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
        r.setOfferDate(o.getOfferDate());
        r.setStatus(o.getStatus());
        r.setFacilityId(o.getFacility() == null ? null : o.getFacility().getId());
        r.setFacilityName(o.getFacility() == null ? null : o.getFacility().getName());
        r.setRecipient(o.getRecipient());
        r.setTenderId(o.getTenderId());
        r.setTitle(o.getTitle());
        r.setSubject(o.getSubject());
        r.setIntro(o.getIntro());
        r.setVatEnabled(o.isVatEnabled());
        r.setDefaultMarkupPct(o.getDefaultMarkupPct());
        r.setRounding(o.getRounding());
        r.setColumns(o.getTableColumns());
        r.setDetailsInName(o.isDetailsInName());
        r.setTerms(o.getTerms());
        r.setTermsStyle(o.getTermsStyle());
        r.setShowAmountInWords(o.isShowAmountInWords());
        r.setShowVatBreakdown(o.isShowVatBreakdown());
        r.setLandscape(o.isLandscape());
        r.setSignoff(o.getSignoff());
        r.setSignoffContacts(o.isSignoffContacts());
        r.setWithStamp(o.isWithStamp());
        r.setInternalNote(o.getInternalNote());
        r.setVersion(o.getVersion());
        r.setCurrency(o.getMarket().currencyCode());
        List<ClientOfferItemDto> items = new ArrayList<>();
        for (int i = 0; i < o.getItems().size(); i++) items.add(toItemDto(o.getItems().get(i), calc.items().get(i)));
        r.setItems(items);
        r.setTotals(calc.totals());
        r.setFileBaseName(fileBaseName);
        r.setCreatedBy(o.getCreatedBy());
        r.setCreatedAt(o.getCreatedAt());
        r.setUpdatedAt(o.getUpdatedAt());
        r.setSentAt(o.getSentAt());
        return r;
    }

    public ClientOfferItemDto toItemDto(ClientOfferItem it, ItemCalc calc) {
        ClientOfferItemDto d = new ClientOfferItemDto();
        d.setId(it.getId());
        d.setKey(it.getClientKey() != null ? it.getClientKey() : "i" + it.getId());
        d.setLineNo(it.getLineNo());
        d.setKind(it.getKind());
        d.setName(it.getName());
        d.setModel(it.getModel());
        d.setManufacturer(it.getManufacturer());
        d.setCountry(it.getCountry());
        d.setUnit(it.getUnit());
        d.setQuantity(it.getQuantity());
        d.setPurchasePrice(it.getPurchasePrice());
        d.setPurchaseVatSame(it.isPurchaseVatSame());
        d.setPurchaseVatRate(it.getPurchaseVatRate());
        d.setSupplierName(it.getSupplierName());
        d.setMarkupPct(it.getMarkupPct());
        d.setPriceOverride(it.getPriceOverride());
        d.setVatRate(it.getVatRate());
        d.setRegistrationStatus(it.getRegistrationStatus());
        d.setRegistrationText(it.getRegistrationText());
        d.setRegNumber(it.getRegNumber());
        d.setNote(it.getNote());
        d.setCalc(calc);
        return d;
    }

    public ClientOfferListItemResponse toListItem(ClientOffer o) {
        ClientOfferListItemResponse r = new ClientOfferListItemResponse();
        r.setId(o.getId());
        r.setNumber(o.getNumber());
        r.setOfferDate(o.getOfferDate());
        r.setStatus(o.getStatus());
        r.setClientName(ClientOfferService.clientName(o));
        r.setItemCount(o.getItemCount());
        r.setTotalAmount(o.getTotalAmount());
        r.setCurrency(o.getMarket().currencyCode());
        r.setTenderId(o.getTenderId());
        r.setUpdatedAt(o.getUpdatedAt());
        return r;
    }
}
```

`src/main/java/com/vladoose/nir/service/ClientOfferService.java`:

```java
package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.dto.request.ClientOfferUpdateRequest;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.ConflictException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.service.document.*;
import com.vladoose.nir.service.offer.ClientOfferCalculator;
import com.vladoose.nir.service.offer.OfferCalculation;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.util.DocFormat;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.*;

/**
 * «КП клиентам» (спека client-kp-constructor §8–§12). Запись — только через этот сервис: гард рынка по id
 * (findById = em.find, фильтр рынка обходит), версия от второй вкладки, строки — через коллекцию (§7 CLAUDE.md).
 */
@Service
public class ClientOfferService {

    public static final int LIST_LIMIT = 300;
    public static final int PREVIEW_MAX_PAGES = 20;
    static final String NOT_REQUIRED_TEXT = "Не подлежит регистрации";
    static final String DEFAULT_TITLE = "КОММЕРЧЕСКОЕ ПРЕДЛОЖЕНИЕ";

    private final ClientOfferRepository repository;
    private final CompanyProfileService profiles;
    private final FacilityRepository facilities;
    private final ClientOfferCalculator calculator;
    private final KpDocumentBuilder builder;
    private final KpHtmlRenderer html;
    private final KpPdfRenderer pdf;
    private final KpPreviewRenderer preview;
    private final KpDocxRenderer docx;

    public ClientOfferService(ClientOfferRepository repository, CompanyProfileService profiles,
                              FacilityRepository facilities, ClientOfferCalculator calculator, KpDocumentBuilder builder,
                              KpHtmlRenderer html, KpPdfRenderer pdf, KpPreviewRenderer preview, KpDocxRenderer docx) {
        this.repository = repository;
        this.profiles = profiles;
        this.facilities = facilities;
        this.calculator = calculator;
        this.builder = builder;
        this.html = html;
        this.pdf = pdf;
        this.preview = preview;
        this.docx = docx;
    }

    @Transactional(readOnly = true)
    public List<ClientOffer> list(Set<ClientOfferStatus> statuses, String q) {
        Pageable top = PageRequest.of(0, LIST_LIMIT);
        if (q == null || q.isBlank()) return repository.findJournal(statuses, top);
        String needle = q.trim();
        Integer number = needle.matches("\\d{1,9}") ? Integer.valueOf(needle) : null;
        return repository.searchJournal(statuses, likePattern(needle), number, top);
    }

    @Transactional(readOnly = true)
    public ClientOffer get(Long id) {
        ClientOffer o = repository.findById(id).orElseThrow(() -> notFound(id));
        if (o.getMarket() != MarketContext.get()) throw notFound(id);
        return o;
    }

    @Transactional
    public ClientOffer create(String author) {
        Market market = MarketContext.get();
        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(profiles.allocateNumber(market))
                .offerDate(today(market))
                .status(ClientOfferStatus.DRAFT)
                .title(DEFAULT_TITLE)
                .intro(p.getDefaultIntro())
                .vatEnabled(true)
                .defaultMarkupPct(p.getDefaultMarkupPct())
                .rounding(OfferRounding.NONE)
                .tableColumns(copyColumns(p.getDefaultColumns()))
                .detailsInName(true)
                .terms(copyTerms(p.getDefaultTerms()))
                .termsStyle(p.getDefaultTermsStyle())
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoff(OfferSignoff.DIRECTOR)
                .totalAmount(BigDecimal.ZERO.setScale(2))
                .createdBy(author)
                .build();
        return repository.saveAndFlush(o);
    }

    @Transactional
    public ClientOffer update(Long id, ClientOfferUpdateRequest r) {
        ClientOffer o = get(id);
        if (r.getVersion() != o.getVersion()) throw new ConflictException("КП изменено в другой вкладке — обновите страницу");
        OfferSettingsValidator.columns(r.getColumns());
        OfferSettingsValidator.terms(r.getTerms());
        CompanyProfile profile = profiles.forMarket(o.getMarket());

        o.setNumber(r.getNumber());
        o.setOfferDate(r.getOfferDate());
        o.setFacility(facility(r.getFacilityId()));
        o.setRecipient(trim(r.getRecipient()));
        o.setTitle(r.getTitle().trim());
        o.setSubject(trim(r.getSubject()));
        o.setIntro(trim(r.getIntro()));
        o.setVatEnabled(r.isVatEnabled());
        o.setDefaultMarkupPct(r.getDefaultMarkupPct());
        o.setRounding(r.getRounding());
        o.setTableColumns(copyColumns(r.getColumns()));
        o.setDetailsInName(r.isDetailsInName());
        o.setTerms(copyTerms(r.getTerms()));
        o.setTermsStyle(r.getTermsStyle());
        o.setShowAmountInWords(r.isShowAmountInWords());
        o.setShowVatBreakdown(r.isShowVatBreakdown());
        o.setLandscape(r.isLandscape());
        o.setSignoff(r.getSignoff());
        o.setSignoffContacts(r.isSignoffContacts());
        o.setWithStamp(r.isWithStamp());
        o.setInternalNote(trim(r.getInternalNote()));
        syncItems(o, r.getItems(), profile);

        OfferCalculation calc = calculator.calculate(o);
        o.setTotalAmount(calc.totals().sum());
        o.setItemCount(calc.totals().itemCount());
        o.setUpdatedAt(OffsetDateTime.now());   // версия растёт на каждом сохранении, даже если поля не поменялись
        return repository.saveAndFlush(o);
    }

    @Transactional
    public ClientOffer duplicate(Long id, String author) {
        ClientOffer src = get(id);
        Market market = src.getMarket();
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(profiles.allocateNumber(market))
                .offerDate(today(market))
                .status(ClientOfferStatus.DRAFT)
                .facility(src.getFacility())
                .recipient(src.getRecipient())
                .tenderId(src.getTenderId())
                .title(src.getTitle())
                .subject(src.getSubject())
                .intro(src.getIntro())
                .vatEnabled(src.isVatEnabled())
                .defaultMarkupPct(src.getDefaultMarkupPct())
                .rounding(src.getRounding())
                .tableColumns(copyColumns(src.getTableColumns()))
                .detailsInName(src.isDetailsInName())
                .terms(copyTerms(src.getTerms()))
                .termsStyle(src.getTermsStyle())
                .showAmountInWords(src.isShowAmountInWords())
                .showVatBreakdown(src.isShowVatBreakdown())
                .landscape(src.isLandscape())
                .signoff(src.getSignoff())
                .signoffContacts(src.isSignoffContacts())
                .withStamp(src.isWithStamp())
                .internalNote(src.getInternalNote())
                .totalAmount(src.getTotalAmount())
                .itemCount(src.getItemCount())
                .createdBy(author)
                .build();
        for (ClientOfferItem it : src.getItems()) o.getItems().add(copyItem(it, o));
        return repository.saveAndFlush(o);
    }

    @Transactional
    public ClientOffer setStatus(Long id, ClientOfferStatus status) {
        ClientOffer o = get(id);
        o.setStatus(status);
        if (status == ClientOfferStatus.SENT && o.getSentAt() == null) o.setSentAt(OffsetDateTime.now());
        o.setUpdatedAt(OffsetDateTime.now());
        return repository.saveAndFlush(o);
    }

    @Transactional
    public void delete(Long id) {
        ClientOffer o = get(id);
        if (o.getStatus() != ClientOfferStatus.DRAFT) {
            throw new BadRequestException("Удалить можно только черновик — отправленное КП отметьте «Отклонено»");
        }
        repository.delete(o);
    }

    public OfferCalculation calculate(ClientOffer o) {
        return calculator.calculate(o);
    }

    @Transactional(readOnly = true)
    public byte[] pdf(Long id) {
        return renderPdf(get(id));
    }

    @Transactional(readOnly = true)
    public byte[] docx(Long id) {
        return docx.render(document(get(id)));
    }

    @Transactional(readOnly = true)
    public List<String> previewPages(Long id) {
        return encode(preview.pages(renderPdf(get(id)), PREVIEW_MAX_PAGES));
    }

    /** Первая страница КП-образца с текущими реквизитами, печатью и подписью — для «Реквизитов и печати». */
    @Transactional(readOnly = true)
    public List<String> samplePreview() {
        Market market = MarketContext.get();
        CompanyProfile p = profiles.forMarket(market);
        ClientOffer o = ClientOffer.builder()
                .market(market)
                .number(p.getNextNumber())
                .offerDate(today(market))
                .title(DEFAULT_TITLE)
                .recipient("Главному врачу\nГКП на ПХВ «Областная больница»")
                .intro(p.getDefaultIntro())
                .vatEnabled(true)
                .defaultMarkupPct(p.getDefaultMarkupPct())
                .tableColumns(copyColumns(p.getDefaultColumns()))
                .detailsInName(true)
                .terms(copyTerms(p.getDefaultTerms()))
                .termsStyle(p.getDefaultTermsStyle())
                .showAmountInWords(true)
                .showVatBreakdown(true)
                .signoffContacts(true)
                .withStamp(p.getStampPng() != null)
                .build();
        o.getItems().add(sample(o, 1, ClientOfferItemKind.ITEM, "Анализатор автоматический биохимический «ВитаЛайн 200»",
                "шт", "1", "7650000", p.getVatRegistered(), OfferRegistrationStatus.MANUAL, "№ РК-МИ (МТ)-0№000000 от 01.01.2024 г. (образец)"));
        o.getItems().add(sample(o, 2, ClientOfferItemKind.SECTION, "Расходные материалы", null, null, null, null, null, null));
        o.getItems().add(sample(o, 3, ClientOfferItemKind.ITEM, "Реакционная кювета к анализатору (упаковка 60 шт)",
                "упак", "2", "70631.23", p.getVatRegistered(), OfferRegistrationStatus.NOT_REQUIRED, NOT_REQUIRED_TEXT));
        o.getItems().add(sample(o, 4, ClientOfferItemKind.INCLUDED, "Гарантийное сервисное обслуживание 12 месяцев",
                null, null, null, null, null, null));
        return encode(preview.pages(renderPdf(o), 1));
    }

    /** «КП № 443 от 14.09.2026 — ГКП …»: без символов, недопустимых в именах файлов. */
    public String fileBaseName(ClientOffer o) {
        String client = clientName(o);
        String base = "КП № " + o.getNumber() + " от " + DocFormat.date(o.getOfferDate()) + (client == null ? "" : " — " + client);
        base = base.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]+", " ").replaceAll("\\s+", " ").trim();
        return base.length() > 150 ? base.substring(0, 150).trim() : base;
    }

    static String clientName(ClientOffer o) {
        if (o.getFacility() != null) return o.getFacility().getName();
        List<String> lines = CompanyLines.split(o.getRecipient());
        return lines.isEmpty() ? null : lines.get(0);
    }

    private KpDocument document(ClientOffer o) {
        return builder.build(o, profiles.forMarket(o.getMarket()), calculator.calculate(o));
    }

    private byte[] renderPdf(ClientOffer o) {
        return pdf.render(html.render(document(o)));
    }

    private void syncItems(ClientOffer o, List<ClientOfferItemDto> dtos, CompanyProfile profile) {
        Map<Long, ClientOfferItem> existing = new HashMap<>();
        for (ClientOfferItem it : o.getItems()) existing.put(it.getId(), it);
        List<ClientOfferItem> ordered = new ArrayList<>();
        int lineNo = 0;
        for (ClientOfferItemDto dto : dtos) {
            ClientOfferItem it;
            if (dto.getId() != null) {
                it = existing.remove(dto.getId());
                if (it == null) throw new BadRequestException("Строка не найдена или повторяется: id=" + dto.getId());
            } else {
                it = ClientOfferItem.builder().offer(o).build();
                o.getItems().add(it);
            }
            applyItem(it, dto, profile);
            it.setLineNo(++lineNo);
            ordered.add(it);
        }
        Set<ClientOfferItem> keep = Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(ordered);
        o.getItems().removeIf(it -> !keep.contains(it));
        o.getItems().sort(Comparator.comparingInt(ClientOfferItem::getLineNo));
    }

    private void applyItem(ClientOfferItem it, ClientOfferItemDto dto, CompanyProfile profile) {
        ClientOfferItemKind kind = dto.getKind() == null ? ClientOfferItemKind.ITEM : dto.getKind();
        it.setKind(kind);
        it.setName(dto.getName() == null ? "" : dto.getName().trim());
        it.setNote(trim(dto.getNote()));
        it.setClientKey(dto.getKey());
        if (kind != ClientOfferItemKind.ITEM) {
            it.setModel(null);
            it.setManufacturer(null);
            it.setCountry(null);
            it.setUnit("шт");
            it.setQuantity(null);
            it.setPurchasePrice(null);
            it.setPurchaseVatSame(true);
            it.setPurchaseVatRate(null);
            it.setSupplierName(null);
            it.setMarkupPct(null);
            it.setPriceOverride(null);
            it.setVatRate(null);
            it.setRegistrationStatus(OfferRegistrationStatus.UNCHECKED);
            it.setRegistrationText(null);
            it.setRegNumber(null);
            it.setSuggestionScore(null);
            return;
        }
        if (dto.getQuantity() != null && dto.getQuantity().signum() <= 0) {
            throw new BadRequestException("Количество должно быть больше нуля: «" + it.getName() + "»");
        }
        nonNegative(dto.getPurchasePrice(), "Цена закупки", it);
        nonNegative(dto.getPriceOverride(), "Цена клиенту", it);
        range(dto.getMarkupPct(), -100, 1000, "Наценка", it);
        range(dto.getPurchaseVatRate(), 0, 100, "НДС закупки", it);
        boolean sameAsStored = it.getId() != null && (it.getVatRate() == null
                ? dto.getVatRate() == null
                : dto.getVatRate() != null && it.getVatRate().compareTo(dto.getVatRate()) == 0);
        if (!OfferSettingsValidator.containsRate(profile.getVatRates(), dto.getVatRate()) && !sameAsStored) {
            throw new BadRequestException("Ставки НДС «" + DocFormat.rate(dto.getVatRate()) + "» нет в настройках рынка");
        }
        it.setModel(trim(dto.getModel()));
        it.setManufacturer(trim(dto.getManufacturer()));
        it.setCountry(trim(dto.getCountry()));
        it.setUnit(trim(dto.getUnit()) == null ? "шт" : dto.getUnit().trim());
        it.setQuantity(dto.getQuantity());
        it.setPurchasePrice(dto.getPurchasePrice());
        it.setPurchaseVatSame(dto.getPurchaseVatSame() == null || dto.getPurchaseVatSame());
        it.setPurchaseVatRate(dto.getPurchaseVatRate());
        it.setSupplierName(trim(dto.getSupplierName()));
        it.setMarkupPct(dto.getMarkupPct());
        it.setPriceOverride(dto.getPriceOverride());
        it.setVatRate(dto.getVatRate());
        applyRegistration(it, dto);
    }

    /** UNCHECKED/MANUAL/NOT_REQUIRED задаёт оператор; CONFIRMED/SUGGESTED — только реестр на сервере (волна 2). */
    private static void applyRegistration(ClientOfferItem it, ClientOfferItemDto dto) {
        OfferRegistrationStatus requested = dto.getRegistrationStatus() == null
                ? OfferRegistrationStatus.UNCHECKED : dto.getRegistrationStatus();
        switch (requested) {
            case NOT_REQUIRED -> {
                it.setRegistrationStatus(OfferRegistrationStatus.NOT_REQUIRED);
                it.setRegistrationText(NOT_REQUIRED_TEXT);
                it.setRegNumber(null);
                it.setSuggestionScore(null);
            }
            case CONFIRMED, SUGGESTED -> {
                if (it.getRegistrationStatus() != requested) {
                    throw new BadRequestException("Подтвердить регистрацию можно только из реестра");
                }
            }
            case UNCHECKED, MANUAL -> {
                String text = trim(dto.getRegistrationText());
                it.setRegistrationStatus(text == null ? OfferRegistrationStatus.UNCHECKED : OfferRegistrationStatus.MANUAL);
                it.setRegistrationText(text);
                it.setRegNumber(null);
                it.setSuggestionScore(null);
            }
        }
    }

    private Facility facility(Long id) {
        if (id == null) return null;
        return facilities.findById(id)
                .filter(f -> f.getMarket() == MarketContext.get())
                .orElseThrow(() -> new BadRequestException("Клиент не найден: id=" + id));
    }

    private static ClientOfferItem copyItem(ClientOfferItem it, ClientOffer offer) {
        return ClientOfferItem.builder()
                .offer(offer).lineNo(it.getLineNo()).kind(it.getKind()).name(it.getName())
                .model(it.getModel()).manufacturer(it.getManufacturer()).country(it.getCountry()).unit(it.getUnit())
                .quantity(it.getQuantity()).purchasePrice(it.getPurchasePrice()).purchaseVatSame(it.isPurchaseVatSame())
                .purchaseVatRate(it.getPurchaseVatRate()).supplierName(it.getSupplierName())
                .distributorId(it.getDistributorId()).tenderLotId(it.getTenderLotId())
                .priceRequestItemId(it.getPriceRequestItemId()).medEquipmentId(it.getMedEquipmentId())
                .markupPct(it.getMarkupPct()).priceOverride(it.getPriceOverride()).vatRate(it.getVatRate())
                .registrationStatus(it.getRegistrationStatus()).registrationText(it.getRegistrationText())
                .regNumber(it.getRegNumber()).suggestionScore(it.getSuggestionScore()).note(it.getNote())
                .build();
    }

    private static ClientOfferItem sample(ClientOffer o, int lineNo, ClientOfferItemKind kind, String name, String unit,
                                          String qty, String price, BigDecimal vat, OfferRegistrationStatus reg, String regText) {
        return ClientOfferItem.builder()
                .offer(o).lineNo(lineNo).kind(kind).name(name)
                .unit(unit == null ? "шт" : unit)
                .quantity(qty == null ? null : new BigDecimal(qty))
                .priceOverride(price == null ? null : new BigDecimal(price))
                .vatRate(vat)
                .registrationStatus(reg == null ? OfferRegistrationStatus.UNCHECKED : reg)
                .registrationText(regText)
                .build();
    }

    private static List<OfferColumn> copyColumns(List<OfferColumn> src) {
        List<OfferColumn> out = new ArrayList<>();
        if (src != null) for (OfferColumn c : src) out.add(new OfferColumn(c.getKey(), c.getLabel()));
        return out;
    }

    private static List<OfferTerm> copyTerms(List<OfferTerm> src) {
        List<OfferTerm> out = new ArrayList<>();
        if (src != null) for (OfferTerm t : src) out.add(new OfferTerm(t.getLabel() == null ? "" : t.getLabel(), t.getValue() == null ? "" : t.getValue()));
        return out;
    }

    private static List<String> encode(List<byte[]> pages) {
        return pages.stream().map(b -> Base64.getEncoder().encodeToString(b)).toList();
    }

    /** Дата КП — по часам рынка: West-Med в Уральске (UTC+5), Регион-Мед в Самаре (UTC+4). */
    static LocalDate today(Market market) {
        return LocalDate.now(market == Market.KZ ? ZoneId.of("Asia/Oral") : ZoneId.of("Europe/Samara"));
    }

    private static String likePattern(String q) {
        String escaped = q.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }

    private static void nonNegative(BigDecimal v, String what, ClientOfferItem it) {
        if (v != null && v.signum() < 0) throw new BadRequestException(what + " не может быть отрицательной: «" + it.getName() + "»");
    }

    private static void range(BigDecimal v, int min, int max, String what, ClientOfferItem it) {
        if (v != null && (v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0)) {
            throw new BadRequestException(what + " — от " + min + " до " + max + "%: «" + it.getName() + "»");
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static NotFoundException notFound(Long id) {
        return new NotFoundException("КП не найдено: id=" + id);
    }
}
```

- [ ] **Step 5: Контроллер и образец**

`src/main/java/com/vladoose/nir/controller/ClientOfferController.java`:

```java
package com.vladoose.nir.controller;

import com.vladoose.nir.dto.request.ClientOfferStatusRequest;
import com.vladoose.nir.dto.request.ClientOfferUpdateRequest;
import com.vladoose.nir.dto.response.ClientOfferListItemResponse;
import com.vladoose.nir.dto.response.ClientOfferResponse;
import com.vladoose.nir.dto.response.PreviewPagesResponse;
import com.vladoose.nir.entity.ClientOffer;
import com.vladoose.nir.entity.ClientOfferStatus;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.service.ClientOfferMapper;
import com.vladoose.nir.service.ClientOfferService;
import jakarta.validation.Valid;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** «КП клиентам» (спека §10). Чтение и скачивание — любому вошедшему, запись — ADMIN, как везде в АИС. */
@RestController
@RequestMapping("/api/client-offers")
public class ClientOfferController {

    static final MediaType DOCX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document");

    private final ClientOfferService service;
    private final ClientOfferMapper mapper;

    public ClientOfferController(ClientOfferService service, ClientOfferMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<ClientOfferListItemResponse> list(@RequestParam(defaultValue = "ALL") String status,
                                                  @RequestParam(required = false) String q) {
        return service.list(parseStatuses(status), q).stream().map(mapper::toListItem).toList();
    }

    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse create() {
        return full(service.create(currentUser()));
    }

    @GetMapping("/{id}")
    public ClientOfferResponse get(@PathVariable Long id) {
        return full(service.get(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse update(@PathVariable Long id, @Valid @RequestBody ClientOfferUpdateRequest req) {
        return full(service.update(id, req));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/duplicate")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse duplicate(@PathVariable Long id) {
        return full(service.duplicate(id, currentUser()));
    }

    @PostMapping("/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ClientOfferResponse status(@PathVariable Long id, @Valid @RequestBody ClientOfferStatusRequest req) {
        return full(service.setStatus(id, req.getStatus()));
    }

    @GetMapping("/{id}/preview")
    public PreviewPagesResponse preview(@PathVariable Long id) {
        return new PreviewPagesResponse(service.previewPages(id));
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
        ClientOffer o = service.get(id);
        return file(service.pdf(id), service.fileBaseName(o) + ".pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping("/{id}/docx")
    public ResponseEntity<byte[]> docx(@PathVariable Long id) {
        ClientOffer o = service.get(id);
        return file(service.docx(id), service.fileBaseName(o) + ".docx", DOCX);
    }

    private ClientOfferResponse full(ClientOffer o) {
        return mapper.toResponse(o, service.calculate(o), service.fileBaseName(o));
    }

    private static ResponseEntity<byte[]> file(byte[] body, String fileName, MediaType type) {
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName, StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .cacheControl(CacheControl.noStore())
                .body(body);
    }

    private static Set<ClientOfferStatus> parseStatuses(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("ALL")) return EnumSet.allOf(ClientOfferStatus.class);
        Set<ClientOfferStatus> set = EnumSet.noneOf(ClientOfferStatus.class);
        for (String s : raw.split(",")) {
            try {
                set.add(ClientOfferStatus.valueOf(s.trim()));
            } catch (IllegalArgumentException e) {
                throw new BadRequestException("Неизвестный статус КП: " + s.trim());
            }
        }
        return set;
    }

    private static String currentUser() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null ? null : a.getName();
    }
}
```

В `CompanyProfileController.java`: в конструктор добавить `ClientOfferService clientOffers` (поле `private final ClientOfferService clientOffers;`), импорты `com.vladoose.nir.dto.response.PreviewPagesResponse` и `com.vladoose.nir.service.ClientOfferService`, и метод:

```java
    /** Первая страница КП-образца с текущими реквизитами, печатью и подписью — «сразу видно, как ляжет». */
    @GetMapping("/sample-preview")
    @PreAuthorize("hasRole('ADMIN')")
    public PreviewPagesResponse samplePreview() {
        return new PreviewPagesResponse(clientOffers.samplePreview());
    }
```

- [ ] **Step 6: Тесты зелёные**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*'`
Expected: PASS.

- [ ] **Step 7: Мутации (по одной, откат копией)**

```bash
cd /Users/vlad/IdeaProjects/AIS && F=src/main/java/com/vladoose/nir/service/ClientOfferService.java && cp "$F" "$F.bak"
# 1) снять гард рынка в get()
sed -i '' 's/if (o.getMarket() != MarketContext.get()) throw notFound(id);/\/\/ гард снят/' "$F"
./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferApiTest' -q; echo "exit=$?"
cp "$F.bak" "$F"
# 2) снять проверку версии
sed -i '' 's/if (r.getVersion() != o.getVersion()) throw/if (false) throw/' "$F"
./gradlew test --tests 'com.vladoose.nir.clientoffer.ClientOfferApiTest' -q; echo "exit=$?"
cp "$F.bak" "$F" && diff -q "$F.bak" "$F" && rm "$F.bak"
```

Expected: оба прогона `exit=1` — падают `offerOfAnotherMarketIsNotFound` и `staleVersionIsConflict` соответственно. Второе — не очевидно, но верно: `@Version` Hibernate сравнивает версию **управляемой сущности** с базой и ловит только одновременные транзакции; версию из тела запроса он не видит (мы её в сущность не пишем), поэтому без явной проверки устаревшая вкладка молча затёрла бы чужие правки. После отката — `diff` молчит, полный прогон пакета PASS.

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add src/main/java/com/vladoose/nir src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
feat(kp): API «КП клиентам» — журнал с поиском, автосохранение с версией, статусы, копия, предпросмотр, PDF и Word

Гард рынка по id (findById обходит фильтр), 409 при чужой версии (и на гонку @Version), строки — через
коллекцию с сохранением порядка, ключ строки клиента эхом в ответе. Регистрацию «подтвердить» клиент не может —
только реестр (волна 2). Образец страницы для «Реквизитов и печати».

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 9: «Заодно» — реквизиты рынка в старых PDF и Excel, свободный шрифт, валюта

**Files:**
- Modify: `src/main/java/com/vladoose/nir/service/CompanyInfoProvider.java`, `PdfCompanyHeader.java`, `ExcelCompanyHeader.java`, `JasperReportService.java`, `ProfitabilityExcelService.java`
- Delete: `src/main/java/com/vladoose/nir/service/CompanyInfo.java`, `src/main/resources/fonts/DejaVuSans.ttf`, `src/main/resources/fonts/DejaVuSans-Bold.ttf`
- Create: `src/main/resources/fonts/LiberationSans-Regular.ttf`, `LiberationSans-Bold.ttf`
- Test: `src/test/java/com/vladoose/nir/clientoffer/LegacyDocumentsRequisitesTest.java`

**Interfaces:**
- Consumes: Task 3 (`CompanyProfileService.current()`), Task 5 (`CompanyLines.of`).
- Produces: `CompanyInfoProvider.Company(String shortName, String fullName, List<String> lines, String directorTitleLine, String directorName, String contacts, String currencyShort, String currencySymbol)`; `PdfCompanyHeader.addTo(Document, BaseFont, Company)`, `PdfCompanyHeader.addDirectorSignature(Document, BaseFont, Company)`; `ExcelCompanyHeader.writeTo(Sheet, Workbook, Company)` — перегрузки без `Company` удаляются.

- [ ] **Step 1: Падающий тест**

`src/test/java/com/vladoose/nir/clientoffer/LegacyDocumentsRequisitesTest.java`:

```java
package com.vladoose.nir.clientoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.ActivityApply;
import com.vladoose.nir.entity.ApplyItem;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.CompanyInfoProvider;
import com.vladoose.nir.service.JasperReportService;
import com.vladoose.nir.service.ProfitabilityExcelService;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Спека §7.4: старые PDF «Заявки» и Excel рентабельности печатают реквизиты СВОЕГО рынка, а не всегда Регион-Мед. */
@SpringBootTest
@Transactional
class LegacyDocumentsRequisitesTest {

    @Autowired CompanyInfoProvider provider;
    @Autowired JasperReportService reports;
    @Autowired ProfitabilityExcelService excel;

    @AfterEach
    void clearMarket() {
        MarketContext.clear();
    }

    @Test
    void providerReturnsMarketRequisites() {
        MarketContext.set(Market.KZ);
        CompanyInfoProvider.Company kz = provider.current();
        assertThat(kz.shortName()).isEqualTo("ТОО «West-Med»");
        assertThat(kz.lines()).contains("РНН 271 800 059 535 БИН 121 040 000 303", "Банк: АО \"Alatau City Bank\" БИК: TSESKZKA");
        assertThat(kz.currencyShort()).isEqualTo("тг");
        MarketContext.set(Market.RF);
        assertThat(provider.current().lines()).contains("ИНН 6318000846 КПП 631801001 ОГРН 1146318039218");
    }

    @Test
    void applyPdfOnKzPrintsWestMedRequisitesAndTenge() throws Exception {
        MarketContext.set(Market.KZ);
        Tender tender = new Tender();
        tender.setTenderNumber("ТЕСТ-1");
        ActivityApply apply = new ActivityApply();
        apply.setTender(tender);
        apply.setStatus("DRAFT");
        ApplyItem item = new ApplyItem();
        item.setOfferedCost(new BigDecimal("100.00"));
        item.setQuantity(2);
        String text = KpTestSupport.text(reports.generateApplyReport(apply, List.of(item)));
        assertThat(text).contains("West-Med", "БИН 121 040 000 303", "Alatau City Bank", " тг", "Ширяев")
                .doesNotContain("6318000846", "РАЙФФАЙЗЕНБАНК", "₽");
    }

    @Test
    void profitabilityExcelOnKzHasWestMedHeaderAndTengeFormat() throws Exception {
        MarketContext.set(Market.KZ);
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(excel.generate()))) {
            Sheet sheet = wb.getSheet("Сводка");
            StringBuilder header = new StringBuilder();
            for (int r = 0; r < 8; r++) {
                Row row = sheet.getRow(r);
                if (row != null && row.getCell(0) != null) header.append(row.getCell(0).getStringCellValue()).append('\n');
            }
            assertThat(header.toString()).contains("«West-Med»", "БИН 121 040 000 303").doesNotContain("6318000846");
            boolean tenge = false;
            for (Row row : sheet) {
                for (Cell cell : row) if (cell.getCellStyle().getDataFormatString().contains("₸")) tenge = true;
            }
            assertThat(tenge).isTrue();
        }
    }
}
```

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew test --tests 'com.vladoose.nir.clientoffer.LegacyDocumentsRequisitesTest'`
Expected: FAIL — компиляция (`lines()` у `Company` нет).

- [ ] **Step 2: Шрифты старых PDF — свободные вместо Arial**

```bash
SP=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/68d890e6-6182-4985-9671-4ab089fe89eb/scratchpad
[ -d "$SP/liberation-fonts-ttf-2.1.5" ] || { curl -sL -o "$SP/liberation.tgz" https://github.com/liberationfonts/liberation-fonts/files/7261482/liberation-fonts-ttf-2.1.5.tar.gz && echo "7191c669bf38899f73a2094ed00f7b800553364f90e2637010a69c0e268f25d0  $SP/liberation.tgz" | shasum -a 256 -c && tar xzf "$SP/liberation.tgz" -C "$SP"; }
cd /Users/vlad/IdeaProjects/AIS
cp "$SP/liberation-fonts-ttf-2.1.5/LiberationSans-Regular.ttf" "$SP/liberation-fonts-ttf-2.1.5/LiberationSans-Bold.ttf" src/main/resources/fonts/
git rm -q src/main/resources/fonts/DejaVuSans.ttf src/main/resources/fonts/DejaVuSans-Bold.ttf
grep -rn "DejaVuSans" src/ || echo "нет ссылок на DejaVuSans"
```

Expected: в конце — ссылки только из `JasperReportService.java` (исправляются в Step 3). Liberation Sans — метрика Arial: вёрстка старых PDF не сдвинется; знаков ₸/₽ нет и в нём — валюта словами.

- [ ] **Step 3: Код**

`src/main/java/com/vladoose/nir/service/CompanyInfoProvider.java` (целиком):

```java
package com.vladoose.nir.service;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.util.DocFormat;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Реквизиты активного рынка для шапок старых PDF/Excel — из «Системы → Реквизиты и печать» (company_profile).
 * Раньше реквизиты были зашиты константами Регион-Мед, и на KZ шапка печатала West-Med с банком Регион-Мед.
 */
@Component
public class CompanyInfoProvider {

    public record Company(String shortName, String fullName, List<String> lines, String directorTitleLine,
                          String directorName, String contacts, String currencyShort, String currencySymbol) {}

    private final CompanyProfileService profiles;

    public CompanyInfoProvider(CompanyProfileService profiles) {
        this.profiles = profiles;
    }

    public Company current() {
        CompanyProfile p = profiles.current();
        String fullName = p.getFullName() == null || p.getFullName().isBlank() ? p.getShortName() : p.getFullName();
        String title = p.getDirectorTitle() == null || p.getDirectorTitle().isBlank()
                ? p.getShortName() : p.getDirectorTitle().trim() + " " + p.getShortName();
        return new Company(p.getShortName(), fullName, CompanyLines.of(p), title, p.getDirectorName(),
                p.getSignoffContacts(), DocFormat.currencyShort(p.getMarket().currencyCode()), p.getMarket().currencySymbol());
    }
}
```

`src/main/java/com/vladoose/nir/service/PdfCompanyHeader.java` (целиком):

```java
package com.vladoose.nir.service;

import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;

import java.awt.Color;

/** Шапка и подпись старых PDF (заявка, отчёт по тендерам) — реквизиты активного рынка (CompanyInfoProvider). */
public final class PdfCompanyHeader {

    private static final Color LINE_GRAY = new Color(180, 180, 180);
    private static final Color LABEL_GRAY = new Color(100, 100, 100);

    private PdfCompanyHeader() {}

    public static void addTo(Document document, BaseFont bf, CompanyInfoProvider.Company company) throws DocumentException {
        Font companyFont = new Font(bf, 12, Font.BOLD, new Color(20, 40, 90));
        Font detailFont = new Font(bf, 8, Font.NORMAL, new Color(60, 60, 60));

        Paragraph name = new Paragraph(company.fullName(), companyFont);
        name.setSpacingAfter(3f);
        document.add(name);
        for (String line : company.lines()) {
            Paragraph p = new Paragraph(line, detailFont);
            p.setLeading(10f);
            document.add(p);
        }

        PdfPTable divider = new PdfPTable(1);
        divider.setWidthPercentage(100);
        PdfPCell rule = new PdfPCell();
        rule.setFixedHeight(1f);
        rule.setBorderWidthTop(1f);
        rule.setBorderColorTop(LINE_GRAY);
        rule.setBorderWidthBottom(0);
        rule.setBorderWidthLeft(0);
        rule.setBorderWidthRight(0);
        divider.addCell(rule);
        divider.setSpacingBefore(4f);
        divider.setSpacingAfter(10f);
        document.add(divider);
    }

    /** Блок подписи директора в конце документа. */
    public static void addDirectorSignature(Document document, BaseFont bf, CompanyInfoProvider.Company company)
            throws DocumentException {
        Font labelFont = new Font(bf, 9, Font.NORMAL, LABEL_GRAY);
        Font valueFont = new Font(bf, 10, Font.NORMAL);
        Font boldFont = new Font(bf, 10, Font.BOLD);

        Paragraph spacer = new Paragraph(" ", valueFont);
        spacer.setSpacingBefore(20f);
        document.add(spacer);

        Paragraph title = new Paragraph(company.directorTitleLine(), boldFont);
        title.setSpacingAfter(2f);
        document.add(title);

        Paragraph signLine = new Paragraph();
        signLine.add(new Phrase("____________________   ", valueFont));
        signLine.add(new Phrase(company.directorName() == null ? "" : company.directorName(), boldFont));
        signLine.setSpacingAfter(6f);
        document.add(signLine);

        if (company.contacts() != null && !company.contacts().isBlank()) {
            document.add(new Paragraph(company.contacts(), labelFont));
        }
    }
}
```

`src/main/java/com/vladoose/nir/service/ExcelCompanyHeader.java` — заменить оба метода `writeTo(...)` и `writeToInternal(...)`/`writeKv(...)` одним (стили `company`, `detail`, `separator`, `merge` оставить как есть):

```java
    /** Шапка с реквизитами активного рынка; возвращает индекс первой свободной строки. */
    public static int writeTo(Sheet sheet, Workbook wb, CompanyInfoProvider.Company company) {
        CellStyle companyStyle = company(wb);
        CellStyle detailStyle = detail(wb);
        CellStyle separator = separator(wb);

        int row = 0;
        Row r0 = sheet.createRow(row++);
        r0.setHeightInPoints(20f);
        Cell c0 = r0.createCell(0);
        c0.setCellValue(company.fullName());
        c0.setCellStyle(companyStyle);
        merge(sheet, r0.getRowNum(), 0, 4);

        for (String line : company.lines()) {
            Row r = sheet.createRow(row++);
            Cell c = r.createCell(0);
            c.setCellValue(line);
            c.setCellStyle(detailStyle);
            merge(sheet, r.getRowNum(), 0, 4);
        }

        Row sep = sheet.createRow(row++);
        sep.setHeightInPoints(4f);
        Cell sc = sep.createCell(0);
        sc.setCellStyle(separator);
        merge(sheet, sep.getRowNum(), 0, 4);
        return row;
    }
```

Удалить неиспользуемые импорты, если IDE/компилятор предупредит. Javadoc класса — «Шапка компании активного рынка для Excel-листа…» вместо «ООО «Регион-Мед»».

`JasperReportService.java`:
- в обоих методах путь шрифта `"/fonts/DejaVuSans.ttf"` → `"/fonts/LiberationSans-Regular.ttf"` и имя `"DejaVuSans.ttf"` → `"LiberationSans-Regular.ttf"`;
- в начале `generateTenderReport` и `generateApplyReport` взять `CompanyInfoProvider.Company company = companyInfoProvider.current();` и передавать `company` в `PdfCompanyHeader.addTo(document, bf, company)`;
- `PdfCompanyHeader.addDirectorSignature(document, bf)` → `PdfCompanyHeader.addDirectorSignature(document, bf, company)`;
- все пять `String.format("%,.2f ₽", X)` → `String.format("%,.2f %s", X, company.currencyShort())` (две в отчёте по тендерам, три в заявке).

`ProfitabilityExcelService.java`:
- в `generate()` первой строкой внутри `try` — `CompanyInfoProvider.Company company = companyInfoProvider.current();`, все четыре `ExcelCompanyHeader.writeTo(…, companyInfoProvider.current())` → `writeTo(…, company)`;
- `CellStyle money = moneyStyle(wb);` → `moneyStyle(wb, company.currencySymbol())`; метод:

```java
    /** Знак валюты рынка: Excel рисует ₸/₽ системным шрифтом — в отличие от PDF, здесь знак уместен. */
    private CellStyle moneyStyle(Workbook wb, String currencySymbol) {
        CellStyle s = wb.createCellStyle();
        DataFormat fmt = wb.createDataFormat();
        s.setDataFormat(fmt.getFormat("#,##0.00 \"" + currencySymbol + "\""));
        return s;
    }
```

Удалить `CompanyInfo.java`:

```bash
cd /Users/vlad/IdeaProjects/AIS && git rm -q src/main/java/com/vladoose/nir/service/CompanyInfo.java && grep -rn "CompanyInfo\." src/ || echo "констант CompanyInfo больше нет"
```

- [ ] **Step 4: Тесты зелёные, сборка без старых ссылок**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && ./gradlew compileJava -q && ./gradlew test --tests 'com.vladoose.nir.clientoffer.*' --tests '*Apply*' --tests '*Report*' --tests '*Profitab*'`
Expected: PASS (если фильтры `*Apply*`/`*Report*`/`*Profitab*` что-то находят — они тоже зелёные).

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add -A src/main/java/com/vladoose/nir/service src/main/resources/fonts src/test/java/com/vladoose/nir/clientoffer && git commit -q -F - <<'EOF'
fix(reports): PDF заявки и Excel рентабельности печатают реквизиты своего рынка; свободный шрифт вместо Arial

Шапка на KZ печатала West-Med рядом с ИНН и банком Регион-Мед: реквизиты были зашиты константами CompanyInfo
(удалены). Теперь — из «Реквизитов и печати». Шрифт DejaVuSans.ttf на деле был Arial (лицензия, нет ₸/₽) —
заменён Liberation Sans с той же метрикой; валюта в PDF словами по рынку, в Excel — знак рынка.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 10: Фронт — основа и страница «Реквизиты и печать»

**Files:**
- Modify: `frontend/package.json` (+ `@angular/cdk`), `frontend/src/styles.scss` (стили перетаскивания), `src/app/app.config.ts` (иконки), `src/app/app.routes.ts` (ленивый маршрут), `src/app/layout/layout.component.ts` (пункт меню), `src/app/services/api.service.ts`
- Create: `src/app/services/company-profile.service.ts`, `src/app/shared/client-offer.ts`, `src/app/shared/offer-terms-editor.component.ts`, `src/app/shared/offer-columns-editor.component.ts`, `src/app/pages/company-profile/company-profile.component.ts`

(пути `src/app/…` — внутри `frontend/`)

**Interfaces:**
- Consumes: REST Task 3 и Task 8 (`/api/company-profile*`, `/api/client-offers*`).
- Produces:
  - `ApiService`: `getCompanyProfile()`, `saveCompanyProfile(body)`, `uploadCompanyImage(kind, file, removeBackground)`, `getCompanyImage(kind): Observable<Blob>`, `deleteCompanyImage(kind)`, `getCompanyProfileSample(): Observable<{pages: string[]}>`, `getClientOffers({status?, q?})`, `createClientOffer()`, `getClientOffer(id)`, `saveClientOffer(id, body)`, `deleteClientOffer(id)`, `duplicateClientOffer(id)`, `setClientOfferStatus(id, status)`, `getClientOfferPreview(id): Observable<{pages: string[]}>`, `downloadClientOfferPdf(id): Observable<Blob>`, `downloadClientOfferDocx(id): Observable<Blob>`.
  - `CompanyProfileService` (кеш профиля рынка): `profile$()`, `invalidate()`, `vatHints$(): Observable<VatHints>`; `VatHints { registered: string; standard: string }`, `DEFAULT_VAT_HINTS`.
  - `shared/client-offer.ts`: типы `ClientOffer`, `OfferItem`, `ItemCalc`, `OfferTotals`, `OfferColumn`, `OfferTerm`, `OfferStatus`, `ItemKind`, `TermsStyle`, `Rounding`, `RegStatus`; `COLUMN_CATALOG`, `OFFER_STATUS_LABELS`, `ROUNDING_OPTIONS`, `TERM_SUGGESTIONS`, `UNIT_SUGGESTIONS`; функции `columnInfo`, `defaultColumnLabel`, `vatLabel`, `money`, `numText`, `parseNum`, `dateText`, `longDateText`, `uid`, `newItem`, `toRequest`, `saveBlob`.
  - `<app-offer-terms-editor [terms] [offerDate] (changed)>`, `<app-offer-columns-editor [columns] [vatEnabled] (changed)>` — правят переданный массив на месте и сообщают `changed`.

- [ ] **Step 1: `@angular/cdk` и стили перетаскивания**

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && npm install @angular/cdk@~21.2.7 && node -e "console.log(require('./package-lock.json').packages['node_modules/@angular/cdk'].version)"
```

Expected: `21.2.x`.

В `frontend/src/styles.scss` **перед** строкой `@media (max-width: 900px) {` (последний блок файла) вставить:

```scss
/* --- перетаскивание строк КП (Angular CDK drag-drop). Превью перетаскивания CDK вешает прямо на body —
   стили компонента до него не достают, поэтому они здесь. --- */
.cdk-drag-preview { box-shadow: var(--shadow-lg); border-radius: 8px; background: var(--surface); opacity: .95; }
.cdk-drag-placeholder { opacity: .35; }
.cdk-drag-animating,
.cdk-drop-list-dragging .cdk-drag:not(.cdk-drag-placeholder) { transition: transform 200ms cubic-bezier(0, 0, 0.2, 1); }

```

- [ ] **Step 2: Иконки, маршрут, пункт меню**

`src/app/app.config.ts`: в импорт из `@lucide/angular` и в `provideLucideIcons(...)` добавить (в **оба** места) `LucideStamp, LucideFilePenLine, LucideGripVertical, LucideCopy, LucideShare2, LucideFileDown`.

`src/app/app.routes.ts`: в `children` после строки `email-template` добавить:

```ts
      { path: 'company-profile', canActivate: [adminGuard],
        loadComponent: () => import('./pages/company-profile/company-profile.component').then(m => m.CompanyProfileComponent) },
```

`src/app/layout/layout.component.ts`: после ссылки «Шаблон письма КП» добавить:

```html
            <a *ngIf="auth.isAdmin()" routerLink="/company-profile" routerLinkActive="active">
              <svg lucideIcon="stamp" [size]="16"></svg> Реквизиты и печать
            </a>
```

- [ ] **Step 3: Методы API**

В конец класса `ApiService` (`src/app/services/api.service.ts`, перед закрывающей `}`):

```ts
  // === КП клиенту: реквизиты и печать (спека 2026-10-02-client-kp-constructor §7) ===
  getCompanyProfile(): Observable<any> {
    return this.http.get<any>(`${this.base}/company-profile`);
  }
  saveCompanyProfile(body: any): Observable<any> {
    return this.http.put<any>(`${this.base}/company-profile`, body);
  }
  uploadCompanyImage(kind: string, file: File, removeBackground: boolean): Observable<any> {
    const fd = new FormData();
    fd.append('file', file);
    return this.http.post<any>(`${this.base}/company-profile/images/${kind}`, fd,
      { params: { removeBackground: String(removeBackground) } });
  }
  /** Только blob через HttpClient: голый <img src> на /api не несёт X-Market (CLAUDE.md §14). */
  getCompanyImage(kind: string): Observable<Blob> {
    return this.http.get(`${this.base}/company-profile/images/${kind}`, { responseType: 'blob' });
  }
  deleteCompanyImage(kind: string): Observable<any> {
    return this.http.delete<any>(`${this.base}/company-profile/images/${kind}`);
  }
  getCompanyProfileSample(): Observable<{ pages: string[] }> {
    return this.http.get<{ pages: string[] }>(`${this.base}/company-profile/sample-preview`);
  }

  // === КП клиенту (спека §10) ===
  getClientOffers(params: { status?: string; q?: string } = {}): Observable<any[]> {
    const p: any = {};
    if (params.status) p.status = params.status;
    if (params.q) p.q = params.q;
    return this.http.get<any[]>(`${this.base}/client-offers`, { params: p });
  }
  createClientOffer(): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers`, {});
  }
  getClientOffer(id: number): Observable<any> {
    return this.http.get<any>(`${this.base}/client-offers/${id}`);
  }
  saveClientOffer(id: number, body: any): Observable<any> {
    return this.http.put<any>(`${this.base}/client-offers/${id}`, body);
  }
  deleteClientOffer(id: number): Observable<void> {
    return this.http.delete<void>(`${this.base}/client-offers/${id}`);
  }
  duplicateClientOffer(id: number): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${id}/duplicate`, {});
  }
  setClientOfferStatus(id: number, status: string): Observable<any> {
    return this.http.post<any>(`${this.base}/client-offers/${id}/status`, { status });
  }
  getClientOfferPreview(id: number): Observable<{ pages: string[] }> {
    return this.http.get<{ pages: string[] }>(`${this.base}/client-offers/${id}/preview`);
  }
  downloadClientOfferPdf(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/client-offers/${id}/pdf`, { responseType: 'blob' });
  }
  downloadClientOfferDocx(id: number): Observable<Blob> {
    return this.http.get(`${this.base}/client-offers/${id}/docx`, { responseType: 'blob' });
  }
```

- [ ] **Step 4: Общие типы и функции КП**

`src/app/shared/client-offer.ts`:

```ts
/**
 * КП клиенту: типы, подписи и общие функции экранов (спека docs/superpowers/specs/2026-10-02-client-kp-constructor-design.md).
 * Формул здесь НЕТ: цены, НДС и маржу считает сервер (ClientOfferCalculator), экран показывает его числа.
 */
export type OfferStatus = 'DRAFT' | 'SENT' | 'ACCEPTED' | 'REJECTED';
export type ItemKind = 'ITEM' | 'SECTION' | 'INCLUDED';
export type TermsStyle = 'TABLE' | 'LIST' | 'NONE';
export type Rounding = 'NONE' | 'UNIT' | 'TEN' | 'HUNDRED';
export type Signoff = 'COMPANY' | 'DIRECTOR';
export type RegStatus = 'UNCHECKED' | 'SUGGESTED' | 'CONFIRMED' | 'NOT_REQUIRED' | 'MANUAL';

export interface OfferColumn { key: string; label?: string | null; }
export interface OfferTerm { label: string; value: string; }

export interface ItemCalc {
  cost: number | null; costTotal: number | null; markupPct: number | null; priceNet: number | null;
  price: number | null; sum: number | null; vatSum: number | null; sumNet: number | null;
  profit: number | null; effectiveVatRate: number | null;
}

export interface OfferItem {
  id?: number | null;
  key: string;
  lineNo?: number;
  kind: ItemKind;
  name: string;
  model?: string | null;
  manufacturer?: string | null;
  country?: string | null;
  unit: string;
  quantity: number | null;
  purchasePrice: number | null;
  purchaseVatSame: boolean;
  purchaseVatRate: number | null;
  supplierName?: string | null;
  markupPct: number | null;
  priceOverride: number | null;
  vatRate: number | null;
  registrationStatus: RegStatus;
  registrationText?: string | null;
  regNumber?: string | null;
  note?: string | null;
  calc?: ItemCalc | null;
  /** только экран: строка раскрыта / отмечена галочкой */
  _open?: boolean;
  _sel?: boolean;
}

export interface VatLine { rate: number; amount: number; }

export interface OfferTotals {
  sum: number; vat: VatLine[]; vatTotal: number; purchase: number; cost: number; revenueNet: number;
  profit: number; markupAvg: number | null; itemCount: number; noPurchaseCount: number; unconfirmedRegistrationCount: number;
}

export interface ClientOffer {
  id: number; number: number; offerDate: string; status: OfferStatus;
  facilityId: number | null; facilityName: string | null; recipient: string | null; tenderId: number | null;
  title: string; subject: string | null; intro: string | null;
  vatEnabled: boolean; defaultMarkupPct: number; rounding: Rounding;
  columns: OfferColumn[]; detailsInName: boolean; terms: OfferTerm[]; termsStyle: TermsStyle;
  showAmountInWords: boolean; showVatBreakdown: boolean; landscape: boolean;
  signoff: Signoff; signoffContacts: boolean; withStamp: boolean; internalNote: string | null;
  version: number; currency: string; items: OfferItem[]; totals: OfferTotals; fileBaseName: string;
  createdBy: string | null; createdAt: string; updatedAt: string; sentAt: string | null;
}

export interface ColumnInfo { key: string; label: string; labelNoVat?: string; vatOnly?: boolean; }

/** Каталог колонок — подписи по умолчанию как на сервере (OfferColumnKey). */
export const COLUMN_CATALOG: ColumnInfo[] = [
  { key: 'NUM', label: '№' },
  { key: 'NAME', label: 'Наименование' },
  { key: 'MODEL', label: 'Модель / артикул' },
  { key: 'MANUFACTURER', label: 'Производитель' },
  { key: 'COUNTRY', label: 'Страна' },
  { key: 'UNIT', label: 'Ед. изм.' },
  { key: 'QTY', label: 'Кол-во' },
  { key: 'PRICE', label: 'Цена (с НДС)', labelNoVat: 'Цена' },
  { key: 'PRICE_NET', label: 'Цена без НДС', vatOnly: true },
  { key: 'VAT_RATE', label: 'Ставка НДС', vatOnly: true },
  { key: 'VAT_SUM', label: 'Сумма НДС', vatOnly: true },
  { key: 'SUM_NET', label: 'Сумма без НДС', vatOnly: true },
  { key: 'SUM', label: 'Сумма (с НДС)', labelNoVat: 'Сумма' },
  { key: 'REGISTRATION', label: 'Регистрация' },
  { key: 'NOTE', label: 'Примечание' },
];

export const OFFER_STATUS_LABELS: Record<OfferStatus, string> = {
  DRAFT: 'Черновик', SENT: 'Отправлено', ACCEPTED: 'Принято', REJECTED: 'Отклонено',
};

export const ROUNDING_OPTIONS: { v: Rounding; l: string }[] = [
  { v: 'NONE', l: 'Не округлять' }, { v: 'UNIT', l: 'До целых' }, { v: 'TEN', l: 'До 10' }, { v: 'HUNDRED', l: 'До 100' },
];

/** Быстрые условия (спека §8.2): из КП отца на ИВЛ и от 24.09. */
export const TERM_SUGGESTIONS: OfferTerm[] = [
  { label: 'Срок действия предложения', value: '10 дней' },
  { label: 'Условия поставки', value: 'DDP Заказчик' },
  { label: 'Порядок оплаты', value: '100% предоплата' },
  { label: 'Форма оплаты', value: 'безналичная' },
  { label: 'Срок поставки', value: '30 рабочих дней после поступления предоплаты' },
  { label: 'Гарантия', value: '12 месяцев с даты подписания акта установки оборудования' },
  { label: 'Обучение', value: 'Включено в стоимость' },
  { label: 'Сведения о регистрации', value: '' },
];

export const UNIT_SUGGESTIONS = ['шт', 'набор', 'упак', 'компл', 'фл', 'л', 'кг', 'м'];

export function columnInfo(key: string): ColumnInfo | undefined {
  return COLUMN_CATALOG.find(c => c.key === key);
}

export function defaultColumnLabel(key: string, vatEnabled: boolean): string {
  const c = columnInfo(key);
  if (!c) return key;
  return !vatEnabled && c.labelNoVat ? c.labelNoVat : c.label;
}

/** «5%», «12,5%», «Без НДС». */
export function vatLabel(rate: number | null | undefined): string {
  return rate == null ? 'Без НДС' : String(rate).replace('.', ',') + '%';
}

const MONEY = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const NUMBER = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 3 });

/** «9 902 248,23»; пусто для null. */
export function money(v: number | null | undefined): string {
  return v == null ? '' : MONEY.format(v);
}

/** Число в поле ввода: «126 000», дробь через запятую; пусто для null. */
export function numText(v: number | null | undefined): string {
  return v == null ? '' : NUMBER.format(v);
}

/** Число из поля: пробелы и неразрывные пробелы между разрядами, запятая или точка. Пусто или мусор — null. */
export function parseNum(text: string | null | undefined): number | null {
  if (text == null) return null;
  const t = String(text).replace(/[\s  ]/g, '').replace(',', '.');
  if (!t) return null;
  const n = Number(t);
  return Number.isFinite(n) ? n : null;
}

/** «2026-09-14» → «14.09.2026». */
export function dateText(iso: string | null | undefined): string {
  if (!iso) return '';
  const [y, m, d] = iso.split('-');
  return `${d}.${m}.${y}`;
}

const MONTHS_GEN = ['января', 'февраля', 'марта', 'апреля', 'мая', 'июня', 'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря'];

/** «2026-09-23» → «"23" сентября 2026 г.» в «ёлочках» — как в КП отца: «23» сентября 2026 г. */
export function longDateText(iso: string): string {
  const [y, m, d] = iso.split('-');
  return `«${d}» ${MONTHS_GEN[Number(m) - 1]} ${y} г.`;
}

export function uid(): string {
  return Math.random().toString(36).slice(2, 10) + Date.now().toString(36);
}

export function newItem(kind: ItemKind, vatRate: number | null): OfferItem {
  return {
    id: null, key: uid(), kind, name: '',
    unit: 'шт', quantity: kind === 'ITEM' ? 1 : null,
    purchasePrice: null, purchaseVatSame: true, purchaseVatRate: null,
    markupPct: null, priceOverride: null, vatRate: kind === 'ITEM' ? vatRate : null,
    registrationStatus: 'UNCHECKED', registrationText: null, note: null, calc: null,
  };
}

/** Тело PUT: только то, что вводит оператор; вычисляемое и экранное не отправляем. */
export function toRequest(o: ClientOffer): any {
  return {
    version: o.version, number: o.number, offerDate: o.offerDate, facilityId: o.facilityId, recipient: o.recipient,
    title: o.title, subject: o.subject, intro: o.intro, vatEnabled: o.vatEnabled, defaultMarkupPct: o.defaultMarkupPct,
    rounding: o.rounding, columns: o.columns, detailsInName: o.detailsInName, terms: o.terms, termsStyle: o.termsStyle,
    showAmountInWords: o.showAmountInWords, showVatBreakdown: o.showVatBreakdown, landscape: o.landscape,
    signoff: o.signoff, signoffContacts: o.signoffContacts, withStamp: o.withStamp, internalNote: o.internalNote,
    items: o.items.map(it => ({
      id: it.id ?? null, key: it.key, kind: it.kind, name: it.name, model: it.model ?? null,
      manufacturer: it.manufacturer ?? null, country: it.country ?? null, unit: it.unit, quantity: it.quantity,
      purchasePrice: it.purchasePrice, purchaseVatSame: it.purchaseVatSame, purchaseVatRate: it.purchaseVatRate,
      supplierName: it.supplierName ?? null, markupPct: it.markupPct, priceOverride: it.priceOverride, vatRate: it.vatRate,
      registrationStatus: it.registrationStatus, registrationText: it.registrationText ?? null, note: it.note ?? null,
    })),
  };
}

/** Скачивание blob файлом (тот же приём, что в «Заявках»; файл с /api — только через HttpClient blob). */
export function saveBlob(blob: Blob, fileName: string) {
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}
```

`src/app/services/company-profile.service.ts`:

```ts
import { Injectable } from '@angular/core';
import { Observable, catchError, map, shareReplay, throwError } from 'rxjs';
import { ApiService } from './api.service';

export interface VatHints { registered: string; standard: string; }

/** Пока профиль не пришёл — без чисел: устаревшая ставка хуже, чем никакой (были «НДС 12%» при 16%). */
export const DEFAULT_VAT_HINTS: VatHints = { registered: 'льготная ставка НДС', standard: 'облагается НДС' };

/**
 * Реквизиты рынка: ставки НДС и умолчания КП (спека §7). Кешируется до сохранения на «Реквизитах и печати»;
 * смена рынка перезагружает страницу — кеш уходит вместе с ней.
 */
@Injectable({ providedIn: 'root' })
export class CompanyProfileService {
  private cache$: Observable<any> | null = null;

  constructor(private api: ApiService) {}

  profile$(): Observable<any> {
    if (!this.cache$) {
      this.cache$ = this.api.getCompanyProfile().pipe(
        catchError(e => { this.cache$ = null; return throwError(() => e); }),
        shareReplay(1));
    }
    return this.cache$;
  }

  invalidate() {
    this.cache$ = null;
  }

  /** «льготная ставка НДС 5%» / «НДС 16%» — из настроек рынка, а не зашитым числом. */
  vatHints$(): Observable<VatHints> {
    return this.profile$().pipe(map(p => ({
      registered: hint(p.vatRegistered, 'льготная ставка НДС '),
      standard: hint(p.vatNotRegistrable, 'НДС '),
    })));
  }
}

function hint(rate: number | null | undefined, prefix: string): string {
  return rate == null ? 'без НДС' : prefix + String(rate).replace('.', ',') + '%';
}
```

- [ ] **Step 5: Редакторы условий и колонок**

`src/app/shared/offer-terms-editor.component.ts`:

```ts
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { OfferTerm, TERM_SUGGESTIONS, longDateText } from './client-offer';

/** Условия КП «название — значение» (спека §8.2). Тот же редактор — в «Реквизитах и печати» (условия по умолчанию). */
@Component({
  selector: 'app-offer-terms-editor',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="terms" cdkDropList (cdkDropListDropped)="drop($event)">
      <div class="term" *ngFor="let t of terms; let i = index" cdkDrag>
        <button type="button" class="grip" cdkDragHandle aria-label="Перетащить условие" title="Перетащить">
          <svg lucideIcon="grip-vertical" [size]="16"></svg>
        </button>
        <input class="t-label" [(ngModel)]="t.label" (ngModelChange)="changed.emit()" placeholder="Название (можно пусто)" aria-label="Название условия" />
        <textarea class="t-value" rows="1" [(ngModel)]="t.value" (ngModelChange)="changed.emit()" placeholder="Значение" aria-label="Значение условия"></textarea>
        <button type="button" class="t-del" (click)="remove(i)" aria-label="Удалить условие" title="Удалить">×</button>
      </div>
    </div>
    <p class="none" *ngIf="!terms.length">Условий нет — добавьте из списка ниже.</p>
    <div class="chips">
      <span class="hint">Добавить:</span>
      <button type="button" class="chip" *ngFor="let s of suggestions" (click)="add(s.label, s.value)">{{ s.label }}</button>
      <button type="button" class="chip" *ngIf="offerDate" (click)="add('Дата коммерческого предложения', longDate())">Дата КП</button>
      <button type="button" class="chip" (click)="add('', '')">+ своё</button>
    </div>
  `,
  styles: [`
    .terms { display: flex; flex-direction: column; gap: 6px; }
    .term { display: grid; grid-template-columns: 28px minmax(140px, .8fr) minmax(180px, 1.2fr) 32px; gap: 6px; align-items: start; background: var(--surface); }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 6px 2px; display: flex; }
    .t-label, .t-value { width: 100%; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 13px; }
    .t-value { resize: vertical; min-height: 32px; }
    .t-del { background: none; border: none; color: var(--text-muted); font-size: 18px; cursor: pointer; }
    .t-del:hover { color: var(--danger-text); }
    .none { color: var(--text-muted); font-size: 13px; margin: 4px 0; }
    .chips { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    /* чип: 15% тинта + текстовый токен (правило kit) */
    .chip { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border: none; border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip:hover { background: color-mix(in srgb, var(--accent) 25%, transparent); }
    @media (max-width: 900px) {
      .term { grid-template-columns: 28px 1fr 32px; grid-template-areas: "grip label del" "grip value del"; }
      .grip { grid-area: grip; } .t-label { grid-area: label; } .t-value { grid-area: value; } .t-del { grid-area: del; }
    }
  `],
})
export class OfferTermsEditorComponent {
  @Input({ required: true }) terms!: OfferTerm[];
  @Input() offerDate: string | null = null;
  @Output() changed = new EventEmitter<void>();
  readonly suggestions = TERM_SUGGESTIONS;

  drop(e: CdkDragDrop<OfferTerm[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.terms, e.previousIndex, e.currentIndex);
    this.changed.emit();
  }

  add(label: string, value: string) {
    this.terms.push({ label, value });
    this.changed.emit();
  }

  remove(i: number) {
    this.terms.splice(i, 1);
    this.changed.emit();
  }

  longDate(): string {
    return this.offerDate ? longDateText(this.offerDate) : '';
  }
}
```

`src/app/shared/offer-columns-editor.component.ts`:

```ts
import { Component, EventEmitter, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { COLUMN_CATALOG, OfferColumn, columnInfo, defaultColumnLabel } from './client-offer';

/** Колонки таблицы КП: порядок перетаскиванием, своя подпись, «Наименование» — обязательна (спека §6.2). */
@Component({
  selector: 'app-offer-columns-editor',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="cols" cdkDropList (cdkDropListDropped)="drop($event)">
      <div class="col" *ngFor="let c of columns; let i = index" cdkDrag [class.muted]="!vatEnabled && vatOnly(c.key)">
        <button type="button" class="grip" cdkDragHandle aria-label="Перетащить колонку" title="Перетащить">
          <svg lucideIcon="grip-vertical" [size]="16"></svg>
        </button>
        <span class="c-name">{{ defaultLabel(c.key) }}</span>
        <input class="c-label" [(ngModel)]="c.label" (ngModelChange)="changed.emit()" [placeholder]="'Подпись: ' + defaultLabel(c.key)"
               [attr.aria-label]="'Своя подпись колонки ' + defaultLabel(c.key)" />
        <span class="c-note" *ngIf="!vatEnabled && vatOnly(c.key)">без НДС не печатается</span>
        <button type="button" class="c-del" *ngIf="c.key !== 'NAME'" (click)="remove(i)"
                [attr.aria-label]="'Убрать колонку ' + defaultLabel(c.key)" title="Убрать">×</button>
      </div>
    </div>
    <div class="more" *ngIf="hidden.length">
      <span class="hint">Добавить колонку:</span>
      <button type="button" class="chip" *ngFor="let h of hidden" (click)="add(h.key)">+ {{ defaultLabel(h.key) }}</button>
    </div>
  `,
  styles: [`
    .cols { display: flex; flex-direction: column; gap: 4px; }
    .col { display: grid; grid-template-columns: 28px 150px minmax(120px, 1fr) auto 32px; gap: 6px; align-items: center; background: var(--surface); }
    .col.muted .c-name { color: var(--text-muted); }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 4px 2px; display: flex; }
    .c-name { font-size: 13px; color: var(--text); }
    .c-label { width: 100%; padding: 5px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .c-note { font-size: 11px; color: var(--text-muted); }
    .c-del { background: none; border: none; color: var(--text-muted); font-size: 18px; cursor: pointer; }
    .c-del:hover { color: var(--danger-text); }
    .more { display: flex; flex-wrap: wrap; gap: 6px; align-items: center; margin-top: 8px; }
    .hint { font-size: 12px; color: var(--text-muted); }
    .chip { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); border: none; border-radius: 999px; padding: 4px 10px; font-size: 12px; cursor: pointer; }
    .chip:hover { background: color-mix(in srgb, var(--accent) 25%, transparent); }
    @media (max-width: 900px) {
      .col { grid-template-columns: 28px 1fr 32px; grid-template-areas: "grip name del" "grip label del" "grip note del"; }
      .grip { grid-area: grip; } .c-name { grid-area: name; } .c-label { grid-area: label; } .c-note { grid-area: note; } .c-del { grid-area: del; }
    }
  `],
})
export class OfferColumnsEditorComponent {
  @Input({ required: true }) columns!: OfferColumn[];
  @Input() vatEnabled = true;
  @Output() changed = new EventEmitter<void>();

  get hidden() {
    return COLUMN_CATALOG.filter(c => !this.columns.some(x => x.key === c.key));
  }

  vatOnly(key: string): boolean {
    return !!columnInfo(key)?.vatOnly;
  }

  defaultLabel(key: string): string {
    return defaultColumnLabel(key, this.vatEnabled);
  }

  drop(e: CdkDragDrop<OfferColumn[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.columns, e.previousIndex, e.currentIndex);
    this.changed.emit();
  }

  add(key: string) {
    this.columns.push({ key, label: null });
    this.changed.emit();
  }

  remove(i: number) {
    if (this.columns[i].key === 'NAME') return;
    this.columns.splice(i, 1);
    this.changed.emit();
  }
}
```

- [ ] **Step 6: Страница «Реквизиты и печать»**

`src/app/pages/company-profile/company-profile.component.ts`:

```ts
import { Component, ChangeDetectorRef, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import { OfferTermsEditorComponent } from '../../shared/offer-terms-editor.component';
import { OfferColumnsEditorComponent } from '../../shared/offer-columns-editor.component';
import { numText, parseNum, vatLabel } from '../../shared/client-offer';

type ImageKind = 'logo' | 'stamp' | 'signature';

/** «Система → Реквизиты и печать» (спека §7): бланк, подписант, картинки, ставки НДС, умолчания нового КП, образец. */
@Component({
  selector: 'app-company-profile',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, OfferTermsEditorComponent, OfferColumnsEditorComponent],
  template: `
    <div class="cp" *ngIf="p; else loadingTpl">
      <div class="page-head">
        <div>
          <h2>Реквизиты и печать</h2>
          <p class="subtitle">{{ marketLabel }} — бланк, подпись и умолчания нового КП. Меняется только активный рынок.</p>
        </div>
        <button type="button" class="btn btn-primary" [disabled]="saving" (click)="save()">{{ saving ? 'Сохраняю…' : 'Сохранить' }}</button>
      </div>

      <div class="cp-grid">
        <div class="cp-forms">
          <section class="card">
            <h3>Бланк</h3>
            <div class="grid2">
              <label>Краткое название <input [(ngModel)]="p.shortName" placeholder="ТОО «West-Med»" /></label>
              <label>Полное название <input [(ngModel)]="p.fullName" /></label>
              <label>Над логотипом слева <textarea rows="2" [(ngModel)]="p.headerLeft"></textarea></label>
              <label>Над логотипом справа <textarea rows="2" [(ngModel)]="p.headerRight"></textarea></label>
              <label>Название вместо логотипа <input [(ngModel)]="p.brandText" /></label>
              <label>БИН / ИНН <input [(ngModel)]="p.binInn" /></label>
              <label class="wide">Строка идентификаторов <input [(ngModel)]="p.idsLine" placeholder="РНН … БИН …" /></label>
              <label class="wide">Адрес <textarea rows="2" [(ngModel)]="p.address"></textarea></label>
              <label class="wide">Счета <textarea rows="2" [(ngModel)]="p.accounts"></textarea></label>
              <label>Банк <input [(ngModel)]="p.bankName" /></label>
              <label>БИК <input [(ngModel)]="p.bik" /></label>
              <label>Телефон <input [(ngModel)]="p.phone" /></label>
              <label>Почта <input [(ngModel)]="p.email" /></label>
            </div>
          </section>

          <section class="card">
            <h3>Подписант</h3>
            <div class="grid2">
              <label>Должность <input [(ngModel)]="p.directorTitle" placeholder="Директор" /></label>
              <label>ФИО <input [(ngModel)]="p.directorName" /></label>
              <label class="wide">Контакты под подписью <input [(ngModel)]="p.signoffContacts" /></label>
            </div>
          </section>

          <section class="card">
            <h3>Логотип, печать, подпись</h3>
            <p class="hint">Скан на белом листе: белый фон уберётся сам. Готовый PNG с прозрачностью не меняется.</p>
            <div class="images">
              <div class="img-card" *ngFor="let k of kinds">
                <div class="img-title">{{ k.label }}</div>
                <div class="img-box">
                  <img *ngIf="images[k.kind]" [src]="images[k.kind]" [alt]="k.label" />
                  <span class="hint" *ngIf="!images[k.kind]">не загружено</span>
                </div>
                <label class="check"><input type="checkbox" [(ngModel)]="removeBg[k.kind]" /> убрать белый фон</label>
                <div class="img-actions">
                  <label class="btn btn-line file-btn">{{ uploading === k.kind ? 'Загружаю…' : 'Загрузить' }}
                    <input type="file" accept="image/png,image/jpeg" (change)="upload(k.kind, $event)" hidden />
                  </label>
                  <button type="button" class="btn btn-line" *ngIf="images[k.kind]" (click)="removeImage(k.kind, k.label)">Удалить</button>
                </div>
              </div>
            </div>
            <label class="narrow">Диаметр печати, мм <input type="number" min="20" max="60" [(ngModel)]="p.stampSizeMm" /></label>
          </section>

          <section class="card">
            <h3>НДС</h3>
            <div class="rates">
              <span class="rate" *ngFor="let r of p.vatRates; let i = index">{{ vatLabel(r) }}
                <button type="button" (click)="removeRate(i)" [attr.aria-label]="'Убрать ставку ' + vatLabel(r)">×</button></span>
            </div>
            <div class="rate-add">
              <input inputmode="decimal" placeholder="Ставка, %" [(ngModel)]="newRate" aria-label="Новая ставка, %" />
              <button type="button" class="btn btn-line" (click)="addRate()">Добавить</button>
              <button type="button" class="btn btn-line" *ngIf="!hasNoVat()" (click)="addNoVat()">+ Без НДС</button>
            </div>
            <div class="grid3">
              <label>Новая строка
                <select [(ngModel)]="p.vatDefault"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
              <label>Подтверждено РУ
                <select [(ngModel)]="p.vatRegistered"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
              <label>«Не подлежит регистрации»
                <select [(ngModel)]="p.vatNotRegistrable"><option *ngFor="let r of p.vatRates" [ngValue]="r">{{ vatLabel(r) }}</option></select></label>
            </div>
          </section>

          <section class="card">
            <h3>Новое КП по умолчанию</h3>
            <div class="grid3">
              <label>Наценка, % <input inputmode="decimal" [value]="numText(p.defaultMarkupPct)" (change)="setDefaultMarkup($event)" /></label>
              <label>Следующий «исх. №» <input type="number" min="1" [(ngModel)]="p.nextNumber" /></label>
              <label>Условия
                <select [(ngModel)]="p.defaultTermsStyle">
                  <option value="LIST">списком под итогом</option><option value="TABLE">таблицей над позициями</option><option value="NONE">не печатать</option>
                </select></label>
            </div>
            <label class="wide">Вводная фраза <textarea rows="2" [(ngModel)]="p.defaultIntro"></textarea></label>
            <h4>Условия</h4>
            <app-offer-terms-editor [terms]="p.defaultTerms"></app-offer-terms-editor>
            <h4>Колонки таблицы</h4>
            <app-offer-columns-editor [columns]="p.defaultColumns"></app-offer-columns-editor>
          </section>
        </div>

        <aside class="cp-sample card">
          <h3>Образец</h3>
          <p class="hint">Первая страница КП с этими реквизитами — обновляется после сохранения и загрузки картинок.</p>
          <img *ngIf="sample" [src]="sample" alt="Образец первой страницы КП" />
          <p class="hint" *ngIf="sampleLoading">Собираю образец…</p>
          <button type="button" class="btn btn-line" (click)="loadSample()">Обновить образец</button>
        </aside>
      </div>
    </div>
    <ng-template #loadingTpl><p class="empty">{{ loadError || 'Загрузка…' }}</p></ng-template>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .cp-grid { display: grid; grid-template-columns: minmax(0, 1fr) 380px; gap: 16px; align-items: start; }
    .cp-forms { display: flex; flex-direction: column; gap: 12px; min-width: 0; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; }
    .card h3 { margin: 0 0 10px; font-size: 15px; }
    .card h4 { margin: 14px 0 6px; font-size: 13px; color: var(--text-muted); }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 12px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px 12px; margin-top: 10px; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.wide { grid-column: 1 / -1; margin-top: 8px; }
    label.narrow { max-width: 220px; margin-top: 10px; }
    label.check { flex-direction: row; align-items: center; gap: 6px; color: var(--text); font-size: 13px; }
    input, textarea, select { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    textarea { resize: vertical; }
    .hint { font-size: 12px; color: var(--text-muted); margin: 0 0 8px; }
    .images { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
    .img-card { border: 1px solid var(--border); border-radius: 8px; padding: 10px; display: flex; flex-direction: column; gap: 8px; }
    .img-title { font-weight: 600; font-size: 13px; color: var(--text); }
    .img-box { height: 110px; display: flex; align-items: center; justify-content: center; background: var(--surface-2); border-radius: 6px; }
    .img-box img { max-width: 100%; max-height: 100px; }
    .img-actions { display: flex; gap: 6px; flex-wrap: wrap; }
    .file-btn { display: inline-flex; align-items: center; cursor: pointer; color: var(--text); font-size: 13px; }
    .rates { display: flex; flex-wrap: wrap; gap: 6px; }
    .rate { display: inline-flex; align-items: center; gap: 4px; padding: 3px 4px 3px 10px; border-radius: 999px; font-size: 13px; font-weight: 600;
            background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .rate button { background: none; border: none; color: var(--accent); cursor: pointer; font-size: 15px; }
    .rate-add { display: flex; gap: 8px; margin-top: 8px; flex-wrap: wrap; }
    .rate-add input { width: 120px; }
    .cp-sample { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); }
    .cp-sample img { width: 100%; border: 1px solid var(--border); margin-bottom: 8px; }
    @media (max-width: 1100px) {
      .cp-grid { grid-template-columns: 1fr; }
      .cp-sample { position: static; }
    }
    @media (max-width: 900px) {
      .grid2, .grid3, .images { grid-template-columns: 1fr; }
      label.narrow { max-width: none; }
    }
  `],
})
export class CompanyProfileComponent implements OnInit, OnDestroy {
  p: any = null;
  loadError = '';
  saving = false;
  uploading: ImageKind | null = null;
  newRate = '';
  sample: string | null = null;
  sampleLoading = false;
  images: Record<ImageKind, string | null> = { logo: null, stamp: null, signature: null };
  removeBg: Record<ImageKind, boolean> = { logo: false, stamp: true, signature: true };
  readonly kinds: { kind: ImageKind; label: string }[] = [
    { kind: 'logo', label: 'Логотип' }, { kind: 'stamp', label: 'Печать' }, { kind: 'signature', label: 'Подпись директора' },
  ];
  vatLabel = vatLabel;
  numText = numText;

  constructor(private api: ApiService, private notify: NotificationService, private confirm: ConfirmService,
              private market: MarketService, private profiles: CompanyProfileService, private cdr: ChangeDetectorRef) {}

  get marketLabel() { return this.market.companyLabel(); }

  ngOnInit() {
    this.api.getCompanyProfile().subscribe({
      next: p => { this.p = p; this.loadImages(); this.loadSample(); this.cdr.detectChanges(); },
      error: e => { this.loadError = 'Не удалось загрузить реквизиты: ' + (e.error?.message || e.message); this.cdr.detectChanges(); },
    });
  }

  ngOnDestroy() {
    for (const k of this.kinds) this.revoke(k.kind);
  }

  /** По change, а не на каждый символ: иначе «20,» превращалось бы в 20 посреди ввода «20,5». */
  setDefaultMarkup(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null) { input.value = numText(this.p.defaultMarkupPct); return; }
    this.p.defaultMarkupPct = v;
  }

  save() {
    this.saving = true;
    this.api.saveCompanyProfile(this.p).subscribe({
      next: p => {
        this.p = p;
        this.saving = false;
        this.profiles.invalidate();
        this.notify.success('Реквизиты сохранены');
        this.loadSample();
        this.cdr.detectChanges();
      },
      error: e => { this.saving = false; this.notify.error('Не сохранено: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  upload(kind: ImageKind, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.uploading = kind;
    this.api.uploadCompanyImage(kind, file, this.removeBg[kind]).subscribe({
      next: p => {
        this.uploading = null;
        this.applyImageFlags(p);
        this.loadImage(kind);
        this.profiles.invalidate();
        this.loadSample();
        this.notify.success('Картинка загружена');
        this.cdr.detectChanges();
      },
      error: e => { this.uploading = null; this.notify.error('Не загружено: ' + errorText(e)); this.cdr.detectChanges(); },
    });
  }

  removeImage(kind: ImageKind, label: string) {
    this.confirm.ask(`Удалить «${label}»?`, 'Из новых документов картинка пропадёт.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteCompanyImage(kind).subscribe({
          next: p => {
            this.applyImageFlags(p);
            this.revoke(kind);
            this.profiles.invalidate();
            this.loadSample();
            this.cdr.detectChanges();
          },
          error: e => this.notify.error('Не удалено: ' + errorText(e)),
        });
      });
  }

  addRate() {
    const v = parseNum(this.newRate);
    if (v == null || v < 0 || v > 100) { this.notify.error('Ставка — число от 0 до 100'); return; }
    if (this.p.vatRates.some((r: number | null) => r === v)) { this.notify.error('Такая ставка уже есть'); return; }
    this.p.vatRates = [...this.p.vatRates, v];
    this.newRate = '';
  }

  addNoVat() {
    this.p.vatRates = [...this.p.vatRates, null];
  }

  hasNoVat(): boolean {
    return this.p.vatRates.some((r: number | null) => r == null);
  }

  removeRate(i: number) {
    this.p.vatRates = this.p.vatRates.filter((_: any, idx: number) => idx !== i);
  }

  loadSample() {
    this.sampleLoading = true;
    this.api.getCompanyProfileSample().subscribe({
      next: r => { this.sample = r.pages.length ? 'data:image/png;base64,' + r.pages[0] : null; this.sampleLoading = false; this.cdr.detectChanges(); },
      error: () => { this.sampleLoading = false; this.cdr.detectChanges(); },
    });
  }

  private applyImageFlags(p: any) {
    this.p.hasLogo = p.hasLogo;
    this.p.hasStamp = p.hasStamp;
    this.p.hasSignature = p.hasSignature;
    this.p.imagesUpdatedAt = p.imagesUpdatedAt;
  }

  private loadImages() {
    if (this.p.hasLogo) this.loadImage('logo');
    if (this.p.hasStamp) this.loadImage('stamp');
    if (this.p.hasSignature) this.loadImage('signature');
  }

  private loadImage(kind: ImageKind) {
    this.api.getCompanyImage(kind).subscribe({
      next: blob => { this.revoke(kind); this.images[kind] = URL.createObjectURL(blob); this.cdr.detectChanges(); },
      error: () => {},
    });
  }

  private revoke(kind: ImageKind) {
    const url = this.images[kind];
    if (url) URL.revokeObjectURL(url);
    this.images[kind] = null;
  }
}

function errorText(e: any): string {
  const errors = e?.error?.errors;
  if (errors && typeof errors === 'object') {
    const first = Object.values(errors)[0];
    if (first) return String(first);
  }
  return e?.error?.message || e?.message || 'ошибка';
}
```

- [ ] **Step 7: Сборка и живая проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: сборка зелёная; в выводе есть ленивый чанк `company-profile-component`; начальный бандл не вырос больше чем на пару кБ (новые методы API и сервис), ошибок бюджета нет.

Тестовый «скан печати» (Python без внешних пакетов + `sips`):

```bash
SP=/private/tmp/claude-501/-Users-vlad-IdeaProjects-AIS/68d890e6-6182-4985-9671-4ab089fe89eb/scratchpad
python3 - "$SP/stamp-scan.png" <<'EOF'
import math, struct, sys, zlib
W = H = 400
def px(x, y):
    d = math.hypot(x - 200, y - 200)
    return (30, 60, 200) if 150 < d < 172 or 100 < d < 108 else (250, 249, 244)
raw = b''.join(b'\x00' + b''.join(bytes(px(x, y)) for x in range(W)) for y in range(H))
def chunk(t, d):
    return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
png = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', W, H, 8, 2, 0, 0, 0)) \
      + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b'')
open(sys.argv[1], 'wb').write(png)
EOF
sips -s format jpeg "$SP/stamp-scan.png" --out "$SP/stamp-scan.jpg" >/dev/null && ls -la "$SP/stamp-scan.jpg"
```

Бэкенд (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun` (фоном), фронт: `cd frontend && npm start` (фоном). Playwright (CLAUDE.md §5): `http://localhost:4200` → admin/admin → `localStorage.setItem('ais.market','KZ')` → «Система → Реквизиты и печать»:
1. поля West-Med заполнены из сида; справа «Образец» — первая страница КП с бланком West-Med (бренд «"West-Med"» текстом);
2. «Печать» → «Загрузить» `stamp-scan.jpg` (галочка «убрать белый фон» стоит) → превью печати на прозрачном фоне; образец обновился, печать видна у подписи;
3. поменять «Телефон» → «Сохранить» → тост «Реквизиты сохранены»; ставку «12» добавить и поставить её в «Новая строка» → сохранить → снова; убрать «12», вернуть «5%» → сохранить;
4. оператор (operator/operator) пункта «Реквизиты и печать» не видит, прямой переход `/company-profile` — не пускает (`adminGuard`);
5. 390px и тёмная тема: карточки в одну колонку, поля 40px, текст читается; иконка «stamp» в меню не пустая (`svg.children.length > 0`).
Вернуть телефон и удалить тестовую печать (или оставить до Task 15 — там нужна).

- [ ] **Step 8: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/package.json frontend/package-lock.json frontend/src && git commit -q -F - <<'EOF'
feat(kp-ui): «Реквизиты и печать» — бланк, подписант, сканы печати/подписи/логотипа, ставки НДС, умолчания, образец

Основа экранов КП: типы и формат чисел без формул (считает сервер), методы API, кеш профиля рынка,
общие редакторы условий и колонок (перетаскивание — @angular/cdk). Страница ленивая (loadComponent):
начальный бандл близок к пределу ошибки 1,5 МБ.

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 11: Фронт — журнал «КП клиентам»

**Files:**
- Create: `frontend/src/app/pages/client-offers/client-offers.component.ts`
- Modify: `frontend/src/app/app.routes.ts`, `frontend/src/app/layout/layout.component.ts`

**Interfaces:**
- Consumes: Task 10 (`ApiService.getClientOffers/createClientOffer/duplicateClientOffer/deleteClientOffer`, `OFFER_STATUS_LABELS`, `money`, `dateText`).
- Produces: маршрут `/client-offers` (ленивый); переход в редактор — `/client-offers/:id` (маршрут добавляет Task 13; до него клик ведёт на несуществующий маршрут — это нормально между задачами).

- [ ] **Step 1: Компонент**

`frontend/src/app/pages/client-offers/client-offers.component.ts`:

```ts
import { Component, ChangeDetectorRef, HostListener, OnDestroy } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { OFFER_STATUS_LABELS, OfferStatus, dateText, money } from '../../shared/client-offer';

/** «КП клиентам» — журнал (спека §8.1): поиск по клиенту, номеру и позициям, фильтр статуса, дублирование. */
@Component({
  selector: 'app-client-offers',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule],
  template: `
    <div class="page-head">
      <div>
        <h2>КП клиентам</h2>
        <p class="subtitle">Коммерческие предложения {{ company }}: черновики, отправленные, принятые</p>
      </div>
      <button type="button" class="btn btn-primary" *ngIf="auth.isAdmin()" [disabled]="creating" (click)="create()">
        {{ creating ? 'Создаю…' : '+ Новое КП' }}
      </button>
    </div>

    <div class="filters">
      <div class="chips" role="group" aria-label="Статус">
        <button type="button" class="chip" *ngFor="let f of statusFilters" [class.on]="statusKey === f.key" (click)="setStatus(f.key)">{{ f.label }}</button>
      </div>
      <input type="search" [(ngModel)]="q" (input)="onSearch()" placeholder="Клиент, № или позиция…" aria-label="Поиск" />
    </div>

    <div class="empty" *ngIf="!loading && !offers.length">{{ q ? 'Ничего не нашлось' : 'КП пока нет — нажмите «+ Новое КП»' }}</div>

    <div class="list">
      <article class="card" *ngFor="let o of offers; trackBy: trackById" tabindex="0" (click)="open(o.id)" (keydown.enter)="open(o.id)">
        <div class="top">
          <span class="num">№ {{ o.number }}</span>
          <span class="date">{{ dateText(o.offerDate) }}</span>
          <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
          <span class="menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
            <button type="button" class="btn btn-more" (click)="toggleMenu(o.id)" [attr.aria-label]="'Действия с КП № ' + o.number">⋯</button>
            <span class="row-menu" *ngIf="menuId === o.id">
              <button type="button" (click)="duplicate(o)">Дублировать</button>
              <button type="button" class="danger" *ngIf="o.status === 'DRAFT'" (click)="remove(o)">Удалить</button>
            </span>
          </span>
        </div>
        <div class="client">{{ o.clientName || 'Клиент не указан' }}</div>
        <div class="bottom">
          <span>{{ o.itemCount }} поз.</span>
          <span class="sum">{{ money(o.totalAmount) }} {{ symbol }}</span>
        </div>
      </article>
    </div>
  `,
  styles: [`
    .page-head { display: flex; justify-content: space-between; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .page-head h2 { margin: 0; }
    .filters { display: flex; justify-content: space-between; align-items: center; gap: 10px; flex-wrap: wrap; margin-bottom: 12px; }
    .chips { display: flex; gap: 6px; flex-wrap: wrap; }
    .chip { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .chip.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .filters input { min-width: 260px; padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; font-size: 13px; background: var(--surface); color: var(--text); }
    .list { display: flex; flex-direction: column; gap: 8px; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 12px 14px; cursor: pointer; transition: box-shadow .15s; }
    .card:hover { box-shadow: var(--shadow); }
    .card:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
    .top { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
    .num { font-weight: 700; color: var(--text); }
    .date { color: var(--text-muted); font-size: 13px; }
    /* чипы статуса: 15% тинта + текстовый токен (правило kit) */
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .menu { margin-left: auto; position: relative; }
    .client { margin-top: 6px; color: var(--text); font-size: 14px; overflow-wrap: anywhere; }
    .bottom { margin-top: 6px; display: flex; justify-content: space-between; gap: 8px; color: var(--text-muted); font-size: 13px; }
    .sum { font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    @media (max-width: 900px) {
      .filters input { flex: 1; min-width: 0; font-size: 16px; }
      .card { padding: 12px; }
      .client { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
    }
  `],
})
export class ClientOffersComponent implements OnDestroy {
  offers: any[] = [];
  loading = false;
  creating = false;
  statusKey = 'ALL';
  q = '';
  menuId: number | null = null;
  readonly statusFilters = [
    { key: 'ALL', label: 'Все' }, { key: 'DRAFT', label: 'Черновики' }, { key: 'SENT', label: 'Отправлены' },
    { key: 'ACCEPTED', label: 'Приняты' }, { key: 'REJECTED', label: 'Отклонены' },
  ];
  dateText = dateText;
  money = money;
  private searchTimer: any = null;

  constructor(private api: ApiService, public auth: AuthService, private notify: NotificationService,
              private confirm: ConfirmService, private market: MarketService, private router: Router,
              private cdr: ChangeDetectorRef) {
    this.load();
  }

  get company() { return this.market.companyLabel(); }
  get symbol() { return this.market.symbol(); }

  ngOnDestroy() {
    clearTimeout(this.searchTimer);
  }

  @HostListener('document:click')
  closeMenu() {
    this.menuId = null;
  }

  load() {
    this.loading = true;
    this.api.getClientOffers({ status: this.statusKey, q: this.q.trim() || undefined }).subscribe({
      next: d => { this.offers = d; this.loading = false; this.cdr.detectChanges(); },
      error: e => { this.loading = false; this.notify.error('Ошибка загрузки КП: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  setStatus(key: string) {
    this.statusKey = key;
    this.load();
  }

  onSearch() {
    clearTimeout(this.searchTimer);
    this.searchTimer = setTimeout(() => this.load(), 300);
  }

  create() {
    this.creating = true;
    this.api.createClientOffer().subscribe({
      next: o => { this.creating = false; this.router.navigate(['/client-offers', o.id]); },
      error: e => { this.creating = false; this.notify.error('КП не создано: ' + (e.error?.message || e.message)); this.cdr.detectChanges(); },
    });
  }

  open(id: number) {
    this.router.navigate(['/client-offers', id]);
  }

  toggleMenu(id: number) {
    this.menuId = this.menuId === id ? null : id;
  }

  duplicate(o: any) {
    this.menuId = null;
    this.api.duplicateClientOffer(o.id).subscribe({
      next: copy => { this.notify.success(`Создана копия — КП № ${copy.number}`); this.router.navigate(['/client-offers', copy.id]); },
      error: e => this.notify.error('Не удалось: ' + (e.error?.message || e.message)),
    });
  }

  remove(o: any) {
    this.menuId = null;
    this.confirm.ask(`Удалить черновик КП № ${o.number}?`, 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' })
      .subscribe(ok => {
        if (!ok) return;
        this.api.deleteClientOffer(o.id).subscribe({
          next: () => { this.notify.success('Черновик удалён'); this.load(); },
          error: e => this.notify.error(e.error?.message || 'Не удалось удалить'),
        });
      });
  }

  statusLabel(s: OfferStatus) {
    return OFFER_STATUS_LABELS[s] || s;
  }

  trackById(_: number, o: any) {
    return o.id;
  }
}
```

- [ ] **Step 2: Маршрут и пункт меню**

`app.routes.ts` — в `children` после `private-requests`:

```ts
      { path: 'client-offers',
        loadComponent: () => import('./pages/client-offers/client-offers.component').then(m => m.ClientOffersComponent) },
```

`layout.component.ts` — после ссылки «Частные заявки»:

```html
            <a routerLink="/client-offers" routerLinkActive="active">
              <svg lucideIcon="file-pen-line" [size]="16"></svg> КП клиентам
            </a>
```

- [ ] **Step 3: Сборка и живая проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёная, чанк `client-offers-component`.

Playwright (KZ, admin): «Заявки → КП клиентам» — пустой журнал с подсказкой; «+ Новое КП» создаёт КП и уводит на `/client-offers/<id>` (до Task 13 там пусто — норма); вернуться: в журнале карточка «№ 1 · дата · Черновик · Клиент не указан · 0 поз. · 0,00 ₸»; «⋯ → Дублировать» → «№ 2»; «⋯ → Удалить» с подтверждением в странице; поиск «2» находит № 2; фильтр «Отправлены» — пусто. 390px, тёмная тема; иконка `file-pen-line` в меню не пустая. Оператор видит журнал, кнопки «+ Новое КП» и «⋯» нет. Тестовые КП удалить.

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src && git commit -q -F - <<'EOF'
feat(kp-ui): журнал «КП клиентам» — поиск, фильтр статуса, новое КП, дублирование, удаление черновика

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 12: Фронт — строки КП

**Files:**
- Create: `frontend/src/app/pages/client-offers/client-offer-items.component.ts`

**Interfaces:**
- Consumes: Task 10 (`ClientOffer`, `OfferItem`, `ItemKind`, `RegStatus`, `newItem`, `uid`, `money`, `numText`, `parseNum`, `vatLabel`, `UNIT_SUGGESTIONS`), `ConfirmService`, `NotificationService`.
- Produces: `<app-client-offer-items [offer] [profile] [readonly] (changed)>` — правит `offer.items` на месте (добавление, порядок, поля), после каждой правки — `changed`. Числа строк (`calc`) только показывает.

- [ ] **Step 1: Компонент**

`frontend/src/app/pages/client-offers/client-offer-items.component.ts`:

```ts
import { Component, EventEmitter, HostListener, Input, Output } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { CdkDrag, CdkDragDrop, CdkDragHandle, CdkDropList, moveItemInArray } from '@angular/cdk/drag-drop';
import { LucideDynamicIcon } from '@lucide/angular';
import { ConfirmService } from '../../services/confirm.service';
import { NotificationService } from '../../services/notification.service';
import {
  ClientOffer, ItemKind, OfferItem, RegStatus, UNIT_SUGGESTIONS, money, newItem, numText, parseNum, uid, vatLabel,
} from '../../shared/client-offer';

/**
 * Строки КП (спека §8.2, блок «Позиции»): позиция / раздел / «включено в стоимость». Главное — в строку, остальное —
 * «Подробнее». Числовые поля сохраняются по change (не на каждый символ — иначе «12,» превращалось бы в 12).
 * Цена клиенту: ввод = ручная цена (наценка выводится сервером обратно), «↺» — вернуть расчёт по наценке.
 */
@Component({
  selector: 'app-client-offer-items',
  standalone: true,
  imports: [NgFor, NgIf, FormsModule, CdkDropList, CdkDrag, CdkDragHandle, LucideDynamicIcon],
  template: `
    <div class="toolbar" *ngIf="!readonly">
      <button type="button" class="btn btn-line" (click)="add('ITEM')">+ Позиция</button>
      <button type="button" class="btn btn-line" (click)="add('SECTION')">+ Раздел</button>
      <button type="button" class="btn btn-line" (click)="add('INCLUDED')">+ Включено в стоимость</button>
    </div>

    <div class="bulk" *ngIf="!readonly && selectedCount() > 0">
      <span class="bulk-count">Выбрано: {{ selectedCount() }}</span>
      <input class="bulk-input" inputmode="decimal" placeholder="Наценка, %" [(ngModel)]="bulkMarkup" aria-label="Наценка для выбранных, %" />
      <button type="button" class="btn btn-line" (click)="applyBulkMarkup()" title="Ручные цены выбранных строк сбросятся">Наценку</button>
      <select [(ngModel)]="bulkVat" aria-label="НДС для выбранных" [disabled]="!offer.vatEnabled">
        <option *ngFor="let r of rates" [ngValue]="r">{{ vatLabel(r) }}</option>
      </select>
      <button type="button" class="btn btn-line" [disabled]="!offer.vatEnabled" (click)="applyBulkVat()">НДС</button>
      <button type="button" class="btn btn-danger" (click)="bulkDelete()">Удалить</button>
      <button type="button" class="btn btn-cancel" (click)="selectAll(false)">Снять выбор</button>
    </div>

    <div class="head line" *ngIf="offer.items.length">
      <span class="c-grip"></span>
      <span class="c-sel"><input type="checkbox" [checked]="allSelected()" (change)="selectAll($any($event.target).checked)" [disabled]="readonly" aria-label="Выбрать все строки" /></span>
      <span class="c-num">№</span><span class="c-name">Наименование</span><span class="c-qty r">Кол-во</span>
      <span class="c-buy r">Закупка</span><span class="c-mk r">Наценка, %</span><span class="c-price r">Цена клиенту</span>
      <span class="c-vat">НДС</span><span class="c-sum r">Сумма</span><span class="c-menu"></span>
    </div>

    <div class="rows" cdkDropList [cdkDropListDisabled]="readonly" (cdkDropListDropped)="drop($event)">
      <div class="row" *ngFor="let it of offer.items; let i = index; trackBy: trackKey" cdkDrag [cdkDragDisabled]="readonly"
           [attr.data-kind]="it.kind" [class.sel]="it._sel" [class.warn]="noPrice(it)">
        <div class="line">
          <button type="button" class="c-grip grip" cdkDragHandle [disabled]="readonly" aria-label="Перетащить строку" title="Перетащить">
            <svg lucideIcon="grip-vertical" [size]="16"></svg>
          </button>
          <span class="c-sel"><input type="checkbox" [(ngModel)]="it._sel" [disabled]="readonly" [attr.aria-label]="'Выбрать строку ' + (i + 1)" /></span>

          <ng-container *ngIf="it.kind === 'ITEM'">
            <span class="c-num">{{ number(i) }}</span>
            <div class="c-name">
              <textarea rows="1" class="name" [(ngModel)]="it.name" (ngModelChange)="emit()" placeholder="Наименование" aria-label="Наименование"></textarea>
              <span class="chip-warn" *ngIf="needsReg(it)">РУ не указано</span>
            </div>
            <label class="c-qty"><span class="cap">Кол-во</span>
              <input inputmode="decimal" [value]="numText(it.quantity)" (change)="setQty(it, $event)" aria-label="Количество" /></label>
            <label class="c-buy"><span class="cap">Закупка</span>
              <input inputmode="decimal" [value]="numText(it.purchasePrice)" (change)="setPurchase(it, $event)" placeholder="—" aria-label="Цена закупки за единицу" /></label>
            <label class="c-mk"><span class="cap">Наценка, %</span>
              <input inputmode="decimal" [value]="markupText(it)" [placeholder]="numText(offer.defaultMarkupPct)"
                     [disabled]="it.priceOverride != null" (change)="setMarkup(it, $event)" aria-label="Наценка, %" /></label>
            <label class="c-price"><span class="cap">Цена клиенту</span>
              <span class="price-wrap">
                <input inputmode="decimal" [class.manual]="it.priceOverride != null" [value]="numText(it.priceOverride ?? it.calc?.price ?? null)"
                       (change)="setPrice(it, $event)" aria-label="Цена клиенту за единицу" />
                <button type="button" class="reset" *ngIf="it.priceOverride != null && !readonly" (click)="resetPrice(it)"
                        title="Вернуть расчёт по наценке" aria-label="Вернуть расчёт по наценке">↺</button>
              </span></label>
            <label class="c-vat"><span class="cap">НДС</span>
              <select *ngIf="offer.vatEnabled; else noVat" [ngModel]="it.vatRate" (ngModelChange)="it.vatRate = $event; emit()" aria-label="Ставка НДС">
                <option *ngFor="let r of rates" [ngValue]="r">{{ vatLabel(r) }}</option>
              </select>
              <ng-template #noVat><span class="muted">Без НДС</span></ng-template></label>
            <span class="c-sum"><span class="cap">Сумма</span>{{ money(it.calc?.sum) || '—' }}</span>
          </ng-container>

          <div class="c-wide" *ngIf="it.kind === 'SECTION'">
            <input class="section" [(ngModel)]="it.name" (ngModelChange)="emit()" placeholder="Заголовок раздела, например «Основные комплектующие:»" aria-label="Заголовок раздела" />
          </div>

          <div class="c-wide included" *ngIf="it.kind === 'INCLUDED'">
            <input [(ngModel)]="it.name" (ngModelChange)="emit()" placeholder="Что включено, например «Гарантийное обслуживание 12 месяцев»" aria-label="Что включено" />
            <input [(ngModel)]="it.note" (ngModelChange)="emit()" placeholder="Включено в стоимость" aria-label="Текст на месте цены" />
          </div>

          <span class="c-menu" (click)="$event.stopPropagation()">
            <button type="button" class="btn btn-more" (click)="toggleMenu(it.key)" [attr.aria-label]="'Действия со строкой ' + (i + 1)">⋯</button>
            <span class="row-menu" *ngIf="openMenuKey === it.key">
              <button type="button" *ngIf="it.kind === 'ITEM'" (click)="it._open = !it._open; openMenuKey = null">{{ it._open ? 'Свернуть' : 'Подробнее' }}</button>
              <button type="button" *ngIf="!readonly" [disabled]="i === 0" (click)="move(i, -1)">Выше</button>
              <button type="button" *ngIf="!readonly" [disabled]="i === offer.items.length - 1" (click)="move(i, 1)">Ниже</button>
              <button type="button" *ngIf="!readonly" (click)="duplicateRow(i)">Дублировать</button>
              <button type="button" *ngIf="!readonly" class="danger" (click)="removeRow(i)">Удалить</button>
            </span>
          </span>
        </div>

        <div class="details" *ngIf="it.kind === 'ITEM' && it._open">
          <label>Модель / артикул <input [(ngModel)]="it.model" (ngModelChange)="emit()" /></label>
          <label>Производитель <input [(ngModel)]="it.manufacturer" (ngModelChange)="emit()" /></label>
          <label>Страна <input [(ngModel)]="it.country" (ngModelChange)="emit()" /></label>
          <label>Ед. изм. <input [(ngModel)]="it.unit" (ngModelChange)="emit()" [attr.list]="'units-' + it.key" /></label>
          <datalist [id]="'units-' + it.key"><option *ngFor="let u of units" [value]="u"></option></datalist>
          <label>НДС в цене закупки
            <select [ngModel]="purchaseVatKey(it)" (ngModelChange)="setPurchaseVat(it, $event)">
              <option value="same">как у продажи</option>
              <option *ngFor="let r of numericRates" [value]="'r:' + r">{{ vatLabel(r) }}</option>
              <option value="none">без НДС</option>
            </select></label>
          <label>Поставщик <input [(ngModel)]="it.supplierName" (ngModelChange)="emit()" placeholder="только для вас" /></label>
          <label class="wide">Регистрация
            <span class="reg">
              <select [ngModel]="it.registrationStatus" (ngModelChange)="setRegStatus(it, $event)">
                <option value="UNCHECKED">не указана</option>
                <option value="MANUAL">указать номер РУ</option>
                <option value="NOT_REQUIRED">не подлежит регистрации</option>
                <option *ngIf="it.registrationStatus === 'CONFIRMED'" value="CONFIRMED">подтверждена по реестру</option>
                <option *ngIf="it.registrationStatus === 'SUGGESTED'" value="SUGGESTED">подсказка реестра</option>
              </select>
              <input *ngIf="it.registrationStatus === 'MANUAL'" [(ngModel)]="it.registrationText" (ngModelChange)="emit()"
                     placeholder="№ РК-МИ (МТ)-0№023037 от 28.10.2021 г." aria-label="Текст регистрации" />
              <span class="muted" *ngIf="it.registrationStatus === 'CONFIRMED' || it.registrationStatus === 'NOT_REQUIRED'">{{ it.registrationText }}</span>
            </span></label>
          <label class="wide">Примечание <textarea rows="2" [(ngModel)]="it.note" (ngModelChange)="emit()"></textarea></label>
          <div class="profit wide">
            <span>Себестоимость: <b>{{ money(it.calc?.cost) || '—' }}</b></span>
            <span>Прибыль: <b [class.neg]="(it.calc?.profit ?? 0) < 0">{{ money(it.calc?.profit) || '—' }}</b></span>
            <span class="muted">только для вас</span>
          </div>
        </div>
      </div>
    </div>
    <p class="empty" *ngIf="!offer.items.length">Позиций пока нет — нажмите «+ Позиция».</p>
  `,
  styles: [`
    :host { display: block; }
    .toolbar { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
    /* подсветка ОБЛАСТИ: 8% тинта поверх --surface + цветная кромка (правило kit) */
    .bulk { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; padding: 8px 10px; margin-bottom: 10px; border-radius: 8px;
            background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid var(--accent); }
    .bulk-count { font-weight: 600; color: var(--text); }
    .bulk input, .bulk select { padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    .bulk-input { width: 110px; }
    .line { display: grid; gap: 6px; align-items: center;
            grid-template-columns: 24px 24px 28px minmax(160px, 1fr) 64px 104px 80px 128px 88px 112px 36px;
            grid-template-areas: "grip sel num name qty buy mk price vat sum menu"; }
    .head { font-size: 12px; color: var(--text-muted); padding: 0 8px 4px 11px; }
    .r { text-align: right; }
    .rows { display: flex; flex-direction: column; gap: 6px; }
    .row { background: var(--surface); border: 1px solid var(--border); border-left: 3px solid transparent; border-radius: 8px; padding: 6px 8px; }
    .row.sel { background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border-color: var(--accent); }
    .row.warn { border-left-color: var(--warn); }
    .row[data-kind="SECTION"] { background: var(--surface-2); }
    .c-grip { grid-area: grip; } .c-sel { grid-area: sel; } .c-num { grid-area: num; text-align: center; color: var(--text-muted); font-size: 13px; }
    .c-name { grid-area: name; display: flex; flex-direction: column; gap: 2px; min-width: 0; }
    .c-qty { grid-area: qty; } .c-buy { grid-area: buy; } .c-mk { grid-area: mk; } .c-price { grid-area: price; } .c-vat { grid-area: vat; }
    .c-sum { grid-area: sum; text-align: right; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    .c-menu { grid-area: menu; position: relative; justify-self: end; }
    .c-wide { grid-column: 3 / 11; display: flex; gap: 6px; min-width: 0; }
    .c-wide input { flex: 1; min-width: 0; }
    .grip { background: none; border: none; color: var(--text-muted); cursor: grab; padding: 2px; display: flex; }
    .grip:disabled { cursor: default; opacity: .4; }
    .line input, .line select, .line textarea, .details input, .details select, .details textarea {
      width: 100%; padding: 6px 8px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 13px; }
    .line input[type="checkbox"] { width: auto; }
    .line input[inputmode="decimal"] { text-align: right; font-variant-numeric: tabular-nums; }
    .name { resize: vertical; min-height: 32px; }
    .section { font-weight: 600; }
    .included input:last-child { color: var(--text-muted); }
    .price-wrap { display: flex; gap: 2px; align-items: center; }
    .manual { border-color: var(--warn) !important; background: color-mix(in srgb, var(--warn) 8%, var(--surface)) !important; }
    .reset { background: none; border: none; color: var(--warn-text); cursor: pointer; font-size: 15px; padding: 2px 4px; }
    .cap { display: none; }
    .muted { color: var(--text-muted); font-size: 13px; }
    .chip-warn { align-self: flex-start; font-size: 11px; font-weight: 600; padding: 1px 8px; border-radius: 10px;
                 background: color-mix(in srgb, var(--warn) 15%, transparent); color: var(--warn-text); }
    .details { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 8px 10px; margin-top: 8px; padding-top: 8px; border-top: 1px dashed var(--border); }
    .details label { display: flex; flex-direction: column; gap: 3px; font-size: 12px; color: var(--text-muted); }
    .details .wide { grid-column: 1 / -1; }
    .reg { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
    .reg select { width: auto; }
    .reg input { flex: 1; min-width: 200px; }
    .profit { display: flex; gap: 16px; flex-wrap: wrap; font-size: 13px; color: var(--text); }
    .neg { color: var(--danger-text); }
    @media (max-width: 900px) {
      .head { display: none; }
      .line { grid-template-columns: 24px 24px 1fr 1fr 1fr 36px;
              grid-template-areas: "grip sel name name name menu" ". . qty buy mk ." ". . price price vat ." ". . sum sum sum ."; }
      .c-num { display: none; }
      .cap { display: block; font-size: 11px; color: var(--text-muted); margin-bottom: 2px; }
      .c-sum { text-align: left; }
      .c-wide { grid-column: 3 / 6; flex-direction: column; }
      .details { grid-template-columns: 1fr; }
      .reg input { min-width: 0; }
    }
  `],
})
export class ClientOfferItemsComponent {
  @Input({ required: true }) offer!: ClientOffer;
  @Input({ required: true }) profile: any;
  @Input() readonly = false;
  @Output() changed = new EventEmitter<void>();

  bulkMarkup = '';
  bulkVat: number | null = null;
  openMenuKey: string | null = null;
  readonly units = UNIT_SUGGESTIONS;
  money = money;
  numText = numText;
  vatLabel = vatLabel;

  constructor(private confirm: ConfirmService, private notify: NotificationService) {}

  get rates(): (number | null)[] {
    return this.profile?.vatRates ?? [];
  }

  get numericRates(): number[] {
    return this.rates.filter((r): r is number => r != null);
  }

  @HostListener('document:click')
  closeMenu() {
    this.openMenuKey = null;
  }

  trackKey(_: number, it: OfferItem) {
    return it.key;
  }

  emit() {
    this.changed.emit();
  }

  /** Номер позиции — только по строкам ITEM, как в документе. */
  number(i: number): number {
    let n = 0;
    for (let k = 0; k <= i; k++) if (this.offer.items[k].kind === 'ITEM') n++;
    return n;
  }

  add(kind: ItemKind) {
    this.offer.items.push(newItem(kind, this.profile?.vatDefault ?? null));
    this.emit();
  }

  drop(e: CdkDragDrop<OfferItem[]>) {
    if (e.previousIndex === e.currentIndex) return;
    moveItemInArray(this.offer.items, e.previousIndex, e.currentIndex);
    this.emit();
  }

  toggleMenu(key: string) {
    this.openMenuKey = this.openMenuKey === key ? null : key;
  }

  move(i: number, delta: number) {
    const j = i + delta;
    this.openMenuKey = null;
    if (j < 0 || j >= this.offer.items.length) return;
    moveItemInArray(this.offer.items, i, j);
    this.emit();
  }

  duplicateRow(i: number) {
    const src = this.offer.items[i];
    this.offer.items.splice(i + 1, 0, { ...src, id: null, key: uid(), calc: src.calc ? { ...src.calc } : null, _sel: false });
    this.openMenuKey = null;
    this.emit();
  }

  removeRow(i: number) {
    this.offer.items.splice(i, 1);
    this.openMenuKey = null;
    this.emit();
  }

  selectedCount(): number {
    return this.offer.items.filter(i => i._sel).length;
  }

  allSelected(): boolean {
    return this.offer.items.length > 0 && this.offer.items.every(i => i._sel);
  }

  selectAll(on: boolean) {
    this.offer.items.forEach(i => (i._sel = on));
  }

  applyBulkMarkup() {
    const v = parseNum(this.bulkMarkup);
    if (v == null) { this.notify.error('Наценка — числом'); return; }
    for (const it of this.offer.items) if (it._sel && it.kind === 'ITEM') { it.markupPct = v; it.priceOverride = null; }
    this.emit();
  }

  applyBulkVat() {
    for (const it of this.offer.items) if (it._sel && it.kind === 'ITEM') it.vatRate = this.bulkVat;
    this.emit();
  }

  bulkDelete() {
    const n = this.selectedCount();
    this.confirm.ask(`Удалить строк: ${n}?`, 'Строки исчезнут из КП.', { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => {
      if (!ok) return;
      for (let i = this.offer.items.length - 1; i >= 0; i--) if (this.offer.items[i]._sel) this.offer.items.splice(i, 1);
      this.emit();
    });
  }

  setQty(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null || v <= 0) {
      input.value = numText(it.quantity);
      this.notify.error('Количество — число больше нуля');
      return;
    }
    it.quantity = v;
    this.emit();
  }

  setPurchase(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v != null && v < 0) { input.value = numText(it.purchasePrice); return; }
    it.purchasePrice = v;
    this.emit();
  }

  /** Пусто — общая наценка КП. */
  setMarkup(it: OfferItem, ev: Event) {
    it.markupPct = parseNum((ev.target as HTMLInputElement).value);
    this.emit();
  }

  /** Ввод цены = ручная цена; пусто — вернуть расчёт; та же цена, что посчитана, — не ручная (просто прошли Tab'ом). */
  setPrice(it: OfferItem, ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null) {
      if (it.priceOverride != null) { it.priceOverride = null; this.emit(); }
      return;
    }
    if (v < 0) { input.value = numText(it.priceOverride ?? it.calc?.price ?? null); return; }
    if (it.priceOverride == null && it.calc?.price != null && Math.abs(v - it.calc.price) < 0.005) return;
    it.priceOverride = v;
    this.emit();
  }

  resetPrice(it: OfferItem) {
    it.priceOverride = null;
    this.emit();
  }

  markupText(it: OfferItem): string {
    return numText(it.priceOverride != null ? (it.calc?.markupPct ?? null) : it.markupPct);
  }

  purchaseVatKey(it: OfferItem): string {
    if (it.purchaseVatSame) return 'same';
    return it.purchaseVatRate == null ? 'none' : 'r:' + it.purchaseVatRate;
  }

  setPurchaseVat(it: OfferItem, key: string) {
    if (key === 'same') { it.purchaseVatSame = true; it.purchaseVatRate = null; }
    else if (key === 'none') { it.purchaseVatSame = false; it.purchaseVatRate = null; }
    else { it.purchaseVatSame = false; it.purchaseVatRate = Number(key.slice(2)); }
    this.emit();
  }

  /** РУ указано → ставка «подтверждённого РУ»; «не подлежит» → её ставка (настройки рынка, как в КП отца: 5% / 16%). */
  setRegStatus(it: OfferItem, status: RegStatus) {
    it.registrationStatus = status;
    if (status === 'UNCHECKED') it.registrationText = null;
    if (status === 'NOT_REQUIRED') {
      it.registrationText = 'Не подлежит регистрации';
      if (this.offer.vatEnabled && this.profile && 'vatNotRegistrable' in this.profile) it.vatRate = this.profile.vatNotRegistrable;
    }
    if (status === 'MANUAL' && this.offer.vatEnabled && this.profile && 'vatRegistered' in this.profile) {
      it.vatRate = this.profile.vatRegistered;
    }
    this.emit();
  }

  noPrice(it: OfferItem): boolean {
    return it.kind === 'ITEM' && it.priceOverride == null && it.purchasePrice == null;
  }

  needsReg(it: OfferItem): boolean {
    return it.kind === 'ITEM' && this.offer.columns.some(c => c.key === 'REGISTRATION')
      && (it.registrationStatus === 'UNCHECKED' || it.registrationStatus === 'SUGGESTED');
  }
}
```

- [ ] **Step 2: Сборка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build`
Expected: зелёная (компонент ещё никем не используется — сборка проверяет только его компиляцию; живая проверка — в Task 13).

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src/app/pages/client-offers/client-offer-items.component.ts && git commit -q -F - <<'EOF'
feat(kp-ui): строки КП — позиция / раздел / «включено», перетаскивание, ручная цена и ↺, массовые наценка и НДС

Числа строк только показываются (считает сервер); числовые поля сохраняются по change — «12,» не теряется
посреди ввода. Регистрация: «указать РУ» ставит ставку подтверждённого РУ, «не подлежит» — свою (настройки рынка).

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 13: Фронт — редактор КП: автосохранение, предпросмотр, выгрузка, «Поделиться», статусы

**Files:**
- Create: `frontend/src/app/pages/client-offers/client-offer-editor.component.ts`, `client-offer-preview.component.ts`
- Modify: `frontend/src/app/app.routes.ts`

**Interfaces:**
- Consumes: Task 10 (API, `CompanyProfileService.profile$()`, редакторы условий и колонок, `toRequest`, `saveBlob`, формат), Task 12 (`<app-client-offer-items>`).
- Produces: маршрут `/client-offers/:id` (ленивый); `<app-client-offer-preview [offerId] [tick] [active]>`.

**Как устроено автосохранение (спека §8.3):** правка → `changed()` → через 600 мс `save()`; одновременно идёт не больше одного `PUT`; из ответа берутся **только** `version`, `totals`, `fileBaseName`, `updatedAt`, `facilityName` и у строк (сведённых по `key`) — `id`, `lineNo`, `calc`: поле, которое оператор продолжает набирать, не затирается; правки, пришедшие за время запроса, отправляются сразу следующим `PUT`. `409` → плашка «изменено в другой вкладке», редактирование блокируется. Выгрузка, статус, копия и уход со страницы сперва дожидаются сохранения (`flushThen`). Предпросмотр запрашивается после успешного сохранения и только когда виден (≥1200 px — всегда, уже — на вкладке «Просмотр»).

- [ ] **Step 1: Предпросмотр**

`frontend/src/app/pages/client-offers/client-offer-preview.component.ts`:

```ts
import { ChangeDetectorRef, Component, HostListener, Input, OnChanges, OnDestroy } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { Subscription } from 'rxjs';
import { ApiService } from '../../services/api.service';

/**
 * Предпросмотр КП — страницы того же PDF, что получит клиент (PNG base64 одним ответом через HttpClient, §14).
 * Старые страницы остаются на экране, пока не придут новые; тап по странице — крупно, Esc — закрыть.
 */
@Component({
  selector: 'app-client-offer-preview',
  standalone: true,
  imports: [NgFor, NgIf],
  template: `
    <div class="pv-state" *ngIf="loading && !pages.length">Собираю предпросмотр…</div>
    <div class="error-banner" *ngIf="error">{{ error }} <button type="button" class="btn btn-line" (click)="load()">Повторить</button></div>
    <div class="pv-pages" [class.stale]="loading && pages.length">
      <button type="button" class="pv-page" *ngFor="let p of pages; let i = index" (click)="zoom = p" [attr.aria-label]="'Страница ' + (i + 1) + ' крупно'">
        <img [src]="p" [alt]="'Страница ' + (i + 1)" />
      </button>
    </div>
    <div class="pv-zoom" *ngIf="zoom" (click)="zoom = null" role="dialog" aria-label="Страница крупно">
      <img [src]="zoom" alt="Страница крупно" />
    </div>
  `,
  styles: [`
    :host { display: block; }
    .pv-state { color: var(--text-muted); font-size: 13px; padding: 24px 0; text-align: center; }
    .pv-pages { display: flex; flex-direction: column; gap: 12px; transition: opacity .2s; }
    .pv-pages.stale { opacity: .55; }
    .pv-page { display: block; padding: 0; border: 1px solid var(--border); background: var(--surface); box-shadow: var(--shadow); cursor: zoom-in; }
    .pv-page img { display: block; width: 100%; height: auto; }
    /* вуаль модалки — самое частое из значений приложения (§16: не заводить пятое) */
    .pv-zoom { position: fixed; inset: 0; z-index: 60; overflow: auto; background: rgba(17, 24, 39, .5); display: flex; justify-content: center; align-items: flex-start;
               padding: calc(16px + env(safe-area-inset-top, 0px)) 16px calc(16px + env(safe-area-inset-bottom, 0px)); cursor: zoom-out; }
    .pv-zoom img { width: 100%; max-width: 1100px; height: auto; box-shadow: var(--shadow-lg); }
  `],
})
export class ClientOfferPreviewComponent implements OnChanges, OnDestroy {
  @Input({ required: true }) offerId!: number;
  @Input() tick = 0;
  @Input() active = true;

  pages: string[] = [];
  loading = false;
  error = '';
  zoom: string | null = null;
  private loadedKey = '';
  private timer: any = null;
  private sub: Subscription | null = null;

  constructor(private api: ApiService, private cdr: ChangeDetectorRef) {}

  ngOnChanges() {
    if (!this.active || this.key() === this.loadedKey) return;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.load(), 250);
  }

  ngOnDestroy() {
    clearTimeout(this.timer);
    this.sub?.unsubscribe();
  }

  @HostListener('document:keydown.escape')
  closeZoom() {
    if (this.zoom) { this.zoom = null; this.cdr.detectChanges(); }
  }

  load() {
    this.sub?.unsubscribe();
    const key = this.key();
    this.loading = true;
    this.error = '';
    this.cdr.detectChanges();
    this.sub = this.api.getClientOfferPreview(this.offerId).subscribe({
      next: r => {
        this.pages = r.pages.map(p => 'data:image/png;base64,' + p);
        this.loading = false;
        this.loadedKey = key;
        this.cdr.detectChanges();
      },
      error: () => { this.loading = false; this.error = 'Предпросмотр не удался'; this.cdr.detectChanges(); },
    });
  }

  private key(): string {
    return this.offerId + ':' + this.tick;
  }
}
```

- [ ] **Step 2: Редактор**

`frontend/src/app/pages/client-offers/client-offer-editor.component.ts`:

```ts
import { ChangeDetectorRef, Component, HostListener, OnDestroy, OnInit } from '@angular/core';
import { NgFor, NgIf } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { Subscription } from 'rxjs';
import { LucideDynamicIcon } from '@lucide/angular';
import { ApiService } from '../../services/api.service';
import { AuthService } from '../../services/auth.service';
import { NotificationService } from '../../services/notification.service';
import { ConfirmService } from '../../services/confirm.service';
import { MarketService } from '../../services/market.service';
import { CompanyProfileService } from '../../services/company-profile.service';
import { OfferTermsEditorComponent } from '../../shared/offer-terms-editor.component';
import { OfferColumnsEditorComponent } from '../../shared/offer-columns-editor.component';
import {
  ClientOffer, OFFER_STATUS_LABELS, OfferItem, OfferStatus, ROUNDING_OPTIONS, TermsStyle,
  dateText, money, numText, parseNum, saveBlob, toRequest, vatLabel,
} from '../../shared/client-offer';
import { ClientOfferItemsComponent } from './client-offer-items.component';
import { ClientOfferPreviewComponent } from './client-offer-preview.component';

/** Редактор КП (спека §8.2–§8.4): блоки слева, предпросмотр справа (уже 1200 px — вкладки), панель выгрузки снизу. */
@Component({
  selector: 'app-client-offer-editor',
  standalone: true,
  imports: [NgIf, NgFor, FormsModule, RouterLink, LucideDynamicIcon, ClientOfferItemsComponent, ClientOfferPreviewComponent,
            OfferTermsEditorComponent, OfferColumnsEditorComponent],
  template: `
    <div class="editor" *ngIf="offer as o; else stateTpl">
      <header class="ed-top">
        <a routerLink="/client-offers" class="back">← КП клиентам</a>
        <div class="ed-title">
          <h2>КП № {{ o.number }} от {{ dateText(o.offerDate) }}</h2>
          <span class="st" [attr.data-status]="o.status">{{ statusLabel(o.status) }}</span>
          <span class="save-state" [class.err]="!!saveError || conflict" aria-live="polite">{{ saveText() }}</span>
          <button type="button" class="btn btn-line" *ngIf="saveError && !conflict" (click)="save()">Повторить</button>
        </div>
        <span class="ed-menu" *ngIf="auth.isAdmin()" (click)="$event.stopPropagation()">
          <button type="button" class="btn btn-more" (click)="menuOpen = !menuOpen" aria-label="Ещё действия">⋯</button>
          <span class="row-menu" *ngIf="menuOpen">
            <button type="button" (click)="duplicate()">Дублировать</button>
            <button type="button" class="danger" *ngIf="o.status === 'DRAFT'" (click)="remove()">Удалить черновик</button>
          </span>
        </span>
      </header>

      <div class="error-banner" *ngIf="conflict">
        КП изменено в другой вкладке — правки этой вкладки не сохранены. <button type="button" class="btn btn-line" (click)="reload()">Обновить</button>
      </div>

      <div class="tabs" role="tablist">
        <button type="button" role="tab" [class.on]="tab === 'edit'" [attr.aria-selected]="tab === 'edit'" (click)="tab = 'edit'">Редактор</button>
        <button type="button" role="tab" [class.on]="tab === 'preview'" [attr.aria-selected]="tab === 'preview'" (click)="tab = 'preview'">Просмотр</button>
      </div>

      <div class="ed-body">
        <section class="pane-edit" [class.off]="tab !== 'edit'">
          <fieldset [disabled]="!auth.isAdmin() || conflict">
            <div class="card">
              <h3>Шапка</h3>
              <div class="grid2">
                <label>Исх. № <input type="number" min="1" [(ngModel)]="o.number" (ngModelChange)="changed()" /></label>
                <label>Дата <input type="date" [(ngModel)]="o.offerDate" (ngModelChange)="changed()" /></label>
                <label class="wide">Клиент
                  <select [ngModel]="o.facilityId" (ngModelChange)="onFacility($event)">
                    <option [ngValue]="null">— без клиента —</option>
                    <option *ngFor="let f of facilities" [ngValue]="f.id">{{ f.name }}</option>
                  </select></label>
                <label class="wide">Кому <textarea rows="2" [(ngModel)]="o.recipient" (ngModelChange)="changed()" placeholder="Главному врачу&#10;ГКП на ПХВ «…»"></textarea></label>
                <label class="wide">Заголовок <input [(ngModel)]="o.title" (ngModelChange)="changed()" /></label>
                <label class="wide">Предмет <input [(ngModel)]="o.subject" (ngModelChange)="changed()" placeholder="Например: Аппарат ИВЛ для экстренной помощи А-ИВЛ-Э-03" /></label>
                <label class="wide">Вводная фраза <textarea rows="2" [(ngModel)]="o.intro" (ngModelChange)="changed()"></textarea></label>
              </div>
            </div>

            <div class="card">
              <h3>Позиции</h3>
              <app-client-offer-items [offer]="o" [profile]="profile" [readonly]="!auth.isAdmin()" (changed)="changed()"></app-client-offer-items>
              <div class="mini-total" *ngIf="o.totals">
                Итого: <b>{{ money(o.totals.sum) }} {{ currencyShort }}</b>
                <span *ngFor="let v of o.totals.vat"> · в т.ч. НДС {{ vatLabel(v.rate) }}: {{ money(v.amount) }}</span>
              </div>
            </div>

            <div class="card">
              <h3>Цены</h3>
              <div class="grid3">
                <label>Общая наценка, % <input inputmode="decimal" [value]="numText(o.defaultMarkupPct)" (change)="setMarkup($event)" /></label>
                <label>Округление цены
                  <select [(ngModel)]="o.rounding" (ngModelChange)="changed()"><option *ngFor="let r of roundings" [ngValue]="r.v">{{ r.l }}</option></select></label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.vatEnabled" (ngModelChange)="changed()" /> Цены с НДС</label>
              </div>
            </div>

            <div class="card">
              <h3>Условия</h3>
              <div class="seg" role="radiogroup" aria-label="Как печатать условия">
                <button type="button" *ngFor="let s of termStyles" role="radio" [attr.aria-checked]="o.termsStyle === s.v"
                        [class.on]="o.termsStyle === s.v" (click)="setTermsStyle(s.v)">{{ s.l }}</button>
              </div>
              <app-offer-terms-editor [terms]="o.terms" [offerDate]="o.offerDate" (changed)="changed()"></app-offer-terms-editor>
            </div>

            <div class="card">
              <h3>Оформление</h3>
              <app-offer-columns-editor [columns]="o.columns" [vatEnabled]="o.vatEnabled" (changed)="changed()"></app-offer-columns-editor>
              <div class="toggles">
                <label class="check"><input type="checkbox" [(ngModel)]="o.detailsInName" (ngModelChange)="changed()" /> Модель, производителя и страну — к наименованию, если у них нет своей колонки</label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.showAmountInWords" (ngModelChange)="changed()" /> Сумма прописью</label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.showVatBreakdown" (ngModelChange)="changed()" [disabled]="!o.vatEnabled" /> Разбивка НДС по ставкам</label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.landscape" (ngModelChange)="changed()" /> Альбомная ориентация</label>
                <label class="inline">Подпись
                  <select [(ngModel)]="o.signoff" (ngModelChange)="changed()">
                    <option value="DIRECTOR">Директор ____ Фамилия И. О.</option>
                    <option value="COMPANY">Только «С уважением, компания»</option>
                  </select></label>
                <label class="check"><input type="checkbox" [(ngModel)]="o.signoffContacts" (ngModelChange)="changed()" /> Контакты под подписью</label>
                <label class="check stamp">
                  <input type="checkbox" [(ngModel)]="o.withStamp" (ngModelChange)="changed()" [disabled]="!profile?.hasStamp" />
                  <span><b>Подпись и печать</b><span class="hint" *ngIf="!profile?.hasStamp"> — загрузите печать в «Реквизитах и печати»</span></span>
                </label>
              </div>
            </div>

            <div class="card" *ngIf="o.totals as t">
              <h3>Маржа <span class="hint">только для вас — в документ не попадает</span></h3>
              <dl class="margin">
                <div><dt>Закупка</dt><dd>{{ money(t.purchase) }}</dd></div>
                <div><dt>Себестоимость</dt><dd>{{ money(t.cost) }}</dd></div>
                <div><dt>Выручка без НДС</dt><dd>{{ money(t.revenueNet) }}</dd></div>
                <div><dt>Прибыль</dt><dd [class.neg]="t.profit < 0">{{ money(t.profit) }}</dd></div>
                <div><dt>Средняя наценка</dt><dd>{{ t.markupAvg == null ? '—' : numText(t.markupAvg) + '%' }}</dd></div>
              </dl>
              <p class="hint warn" *ngIf="t.noPurchaseCount">Строк без цены закупки: {{ t.noPurchaseCount }} — их прибыль не посчитана</p>
              <label class="wide">Заметка для себя <textarea rows="2" [(ngModel)]="o.internalNote" (ngModelChange)="changed()"></textarea></label>
            </div>
          </fieldset>
        </section>

        <aside class="pane-preview" [class.off]="tab !== 'preview'">
          <app-client-offer-preview [offerId]="o.id" [tick]="previewTick" [active]="previewActive()"></app-client-offer-preview>
        </aside>
      </div>

      <footer class="ed-bar">
        <span class="warns">
          <span *ngIf="o.totals?.noPurchaseCount">без закупки: {{ o.totals.noPurchaseCount }}</span>
          <span *ngIf="regWarnings()">без регистрации: {{ regWarnings() }}</span>
        </span>
        <button type="button" class="btn btn-primary" (click)="download('pdf')" [disabled]="busy"><svg lucideIcon="file-down" [size]="16"></svg> PDF</button>
        <button type="button" class="btn btn-line" (click)="download('docx')" [disabled]="busy">Word</button>
        <button type="button" class="btn btn-line" *ngIf="canShare && !shareFile" (click)="prepareShare()" [disabled]="busy">
          <svg lucideIcon="share-2" [size]="16"></svg> Поделиться</button>
        <button type="button" class="btn btn-primary" *ngIf="shareFile" (click)="sendShare()">Отправить PDF</button>
        <select *ngIf="auth.isAdmin()" class="status" [ngModel]="o.status" (ngModelChange)="setStatus($event)" aria-label="Статус КП">
          <option *ngFor="let s of statuses" [ngValue]="s">{{ statusLabel(s) }}</option>
        </select>
      </footer>
    </div>
    <ng-template #stateTpl><p class="empty">{{ loadError || 'Загрузка…' }}</p></ng-template>
  `,
  styles: [`
    .editor { display: flex; flex-direction: column; gap: 12px; }
    .ed-top { display: flex; align-items: flex-start; gap: 12px; flex-wrap: wrap; }
    .back { color: var(--accent); text-decoration: none; font-size: 13px; padding-top: 4px; }
    .ed-title { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; flex: 1; min-width: 0; }
    .ed-title h2 { margin: 0; font-size: 20px; }
    .st { font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: 10px; background: var(--surface-2); color: var(--text-muted); }
    .st[data-status="SENT"] { background: color-mix(in srgb, var(--accent) 15%, transparent); color: var(--accent); }
    .st[data-status="ACCEPTED"] { background: color-mix(in srgb, var(--success) 15%, transparent); color: var(--success-text); }
    .st[data-status="REJECTED"] { background: color-mix(in srgb, var(--danger) 15%, transparent); color: var(--danger-text); }
    .save-state { font-size: 12px; color: var(--text-muted); }
    .save-state.err { color: var(--danger-text); }
    .ed-menu { position: relative; }
    .tabs { display: none; gap: 6px; }
    .tabs button { flex: 1; border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 8px; padding: 8px; font-size: 14px; cursor: pointer; }
    .tabs button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .ed-body { display: grid; grid-template-columns: minmax(0, 1fr) minmax(420px, 44%); gap: 16px; align-items: start; }
    .pane-preview { position: sticky; top: calc(env(safe-area-inset-top, 0px) + 12px); max-height: calc(100vh - 150px); overflow: auto; }
    fieldset { border: none; padding: 0; margin: 0; min-width: 0; display: flex; flex-direction: column; gap: 12px; }
    .card { background: var(--surface); border: 1px solid var(--border); border-radius: 10px; padding: 14px 16px; min-width: 0; }
    .card h3 { margin: 0 0 10px; font-size: 15px; display: flex; gap: 8px; align-items: baseline; flex-wrap: wrap; }
    .grid2 { display: grid; grid-template-columns: 1fr 1fr; gap: 10px 12px; }
    .grid3 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 10px 12px; align-items: end; }
    label { display: flex; flex-direction: column; gap: 4px; font-size: 12px; color: var(--text-muted); }
    label.wide { grid-column: 1 / -1; }
    label.check { flex-direction: row; align-items: center; gap: 8px; color: var(--text); font-size: 13px; }
    label.inline { flex-direction: row; align-items: center; gap: 8px; color: var(--text); font-size: 13px; }
    label.stamp { padding: 8px 10px; border-radius: 8px; background: color-mix(in srgb, var(--accent) 8%, var(--surface)); border: 1px solid var(--accent); }
    .card input:not([type="checkbox"]), .card select, .card textarea { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font: inherit; font-size: 14px; }
    .card textarea { resize: vertical; }
    .mini-total { margin-top: 10px; font-size: 13px; color: var(--text-muted); text-align: right; }
    .mini-total b { color: var(--text); }
    .seg { display: flex; gap: 6px; flex-wrap: wrap; margin-bottom: 10px; }
    .seg button { border: 1px solid var(--border); background: var(--surface); color: var(--text); border-radius: 999px; padding: 5px 12px; font-size: 13px; cursor: pointer; }
    .seg button.on { background: var(--accent); border-color: var(--accent); color: var(--accent-contrast); }
    .toggles { display: flex; flex-direction: column; gap: 8px; margin-top: 12px; }
    .hint { font-size: 12px; color: var(--text-muted); font-weight: 400; }
    .hint.warn { color: var(--warn-text); }
    .margin { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 8px; margin: 0 0 8px; }
    .margin dt { font-size: 12px; color: var(--text-muted); }
    .margin dd { margin: 2px 0 0; font-weight: 600; color: var(--text); font-variant-numeric: tabular-nums; }
    .neg { color: var(--danger-text) !important; }
    .ed-bar { position: sticky; bottom: 0; z-index: 5; display: flex; align-items: center; gap: 8px; flex-wrap: wrap; justify-content: flex-end;
              padding: 10px 12px calc(10px + env(safe-area-inset-bottom, 0px)); background: var(--surface); border-top: 1px solid var(--border); box-shadow: var(--shadow); }
    .ed-bar .btn { display: inline-flex; align-items: center; gap: 6px; }
    .warns { margin-right: auto; display: flex; gap: 10px; flex-wrap: wrap; font-size: 12px; color: var(--warn-text); }
    .status { padding: 7px 10px; border: 1px solid var(--border); border-radius: 6px; background: var(--surface); color: var(--text); font-size: 13px; }
    @media (max-width: 1199px) {
      .tabs { display: flex; }
      .ed-body { display: block; }
      .off { display: none; }
      .pane-preview { position: static; max-height: none; overflow: visible; }
    }
    @media (max-width: 900px) {
      .grid2, .grid3 { grid-template-columns: 1fr; }
      .margin { grid-template-columns: 1fr 1fr; }
      .ed-title h2 { font-size: 17px; }
      .card { padding: 12px; }
      .warns { width: 100%; }
    }
  `],
})
export class ClientOfferEditorComponent implements OnInit, OnDestroy {
  offer: ClientOffer | null = null;
  profile: any = null;
  facilities: any[] = [];
  loadError = '';
  tab: 'edit' | 'preview' = 'edit';
  wide = false;
  saving = false;
  dirty = false;
  saveError = '';
  conflict = false;
  savedAt: Date | null = null;
  previewTick = 0;
  busy = false;
  menuOpen = false;
  shareFile: File | null = null;
  readonly canShare = canShareFiles();
  readonly statuses: OfferStatus[] = ['DRAFT', 'SENT', 'ACCEPTED', 'REJECTED'];
  readonly roundings = ROUNDING_OPTIONS;
  readonly termStyles: { v: TermsStyle; l: string }[] = [
    { v: 'LIST', l: 'Списком под итогом' }, { v: 'TABLE', l: 'Таблицей над позициями' }, { v: 'NONE', l: 'Не печатать' },
  ];
  dateText = dateText;
  money = money;
  numText = numText;
  vatLabel = vatLabel;

  private id = 0;
  private changeSeq = 0;
  private sentSeq = 0;
  private timer: any = null;
  private afterSave: (() => void)[] = [];
  private media: MediaQueryList | null = null;
  private readonly onMedia = (e: MediaQueryListEvent) => { this.wide = e.matches; this.cdr.detectChanges(); };
  private routeSub: Subscription | null = null;

  constructor(private route: ActivatedRoute, private router: Router, private api: ApiService, public auth: AuthService,
              private profiles: CompanyProfileService, private notify: NotificationService, private confirm: ConfirmService,
              private market: MarketService, private cdr: ChangeDetectorRef) {}

  get currencyShort(): string {
    return this.market.value === 'RF' ? 'руб.' : 'тг';
  }

  ngOnInit() {
    this.media = window.matchMedia('(min-width: 1200px)');
    this.wide = this.media.matches;
    this.media.addEventListener('change', this.onMedia);
    this.profiles.profile$().subscribe({ next: p => { this.profile = p; this.cdr.detectChanges(); }, error: () => {} });
    this.api.getFacilities().subscribe({ next: f => { this.facilities = f; this.cdr.detectChanges(); }, error: () => {} });
    this.routeSub = this.route.paramMap.subscribe(p => this.load(Number(p.get('id'))));
  }

  ngOnDestroy() {
    this.media?.removeEventListener('change', this.onMedia);
    this.routeSub?.unsubscribe();
    clearTimeout(this.timer);
    // уход со страницы с несохранённым — дописать (запрос не отменяется вместе с видом)
    if (this.dirty && !this.saving && !this.conflict && this.offer && this.auth.isAdmin()) {
      this.api.saveClientOffer(this.offer.id, toRequest(this.offer)).subscribe({ error: () => {} });
    }
  }

  @HostListener('window:beforeunload', ['$event'])
  beforeUnload(e: BeforeUnloadEvent) {
    if (this.dirty || this.saving) { e.preventDefault(); e.returnValue = ''; }
  }

  @HostListener('document:click')
  closeMenu() {
    this.menuOpen = false;
  }

  load(id: number) {
    this.id = id;
    this.offer = null;
    this.loadError = '';
    this.conflict = false;
    this.saveError = '';
    this.dirty = false;
    this.shareFile = null;
    this.api.getClientOffer(id).subscribe({
      next: o => {
        o.items.forEach((it: OfferItem) => { if (!it.key) it.key = 'i' + it.id; });
        this.offer = o;
        this.previewTick++;
        this.cdr.detectChanges();
      },
      error: e => {
        this.loadError = e.status === 404 ? 'КП не найдено' : 'Не удалось загрузить КП: ' + (e.error?.message || e.message);
        this.cdr.detectChanges();
      },
    });
  }

  reload() {
    this.load(this.id);
  }

  previewActive(): boolean {
    return this.wide || this.tab === 'preview';
  }

  changed() {
    this.dirty = true;
    this.changeSeq++;
    this.shareFile = null;
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.save(), 600);
  }

  save() {
    if (!this.offer || this.conflict || !this.auth.isAdmin() || this.saving) return;
    clearTimeout(this.timer);
    this.saving = true;
    this.saveError = '';
    this.sentSeq = this.changeSeq;
    this.api.saveClientOffer(this.offer.id, toRequest(this.offer)).subscribe({
      next: r => {
        this.merge(r);
        this.saving = false;
        this.savedAt = new Date();
        if (this.changeSeq > this.sentSeq) {
          this.save();
        } else {
          this.dirty = false;
          this.previewTick++;
          this.runAfterSave();
        }
        this.cdr.detectChanges();
      },
      error: e => {
        this.saving = false;
        this.afterSave = [];
        if (e.status === 409) this.conflict = true;
        else this.saveError = errorText(e);
        this.cdr.detectChanges();
      },
    });
  }

  /** Из ответа — только вычисляемое; набираемые поля не трогаем (спека §8.3). */
  private merge(r: ClientOffer) {
    const o = this.offer!;
    o.version = r.version;
    o.totals = r.totals;
    o.fileBaseName = r.fileBaseName;
    o.updatedAt = r.updatedAt;
    o.facilityName = r.facilityName;
    const byKey = new Map(r.items.map(it => [it.key, it]));
    for (const it of o.items) {
      const s = byKey.get(it.key);
      if (s) { it.id = s.id; it.lineNo = s.lineNo; it.calc = s.calc; }
    }
  }

  /** Выгрузка, статус, копия — только после сохранения последних правок. */
  private flushThen(fn: () => void) {
    if (!this.dirty && !this.saving) { fn(); return; }
    this.afterSave.push(fn);
    if (!this.saving) this.save();
  }

  private runAfterSave() {
    const list = this.afterSave;
    this.afterSave = [];
    list.forEach(f => f());
  }

  saveText(): string {
    if (this.conflict) return 'Изменено в другой вкладке';
    if (this.saveError) return 'Не сохранено: ' + this.saveError;
    if (this.saving || this.dirty) return 'Сохраняю…';
    return this.savedAt ? 'Сохранено · ' + this.savedAt.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' }) : 'Сохранено';
  }

  setMarkup(ev: Event) {
    const input = ev.target as HTMLInputElement;
    const v = parseNum(input.value);
    if (v == null) { input.value = numText(this.offer!.defaultMarkupPct); return; }
    this.offer!.defaultMarkupPct = v;
    this.changed();
  }

  setTermsStyle(style: TermsStyle) {
    this.offer!.termsStyle = style;
    this.changed();
  }

  /** «Кому» подставляется названием клиента, если пусто или было названием прежнего клиента. */
  onFacility(id: number | null) {
    const o = this.offer!;
    const prev = this.facilities.find(f => f.id === o.facilityId)?.name;
    o.facilityId = id;
    const f = this.facilities.find(x => x.id === id);
    if (f && (!o.recipient || o.recipient === prev)) o.recipient = f.name;
    this.changed();
  }

  download(kind: 'pdf' | 'docx') {
    this.flushThen(() => {
      this.busy = true;
      this.cdr.detectChanges();
      const req = kind === 'pdf' ? this.api.downloadClientOfferPdf(this.id) : this.api.downloadClientOfferDocx(this.id);
      req.subscribe({
        next: blob => { saveBlob(blob, this.offer!.fileBaseName + '.' + kind); this.busy = false; this.cdr.detectChanges(); },
        error: () => { this.busy = false; this.notify.error('Файл не собран — попробуйте ещё раз'); this.cdr.detectChanges(); },
      });
    });
  }

  /** «Поделиться» в два нажатия: браузер требует, чтобы share шёл прямо из нажатия, а скачивание PDF его «съедает». */
  prepareShare() {
    this.flushThen(() => {
      this.busy = true;
      this.cdr.detectChanges();
      this.api.downloadClientOfferPdf(this.id).subscribe({
        next: blob => {
          this.shareFile = new File([blob], this.offer!.fileBaseName + '.pdf', { type: 'application/pdf' });
          this.busy = false;
          this.cdr.detectChanges();
        },
        error: () => { this.busy = false; this.notify.error('PDF не собран'); this.cdr.detectChanges(); },
      });
    });
  }

  sendShare() {
    const file = this.shareFile;
    if (!file) return;
    (navigator as any).share({ files: [file], title: this.offer!.fileBaseName }).then(() => {}, () => {});
  }

  setStatus(status: OfferStatus) {
    this.flushThen(() => this.api.setClientOfferStatus(this.id, status).subscribe({
      next: r => {
        const o = this.offer!;
        o.status = r.status;
        o.sentAt = r.sentAt;
        o.version = r.version;
        this.notify.success('Статус: ' + this.statusLabel(r.status));
        this.cdr.detectChanges();
      },
      error: e => { this.notify.error('Статус не изменён: ' + errorText(e)); this.reload(); },
    }));
  }

  duplicate() {
    this.menuOpen = false;
    this.flushThen(() => this.api.duplicateClientOffer(this.id).subscribe({
      next: r => { this.notify.success(`Создана копия — КП № ${r.number}`); this.router.navigate(['/client-offers', r.id]); },
      error: e => this.notify.error('Не удалось: ' + errorText(e)),
    }));
  }

  remove() {
    this.menuOpen = false;
    this.confirm.ask('Удалить черновик КП?', 'Это действие нельзя отменить.', { danger: true, confirmLabel: 'Удалить' }).subscribe(ok => {
      if (!ok) return;
      clearTimeout(this.timer);
      this.dirty = false;
      this.api.deleteClientOffer(this.id).subscribe({
        next: () => { this.notify.success('Черновик удалён'); this.router.navigate(['/client-offers']); },
        error: e => this.notify.error(errorText(e)),
      });
    });
  }

  statusLabel(s: OfferStatus): string {
    return OFFER_STATUS_LABELS[s] || s;
  }

  regWarnings(): number {
    const o = this.offer;
    if (!o?.totals || !o.columns.some(c => c.key === 'REGISTRATION')) return 0;
    return o.totals.unconfirmedRegistrationCount;
  }
}

function canShareFiles(): boolean {
  try {
    const nav = navigator as any;
    return !!nav.share && !!nav.canShare && nav.canShare({ files: [new File(['x'], 'x.pdf', { type: 'application/pdf' })] });
  } catch {
    return false;
  }
}

function errorText(e: any): string {
  const errors = e?.error?.errors;
  if (errors && typeof errors === 'object') {
    const first = Object.values(errors)[0];
    if (first) return String(first);
  }
  return e?.error?.message || 'нет связи с сервером';
}
```

- [ ] **Step 3: Маршрут**

`app.routes.ts` — после маршрута `client-offers`:

```ts
      { path: 'client-offers/:id',
        loadComponent: () => import('./pages/client-offers/client-offer-editor.component').then(m => m.ClientOfferEditorComponent) },
```

- [ ] **Step 4: Сборка и живая проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёная, без ошибок бюджета (каждый компонент < 24 кБ стилей).

Playwright (бэкенд и фронт запущены, KZ, admin) — сценарий «КП как от 24.09»:
1. «КП клиентам → + Новое КП» → редактор: колонки и условия West-Med подставлены, справа предпросмотр с бланком;
2. «+ Позиция» ×4: «Пульсоксиметр QMP – PO70 взрослый», 3 шт, закупка 88 000, НДС 5% → цена считается сервером (88 000 / 1,05 × 1,2 × 1,05 = 105 600), сумма 316 800; вторая — ручная цена 13 515 (поле жёлтое, «↺» возвращает расчёт); в «Подробнее» — «указать номер РУ» (ставка сама 5%) и «не подлежит регистрации» (ставка сама 16%);
3. «+ Раздел», «+ Включено в стоимость» — в предпросмотре раздел на всю ширину, «Включено в стоимость» на месте цен;
4. перетащить строку за ручку; «⋯ → Выше/Ниже/Дублировать/Удалить»; выбрать две строки → «Наценку» 25 → цены пересчитались;
5. «Цены с НДС» выключить → колонки НДС пропали из предпросмотра, внизу «Без НДС»; включить обратно;
6. условия «Таблицей над позициями» — в предпросмотре таблица условий над позициями; «Дата КП» добавляет «Дата коммерческого предложения — «02» октября 2026 г.»;
7. переименовать колонку «Наименование» → в предпросмотре новая подпись;
8. «Подпись и печать» (после загрузки печати в Task 10) → печать у подписи в предпросмотре;
9. «PDF» и «Word» скачиваются с именем «КП № … от ….pdf/.docx»; PDF открыть — совпадает с предпросмотром;
10. статус «Отправлено» → чип и журнал; «⋯ → Дублировать» → новое КП с тем же содержимым;
11. вторая вкладка того же КП: правка там, затем правка здесь → плашка «изменено в другой вкладке», поля заблокированы, «Обновить» подтягивает;
12. 390 px: вкладки «Редактор / Просмотр», строки — карточками с подписями полей, нижняя панель не перекрывает поля (safe-area); 1280 px — две колонки; тёмная тема — всё читается;
13. оператор: КП открывается только для чтения (поля недоступны), PDF скачивается.

- [ ] **Step 5: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src && git commit -q -F - <<'EOF'
feat(kp-ui): редактор КП — автосохранение с версией, предпросмотр страницами, PDF/Word, «Поделиться», статусы

Из ответа автосохранения берётся только вычисляемое (набираемое не затирается), правки во время запроса уходят
следующим PUT, 409 блокирует вкладку. Выгрузка/статус/копия ждут сохранения. «Поделиться» на iPhone — в два
нажатия (share должен идти прямо из нажатия). Уже 1200 px — вкладки «Редактор / Просмотр».

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 14: Фронт — подписи НДС из настроек рынка

**Files:**
- Modify: `frontend/src/app/components/equipment-detail-modal/equipment-detail-modal.component.ts`, `frontend/src/app/pages/registry-reconciliation/registry-reconciliation.component.ts`, `frontend/src/app/pages/private-requests/private-request-card.component.ts`

**Interfaces:**
- Consumes: Task 10 (`CompanyProfileService.vatHints$()`, `VatHints`, `DEFAULT_VAT_HINTS`).

Почему: «облагается НДС 12%», «НДС 12%» и «НДС-льгота» зашиты в шаблоны, а ставки уже другие (в КП отца — 5% и 16%). Подписи берут ставки рынка: «льготная ставка НДС 5%» / «НДС 16%» (RF — «без НДС» / «НДС 22%»). Обновление вида — `markForCheck()`, **не** `detectChanges()`: из кеша `shareReplay` значение приходит синхронно ещё в конструкторе, и `detectChanges()` там уронил бы dev-режим («Should be run in update mode», CLAUDE.md §14).

- [ ] **Step 1: Правки**

Во всех трёх компонентах:
- импорт: `import { CompanyProfileService, DEFAULT_VAT_HINTS, VatHints } from '…/services/company-profile.service';` (путь: из `components/equipment-detail-modal/` — `'../../services/company-profile.service'`, из `pages/…/` — тот же `'../../services/company-profile.service'`);
- поле класса: `vat: VatHints = DEFAULT_VAT_HINTS;`
- в конструктор добавить параметр `private companyProfiles: CompanyProfileService` и в тело конструктора (у `equipment-detail-modal` и `private-request-card` тело пустое `{}` — заменить):

```ts
    this.companyProfiles.vatHints$().subscribe({ next: v => { this.vat = v; this.cdr.markForCheck(); }, error: () => {} });
```

Шаблоны:
- `equipment-detail-modal.component.ts`: `НДС-льгота` → `{{ vat.registered }}`; `облагается НДС 12%` → `{{ vat.standard }}`;
- `registry-reconciliation.component.ts`: в подзаголовке `(НДС-льгота)` → `(льготная ставка НДС)`; бейджи `НДС-льгота` → `{{ vat.registered }}`, `НДС 12%` → `{{ vat.standard }}`; комментарий в стилях про «НДС-льгота» → «ставка НДС»;
- `private-request-card.component.ts`: `НДС-льгота` → `{{ vat.registered }}`; комментарий `/* НДС-льгота — просто подпись… */` → `/* ставка НДС — просто подпись… */`.

Проверка, что старых чисел не осталось:

```bash
cd /Users/vlad/IdeaProjects/AIS/frontend && grep -rn "НДС 12%\|НДС-льгота" src/app || echo "старых подписей нет"
```

- [ ] **Step 2: Сборка и живая проверка**

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёная.
Playwright (KZ): «Сверка с реестром» — у зарегистрированных «льготная ставка НДС 5%», у остальных «НДС 16%»; карточка частной заявки — «льготная ставка НДС 5%» у зарегистрированной строки; карточка оборудования — то же. В консоли браузера нет «ExpressionChangedAfterItHasBeenChecked» и «Should be run in update mode».

- [ ] **Step 3: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add frontend/src && git commit -q -F - <<'EOF'
fix(ui): подписи НДС берут ставки из настроек рынка — было «НДС 12%» при 16%, «НДС-льгота» без ставки

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

### Task 15: Документация, полный гейт, живая проверка всего блока

**Files:**
- Modify: `CLAUDE.md` (§8, §12, §13, §14, §15, §16), `docs/PROGRESS.md` («▶ Последняя задача» + запись сессии)

- [ ] **Step 1: Полный гейт**

Run (sandbox off): `cd /Users/vlad/IdeaProjects/AIS && lsof -ti tcp:8080 -sTCP:LISTEN | xargs kill 2>/dev/null; ./gradlew cleanTest test`
Expected: BUILD SUCCESSFUL, 0 падений (прежние ~781 + новые тесты пакета `clientoffer`). Число тестов — в отчёт и в CLAUDE.md §13.

Run: `cd /Users/vlad/IdeaProjects/AIS/frontend && npm run build` — зелёная; записать размер начального бандла (был 1,36 МБ).

- [ ] **Step 2: Живая проверка блока целиком**

Запуск (sandbox off): бэкенд `JAVA_TOOL_OPTIONS=-Xmx1g ./gradlew bootRun`, фронт `cd frontend && npm start`. Логотип West-Med — вырезать из PDF КП на центрифугу (Swift/PDFKit, как при разборе образцов): отрисовать 1-ю страницу `~/Downloads/2026 КП WEST-MED центрифуга.pdf` в 300 dpi, обрезать полосу «"West-Med"» (верхние ~25% страницы, по полям надписи) — `sips --cropToHeightWidth`/`--cropOffset` — и загрузить как «Логотип» без удаления фона. Печать — `stamp-scan.jpg` из Task 10 (подпись — тот же скрипт с другой фигурой или пропустить).

Сценарий:
1. «Реквизиты и печать» — логотип и печать загружены, «Образец» с логотипом и печатью;
2. КП «как от 24.09» — 44 строки (взять строки из `~/Downloads/КП West-Med 24.09.2026.docx`: `pandoc -t plain` → наименование, ед., кол-во, цена, НДС 5/16) — внести через API одним `PUT` (скрипт `curl` с сессией из логина) или руками первые 6 и «Дублировать строку»; итог сверить с документом отца (у полного набора — **20 417 347,00**), разбивка 5% / 16%;
3. предпросмотр: бланк только на 1-й странице, шапка таблицы на каждой, подпись с печатью в конце; длинные наименования переносятся;
4. PDF: открыть — совпадает с предпросмотром; Word: скачать и попросить оператора открыть в **Word** (Quick Look плавающие картинки не рисует) — бланк, таблица на всю ширину, печать у подписи и её можно сдвинуть;
5. старый PDF «Заявки на участие» на KZ — реквизиты West-Med, «тг»;
6. 390 px / 1280 px, обе темы, оператор — по пунктам Task 10, 11, 13, 14;
7. замер подстроить, если печать легла неудачно: `KpHtmlRenderer.STAMP_LEFT_MM/STAMP_TOP_MM` и `KpDocxRenderer.STAMP_X_MM/STAMP_Y_MM` — отдельным коммитом с замером «было/стало».

Тестовые КП, логотип и печать после проверки — удалить (или оставить, если оператор попросит), реквизиты вернуть как в сиде.

- [ ] **Step 3: Документация**

CLAUDE.md:
- §8 — новый блок «**КП клиенту — конструктор (волна 1, 2026-10-02, ветка `feature/client-kp-constructor`)**»: что сделано (страница реквизитов, журнал, редактор, расчёт «с учётом НДС», модель документа → PDF/предпросмотр/Word, «заодно»), ключевые решения (наценка без входного НДС, вход без НДС — в себестоимости; одна функция расчёта; `KpDocument` для обоих рендереров; openhtmltopdf только `data:`; Liberation Serif без ₸/₽ → валюта словами; ширины Word только явные; номер — `UPDATE … RETURNING`; версия + 409; печать — плавающая картинка); что проверено (тесты, мутации, живьём);
- §12 — новые страницы ленивые (`loadComponent`), `@angular/cdk` только в них; общие редакторы условий и колонок;
- §13 — новое число тестов;
- §14 — уроки: Quick Look не рисует плавающие картинки .docx и игнорирует ширины ячеек — проверять в Word; `setWidth("100%")` у таблиц POI не работает — нужна явная ширина в DXA + fixed + gridCol + tcW; openhtmltopdf тянет `de.rototor.pdfbox:graphics2d` и PDFBox 3.0.7, логи — через `XRLog.setLevel`; в Liberation и нынешнем шрифте нет ₸/₽; начальный бандл у предела ошибки 1,5 МБ → новые страницы лениво; в тестах `@Transactional` сбойный запрос оставляет правки в общем контексте (проверять следующим запросом, а не состоянием сущности);
- §15 — API `/api/company-profile*`, `/api/client-offers*`;
- §16 — волна 2 (частная заявка, Excel поставщика, реестр и каталог — спека §9.2–§9.4), бэклог спеки §15, «найдено попутно» (§16 спеки), RF ставка новой строки (22% — уточнить у отца), логотип West-Med — картинка из PDF (лучше исходник), печать и подпись — сканы отца.

PROGRESS.md: «▶ Последняя задача» — блок «КП клиенту, волна 1»: состояние (в `main` / ждёт push), что ждёт оператора (сканы печати и подписи, следующий «исх. №», ставка RF, открыть .docx в Word), следующий шаг — волна 2 (свой план). Запись сессии 2026-10-02 (бриф: запрос, решения, сделано, поймано живьём). Памятку `last-task-pointer` обновить (одна строка в MEMORY.md).

- [ ] **Step 4: Commit**

```bash
cd /Users/vlad/IdeaProjects/AIS && git add CLAUDE.md docs/PROGRESS.md && git commit -q -F - <<'EOF'
docs(kp): конструктор КП, волна 1 — механика, уроки, API, бэклог волны 2; статус в PROGRESS

Co-Authored-By: Claude Opus 5.5 (1M context) <noreply@anthropic.com>
EOF
```

---

## Самопроверка плана (сделана при написании)

- **Покрытие спеки (волна 1):** §4 — Task 2; §5 — Task 4; §6.1–§6.3 — Tasks 5–6; §6.4 — Tasks 1, 6, 7; §6.5 — Tasks 1, 9; §6.6 — Task 8; §7.1–§7.3 — Tasks 2, 3, 10; §7.4 — Tasks 9, 14; §8 — Tasks 11–13; §9.1 — Task 8 (+ кнопка в 11/13); §10 (волна 1) — Tasks 3, 8; §11 — Tasks 3, 8 (+ мутации); §12 — Tasks 8, 12, 13; §13 — тесты по задачам + Task 15; §14 — Task 15. Волна 2 (§9.2–§9.4, строки «волна 2» §10) — отдельный план.
- **Отклонения от спеки (осознанные):** JSON-колонка КП названа `table_columns` (в спеке `columns`) — не спорить с ключевым словом SQL; в API поле по-прежнему `columns`. Проверка .docx глазами — в Word, не Quick Look (выяснено пробным запуском).
- **Типы и имена сквозные:** `ItemCalc`/`OfferTotals`/`VatLine`/`OfferCalculation` (Task 4) → `KpDocumentBuilder` (5), `ClientOfferService`/`ClientOfferMapper` (8); `KpDocument.*` (5) → `KpHtmlRenderer` (6), `KpDocxRenderer` (7); `CompanyLines` (5) → `CompanyInfoProvider` (9); `DocFormat` (3) → 4, 5, 8, 9; фронт: `shared/client-offer.ts` (10) → 11–13.
