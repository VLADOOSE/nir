package com.vladoose.nir.service;

import com.vladoose.nir.context.MarketContext;
import com.vladoose.nir.dto.request.CompanyProfileRequest;
import com.vladoose.nir.entity.CompanyProfile;
import com.vladoose.nir.entity.Market;
import com.vladoose.nir.exception.BadRequestException;
import com.vladoose.nir.exception.NotFoundException;
import com.vladoose.nir.repository.CompanyProfileRepository;
import com.vladoose.nir.service.offer.OfferSettingsValidator;
import com.vladoose.nir.util.DocFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/** Реквизиты и настройки КП рынка (спека §7). Строка на рынок — по MarketContext, как EmailTemplateService. */
@Service
public class CompanyProfileService {

    private final CompanyProfileRepository repository;
    private final ImageProcessor images;
    private final JdbcTemplate jdbc;

    public CompanyProfileService(CompanyProfileRepository repository, ImageProcessor images, JdbcTemplate jdbc) {
        this.repository = repository;
        this.images = images;
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public CompanyProfile current() {
        return forMarket(MarketContext.get());
    }

    @Transactional(readOnly = true)
    public CompanyProfile forMarket(Market market) {
        return repository.findByMarket(market)
                .orElseThrow(() -> new NotFoundException("Реквизиты рынка " + market + " не найдены"));
    }

    @Transactional
    public CompanyProfile update(CompanyProfileRequest r) {
        OfferSettingsValidator.vatRates(r.getVatRates());
        requireRate(r.getVatRates(), r.getVatDefault(), "новой строки");
        requireRate(r.getVatRates(), r.getVatRegistered(), "для подтверждённого РУ");
        requireRate(r.getVatRates(), r.getVatNotRegistrable(), "для «не подлежит регистрации»");
        OfferSettingsValidator.columns(r.getDefaultColumns());
        OfferSettingsValidator.terms(r.getDefaultTerms());

        CompanyProfile p = current();
        p.setShortName(r.getShortName().trim());
        p.setFullName(trim(r.getFullName()));
        p.setHeaderLeft(trim(r.getHeaderLeft()));
        p.setHeaderRight(trim(r.getHeaderRight()));
        p.setBrandText(trim(r.getBrandText()));
        p.setIdsLine(trim(r.getIdsLine()));
        p.setBinInn(trim(r.getBinInn()));
        p.setAddress(trim(r.getAddress()));
        p.setAccounts(trim(r.getAccounts()));
        p.setBankName(trim(r.getBankName()));
        p.setBik(trim(r.getBik()));
        p.setPhone(trim(r.getPhone()));
        p.setEmail(trim(r.getEmail()));
        p.setDirectorTitle(trim(r.getDirectorTitle()));
        p.setDirectorName(trim(r.getDirectorName()));
        p.setSignoffContacts(trim(r.getSignoffContacts()));
        p.setStampSizeMm(r.getStampSizeMm());
        p.setVatRates(new ArrayList<>(r.getVatRates()));
        p.setVatDefault(r.getVatDefault());
        p.setVatRegistered(r.getVatRegistered());
        p.setVatNotRegistrable(r.getVatNotRegistrable());
        p.setDefaultMarkupPct(r.getDefaultMarkupPct());
        p.setDefaultColumns(new ArrayList<>(r.getDefaultColumns()));
        p.setDefaultTerms(new ArrayList<>(r.getDefaultTerms()));
        p.setDefaultTermsStyle(r.getDefaultTermsStyle());
        p.setDefaultIntro(trim(r.getDefaultIntro()));
        p.setNextNumber(r.getNextNumber());
        p.setUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public CompanyProfile putImage(CompanyImageKind kind, byte[] bytes, boolean removeBackground) {
        byte[] png = images.process(bytes, removeBackground);
        CompanyProfile p = current();
        switch (kind) {
            case LOGO -> p.setLogoPng(png);
            case STAMP -> p.setStampPng(png);
            case SIGNATURE -> p.setSignaturePng(png);
        }
        p.setImagesUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public CompanyProfile deleteImage(CompanyImageKind kind) {
        CompanyProfile p = current();
        switch (kind) {
            case LOGO -> p.setLogoPng(null);
            case STAMP -> p.setStampPng(null);
            case SIGNATURE -> p.setSignaturePng(null);
        }
        p.setImagesUpdatedAt(OffsetDateTime.now());
        return repository.save(p);
    }

    @Transactional(readOnly = true)
    public byte[] image(CompanyImageKind kind) {
        CompanyProfile p = current();
        byte[] png = switch (kind) {
            case LOGO -> p.getLogoPng();
            case STAMP -> p.getStampPng();
            case SIGNATURE -> p.getSignaturePng();
        };
        if (png == null) throw new NotFoundException("Картинка не загружена");
        return png;
    }

    /**
     * Следующий «исх. №» рынка — атомарно (UPDATE … RETURNING), без гонки двух вкладок. Мимо JPA: сущность профиля
     * в этой транзакции не меняется, поэтому её устаревший nextNumber никто не запишет обратно.
     */
    @Transactional
    public int allocateNumber(Market market) {
        Integer number = jdbc.queryForObject(
                "UPDATE company_profile SET next_number = next_number + 1 WHERE market = ? RETURNING next_number - 1",
                Integer.class, market.name());
        if (number == null) throw new NotFoundException("Реквизиты рынка " + market + " не найдены");
        return number;
    }

    private static void requireRate(List<BigDecimal> rates, BigDecimal rate, String what) {
        if (!OfferSettingsValidator.containsRate(rates, rate)) {
            throw new BadRequestException("Ставки " + what + " «" + DocFormat.rate(rate) + "» нет в списке ставок");
        }
    }

    private static String trim(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
