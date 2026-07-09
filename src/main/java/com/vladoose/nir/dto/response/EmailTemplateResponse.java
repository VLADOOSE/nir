package com.vladoose.nir.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailTemplateResponse {
    private String market;
    private String subject;
    private String body;
    private OffsetDateTime updatedAt;
    private List<String> warnings;
}
