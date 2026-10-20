package com.vladoose.nir.productwatch;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.TenderSearchController;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Пути «Найти тендеры» (спека 2026-10-19-product-watch §13): «Завести в АИС» — только админ; номер и рынок проверяются до площадки. */
@SpringBootTest
@Transactional
class TenderSearchControllerTest {

    @Autowired TenderSearchController controller;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    @Test
    @WithMockUser(roles = "USER")
    void operatorCannotImport() {
        assertThatThrownBy(() -> controller.importAnnouncement("17802320-1")).isInstanceOf(AccessDeniedException.class);
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void badNumberOrOtherMarket_400BeforeUpstream() {
        assertThatThrownBy(() -> controller.result("17802320-1; drop")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Неверный номер объявления");
        MarketContext.set(Market.RF);
        assertThatThrownBy(() -> controller.importAnnouncement("17802320-1")).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Только для Казахстана");
    }
}
