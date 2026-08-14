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

    // Зеркалят откалиброванные константы RegistryMatchService (там же и обоснование).
    // Дублируются, а не импортируются: константы служебные (package-private в другом пакете),
    // а тест обязан гонять запрос ровно с боевыми значениями.
    private static final double BETA2 = 2.25;
    private static final double SMOOTH = 2.0;
    private static final double BONUS = 0.5;
    private static final double FULL_COVER = 0.8;
    private static final double MIN_SCORE = 0.19;

    @Autowired MedRegistryRepository repo;

    private List<? extends RegistryCandidateRow> search(String tokens, String weights, String quals,
                                                        double minScore, int limit) {
        return repo.searchByTokensV2(tokens, weights, quals, BETA2, SMOOTH, BONUS, FULL_COVER,
                minScore, limit);
    }

    /**
     * Находка 2: у «перчатки» было 147 записей со скором ровно 1.000 — оператор видел 6 из них,
     * и какие именно, решал планировщик.
     *
     * <p><b>Уникальности скоров тут ждать НЕЛЬЗЯ, и это не слабость проверки.</b> При одном
     * токене запроса и одном совпавшем слове (nhit=1) скор равен
     * {@code 3.25r/(2.25 + r·(nsig+2))}, где r = recall, nsig — число значимых слов названия
     * записи, то есть различных значений ровно столько, сколько различных nsig в выдаче.
     * При r=1 это решётка {@code 3.25/(nsig+4.25)}: 0.619, 0.520, 0.448, 0.394, 0.351 …
     * (до калибровки 2026-08-02 формула была F1 без сглаживания и решётка была {@code 2/(n+1)}).
     *
     * <p>NB: НЕВЕРНО думать, будто r всегда равен 1.0 (это правда для «перчатки» — 98.6% строк,
     * но не вообще: у «спектрофотометр» 0 из 3, у «шприц» 33.5%, у «томограф» 44.7%). Вывод
     * держится на более сильном основании: отсечка {@code score >= 0.19} равносильна
     * {@code nsig <= 15.105 − 2.25/r}, а на всей допустимой полосе r ∈ [0.6, 1] эта граница
     * пробегает лишь [11.36, 12.86] — то есть отсечка по-прежнему управляет ДЛИНОЙ названия,
     * а не близостью совпадения. Порог намеренно выбран МЕЖДУ узлами решётки (узел nsig=12
     * приходится ровно на 0.2000, следующий — 0.1884), иначе судьбу целой корзины записей
     * решало бы округление float8.
     *
     * <p>Проверяем то, ради чего всё делалось: скор перестал быть константой и выдача
     * осмысленно упорядочена (прежняя выдача была сплошь 1.000 у 147 записей).
     */
    @Test
    void breaksTiesForGenericOneWordLot() {
        List<? extends RegistryCandidateRow> rows = search("перчатки", "1.0", "", MIN_SCORE, 10);

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
        List<? extends RegistryCandidateRow> rows =
                search("насос", "1.0", "вакуумный|производительность", MIN_SCORE, 5);

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
        int withoutQualifier = search("спектрофотометр", "1.0", "", 0.0, 100).size();
        int withQualifier = search("спектрофотометр", "1.0",
                "измерения|оптической|плотности|раствора", 0.0, 100).size();

        assertThat(withQualifier).isEqualTo(withoutQualifier);
    }

    /**
     * Тот же инвариант на РАБОЧЕМ пороге. Отдельным тестом, потому что при minScore = 0
     * равенство выше выполняется тривиально, и одного его мало: бонус прибавляется ДО отсечки,
     * поэтому на живом пороге выдача qualifier'ом реально растёт (по «перчатки» 66 → 143).
     * Это законно — бонус поднимает УЖЕ ОТОБРАННЫЕ identity строки. Незаконно было бы
     * привести строку, которой identity не выбирал: верхняя граница — размер identity-множества.
     */
    @Test
    void qualifierNeverExceedsIdentityCandidateCountAtRealThreshold() {
        int identityCandidates = search("перчатки", "1.0", "", 0.0, 1000).size();
        int withQualifierAtThreshold = search("перчатки", "1.0",
                "смотровые|нитриловые|неопудренные", MIN_SCORE, 1000).size();
        int withoutQualifierAtThreshold = search("перчатки", "1.0", "", MIN_SCORE, 1000).size();

        assertThat(withQualifierAtThreshold)
                .describedAs("qualifier не может привести строку вне identity-отбора")
                .isLessThanOrEqualTo(identityCandidates);
        // и он действительно поднимает строки над порогом — иначе тест ничего не сторожил бы
        assertThat(withQualifierAtThreshold).isGreaterThan(withoutQualifierAtThreshold);
    }

    /**
     * Слово в чужом для запроса алфавите совпасть не может (word_similarity('монитор','monitor')
     * = 0), поэтому вся латиница входит в знаменатель prec как ОДНА необъяснённая единица,
     * а не поштучно (и не выбрасывается совсем — обе крайности разобраны в комментарии у SQL).
     * Раньше перечень моделей штрафовал запись за длину, которую кириллический лот всё равно
     * не покроет: у эталонной записи 60 из 64 значимых слов — латиница, скор был 0.031
     * (недостижима), стал <b>0.33333</b> при отсечке 0.2. По всему реестру отсечку благодаря
     * этому пересекают <b>1014 записей (7.2%)</b> из 14 072.
     */
    @Test
    void latinTradeNamesDoNotDiluteDenominatorForCyrillicQuery() {
        List<? extends RegistryCandidateRow> rows = search("катетер", "1.0", "", MIN_SCORE, 200);

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
        List<? extends RegistryCandidateRow> rows = search("морозильник", "1.0", "", MIN_SCORE, 10);

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
        assertThat(search("центрифуга", "1.0", "", MIN_SCORE, 5)).isNotEmpty();
    }
}
