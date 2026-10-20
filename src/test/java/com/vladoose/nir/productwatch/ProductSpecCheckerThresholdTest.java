package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.integration.goszakup.ImportSummary;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.LotSpecParser;
import com.vladoose.nir.service.TechSpecLotParser;
import com.vladoose.nir.service.TechSpecStatusWriter;
import com.vladoose.nir.service.productwatch.*;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** ТЗ качаем только у тендеров, где наши лоты от порога (спека §12): дешёвые и с неизвестной суммой — нет. */
@SpringBootTest
@Transactional
class ProductSpecCheckerThresholdTest {

    @Autowired TenderRepository tenders;
    @Autowired TenderLotRepository lots;
    @Autowired TenderLotProductMatchRepository matches;
    @Autowired ProductGroupRepository groupRepo;
    @Autowired SiteProductRepository productRepo;
    @Autowired TechSpecStatusWriter statusWriter;
    @Autowired EntityManager em;

    final List<Long> parsed = new ArrayList<>();
    ProductMatchService matchService;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        groupRepo.deleteAllInBatch();
        productRepo.deleteAllInBatch();
        em.flush();
        List<ProductGroup> groups = ProductFixtures.groups().stream().peek(g -> g.setId(null)).map(groupRepo::save).toList();
        CatalogSnapshot snap = CatalogSnapshot.build(groups, List.of());
        matchService = new ProductMatchService(tenders, matches, () -> snap);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** Тендер: наш лот «Отсасыватель» (цена за единицу × количество) + чужой «Бумага офисная»; приём — через `days` дней. */
    long lot(String ext, Integer qty, String unitPrice, BigDecimal total, int days) {
        Tender t = new Tender();
        t.setTenderNumber(ext); t.setSourceExtId(ext); t.setStatus("ACTIVE"); t.setMarket(Market.KZ);
        t.setDescription("Закуп"); t.setPlatform(TenderPlatform.GOSZAKUP); t.setTotalCost(total);
        t.setDeadline(LocalDateTime.now().plusDays(days).toLocalDate()); t.setDeadlineAt(LocalDateTime.now().plusDays(days));
        TenderLot l = new TenderLot(); l.setTender(t); l.setEquipName("Отсасыватель"); l.setQuantity(qty);
        if (unitPrice != null) l.setMaxCost(new BigDecimal(unitPrice));
        l.setTechSpecStatus(TechSpecStatus.PENDING);
        t.getLots().add(l);
        TenderLot other = new TenderLot(); other.setTender(t); other.setEquipName("Бумага офисная"); other.setQuantity(1);
        t.getLots().add(other);
        tenders.save(t);
        em.flush();
        matchService.evaluate(t.getId(), ProductMatchSource.SEARCH);
        em.flush();
        return t.getLots().get(0).getId();
    }

    ProductSpecChecker checker(String minSum) {
        LotSpecParser parser = lotId -> { parsed.add(lotId); return new TechSpecLotParser.Result(TechSpecStatus.NO_FILE, false); };
        return new ProductSpecChecker(matches, lots, statusWriter, parser, matchService, () -> true, ms -> {},
                ProductNotifyPolicy.of("star", minSum));
    }

    @Test
    void onlyFromThreshold() {
        long cheap = lot("TH-1", 1, "300000", null, 2);
        long rich = lot("TH-2", 2, "900000", null, 2);
        long unknown = lot("TH-3", null, null, new BigDecimal("5000000"), 2);   // лотов два, цены нет — сумма не доказана
        checker("1000000").run(10, new ImportSummary());
        assertThat(parsed).containsExactly(rich).doesNotContain(cheap, unknown);
    }

    /** Фильтр — до предела пачки: дешёвый лот с ближайшим сроком не занимает место дорогого. */
    @Test
    void limitCountsOnlyPassing() {
        lot("TH-6", 1, "300000", null, 1);
        long rich = lot("TH-7", 2, "900000", null, 3);
        checker("1000000").run(1, new ImportSummary());
        assertThat(parsed).containsExactly(rich);
    }

    @Test
    void zeroThreshold_all() {
        lot("TH-4", 1, "300000", null, 2);
        lot("TH-5", 2, "900000", null, 2);
        checker("0").run(10, new ImportSummary());
        assertThat(parsed).hasSize(2);
    }
}
