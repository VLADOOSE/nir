package com.vladoose.nir.tenderresults;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.TenderRepository;
import com.vladoose.nir.repository.TenderResultEventRepository;
import com.vladoose.nir.service.deal.DealTimelineService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.tenderresults.ResultsFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

/** «История» тендера — строки из событий итогов (спека §11): время площадки, повтор — ссылкой на тендер. */
@SpringBootTest
@Transactional
class TenderResultsTimelineTest {

    @Autowired DealTimelineService timeline;
    @Autowired TenderRepository tenders;
    @Autowired TenderResultEventRepository events;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void resultEventsInHistory() {
        Tender t = ResultsFixtures.tender(tenders, 970006001L, NOW.minusDays(5), "1-ЗЦП1", "Тележка");
        Tender next = ResultsFixtures.tender(tenders, 970006002L, NOW.plusDays(2), "1-ЗЦП2", "Тележка");
        Long lot = t.getLots().get(0).getId();
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot).kind(ResultEventKind.NO_BIDS)
                .occurredAt(LocalDateTime.of(2026, 10, 17, 10, 0)).text("никто не подал — объявят заново").urgent(true)
                .topic(ResultTopic.NONE).notifiedAt(OffsetDateTime.now()).digestedAt(OffsetDateTime.now()).build());
        events.saveAndFlush(TenderResultEvent.builder().tenderId(t.getId()).tenderLotId(lot).kind(ResultEventKind.REPEAT).refKey("970006002-1")
                .occurredAt(LocalDateTime.of(2026, 10, 20, 9, 0)).text("объявлена повторная попытка № 970006002-1 (запрос цен)")
                .urgent(false).topic(ResultTopic.NONE).refTenderId(next.getId()).notifiedAt(OffsetDateTime.now()).digestedAt(OffsetDateTime.now())
                .build());
        List<DealTimelineItem> items = timeline.forTender(t.getId(), null, 50).items().stream().filter(i -> "RESULT".equals(i.kind())).toList();
        assertThat(items).extracting(DealTimelineItem::text).containsExactly("Итоги: никто не подал — объявят заново",
                "Объявлена повторная попытка № 970006002-1 (запрос цен)");
        assertThat(items.get(0).at().toLocalDateTime()).isEqualTo(LocalDateTime.of(2026, 10, 17, 10, 0));
        assertThat(items.get(1).linkType()).isEqualTo("TENDER");
        assertThat(items.get(1).linkId()).isEqualTo(next.getId());
    }
}
