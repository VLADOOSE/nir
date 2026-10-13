package com.vladoose.nir.dto.response;

/** Позиция каталога в поиске «Из реестра и каталога»: РУ — если привязан. */
public record EquipmentSearchItemResponse(Long id, String name, String manufact, RegistryCandidateResponse registry) {}
