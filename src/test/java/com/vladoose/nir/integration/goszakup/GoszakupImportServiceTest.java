package com.vladoose.nir.integration.goszakup;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Facility;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.SubjectDto;
import com.vladoose.nir.repository.FacilityRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class GoszakupImportServiceTest {

    // Тесты работают в регионе, которого НЕТ в сиде V13 (ЗКО), чтобы сеяные больницы не мешали счётчикам.
    private static final String REGION = "Карагандинская область";

    @Autowired TenderRepository tenderRepository;
    @Autowired FacilityRepository facilityRepository;
    @Autowired RegionResolver regionResolver;

    FakeGoszakupClient fake;
    GoszakupTenderWriter writer;
    GoszakupImportService service;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        writer = new GoszakupTenderWriter(tenderRepository, regionResolver);
        service = svc("", 3650);
    }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private GoszakupImportService svc(String statuses, int sinceDays) {
        return new GoszakupImportService(fake, writer, facilityRepository, statuses, sinceDays, 20, ms -> { });
    }

    /** Мониторимая KZ-больница в тестовом регионе с заданным БИН. */
    private Facility hospital(String name, String bin) {
        Facility f = new Facility();
        f.setName(name); f.setInn(bin); f.setRegion(REGION);
        f.setMonitorTenders(true); f.setMarket(Market.KZ);
        return facilityRepository.save(f);
    }

    private static LotDto lot(String name, String descr) {
        LotDto l = new LotDto();
        l.setLotNumber("1"); l.setNameRu(name); l.setDescriptionRu(descr);
        l.setAmount(new BigDecimal("6000000")); l.setCount(BigDecimal.ONE);
        return l;
    }

    @Test
    void createsKzPublicTender_fromOrgBinFeed() {
        hospital("Больница А", "BIN1");
        fake.orgPage("BIN1", FakeGoszakupClient.buy("100-1", "Приобретение изделий", 230, "BIN1",
                "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ портативный", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getOrgsTotal()).isEqualTo(1);
        assertThat(s.getOrgsProcessed()).isEqualTo(1);
        assertThat(s.getFetched()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(s.getMatched()).isEqualTo(1);
        assertThat(fake.orgBinsQueried).contains("BIN1");
        Tender t = tenderRepository.findBySourceExtId("100-1").orElseThrow();
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getSource()).isEqualTo(Source.PUBLIC_TENDER);
        assertThat(t.getRegion()).isEqualTo(REGION);            // регион из реестра, не от заказчика
        assertThat(t.getFacility()).isNull();
    }

    /**
     * B3: дробное количество и нулевая сумма лота — тендер всё равно записывается (раньше 0 ушёл бы
     * в CHECK max_cost &gt; 0 / количество обрезалось бы), количество и цена пусты, сырое — в пометке.
     */
    @Test
    void fractionalCountAndZeroAmount_writtenEmptyWithNote() {
        hospital("Больница Д", "BIN9");
        fake.orgPage("BIN9", FakeGoszakupClient.buy("B3-1", "Приобретение изделий", 230, "BIN9",
                "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        LotDto l = lot("Аппарат УЗИ портативный", "Портативный, с конвексным датчиком");
        l.setCount(new BigDecimal("0.5"));
        l.setAmount(BigDecimal.ZERO);
        fake.lotsByAnno.put("B3-1", List.of(l));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getErrors()).isZero();
        assertThat(s.getCreated()).isEqualTo(1);
        var lot = tenderRepository.findBySourceExtId("B3-1").orElseThrow().getLots().get(0);
        assertThat(lot.getQuantity()).isNull();
        assertThat(lot.getMaxCost()).isNull();
        assertThat(lot.getRequiredSpec()).startsWith("Количество на площадке: 0.5")
                .endsWith("Портативный, с конвексным датчиком");
    }

    @Test
    void dropsNonMedicalLots_evenFromMonitoredHospital() {
        hospital("Больница Б", "BIN2");
        fake.orgPage("BIN2",
                FakeGoszakupClient.buy("MED-1", "Государственный закупки медицинских изделий", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("FOOD-1", "Приобретение продуктов питания", 230, "BIN2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("MED-1", List.of(lot("Облучатель бактерицидный", null)));
        fake.lotsByAnno.put("FOOD-1", List.of(lot("Помидор", null), lot("Молоко натуральное", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("MED-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("FOOD-1")).isEmpty();
    }

    @Test
    void importedTenderNotVisibleOnRf() {
        hospital("Больница В", "BIN3");
        fake.orgPage("BIN3", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN3", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат ИВЛ", null)));
        service.importMedicalTenders(REGION);

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findBySourceExtId("100-1")).isEmpty();
    }

    @Test
    void idempotent_secondRunUpdatesNotDuplicates() {
        hospital("Больница Г", "BIN4");
        fake.orgPage("BIN4", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN4", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ портативный", null)));

        assertThat(service.importMedicalTenders(REGION).getCreated()).isEqualTo(1);
        fake.orgPages.get("BIN4").getItems().get(0).setTotalSum(new BigDecimal("9999999"));
        ImportSummary second = svc("", 3650).importMedicalTenders(REGION);

        assertThat(second.getCreated()).isEqualTo(0);
        assertThat(second.getUpdated()).isEqualTo(1);
        assertThat(tenderRepository.findAll().stream().filter(x -> "100-1".equals(x.getSourceExtId())).count()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("100-1").orElseThrow().getTotalCost()).isEqualByComparingTo("9999999");
    }

    @Test
    void regionFromRegistry_overridesResolvedCustomerRegion() {
        // республиканский заказчик (юрадрес Астана), но больница в реестре ЗКО-региона теста → регион = реестровый
        hospital("Больница Д", "BINAST");
        fake.orgPage("BINAST", FakeGoszakupClient.buy("Z-3", "Закуп", 230, "BINAST", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("Z-3", List.of(lot("Аппарат ИВЛ", null)));
        SubjectDto subj = new SubjectDto(); subj.setBin("BINAST"); subj.setNameRu("РГКП «Центр» г. Астана");
        fake.subjectsByBin.put("BINAST", subj);

        service.importMedicalTenders(REGION);

        Tender t = tenderRepository.findBySourceExtId("Z-3").orElseThrow();
        assertThat(t.getRegion()).isEqualTo(REGION);
        assertThat(t.getCustomerName()).contains("Центр");   // заказчик сохранён
    }

    @Test
    void skipsOld_bySinceDays() {
        hospital("Больница Е", "BIN5");
        String recent = java.time.LocalDate.now().minusDays(5) + "T00:00:00";
        String old = java.time.LocalDate.now().minusDays(400) + "T00:00:00";
        fake.orgPage("BIN5",
                FakeGoszakupClient.buy("NEW-1", "Закуп", 230, "BIN5", recent, recent),
                FakeGoszakupClient.buy("OLD-1", "Закуп", 230, "BIN5", old, old));
        fake.lotsByAnno.put("NEW-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("OLD-1", List.of(lot("Аппарат УЗИ", null)));

        svc("", 30).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("NEW-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("OLD-1")).isEmpty();
    }

    @Test
    void itemError_isCountedAndDoesNotAbortRun() {
        hospital("Больница Ж", "BIN6");
        fake.orgPage("BIN6",
                FakeGoszakupClient.buy("OK-1", "Закуп", 230, "BINOK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("ERR-1", "Закуп", 230, "BINERR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("OK-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("ERR-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsFailuresLeft.put("ERR-1", 99);   // лоты лежат и после повторов

        ImportSummary s = svc("", 3650).importMedicalTenders(REGION);

        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getLastError()).isEqualTo("объявление ERR-1: goszakup API недоступно: header parser received no bytes");
        assertThat(fake.lotsCalls.get("ERR-1")).isEqualTo(3);   // три попытки, не больше
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(tenderRepository.findBySourceExtId("OK-1")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("ERR-1")).isEmpty();
    }

    @Test
    void lotDescriptionRu_savedAsRequiredSpec() {
        hospital("Больница З", "BIN7");
        fake.orgPage("BIN7", FakeGoszakupClient.buy("100-1", "Закуп", 230, "BIN7", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("100-1", List.of(lot("Аппарат УЗИ", "УЗИ экспертного класса, не менее 3 датчиков, доплер")));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("100-1").orElseThrow().getLots().get(0).getRequiredSpec())
                .isEqualTo("УЗИ экспертного класса, не менее 3 датчиков, доплер");
    }

    @Test
    void skipsNonCurrentSystemId() {
        hospital("Больница И", "BIN8");
        var legacy = FakeGoszakupClient.buy("LEG-2", "Закуп", 230, "BIN8", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        legacy.setSystemId(2);
        var current = FakeGoszakupClient.buy("CUR-3", "Закуп", 230, "BIN8", "2026-06-01T00:00:00", "2026-06-20T00:00:00");
        current.setSystemId(3);
        fake.orgPage("BIN8", legacy, current);
        fake.lotsByAnno.put("LEG-2", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("CUR-3", List.of(lot("Аппарат УЗИ", null)));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("CUR-3")).isPresent();
        assertThat(tenderRepository.findBySourceExtId("LEG-2")).isEmpty();
    }

    @Test
    void expiredDeadline_overridesPortalActiveStatus() {
        hospital("Больница К", "BIN9");
        String past = java.time.LocalDate.now().minusDays(2) + "T00:00:00";
        fake.orgPage("BIN9", FakeGoszakupClient.buy("EXP-1", "Закуп", 220, "BIN9", past, past));
        fake.lotsByAnno.put("EXP-1", List.of(lot("Аппарат УЗИ", null)));

        svc("", 3650).importMedicalTenders(REGION);

        assertThat(tenderRepository.findBySourceExtId("EXP-1").orElseThrow().getStatus()).isEqualTo("COMPLETED");
    }

    @Test
    void emptyRegistryForRegion_returnsMessageWithoutFetching() {
        ImportSummary s = service.importMedicalTenders(REGION); // ни одной больницы не создали

        assertThat(s.isEnabled()).isFalse();
        assertThat(s.getMessage()).contains(REGION);
        assertThat(s.getOrgsTotal()).isZero();
        assertThat(fake.orgBinsQueried).isEmpty();
    }

    @Test
    void processesMultipleHospitals_countingOrgs() {
        hospital("Больница Л", "BIN10");
        hospital("Больница М", "BIN11");
        fake.orgPage("BIN10", FakeGoszakupClient.buy("A-1", "Закуп", 230, "BIN10", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.orgPage("BIN11", FakeGoszakupClient.buy("B-1", "Закуп", 230, "BIN11", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("A-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("B-1", List.of(lot("Аппарат ИВЛ", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getOrgsTotal()).isEqualTo(2);
        assertThat(s.getOrgsProcessed()).isEqualTo(2);
        assertThat(s.getCreated()).isEqualTo(2);
        assertThat(fake.orgBinsQueried).contains("BIN10", "BIN11");
    }

    /** Больница, по которой площадка не ответила, названа в итоге прогона вместе с причиной. */
    @Test
    void orgFeedError_namesTheHospitalAndTheReason() {
        hospital("Больница Н", "BIN12");
        hospital("Больница О", "BIN13");
        fake.failingOrgBins.add("BIN12");
        fake.orgPage("BIN13", FakeGoszakupClient.buy("C-1", "Закуп", 230, "BIN13", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("C-1", List.of(lot("Аппарат УЗИ", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(s.getLastError()).isEqualTo("Больница Н: goszakup API недоступно: ConnectException");
    }

    /** Ревью A5: на разовом обрыве выпадало 4 из 29 больниц — лента и лоты повторяются. */
    @Test
    void transientFeedFailure_retried_orgImported() {
        hospital("Больница Р", "BINR");
        fake.orgPage("BINR", FakeGoszakupClient.buy("200-1", "Закуп", 230, "BINR", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("200-1", List.of(lot("Аппарат УЗИ", null)));
        fake.orgFailuresLeft.put("BINR", 1);
        fake.lotsFailuresLeft.put("200-1", 1);

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getErrors()).isZero();
        assertThat(s.getCreated()).isEqualTo(1);
        assertThat(fake.orgCalls.get("BINR")).isEqualTo(2);
        assertThat(fake.lotsCalls.get("200-1")).isEqualTo(2);
    }

    /** 400/403 повтором не лечатся — один вызов, сразу в ошибки прогона. */
    @Test
    void clientErrorNotRetried() {
        hospital("Больница Ф", "BINF");
        fake.forbiddenOrgBins.add("BINF");

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getErrors()).isEqualTo(1);
        assertThat(s.getLastError()).isEqualTo("Больница Ф: goszakup API 403 на /v3/graphql");
        assertThat(fake.orgCalls.get("BINF")).isEqualTo(1);
    }

    /** subject — только для профильных объявлений и один раз на БИН за прогон. */
    @Test
    void subjectFetchedOnlyForRelevant_andOncePerBin() {
        hospital("Больница С", "BINS");
        fake.orgPage("BINS",
                FakeGoszakupClient.buy("301-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("302-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("303-1", "Закуп", 230, "BINS", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("301-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("302-1", List.of(lot("Монитор пациента", null)));
        fake.lotsByAnno.put("303-1", List.of(lot("Бумага офисная", null)));
        // больница, у которой единственное объявление непрофильное: subject для её БИН не нужен вовсе
        hospital("Больница С2", "BINS2");
        fake.orgPage("BINS2",
                FakeGoszakupClient.buy("304-1", "Закуп", 230, "BINS2", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("304-1", List.of(lot("Бумага офисная", null)));

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getCreated()).isEqualTo(2);
        assertThat(fake.subjectCalls.get("BINS")).isEqualTo(1);
        assertThat(fake.subjectCalls.getOrDefault("BINS2", 0)).isZero();
    }

    /** Неудача subject тоже запоминается на прогон — иначе каждое объявление больницы повторит три попытки. */
    @Test
    void subjectFailureCachedPerBin_tenderWrittenWithoutCustomer() {
        hospital("Больница Т", "BINT");
        fake.orgPage("BINT",
                FakeGoszakupClient.buy("501-1", "Закуп", 230, "BINT", "2026-06-01T00:00:00", "2026-06-20T00:00:00"),
                FakeGoszakupClient.buy("502-1", "Закуп", 230, "BINT", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("501-1", List.of(lot("Аппарат УЗИ", null)));
        fake.lotsByAnno.put("502-1", List.of(lot("Монитор пациента", null)));
        fake.subjectFailuresLeft.put("BINT", 99);

        ImportSummary s = service.importMedicalTenders(REGION);

        assertThat(s.getErrors()).isZero();
        assertThat(s.getCreated()).isEqualTo(2);
        assertThat(fake.subjectCalls.get("BINT")).isEqualTo(3);   // три попытки один раз, не 3 × 2
        Tender t = tenderRepository.findBySourceExtId("501-1").orElseThrow();
        assertThat(t.getCustomerName()).isNull();
        assertThat(t.getRegion()).isEqualTo(REGION);
    }

    /** Review Focus 4: subject лежит и после повторов — существующий тендер обновляется, заказчик не затирается. */
    @Test
    void subjectDownAfterRetries_existingTenderKeepsCustomer() {
        hospital("Больница К2", "BINK");
        fake.orgPage("BINK", FakeGoszakupClient.buy("400-1", "Закуп", 230, "BINK", "2026-06-01T00:00:00", "2026-06-20T00:00:00"));
        fake.lotsByAnno.put("400-1", List.of(lot("Аппарат УЗИ", null)));
        SubjectDto subj = new SubjectDto();
        subj.setBin("BINK"); subj.setNameRu("ГКП Больница К");
        com.vladoose.nir.integration.goszakup.dto.SubjectAddressDto addr = new com.vladoose.nir.integration.goszakup.dto.SubjectAddressDto();
        addr.setAddress("г. Караганда, ул. Ленина, 1"); addr.setKatoCode("351010000");
        subj.setAddress(List.of(addr));
        fake.subjectsByBin.put("BINK", subj);
        service.importMedicalTenders(REGION);                        // первый прогон — заказчик записан

        fake.subjectFailuresLeft.put("BINK", 99);                    // дальше subject лежит
        ImportSummary s = svc("", 3650).importMedicalTenders(REGION);

        assertThat(s.getErrors()).isZero();
        assertThat(s.getUpdated()).isEqualTo(1);
        Tender t = tenderRepository.findBySourceExtId("400-1").orElseThrow();
        assertThat(t.getCustomerName()).isEqualTo("ГКП Больница К");
        assertThat(t.getRegionKato()).isEqualTo("351010000");
        assertThat(t.getDeliveryAddress()).isEqualTo("г. Караганда, ул. Ленина, 1");
        assertThat(t.getRegion()).isEqualTo(REGION);
    }
}
