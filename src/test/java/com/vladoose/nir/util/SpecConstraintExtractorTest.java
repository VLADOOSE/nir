package com.vladoose.nir.util;

import com.vladoose.nir.util.SpecConstraintExtractor.SpecConstraints;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class SpecConstraintExtractorTest {

    @Test
    void tripleMmWithNotMore() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Аппарат УЗИ. Габаритные размеры: не более 1200х800х1300 мм, питание 220 В.");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void tripleCmConverted() {
        SpecConstraints c = SpecConstraintExtractor.extract("Размеры 120 x 80 x 130 см");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void tripleMetersDecimalCommaAndCross() {
        SpecConstraints c = SpecConstraintExtractor.extract("габариты до 1,2×0,8×1,3 м");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1300);
    }

    @Test
    void starSeparator() {
        SpecConstraints c = SpecConstraintExtractor.extract("размер 1200*800*1300 мм");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
    }

    @Test
    void weightKg() {
        SpecConstraints c = SpecConstraintExtractor.extract("Вес не более 45 кг");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("45"));
    }

    @Test
    void weightKgWithoutQualifier() {
        SpecConstraints c = SpecConstraintExtractor.extract("Масса: 45,5 кг");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("45.5"));
    }

    @Test
    void weightGramsConverted() {
        SpecConstraints c = SpecConstraintExtractor.extract("масса до 4500 г");
        assertThat(c.maxWeightKg()).isEqualByComparingTo(new BigDecimal("4.50"));
    }

    @Test
    void lowerBoundsIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Размеры не менее 500х400х300 мм. Вес не менее 5 кг.");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void bareTripleWithoutKeywordIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract("В комплекте кабель 1200х800х1300 мм");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void garbageAndNullSafe() {
        assertThat(SpecConstraintExtractor.extract("Класс безопасности IIa, питание 220 В").isEmpty()).isTrue();
        assertThat(SpecConstraintExtractor.extract(null).isEmpty()).isTrue();
        assertThat(SpecConstraintExtractor.extract("  ").isEmpty()).isTrue();
    }

    @Test
    void perAxisConstraints() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Длина не более 1200 мм. Ширина до 80 см. Высота не более 1,5 м. Глубина не менее 100 мм.");
        assertThat(c.maxLengthMm()).isEqualTo(1200);
        assertThat(c.maxWidthMm()).isEqualTo(800);
        assertThat(c.maxHeightMm()).isEqualTo(1500);
        // «глубина не менее» — нижняя граница, игнор (и не перетирает length)
    }

    @Test
    void depthMapsToLength_whenNoLength() {
        SpecConstraints c = SpecConstraintExtractor.extract("Глубина не более 450 мм, высота до 900 мм");
        assertThat(c.maxLengthMm()).isEqualTo(450);
        assertThat(c.maxHeightMm()).isEqualTo(900);
        assertThat(c.maxWidthMm()).isNull();
    }

    @Test
    void twoDimensionalWithKeyword() {
        SpecConstraints c = SpecConstraintExtractor.extract("электроды силиконовые, размеры 55*80 мм");
        assertThat(c.maxLengthMm()).isEqualTo(55);
        assertThat(c.maxWidthMm()).isEqualTo(80);
        assertThat(c.maxHeightMm()).isNull();
    }

    @Test
    void twoDimensionalWithoutKeywordIgnored() {
        SpecConstraints c = SpecConstraintExtractor.extract("кабель сечением 2*4 мм");
        assertThat(c.isEmpty()).isTrue();
    }

    @Test
    void tripleTakesPriorityOverAxesAndTwoD() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Габариты не более 1000х600х400 мм. Длина не более 9999 мм. Размер 55*80 мм.");
        assertThat(c.maxLengthMm()).isEqualTo(1000);
        assertThat(c.maxWidthMm()).isEqualTo(600);
        assertThat(c.maxHeightMm()).isEqualTo(400);
    }

    @Test
    void snippetsCaptured() {
        SpecConstraints c = SpecConstraintExtractor.extract(
                "Габариты не более 1200х800х1300 мм. Вес не более 45 кг.");
        assertThat(c.snippets()).hasSize(2);
        assertThat(c.snippets().get(0)).contains("1200х800х1300 мм");
        assertThat(c.snippets().get(1)).contains("45 кг");
    }
}
