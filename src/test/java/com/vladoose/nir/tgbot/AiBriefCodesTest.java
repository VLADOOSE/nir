package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.ProductAiBrief;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Коды для блока «Для АИС» (спека 2026-10-21-tender-telegram-bot §5): [L…] у лота, [P…] у нашего кандидата. */
@SpringBootTest
@Transactional
class AiBriefCodesTest {

    @Autowired TenderRepository tenders;
    @Autowired SiteProductRepository products;
    @Autowired ProductGroupRepository groups;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductAiBrief brief;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void lotAndCandidateCodes_andBlockRequest() {
        MarketContext.set(Market.KZ);
        TenderLot lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        SiteProduct dki = TgFixtures.product(products, "ZZ Дефибриллятор ДКИ-Н-12", "ZZАксион");
        TgFixtures.match(matches, lot, TgFixtures.group(groups).getId(), ProductMatchLevel.TYPE, null, List.of(dki.getId()));

        String text = brief.forTenders(List.of(lot.getTender().getId())).text();

        assertThat(text).contains("[L" + lot.getId() + "]: ZZ Дефибриллятор");
        assertThat(text).contains("* [P" + dki.getId() + "] ZZ Дефибриллятор ДКИ-Н-12");
        assertThat(text).contains("Для АИС").contains("| идти / не наше / не ясно |");
    }
}
