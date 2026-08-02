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

    /**
     * Слабый матч + ТЗ не пытались брать → подсказка «разберите ТЗ».
     *
     * <p>Ассерт БЕЗУСЛОВНЫЙ намеренно. Замер 2026-08-02 на живом реестре: топ-кандидат
     * («Транспортный бокс с активным охлаждением») даёт <b>0.2629</b> — ниже
     * {@code SHORTLIST_MIN} (0.30) на 0.037, то есть кейс лежит в зоне CANNOT ПО ЗАМЫСЛУ:
     * кандидаты есть, но это мусор (транспортный бокс, наборы для микробиологических
     * исследований), а верный ламинарный бокс BioGuard только пятый с 0.2292.
     *
     * <p>⚠️ Калибровка порогов — Task 5. Опустите {@code SHORTLIST_MIN} до 0.25 — кейс
     * уедет в SHORTLIST и этот тест УПАДЁТ. Так и задумано: падение заставит принять
     * решение осознанно. Прежняя формулировка через {@code if (confidence == CANNOT)}
     * вместо падения молча переставала проверять что-либо, унося с собой единственное
     * покрытие {@code NEED_TECH_SPEC}.
     */
    @Test
    void weakMatchWithoutTechSpecAsksForIt() {
        TenderLot l = lot("Бокс микробиологической безопасности", null, null);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getCandidates()).isNotEmpty();   // кандидаты есть — отличие от NO_CANDIDATES
        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.NEED_TECH_SPEC);
    }

    /**
     * Тот же слабый матч, но ТЗ уже пытались взять и не смогли → причина ДРУГАЯ:
     * не «разберите ТЗ» (разбирать нечего), а «ТЗ взять не удалось».
     *
     * <p>Отличие от {@link #noCandidatesGivesCannot()} — именно в кандидатах: здесь они
     * ЕСТЬ, но слабые (топ 0.2629 < SHORTLIST_MIN), поэтому {@code cannotReasonOf} доходит
     * до чтения {@code lot.getTechSpecStatus()}. На ненайденном лоте (0 кандидатов) ветка
     * {@code candidates.isEmpty()} возвращает NO_CANDIDATES раньше, статус ТЗ не читается
     * вовсе — и тест был бы дубликатом соседнего.
     *
     * <p>Это единственное покрытие {@code TECH_SPEC_FAILED}. Значение поднимется в проде,
     * когда Task 7 начнёт писать {@code TechSpecStatus}: сейчас его в {@code src/main}
     * не пишет никто, так что ошибку в наборе статусов ловит только этот тест.
     */
    @Test
    void weakMatchAfterFailedTechSpecSaysSo() {
        TenderLot l = lot("Бокс микробиологической безопасности", null, TechSpecStatus.ERROR);

        LotRegistryMatchResponse r = service.matchForLotUi(l.getId(), 5);

        assertThat(r.getCandidates()).isNotEmpty();
        assertThat(r.getConfidence()).isEqualTo(MatchConfidence.CANNOT);
        assertThat(r.getCannotReason()).isEqualTo(CannotReason.TECH_SPEC_FAILED);
    }
}
