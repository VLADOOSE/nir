package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class LeadListItemResponse {
    private Long id;
    private String channel;
    private String source;
    private String subject;
    private String contactName;
    private String contactPhone;
    private String company;
    private int itemsCount;
    private List<String> itemsPreview;
    private String messagePreview;
    private String status;
    private String closeReason;
    private OffsetDateTime receivedAt;
    private Long privateRequestId;
    private String privateRequestNumber;
    /** Статус на сайт записать не удалось (повторим автоматически). */
    private boolean syncError;
}
