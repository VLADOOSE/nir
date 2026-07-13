package com.vladoose.nir.util;

import com.vladoose.nir.util.LotQueryTokenizer.WeightedToken;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LotQueryTokenizerTest {

    @Test
    void stripsCancelariteAndKeepsSignificantTokens_nameWeightFlat() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Устройство оцифровки рентген снимков", null);
        assertThat(t).extracting(WeightedToken::token)
                .containsExactly("оцифровки", "рентген", "снимков");
        // вес токена имени = фактор источника 1.0 (различие даёт IDF в сервисе, не позиция)
        assertThat(t).allSatisfy(w -> assertThat(w.weight()).isEqualTo(1.0));
    }

    @Test
    void genericNoun_ustanovka_dropped_leavesDistinctiveWord() {
        // «установка» — родовое слово (как «система»/«устройство»): без него в реестре цепляется
        // мусор (установка обеззараживания воздуха/воды), различающее прилагательное остаётся
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Ангиографическая установка", null);
        assertThat(t).extracting(WeightedToken::token).containsExactly("ангиографическая");
    }

    @Test
    void hyphenWordsStayWhole_andServiceWordsDropped() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Дефибриллятор-монитор для отделения и палат", null);
        assertThat(t).extracting(WeightedToken::token)
                .containsExactly("дефибриллятор-монитор", "отделения", "палат");
    }

    @Test
    void specCharacteristicsAddTokensAtHalfWeight_noDuplicates() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize("Электрод",
                "Резиновые пластинки для аппарата электрофореза \"Элэскулап\", размеры 55*80 мм, электрод");
        assertThat(t).extracting(WeightedToken::token)
                .startsWith("электрод")                       // из имени, вес 1.0
                .contains("резиновые", "пластинки", "электрофореза", "элэскулап")
                .doesNotContain("аппарата", "размеры", "мм"); // канцелярит/служебные/короткие — вон
        assertThat(t).filteredOn(x -> x.token().equals("электрод")).hasSize(1); // без дублей
        WeightedToken rez = t.stream().filter(x -> x.token().equals("резиновые")).findFirst().orElseThrow();
        assertThat(rez.weight()).isEqualTo(0.5); // 1.0 × 0.5
    }

    @Test
    void numbersShortAndBlankDropped_emptyGivesEmpty() {
        assertThat(LotQueryTokenizer.tokenize("Аппарат 2 шт", null)).isEmpty();
        assertThat(LotQueryTokenizer.tokenize(null, null)).isEmpty();
        assertThat(LotQueryTokenizer.tokenize("  ", "  ")).isEmpty();
    }

    @Test
    void capsAtFiveNameTokens() {
        List<WeightedToken> t = LotQueryTokenizer.tokenize(
                "Оцифровщик рентгеновских снимков панорамный цифровой беспроводной переносной", null);
        assertThat(t).hasSize(5);
        assertThat(t.get(4).weight()).isEqualTo(1.0);   // все токены имени — вес 1.0
    }
}
