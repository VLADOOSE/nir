package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** V41 (спека 2026-10-21-tender-telegram-bot §9): колонки, JSONB пачки и черновика, перевод статуса — один раз. */
@SpringBootTest
@Transactional
class TgBotSchemaTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired ProductDigestRepository digests;
    @Autowired BotLetterRepository letters;
    @Autowired TenderRepository tenders;
    @Autowired DistributorRepository distributors;
    @Autowired EntityManager em;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void v41Columns() {
        assertThat(jdbc.queryForList("select column_name from information_schema.columns where table_name = 'tender_lot' "
                + "and (column_name like 'ai\\_%' or column_name = 'model_by_ai')", String.class))
                .containsExactlyInAnyOrder("ai_verdict", "ai_product_id", "ai_note", "ai_by", "ai_at", "model_by_ai");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'user_account' "
                + "and column_name = 'telegram_user_id'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.columns where table_name = 'price_request' "
                + "and column_name = 'created_by'", Integer.class)).isEqualTo(1);
    }

    @Test
    void digestKeepsTenderIdsAsLongs() {
        ProductDigest d = digests.saveAndFlush(ProductDigest.builder().tenderIds(new ArrayList<>(List.of(5L, 7L))).build());
        em.clear();
        assertThat(digests.findById(d.getId()).orElseThrow().getTenderIds()).containsExactly(5L, 7L);
    }

    @Test
    void letterItemsRoundTrip_andTransitionOnlyOnce() {
        MarketContext.set(Market.KZ);
        Tender t = tenders.saveAndFlush(Tender.builder().tenderNumber("ZZ-БОТ-" + System.nanoTime()).status("ACTIVE")
                .source(Source.PUBLIC_TENDER).market(Market.KZ).currency("KZT").build());
        Distributor d = distributors.saveAndFlush(Distributor.builder().name("ZZ Завод " + System.nanoTime()).market(Market.KZ).build());
        BotLetter b = letters.saveAndFlush(BotLetter.builder().tenderId(t.getId()).distributorId(d.getId()).lineNo(1)
                .items(new ArrayList<>(List.of(new BotLetterItem(11L, 22L, 3)))).build());
        em.clear();
        assertThat(letters.findById(b.getId()).orElseThrow().getItems()).containsExactly(new BotLetterItem(11L, 22L, 3));
        List<BotLetterStatus> from = List.of(BotLetterStatus.DRAFT, BotLetterStatus.FAILED);
        assertThat(letters.transition(b.getId(), from, BotLetterStatus.SENDING)).isEqualTo(1);
        assertThat(letters.transition(b.getId(), from, BotLetterStatus.SENDING)).isZero();   // второе нажатие — мимо
    }
}
