package com.vladoose.nir.pricelist;

import com.vladoose.nir.service.pricelist.PriceListCodes;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PriceListCodesTest {

    @Test
    void codesAndKeys() {
        assertThat(PriceListCodes.codes("Дефибриллятор ДКИ-Н-12. Наилучший вариант")).containsExactly("ДКИ-Н-12");
        assertThat(PriceListCodes.key("ДКИ-Н-12")).isEqualTo("дкин12");
        assertThat(PriceListCodes.codes("Стерилизатор озоновый «Орион» СК – 16 л")).contains("СК-16");   // склейка «СК – 16»
        assertThat(PriceListCodes.codes("Дозатор шприцевый Д01")).isEmpty();                            // одна буква — не код
        assertThat(PriceListCodes.codes("Отсасыватель ОМХ-5/80-01 (без стойки)")).containsExactly("ОМХ-5/80-01");
        // латинские двойники и разделители не важны: «OMX 5/80-01» латиницей = «ОМХ-5/80-01»
        assertThat(PriceListCodes.key("OMX 5/80-01")).isEqualTo(PriceListCodes.key("ОМХ-5/80-01"));
        assertThat(PriceListCodes.keys("Дефибриллятор ДКИ-Н-12 Аксион, сумка СК-16")).containsExactlyInAnyOrder("дкин12", "ск16");
    }
}
