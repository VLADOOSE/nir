package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class InboundEmailResponse {
    private Long id;
    /** Ящик письма (zakup@ / info@) нижним регистром; у писем до V24 — null. */
    private String mailbox;
    private String fromAddress;
    private String subject;
    private OffsetDateTime receivedAt;
    private String type;
    private Long matchedPriceRequestId;
    private String attachmentName;
    private boolean hasAttachment;
    private String excerpt;
    private String status;
    /** Файлы ответа поставщика из этого письма (supplier_reply_file) — во «Входящих» рядом с «Сверить». */
    private List<String> files = List.of();
    /** Файлы письма «прочее» для «Занести как прайс» (спека 2026-10-23 §5.1): без байтов. */
    private List<PriceFile> priceFiles = List.of();

    public record PriceFile(Long id, String name, boolean priceLike) {}
}
