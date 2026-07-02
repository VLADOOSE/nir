package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Source;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.repository.TenderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class TenderStatusServiceTest {

    @Autowired TenderRepository tenderRepository;
    @Autowired TenderStatusService service;

    @BeforeEach
    void setUp() { MarketContext.set(Market.KZ); }

    @AfterEach
    void tearDown() { MarketContext.clear(); }

    private Tender tender(String number, String status, LocalDate deadline) {
        Tender t = new Tender();
        t.setTenderNumber(number);
        t.setStatus(status);
        t.setDeadline(deadline);
        t.setSource(Source.PUBLIC_TENDER);
        return tenderRepository.save(t);
    }

    @Test
    void expiredActive_becomesCompleted_futureAndOtherStatusesUntouched() {
        Tender expired = tender("EXP-1", "ACTIVE", LocalDate.now().minusDays(1));
        Tender future = tender("FUT-1", "ACTIVE", LocalDate.now().plusDays(3));
        Tender cancelled = tender("CAN-1", "CANCELLED", LocalDate.now().minusDays(5));
        Tender noDeadline = tender("NOD-1", "ACTIVE", null);

        int changed = service.completeExpired(LocalDate.now());

        assertThat(changed).isEqualTo(1);
        assertThat(tenderRepository.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(tenderRepository.findById(future.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(tenderRepository.findById(cancelled.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(tenderRepository.findById(noDeadline.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void respectsMarketIsolation_kzRunDoesNotTouchRf() {
        MarketContext.set(Market.RF);
        Tender rfExpired = tender("RF-EXP", "ACTIVE", LocalDate.now().minusDays(2));

        MarketContext.set(Market.KZ);
        service.completeExpired(LocalDate.now());

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findById(rfExpired.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }
}
