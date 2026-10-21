package com.vladoose.nir.tgbot;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PriceRequestWriter;
import com.vladoose.nir.service.deal.DealTimelineService;
import com.vladoose.nir.service.lotmodel.LotModelService;
import com.vladoose.nir.util.CurrentUser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Кто и откуда (спека 2026-10-21-tender-telegram-bot §8): «vlad, Telegram» в модели, событии и запросе цен. */
@SpringBootTest
@Transactional
class ActionAuthorTest {

    @Autowired TenderRepository tenders;
    @Autowired MedEquipmentRepository equipment;
    @Autowired DistributorRepository distributors;
    @Autowired LotModelService models;
    @Autowired PriceRequestWriter writer;
    @Autowired DealTimelineService timeline;
    @Autowired JdbcTemplate jdbc;
    TenderLot lot;
    MedEquipment eq;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        lot = TgFixtures.lot(tenders, "ZZ Дефибриллятор", "ZZ Горбольница №7");
        eq = equipment.saveAndFlush(MedEquipment.builder().name("ZZ ДКИ-Н-12").manufact("ZZАксион").market(Market.KZ).build());
    }

    @AfterEach
    void clear() { CurrentUser.setVia(null); MarketContext.clear(); }

    @Test
    void labelAddsVia() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken("vlad", null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))));
        try {
            assertThat(CurrentUser.label()).isEqualTo("vlad");
            CurrentUser.setVia("Telegram");
            assertThat(CurrentUser.label()).isEqualTo("vlad, Telegram");
        } finally {
            SecurityContextHolder.clearContext();
        }
        assertThat(CurrentUser.label()).isNull();   // система — без автора
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void aiPinMarksLotAndEvent_manualConfirmClearsMark() {
        CurrentUser.setVia("Telegram");
        models.applyByAi(lot, eq, "ИИ: подходит");
        CurrentUser.setVia(null);
        assertThat(lot.isModelByAi()).isTrue();
        assertThat(lot.getModelSetBy()).isEqualTo("vlad, Telegram");
        Map<String, Object> ev = jdbc.queryForMap("select text, author from deal_event where tender_id = ? order by id desc limit 1",
                lot.getTender().getId());
        assertThat((String) ev.get("text")).endsWith("— по ответу ИИ");
        assertThat(ev.get("author")).isEqualTo("vlad, Telegram");

        models.apply(lot, eq, null);   // человек подтвердил ту же модель — она теперь ручная
        assertThat(lot.isModelByAi()).isFalse();
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void priceRequestRemembersAuthor_andHistoryShowsIt() {
        Distributor d = TgFixtures.distributor(distributors, "ZZ Завод", "zz@example.kz", true, false, "ZZАксион");
        PriceRequest pr = writer.persist(lot.getTender(), d, List.of(new PriceRequestWriter.Line(lot, eq, 1)));
        assertThat(pr.getCreatedBy()).isEqualTo("vlad");
        List<DealTimelineItem> items = timeline.forTender(lot.getTender().getId(), null, 50).items();
        assertThat(items).filteredOn(i -> "RFQ_SENT".equals(i.kind())).singleElement()
                .satisfies(i -> assertThat(i.author()).isEqualTo("vlad"));
    }
}
