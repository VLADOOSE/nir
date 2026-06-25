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
}
