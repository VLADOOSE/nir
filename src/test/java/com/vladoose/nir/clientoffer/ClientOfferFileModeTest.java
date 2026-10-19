package com.vladoose.nir.clientoffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.repository.DealEventRepository;
import com.vladoose.nir.service.ClientOfferService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** КП в режиме «из файла» (спека 2026-10-19 §3.3, §5.1, §6; решения при планировании 1, 6, 10). */
@SpringBootTest
@Transactional
class ClientOfferFileModeTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired ClientOfferRepository offers;
    @Autowired ClientOfferService service;
    @Autowired DealEventRepository events;
    @Autowired PrivateRequestService requests;
    @Autowired CompanyProfileRepository profiles;
    MockMvc mvc;

    @BeforeEach
    void setUp() {
        MarketContext.set(Market.KZ);
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(SecurityMockMvcConfigurers.springSecurity()).build();
        CompanyProfile kz = profiles.findByMarket(Market.KZ).orElseThrow();
        kz.setVatRates(new ArrayList<>(Arrays.asList(new BigDecimal("5"), new BigDecimal("16"), null)));
        kz.setDefaultMarkupPct(new BigDecimal("20.00"));
    }

    @AfterEach void clear() { MarketContext.clear(); }

    /** КП из файла заявки: «Отправлено», одна позиция с ценой из файла и закупкой. */
    private ClientOffer fileOffer(Tender t, Integer number) {
        ClientOffer o = ClientOfferTestData.newOffer(1);
        o.setNumber(number);
        o.setOrigin(ClientOfferOrigin.FILE);
        o.setStatus(ClientOfferStatus.SENT);
        o.setSentAt(OffsetDateTime.now());
        o.setOfferDate(LocalDate.of(2026, 9, 24));
        o.setTenderId(t == null ? null : t.getId());
        ClientOfferItem it = ClientOfferTestData.item(o, 1, "Пульсоксиметр", "80000", "5");
        it.setPriceOverride(new BigDecimal("105600"));
        o.getItems().add(it);
        return offers.saveAndFlush(o);
    }

    /** Ответ GET КП как JSON — тело PUT (лишние поля сервер игнорирует, как в ClientOfferApiTest). */
    private JsonNode json(long id) throws Exception {
        return om.readTree(mvc.perform(get("/api/client-offers/" + id).header("X-Market", "KZ")
                        .with(user("admin").roles("ADMIN")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    @Test
    void putWithoutNumber_fileOk_builderRejected() throws Exception {
        ClientOffer file = fileOffer(null, null);
        ObjectNode body = json(file.getId()).deepCopy();
        body.putNull("number");
        mvc.perform(put("/api/client-offers/" + file.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(body.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.number", nullValue()));

        MarketContext.set(Market.KZ);   // перехватчик рынка чистит MarketContext после каждого запроса MockMvc
        ClientOffer built = service.create("admin");
        ObjectNode b2 = json(built.getId()).deepCopy();
        b2.putNull("number");
        mvc.perform(put("/api/client-offers/" + built.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content(b2.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Укажите номер КП"));
    }

    @Test
    void fileOfferDeletedInAnyStatus_itsEventsGone_builderSentStaysProtected() throws Exception {
        Tender t = requests.createEmpty();
        ClientOffer o = fileOffer(t, null);
        service.setStatus(o.getId(), ClientOfferStatus.ACCEPTED);
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).extracting(DealEvent::getText)
                .contains("КП от 24.09.2026: клиент согласился");

        mvc.perform(delete("/api/client-offers/" + o.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isNoContent());
        assertThat(offers.findById(o.getId())).isEmpty();
        assertThat(events.findByTenderIdOrderByOccurredAtAscIdAsc(t.getId())).noneMatch(e -> o.getId().equals(e.getRefId()));

        MarketContext.set(Market.KZ);
        ClientOffer built = service.create("admin");
        service.setStatus(built.getId(), ClientOfferStatus.SENT);
        mvc.perform(delete("/api/client-offers/" + built.getId()).header("X-Market", "KZ").with(user("admin").roles("ADMIN")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void fileOfferHasNoPdfWordPreview() throws Exception {
        ClientOffer o = fileOffer(null, 24);
        for (String path : new String[]{"pdf", "docx", "preview"}) {
            mvc.perform(get("/api/client-offers/" + o.getId() + "/" + path).header("X-Market", "KZ")
                            .with(user("op").roles("OPERATOR")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("КП из файла — скачайте файл"));
        }
    }

    @Test
    void duplicateOfFileOfferIsBuilderWithCounterNumberAndSamePrices() {
        ClientOffer o = fileOffer(null, null);
        int next = profiles.findByMarket(Market.KZ).orElseThrow().getNextNumber();
        ClientOffer copy = service.duplicate(o.getId(), "admin");
        assertThat(copy.getOrigin()).isEqualTo(ClientOfferOrigin.BUILDER);
        assertThat(copy.getNumber()).isEqualTo(next);
        assertThat(copy.getItems().get(0).getPriceOverride()).isEqualByComparingTo("105600");
    }

    @Test
    void setStatusWithTime_usesGivenTime_labelsWithoutNumber() {
        ClientOffer o = service.create("admin");
        OffsetDateTime at = OffsetDateTime.of(2026, 10, 15, 7, 30, 0, 0, ZoneOffset.UTC);
        assertThat(service.setStatus(o.getId(), ClientOfferStatus.SENT, at).getSentAt()).isEqualTo(at);

        ClientOffer file = fileOffer(null, null);
        assertThat(service.fileBaseName(file)).startsWith("КП от 24.09.2026");
    }
}
