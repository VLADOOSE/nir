package com.vladoose.nir.rates;

import com.vladoose.nir.exception.UpstreamException;
import com.vladoose.nir.integration.nbrk.NbrkRate;
import com.vladoose.nir.integration.nbrk.NbrkRatesParser;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NbrkRatesParserTest {

    static final String XML = """
            <?xml version="1.0" encoding="utf-8"?>
            <rates>
                <generator>Alternate RSS Builder</generator>
                <title>Official exchange rates of National Bank of Republic Kazakhstan</title>
                <date>22.10.2026</date>
                <item><fullname>АРМЯНСКИЙ ДРАМ</fullname><title>AMD</title><description>12.5</description><quant>10</quant><index>DOWN</index><change>-0.05</change></item>
                <item><fullname>РОССИЙСКИЙ РУБЛЬ</fullname><title>RUB</title><description>5.48</description><quant>1</quant><index>UP</index><change>+0.01</change></item>
                <item><fullname>ДОЛЛАР США</fullname><title>USD</title><description>448.61</description><quant>1</quant></item>
                <item><fullname>ЕВРО</fullname><title>EUR</title><description>503.83</description><quant>1</quant></item>
                <item><fullname>ЮАНЬ ЖЭНЬМИНЬБИ</fullname><title>CNY</title><description>67.2</description><quant>1</quant></item>
                <item><title>XXX</title><description>не число</description><quant>1</quant></item>
            </rates>
            """;

    @Test
    void parse_perUnit() {
        List<NbrkRate> r = NbrkRatesParser.parse(XML.getBytes(StandardCharsets.UTF_8));
        assertThat(r).extracting(NbrkRate::currency).containsExactly("AMD", "RUB", "USD", "EUR", "CNY");
        assertThat(r.get(0).rate()).isEqualByComparingTo("1.25");          // 12,5 за 10 драм
        assertThat(r.get(1).rate()).isEqualByComparingTo("5.48");
    }

    @Test
    void garbage_isUpstreamError() {
        assertThatThrownBy(() -> NbrkRatesParser.parse("<html>Сервис недоступен".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("Нацбанк РК");
        assertThatThrownBy(() -> NbrkRatesParser.parse("<rates></rates>".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpstreamException.class).hasMessageContaining("курсов нет");
    }

    @Test
    void doctype_isRejected() {
        String xxe = "<?xml version=\"1.0\"?><!DOCTYPE r [<!ENTITY x SYSTEM \"file:///etc/passwd\">]><rates><item><title>RUB</title>"
                + "<description>&x;</description><quant>1</quant></item></rates>";
        assertThatThrownBy(() -> NbrkRatesParser.parse(xxe.getBytes(StandardCharsets.UTF_8))).isInstanceOf(UpstreamException.class);
        // DOCTYPE запрещён сам по себе, а не только внешние сущности: внутренняя сущность иначе дала бы курс 5,48
        String internal = "<?xml version=\"1.0\"?><!DOCTYPE rates [<!ENTITY x \"5.48\">]><rates><item><title>RUB</title>"
                + "<description>&x;</description><quant>1</quant></item></rates>";
        assertThatThrownBy(() -> NbrkRatesParser.parse(internal.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(UpstreamException.class).hasMessage("Нацбанк РК: ответ не разобран");
    }
}
