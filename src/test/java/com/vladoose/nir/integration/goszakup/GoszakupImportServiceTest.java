package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GoszakupImportServiceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired RegionResolver regionResolver;

    FakeGoszakupClient fake;
    GoszakupTenderWriter writer;
    KatoDictionary kato;
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        writer = new GoszakupTenderWriter(tenderRepository, regionResolver);
        kato = new KatoDictionary(fake);
        service = new GoszakupImportService(fake, writer, kato, "аппарат,узи", "", 3650, 20);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private GoszakupImportService svc(String keywords, String statuses, int sinceDays) {
        return new GoszakupImportService(fake, writer, kato, keywords, statuses, sinceDays, 20);
    }

    @Test
    void filtersByKeyword_andCreatesKzPublicTender() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ экспертного класса", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("200-1", "Канцелярские товары для офиса", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(s.getErrors()).isEqualTo(0);

        Optional<Tender> t = tenderRepository.findBySourceExtId("100-1");
        assertThat(t).isPresent();
        assertThat(t.get().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.get().getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.get().getCurrency()).isEqualTo("KZT");
        assertThat(t.get().getFacility()).isNull();
        assertThat(t.get().getDescription()).isEqualTo("Аппарат УЗИ экспертного класса");
        assertThat(tenderRepository.findBySourceExtId("200-1")).isEmpty();
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат ИВЛ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        svc("аппарат", "", 3650).importMedicalTenders();

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findBySourceExtId("100-1")).isEmpty();
    }

    @Test
    void idempotent_secondRunUpdatesNotDuplicates() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.LotDto lot = new com.vladoose.nir.integration.goszakup.dto.LotDto();
        lot.setLotNumber("1"); lot.setNameRu("Аппарат УЗИ портативный");
        lot.setAmount(new java.math.BigDecimal("6000000")); lot.setCount(2);
        fake.lotsByAnno.put("100-1", java.util.List.of(lot));

        GoszakupImportService s1 = svc("аппарат", "", 3650);
        assertThat(s1.importMedicalTenders().getCreated()).isEqualTo(1);

        fake.pages.get(null).getItems().get(0).setTotalSum(new java.math.BigDecimal("9999999"));
        ImportSummary second = svc("аппарат", "", 3650).importMedicalTenders();
        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(tenderRepository.findAll().stream()
                .filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1);
        assertThat(t.getTotalCost()).isEqualByComparingTo("9999999");
        assertThat(t.getLots()).hasSize(1);
        assertThat(t.getLots().get(0).getEquipName()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(t.getLots().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void lenientStatusesParse_skipsNonNumericTokenAndStillFiltersByValidId() {
        fake.page(null, null,
                FakeGoszakupClient.buy("OK-230", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("NO-999", "Аппарат УЗИ", 999, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = svc("аппарат", "230,foo", 3650).importMedicalTenders();

        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-230")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("NO-999")).isEmpty();
    }

    @Test
    void resolvesRegionFromSubject() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("BIN1"); subj.setNameRu("Городская поликлиника №5 г. Алматы");
        fake.subjectsByBin.put("BIN1", subj);

        svc("аппарат", "", 3650).importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getRegion()).isEqualTo("г. Алматы");
        assertThat(t.getCustomerName()).isEqualTo("Городская поликлиника №5 г. Алматы");
    }

    @Test
    void fallsBackToOrgBin_whenCustomerBinAbsent() {
        // живой /v2/trd-buy отдаёт только org_bin (customer_bin в ответе нет) —
        // subject всё равно должен подтянуться, а BIN — сохраниться в тендере
        var d = FakeGoszakupClient.buy("ORG-1", "Аппарат УЗИ", 230, null, "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        d.setOrgBin("971240005114");
        fake.page(null, null, d);
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("971240005114"); subj.setNameRu("Городская поликлиника №5 г. Алматы");
        fake.subjectsByBin.put("971240005114", subj);

        svc("аппарат", "", 3650).importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("ORG-1").orElseThrow();
        assertThat(t.getCustomerBin()).isEqualTo("971240005114");
        assertThat(t.getCustomerName()).isEqualTo("Городская поликлиника №5 г. Алматы");
    }

    @Test
    void paginatesAcrossPages_andSkipsOldBySinceDays() {
        String recentIso = java.time.LocalDate.now().minusDays(5) + "T00:00:00";
        String oldIso = java.time.LocalDate.now().minusDays(400) + "T00:00:00";
        fake.page(null, "/v2/trd-buy?page=next&search_after=1",
                FakeGoszakupClient.buy("NEW-1", "Аппарат УЗИ", 230, "BIN1", recentIso, recentIso));
        fake.page("/v2/trd-buy?page=next&search_after=1", null,
                FakeGoszakupClient.buy("OLD-1", "Аппарат УЗИ", 230, "BIN2", oldIso, oldIso));

        ImportSummary s = svc("аппарат", "", 30).importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty();
    }

    @Test
    void itemError_isCountedAndDoesNotAbortRun() {
        // ERR-1: fetchSubject бросает (имитация 500/таймаута) → объявление уходит в errors,
        // OK-1 импортируется штатно (раньше один сбой ронял весь @Transactional-прогон).
        fake.page(null, null,
                FakeGoszakupClient.buy("OK-1", "Аппарат УЗИ", 230, "BINOK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("ERR-1", "Аппарат УЗИ", 230, "BINERR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.failingSubjectBins.add("BINERR");

        ImportSummary s = svc("аппарат", "", 3650).importMedicalTenders();

        assertThat(s.getMatched()).isEqualTo(2);
        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("ERR-1")).isEmpty();
    }

    @Test
    void lotDescriptionRu_savedAsRequiredSpec() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.LotDto lot = new com.vladoose.nir.integration.goszakup.dto.LotDto();
        lot.setNameRu("Аппарат УЗИ");
        lot.setDescriptionRu("УЗИ экспертного класса, не менее 3 датчиков, доплер");
        lot.setAmount(new java.math.BigDecimal("6000000")); lot.setCount(1);
        fake.lotsByAnno.put("100-1", java.util.List.of(lot));

        svc("аппарат", "", 3650).importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getLots().get(0).getRequiredSpec())
                .isEqualTo("УЗИ экспертного класса, не менее 3 датчиков, доплер");
    }

    @Test
    void stopsPaginating_whenWholePageOlderThanCutoff() {
        // лента /trd-buy отсортирована по id DESC → страница целиком старше cutoff
        // означает «дальше только старее», вторую страницу запрашивать не нужно
        String oldIso = java.time.LocalDate.now().minusDays(400) + "T00:00:00";
        fake.page(null, "/v2/trd-buy?page=next&search_after=1",
                FakeGoszakupClient.buy("OLD-1", "Аппарат УЗИ", 230, "BIN1", oldIso, oldIso),
                FakeGoszakupClient.buy("OLD-2", "Аппарат УЗИ", 230, "BIN2", oldIso, oldIso));
        String recentIso = java.time.LocalDate.now().minusDays(5) + "T00:00:00";
        fake.page("/v2/trd-buy?page=next&search_after=1", null,
                FakeGoszakupClient.buy("LATE-1", "Аппарат УЗИ", 230, "BIN3", recentIso, recentIso));

        ImportSummary s = svc("аппарат", "", 30).importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2); // вторая страница не читалась
        assertThat(tenderRepository.findBySourceExtId("LATE-1")).isEmpty();
    }

    @Test
    void regionImport_usesKatoFilteredV3Path_withSameKeywordFilter() {
        fake.katoPage(null, null, FakeGoszakupClient.kato("27", "10", "10", "000"));
        fake.v3Page(null, 100L,
                FakeGoszakupClient.buy("ZKO-1", "Аппарат УЗИ для ЗКО", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.v3Page(100L, null,
                FakeGoszakupClient.buy("ZKO-2", "Канцтовары", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = svc("аппарат", "", 3650).importMedicalTenders("Западно-Казахстанская область");

        assertThat(fake.lastKatoFilter).containsExactly("271010000"); // серверный фильтр région
        assertThat(s.getFetched()).isEqualTo(2);                      // обе v3-страницы прочитаны
        assertThat(s.getCreated()).isEqualTo(1);                      // ключевые слова работают и тут
        assertThat(tenderRepository.findBySourceExtId("ZKO-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("ZKO-2")).isEmpty();
        assertThat(fake.trdBuyFetches).isZero();                      // общая лента v2 не тронута
    }

    @Test
    void regionImport_overridesResolvedRegion_withRequestedOne() {
        // республиканский заказчик (юрадрес Астана) с поставкой в ЗКО: сервер отфильтровал
        // по КАТО поставки → регион тендера ставим запрошенный, а не регион заказчика
        fake.katoPage(null, null, FakeGoszakupClient.kato("27", "10", "10", "000"));
        fake.v3Page(null, null,
                FakeGoszakupClient.buy("ZKO-3", "Аппарат ИВЛ", 230, "BINAST", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("BINAST"); subj.setNameRu("РГКП «Центр судебных экспертиз» г. Астана");
        fake.subjectsByBin.put("BINAST", subj);

        svc("аппарат", "", 3650).importMedicalTenders("Западно-Казахстанская область");

        var t = tenderRepository.findBySourceExtId("ZKO-3").orElseThrow();
        assertThat(t.getRegion()).isEqualTo("Западно-Казахстанская область");
        assertThat(t.getCustomerName()).contains("Центр судебных экспертиз"); // заказчик не потерян
    }

    @Test
    void regionImport_unknownRegion_returnsMessageWithoutFetching() {
        ImportSummary s = service.importMedicalTenders("Марс");

        assertThat(s.isEnabled()).isFalse();
        assertThat(s.getMessage()).contains("Марс");
        assertThat(fake.trdBuyFetches).isZero();
    }

    @Test
    void expiredDeadline_overridesPortalActiveStatus() {
        // площадка может держать 220 «Опубликовано (приём заявок)» после дедлайна —
        // локально такой тендер сразу COMPLETED (без дребезга до ночного джоба)
        String past = java.time.LocalDate.now().minusDays(2) + "T00:00:00";
        fake.page(null, null,
                FakeGoszakupClient.buy("EXP-1", "Аппарат УЗИ", 220, "BIN1", past, past));

        svc("аппарат", "", 3650).importMedicalTenders();

        assertThat(tenderRepository.findBySourceExtId("EXP-1").orElseThrow().getStatus())
                .isEqualTo("COMPLETED");
    }

    @Test
    void skipsNonCurrentSystemId() {
        // system_id: 1=ценовые предложения, 2=конкурс/аукцион, 3=текущая версия госзакупа.
        var legacy = FakeGoszakupClient.buy("LEG-2", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        legacy.setSystemId(2);
        var current = FakeGoszakupClient.buy("CUR-3", "Аппарат УЗИ", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        current.setSystemId(3);
        fake.page(null, null, legacy, current);

        svc("аппарат", "", 3650).importMedicalTenders();

        assertThat(tenderRepository.findBySourceExtId("CUR-3")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("LEG-2")).isEmpty(); // system_id=2 отброшен
    }
}
