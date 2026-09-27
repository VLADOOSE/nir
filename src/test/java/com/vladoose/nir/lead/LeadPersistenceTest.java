package com.vladoose.nir.lead;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.LeadRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class LeadPersistenceTest {

    @Autowired LeadRepository leadRepository;
    @Autowired EntityManager em;

    @AfterEach
    void clear() { MarketContext.clear(); }

    private static Lead lead(String externalId) {
        return Lead.builder().channel(LeadChannel.SITE).source("zz-test").externalId(externalId)
                .subject("Запрос КП").status(LeadStatus.NEW).receivedAt(OffsetDateTime.now()).build();
    }

    @Test
    void persistsItemsAndEventsAndStampsMarketFromContext() {
        MarketContext.set(Market.KZ);
        Lead l = lead("ext-" + System.nanoTime());
        l.addItem("Облучатель ОБН-150", "Азов", 2, "https://westmed.kz/product/obn-150");
        l.addItem("Рециркулятор", null, 0, null);   // количество < 1 превращается в 1
        l.addEvent(LeadEventType.RECEIVED, null, "Запрос КП — westmed.kz");
        Long id = leadRepository.saveAndFlush(l).getId();
        em.clear();

        Lead back = leadRepository.findById(id).orElseThrow();
        assertThat(back.getMarket()).isEqualTo(Market.KZ);
        assertThat(back.getItems()).extracting(LeadItem::getLineNo, LeadItem::getName, LeadItem::getQuantity)
                .containsExactly(tuple(1, "Облучатель ОБН-150", 2), tuple(2, "Рециркулятор", 1));
        assertThat(back.getEvents()).extracting(LeadEvent::getType).containsExactly(LeadEventType.RECEIVED);
        assertThat(back.getEvents().get(0).getOccurredAt()).isNotNull();
        assertThat(back.getCreatedAt()).isNotNull();
        assertThat(back.getUpdatedAt()).isNotNull();
    }

    @Test
    void sourceAndExternalIdAreUnique() {
        MarketContext.set(Market.KZ);
        String ext = "dup-" + System.nanoTime();
        leadRepository.saveAndFlush(lead(ext));
        assertThatThrownBy(() -> leadRepository.saveAndFlush(lead(ext)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void leadsWithoutExternalIdDoNotCollide() {
        MarketContext.set(Market.KZ);
        leadRepository.saveAndFlush(lead(null));
        leadRepository.saveAndFlush(lead(null));   // частичный уникальный индекс: NULL в нём не участвует
    }

    @Test
    void marketFilterHidesLeadsOfAnotherMarket() {
        MarketContext.set(Market.KZ);
        Lead kz = leadRepository.saveAndFlush(lead("kz-" + System.nanoTime()));
        MarketContext.set(Market.RF);
        assertThat(leadRepository.findAll()).extracting(Lead::getId).doesNotContain(kz.getId());
        assertThat(leadRepository.findBySourceAndExternalId("zz-test", kz.getExternalId())).isEmpty();
    }
}
