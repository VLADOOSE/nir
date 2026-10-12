package com.vladoose.nir.dto.response;

import lombok.Data;

/** Позиция на странице заявки. registry — запись реестра для подсказки или подтверждённого РУ. */
@Data
public class PrivateRequestLineView {
    private Long lotId;
    private Integer lineNo;
    private String name;
    private String brand;
    private Integer quantity;
    private String unit;
    private String spec;
    private String productUrl;
    /** null | SUGGESTED | CONFIRMED | NOT_FOUND | NOT_REQUIRED */
    private String regStatus;
    private RegistryCandidateResponse registry;
    /** по позиции есть запросы цен — удалить нельзя */
    private boolean requested;
}
