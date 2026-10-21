package com.vladoose.nir.service.productwatch;

import com.vladoose.nir.dto.response.OurProductSummary;
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
        FitSummary f = new FitSummary(v, "Облучатель ОБН-150", "obn150", met, 0, total, total - met, reasonShort, null, "ACTIVE");
        return new LotLine(id, "Облучатель", 2, ProductMatchLevel.TYPE, "Облучатели", "облучатель", null, null, null, List.of(), f);
    }

    @Test
    void partialWithoutConfirmedRequirement_isOurKind() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null))))
                .isEqualTo("🏷 наш вид: облучатель");
    }

    /** «◐ похоже» — с цифрами сверки и товаром (отзыв оператора 2026-10-20: сильные «похоже» видны в списке). */
    @Test
    void partialWithConfirmedRequirement_staysSimilar_withCounts() {
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, List.of(lot(1, FitVerdict.PARTIAL, 2, 6, null))))
                .isEqualTo("◐ похоже: ✓ 2 · ✗ 0 · Облучатель ОБН-150");
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE,
                List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null), lot(2, FitVerdict.PARTIAL, 1, 6, null))))
                .isEqualTo("◐ похоже: ✓ 1 · ✗ 0 · Облучатель ОБН-150");
    }

    /** Из двух «похоже» — лот, где больше ✓: по нему подпись и сортировка списка «Ближе к нашему товару». */
    @Test
    void partial_bestByConfirmedCount() {
        List<LotLine> lines = List.of(lot(1, FitVerdict.PARTIAL, 2, 6, null), lot(2, FitVerdict.PARTIAL, 5, 9, null));
        assertThat(ProductMatchQuery.label(ProductMatchLevel.TYPE, lines)).startsWith("◐ похоже: ✓ 5 · ✗ 0");
        OurProductSummary.Fit b = ProductMatchQuery.fitBrief(lines);
        assertThat(b.verdict()).isEqualTo("PARTIAL");
        assertThat(b.met()).isEqualTo(5);
        assertThat(b.total()).isEqualTo(9);
    }

    /** Без ✓ сверка ничего не установила — для сортировки её нет; «не подходит» — есть. */
    @Test
    void fitBrief_unconfirmedPartialIsNull_notFitKept() {
        assertThat(ProductMatchQuery.fitBrief(List.of(lot(1, FitVerdict.PARTIAL, 0, 6, null)))).isNull();
        assertThat(ProductMatchQuery.fitBrief(List.of(lot(1, FitVerdict.NOT_FIT, 0, 6, "мощность"))).verdict()).isEqualTo("NOT_FIT");
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
