package com.vladoose.nir.deal;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.DealController;
import com.vladoose.nir.dto.request.DealEventRequest;
import com.vladoose.nir.dto.response.DealTimelineItem;
import com.vladoose.nir.entity.DealEventType;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Пути ленты и событий заявки (спека 2026-10-18 §6): права, гард рынка и вида, курсор. */
@SpringBootTest
@Transactional
class DealControllerTest {

    @Autowired DealController controller;
    @Autowired PrivateRequestService requests;
    @Autowired WebApplicationContext wac;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(roles = "USER")
    void operatorReadsButCannotWrite() {
        Tender t = requests.createEmpty();
        assertThat(controller.privateTimeline(t.getId(), null, 50).items()).isNotEmpty();
        assertThatThrownBy(() -> controller.privateEvent(t.getId(), new DealEventRequest(DealEventType.NOTE, "x")))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(username = "vlad", roles = "ADMIN")
    void adminAddsCallAndSeesIt() {
        Tender t = requests.createEmpty();
        DealTimelineItem i = controller.privateEvent(t.getId(), new DealEventRequest(DealEventType.CALL, "перезвонить в 15:00"));
        assertThat(i.kind()).isEqualTo("CALL");
        assertThat(i.author()).isEqualTo("vlad");
        assertThat(controller.privateTimeline(t.getId(), null, 50).items()).extracting(DealTimelineItem::text)
                .contains("перезвонить в 15:00");
        assertThatThrownBy(() -> controller.privateEvent(t.getId(), new DealEventRequest(DealEventType.NOTE, " ")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void otherMarketIs404() {
        Tender t = requests.createEmpty();
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.privateTimeline(t.getId(), null, 50)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> controller.privateEvent(t.getId(), new DealEventRequest(DealEventType.NOTE, "x")))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void beforeAcceptsUtcAndOffset() throws Exception {
        Tender t = requests.createEmpty();
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(wac).apply(springSecurity()).build();
        mvc.perform(get("/api/private-requests/{id}/timeline", t.getId()).param("before", "2099-01-01T00:00:00Z")
                        .header("X-Market", "KZ").with(user("op").roles("USER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].kind").value("CREATED"));
        mvc.perform(get("/api/private-requests/{id}/timeline", t.getId()).param("before", "2099-01-01T05:00:00+05:00")
                        .header("X-Market", "KZ").with(user("op").roles("USER")))
                .andExpect(status().isOk());
    }
}
