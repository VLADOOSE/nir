package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Slim tender projection used in nested references (without lots and facility),
 * to avoid recursion when serializing graphs like TenderLotResponse.tender or
 * ActivityApplyResponse.tender.
 */
@Data
public class TenderShortResponse {
    private Long id;
    private String tenderNumber;
    private String status;
    private LocalDate deadline;
    /** Время окончания приёма с площадки — местное время площадки; пусто у ручных тендеров (B2). */
    private LocalDateTime deadlineAt;
}
