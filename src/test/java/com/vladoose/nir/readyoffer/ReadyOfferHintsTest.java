package com.vladoose.nir.readyoffer;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.OfferFileCandidate;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.ReadyOfferService;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import com.vladoose.nir.service.readyoffer.ReadyOfferFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

/** Подсказка «КП ушло клиенту» и окно «Готовое КП» (спека 2026-10-19 §3.2, §3.4; Review Focus 2). */
@SpringBootTest
@Transactional
class ReadyOfferHintsTest {

    @Autowired ReadyOfferService service;
    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService clientOffers;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired ChatAttachmentRepository attachments;
    @Autowired LeadRepository leads;
    @Autowired EntityManager em;

    static final OffsetDateTime LEAD_AT = OffsetDateTime.parse("2026-10-14T09:00:00Z");

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    /** Заявка с позицией из обращения с почтовой перепиской. */
    private Tender deal(Chat c) {
        Tender t = requests.createEmpty();
        requests.addLines(t.getId(), List.of(input("Кушетка", null, 1, null, null, null)), LinesMode.APPEND);
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.EMAIL).source("email").externalId("zz" + System.nanoTime())
                .subject("Запрос КП").contactName("Клиент").status(LeadStatus.CONVERTED).chat(c).privateRequest(t)
                .receivedAt(LEAD_AT).build());
        return t;
    }

    private Chat chat(LeadChannel channel) {
        return chats.save(Chat.builder().market(Market.KZ).channel(channel).account("info@westmed.kz")
                .externalChatId("zz" + System.nanoTime() + "@clinic.kz").title("Клиника").build());
    }

    private ChatAttachment file(Chat c, LeadDirection dir, String at, String subject, String body, String name) throws Exception {
        ChatMessage m = messages.save(ChatMessage.builder().chat(c).externalId("zz" + System.nanoTime()).direction(dir)
                .type(ChatMessageType.EMAIL).subject(subject).body(body).sentAt(OffsetDateTime.parse(at)).build());
        byte[] content = ReadyOfferFixtures.fatherKp("24.09.2026", List.<String[]>of(new String[]{"Кушетка", "1", "100", "100"}), "100");
        return attachments.save(ChatAttachment.builder().message(m).fileName(name).mimeType("application/octet-stream")
                .sizeBytes((long) content.length).content(content).build());
    }

    private DealProgress.Next next(Tender t) {
        em.flush();
        return views.build(requests.findById(t.getId())).getProgress().next();
    }

    @Test
    void ourOfferLetter_hintToAttach_goneAfterAttach() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ChatAttachment a = file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "Коммерческое предложение West-Med", "Во вложении.", "Предложение.docx");

        DealProgress.Next n = next(t);
        assertThat(n.action()).isEqualTo("ATTACH_OFFER_FILE");
        assertThat(n.hintId()).isEqualTo(a.getId());
        assertThat(n.text()).isEqualTo("Похоже, КП ушло клиенту 15.10 письмом — «Предложение.docx»");

        service.attach(t.getId(), null, a.getId(), ClientOfferStatus.SENT, LocalDate.of(2026, 10, 15), "vlad");
        assertThat(next(t).action()).isEqualTo("OFFER_DECISION");
    }

    @Test
    void noHintForIncomingFilesWithoutWordsOrPictures_latinKpInNameHints() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        file(c, LeadDirection.IN, "2026-10-15T07:00:00Z", "КП", "Ваше КП", "КП клиента.docx");
        file(c, LeadDirection.OUT, "2026-10-15T07:10:00Z", "Счёт", "Счёт во вложении", "Счёт.pdf");
        file(c, LeadDirection.OUT, "2026-10-15T07:20:00Z", "КП", "Фото", "photo.jpg");
        assertThat(next(t).action()).isEqualTo("REQUEST_PRICES");

        ChatAttachment kp = file(c, LeadDirection.OUT, "2026-10-15T08:00:00Z", null, null, "KP-235143.pdf");
        assertThat(next(t).hintId()).isEqualTo(kp.getId());
    }

    @Test
    void dismissedFileNoHint_olderFileHintedNext() throws Exception {
        Chat c = chat(LeadChannel.WHATSAPP);
        Tender t = deal(c);
        ChatAttachment older = file(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", null, "Высылаем КП", "kp v1.pdf");
        ChatAttachment newer = file(c, LeadDirection.OUT, "2026-10-15T08:00:00Z", null, "Высылаем КП", "kp v2.pdf");
        assertThat(next(t).hintId()).isEqualTo(newer.getId());
        assertThat(next(t).text()).contains("в WhatsApp");

        service.dismiss(t.getId(), newer.getId());
        em.clear();
        assertThat(next(t).hintId()).isEqualTo(older.getId());
    }

    @Test
    void aisDraftPdfInLetter_markSentWithLetterTime() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ClientOffer built = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        String name = clientOffers.fileBaseName(built) + ".pdf";
        ChatAttachment a = file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "Re: Запрос", "Добрый день", name);

        DealProgress.Next n = next(t);
        assertThat(n.action()).isEqualTo("MARK_OFFER_SENT");
        assertThat(n.refId()).isEqualTo(built.getId());
        assertThat(n.text()).isEqualTo("КП № " + built.getNumber() + " ушло клиенту 15.10 письмом — отметить отправленным?");

        ClientOffer sent = service.markSent(t.getId(), a.getId());
        assertThat(sent.getStatus()).isEqualTo(ClientOfferStatus.SENT);
        assertThat(sent.getSentAt()).isAtSameInstantAs(OffsetDateTime.parse("2026-10-15T07:30:00Z"));
        assertThat(next(t).action()).isEqualTo("OFFER_DECISION");
    }

    @Test
    void candidatesNewestFirstWithFlagsAndMarketDate() throws Exception {   // Review Focus 2
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ChatAttachment plain = file(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", "Паспорт", "Паспорт изделия", "Паспорт.pdf");
        ChatAttachment late = file(c, LeadDirection.OUT, "2026-10-15T20:30:00Z", "КП", "Высылаем КП", "КП.docx");
        file(c, LeadDirection.IN, "2026-10-15T21:00:00Z", "КП", "Клиент прислал", "Смета.xlsx");

        List<OfferFileCandidate> list = service.candidates(t.getId());
        assertThat(list).extracting(OfferFileCandidate::attachmentId).containsExactly(late.getId(), plain.getId());
        assertThat(list.get(0).looksLikeOffer()).isTrue();
        assertThat(list.get(0).sentOn()).isEqualTo(LocalDate.of(2026, 10, 16));   // 20:30 UTC — уже 16.10 в Уральске
        assertThat(list.get(1).looksLikeOffer()).isFalse();

        ClientOffer o = service.attach(t.getId(), null, late.getId(), ClientOfferStatus.SENT, LocalDate.of(2026, 10, 16), "vlad");
        assertThat(service.candidates(t.getId()).get(0).usedByOfferId()).isEqualTo(o.getId());
    }

    @Test
    void sentOfferNoHint_rejectedOfferFileNotHintedAgain() throws Exception {
        // КП уже отправлено — свежий файл «КП» в переписке не подсказывается (спека §3.4: только пока КП не ушло)
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        ClientOffer built = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        clientOffers.setStatus(built.getId(), ClientOfferStatus.SENT);
        file(c, LeadDirection.OUT, "2026-10-15T07:00:00Z", "КП", "Высылаем КП", "КП.pdf");
        assertThat(next(t).action()).isEqualTo("OFFER_DECISION");

        // занесённое КП клиент отклонил — тот же файл второй раз не предлагается
        Chat c2 = chat(LeadChannel.EMAIL);
        Tender t2 = deal(c2);
        ChatAttachment a = file(c2, LeadDirection.OUT, "2026-10-15T07:30:00Z", "КП", "Высылаем КП", "КП.docx");
        ClientOffer o = service.attach(t2.getId(), null, a.getId(), ClientOfferStatus.SENT, LocalDate.of(2026, 10, 15), "vlad");
        clientOffers.setStatus(o.getId(), ClientOfferStatus.REJECTED);
        DealProgress.Next n = next(t2);
        assertThat(n.action()).isEqualTo("CREATE_OFFER");
        assertThat(n.text()).isEqualTo("Клиент отказался от КП от 24.09.2026 — новое КП или закройте сделку");
    }

    @Test
    void closedDealNoHint() throws Exception {
        Chat c = chat(LeadChannel.EMAIL);
        Tender t = deal(c);
        file(c, LeadDirection.OUT, "2026-10-15T07:30:00Z", "КП", "Высылаем КП", "КП.pdf");
        requests.close(t.getId(), PrivateRequestStatus.WON);
        assertThat(next(t).action()).isNull();
    }
}
