package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
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
     * <p><b>Уникальности скоров тут ждать НЕЛЬЗЯ, и это не слабость проверки.</b> При одном
     * токене запроса и одном совпавшем слове (nhit=1) скор равен
     * {@code 2r/(r·nsig + 1)}, где r = recall, nsig — число значимых слов названия записи,
     * то есть значений ровно столько, сколько различных nsig в выдаче. Скоры ложатся на
     * дискретную решётку {@code 2/(n+1)}: 0.400, 0.333, 0.286, 0.250, 0.222, 0.200, 0.182 …
     *
     * <p>NB: НЕВЕРНО думать, будто r всегда равен 1.0 (это правда для «перчатки» — 98.6% строк,
     * но не вообще: у «спектрофотометр» 0 из 3, у «шприц» 33.5%, у «томограф» 44.7%). Вывод
     * держится на более сильном основании: отсечка score ≥ 0.2 равносильна
     * {@code nsig ≤ 10 − 1/r}, а на всей допустимой полосе r ∈ [0.6, 1] эта граница пробегает
     * лишь [8.33, 9] — то есть практически не зависит от близости совпадения. Фактическая
     * константа — <b>8, а не 9</b>: при r=1 и nsig=9 арифметика float8 даёт
     * {@code 2·(1/9)/(1+1/9) = 0.19999999999999998}, что порог ≥ 0.2 не проходит.
     *
     * <p>Проверяем то, ради чего всё делалось: скор перестал быть константой и выдача
     * осмысленно упорядочена. Порог «≥ 3 различных» взят не с потолка и не подогнан под
     * зелёный: замер по «перчатки» на живом реестре даёт в топ-10 ровно <b>4</b> различных
     * значения — вёдра 0.4000 (1 запись), 0.3333 (5), 0.2927 (1), 0.2857 (6), далее
     * 0.2500 (24), 0.2222 (29). То есть у проверки один запас-ведро на дрейф реестра.
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
     *
     * <p>Проверяется при minScore = 0, потому что это честная формулировка именно свойства
     * ОТБОРА: отсечка выключена, сравниваются сами множества кандидатов.
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

    /**
     * Тот же инвариант на РАБОЧЕМ пороге. Отдельным тестом, потому что при minScore = 0
     * равенство выше выполняется тривиально, и одного его мало: бонус прибавляется ДО отсечки,
     * поэтому на живом пороге выдача qualifier'ом реально растёт (по «перчатки» 55 → 146).
     * Это законно — бонус поднимает УЖЕ ОТОБРАННЫЕ identity строки. Незаконно было бы
     * привести строку, которой identity не выбирал: верхняя граница — размер identity-множества.
     */
    @Test
    void qualifierNeverExceedsIdentityCandidateCountAtRealThreshold() {
        int identityCandidates =
                repo.searchByTokensV2("перчатки", "1.0", "", BONUS, 0.0, 1000).size();
        int withQualifierAtThreshold =
                repo.searchByTokensV2("перчатки", "1.0",
                        "смотровые|нитриловые|неопудренные", BONUS, MIN_SCORE, 1000).size();
        int withoutQualifierAtThreshold =
                repo.searchByTokensV2("перчатки", "1.0", "", BONUS, MIN_SCORE, 1000).size();

        assertThat(withQualifierAtThreshold)
                .describedAs("qualifier не может привести строку вне identity-отбора")
                .isLessThanOrEqualTo(identityCandidates);
        // и он действительно поднимает строки над порогом — иначе тест ничего не сторожил бы
        assertThat(withQualifierAtThreshold).isGreaterThan(withoutQualifierAtThreshold);
    }

    /**
     * Слово в чужом для запроса алфавите не может совпасть (word_similarity('монитор','monitor')
     * = 0), поэтому оно исключено из знаменателя prec. Без этого записи с латинскими
     * перечнями моделей штрафовались за длину, которую кириллический лот всё равно не покроет:
     * у эталонной записи 60 из 64 значимых слов — латиница, скор был 0.031 (недостижима),
     * стал 0.400. По всему реестру порог 0.2 из-за латиницы пересекали 1585 записей (11.3%).
     */
    @Test
    void latinTradeNamesDoNotDiluteDenominatorForCyrillicQuery() {
        List<RegistryCandidateRow> rows =
                repo.searchByTokensV2("катетер", "1.0", "", BONUS, MIN_SCORE, 200);

        assertThat(rows).isNotEmpty();
        assertThat(rows).extracting(RegistryCandidateRow::getRegNumber)
                .describedAs("запись с длинным латинским перечнем моделей больше не тонет")
                .contains("РК-ИМН-5№015848");
    }

    /**
     * Длинная запись, где слова запроса — малая часть названия, должна проигрывать короткой
     * профильной. Меряем в ЗНАЧИМЫХ СЛОВАХ, а не в символах: формула нормирует именно по их
     * числу, и длина строки с ним не совпадает (в живой выдаче «морозильник» записи с 4
     * значимыми словами имеют одинаковый скор 0.393 при длине названия 54 и 182 символа —
     * посимвольная проверка проходила бы по совпадению).
     */
    @Test
    void penalizesRegistryEntriesWithMuchUnexplainedContent() {
        List<RegistryCandidateRow> rows =
                repo.searchByTokensV2("морозильник", "1.0", "", BONUS, MIN_SCORE, 10);

        // нужны минимум две строки, иначе top == last и сравнение бессмысленно
        assertThat(rows).hasSizeGreaterThan(1);
        RegistryCandidateRow top = rows.get(0);
        RegistryCandidateRow last = rows.get(rows.size() - 1);
        assertThat(significantWords(top.getName()))
                .describedAs("у лучшей записи меньше необъяснённых запросом слов")
                .isLessThan(significantWords(last.getName()));
    }

    /**
     * Значимые слова названия В АЛФАВИТЕ ЗАПРОСА (тесты гоняют кириллические запросы): ≥4 символов
     * и содержит кириллицу. Это ведущий член знаменателя prec, но не весь он — SQL добавляет ещё
     * +1, если в названии есть латиница (см. комментарий у запроса). Для этой проверки достаточно
     * ведущего члена: сравниваем, у какой записи больше НЕОБЪЯСНЁННОГО запросом содержания.
     */
    private static long significantWords(String name) {
        return Arrays.stream(name.toLowerCase().replaceAll("[^\\p{IsAlphabetic}]", " ").split(" "))
                .filter(w -> w.length() >= 4)
                .filter(w -> w.matches(".*[а-яё].*"))
                .count();
    }

    @Test
    void emptyQualifierIsHandled() {
        assertThat(repo.searchByTokensV2("центрифуга", "1.0", "", BONUS, MIN_SCORE, 5)).isNotEmpty();
    }
}
