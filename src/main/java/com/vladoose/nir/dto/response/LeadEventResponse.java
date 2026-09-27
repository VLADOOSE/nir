package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class LeadEventResponse {
    private Long id;
    private OffsetDateTime at;
    private String type;
    private String direction;
    private String channel;
    private String author;
    private String body;
}
