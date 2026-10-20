package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.FitVerdict;
import com.vladoose.nir.service.productwatch.*;
import com.vladoose.nir.service.productwatch.fit.LotFit;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сверки ТЗ с нашими товарами (спека 2026-10-20-product-spec-fit §13) на настоящих ТЗ — тем же кодом, что считает отметку
 * (`ProductMatcher.match` → `ProductMatchService.fitText / fitFor`): ложных «подходит» — 0; чужой бренд — как размечено;
 * найдено верных «подходит» — не меньше FOUND_MIN (храповик: упало — разбираться, а не понижать).
 */
class ProductFitQualityTest {

    /**
     * Калибровка задачи 14 (2026-10-20): найдено 2 из 4 строк FITS* — ДКИ-Н-12 (17733214-1, ТЗ списано с карточки РУ) и
     * ОМП-5/80 (17729514-1, РУ истекло); не найдены комплектные ТЗ Астаны (состав набора, сверять нечем; там ★ COPIED).
     * Храповик: дальше не понижать.
     */
    static final int FOUND_MIN = 2;

    @Test
    void gate() {
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.seedGroups(), ProductFixtures.catalogFull(),
                ProductFixtures.registrations(), ProductFixtures.registryBrands());
        int falseFits = 0, found = 0, foreignWrong = 0, unlabeled = 0;
        List<String> report = new ArrayList<>();
        for (ProductFixtures.FitGoldenRow row : ProductFixtures.fitGolden()) {
            if (row.expected().equals("?")) { unlabeled++; continue; }
            String spec = ProductFixtures.spec(row.file());
            String text = ProductMatchService.fitText(spec, row.description());
            FitVerdict best = FitVerdict.NO_REQUIREMENTS;
            String product = null, foreign = null;
            for (LotMatch m : ProductMatcher.match(snap, row.announcement(), List.of(new LotInput(1, row.lot(), row.description(), spec)))) {
                LotFit f = ProductMatchService.fitFor(snap, m.groupId(), text);
                if (f == null) continue;
                if (foreign == null) foreign = f.foreignBrand();
                if (f.verdict().rank() > best.rank()) { best = f.verdict(); product = f.best() == null ? null : f.best().name(); }
            }
            // «подходит» не тот товар — тоже ложное: в Telegram ушло бы «подходит <другой товар>»
            if (best == FitVerdict.FITS && !(row.expected().equals("FITS") && product != null && product.startsWith(row.product()))) falseFits++;
            if (row.expected().startsWith("FITS") && best.name().equals(row.expected()) && product != null && product.startsWith(row.product())) found++;
            if (!Objects.equals(row.foreign().equals("-") ? null : row.foreign(), foreign)) foreignWrong++;
            report.add("REPORT " + row.file() + " | ждали " + row.expected() + " " + row.product() + " | " + best + " " + product
                    + " | чужой: ждали " + row.foreign() + ", " + foreign);
        }
        report.forEach(System.out::println);
        assertThat(unlabeled).as("неразмеченных строк").isZero();
        assertThat(falseFits).as("ложных «подходит»").isZero();
        assertThat(foreignWrong).as("чужой бренд не как размечено").isZero();
        assertThat(found).as("найдено верных «подходит»").isGreaterThanOrEqualTo(FOUND_MIN);
    }
}
