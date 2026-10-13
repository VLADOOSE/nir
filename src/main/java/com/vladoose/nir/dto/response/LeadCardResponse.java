package com.vladoose.nir.dto.response;

import com.vladoose.nir.dto.request.LeadItemDto;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

@Data
public class LeadCardResponse {
    private Long id;
    private String channel;
    private String source;
    private String subject;
    private String contactName;
    private String contactPhone;
    private String phoneNorm;
    private String contactEmail;
    private String company;
    private String message;
    private String status;
    private String closeReason;
    private OffsetDateTime receivedAt;
    private Long facilityId;
    private String facilityName;
    private Long privateRequestId;
    private String privateRequestNumber;
    private String extStatus;
    private String extStatusPending;
    private String extSyncError;
    /** Чат обращения (WhatsApp или почта); null — к чату не привязано. */
    private Long chatId;
    /** Канал этого чата: WHATSAPP / EMAIL; null — чата нет. */
    private String chatChannel;
    private List<LeadItemDto> items;
    private List<LeadEventResponse> events;
    private List<LeadRefResponse> samePhone;
}
