package com.vladoose.nir.integration.goszakup;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class RegionResolverTest {

    final RegionResolver r = new RegionResolver();

    @Test void detectsCityFromCustomerName() {
        assertThat(r.resolve("ГКП на ПХВ «Городская поликлиника №5 г. Алматы»")).isEqualTo("г. Алматы");
    }
    @Test void detectsOblast() {
        assertThat(r.resolve("ГУ Управление здравоохранения Акмолинской области")).isEqualTo("Акмолинская область");
    }
    @Test void oblastNotConfusedWithCity() {
        assertThat(r.resolve("Больница Алматинской области, г. Талдыкорган")).isEqualTo("Алматинская область");
    }
    @Test void abbreviationVKO() {
        assertThat(r.resolve("Поликлиника ВКО")).isEqualTo("Восточно-Казахстанская область");
    }
    @Test void astanaVariants() {
        assertThat(r.resolve("Больница г. Нур-Султан")).isEqualTo("г. Астана");
    }
    @Test void scansMultipleCandidatesAndReturnsNullWhenNone() {
        assertThat(r.resolve(null, "", "что-то без региона")).isNull();
        assertThat(r.resolve(null, "г. Шымкент")).isEqualTo("г. Шымкент");
    }
    @Test void abbreviationNotMatchedInsideWord() {
        assertThat(r.resolve("Республиканское государственное предприятие «Городская больница»")).isNull();
        assertThat(r.resolve("Поликлиника, СКО")).isEqualTo("Северо-Казахстанская область");
    }
    @Test void oblastBeforeCity_whenBothPresent() {
        assertThat(r.resolve("Управление здравоохранения Алматинской области, г. Алматы")).isEqualTo("Алматинская область");
    }
}
