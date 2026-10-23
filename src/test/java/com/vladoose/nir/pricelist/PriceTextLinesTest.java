package com.vladoose.nir.pricelist;

import com.vladoose.nir.service.pricelist.PriceTextLines;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PriceTextLinesTest {

    @Test
    void dotLeaderLines_sections_text() {
        PriceTextLines.Result r = PriceTextLines.read(List.of(
                "ООО «ZZ-Си»", "115419, г. Москва, ул. Орджоникидзе, дом 11", "тел.: +7 (495) 222-22-74",
                "РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ:",
                "  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.",
                "    Стерилизатор озоновый «ZZ» без камеры (генератор озона)………………..   475 000 руб.",
                "Ресурс работы рассчитан на 1 год."));
        assertThat(r.rows()).hasSize(2);
        assertThat(r.rows().get(0).name()).isEqualTo("Стерилизатор озоновый «ZZ» СК – 16 л");
        assertThat(r.rows().get(0).prices().get(0)).isEqualByComparingTo("957000");
        assertThat(r.rows().get(0).section()).isEqualTo("СТЕРИЛИЗАТОРЫ ОЗОНОВЫЕ «ZZ» - МНОГОФУНКЦИОНАЛЬНЫЕ");
        assertThat(r.rows().get(1).name()).isEqualTo("Стерилизатор озоновый «ZZ» без камеры (генератор озона)");
        assertThat(r.currency()).isEqualTo("RUB");
        assertThat(r.text()).contains("РЕКОМЕНДОВАННЫЕ РОЗНИЧНЫЕ ЦЕНЫ", "Ресурс работы рассчитан на 1 год.")
                .doesNotContain("  1.  Стерилизатор озоновый «ZZ» СК – 16 л ……………………...…..……....      957 000 руб.");
    }
}
