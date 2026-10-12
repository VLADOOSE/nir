package com.vladoose.nir.sourcing;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.controller.DistributorController;
import com.vladoose.nir.dto.request.DistributorRequest;
import com.vladoose.nir.dto.response.DistributorResponse;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** «Доверенный» и «Производитель» в карточке поставщика, несколько адресов почты (спека 2026-10-12 §3.2, §6.2). */
@SpringBootTest
@Transactional
@WithMockUser(roles = "ADMIN")
class DistributorFlagsTest {

    @Autowired DistributorController controller;

    @BeforeEach void kz() { MarketContext.set(Market.KZ); }
    @AfterEach void clear() { MarketContext.clear(); }

    private static DistributorRequest request(String name) {
        DistributorRequest r = new DistributorRequest();
        r.setName(name);
        return r;
    }

    @Test
    void flagsRoundTrip_notSentFlagIsKept_emailsNormalized() {
        DistributorRequest r = request("ZZ Флаги " + System.nanoTime());
        r.setTrusted(true);
        r.setManufacturer(true);
        r.setEmail(" sales7@ema.su; sales9@ema.su ");

        DistributorResponse created = controller.create(r);

        assertThat(created.isTrusted()).isTrue();
        assertThat(created.isManufacturer()).isTrue();
        assertThat(created.getEmail()).isEqualTo("sales7@ema.su, sales9@ema.su");

        DistributorRequest u = request(r.getName());
        u.setTrusted(false);                       // «производитель» и почта не присланы — не меняются
        DistributorResponse updated = controller.update(created.getId(), u);

        assertThat(updated.isTrusted()).isFalse();
        assertThat(updated.isManufacturer()).isTrue();
        assertThat(updated.getEmail()).isEqualTo("sales7@ema.su, sales9@ema.su");
    }

    @Test
    void newSupplierWithoutFlagsIsNeither() {
        DistributorResponse created = controller.create(request("ZZ Без флагов " + System.nanoTime()));
        assertThat(created.isTrusted()).isFalse();
        assertThat(created.isManufacturer()).isFalse();
    }

    @Test
    void invalidEmailIs400WithTheAddress() {
        DistributorRequest r = request("ZZ Кривая почта " + System.nanoTime());
        r.setEmail("ok@a.kz, broken@kz");
        assertThatThrownBy(() -> controller.create(r))
                .isInstanceOf(BadRequestException.class).hasMessageContaining("broken@kz");
    }

    @Test
    void blankEmailClearsTheCard() {
        DistributorRequest r = request("ZZ Очистка " + System.nanoTime());
        r.setEmail("a@zz.kz");
        DistributorResponse created = controller.create(r);
        DistributorRequest u = request(r.getName());
        u.setEmail("   ");
        assertThat(controller.update(created.getId(), u).getEmail()).isEmpty();
    }
}
