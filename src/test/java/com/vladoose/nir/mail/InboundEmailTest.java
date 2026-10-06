package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.InboundEmail;
import com.vladoose.nir.entity.InboundStatus;
import com.vladoose.nir.entity.InboundType;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundEmailTest {

    @Autowired InboundEmailRepository repository;

    @AfterEach
    void clearCtx() { MarketContext.clear(); }

    @Test
    void persistsAndStampsActiveMarket() {
        MarketContext.set(Market.KZ);
        InboundEmail saved = repository.save(InboundEmail.builder()
                .fromAddress("supplier@x.kz")
                .subject("Re: КП [КП-1]")
                .receivedAt(OffsetDateTime.now(ZoneOffset.UTC))
                .type(InboundType.SUPPLIER_RESPONSE)
                .status(InboundStatus.NEW)
                .build());
        repository.flush();

        InboundEmail loaded = repository.findById(saved.getId()).orElseThrow();
        assertThat(loaded.getMarket()).isEqualTo(Market.KZ);
        assertThat(loaded.getType()).isEqualTo(InboundType.SUPPLIER_RESPONSE);
        assertThat(loaded.getStatus()).isEqualTo(InboundStatus.NEW);
    }

    /**
     * «Входящие» — последние 300 писем, новые сверху: список не растёт без предела. Даты — 2099 год, новее любой
     * живой строки базы, поэтому верх выдачи — свои строки (строки без даты PostgreSQL ставит выше — сверка это
     * допускает).
     */
    @Test
    void last300_newestFirst() {
        MarketContext.set(Market.KZ);
        OffsetDateTime base = OffsetDateTime.parse("2099-01-01T00:00:00Z");
        List<InboundEmail> mine = new ArrayList<>();
        for (int i = 0; i <= 300; i++) {
            mine.add(InboundEmail.builder().fromAddress("zz300@x.kz").subject("ZZ300 " + i)
                    .receivedAt(base.plusMinutes(i)).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
        }
        repository.saveAll(mine);
        repository.flush();

        List<InboundEmail> top = repository.findTop300ByOrderByReceivedAtDesc();

        assertThat(top).hasSize(300);
        assertThat(top).extracting(InboundEmail::getReceivedAt)
                .isSortedAccordingTo(Comparator.nullsFirst(Comparator.<OffsetDateTime>reverseOrder()));
        assertThat(top).extracting(InboundEmail::getSubject).contains("ZZ300 300").doesNotContain("ZZ300 0");
    }
}
