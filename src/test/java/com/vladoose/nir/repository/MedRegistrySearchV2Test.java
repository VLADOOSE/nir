package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Скоринг V2 на живом реестре НЦЭЛС (~14k записей в nirdb).
 * Проверяет ровно то, ради чего затевался: ничьи разбиты, qualifier поднимает нужное,
 * но охват не расширяет.
 */
@SpringBootTest
@Transactional
class MedRegistrySearchV2Test {

    private static final double BONUS = 0.3;
    private static final double MIN_SCORE = 0.2;

    @Autowired MedRegistryRepository repo;

    /**
     * Находка 2: у «перчатки» было 147 записей со скором ровно 1.000 — оператор видел 6 из них,
     * и какие именно, решал планировщик.
     *
     * <p>Уникальности скоров тут ждать НЕЛЬЗЯ, и это не слабость проверки. При одном токене
     * запроса word_similarity равен 1.0 у каждой совпавшей записи, поэтому скор алгебраически
     * вырождается в 2/(nsig+1), где nsig — число значимых (≥4 симв.) слов в названии записи.
     * Значений столько же, сколько различных длин названий в выдаче. Проверяем то, ради чего
     * всё делалось: скор перестал быть константой и выдача осмысленно упорядочена.
     */
    @Test
    void breaksTiesForGenericOneWordLot() {
        List<RegistryCandidateRow> rows =
                repo.searchByTokensV2("перчатки", "1.0", "", BONUS, MIN_SCORE, 10);

        assertThat(rows).hasSizeGreaterThan(3);

        List<Double> scores = rows.stream().map(RegistryCandidateRow::getScore).toList();
        assertThat(scores).doesNotContainNull();
        // главное: скор больше не одинаковый у всех — прежняя выдача была сплошь 1.000
        assertThat(Set.copyOf(scores))
                .describedAs("скор перестал быть константой (было 147 записей с ровно 1.000)")
                .hasSizeGreaterThanOrEqualTo(3);
        assertThat(scores).isSortedAccordingTo(Comparator.reverseOrder());
        assertThat(rows.get(0).getName().toLowerCase()).contains("перчатк");
    }

    /** Находка 5: описание «вакуумный» должно поднять вакуумный насос на первое место. */
    @Test
    void qualifierLiftsMatchingCandidate() {
        List<RegistryCandidateRow> rows =
                repo.searchByTokensV2("насос", "1.0", "вакуумный|производительность", BONUS, MIN_SCORE, 5);

        assertThat(rows).isNotEmpty();
        assertThat(rows.get(0).getName().toLowerCase()).contains("вакуумн");
    }

    /**
     * Находка 4 — то, из-за чего вся схема двухстадийная: qualifier НЕ должен добавлять
     * кандидатов, иначе охват взрывается (спектрофотометр 3 → 278) и верный ответ тонет.
     */
    @Test
    void qualifierDoesNotWidenCandidateSet() {
        int withoutQualifier =
                repo.searchByTokensV2("спектрофотометр", "1.0", "", BONUS, 0.0, 100).size();
        int withQualifier =
                repo.searchByTokensV2("спектрофотометр", "1.0",
                        "измерения|оптической|плотности|раствора", BONUS, 0.0, 100).size();

        assertThat(withQualifier).isEqualTo(withoutQualifier);
    }

    /** Длинная запись, где слова запроса — малая часть названия, должна проигрывать короткой профильной. */
    @Test
    void penalizesRegistryEntriesWithMuchUnexplainedContent() {
        List<RegistryCandidateRow> rows =
                repo.searchByTokensV2("морозильник", "1.0", "", BONUS, MIN_SCORE, 10);

        assertThat(rows).isNotEmpty();
        RegistryCandidateRow top = rows.get(0);
        RegistryCandidateRow last = rows.get(rows.size() - 1);
        assertThat(top.getName().length()).isLessThan(last.getName().length());
    }

    @Test
    void emptyQualifierIsHandled() {
        assertThat(repo.searchByTokensV2("центрифуга", "1.0", "", BONUS, MIN_SCORE, 5)).isNotEmpty();
    }
}
