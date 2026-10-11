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
import java.time.LocalDateTime;

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
        service.completeExpired(LocalDateTime.now());   // живая nirdb: сперва закрыть то, что уже просрочено там
        Tender expired = tender("EXP-1", "ACTIVE", LocalDate.now().minusDays(1));
        Tender future = tender("FUT-1", "ACTIVE", LocalDate.now().plusDays(3));
        Tender cancelled = tender("CAN-1", "CANCELLED", LocalDate.now().minusDays(5));
        Tender noDeadline = tender("NOD-1", "ACTIVE", null);

        int changed = service.completeExpired(LocalDateTime.now());

        assertThat(changed).isEqualTo(1);
        assertThat(tenderRepository.findById(expired.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(tenderRepository.findById(future.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(tenderRepository.findById(cancelled.getId()).orElseThrow().getStatus()).isEqualTo("CANCELLED");
        assertThat(tenderRepository.findById(noDeadline.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    /**
     * B2: площадка назвала время окончания приёма — тендер закрывается по нему, а не в полночь следующего дня.
     * Без времени (ручной тендер) весь день срока приём ещё идёт.
     */
    @Test
    void deadlineTime_closesByTime_dateOnlyWaitsForNextDay() {
        LocalDateTime now = LocalDateTime.of(2026, 10, 9, 14, 10);
        service.completeExpired(now);   // живая nirdb: сперва закрыть то, что уже просрочено там
        Tender closedAtTen = tender("TM-1", "ACTIVE", now.toLocalDate());
        closedAtTen.setDeadlineAt(now.toLocalDate().atTime(10, 25));
        Tender closesTonight = tender("TM-2", "ACTIVE", now.toLocalDate());
        closesTonight.setDeadlineAt(now.toLocalDate().atTime(18, 0));
        Tender dateOnlyToday = tender("TM-3", "ACTIVE", now.toLocalDate());
        tenderRepository.saveAllAndFlush(java.util.List.of(closedAtTen, closesTonight));

        int changed = service.completeExpired(now);

        assertThat(changed).isEqualTo(1);
        assertThat(tenderRepository.findById(closedAtTen.getId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(tenderRepository.findById(closesTonight.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
        assertThat(tenderRepository.findById(dateOnlyToday.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }

    @Test
    void respectsMarketIsolation_kzRunDoesNotTouchRf() {
        MarketContext.set(Market.RF);
        Tender rfExpired = tender("RF-EXP", "ACTIVE", LocalDate.now().minusDays(2));

        MarketContext.set(Market.KZ);
        service.completeExpired(LocalDateTime.now());

        MarketContext.set(Market.RF);
        assertThat(tenderRepository.findById(rfExpired.getId()).orElseThrow().getStatus()).isEqualTo("ACTIVE");
    }
}
