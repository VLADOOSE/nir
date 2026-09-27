package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

/** Короткая ссылка на обращение: «этот номер уже обращался», обратная ссылка из частной заявки. */
@Data
public class LeadRefResponse {
    private Long id;
    private String subject;
    private String source;
    private String status;
    private OffsetDateTime receivedAt;
}
