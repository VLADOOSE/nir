package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.ProductMatchQuery.FitSummary;
import com.vladoose.nir.service.productwatch.ProductMatchQuery.LotLine;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Подпись чипа тендера. «◐ похоже» — только когда хоть одно требование ТЗ подтверждено: на проде 36 из 48 «◐ похоже» были
 * без единого ✓ (халаты, баллоны, индикаторы стерилизации — характеристик товара нет), и метка обещала сходство, которого
 * сверка не нашла. Без ✓ — «🏷 наш вид», как у лота без сверки.
 */
class ProductMatchLabelTest {

    static LotLine lot(long id, FitVerdict v, int met, int total, String reasonShort) {
        FitSummary f = new FitSummary(v, "Облучатель ОБН-150", "obn150", met, total, total - met, reasonShort, null, "ACTIVE");
        return new LotLine(id, "Облучатель", 2, ProductMatchLevel.TYPE, "Облучатели", "облучатель", null, null, null, List.of(), f);
    }

    @Test
    void partialWithoutConfirmedRequirement_isOurKind() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null))))
                .isEqualTo("🏷 наш вид: облучатель");
    }

    @Test
    void partialWithConfirmedRequirement_staysSimilar() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, List.of(lot(1, FitVerdict.PARTIAL, 2, 6, null))))
                .isEqualTo("◐ похоже: облучатель");
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE,
                List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null), lot(2, FitVerdict.PARTIAL, 1, 6, null))))
                .isEqualTo("◐ похоже: облучатель");
    }

    /** «Не подходит» и без единого ✓ — установленный отказ, его метка остаётся. */
    @Test
    void notFitWithoutConfirmed_staysNotFit() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, List.of(lot(1, FitVerdict.NOT_FIT, 0, 6, "мощность"))))
                .isEqualTo("✗ не подходит: мощность");
    }

    /** Лот без ✓ — как лот без сверки: вердикт тендера — у лота, где сверка что-то установила. */
    @Test
    void partialWithoutConfirmed_doesNotHideOtherLotsVerdict() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE,
                List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null), lot(2, FitVerdict.NOT_FIT, 1, 6, "мощность"))))
                .isEqualTo("✗ не подходит: мощность");
    }
}
