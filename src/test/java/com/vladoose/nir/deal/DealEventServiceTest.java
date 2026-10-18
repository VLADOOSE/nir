package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.DealEvent;
import com.vladoose.nir.entity.DealEventType;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** События сделки (спека 2026-10-18 §5): заметка и звонок с автором, смена этапа, удаление вместе с заявкой. */
@SpringBootTest
@Transactional
class DealEventServiceTest {

    @Autowired DealEventService events;
    @Autowired DealEventRepository repository;
    @Autowired PrivateRequestService requests;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void noteKeepsTrimmedTextAndAuthor() {
        Tender t = requests.createEmpty();
        DealEvent e = events.note(t.getId(), DealEventType.NOTE, "  просит доставку в Каскелен \n");
        assertThat(e.getText()).isEqualTo("просит доставку в Каскелен");
        assertThat(e.getAuthor()).isEqualTo("vlad");
        assertThat(e.getType()).isEqualTo(DealEventType.NOTE);
        assertThat(e.getOccurredAt()).isNotNull();
    }

    @Test
    void noteRejectsEmptyTooLongAndStageType() {
        Tender t = requests.createEmpty();
        assertThatThrownBy(() -> events.note(t.getId(), DealEventType.CALL, "   "))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> events.note(t.getId(), DealEventType.NOTE, "я".repeat(2001)))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> events.note(t.getId(), DealEventType.STAGE, "x"))
                .isInstanceOf(BadRequestException.class);
        assertThat(events.note(t.getId(), DealEventType.NOTE, "я".repeat(2000)).getText()).hasSize(2000);
    }

    @Test
    void stageWithoutUserHasNoAuthor() {
        Tender t = requests.createEmpty();
        DealEvent e = events.stage(t.getId(), DealEventService.CLOSED, "Закрыта: купили у нас", null);
        assertThat(e.getAuthor()).isNull();
        assertThat(e.getType()).isEqualTo(DealEventType.STAGE);
        assertThat(e.getCode()).isEqualTo("CLOSED");
    }

    @Test
    void deletingRequestDeletesItsEvents() {
        Tender t = requests.createEmpty();
        Long id = events.note(t.getId(), DealEventType.NOTE, "заметка").getId();
        em.flush();
        requests.delete(t.getId(), "t");
        em.flush();
        em.clear();
        assertThat(repository.findById(id)).isEmpty();
    }
}
