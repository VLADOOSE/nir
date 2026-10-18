package com.vladoose.nir.lead;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.entity.LeadChannel;
import com.vladoose.nir.entity.LeadStatus;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.integration.lead.IncomingLead;
import com.vladoose.nir.integration.lead.LeadSources;
import com.vladoose.nir.repository.ClientOfferRepository;
import com.vladoose.nir.service.LeadIntakeService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Параметр limit у списков сделки — обращений, частных заявок и КП клиенту (ревью «Переходов между страницами»,
 * 2026-10-18): глобальный поиск показывает по 5 в группе и не тянет ради них по 300 полных строк. Без параметра —
 * как раньше; порядок — тот же, что у полного списка (новые сверху).
 */
@SpringBootTest
@Transactional
class DealListLimitApiTest {

    @Autowired WebApplicationContext wac;
    @Autowired ObjectMapper om;
    @Autowired LeadIntakeService intake;
    @Autowired ClientOfferRepository offers;
    MockMvc mvc;

    /** Метка: поиск по ней отрезает записи живой nirdb — в выдаче только записи теста. */
    private final String tag = "ZZ-лимит-" + System.nanoTime();

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        MarketContext.set(Market.KZ);
    }

    @AfterEach void clear() { MarketContext.clear(); }

    private static MockHttpServletRequestBuilder kz(MockHttpServletRequestBuilder b) {
        return b.header("X-Market", "KZ");
    }

    private List<Long> ids(String url) throws Exception {
        String body = mvc.perform(kz(get(url))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<Long> ids = new ArrayList<>();
        for (JsonNode n : om.readTree(body)) ids.add(n.get("id").asLong());
        return ids;
    }

    @Test
    @WithMockUser(roles = "OPERATOR")
    void leads() throws Exception {
        for (int h = 3; h >= 1; h--) {
            intake.ingest(new IncomingLead(LeadSources.MANUAL, null, LeadChannel.PHONE, "Звонок",
                    OffsetDateTime.now().minusHours(h), tag, null, null, null, "Нужен облучатель", List.of(),
                    LeadStatus.NEW, null, "t")).orElseThrow();
        }
        List<Long> all = ids("/api/leads?status=ALL&q=" + tag);
        assertThat(all).hasSize(3);
        assertThat(ids("/api/leads?status=ALL&q=" + tag + "&limit=2")).containsExactlyElementsOf(all.subList(0, 2));
        assertThat(ids("/api/leads?status=ACTIVE&q=" + tag + "&limit=1")).containsExactly(all.get(0));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void privateRequests() throws Exception {
        for (int i = 0; i < 3; i++) {
            String created = mvc.perform(kz(post("/api/private-requests"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            long id = om.readTree(created).get("id").asLong();
            mvc.perform(kz(patch("/api/private-requests/" + id)).contentType(MediaType.APPLICATION_JSON)
                    .content("{\"field\":\"CONTACT_NAME\",\"value\":\"" + tag + "\"}")).andExpect(status().isOk());
        }
        List<Long> all = ids("/api/private-requests?status=ALL&q=" + tag);
        assertThat(all).hasSize(3);
        assertThat(ids("/api/private-requests?status=ALL&q=" + tag + "&limit=2")).containsExactlyElementsOf(all.subList(0, 2));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void clientOffers() throws Exception {
        for (int i = 0; i < 3; i++) {
            String created = mvc.perform(kz(post("/api/client-offers"))).andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            offers.findById(om.readTree(created).get("id").asLong()).orElseThrow().setRecipient(tag);
        }
        offers.flush();
        List<Long> all = ids("/api/client-offers?status=ALL&q=" + tag);
        assertThat(all).hasSize(3);
        assertThat(ids("/api/client-offers?status=ALL&q=" + tag + "&limit=2")).containsExactlyElementsOf(all.subList(0, 2));
    }
}
