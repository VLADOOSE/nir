package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.ApparatusRow;
import com.vladoose.nir.dto.response.RegistryCandidateRow;
import com.vladoose.nir.dto.response.RegistryCandidateRowV2;
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
     * <p>score = F<sub>β</sub>(recall_q, precision_d) + bonus·qualifier_hit_ratio, где
     * <ul>
     *   <li>recall_q — взвешенное (IDF) покрытие запроса названием записи;</li>
     *   <li>precision_d — доля значимых (≥4 симв.) слов НАЗВАНИЯ ЗАПИСИ, покрытых запросом,
     *       со СГЛАЖЕННЫМ знаменателем {@code nhit/(nden + :smoothing)}.
     *       Знаменатель: слова алфавита запроса поштучно + вся латиница как ОДНА единица
     *       (почему именно так и какие два «очевидных» варианта отвергнуты — в комментарии
     *       у знаменателя ниже). Это новый член: он нормирует по длине записи, чего в V1
     *       не было вообще, из-за чего «перчатки» давали 147 записей со скором 1.000.</li>
     * </ul>
     *
     * <p><b>Почему F<sub>β</sub>, а не F1, и почему знаменатель сглажен (калибровка 2026-08-02,
     * golden-набор 71 лот / реестр 14072).</b> При одном-двух identity-токенах симметричный F1
     * вырождался в приз за КОРОТКОЕ название реестра: у однотокенного запроса скор алгебраически
     * схлопывается в {@code 2/(nsig+1)} — чистую функцию ДЛИНЫ названия записи, не несущую
     * никакой информации о качестве совпадения, а однословных имён лотов в корпусе 52.5 %.
     * Мерялись все три варианта из плана, по одному за раз:
     * <ul>
     *   <li>(а) сглаживание знаменателя {@code nden+2} В ОДИНОЧКУ ранжирование НЕ двигает
     *       (recall@5 10/19 → 10/19): оно монотонно по nden, поэтому порядок записей с равным
     *       recall сохраняет — сжимает шкалу, но не переставляет;</li>
     *   <li>(б) IDF-взвешивание qualifier-попаданий — при бонусе 0.5 метрики те же, что у простой
     *       доли, поэтому оставлена простая доля как более дешёвая;</li>
     *   <li>(в) сдвиг баланса к recall (β=1.5) — единственное, что переставляет выдачу.</li>
     * </ul>
     * Принято (в) + (а) + бонус qualifier 0.3 → 0.5; итог в рабочей точке — recall@5 10 → 13/19,
     * precision@1 9 → 11/19. Бонус — рычаг случая «класс верный, запись не та»: у лота
     * «Стерилизатор» ТЗ прямым текстом называет «плазменного стерилизатора Lowtem», и при бонусе
     * 0.3 этот сигнал тонул в разнице длин названий.
     *
     * <p>⚠️ Точные цифры по каждой оси — в {@code RegistryMatchService} рядом с константами:
     * они перемерены СОВМЕСТНО в финальной рабочей точке (fix-round 1), а не покоординатно,
     * потому что покоординатный спуск здесь давал ложный оптимум.
     *
     * <p><b>Отвергнуто с цифрами:</b> расширение qualifier-текста (санитайз goszakup-шапки через
     * {@code LotDescriptiveText}) и подъём потолка qualifier-токенов 5 → 12/25 метрик НЕ меняют,
     * а в связке с бонусом 0.5 даже теряют один кейс: лишние токены
     * размывают долю попаданий сильнее, чем добавляют сигнала. Способ сборки запроса оставлен
     * как был.
     *
     * <p><b>{@code rivals}</b> — сколько записей реестра ПОЛНОСТЬЮ покрывают identity-запрос
     * ({@code recall >= :fullCover}), то есть сколько равноправных ответов у лота есть в
     * принципе. Считается окном по ВСЕМУ отобранному пулу (до отсечки {@code :minScore}),
     * поэтому одинаково у всех строк выдачи. Это честная мера неразличимости: «Перчатки» дают
     * 147 равноправных записей, и показывать первую с процентом — враньё независимо от её скора.
     *
     * <p>Порог совпадения слова 0.6 = глобальный word_similarity_threshold; передаётся в запрос,
     * глобальную настройку не трогаем.
     */
    @Query(nativeQuery = true, value =
            "SELECT * FROM ( " +
            "  SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "         m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "         m.unlimited AS unlimited, " +
            "         ((1 + :beta2) * r.recall * (p.nhit / (p.nden + :smoothing)) " +
            "          / NULLIF(:beta2 * (p.nhit / (p.nden + :smoothing)) + r.recall, 0) " +
            "          + :bonus * COALESCE(q.hit, 0)) AS score, " +
            // окно по всему пулу: считается ДО отсечки :minScore, поэтому не зависит от того,
            // сколько строк переживёт порог — иначе «сколько равноправных ответов» мерило бы
            // само себя после фильтра
            "         CAST(count(*) FILTER (WHERE r.recall >= :fullCover) OVER () AS int) AS rivals " +
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
            "             WHERE word_similarity(tk.tok, d.w) >= 0.6))::float8 AS nhit, " +
            "           greatest( " +
            "               count(*) FILTER (WHERE (d.w ~ '[а-яё]' AND lower(:tokens) ~ '[а-яё]') " +
            "                                   OR (d.w ~ '[a-z]'  AND lower(:tokens) ~ '[a-z]')) " +
            "             + least(count(*) FILTER (WHERE NOT ((d.w ~ '[а-яё]' AND lower(:tokens) ~ '[а-яё]') " +
            "                                             OR (d.w ~ '[a-z]'  AND lower(:tokens) ~ '[a-z]'))), 1) " +
            "             , 1)::float8 AS nden " +
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
            // Тай-брейк ОБЯЗАТЕЛЕН, а не косметика: скоры ложатся на дискретную решётку, и
            // совпадения бит-в-бит массовые — у «Магнитно-резонансный томограф (безгелиевый)»
            // 11+ записей с одинаковым 0.393939. Без второго ключа сортировки то, какая из них
            // попадёт в LIMIT, решает heapsort, и выдача МЕНЯЕТСЯ ОТ LIMIT: тот же лот при
            // limit=5/6/10 давал разный топ-5. Прод зовёт matchForLotUi(id, min(limit,20)),
            // а гейт — limit=5, так что без тай-брейка гейт мерил другую выдачу, чем видит
            // оператор, и метрика была подбрасыванием монеты.
            "ORDER BY s.score DESC, s.regNumber " +
            "LIMIT :limit")
    List<RegistryCandidateRowV2> searchByTokensV2(@Param("tokens") String tokens,
                                                  @Param("weights") String weights,
                                                  @Param("qualifiers") String qualifiers,
                                                  @Param("beta2") double beta2,
                                                  @Param("smoothing") double smoothing,
                                                  @Param("bonus") double bonus,
                                                  @Param("fullCover") double fullCover,
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
