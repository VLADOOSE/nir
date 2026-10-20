package com.vladoose.nir.productwatch;

import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ProductTextTest {

    @Test
    void normalize_caseYoLookalikesOnlyInsideCyrillicWords() {
        assertThat(ProductText.normalize("ОБЛУЧАТЕЛЬ Облучатeль")).isEqualTo("облучатель облучатель");   // латинская e
        assertThat(ProductText.normalize("Ёмкость")).isEqualTo("емкость");
        assertThat(ProductText.normalize("ACTITON SpO2")).isEqualTo("actiton spo2");                     // чистая латиница — как есть
        assertThat(ProductText.normalize("ЭХBЧ-350")).isEqualTo("эхвч-350");                               // латинская B внутри кириллицы
    }

    @Test
    void searchStem_dropsVowelTailKeepsFive() {
        assertThat(ProductText.searchStem("облучатель")).isEqualTo("облучател");
        assertThat(ProductText.searchStem("аспирации")).isEqualTo("аспирац");
        assertThat(ProductText.searchStem("электрокардиограф")).isEqualTo("электрокардиограф");
        assertThat(ProductText.searchStem("стойка")).isEqualTo("стойк");
        assertThat(ProductText.searchStem("холтер")).isEqualTo("холтер");
    }

    @Test
    void prefixesAndSignificant() {
        assertThat(ProductText.prefixes("Держатель шланга аспирационного")).containsExactly("держа", "шланг", "аспир");
        assertThat(ProductText.significant("для")).isFalse();
        assertThat(ProductText.significant("150")).isFalse();
        assertThat(ProductText.significant("блок")).isTrue();
    }
}
