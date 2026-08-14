package com.vladoose.nir.dto.response;

/**
 * Проекция скоринга V2: кандидат + {@code rivals}.
 *
 * <p>Отдельный интерфейс, а не поле в {@link RegistryCandidateRow}, потому что {@code rivals}
 * отдаёт только {@code searchByTokensV2}. Добавь его в общую проекцию — и бренд-путь
 * {@code findCandidates}, у которого такой колонки нет, падал бы при обращении к геттеру.
 */
public interface RegistryCandidateRowV2 extends RegistryCandidateRow {

    /**
     * Сколько записей реестра ПОЛНОСТЬЮ покрывают identity-запрос, то есть сколько равноправных
     * ответов есть у лота в принципе. Одинаково у всех строк выдачи (окно по всему пулу).
     */
    Integer getRivals();
}
