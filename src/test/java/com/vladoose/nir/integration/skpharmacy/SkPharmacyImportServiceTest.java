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
    @MockitoBean SkPharmacyClient client;

    @AfterEach void clear() { MarketContext.clear(); }

    private String fixture(String name) throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/" + name)) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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
        when(client.lotsPage(anyString(), anyInt())).thenReturn(fixture("lots.html"));   // device-лоты (томограф/МРТ)
        when(client.generalPage(anyString())).thenReturn(fixture("general-distributor.html"));  // вкладка «Общие сведения» 521464

        ImportSummary sum = new ImportSummary();
        importService.fillImport(sum);

        assertThat(sum.getFetched()).isEqualTo(10);      // 10 объявлений в фикстуре
        assertThat(sum.getMatched()).isGreaterThanOrEqualTo(1);
        assertThat(sum.getCreated()).isGreaterThanOrEqualTo(1);

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
}
