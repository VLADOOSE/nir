package com.vladoose.nir.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDate;
import java.util.List;

/** «Отправить N писем» (спека 2026-10-12 §6.2): каждому поставщику — одно письмо только с его позициями. */
@Data
public class RfqSendRequest {
    private LocalDate replyBy;
    /** Приложить запрос на бланке (Word) и поставить в нём подпись и печать. */
    private boolean withDocument;
    private boolean withStamp;
    @NotEmpty(message = "Не выбраны поставщики")
    @Valid
    private List<Supplier> suppliers;

    @Data
    public static class Supplier {
        /** Поставщик справочника; пусто — новый (newSupplier). */
        private Long distributorId;
        @Valid
        private NewSupplier newSupplier;
        /** Почта из окна: null — почта карточки; пустая — запрос без письма; иначе — один или несколько адресов. */
        private String email;
        /** «Указать клиента» — строка о конечном заказчике в письме и документе. */
        private boolean discloseClient;
        @NotEmpty(message = "Не выбраны позиции")
        private List<Long> lotIds;
    }

    /** Новый поставщик заводится в «Дистрибьюторах» при отправке. */
    @Data
    public static class NewSupplier {
        private String name;
        /** «Запомнить: возит <бренды выбранных позиций>». */
        private boolean rememberBrands = true;
    }
}
