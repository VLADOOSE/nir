package com.vladoose.nir.integration;

import com.vladoose.nir.entity.TenderLot;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Правила слияния лотов при переимпорте — быстрым модульным тестом, без Spring. */
class LotMergeIndexTest {

    /** Входящий лот площадки в терминах индекса: код + наименование. */
    private record In(String code, String name) {}

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
        assertThat(index.claimByCode("87197521-ОИ2")).isSameAs(a);
    }

    @Test
    void codeMatchIsCaseInsensitiveAndTrimmed() {
        TenderLot a = lot("A-Т1", "Томограф");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claimByCode("  a-т1  ")).isSameAs(a);
    }

    /**
     * Лоты, импортированные до появления кода площадки, кода не имеют (в живой БД это ВСЕ
     * goszakup-лоты). Без запасного матча по имени первый же переимпорт пересоздал бы их.
     */
    @Test
    void fallsBackToNameWhenExistingRowHasNoCode() {
        TenderLot legacy = lot(null, "Датчик ультразвуковой");
        LotMergeIndex index = new LotMergeIndex(List.of(legacy));
        List<TenderLot> matched = index.matchAll(
                List.of(new In("17304732-Т1", "Датчик ультразвуковой")), In::code, In::name);
        assertThat(matched.get(0)).isSameAs(legacy);
    }

    @Test
    void claimedOnlyOnce_twoIncomingLotsShareKey() {
        TenderLot a = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claimByCode("К1")).isSameAs(a);
        assertThat(index.claimByCode("К1")).as("второй раз тот же лот отдавать нельзя").isNull();
    }

    /** Симметрия: два СУЩЕСТВУЮЩИХ лота с одним ключом должны забираться оба, а не затирать друг друга. */
    @Test
    void twoExistingRowsWithSameKeyAreBothClaimable() {
        TenderLot a = lot("К1", "Центрифуга");
        TenderLot b = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a, b));
        assertThat(index.claimByCode("К1")).isSameAs(a);
        assertThat(index.claimByCode("К1")).isSameAs(b);
        assertThat(index.claimByCode("К1")).isNull();
    }

    /** Лот, уже забранный по коду, не должен всплыть второй раз через индекс имён. */
    @Test
    void lotClaimedByCodeIsNotClaimableByNameAgain() {
        TenderLot a = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claimByCode("К1")).isSameAs(a);
        assertThat(index.claimByUniqueName("Центрифуга")).isNull();
    }

    /**
     * Имя внутри тендера НЕ уникально (на живой БД 283 лота из 1305 в 97 группах одинаковых имён).
     * Спаривание по такому имени решалось бы порядком строк в куче — то есть случайно.
     */
    @Test
    void ambiguousNameIsNotAMatch() {
        TenderLot a = lot(null, "Набор реагентов");
        TenderLot b = lot(null, "Набор реагентов");
        LotMergeIndex index = new LotMergeIndex(List.of(a, b));
        assertThat(index.claimByUniqueName("Набор реагентов")).isNull();
        assertThat(index.claimByUniqueName("Набор реагентов")).isNull();
    }

    /** Если по коду забрали всех однофамильцев кроме одного — имя снова различает. */
    @Test
    void nameBecomesUnambiguousOnceRivalsAreClaimedByCode() {
        TenderLot a = lot("К1", "Набор реагентов");
        TenderLot b = lot(null, "Набор реагентов");
        LotMergeIndex index = new LotMergeIndex(List.of(a, b));
        assertThat(index.claimByCode("К1")).isSameAs(a);
        assertThat(index.claimByUniqueName("Набор реагентов")).isSameAs(b);
    }

    /**
     * Ключевой порядок: сперва ВСЕ коды, потом имена. Жадный «код-или-имя» на каждом лоте позволял
     * новому лоту забрать по имени строку, которую следующий лот забрал бы по коду — и разобранное
     * ТЗ переезжало на чужой лот.
     */
    @Test
    void codeMatchesWinOverNameMatches_regardlessOfIncomingOrder() {
        TenderLot existing = lot("К7", "Датчик ультразвуковой");
        LotMergeIndex index = new LotMergeIndex(List.of(existing));

        // входящий №1 — новый лот с тем же именем, входящий №2 — тот самый лот по коду
        List<TenderLot> matched = index.matchAll(
                List.of(new In("К9", "Датчик ультразвуковой"), new In("К7", "Датчик ультразвуковой")),
                In::code, In::name);

        assertThat(matched.get(0)).as("новый лот не должен забирать чужую строку по имени").isNull();
        assertThat(matched.get(1)).as("строка достаётся своему коду").isSameAs(existing);
    }

    @Test
    void matchAllKeepsSizeAndOrder() {
        TenderLot a = lot("К1", "Центрифуга");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        List<TenderLot> matched = index.matchAll(
                List.of(new In("К0", "Морозильник"), new In("К1", "Центрифуга"), new In("К2", "Весы")),
                In::code, In::name);
        assertThat(matched).hasSize(3);
        assertThat(matched.get(0)).isNull();
        assertThat(matched.get(1)).isSameAs(a);
        assertThat(matched.get(2)).isNull();
    }

    @Test
    void blankKeysNeverMatch() {
        TenderLot a = lot("", "  ");
        LotMergeIndex index = new LotMergeIndex(List.of(a));
        assertThat(index.claimByCode("")).isNull();
        assertThat(index.claimByUniqueName("  ")).isNull();
        assertThat(index.claimByCode(null)).isNull();
        assertThat(index.claimByUniqueName(null)).isNull();
    }
}
