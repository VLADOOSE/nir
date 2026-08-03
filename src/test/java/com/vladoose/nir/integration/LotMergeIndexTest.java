package com.vladoose.nir.integration;

import com.vladoose.nir.entity.TenderLot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила слияния лотов при переимпорте — быстрым модульным тестом, без Spring. */
class LotMergeIndexTest {

    private static TenderLot lot(String code, String name) {
        TenderLot l = new TenderLot();
        l.setSourceLotCode(code);
        l.setEquipName(name);
        return l;
    }

    @Test
    void claimsByPlatformCode() {
        TenderLot a = lot("87197521-ОИ2", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claim("87197521-ОИ2", "неважно")).isSameAs(a);
    }

    @Test
    void codeMatchIsCaseInsensitiveAndTrimmed() {
        TenderLot a = lot("A-Т1", "Томограф");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claim("  a-т1  ", null)).isSameAs(a);
    }

    /**
     * Лоты, импортированные до появления кода площадки, кода не имеют (в живой БД это ВСЕ
     * goszakup-лоты). Без запасного матча по имени первый же переимпорт пересоздал бы их.
     */
    @Test
    void fallsBackToNameWhenExistingRowHasNoCode() {
        TenderLot legacy = lot(null, "Датчик ультразвуковой");
        LotMergeIndex index = new LotMergeIndex(List.of(legacy));
        assertThat(index.claim("17304732-Т1", "Датчик ультразвуковой")).isSameAs(legacy);
    }

    @Test
    void claimedOnlyOnce_twoIncomingLotsShareKey() {
        TenderLot a = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claim("К1", "Центрифуга")).isSameAs(a);
        assertThat(index.claim("К1", "Центрифуга")).as("второй раз тот же лот отдавать нельзя").isNull();
    }

    /** Симметрия: два СУЩЕСТВУЮЩИХ лота с одним ключом должны забираться оба, а не затирать друг друга. */
    @Test
    void twoExistingRowsWithSameKeyAreBothClaimable() {
        TenderLot a = lot("К1", "Центрифуга");
        TenderLot b = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a, b));
        assertThat(index.claim("К1", "Центрифуга")).isSameAs(a);
        assertThat(index.claim("К1", "Центрифуга")).isSameAs(b);
        assertThat(index.claim("К1", "Центрифуга")).isNull();
    }

    /** Лот, уже забранный по коду, не должен всплыть второй раз через индекс имён. */
    @Test
    void lotClaimedByCodeIsNotClaimableByNameAgain() {
        TenderLot a = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claim("К1", null)).isSameAs(a);
        assertThat(index.claim("К9", "Центрифуга")).isNull();
    }

    @Test
    void blankKeysNeverMatch() {
        TenderLot a = lot("", "  ");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claim("", "  ")).isNull();
        assertThat(index.claim(null, null)).isNull();
    }
}
