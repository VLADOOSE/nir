package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.LinesMode;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.*;
import com.vladoose.nir.service.ClientOfferRequestService;
import com.vladoose.nir.service.PrivateRequestService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.vladoose.nir.service.PrivateRequestService.input;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Строка КП ↔ ответ поставщика (спека 2026-10-12 §8, решения плана 5–7): гард PUT и «Поставщик ▾». */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class ClientOfferResponseLinkTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired EntityManager em;
    @Autowired PrivateRequestService service;
    @Autowired ClientOfferRequestService requestOffers;
    @Autowired DistributorRepository distributors;
    @Autowired PriceRequestRepository priceRequests;
    @Autowired PriceRequestItemRepository priceItems;
    @Autowired ClientOfferRepository offers;
    MockMvc mvc;

    Tender t;
    TenderLot lotA, lotB;
    Distributor a, b;
    PriceRequestItem aOnA, bOnA, aOnB;
    ClientOffer kp;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        t = service.createEmpty();
        service.addLines(t.getId(), List.of(input("ZZ Ссылка А", null, 1, null, null, null),
                input("ZZ Ссылка Б", null, 1, null, null, null)), LinesMode.APPEND);
        List<TenderLot> l = new ArrayList<>(t.getLots());
        l.sort(Comparator.comparing(TenderLot::getLotNumber));
        lotA = l.get(0);
        lotB = l.get(1);
        a = distributors.save(Distributor.builder().name("ZZ Ссылка Поставщик А " + System.nanoTime()).build());
        b = distributors.save(Distributor.builder().name("ZZ Ссылка Поставщик Б " + System.nanoTime()).build());
        aOnA = answer(a, lotA, "RESPONDED", "100");
        bOnA = answer(b, lotA, "RESPONDED", "150");
        aOnB = answer(a, lotB, "RESPONDED", "40");
        em.flush();
        kp = requestOffers.createFromPrivateRequest(t.getId(), "test");
        em.flush();
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private PriceRequestItem answer(Distributor d, TenderLot lot, String status, String price) {
        PriceRequest pr = priceRequests.save(PriceRequest.builder().tender(lot.getTender()).distributor(d).status(status)
                .letterStatus(LetterStatus.SENT).sentAt(OffsetDateTime.now()).build());
        PriceRequestItem i = priceItems.save(PriceRequestItem.builder().priceRequest(pr).tenderLot(lot).requestedQuantity(1)
                .responsePrice(new BigDecimal(price)).build());
        pr.getItems().add(i);
        return i;
    }

    private JsonNode getOffer() throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId()).header("X-Market", "KZ"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions putOffer(ObjectNode body) throws Exception {
        return mvc.perform(put("/api/client-offers/" + kp.getId()).header("X-Market", "KZ")
                .contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    /** Тело PUT = ответ GET с правкой ссылки строки row (как в ClientOfferApiTest). */
    private ObjectNode withLink(int row, Long priceRequestItemId) throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ObjectNode item = (ObjectNode) body.get("items").get(row);
        if (priceRequestItemId == null) item.putNull("priceRequestItemId"); else item.put("priceRequestItemId", priceRequestItemId);
        return body;
    }

    private ClientOfferItem stored(int row) {
        em.flush();
        em.clear();
        return offers.findById(kp.getId()).orElseThrow().getItems().get(row);
    }

    @Test
    void createdRowCarriesLinkReadOnlyFields() throws Exception {
        JsonNode row = getOffer().get("items").get(0);
        assertThat(row.get("priceRequestItemId").asLong()).isEqualTo(aOnA.getId());   // дешевле всех — А, 100
        assertThat(row.get("distributorId").asLong()).isEqualTo(a.getId());
        assertThat(row.get("tenderLotId").asLong()).isEqualTo(lotA.getId());
    }

    @Test
    void linkToAnotherAnswerOfThisPosition_isSaved_distributorFromAnswer() throws Exception {
        putOffer(withLink(0, bOnA.getId())).andExpect(status().isOk());
        ClientOfferItem it = stored(0);
        assertThat(it.getPriceRequestItemId()).isEqualTo(bOnA.getId());
        assertThat(it.getDistributorId()).isEqualTo(b.getId());
    }

    @Test
    void linkToAnswerOfAnotherRequestIs400() throws Exception {
        Tender other = service.createEmpty();
        service.addLines(other.getId(), List.of(input("ZZ Ссылка Чужая", null, 1, null, null, null)), LinesMode.APPEND);
        PriceRequestItem foreign = answer(a, other.getLots().get(0), "RESPONDED", "1");
        em.flush();
        putOffer(withLink(0, foreign.getId())).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("не относится к этой позиции")));
        assertThat(stored(0).getPriceRequestItemId()).isEqualTo(aOnA.getId());
    }

    @Test
    void linkToAnswerOfAnotherPositionIs400() throws Exception {
        putOffer(withLink(0, aOnB.getId())).andExpect(status().isBadRequest());
    }

    /**
     * Снимок: запрос, на ответ которого ссылается строка, потом закрыли и цену в нём стёрли — та же ссылка живёт (решение
     * плана 5): перепроверка отклонила бы ответ без цены, а КП уже собрано из него.
     */
    @Test
    void unchangedLinkSurvivesClosedRequest() throws Exception {
        aOnA.getPriceRequest().setStatus("CLOSED");
        aOnA.setResponsePrice(null);
        em.flush();
        putOffer(withLink(0, aOnA.getId())).andExpect(status().isOk());
        assertThat(stored(0).getPriceRequestItemId()).isEqualTo(aOnA.getId());
    }

    @Test
    void nullClearsLinkAndDistributor_supplierTextStays() throws Exception {
        putOffer(withLink(0, null)).andExpect(status().isOk());
        ClientOfferItem it = stored(0);
        assertThat(it.getPriceRequestItemId()).isNull();
        assertThat(it.getDistributorId()).isNull();
        assertThat(it.getSupplierName()).isEqualTo(a.getName());
    }

    /** Строку ответа удалили вместе с запросом — автосохранение не падает, ссылка снимается (Review Focus 1). */
    @Test
    void unknownResponseLineDropsLinkSilently() throws Exception {
        putOffer(withLink(1, Long.MAX_VALUE)).andExpect(status().isOk());
        assertThat(stored(1).getPriceRequestItemId()).isNull();
    }

    /** У новой строки позиции нет — ссылка на любой ответ даёт 400 (Review Focus 5). */
    @Test
    void newRowWithLinkIs400() throws Exception {
        ObjectNode body = getOffer().deepCopy();
        ObjectNode copy = ((ObjectNode) body.get("items").get(0)).deepCopy();
        copy.remove("id");
        copy.put("key", "zz-new-row");
        ((ArrayNode) body.get("items")).add(copy);
        putOffer(body).andExpect(status().isBadRequest());
    }

    @Test
    void offersList_latestPricedPerSupplier_cheapestAndSelected() throws Exception {
        long itemId = getOffer().get("items").get(0).get("id").asLong();
        JsonNode list = om.readTree(mvc.perform(get("/api/client-offers/" + kp.getId() + "/items/" + itemId + "/offers")
                .header("X-Market", "KZ")).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("priceRequestItemId").asLong()).isEqualTo(aOnA.getId());
        assertThat(list.get(0).get("cheapest").asBoolean()).isTrue();
        assertThat(list.get(0).get("selected").asBoolean()).isTrue();
        assertThat(list.get(1).get("priceRequestItemId").asLong()).isEqualTo(bOnA.getId());
        assertThat(list.get(1).get("cheapest").asBoolean()).isFalse();
    }
}
