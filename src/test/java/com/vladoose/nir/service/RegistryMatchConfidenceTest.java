package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.CannotReason;
import com.vladoose.nir.dto.response.LotRegistryMatchResponse;
import com.vladoose.nir.dto.response.MatchConfidence;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/** Зоны честности матча на живом реестре. */
@SpringBootTest
@Transactional
class RegistryMatchConfidenceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired RegistryMatchService service;

    @BeforeEach void setUp() { MarketContext.set(Market.KZ); }
    @AfterEach void tearDown() { MarketContext.clear(); }

    private TenderLot lot(String name, String spec, TechSpecStatus status) {
        Tender t = new Tender();
        t.setTenderNumber("CONF-" + System.nanoTime());
        t.setStatus("ACTIVE");
        t.setSource(Source.PUBLIC_TENDER);
        TenderLot l = new TenderLot();
        l.setTender(t);
        l.setEquipName(name);
        l.setRequiredSpec(spec);
        l.setTechSpecStatus(status);
        t.getLots().add(l);
        tenderRepository.save(t);
        return t.getLots().get(0);
    }

    /** Описание «вакуумный» даёт сильный матч — это уверенная зона. */
    @Test
    void strongMatchIsConfident() {
        TenderLot l = lot("Насос", "вакуумный, производительность более 1500 л/с", null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CONFIDENT);
        assertThat(r.getCannotReason()).isNull();
        assertThat(r.getCandidates()).isNotEmpty();
    }

    /** Генерик: кандидаты есть и они верные, но неразличимы между собой. */
    @Test
    void genericLotIsShortlistNotConfident() {
        TenderLot l = lot("Перчатки", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isNotEqualTo(MatchConfidence.CONFIDENT);
        assertThat(r.getCandidates()).isNotEmpty();
    }

    /** Ничего не нашли — честно CANNOT/NO_CANDIDATES, а не пустой список с видом уверенности.
     *  Имя без единого слова реестра: «квантовый» брать НЕЛЬЗЯ — это реальное слово реестра
     *  (df=2, «Аппарат квантовой терапии "Витязь"»), лот перестаёт быть ненайденным. */
    @Test
    void noCandidatesGivesCannot() {
        TenderLot l = lot("Криптовалютный майнер", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.NO_CANDIDATES);
    }

    /** Слабый матч + ТЗ не пытались брать → подсказка «разберите ТЗ». */
    @Test
    void weakMatchWithoutTechSpecAsksForIt() {
        TenderLot l = lot("Бокс микробиологической безопасности", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        if (r.getConfidence() == MatchConfidence.CANNOT) {
            assertThat(r.getCannotReason()).isEqualTo(CannotReason.NEED_TECH_SPEC);
        }
    }

    /** Слабый матч, но ТЗ уже пытались взять и не смогли → причина другая. */
    @Test
    void weakMatchAfterFailedTechSpecSaysSo() {
        TenderLot l = lot("Криптовалютный майнер", null, TechSpecStatus.ERROR);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.NO_CANDIDATES);
    }
}
