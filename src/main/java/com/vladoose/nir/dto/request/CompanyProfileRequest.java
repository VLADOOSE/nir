package com.vladoose.nir.dto.request;

import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import jakarta.validation.constraints.*;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** Реквизиты и умолчания КП рынка (страница «Реквизиты и печать»). Картинки — отдельными запросами. */
@Data
public class CompanyProfileRequest {
    @NotBlank(message = "Краткое название обязательно") @Size(max = 255) private String shortName;
    @Size(max = 500) private String fullName;
    @Size(max = 1000) private String headerLeft;
    @Size(max = 1000) private String headerRight;
    @Size(max = 100) private String brandText;
    @Size(max = 500) private String idsLine;
    @Size(max = 20) private String binInn;
    @Size(max = 1000) private String address;
    @Size(max = 1000) private String accounts;
    @Size(max = 255) private String bankName;
    @Size(max = 20) private String bik;
    @Size(max = 100) private String phone;
    @Size(max = 255) private String email;
    @Size(max = 100) private String directorTitle;
    @Size(max = 255) private String directorName;
    @Size(max = 500) private String signoffContacts;
    @Min(value = 20, message = "Печать — от 20 мм") @Max(value = 60, message = "Печать — до 60 мм") private int stampSizeMm = 40;
    @NotNull private List<BigDecimal> vatRates;
    private BigDecimal vatDefault;
    private BigDecimal vatRegistered;
    private BigDecimal vatNotRegistrable;
    @NotNull @DecimalMin("-100") @DecimalMax("1000") private BigDecimal defaultMarkupPct;
    @NotNull private List<OfferColumn> defaultColumns;
    @NotNull private List<OfferTerm> defaultTerms;
    @NotNull private TermsStyle defaultTermsStyle;
    @Size(max = 2000) private String defaultIntro;
    @Min(value = 1, message = "Номер — от 1") private int nextNumber = 1;
}
