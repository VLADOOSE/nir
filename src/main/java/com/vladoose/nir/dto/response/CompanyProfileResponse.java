package com.vladoose.nir.dto.response;

import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.OfferColumn;
import com.vladoose.nir.entity.OfferTerm;
import com.vladoose.nir.entity.TermsStyle;
import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/** Профиль рынка без байтов картинок: только «есть ли» и когда менялись. */
@Data
public class CompanyProfileResponse {
    private String market;
    private String currency;
    private String shortName;
    private String fullName;
    private String headerLeft;
    private String headerRight;
    private String brandText;
    private String idsLine;
    private String binInn;
    private String address;
    private String accounts;
    private String bankName;
    private String bik;
    private String phone;
    private String email;
    private String directorTitle;
    private String directorName;
    private String signoffContacts;
    private int stampSizeMm;
    private List<BigDecimal> vatRates;
    private BigDecimal vatDefault;
    private BigDecimal vatRegistered;
    private BigDecimal vatNotRegistrable;
    private BigDecimal defaultMarkupPct;
    private List<OfferColumn> defaultColumns;
    private List<OfferTerm> defaultTerms;
    private TermsStyle defaultTermsStyle;
    private String defaultIntro;
    private int nextNumber;
    private boolean hasLogo;
    private boolean hasStamp;
    private boolean hasSignature;
    private OffsetDateTime imagesUpdatedAt;
    private OffsetDateTime updatedAt;

    public static CompanyProfileResponse of(CompanyProfile p) {
        CompanyProfileResponse r = new CompanyProfileResponse();
        r.market = p.getMarket().name();
        r.currency = p.getMarket().currencyCode();
        r.shortName = p.getShortName();
        r.fullName = p.getFullName();
        r.headerLeft = p.getHeaderLeft();
        r.headerRight = p.getHeaderRight();
        r.brandText = p.getBrandText();
        r.idsLine = p.getIdsLine();
        r.binInn = p.getBinInn();
        r.address = p.getAddress();
        r.accounts = p.getAccounts();
        r.bankName = p.getBankName();
        r.bik = p.getBik();
        r.phone = p.getPhone();
        r.email = p.getEmail();
        r.directorTitle = p.getDirectorTitle();
        r.directorName = p.getDirectorName();
        r.signoffContacts = p.getSignoffContacts();
        r.stampSizeMm = p.getStampSizeMm();
        r.vatRates = p.getVatRates();
        r.vatDefault = p.getVatDefault();
        r.vatRegistered = p.getVatRegistered();
        r.vatNotRegistrable = p.getVatNotRegistrable();
        r.defaultMarkupPct = p.getDefaultMarkupPct();
        r.defaultColumns = p.getDefaultColumns();
        r.defaultTerms = p.getDefaultTerms();
        r.defaultTermsStyle = p.getDefaultTermsStyle();
        r.defaultIntro = p.getDefaultIntro();
        r.nextNumber = p.getNextNumber();
        r.hasLogo = p.getLogoPng() != null;
        r.hasStamp = p.getStampPng() != null;
        r.hasSignature = p.getSignaturePng() != null;
        r.imagesUpdatedAt = p.getImagesUpdatedAt();
        r.updatedAt = p.getUpdatedAt();
        return r;
    }
}
