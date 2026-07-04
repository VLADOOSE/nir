package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.exception.UnprocessableException;
import com.vladoose.nir.integration.goszakup.FakeGoszakupClient;
import com.vladoose.nir.integration.goszakup.dto.LotTechSpecRef;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.util.SpecConstraintExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Transactional
class TechSpecServiceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderLotRepository tenderLotRepository;
    @Autowired TenderLotService tenderLotService;
    @Autowired TechSpecWriter writer;

    FakeGoszakupClient fake;
    TechSpecService service;
    Tender tender;
    TenderLot lot;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        fake = new FakeGoszakupClient();
        service = new TechSpecService(tenderLotService, fake, writer);

        tender = new Tender();
        tender.setTenderNumber("ZZTS-874-1");
        tender.setStatus("ACTIVE");
        tender.setSourceExtId("ZZTS-874-1");
        tenderRepository.save(tender);

        lot = new TenderLot();
        lot.setTender(tender);
        lot.setEquipName("Пульсоксиметр");
        lot.setQuantity(5);
        lot.setRequiredSpec("медицинский");
        tenderLotRepository.save(lot);
    }

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    private byte[] fixture() throws Exception {
        try (var in = getClass().getResourceAsStream("/goszakup/techspec-pulse.pdf")) {
            return in.readAllBytes();
        }
    }

    @Test
    void parsesRealPdf_writesRussianSectionToLot() throws Exception {
        fake.techSpecByKey.put("ZZTS-874-1|Пульсоксиметр",
                new LotTechSpecRef("http://f/1", "techspec_17280874_.pdf", false));
        fake.filesByUrl.put("http://f/1", fixture());

        TechSpecService.ParseResult r = service.parse(lot.getId());

        // PDF переносит строки внутри фраз — проверяем фрагменты, не пересекающие перенос
        assertThat(r.lot().getRequiredSpec())
                .contains("Портативное устройство")
                .contains("сатурации: 0-100")
                .doesNotContain("Лоттың"); // казахская секция отрезана
        assertThat(r.dimsFound()).isFalse();   // у пульсоксиметра габаритов в ТЗ нет
        assertThat(r.weightFound()).isFalse();
        assertThat(r.ambiguous()).isFalse();
        assertThat(r.source()).isEqualTo("techspec_17280874_.pdf");
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .contains("сатурации: 0-100");
    }

    @Test
    void writerSemantics_dimsWrittenAndNotErasedByNextParse() {
        var c1 = SpecConstraintExtractor.extract("Габариты не более 1200х800х1300 мм, вес не более 45 кг");
        writer.apply(lot.getId(), "ТЗ раз", c1);
        TenderLot l1 = tenderLotRepository.findById(lot.getId()).orElseThrow();
        assertThat(l1.getMaxLengthMm()).isEqualTo(1200);
        assertThat(l1.getMaxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
        assertThat(l1.getRequiredSpec()).isEqualTo("ТЗ раз");

        var empty = SpecConstraintExtractor.extract("никаких чисел");
        writer.apply(lot.getId(), "ТЗ два", empty);
        TenderLot l2 = tenderLotRepository.findById(lot.getId()).orElseThrow();
        assertThat(l2.getRequiredSpec()).isEqualTo("ТЗ два"); // текст перезаписан
        assertThat(l2.getMaxLengthMm()).isEqualTo(1200);       // габариты НЕ затёрты
        assertThat(l2.getMaxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    void manualTenderRejected() {
        tender.setSourceExtId(null);
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("импортирован");
    }

    @Test
    void tokenNotConfiguredRejected() {
        fake.configured = false;
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("GOSZAKUP_TOKEN");
    }

    @Test
    void foreignMarketLotHidden() {
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void techSpecFileNotFound_lotUntouched() {
        // фейк ничего не знает про лот → ref == null
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Техническая спецификация");
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .isEqualTo("медицинский");
    }

    @Test
    void unreadablePdf_lotUntouched() {
        fake.techSpecByKey.put("ZZTS-874-1|Пульсоксиметр", new LotTechSpecRef("http://f/2", "x.pdf", false));
        fake.filesByUrl.put("http://f/2", "не pdf".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> service.parse(lot.getId()))
                .isInstanceOf(UnprocessableException.class);
        assertThat(tenderLotRepository.findById(lot.getId()).orElseThrow().getRequiredSpec())
                .isEqualTo("медицинский");
    }
}
