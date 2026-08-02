package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ApparatusRow;
import com.vladoose.nir.dto.response.RegistryCandidateRow;
import com.vladoose.nir.dto.response.TokenDfRow;
import com.vladoose.nir.entity.MedRegistry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MedRegistryRepository extends JpaRepository<MedRegistry, Long> {

    Optional<MedRegistry> findByRegNumber(String regNumber);

    /**
     * Кандидаты по нечёткой триграммной похожести: производитель (0.6) + наименование (0.4).
     * Алиасы в camelCase без подчёркиваний → проекция матчится case-insensitive.
     */
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "m.unlimited AS unlimited, " +
            "(0.6 * GREATEST(similarity(m.producer, :manufact), word_similarity(:manufact, m.producer)) + " +
            " 0.4 * GREATEST(similarity(m.name, :name),         word_similarity(:name, m.name))) AS score " +
            "FROM med_registry m " +
            // только word_similarity (<%) — он индексо-дружелюбен (GIN gin_trgm_ops); оператор % (similarity)
            // по всему длинному названию форсил seq scan по 14k записям (~600мс/строку). Качество матчинга
            // сохраняется (word_similarity ловит вхождение названия изделия в реестр).
            "WHERE :manufact <% m.producer OR :name <% m.name " +
            "ORDER BY score DESC " +
            "LIMIT :limit")
    List<RegistryCandidateRow> findCandidates(@Param("name") String name,
                                              @Param("manufact") String manufact,
                                              @Param("limit") int limit);

    /**
     * Скоринг V2. Отбор кандидатов — ТОЛЬКО по identity-токенам (тот же индексо-дружелюбный
     * приём: IN(join tok &lt;% name) + OFFSET 0 как фенс от расплющивания планировщиком).
     *
     * <p><b>Что именно гарантирует qualifier.</b> Он не расширяет МНОЖЕСТВО КАНДИДАТОВ:
     * {@code :qualifiers} участвует только в LATERAL {@code q}, а отбор строк идёт исключительно
     * по {@code :tokens} — поэтому взрыв охвата «спектрофотометр 3 → 278» повториться не может.
     * Но qualifier ВЛИЯЕТ на итоговую выдачу: бонус прибавляется ДО отсечки {@code :minScore},
     * значит уже отобранные identity строки он может поднять выше порога (замер по «перчатки»:
     * 66 → 143 из 148 кандидатов). Инвариант — «не больше, чем отобрал identity», а не
     * «выдача не меняется».
     *
     * <p>score = F1(recall_q, precision_d) + bonus·qualifier_hit_ratio, где
     * <ul>
     *   <li>recall_q — взвешенное (IDF) покрытие запроса названием записи;</li>
     *   <li>precision_d — доля значимых (≥4 симв.) слов НАЗВАНИЯ ЗАПИСИ, покрытых запросом.
     *       Знаменатель: слова алфавита запроса поштучно + вся латиница как ОДНА единица
     *       (почему именно так и какие два «очевидных» варианта отвергнуты — в комментарии
     *       у знаменателя ниже). Это новый член: он нормирует по длине записи, чего в V1
     *       не было вообще, из-за чего «перчатки» давали 147 записей со скором 1.000.</li>
     * </ul>
     * Порог совпадения слова 0.6 = глобальный word_similarity_threshold; передаётся в запрос,
     * глобальную настройку не трогаем.
     */
    @Query(nativeQuery = true, value =
            "SELECT * FROM ( " +
            "  SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "         m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "         m.unlimited AS unlimited, " +
            "         (2 * r.recall * p.prec / NULLIF(r.recall + p.prec, 0) " +
            "          + :bonus * COALESCE(q.hit, 0)) AS score " +
            "  FROM med_registry m " +
            "  CROSS JOIN LATERAL ( " +
            "    SELECT sum(w.wgt::float8 * word_similarity(t.tok, m.name)) / sum(w.wgt::float8) AS recall " +
            "    FROM unnest(string_to_array(:tokens,'|'))  WITH ORDINALITY AS t(tok, i) " +
            "    JOIN unnest(string_to_array(:weights,'|')) WITH ORDINALITY AS w(wgt, j) ON t.i = w.j " +
            "  ) r " +
            // ЗНАМЕНАТЕЛЬ prec: слова СВОЕГО скрипта поштучно + вся латиница как ОДНА
            // необъяснённая единица. Обе «очевидные» крайности проверены на живом реестре и обе
            // неверны — не упрощать ни в ту, ни в другую сторону:
            //  • считать каждое чужое слово (как было) — модельный ряд топит запись: у «катетер
            //    ASAHI Hyperion Judkins Right…» 60 из 64 слов латинские, скор 0.031, запись
            //    недостижима для кириллического лота. Чужое слово совпасть НЕ МОЖЕТ в принципе:
            //    word_similarity('монитор','monitor') = 0, а не «мало» (разные скрипты не делят
            //    ни одной триграммы), то есть это мёртвый груз в знаменателе;
            //  • выкинуть чужие слова совсем — запись схлопывается до родового слова и получает
            //    prec = 1/1, то есть ИДЕАЛЬНЫЙ скор на общий запрос: «Насос Penumbra ENGINE™»
            //    давал 1.000 против верного «Вакуумный насос К-MAR-5200» 0.8167, и поднять
            //    верный ответ уже нечем — 1.000 это потолок шкалы. По реестру так схлопываются
            //    160 записей до одного кириллического слова и 752 до двух: чем сильнее запись
            //    обрендирована латиницей, тем «точнее» она выглядит.
            // LEAST(...,1) говорит ровно то, что мы имеем в виду: «здесь есть необъяснённое
            // брендовое содержание» — один раз. 60 слов модельного ряда ASAHI это одна
            // необъяснённая СУЩНОСТЬ, перечисленная 60 способами, а не 60 разных понятий.
            // lower(:tokens), а не :tokens — остальной запрос регистронезависим (pg_trgm
            // лоуэркейзит сам), а голый '[а-яё]' на токене «ПЕРЧАТКИ» не сматчится: предикат
            // молча обнулил бы знаменатель и вернул пустую выдачу.
            "  CROSS JOIN LATERAL ( " +
            "    SELECT count(*) FILTER (WHERE EXISTS ( " +
            "             SELECT 1 FROM unnest(string_to_array(:tokens,'|')) tk(tok) " +
            "             WHERE word_similarity(tk.tok, d.w) >= 0.6))::float8 " +
            "           / greatest( " +
            "               count(*) FILTER (WHERE (d.w ~ '[а-яё]' AND lower(:tokens) ~ '[а-яё]') " +
            "                                   OR (d.w ~ '[a-z]'  AND lower(:tokens) ~ '[a-z]')) " +
            "             + least(count(*) FILTER (WHERE NOT ((d.w ~ '[а-яё]' AND lower(:tokens) ~ '[а-яё]') " +
            "                                             OR (d.w ~ '[a-z]'  AND lower(:tokens) ~ '[a-z]'))), 1) " +
            "             , 1) AS prec " +
            "    FROM unnest(string_to_array(lower(regexp_replace(m.name,'[^[:alpha:]]',' ','g')),' ')) d(w) " +
            "    WHERE length(d.w) >= 4 " +
            "  ) p " +
            "  CROSS JOIN LATERAL ( " +
            "    SELECT count(*) FILTER (WHERE word_similarity(qt.tok, m.name) >= 0.6)::float8 " +
            "           / greatest(count(*) FILTER (WHERE qt.tok <> ''), 1) AS hit " +
            "    FROM unnest(string_to_array(:qualifiers,'|')) qt(tok) WHERE qt.tok <> '' " +
            "  ) q " +
            "  WHERE m.id IN (SELECT m2.id FROM unnest(string_to_array(:tokens,'|')) tk(tok) " +
            "                 JOIN med_registry m2 ON tk.tok <% m2.name) " +
            "  OFFSET 0 " +
            ") s WHERE s.score >= :minScore " +
            "ORDER BY s.score DESC " +
            "LIMIT :limit")
    List<RegistryCandidateRow> searchByTokensV2(@Param("tokens") String tokens,
                                                @Param("weights") String weights,
                                                @Param("qualifiers") String qualifiers,
                                                @Param("bonus") double bonus,
                                                @Param("minScore") double minScore,
                                                @Param("limit") int limit);

    /**
     * Частотность (document frequency) каждого токена: сколько записей реестра пословно похожи
     * на него (индексо-дружелюбный {@code <%} по GIN). Питает IDF-веса матча: редкий токен
     * («томограф», «ангиографическая») получает больший вес, чем частый («компьютерный»).
     * Один запрос на все токены (LEFT JOIN → df=0 для несовпавших). Токены — строкой через '|'.
     */
    @Query(nativeQuery = true, value =
            "SELECT t.tok AS tok, count(m.id) AS df " +
            "FROM unnest(string_to_array(:tokens,'|')) t(tok) " +
            "LEFT JOIN med_registry m ON t.tok <% m.name " +
            "GROUP BY t.tok")
    List<TokenDfRow> tokenDocFreq(@Param("tokens") String tokens);

    /**
     * Аппараты-кандидаты по бренду из ТЗ: только записи типа «(МТ)» (аппаратура, у них есть комплектность),
     * индексо-дружелюбный `<%` (word_similarity ≥ глобального порога). Бренд в реестре живёт то в name,
     * то в producer («Система … HS60» + producer «Самсунг Медисон») → матчим ОБА поля (оба под GIN-trgm),
     * ранг — по большему из двух сходств. Разные скрипты бренда разводит {@code BrandTransliterator} выше.
     */
    @Query(nativeQuery = true, value =
            "SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "       m.country AS country, m.ndda_id AS nddaId " +
            "FROM med_registry m " +
            "WHERE m.reg_number LIKE '%(МТ)%' AND (:term <% m.name OR :term <% m.producer) " +
            "ORDER BY greatest(word_similarity(:term, m.name), word_similarity(:term, m.producer)) DESC " +
            "LIMIT :limit")
    List<ApparatusRow> findApparatusByTerm(@Param("term") String term, @Param("limit") int limit);
}
