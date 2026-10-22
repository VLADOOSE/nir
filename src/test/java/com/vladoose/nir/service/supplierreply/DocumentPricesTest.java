package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.entity.TextPrice;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Цены в тексте КП-буклета без таблицы (§16 «Примеры КП оператора», находка 4): фразы настоящих КП — АМИКО «ПроСкан-2000»
 * (.doc, цены в ячейке), Шармстар «КП прессо» (PDF), «Элема-Н» (буклет на много позиций). Подсказка, не подстановка.
 */
class DocumentPricesTest {

    static List<String> amounts(List<TextPrice> p) {
        return p.stream().map(x -> x.amount().stripTrailingZeros().toPlainString() + " " + x.currency()).toList();
    }

    @Test
    void amiko_threePricesInOneCell_eachWithItsPhrase() {
        List<TextPrice> p = DocumentPrices.find(List.of("Стоимость оборудования со скидкой – 5 000 000 рублей. Стоимость "
                + "оборудования со скидкой (без АРМ врача) – 4 500 000 рублей. При заключении сделки возможно предоставление "
                + "дополнительной скидки. Ввод в эксплуатацию – 200 000 рублей. Доставка - оплачивается отдельно. Срок "
                + "изготовления - 60 дней"));
        assertThat(amounts(p)).containsExactly("5000000 RUB", "4500000 RUB", "200000 RUB");
        assertThat(p).extracting(TextPrice::text).containsExactly("Стоимость оборудования со скидкой – 5 000 000 рублей",
                "Стоимость оборудования со скидкой (без АРМ врача) – 4 500 000 рублей", "Ввод в эксплуатацию – 200 000 рублей");
    }

    @Test
    void charmstar_priceAfterDash_conditionsWithoutNumberSkipped() {
        List<TextPrice> p = DocumentPrices.find(List.of("Аппарат электрический, косметический – Charmstar Super Slim – 110 000 рублей.",
                "Условия приобретения– предоплата 100%. Цена указана при оплате переводом на карту,"));
        assertThat(amounts(p)).containsExactly("110000 RUB");
    }

    /** «Элема-Н»: цена за штуку, а не сумма строки; «Итого» и реквизиты — не цены. */
    @Test
    void elema_unitPriceFirst_totalsAndRequisitesSkipped() {
        List<TextPrice> p = DocumentPrices.find(List.of("Стоимость 1 935 000 тенге х4 шт.-7 740 000 тенге.",
                "Стоимость 1 830 000 тенгех 4 шт-7 320 000 тенге", "Итого на сумму : 122 165 000, 00 тенге",
                "KZ26 998R TB00 0147 3655 (тенге) KZ68 998R TB00 0147 3675 (рубли)"));
        assertThat(amounts(p)).containsExactly("1935000 KZT", "1830000 KZT");
    }

    /** Без валюты рядом и без «стоимость / цена» перед числом — не цена: годы, сроки, телефоны, модели, проценты. */
    @Test
    void noAnchor_noPrice() {
        assertThat(DocumentPrices.find(List.of("Срок поставки — до 15.12.2026, гарантия 24 месяца", "Год выпуска 2026 г.",
                "Тел.: 8 963 343 24 18", "Модель ТГ-1500 «Аксион»", "Скидка 15% при оплате до 2026 года",
                "Количество 2 000 шт."))).isEmpty();
    }

    /** Цена при слове «цена» без валюты — подсказка без валюты (валюту назовёт документ рядом или человек). */
    @Test
    void keywordAnchor_currencyUnknown() {
        assertThat(amounts(DocumentPrices.find(List.of("Цена за единицу: 345 000")))).containsExactly("345000 null");
    }

    @Test
    void sameAmountAndCurrency_once_capped() {
        List<String> lines = new java.util.ArrayList<>(List.of("Стоимость 1 390 000 тенге", "Стоимость 1 390 000 тенге"));
        for (int i = 1; i <= 15; i++) lines.add("Стоимость " + (100 + i) + " 000 тенге");
        List<TextPrice> p = DocumentPrices.find(lines);
        assertThat(p).hasSize(DocumentPrices.MAX);
        assertThat(amounts(p).get(0)).isEqualTo("1390000 KZT");
        assertThat(amounts(p).get(1)).isEqualTo("101000 KZT");
    }
}
