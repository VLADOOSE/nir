package com.vladoose.nir.privaterequest;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse;
import com.vladoose.nir.dto.response.PrivateRequestPricesResponse.Cell;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.PrivateRequestService;
import com.vladoose.nir.service.privaterequest.PrivateRequestPricesService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class PrivateRequestPricesTest {

    @Autowired PrivateRequestService service;
    @Autowired PrivateRequestPricesService prices;
    @Autowired DistributorRepository distributorRepository;
    @Autowired PriceRequestRepository priceRequestRepository;
    @Autowired PriceRequestItemRepository itemRepository;
    @Autowired InboundEmailRepository inboundRepository;
    @Autowired EntityManager em;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private Distributor dist(String name) {
        return distributorRepository.save(Distributor.builder().name(name + " " + System.nanoTime()).email("p@zz.kz").build());
    }

    private PriceRequestItem ask(Tender t, Distributor d, TenderLot lot, String status, LetterStatus letter, String price) {
        PriceRequest pr = priceRequestRepository.save(PriceRequest.builder().tender(t).distributor(d).status(status)
                .letterStatus(letter).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = itemRepository.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot)
                .requestedQuantity(1).responsePrice(price == null ? null : new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private static Cell cell(PrivateRequestPricesResponse r, int line, Distributor d) {
        return r.lines().get(line).cells().stream().filter(c -> c.distributorId().equals(d.getId())).findFirst().orElseThrow();
    }

    @Test
    void cellStatesBestPriceAndSupplierOrder() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены А", null, 2, "упак", null, null),
                input("ZZ Цены Б", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> lots = new ArrayList<>(t.getLots());
        lots.sort(Comparator.comparing(TenderLot::getLotNumber));
        Distributor cheap = dist("ZZ Цены Дешёвый");
        Distributor dear = dist("ZZ Цены Дорогой");
        Distributor declined = dist("ZZ Цены Отказ");
        Distributor bounced = dist("ZZ Цены Возврат");
        Distributor noMail = dist("ZZ Цены Без письма");

        ask(t, dear, lots.get(0), "RESPONDED", LetterStatus.SENT, "20000.00");
        ask(t, cheap, lots.get(0), "RESPONDED", LetterStatus.SENT, "18400.00");
        ask(t, declined, lots.get(0), "DECLINED", LetterStatus.SENT, null);
        PriceRequestItem b = ask(t, bounced, lots.get(1), "SENT", LetterStatus.SENT, null);
        ask(t, noMail, lots.get(1), "SENT", LetterStatus.NO_EMAIL, null);
        inboundRepository.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(b.getPriceRequest().getId()).status(InboundStatus.NEW).build());
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(r.suppliers()).extracting(PrivateRequestPricesResponse.Supplier::distributorId)
                .containsExactly(dear.getId(), cheap.getId(), declined.getId(), bounced.getId(), noMail.getId());
        assertThat(r.lines().get(0).unit()).isEqualTo("упак");
        assertThat(cell(r, 0, cheap).state()).isEqualTo("PRICE");
        assertThat(cell(r, 0, cheap).best()).isTrue();
        assertThat(cell(r, 0, dear).best()).isFalse();
        assertThat(r.lines().get(0).bestPrice()).isEqualByComparingTo("18400.00");
        assertThat(r.lines().get(0).bestDistributorName()).isEqualTo(cheap.getName());
        assertThat(cell(r, 0, declined).state()).isEqualTo("DECLINED");
        assertThat(cell(r, 0, bounced).state()).isEqualTo("NONE");
        assertThat(cell(r, 1, bounced).state()).isEqualTo("BOUNCED");
        assertThat(cell(r, 1, noMail).state()).isEqualTo("NOT_SENT");
        assertThat(cell(r, 1, cheap).state()).isEqualTo("NONE");
        assertThat(r.lines().get(1).bestPrice()).isNull();
    }

    @Test
    void latestRequestToSameSupplierWins() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены повтор", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Повтор");
        ask(t, d, lot, "RESPONDED", LetterStatus.SENT, "100.00");
        ask(t, d, lot, "SENT", LetterStatus.SENT, null);
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(r.suppliers()).hasSize(1);
        assertThat(cell(r, 0, d).state()).isEqualTo("WAITING");   // повторный запрос свежее прежнего ответа
    }

    @Test
    void bounceBeforeResendIsForgotten() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены возврат", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Переслали");
        PriceRequestItem i = ask(t, d, lot, "SENT", LetterStatus.SENT, null);
        PriceRequest pr = i.getPriceRequest();
        pr.setSentAt(OffsetDateTime.now().minusHours(2));
        inboundRepository.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(pr.getId()).status(InboundStatus.NEW)
                .receivedAt(OffsetDateTime.now().minusHours(1)).build());
        em.flush();
        assertThat(cell(prices.build(t.getId()), 0, d).state()).isEqualTo("BOUNCED");

        pr.setSentAt(OffsetDateTime.now());   // «↻ Переслать» после исправления адреса — письмо ушло позже возврата
        em.flush();

        assertThat(cell(prices.build(t.getId()), 0, d).state()).isEqualTo("WAITING");
    }

    @Test
    void instantBounceDatedSecondsBeforeSendMarkIsStillBounced() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены быстрый возврат", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Мгновенный возврат");
        PriceRequestItem i = ask(t, d, lot, "SENT", LetterStatus.SENT, null);
        PriceRequest pr = i.getPriceRequest();
        // отметку отправки ставим после ответа SMTP, а дата возврата — с часов чужого сервера и с точностью до секунды
        pr.setSentAt(OffsetDateTime.now());
        inboundRepository.save(InboundEmail.builder().fromAddress("MAILER-DAEMON@mail.ru").subject("Undelivered")
                .type(InboundType.BOUNCE).matchedPriceRequestId(pr.getId()).status(InboundStatus.NEW)
                .receivedAt(OffsetDateTime.now().minusSeconds(10)).build());
        em.flush();

        assertThat(cell(prices.build(t.getId()), 0, d).state()).isEqualTo("BOUNCED");
    }

    @Test
    void priceEnteredAfterDeclineCounts() {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Цены передумал", null, 1, null, null, null)), LinesMode.APPEND);
        TenderLot lot = t.getLots().get(0);
        Distributor d = dist("ZZ Цены Передумал");
        ask(t, d, lot, "DECLINED", LetterStatus.SENT, "15000.00");   // отказ письмом, потом цена по телефону
        em.flush();

        PrivateRequestPricesResponse r = prices.build(t.getId());

        assertThat(cell(r, 0, d).state()).isEqualTo("PRICE");
        assertThat(cell(r, 0, d).best()).isTrue();
        assertThat(r.lines().get(0).bestPrice()).isEqualByComparingTo("15000.00");
    }
}
