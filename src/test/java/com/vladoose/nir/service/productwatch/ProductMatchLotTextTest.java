package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.TechSpecStatus;
import com.vladoose.nir.entity.TenderLot;
import com.vladoose.nir.service.productwatch.fit.SpecRequirements;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Текст ТЗ лота для сверки — один на отметку, сверку модели в работе и «Для ИИ» (спека 2026-10-20-lot-model-in-work §6). */
class ProductMatchLotTextTest {

    static TenderLot lot(TechSpecStatus status, String spec) {
        TenderLot l = new TenderLot();
        l.setTechSpecStatus(status);
        l.setRequiredSpec(spec);
        return l;
    }

    @Test
    void parsedSpec_usesSpecBody_elsePlatformDescription() {
        String spec = "Наименование закупки: Аспиратор\nХарактеристики закупаемых товаров: производительность не менее 20 л/мин";
        assertThat(ProductMatchService.lotFitText(lot(TechSpecStatus.OK, spec))).isEqualTo(SpecRequirements.body(spec));
        assertThat(ProductMatchService.lotFitText(lot(TechSpecStatus.PENDING, "строка 1\nстрока 2")))
                .isEqualTo(SpecRequirements.reflowText("строка 1\nстрока 2"));
        assertThat(ProductMatchService.lotFitText(lot(null, null))).isEmpty();
    }
}
