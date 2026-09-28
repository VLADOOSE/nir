package com.vladoose.nir.dto.response;

/** Обращение чата в списке и шапке: id и статус (NEW / IN_WORK / CONVERTED / CLOSED). */
public record ChatLeadRef(Long id, String status) {}
