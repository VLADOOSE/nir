package com.vladoose.nir.chat;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.response.ChatAttachmentMeta;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ChatAttachmentRepository;
import com.vladoose.nir.repository.ChatMessageRepository;
import com.vladoose.nir.repository.ChatRepository;
import com.vladoose.nir.repository.LeadRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Transactional
class ChatPersistenceTest {

    static final String ACCOUNT = "77000000001";

    @Autowired ChatRepository chatRepository;
    @Autowired ChatMessageRepository messageRepository;
    @Autowired ChatAttachmentRepository attachmentRepository;
    @Autowired LeadRepository leadRepository;
    @Autowired EntityManager em;

    @AfterEach void clear() { MarketContext.clear(); }

    static String chatId() {
        return "7701" + ThreadLocalRandom.current().nextInt(1_000_000, 9_999_999) + "@c.us";
    }

    private Chat chat(String ext) {
        return chatRepository.saveAndFlush(Chat.builder().channel(LeadChannel.WHATSAPP).account(ACCOUNT)
                .externalChatId(ext).title("Айгерим").build());
    }

    private ChatMessage message(Chat c, String ext, OffsetDateTime at) {
        return messageRepository.saveAndFlush(ChatMessage.builder().chat(c).externalId(ext)
                .direction(LeadDirection.IN).type(ChatMessageType.TEXT).body("Здравствуйте").sentAt(at).build());
    }

    @Test
    void chatIsStampedWithMarketAndInvisibleFromOtherMarket() {
        MarketContext.set(Market.KZ);
        String ext = chatId();
        chat(ext);
        em.clear();

        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, ACCOUNT, ext))
                .get().satisfies(c -> {
                    assertThat(c.getMarket()).isEqualTo(Market.KZ);
                    assertThat(c.getCreatedAt()).isNotNull();
                    assertThat(c.isGroup()).isFalse();
                    assertThat(c.isNotClient()).isFalse();
                });
        MarketContext.set(Market.RF);
        assertThat(chatRepository.findByChannelAndAccountAndExternalChatId(LeadChannel.WHATSAPP, ACCOUNT, ext)).isEmpty();
    }

    @Test
    void messageIdIsUniqueWithinChat() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        message(c, "MSG-1", OffsetDateTime.now());

        assertThatThrownBy(() -> message(c, "MSG-1", OffsetDateTime.now()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void pagesGoNewestFirstAndKeysetContinues() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        OffsetDateTime t = OffsetDateTime.now().minusHours(1);
        ChatMessage m1 = message(c, "A", t);
        ChatMessage m2 = message(c, "B", t.plusMinutes(1));
        ChatMessage m3 = message(c, "C", t.plusMinutes(2));

        assertThat(messageRepository.findLatest(c.getId(), PageRequest.of(0, 2)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId(), m2.getId());
        assertThat(messageRepository.findBefore(c.getId(), m2.getSentAt(), m2.getId(), PageRequest.of(0, 2)))
                .extracting(ChatMessage::getId).containsExactly(m1.getId());
        assertThat(messageRepository.findSince(c.getId(), m2.getSentAt(), PageRequest.of(0, 10)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId(), m2.getId());
        assertThat(messageRepository.findEarliestAfter(c.getId(), m1.getSentAt(), PageRequest.of(0, 1)))
                .extracting(ChatMessage::getId).containsExactly(m2.getId());
        assertThat(messageRepository.findLatestByDirection(c.getId(), LeadDirection.IN, PageRequest.of(0, 1)))
                .extracting(ChatMessage::getId).containsExactly(m3.getId());
    }

    @Test
    void chatIdsAreFoundByMessageText() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        messageRepository.saveAndFlush(ChatMessage.builder().chat(c).externalId("T1").direction(LeadDirection.IN)
                .type(ChatMessageType.TEXT).body("Нужен Облучатель-" + c.getExternalChatId()).sentAt(OffsetDateTime.now()).build());

        assertThat(messageRepository.findChatIdsByBody("облучатель-" + c.getExternalChatId())).containsExactly(c.getId());
    }

    @Test
    void attachmentMetaIsReadWithoutContent() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        ChatMessage stored = message(c, "F1", OffsetDateTime.now());
        ChatMessage skipped = message(c, "F2", OffsetDateTime.now());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(stored).fileName("ТЗ.pdf")
                .mimeType("application/pdf").sizeBytes(3L).content(new byte[]{1, 2, 3}).build());
        attachmentRepository.saveAndFlush(ChatAttachment.builder().message(skipped).fileName("видео.mp4")
                .mimeType("video/mp4").notStoredReason(AttachmentNotStoredReason.TOO_LARGE).build());

        List<ChatAttachmentMeta> meta = attachmentRepository.findMetaByMessageIds(List.of(stored.getId(), skipped.getId()));

        assertThat(meta).extracting(ChatAttachmentMeta::messageId, ChatAttachmentMeta::fileName, ChatAttachmentMeta::stored)
                .containsExactlyInAnyOrder(tuple(stored.getId(), "ТЗ.pdf", true), tuple(skipped.getId(), "видео.mp4", false));
    }

    @Test
    void leadKeepsItsChat() {
        MarketContext.set(Market.KZ);
        Chat c = chat(chatId());
        Lead l = leadRepository.saveAndFlush(Lead.builder().channel(LeadChannel.WHATSAPP).source("whatsapp")
                .subject("WhatsApp").status(LeadStatus.NEW).receivedAt(OffsetDateTime.now()).chat(c).build());
        em.clear();

        assertThat(leadRepository.findById(l.getId()).orElseThrow().getChat().getId()).isEqualTo(c.getId());
        assertThat(leadRepository.findByChatIdIn(List.of(c.getId()))).extracting(Lead::getId).containsExactly(l.getId());
        assertThat(leadRepository.findByChatIdAndStatusIn(c.getId(), List.of(LeadStatus.NEW))).hasSize(1);
        assertThat(leadRepository.findByChatIdAndStatusIn(c.getId(), List.of(LeadStatus.CLOSED))).isEmpty();
    }
}
