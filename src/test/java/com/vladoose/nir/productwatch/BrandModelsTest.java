package com.vladoose.nir.productwatch;

import com.vladoose.nir.service.productwatch.BrandModels;
import com.vladoose.nir.service.productwatch.ProductText;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class BrandModelsTest {

    private static boolean hit(String word, String text) {
        Pattern p = BrandModels.compile(word);
        return p.matcher(ProductText.normalize(text)).find();
    }

    @Test
    void codesIgnoreSeparatorsAndExecution() {
        assertThat(hit("ЭХВЧ-350", "аппарат ЭХВЧ 350-03 «ФОТЕК»")).isTrue();
        assertThat(hit("ЭХВЧ-350-03", "эхвч35003")).isTrue();
        assertThat(hit("эк12т", "Электрокардиограф ЭК12Т-01-«Р-Д»")).isTrue();
        assertThat(hit("эк12т", "ЭК12ТЦ")).as("другая модель").isFalse();
        assertThat(hit("ск-16", "СК-160")).isFalse();
        assertThat(hit("ЭХВЧ-350", "ЭХВЧ-3500")).as("код с цифрами — граница и у длинного слова").isFalse();
        assertThat(hit("дки-н", "Дефибриллятор ДКИ-Н-11")).isTrue();
    }

    @Test
    void shortWordsWholeLongWordsStem() {
        assertThat(hit("обн", "обновление ПО")).isFalse();
        assertThat(hit("обн", "Облучатель ОБН 150")).isTrue();
        assertThat(hit("аксион", "запчасти к дефибриллятору «Аксиона»")).isTrue();
        assertThat(hit("нпп монитор", "производства НПП «Монитор»")).isTrue();
        assertThat(hit("фотек", "FOTEK")).isFalse();
        assertThat(hit("fotek", "аппарат FOTEK")).isTrue();
    }

    @Test
    void modelCodesFromCatalogNames_globalOnlyDistinct() {
        assertThat(BrandModels.modelCodes("Дефибриллятор-монитор ДКИ-Н-11 «Аксион» (ЭКГ, SpO2, НИАД)"))
                .contains("ДКИ-Н-11").as("одна цифра — параметр, не модель").doesNotContain("SpO2");
        assertThat(BrandModels.modelCodes("ВЧ электрохирургический блок для аппарата ЭХВЧ-350-03-\"ФОТЕК\"")).contains("ЭХВЧ-350-03");
        assertThat(BrandModels.modelCodes("Рециркулятор бактерицидный настенный РБм 2Х15")).isEmpty();
        assertThat(BrandModels.globalCode("ДКИ-Н-11")).isTrue();
        assertThat(BrandModels.globalCode("SpO2")).isFalse();
        assertThat(BrandModels.globalCode("СК-16")).isFalse();
        assertThat(BrandModels.globalCode("ОБПЕ-450")).isTrue();
    }
}
