package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.dto.response.DealProgress;
import com.vladoose.nir.dto.response.DealProgress.Next;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.DistributorRepository;
import com.vladoose.nir.repository.InboundEmailRepository;
import com.vladoose.nir.repository.PriceRequestRepository;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.deal.DealTexts;
import com.vladoose.nir.service.privaterequest.PrivateRequestViewBuilder;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;

/** Этапы и «Дальше» страницы заявки (спека 2026-10-18 §3.1) — по таблице «Где сделка / Дальше / Кнопка». */
@SpringBootTest
@Transactional
class PrivateRequestProgressTest {

    @Autowired PrivateRequestService requests;
    @Autowired PrivateRequestViewBuilder views;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired InboundEmailRepository inbound;
    @Autowired ClientOfferRequestService offersFromRequest;
    @Autowired ClientOfferService offers;
    @Autowired EntityManager em;

    private static final DateTimeFormatter DD_MM = DateTimeFormatter.ofPattern("dd.MM");
    /** Шаги исполнения до принятого КП (спека 2026-10-19-deal-execution §3.1). */
    private static final String EXEC_TODO = " CONTRACT:TODO PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO";

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private DealProgress progress(Tender t) {
        em.flush();
        return views.build(requests.findById(t.getId())).getProgress();
    }

    private static String states(DealProgress p) {
        return p.steps().stream().map(s -> s.key() + ":" + s.state()).collect(joining(" "));
    }

    private Tender withLines(int n) {
        Tender t = requests.createEmpty();
        List<PrivateRequestLineInput> lines = new ArrayList<>();
        for (int i = 1; i <= n; i++) lines.add(input("ZZ Прогресс " + i, null, 1, null, null, null));
        return requests.addLines(t.getId(), lines, LinesMode.APPEND);
    }

    /** Запрос цен одному новому поставщику; price — цена по первой позиции (null — без цены). */
    private PriceRequest ask(Tender t, String status, LetterStatus letter, String price) {
        Distributor d = distributors.save(Distributor.builder().name("ZZ Прогресс " + System.nanoTime()).email("p@zz.kz").build());
        PriceRequest pr = PriceRequest.builder().tender(t).distributor(d).status(status).letterStatus(letter)
                .sentAt(OffsetDateTime.now()).build();
        TenderLot lot = t.getLots().stream().min(Comparator.comparing(TenderLot::getLotNumber)).orElseThrow();
        pr.getItems().add(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(price == null ? null : new BigDecimal(price)).build());
        return priceRequests.save(pr);
    }

    @Test
    void emptyRequest() {
        DealProgress p = progress(requests.createEmpty());
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:CURRENT PRICES:TODO OFFER:TODO" + EXEC_TODO + " RESULT:TODO");
        assertThat(p.steps().get(0).label()).isEqualTo("Создана");
        assertThat(p.steps().get(1).detail()).isEqualTo("нет позиций");
        assertThat(p.next()).isEqualTo(new Next("Добавьте позиции", "ADD_LINES", null));
    }

    @Test
    void newRequestAsksForPrices() {
        DealProgress p = progress(withLines(2));
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:DONE PRICES:CURRENT OFFER:TODO" + EXEC_TODO + " RESULT:TODO");
        assertThat(p.steps().get(1).detail()).isEqualTo("2 позиции");
        assertThat(p.steps().get(2).detail()).isEqualTo("не запрашивали");
        assertThat(p.next()).isEqualTo(new Next("Запросите цены у поставщиков", "REQUEST_PRICES", null));
    }

    @Test
    void waitingCountsAnsweredAndFailedLetters() {
        Tender t = withLines(1);
        ask(t, "RESPONDED", LetterStatus.SENT, null);
        ask(t, "SENT", LetterStatus.SENT, null);
        ask(t, "SENT", LetterStatus.NO_EMAIL, null);
        PriceRequest bounced = ask(t, "SENT", LetterStatus.SENT, null);
        inbound.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(bounced.getId()).receivedAt(OffsetDateTime.now())
                .status(InboundStatus.NEW).build());
        DealProgress p = progress(t);
        assertThat(p.steps().get(2).detail()).isEqualTo("ответил 1 из 4");
        assertThat(p.next()).isEqualTo(new Next("Ответил 1 из 4; 2 письма не ушли", "OPEN_PRICES", null));
    }

    @Test
    void noAnswersYet() {
        Tender t = withLines(1);
        ask(t, "SENT", LetterStatus.SENT, null);
        ask(t, "SENT", LetterStatus.SENT, null);
        DealProgress p = progress(t);
        assertThat(p.steps().get(2).detail()).isEqualTo("ответов пока нет");
        assertThat(p.next()).isEqualTo(new Next("Ответов пока нет — запрошено у 2 поставщиков", "OPEN_PRICES", null));
    }

    @Test
    void pricedSuggestsOffer() {
        Tender t = withLines(2);
        ask(t, "RESPONDED", LetterStatus.SENT, "1000.00");
        DealProgress p = progress(t);
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:CURRENT" + EXEC_TODO + " RESULT:TODO");
        assertThat(p.steps().get(2).detail()).isEqualTo("цены по 1 из 2");
        assertThat(p.next()).isEqualTo(new Next("Цены есть по 1 из 2 позиций — можно делать КП", "CREATE_OFFER", null));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void offerLifecycle() {
        Tender t = withLines(1);
        ask(t, "RESPONDED", LetterStatus.SENT, "1000.00");
        ClientOffer o = offersFromRequest.createFromPrivateRequest(t.getId(), "t");
        assertThat(progress(t).next()).isEqualTo(new Next("Доделайте и отправьте КП № " + o.getNumber(), "OPEN_OFFER", o.getId()));

        ClientOffer sent = offers.setStatus(o.getId(), ClientOfferStatus.SENT);
        String day = DD_MM.format(sent.getSentAt().atZoneSameInstant(Market.KZ.zone()));
        DealProgress p = progress(t);
        assertThat(p.steps().get(3).detail()).isEqualTo("№ " + o.getNumber() + " · отправлено " + day);
        assertThat(p.next()).isEqualTo(new Next("КП № " + o.getNumber() + " отправлено " + day + " — что ответил клиент?",
                "OFFER_DECISION", o.getId()));

        offers.setStatus(o.getId(), ClientOfferStatus.REJECTED);
        assertThat(progress(t).next()).isEqualTo(new Next("Клиент отказался от КП № " + o.getNumber()
                + " — новое КП или закройте сделку", "CREATE_OFFER", null));

        offers.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);
        p = progress(t);
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE "
                + "CONTRACT:CURRENT PAYMENT:TODO SUPPLIERS:TODO DELIVERY:TODO DOCS:TODO RESULT:TODO");
        assertThat(p.next()).isEqualTo(new Next("Клиент согласился на КП № " + o.getNumber() + " — договор или счёт",
                "EXEC_CONTRACT", null));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void mainOfferPrefersSentOverNewerDraft() {
        Tender t = withLines(1);
        ask(t, "RESPONDED", LetterStatus.SENT, "1000.00");
        ClientOffer first = offersFromRequest.createFromPrivateRequest(t.getId(), "t");
        offers.setStatus(first.getId(), ClientOfferStatus.SENT);
        offersFromRequest.createFromPrivateRequest(t.getId(), "t");   // новее, но черновик
        DealProgress p = progress(t);
        assertThat(p.next().action()).isEqualTo("OFFER_DECISION");
        assertThat(p.next().refId()).isEqualTo(first.getId());
    }

    @Test
    void closedShowsOutcomeWithDate() {
        Tender t = withLines(1);
        requests.close(t.getId(), PrivateRequestStatus.WON);
        String day = DD_MM.format(OffsetDateTime.now().atZoneSameInstant(Market.KZ.zone()));
        DealProgress p = progress(t);
        assertThat(states(p)).isEqualTo("SOURCE:DONE LINES:DONE PRICES:DONE OFFER:DONE CONTRACT:SKIPPED PAYMENT:SKIPPED "
                + "SUPPLIERS:SKIPPED DELIVERY:SKIPPED DOCS:SKIPPED RESULT:DONE");
        assertThat(p.steps().get(9).detail()).isEqualTo("купили у нас " + day);
        assertThat(p.next()).isEqualTo(new Next("Закрыта: купили у нас, " + day, null, null));
    }

    @Test
    void pluralForms() {
        assertThat(DealTexts.positions(1)).isEqualTo("1 позиция");
        assertThat(DealTexts.positions(4)).isEqualTo("4 позиции");
        assertThat(DealTexts.positions(11)).isEqualTo("11 позиций");
        assertThat(DealTexts.positions(21)).isEqualTo("21 позиция");
        assertThat(DealTexts.lots(12)).isEqualTo("12 лотов");
        assertThat(DealTexts.lettersFailed(1)).isEqualTo("1 письмо не ушло");
        assertThat(DealTexts.lettersFailed(5)).isEqualTo("5 писем не ушли");
        assertThat(DealTexts.answered(21, 30, true)).isEqualTo("Ответил 21 из 30");
    }
}
