package com.vladoose.nir.service.supplierreply;

import com.vladoose.nir.service.supplierreply.ReplyMatcher.Kind;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Match;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Position;
import com.vladoose.nir.service.supplierreply.ReplyMatcher.Row;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ReplyMatcherTest {

    static List<Position> positions(String... names) {
        List<Position> out = new ArrayList<>();
        for (int i = 0; i < names.length; i++) out.add(new Position(100 + i, i + 1, names[i], 1));
        return out;
    }

    static List<Row> rows(String... texts) {
        List<Row> out = new ArrayList<>();
        for (int i = 0; i < texts.length; i++) out.add(new Row(20 + i, i, texts[i], null));
        return out;
    }

    static Match of(List<Match> ms, long itemId) {
        return ms.stream().filter(m -> m.itemId() == itemId).findFirst().orElse(null);
    }

    @Test
    void reorderedAndRenamed_codesDecide() {
        List<Match> ms = ReplyMatcher.match(
                positions("Скальпель офтальмологический MSL24", "Скальпель офтальмологический MST15", "Материал шовный нейлон 2056"),
                rows("Материал шовный стерильный с иглами: нейлон, арт. 2056, 1 уп",
                        "Скальпели офтальмологические: Скальпель MST15 15°",
                        "Скальпели офтальмологические: Скальпель MSL24 2,4 мм",
                        "Доставка до склада"));
        assertThat(of(ms, 100)).extracting(Match::fileRow, Match::kind).containsExactly(22, Kind.AUTO);
        assertThat(of(ms, 101)).extracting(Match::fileRow, Match::kind).containsExactly(21, Kind.AUTO);
        assertThat(of(ms, 102)).extracting(Match::fileRow, Match::kind).containsExactly(20, Kind.AUTO);
        assertThat(ms).noneMatch(m -> m.fileRow() == 23);
    }

    @Test
    void twinPositionsAreSuggestionsOnly() {
        List<Match> ms = ReplyMatcher.match(positions("Набор реагентов", "Набор реагентов"),
                rows("Набор реагентов для биохимического анализатора", "Набор реагентов (контроль)"));
        assertThat(ms).isNotEmpty().allMatch(m -> m.kind() == Kind.SUGGEST);
    }

    @Test
    void singlePositionSingleRow_auto() {
        List<Match> ms = ReplyMatcher.match(positions("Кушетка"), rows("Кушетка смотровая КС-1"));
        assertThat(ms).singleElement().extracting(Match::kind).isEqualTo(Kind.AUTO);
    }

    @Test
    void latinLookalikesInCode() {
        List<Match> ms = ReplyMatcher.match(positions("Монитор пациента BeneVision N12"),
                rows("Монитор пациента ВеnеVision N12 (Mindray)"));
        assertThat(ms).singleElement().extracting(Match::kind).isEqualTo(Kind.AUTO);
    }

    @Test
    void unrelatedNames_noMatch() {
        assertThat(ReplyMatcher.match(positions("Тонометр"), rows("Измеритель артериального давления OMRON M2"))).isEmpty();
    }

    /** «ХЭЛП»: кюретки № 5 и № 2 различаются только номером — позиция № 5 встаёт к своей строке. */
    @Test
    void siblingNumberPicksRightRow() {
        List<Match> ms = ReplyMatcher.match(positions("Кюретка аденоидная изогнутая Negus № 5"),
                rows("Кюретка аденоидная изогнутая (Negus) номер 2 (КА)", "Кюретка аденоидная изогнутая (Negus) номер 5 (КА)"));
        assertThat(ms).singleElement().extracting(Match::fileRow, Match::kind).containsExactly(21, Kind.AUTO);
    }

    /** В файле только соседний размер — не подставлять (иначе позиция «60 мм» получила бы цену «40 мм»). */
    @Test
    void onlySiblingInFile_isNotAuto() {
        List<Match> ms = ReplyMatcher.match(positions("Зеркало носовое Killian 60 мм"),
                rows("Зеркало носовое по KILLIAN длина крыльев 40 мм"));
        assertThat(ms).noneMatch(m -> m.kind() == Kind.AUTO);
    }

    /**
     * Поставщик назвал товар своими словами, совпадает только код модели — строка всё равно находится (вес кода втрое,
     * спека §3.3). С кодом весом как обычное слово пара тонет ниже подсказки, и позиция осталась бы без цены.
     */
    @Test
    void renamedRowWithSameModelCode_isFound() {
        List<Match> ms = ReplyMatcher.match(positions("Скальпель офтальмологический MSL24"),
                rows("Нож микрохирургический MSL24 (Mani)"));
        assertThat(ms).singleElement().extracting(Match::fileRow).isEqualTo(20);
    }

    @Test
    void missingPosition_hasNoMatch() {
        List<Match> ms = ReplyMatcher.match(positions("Кушетка смотровая", "Ширма двухсекционная"), rows("Кушетка смотровая КМС-01"));
        assertThat(of(ms, 101)).isNull();
    }
}
