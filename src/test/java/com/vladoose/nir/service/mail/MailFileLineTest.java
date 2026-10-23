package com.vladoose.nir.service.mail;

import com.vladoose.nir.entity.TextPrice;
import com.vladoose.nir.service.supplierreply.SupplierReplyService.ReplyOutcome;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Строка о файле в уведомлении «Ответ поставщика»: КП-буклет без таблицы — цены из текста подсказкой (находка 4). */
class MailFileLineTest {

    static ReplyOutcome booklet(List<TextPrice> prices) {
        return new ReplyOutcome(List.of(1L), "ПроСкан-2000.docx", null, 0, 1, 0, null, false, false, "ПроСкан-2000.docx", false, null,
                "ПроСкан-2000.docx", prices);
    }

    static TextPrice p(String amount, String currency) { return new TextPrice(new BigDecimal(amount), currency, "Стоимость"); }

    @Test
    void bookletTextPrices_insteadOfUnreadable() {
        assertThat(MailIngestWriter.fileLine(booklet(List.of(p("5000000", "RUB"), p("200000", "RUB")))))
                .isEqualTo("📄 Таблицы в файле «ПроСкан-2000.docx» нет, в тексте цены: 5 000 000 руб., "
                        + "200 000 руб. — внесите цену по документу");
        assertThat(MailIngestWriter.fileLine(booklet(List.of(p("1", "KZT"), p("2", "KZT"), p("3", "KZT"), p("4", "KZT")))))
                .contains("и ещё 1");
        assertThat(MailIngestWriter.fileLine(booklet(List.of()))).isEqualTo("Файл «ПроСкан-2000.docx» не разобран — введите цены вручную");
    }

    @Test
    void ourRate_withToday() {
        var o = new ReplyOutcome(List.of(1L), "КП руб.xlsx", "КП руб.xlsx", 3, 3, 0, null, false, false, null, false,
                new BigDecimal("6"), null, List.of(), true, new BigDecimal("5.48"));
        assertThat(MailIngestWriter.fileLine(o)).isEqualTo("💡 Цены из файла «КП руб.xlsx»: подставлено 3 из 3 по нашему курсу 6 (сегодня 5,48) — сверьте");
    }
}
