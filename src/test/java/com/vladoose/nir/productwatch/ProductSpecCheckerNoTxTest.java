package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.TenderLotProductMatchRepository;
import com.vladoose.nir.repository.TenderLotRepository;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import com.vladoose.nir.service.productwatch.ProductMatchService;
import com.vladoose.nir.service.productwatch.ProductNotifyPolicy;
import com.vladoose.nir.service.productwatch.ProductSpecChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Очередь ТЗ идёт в потоке product-watch — без транзакции и без привязанной сессии (CLAUDE.md §6). Прод 2026-10-21: порог
 * суммы читал ленивую коллекцию Tender.lots у отсоединённого тендера — «failed to lazily initialize», каждый поиск обрывался
 * до первого ТЗ. Тест без @Transactional; отметки — заглушкой (по очереди nirdb не ходим), данные — свои, убираются после.
 */
@SpringBootTest
class ProductSpecCheckerNoTxTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TechSpecStatusWriter statusWriter;
    @Autowired PlatformTransactionManager tm;

    Long tenderId;

    @AfterEach
    void cleanup() {
        MarketContext.clear();
        if (tenderId != null) new TransactionTemplate(tm).executeWithoutResult(st -> tenders.deleteById(tenderId));
    }

    @Test
    void queueOutsideTransaction_thresholdReadsLots_parsesSpec() {
        MarketContext.set(Market.KZ);
        long[] ids = new TransactionTemplate(tm).execute(st -> {
            Tender t = new Tender();
            t.setTenderNumber("ZZ-NOTX-1"); t.setSourceExtId("ZZ-NOTX-1"); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
            t.setDescription("Закуп"); t.setPlatform(TenderPlatform.GOSZAKUP);
            t.setDeadline(LocalDateTime.now().plusDays(2).toLocalDate()); t.setDeadlineAt(LocalDateTime.now().plusDays(2));
            TenderLot ours = new TenderLot(); ours.setTender(t); ours.setEquipName("Отсасыватель"); ours.setQuantity(2);
            ours.setMaxCost(new BigDecimal("600000")); ours.setTechSpecStatus(TechSpecStatus.PENDING);
            t.getLots().add(ours);
            TenderLot other = new TenderLot(); other.setTender(t); other.setEquipName("Бумага офисная"); other.setQuantity(1);
            t.getLots().add(other);
            Tender saved = tenders.save(t);
            return new long[]{saved.getId(), saved.getLots().get(0).getId()};
        });
        tenderId = ids[0];
        long lotId = ids[1];

        TenderLotProductMatchRepository matches = mock(TenderLotProductMatchRepository.class);
        when(matches.findLotsForSpecCheckUnordered(anyBoolean())).thenReturn(List.of(lotId));
        TenderLotProductMatch m = new TenderLotProductMatch();
        m.setTenderId(tenderId);
        m.setLotId(lotId);
        when(matches.findByTenderIdIn(anySet())).thenReturn(List.of(m));
        List<Long> parsed = new ArrayList<>();
        LotSpecParser parser = id -> { parsed.add(id); return new TechSpecLotParser.Result(TechSpecStatus.NO_FILE, false); };
        ProductSpecChecker checker = new ProductSpecChecker(matches, lots, statusWriter, parser, mock(ProductMatchService.class),
                () -> true, ms -> {}, ProductNotifyPolicy.of("star", "1000000"));

        int done = checker.run(10, new ImportSummary());

        assertThat(parsed).containsExactly(lotId);   // 600 000 × 2 = 1,2 млн — от порога: ТЗ качается
        assertThat(done).isEqualTo(1);
    }
}
