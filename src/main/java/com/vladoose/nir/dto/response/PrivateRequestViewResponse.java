package com.vladoose.nir.dto.response;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;

/** Страница частной заявки (спека §5). stage/stageText заполняет задача 3. */
@Data
public class PrivateRequestViewResponse {
    private Long id;
    private String number;
    /** NEW | WON | LOST | CANCELLED */
    private String status;
    private String stage;
    private String stageText;
    /** Этапы всего цикла и «Дальше» (спека 2026-10-18 §3.1). */
    private DealProgress progress;
    /** Исполнение сделки — раздел после «КП клиенту»; пусто, пока нет принятого КП и данных (спека 2026-10-19-deal-execution §4.1). */
    private DealExecutionView execution;
    private Long facilityId;
    private String facilityName;
    private String customerName;
    /** как клиент показывается: учреждение → текст → контакт → телефон → «Клиент не указан» */
    private String clientLabel;
    private String contactName;
    private String contactPhone;
    private String contactEmail;
    private String deliveryAddress;
    private String note;
    private OffsetDateTime createdAt;
    private LeadRefResponse lead;
    private List<PrivateRequestLineView> lines;
    /** КП клиенту по заявке, новые сверху (спека §8). */
    private List<PrivateRequestOfferRef> offers;
}
