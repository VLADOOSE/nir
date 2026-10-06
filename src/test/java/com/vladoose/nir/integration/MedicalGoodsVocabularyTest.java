package com.vladoose.nir.integration;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class MedicalGoodsVocabularyTest {
    @Test
    void multiWordTerm_matchesStemsInAnyOrderAndCase() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Кислородный концентратор", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Концентратор кислорода 5 л", List.of("концентратор кислород"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Государственные закупки медицинских изделий", List.of("издели медицинск"))).isTrue();
    }
    @Test
    void stemMatchesOnlyAtWordStart() {
        assertThat(MedicalGoodsVocabulary.matchesAny("кузина", List.of("узи"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Аппарат УЗИ", List.of("узи"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("тест-полоски", List.of("полоск"))).isTrue(); // дефис — граница слова
    }
    @Test
    void termWithShortStemIsMatchedAsPhrase() {   // «по» как начало слова есть почти в любом тексте
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы по ремонту кровли", List.of("работы по"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Поставка, выполнение работы и обучение", List.of("работы по"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Объём работы поставщика", List.of("работы по"))).isFalse();
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы\nпо ремонту", List.of("работы по"))).isTrue();
        assertThat(MedicalGoodsVocabulary.matchesAny("Работы  по ремонту", List.of("работы по"))).isTrue();
    }
    @Test
    void yoIsTreatedAsYe() {
        assertThat(MedicalGoodsVocabulary.matchesAny("Аппарат искусственной вентиляции лёгких", List.of("вентиляц легк"))).isTrue();
    }
    @Test
    void medicineMarkers() {
        assertThat(MedicalGoodsVocabulary.hasMedicine("Раствор для инфузий 0,9%")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Таблетки 8 мг")).isTrue();
        assertThat(MedicalGoodsVocabulary.hasMedicine("Монитор пациента")).isFalse();
    }
}
