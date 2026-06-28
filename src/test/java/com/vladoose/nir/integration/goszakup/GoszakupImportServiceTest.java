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
}
