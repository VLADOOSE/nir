package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.integration.skpharmacy.SkTechSpecClient;
import com.vladoose.nir.integration.skpharmacy.SkTechSpecRef;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/** Разбор ТЗ SK-лота: мок SkTechSpecClient (реальный PDF-фикстур) + join по коду лота + таксономия ошибок. */
@SpringBootTest
@Transactional
class TechSpecServiceSkTest {

    @Autowired TechSpecService techSpecService;
    @Autowired TenderRepository tenderRepository;
    @MockitoBean SkTechSpecClient skClient;

    private static final String URL = "https://fms.ecc.kz/files/download_file/49046026/";

    @AfterEach void clear() { MarketContext.clear(); }

    /** SK-тендер (синтетический SKTEST-1) + лот с кодом; вернуть id лота. */
    private Long persistSkLot(String lotCode) {
        Tender t = new Tender();
        t.setTenderNumber("SKTEST-1");          // синтетический — не коллидит с реально импортированными
        t.setSourceExtId("SKTEST-1");
        t.setPlatform(TenderPlatform.SK_PHARMACY);
        t.setSource(Source.PUBLIC_TENDER);
        t.setMarket(Market.KZ);
        t.setStatus("ACTIVE");
        TenderLot lot = new TenderLot();
        lot.setTender(t);
        lot.setLotNumber(1);
        lot.setSourceLotCode(lotCode);
        lot.setEquipName("компьютерный томограф");
        t.getLots().add(lot);
        tenderRepository.save(t);
        return lot.getId();
    }

    private byte[] pdfFixture() throws IOException {
        try (var is = getClass().getResourceAsStream("/skpharmacy/techspec-kt.pdf")) {
            return is.readAllBytes();
        }
    }

    @Test
    void parse_skLot_fillsRequiredSpec() throws IOException {
        MarketContext.set(Market.KZ);
        Long lotId = persistSkLot("1040409-Т1");
        when(skClient.fetchTechSpecRefs("SKTEST"))
                .thenReturn(List.of(new SkTechSpecRef("1040409-Т1", URL, "ТС КТ каз-русс.pdf")));
        when(skClient.downloadFile(URL)).thenReturn(pdfFixture());

        TechSpecService.ParseResult res = techSpecService.parse(lotId);

        assertThat(res.source()).isEqualTo("ТС КТ каз-русс.pdf");
        assertThat(res.ambiguous()).isFalse();
        assertThat(res.lot().getRequiredSpec()).isNotBlank();
    }

    @Test
    void parse_skLot_multipleFiles_firstWithAmbiguous() throws IOException {
        MarketContext.set(Market.KZ);
        Long lotId = persistSkLot("1040409-Т1");
        when(skClient.fetchTechSpecRefs("SKTEST")).thenReturn(List.of(
                new SkTechSpecRef("1040409-Т1", URL, "ТС КТ.pdf"),
                new SkTechSpecRef("1040409-Т1", "https://fms.ecc.kz/files/download_file/2/", "ТС КТ не подп.pdf")));
        when(skClient.downloadFile(URL)).thenReturn(pdfFixture());

        TechSpecService.ParseResult res = techSpecService.parse(lotId);

        assertThat(res.source()).isEqualTo("ТС КТ.pdf");   // первый
        assertThat(res.ambiguous()).isTrue();
    }

    @Test
    void parse_skLot_withoutCode_400() {
        MarketContext.set(Market.KZ);
        Long lotId = persistSkLot(null);   // старый импорт до V12 — кода нет
        assertThatThrownBy(() -> techSpecService.parse(lotId)).isInstanceOf(BadRequestException.class);
    }

    @Test
    void parse_skLot_noTechSpecRow_404() {
        MarketContext.set(Market.KZ);
        Long lotId = persistSkLot("1040409-Т1");
        when(skClient.fetchTechSpecRefs("SKTEST")).thenReturn(List.of());  // на объявлении нет ТЗ
        assertThatThrownBy(() -> techSpecService.parse(lotId)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void parse_skLot_pdfGone_404() {
        MarketContext.set(Market.KZ);
        Long lotId = persistSkLot("1040409-Т1");
        when(skClient.fetchTechSpecRefs("SKTEST"))
                .thenReturn(List.of(new SkTechSpecRef("1040409-Т1", URL, "ТС КТ.pdf")));
        when(skClient.downloadFile(URL)).thenReturn(null);  // 404
        assertThatThrownBy(() -> techSpecService.parse(lotId)).isInstanceOf(NotFoundException.class);
    }
}
