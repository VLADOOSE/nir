package com.vladoose.nir.productwatch;

import com.vladoose.nir.entity.ProductMatchLevel;
import com.vladoose.nir.service.productwatch.*;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Гейт сверки ТЗ (спека 2026-10-19-product-watch §15.2): ложных ★ — 0, Астана — COPIED, размеченные COPIED — не меньше
 * {@link #COPIED_RECALL_MIN}. Строки REPORT — по каждому ТЗ: ожидание, итог, найденные фразы.
 */
class ProductSpecMatchQualityTest {

    /** Храповик: найдено размеченных COPIED на наборе (2 из 2 — Астана, 2026-10-20); вниз не опускать (перекалибровка — по шапке TSV). */
    static final int COPIED_RECALL_MIN = 2;

    @Test
    void gate() throws Exception {
        // Сид (V36 + калибровка V37) и каталог с описаниями — как будет на проде (админ-API сайта).
        CatalogSnapshot snap = CatalogSnapshot.build(ProductFixtures.seedGroups(), ProductFixtures.catalogFull());
        int falseExact = 0, copiedFound = 0, copiedLabeled = 0;
        List<String> astana = new ArrayList<>();
        for (ProductFixtures.GoldenRow row : ProductFixtures.golden()) {
            String spec = ProductFixtures.spec(row.file());
            List<LotMatch> m = ProductMatcher.match(snap, row.announcement(), List.of(new LotInput(1, row.lot(), row.description(), spec)));
            ProductMatchLevel got = m.stream().map(LotMatch::level).reduce(null, ProductMatchLevel::max);
            String product = m.stream().filter(x -> x.productId() != null).map(x -> snap.products().stream()
                    .filter(p -> p.id() == x.productId()).findFirst().map(CatalogProduct::name).orElse("?")).findFirst().orElse("");
            String evidence = m.stream().map(LotMatch::evidence).filter(Objects::nonNull).findFirst().orElse("");
            System.out.println("REPORT\t" + row.file() + "\texpected=" + row.expected() + "\tgot=" + got + "\t" + product + "\t" + evidence);
            boolean exactGot = got != null && got.exact();
            boolean exactExpected = row.expected().equals("COPIED") || row.expected().equals("NAMED");
            if (exactGot && !exactExpected) falseExact++;
            if (row.expected().equals("COPIED")) {
                copiedLabeled++;
                if (got == ProductMatchLevel.COPIED && product.startsWith(row.productPrefix())) copiedFound++;
            }
            if (row.file().startsWith("17779323-1") || row.file().startsWith("17802320-1")) astana.add(got + " " + product);
        }
        System.out.println("REPORT\tИТОГ\tложных ★=" + falseExact + "\tCOPIED найдено " + copiedFound + " из " + copiedLabeled);
        assertThat(falseExact).as("ложные ★").isZero();
        assertThat(astana).isNotEmpty().allSatisfy(x -> assertThat(x).startsWith("COPIED Аппарат для аспирации дыма АСД"));
        assertThat(copiedFound).as("найдено размеченных COPIED").isGreaterThanOrEqualTo(COPIED_RECALL_MIN);
    }
}
