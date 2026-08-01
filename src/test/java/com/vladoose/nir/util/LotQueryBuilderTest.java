package com.vladoose.nir.util;

import com.vladoose.nir.util.LotQueryBuilder.LotQuery;
import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Сбор запроса из лота: имя → identity, описание/ТЗ → qualifier. */
class LotQueryBuilderTest {

    /** Дефект, который чинит эта задача: description_ru выбрасывался целиком. */
    @Test
    void usesRawDescriptionWhenTechSpecAnchorAbsent() {
        LotQuery q = LotQueryBuilder.build("Нить", "хирургическая, синтетическая, стерильная");

        assertThat(q.identity()).extracting(WeightedToken::token).containsExactly("нить");
        // вес identity пинуется явно: tokenize(null, equipName) дал бы те же токены на 0.5
        assertThat(q.identity()).extracting(WeightedToken::weight).containsOnly(1.0);
        assertThat(q.qualifier()).contains("хирургическая", "синтетическая", "стерильная");
        assertThat(q.techSpecParsed()).isFalse();
    }

    /** Разобранное ТЗ точнее: якорь отрезает закупочную шапку — он в приоритете. */
    @Test
    void prefersCharacteristicsBlockWhenAnchorPresent() {
        String spec = "Номер закупки: 17295275-1 Место поставки: Уральск "
                + "характеристики закупаемых товаров: центрифуга лабораторная охлаждаемая";

        LotQuery q = LotQueryBuilder.build("Центрифуга", spec);

        assertThat(q.techSpecParsed()).isTrue();
        assertThat(q.qualifier()).contains("лабораторная", "охлаждаемая");
        assertThat(q.qualifier()).doesNotContain("уральск");
    }

    /** Токен, уже попавший в identity, не должен второй раз весить в qualifier. */
    @Test
    void qualifierExcludesIdentityTokens() {
        LotQuery q = LotQueryBuilder.build("Насос вакуумный", "вакуумный, производительность 1500");

        assertThat(q.identity()).extracting(WeightedToken::token).contains("насос", "вакуумный");
        // положительная половина: без неё тест прошёл бы и на qualifier == List.of()
        assertThat(q.qualifier()).contains("производительность");
        assertThat(q.qualifier()).doesNotContain("насос", "вакуумный");
    }

    @Test
    void emptySpecGivesEmptyQualifier() {
        LotQuery q = LotQueryBuilder.build("Морозильник", null);

        assertThat(q.identity()).extracting(WeightedToken::token).containsExactly("морозильник");
        assertThat(q.qualifier()).isEmpty();
        assertThat(q.techSpecParsed()).isFalse();
    }

    /**
     * Живой случай: 5 лотов названы ровно «Аппарат» — слово в стоп-листе, значит отбирающих
     * токенов ноль, хотя описание идеальное. Продвигаем описание в identity.
     */
    @Test
    void promotesQualifierToIdentityWhenNameIsAllStopWords() {
        LotQuery q = LotQueryBuilder.build("Аппарат", "ультразвуковой низкочастотный оториноларингологический");

        assertThat(q.identity()).extracting(WeightedToken::token)
                .contains("ультразвуковой", "низкочастотный", "оториноларингологический");
        assertThat(q.identity()).extracting(WeightedToken::weight).containsOnly(1.0);
        assertThat(q.qualifier()).isEmpty();
    }

    /** Пустое имя — та же развилка: отбирать нечем, значит описание становится identity. */
    @Test
    void blankNamePromotesSpecToIdentity() {
        LotQuery q = LotQueryBuilder.build("  ", "что-то");

        assertThat(q.identity()).extracting(WeightedToken::token).containsExactly("что-то");
        assertThat(q.qualifier()).isEmpty();
    }

    /** Продвигать нечего — запрос честно пустой, этот лот неотвечаем. */
    @Test
    void blankNameAndBlankSpecGivesEmptyQuery() {
        LotQuery q = LotQueryBuilder.build("  ", null);

        assertThat(q.identity()).isEmpty();
        assertThat(q.qualifier()).isEmpty();
        assertThat(q.techSpecParsed()).isFalse();
    }
}
