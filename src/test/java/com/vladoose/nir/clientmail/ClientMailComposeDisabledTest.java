package com.vladoose.nir.clientmail;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ClientMailController;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.entity.Tender;
import com.vladoose.nir.service.ClientMailComposeService;
import com.vladoose.nir.service.PrivateRequestService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Обычный контекст — SMTP info@ не задан: окно открывается с причиной, «Отправить» нет (спека §4). */
@SpringBootTest
@Transactional
class ClientMailComposeDisabledTest {

    @Autowired ClientMailComposeService compose;
    @Autowired ClientMailController controller;
    @Autowired PrivateRequestService requests;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    void notConfigured_reason() {
        Tender t = requests.createEmpty();
        var r = compose.compose(null, null, t.getId(), null, "admin");
        assertThat(r.enabled()).isFalse();
        assertThat(r.reason()).isEqualTo("Отправка писем клиентам не настроена");
    }

    @Test
    @WithMockUser(roles = "USER")
    void operator_403() {
        assertThatThrownBy(() -> controller.compose(null, null, 1L, null)).isInstanceOf(AccessDeniedException.class);
    }
}
