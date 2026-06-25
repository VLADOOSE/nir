package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class InboundEmailResponse {
    private Long id;
    private String fromAddress;
    private String subject;
    private OffsetDateTime receivedAt;
    private String type;
    private Long matchedPriceRequestId;
    private String attachmentName;
    private boolean hasAttachment;
    private String excerpt;
    private String status;
}
