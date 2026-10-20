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
import com.vladoose.nir.integration.goszakup.dto.FoundPlanPoint;
import com.vladoose.nir.integration.goszakup.dto.SearchPage;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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

    static FoundPlanPoint plan(long id, String name, String desc) {
        return new FoundPlanPoint(id, 2026, 11, "Ноябрь", "990240006109", "ГКП «Айтекебийская районная больница»", name, desc, "",
                BigDecimal.ONE, "Штука", new BigDecimal("1200000"), new BigDecimal("1200000"), "Запрос ценовых предложений", 2,
                "Утвержден", "Актюбинская область");
    }

    /**
     * Планы — только по словам наших видов и только пункты, проходящие вид позиций заявки: «Блок управления» похож на
     * «Блок аспирации» одним словом, но это не отсасыватель; по «запасным» словам («сменный», «фильтр») планы не ищутся.
     */
    @Test
    void plans_onlyOurTypes_notByFallbackWords() {
        long id = request(List.of("Блок аспирации", "Фильтр сменный"));
        search.plansByWord.put("аспирации", List.of(plan(1, "Отсасыватель хирургический", "для аспирации жидкостей"),
                plan(2, "Блок управления", "электронный")));
        search.plansByWord.put("сменный", List.of(plan(3, "Фильтр", "для маски защиты органов дыхания, сменный")));
        search.plansByWord.put("фильтр", List.of(plan(4, "Фильтр", "для маски защиты органов дыхания, сменный")));

        TenderSearchStatus s = service.runNow(id);

        assertThat(s.hits()).extracting(TenderSearchHit::lotName).containsExactly("Отсасыватель хирургический");
        assertThat(search.calls).contains("plans:аспирации").doesNotContain("plans:сменный", "plans:фильтр");
    }

    /** «ждёт: идёт поиск по другой заявке» — только когда правда идёт другой поиск. */
    @Test
    void waitingOnlyBehindAnotherSearch() throws Exception {
        long first = request(List.of("Блок аспирации"));
        long second = request(List.of("Наконечник аспирационный"));
        CountDownLatch release = new CountDownLatch(1);
        FakeGoszakupSearchClient slow = new FakeGoszakupSearchClient() {
            @Override public SearchPage<FoundLot> searchLots(String w, List<Integer> st, LocalDate f, LocalDate t, Long a) {
                try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return super.searchLots(w, st, f, t, a);
            }
        };
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.groups(), List.of());
        TenderSearchService svc = new TenderSearchService(slow, client, requests, () -> snap, tenders, regions, CLOCK, ms -> { });
        try {
            assertThat(svc.start(first).waiting()).as("первый — не в очереди").isNull();
            assertThat(svc.start(second).waiting()).isEqualTo("ждёт: идёт поиск по другой заявке");
        } finally {
            release.countDown();
        }
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
