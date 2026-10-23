package com.vladoose.nir.pricelist;

import com.vladoose.nir.dto.response.PriceHint;
import com.vladoose.nir.dto.response.PriceHintRow;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.pricelist.LotPriceHints;
import com.vladoose.nir.service.pricelist.PriceHints;
import com.vladoose.nir.service.pricelist.PriceLines;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LotPriceHintsTest {

    private static TenderLotProductMatch match(ProductMatchLevel level, Long product, FitVerdict fit, Long fitProduct, Integer met) {
        return TenderLotProductMatch.builder().level(level).productId(product).fit(fit).fitProductId(fitProduct).fitMet(met).build();
    }

    @Test
    void target_modelInWork_thenFit_thenNamed() {
        TenderLot lot = new TenderLot();
        MedEquipment e = MedEquipment.builder().id(5L).name("ДКИ-Н-12").build();
        lot.setProposedEquipment(e);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.NAMED, 9L, null, null, null))).equipmentId()).isEqualTo(5L);
        lot.setProposedEquipment(null);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.FITS, 1L, FitVerdict.FITS, 7L, 21))).siteProductId()).isEqualTo(7L);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.PARTIAL, 7L, 3))).siteProductId()).isEqualTo(7L);
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.PARTIAL, 7L, 0)))).isNull();   // ◐ без ✓
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.TYPE, null, FitVerdict.NOT_FIT, 7L, 9)))).isNull();
        assertThat(LotPriceHints.target(lot, List.of(match(ProductMatchLevel.COPIED, 8L, null, null, null))).siteProductId()).isEqualTo(8L);
        assertThat(LotPriceHints.target(lot, List.of())).isNull();
    }

    @Test
    void budgetPct_andMerge() {
        PriceHintRow row = new PriceHintRow(1L, 2L, 3L, "Аксион", true, null, "ДКИ-Н-12", null, null, null, null, false, "RUB",
                new BigDecimal("150000"), new BigDecimal("6"), new BigDecimal("900000"), null, null, null, null, null, null, null);
        PriceHint h = new PriceHint(null, List.of(row));
        assertThat(LotPriceHints.budgetPct(h, new BigDecimal("1080000"))).isEqualTo(83);
        assertThat(LotPriceHints.budgetPct(h, null)).isNull();
        assertThat(LotPriceHints.budgetPct(new PriceHint(null, List.of()), new BigDecimal("1"))).isNull();
        Map<Long, String> market = new LinkedHashMap<>(Map.of(1L, "рынок: медиана 1 ₸ (2 дог.)"));
        Map<Long, String> merged = PriceLines.merge(market, Map.of(1L, "закупка по прайсу ≈ 900 000 ₸", 2L, "закупка по прайсу ≈ 1 ₸"));
        assertThat(merged.get(1L)).isEqualTo("рынок: медиана 1 ₸ (2 дог.) · закупка по прайсу ≈ 900 000 ₸");
        assertThat(merged.get(2L)).isEqualTo("закупка по прайсу ≈ 1 ₸");
    }
}
