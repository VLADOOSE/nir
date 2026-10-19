package com.vladoose.nir.dto.request;

import java.util.List;

/**
 * Письмо клиенту (спека 2026-10-19-client-mail-reply §8). chatId + replyToMessageId — ответ в переписку; chatId без
 * replyToMessageId — новой темой на адрес переписки; без chatId — новое письмо на адреса to. leadId / tenderId — откуда
 * открыли окно (событие обращения / сделки). token — окна письма: повтор того же запроса — 409.
 */
public record ClientMailSendRequest(Long chatId, Long replyToMessageId, String to, String subject, String text,
                                    Long leadId, Long tenderId, List<OfferAttachment> offers, String token) {

    /** КП во вложении: PDF / DOCX — из конструктора, FILE — файл готового КП. */
    public record OfferAttachment(Long id, String format) {}
}
