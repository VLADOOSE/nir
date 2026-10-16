package com.vladoose.nir.dto.response;

/**
 * Обращение чата в списке и шапке: id и статус (NEW / IN_WORK / CONVERTED / CLOSED). Заявка, в которую обращение
 * превращено, — id и номер («ЧЗ-…»), только в шапке чата (GET /api/chats/{id}); в списке чатов — пусто.
 */
public record ChatLeadRef(Long id, String status, Long privateRequestId, String privateRequestNumber) {}
