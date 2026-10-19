package com.vladoose.nir.dto.response;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * Данные окна письма клиенту (спека 2026-10-19-client-mail-reply §4, §8): можно ли отправлять и почему нет, от кого,
 * переписка и письмо, на которое отвечаем (или адреса нового письма), шаблоны, подпись с ФИО отправителя, КП сделки.
 */
public record ClientMailComposeResponse(boolean enabled, String reason, String from, Long chatId, String chatTitle,
                                        List<String> to, Reply reply, String newSubject, boolean firstLetter,
                                        List<ReplyTemplateDto> templates, String signature, List<OfferRef> offers,
                                        Long offerId, Long leadId, Long tenderId) {

    public record Reply(Long messageId, String subject, String replySubject, OffsetDateTime sentAt, String fromName,
                        String fromAddress, String direction) {}

    /** КП сделки для «📎 КП»: у готового КП — его файл, у КП из конструктора — PDF (Word — по флажку). */
    public record OfferRef(Long id, String label, String origin, String status, String fileName) {}
}
