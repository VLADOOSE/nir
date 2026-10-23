package com.vladoose.nir.dto.response;

import com.vladoose.nir.dto.request.ClientOfferItemDto;
import com.vladoose.nir.entity.*;
import com.vladoose.nir.service.offer.OfferTotals;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Data
public class ClientOfferResponse {
    private Long id;
    private Integer number;
    private ClientOfferOrigin origin;
    /** Файл готового КП (без байтов и таблиц) — только у origin = FILE. */
    private ClientOfferFileMeta file;
    private LocalDate offerDate;
    private ClientOfferStatus status;
    private Long facilityId;
    private String facilityName;
    private String recipient;
    private Long tenderId;
    /** Номер заявки-источника («из заявки ЧЗ-…»). */
    private String tenderNumber;
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
    /** Наш курс по валютам рынка и курс сегодня (спека 2026-10-23 §4.5): переключатель валюты закупки, плашка «Пересчитать». */
    private Map<String, BigDecimal> ourRates;
    private Map<String, TodayRate> todayRates;

    public record TodayRate(BigDecimal rate, LocalDate date) {}
}
