package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderController;
import com.vladoose.nir.controller.TenderResultsController;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.GoszakupSubjectRepository;
import com.vladoose.nir.repository.TenderLotResultRepository;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Пути итогов (спека 2026-10-22-tender-results-market-prices §13): читают все, проверку запускает админ; чужой рынок — 404. */
@SpringBootTest
@Transactional
class TenderResultsApiTest {

    @Autowired TenderResultsController controller;
    @Autowired TenderController tenderController;
    @Autowired TenderRepository tenders;
    @Autowired TenderLotResultRepository results;
    @Autowired GoszakupSubjectRepository subjects;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Tender withResult(long buyId) {
        Tender t = ResultsFixtures.tender(tenders, buyId, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        results.saveAndFlush(TenderLotResult.builder().tenderLotId(t.getLots().get(0).getId()).tenderId(t.getId())
                .outcome(LotOutcome.DONE).winnerName("ИП X").winnerPrice(new BigDecimal("1000")).build());
        return t;
    }

    @Test
    @WithMockUser(roles = "USER")
    void operatorReads_cannotCheck() {
        Tender t = withResult(970005001L);
        subjects.saveAndFlush(GoszakupSubject.builder().bin("110240019426").nameRu("ГКП «ZZ больница»").phone("31398")
                .fetchedAt(OffsetDateTime.now()).build());
        assertThat(controller.lotResult(t.getLots().get(0).getId()).outcome()).isEqualTo("DONE");
        assertThat(controller.customer(t.getId()).phone()).isEqualTo("31398");
        assertThat(tenderController.getLots(t.getId()).get(0).getResult().text()).startsWith("✓ Состоялся · победитель ИП X");
        assertThat(tenderController.findById(t.getId()).getResult().chip()).startsWith("итог: ИП X");
        assertThatThrownBy(() -> controller.check(t.getId())).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.status()).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarket_404_rf_400() {
        Tender t = withResult(970005002L);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.lotResult(t.getLots().get(0).getId())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.customer(t.getId())).isInstanceOf(NotFoundException.class);
        Tender rf = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-RF-" + System.nanoTime()).status("ACTIVE").market(Market.RF).build());
        assertThatThrownBy(() -> controller.customer(rf.getId())).isInstanceOf(BadRequestException.class);
    }

    /** Ручной тендер или срок не прошёл — 400 сразу, без очереди. */
    @Test
    @WithMockUser(roles = "ADMIN")
    void check_notEligible_400() {
        Tender future = ResultsFixtures.tender(tenders, 970005003L, NOW.plusYears(1), "1-ЗЦП1", "Ещё идёт");
        assertThatThrownBy(() -> controller.check(future.getId())).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("итогов пока нет");
    }
}
