package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.ProductWatchController;
import com.vladoose.nir.dto.request.ProductGroupsRequest;
import com.vladoose.nir.dto.response.ProductGroupDto;
import com.vladoose.nir.entity.Market;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Пути «Нашей продукции» (спека 2026-10-19-product-watch §13): читать — любой вошедший, писать и запускать — админ. */
@SpringBootTest
@Transactional
class ProductWatchControllerTest {

    @Autowired ProductWatchController controller;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(roles = "USER")
    void operatorReadsButCannotWriteOrRun() {
        assertThat(controller.get().kz()).isTrue();
        assertThat(controller.status()).isNotNull();
        ProductGroupDto g = new ProductGroupDto(null, "ЭКГ", List.of("электрокардиограф"), List.of(), List.of(), List.of(), true, false);
        assertThatThrownBy(() -> controller.save(new ProductGroupsRequest(List.of(g)))).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.probe(g)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.run()).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> controller.syncCatalog()).isInstanceOf(AccessDeniedException.class);
    }

    /** Блок выключен (по умолчанию) — «Искать сейчас» ничего не запускает, ответ говорит «выключено». */
    @Test
    @WithMockUser(roles = "ADMIN")
    void disabledBlock_runReportsDisabled() {
        assertThat(controller.run().enabled()).isFalse();
        assertThat(controller.syncCatalog().enabled()).isFalse();
    }
}
