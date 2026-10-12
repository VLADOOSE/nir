package com.vladoose.nir.privaterequest;

import com.vladoose.nir.service.privaterequest.PrivateRequestRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Код модели позиции должен встречаться в записи реестра — иначе это другой аппарат того же бренда
 * (калибровка 2026-10-12: «BeneHeart R12» → «BeneHeart R900, R700…» со скором 0.963).
 */
class PrivateRequestRegistryModelCodeTest {

    @Test
    void sameModelWrittenDifferentlyMatches() {
        assertThat(PrivateRequestRegistry.mentionsModel("Светильник медицинский ЭМАЛЕД 100П",
                "Светильник передвижной «ЭМАЛЕД» в вариантах исполнения «ЭМАЛЕД 100 П», «ЭМАЛЕД 200 П»")).isTrue();
        assertThat(PrivateRequestRegistry.mentionsModel("Спирограф СМП-02-Р-Д",
                "Спирограф микропроцессорный СМП-02-«Р-Д» в вариантах исполнения")).isTrue();
        assertThat(PrivateRequestRegistry.mentionsModel("Электрокардиограф ЭК3ТЦ-3/6-04",
                "Электрокардиограф 3-6 канальный ЭК3ТЦ-3/6-04 \"АКСИОН\"")).isTrue();
        // латиница и кириллица одного начертания: «ВМ-25» набрано латинскими B и M
        assertThat(PrivateRequestRegistry.mentionsModel("Облучатель BM-25", "Облучатель ВМ-25 настенный")).isTrue();
    }

    @Test
    void otherModelOfSameFamilyDoesNotMatch() {
        assertThat(PrivateRequestRegistry.mentionsModel("Электрокардиограф BeneHeart R12",
                "Электрокардиограф BeneHeart R900, BeneHeart R90, BeneHeart R700, BeneHeart R70")).isFalse();
        assertThat(PrivateRequestRegistry.mentionsModel("Монитор пациента iMEC10",
                "Монитор пациента uMEC 30, uMEC 60, uMEC 70")).isFalse();
        assertThat(PrivateRequestRegistry.mentionsModel("Рециркулятор бактерицидный настенный РБм 3Х15",
                "Рециркулятор бактерицидный в двух исполнениях: настенный РБ 2х15 и передвижной РБПе 6х15")).isFalse();
    }

    @Test
    void lineWithoutModelCodeIsNotRestricted() {
        assertThat(PrivateRequestRegistry.mentionsModel("Стерилизатор озоновый Орион",
                "Стерилизатор озоновый \"Орион\" по ТУ 9451-001-52447360-2005")).isTrue();
        assertThat(PrivateRequestRegistry.mentionsModel("Кушетка медицинская", "Кушетка медицинская ТВ-КРП")).isTrue();
    }
}
