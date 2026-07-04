package com.vladoose.nir.repository;

import com.vladoose.nir.dto.response.RegistryCandidateRow;
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
     * Пословный триграммный матч значимых токенов лота: кандидаты — через IN(join «tok <% name»)
     * + OFFSET 0 (фенс от расплющивания планировщиком) → Bitmap Index Scan по idx_reg_name_trgm
     * (~64мс против ~650мс seq scan у наивного EXISTS: score-сабплан считался на все 14k строк).
     * Глобальный порог 0.6 не трогаем. Ранг — взвешенное покрытие ВСЕХ токенов
     * (Σ wᵢ·word_similarity(tᵢ,name)/Σ wᵢ), отсечка мусора score >= 0.2.
     * Токены/веса — строками через '|' (string_to_array), чтобы не возиться с массивами в Hibernate.
     */
    @Query(nativeQuery = true, value =
            "SELECT * FROM ( " +
            "  SELECT m.reg_number AS regNumber, m.name AS name, m.producer AS producer, " +
            "         m.country AS country, m.reg_date AS regDate, m.expiration_date AS expirationDate, " +
            "         m.unlimited AS unlimited, " +
            "         (SELECT sum(w.wgt::float8 * word_similarity(t.tok, m.name)) / sum(w.wgt::float8) " +
            "          FROM unnest(string_to_array(:tokens,'|'))  WITH ORDINALITY AS t(tok, i) " +
            "          JOIN unnest(string_to_array(:weights,'|')) WITH ORDINALITY AS w(wgt, j) ON t.i = w.j " +
            "         ) AS score " +
            "  FROM med_registry m " +
            "  WHERE m.id IN (SELECT m2.id FROM unnest(string_to_array(:tokens,'|')) tk(tok) " +
            "                 JOIN med_registry m2 ON tk.tok <% m2.name) " +
            "  OFFSET 0 " +
            ") s WHERE s.score >= 0.2 " +
            "ORDER BY s.score DESC " +
            "LIMIT :limit")
    List<RegistryCandidateRow> searchByTokens(@Param("tokens") String tokens,
                                              @Param("weights") String weights,
                                              @Param("limit") int limit);
}
