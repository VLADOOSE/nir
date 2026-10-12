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
