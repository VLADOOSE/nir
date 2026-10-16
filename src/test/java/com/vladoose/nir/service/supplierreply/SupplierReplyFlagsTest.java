package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.PriceRequestItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/** Пометки позиции на экране «Ответ поставщика» о валюте строки файла. */
class SupplierReplyFlagsTest {

    static ReplyRowValues row(String currency) {
        return new ReplyRowValues(5, "Скальпель", null, null, null, null, null, new BigDecimal("100"), false, false, null, null, currency, null);
    }

    /** Валюта строки без курса — по-русски после «в»: «в руб.», «в юанях» (не «валюта: юанях»). */
    @Test
    void foreignRowWithoutRate_currencyInRussian() {
        PriceRequestItem it = PriceRequestItem.builder().responsePrice(new BigDecimal("8500")).build();
        assertThat(SupplierReplyViewBuilder.flags(it, null, row("CNY"), 1.0, "KZT", null)).containsExactly("цена файла в юанях");
        assertThat(SupplierReplyViewBuilder.flags(it, null, row("RUB"), 1.0, "KZT", null)).containsExactly("цена файла в руб.");
    }

    /** Цена позиции = пересчёт строки по курсу — без пометки; поправлена руками — «цена не по курсу». */
    @Test
    void convertedRow_noFlag_handEdited_notByRate() {
        SupplierReplyService.FileRate rate = new SupplierReplyService.FileRate("RUB", new BigDecimal("6"));
        PriceRequestItem same = PriceRequestItem.builder().responsePrice(new BigDecimal("600")).build();
        assertThat(SupplierReplyViewBuilder.flags(same, null, row("RUB"), 1.0, "KZT", rate)).isEmpty();
        PriceRequestItem edited = PriceRequestItem.builder().responsePrice(new BigDecimal("590")).build();
        assertThat(SupplierReplyViewBuilder.flags(edited, null, row("RUB"), 1.0, "KZT", rate))
                .singleElement().asString().startsWith("цена не по курсу 6: по строке файла — 600,00").endsWith(" тг");
    }
}
