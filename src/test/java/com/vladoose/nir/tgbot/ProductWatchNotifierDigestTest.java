package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.integration.telegram.TelegramSettings;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Режим кнопок бота у «Наш товар» (спека 2026-10-21-tender-telegram-bot §4): пачка вместо прежнего сообщения, «не наше» —
 * вне сообщения и молча сообщено, не ушло — не отмечено. Удаление групп в откатываемой транзакции убирает каскадом чужие
 * отметки nirdb (как ProductWatchNotifierTest).
 */
@SpringBootTest
@Transactional
class ProductWatchNotifierDigestTest {
    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired ProductMatchQuery query;
    @Autowired ProductMatchService matchService;
    @Autowired PlatformTransactionManager tm;
    @Autowired EntityManager em;
    final List<ProductDigestComposer.Chunk> published = new ArrayList<>();
    boolean failPublish;
    static final TelegramSettings TG = new TelegramSettings(true, "http://127.0.0.1:1", "t:k", "-1", "52", "55", "95");

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    ProductWatchNotifier notifier() {
        return new ProductWatchNotifier(matches, tenders, query, matchService, tm, TG, (text, silent) -> { },
                true, ProductNotifyPolicy.of("all", "0"), "https://ais.westmed.kz", Clock.systemUTC(),
                chunk -> { if (failPublish) return false; published.add(chunk); return true; });
    }

    TenderLot marked(String name) {
        TenderLot lot = TgFixtures.lot(tenders, name, "ZZ ГБ");
        TgFixtures.match(matches, lot, TgFixtures.group(groupRepo).getId(), ProductMatchLevel.TYPE, null, List.of());
        return lot;
    }

    @Test
    void digestMode_publishesChunk_marksNotified() {
        TenderLot lot = marked("ZZ Облучатель");
        ImportSummary sum = new ImportSummary();
        notifier().afterRun(sum);
        assertThat(published).singleElement().satisfies(c -> assertThat(c.tenderIds()).containsExactly(lot.getTender().getId()));
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void allLotsNotOurs_noChunk_markedSilently() {
        TenderLot lot = marked("ZZ Халат");
        lot.setAiVerdict(AiVerdict.NO);
        lots.saveAndFlush(lot);
        notifier().afterRun(new ImportSummary());
        assertThat(published).isEmpty();
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }

    /** «Аналог» — решение уже принято, как «не наше»: в пачку не идёт, тендер отмечается сообщённым молча. */
    @Test
    void analogLots_notInDigest_likeNotOurs() {
        TenderLot lot = marked("ZZ Деструктор-аспиратор");
        lot.setAiVerdict(AiVerdict.ANALOG);
        lots.saveAndFlush(lot);
        notifier().afterRun(new ImportSummary());
        assertThat(published).isEmpty();
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isEqualTo(ProductMatchLevel.TYPE);
    }

    @Test
    void publishFails_notMarked_sideError() {
        TenderLot lot = marked("ZZ Аспиратор");
        failPublish = true;
        ImportSummary sum = new ImportSummary();
        notifier().afterRun(sum);
        assertThat(sum.getErrors()).isPositive();
        em.flush(); em.clear();
        assertThat(tenders.findById(lot.getTender().getId()).orElseThrow().getProductNotifiedLevel()).isNull();
    }
}
