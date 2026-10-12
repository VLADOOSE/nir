package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Карточка в списке «Частные заявки» (спека §5.5). */
@Data
public class PrivateRequestListItemResponse {
    private Long id;
    private String number;
    private String status;
    private String stage;
    private String stageText;
    private String clientLabel;
    private long lineCount;
    private OffsetDateTime createdAt;
    /** обращение, из которого создана заявка, — значок «из обращения» */
    private Long leadId;
}
