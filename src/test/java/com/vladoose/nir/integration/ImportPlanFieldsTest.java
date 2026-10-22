package com.vladoose.nir.integration;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.integration.goszakup.GoszakupTenderWriter;
import com.vladoose.nir.integration.goszakup.dto.LotDto;
import com.vladoose.nir.integration.goszakup.dto.TrdBuyDto;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Импорт goszakup пишет id лота площадки и пункт плана (спека 2026-10-22-tender-results-market-prices §12). */
@SpringBootTest
@Transactional
class ImportPlanFieldsTest {

    @Autowired GoszakupTenderWriter writer;
    @Autowired TenderRepository tenders;

    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void writerStoresExtLotIdAndPlanPoint() {
        MarketContext.set(Market.KZ);
        TrdBuyDto d = new TrdBuyDto();
        d.setNumberAnno("979000001-1");
        d.setNameRu("ZZ тележки");
        d.setRefBuyStatusId(220);
        d.setEndDate("2099-01-01 10:00:00");
        LotDto lot = new LotDto();
        lot.setId(43546011L);
        lot.setLotNumber("88214663-ЗЦП2");
        lot.setNameRu("Тележка");
        lot.setCount(new BigDecimal("3"));
        lot.setAmount(new BigDecimal("1841700"));
        lot.setPointList(List.of(88214663L));
        writer.upsertOne(d, null, List.of(lot));
        TenderLot l = tenders.findBySourceExtId("979000001-1").orElseThrow().getLots().get(0);
        assertThat(l.getExtLotId()).isEqualTo(43546011L);
        assertThat(l.getPlanPointId()).isEqualTo(88214663L);
    }
}
