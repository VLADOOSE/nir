package com.vladoose.nir.service.mail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.MailCursorRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class MailSchemaTest {

    @Autowired InboundEmailRepository inboundRepo;
    @Autowired MailCursorRepository cursorRepo;
    @Autowired EntityManager em;

    @AfterEach
    void clear() { MarketContext.clear(); }

    @Test
    void inboundEmail_keepsMailboxAndTelegramQueue() {
        MarketContext.set(Market.KZ);
        OffsetDateTime queued = OffsetDateTime.parse("2026-10-05T10:15:30Z");
        InboundEmail saved = inboundRepo.save(InboundEmail.builder()
                .fromAddress("a@x.kz").subject("S").type(InboundType.BOUNCE).status(InboundStatus.NEW)
                .mailbox("zz-schema@test.kz").imapUid(42L).messageId("<m1@x.kz>")
                .notifyStatus(NotifyStatus.PENDING).notifyText("⚠️ текст").notifySilent(true)
                .notifyQueuedAt(queued).build());
        em.flush();
        em.clear();

        InboundEmail back = inboundRepo.findById(saved.getId()).orElseThrow();
        assertThat(back.getType()).isEqualTo(InboundType.BOUNCE);
        assertThat(back.getMailbox()).isEqualTo("zz-schema@test.kz");
        assertThat(back.getImapUid()).isEqualTo(42L);
        assertThat(back.getMessageId()).isEqualTo("<m1@x.kz>");
        assertThat(back.getNotifyStatus()).isEqualTo(NotifyStatus.PENDING);
        assertThat(back.getNotifyText()).isEqualTo("⚠️ текст");
        assertThat(back.isNotifySilent()).isTrue();
        assertThat(back.getNotifyQueuedAt().toInstant()).isEqualTo(queued.toInstant());
        assertThat(back.getNotifyAttempts()).isZero();
        assertThat(back.getNotifiedAt()).isNull();
        assertThat(inboundRepo.existsByMailboxAndMessageId("zz-schema@test.kz", "<m1@x.kz>")).isTrue();
        assertThat(inboundRepo.existsByMailboxAndMessageId("other@test.kz", "<m1@x.kz>")).isFalse();
    }

    @Test
    void autoReplyType_fitsColumn() {
        MarketContext.set(Market.KZ);
        InboundEmail saved = inboundRepo.save(InboundEmail.builder()
                .fromAddress("a@x.kz").type(InboundType.AUTO_REPLY).status(InboundStatus.NEW).build());
        em.flush();
        em.clear();
        assertThat(inboundRepo.findById(saved.getId()).orElseThrow().getType()).isEqualTo(InboundType.AUTO_REPLY);
    }

    @Test
    void mailCursor_roundTrip() {
        cursorRepo.save(MailCursor.builder().mailbox("zz-cursor@test.kz").uidValidity(7L).lastUid(100L)
                .updatedAt(OffsetDateTime.now()).build());
        em.flush();
        em.clear();
        MailCursor c = cursorRepo.findById("zz-cursor@test.kz").orElseThrow();
        assertThat(c.getUidValidity()).isEqualTo(7L);
        assertThat(c.getLastUid()).isEqualTo(100L);
    }
}
