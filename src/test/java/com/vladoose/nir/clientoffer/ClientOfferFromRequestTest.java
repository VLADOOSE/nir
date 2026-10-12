package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.dto.request.PrivateRequestField;
import com.vladoose.nir.dto.request.PrivateRequestLineInput;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** КП клиенту из частной заявки (спека 2026-10-12 §8; решения плана 1–4, 12). */
@SpringBootTest
@Transactional
class ClientOfferFromRequestTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired CompanyProfileRepository profiles;
    @Autowired DistributorRepository distributors;
    @Autowired FacilityRepository facilities;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired TenderLotRepository lots;
    @Autowired TenderRepository tenders;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        // реквизиты в nirdb правит оператор: ставки и умолчания — явно, в транзакции теста (откатывается)
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setVatDefault(new BigDecimal("16"));
        kz.setVatRegistered(new BigDecimal("5"));
        kz.setVatNotRegistrable(null);
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private Tender request(PrivateRequestLineInput... lines) {
        Tender t = service.createEmpty();
        service.addLines(t.getId(), List.of(lines), LinesMode.APPEND);
        return t;
    }

    private static List<TenderLot> sorted(Tender t) {
        List<TenderLot> l = new ArrayList<>(t.getLots());
        l.sort(Comparator.comparing(TenderLot::getLotNumber));
        return l;
    }

    private Distributor dist(String name) {
        return distributors.save(Distributor.builder().name(name + " " + System.nanoTime()).email("d@x.kz").build());
    }

    /** Ответ поставщика по позиции: запрос со статусом и строка с ценой (как в PrivateRequestPricesTest). */
    private PriceRequestItem answer(Tender t, Distributor d, TenderLot lot, String status, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(t).distributor(d).status(status)
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(price == null ? null : new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    @Test
    void rowsFromPositionsInOrder_clientAndCheapestAnswer() {
        Facility f = facilities.save(Facility.builder().name("ZZ КП Клиника " + System.nanoTime()).build());
        Tender t = request(input("ZZ КП Монитор", "Mindray", 2, "компл", null, null),
                input("ZZ КП Кушетка", null, null, null, null, null));
        service.updateField(t.getId(), PrivateRequestField.FACILITY, String.valueOf(f.getId()));
        List<TenderLot> l = sorted(t);
        Distributor a = dist("ZZ КП Поставщик А"), b = dist("ZZ КП Поставщик Б");
        answer(t, a, l.get(0), "RESPONDED", "120000");
        PriceRequestItem cheaper = answer(t, b, l.get(0), "RESPONDED", "110000.5");
        em.flush();

        ClientOffer o = requestOffers.createFromPrivateRequest(t.getId(), "test");

        assertThat(o.getTenderId()).isEqualTo(t.getId());
        assertThat(o.getFacility().getId()).isEqualTo(f.getId());
        assertThat(o.getRecipient()).isEqualTo(f.getName());
        assertThat(o.getStatus()).isEqualTo(ClientOfferStatus.DRAFT);
        assertThat(o.getItems()).hasSize(2);
        ClientOfferItem first = o.getItems().get(0), second = o.getItems().get(1);
        assertThat(first.getName()).isEqualTo("ZZ КП Монитор");
        assertThat(first.getQuantity()).isEqualByComparingTo("2");
        assertThat(first.getUnit()).isEqualTo("компл");
        assertThat(first.getTenderLotId()).isEqualTo(l.get(0).getId());
        assertThat(first.getPurchasePrice()).isEqualByComparingTo("110000.50");
        assertThat(first.getPriceRequestItemId()).isEqualTo(cheaper.getId());
        assertThat(first.getDistributorId()).isEqualTo(b.getId());
        assertThat(first.getSupplierName()).isEqualTo(b.getName());
        assertThat(second.getQuantity()).isEqualByComparingTo("1");   // кол-ва нет — 1
        assertThat(second.getUnit()).isEqualTo("шт");                    // ед. изм. нет — «шт»
        assertThat(second.getPurchasePrice()).isNull();                  // ответов нет — закупка пустая
        assertThat(second.getPriceRequestItemId()).isNull();
        assertThat(o.getItemCount()).isEqualTo(2);
        assertThat(o.getTotalAmount()).isNotNull();
    }

    @Test
    void registrationManufacturerCountryAndVatByLabel() {
        String reg = jdbc.queryForObject("select reg_number from med_registry where reg_date is not null and producer is not null"
                + " and country is not null order by reg_number limit 1", String.class);
        Tender t = request(input("ZZ КП Подтверждено", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Не подлежит", "ZZBRAND", 1, null, null, null),
                input("ZZ КП Подсказка", "ZZBRAND", 1, null, null, null));
        List<TenderLot> l = sorted(t);
        l.get(0).setRegStatus(LineRegStatus.CONFIRMED);
        l.get(0).setRegNumber(reg);
        l.get(1).setRegStatus(LineRegStatus.NOT_REQUIRED);
        l.get(2).setRegStatus(LineRegStatus.SUGGESTED);
        l.get(2).setRegNumber(reg);
        em.flush();
        MedRegistry rec = jdbc.queryForObject("select reg_number, producer, country from med_registry where reg_number = ?",
                (rs, n) -> { MedRegistry m = new MedRegistry(); m.setProducer(rs.getString(2)); m.setCountry(rs.getString(3)); return m; }, reg);

        List<ClientOfferItem> items = requestOffers.createFromPrivateRequest(t.getId(), "test").getItems();

        assertThat(items.get(0).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.CONFIRMED);
        assertThat(items.get(0).getRegNumber()).isEqualTo(reg);
        assertThat(items.get(0).getRegistrationText()).startsWith("№ " + reg + " от ");
        assertThat(items.get(0).getManufacturer()).isEqualTo(rec.getProducer().length() > 500 ? rec.getProducer().substring(0, 500) : rec.getProducer());
        assertThat(items.get(0).getCountry()).isNotBlank();
        assertThat(items.get(0).getVatRate()).isEqualByComparingTo("5");
        assertThat(items.get(1).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.NOT_REQUIRED);
        assertThat(items.get(1).getRegistrationText()).isEqualTo("Не подлежит регистрации");
        assertThat(items.get(1).getVatRate()).isNull();
        assertThat(items.get(2).getRegistrationStatus()).isEqualTo(OfferRegistrationStatus.UNCHECKED);
        assertThat(items.get(2).getRegNumber()).isNull();
        assertThat(items.get(2).getManufacturer()).isEqualTo("ZZBRAND");
        assertThat(items.get(2).getVatRate()).isEqualByComparingTo("16");
    }

    /** Отказ, отклонённый и закрытый запрос — не кандидаты; принятый сильнее более дешёвого ответа. */
    @Test
    void priceRule_acceptedFirst_declinedRejectedClosedIgnored() {
        Tender t = request(input("ZZ КП Правило", null, 1, null, null, null));
        TenderLot lot = sorted(t).get(0);
        answer(t, dist("ZZ КП Отказ"), lot, "DECLINED", "10");
        answer(t, dist("ZZ КП Отклонён"), lot, "REJECTED", "11");
        answer(t, dist("ZZ КП Закрыт"), lot, "CLOSED", "12");
        answer(t, dist("ZZ КП Дешёвый"), lot, "RESPONDED", "500");
        Distributor accepted = dist("ZZ КП Принятый");
        answer(t, accepted, lot, "ACCEPTED", "700");
        em.flush();

        ClientOfferItem it = requestOffers.createFromPrivateRequest(t.getId(), "test").getItems().get(0);

        assertThat(it.getPurchasePrice()).isEqualByComparingTo("700");
        assertThat(it.getDistributorId()).isEqualTo(accepted.getId());
    }

    /** «Кому» — печатный текст: «Клиент не указан» и телефон в него не попадают (Review Focus 4, решение плана 1). */
    @Test
    void recipientNeverPrintsPlaceholder() {
        Tender none = request(input("ZZ КП Без клиента", null, 1, null, null, null));
        assertThat(requestOffers.createFromPrivateRequest(none.getId(), "test").getRecipient()).isNull();

        Tender phone = request(input("ZZ КП Телефон", null, 1, null, null, null));
        service.updateField(phone.getId(), PrivateRequestField.CONTACT_PHONE, "+7 701 000 00 00");
        assertThat(requestOffers.createFromPrivateRequest(phone.getId(), "test").getRecipient()).isNull();

        Tender contact = request(input("ZZ КП Контакт", null, 1, null, null, null));
        service.updateField(contact.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Айгуль Сапарова");
        assertThat(requestOffers.createFromPrivateRequest(contact.getId(), "test").getRecipient()).isEqualTo("ZZ Айгуль Сапарова");

        Tender named = request(input("ZZ КП Текстом", null, 1, null, null, null));
        service.updateField(named.getId(), PrivateRequestField.CUSTOMER_NAME, "ZZ Частная клиника");
        service.updateField(named.getId(), PrivateRequestField.CONTACT_NAME, "ZZ Контакт");
        assertThat(requestOffers.createFromPrivateRequest(named.getId(), "test").getRecipient()).isEqualTo("ZZ Частная клиника");
    }

    @Test
    void hugeSumIs400NotServerError() {
        Tender t = request(input("ZZ КП Огромная", null, 1000, null, null, null));
        answer(t, dist("ZZ КП Дорогой"), sorted(t).get(0), "RESPONDED", "9999999999999");
        em.flush();
        assertThatThrownBy(() -> requestOffers.createFromPrivateRequest(t.getId(), "test"))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("Сумма позиции");
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void endpoint_createsFromRequest_otherMarketAndTendersAre404() throws Exception {
        Tender t = request(input("ZZ КП Путь", null, 1, null, null, null));
        JsonNode r = om.readTree(mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(r.get("tenderId").asLong()).isEqualTo(t.getId());
        assertThat(r.get("items").get(0).get("tenderLotId").asLong()).isEqualTo(sorted(t).get(0).getId());

        mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "RF"))
                .andExpect(status().isNotFound());
        Tender publicTender = tenders.save(Tender.builder().tenderNumber("ZZ-KP-PUB").status("ACTIVE").source(Source.PUBLIC_TENDER).build());
        mvc.perform(post("/api/client-offers/from-private-request/" + publicTender.getId()).header("X-Market", "KZ"))
                .andExpect(status().isNotFound());
    }

    @Test
    @WithMockUser(roles = "USER")
    void operatorCannotCreate() throws Exception {
        Tender t = request(input("ZZ КП Оператор", null, 1, null, null, null));
        mvc.perform(post("/api/client-offers/from-private-request/" + t.getId()).header("X-Market", "KZ"))
                .andExpect(status().isForbidden());
    }
}
