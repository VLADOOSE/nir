package com.vladoose.nir.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.InboundController;
import com.vladoose.nir.dto.response.InboundEmailResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class InboundMailboxFilterTest {

    @Autowired InboundController controller;
    @Autowired InboundEmailRepository repository;

    @AfterEach void clear() { MarketContext.clear(); }

    private void row(Market market, String mailbox, String subject) {
        MarketContext.set(market);
        repository.save(InboundEmail.builder().mailbox(mailbox).fromAddress("a@x.kz").subject(subject)
                .receivedAt(OffsetDateTime.now().plusMinutes(5)).type(InboundType.UNMATCHED).status(InboundStatus.NEW).build());
    }

    @Test
    void filterByMailbox_caseInsensitive_mailboxesOfOwnMarket() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        String a = "zz-a-" + tag + "@test.kz", b = "zz-b-" + tag + "@test.kz", c = "zz-c-" + tag + "@test.kz";
        row(Market.KZ, a, "Письмо A");
        row(Market.KZ, b, "Письмо B");
        row(Market.RF, c, "Письмо C");
        MarketContext.set(Market.KZ);

        assertThat(controller.list(a.toUpperCase(Locale.ROOT))).extracting(InboundEmailResponse::getSubject).containsExactly("Письмо A");
        assertThat(controller.list(null)).extracting(InboundEmailResponse::getMailbox).contains(a, b).doesNotContain(c);
        assertThat(controller.mailboxes()).contains(a, b).doesNotContain(c);
    }
}
