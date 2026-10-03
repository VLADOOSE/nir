package com.vladoose.nir.dto.response;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.OfferTotals;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ClientOfferResponse {
    private Long id;
    private Integer number;
    private LocalDate offerDate;
    private ClientOfferStatus status;
    private Long facilityId;
    private String facilityName;
    private String recipient;
    private Long tenderId;
    private String title;
    private String subject;
    private String intro;
    private boolean vatEnabled;
    private BigDecimal defaultMarkupPct;
    private OfferRounding rounding;
    private List<OfferColumn> columns;
    private boolean detailsInName;
    private List<OfferTerm> terms;
    private TermsStyle termsStyle;
    private boolean showAmountInWords;
    private boolean showVatBreakdown;
    private boolean landscape;
    private OfferSignoff signoff;
    private boolean signoffContacts;
    private boolean withStamp;
    private String internalNote;
    private int version;
    private String currency;
    private List<ClientOfferItemDto> items;
    private OfferTotals totals;
    private String fileBaseName;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime sentAt;
}
