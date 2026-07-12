package com.vladoose.nir.integration.skpharmacy;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderPlatform;
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
import static org.mockito.Mockito.when;

/** Импорт СК-Ф на моке клиента (реальные HTML-фикстуры) → парс+фильтр+upsert. */
@SpringBootTest
@Transactional
@org.springframework.test.context.TestPropertySource(properties = "skpharmacy.import.throttle-ms=0")
class SkPharmacyImportServiceTest {

    @Autowired SkPharmacyImportService importService;
    @Autowired TenderRepository tenderRepository;
    @MockitoBean SkPharmacyClient client;

    @AfterEach void clear() { MarketContext.clear(); }

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void import_createsSkTenders_withPlatformAndDeviceLots() throws IOException {
        MarketContext.set(Market.KZ);
        when(client.searchPage(anyInt())).thenAnswer(inv ->
                inv.getArgument(0, Integer.class) == 1 ? fixture("search.html") : "");   // 1 страница, дальше конец
        when(client.lotsPage(anyString())).thenReturn(fixture("lots.html"));             // device-лоты (томограф/МРТ)

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getFetched()).isEqualTo(10);      // 10 объявлений в фикстуре
        assertThat(sum.getMatched()).isGreaterThanOrEqualTo(1);
        assertThat(sum.getCreated()).isGreaterThanOrEqualTo(1);

        Tender t = tenderRepository.findBySourceExtId("521464-1").orElseThrow();
        assertThat(t.getPlatform()).isEqualTo(TenderPlatform.SK_PHARMACY);
        assertThat(t.getMarket()).isEqualTo(Market.KZ);
        assertThat(t.getCurrency()).isEqualTo("KZT");
        assertThat(t.getLots()).isNotEmpty()
                .anySatisfy(l -> assertThat(l.getEquipName().toLowerCase()).contains("томограф"))
                .anySatisfy(l -> assertThat(l.getSourceLotCode()).isEqualTo("1040409-Т1"));  // код лота сохранён (ключ ТЗ)
    }
}
