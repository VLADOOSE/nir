package com.vladoose.nir.clientoffer;

import com.vladoose.nir.util.AmountInWords;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class AmountInWordsTest {

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "0          | KZT | Ноль тенге 00 тиын",
            "1          | RUB | Один рубль 00 копеек",
            "2          | RUB | Два рубля 00 копеек",
            "5          | RUB | Пять рублей 00 копеек",
            "11         | RUB | Одиннадцать рублей 00 копеек",
            "14         | RUB | Четырнадцать рублей 00 копеек",
            "21         | RUB | Двадцать один рубль 00 копеек",
            "101.01     | RUB | Сто один рубль 01 копейка",
            "112.14     | RUB | Сто двенадцать рублей 14 копеек",
            "0.02       | RUB | Ноль рублей 02 копейки",
            "1000       | RUB | Одна тысяча рублей 00 копеек",
            "1001.22    | RUB | Одна тысяча один рубль 22 копейки",
            "2000       | KZT | Две тысячи тенге 00 тиын",
            "5000       | KZT | Пять тысяч тенге 00 тиын",
            "21000      | RUB | Двадцать одна тысяча рублей 00 копеек",
            "1000000    | KZT | Один миллион тенге 00 тиын",
            "2721000    | KZT | Два миллиона семьсот двадцать одна тысяча тенге 00 тиын",
            "748060     | KZT | Семьсот сорок восемь тысяч шестьдесят тенге 00 тиын",
            "9902248.23 | KZT | Девять миллионов девятьсот две тысячи двести сорок восемь тенге 23 тиын",
    })
    void spellsAmount(String amount, String currency, String expected) {
        assertThat(AmountInWords.of(new BigDecimal(amount), currency)).isEqualTo(expected);
    }
}
