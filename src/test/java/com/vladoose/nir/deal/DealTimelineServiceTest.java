package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.dto.response.DealTimelineResponse;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.LeadService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealEventService;
import com.vladoose.nir.service.deal.DealTimelineService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Лента «Переписка и история» заявки (спека 2026-10-18 §3.2). */
@SpringBootTest
@Transactional
class DealTimelineServiceTest {

    @Autowired DealTimelineService timeline;
    @Autowired PrivateRequestService requests;
    @Autowired LeadService leadService;
    @Autowired LeadRepository leads;
    @Autowired ChatRepository chats;
    @Autowired ChatMessageRepository messages;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired InboundEmailRepository inbound;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService offers;
    @Autowired DealEventService events;
    @Autowired DealEventRepository eventRepository;
    @Autowired TenderRepository tenders;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Chat chat() {
        return chats.save(Chat.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).account("77000000001")
                .externalChatId("ZZ" + System.nanoTime() + "@c.us").title("Клиент").build());
    }

    private void msg(Chat c, OffsetDateTime at, String text) {
        messages.save(ChatMessage.builder().chat(c).externalId("ZZ" + System.nanoTime()).direction(LeadDirection.IN)
                .type(ChatMessageType.TEXT).body(text).sentAt(at).build());
    }

    private Lead lead(Chat c, OffsetDateTime receivedAt) {
        return leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).source("whatsapp")
                .externalId("ZZ" + System.nanoTime()).subject("WhatsApp").contactName("Клиент").status(LeadStatus.IN_WORK)
                .chat(c).receivedAt(receivedAt).build());
    }

    private List<DealTimelineItem> items(Tender t) {
        em.flush();
        return timeline.forPrivateRequest(t.getId(), null, 50).items();
    }

    private Tender withLines(int n) {
        Tender t = requests.createEmpty();
        List<PrivateRequestLineInput> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) lines.add(input("ZZ Лента " + i, null, 1, null, null, null));
        return requests.addLines(t.getId(), lines, LinesMode.APPEND);
    }

    private PriceRequest ask(Tender t, String supplier, String status, LetterStatus letter, OffsetDateTime sentAt,
                             String... prices) {
        Distributor d = distributors.save(Distributor.builder().name(supplier + " " + System.nanoTime()).email("p@zz.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).letterStatus(letter).sentAt(sentAt).build();
        List<TenderLot> lots = t.getLots().stream().sorted(Comparator.comparing(TenderLot::getLotNumber)).toList();
        for (int i = 0; i < lots.size(); i++) {
            String p = i < prices.length ? prices[i] : null;
            pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(lots.get(i)).requestedQuantity(1)
                    .responsePrice(p == null ? null : new BigDecimal(p)).build());
        }
        return priceRequests.save(pr);
    }

    /** Запросов к базе на ленту — столько же при 6 поставщиках, сколько при 2: поставщик и строки — одним запросом. */
    @Test
    void queriesDoNotGrowWithPriceRequests() {
        assertThat(timelineStatements(6)).isEqualTo(timelineStatements(2));
    }

    private long timelineStatements(int suppliers) {
        Tender t = withLines(2);
        OffsetDateTime at = OffsetDateTime.now().minusHours(2).truncatedTo(ChronoUnit.SECONDS);
        for (int i = 0; i < suppliers; i++) {
            ask(t, "ZZ Запросы " + i, "RESPONDED", LetterStatus.SENT, at.plusMinutes(5L * i), "100.00");
        }
        em.flush();
        em.clear();
        Statistics stats = emf.unwrap(SessionFactory.class).getStatistics();
        boolean was = stats.isStatisticsEnabled();
        stats.setStatisticsEnabled(true);
        stats.clear();
        try {
            timeline.forPrivateRequest(t.getId(), null, 50);
            return stats.getPrepareStatementCount();
        } finally {
            stats.setStatisticsEnabled(was);
        }
    }

    @Test
    void chatMessagesOnlyWithinLeadEpisode() {
        Chat c = chat();
        OffsetDateTime base = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
        msg(c, base.minusHours(1), "до обращения");
        Lead l = lead(c, base);
        msg(c, base.plusMinutes(5), "нужен АСД");
        Tender t = requests.findById(leadService.convert(l.getId(), null, "t").getPrivateRequestId());
        msg(c, base.plusHours(2), "после заявки");
        lead(c, base.plusDays(1));
        msg(c, base.plusDays(1).plusMinutes(1), "уже про другое");

        List<DealTimelineItem> all = items(t);
        assertThat(all).filteredOn(i -> "MESSAGE".equals(i.kind())).extracting(i -> i.message().getBody())
                .containsExactly("нужен АСД", "после заявки");
        assertThat(all).filteredOn(i -> "MESSAGE".equals(i.kind())).allMatch(i -> c.getId().equals(i.linkId()));
        assertThat(all.get(0).kind()).isEqualTo("LEAD");
        assertThat(all.get(0).text()).isEqualTo("Обращение (WhatsApp): «WhatsApp»");
        assertThat(all).extracting(DealTimelineItem::kind).contains("CREATED", "LEAD_STATUS");
    }

    @Test
    void supplyEventsGroupedLinkedAndCrossMarket() {
        Tender t = withLines(2);
        OffsetDateTime at = OffsetDateTime.now().minusHours(3).truncatedTo(ChronoUnit.MINUTES).plusSeconds(5);
        PriceRequest a = ask(t, "ZZ ФОТЕК", "RESPONDED", LetterStatus.SENT, at, "277025.00");
        PriceRequest b = ask(t, "ZZ Аксион", "SENT", LetterStatus.MANUAL, at.plusSeconds(20));
        PriceRequest c = ask(t, "ZZ МЕДСИСТ", "SENT", LetterStatus.NO_EMAIL, null);
        PriceRequest d = ask(t, "ZZ Возврат", "SENT", LetterStatus.SENT, at.plusMinutes(5));
        OffsetDateTime replyAt = at.plusHours(1);
        // ответ на запрос пришёл в ящик ДРУГОГО рынка (R17) — строка «Входящих» рынка РФ
        inbound.save(InboundEmail.builder().fromAddress("fotek@zz.ru").subject("Re: [КП-" + a.getId() + "]")
                .type(InboundType.SUPPLIER_RESPONSE).matchedPriceRequestId(a.getId()).receivedAt(replyAt)
                .status(InboundStatus.NEW).market(Market.RF).build());
        inbound.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(d.getId()).receivedAt(at.plusMinutes(6))
                .status(InboundStatus.NEW).build());

        List<DealTimelineItem> all = items(t);
        DealTimelineItem rfq = all.stream().filter(i -> "RFQ_SENT".equals(i.kind())).findFirst().orElseThrow();
        assertThat(rfq.text()).isEqualTo("Запрос цен: письмом — " + a.getDistributor().getName()
                + "; передан вручную — " + b.getDistributor().getName());
        assertThat(rfq.linkId()).isNull();
        assertThat(all).filteredOn(i -> "LETTER_FAILED".equals(i.kind())).singleElement().satisfies(i -> {
            assertThat(i.text()).isEqualTo(c.getDistributor().getName() + ": письмо не ушло — нет почты");
            assertThat(i.linkType()).isEqualTo("PRICE_REQUEST");
            assertThat(i.linkId()).isEqualTo(c.getId());
        });
        assertThat(all).filteredOn(i -> "BOUNCED".equals(i.kind())).extracting(DealTimelineItem::text)
                .containsExactly(d.getDistributor().getName() + ": письмо вернулось");
        assertThat(all).filteredOn(i -> "REPLY".equals(i.kind())).singleElement().satisfies(i -> {
            assertThat(i.at().toInstant()).isEqualTo(replyAt.toInstant());
            assertThat(i.dateOnly()).isFalse();
            assertThat(i.text()).isEqualTo(a.getDistributor().getName() + " ответил: цены по 1 из 2");
            assertThat(i.linkId()).isEqualTo(a.getId());
        });
    }

    @Test
    void replyWithoutLetterIsDateOnlyAtNoon() {
        Tender t = withLines(1);
        PriceRequest pr = ask(t, "ZZ Руками", "RESPONDED", LetterStatus.MANUAL, OffsetDateTime.now().minusDays(2), "100.00");
        pr.setResponseDate(LocalDate.now(Market.KZ.zone()).minusDays(1));
        DealTimelineItem reply = items(t).stream().filter(i -> "REPLY".equals(i.kind())).findFirst().orElseThrow();
        assertThat(reply.dateOnly()).isTrue();
        assertThat(reply.at().atZoneSameInstant(Market.KZ.zone()).toLocalTime()).isEqualTo(LocalTime.NOON);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void offersAndEventsAppearWithAuthors() {
        Tender t = withLines(1);
        ask(t, "ZZ Цена", "RESPONDED", LetterStatus.SENT, OffsetDateTime.now().minusHours(2), "100.00");
        ClientOffer o = offersFromRequest.createFromPrivateRequest(t.getId(), "vlad");
        offers.setStatus(o.getId(), ClientOfferStatus.SENT);
        offers.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);
        events.note(t.getId(), DealEventType.NOTE, "просит доставку в Каскелен");
        List<DealTimelineItem> all = items(t);
        assertThat(all).extracting(DealTimelineItem::kind).contains("OFFER_CREATED", "OFFER_SENT", "STAGE", "NOTE");
        assertThat(all).filteredOn(i -> "STAGE".equals(i.kind())).singleElement().satisfies(i -> {
            assertThat(i.text()).isEqualTo("КП № " + o.getNumber() + ": клиент согласился");
            assertThat(i.linkType()).isEqualTo("OFFER");
            assertThat(i.linkId()).isEqualTo(o.getId());
            assertThat(i.author()).isEqualTo("vlad");
        });
    }

    @Test
    void pagesFromNewestBackwards() {
        Tender t = requests.createEmpty();
        OffsetDateTime now = OffsetDateTime.now();
        for (int k = 1; k <= 3; k++) {
            eventRepository.save(DealEvent.builder().tenderId(t.getId()).type(DealEventType.NOTE).text("заметка " + k)
                    .occurredAt(now.plusMinutes(k)).build());
        }
        em.flush();
        DealTimelineResponse first = timeline.forPrivateRequest(t.getId(), null, 2);
        assertThat(first.items()).extracting(DealTimelineItem::text).containsExactly("заметка 2", "заметка 3");
        assertThat(first.hasMore()).isTrue();
        DealTimelineResponse second = timeline.forPrivateRequest(t.getId(), first.items().get(0).at(), 2);
        assertThat(second.items()).extracting(DealTimelineItem::kind).containsExactly("CREATED", "NOTE");
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void otherMarketAndPublicTenderAre404() {
        Tender t = requests.createEmpty();
        Tender pub = tenders.save(Tender.builder().tenderNumber("ZZ-T-" + System.nanoTime()).status("ACTIVE").build());
        em.flush();
        assertThatThrownBy(() -> timeline.forPrivateRequest(pub.getId(), null, 50)).isInstanceOf(NotFoundException.class);
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> timeline.forPrivateRequest(t.getId(), null, 50)).isInstanceOf(NotFoundException.class);
    }

    /** Альбом фото — сообщения одной секунды: на границе страницы ни одно не теряется (курсор — время). */
    @Test
    void sameSecondMessagesAreNotLostBetweenPages() {
        Tender t = requests.createEmpty();
        Chat c = chat();
        OffsetDateTime base = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).source("whatsapp")
                .externalId("ZZ" + System.nanoTime()).subject("WhatsApp").contactName("Клиент")
                .status(LeadStatus.CONVERTED).chat(c).receivedAt(base).privateRequest(t).build());
        msg(c, base.plusMinutes(1), "a");
        for (String b : List.of("фото 1", "фото 2", "фото 3", "фото 4")) msg(c, base.plusMinutes(2), b);
        msg(c, base.plusMinutes(3), "e");
        em.flush();

        List<String> bodies = new ArrayList<>();
        OffsetDateTime before = null;
        boolean more = true;
        for (int guard = 0; more && guard < 10; guard++) {
            DealTimelineResponse p = timeline.forPrivateRequest(t.getId(), before, 2);
            List<String> page = p.items().stream().filter(i -> "MESSAGE".equals(i.kind()))
                    .map(i -> i.message().getBody()).toList();
            bodies.addAll(0, page);
            more = p.hasMore();
            before = p.items().get(0).at();
        }
        assertThat(bodies).containsExactly("a", "фото 1", "фото 2", "фото 3", "фото 4", "e");
    }

    /** Одно окно «Запросить цены» через границу минуты — одна строка; следующее окно через 3 минуты — своя. */
    @Test
    void rfqWindowAcrossMinuteBoundaryIsOneRow() {
        Tender t = withLines(1);
        OffsetDateTime minute = OffsetDateTime.now().minusHours(3).truncatedTo(ChronoUnit.MINUTES);
        PriceRequest a = ask(t, "ZZ Первый", "SENT", LetterStatus.SENT, minute.plusSeconds(58));
        PriceRequest b = ask(t, "ZZ Второй", "SENT", LetterStatus.SENT, minute.plusSeconds(63));
        PriceRequest c = ask(t, "ZZ Позже", "SENT", LetterStatus.SENT, minute.plusMinutes(4));
        assertThat(items(t)).filteredOn(i -> "RFQ_SENT".equals(i.kind())).extracting(DealTimelineItem::text)
                .containsExactly("Запрос цен: письмом — " + a.getDistributor().getName() + ", " + b.getDistributor().getName(),
                        "Запрос цен: письмом — " + c.getDistributor().getName());
    }

    /**
     * Фронт шлёт курсор с точностью до миллисекунды (toISOString), а время в базе — с микросекундами: строки одной
     * миллисекунды на границе страницы не теряются.
     */
    @Test
    void millisecondCursorDoesNotLoseItems() {
        Tender t = requests.createEmpty();
        OffsetDateTime ms = OffsetDateTime.now().plusMinutes(5).truncatedTo(ChronoUnit.MILLIS);
        eventRepository.save(DealEvent.builder().tenderId(t.getId()).type(DealEventType.NOTE).text("раньше")
                .occurredAt(ms.plusNanos(100_000)).build());
        eventRepository.save(DealEvent.builder().tenderId(t.getId()).type(DealEventType.NOTE).text("позже")
                .occurredAt(ms.plusNanos(400_000)).build());
        em.flush();

        List<String> texts = new ArrayList<>();
        OffsetDateTime before = null;
        boolean more = true;
        for (int guard = 0; more && guard < 10; guard++) {
            DealTimelineResponse p = timeline.forPrivateRequest(t.getId(), before, 1);
            texts.addAll(0, p.items().stream().map(DealTimelineItem::text).toList());
            more = p.hasMore();
            before = p.items().get(0).at().truncatedTo(ChronoUnit.MILLIS);   // как new Date(at).toISOString()
        }
        assertThat(texts).containsExactly("Создана заявка " + t.getTenderNumber(), "раньше", "позже");
    }

    /** Сообщения одной миллисекунды, разрезанные выборкой n + 1, докачиваются целиком — курсор до миллисекунды их не теряет. */
    @Test
    void sameMillisecondMessagesAreFetchedWhole() {
        Tender t = requests.createEmpty();
        Chat c = chat();
        OffsetDateTime base = OffsetDateTime.now().minusDays(3).truncatedTo(ChronoUnit.SECONDS);
        leads.save(Lead.builder().market(Market.KZ).channel(LeadChannel.WHATSAPP).source("whatsapp")
                .externalId("ZZ" + System.nanoTime()).subject("WhatsApp").contactName("Клиент")
                .status(LeadStatus.CONVERTED).chat(c).receivedAt(base).privateRequest(t).build());
        OffsetDateTime ms = base.plusMinutes(1);
        msg(c, ms.plusNanos(100_000), "x");
        msg(c, ms.plusNanos(400_000), "y");
        msg(c, ms.plusNanos(700_000), "w");
        msg(c, base.plusMinutes(2), "e");
        em.flush();

        List<String> bodies = new ArrayList<>();
        OffsetDateTime before = null;
        boolean more = true;
        for (int guard = 0; more && guard < 10; guard++) {
            DealTimelineResponse p = timeline.forPrivateRequest(t.getId(), before, 1);
            bodies.addAll(0, p.items().stream().filter(i -> "MESSAGE".equals(i.kind()))
                    .map(i -> i.message().getBody()).toList());
            more = p.hasMore();
            before = p.items().get(0).at().truncatedTo(ChronoUnit.MILLIS);
        }
        assertThat(bodies).containsExactly("x", "y", "w", "e");
    }

    /** Цену вписали руками в день запроса после полудня: ответ без письма — не раньше самого запроса (живая проверка). */
    @Test
    void replyWithoutLetterIsNotBeforeItsRequest() {
        Tender t = withLines(1);
        LocalDate day = LocalDate.now(Market.KZ.zone()).minusDays(1);
        OffsetDateTime sent = day.atTime(18, 0).atZone(Market.KZ.zone()).toOffsetDateTime();
        PriceRequest pr = ask(t, "ZZ Вечером", "RESPONDED", LetterStatus.SENT, sent, "100.00");
        pr.setResponseDate(day);
        List<DealTimelineItem> all = items(t);
        DealTimelineItem reply = all.stream().filter(i -> "REPLY".equals(i.kind())).findFirst().orElseThrow();
        assertThat(reply.dateOnly()).isTrue();
        assertThat(reply.at().toInstant()).isEqualTo(sent.toInstant());
        assertThat(all).extracting(DealTimelineItem::kind).containsSubsequence("RFQ_SENT", "REPLY");
    }
}
