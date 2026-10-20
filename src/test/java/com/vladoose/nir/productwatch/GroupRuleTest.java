package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductGroup;
import com.vladoose.nir.service.productwatch.GroupRule;
import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Группа на лоте (спека §5.2): слово, уточнения, исключения, название объявления. */
class GroupRuleTest {

    private static GroupRule rule(long id) {
        return GroupRule.of(ProductFixtures.groups().stream().filter(g -> g.getId() == id).findFirst().orElseThrow());
    }

    private static String type(long groupId, String lot, String announcement, boolean single) {
        return rule(groupId).typeWord(ProductText.normalize(lot), ProductText.normalize(announcement), single);
    }

    @Test
    void lamps_requireAndExclude() {
        assertThat(type(8, "Светильник операционный потолочный", "", true)).isEqualTo("светильник");
        assertThat(type(8, "Светильник уличный светодиодный медицинского учреждения", "", true)).isNull();
        assertThat(type(8, "Светильник светодиодный", "Ремонт кабинета", true)).as("нет уточнения").isNull();
    }

    @Test
    void electrodes_weldingAndEcgOut_neutralIn() {
        assertThat(type(13, "Электрод сварочный", "", true)).isNull();
        assertThat(type(13, "Электрод нейтральный (возвратный) одноразовый", "", true)).isEqualTo("электрод");
        assertThat(type(13, "Электроды для ЭКГ одноразовые", "", true)).isNull();
    }

    @Test
    void dosersAndMonitors() {
        assertThat(type(24, "Дозатор лабораторный пипеточный", "", true)).isNull();
        assertThat(type(24, "Дозатор шприцевой", "", true)).isEqualTo("дозатор");
        assertThat(type(23, "Монитор компьютерный, диагональ 24", "", true)).isNull();
        assertThat(type(23, "Монитор пациента прикроватный", "", true)).isEqualTo("монитор");
    }

    @Test
    void announcementNameCountsOnlyForSingleLot() {
        assertThat(type(10, "Отсасыватель", "Аппарат для аспирации дыма", true)).isEqualTo("отсасыватель");
        assertThat(type(10, "Насадка", "Аппарат для аспирации дыма", true)).as("одно-лотовое: слово в объявлении").isEqualTo("аспирации");
        assertThat(type(10, "Насадка", "Аппарат для аспирации дыма и прочее", false)).as("много лотов").isNull();
    }

    @Test
    void caseYoAndLatinLookalikes() {   // Review Focus #2
        assertThat(type(5, "ОБЛУЧАТЕЛЬ бактерицидный", "", true)).isEqualTo("облучатель");
        assertThat(type(5, "Облучатeль бактерицидный", "", true)).as("латинская e").isEqualTo("облучатель");
        assertThat(type(2, "ЭЛЕКТРОКАРДИОГРАФ 12-канальный", "", true)).isEqualTo("электрокардиограф");
    }
}
