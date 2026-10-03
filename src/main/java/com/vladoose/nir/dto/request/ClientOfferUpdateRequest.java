package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferRounding;
import com.vladoose.nir.entity.OfferSignoff;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Автосохранение КП целиком (спека §8.3): шапка, настройки и строки. version — защита от второй вкладки. */
@Data
public class ClientOfferUpdateRequest {
    @NotNull private Integer version;
    @NotNull @Min(value = 1, message = "Номер — от 1") private Integer number;
    @NotNull(message = "Дата КП обязательна") private LocalDate offerDate;
    private Long facilityId;
    @Size(max = 2000) private String recipient;
    @NotBlank(message = "Заголовок не может быть пустым") @Size(max = 200) private String title;
    @Size(max = 1000) private String subject;
    @Size(max = 4000) private String intro;
    private boolean vatEnabled = true;
    @NotNull @DecimalMin(value = "-100", message = "Наценка — от −100%") @DecimalMax(value = "1000", message = "Наценка — до 1000%")
    private BigDecimal defaultMarkupPct;
    @NotNull private OfferRounding rounding;
    @NotNull private List<OfferColumn> columns;
    private boolean detailsInName = true;
    @NotNull private List<OfferTerm> terms;
    @NotNull private TermsStyle termsStyle;
    private boolean showAmountInWords = true;
    private boolean showVatBreakdown = true;
    private boolean landscape;
    @NotNull private OfferSignoff signoff;
    private boolean signoffContacts;
    private boolean withStamp;
    @Size(max = 4000) private String internalNote;
    @NotNull @Size(max = 500, message = "Строк больше 500") @Valid private List<ClientOfferItemDto> items;
}
