package com.vladoose.nir.market;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.MarketPriceController;
import com.vladoose.nir.dto.request.MarketHintsRequest;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.MarketLookupRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.entity.MarketLookup;
import com.vladoose.nir.tenderresults.ResultsFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Путь цен рынка (спека §13): только KZ, лот чужого рынка — 404, читают все. */
@SpringBootTest
@Transactional
@WithMockUser(roles = "USER")
class MarketPriceApiTest {

    @Autowired MarketPriceController controller;
    @Autowired TenderRepository tenders;
    @Autowired MarketLookupRepository lookups;

    @BeforeEach
    void kz() {
        MarketContext.set(Market.KZ);
        lookups.saveAndFlush(MarketLookup.builder().lookupKey("w:тележка").fetchedAt(OffsetDateTime.now()).build());
    }

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void operatorReads_rf400_otherMarketLot404() {
        Tender t = ResultsFixtures.tender(tenders, 970009001L, NOW.plusDays(3), "1-ЗЦП1", "Тележка");
        assertThat(controller.get(t.getLots().get(0).getId(), null, null, null, null)).isNotNull();
        assertThat(controller.hints(new MarketHintsRequest(List.of("Тележка")))).hasSize(1);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.get(t.getLots().get(0).getId(), null, null, null, null))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Казахстана");
        MarketContext.set(Market.KZ);
        Tender rf = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-RF-" + System.nanoTime()).status("ACTIVE").market(Market.RF).build());
        rf.getLots().add(com.vladoose.nir.entity.TenderLot.builder().tender(rf).equipName("Тележка").build());
        tenders.saveAndFlush(rf);
        assertThatThrownBy(() -> controller.get(rf.getLots().get(0).getId(), null, null, null, null)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.get(null, " ", null, null, null)).isInstanceOf(BadRequestException.class);
    }
}
