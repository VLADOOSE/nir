package com.vladoose.nir.pricelist;

import com.vladoose.nir.service.pricelist.PriceListRegs;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** № РУ из текста прайса: настоящий PDF Аксиона пишет «No» вместо «№» (шрифт PDF). */
class PriceListRegsTest {

    @Test
    void numberForms_andNone() {
        assertThat(PriceListRegs.number("РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК-МТ-5No017589 (бессрочно), Зарегистрирован в реестре"))
                .isEqualTo("РК-МТ-5№017589");
        assertThat(PriceListRegs.number("РЕГИСТРАЦИОННОЕ УДОСТОВЕРЕНИЕ РК МИ (МТ)-0No024573 (бессрочно)")).isEqualTo("РК МИ (МТ)-0№024573");
        assertThat(PriceListRegs.number("РК МИ (МТ)-0№029792 (бессрочно)")).isEqualTo("РК МИ (МТ)-0№029792");
        assertThat(PriceListRegs.number("Нет РУ в Казахстане")).isNull();
        assertThat(PriceListRegs.none("Нет РУ в Казахстане")).isTrue();
        assertThat(PriceListRegs.none("Сертификаты не имеются, поскольку данный товар не является медицинским оборудованием")).isTrue();
        assertThat(PriceListRegs.none("Нет сертификатов")).isTrue();
        assertThat(PriceListRegs.none("РК-МТ-5№021045")).isFalse();
    }
}
