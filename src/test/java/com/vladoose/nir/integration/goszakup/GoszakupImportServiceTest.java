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
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        // keywords=аппарат,узи ; statuses пусто ; since-days большой ; max-pages 20
        service = new GoszakupImportService(fake, regionResolver, tenderRepository,
                "аппарат,узи", "", 3650, 20);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    @Test
    void filtersByKeyword_andCreatesKzPublicTender() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ экспертного класса", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("200-1", "Канцелярские товары для офиса", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);

        Optional<Tender> t = tenderRepository.findBySourceExtId("100-1");
        assertThat(t).isPresent();
        assertThat(t.get().getMarket()).isEqualTo(Market.KZ);
        assertThat(t.get().getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.get().getCurrency()).isEqualTo("KZT");
        assertThat(t.get().getFacility()).isNull();
        assertThat(t.get().getDescription()).isEqualTo("Аппарат УЗИ экспертного класса");
        assertThat(tenderRepository.findBySourceExtId("200-1")).isEmpty(); // нерелевантный отброшен
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат ИВЛ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        service.importMedicalTenders();

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

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        ImportSummary first = service.importMedicalTenders();
        assertThat(first.getCreated()).isEqualTo(1);

        // меняем сумму и лот → второй прогон обновляет
        fake.pages.get(null).getItems().get(0).setTotalSum(new java.math.BigDecimal("9999999"));
        ImportSummary second = service.importMedicalTenders();
        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(tenderRepository.findAll().stream()
                .filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1); // нет дублей
        assertThat(t.getTotalCost()).isEqualByComparingTo("9999999");
        assertThat(t.getLots()).hasSize(1);
        assertThat(t.getLots().get(0).getEquipName()).isEqualTo("Аппарат УЗИ портативный");
        assertThat(t.getLots().get(0).getQuantity()).isEqualTo(2);
    }

    @Test
    void lenientStatusesParse_skipsNonNumericTokenAndStillFiltersByValidId() {
        // statuses="230,foo": кривой токен "foo" не должен ронять конструктор бина,
        // при этом фильтрация по валидному id 230 продолжает работать
        fake.page(null, null,
                FakeGoszakupClient.buy("OK-230", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("NO-999", "Аппарат УЗИ", 999, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));

        // конструкция с нечисловым токеном не бросает
        service = new GoszakupImportService(fake, regionResolver, tenderRepository,
                "аппарат", "230,foo", 3650, 20);
        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-230")).isPresent();   // статус 230 распознан
        assertThat(tenderRepository.findBySourceExtId("NO-999")).isEmpty();     // 999 отфильтрован → фильтр активен, "foo" проигнорирован
    }

    @Test
    void resolvesRegionFromSubject() {
        fake.page(null, null,
                FakeGoszakupClient.buy("100-1", "Аппарат УЗИ", 230, "BIN1", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        com.vladoose.nir.integration.goszakup.dto.SubjectDto subj = new com.vladoose.nir.integration.goszakup.dto.SubjectDto();
        subj.setBin("BIN1"); subj.setNameRu("Городская поликлиника №5 г. Алматы");
        fake.subjectsByBin.put("BIN1", subj);

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 3650, 20);
        service.importMedicalTenders();

        var t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getRegion()).isEqualTo("г. Алматы");
        assertThat(t.getCustomerName()).isEqualTo("Городская поликлиника №5 г. Алматы");
    }

    @Test
    void paginatesAcrossPages_andSkipsOldBySinceDays() {
        // since-days=30; даты считаем от now() — тест стабилен в любой день прогона
        String recentIso = java.time.LocalDate.now().minusDays(5) + "T00:00:00";   // в пределах 30 дней
        String oldIso = java.time.LocalDate.now().minusDays(400) + "T00:00:00";    // старше 30 дней
        fake.page(null, "/v2/trd-buy?page=next&search_after=1",
                FakeGoszakupClient.buy("NEW-1", "Аппарат УЗИ", 230, "BIN1", recentIso, recentIso));
        fake.page("/v2/trd-buy?page=next&search_after=1", null,
                FakeGoszakupClient.buy("OLD-1", "Аппарат УЗИ", 230, "BIN2", oldIso, oldIso));

        service = new GoszakupImportService(fake, regionResolver, tenderRepository, "аппарат", "", 30, 20);
        ImportSummary s = service.importMedicalTenders();

        assertThat(s.getFetched()).isEqualTo(2);
        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty(); // старше since-days
    }
}
