package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderPlatform;
import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Импорт СК-Ф на моке клиента (реальные HTML-фикстуры) → парс+фильтр+upsert. */
@SpringBootTest
@Transactional
@org.springframework.test.context.TestPropertySource(properties = "skpharmacy.import.throttle-ms=0")
class SkPharmacyImportServiceTest {

    @Autowired SkPharmacyImportService importService;
    @Autowired TenderRepository tenderRepository;
    @Autowired SkPharmacyTenderWriter writer;
    @MockitoBean SkPharmacyClient client;

    @org.junit.jupiter.api.BeforeEach void noSleep() { importService.setSleeper(ms -> { }); }

    @AfterEach void clear() {
        MarketContext.clear();
        importService.setSleeper(com.vladoose.nir.integration.http.UpstreamRetry.REAL);
    }

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    /** Остальные объявления фикстуры ленты — закупки услуг (пропускаются), чтобы не мешать сверке лент/вкладки. */
    private void servicesForOthers() throws IOException {
        when(client.lotsPage(anyString(), anyInt())).thenReturn(fixture("lots-services.html"));
    }

    /** Реальная лента, у объявления 521464-1 число лотов подменено (в фикстуре — 12). */
    private String searchWithLotsCount(int n) throws IOException {
        String s = fixture("search.html");
        int c = s.indexOf("<td>12</td>", s.indexOf("521464-1"));
        return s.substring(0, c) + "<td>" + n + "</td>" + s.substring(c + "<td>12</td>".length());
    }

    private static final String THROTTLED = "<html><body>Too many requests</body></html>";

    /**
     * ⚠️ Счётчик created зависит от СОСТОЯНИЯ nirdb: объявления фикстуры давно на площадке, и после живого
     * прогона импорта они в базе уже есть → upsert вернёт UPDATED, а не CREATED (на этом тест и падал).
     * Убираем тендер до прогона — тест @Transactional, удаление откатится вместе с остальным.
     */
    @Test
    void import_createsSkTenders_withPlatformAndDeviceLots() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");   // 1 страница, дальше конец
        // device-лоты (томограф/МРТ) только у 521464 (в ленте 12 лотов = 12 в фикстуре); остальным — услуги,
        // иначе у них лента и вкладка разошлись бы (521324: 14 против 12) и прогон честно насчитал бы ошибки
        servicesForOthers();
        when(client.lotsPage(eq("521464"), anyInt())).thenReturn(fixture("lots.html"));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));  // вкладка «Общие сведения» 521464

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getFetched()).isEqualTo(10);      // 10 объявлений в фикстуре
        assertThat(sum.getErrors()).isZero();            // пустая ВТОРАЯ страница — конец ленты, не ошибка
        assertThat(sum.getMatched()).isGreaterThanOrEqualTo(1);
        assertThat(sum.getCreated()).isGreaterThanOrEqualTo(1);
        assertThat(sum.getCreatedExtIds()).contains("521464-1");          // для уведомления о новых тендерах
        assertThat(sum.getCreatedExtIds()).hasSize(sum.getCreated());

        Tender t = tenderRepository.findBySourceExtId("521464-1").orElseThrow();
        assertThat(t.getPlatform()).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getCurrency()).isEqualTo("KZT");
        // поля вкладки «Общие сведения» (?tab=general): регион организатора + БИН + контакт больше не пусты
        assertThat(t.getRegion()).isEqualTo("г. Астана");
        assertThat(t.getRegionKato()).isEqualTo("711210000");
        assertThat(t.getCustomerBin()).isEqualTo("090340007747");
        assertThat(t.getContactEmail()).isEqualTo("t.omirbay@sk-pharmacy.kz");
        assertThat(t.getLots()).isNotEmpty()
                .anySatisfy(l -> assertThat(l.getEquipName().toLowerCase()).contains("томограф"))
                .anySatisfy(l -> assertThat(l.getSourceLotCode()).isEqualTo("1040409-Т1"));  // код лота сохранён (ключ ТЗ)
    }

    /** Лоты бывают на нескольких страницах вкладки; берём все, пока пейджер даёт ссылку вперёд. */
    @Test
    void import_walksAllLotPages_untilPagerHasNoNextLink() throws IOException {
        MarketContext.set(Market.KZ);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        when(client.lotsPage(anyString(), anyInt())).thenAnswer(inv ->
                inv.getArgument(1, Integer.class) == 1 ? fixture("lots-ed-order.html")      // 20 лотов, в пейджере есть «вперёд»
                                                       : fixture("lots-ed-order-last.html"));  // 16 лотов, ссылки вперёд нет
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        importService.fillImport(new ImportSummary());

        Tender t = tenderRepository.findBySourceExtId("521464-1").orElseThrow();
        assertThat(t.getLots()).hasSize(36);          // 20 + 16, а не только первая страница
        assertThat(t.getLots())
                .anySatisfy(l -> assertThat(l.getSourceLotCode()).isEqualTo("4875223-Д_ЛС_МИ1"))   // стр. 1
                .anySatisfy(l -> assertThat(l.getSourceLotCode()).isEqualTo("4875274-Д_ЛС_МИ1"));  // стр. 2
    }

    /**
     * Закупка медицинских УСЛУГ (ГОБМП/ОСМС) — не наш предмет: у неё нет колонки товара в принципе.
     * Такие объявления пропускаются целиком, а не пишутся тендером с нулём лотов.
     */
    @Test
    void import_skipsMedicalServicesAnnouncements() throws IOException {
        MarketContext.set(Market.KZ);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        when(client.lotsPage(anyString(), anyInt())).thenReturn(fixture("lots-services.html"));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getCreated()).isZero();
        assertThat(sum.getSkipped()).isEqualTo(10);                      // все 10 объявлений фикстуры — услуги
        assertThat(tenderRepository.findBySourceExtId("521464-1")).isEmpty();
    }

    /**
     * Портал за последней страницей отдаёт контент последней (page=4 = page=3) и пейджер продолжает обещать
     * «вперёд» — обход обязан остановиться на странице без НОВЫХ лотов, иначе импорт крутится до предела страниц.
     */
    @Test
    void import_stopsWhenPortalRepeatsLastLotPage() throws IOException {
        MarketContext.set(Market.KZ);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        // та же реальная страница, но пейджер ВСЕГДА обещает следующую: одного признака «есть ссылка вперёд» мало
        when(client.lotsPage(anyString(), anyInt())).thenAnswer(inv ->
                fixture("lots-ed-order.html").replace("page=2", "page=" + (inv.getArgument(1, Integer.class) + 1)));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        importService.fillImport(new ImportSummary());

        Tender t = tenderRepository.findBySourceExtId("521464-1").orElseThrow();
        assertThat(t.getLots()).hasSize(20);                              // без дублей
        verify(client, times(2)).lotsPage(eq("521464"), anyInt());        // стр. 1 + одна проверка повтора, дальше стоп
    }

    /** Лента не открылась (сеть, сертификат, бан) — ошибка прогона с причиной, а не «ничего нового». */
    @Test
    void import_unreachableFeed_reportsTheReason() {
        when(client.searchPage(1)).thenThrow(new UpstreamException("Сеть fms.ecc.kz: PKIX path building failed"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getFetched()).isZero();
        assertThat(sum.getLastError()).isEqualTo("лента, стр. 1: Сеть fms.ecc.kz: PKIX path building failed");
    }

    /** Пустая ПЕРВАЯ страница — не конец ленты (на портале тысячи объявлений), а сменившаяся вёрстка. */
    @Test
    void import_emptyFirstFeedPage_isAnError() {
        when(client.searchPage(anyInt())).thenReturn("<html><body><p>Ведутся технические работы</p></body></html>");

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo("на первой странице ленты нет объявлений — похоже, сменилась вёрстка fms.ecc.kz");
    }

    @Test
    void import_announcementError_reportsWhichAnnouncement() throws IOException {
        MarketContext.set(Market.KZ);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        when(client.lotsPage(anyString(), anyInt())).thenThrow(new UpstreamException("fms.ecc.kz вернул 503"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isPositive();
        assertThat(sum.getLastError()).matches("объявление \\d+-\\d+: fms\\.ecc\\.kz вернул 503");
    }

    /** Разовый обрыв ленты и лотов — повторяется, объявление не теряется (ревью A5). */
    @Test
    void import_transientFeedAndLotsFailure_retried() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        when(client.searchPage(1))
                .thenThrow(new SkCallException("Сеть fms.ecc.kz: нет ответа за 60 с", true))
                .thenReturn(fixture("search.html"));
        when(client.searchPage(2)).thenReturn("");
        servicesForOthers();
        when(client.lotsPage(eq("521464"), anyInt()))
                .thenThrow(new SkCallException("Сеть fms.ecc.kz: нет ответа за 60 с", true))
                .thenReturn(fixture("lots.html"));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isZero();
        assertThat(sum.getFetched()).isEqualTo(10);
        assertThat(tenderRepository.findBySourceExtId("521464-1")).isPresent();
        verify(client, times(2)).searchPage(1);
    }

    /** 403 повтором не лечится — один вызов, ошибка прогона. */
    @Test
    void import_clientErrorOnFeed_notRetried() {
        when(client.searchPage(1)).thenThrow(new SkCallException("fms.ecc.kz вернул 403", false));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo("лента, стр. 1: fms.ecc.kz вернул 403");
        verify(client, times(1)).searchPage(1);
    }

    // ---------- полнота лотов (ревью A3) ----------

    /**
     * Review Focus 2: вторая страница лотов пришла без таблицы (троттлинг). Список неполный → лоты, которых нет
     * в нём, не удаляются, прогон честно получает ошибку «на площадке K, получено M».
     */
    @Test
    void import_lotPageWithoutTable_keepsExistingLots_andReportsError() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        servicesForOthers();
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? searchWithLotsCount(36) : "");
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));
        // первый прогон — полный список: 20 + 16 = 36, как в ленте
        when(client.lotsPage(eq("521464"), anyInt())).thenAnswer(inv ->
                inv.getArgument(1, Integer.class) == 1 ? fixture("lots-ed-order.html") : fixture("lots-ed-order-last.html"));
        ImportSummary first = new ImportSummary();
        importService.fillImport(first);
        assertThat(first.getErrors()).isZero();
        assertThat(tenderRepository.findBySourceExtId("521464-1").orElseThrow().getLots()).hasSize(36);

        // второй прогон — стр. 2 вместо таблицы отдала заглушку, хотя стр. 1 обещала её в пейджере
        when(client.lotsPage(eq("521464"), anyInt())).thenAnswer(inv ->
                inv.getArgument(1, Integer.class) == 1 ? fixture("lots-ed-order.html") : THROTTLED);
        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo(
                "объявление 521464-1: на площадке 36 лотов, получено 20 — лишние лоты не удалялись");
        assertThat(tenderRepository.findBySourceExtId("521464-1").orElseThrow().getLots()).hasSize(36);
    }

    /** Упор в предел max-lot-pages при живой ссылке «вперёд» — тоже неполный список. */
    @Test
    void import_lotPagesLimitReached_isIncomplete() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        servicesForOthers();
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? searchWithLotsCount(36) : "");
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));
        when(client.lotsPage(eq("521464"), anyInt())).thenAnswer(inv ->
                inv.getArgument(1, Integer.class) == 1 ? fixture("lots-ed-order.html") : fixture("lots-ed-order-last.html"));
        SkPharmacyImportService limited = new SkPharmacyImportService(client, writer, 1, 1, 0);   // предел — 1 страница
        limited.setSleeper(ms -> { });

        ImportSummary sum = new ImportSummary();
        limited.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo(
                "объявление 521464-1: на площадке 36 лотов, получено 20 — лишние лоты не удалялись");
        assertThat(tenderRepository.findBySourceExtId("521464-1").orElseThrow().getLots()).hasSize(20);
        verify(client, times(1)).lotsPage(eq("521464"), anyInt());
    }

    /**
     * 0 лотов при непустом числе в ленте (и не услуги) — сбой разбора, а не «тендер без лотов»: раньше пустой
     * список уходил в фолбэк фильтра по имени объявления и тихо писался тендер без единого лота.
     */
    @Test
    void import_zeroLotsWhileFeedHasSome_isErrorAndNotWritten() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        servicesForOthers();
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        when(client.lotsPage(eq("521464"), anyInt())).thenReturn(THROTTLED);
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo(
                "объявление 521464-1: лоты не разобраны — на площадке 12, получено 0");
        assertThat(tenderRepository.findBySourceExtId("521464-1")).isEmpty();
    }

    /** Полный список (12 в ленте = 12 на вкладке) — без ошибок. */
    @Test
    void import_completeLotList_noError() throws IOException {
        MarketContext.set(Market.KZ);
        servicesForOthers();
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");
        when(client.lotsPage(eq("521464"), anyInt())).thenReturn(fixture("lots.html"));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isZero();
        assertThat(tenderRepository.findBySourceExtId("521464-1").orElseThrow().getLots()).hasSize(12);
    }

    /** Лента с другим названием объявления 521464-1 (в фикстуре — «Закуп медицинской техники»). */
    private String searchWithName(String name) throws IOException {
        return fixture("search.html").replaceFirst("<div>Закуп медицинской техники</div>", "<div>" + name + "</div>");
    }

    /** I1: «Допуск КТП/ОТП …» пропускается по названию, лоты даже не запрашиваются, тендера нет. */
    @Test
    void import_skipsDomesticProducerAdmission_byName() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        servicesForOthers();
        when(client.searchPage(anyInt())).thenAnswer(inv -> inv.getArgument(0, Integer.class) == 1
                ? searchWithName("Допуск КТП/ОТП к закупу МИ в рамках Долгосрочных договоров") : "");
        when(client.lotsPage(eq("521464"), anyInt())).thenReturn(fixture("lots.html"));
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isZero();
        assertThat(sum.getSkipped()).isEqualTo(10);                       // 9 услуг + допуск
        assertThat(tenderRepository.findBySourceExtId("521464-1")).isEmpty();
        verify(client, times(0)).lotsPage(eq("521464"), anyInt());
    }

    /**
     * I2: список лотов неполный (стр. 2 без таблицы), а полученные лоты — сплошь лекарства. Релевантность по
     * неполному списку не определить — это ошибка прогона, а не тихий пропуск.
     */
    @Test
    void import_incompleteLotsAllMedicines_isErrorNotSilentSkip() throws IOException {
        MarketContext.set(Market.KZ);
        tenderRepository.findBySourceExtId("521464-1").ifPresent(tenderRepository::delete);
        servicesForOthers();
        String feed = searchWithName("Закуп товаров").replaceFirst(
                "(521464-1[\\s\\S]*?)<td>12</td>", "$1<td>36</td>");
        when(client.searchPage(anyInt())).thenAnswer(inv -> inv.getArgument(0, Integer.class) == 1 ? feed : "");
        String medicines = """
                <html><body><table>
                <tr><th>№ п/п</th><th>№ лота</th><th>Наименование лота</th><th>Цена выделенная</th><th>Количество</th></tr>
                <tr><td>1</td><td>L-1</td><td>Парацетамол таблетки 500 мг</td><td>100.00</td><td>10</td></tr>
                <tr><td>2</td><td>L-2</td><td>Инсулин человеческий</td><td>200.00</td><td>5</td></tr>
                </table>
                <ul class="pagination"><li><a href="https://fms.ecc.kz/ru/announce/index/521464?tab=lots&amp;page=2">2</a></li></ul>
                </body></html>""";
        when(client.lotsPage(eq("521464"), anyInt())).thenAnswer(inv ->
                inv.getArgument(1, Integer.class) == 1 ? medicines : THROTTLED);
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getErrors()).isEqualTo(1);
        assertThat(sum.getLastError()).isEqualTo(
                "объявление 521464-1: список лотов неполный (получено 2 из 36) — релевантность не определена");
        assertThat(tenderRepository.findBySourceExtId("521464-1")).isEmpty();
    }
}
