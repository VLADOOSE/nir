package com.vladoose.nir.dto.response;

import lombok.Data;

import java.util.List;

/** Ответ панели «Реестр» у лота: кандидаты + метаданные достоверности матча. */
@Data
public class LotRegistryMatchResponse {
    private List<RegistryCandidateResponse> candidates;
    private boolean distinctive;    // есть чем различать записи (≥2 значимых токена или задан бренд); иначе % врёт
    private boolean techSpecParsed; // ТЗ разобрано (в requiredSpec есть блок характеристик)
}
