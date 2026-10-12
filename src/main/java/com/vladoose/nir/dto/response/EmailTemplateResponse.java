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
    /** Тексты запроса на бланке — сохранённые или зашитые (RfqTexts); пункты — по одному на строку. */
    private String rfqTitle;
    private String rfqIntro;
    private String rfqAsk;
    private String rfqClientLine;
}
