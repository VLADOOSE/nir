package com.vladoose.nir.dto.request;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** Письмо или документ для одного поставщика до отправки (спека §6.2): из справочника или ещё не заведённый. */
@Data
public class RfqPreviewRequest {
    private Long distributorId;
    private String newSupplierName;
    @NotEmpty(message = "Не выбраны позиции")
    private List<Long> lotIds;
    private LocalDate replyBy;
    private boolean discloseClient;
    private boolean withDocument;
    private boolean withStamp;
}
