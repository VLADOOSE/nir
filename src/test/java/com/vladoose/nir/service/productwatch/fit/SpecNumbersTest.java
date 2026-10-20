package com.vladoose.nir.service.productwatch.fit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Числа требований и характеристик: условие, единица, диапазон, габариты (задача 2 плана 2026-10-20-product-spec-fit). */
class SpecNumbersTest {

    private static SpecNumbers.Hit one(String text) {
        List<SpecNumbers.Hit> hits = SpecNumbers.find(text);
        assertThat(hits).as(text).hasSize(1);
        return hits.get(0);
    }

    @Test
    void minMaxRangeUpto() {
        SpecNumbers.Hit min = one("производительность не менее 15 л/мин");
        assertThat(min.cmp()).isEqualTo(SpecCmp.MIN);
        assertThat(min.lo()).isEqualTo(15.0);
        assertThat(min.unit()).isEqualTo(SpecUnit.L_MIN);

        SpecNumbers.Hit max = one("масса не более 4,5 кг");
        assertThat(max.cmp()).isEqualTo(SpecCmp.MAX);
        assertThat(max.hi()).isEqualTo(4500.0);          // база массы — граммы
        assertThat(max.unit()).isEqualTo(SpecUnit.KG);

        SpecNumbers.Hit range = one("производительность отсасывания: примерно 15–30 л/мин");
        assertThat(range.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(range.lo()).isEqualTo(15.0);
        assertThat(range.hi()).isEqualTo(30.0);

        SpecNumbers.Hit upto = one("уровень вакуума: до 75 кпа");
        assertThat(upto.cmp()).isEqualTo(SpecCmp.UPTO);
        assertThat(upto.hi()).isEqualTo(75.0);
    }

    /** Review Focus #2: «1 000», «0,5», «220±22», «−80», «от 30 до 300». */
    @Test
    void oddNumbers() {
        assertThat(one("мощность 1 000 вт").lo()).isEqualTo(1000.0);
        assertThat(one("объем 0,5 л").lo()).isEqualTo(500.0);
        SpecNumbers.Hit pm = one("напряжение 220±22 в");
        assertThat(pm.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(pm.lo()).isEqualTo(198.0);
        assertThat(pm.hi()).isEqualTo(242.0);
        SpecNumbers.Hit neg = one("разрежение от −5 до −80 кпа");
        assertThat(neg.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(neg.lo()).isEqualTo(5.0);
        assertThat(neg.hi()).isEqualTo(80.0);
        SpecNumbers.Hit from = one("частота от 30 до 300 гц");
        assertThat(from.lo()).isEqualTo(30.0);
        assertThat(from.hi()).isEqualTo(300.0);
    }

    @Test
    void dimensionsSortedDescending() {
        SpecNumbers.Hit d = one("габаритные размеры не более 295х100х295 мм");
        assertThat(d.cmp()).isEqualTo(SpecCmp.MAX);
        assertThat(d.dims()).containsExactly(295.0, 295.0, 100.0);
        assertThat(d.unit()).isEqualTo(SpecUnit.MM);
    }

    @Test
    void bareNumberIsApprox_trailingCountIsCount() {
        SpecNumbers.Hit w = one("мощность лампы - 20 вт");
        assertThat(w.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(w.lo()).isEqualTo(20.0);
        SpecNumbers.Hit count = one("количество ламп - 1");
        assertThat(count.unit()).isNull();
        assertThat(count.lo()).isEqualTo(1.0);
    }

    /** «2025 г.» — не граммы; «г» без «р» / «рамм» единицей не считается. */
    @Test
    void yearIsNotGrams() {
        assertThat(SpecNumbers.find("год выпуска не ранее 2025 г.")).isEmpty();
    }

    /** Живые ТЗ (BOWA 17828560-1, Астана 17802320-1): предлоги «с» / «в» после числа — не секунды и не вольты. */
    @Test
    void prepositionsAreNotUnits() {
        assertThat(SpecNumbers.find("штекер типа cee 7 с защитным контактом")).isEmpty();
        assertThat(SpecNumbers.find("электроды 5 в комплекте")).isEmpty();
        assertThat(one("время выхода на режим 10 с").unit()).isEqualTo(SpecUnit.S);
        assertThat(one("питание 220 в, 50 гц".substring(0, 13)).unit()).isEqualTo(SpecUnit.V);
    }

    /** «исполнение 3 - 1шт» — исполнение и количество, а не диапазон 1–3 шт. */
    @Test
    void dashBeforeCountIsNotRange() {
        SpecNumbers.Hit one = one("исполнение 3 - 1шт");
        assertThat(one.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(one.lo()).isEqualTo(1.0);
        assertThat(one.unit()).isEqualTo(SpecUnit.PCS);
        SpecNumbers.Hit three = one("наконечник аспирационный, исполнение 2 - 3шт");
        assertThat(three.lo()).isEqualTo(3.0);
        assertThat(three.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(one("производительность 15-30 л/мин").cmp()).isEqualTo(SpecCmp.RANGE);
    }

    /** Шаблон портала «не менее и не более X» — ровно X, а не «не более». */
    @Test
    void notLessAndNotMoreIsExact() {
        SpecNumbers.Hit w = one("ширина: не менее и не более - 430 мм");
        assertThat(w.cmp()).isEqualTo(SpecCmp.APPROX);
        assertThat(w.lo()).isEqualTo(430.0);
        assertThat(w.hi()).isEqualTo(430.0);
    }

    /** «см²», «мм²» — площадь, а не длина; слова «метров», «литров» — единицы. */
    @Test
    void areaAndWordUnits() {
        SpecNumbers.Hit area = one("площадь рабочей поверхности 375 см²");
        assertThat(area.unit()).isEqualTo(SpecUnit.CM2);
        assertThat(area.unit().family()).isEqualTo(SpecUnit.M2.family());
        assertThat(one("сечение 1,5 мм2").unit()).isEqualTo(SpecUnit.MM2);
        assertThat(one("длина кабеля 5 метров").lo()).isEqualTo(5000.0);
        assertThat(one("емкость 2 литра").lo()).isEqualTo(2000.0);
    }

    /** «50 мм/с», «10 мм/мВ» — скорость и чувствительность, а не длина; «л/мин» — своя единица. */
    @Test
    void compoundUnitsAreNotLength() {
        assertThat(SpecNumbers.find("скорость записи 50 мм/с")).isEmpty();
        assertThat(SpecNumbers.find("чувствительность 10 мм/мв")).isEmpty();
        assertThat(one("расход 15 л/мин").unit()).isEqualTo(SpecUnit.L_MIN);
    }

    /** «220~240 В» — диапазон; «ОБН 2×30» (модель) — не габариты: без единицы габариты — только из трёх чисел; «м³» — не метры. */
    @Test
    void tildeRange_twoNumbersWithoutUnit_cubicMeters() {
        SpecNumbers.Hit v = one("напряжение сети 220~240 в");
        assertThat(v.cmp()).isEqualTo(SpecCmp.RANGE);
        assertThat(v.lo()).isEqualTo(220.0);
        assertThat(v.hi()).isEqualTo(240.0);
        assertThat(SpecNumbers.find("облучатель бактерицидный обн 2×30")).isEmpty();
        assertThat(one("габариты 600х400х300").dims()).containsExactly(600.0, 400.0, 300.0);
        assertThat(SpecNumbers.find("объем камеры 65 м³")).isEmpty();
    }

    /** Карточка РУ НЦЭЛС пишет габариты через «/» («295/295/100 мм»); «50/60 Гц» и дата «12/05/2026» — не габариты. */
    @Test
    void slashDimensions() {
        SpecNumbers.Hit d = one("габаритные размеры блока (не более): 295/295/100 мм");
        assertThat(d.dims()).containsExactly(295.0, 295.0, 100.0);
        assertThat(d.cmp()).isEqualTo(SpecCmp.MAX);
        assertThat(SpecNumbers.find("частота 50/60 гц")).allSatisfy(h -> assertThat(h.dims()).isEmpty());
        assertThat(SpecNumbers.find("дата 12/05/2026")).isEmpty();
    }
}
