package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

/** Ответ панели «Подбор» у лота: кандидаты + честная оценка того, можно ли им верить. */
@Data
public class LotRegistryMatchResponse {
    private List<RegistryCandidateResponse> candidates;
    /** Зона честности; заменила прежний distinctive (тот мерил запрос, а не результат). */
    private MatchConfidence confidence;
    /** Заполнено только при confidence == CANNOT. */
    private CannotReason cannotReason;
    /** ТЗ разобрано (в requiredSpec есть блок характеристик). */
    private boolean techSpecParsed;
}
